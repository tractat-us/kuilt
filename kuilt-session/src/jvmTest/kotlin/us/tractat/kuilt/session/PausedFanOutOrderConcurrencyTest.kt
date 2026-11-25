@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the probe parks a consumer's event collector INLINE on the room's deciding thread, which needs Dispatchers.Unconfined (resumed in the emitter's frame) and real threads to run the readmission while that thread is parked; a test dispatcher serialises both.
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.ProvenResume
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.StageTracker
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.runConcurrencyStress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A host's `Paused` for a member must never reach a bystander after a later membership change to
 * that same member (#3625).
 *
 * `SeamRoom.refineWindow` and `SeamRoom.markPartitioned` each decide a pause under the room's lock.
 * Before #3625 each released the lock, announced the pause locally, and only then enqueued the
 * `Paused` fan-out. A `Goodbye` and a fresh `Hello` from the same member could both complete in that
 * gap, so a bystander received `Farewell`, `Welcome`, and then a late `Paused`. It then held a seated
 * member `Partitioned`, and no `Unpaused` follows.
 *
 * **How the gap is forced, without a production hook.** The local announcement in the gap goes
 * through the room's `events` flow. A consumer collecting it on [Dispatchers.Unconfined] is resumed
 * inline, on the thread that is deciding. This probe's collector parks that thread on a latch when
 * the pause is announced, drives the departure and readmission from the test on other threads, and
 * only then releases it. So the interleaving is a decision, not a sample: one round reproduces it.
 * The rig asserts it fired. The readmission must have completed while the deciding thread was parked.
 * With the fix, the fan-out is enqueued before the lock is released, and so before the parking point.
 *
 * The host's seam is a hand-written fake, as in [AdmissionRosterConcurrencyTest]. The bystander is a
 * recipient whose admit frames are recorded off `sendTo`, so the assertion reads the wire it would
 * have received. Every admission is the whole exchange since #3211: a Hello, the host's `Challenge`
 * (taken off `sendTo` too), and a `Proof` signed with the key the Hello named. The bystander, the
 * member and the latecomer each prove a different key, so no admission takes another's seat over. The bystander is not in `Seam.peers`, so no heartbeat runs to it. The member under
 * test is in `peers` while its detector must declare it unresponsive, and out of it before its
 * readmission, so a second detector does not pause it again.
 *
 * A third arm covers the `Unpaused` a Proof sends when it restores a partitioned seat. Its gap has no
 * consumer code in it to park on, so that arm pins where the enqueue sits instead; see its KDoc.
 *
 * A `*ConcurrencyTest`: excluded from the normal `jvmTest`, runs under `-Pconcurrency.stress.tests=true`.
 */
class PausedFanOutOrderConcurrencyTest {

    @Test
    fun aRefinedPauseDoesNotReachABystanderAfterTheMembersReadmission() = runConcurrencyStress(cap = 60.seconds) { stage ->
        withRig { rig ->
            rig.admitBystanderAndMember(stage)
            val detectedAt = rig.awaitDetection(stage)
            rig.parkOn { it is MembershipEvent.WindowOpened && it.peerId == MEMBER && it.expiresAt.toEpochMilliseconds() == REFINED }
            // Delivered from its own coroutine: the room's reconnect loop takes it and parks inside
            // refineWindow, so nothing here may wait on the loop finishing.
            rig.scope.launch { rig.policy.announce(MEMBER, detectedAt = detectedAt, expiresAt = REFINED) }
            rig.readmitWhileParked(stage)
            // A rendezvous emit returns only once the loop has taken it, i.e. after refineWindow
            // returned. An announcement for a non-member is a no-op there.
            stage.at("drain the reconnect loop")
            rig.policy.announce(PeerId("nobody"), detectedAt = 0, expiresAt = REFINED)
            rig.assertLastFrameNamingTheMemberIsItsWelcome(stage)
        }
    }

    @Test
    fun aFirstDetectionPauseDoesNotReachABystanderAfterTheMembersReadmission() = runConcurrencyStress(cap = 60.seconds) { stage ->
        withRig { rig ->
            rig.parkOn { it is MembershipEvent.Partitioned && it.peerId == MEMBER }
            rig.admitBystanderAndMember(stage)
            rig.readmitWhileParked(stage)
            // markPartitioned calls onPeerUnresponsive after its fan-out, so the detection signal
            // arriving means the Paused has been enqueued — on either side of the fix.
            rig.awaitDetection(stage)
            rig.assertLastFrameNamingTheMemberIsItsWelcome(stage)
        }
    }

    /**
     * The member proves its key again while its seat is held partitioned, and the hold policy
     * answers `WindowNotYetOpen`, so the Proof restores the seat. That restore ends the pause, and its
     * `Unpaused` must be enqueued inside the section that decided the restore, as `markRecovered`'s
     * is. Enqueued after it, a re-detection deciding in between would enqueue its `Paused` first, and
     * this `Unpaused` would then release, on every bystander, a member the host holds `Partitioned`.
     *
     * **What this arm pins, and what it cannot.** That gap runs from the end of `settleSeat`'s section
     * to `fanOutToOtherMembers`, on the same thread, with no consumer code in it, so nothing outside
     * the room can park a thread there and no test can force the inverted wire order without a
     * production hook. What a test can see is the section itself: the restore announces `Resumed`
     * under the lock, so parking that announcement holds the restoring thread INSIDE its section. The
     * bystander's lane is drained by its own writer, which takes no lock, so an `Unpaused` enqueued in
     * the section reaches the wire while the thread is parked, and one enqueued after the section
     * cannot. The arm therefore reds when the enqueue leaves the section, and also when it moves
     * after `Resumed` within it, which would still be correct; that second red is the price of having
     * no later point in the section to park on.
     *
     * The rig's heartbeat polls once a [SLOW_POLL] interval and holds a peer healthy only on a frame
     * from the last 100 ms, so the member's Hello and Proof, sent straight after its detection, are
     * more than a timeout old at the next poll: no heal by the detector ends the pause first. The
     * rig asserts that, since a heal would leave the restore with no pause to end.
     */
    @Test
    fun aRestoredSeatsUnpausedIsEnqueuedInTheSectionThatRestoredIt() = runConcurrencyStress(cap = 60.seconds) { stage ->
        withRig(SLOW_POLL) { rig ->
            rig.admitBystanderAndMember(stage)
            rig.awaitDetection(stage)
            rig.awaitPausedOnTheBystandersWire(stage)
            rig.parkOn { it is MembershipEvent.Resumed && it.peerId == MEMBER }
            // From its own coroutine: the Proof's coroutine parks inside the restore's section.
            rig.scope.launch { rig.exchange(MEMBER) }
            rig.assertUnpausedReachedTheBystanderWhileParked(stage)
        }
    }

    private suspend fun withRig(heartbeat: HeartbeatConfig = FAST_POLL, body: suspend (Rig) -> Unit) {
        val rig = Rig(RoomDevice.of(fakeKeyStore(0)), heartbeat)
        try {
            body(rig)
        } finally {
            rig.shutdown()
        }
    }

    private class Rig(device: RoomDevice, heartbeat: HeartbeatConfig) {
        private val executor = Executors.newFixedThreadPool(4)
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        // Bounded: at most a few admit frames are ever in flight here.
        private val inbound = Channel<Swatch>(capacity = 8)
        private val peers = MutableStateFlow(setOf(HOST, MEMBER))
        private val bystanderFrames = MutableStateFlow<List<AdmitMessage>>(emptyList())

        /** Each joiner's challenges, in the order the host sent them. */
        private val challenges = listOf(BYSTANDER, MEMBER, LATECOMER).associateWith { Channel<AdmitMessage.Challenge>(Channel.UNLIMITED) }
        val policy = HoldPolicy()
        private val seam = object : Seam {
            override val selfId = HOST
            override val peers = this@Rig.peers
            override val state = MutableStateFlow<SeamState>(SeamState.Woven)
            override val incoming = inbound.receiveAsFlow()
            override suspend fun broadcast(payload: ByteArray) = Unit
            override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                val message = AdmitMessage.decode(payload) ?: return
                if (message is AdmitMessage.Challenge) {
                    challenges[peer]?.send(message)
                    return
                }
                if (peer != BYSTANDER) return
                bystanderFrames.update { it + message }
            }
            override suspend fun close(reason: CloseReason) = Unit
        }
        val room = SeamRoom(
            seam = seam,
            role = SessionRole.Host,
            memberName = "host",
            scope = scope,
            clock = { Clock.System.now() },
            device = device,
            heartbeatConfig = heartbeat,
            roomId = RoomId("room-3625"),
            reconnectControllerFactory = { _, _, _ -> policy },
            admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()),
        )
        private val release = CountDownLatch(1)
        private val parked = CompletableDeferred<Unit>()
        @Volatile
        private var releasedInTime = true

        /** The member's Welcomes on the bystander's wire when the parked thread went on; -1 if it never parked. */
        @Volatile
        private var welcomesWhenReleased = -1

        /** Exchanges sent again because the first drew no admission in [EXCHANGE_GRACE]; see [admit]. */
        private var retries = 0

        /** Park the thread that announces the first event matching [match], until [readmitWhileParked] is done. */
        fun parkOn(match: (MembershipEvent) -> Boolean) {
            scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                room.events.first(match)
                parked.complete(Unit)
                // Blocking on purpose: this IS the deciding thread, resumed inline by tryEmit.
                releasedInTime = release.await(30, TimeUnit.SECONDS)
                // Read on the deciding thread before it goes on: the witness that the readmission
                // finished inside the gap, not merely that a latch was counted down.
                welcomesWhenReleased = bystanderFrames.value.count { it.isWelcomeOf(MEMBER) }
            }
        }

        /** Every membership event the host emitted, in order. */
        private val events = MutableStateFlow<List<MembershipEvent>>(emptyList())

        suspend fun admitBystanderAndMember(stage: StageTracker) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { room.events.collect { event -> events.update { it + event } } }
            room.start()
            stage.at("bystander admitted", ::snapshot)
            admit(BYSTANDER) { room.roster.value.any { it.id == BYSTANDER } }
            stage.at("member's Welcome reaches the bystander", ::snapshot)
            admit(MEMBER) { bystanderFrames.value.any { it.isWelcomeOf(MEMBER) } }
            stage.at("host's reconnect loop subscribed", ::snapshot)
            policy.subscribed.first { it == 1 }
        }

        /**
         * Admit [peer] the way a current joiner is admitted: a Hello, the host's challenge, and a
         * Proof signed with [peer]'s key. Returns once [admitted] holds, read off the bystander's wire
         * or the roster. An exchange that draws nothing in [EXCHANGE_GRACE] is sent again, and
         * counted: the room forgets the open challenge of a sender that leaves the route set, and a
         * forgotten challenge seats nobody.
         */
        suspend fun admit(peer: PeerId, admitted: () -> Boolean) {
            while (true) {
                exchange(peer)
                val done = withTimeoutOrNull(EXCHANGE_GRACE) { merge(bystanderFrames, room.roster).first { admitted() } }
                if (done != null) return
                retries++
            }
        }

        /** One admission exchange for [peer]: its Hello, the host's challenge, and the Proof answering it. */
        suspend fun exchange(peer: PeerId) {
            val (device, key) = credentialsOf(peer)
            val hello = currentHello(peer.value, sessionId = peer.value, key = key)
            inbound.send(Swatch(AdmitMessage.encode(hello), sender = peer))
            val challenge = challenges.getValue(peer).receive()
            inbound.send(Swatch(AdmitMessage.encode(handProof(challenge, HOST, device)), sender = peer))
        }

        suspend fun awaitPausedOnTheBystandersWire(stage: StageTracker) {
            stage.at("member's Paused reaches the bystander", ::snapshot)
            bystanderFrames.first { frames -> frames.any { it is AdmitMessage.Paused && it.peerId == MEMBER.value } }
        }

        /** The restore arm's verdict: see [aRestoredSeatsUnpausedIsEnqueuedInTheSectionThatRestoredIt]. */
        suspend fun assertUnpausedReachedTheBystanderWhileParked(stage: StageTracker) {
            stage.at("restoring thread parked in its section", ::snapshot)
            parked.await()
            stage.at("member's Unpaused reaches the bystander while parked", ::snapshot)
            val whileParked = withTimeoutOrNull(PARKED_GRACE) {
                bystanderFrames.first { frames -> frames.any { it is AdmitMessage.Unpaused && it.peerId == MEMBER.value } }
            } != null
            release.countDown()
            stage.at("latecomer's Welcome reaches the bystander (lane drained)", ::snapshot)
            admit(LATECOMER) { bystanderFrames.value.any { it.isWelcomeOf(LATECOMER) } }
            val naming = bystanderFrames.value.filter { it.names(MEMBER) }
            val edges = events.value.filter { it.peerId() == MEMBER }.map { it::class.simpleName }
            println("paused-fanout-order restore: whileParked=$whileParked asked=${policy.asked.get()} edges=$edges frames naming member=$naming")
            assertAll(
                { assertTrue(releasedInTime, "rig: the parked thread must have been released by the probe, not by its timeout") },
                { assertTrue(policy.asked.get() >= 1, "rig: the Proof must have asked the hold policy about the partitioned seat") },
                {
                    assertTrue(
                        "Recovered" !in edges && "Resumed" in edges,
                        "rig: the Proof's restore must be what ended the pause, not a heal by the detector — edges: $edges",
                    )
                },
                {
                    assertIs<Liveness.Connected>(
                        room.roster.value.single { it.id == MEMBER }.liveness,
                        "rig: the host must hold the restored member Connected",
                    )
                },
                {
                    assertEquals(
                        1,
                        naming.count { it is AdmitMessage.Unpaused },
                        "rig: the restore must send the bystander exactly one Unpaused — frames naming the member: $naming",
                    )
                },
                {
                    assertTrue(
                        whileParked,
                        "#3625: the restore's Unpaused must be enqueued inside the section that restored the seat; " +
                            "enqueued after it, a re-detection's Paused can reach the bystander first. Frames naming the member: $naming",
                    )
                },
            )
        }

        suspend fun awaitDetection(stage: StageTracker): Long {
            stage.at("member detected unresponsive", ::snapshot)
            return policy.detections.receive()
        }

        /** Departure and readmission of [MEMBER], each observed on the bystander's wire, inside the gap. */
        suspend fun readmitWhileParked(stage: StageTracker) {
            stage.at("deciding thread parked", ::snapshot)
            parked.await()
            // Out of the route set before it returns, so the readmission starts no second detector to
            // pause it again. Dropped before the Goodbye, so the room's watcher of the route set has
            // long seen the drop when the readmission's Hello opens a challenge it would forget.
            peers.value = setOf(HOST)
            inbound.send(Swatch(AdmitMessage.encode(AdmitMessage.Goodbye), sender = MEMBER))
            stage.at("member's Farewell reaches the bystander", ::snapshot)
            bystanderFrames.first { frames -> frames.any { it is AdmitMessage.Farewell && it.peerId == MEMBER.value } }
            val welcomesBefore = bystanderFrames.value.count { it.isWelcomeOf(MEMBER) }
            stage.at("member's second Welcome reaches the bystander", ::snapshot)
            admit(MEMBER) { bystanderFrames.value.count { it.isWelcomeOf(MEMBER) } > welcomesBefore }
            release.countDown()
        }

        suspend fun assertLastFrameNamingTheMemberIsItsWelcome(stage: StageTracker) {
            // A later admission on the same lane: once its Welcome is recorded, every frame enqueued
            // for the bystander before it has been sent.
            stage.at("latecomer's Welcome reaches the bystander (lane drained)", ::snapshot)
            admit(LATECOMER) { bystanderFrames.value.any { it.isWelcomeOf(LATECOMER) } }
            val frames = bystanderFrames.value
            val naming = frames.filter { it.names(MEMBER) }
            println("paused-fanout-order: welcomesWhenReleased=$welcomesWhenReleased retries=$retries frames naming member=$naming")
            assertAll(
                { assertTrue(releasedInTime, "rig: the parked thread must have been released by the probe, not by its timeout") },
                {
                    assertEquals(
                        2,
                        welcomesWhenReleased,
                        "rig: both of the member's Welcomes must be on the bystander's wire while the deciding thread is parked",
                    )
                },
                {
                    assertIs<Liveness.Connected>(
                        room.roster.value.single { it.id == MEMBER }.liveness,
                        "rig: the host must hold the readmitted member Connected",
                    )
                },
                {
                    assertTrue(
                        naming.count { it is AdmitMessage.Paused } >= 1,
                        "rig: the bystander must have been sent a Paused for the member at all — " +
                            "frames naming it: $naming",
                    )
                },
                {
                    assertTrue(
                        naming.lastOrNull()?.isWelcomeOf(MEMBER) == true,
                        "#3625: the last frame naming the member must be its readmission's Welcome; " +
                            "a Paused after it leaves the bystander holding a seated member Partitioned. " +
                            "Frames naming it, in wire order: $naming",
                    )
                },
            )
        }

        private fun snapshot(): String =
            "roster=${room.roster.value.map { "${it.id.value}:${it.liveness::class.simpleName}" }} " +
                "bystanderFrames=${bystanderFrames.value}"

        suspend fun shutdown() {
            release.countDown()
            room.leave()
            scope.cancel()
            executor.shutdownNow()
        }
    }

    /**
     * A hold policy that reports each drop and announces windows only when the probe says so.
     * `events` is a rendezvous flow, so [announce] returns only once the room's loop has taken it.
     */
    private class HoldPolicy : JoinerReconnectController {
        private val _events = MutableSharedFlow<JoinerReconnectEvent>()
        override val events: SharedFlow<JoinerReconnectEvent> = _events.asSharedFlow()
        val subscribed = _events.subscriptionCount
        val detections = Channel<Long>(Channel.UNLIMITED)

        override fun onPeerUnresponsive(peerId: PeerId, at: Long) {
            if (peerId == MEMBER) detections.trySend(at)
        }

        suspend fun announce(peerId: PeerId, detectedAt: Long, expiresAt: Long) {
            _events.emit(JoinerReconnectEvent.WindowOpened(peerId, expiresAt = expiresAt, detectedAt = detectedAt))
        }

        /** How many times a Proof asked about a partitioned seat. */
        val asked = AtomicInteger(0)

        override suspend fun tryResume(resume: ProvenResume, at: Long): ResumeResult.HostVerdict {
            asked.incrementAndGet()
            return ResumeResult.WindowNotYetOpen
        }

        override fun onPeerRecovered(peerId: PeerId, at: Long) = Unit

        override fun expire(peerId: PeerId, at: Long) = Unit
    }

    private companion object {
        val HOST = PeerId("host")
        val MEMBER = PeerId("member")
        val BYSTANDER = PeerId("bystander")
        val LATECOMER = PeerId("latecomer")

        /** A deadline no HeartbeatConfig estimate can equal, so the refinement is Applied. */
        const val REFINED = 4_000_000_000_000L

        /** The first two arms' heartbeat: a member that stops answering is paused within a few polls. */
        val FAST_POLL = HeartbeatConfig(
            interval = 20.milliseconds,
            timeout = 100.milliseconds,
            // Far past the probe: no PeerLost or expiry may enter it.
            reconnectWindow = 600.seconds,
        )

        /**
         * The restore arm's heartbeat. A detector holds a partitioned peer healthy again only on a
         * frame from the last [HeartbeatConfig.timeout] at one of its polls, and it polls once an
         * [HeartbeatConfig.interval]: frames sent straight after a detection are 900 ms stale at the
         * next poll, so they heal nothing.
         */
        val SLOW_POLL = FAST_POLL.copy(interval = 1.seconds)

        /** How long the restore arm waits for an `Unpaused` while the restoring thread is parked. */
        val PARKED_GRACE = 3.seconds

        fun MembershipEvent.peerId(): PeerId? = when (this) {
            is MembershipEvent.Joined -> member.id
            is MembershipEvent.Resumed -> peerId
            is MembershipEvent.Recovered -> peerId
            is MembershipEvent.Partitioned -> peerId
            is MembershipEvent.Left -> peerId
            else -> null
        }

        /** How long one admission exchange may take before the rig sends it again. */
        val EXCHANGE_GRACE = 3.seconds

        /** The latecomer's key: a third one, so its admission takes nobody's seat over. */
        val LATECOMER_KEY: DidKey = fakeDidKey(102)
        val LATECOMER_DEVICE: DeviceKey = fakeDeviceKey(102)

        fun credentialsOf(peer: PeerId): Pair<DeviceKey, DidKey> = when (peer) {
            MEMBER -> TEST_DEVICE_A to TEST_KEY_A
            BYSTANDER -> TEST_DEVICE_B to TEST_KEY_B
            LATECOMER -> LATECOMER_DEVICE to LATECOMER_KEY
            else -> error("no key for ${peer.value}")
        }

        fun AdmitMessage.isWelcomeOf(peer: PeerId) = this is AdmitMessage.Welcome && assignedPeerId == peer.value

        fun AdmitMessage.names(peer: PeerId) = when (this) {
            is AdmitMessage.Welcome -> assignedPeerId == peer.value
            is AdmitMessage.Paused -> peerId == peer.value
            is AdmitMessage.Unpaused -> peerId == peer.value
            is AdmitMessage.Farewell -> peerId == peer.value
            else -> false
        }
    }
}
