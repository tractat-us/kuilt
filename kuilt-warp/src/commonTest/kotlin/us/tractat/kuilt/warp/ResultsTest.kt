package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Convergence tests for [Results] — an [us.tractat.kuilt.crdt.ORMap] of task results
 * with last-write-wins per task via [us.tractat.kuilt.crdt.LWWRegister].
 *
 * The key guarantee: duplicate execution of the same task (same taskId) is absorbed
 * idempotently. Under last-write-wins the result with the higher (timestamp, replicaId)
 * tag wins — a duplicate execution from another peer overwrites the losing result but
 * never produces *two* results for the same task. Callers that require strict exactly-once
 * semantics should use the Coordinated path (slice B).
 *
 * Merge semantics documented choice:
 *   LWW (last-writer-wins) was chosen for Results over MVRegister because:
 *   - We want exactly one visible result per taskId after convergence.
 *   - Double-execution under failover is expected and benign (embarrassingly-parallel tasks).
 *   - The "wrong" result is bounded: the losing execution's result is silently discarded —
 *     it cannot corrupt the winner's result, and both results were produced by the same
 *     task function on the same input. Clock skew can select the "older" result, but that
 *     is the caller's responsibility (use a logical clock or HLC for the timestamp).
 */
class ResultsTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    @Test
    fun emptyResultsHasNoEntries() {
        assertTrue(Results.empty<String, String>().taskIds.isEmpty())
    }

    @Test
    fun recordedResultIsRetrievable() {
        val r = Results.empty<String, String>().record(alice, "task-1", timestamp = 1L, result = "done")
        assertEquals("done", r["task-1"])
    }

    @Test
    fun absentTaskReturnsNull() {
        assertNull(Results.empty<String, String>()["task-99"])
    }

    @Test
    fun duplicateExecutionSameTaskIsAbsorbedIdempotently() {
        // Alice finishes task-1 at t=1; bob also finished it at t=2 (later → wins)
        val ra = Results.empty<String, String>().record(alice, "task-1", timestamp = 1L, result = "alice-result")
        val rb = Results.empty<String, String>().record(bob, "task-1", timestamp = 2L, result = "bob-result")
        val merged = ra.merge(rb)
        // bob's timestamp is higher → bob's result wins; still exactly one result
        assertEquals("bob-result", merged["task-1"])
        assertEquals(setOf("task-1"), merged.taskIds)
    }

    @Test
    fun duplicateExecutionSameTimestampBreaksTieOnReplicaId() {
        // Both write at same timestamp; replicaId tiebreak is lexicographic — "bob" > "alice"
        val ra = Results.empty<String, String>().record(alice, "task-1", timestamp = 5L, result = "alice-result")
        val rb = Results.empty<String, String>().record(bob, "task-1", timestamp = 5L, result = "bob-result")
        val merged = ra.merge(rb)
        // "bob" > "alice" lexicographically
        assertEquals("bob-result", merged["task-1"])
        assertEquals(setOf("task-1"), merged.taskIds)
    }

    @Test
    fun mergeIsCommutative() {
        val ra = Results.empty<String, String>().record(alice, "task-1", timestamp = 3L, result = "alpha")
        val rb = Results.empty<String, String>().record(bob, "task-1", timestamp = 7L, result = "beta")
        assertEquals(ra.merge(rb), rb.merge(ra))
    }

    @Test
    fun mergeIsIdempotent() {
        val r = Results.empty<String, String>().record(alice, "task-1", timestamp = 1L, result = "result")
        assertEquals(r, r.merge(r))
    }

    @Test
    fun distinctTasksFromDifferentReplicasAllSurviveMerge() {
        val ra = Results.empty<String, String>().record(alice, "task-A", timestamp = 1L, result = "A-done")
        val rb = Results.empty<String, String>().record(bob, "task-B", timestamp = 1L, result = "B-done")
        val merged = ra.merge(rb)
        assertEquals(setOf("task-A", "task-B"), merged.taskIds)
        assertEquals("A-done", merged["task-A"])
        assertEquals("B-done", merged["task-B"])
    }

    @Test
    fun earlierResultIsOverwrittenByLater() {
        // Same replica, sequential writes — later timestamp wins
        val r1 = Results.empty<String, String>().record(alice, "task-1", timestamp = 1L, result = "v1")
        val r2 = Results.empty<String, String>().record(alice, "task-1", timestamp = 2L, result = "v2")
        assertEquals("v2", r1.merge(r2)["task-1"])
        assertEquals("v2", r2.merge(r1)["task-1"])
    }

    @Test
    fun threeWayMergeConvergesOnHighestTimestamp() {
        val carol = ReplicaId("carol")
        val ra = Results.empty<String, String>().record(alice, "task-1", timestamp = 1L, result = "a")
        val rb = Results.empty<String, String>().record(bob, "task-1", timestamp = 3L, result = "b")
        val rc = Results.empty<String, String>().record(carol, "task-1", timestamp = 2L, result = "c")
        val merged = ra.merge(rb).merge(rc)
        assertEquals("b", merged["task-1"]) // t=3 wins
    }
}
