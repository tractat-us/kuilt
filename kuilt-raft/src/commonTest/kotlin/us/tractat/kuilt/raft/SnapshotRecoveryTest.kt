@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Restart recovery: a node constructed over storage that already holds a persisted snapshot must
 * seed its snapshot baseline (`snapshotIndex`/`compactionFloor`) and raise `commitIndex` to that
 * baseline — a snapshot is by definition committed. Without this, a restarted node would treat the
 * compacted prefix as un-committed and re-derive a floor of 0.
 */
class SnapshotRecoveryTest {

    @Test
    fun nodeRestart_recoversSnapshotBaseline_andReplaysInstallThenTail() = raftRunTest {
        val storage = InMemoryRaftStorage()
        // A prior life: a durable snapshot through index 7 (term 3) plus uncompacted entries 8, 9.
        // The persisted term is seeded alongside them (#1887): `storage.term() >= max(log entry terms)`
        // is an invariant of every append path — `persistTermAndVote` is storage-first and runs before
        // `storage.appendEntries` — so a node cannot hold term-3 entries while recorded at term 0.
        // Seeding only the entries produced a state no node can reach, which the restore now refuses.
        storage.saveTermAndVotedFor(3L, null)
        storage.saveSnapshot(SnapshotMeta(7L, 3L), byteArrayOf(1, 2, 3))
        storage.appendEntries(
            listOf(LogEntry(8L, 3L, byteArrayOf(8)), LogEntry(9L, 3L, byteArrayOf(9))),
        )

        // Restart: a fresh node over the pre-loaded storage.
        val h = singleVoterNode(backgroundScope, storage)
        val node = h.node

        // The single voter self-elects and commits its own-term no-op, pulling 8 and 9 to committed.
        h.awaitCommit(9L)

        // Recovery seeded the compaction floor from the persisted snapshot (0 without recovery).
        assertEquals(7L, node.compactionFloor.value, "compaction floor recovered from persisted snapshot")
        assertTrue(node.commitIndex.value >= 7L, "commitIndex recovered to >= snapshot baseline")

        // Resuming below the floor leads with the stored snapshot, then entries 8, 9 in order.
        val seen = node.committedFrom(1L).take(3).toList()
        assertEquals(
            Snapshot(7L, byteArrayOf(1, 2, 3)),
            (seen[0] as Committed.Install).snapshot,
            "committedFrom below the floor leads with the recovered snapshot",
        )
        assertEquals(listOf(8L, 9L), seen.drop(1).map { (it as Committed.Entry).entry.index })
    }

    /**
     * Crash-window recovery (#1221): [RaftStorage.saveSnapshot]'s KDoc mandates snapshot-durable-first
     * and promises that a crash *between* the snapshot write and `discardLogPrefix` "leaves the snapshot
     * plus the full log — redundant but safe and recoverable". This constructs exactly that state — a
     * durable snapshot@100 AND the un-discarded full log 1..150 — and proves recovery honours the
     * promise: the restore must filter the compacted prefix (entries with `index <= snapshotIndex`) out
     * of the in-memory log so the positional log math stays correct.
     *
     * All log access is positional — `entryAt(i) == log[i - snapshotIndex - 1]` — which assumes the
     * in-memory list begins at `snapshotIndex + 1`. If the restore loaded `entries()` **unfiltered**, the
     * list would begin at index 1, and `entryAt(120)` would silently resolve `log[120 - 100 - 1] = log[19]`
     * = the entry with index **20**, not 120: wrong prevTerm, wrong conflict checks, wrong applies. This
     * test observes that positional read through the public `committedFrom` seam.
     */
    @Test
    fun nodeRestart_filtersCompactedPrefix_whenLogDiscardCrashedMidWay() = raftRunTest {
        val storage = InMemoryRaftStorage()
        // A prior life that crashed in the saveSnapshot→discardLogPrefix window: the snapshot through
        // index 100 (term 5) is durable, but `discardLogPrefix` never ran — so storage still holds the
        // full log 1..150 alongside the snapshot. The persisted term is seeded alongside them for the
        // reason given in the test above (#1887): `storage.term() >= max(log entry terms)`.
        storage.saveTermAndVotedFor(5L, null)
        storage.saveSnapshot(SnapshotMeta(100L, 5L), byteArrayOf(1, 2, 3))
        storage.appendEntries((1L..150L).map { LogEntry(it, 5L, byteArrayOf(it.toByte())) })

        // Restart over the crash-window storage.
        val h = singleVoterNode(backgroundScope, storage)
        val node = h.node

        // The single voter self-elects and commits its own-term no-op, pulling the tail to committed.
        h.awaitCommit(150L)

        // committedFrom(1) resumes below the floor: it leads with the recovered snapshot Install@100,
        // then the FIRST live entry must be index 101 — proving the compacted prefix (1..100) was
        // filtered out of the in-memory log (it would be index 1 under the unfiltered-load bug).
        val fromStart = node.committedFrom(1L).take(2).toList()
        val firstEntryAfterInstall = (fromStart[1] as Committed.Entry).entry.index

        // committedFrom(120) resumes ABOVE the floor (no Install): its first entry is a direct positional
        // read — `entryAt(120)` — which must return the entry whose index is 120. Under the unfiltered
        // bug it maps to log[19] and yields index 20.
        val positionalRead = (node.committedFrom(120L).first() as Committed.Entry).entry.index

        assertAll(
            { assertEquals(100L, node.compactionFloor.value, "snapshotIndex recovered from persisted snapshot") },
            { assertTrue(node.commitIndex.value >= 100L, "commitIndex recovered to >= snapshot baseline") },
            { assertEquals(101L, firstEntryAfterInstall, "recovered in-memory log starts at snapshotIndex+1, not 1") },
            { assertEquals(120L, positionalRead, "positional entryAt(120) returns the entry with index 120") },
        )
    }
}
