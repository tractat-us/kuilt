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
 * entries its earlier incarnation issued. Those must not read as a second live writer (#3060). The
 * caller hands over the floor that says so, and each arm models that caller: [DurableRestartRig.issue]
 * commits the serial as the new floor before it proposes.
 *
 * Every arm runs real nodes over [DurableStoreRaftStorage] on an in-memory store, reopened per
 * incarnation. Each asserts its own rig: the restarted incarnation's `committed` stream must carry
 * the old serials, which is the only evidence that replay re-walked them rather than skipping them.
 */
class DurableIdentityRestartTest {

    // ── Replay is not a collision ─────────────────────────────────────────────

    @Test
    fun singleVoterReplaysItsOwnSerials() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val last = (1L..3L).map { rig.issue(SOLO_ID, it) }.last()
        rig.restart(SOLO_ID)

        rig.awaitReplay(SOLO_ID, through = last.index)
        rig.assertStayedUpHavingWalked(SOLO_ID, listOf(1L, 2L, 3L))
    }

    @Test
    fun restartedFollowerReplaysItsOwnForwardedEntries() = raftRunTest {
        val rig = durableRig(TRIO)
        val leader = rig.sim.awaitLeader()
        val followerId = TRIO.first { rig.sim.nodes[it] !== leader }

        val last = (1L..3L).map { rig.issue(followerId, it) }.last()
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

        val last = (1L..3L).map { rig.issue(leaderId, it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.restart(leaderId)

        rig.awaitReplay(leaderId, through = last.index)
        rig.assertStayedUpHavingWalked(leaderId, listOf(1L, 2L, 3L))
    }

    @Test
    fun wholeClusterRestartReplaysEveryNodesOwnEntries() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()

        val indices = TRIO.map { id -> (1L..2L).map { rig.issue(id, it).index }.last() }
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

        val entries = (1L..4L).map { rig.issue(SOLO_ID, it) }
        rig.compactThrough(SOLO_ID, entries[1].index)
        rig.restart(SOLO_ID)

        rig.awaitReplay(SOLO_ID, through = entries.last().index)
        // Serials 1 and 2 went with the prefix: the rig is what proves the snapshot really cut them.
        rig.assertStayedUpHavingWalked(SOLO_ID, listOf(3L, 4L))
    }

    /** The control. Compacting past every own entry leaves replay nothing to walk. */
    @Test
    fun aSnapshotCoveringEveryOwnEntryLeavesNothingToReplay() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val entries = (1L..3L).map { rig.issue(SOLO_ID, it) }
        rig.compactThrough(SOLO_ID, entries.last().index)
        rig.restart(SOLO_ID)

        rig.sim.awaitLeader()
        rig.assertStayedUpHavingWalked(SOLO_ID, emptyList())
    }

    // ── What the floor reaches that retained storage cannot ───────────────────

    /**
     * Retained storage cannot tell this node about a serial it never received. The earlier
     * incarnation forwarded the proposal and died before replication reached it, which is the crash
     * a durable id exists to survive. The floor was written ahead of that forward, so the catch-up is
     * this node's own history, and the retry the crash calls for lands on the committed entry.
     */
    @Test
    fun aForwardInFlightAcrossACrashIsNotASecondWriter() = raftRunTest {
        val rig = durableRig(TRIO)
        val leader = rig.sim.awaitLeader()
        val leaderId = TRIO.first { rig.sim.nodes[it] === leader }
        val followerId = TRIO.first { it != leaderId }
        val before = rig.issue(leaderId, 1L)
        rig.sim.awaitCommit(before.index)

        rig.sim.dropLink(from = leaderId, to = followerId)
        rig.launch { rig.issue(followerId, 1L) }
        rig.sim.awaitTrue("the leader to commit the forwarded proposal") {
            leader.commitIndex.value > before.index
        }
        val retained = rig.storedKeysOf(followerId)
        rig.stop(followerId)
        rig.sim.heal()
        rig.start(followerId)
        rig.awaitReplay(followerId, through = before.index + 1)

        assertEquals(emptyList(), retained.filter { it.clientId == ClientId(followerId.value) }, "the rig: never received")
        rig.assertStayedUpHavingWalked(followerId, listOf(1L))
        assertEquals(before.index + 1, rig.issue(followerId, 1L).index, "the retry lands on the committed entry")
    }

    /**
     * A node that retained nothing and the never-issued follower in
     * `RaftEngineDedupIntegrationTest.sharedDurableClientIdSurfacesCollision` make the same
     * observation: an empty log, then their own id at serial 1. Only the floor tells them apart. This
     * learner over a wiped store is the shape of a restarted `clusterClient`, whose real form is
     * `ClusterClientDurableRestartE2ETest` in `:examples`.
     */
    @Test
    fun aWipedDurableLearnerTakesItsOwnHistoryFromItsFloor() = raftRunTest {
        val client = NodeId("client")
        val rig = durableRig(TRIO + client, learners = setOf(client))
        rig.sim.awaitLeader()

        val last = (1L..2L).map { rig.issue(client, it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.stop(client)
        rig.wipe(client)
        rig.start(client)
        rig.awaitReplay(client, through = last.index)

        rig.assertStayedUpHavingWalked(client, listOf(1L, 2L))
        val next = rig.issue(client, 3L)
        rig.sim.awaitCommit(next.index)
        assertEquals(listOf("cmd-1", "cmd-2", "cmd-3"), rig.commandsAppliedBy(client))
    }

    /** Compaction cuts the last record of the id in the log. The caller's floor outlives it. */
    @Test
    fun aSnapshotCoveringEveryEntryLeavesTheNextCommandFresh() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val entries = (1L..3L).map { rig.issue(SOLO_ID, it) }
        val table = ClientSessionTable()
        entries.forEach { table.shouldApply(it.dedupKey) }
        rig.compactThrough(SOLO_ID, entries.last().index)
        rig.restart(SOLO_ID)
        rig.sim.awaitLeader()

        val storedBefore = rig.storedKeysOf(SOLO_ID)
        // The form that used to restart at serial 1 here, and lose this command silently.
        assertFailsWith<IllegalStateException> { rig.node(SOLO_ID).propose(command(4L)) }
        val fresh = rig.issue(SOLO_ID, rig.floorOf(SOLO_ID) + 1)

        assertAll(
            { assertEquals(emptyList(), storedBefore, "the rig: the log held no record") },
            { assertEquals(4L, fresh.dedupKey?.requestId, "the serial resumes above the floor") },
            { assertTrue(table.shouldApply(fresh.dedupKey), "a fresh command must not read as a duplicate") },
        )
    }

    /**
     * The crash this contract exists for, with the next proposal arriving before catch-up: the floor
     * covers the serial still in flight, so the next command draws a fresh one and both apply.
     */
    @Test
    fun aCommandBeforeCatchUpDrawsASerialAboveTheOneInFlight() = raftRunTest {
        val rig = durableRig(TRIO)
        val crash = rig.crashWithSerialFourInFlight(writtenAhead = true)

        val landed = rig.proposeBeforeCatchUp(crash) { rig.issue(crash.followerId, rig.floorOf(crash.followerId) + 1, command(99L)) }

        assertAll(
            { assertEquals(listOf(1L, 2L, 3L), crash.retained, "the rig: serial 4 was in flight at the crash") },
            { assertEquals(listOf(1L, 2L, 3L), landed.storedAtPropose, "the rig: the propose came before catch-up") },
            { assertEquals(5L, landed.entry.dedupKey?.requestId, "the serial sits above the one in flight") },
            { rig.assertStayedUpHavingWalked(crash.followerId, listOf(1L, 2L, 3L, 4L, 5L)) },
            { assertContains(rig.commandsAppliedBy(crash.thirdId), "cmd-4", "the older command applied") },
            { assertContains(rig.commandsAppliedBy(crash.thirdId), "cmd-99", "and so did the new one") },
        )
    }

    /**
     * Surviving the replay is what makes the next serial reachable. A floor below the replayed
     * maximum would reuse one, which is the stale-floor arm below.
     */
    @Test
    fun theNextSerialAboveTheFloorIsNotADuplicate() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()

        val last = (1L..3L).map { rig.issue(SOLO_ID, it) }.last()
        rig.restart(SOLO_ID)
        rig.awaitReplay(SOLO_ID, through = last.index)
        val table = ClientSessionTable()
        val replayed = rig.keysWalkedBy(SOLO_ID)
        replayed.forEach { table.shouldApply(it) }

        val fresh = rig.issue(SOLO_ID, rig.floorOf(SOLO_ID) + 1)

        assertAll(
            { assertEquals(3, replayed.size, "the table must have folded the replay") },
            { assertEquals(4L, fresh.dedupKey?.requestId) },
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

        val entries = (1L..3L).map { rig.issue(SOLO_ID, it) }
        rig.restart(SOLO_ID)
        rig.awaitReplay(SOLO_ID, through = entries.last().index)

        val retried = rig.issue(SOLO_ID, 3L)
        assertFailsWith<RequestIdConflictException> { rig.node(SOLO_ID).propose(command(99L), requestId = 2L) }

        assertAll(
            { assertEquals(emptyList(), rig.failures.map { it.toString() }) },
            { assertEquals(entries.last().index, retried.index, "the retry lands on the replayed entry") },
        )
    }

    // ── One-argument propose ──────────────────────────────────────────────────

    /**
     * A serial the node drew for itself can never be on disk before it is issued, so no floor can
     * cover it. The call is refused before it reaches the actor, which is what the serial after it
     * proves: nothing was drawn or appended, and the node is still up.
     */
    @Test
    fun aOneArgumentProposeIsRefusedUnderADurableId() = raftRunTest {
        val rig = durableRig(SOLO)
        rig.sim.awaitLeader()
        rig.issue(SOLO_ID, 1L)

        val refusal = assertFailsWith<IllegalStateException> { rig.node(SOLO_ID).propose(command(2L)) }
        val next = rig.issue(SOLO_ID, 2L)
        val stored = rig.storedKeysOf(SOLO_ID).map { it.requestId }

        assertAll(
            { assertContains(refusal.message.orEmpty(), "propose(command, requestId)", message = "the refusal names the remedy") },
            { assertEquals(listOf(1L, 2L), stored, "the refusal appended nothing") },
            { assertEquals(2L, next.dedupKey?.requestId) },
            { rig.assertStayedUpHavingWalked(SOLO_ID, listOf(1L, 2L)) },
        )
    }

    /** The same refusal on the forwarding path: the leader must never hear of the refused command. */
    @Test
    fun aOneArgumentProposeOnAFollowerIsNeverForwarded() = raftRunTest {
        val rig = durableRig(TRIO)
        val leader = rig.sim.awaitLeader()
        val leaderId = TRIO.first { rig.sim.nodes[it] === leader }
        val followerId = TRIO.first { it != leaderId }
        val first = rig.issue(followerId, 1L)
        rig.sim.awaitCommit(first.index)

        assertFailsWith<IllegalStateException> { rig.node(followerId).propose(command(2L)) }
        rig.sim.settle()
        val onLeader = rig.storedKeysOf(leaderId).map { it.requestId }

        assertAll(
            { assertEquals(listOf(1L), onLeader, "the rig: serial 1 was forwarded, and only it") },
            { rig.assertStayedUpHavingWalked(followerId, listOf(1L)) },
        )
    }

    @Test
    fun aNegativeFloorIsRefusedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { ClientIdentity.Durable(ClientId("x"), issuedThrough = -1L) }
    }

    // ── What a stale floor gets ───────────────────────────────────────────────

    /**
     * A floor behind what the id issued fails loud, and goes on failing. The retained log no longer
     * raises the mark, so a supervisor restarting on the exception cannot talk itself past it. Only a
     * true floor boots, and it says nothing about who wrote the serials it covers.
     */
    @Test
    fun aStaleFloorDiesOnReplayAtEveryBootUntilItIsCorrected() = raftRunTest {
        val rig = durableRig(TRIO)
        val leader = rig.sim.awaitLeader()
        val holder = TRIO.first { rig.sim.nodes[it] !== leader }
        val survivor = TRIO.first { it != holder }

        val last = (1L..3L).map { rig.issue(holder, it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.setFloor(holder, 1L)

        repeat(2) {
            rig.restart(holder)
            rig.sim.awaitTrue("the replay past the stale floor") { rig.failures.isNotEmpty() }
            rig.assertCollisionKilled(holder, foreignSerial = 2L, survivor = survivor)
            rig.failures.clear()
        }

        rig.setFloor(holder, 3L)
        rig.restart(holder)
        rig.awaitReplay(holder, through = last.index)
        rig.assertStayedUpHavingWalked(holder, listOf(1L, 2L, 3L))
    }

    @Test
    fun aWipedLearnerWithAStaleFloorDiesOnCatchUp() = raftRunTest {
        val client = NodeId("client")
        val rig = durableRig(TRIO + client, learners = setOf(client))
        rig.sim.awaitLeader()

        val last = (1L..2L).map { rig.issue(client, it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.stop(client)
        rig.wipe(client)
        rig.setFloor(client, 1L)
        rig.start(client)
        rig.sim.awaitTrue("the catch-up past the stale floor") { rig.failures.isNotEmpty() }

        rig.assertCollisionKilled(client, foreignSerial = 2L, survivor = TRIO[0])
    }

    /**
     * The contract's quiet half. This caller wrote its floor after the forward, so the crash leaves
     * it one behind. Its next propose raises the mark over the serial in flight before that serial
     * arrives, so nothing throws, and the call reuses it. This leader committed the old entry as a
     * follower, so its cache is cold: it appends, the caller holds a success, and a consumer's table
     * drops the command. `ClientIdentity.Durable` states this as the cost of breaking write-ahead.
     */
    @Test
    fun aStaleFloorCanReuseASerialInFlightAndTheCommandIsDropped() = raftRunTest {
        val rig = durableRig(TRIO)
        val crash = rig.crashWithSerialFourInFlight(writtenAhead = false)

        val landed = rig.proposeBeforeCatchUp(crash) {
            rig.node(crash.followerId).propose(command(99L), requestId = rig.floorOf(crash.followerId) + 1)
        }

        assertAll(
            { assertEquals(3L, rig.floorOf(crash.followerId), "the rig: the floor was one behind") },
            { assertEquals(listOf(1L, 2L, 3L), landed.storedAtPropose, "the rig: the propose came before catch-up") },
            { assertEquals(4L, landed.entry.dedupKey?.requestId, "the propose reuses the in-flight serial") },
            { rig.assertStayedUpHavingWalked(crash.followerId, listOf(1L, 2L, 3L, 4L, 4L)) },
            { assertContains(rig.commandsWalkedBy(crash.thirdId), "cmd-99", "the rig: the command did commit") },
            { assertContains(rig.commandsAppliedBy(crash.thirdId), "cmd-4", "the rig: the table applied the older one") },
            { assertFalse("cmd-99" in rig.commandsAppliedBy(crash.thirdId), "propose returned success and the table drops it") },
        )
    }

    // ── A second live writer is still caught ──────────────────────────────────

    /**
     * A second process handed the id and its floor after a restart. Both replay the shared history
     * without a failure, and the serial past that floor is what gives it away.
     */
    @Test
    fun aSecondLiveWriterStillThrowsAfterARestart() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()
        val (holder, second) = TRIO[0] to TRIO[1]

        val last = (1L..3L).map { rig.issue(holder, it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.restart(holder)
        rig.awaitReplay(holder, through = last.index)
        rig.assertStayedUpHavingWalked(holder, listOf(1L, 2L, 3L))

        rig.handId(of = holder, to = second)
        rig.restart(second)
        rig.awaitReplay(second, through = last.index)
        assertEquals(emptyList(), rig.failures.map { it.toString() }, "the second process replays without a failure")

        // Launched: if the holder leads, it dies inside the commit that would have answered this.
        rig.launch { rig.issue(second, 4L) }
        rig.sim.awaitTrue("the holder to see the foreign serial") { rig.failures.isNotEmpty() }

        rig.assertCollisionKilled(holder, foreignSerial = 4L, survivor = second)
    }

    @Test
    fun aWriterThatCommittedWhileThisNodeWasDownThrowsOnCatchUp() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()
        val (holder, second) = TRIO[0] to TRIO[1]

        val last = (1L..2L).map { rig.issue(holder, it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.stop(holder)

        rig.handId(of = holder, to = second)
        rig.restart(second)
        rig.sim.awaitLeader(among = setOf(second, TRIO[2]))
        rig.issue(second, 3L)

        rig.start(holder)
        rig.sim.awaitTrue("the holder to catch up on the foreign serial") { rig.failures.isNotEmpty() }

        rig.assertCollisionKilled(holder, foreignSerial = 3L, survivor = second)
    }

    /** Handed the id without its floor, a process reads the shared history as foreign at its first entry. */
    @Test
    fun aSecondProcessHandedTheIdButNotItsFloorDiesOnReplay() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()
        val (holder, second) = TRIO[0] to TRIO[1]

        val last = (1L..2L).map { rig.issue(holder, it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.handId(of = holder, to = second)
        rig.setFloor(second, 0L)
        rig.restart(second)
        rig.sim.awaitTrue("the replay of the shared history") { rig.failures.isNotEmpty() }

        rig.assertCollisionKilled(second, foreignSerial = 1L, survivor = holder)
    }

    /**
     * A collision that kills a node no longer teaches it. The foreign entry reached storage before it
     * committed, so the next boot still holds it, but holding it no longer raises the mark. The
     * node dies again on that same serial, so a supervisor restarting on the exception cannot erase
     * the collision. Reds if the retained log ever seeds the mark again.
     */
    @Test
    fun aRestartDoesNotAbsorbAGenuineSecondWritersSerial() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()
        val (holder, second) = TRIO[0] to TRIO[1]
        rig.handId(of = holder, to = second)
        rig.restart(second)

        // Launched: if the second process leads, it dies inside the commit that would answer this.
        rig.launch { rig.issue(holder, 1L) }
        rig.sim.awaitTrue("the second process to see the foreign serial") { rig.failures.isNotEmpty() }
        rig.assertCollisionKilled(second, foreignSerial = 1L, survivor = holder)
        assertEquals(1, rig.storedKeysOf(second).size, "the rig: the foreign entry reached storage")

        repeat(2) {
            rig.failures.clear()
            rig.restart(second)
            rig.sim.awaitTrue("the boot over the retained foreign serial") { rig.failures.isNotEmpty() }
            rig.assertCollisionKilled(second, foreignSerial = 1L, survivor = holder)
        }
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

    // ── What the floor does not reach, tracked by #3081 ───────────────────────
    //
    // Each `knownLimit` arm asserts today's wrong answer, so that the change that fixes it reds here
    // and comes to look. None of them is a guarantee. Assertions on the wrong answer say so in their
    // message; the ones marked "the rig" prove the arm reached the state it is about.

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

        val last = (1L..3L).map { rig.issue(holder, it) }.last()
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

    /**
     * The floor does not help two writers holding the same one. Each draws the next serial, each
     * raises its own mark over it first, and neither sees a foreign serial when both commit. Both
     * calls return success, and a consumer's table applies one command.
     */
    @Test
    fun knownLimitTwoWritersDrawingTheSameNextSerialGoUnseen() = raftRunTest {
        val rig = durableRig(TRIO)
        rig.sim.awaitLeader()
        val (holder, second) = TRIO[0] to TRIO[1]
        val third = TRIO[2]

        val last = (1L..3L).map { rig.issue(holder, it) }.last()
        rig.sim.awaitCommit(last.index)
        rig.handId(of = holder, to = second)
        rig.restart(second)
        rig.sim.awaitCommit(last.index)

        val oldLeader = rig.sim.awaitLeader()
        if (rig.sim.nodes[third] !== oldLeader) oldLeader.transferLeadership(third)
        rig.sim.awaitLeader(among = setOf(third))

        val fromHolder = CompletableDeferred<LogEntry>()
        val fromSecond = CompletableDeferred<LogEntry>()
        rig.launch { fromHolder.complete(rig.issue(holder, 4L, command(41L))) }
        rig.launch { fromSecond.complete(rig.issue(second, 4L, command(42L))) }
        rig.sim.awaitTrue("both proposals to return") { fromHolder.isCompleted && fromSecond.isCompleted }
        rig.sim.awaitCommit(maxOf(fromHolder.await().index, fromSecond.await().index))

        assertAll(
            { assertEquals(emptyList(), rig.failures.map { it.toString() }, "${KNOWN_WRONG}neither writer saw the other") },
            { assertContains(rig.commandsWalkedBy(third), "cmd-41", "the rig: the first command did commit") },
            { assertContains(rig.commandsWalkedBy(third), "cmd-42", "the rig: so did the second") },
            {
                assertEquals(
                    1,
                    listOf("cmd-41", "cmd-42").count { it in rig.commandsAppliedBy(third) },
                    "${KNOWN_WRONG}two acknowledged commands, and the table applies one",
                )
            },
        )
    }

    private companion object {
        val SOLO_ID = NodeId("solo")
        val SOLO = listOf(SOLO_ID)
        val TRIO = listOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
        const val KNOWN_WRONG = "known wrong, tracked by #3081: "
    }
}

private fun command(serial: Long): ByteArray = "cmd-$serial".encodeToByteArray()

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

    /** What each node's caller holds in its own store. It outlives [wipe], as it must. */
    private val floors = ids.associateWith { 0L }.toMutableMap()
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
                ClientIdentity.Durable(clientIds.getValue(id), issuedThrough = floors.getValue(id)),
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

    /** The caller's half of the contract: commit [requestId] as the new floor, then propose it. */
    suspend fun issue(id: NodeId, requestId: Long, bytes: ByteArray = command(requestId)): LogEntry {
        floors[id] = maxOf(floors.getValue(id), requestId)
        return node(id).propose(bytes, requestId)
    }

    fun floorOf(id: NodeId): Long = floors.getValue(id)

    /** A caller whose store lags or was restored from an old copy. Takes effect at [id]'s next boot. */
    fun setFloor(id: NodeId, floor: Long) {
        floors[id] = floor
    }

    /** The operator's mistake: [to]'s next incarnation is handed the durable id [of] holds, and its floor. */
    fun handId(of: NodeId, to: NodeId) {
        clientIds[to] = clientIds.getValue(of)
        floors[to] = floors.getValue(of)
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

    /**
     * Crashes a follower with serial 4 forwarded and committed elsewhere, but never received. The
     * follower is back up and cut off from the leader when this returns. [writtenAhead] says whether
     * its caller committed the floor before that forward, as the contract asks, or only after, which
     * the crash stopped from happening.
     */
    suspend fun crashWithSerialFourInFlight(writtenAhead: Boolean): CrashedForward {
        val leader = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val (followerId, thirdId) = sim.nodes.keys.filter { it != leaderId }
        val durable = (1L..3L).map { issue(followerId, it) }.last()
        sim.awaitCommit(durable.index)

        sim.dropLink(from = leaderId, to = followerId)
        launch {
            if (writtenAhead) issue(followerId, 4L) else node(followerId).propose(command(4L), requestId = 4L)
        }
        sim.awaitCommit(durable.index + 1, on = listOf(leaderId, thirdId))
        val retained = storedKeysOf(followerId).map { it.requestId }
        restart(followerId)
        return CrashedForward(leader, followerId, thirdId, retained)
    }

    /**
     * Gets [proposal] in ahead of catch-up: it reaches the actor while the leader is unreachable, then
     * leadership moves to a node whose dedup cache is cold, and only then does replication resume.
     */
    suspend fun proposeBeforeCatchUp(crash: CrashedForward, proposal: suspend () -> LogEntry): LandedProposal {
        val fresh = CompletableDeferred<LogEntry>()
        launch { fresh.complete(proposal()) }
        sim.settle()
        val storedAtPropose = storedKeysOf(crash.followerId).map { it.requestId }
        crash.leader.transferLeadership(crash.thirdId)
        sim.awaitLeader(among = setOf(crash.thirdId))
        sim.heal()
        sim.awaitTrue("the propose to return") { fresh.isCompleted }
        val entry = fresh.await()
        sim.awaitCommit(entry.index)
        return LandedProposal(entry, storedAtPropose)
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

private class CrashedForward(val leader: RaftNode, val followerId: NodeId, val thirdId: NodeId, val retained: List<Long>)

private class LandedProposal(val entry: LogEntry, val storedAtPropose: List<Long>)
