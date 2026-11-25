@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
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
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.admit.ReturnKind
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
                        rig.awaitEdge()
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
                            "carolLastNotWelcome=$carolLastNotWelcome retries=${rig.retries.get()} failures=$failures",
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
     * Either the heal wins, and the seat is restored (`Recovered`, and nothing more: the restore of a
     * seat the heal already reconnected is silent, #3215), or the release wins, and bob leaves and is
     * admitted afresh (`Left`, then `Joined`). Never both: a heal that is then released evicts a member
     * who is here, and carol, told `Unpaused` and then `Farewell`, drops him.
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
                        rig.awaitSeating(bob, introsBefore)
                        // A restore needs the heal, and a release tells the controller; either way
                        // one onPeerRecovered closes the round's window, and it is the last thing
                        // the heal (or the release) does before the room is settled.
                        stage.at("round $round: awaiting the window's close") { rig.snapshot() }
                        rig.controller.recoveredCount.first { it > recoveredBefore }
                        rig.quiesce(stage, round)

                        val edges = rig.edgesOf(bob)
                        val toCarol = rig.framesToCarolNaming(bob).map { it::class.simpleName }
                        when (edges) {
                            listOf("Recovered") -> healFirst++
                            listOf("Left", "Joined") -> releaseFirst++
                            else -> disallowedEdges++
                        }
                        val unpaused = toCarol.indexOf("Unpaused")
                        if (unpaused >= 0 && toCarol.drop(unpaused + 1).contains("Farewell")) unpausedThenFarewell++
                        val seated = rig.room.isAdmitted(bob)
                        if (!seated) notSeated++
                        val good = edges == listOf("Recovered") || edges == listOf("Left", "Joined")
                        if (!(good && seated) && failures.size < 5) {
                            failures += "round $round edges=$edges toCarol=$toCarol seated=$seated"
                        }
                        completed++
                        if (!seated) break
                    }
                    println(
                        "proven-restore heal: completed=$completed healFirst=$healFirst releaseFirst=$releaseFirst " +
                            "disallowedEdges=$disallowedEdges unpausedThenFarewell=$unpausedThenFarewell " +
                            "notSeated=$notSeated retries=${rig.retries.get()} failures=$failures",
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
                        rig.awaitSeating(bob, introsBefore)
                        // The burst is recorded as it is sent, and the events by a collector that can
                        // lag it: read the outcome once its edge is in — `Joined` for a readmission,
                        // `Recovered` for the heal a restore needs.
                        rig.events.first { events ->
                            events.any { (it is MembershipEvent.Joined && it.member.id == bob) || (it is MembershipEvent.Recovered && it.peerId == bob) }
                        }
                        val readmitted = "Joined" in rig.edgesOf(bob)
                        rig.slowPingNanos = 0
                        stage.at("round $round: awaiting the heal's window close") { rig.snapshot() }
                        // A restore needs the heal, whose last step closes the window; a release
                        // closes it before the Proof decides again, so before the Joined.
                        if (!readmitted) rig.controller.recoveredCount.first { it > recoveredBefore }
                        rig.quiesce(stage, round)
                        // A loss the old detector already handed over is handled on its own thread;
                        // give it two of its intervals to land before the round is read.
                        delay(LOSS_INTERVAL * 2)

                        val released = rig.controller.recovered.get() > recoveredBefore
                        val edges = rig.edgesOf(bob)
                        val toCarol = rig.framesToCarolNaming(bob)
                        when {
                            edges == listOf("Recovered") -> healFirst++
                            edges == listOf("Left", "Joined") && released -> releaseFirst++
                            edges == listOf("Left", "Joined") -> lossFirst++
                            else -> disallowedEdges++
                        }
                        val seated = rig.room.isAdmitted(bob)
                        val watched = rig.room.hasDetector(bob)
                        val carolLastIsWelcome = !readmitted || toCarol.lastOrNull() is AdmitMessage.Welcome
                        if (!seated) notSeated++
                        if (!watched) noDetector++
                        if (!carolLastIsWelcome) carolLastNotWelcome++
                        val good = edges == listOf("Recovered") || edges == listOf("Left", "Joined")
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
                            "noDetector=$noDetector carolLastNotWelcome=$carolLastNotWelcome retries=${rig.retries.get()} failures=$failures",
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
     * Bob's device says Hello under its other fabric id while its old seat's window expires (#3213).
     * The hold policy refuses, because the window has just closed, and the expiry evicts on the
     * reconnect loop's thread while the Proof decides on another.
     *
     * Either order ends the same way: the old id leaves as an expiry, never superseded, and the new
     * id is a new member, seated, watched, and the last thing carol hears about it is its Welcome.
     * The seat moves to a fresh id each round.
     */
    @Test
    fun anExpiryRacingANewIdsProofLeavesTheNewMemberSeated() =
        runConcurrencyStress(cap = ARM_CAP) { stage ->
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                val rig = Rig(dispatcher, interval = 1.seconds, RoomDevice.of(fakeKeyStore(0)))
                var completed = 0
                var expiryFirst = 0
                var proofFirst = 0
                var wrongEdges = 0
                var wrongRoster = 0
                var noDetector = 0
                var carolWrongLast = 0
                val failures = mutableListOf<String>()
                try {
                    rig.start(stage)
                    var holder = bob
                    for (round in 0 until SUPERSEDE_ROUNDS) {
                        val old = holder
                        // A fresh id each round: an id superseded in an earlier round is told so when it says
                        // Hello again (#3159, Task 1.7), so it can never be the new process here.
                        val new = PeerId("bob-expiry-$round").also { id -> rig.peers.update { it + id } }
                        val episode = rig.partition(stage, round, old)
                        val recoveredBefore = rig.controller.recovered.get()
                        val spinNanos = jitter.nextLong(0, MAX_SPIN_NANOS)
                        rig.controller.onAsk = { resume, at ->
                            val expiry = JoinerReconnectEvent.WindowExpired(resume.peerId, at, episode)
                            when (round % 3) {
                                0 -> rig.controller.flow.emit(expiry)
                                else -> {
                                    rig.pendingExpiry = rig.scope.launch { rig.controller.flow.emit(expiry) }
                                    if (round % 3 == 2) spin(spinNanos)
                                }
                            }
                            ResumeResult.WindowClosed
                        }
                        val introsBefore = rig.hostIntrosTo(new)
                        rig.prove(new, TEST_DEVICE_A, TEST_KEY_A)
                        stage.at("round $round: awaiting the new member") { rig.snapshot() }
                        rig.awaitEdge(new)
                        rig.awaitIntros(new, introsBefore + 1)
                        rig.quiesce(stage, round)

                        val released = rig.controller.recovered.get() > recoveredBefore
                        if (released) proofFirst++ else expiryFirst++
                        val oldLeft = rig.events.value.filterIsInstance<MembershipEvent.Left>().filter { it.peerId == old }
                        val edgesGood = oldLeft.map { it.reason } == listOf<LeaveReason>(LeaveReason.PartitionExpired) &&
                            rig.edgesOf(old) == listOf("Left") && rig.edgesOf(new) == listOf("Joined")
                        val rosterGood = rig.room.isAdmitted(new) && !rig.room.isAdmitted(old)
                        val watched = rig.room.hasDetector(new)
                        val carolGood = rig.framesToCarolNaming(old).lastOrNull() is AdmitMessage.Farewell &&
                            rig.framesToCarolNaming(new).lastOrNull() is AdmitMessage.Welcome
                        if (!edgesGood) wrongEdges++
                        if (!rosterGood) wrongRoster++
                        if (!watched) noDetector++
                        if (!carolGood) carolWrongLast++
                        if (!(edgesGood && rosterGood && watched && carolGood) && failures.size < 5) {
                            failures += "round $round released=$released old=${old.value} edges=${rig.edgesOf(old)}/${rig.edgesOf(new)} " +
                                "left=$oldLeft toCarol=${rig.framesToCarolNaming(old).map { it::class.simpleName }}/" +
                                "${rig.framesToCarolNaming(new).map { it::class.simpleName }} roster=$rosterGood watched=$watched"
                        }
                        completed++
                        if (!rig.room.isAdmitted(new)) break
                        holder = new
                    }
                    println(
                        "proven-restore supersede-expiry: completed=$completed expiryFirst=$expiryFirst proofFirst=$proofFirst " +
                            "wrongEdges=$wrongEdges wrongRoster=$wrongRoster noDetector=$noDetector carolWrongLast=$carolWrongLast " +
                            "retries=${rig.retries.get()} failures=$failures",
                    )
                    assertAll(
                        { assertEquals(SUPERSEDE_ROUNDS, completed, "every round ran") },
                        { assertEquals(0, wrongEdges, "the old id expired and the new one joined: $failures") },
                        { assertEquals(0, wrongRoster, "only the new id is seated: $failures") },
                        { assertEquals(0, noDetector, "the new member is watched: $failures") },
                        { assertEquals(0, carolWrongLast, "carol last heard Farewell(old) and Welcome(new): $failures") },
                        { assertTrue(expiryFirst > 0, "the expiry's section ran first in some round") },
                        { assertTrue(proofFirst > 0, "the Proof's release ran first in some round") },
                    )
                } finally {
                    rig.close()
                }
            }
        }

    /**
     * Bob's old link heals while the host takes his seat over for his device's other fabric id
     * (#3213). The window answers `Success`, and a pong from the old id lands on another thread, so
     * the old detector's recovery races the supersession.
     *
     * Either the heal wins, and the old seat is connected again, so the new id gets a seat of its own
     * beside it (Decided 30: a connected seat is never superseded), or the supersession wins, and the
     * old id leaves `Superseded(by = new)`. Never both, and carol is never told `Unpaused` for a
     * member it is then told left, nor told the new member joined before the old one left.
     */
    @Test
    fun aHealRacingASupersessionKeepsBothSeatsOrMovesOne() =
        runConcurrencyStress(cap = ARM_CAP) { stage ->
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                val rig = Rig(dispatcher, interval = 2.milliseconds, RoomDevice.of(fakeKeyStore(0)))
                var completed = 0
                var healFirst = 0
                var supersedeFirst = 0
                var disallowed = 0
                var wrongRoster = 0
                var noDetector = 0
                var carolMisordered = 0
                val failures = mutableListOf<String>()
                try {
                    rig.start(stage)
                    var holder = bob
                    for (round in 0 until SUPERSEDE_ROUNDS) {
                        val old = holder
                        // A fresh id each round: an id superseded in an earlier round is told so when it says
                        // Hello again (#3159, Task 1.7), so it can never be the new process here.
                        val new = PeerId("bob-heal-$round").also { id -> rig.peers.update { it + id } }
                        rig.partition(stage, round, old)
                        rig.controller.onAsk = { resume, at ->
                            // As the default controller does: a Success is echoed on the reconnect loop.
                            rig.scope.launch { rig.controller.flow.emit(JoinerReconnectEvent.Resumed(resume.peerId, at)) }
                            ResumeResult.Success
                        }
                        val introsBefore = rig.hostIntrosTo(new)
                        rig.prove(new, TEST_DEVICE_A, TEST_KEY_A) {
                            rig.scope.launch { rig.inbound.send(Swatch(PONG, sender = old)) }
                            delay(jitter.nextLong(0, HEAL_PROOF_DELAY_MS))
                        }
                        stage.at("round $round: awaiting the new member") { rig.snapshot() }
                        rig.awaitEdge(new)
                        rig.awaitIntros(new, introsBefore + 1)
                        rig.quiesce(stage, round)

                        val oldEdges = rig.edgesOf(old)
                        val oldLeft = rig.events.value.filterIsInstance<MembershipEvent.Left>().filter { it.peerId == old }
                        val newEdges = rig.edgesOf(new)
                        val toCarol = rig.sent.value.filter { (to, m) -> to == carol && (m.names(old) || m.names(new)) }.map { it.second }
                        val farewellAt = toCarol.indexOfFirst { it is AdmitMessage.Farewell && it.peerId == old.value }
                        val welcomeAt = toCarol.indexOfFirst { it is AdmitMessage.Welcome && it.assignedPeerId == new.value }
                        val unpausedAt = toCarol.indexOfFirst { it is AdmitMessage.Unpaused && it.peerId == old.value }
                        val kept = oldEdges == listOf("Recovered") && newEdges == listOf("Joined")
                        val moved = oldEdges == listOf("Left") && newEdges == listOf("Joined") &&
                            oldLeft.single().reason == LeaveReason.Superseded(by = new)
                        val rosterGood = when {
                            kept -> rig.room.isAdmitted(old) && rig.room.isAdmitted(new)
                            moved -> !rig.room.isAdmitted(old) && rig.room.isAdmitted(new)
                            else -> false
                        }
                        val watched = rig.room.hasDetector(new) && (!kept || rig.room.hasDetector(old))
                        val carolGood = when {
                            kept -> farewellAt < 0 && unpausedAt >= 0 && welcomeAt >= 0
                            moved -> farewellAt >= 0 && welcomeAt > farewellAt &&
                                (toCarol[farewellAt] as AdmitMessage.Farewell).supersededBy == new.value &&
                                unpausedAt < 0
                            else -> false
                        }
                        when {
                            kept -> healFirst++
                            moved -> supersedeFirst++
                            else -> disallowed++
                        }
                        if (!rosterGood) wrongRoster++
                        if (!watched) noDetector++
                        if (!carolGood) carolMisordered++
                        if (!(rosterGood && watched && carolGood) && failures.size < 5) {
                            failures += "round $round old=${old.value} edges=$oldEdges/$newEdges left=$oldLeft " +
                                "toCarol=${toCarol.map { it::class.simpleName }} roster=$rosterGood watched=$watched"
                        }
                        completed++
                        if (!rig.room.isAdmitted(new)) break
                        if (kept) {
                            // Back to one seat for the key: the new id says Goodbye, and the old one stays.
                            rig.inbound.send(Swatch(AdmitMessage.encode(AdmitMessage.Goodbye), sender = new))
                            stage.at("round $round: the extra seat leaves") { rig.snapshot() }
                            rig.events.first { events -> events.any { it is MembershipEvent.Left && it.peerId == new } }
                        } else {
                            holder = new
                        }
                    }
                    println(
                        "proven-restore supersede-heal: completed=$completed healFirst=$healFirst supersedeFirst=$supersedeFirst " +
                            "disallowed=$disallowed wrongRoster=$wrongRoster noDetector=$noDetector carolMisordered=$carolMisordered " +
                            "retries=${rig.retries.get()} failures=$failures",
                    )
                    assertAll(
                        { assertEquals(SUPERSEDE_ROUNDS, completed, "every round ran") },
                        { assertEquals(0, disallowed, "each round keeps both seats or moves one, never both: $failures") },
                        { assertEquals(0, wrongRoster, "the host's roster matches the outcome: $failures") },
                        { assertEquals(0, noDetector, "every seated member is watched: $failures") },
                        { assertEquals(0, carolMisordered, "carol hears the outcome in order: $failures") },
                        { assertTrue(healFirst > 0, "the heal ran first in some round") },
                        { assertTrue(supersedeFirst > 0, "the supersession ran first in some round") },
                    )
                } finally {
                    rig.close()
                }
            }
        }

    /**
     * Two tabs of bob's device come back under his two other fabric ids at once, against his one
     * partitioned seat (#3213). The window answers `Success` once and refuses after, as the default
     * controller does once a window is spent, and echoes the `Success` on the reconnect loop.
     *
     * Only one exchange may ask about the seat. Either way round, exactly one new id takes the seat
     * over and the other gets a seat of its own beside it (Decided 30); nobody sees a `Resumed` or is
     * sent an `Unpaused` or a `ResumeAck`; and carol last heard `Farewell(old, supersededBy = winner)`
     * and each new id's `Welcome`. Without one asker per seat the refused ask releases the seat the
     * other was given, or lets the echo restore the member who left. The round ends with the extra
     * seat saying Goodbye, so the next round starts from one seat again.
     */
    @Test
    fun twoNewIdsRacingForOneSeatTakeItOverOnce() =
        runConcurrencyStress(cap = ARM_CAP) { stage ->
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                val rig = Rig(dispatcher, interval = 1.seconds, RoomDevice.of(fakeKeyStore(0)))
                var completed = 0
                var lowerIdWon = 0
                var higherIdWon = 0
                var twoAsks = 0
                var wrongEdges = 0
                var wrongRoster = 0
                var strayResume = 0
                var carolWrongLast = 0
                val failures = mutableListOf<String>()
                try {
                    rig.start(stage)
                    var holder = bob
                    for (round in 0 until SUPERSEDE_ROUNDS) {
                        val old = holder
                        // Fresh ids each round: an id superseded in an earlier round is told so when it says
                        // Hello again (#3159, Task 1.7), so it can never be one of the new processes here.
                        val (low, high) = listOf(PeerId("bob-race-$round-a"), PeerId("bob-race-$round-b"))
                        rig.peers.update { it + low + high }
                        rig.partition(stage, round, old)
                        val spent = AtomicBoolean(false)
                        val asks = AtomicInteger(0)
                        val spinNanos = jitter.nextLong(0, MAX_SPIN_NANOS)
                        rig.controller.onAsk = { resume, at ->
                            asks.incrementAndGet()
                            if (resume.peerId == old && spent.compareAndSet(false, true)) {
                                rig.scope.launch { rig.controller.flow.emit(JoinerReconnectEvent.Resumed(resume.peerId, at)) }
                                // Hold the Success a moment, so a second ask lands before it is acted on.
                                spin(spinNanos)
                                ResumeResult.Success
                            } else {
                                ResumeResult.WindowClosed
                            }
                        }
                        val introsLow = rig.hostIntrosTo(low)
                        val introsHigh = rig.hostIntrosTo(high)
                        val proofs = listOf(low, high).shuffled(jitter).map { peer ->
                            rig.scope.launch { rig.prove(peer, TEST_DEVICE_A, TEST_KEY_A) }
                        }
                        proofs.forEach { it.join() }
                        stage.at("round $round: awaiting both new members") { rig.snapshot() }
                        rig.awaitEdge(low)
                        rig.awaitEdge(high)
                        rig.awaitIntros(low, introsLow + 1)
                        rig.awaitIntros(high, introsHigh + 1)
                        rig.quiesce(stage, round)

                        val oldLeft = rig.events.value.filterIsInstance<MembershipEvent.Left>().filter { it.peerId == old }
                        val winner = (oldLeft.singleOrNull()?.reason as? LeaveReason.Superseded)?.by
                        val loser = if (winner == low) high else low
                        val edgesGood = winner != null && rig.edgesOf(old) == listOf("Left") &&
                            rig.edgesOf(low) == listOf("Joined") && rig.edgesOf(high) == listOf("Joined")
                        val rosterGood = !rig.room.isAdmitted(old) && rig.room.isAdmitted(low) && rig.room.isAdmitted(high) &&
                            rig.room.hasDetector(low) && rig.room.hasDetector(high)
                        val stray = rig.events.value.any { it is MembershipEvent.Resumed || it is MembershipEvent.Recovered } ||
                            rig.sent.value.any { (_, m) -> m is AdmitMessage.Unpaused }
                        val lastAboutOld = rig.framesToCarolNaming(old).lastOrNull()
                        val carolGood = lastAboutOld is AdmitMessage.Farewell && lastAboutOld.supersededBy == winner?.value &&
                            rig.framesToCarolNaming(low).lastOrNull() is AdmitMessage.Welcome &&
                            rig.framesToCarolNaming(high).lastOrNull() is AdmitMessage.Welcome
                        when (winner) {
                            low -> lowerIdWon++
                            high -> higherIdWon++
                        }
                        if (asks.get() > 1) twoAsks++
                        if (!edgesGood) wrongEdges++
                        if (!rosterGood) wrongRoster++
                        if (stray) strayResume++
                        if (!carolGood) carolWrongLast++
                        if (!(edgesGood && rosterGood && !stray && carolGood) && failures.size < 5) {
                            failures += "round $round old=${old.value} winner=${winner?.value} asks=${asks.get()} " +
                                "edges=${rig.edgesOf(old)}/${rig.edgesOf(low)}/${rig.edgesOf(high)} left=$oldLeft " +
                                "toCarol=${rig.framesToCarolNaming(old).map { it::class.simpleName }} roster=$rosterGood stray=$stray"
                        }
                        completed++
                        if (winner == null || !rig.room.isAdmitted(winner) || !rig.room.isAdmitted(loser)) break
                        // Back to one seat for the key: the extra seat says Goodbye.
                        rig.inbound.send(Swatch(AdmitMessage.encode(AdmitMessage.Goodbye), sender = loser))
                        stage.at("round $round: the extra seat leaves") { rig.snapshot() }
                        rig.events.first { events -> events.any { it is MembershipEvent.Left && it.peerId == loser } }
                        holder = winner
                    }
                    println(
                        "proven-restore supersede-race: completed=$completed lowerIdWon=$lowerIdWon higherIdWon=$higherIdWon " +
                            "twoAsks=$twoAsks wrongEdges=$wrongEdges wrongRoster=$wrongRoster strayResume=$strayResume " +
                            "carolWrongLast=$carolWrongLast retries=${rig.retries.get()} failures=$failures",
                    )
                    assertAll(
                        { assertEquals(SUPERSEDE_ROUNDS, completed, "every round ran") },
                        { assertEquals(0, wrongEdges, "one takeover and one additional seat: $failures") },
                        { assertEquals(0, wrongRoster, "both new ids are seated and watched, the old one is not: $failures") },
                        { assertEquals(0, strayResume, "no Resumed, Unpaused or ResumeAck for the seat that left: $failures") },
                        { assertEquals(0, carolWrongLast, "carol last heard the supersession and both Welcomes: $failures") },
                        { assertEquals(0, twoAsks, "one exchange asks about the seat: $failures") },
                        { assertTrue(lowerIdWon > 0, "the lower id took the seat over in some round") },
                        { assertTrue(higherIdWon > 0, "the higher id took the seat over in some round") },
                    )
                } finally {
                    rig.close()
                }
            }
        }

    /**
     * A superseded process's re-Hello racing its own supersession (#3159, Task 1.7). Bob's seat is
     * partitioned; a new process of his device comes back under a fresh id while the old one — whose
     * link healed — says Hello again under its own. Both prove the same key, on two threads. The window
     * answers `Success` once and refuses after, as a spent window does.
     *
     * Either the old process restores its own seat first, and the new id then gets a seat of its own
     * beside it (Decided 30), or the new id takes the seat over first, and the old process must then be
     * **told**, never seated: the host answers its exchange with `Farewell(supersededBy)` whether the
     * Hello came in after the takeover (the Hello gate) or before it (the Proof-time check, read under
     * the lock that recorded the takeover). A single thread cannot put the old exchange's decision
     * after the takeover's record and before its own seat, which is the window the Proof-time check
     * closes; without it the old process is admitted to an additional seat and the superseded
     * membership is back.
     */
    @Test
    fun aSupersededProcessReHelloingAgainstItsTakeoverIsToldNotSeated() =
        runConcurrencyStress(cap = ARM_CAP) { stage ->
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                val rig = Rig(dispatcher, interval = 1.seconds, RoomDevice.of(fakeKeyStore(0)))
                var completed = 0
                var oldWon = 0
                var newWon = 0
                var toldAtProof = 0
                var reSeated = 0
                var slowChallenged = 0
                var wrongOutcome = 0
                val failures = mutableListOf<String>()
                try {
                    rig.start(stage)
                    var holder = bob
                    for (round in 0 until REHELLO_ROUNDS) {
                        val old = holder
                        val fresh = PeerId("bob-rehello-$round")
                        rig.peers.update { it + fresh }
                        rig.drainChallenges()
                        // Some rounds' host is slow to challenge the old id: its answer is still a
                        // challenge, however late, and the rig must read it as one.
                        rig.slowChallengeTo = old.takeIf { round % SLOW_CHALLENGE_EVERY == 0 }
                        rig.partition(stage, round, old)
                        val spent = AtomicBoolean(false)
                        rig.controller.onAsk = { resume, at ->
                            if (resume.peerId == old && spent.compareAndSet(false, true)) {
                                rig.scope.launch { rig.controller.flow.emit(JoinerReconnectEvent.Resumed(resume.peerId, at)) }
                                spin(jitter.nextLong(0, MAX_SPIN_NANOS))
                                ResumeResult.Success
                            } else {
                                ResumeResult.WindowClosed
                            }
                        }
                        val introsFresh = rig.hostIntrosTo(fresh)
                        val oldChallenged = AtomicBoolean(false)
                        val exchanges = listOf(
                            rig.scope.launch { rig.prove(fresh, TEST_DEVICE_A, TEST_KEY_A) },
                            rig.scope.launch {
                                val challenge = rig.helloOrTold(old) ?: return@launch
                                oldChallenged.set(true)
                                rig.inbound.send(Swatch(AdmitMessage.encode(handProof(challenge, host, TEST_DEVICE_A)), sender = old))
                            },
                        ).shuffled(jitter)
                        exchanges.forEach { it.join() }
                        stage.at("round $round: awaiting the new id's seat") { rig.snapshot() }
                        rig.awaitEdge(fresh)
                        rig.awaitIntros(fresh, introsFresh + 1)
                        // The old exchange's own answer: its restored seat, or the Farewell telling it.
                        withTimeoutOrNull(STALL_GRACE) {
                            rig.sent.first { frames ->
                                rig.events.value.any { it is MembershipEvent.Resumed && it.peerId == old } ||
                                    frames.count { (to, m) -> to == old && m is AdmitMessage.Farewell && m.supersededBy == fresh.value } >= 2
                            }
                        }
                        rig.quiesce(stage, round)

                        val supersededByFresh = rig.events.value.any {
                            it is MembershipEvent.Left && it.peerId == old && it.reason == LeaveReason.Superseded(fresh)
                        }
                        val oldRestored = rig.events.value.any { it is MembershipEvent.Resumed && it.peerId == old }
                        val oldJoinedAgain = rig.events.value.any { it is MembershipEvent.Joined && it.member.id == old }
                        val toldOld = rig.supersededFarewellsTo(old)
                        val good = when {
                            supersededByFresh -> !rig.room.isAdmitted(old) && !oldJoinedAgain && toldOld >= 2 && rig.room.isAdmitted(fresh)
                            oldRestored -> rig.room.isAdmitted(old) && rig.room.isAdmitted(fresh) && toldOld == 0
                            else -> false
                        }
                        when {
                            supersededByFresh -> newWon++
                            oldRestored -> oldWon++
                        }
                        if (supersededByFresh && oldChallenged.get()) toldAtProof++
                        if (rig.slowChallengeTo != null && oldChallenged.get()) slowChallenged++
                        if (supersededByFresh && (oldJoinedAgain || rig.room.isAdmitted(old))) reSeated++
                        if (!good) wrongOutcome++
                        if (!good && failures.size < 5) {
                            failures += "round $round old=${old.value} superseded=$supersededByFresh restored=$oldRestored " +
                                "joinedAgain=$oldJoinedAgain told=$toldOld challenged=${oldChallenged.get()} " +
                                "admitted=${rig.room.isAdmitted(old)}/${rig.room.isAdmitted(fresh)} edges=${rig.edgesOf(old)}/${rig.edgesOf(fresh)}"
                        }
                        completed++
                        if (!good) break
                        if (supersededByFresh) {
                            holder = fresh
                        } else {
                            // Back to one seat for the key: the fresh id's additional seat says Goodbye.
                            rig.inbound.send(Swatch(AdmitMessage.encode(AdmitMessage.Goodbye), sender = fresh))
                            stage.at("round $round: the extra seat leaves") { rig.snapshot() }
                            rig.events.first { events -> events.any { it is MembershipEvent.Left && it.peerId == fresh } }
                        }
                    }
                    println(
                        "proven-restore rehello-race: completed=$completed oldWon=$oldWon newWon=$newWon " +
                            "toldAtProof=$toldAtProof reSeated=$reSeated slowChallenged=$slowChallenged wrongOutcome=$wrongOutcome " +
                            "retries=${rig.retries.get()} failures=$failures",
                    )
                    assertAll(
                        { assertEquals(REHELLO_ROUNDS, completed, "every round ran") },
                        { assertEquals(0, reSeated, "a superseded process is never seated again: $failures") },
                        { assertEquals(0, wrongOutcome, "one restore, or one takeover and a told old process: $failures") },
                        { assertTrue(oldWon > 0, "the old process restored its seat first in some round") },
                        { assertTrue(newWon > 0, "the new id took the seat over first in some round") },
                        { assertTrue(toldAtProof > 0, "in some round the old exchange was challenged before the takeover, and told at its proof") },
                        { assertTrue(slowChallenged > 0, "in some round the host's late challenge reached the old exchange and was answered") },
                    )
                } finally {
                    rig.close()
                }
            }
        }

    /**
     * The joiner side, outside any reconnect episode: the host's `Welcome` answering the joiner's latest
     * Hello arrives again, stale, while a caller's own attempt installs its flight. The flight's
     * install forgets that Hello, so the `Welcome` must settle nothing whichever lands first. The room
     * checks the Hello the `Welcome` names and takes the flight in one critical section; were they two,
     * a flight installed between them would be taken by an answer to a Hello it never sent, and report
     * `Success` (review round 2 on #3614; #3215).
     *
     * The gap between two such sections holds no suspension point and calls out to nothing, so a
     * single thread cannot reach it: only a second thread installing the flight can. Each round is
     * then settled honestly, by challenging the caller's Hello and answering it, so the next round's
     * stale `Welcome` again names the latest Hello and passes the check.
     */
    @Test
    fun aStaleWelcomeRacingACallersResumeSettlesNothing() =
        runConcurrencyStress(cap = ARM_CAP) { stage ->
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                val rig = JoinerRig(dispatcher, RoomDevice.of(fakeKeyStore(0)))
                var completed = 0
                var settledByStale = 0
                var honest = 0
                val failures = mutableListOf<String>()
                try {
                    rig.admit(stage)
                    for (round in 0 until STALE_WELCOME_ROUNDS) {
                        val handled = rig.framesHandled.value
                        val hellos = rig.hellos.value.size
                        // The Hello the last round settled: the latest until the caller mints its own.
                        val staleHello = rig.hellos.value.last()
                        val welcomeSpin = jitter.nextLong(0, STALE_WELCOME_SPIN_NANOS)
                        val resumeSpin = jitter.nextLong(0, STALE_WELCOME_SPIN_NANOS)
                        val stale = rig.scope.launch {
                            spin(welcomeSpin)
                            rig.inbound.send(Swatch(AdmitMessage.encode(rig.restoredWelcome(staleHello)), sender = host))
                        }
                        val call = rig.scope.async {
                            spin(resumeSpin)
                            rig.room.sayHelloAgain()
                        }
                        stale.join()
                        stage.at("round $round: the stale Welcome is handled") { rig.snapshot() }
                        rig.framesHandled.first { it > handled }
                        stage.at("round $round: the caller says Hello, or the stale Welcome settled it") { rig.snapshot() }
                        coroutineScope {
                            val hello = async { rig.hellos.first { it.size > hellos } }
                            select<Unit> {
                                hello.onAwait { }
                                call.onAwait { }
                            }
                            hello.cancel()
                        }
                        // Settled with no Hello of its own, a call was taken by the stale Welcome. The round
                        // then re-arms with a second call, so the next round's stale Welcome names the
                        // latest Hello again.
                        val settling = if (call.isCompleted) {
                            settledByStale++
                            if (failures.size < 5) failures += "round $round: ${call.await()} welcomeSpin=$welcomeSpin resumeSpin=$resumeSpin"
                            val rearm = rig.scope.async { rig.room.sayHelloAgain() }
                            stage.at("round $round: the re-arming call says Hello") { rig.snapshot() }
                            rig.hellos.first { it.size > hellos }
                            rearm
                        } else {
                            call
                        }
                        // Settle the round honestly: the host challenges the latest Hello, the joiner
                        // answers, and the host's Welcome closes the flight.
                        val proofs = rig.proofCount.value
                        rig.inbound.send(Swatch(AdmitMessage.encode(handChallenge(rig.hellos.value.last(), joiner, host)), sender = host))
                        stage.at("round $round: the joiner answers the challenge") { rig.snapshot() }
                        rig.proofCount.first { it > proofs }
                        rig.inbound.send(Swatch(AdmitMessage.encode(rig.restoredWelcome(rig.hellos.value.last())), sender = host))
                        stage.at("round $round: the honest Welcome settles the call") { rig.snapshot() }
                        if (settling.await() == ResumeResult.Success) honest++
                        completed++
                    }
                    println(
                        "proven-restore stale-welcome-race: completed=$completed settledByStale=$settledByStale " +
                            "honest=$honest failures=$failures",
                    )
                    assertAll(
                        { assertEquals(STALE_WELCOME_ROUNDS, completed, "every round ran: $failures") },
                        { assertEquals(0, settledByStale, "a stale Welcome settles no caller's flight: $failures") },
                        { assertEquals(completed, honest, "the honest Welcome settled every round's call") },
                    )
                } finally {
                    rig.close()
                }
            }
        }

    /**
     * The `Reject` twin of [aStaleWelcomeRacingACallersResumeSettlesNothing] (#3215): a terminal
     * `Reject` naming the Hello the last round settled arrives, stale, while a caller's own attempt
     * installs its flight. The room checks the Hello the `Reject` names and takes the flight in one
     * critical section, so the refusal settles nothing in either order: checked before the install it
     * finds no flight, checked after it it finds no latest Hello. Were the check and the take two
     * sections, a flight installed between them would be refused by an answer to a Hello it never sent.
     *
     * Each ordering is **forced**, half the rounds each, and counted:
     * - **check first** — the stale `Reject` lands first, and [SeamRoom.rejectCheckedForTest] parks the
     *   frame thread between the check and the take while it starts the caller's attempt on the other
     *   thread, waits until that caller is running (`reachedGap`, so a caller never scheduled cannot
     *   pass for one held off), and gives it [STALE_REJECT_GAP_NANOS] to install its flight. Under one
     *   section the caller cannot: it waits on the lock the frame thread holds, which `inGap` counts as
     *   zero.
     * - **install first** — the caller's flight is installed before the stale `Reject` is sent, so the
     *   check finds the latest Hello forgotten.
     *
     * Every round is then settled honestly. Measured: with `handleReject` split into two sections
     * around the hook, every check-first round is settled by the stale refusal.
     */
    @Test
    fun aStaleRejectRacingACallersResumeSettlesNothing() =
        runConcurrencyStress(cap = ARM_CAP) { stage ->
            // The check-first caller starts on a thread of its own: the room's two can both be held
            // while the frame thread parks in the gap (the other waiting on the lock it holds), and a
            // caller with no thread to start on would pass for one the lock held off.
            Executors.newCachedThreadPool().asCoroutineDispatcher().use { callers ->
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                val rig = JoinerRig(dispatcher, RoomDevice.of(fakeKeyStore(0)))
                val callerScope = CoroutineScope(SupervisorJob() + callers)
                var checkFirst = 0
                var installFirst = 0
                var wrongOrdering = 0
                var inGap = 0
                var reachedGap = 0
                var settledByStale = 0
                var honest = 0
                val failures = mutableListOf<String>()
                // The round's stale Reject only: taken by the hook, so no other Reject is parked.
                val gap = AtomicReference<StaleRejectGap?>(null)
                try {
                    rig.admit(stage)
                    // Frames this arm has sent the room, the admission's two included. A wait on the
                    // stale Reject waits for exactly its own handling: the round before's Welcome can
                    // settle its call before the collector counts that Welcome as handled.
                    var sent = 2
                    rig.room.rejectCheckedForTest = { answersLatest ->
                        gap.getAndSet(null)?.let { g ->
                            g.answersLatest = answersLatest
                            g.onCheck?.invoke()
                        }
                    }
                    for (round in 0 until STALE_REJECT_ROUNDS) {
                        val hellos = rig.hellos.value.size
                        val staleHello = rig.hellos.value.last()
                        val stale = AdmitMessage.Reject("key-conflict: stale", RejectCode.KeyConflict, staleHello.joinerNonce)
                        val forceCheckFirst = round % 2 == 0
                        val g = StaleRejectGap()
                        val call: Deferred<ResumeResult.JoinerOutcome>
                        if (forceCheckFirst) {
                            // The caller starts from inside the section that checked the stale Reject.
                            g.onCheck = {
                                // `sayHelloAgain` goes straight to the room's lock with no suspension
                                // point first, so a caller seen entering is one about to contend for it.
                                val entered = AtomicBoolean(false)
                                g.call = callerScope.async {
                                    entered.set(true)
                                    rig.room.sayHelloAgain()
                                }
                                val enterBy = System.nanoTime() + STALE_REJECT_ENTER_NANOS
                                while (!entered.get() && System.nanoTime() < enterBy) Thread.onSpinWait()
                                g.reachedGap = entered.get()
                                val deadline = System.nanoTime() + STALE_REJECT_GAP_NANOS
                                while (!rig.room.hasPendingResume() && System.nanoTime() < deadline) Thread.onSpinWait()
                                g.installedInGap = rig.room.hasPendingResume()
                            }
                            gap.set(g)
                            rig.inbound.send(Swatch(AdmitMessage.encode(stale), sender = host))
                            sent++
                            stage.at("round $round: the stale Reject is checked first") { rig.snapshot() }
                            rig.framesHandled.first { it >= sent }
                            call = checkNotNull(g.call) { "round $round: the hook did not run" }
                        } else {
                            call = rig.scope.async { rig.room.sayHelloAgain() }
                            stage.at("round $round: the caller installs its flight first") { rig.snapshot() }
                            while (!rig.room.hasPendingResume() && !call.isCompleted) delay(1)
                            gap.set(g)
                            rig.inbound.send(Swatch(AdmitMessage.encode(stale), sender = host))
                            sent++
                            rig.framesHandled.first { it >= sent }
                        }
                        when {
                            forceCheckFirst && g.answersLatest == true -> checkFirst++
                            !forceCheckFirst && g.answersLatest == false -> installFirst++
                            else -> {
                                wrongOrdering++
                                if (failures.size < 5) failures += "round $round checkFirst=$forceCheckFirst answersLatest=${g.answersLatest}"
                            }
                        }
                        if (g.installedInGap) inGap++
                        if (g.reachedGap) reachedGap++
                        stage.at("round $round: the caller says Hello, or the stale Reject settled it") { rig.snapshot() }
                        coroutineScope {
                            val hello = async { rig.hellos.first { it.size > hellos } }
                            select<Unit> {
                                hello.onAwait { }
                                call.onAwait { }
                            }
                            hello.cancel()
                        }
                        val settling = if (call.isCompleted) {
                            settledByStale++
                            if (failures.size < 5) failures += "round $round checkFirst=$forceCheckFirst: ${call.await()} inGap=${g.installedInGap}"
                            // The settled call may still have sent its Hello: wait for the re-arm's own.
                            val beforeRearm = rig.hellos.value.size
                            val rearm = rig.scope.async { rig.room.sayHelloAgain() }
                            stage.at("round $round: the re-arming call says Hello") { rig.snapshot() }
                            rig.hellos.first { it.size > beforeRearm }
                            rearm
                        } else {
                            call
                        }
                        val proofs = rig.proofCount.value
                        rig.inbound.send(Swatch(AdmitMessage.encode(handChallenge(rig.hellos.value.last(), joiner, host)), sender = host))
                        sent++
                        stage.at("round $round: the joiner answers the challenge") { rig.snapshot() }
                        rig.proofCount.first { it > proofs }
                        rig.inbound.send(Swatch(AdmitMessage.encode(rig.restoredWelcome(rig.hellos.value.last())), sender = host))
                        sent++
                        stage.at("round $round: the honest Welcome settles the call") { rig.snapshot() }
                        if (settling.await() == ResumeResult.Success) honest++
                    }
                    println(
                        "proven-restore stale-reject-race: rounds=$STALE_REJECT_ROUNDS checkFirst=$checkFirst " +
                            "installFirst=$installFirst wrongOrdering=$wrongOrdering reachedGap=$reachedGap inGap=$inGap " +
                            "settledByStale=$settledByStale honest=$honest failures=$failures",
                    )
                    assertAll(
                        { assertEquals(STALE_REJECT_ROUNDS / 2, checkFirst, "rig: half the rounds checked the stale Reject first, naming the latest Hello") },
                        { assertEquals(STALE_REJECT_ROUNDS / 2, installFirst, "rig: half installed the flight first, so the check named no latest Hello") },
                        { assertEquals(STALE_REJECT_ROUNDS / 2, reachedGap, "rig: every check-first caller was running while the gap was held") },
                        { assertEquals(0, inGap, "no caller installs its flight between the check and the take: $failures") },
                        { assertEquals(0, settledByStale, "a stale Reject settles no caller's flight: $failures") },
                        { assertEquals(STALE_REJECT_ROUNDS, honest, "the honest Welcome settled every round's call") },
                    )
                } finally {
                    rig.room.rejectCheckedForTest = null
                    rig.close()
                    callerScope.cancel()
                }
            }
            }
        }

    /** One round's stale `Reject`, as the hook saw it. Written on the frame thread, read after it. */
    private class StaleRejectGap {
        @Volatile var answersLatest: Boolean? = null

        @Volatile var onCheck: (() -> Unit)? = null

        @Volatile var call: Deferred<ResumeResult.JoinerOutcome>? = null

        @Volatile var installedInGap = false

        @Volatile var reachedGap = false
    }

    /**
     * A joiner room the probe plays the host for. Its first Hello is broadcast and every later one
     * is sent to the host alone; both land in [hellos]. [framesHandled] counts the frames the room's
     * collector has finished handling, and [proofCount] the Proofs the joiner sent.
     */
    private class JoinerRig(dispatcher: CoroutineDispatcher, device: RoomDevice) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        // Bounded, as every Swatch delivery channel is (forbidUnboundedSwatchDelivery): a round sends a few frames.
        val inbound = Channel<Swatch>(capacity = 64)
        val framesHandled = MutableStateFlow(0)
        val hellos = MutableStateFlow<List<AdmitMessage.Hello>>(emptyList())
        val proofCount = MutableStateFlow(0)
        private val ticks = AtomicLong(1_000_000)

        private val seam = object : Seam {
            override val selfId = joiner
            override val peers = MutableStateFlow(setOf(joiner, host))
            override val state = MutableStateFlow<SeamState>(SeamState.Woven)
            override val incoming = flow {
                for (frame in inbound) {
                    emit(frame)
                    framesHandled.update { it + 1 }
                }
            }
            override suspend fun broadcast(payload: ByteArray) = record(payload)
            override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                if (peer == host) record(payload)
            }
            private fun record(payload: ByteArray) {
                when (val message = AdmitMessage.decode(payload)) {
                    is AdmitMessage.Hello -> hellos.update { it + message }
                    is AdmitMessage.Proof -> proofCount.update { it + 1 }
                    else -> Unit
                }
            }
            override suspend fun close(reason: CloseReason) = Unit
        }

        val room = SeamRoom(
            seam = seam,
            role = SessionRole.Joiner,
            memberName = "joiner",
            scope = scope,
            clock = { Instant.fromEpochMilliseconds(ticks.incrementAndGet()) },
            heartbeatConfig = HeartbeatConfig(interval = 1.seconds, timeout = 100_000.hours, resumeTimeout = 100_000.hours),
            roomId = null,
            device = device,
            reweave = { seam },
        )

        /**
         * The host's `Welcome` to the joiner when its seat is held, answering [hello] and naming the
         * membership the joiner was admitted under (#3215). Answering a Hello older than the latest,
         * it is the stale copy.
         */
        fun restoredWelcome(hello: AdmitMessage.Hello) = AdmitMessage.Welcome(
            assignedPeerId = joiner.value,
            displayName = "joiner",
            sessionId = joiner.value,
            key = TEST_KEY_A,
            roomId = "room-1",
            returnKind = ReturnKind.Restored,
            answers = hello.joinerNonce,
            membership = "membership-1",
        )

        /** The host challenges the joiner's broadcast Hello and admits it. */
        suspend fun admit(stage: StageTracker) {
            room.start()
            stage.at("setup: the joiner says Hello") { snapshot() }
            val hello = hellos.first { it.isNotEmpty() }.first()
            inbound.send(Swatch(AdmitMessage.encode(handChallenge(hello, joiner, host)), sender = host))
            stage.at("setup: the joiner answers the challenge") { snapshot() }
            proofCount.first { it > 0 }
            inbound.send(Swatch(AdmitMessage.encode(restoredWelcome(hello).copy(returnKind = null)), sender = host))
            stage.at("setup: the joiner is admitted") { snapshot() }
            framesHandled.first { it >= 2 }
            checkNotNull(room.roomId.value) { "the joiner was admitted" }
        }

        fun snapshot(): String =
            "handled=${framesHandled.value} hellos=${hellos.value.size} proofs=${proofCount.value} pending=${room.hasPendingResume()}"

        suspend fun close() {
            room.leave()
            scope.cancel()
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
            if (peerId == carol) return
            episode.value = at
            scope.launch { flow.emit(JoinerReconnectEvent.WindowOpened(peerId, deadlineFor(at), at)) }
        }

        fun deadlineFor(at: Long): Long = at + windowMs + 1

        override suspend fun tryResume(resume: ProvenResume, at: Long): ResumeResult.HostVerdict = onAsk(resume, at)

        override fun onPeerRecovered(peerId: PeerId, at: Long) {
            if (peerId == carol) return
            recoveredCount.value = recovered.incrementAndGet()
        }

        override fun expire(peerId: PeerId, at: Long) = Unit
    }

    private class Rig(
        dispatcher: CoroutineDispatcher,
        private val interval: Duration,
        device: RoomDevice,
        /** How long a detector waits on a partitioned peer before its `PeerLost`. */
        detectorWindow: Duration = 100_000.hours,
    ) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val peers = MutableStateFlow(setOf(host, bob, carol))
        // Bounded, as every Swatch delivery channel is (forbidUnboundedSwatchDelivery): a round sends a few frames.
        val inbound = Channel<Swatch>(capacity = 64)
        /** Each joiner id's challenges, in arrival order; an id a round makes up gets its channel on first use. */
        private val challenges = java.util.concurrent.ConcurrentHashMap<PeerId, Channel<AdmitMessage.Challenge>>()

        fun challengesFor(peer: PeerId): Channel<AdmitMessage.Challenge> =
            challenges.computeIfAbsent(peer) { Channel(Channel.UNLIMITED) }

        /** Forget every challenge no exchange took, so a later round reads only its own. */
        fun drainChallenges() {
            challenges.values.forEach { channel -> do { val left = channel.tryReceive() } while (left.isSuccess) }
        }

        /**
         * A Hello from [peer] under bob's key, and the challenge it drew — or null when the host
         * answered it with `Farewell(supersededBy)` instead, as it does a superseded id (#3159, Task 1.7).
         *
         * Decided by whichever answer arrives first, never by a clock. The Hello gate tells [peer] only
         * once its successor holds the seat, and the takeover has already sent [peer] its own
         * `Farewell` by then, so the gate's answer is the second: [TOLD_BY_GATE]. A Hello challenged
         * before the takeover sees the takeover's `Farewell` too, but never a second one until it
         * proves, so one `Farewell` alone decides nothing.
         */
        suspend fun helloOrTold(peer: PeerId): AdmitMessage.Challenge? = coroutineScope {
            inbound.send(Swatch(AdmitMessage.encode(currentHello(peer.value, sessionId = peer.value, key = TEST_KEY_A)), sender = peer))
            val told = async { sent.first { supersededFarewellsTo(peer) >= TOLD_BY_GATE } }
            select<AdmitMessage.Challenge?> {
                challengesFor(peer).onReceive { it }
                told.onAwait { null }
            }.also { told.cancel() }
        }

        fun supersededFarewellsTo(peer: PeerId): Int =
            sent.value.count { (to, m) -> to == peer && m is AdmitMessage.Farewell && m.peerId == peer.value && m.supersededBy != null }

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

        /** A joiner id whose challenges the host is slow to send, by [SLOW_CHALLENGE]; null for none. */
        @Volatile
        var slowChallengeTo: PeerId? = null

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
                    if (peer == slowChallengeTo) delay(SLOW_CHALLENGE)
                    challengesFor(peer).send(message)
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
            return challengesFor(peer).receive()
        }

        /** Rig retries: a drop the detector could not see yet, or an exchange the room never answered. */
        val retries = AtomicInteger(0)

        /**
         * Bob's `Resumed` or `Joined` this round. An exchange that draws nothing is sent again: the
         * room forgets the open challenge of a sender that leaves the fabric, so a Hello it opened
         * before its watcher of the fabric read bob's drop goes with the drop. One opened after that
         * read survives it (#3610). A second exchange makes the first stale, so it seats nobody. A rig
         * retry, counted.
         */
        suspend fun awaitEdge(peer: PeerId = bob): MembershipEvent {
            while (true) {
                val seen = withTimeoutOrNull(STALL_GRACE) { events.first { events -> events.any { it.isEdgeOf(peer) } } }
                if (seen != null) return seen.first { it.isEdgeOf(peer) }
                retries.incrementAndGet()
                prove(peer, TEST_DEVICE_A, TEST_KEY_A)
            }
        }

        /**
         * Bob seated this round, by a restore or a readmission: the Proof's burst has ended with a
         * host intro beyond [introsBefore]. A restore of a seat a heal already reconnected announces
         * nothing (#3215), so the burst is the one witness both outcomes share. An exchange that draws
         * nothing is sent again, as [awaitEdge] does. A rig retry, counted.
         */
        suspend fun awaitSeating(peer: PeerId, introsBefore: Int) {
            while (true) {
                val seated = withTimeoutOrNull(STALL_GRACE) { awaitIntros(peer, introsBefore + 1) }
                if (seated != null) return
                retries.incrementAndGet()
                prove(peer, TEST_DEVICE_A, TEST_KEY_A)
            }
        }

        /** Hello, then the Proof answering its challenge; [beforeProof] runs once the challenge is in. */
        suspend fun prove(peer: PeerId, device: DeviceKey, key: DidKey, beforeProof: suspend () -> Unit = {}) {
            inbound.send(Swatch(AdmitMessage.encode(currentHello(peer.value, sessionId = peer.value, key = key)), sender = peer))
            val challenge = challengesFor(peer).receive()
            beforeProof()
            inbound.send(Swatch(AdmitMessage.encode(handProof(challenge, host, device)), sender = peer))
        }

        /**
         * Start a round: forget the last one's frames and events, drop bob from the fabric, wait until
         * the host holds his seat partitioned under the controller's window, and put him back. Returns
         * the episode.
         */
        suspend fun partitionBob(stage: StageTracker, round: Int): Long = partition(stage, round, bob)

        /** [partitionBob] for whichever id holds the key's seat this round. */
        suspend fun partition(stage: StageTracker, round: Int, bob: PeerId): Long {
            sent.value = emptyList()
            events.value = emptyList()
            val previous = controller.episode.value
            peers.update { it - bob }
            stage.at("round $round: ${bob.value} partitions") { snapshot() }
            while (withTimeoutOrNull(STALL_GRACE) { events.first { events -> events.any { it.isPartitionOf(bob) } } } == null) {
                // A detector notices a peer leave only once it has seen the peer listed, and a new one
                // looks first when it starts: dropped before then, bob is never partitioned. List him
                // again until the detector has looked, then drop him again. A rig retry, counted.
                retries.incrementAndGet()
                peers.update { it + bob }
                delay(interval + 50.milliseconds)
                peers.update { it - bob }
            }
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
        val joiner = PeerId("joiner")
        val bob = PeerId("bob")
        val carol = PeerId("carol")
        val sentinel = PeerId("sentinel")

        // A supersession round's new ids are made up per round (`bob-heal-<n>`, `bob-race-<n>-a`, …): an
        // id superseded in one round is told so if it says Hello again (#3159, Task 1.7), so no round
        // may bring one back as a new process.
        const val SUPERSEDE_ROUNDS = 5_000
        const val REHELLO_ROUNDS = 1_000

        /** Every this many re-Hello rounds, the host challenges the old id [SLOW_CHALLENGE] late. */
        const val SLOW_CHALLENGE_EVERY = 100

        /** Slower than a rig waiting on a clock for its challenge would wait. */
        val SLOW_CHALLENGE = 1.seconds
        const val STALE_WELCOME_ROUNDS = 20_000

        /** Even, so the two forced orderings split it exactly. */
        const val STALE_REJECT_ROUNDS = 1_000

        /** How long a check-first round gives the caller to install its flight inside the gap: 20 ms. */
        const val STALE_REJECT_GAP_NANOS = 20_000_000L

        /** How long a check-first round waits for the caller to start running before the gap: 1 s. */
        const val STALE_REJECT_ENTER_NANOS = 1_000_000_000L

        /** Up to about as long as the room takes to handle a frame, so the two arrivals overlap. */
        const val STALE_WELCOME_SPIN_NANOS = 20_000L

        /** The superseded `Farewell`s an old id has had once the Hello gate told it: the takeover's, then the gate's. */
        const val TOLD_BY_GATE = 2
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

        /** How long a round waits on the room before it treats the wait as a rig miss and retries. */
        val STALL_GRACE = 3.seconds

        fun MembershipEvent.isPartitionOf(peer: PeerId): Boolean = this is MembershipEvent.Partitioned && peerId == peer

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
