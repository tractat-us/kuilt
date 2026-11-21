@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.session.admit.AdmitMessage
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.random.Random
import kotlin.time.Instant

/**
 * The races a proven resume (#3212) runs on real threads, where a single-threaded test cannot put
 * them: both sides decide and act without suspending, so on one thread each side's sections run
 * back to back and the interleavings below never happen.
 *
 * Bob holds a seat and carol is a bystander. Each round partitions bob by dropping him from the
 * fabric's peers, waits for the controller's window to be seen, then has bob prove his key again
 * while a second thread does the thing the arm races against. A round quiesces before it is read:
 * the joiner's whole burst sent, the reconnect loop past a sentinel, and carol's lane drained past a
 * sentinel `Farewell`. So every count is of a settled state, not of a read across a transition.
 *
 * The clock is a counter, so every partition episode has its own identity; the heartbeat timeout is
 * far beyond anything the counter reaches, so only a fabric drop partitions and only a frame heals.
 *
 * A `*ConcurrencyTest`, so it is excluded from the normal `jvmTest` and runs only under
 * `-Pconcurrency.stress.tests=true` in the non-blocking `concurrency-probes` CI job (see
 * `kuilt-session/build.gradle.kts`). It samples interleavings rather than forcing one, so each arm
 * counts both orders and requires each to have happened: a run that only ever saw one order has
 * tested nothing about the race.
 */
