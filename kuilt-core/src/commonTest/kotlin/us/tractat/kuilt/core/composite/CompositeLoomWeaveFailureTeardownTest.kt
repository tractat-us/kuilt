package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `CompositeLoom.weave`'s failure teardown must close EVERY already-woven ply, and must surface the real
 * weave failure — even when one ply's `close()` mints a `CancellationException` of its own (#1803).
 *
 * `Seam.close` now carries the same "must not report failure as cancellation" obligation as
 * `sendTo`/`broadcast`/`Loom.weave` (#1826), so a ply whose `close` bounds teardown with a bare
 * `withTimeout` is **non-conforming**. That does not weaken this test — it strengthens why it exists: a
 * library cannot trust a consumer, so a `TimeoutCancellationException` out of one ply's `close` is exactly
 * the hostile input `CompositeLoom.weave`'s teardown must survive without leaking its siblings.
 *
 * Before the fix the teardown guarded each ply with `runCatchingCancellable`, which rethrows any
 * `CancellationException`. Inside `withContext(NonCancellable)` that can only ever be callee-minted — our
 * own job is not cancellable there — so the rethrow escaped the shield and cost two things at once: the
 * remaining plies were never closed (leaking their transports), and the caller received the masquerading
 * cancellation *instead of* the real failure, which cancels the caller rather than failing it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeLoomWeaveFailureTeardownTest {

    @Test
    fun weaveFailureClosesEveryWovenPlyAndSurfacesTheRealFailure() = runTest {
        // Ply A closes by minting a TimeoutCancellationException — a bounded teardown, contract-legal.
        val plyA = RecordingSeam(PeerId("A"), closeMintsCancellation = true)
        // Ply B is ordinary. Pre-fix it was never closed: A's rethrow aborted the loop.
        val plyB = RecordingSeam(PeerId("B"), closeMintsCancellation = false)
        // Ply C fails to weave at all, which is what triggers the teardown of A and B.
        val boom = RuntimeException("boom")

        val desired = listOf(
            PlyId("a") to FakeLoom(plyA),
            PlyId("b") to FakeLoom(plyB),
            PlyId("c") to FakeLoom(seam = null, weaveFailure = boom),
        )
        val loom = CompositeLoom(MutableStateFlow(desired), UnconfinedTestDispatcher())

        val thrown = runCatching { loom.host(Pattern("t")) }.exceptionOrNull()

        assertAll(
            {
                assertEquals(
                    boom,
                    thrown,
                    "expected the real weave failure, got: $thrown",
                )
            },
            { assertTrue(plyA.closed, "ply A must be closed") },
            {
                assertTrue(
                    plyB.closed,
                    "ply B must still be closed after ply A's close() minted a CancellationException",
                )
            },
        )
    }

    private class FakeLoom(
        private val seam: Seam?,
        private val weaveFailure: Throwable? = null,
    ) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            weaveFailure?.let { throw it }
            return requireNotNull(seam) { "FakeLoom needs a seam or a weaveFailure" }
        }
    }

    private class RecordingSeam(
        override val selfId: PeerId,
        private val closeMintsCancellation: Boolean,
    ) : Seam {
        var closed: Boolean = false
            private set

        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
        override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
        override val plies: StateFlow<Map<PlyId, SeamState>> = MutableStateFlow(emptyMap())
        override val capability: StateFlow<TransportCapability> =
            MutableStateFlow(TransportCapability(roles = setOf(TransportRole.Data), availability = FabricAvailability.Available))
        override val incoming: Flow<Swatch> = emptyFlow()

        override suspend fun broadcast(payload: ByteArray) = Unit

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit

        override suspend fun close(reason: CloseReason) {
            closed = true
            if (closeMintsCancellation) {
                // A bounded teardown that overruns: throws TimeoutCancellationException TO ITS CALLER
                // without cancelling the caller's job. This is the whole hazard.
                withTimeout(1.milliseconds) { delay(1.seconds) }
            }
        }
    }
}
