@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session.partition

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Once the host's `ResumeAck` has taken a resume flight, that flight's verdict is the ack's, whatever
 * the joiner's own tear or deadline path sees afterwards (#3155 review).
 *
 * The room's ack handler takes the flight under the lock, then emits `Resumed` and completes the
 * flight **outside** it. On a multi-threaded dispatcher the seam can tear, or the `resumeTimeout`
 * deadline can fire, between those two steps. A tear or deadline path that settles the flight as
 * `TimedOut` regardless would then report a failed attempt for a room that has already announced
 * `Resumed`; the reconnect loop retries, and the room fires a second `Resumed` or a contradictory
 * `HostLost`.
 *
 * A test dispatcher cannot split the ack handler, which has no suspension point, so these tests play
 * its two halves by hand: [JoinerResumeMachine.takePendingFlight] is the ack taking the flight, and
 * completing the taken deferred with [ResumeResult.Success] is the ack finishing. The tear or the
 * deadline is forced between them.
 */
class ResumeFlightOwnershipTest {

    private val joinerId = PeerId("joiner")
    private val hostId = PeerId("host")

    private val config = HeartbeatConfig(
        interval = 50.milliseconds,
        timeout = 500.milliseconds,
        reconnectWindow = 2.seconds,
    )

    @Test
    fun `a tear after the ack took the flight reports the ack Success and does not retry`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val flight = rig.ackTakesTheFlight()

            rig.seam.tear()
            runCurrent()
            flight.complete(ResumeResult.Success)
            runCurrent()
            advanceTimeBy(config.interval.inWholeMilliseconds * 2)
            runCurrent()

            rig.assertResumedOnce()
        }

    @Test
    fun `a deadline after the ack took the flight reports the ack Success and does not retry`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val flight = rig.ackTakesTheFlight()

            advanceTimeBy(config.resumeTimeout.inWholeMilliseconds + 1)
            runCurrent()
            flight.complete(ResumeResult.Success)
            runCurrent()
            advanceTimeBy(config.interval.inWholeMilliseconds * 2)
            runCurrent()

            rig.assertResumedOnce()
        }

    /**
     * A generation that tears with the `Resume` already sent and no verdict back settles the flight
     * at once, and the loop re-weaves without waiting out `resumeTimeout` (#3151).
     *
     * `DrainingGenerationReconnectTest` used to pin this, but since a `Resume` goes to the host alone
     * (#3165) the draining generation never lists the host, so its flight never gets as far as
     * sending and that test now exercises the wait before the send instead. Here the host stays
     * listed, so the frame does go out and only the post-send tear arm can settle it.
     */
    @Test
    fun `a tear with the sent flight still pending settles it at once and re-weaves`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            rig.machine.attemptReconnect(Instant.fromEpochMilliseconds(0))
            runCurrent()
            val sentBeforeTear = rig.seam.resumesSent
            val pendingBeforeTear = rig.machine.hasPendingFlight()

            rig.seam.tear()
            runCurrent()

            assertAll(
                { assertEquals(1, sentBeforeTear, "precondition: the Resume went out") },
                { assertTrue(pendingBeforeTear, "precondition: its verdict had not come back") },
                { assertEquals(2, rig.reweaves(), "the tear must re-weave at once, not after resumeTimeout") },
                { assertEquals(2, rig.seam.resumesSent, "and resume again over the healed generation") },
            )
        }

    /**
     * A caller that joins a flight whose owner is still waiting for its host to be addressable
     * starts its reply deadline from the send, not from the moment it joined (#3165 review).
     *
     * The owner waits up to `resumeTimeout` before it can send at all, so a joined caller timing from
     * its join could settle the shared flight `TimedOut` after the host had spent the window but
     * before the ack arrived, and every later attempt would be refused. Here the joined caller's old
     * deadline (join + `resumeTimeout`) falls between the send and the ack.
     */
    @Test
    fun `a caller joining a flight that waits for its host times its reply from the send`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val seam = FlightSeam(joinerId, hostId, hostListed = false).also { it.heal() }
            val machine = JoinerResumeMachine(
                seam = seam,
                scope = backgroundScope,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                heartbeatConfig = config,
                reweave = null,
                lock = ReentrantLock(),
                host = FlightResumeHost(hostId),
            )
            machine.mintTokenIfAbsent("room-1", ResumeSecret.generate())
            val token = assertNotNull(machine.resumeToken)
            val bound = config.resumeTimeout.inWholeMilliseconds

            val owner = async { machine.resume(token) }
            runCurrent()
            advanceTimeBy(bound / 2)
            val joined = async { machine.resume(token) }
            runCurrent()
            advanceTimeBy(bound / 2 - 10)
            seam.listHost() // the owner sends now, at about one bound in
            runCurrent()
            val sent = seam.resumesSent
            advanceTimeBy(bound / 2 + 20) // past join + bound, short of send + bound
            runCurrent()
            val stillPending = machine.hasPendingFlight()
            machine.takePendingFlight()?.complete(ResumeResult.Success)
            runCurrent()
            val ownerResult = owner.await()
            val joinedResult = joined.await()

            assertAll(
                { assertEquals(1, sent, "precondition: the owner sent once the host appeared") },
                { assertTrue(stillPending, "the joined caller must not settle the flight before its send + bound") },
                { assertEquals(ResumeResult.Success, ownerResult) },
                { assertEquals(ResumeResult.Success, joinedResult) },
            )
        }

    private class Rig(
        val seam: FlightSeam,
        val host: FlightResumeHost,
        val machine: JoinerResumeMachine,
        val reweaves: () -> Int,
        val scope: TestScope,
    ) {
        /**
         * Starts the reconnect, lets it send its `Resume`, and takes the pending flight as the room's
         * ack handler would — leaving the flight taken but not yet completed.
         */
        fun ackTakesTheFlight(): CompletableDeferred<ResumeResult.JoinerOutcome> {
            machine.attemptReconnect(Instant.fromEpochMilliseconds(0))
            scope.runCurrent()
            assertAll(
                { assertEquals(1, reweaves(), "precondition: one re-weave before the first Resume") },
                { assertEquals(1, seam.resumesSent, "precondition: the first Resume went out") },
                { assertTrue(machine.hasPendingFlight(), "precondition: the first Resume is in flight") },
            )
            return checkNotNull(machine.takePendingFlight()) { "the flight vanished before the ack took it" }
        }

        fun assertResumedOnce() = assertAll(
            { assertEquals(1, reweaves(), "the ack's Success must end the episode with no second re-weave") },
            { assertEquals(1, seam.resumesSent, "no second Resume may go out after the ack's Success") },
            { assertEquals(1, host.restored, "the episode must end on the success branch") },
            { assertEquals(emptyList(), host.failures, "a resumed room must not go host-lost") },
            { assertFalse(machine.hasPendingFlight(), "no second flight may be in flight") },
        )
    }

    private fun TestScope.rig(): Rig {
        val seam = FlightSeam(joinerId, hostId)
        val host = FlightResumeHost(hostId)
        var reweaves = 0
        val machine = JoinerResumeMachine(
            seam = seam,
            scope = backgroundScope,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            heartbeatConfig = config,
            reweave = {
                reweaves++
                seam.heal()
                seam
            },
            lock = ReentrantLock(),
            host = host,
        )
        machine.mintTokenIfAbsent("room-1", ResumeSecret.generate())
        return Rig(seam, host, machine, { reweaves }, this)
    }
}

