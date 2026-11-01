@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A [ClientIdentity.Durable] id outlives the process that held it, so a restarted node walks log
 * entries its earlier incarnation issued. Those must not read as a second live writer (#3060).
 *
 * Every arm runs real nodes over [DurableStoreRaftStorage] on an in-memory store, reopened per
 * incarnation. Each asserts its own rig: the restarted incarnation's `committed` stream must carry
 * the old serials, which is the only evidence that replay re-walked them rather than skipping them.
 */
class DurableIdentityRestartTest {

    @Test
    fun singleVoterReplaysItsOwnPinnedSerials() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val last = (1L..3L).map { rig.node(SOLO_ID).propose(command(it), requestId = it) }.last()
        rig.restart(SOLO_ID)

        rig.awaitReplay(SOLO_ID, through = last.index)
        rig.assertStayedUpHavingWalked(SOLO_ID, listOf(1L, 2L, 3L))
    }

    @Test
    fun singleVoterReplaysItsOwnAutoSerials() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val last = (1L..3L).map { rig.node(SOLO_ID).propose(command(it)) }.last()
        rig.restart(SOLO_ID)

        rig.awaitReplay(SOLO_ID, through = last.index)
        rig.assertStayedUpHavingWalked(SOLO_ID, listOf(1L, 2L, 3L))
    }

    @Test
    fun restartedFollowerReplaysItsOwnForwardedEntries() = raftRunTest {
        val rig = durableRig(TRIO)
        val leader = rig.sim.awaitLeader()
        val followerId = TRIO.first { rig.sim.nodes[it] !== leader }

        val last = (1L..3L).map { rig.node(followerId).propose(command(it), requestId = it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.restart(followerId)

        rig.awaitReplay(followerId, through = last.index)
        rig.assertStayedUpHavingWalked(followerId, listOf(1L, 2L, 3L))
    }

    @Test
    fun restartedLeaderReplaysItsOwnEntriesAsAFollower() = raftRunTest {
        val rig = durableRig(TRIO)
        val leader = rig.sim.awaitLeader()
        val leaderId = TRIO.first { rig.sim.nodes[it] === leader }

        val last = (1L..3L).map { leader.propose(command(it), requestId = it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.restart(leaderId)

        rig.awaitReplay(leaderId, through = last.index)
        rig.assertStayedUpHavingWalked(leaderId, listOf(1L, 2L, 3L))
    }

    @Test
    fun wholeClusterRestartReplaysEveryNodesOwnEntries() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()

        val indices = TRIO.map { id -> (1L..2L).map { rig.node(id).propose(command(it), requestId = it).index }.last() }
        val through = indices.max()
        rig.sim.awaitCommit(through)
        TRIO.forEach { rig.stop(it) }
        TRIO.forEach { rig.start(it) }

        TRIO.forEach { rig.awaitReplay(it, through) }
        val perNode: List<() -> Unit> = TRIO.map { id -> { rig.assertStayedUpHavingWalked(id, listOf(1L, 2L)) } }
        assertAll(*perNode.toTypedArray())
    }

    @Test
    fun aRetainedTailAboveASnapshotStillReplays() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val entries = (1L..4L).map { rig.node(SOLO_ID).propose(command(it), requestId = it) }
        rig.compactThrough(SOLO_ID, entries[1].index)
        rig.restart(SOLO_ID)

        rig.awaitReplay(SOLO_ID, through = entries.last().index)
        // Serials 1 and 2 went with the prefix: the rig is what proves the snapshot really cut them.
        rig.assertStayedUpHavingWalked(SOLO_ID, listOf(3L, 4L))
    }

    /**
     * The control. Compacting past every own entry leaves replay nothing to walk, so this arm
     * stays up on a build with the defect too: it says the defect is the re-walk, not the restart.
     */
    @Test
    fun aSnapshotCoveringEveryOwnEntryLeavesNothingToReplay() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val entries = (1L..3L).map { rig.node(SOLO_ID).propose(command(it), requestId = it) }
        rig.compactThrough(SOLO_ID, entries.last().index)
        rig.restart(SOLO_ID)

        rig.sim.awaitLeader()
        rig.assertStayedUpHavingWalked(SOLO_ID, emptyList())
    }

    private companion object {
        val SOLO_ID = NodeId("solo")
        val SOLO = listOf(SOLO_ID)
        val TRIO = listOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))

        fun command(serial: Long): ByteArray = "cmd-$serial".encodeToByteArray()
    }
}

