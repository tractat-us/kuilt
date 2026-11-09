@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session.partition

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.coroutines.CompletableDeferred
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
                { assertEquals(1, seam.broadcasts, "precondition: the first Resume went out") },
                { assertTrue(machine.hasPendingFlight(), "precondition: the first Resume is in flight") },
            )
            return checkNotNull(machine.takePendingFlight()) { "the flight vanished before the ack took it" }
        }

        fun assertResumedOnce() = assertAll(
            { assertEquals(1, reweaves(), "the ack's Success must end the episode with no second re-weave") },
            { assertEquals(1, seam.broadcasts, "no second Resume may go out after the ack's Success") },
            { assertEquals(1, host.restored, "the episode must end on the success branch") },
            { assertEquals(emptyList(), host.failures, "a resumed room must not go host-lost") },
            { assertFalse(machine.hasPendingFlight(), "no second flight may be in flight") },
        )
    }

    private fun TestScope.rig(): Rig {
        val seam = FlightSeam(joinerId)
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
        machine.mintTokenIfAbsent("room-1")
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
 * counts broadcasts, which in these tests are only ever the joiner's `Resume`.
 */
private class FlightSeam(override val selfId: PeerId) : Seam {
    private val _state = MutableStateFlow<SeamState>(SeamState.Torn(CloseReason.Normal))
    override val state: StateFlow<SeamState> = _state.asStateFlow()
    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId)).asStateFlow()
    override val incoming: Flow<Swatch> = emptyFlow()

    var broadcasts: Int = 0
        private set

    fun heal() {
        _state.value = SeamState.Woven
    }

    fun tear() {
        _state.value = SeamState.Torn(CloseReason.Normal)
    }

    override suspend fun broadcast(payload: ByteArray) {
        broadcasts++
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
    override suspend fun close(reason: CloseReason) = Unit
}
