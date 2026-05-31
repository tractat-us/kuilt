package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.composite.CompositeLoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Multi-ply composite behaviours: rollup, exactly-once dedup, and no-flap membership.
 *
 * Uses two [DelayedWovenLoom] plies bonded under one [CompositeLoom], driving
 * [DelayedWovenSeam.markWoven] explicitly to control per-ply lifecycle transitions.
 *
 * [UnconfinedTestDispatcher] is injected so reconciliation coroutines run eagerly
 * inside [runTest]'s virtual clock, making synchronous `.value` reads deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeMultiPlyTest {

    private fun makeLoom(vararg plies: Pair<PlyId, DelayedWovenLoom>) =
        CompositeLoom(
            plies = plies.toList(),
            dispatcher = UnconfinedTestDispatcher(),
        )

    @Test
    fun aggregateWovenWhenOnlyOnePlyWoven() = runTest {
        val plyA = DelayedWovenLoom()
        val plyB = DelayedWovenLoom()
        val loom = makeLoom(PlyId("a") to plyA, PlyId("b") to plyB)
        val seam = loom.host(Pattern("host"))
        assertIs<SeamState.Weaving>(seam.state.value)

        // Mark only ply A woven; aggregate must reach Woven immediately.
        plyA.wovenSeams.single().markWoven()

        assertIs<SeamState.Woven>(seam.state.first { it is SeamState.Woven })
        assertEquals(SeamState.Woven, seam.plies.value[PlyId("a")])
        assertEquals(SeamState.Weaving, seam.plies.value[PlyId("b")])
    }

    @Test
    fun frameOverTwoSharedPliesIsDeliveredExactlyOnce() = runTest {
        // Both plies shared by host and joiner: a broadcast goes over both plies.
        // The inbound gate must deduplicate, delivering exactly one copy.
        val plyA = DelayedWovenLoom()
        val plyB = DelayedWovenLoom()
        val loom = makeLoom(PlyId("a") to plyA, PlyId("b") to plyB)
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))

        // Mark all per-ply seams woven so identity reconciliation completes.
        plyA.wovenSeams.forEach { it.markWoven() }
        plyB.wovenSeams.forEach { it.markWoven() }

        // Wait for peers to be reconciled (Announce exchange must complete).
        host.peers.first { it.size == 2 }

        host.broadcast(byteArrayOf(5))

        val received = joiner.incoming.take(1).toList()
        assertEquals(1, received.size, "exactly one delivery despite two plies carrying it")
        assertEquals(5, received.single().payload.single())
    }

    @Test
    fun onePlyTearingDoesNotRemoveAPeerStillOnAnother() = runTest {
        val plyA = DelayedWovenLoom()
        val plyB = DelayedWovenLoom()
        val loom = makeLoom(PlyId("a") to plyA, PlyId("b") to plyB)
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))

        plyA.wovenSeams.forEach { it.markWoven() }
        plyB.wovenSeams.forEach { it.markWoven() }

        // Wait for both peers to be fully reconciled on both plies.
        val peers = host.peers.first { it.size == 2 }
        assertEquals(2, peers.size)

        // Tear the joiner's plyB link; the joiner is still reachable on plyA.
        // plyB.wovenSeams contains seams for both host and joiner. Close the joiner's
        // (the second one weaved on plyB).
        plyB.wovenSeams.last().close(CloseReason.RemoteRequested)

        // Membership must stay at 2 (no flap) and aggregate must stay Woven.
        assertEquals(2, host.peers.value.size, "joiner still reachable via plyA")
        assertIs<SeamState.Woven>(host.state.value, "aggregate stays Woven when one ply tears")
    }
}
