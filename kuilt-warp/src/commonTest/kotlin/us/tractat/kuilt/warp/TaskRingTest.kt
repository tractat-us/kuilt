package us.tractat.kuilt.warp

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskRingTest {

    // -------------------------------------------------------------------------
    // Determinism — same roster, same seed → same owner
    // -------------------------------------------------------------------------

    @Test
    fun `owner is deterministic for the same roster snapshot`() {
        val peers = peersOf("a", "b", "c", "d")
        val ring1 = TaskRing(peers, vnodeCount = 64, seed = 42)
        val ring2 = TaskRing(peers, vnodeCount = 64, seed = 42)
        tasks(100).forEach { task ->
            assertEquals(ring1.owner(task), ring2.owner(task), "task=$task must map to same owner")
        }
    }

    @Test
    fun `owner is independent of roster iteration order`() {
        val peers1 = listOf("a", "b", "c", "d").map { PeerId(it) }.toSet()
        val peers2 = listOf("d", "c", "b", "a").map { PeerId(it) }.toSet()
        val ring1 = TaskRing(peers1, vnodeCount = 64, seed = 42)
        val ring2 = TaskRing(peers2, vnodeCount = 64, seed = 42)
        tasks(100).forEach { task ->
            assertEquals(ring1.owner(task), ring2.owner(task), "task=$task should be order-independent")
        }
    }

    // -------------------------------------------------------------------------
    // Empty roster
    // -------------------------------------------------------------------------

    @Test
    fun `owner returns null for empty roster`() {
        val ring = TaskRing(emptySet(), vnodeCount = 64, seed = 42)
        assertNull(ring.owner(TaskId("task-0")))
    }

    @Test
    fun `successor returns null when all peers excluded`() {
        val peers = peersOf("a", "b")
        val ring = TaskRing(peers, vnodeCount = 64, seed = 42)
        assertNull(ring.successor(TaskId("task-0"), excluding = peers))
    }

    // -------------------------------------------------------------------------
    // Single peer — owns everything
    // -------------------------------------------------------------------------

    @Test
    fun `single peer owns all tasks`() {
        val only = PeerId("only")
        val ring = TaskRing(setOf(only), vnodeCount = 64, seed = 42)
        tasks(50).forEach { task ->
            assertEquals(only, ring.owner(task), "single peer must own task=$task")
        }
    }

    // -------------------------------------------------------------------------
    // All tasks covered — every task has an owner
    // -------------------------------------------------------------------------

    @Test
    fun `every task has an owner when roster is non-empty`() {
        val peers = peersOf("a", "b", "c")
        val ring = TaskRing(peers, vnodeCount = 64, seed = 42)
        tasks(200).forEach { task ->
            assertEquals(true, ring.owner(task) in peers, "task=$task must have an owner in roster")
        }
    }

    // -------------------------------------------------------------------------
    // Load distribution — virtual nodes produce even spread
    // -------------------------------------------------------------------------

    @Test
    fun `load is distributed within acceptable tolerance across peers`() {
        val peers = peersOf("a", "b", "c", "d")
        val ring = TaskRing(peers, vnodeCount = 150, seed = 42)
        val tasks = tasks(1000)
        val distribution = tasks.groupingBy { ring.owner(it)!! }.eachCount()

        val expected = tasks.size.toDouble() / peers.size
        val tolerance = 0.35  // within 35% of ideal — generous for probabilistic hash

        assertAll(*peers.map { peer ->
            val count = distribution.getOrElse(peer) { 0 }
            val deviation = abs(count - expected) / expected
            fun(): Unit {
                assertEquals(
                    true,
                    deviation <= tolerance,
                    "peer=$peer count=$count expected~=${expected.toInt()} deviation=${(deviation * 100).toInt()}% > ${(tolerance * 100).toInt()}%",
                )
            }
        }.toTypedArray())
    }

    // -------------------------------------------------------------------------
    // Minimal reshuffling — consistent-hash property
    // -------------------------------------------------------------------------

    @Test
    fun `removing one peer only reassigns that peer's tasks`() {
        val peers = peersOf("a", "b", "c", "d")
        val ring = TaskRing(peers, vnodeCount = 64, seed = 42)
        val reduced = peersOf("a", "b", "c")
        val ringReduced = TaskRing(reduced, vnodeCount = 64, seed = 42)
        val tasks = tasks(1000)

        var reassigned = 0
        for (task in tasks) {
            val before = ring.owner(task)
            val after = ringReduced.owner(task)
            if (before != after) {
                assertEquals(PeerId("d"), before, "task=$task: only the removed peer's tasks should move")
                reassigned++
            }
        }

        val moveRate = reassigned.toDouble() / tasks.size
        assertEquals(
            true,
            moveRate in 0.10..0.50,
            "reassigned=$reassigned move_rate=${(moveRate * 100).toInt()}% should be near 25%",
        )
    }

    @Test
    fun `adding one peer takes only its fair share of tasks`() {
        val before = peersOf("a", "b", "c")
        val after = peersOf("a", "b", "c", "d")
        val ringBefore = TaskRing(before, vnodeCount = 64, seed = 42)
        val ringAfter = TaskRing(after, vnodeCount = 64, seed = 42)
        val tasks = tasks(1000)

        var moved = 0
        for (task in tasks) {
            val ownerBefore = ringBefore.owner(task)
            val ownerAfter = ringAfter.owner(task)
            if (ownerBefore != ownerAfter) {
                assertEquals(PeerId("d"), ownerAfter, "task=$task should only move TO the newly added peer")
                moved++
            }
        }

        val moveRate = moved.toDouble() / tasks.size
        assertEquals(
            true,
            moveRate in 0.10..0.50,
            "moved=$moved move_rate=${(moveRate * 100).toInt()}% should be near 25%",
        )
    }

    // -------------------------------------------------------------------------
    // Successor — failover hook
    // -------------------------------------------------------------------------

    @Test
    fun `successor returns a different peer than the primary owner`() {
        val peers = peersOf("a", "b", "c")
        val ring = TaskRing(peers, vnodeCount = 64, seed = 42)
        tasks(50).forEach { task ->
            val owner = ring.owner(task)!!
            val next = ring.successor(task, excluding = setOf(owner))
            assertEquals(true, next != null, "task=$task must have a successor")
            assertEquals(true, next != owner, "task=$task successor must differ from owner")
            assertEquals(true, next in peers, "task=$task successor must be in roster")
        }
    }

    @Test
    fun `successor skips all excluded peers`() {
        val peers = peersOf("a", "b", "c", "d")
        val ring = TaskRing(peers, vnodeCount = 64, seed = 42)
        val task = TaskId("task-0")
        val owner = ring.owner(task)!!
        val otherPeer = peers.first { it != owner }
        val excluding = setOf(owner, otherPeer)
        val next = ring.successor(task, excluding = excluding)
        if (next != null) {
            assertEquals(true, next !in excluding, "successor must not be in excluded set")
            assertEquals(true, next in peers, "successor must be in roster")
        }
    }

    @Test
    fun `successor is deterministic for the same inputs`() {
        val peers = peersOf("a", "b", "c", "d")
        val ring = TaskRing(peers, vnodeCount = 64, seed = 42)
        val task = TaskId("task-17")
        val owner = ring.owner(task)!!
        val excluding = setOf(owner)
        val s1 = ring.successor(task, excluding = excluding)
        val s2 = ring.successor(task, excluding = excluding)
        assertEquals(s1, s2, "successor must be deterministic")
    }

    // -------------------------------------------------------------------------
    // RosterSnapshot adapter
    // -------------------------------------------------------------------------

    @Test
    fun `RosterSnapshot toTaskRing matches direct TaskRing construction`() {
        val peers = peersOf("a", "b", "c")
        val snapshot = RosterSnapshot(peers)
        val fromSnapshot = snapshot.toTaskRing(vnodeCount = 64, seed = 42)
        val fromDirect = TaskRing(peers, vnodeCount = 64, seed = 42)
        tasks(100).forEach { task ->
            assertEquals(fromDirect.owner(task), fromSnapshot.owner(task), "task=$task")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun peersOf(vararg ids: String): Set<PeerId> = ids.map { PeerId(it) }.toSet()
    private fun tasks(count: Int): List<TaskId> = (0 until count).map { TaskId("task-$it") }
}