/**
 * Stands up [ids] as a voter cluster over reopenable durable storage. `suspend` because opening a
 * [DurableStoreRaftStorage] is, and the simulation starts its nodes on construction.
 */
private suspend fun TestScope.durableRig(ids: List<NodeId>): DurableRestartRig {
    val stores = ids.associateWith { InMemoryDurableStore() }
    val opened = stores.mapValues { (_, store) -> DurableStoreRaftStorage.open(store) }
    return DurableRestartRig(this, ids, stores, opened)
}

/**
 * The restart harness over [RaftSimulation]. Failures are captured by a [SupervisorJob] and handler
 * rather than propagated, because a [ClientIdCollisionException] from one node's actor loop is the
 * thing under observation and must not tear down the test.
 */
private class DurableRestartRig(
    test: TestScope,
    private val ids: List<NodeId>,
    private val stores: Map<NodeId, InMemoryDurableStore>,
    opened: Map<NodeId, DurableStoreRaftStorage>,
) {
    val failures = mutableListOf<Throwable>()
    private val storages = opened.toMutableMap()
    private val nodeScopes = mutableMapOf<NodeId, CoroutineScope>()
    private val walked = mutableMapOf<NodeId, MutableList<Committed>>()

    private val capturing = CoroutineScope(
        test.backgroundScope.coroutineContext +
            SupervisorJob(test.backgroundScope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, e -> failures += e },
    )
    private val config = fastRaftConfig()

    val sim = RaftSimulation(
        nodeIds = ids,
        scope = test,
        nodeScope = capturing,
        nodeFactory = { id, transport, _, child ->
            val node = child.raftNode(
                ClusterConfig(voters = ids.toSet()),
                transport,
                storages.getValue(id),
                config,
                ClientIdentity.Durable(ClientId(id.value)),
            )
            val log = mutableListOf<Committed>()
            walked[id] = log
            nodeScopes[id] = child
            child.launch { node.committed.collect { log += it } }
            node
        },
    )

    fun node(id: NodeId): RaftNode = sim.nodes.getValue(id)

    suspend fun stop(id: NodeId) {
        node(id).close()
        sim.crash(id)
    }

    /** A fresh handle onto the same store, as a new process would have. */
    suspend fun start(id: NodeId) {
        storages[id] = DurableStoreRaftStorage.open(stores.getValue(id))
        sim.restart(id)
    }

    suspend fun restart(id: NodeId) {
        stop(id)
        start(id)
    }

    /** Publish a snapshot through [index] and wait for the durable log prefix to go. */
    suspend fun compactThrough(id: NodeId, index: Long) {
        node(id).snapshots.value = Snapshot(index, byteArrayOf(1))
        sim.awaitTrue("compaction through $index on ${id.value}") {
            node(id).compactionFloor.value >= index
        }
    }

    /** Ends on the first of: [id] has committed through [through], or some node has failed. */
    suspend fun awaitReplay(id: NodeId, through: Long) {
        sim.awaitTrue("replay through $through on ${id.value}, or a failure") {
            failures.isNotEmpty() || (sim.nodes[id]?.commitIndex?.value ?: -1L) >= through
        }
    }

    /** Own-id serials the current incarnation of [id] surfaced, in commit order. */
    fun serialsWalkedBy(id: NodeId): List<Long> =
        walked.getValue(id).filterIsInstance<Committed.Entry>()
            .mapNotNull { it.entry.dedupKey }
            .filter { it.clientId == ClientId(id.value) }
            .map { it.requestId }

    fun assertStayedUpHavingWalked(id: NodeId, serials: List<Long>) {
        assertAll(
            { assertEquals(emptyList(), failures.map { it.toString() }, "no node may have failed") },
            { assertTrue(nodeScopes.getValue(id).isActive, "${id.value}'s scope must still be active") },
            { assertEquals(serials, serialsWalkedBy(id), "the restarted incarnation's committed stream") },
        )
    }
}
