@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session.partition

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A throwaway seam that refuses to close must not swallow the machine's terminal bookkeeping (#2286).
 *
 * A non-conforming `Loom` answers `reweave` with an unrelated seam and leaves ours `Torn`, so there is
 * nothing to resume onto. [JoinerResumeMachine] closes that throwaway (else a live connection leaks)
 * and *then* goes terminal — `failureReason = Unrecoverable`, out of the window, into
 * `JoinerResumeHost.onReconnectFailed`. Guarding the close with `runCatchingCancellable` re-throws a
 * `CancellationException` the throwaway minted itself, which escapes `withTimeoutOrNull` and cancels
 * the reconnect coroutine outright: the assignment, the return and the whole terminal tail are skipped,
 * and the machine simply stops — no `HostLost`, no reason, nothing in the log. The room is left waiting
 * on a resume that will never complete.
 */
class ThrowawaySeamCloseCancellationTest {

    private val joinerId = PeerId("joiner")
    private val hostId = PeerId("host")

    private val config = HeartbeatConfig(
        interval = 50.milliseconds,
        timeout = 500.milliseconds,
        reconnectWindow = 2.seconds,
    )

    @Test
    fun `a throwaway seam whose close mints a cancellation must still report Unrecoverable`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val torn = StubSeam(joinerId, initial = SeamState.Torn(CloseReason.Normal))
            val throwaway = StubSeam(
                joinerId,
                onClose = { throw CancellationException("close timed out inside the throwaway seam") },
            )
            val host = RecordingResumeHost(hostId)
            val machine = JoinerResumeMachine(
                seam = torn,
                scope = backgroundScope,
                clock = { Instant.fromEpochMilliseconds(0) },
                heartbeatConfig = config,
                reweave = { throwaway },
                lock = ReentrantLock(),
                host = host,
            )
            machine.mintTokenIfAbsent("room-1")

            machine.attemptReconnect(Instant.fromEpochMilliseconds(0))
            runCurrent()

            assertAll(
                { assertTrue(throwaway.closeAttempted, "the throwaway seam must be closed, not leaked") },
                {
                    assertEquals(
                        listOf<FailureReason>(FailureReason.Unrecoverable),
                        host.failures,
                        "a close that refuses must not skip the machine's terminal bookkeeping",
                    )
                },
            )
        }
}

/** Records the terminal callbacks [JoinerResumeMachine] owes its room; every other member is inert. */
private class RecordingResumeHost(private val hostPeer: PeerId) : JoinerResumeHost {
    val failures: MutableList<FailureReason> = mutableListOf()

    override fun hostPeer(): PeerId = hostPeer
    override fun isTerminal(): Boolean = false
    override fun isClosed(): Boolean = false
    override fun silenceHostDetector(hostId: PeerId) = Unit
    override fun restoreHostDetector(hostId: PeerId) = Unit
    override fun restartIncomingCollect() = Unit
    override fun onReconnectStarted(hostId: PeerId, at: Instant, windowDeadline: Instant) = Unit
    override fun onNoOpResume(hostId: PeerId, at: Instant) = Unit
    override suspend fun onReconnectFailed(at: Instant, reason: FailureReason) {
        failures += reason
    }
}

/** A [Seam] pinned to one state whose [close] runs [onClose] — the only member carrying behaviour. */
private class StubSeam(
    override val selfId: PeerId,
    initial: SeamState = SeamState.Woven,
    private val onClose: suspend () -> Unit = {},
) : Seam {
    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId)).asStateFlow()
    override val state: StateFlow<SeamState> = MutableStateFlow(initial).asStateFlow()
    override val incoming: Flow<Swatch> = emptyFlow()

    var closeAttempted: Boolean = false
        private set

    override suspend fun broadcast(payload: ByteArray) = Unit
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit

    override suspend fun close(reason: CloseReason) {
        closeAttempted = true
        onClose()
    }
}
