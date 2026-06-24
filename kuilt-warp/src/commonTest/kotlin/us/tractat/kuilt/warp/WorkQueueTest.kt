package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Convergence tests for [WorkQueue] — an [us.tractat.kuilt.crdt.ORSet] of pending task IDs.
 *
 * These tests exercise the CRDT layer directly (no Seam/Quilter/Raft). The invariants are:
 *  - Concurrent adds from multiple replicas all survive merge.
 *  - Removes are observed-remove: they only cancel the dots they saw.
 *  - No task is lost across merges.
 *  - Merge is commutative and idempotent.
 */
class WorkQueueTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    @Test
    fun emptyQueueHasNoPendingTasks() {
        assertTrue(WorkQueue.empty<String>().pending.isEmpty())
    }

    @Test
    fun addedTaskAppearsInPending() {
        val q = WorkQueue.empty<String>().add(alice, "task-1")
        assertTrue(q.pending.contains("task-1"))
    }

    @Test
    fun removedTaskDisappearsFromPending() {
        val q = WorkQueue.empty<String>().add(alice, "task-1").remove("task-1")
        assertFalse(q.pending.contains("task-1"))
    }

    @Test
    fun concurrentAddsFromDifferentReplicasBothSurviveMerge() {
        val qa = WorkQueue.empty<String>().add(alice, "task-A")
        val qb = WorkQueue.empty<String>().add(bob, "task-B")
        val merged = qa.merge(qb)
        assertEquals(setOf("task-A", "task-B"), merged.pending)
    }

    @Test
    fun addWinsOverConcurrentRemove() {
        // shared start: alice added "task-1"
        val start = WorkQueue.empty<String>().add(alice, "task-1")
        // bob removes what he saw
        val bobView = start.remove("task-1")
        // alice concurrently re-adds "task-1" (a new dot)
        val aliceView = start.add(alice, "task-1")
        val merged = bobView.merge(aliceView)
        assertTrue(merged.pending.contains("task-1"))
    }

    @Test
    fun removeWithoutConcurrentAddDisappears() {
        val start = WorkQueue.empty<String>().add(alice, "task-1")
        val removed = start.remove("task-1")
        // merging with the original (stale-present) view still drops the task
        val merged = removed.merge(start)
        assertFalse(merged.pending.contains("task-1"))
    }

    @Test
    fun mergeIsCommutative() {
        val qa = WorkQueue.empty<String>().add(alice, "task-A")
        val qb = WorkQueue.empty<String>().add(bob, "task-B")
        assertEquals(qa.merge(qb), qb.merge(qa))
    }

    @Test
    fun mergeIsIdempotent() {
        val q = WorkQueue.empty<String>().add(alice, "task-1").add(bob, "task-2")
        assertEquals(q, q.merge(q))
    }

    @Test
    fun multipleTasksAddedByOneReplica() {
        val q = WorkQueue.empty<String>()
            .add(alice, "task-1")
            .add(alice, "task-2")
            .add(alice, "task-3")
        assertEquals(setOf("task-1", "task-2", "task-3"), q.pending)
    }

    @Test
    fun removeOneOfManyTasksLeavesOthers() {
        val q = WorkQueue.empty<String>()
            .add(alice, "task-1")
            .add(alice, "task-2")
            .remove("task-1")
        assertFalse(q.pending.contains("task-1"))
        assertTrue(q.pending.contains("task-2"))
    }

    @Test
    fun threeWayMergePreservesAllTasks() {
        val qa = WorkQueue.empty<String>().add(alice, "task-A")
        val qb = WorkQueue.empty<String>().add(bob, "task-B")
        val qc = WorkQueue.empty<String>().add(ReplicaId("carol"), "task-C")
        val merged = qa.merge(qb).merge(qc)
        assertEquals(setOf("task-A", "task-B", "task-C"), merged.pending)
    }
}
