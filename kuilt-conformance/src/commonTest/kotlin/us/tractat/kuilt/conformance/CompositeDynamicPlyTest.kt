package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.composite.CompositeLoom
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Dynamic ply attach/detach behaviour: a changing desired-set [MutableStateFlow]
 * drives plies in and out of a live composite session.
 *
 * Shared in-memory plies (one [InMemoryLoom] per [PlyId]) are referenced by both
 * host and joiner desired sets, so attaching a ply on both sides bonds them.
 * [UnconfinedTestDispatcher] makes reconciliation and the Announce round-trip
 * settle synchronously for `.value` assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeDynamicPlyTest {

    @Test
    fun attachingAPlyMidSessionAddsItToPliesAndDedupsAcrossBoth() = runTest {
        val relay = InMemoryLoom()
        val overlay = InMemoryLoom()
        val hostDesired = MutableStateFlow(listOf(PlyId("relay") to relay as Loom))
        val joinDesired = MutableStateFlow(listOf(PlyId("relay") to relay as Loom))

        val host = CompositeLoom(hostDesired, UnconfinedTestDispatcher()).host(Pattern("s"))
        val joiner = CompositeLoom(joinDesired, UnconfinedTestDispatcher()).join(InMemoryTag("s"))
        host.peers.first { it.size == 2 }

        // Attach the overlay ply on both sides.
        hostDesired.update { it + (PlyId("overlay") to overlay as Loom) }
        joinDesired.update { it + (PlyId("overlay") to overlay as Loom) }
        host.peers.first { it.size == 2 } // still 2 — same peer over two plies, no double-count

        assertAll(
            { assertEquals(setOf(PlyId("relay"), PlyId("overlay")), host.plies.value.keys) },
            { assertEquals(2, host.peers.value.size, "multi-homed peer counted once") },
        )

        // A broadcast now rides both plies but must be delivered exactly once.
        host.broadcast(byteArrayOf(7))
        val received = joiner.incoming.first()
        assertEquals(7, received.payload.single())
    }

    @Test
    fun detachingAnOverlayKeepsAPeerReachableOnTheRelay() = runTest {
        val relay = InMemoryLoom()
        val overlay = InMemoryLoom()
        val hostDesired = MutableStateFlow(
            listOf(PlyId("relay") to relay as Loom, PlyId("overlay") to overlay as Loom),
        )
        val joinDesired = MutableStateFlow(
            listOf(PlyId("relay") to relay as Loom, PlyId("overlay") to overlay as Loom),
        )
        val host = CompositeLoom(hostDesired, UnconfinedTestDispatcher()).host(Pattern("s"))
        val joiner = CompositeLoom(joinDesired, UnconfinedTestDispatcher()).join(InMemoryTag("s"))
        host.peers.first { it.size == 2 }

        // Detach the overlay on the host side only.
        hostDesired.update { it.filterNot { (id, _) -> id == PlyId("overlay") } }

        assertAll(
            { assertEquals(setOf(PlyId("relay")), host.plies.value.keys, "overlay gone from plies") },
            { assertIs<SeamState.Woven>(host.state.value, "aggregate stays Woven via relay") },
            { assertEquals(2, host.peers.value.size, "joiner still reachable on relay — no flap") },
        )
    }

    @Test
    fun detachingEveryPlyGoesWeavingAndReattachRecoversToWoven() = runTest {
        val relay = InMemoryLoom()
        val desired = MutableStateFlow(listOf(PlyId("relay") to relay as Loom))
        val seam = CompositeLoom(desired, UnconfinedTestDispatcher()).host(Pattern("s"))
        assertIs<SeamState.Woven>(seam.state.value)

        // Detach the only ply: aggregate must go Weaving (recoverable), not Torn.
        desired.update { emptyList() }
        assertAll(
            { assertTrue(seam.plies.value.isEmpty(), "no live plies") },
            { assertIs<SeamState.Weaving>(seam.state.value, "zero plies => Weaving, not Torn") },
        )

        // Re-attach a ply: aggregate recovers to Woven.
        desired.update { listOf(PlyId("relay2") to InMemoryLoom() as Loom) }
        assertAll(
            { assertEquals(setOf(PlyId("relay2")), seam.plies.value.keys) },
            { assertIs<SeamState.Woven>(seam.state.value, "re-attach recovers to Woven") },
        )
    }

    @Test
    fun reAttachingTheSamePlyIdStartsCleanAndStillDelivers() = runTest {
        val relay = InMemoryLoom()
        val overlay = InMemoryLoom()
        val hostDesired = MutableStateFlow(
            listOf(PlyId("relay") to relay as Loom, PlyId("overlay") to overlay as Loom),
        )
        val joinDesired = MutableStateFlow(
            listOf(PlyId("relay") to relay as Loom, PlyId("overlay") to overlay as Loom),
        )
        val host = CompositeLoom(hostDesired, UnconfinedTestDispatcher()).host(Pattern("s"))
        val joiner = CompositeLoom(joinDesired, UnconfinedTestDispatcher()).join(InMemoryTag("s"))
        host.peers.first { it.size == 2 }

        // Detach then re-attach the same PlyId on the host side.
        hostDesired.update { it.filterNot { (id, _) -> id == PlyId("overlay") } }
        assertEquals(setOf(PlyId("relay")), host.plies.value.keys)
        hostDesired.update { it + (PlyId("overlay") to overlay as Loom) }

        // Mapping rebuilt via a fresh Announce; delivery still works exactly once.
        host.peers.first { it.size == 2 }
        host.broadcast(byteArrayOf(9))
        val received = joiner.incoming.first()
        assertEquals(9, received.payload.single())
        assertEquals(setOf(PlyId("relay"), PlyId("overlay")), host.plies.value.keys)
    }

    @Test
    fun singleElementFlowIsTheDegenerateStaticCase() = runTest {
        // A never-changing single-element flow behaves exactly like the static ctor.
        val mem = InMemoryLoom()
        val desired = MutableStateFlow(listOf(PlyId("mem") to mem as Loom))
        val loom = CompositeLoom(desired, UnconfinedTestDispatcher())

        val host = loom.host(Pattern("s"))
        val joiner = loom.join(InMemoryTag("s"))
        host.peers.first { it.size == 2 }

        host.broadcast(byteArrayOf(3))
        assertEquals(3, joiner.incoming.first().payload.single())
        assertEquals(setOf(PlyId("mem")), host.plies.value.keys)
    }
}