class ProvenRestoreConcurrencyTest {
    /**
     * The window expires while the host asks about bob's seat, which is how the default controller
     * meets a Proof: the ask is refused because the window has just closed. The expiry evicts on the
     * reconnect loop's thread while the Proof decides on another.
     *
     * Either order must leave a readmitted bob seated and watched, and carol must hear about bob's
     * departure before his arrival. A `Farewell` that lands after the `Welcome` makes carol drop the
     * new bob for good, with nothing to re-admit him (#2556's split).
     */
    @Test
    fun anExpiryRacingAProofLeavesTheReadmittedMemberSeatedEverywhere() =
        runConcurrencyStress(cap = ARM_CAP) { stage ->
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                // A long interval: the old detector never polls inside a round, so nothing heals bob.
                val rig = Rig(dispatcher, interval = 1.seconds, RoomDevice.of(fakeKeyStore(0)))
                var completed = 0
                var expiryFirst = 0
                var proofFirst = 0
                var expiryBetweenProofSections = 0
                var notSeated = 0
                var noDetector = 0
                var lastEventNotJoined = 0
                var carolLastNotWelcome = 0
                val failures = mutableListOf<String>()
                try {
                    rig.start(stage)
                    for (round in 0 until EXPIRY_ROUNDS) {
                        val episode = rig.partitionBob(stage, round)
                        val recoveredBefore = rig.controller.recovered.get()
                        val spinNanos = jitter.nextLong(0, MAX_SPIN_NANOS)
                        rig.controller.onAsk = { _, at ->
                            val expiry = JoinerReconnectEvent.WindowExpired(bob, at, episode)
                            when (round % 3) {
                                // Returns once the reconnect loop has taken the expiry, so the loop's
                                // thread is already evicting when this one goes on to decide: the
                                // expiry's section tends to run first.
                                0 -> rig.controller.flow.emit(expiry)
                                // Emitted from another coroutine, so this one decides at once and the
                                // loop has to be dispatched first: the Proof's sections tend to run
                                // first. With a spin, anything in between.
                                else -> {
                                    rig.pendingExpiry = rig.scope.launch { rig.controller.flow.emit(expiry) }
                                    if (round % 3 == 2) spin(spinNanos)
                                }
                            }
                            ResumeResult.WindowClosed
                        }
                        val introsBefore = rig.hostIntrosTo(bob)
                        rig.prove(bob, TEST_DEVICE_A, TEST_KEY_A)
                        stage.at("round $round: awaiting the readmission") { rig.snapshot() }
                        rig.events.first { events -> events.any { it is MembershipEvent.Joined && it.member.id == bob } }
                        rig.awaitIntros(bob, introsBefore + 1)
                        rig.quiesce(stage, round)

                        val released = rig.controller.recovered.get() > recoveredBefore
                        val toCarol = rig.framesToCarolNaming(bob)
                        val farewells = toCarol.count { it is AdmitMessage.Farewell }
                        when {
                            !released -> expiryFirst++
                            farewells >= 2 -> expiryBetweenProofSections++
                            else -> proofFirst++
                        }
                        val edges = rig.edgesOf(bob)
                        val seated = rig.room.isAdmitted(bob)
                        val watched = rig.room.hasDetector(bob)
                        val lastIsJoined = edges.lastOrNull() == "Joined"
                        val carolLastIsWelcome = toCarol.lastOrNull() is AdmitMessage.Welcome
                        if (!seated) notSeated++
                        if (!watched) noDetector++
                        if (!lastIsJoined) lastEventNotJoined++
                        if (!carolLastIsWelcome) carolLastNotWelcome++
                        if (!(seated && watched && lastIsJoined && carolLastIsWelcome) && failures.size < 5) {
                            failures += "round $round released=$released edges=$edges toCarol=${toCarol.map { it::class.simpleName }} " +
                                "seated=$seated watched=$watched"
                        }
                        completed++
                        // Without a seated bob the next round has nothing to partition.
                        if (!seated) break
                    }
                    println(
                        "proven-restore expiry: completed=$completed expiryFirst=$expiryFirst proofFirst=$proofFirst " +
                            "expiryBetweenProofSections=$expiryBetweenProofSections notSeated=$notSeated " +
                            "noDetector=$noDetector lastEventNotJoined=$lastEventNotJoined " +
                            "carolLastNotWelcome=$carolLastNotWelcome failures=$failures",
                    )
                    assertAll(
                        { assertEquals(EXPIRY_ROUNDS, completed, "every round ran") },
                        { assertEquals(0, notSeated, "the readmitted bob is seated: $failures") },
                        { assertEquals(0, noDetector, "the readmitted bob is watched by a detector: $failures") },
                        { assertEquals(0, lastEventNotJoined, "bob's last membership edge is Joined: $failures") },
                        { assertEquals(0, carolLastNotWelcome, "carol's last frame naming bob is his Welcome: $failures") },
                        { assertTrue(expiryFirst > 0, "the expiry's section ran first in some round") },
                        { assertTrue(proofFirst + expiryBetweenProofSections > 0, "the Proof's release ran first in some round") },
                    )
                } finally {
                    rig.close()
                }
            }
        }

    /**
     * Bob's old link heals while the host is asking about his seat, and the hold policy refuses. A
     * pong from bob lands on another thread, so the old detector's recovery races the Proof's release.
     *
     * Either the heal wins, and the seat is restored (`Recovered`, then `Resumed`), or the release
     * wins, and bob leaves and is admitted afresh (`Left`, then `Joined`). Never both: a heal that is
     * then released evicts a member who is here, and carol, told `Unpaused` and then `Farewell`,
     * drops him.
     */
    @Test
    fun aHealRacingAReleaseEitherRestoresOrReadmits() =
        runConcurrencyStress(cap = ARM_CAP) { stage ->
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                // A short interval: the old detector polls often, so its recovery lands among the
                // Proof's sections.
                val rig = Rig(dispatcher, interval = 2.milliseconds, RoomDevice.of(fakeKeyStore(0)))
                var completed = 0
                var healFirst = 0
                var releaseFirst = 0
                var disallowedEdges = 0
                var unpausedThenFarewell = 0
                var notSeated = 0
                val failures = mutableListOf<String>()
                try {
                    rig.start(stage)
                    rig.controller.onAsk = { _, _ -> ResumeResult.WindowClosed }
                    for (round in 0 until HEAL_ROUNDS) {
                        rig.partitionBob(stage, round)
                        val recoveredBefore = rig.controller.recovered.get()
                        val introsBefore = rig.hostIntrosTo(bob)
                        rig.prove(bob, TEST_DEVICE_A, TEST_KEY_A) {
                            rig.scope.launch { rig.inbound.send(Swatch(PONG, sender = bob)) }
                            // The old detector polls every interval; a Proof sent at a random phase
                            // of it lands before the heal, after it, or among the heal's sections.
                            delay(jitter.nextLong(0, HEAL_PROOF_DELAY_MS))
                        }
                        stage.at("round $round: awaiting the restore or readmission") { rig.snapshot() }
                        val edge = rig.events.first { events ->
                            events.any { (it is MembershipEvent.Resumed && it.peerId == bob) || (it is MembershipEvent.Joined && it.member.id == bob) }
                        }.first { (it is MembershipEvent.Resumed && it.peerId == bob) || (it is MembershipEvent.Joined && it.member.id == bob) }
                        rig.awaitIntros(bob, introsBefore + 1)
                        // A restore needs the heal, and a release tells the controller; either way
                        // one onPeerRecovered closes the round's window, and it is the last thing
                        // the heal (or the release) does before the room is settled.
                        stage.at("round $round: awaiting the window's close") { rig.snapshot() }
                        if (edge is MembershipEvent.Resumed) {
                            rig.events.first { events -> events.any { it is MembershipEvent.Recovered && it.peerId == bob } }
                        }
                        rig.controller.recoveredCount.first { it > recoveredBefore }
                        rig.quiesce(stage, round)

                        val edges = rig.edgesOf(bob)
                        val toCarol = rig.framesToCarolNaming(bob).map { it::class.simpleName }
                        when (edges) {
                            listOf("Recovered", "Resumed") -> healFirst++
                            listOf("Left", "Joined") -> releaseFirst++
                            else -> disallowedEdges++
                        }
                        val unpaused = toCarol.indexOf("Unpaused")
                        if (unpaused >= 0 && toCarol.drop(unpaused + 1).contains("Farewell")) unpausedThenFarewell++
                        val seated = rig.room.isAdmitted(bob)
                        if (!seated) notSeated++
                        val good = edges == listOf("Recovered", "Resumed") || edges == listOf("Left", "Joined")
                        if (!(good && seated) && failures.size < 5) {
                            failures += "round $round edges=$edges toCarol=$toCarol seated=$seated"
                        }
                        completed++
                        if (!seated) break
                    }
                    println(
                        "proven-restore heal: completed=$completed healFirst=$healFirst releaseFirst=$releaseFirst " +
                            "disallowedEdges=$disallowedEdges unpausedThenFarewell=$unpausedThenFarewell " +
                            "notSeated=$notSeated failures=$failures",
                    )
                    assertAll(
                        { assertEquals(HEAL_ROUNDS, completed, "every round ran") },
                        { assertEquals(0, disallowedEdges, "each round restores or readmits, never both: $failures") },
                        { assertEquals(0, unpausedThenFarewell, "carol is never told Unpaused, then Farewell: $failures") },
                        { assertEquals(0, notSeated, "bob is seated after every round: $failures") },
                        { assertTrue(healFirst > 0, "the heal ran first in some round") },
                        { assertTrue(releaseFirst > 0, "the release ran first in some round") },
                    )
                } finally {
                    rig.close()
                }
            }
        }

    /**
     * Bob's old detector gives up on him — its `PeerLost` — while a Proof releases his seat and seats
     * him afresh. The loss is about the membership that is over, so it must not evict the new one.
     *
     * The detector's window is one heartbeat interval, so its loss lands a few milliseconds after the
     * drop. Bob's Hello and Proof are sent at a random moment around it: before the detector looks
     * (it heals), between its look and its loss, or after the loss. A loss already taken off the detector's channel when the
     * release stops that detector is handled anyway, after the readmission: that is the interleaving
     * which, with the detector check gone from `handlePeerLost`, evicts the new bob.
     */
    @Test
    fun aStaleDetectorsLossRacingAReleaseLeavesTheReadmittedMemberSeated() =
        runConcurrencyStress(cap = ARM_CAP) { stage ->
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                val rig = Rig(dispatcher, interval = LOSS_INTERVAL, RoomDevice.of(fakeKeyStore(0)), detectorWindow = LOSS_INTERVAL)
                var completed = 0
                var healFirst = 0
                var releaseFirst = 0
                var lossFirst = 0
                var disallowedEdges = 0
                var notSeated = 0
                var noDetector = 0
                var carolLastNotWelcome = 0
                val failures = mutableListOf<String>()
                try {
                    rig.start(stage)
                    rig.controller.onAsk = { _, _ -> ResumeResult.WindowClosed }
                    for (round in 0 until LOSS_ROUNDS) {
                        rig.partitionBob(stage, round)
                        val recoveredBefore = rig.controller.recovered.get()
                        val introsBefore = rig.hostIntrosTo(bob)
                        rig.slowPingNanos = jitter.nextLong(0, LOSS_SLOW_PING_NANOS)
                        val wait = jitter.nextLong(0, LOSS_PROOF_DELAY_NANOS)
                        delay(wait / 1_000_000)
                        spin(wait % 1_000_000)
                        // Not before the drop: leaving the fabric forgets a sender's open challenge.
                        val challenge = rig.hello(bob)
                        rig.inbound.send(Swatch(AdmitMessage.encode(handProof(challenge, host, TEST_DEVICE_A)), sender = bob))
                        stage.at("round $round: awaiting the restore or readmission") { rig.snapshot() }
                        val edge = rig.events.first { events -> events.any { it.isEdgeOf(bob) } }.first { it.isEdgeOf(bob) }
                        rig.awaitIntros(bob, introsBefore + 1)
                        rig.slowPingNanos = 0
                        stage.at("round $round: awaiting the heal's window close") { rig.snapshot() }
                        // A restore needs the heal, whose last step closes the window; a release
                        // closes it before the Proof decides again, so before the Joined.
                        if (edge is MembershipEvent.Resumed) rig.controller.recoveredCount.first { it > recoveredBefore }
                        rig.quiesce(stage, round)
                        // A loss the old detector already handed over is handled on its own thread;
                        // give it two of its intervals to land before the round is read.
                        delay(LOSS_INTERVAL * 2)

                        val released = rig.controller.recovered.get() > recoveredBefore
                        val edges = rig.edgesOf(bob)
                        val toCarol = rig.framesToCarolNaming(bob)
                        when {
                            edges == listOf("Recovered", "Resumed") -> healFirst++
                            edges == listOf("Left", "Joined") && released -> releaseFirst++
                            edges == listOf("Left", "Joined") -> lossFirst++
                            else -> disallowedEdges++
                        }
                        val seated = rig.room.isAdmitted(bob)
                        val watched = rig.room.hasDetector(bob)
                        val carolLastIsWelcome = edge !is MembershipEvent.Joined || toCarol.lastOrNull() is AdmitMessage.Welcome
                        if (!seated) notSeated++
                        if (!watched) noDetector++
                        if (!carolLastIsWelcome) carolLastNotWelcome++
                        val good = edges == listOf("Recovered", "Resumed") || edges == listOf("Left", "Joined")
                        if (!(good && seated && watched && carolLastIsWelcome) && failures.size < 5) {
                            failures += "round $round released=$released edges=$edges " +
                                "toCarol=${toCarol.map { it::class.simpleName }} seated=$seated watched=$watched"
                        }
                        completed++
                        if (!seated) break
                    }
                    println(
                        "proven-restore loss: completed=$completed healFirst=$healFirst releaseFirst=$releaseFirst " +
                            "lossFirst=$lossFirst disallowedEdges=$disallowedEdges notSeated=$notSeated " +
                            "noDetector=$noDetector carolLastNotWelcome=$carolLastNotWelcome failures=$failures",
                    )
                    assertAll(
                        { assertEquals(LOSS_ROUNDS, completed, "every round ran") },
                        { assertEquals(0, notSeated, "the readmitted bob is seated: $failures") },
                        { assertEquals(0, noDetector, "and watched by a detector: $failures") },
                        { assertEquals(0, disallowedEdges, "each round restores or readmits, once: $failures") },
                        { assertEquals(0, carolLastNotWelcome, "carol's last frame naming a readmitted bob is his Welcome: $failures") },
                        { assertTrue(releaseFirst > 0, "the release ran first in some round") },
                        { assertTrue(lossFirst > 0, "the loss ran first in some round") },
                    )
                } finally {
                    rig.close()
                }
            }
        }

    /**
     * The window controller this probe drives the room with. A partition is announced at once, with
     * a deadline that differs from the room's own estimate, so the room records the episode's
     * `WindowOpened` and a refusal is acted on. [flow] has no buffer: an emit returns only once the
     * reconnect loop has taken the event, and so only once it has finished with the event before.
     */
    private class Controller(private val windowMs: Long) : JoinerReconnectController {
        val flow = MutableSharedFlow<JoinerReconnectEvent>()
        override val events: SharedFlow<JoinerReconnectEvent> = flow.asSharedFlow()
        lateinit var scope: CoroutineScope

        /** The `at` of the latest partition of bob the room reported: the episode's identity. */
        val episode = MutableStateFlow(-1L)
        val recovered = AtomicInteger(0)
        val recoveredCount = MutableStateFlow(0)

        @Volatile
        var onAsk: suspend (ProvenResume, Long) -> ResumeResult.HostVerdict = { _, _ -> ResumeResult.WindowClosed }

        override fun onPeerUnresponsive(peerId: PeerId, at: Long) {
            if (peerId != bob) return
            episode.value = at
            scope.launch { flow.emit(JoinerReconnectEvent.WindowOpened(peerId, deadlineFor(at), at)) }
        }

        fun deadlineFor(at: Long): Long = at + windowMs + 1

        override suspend fun tryResume(resume: ProvenResume, at: Long): ResumeResult.HostVerdict = onAsk(resume, at)

        override fun onPeerRecovered(peerId: PeerId, at: Long) {
            if (peerId != bob) return
            recoveredCount.value = recovered.incrementAndGet()
        }

        override fun expire(peerId: PeerId, at: Long) = Unit
    }

    private class Rig(
        dispatcher: CoroutineDispatcher,
        interval: Duration,
        device: RoomDevice,
        /** How long a detector waits on a partitioned peer before its `PeerLost`. */
        detectorWindow: Duration = 100_000.hours,
    ) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val peers = MutableStateFlow(setOf(host, bob, carol))
        val inbound = Channel<Swatch>(Channel.UNLIMITED)
        private val challenges = mapOf(
            bob to Channel<AdmitMessage.Challenge>(Channel.UNLIMITED),
            carol to Channel<AdmitMessage.Challenge>(Channel.UNLIMITED),
        )

        /** Every admit frame the host sent, in send order, since the round began. */
        val sent = MutableStateFlow<List<Pair<PeerId, AdmitMessage>>>(emptyList())

        /** Every membership event the host emitted since the round began. */
        val events = MutableStateFlow<List<MembershipEvent>>(emptyList())

        private val config = HeartbeatConfig(interval = interval, timeout = 100_000.hours, reconnectWindow = detectorWindow)
        val controller = Controller(config.reconnectWindow.inWholeMilliseconds)

        /**
         * How long a heartbeat ping to bob holds the detector's thread. A detector pings after it looks
         * for a frame and before it gives up, so a slow ping widens the gap between the look and the
         * `PeerLost`, which is where a Proof's release has to land to race the loss.
         */
        @Volatile
        var slowPingNanos: Long = 0

        /** An expiry emitted from its own coroutine this round, which [quiesce] waits out before its sentinels. */
        @Volatile
        var pendingExpiry: Job? = null
        private val ticks = AtomicLong(1_000_000)

        private val seam = object : Seam {
            override val selfId = host
            override val peers = this@Rig.peers
            override val state = MutableStateFlow<SeamState>(SeamState.Woven)
            override val incoming = inbound.receiveAsFlow()
            override suspend fun broadcast(payload: ByteArray) = Unit
            override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                if (peer == bob && payload.isPing()) spin(slowPingNanos)
                val message = AdmitMessage.decode(payload) ?: return
                if (message is AdmitMessage.Challenge) {
                    challenges[peer]?.send(message)
                } else {
                    sent.update { it + (peer to message) }
                }
            }
            override suspend fun close(reason: CloseReason) = Unit
        }

        val room = SeamRoom(
            seam = seam,
            role = SessionRole.Host,
            roomId = RoomId("test-room"),
            memberName = "host",
            scope = scope,
            clock = { Instant.fromEpochMilliseconds(ticks.incrementAndGet()) },
            device = device,
            heartbeatConfig = config,
            reconnectControllerFactory = { _, scope, _ -> controller.also { it.scope = scope } },
        )

        suspend fun start(stage: StageTracker) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                room.events.collect { event -> events.update { it + event } }
            }
            room.start()
            stage.at("setup: the reconnect loop subscribes") { snapshot() }
            controller.flow.subscriptionCount.first { it > 0 }
            for ((peer, device, key) in listOf(Triple(bob, TEST_DEVICE_A, TEST_KEY_A), Triple(carol, TEST_DEVICE_B, TEST_KEY_B))) {
                val intros = hostIntrosTo(peer)
                prove(peer, device, key)
                stage.at("setup: ${peer.value} is admitted") { snapshot() }
                events.first { events -> events.any { it is MembershipEvent.Joined && it.member.id == peer } }
                awaitIntros(peer, intros + 1)
            }
        }

        /** A Hello from [peer] under bob's key, and the challenge it drew. */
        suspend fun hello(peer: PeerId): AdmitMessage.Challenge {
            inbound.send(Swatch(AdmitMessage.encode(currentHello(peer.value, sessionId = peer.value, key = TEST_KEY_A)), sender = peer))
            return checkNotNull(challenges[peer]).receive()
        }

        /** Hello, then the Proof answering its challenge; [beforeProof] runs once the challenge is in. */
        suspend fun prove(peer: PeerId, device: DeviceKey, key: DidKey, beforeProof: suspend () -> Unit = {}) {
            inbound.send(Swatch(AdmitMessage.encode(currentHello(peer.value, sessionId = peer.value, key = key)), sender = peer))
            val challenge = checkNotNull(challenges[peer]).receive()
            beforeProof()
            inbound.send(Swatch(AdmitMessage.encode(handProof(challenge, host, device)), sender = peer))
        }

        /**
         * Start a round: forget the last one's frames and events, drop bob from the fabric, wait until
         * the host holds his seat partitioned under the controller's window, and put him back. Returns
         * the episode.
         */
        suspend fun partitionBob(stage: StageTracker, round: Int): Long {
            sent.value = emptyList()
            events.value = emptyList()
            val previous = controller.episode.value
            peers.update { it - bob }
            stage.at("round $round: bob partitions") { snapshot() }
            events.first { events -> events.any { it is MembershipEvent.Partitioned && it.peerId == bob } }
            // The room reports the partition to the controller after it emits Partitioned.
            val episode = controller.episode.first { it != previous }
            val deadline = Instant.fromEpochMilliseconds(controller.deadlineFor(episode))
            // Or the seat is already lost, when the detector's own window is shorter than the round.
            events.first { events ->
                events.any {
                    (it is MembershipEvent.WindowOpened && it.peerId == bob && it.expiresAt == deadline) ||
                        (it is MembershipEvent.Left && it.peerId == bob)
                }
            }
            peers.update { it + bob }
            return episode
        }

        fun hostIntrosTo(peer: PeerId): Int =
            sent.value.count { (to, message) -> to == peer && message is AdmitMessage.Welcome && message.assignedPeerId == host.value }

        /** The host intro is the last frame of a burst, so this is the Proof's coroutine finishing. */
        suspend fun awaitIntros(peer: PeerId, count: Int) {
            sent.first { frames -> frames.count { (to, m) -> to == peer && m is AdmitMessage.Welcome && m.assignedPeerId == host.value } >= count }
        }

        /**
         * The reconnect loop is past everything emitted before (an emit returns only once the loop has
         * taken it, after finishing the one before), and carol's lane is drained past the sentinel
         * `Farewell` the second sentinel fans out.
         */
        suspend fun quiesce(stage: StageTracker, round: Int) {
            stage.at("round $round: quiescing") { snapshot() }
            pendingExpiry?.join()
            pendingExpiry = null
            controller.flow.emit(JoinerReconnectEvent.Resumed(sentinel, 0))
            controller.flow.emit(JoinerReconnectEvent.WindowExpired(sentinel, 0, 0))
            sent.first { frames -> frames.any { (to, m) -> to == carol && m is AdmitMessage.Farewell && m.peerId == sentinel.value } }
        }

        fun framesToCarolNaming(peer: PeerId): List<AdmitMessage> =
            sent.value.filter { (to, message) -> to == carol && message.names(peer) }.map { it.second }

        fun edgesOf(peer: PeerId): List<String> = events.value.mapNotNull {
            when {
                it is MembershipEvent.Left && it.peerId == peer -> "Left"
                it is MembershipEvent.Joined && it.member.id == peer -> "Joined"
                it is MembershipEvent.Resumed && it.peerId == peer -> "Resumed"
                it is MembershipEvent.Recovered && it.peerId == peer -> "Recovered"
                else -> null
            }
        }

        fun snapshot(): String =
            "events=${events.value.map { it::class.simpleName }} sent=${sent.value.map { (to, m) -> "${to.value}:${m::class.simpleName}" }} " +
                "admitted=${room.isAdmitted(bob)} episode=${controller.episode.value} recovered=${controller.recovered.get()}"

        suspend fun close() {
            room.leave()
            scope.cancel()
        }
    }

    private companion object {
        val host = PeerId("host")
        val bob = PeerId("bob")
        val carol = PeerId("carol")
        val sentinel = PeerId("sentinel")
        val PONG = HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray()
        /** Each arm takes under 40 s on a dev Mac; the cap is a wedge backstop with room for a slower runner. */
        val ARM_CAP = 3.minutes
        const val EXPIRY_ROUNDS = 10_000
        const val HEAL_ROUNDS = 10_000
        const val MAX_SPIN_NANOS = 40_000L
        const val HEAL_PROOF_DELAY_MS = 4L
        const val LOSS_ROUNDS = 5_000
        val LOSS_INTERVAL = 2.milliseconds

        /** Up to a little past the old detector's loss, which lands one interval after the drop. */
        const val LOSS_PROOF_DELAY_NANOS = 3_000_000L

        /** About as long as a Hello takes to become a release, so the two overlap in some rounds. */
        const val LOSS_SLOW_PING_NANOS = 800_000L

        val PING = HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray()

        fun ByteArray.isPing(): Boolean = size >= PING.size && PING.indices.all { this[it] == PING[it] }

        fun MembershipEvent.isEdgeOf(peer: PeerId): Boolean =
            (this is MembershipEvent.Resumed && peerId == peer) || (this is MembershipEvent.Joined && member.id == peer)

        /** Seeded, so a run that reds can be read again; the interleavings still vary with the box. */
        val jitter = Random(3212)

        /** Hold this thread for [nanos], so the other one runs ahead. A few microseconds, on one core. */
        fun spin(nanos: Long) {
            val until = System.nanoTime() + nanos
            while (System.nanoTime() < until) Thread.onSpinWait()
        }
    }
}
