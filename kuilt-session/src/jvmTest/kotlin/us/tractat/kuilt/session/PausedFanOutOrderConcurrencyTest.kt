package us.tractat.kuilt.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
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
 * have received. The bystander is not in `Seam.peers`, so no heartbeat runs to it. The member under
 * test is in `peers` while its detector must declare it unresponsive, and out of it before its
 * readmission, so a second detector does not pause it again.
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

    private suspend fun withRig(body: suspend (Rig) -> Unit) {
        val rig = Rig()
        try {
            body(rig)
        } finally {
            rig.shutdown()
        }
    }

    private class Rig {
        private val executor = Executors.newFixedThreadPool(4)
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        // Bounded: at most a few admit frames are ever in flight here.
        private val inbound = Channel<Swatch>(capacity = 8)
        private val peers = MutableStateFlow(setOf(HOST, MEMBER))
        private val bystanderFrames = MutableStateFlow<List<AdmitMessage>>(emptyList())
        val policy = HoldPolicy()
        private val seam = object : Seam {
            override val selfId = HOST
            override val peers = this@Rig.peers
            override val state = MutableStateFlow<SeamState>(SeamState.Woven)
            override val incoming = inbound.receiveAsFlow()
            override suspend fun broadcast(payload: ByteArray) = Unit
            override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                if (peer != BYSTANDER) return
                val message = AdmitMessage.decode(payload) ?: return
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
            heartbeatConfig = HeartbeatConfig(
                interval = 20.milliseconds,
                timeout = 100.milliseconds,
                // Far past the probe: no PeerLost or expiry may enter it.
                reconnectWindow = 600.seconds,
            ),
            roomId = RoomId("room-3625"),
            reconnectControllerFactory = { _, _, _ -> policy },
        )
        private val release = CountDownLatch(1)
        private val parked = CompletableDeferred<Unit>()
        private var releasedInTime = true

        /** Park the thread that announces the first event matching [match], until [readmitWhileParked] is done. */
        fun parkOn(match: (MembershipEvent) -> Boolean) {
            scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                room.events.first(match)
                parked.complete(Unit)
                // Blocking on purpose: this IS the deciding thread, resumed inline by tryEmit.
                releasedInTime = release.await(30, TimeUnit.SECONDS)
            }
        }

        suspend fun admitBystanderAndMember(stage: StageTracker) {
            room.start()
            stage.at("bystander admitted", ::snapshot)
            inbound.send(hello(BYSTANDER))
            room.roster.first { members -> members.any { it.id == BYSTANDER } }
            stage.at("member's Welcome reaches the bystander", ::snapshot)
            inbound.send(hello(MEMBER))
            bystanderFrames.first { frames -> frames.any { it.isWelcomeOf(MEMBER) } }
            stage.at("host's reconnect loop subscribed", ::snapshot)
            policy.subscribed.first { it == 1 }
        }

        suspend fun awaitDetection(stage: StageTracker): Long {
            stage.at("member detected unresponsive", ::snapshot)
            return policy.detections.receive()
        }

        /** Departure and readmission of [MEMBER], each observed on the bystander's wire, inside the gap. */
        suspend fun readmitWhileParked(stage: StageTracker) {
            stage.at("deciding thread parked", ::snapshot)
            parked.await()
            inbound.send(Swatch(AdmitMessage.encode(AdmitMessage.Goodbye), sender = MEMBER))
            stage.at("member's Farewell reaches the bystander", ::snapshot)
            bystanderFrames.first { frames -> frames.any { it is AdmitMessage.Farewell && it.peerId == MEMBER.value } }
            // Out of the route set before it returns, so admitPeer starts no second detector.
            peers.value = setOf(HOST)
            val welcomesBefore = bystanderFrames.value.count { it.isWelcomeOf(MEMBER) }
            inbound.send(hello(MEMBER))
            stage.at("member's second Welcome reaches the bystander", ::snapshot)
            bystanderFrames.first { frames -> frames.count { it.isWelcomeOf(MEMBER) } > welcomesBefore }
            release.countDown()
        }

        suspend fun assertLastFrameNamingTheMemberIsItsWelcome(stage: StageTracker) {
            // A later admission on the same lane: once its Welcome is recorded, every frame enqueued
            // for the bystander before it has been sent.
            inbound.send(hello(LATECOMER))
            stage.at("latecomer's Welcome reaches the bystander (lane drained)", ::snapshot)
            bystanderFrames.first { frames -> frames.any { it.isWelcomeOf(LATECOMER) } }
            val frames = bystanderFrames.value
            val naming = frames.filter { it.names(MEMBER) }
            println("paused-fanout-order: frames naming member=$naming")
            assertAll(
                { assertTrue(releasedInTime, "rig: the parked thread must have been released by the probe, not by its timeout") },
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

        override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult.HostVerdict =
            ResumeResult.WindowNotYetOpen

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

        fun hello(peer: PeerId) = Swatch(
            AdmitMessage.encode(
                AdmitMessage.Hello(displayName = peer.value, sessionId = peer.value, protocolVersion = ProtocolVersion.CURRENT),
            ),
            sender = peer,
        )

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
