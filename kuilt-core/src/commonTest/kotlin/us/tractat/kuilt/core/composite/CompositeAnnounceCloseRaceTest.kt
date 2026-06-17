package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
import kotlin.test.Test

/**
 * Regression for #535. A ply that reaches [SeamState.Woven] and is then torn down
 * throws [IllegalStateException] from `broadcast` (the documented `Seam` contract:
 * "either call when Torn throws IllegalStateException"). The composite's per-ply
 * announce pump must treat that announce as a best-effort fabric send and swallow
 * the failure — otherwise it surfaces as an uncaught coroutine exception on the
 * composite's own (test-outliving) scope and `runTest` reports it as
 * `UncaughtExceptionsBeforeTest` against whatever test runs next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeAnnounceCloseRaceTest {
    @Test
    fun wovenPlyThatThrowsOnAnnounceDoesNotLeakUncaughtException() = runTest {
        val loom = CompositeLoom(
            listOf(PlyId("torn") to TornOnBroadcastLoom()),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        // Weaving attaches the ply, whose Woven state drives the announce pump to call
        // broadcast — which throws. Before the fix this leaks an uncaught exception.
        loom.host(Pattern("host"))
    }

    /** A [Loom] whose woven [Seam] reports [SeamState.Woven] but throws on every send. */
    private class TornOnBroadcastLoom : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = TornOnBroadcastSeam()
        override fun availability(): FabricAvailability = FabricAvailability.Available
    }

    private class TornOnBroadcastSeam : Seam {
        override val selfId: PeerId = PeerId("torn-self")
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId)).asStateFlow()
        override val state: StateFlow<SeamState> = MutableStateFlow<SeamState>(SeamState.Woven).asStateFlow()
        override val incoming: Flow<Swatch> = emptyFlow()

        override suspend fun broadcast(payload: ByteArray): Unit =
            throw IllegalStateException("Seam for $selfId is closed")

        override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit =
            throw IllegalStateException("Seam for $selfId is closed")

        override suspend fun close(reason: CloseReason) = Unit
    }
}
