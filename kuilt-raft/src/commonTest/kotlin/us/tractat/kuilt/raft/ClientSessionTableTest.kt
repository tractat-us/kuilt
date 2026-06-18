package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientSessionTableTest {
    @Test
    fun firstSerialAppliesAndIsRecorded() {
        val t = ClientSessionTable()
        assertTrue(t.shouldApply(DedupKey(ClientId("c"), 1)))
    }

    @Test
    fun serialAtOrBelowHighWaterMarkIsSkipped() {
        val t = ClientSessionTable()
        t.shouldApply(DedupKey(ClientId("c"), 5))
        assertFalse(t.shouldApply(DedupKey(ClientId("c"), 5))) // exact retry
        assertFalse(t.shouldApply(DedupKey(ClientId("c"), 3))) // stale
        assertTrue(t.shouldApply(DedupKey(ClientId("c"), 6)))  // advances
    }

    @Test
    fun unkeyedEntriesAlwaysApply() {
        val t = ClientSessionTable()
        assertTrue(t.shouldApply(null))
        assertTrue(t.shouldApply(null))
    }

    @Test
    fun snapshotRoundTripPreservesHighWaterMarks() {
        val t = ClientSessionTable()
        t.shouldApply(DedupKey(ClientId("a"), 4))
        t.shouldApply(DedupKey(ClientId("b"), 9))
        val restored = ClientSessionTable.fromBytes(t.toBytes())
        assertFalse(restored.shouldApply(DedupKey(ClientId("a"), 4)))
        assertEquals(true, restored.shouldApply(DedupKey(ClientId("a"), 5)))
    }

    // --- dedup GC v2: supersession prune (#565 / #495) ---

    // An auto-shaped id: "auto:$nodeId-$16hex". Distinct suffixes are distinct incarnations.
    private fun auto(node: String, suffix: String) = ClientId("auto:$node-${suffix.padStart(16, '0')}")

    @Test
    fun supersessionEvictsThePriorIncarnation() {
        val t = ClientSessionTable()
        val first = auto("nodeA", "aaaa")
        val second = auto("nodeA", "bbbb")
        assertTrue(t.shouldApply(DedupKey(first, 3)))
        assertTrue(t.shouldApply(DedupKey(second, 1))) // new incarnation proves `first` dead
        // The surviving incarnation's mark is intact.
        assertFalse(t.shouldApply(DedupKey(second, 1)))
        assertTrue(t.shouldApply(DedupKey(second, 2)))
        // The old sibling is gone: a stale straggler from it now re-applies (no longer suppressed).
        // (Checked last — re-applying `first` re-introduces its family and would itself evict `second`.)
        assertTrue(t.shouldApply(DedupKey(first, 2)))
    }

    @Test
    fun noCrossFamilyEviction() {
        val t = ClientSessionTable()
        val a1 = auto("nodeA", "1111")
        val b1 = auto("nodeB", "2222")
        t.shouldApply(DedupKey(a1, 5))
        t.shouldApply(DedupKey(b1, 5))
        // A new nodeA incarnation evicts only nodeA siblings, never nodeB.
        t.shouldApply(DedupKey(auto("nodeA", "3333"), 1))
        assertFalse(t.shouldApply(DedupKey(b1, 5))) // nodeB mark untouched
        assertTrue(t.shouldApply(DedupKey(a1, 5)))  // old nodeA sibling was evicted → re-applies
    }

    @Test
    fun durableIdsAreNeverPruned() {
        val t = ClientSessionTable()
        val durable = ClientId("svc-1")
        t.shouldApply(DedupKey(durable, 7))
        // Auto incarnations churn around it; the durable mark survives untouched.
        repeat(5) { i -> t.shouldApply(DedupKey(auto("nodeA", i.toString()), 1)) }
        assertFalse(t.shouldApply(DedupKey(durable, 7))) // still suppressed → never evicted
        assertTrue(t.shouldApply(DedupKey(durable, 8)))
    }

    @Test
    fun snapshotAfterSupersessionIsAlreadyPrunedAndFormatStable() {
        val pruned = ClientSessionTable()
        pruned.shouldApply(DedupKey(auto("nodeA", "aaaa"), 3))
        pruned.shouldApply(DedupKey(auto("nodeA", "bbbb"), 1)) // evicts aaaa

        // A fresh v1 table holding only the surviving entry emits byte-identical bytes.
        val survivorOnly = ClientSessionTable()
        survivorOnly.shouldApply(DedupKey(auto("nodeA", "bbbb"), 1))

        assertContentEquals(survivorOnly.toBytes(), pruned.toBytes())
    }

    @Test
    fun snapshotBlobLoadsThenRePrunesOnNextSibling() {
        // Back-compat: bytes round-trip through the unchanged v1 format, and a restored entry is
        // still GC-eligible — the next sibling prunes it.
        val saved = ClientSessionTable()
        saved.shouldApply(DedupKey(auto("nodeA", "aaaa"), 3))
        val restored = ClientSessionTable.fromBytes(saved.toBytes())
        assertFalse(restored.shouldApply(DedupKey(auto("nodeA", "aaaa"), 3))) // mark survived the round-trip
        restored.shouldApply(DedupKey(auto("nodeA", "bbbb"), 1))              // new sibling prunes the restored entry
        assertTrue(restored.shouldApply(DedupKey(auto("nodeA", "aaaa"), 3)))  // old sibling evicted → re-applies
    }

    @Test
    fun pruneIsDeterministicUnderReplay() {
        val stream = listOf(
            DedupKey(auto("nodeA", "aaaa"), 1),
            DedupKey(ClientId("svc-1"), 4),
            DedupKey(auto("nodeB", "bbbb"), 2),
            DedupKey(auto("nodeA", "cccc"), 1), // supersedes nodeA-aaaa
            DedupKey(ClientId("svc-1"), 5),
            DedupKey(auto("nodeB", "dddd"), 1), // supersedes nodeB-bbbb
        )
        val left = ClientSessionTable()
        val right = ClientSessionTable()
        stream.forEach { left.shouldApply(it) }
        stream.forEach { right.shouldApply(it) }
        assertContentEquals(left.toBytes(), right.toBytes())
    }

    @Test
    fun closeSessionResetsTheMark() {
        val t = ClientSessionTable()
        val id = ClientId("svc-1")
        t.shouldApply(DedupKey(id, 5))
        assertFalse(t.shouldApply(DedupKey(id, 5))) // suppressed
        t.closeSession(id)
        assertTrue(t.shouldApply(DedupKey(id, 5))) // mark reset → applies again
    }

    @Test
    fun evictedStragglerReAppliesNeverSilentlyDropped() {
        val t = ClientSessionTable()
        // nodeA incarnation 1 applies reqId 3.
        t.shouldApply(DedupKey(auto("nodeA", "aaaa"), 3))
        // incarnation 2 commits → incarnation 1 evicted.
        t.shouldApply(DedupKey(auto("nodeA", "bbbb"), 1))
        // A straggler duplicate of incarnation 1 (reqId 2 ≤ its old mark 3) is NOT silently dropped:
        // it re-applies — the auto path's documented at-least-once floor. (It also re-introduces the
        // nodeA family and so would supersede the live incarnation; still at-least-once, never a drop.)
        assertTrue(t.shouldApply(DedupKey(auto("nodeA", "aaaa"), 2)))
    }
}
