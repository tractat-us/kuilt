@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

    // ── Replay is not a collision ─────────────────────────────────────────────

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
     * The control. Compacting past every own entry leaves replay nothing to walk, so this arm stays
     * up on a build with the defect too: it says the defect is the re-walk, not the restart.
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

    // ── What the next proposal does ───────────────────────────────────────────

    /**
     * Surviving the replay is what makes the next auto serial reachable, and a restart at 1 would
     * reuse one. A leader that replayed it refuses the new bytes, and without that cached entry a
     * consumer's table drops the command as a duplicate: see the full-compaction arm below.
     */
    @Test
    fun theNextAutoSerialResumesAboveTheReplayedMaximum() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val last = (1L..3L).map { rig.node(SOLO_ID).propose(command(it)) }.last()
        rig.restart(SOLO_ID)
        rig.awaitReplay(SOLO_ID, through = last.index)
        val table = ClientSessionTable()
        val replayed = rig.keysWalkedBy(SOLO_ID)
        replayed.forEach { table.shouldApply(it) }

        val fresh = rig.node(SOLO_ID).propose(command(4L))

        assertAll(
            { assertEquals(3, replayed.size, "the table must have folded the replay") },
            { assertEquals(4L, fresh.dedupKey?.requestId, "the serial resumes above the replayed maximum") },
            { assertTrue(table.shouldApply(fresh.dedupKey), "a fresh command must not read as a duplicate") },
        )
    }

    /**
     * A restarted single voter leads through its own replay, so its best-effort cache holds the old
     * entries again. Same bytes coalesce, and other bytes under an old serial are refused rather than
     * acknowledged: that is the conflict a caller restarting its own serials at 1 would meet.
     */
    @Test
    fun aPinnedRetryOfAnOldSerialAfterARestartCoalesces() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val entries = (1L..3L).map { rig.node(SOLO_ID).propose(command(it), requestId = it) }
        rig.restart(SOLO_ID)
        rig.awaitReplay(SOLO_ID, through = entries.last().index)

        val retried = rig.node(SOLO_ID).propose(command(3L), requestId = 3L)
        assertFailsWith<RequestIdConflictException> { rig.node(SOLO_ID).propose(command(99L), requestId = 2L) }

        assertAll(
            { assertEquals(emptyList(), rig.failures.map { it.toString() }) },
            { assertEquals(entries.last().index, retried.index, "the retry lands on the replayed entry") },
        )
    }

    // ── A second live writer is still caught ──────────────────────────────────

    /**
     * A second process handed the id after a restart. Both replay the shared history without a
     * failure, so each one's detector is seeded, and the serial past that seed is what gives it away.
     */
    @Test
    fun aSecondLiveWriterStillThrowsAfterARestart() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()
        val (holder, second) = TRIO[0] to TRIO[1]

        val last = (1L..3L).map { rig.node(holder).propose(command(it), requestId = it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.restart(holder)
        rig.awaitReplay(holder, through = last.index)
        rig.assertStayedUpHavingWalked(holder, listOf(1L, 2L, 3L))

        rig.handId(of = holder, to = second)
        rig.restart(second)
        rig.awaitReplay(second, through = last.index)
        assertEquals(emptyList(), rig.failures.map { it.toString() }, "the second process replays without a failure")

        // Launched: if the holder leads, it dies inside the commit that would have answered this.
        rig.launch { rig.node(second).propose(command(4L), requestId = 4L) }
        rig.sim.awaitTrue("the holder to see the foreign serial") { rig.failures.isNotEmpty() }

        rig.assertCollisionKilled(holder, foreignSerial = 4L, survivor = second)
    }

    @Test
    fun aWriterThatCommittedWhileThisNodeWasDownThrowsOnCatchUp() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()
        val (holder, second) = TRIO[0] to TRIO[1]

        val last = (1L..2L).map { rig.node(holder).propose(command(it), requestId = it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.stop(holder)

        rig.handId(of = holder, to = second)
        rig.restart(second)
        rig.sim.awaitLeader(among = setOf(second, TRIO[2]))
        rig.node(second).propose(command(3L), requestId = 3L)

        rig.start(holder)
        rig.sim.awaitTrue("the holder to catch up on the foreign serial") { rig.failures.isNotEmpty() }

        rig.assertCollisionKilled(holder, foreignSerial = 3L, survivor = second)
    }

    /**
     * The exemption. An auto id in its own log means a same-seed restart drew the same suffix, so the
     * replay must keep re-minting: resuming the serial there would keep aliasing the dead incarnation.
     */
    @Test
    fun anAutoIdStillReMintsWhenReplayFindsItsOwnKey() = raftRunTest {
        val storage = InMemoryRaftStorage()
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val first = singleVoterNode(firstScope, storage)
        first.node.awaitLeadership()
        val before = first.node.propose(command(1L))
        firstScope.cancel()

        // singleVoterNode mints Random(RAFT_TEST_SEED) per call, so the second boot draws the same id.
        val second = singleVoterNode(backgroundScope, storage)
        second.node.awaitLeadership()
        second.awaitCommit(before.index)
        val after = second.node.propose(command(2L))

        assertAll(
            { assertNotEquals(before.dedupKey?.clientId, after.dedupKey?.clientId, "must have re-minted") },
            { assertEquals(1L, after.dedupKey?.requestId, "a re-mint starts a fresh serial space") },
        )
    }

    // ── What this fix does not reach, tracked by #3060 ────────────────────────
    //
    // Each `knownLimit` arm asserts today's wrong answer, so that the change that fixes it reds here
    // and comes to look. None of them is a guarantee. Assertions on the wrong answer say so in their
    // message; the ones marked "the rig" prove the arm reached the state it is about.

    /**
     * Retained storage cannot tell this node about a serial it never received. The earlier
     * incarnation forwarded the proposal and died before replication reached it, which is the crash
     * a durable id exists to survive, and the catch-up reads exactly like
     * [aWriterThatCommittedWhileThisNodeWasDownThrowsOnCatchUp].
     */
    @Test
    fun knownLimitAForwardInFlightAcrossACrashReadsAsASecondWriter() = raftRunTest {
        val rig = durableRig(TRIO)
        val leader = rig.sim.awaitLeader()
        val leaderId = TRIO.first { rig.sim.nodes[it] === leader }
        val followerId = TRIO.first { it != leaderId }
        val before = leader.propose(command(0L))
        rig.sim.awaitCommit(before.index)

        rig.sim.dropLink(from = leaderId, to = followerId)
        rig.launch { rig.node(followerId).propose(command(1L), requestId = 1L) }
        rig.sim.awaitTrue("the leader to commit the forwarded proposal") {
            leader.commitIndex.value > before.index
        }
        val retained = rig.storedKeysOf(followerId)
        rig.stop(followerId)
        rig.sim.heal()
        rig.start(followerId)
        rig.sim.awaitTrue("the catch-up on the in-flight serial") { rig.failures.isNotEmpty() }

        assertEquals(emptyList(), retained.filter { it.clientId == ClientId(followerId.value) }, "the rig: never received")
        rig.assertCollisionKilled(followerId, foreignSerial = 1L, survivor = leaderId, prefix = KNOWN_WRONG)

        // The entry was durable before it committed, so the boot after this one finds it and recovers.
        rig.failures.clear()
        rig.restart(followerId)
        rig.awaitReplay(followerId, through = before.index + 1)
        rig.assertStayedUpHavingWalked(followerId, listOf(1L), prefix = KNOWN_WRONG)
    }

    /**
     * The same crash, with the next proposal arriving first. The node boots at the retained serial,
     * so a one-argument propose before catch-up draws the serial still in flight and raises the mark
     * over it. The in-flight entry is then not foreign, and nothing throws. A leader that committed
     * the old entry in its own tenure refuses the new bytes; this one committed it as a follower, so
     * its cache is cold, it appends, and the caller holds a success for a command a consumer's table
     * drops.
     */
    @Test
    fun knownLimitAProposeBeforeCatchUpReusesAnInFlightSerialAndIsDropped() = raftRunTest {
        val rig = durableRig(TRIO)
        val leader = rig.sim.awaitLeader()
        val leaderId = TRIO.first { rig.sim.nodes[it] === leader }
        val (followerId, thirdId) = TRIO.filter { it != leaderId }
        val durable = (1L..3L).map { rig.node(followerId).propose(command(it)) }.last()
        rig.sim.awaitCommit(durable.index)

        rig.sim.dropLink(from = leaderId, to = followerId)
        rig.launch { rig.node(followerId).propose(command(4L)) }
        rig.sim.awaitCommit(durable.index + 1, on = listOf(leaderId, thirdId))
        val retained = rig.storedKeysOf(followerId).map { it.requestId }
        rig.restart(followerId)

        val fresh = CompletableDeferred<LogEntry>()
        rig.launch { fresh.complete(rig.node(followerId).propose(command(99L))) }
        rig.sim.settle()
        val beforeCatchUp = rig.storedKeysOf(followerId).map { it.requestId }
        leader.transferLeadership(thirdId)
        rig.sim.awaitLeader(among = setOf(thirdId))
        rig.sim.heal()
        rig.sim.awaitTrue("the one-argument propose to return") { fresh.isCompleted }
        val landed = fresh.await()
        rig.sim.awaitCommit(landed.index)

        assertAll(
            { assertEquals(listOf(1L, 2L, 3L), retained, "the rig: serial 4 was in flight at the crash") },
            { assertEquals(listOf(1L, 2L, 3L), beforeCatchUp, "the rig: the propose came before catch-up") },
            { assertEquals(4L, landed.dedupKey?.requestId, "${KNOWN_WRONG}the propose reuses the in-flight serial") },
            { rig.assertStayedUpHavingWalked(followerId, listOf(1L, 2L, 3L, 4L, 4L), prefix = KNOWN_WRONG) },
            { assertContains(rig.commandsWalkedBy(thirdId), "cmd-99", "the rig: the command did commit") },
            { assertContains(rig.commandsAppliedBy(thirdId), "cmd-4", "the rig: the table applied the older one") },
            {
                assertFalse(
                    "cmd-99" in rig.commandsAppliedBy(thirdId),
                    "${KNOWN_WRONG}propose returned success and the table drops the command",
                )
            },
        )
    }

    /**
     * A node that retained nothing and the never-issued follower in
     * `RaftEngineDedupIntegrationTest.sharedDurableClientIdSurfacesCollision` make the same
     * observation: an empty log, then their own id at serial 1. A `clusterClient` restarted under a
     * durable id builds its node over an `InMemoryRaftStorage` that does not outlive it, and this arm
     * models that: it is a learner over a wiped store, not a `clusterClient`. An arm over the real
     * client belongs in `:kuilt-cluster` and is tracked by #3060.
     */
    @Test
    fun knownLimitAWipedDurableLearnerReadsItsOwnHistoryAsASecondWriter() = raftRunTest {
        val client = NodeId("client")
        val rig = durableRig(TRIO + client, learners = setOf(client))
        rig.sim.awaitLeader()

        val last = (1L..2L).map { rig.node(client).propose(command(it), requestId = it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.stop(client)
        rig.wipe(client)
        rig.start(client)
        rig.sim.awaitTrue("the catch-up on the node's own history") { rig.failures.isNotEmpty() }

        rig.assertCollisionKilled(client, foreignSerial = 1L, survivor = TRIO[0], prefix = KNOWN_WRONG)
    }

    /**
     * Compaction can cut the only record of the serial floor, and then a one-argument propose
     * reuses a serial and a consumer's table drops the new command without a word.
     */
    @Test
    fun knownLimitAnAutoSerialAfterFullCompactionReusesAnAppliedSerial() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val entries = (1L..3L).map { rig.node(SOLO_ID).propose(command(it)) }
        val table = ClientSessionTable()
        entries.forEach { table.shouldApply(it.dedupKey) }
        rig.compactThrough(SOLO_ID, entries.last().index)
        rig.restart(SOLO_ID)
        rig.sim.awaitLeader()

        val fresh = rig.node(SOLO_ID).propose(command(4L))

        assertAll(
            { assertEquals(1L, fresh.dedupKey?.requestId, "${KNOWN_WRONG}the floor went with the compacted prefix") },
            { assertFalse(table.shouldApply(fresh.dedupKey), "${KNOWN_WRONG}so a new command reads as a duplicate") },
        )
    }

    /**
     * A collision that kills a node also teaches it. The foreign entry reached storage before it
     * committed, so the next boot retains it and takes that serial as its own floor. It replays
     * cleanly and stays up beside the other writer, with nothing left to say the id is shared.
     *
     * Only that serial is absorbed. The next serial above the floor still kills the node, which is
     * why this arm goes on to prove it: a supervisor restarting on the exception gets one quiet death
     * per collision, not silence.
     */
    @Test
    fun knownLimitARestartAbsorbsAGenuineSecondWritersSerial() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()
        val (holder, second) = TRIO[0] to TRIO[1]
        rig.handId(of = holder, to = second)
        rig.restart(second)

        // Launched: if the second process leads, it dies inside the commit that would answer this.
        rig.launch { rig.node(holder).propose(command(1L), requestId = 1L) }
        rig.sim.awaitTrue("the second process to see the foreign serial") { rig.failures.isNotEmpty() }
        rig.assertCollisionKilled(second, foreignSerial = 1L, survivor = holder)
        val absorbed = rig.storedEntriesOf(second).single { it.dedupKey != null }

        rig.failures.clear()
        rig.restart(second)
        rig.awaitReplay(second, through = absorbed.index)
        rig.assertStayedUpHavingWalked(second, listOf(1L), prefix = KNOWN_WRONG)

        rig.launch { rig.node(holder).propose(command(2L), requestId = 2L) }
        rig.sim.awaitTrue("the second process to see the next foreign serial") { rig.failures.isNotEmpty() }
        rig.assertCollisionKilled(second, foreignSerial = 2L, survivor = holder)
    }

    /**
     * The detector only looks above its mark, so a second writer reusing a serial at or below it is
     * not caught by anything. A leader that committed that serial in its own tenure refuses other
     * bytes; one that committed it as a follower has a cold cache and appends them, and the consumer's
     * table drops the command.
     */
    @Test
    fun knownLimitAForeignSerialAtOrBelowTheMarkGoesUnseen() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()
        val (holder, second) = TRIO[0] to TRIO[1]

        val last = (1L..3L).map { rig.node(holder).propose(command(it), requestId = it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.handId(of = holder, to = second)
        rig.restart(second)
        rig.sim.awaitCommit(last.index)

        val oldLeader = rig.sim.awaitLeader()
        val coldLeader = TRIO.first { rig.sim.nodes[it] !== oldLeader }
        oldLeader.transferLeadership(coldLeader)
        rig.sim.awaitLeader(among = setOf(coldLeader))

        val foreign = rig.node(second).propose(command(99L), requestId = 2L)
        rig.sim.awaitCommit(foreign.index)

        assertAll(
            { rig.assertStayedUpHavingWalked(holder, listOf(1L, 2L, 3L, 2L), prefix = KNOWN_WRONG) },
            { assertContains(rig.commandsWalkedBy(holder), "cmd-99", "the rig: the foreign command did commit") },
            {
                assertFalse(
                    "cmd-99" in rig.commandsAppliedBy(holder),
                    "${KNOWN_WRONG}a second writer's command is acknowledged, then dropped",
                )
            },
        )
    }

    private companion object {
        val SOLO_ID = NodeId("solo")
        val SOLO = listOf(SOLO_ID)
        val TRIO = listOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
        const val KNOWN_WRONG = "known wrong, tracked by #3060: "

        fun command(serial: Long): ByteArray = "cmd-$serial".encodeToByteArray()
    }
}

/**
 * Stands up [ids] over reopenable durable storage. `suspend` because opening a
 * [DurableStoreRaftStorage] is, and the simulation starts its nodes on construction. Every node
 * holds a durable id named after itself until [DurableRestartRig.handId] says otherwise; every id
 * not in [learners] votes.
 */
private suspend fun TestScope.durableRig(ids: List<NodeId>, learners: Set<NodeId> = emptySet()): DurableRestartRig {
    val stores = ids.associateWith { InMemoryDurableStore() }
    val opened = stores.mapValues { (_, store) -> DurableStoreRaftStorage.open(store) }
    return DurableRestartRig(this, ids, learners, stores, opened)
}

/**
 * The restart harness over [RaftSimulation]. Failures are captured by a [SupervisorJob] and handler
 * rather than propagated, because a [ClientIdCollisionException] from one node's actor loop is the
 * thing under observation and must not tear down the test.
 */
private class DurableRestartRig(
    test: TestScope,
    ids: List<NodeId>,
    learners: Set<NodeId>,
    stores: Map<NodeId, InMemoryDurableStore>,
    opened: Map<NodeId, DurableStoreRaftStorage>,
) {
    val failures = mutableListOf<Throwable>()
    private val stores = stores.toMutableMap()
    private val storages = opened.toMutableMap()
    private val clientIds = ids.associateWith { ClientId(it.value) }.toMutableMap()
    private val nodeScopes = mutableMapOf<NodeId, CoroutineScope>()
    private val walked = mutableMapOf<NodeId, MutableList<Committed>>()

    private val capturing = CoroutineScope(
        test.backgroundScope.coroutineContext +
            SupervisorJob(test.backgroundScope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, e -> failures += e },
    )
    private val background = test.backgroundScope
    private val config = fastRaftConfig()
    private val cluster = ClusterConfig(voters = (ids - learners).toSet(), learners = learners)

    val sim = RaftSimulation(
        nodeIds = ids,
        scope = test,
        nodeScope = capturing,
        nodeFactory = { id, transport, _, child ->
            val node = child.raftNode(
                cluster,
                transport,
                storages.getValue(id),
                config,
                ClientIdentity.Durable(clientIds.getValue(id)),
            )
            val log = mutableListOf<Committed>()
            walked[id] = log
            nodeScopes[id] = child
            child.launch { node.committed.collect { log += it } }
            node
        },
    )

    fun node(id: NodeId): RaftNode = sim.nodes.getValue(id)

    /**
     * Runs a proposal apart from the node scopes, for when its answer may never come. A proposer
     * crashing out from under its own call ends it with [LeadershipLostException], which here is the
     * expected end of the caller, not a failure of the node under observation.
     */
    fun launch(proposal: suspend () -> Unit) {
        background.launch {
            try {
                proposal()
            } catch (_: LeadershipLostException) {
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /** The operator's mistake: [to]'s next incarnation is handed the durable id [of] already holds. */
    fun handId(of: NodeId, to: NodeId) {
        clientIds[to] = clientIds.getValue(of)
    }

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

    /** Lose [id]'s storage, as a process that kept its log only in memory does. */
    fun wipe(id: NodeId) {
        stores[id] = InMemoryDurableStore()
    }

    suspend fun storedEntriesOf(id: NodeId): List<LogEntry> = storages.getValue(id).entries()

    suspend fun storedKeysOf(id: NodeId): List<DedupKey> = storedEntriesOf(id).mapNotNull { it.dedupKey }

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

    /** Own-id keys the current incarnation of [id] surfaced, in commit order. */
    fun keysWalkedBy(id: NodeId): List<DedupKey> =
        walked.getValue(id).filterIsInstance<Committed.Entry>()
            .mapNotNull { it.entry.dedupKey }
            .filter { it.clientId == clientIds.getValue(id) }

    /** Every command the current incarnation of [id] surfaced, in commit order. */
    fun commandsWalkedBy(id: NodeId): List<String> =
        walked.getValue(id).filterIsInstance<Committed.Entry>().map { it.entry.command.decodeToString() }

    /** Those of [commandsWalkedBy] a consumer folding one [ClientSessionTable] would apply. */
    fun commandsAppliedBy(id: NodeId): List<String> {
        val table = ClientSessionTable()
        return walked.getValue(id).filterIsInstance<Committed.Entry>()
            .filter { table.shouldApply(it.entry.dedupKey) }
            .map { it.entry.command.decodeToString() }
    }

    fun assertStayedUpHavingWalked(id: NodeId, serials: List<Long>, prefix: String = "") {
        assertAll(
            { assertEquals(emptyList(), failures.map { it.toString() }, "${prefix}no node may have failed") },
            { assertTrue(nodeScopes.getValue(id).isActive, "$prefix${id.value}'s scope must still be active") },
            { assertEquals(serials, keysWalkedBy(id).map { it.requestId }, "${prefix}the restarted incarnation's committed stream") },
        )
    }

    fun assertCollisionKilled(id: NodeId, foreignSerial: Long, survivor: NodeId, prefix: String = "") {
        val failure = failures.singleOrNull()
        assertAll(
            { assertIs<ClientIdCollisionException>(failure, "${prefix}exactly one failure, a collision: $failures") },
            { assertContains(failure?.message.orEmpty(), "foreign serial $foreignSerial", message = "${prefix}the serial it names") },
            { assertFalse(nodeScopes.getValue(id).isActive, "$prefix${id.value} must have died of it") },
            { assertTrue(nodeScopes.getValue(survivor).isActive, "$prefix${survivor.value} must not have") },
        )
    }
}
