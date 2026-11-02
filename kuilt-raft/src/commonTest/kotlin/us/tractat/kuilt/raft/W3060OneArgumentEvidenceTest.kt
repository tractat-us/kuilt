@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.store.InMemoryDurableStore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THROWAWAY evidence for the #3060 refusal decision, deleted in the commit that refuses. Each arm
 * gives one-argument `propose` the strongest floor its caller could possibly hold: the highest serial
 * a call has RETURNED, since nothing tells the caller a serial before that. Each asserts the failure.
 */
class W3060OneArgumentEvidenceTest {
    private val trio = listOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
    private val solo = NodeId("solo")

    private suspend fun rig(test: TestScope, ids: List<NodeId>): Rig {
        val stores = ids.associateWith { InMemoryDurableStore() }
        return Rig(test, ids, stores, stores.mapValues { (_, store) -> DurableStoreRaftStorage.open(store) }.toMutableMap())
    }

    private class Rig(
        test: TestScope,
        ids: List<NodeId>,
        val stores: Map<NodeId, InMemoryDurableStore>,
        val storages: MutableMap<NodeId, DurableStoreRaftStorage>,
    ) {
        val failures = mutableListOf<Throwable>()
        val floors = ids.associateWith { 0L }.toMutableMap()
        val walked = mutableMapOf<NodeId, MutableList<Committed>>()
        private val config = fastRaftConfig()
        private val cluster = ClusterConfig(voters = ids.toSet())
        private val capturing = CoroutineScope(
            test.backgroundScope.coroutineContext +
                SupervisorJob(test.backgroundScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> failures += e },
        )
        val background = test.backgroundScope
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = test,
            nodeScope = capturing,
            nodeFactory = { id, transport, _, child ->
                val node = child.raftNode(
                    cluster, transport, storages.getValue(id), config,
                    ClientIdentity.Durable(ClientId(id.value), floors.getValue(id)),
                )
                val log = mutableListOf<Committed>()
                walked[id] = log
                child.launch { node.committed.collect { log += it } }
                node
            },
        )
        suspend fun restart(id: NodeId) {
            sim.nodes.getValue(id).close()
            sim.crash(id)
            storages[id] = DurableStoreRaftStorage.open(stores.getValue(id))
            sim.restart(id)
        }
        fun applied(id: NodeId): List<String> {
            val table = ClientSessionTable()
            return walked.getValue(id).filterIsInstance<Committed.Entry>()
                .filter { table.shouldApply(it.entry.dedupKey) }
                .map { it.entry.command.decodeToString() }
        }
        fun raw(id: NodeId): List<String> =
            walked.getValue(id).filterIsInstance<Committed.Entry>().map { it.entry.command.decodeToString() }
    }

    private fun bytes(s: String) = s.encodeToByteArray()

    /** Crash with serial 4 in flight, floor 3 is all the caller could know: the catch-up kills the node. */
    @Test
    fun theSerialInFlightAtACrashIsAboveAnyFloorTheCallerCouldHold() = raftRunTest {
        val rig = rig(this, trio)
        val leader = rig.sim.awaitLeader()
        val leaderId = trio.first { rig.sim.nodes[it] === leader }
        val followerId = trio.first { it != leaderId }
        val returned = (1..3).map { rig.sim.nodes.getValue(followerId).propose(bytes("cmd-$it")) }
        rig.floors[followerId] = returned.maxOf { it.dedupKey?.requestId ?: 0L }
        rig.sim.awaitCommit(returned.last().index)

        rig.sim.dropLink(from = leaderId, to = followerId)
        rig.background.launch {
            try {
                rig.sim.nodes.getValue(followerId).propose(bytes("cmd-4"))
            } catch (_: LeadershipLostException) {
            }
        }
        rig.sim.awaitTrue("the leader to commit serial 4") { leader.commitIndex.value > returned.last().index }
        rig.restart(followerId)
        rig.sim.heal()
        rig.sim.awaitTrue("the catch-up") { rig.failures.isNotEmpty() }

        assertEquals(3L, rig.floors[followerId], "the strongest floor the caller could hold")
        assertContains(rig.failures.single().message.orEmpty(), "foreign serial 4")
    }

    /** The same crash with the next command first: the node draws 4 again, success, and the table drops it. */
    @Test
    fun theNextOneArgumentProposeReusesTheSerialInFlightAndIsDropped() = raftRunTest {
        val rig = rig(this, trio)
        val leader = rig.sim.awaitLeader()
        val leaderId = trio.first { rig.sim.nodes[it] === leader }
        val (followerId, thirdId) = trio.filter { it != leaderId }
        val returned = (1..3).map { rig.sim.nodes.getValue(followerId).propose(bytes("cmd-$it")) }
        rig.floors[followerId] = returned.maxOf { it.dedupKey?.requestId ?: 0L }
        rig.sim.awaitCommit(returned.last().index)

        rig.sim.dropLink(from = leaderId, to = followerId)
        rig.background.launch {
            try {
                rig.sim.nodes.getValue(followerId).propose(bytes("cmd-4"))
            } catch (_: LeadershipLostException) {
            }
        }
        rig.sim.awaitCommit(returned.last().index + 1, on = listOf(leaderId, thirdId))
        rig.restart(followerId)

        val fresh = CompletableDeferred<LogEntry>()
        rig.background.launch { fresh.complete(rig.sim.nodes.getValue(followerId).propose(bytes("cmd-99"))) }
        rig.sim.settle()
        leader.transferLeadership(thirdId)
        rig.sim.awaitLeader(among = setOf(thirdId))
        rig.sim.heal()
        rig.sim.awaitTrue("the propose to return") { fresh.isCompleted }
        val landed = fresh.await()
        rig.sim.awaitCommit(landed.index)

        assertEquals(4L, landed.dedupKey?.requestId, "the propose reuses the in-flight serial")
        assertContains(rig.raw(thirdId), "cmd-99", "it committed and the caller holds a success")
        assertFalse("cmd-99" in rig.applied(thirdId), "and a consumer's table drops it")
    }

    /** A snapshot past the last entry, and a caller that kept nothing because it pinned nothing. */
    @Test
    fun aSnapshotPastTheLastEntryRestartsTheSerialForACallerWithNoFloor() = raftRunTest {
        val rig = rig(this, listOf(solo))
        rig.sim.awaitLeader()
        val entries = (1..3).map { rig.sim.nodes.getValue(solo).propose(bytes("cmd-$it")) }
        val table = ClientSessionTable()
        entries.forEach { table.shouldApply(it.dedupKey) }
        rig.sim.nodes.getValue(solo).snapshots.value = Snapshot(entries.last().index, byteArrayOf(1))
        rig.sim.awaitTrue("compaction") { rig.sim.nodes.getValue(solo).compactionFloor.value >= entries.last().index }
        rig.restart(solo)
        rig.sim.awaitLeader()

        val fresh = rig.sim.nodes.getValue(solo).propose(bytes("cmd-4"))

        assertEquals(1L, fresh.dedupKey?.requestId, "the serial restarts at 1")
        assertFalse(table.shouldApply(fresh.dedupKey), "and the new command reads as a duplicate")
    }

    /** No crash at all: the two forms share no counter, so a pinned serial does not move the auto one. */
    @Test
    fun mixingTheFormsReusesASerialWithoutACrash() = raftRunTest {
        val rig = rig(this, listOf(solo))
        rig.sim.awaitLeader()
        rig.sim.nodes.getValue(solo).propose(bytes("cmd-1"), requestId = 1L)

        val outcome = runCatching { rig.sim.nodes.getValue(solo).propose(bytes("cmd-2")) }

        assertTrue(outcome.exceptionOrNull() is RequestIdConflictException, "the auto serial drew 1 again: $outcome")
    }
}
