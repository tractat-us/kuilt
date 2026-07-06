@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Safety regressions for the missing staleness guard in `RaftEngine.finalizeInstalledSnapshot`
 * (epic #1218, sub-issues #1219 + #1220). A fully-reassembled snapshot was finalized unconditionally,
 * with no check that it advances the receiver's applied frontier — so a snapshot at or below
 * [RaftNode.commitIndex] regressed state instead of being acked and ignored (Raft Fig 13 rule 5).
 *
 * Both tests drive a real 3-node cluster through the canonical [RaftSimulation] harness: they let a
 * follower catch up and compact legitimately, then inject — via [RaftSimulation.deliverInstallSnapshot],
 * bypassing partition/drop rules — a stale/behind-commit InstallSnapshot that the leader would never
 * have needed to send, exactly the delayed/duplicate message Raft's model requires tolerating. The
 * follower is partitioned off first so no real leader traffic repairs (or masks) the injected
 * corruption before the assertion; the injected message reaches the actor loop via the bounded
 * `delay(20)` idiom (cf. `MatchIndexClampTest`).
 */
class FinalizeInstalledSnapshotGuardTest {

    /**
     * #1219 — a delayed/duplicate InstallSnapshot for an index **below** the follower's compaction
     * floor (same term → passes the term guard) must not touch storage/log. Without the guard,
     * `entryAt(staleIndex)` is null → the discard-whole branch runs `truncateFrom(0)` / `log.clear()`,
     * wiping the retained committed suffix and regressing `snapshotIndex`; `saveSnapshot` also
     * overwrites the newer stored snapshot with the older one.
     */
    @Test
    fun staleDuplicateInstallSnapshotBelowFloor_doesNotWipeLog() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val followerId = sim.nodeIds.first { it != leaderId }
        val follower = sim.nodes.getValue(followerId)

        // Replicate a healthy committed log to the whole cluster.
        repeat(30) { leader.propose(byteArrayOf(1)) }
        val commit = leader.commitIndex.value
        sim.awaitCommit(commit, on = sim.nodeIds)

        // The follower compacts past most of its log, retaining a committed suffix (floor+1..commit).
        val floor = commit - 6
        follower.snapshots.value = Snapshot(floor, sim.stateBytes(followerId, floor))
        sim.awaitTrue("follower compacted to $floor") { follower.compactionFloor.value == floor }

        // Isolate the follower, then inject a stale DUPLICATE InstallSnapshot below the floor.
        sim.partitionOff(followerId)
        val term = sim.storages.getValue(leaderId).term()
        val staleIndex = floor - 5
        sim.deliverInstallSnapshot(
            to = followerId, from = leaderId, term = term,
            lastIncludedIndex = staleIndex, lastIncludedTerm = 1L,
        )
        delay(20) // bounded: let the follower's actor drain the injected message (cf. MatchIndexClampTest)

        // Hoist the suspend storage reads out of the (non-suspend) assertAll lambdas.
        val survivedSuffix = sim.storages.getValue(followerId).entries(floor + 1).map { it.index }
        val storedSnapshotIndex = sim.storages.getValue(followerId).loadSnapshot()?.meta?.lastIncludedIndex
        assertAll(
            {
                assertEquals(
                    floor, follower.compactionFloor.value,
                    "compaction floor must not regress when a stale snapshot below it is (re)delivered",
                )
            },
            {
                assertEquals(
                    floor, storedSnapshotIndex,
                    "a stale snapshot must not overwrite the newer stored snapshot",
                )
            },
            {
                assertEquals(
                    ((floor + 1)..commit).toList(), survivedSuffix,
                    "the retained committed log suffix must survive a stale InstallSnapshot",
                )
            },
        )
    }

    /**
     * #1220 — a behind-commit InstallSnapshot whose `lastIncludedIndex` is still in the retained log
     * and matches the boundary term takes the retain-suffix branch (correct: commit is not regressed),
     * but it must NOT emit `Committed.Install`. That emit would reset the consumer's state machine back
     * to `state@lastIncludedIndex`; the gap `(lastIncludedIndex, commitIndex]` is never re-delivered
     * (`advanceCommit` only re-emits above `commitIndex`), leaving permanent per-node divergence.
     */
    @Test
    fun behindCommitInstallSnapshot_doesNotEmitInstall() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val followerId = sim.nodeIds.first { it != leaderId }
        val follower = sim.nodes.getValue(followerId)

        repeat(30) { leader.propose(byteArrayOf(1)) }
        val commit = leader.commitIndex.value
        sim.awaitCommit(commit, on = sim.nodeIds)

        // Follower compacts to a low floor, keeping a long committed suffix in the live log.
        val floor = 10L
        follower.snapshots.value = Snapshot(floor, sim.stateBytes(followerId, floor))
        sim.awaitTrue("follower compacted to $floor") { follower.compactionFloor.value == floor }

        // Record installs BEFORE injecting; the follower self-compacted (no Install), so this is empty.
        val installs = sim.collectInstalls(followerId)
        sim.settle() // ensure the collector is subscribed before we inject
        sim.partitionOff(followerId)

        // A behind-commit snapshot whose boundary is in (floor, commit], entry present & term-matching
        // → retain-suffix branch. It is <= commit, so it must not reset the state machine.
        val term = sim.storages.getValue(leaderId).term()
        val boundaryIndex = floor + 5
        val boundaryTerm = sim.storages.getValue(followerId).entries(boundaryIndex)
            .first { it.index == boundaryIndex }.term
        sim.deliverInstallSnapshot(
            to = followerId, from = leaderId, term = term,
            lastIncludedIndex = boundaryIndex, lastIncludedTerm = boundaryTerm,
        )
        delay(20)

        assertAll(
            {
                assertTrue(
                    installs.isEmpty(),
                    "a behind-commit snapshot must not emit Committed.Install (would reset the state machine " +
                        "backward and lose (${boundaryIndex}, $commit]): $installs",
                )
            },
            {
                assertTrue(
                    follower.commitIndex.value >= commit,
                    "commit index must not regress below $commit; was ${follower.commitIndex.value}",
                )
            },
        )
    }
}