/** Records the callbacks that tell a resumed episode from a failed one; every other member is inert. */
private class FlightResumeHost(private val hostPeer: PeerId) : JoinerResumeHost {
    val failures: MutableList<FailureReason> = mutableListOf()
    var restored: Int = 0
        private set

    override fun hostPeer(): PeerId = hostPeer
    override fun isTerminal(): Boolean = false
    override fun isClosed(): Boolean = false
    override fun silenceHostDetector(hostId: PeerId) = Unit
    override fun restoreHostDetector(hostId: PeerId) {
        restored++
    }
    override fun restartIncomingCollect() = Unit
    override fun onReconnectStarted(hostId: PeerId, at: Instant, windowDeadline: Instant) = Unit
    override fun onNoOpResume(hostId: PeerId, at: Instant) = Unit
    override suspend fun onReconnectFailed(at: Instant, reason: FailureReason) {
        failures += reason
    }
}

/**
 * A seam that starts torn, is healed in place by the re-weave, and can be torn again on demand. It
 * counts sends to the host, which in these tests are only ever the joiner's `Resume`.
 */
private class FlightSeam(
    override val selfId: PeerId,
    private val hostId: PeerId,
    hostListed: Boolean = true,
) : Seam {
    private val _state = MutableStateFlow<SeamState>(SeamState.Torn(CloseReason.Normal))
    override val state: StateFlow<SeamState> = _state.asStateFlow()
    private val _peers = MutableStateFlow(if (hostListed) setOf(selfId, hostId) else setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    /** The host becomes addressable on this seam. */
    fun listHost() {
        _peers.value = setOf(selfId, hostId)
    }
    override val incoming: Flow<Swatch> = emptyFlow()

    var resumesSent: Int = 0
        private set

    fun heal() {
        _state.value = SeamState.Woven
    }

    fun tear() {
        _state.value = SeamState.Torn(CloseReason.Normal)
    }

    override suspend fun broadcast(payload: ByteArray) {
        error("a Resume must never be broadcast (#3165)")
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (peer == hostId) resumesSent++
    }
    override suspend fun close(reason: CloseReason) = Unit
}
