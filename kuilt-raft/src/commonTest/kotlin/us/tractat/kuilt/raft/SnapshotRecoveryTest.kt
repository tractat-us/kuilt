@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
}
