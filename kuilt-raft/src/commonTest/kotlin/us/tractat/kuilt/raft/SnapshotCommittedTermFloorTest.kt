@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for #1910: an `InstallSnapshot` whose `lastIncludedTerm` is **below the term of the
 * recipient's own entry at `commitIndex`** must be dropped.
 *
 * `snapshotChunkRefusal` (#1868) bounds the metadata against the *frame's own* fields
 * (`lastIncludedTerm <= term`, both halves under the plausibility ceilings). Nothing compared it
 * against the recipient's local state, so a stale, replayed or forged frame naming a *real earlier
 * term* — perfectly well-formed — reached `finalizeInstalledSnapshot`, took the discard-whole branch
 * (`entryAt` is null above the log), wiped the log, and fabricated a commit index above the victim's
 * committed frontier.
 *
 * **Soundness of the floor.** If a snapshot legitimately covers an index above our commit frontier
 * `C`, then by Leader Completeness the sender's log holds our committed entry at `C` (the frame
 * cleared the stale-term check, so the sender's term is at least ours, hence at least the term in
 * which `C` was committed), and terms are non-decreasing along a log, so its `lastIncludedTerm` is at
 * or above `C`'s term. The floor therefore never rejects a legitimate snapshot — which is what
 * [snapshotExactlyAtTheCommittedFloorStillInstalls] and
 * [snapshotAboveTheCommittedFloorStillInstalls] pin from the other side.
 *
 * **The vacuity trap this fixture exists to avoid.** A fresh node has `commitIndex == 0` and
 * `snapshotTerm == 0`, so the floor is `0` and the check is trivially satisfied: a test written on a
 * fresh node passes before *and* after the fix and proves nothing. [clusterCommittedAboveTermOne]
 * therefore crashes the term-1 leader and lets the survivors commit under a later term, so the
 * victim holds a genuinely committed entry at a genuinely non-zero term, and the forgery can name a
 * real earlier term rather than the degenerate `0`.
 *
 * **What this does NOT establish.** The floor stops a *low*-term forgery and a stale/replayed/buggy
 * sender. The §5.4.1 domination lever of #1876 wants a *high* `lastIncludedTerm` and the attacker
 * picks the term, so this is bounded hardening, not a fix for the Byzantine case (#1876/#1907), and
 * not the leader-identity check that is blocked on #1906.
 */
internal class SnapshotCommittedTermFloorTest {

    /** A snapshot boundary comfortably above the victim's commit frontier — the attack's shape. */
    private val jumpAhead = 50L

    /**
     * A converged 3-node cluster whose *surviving* nodes committed entries under a term above 1,
     * with one of them ([victimId]) partitioned off and ready to receive an injected frame.
     */
    private class Fixture(
        val sim: RaftSimulation,
        val senderId: NodeId,
        val victimId: NodeId,
        val victim: RaftNode,
        /** The victim's commit frontier — the index the floor is read at. */
        val commit: Long,
        /** The term of the victim's own committed entry at [commit] — the floor itself. */
        val floorTerm: Long,
        val appliedBefore: ByteArray,
        val logBefore: List<Long>,
        val storedSnapshotBefore: SnapshotMeta?,
    )

    /**
     * Build the fixture: elect, commit under term 1, crash that leader so the survivors elect and
     * commit again under a higher term, then isolate the victim.
     *
     * The victim is partitioned off (as in `InstallSnapshotMetaValidationTest`) so live leader
     * traffic cannot repair — or mask — the injected frame's effect before the assertions run;
     * `deliverInstallSnapshot` bypasses the partition.
     */
    private suspend fun TestScope.clusterCommittedAboveTermOne(): Fixture {
        val sim = raftSim(this, backgroundScope, n = 3)
        val first = awaitLeader(sim)
        val firstId = sim.nodes.entries.first { it.value === first }.key
        repeat(3) { sim.proposeOnLeader(byteArrayOf(1)) }

        // Crash the term-1 leader: the two survivors elect at a higher term and commit fresh entries
        // under it. Without this the committed floor would be term 1 and the negative case could not
        // name a *real* earlier term as its forgery.
        sim.crash(firstId)
        val survivors = sim.nodeIds.filter { it != firstId }.toSet()
        val second = sim.awaitLeader(among = survivors)
        val senderId = sim.nodes.entries.first { it.value === second }.key
        val lastProposed = (1..3).map { sim.proposeOnLeader(byteArrayOf(2), among = survivors) }.last()
        sim.awaitCommit(lastProposed.index, on = survivors)

        val victimId = survivors.first { it != senderId }
        val victim = sim.nodes.getValue(victimId)
        val commit = victim.commitIndex.value
        val floorTerm = sim.storages.getValue(victimId).entries(commit).first { it.index == commit }.term
        assertTrue(
            floorTerm > 1L,
            "fixture must commit above term 1 or the floor is vacuous and the test proves nothing " +
                "(floorTerm=$floorTerm at commit=$commit)",
        )

        val fixture = Fixture(
            sim = sim,
            senderId = senderId,
            victimId = victimId,
            victim = victim,
            commit = commit,
            floorTerm = floorTerm,
            appliedBefore = sim.appliedState(victimId),
            logBefore = sim.storages.getValue(victimId).entries(1L).map { it.index },
            storedSnapshotBefore = sim.storages.getValue(victimId).loadSnapshot()?.meta,
        )
        sim.partitionOff(victimId)
        return fixture
    }

    /**
     * The negative case: `lastIncludedIndex` above the victim's `commitIndex` (so the `<= commit`
     * staleness guard does not fire) with `lastIncludedTerm` one below the committed floor — a real,
     * previously-legal term, so every frame-internal bound of #1868 is satisfied.
     *
     * Asserted on four independent surfaces plus the applied state machine, because on unfixed code
     * they all move together: the durable log is wiped by `truncateFrom(0)`, the compaction floor and
     * commit index jump to the fabricated boundary, a snapshot is stored, and `Committed.Install`
     * resets the consumer's state machine to the frame's payload bytes.
     */
    @Test
    fun snapshotBelowTheCommittedFloorIsDropped() = raftRunTest {
        val f = clusterCommittedAboveTermOne()
        val installs = f.sim.collectInstalls(f.victimId)
        f.sim.settle() // ensure the collector is subscribed before we inject

        f.sim.deliverInstallSnapshot(
            to = f.victimId,
            from = f.senderId,
            // The victim's OWN current term: the frame is stale-term-clean and passes the §5.2
            // leader-authority gate, so nothing but the committed floor stands before the install.
            term = f.sim.storages.getValue(f.victimId).term(),
            lastIncludedIndex = f.commit + jumpAhead,
            lastIncludedTerm = f.floorTerm - 1L,
            data = byteArrayOf(9, 9, 9),
        )
        delay(20) // bounded: let the victim's actor drain the injected frame (cf. MatchIndexClampTest)

        // Hoist the suspend storage reads out of the (non-suspend) assertAll lambdas.
        val logAfter = f.sim.storages.getValue(f.victimId).entries(1L).map { it.index }
        val storedSnapshotAfter = f.sim.storages.getValue(f.victimId).loadSnapshot()?.meta
        val appliedAfter = f.sim.appliedState(f.victimId)
        assertAll(
            {
                assertEquals(
                    f.logBefore, logAfter,
                    "a snapshot below the committed floor (term ${f.floorTerm - 1} < ${f.floorTerm}) must not " +
                        "wipe the durable log via the discard-whole branch",
                )
            },
            {
                assertEquals(
                    f.commit, f.victim.commitIndex.value,
                    "commitIndex must not be fabricated from a snapshot whose term is below our committed floor",
                )
            },
            {
                assertEquals(
                    0L, f.victim.compactionFloor.value,
                    "the compaction floor must not jump to the rejected snapshot's lastIncludedIndex",
                )
            },
            {
                assertEquals(
                    f.storedSnapshotBefore, storedSnapshotAfter,
                    "a rejected snapshot must not be written to durable storage",
                )
            },
            {
                assertContentEquals(
                    f.appliedBefore, appliedAfter,
                    "the applied state machine must not be reset to the rejected snapshot's payload",
                )
            },
            {
                assertTrue(
                    installs.isEmpty(),
                    "a rejected snapshot must not emit Committed.Install: $installs",
                )
            },
        )
    }

    /**
     * Positive control at the boundary: `lastIncludedTerm` **exactly** at the committed floor still
     * installs. This is what proves the floor is not over-tight — a legitimate leader whose snapshot
     * boundary lands in the same term as our commit frontier is the tightest honest case, and the
     * floor must admit it.
     */
    @Test
    fun snapshotExactlyAtTheCommittedFloorStillInstalls() = raftRunTest {
        val f = clusterCommittedAboveTermOne()
        val installs = f.sim.collectInstalls(f.victimId)
        f.sim.settle()
        val boundary = f.commit + jumpAhead
        val payload = byteArrayOf(7, 7)

        f.sim.deliverInstallSnapshot(
            to = f.victimId,
            from = f.senderId,
            term = f.sim.storages.getValue(f.victimId).term(),
            lastIncludedIndex = boundary,
            lastIncludedTerm = f.floorTerm,
            data = payload,
        )
        delay(20)

        assertAll(
            {
                assertEquals(
                    boundary, f.victim.compactionFloor.value,
                    "a snapshot at exactly the committed floor term is legitimate and must install",
                )
            },
            {
                assertEquals(
                    boundary, f.victim.commitIndex.value,
                    "an installed snapshot must advance the commit frontier to its boundary",
                )
            },
            {
                assertContentEquals(
                    payload, f.sim.appliedState(f.victimId),
                    "an installed snapshot must reset the state machine to its payload",
                )
            },
            {
                assertEquals(
                    listOf(boundary), installs.map { it.snapshot.throughIndex },
                    "exactly one Committed.Install, at the snapshot's boundary",
                )
            },
        )
    }

    /**
     * Second positive control: the ordinary catch-up shape — a later-term leader whose snapshot
     * boundary carries a term strictly *above* our committed floor — still installs. Guards against
     * a floor keyed to the wrong side of the comparison, and against the refuted original framing of
     * #1910 (reject when `entryAt(lastIncludedIndex)` is null) sneaking back in: `entryAt` is null
     * here, which is the normal far-behind catch-up path, not a fail-open.
     */
    @Test
    fun snapshotAboveTheCommittedFloorStillInstalls() = raftRunTest {
        val f = clusterCommittedAboveTermOne()
        val installs = f.sim.collectInstalls(f.victimId)
        f.sim.settle()
        val boundary = f.commit + jumpAhead
        // A leader one term ahead of our committed floor (and of the victim, so the frame is not stale).
        val laterTerm = maxOf(f.sim.storages.getValue(f.victimId).term(), f.floorTerm + 1L)

        f.sim.deliverInstallSnapshot(
            to = f.victimId,
            from = f.senderId,
            term = laterTerm,
            lastIncludedIndex = boundary,
            lastIncludedTerm = laterTerm,
            data = byteArrayOf(5),
        )
        delay(20)

        assertAll(
            {
                assertEquals(
                    boundary, f.victim.compactionFloor.value,
                    "an ordinary far-behind catch-up from a later-term leader must still install",
                )
            },
            {
                assertEquals(
                    boundary, f.victim.commitIndex.value,
                    "an installed catch-up snapshot must advance the commit frontier",
                )
            },
            {
                assertEquals(
                    listOf(boundary), installs.map { it.snapshot.throughIndex },
                    "exactly one Committed.Install, at the snapshot's boundary",
                )
            },
        )
    }
}
