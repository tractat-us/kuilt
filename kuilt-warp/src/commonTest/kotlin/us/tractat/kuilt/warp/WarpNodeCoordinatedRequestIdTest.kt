@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Two coordinated tasks whose ids share a `String.hashCode` both run (#2923).
 *
 * `WarpNode` used to pin its raft request id to `taskId.value.hashCode()`. The leader keeps every
 * key it applies during its tenure, so once the first task committed, the second task's propose
 * found the first one's key. Before #2987 the leader handed back the first task's entry, so no entry
 * for the second task was ever appended and it never ran. After #2987 the leader refuses it as a
 * reused id on every retrigger, with the same result for the task.
 *
 * The second task is enqueued only after the first has a result. Proposed together, both would be
 * appended before either committed, the cache would hold neither, and the collision would not fire.
 */
class WarpNodeCoordinatedRequestIdTest {

    @Test
    fun twoTasksWithCollidingStringHashesBothExecute() = raftSimTest(n = 3) { sim ->
        val first = TaskId("Aa")
        val second = TaskId("BB")

        val leader = sim.awaitLeader()
        val seam = InMemoryLoom().host(Pattern("warp-request-id"))
        val executed = mutableListOf<TaskId>()
        val lock = reentrantLock()
        // One warp peer on the raft leader: it owns both tasks, proposes both under one client
        // id, and is the node whose committed-log listener executes them.
        val warp = WarpNode(
            selfId = seam.selfId,
            seam = seam,
            rosterFlow = MutableStateFlow<Set<PeerId>>(setOf(seam.selfId)),
            scope = backgroundScope,
            quilterConfig = QUILTER_CONFIG,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            strategy = ClaimStrategy.Ring,
            registry = OpRegistry(),
            coordinatedExecutor = { taskId ->
                lock.withLock { executed.add(taskId) }
                "ran-${taskId.value}"
            },
            raftNode = leader,
            epoch = 0L,
        )

        warp.enqueue(first, CoordinationKind.Coordinated)
        sim.awaitTrue("the first task runs", within = 4.seconds) { warp.results[first] != null }

        warp.enqueue(second, CoordinationKind.Coordinated)
        sim.awaitTrue("the second task runs", within = 4.seconds) { warp.results[second] != null }

        assertAll(
            { assertEquals(first.value.hashCode(), second.value.hashCode(), "rig: the two ids must collide") },
            { assertEquals(RaftRole.Leader, leader.role.value, "rig: one leader tenure throughout") },
            { assertEquals(listOf(first, second), lock.withLock { executed.toList() }, "each task runs exactly once") },
            { assertNotNull(warp.results[second], "the second task has its own result") },
        )
        sim.checkInvariants()
        warp.close()
    }

    private companion object {
        val QUILTER_CONFIG = QuilterConfig(
            antiEntropyInterval = 100.milliseconds,
            fullStateRetryInterval = 150.milliseconds,
            expectVirtualTime = true,
        )
    }
}
