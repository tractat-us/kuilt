package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #542 — a ply that tears between the composite's `Torn` filter and the actual send must not
 * fail a [CompositeSeam.broadcast] that another live ply can carry. Modelled by a ply whose
 * `state` reports [SeamState.Woven] (passes the filter) but whose `broadcast` throws (the
 * `Seam` contract: a send while `Torn` throws [IllegalStateException]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeSendToctouTest {
    @Test
    fun broadcastSucceedsWhenOnePlyTearsMidSendAndAnotherCarriesIt() = runTest {
        val good = InMemoryLoom()
        val loom = CompositeLoom(
            listOf(
                PlyId("good") to good,
                PlyId("bad") to TearOnSendLoom(),
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))
        host.peers.first { it.size == 2 } // reconciliation over the good ply

        // The bad ply throws on send; the good ply must still carry the frame and broadcast
        // must not propagate the bad ply's failure to the caller.
        host.broadcast(byteArrayOf(7, 8, 9))

        val got = withTimeoutOrNull(2_000) { joiner.incoming.first() }
        assertNotNull(got, "good ply should have delivered the frame despite the bad ply tearing")
        assertTrue(byteArrayOf(7, 8, 9).contentEquals(got.payload))
    }

    /** A [Loom] whose woven [Seam] reports [SeamState.Woven] but throws on every send. */
    private class TearOnSendLoom : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = TearOnSendSeam()
        override fun availability(): FabricAvailability = FabricAvailability.Available
    }

    private class TearOnSendSeam : Seam {
        override val selfId: PeerId = PeerId("bad-self")
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
