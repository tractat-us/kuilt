package us.tractat.kuilt.conformance

import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.RaftStorage

/**
 * Verifies the reference [InMemoryRaftStorage] satisfies the full
 * [RaftStorageConformanceSuite]. Mirrors [InMemoryRoomConformanceTest].
 */
class InMemoryRaftStorageConformanceTest : RaftStorageConformanceSuite() {
    override fun newStorage(): RaftStorage = InMemoryRaftStorage()

    /**
     * A restart, modelled the only way an in-memory storage can model one: a **fresh instance
     * rebuilt from what the old one's public read surface reports**.
     *
     * That constraint is the whole of it, and it is what stops this hook from being decoration.
     * The tempting implementation — copy the five fields across — would make both handles agree by
     * construction, so every restart property in the suite would hold whatever the implementation
     * did, which is the vacuity the hook exists to remove reappearing one level in. It is not
     * merely discouraged here, it is unwritable: `InMemoryRaftStorage`'s fields are all private, so
     * a subclass in this module has no way to reach them. Reconstructing through `term()` /
     * `votedFor()` / `leaderForTerm()` / `loadSnapshot()` / `entries()` means a value that never
     * went through a `save*` call cannot appear in the rebuilt handle.
     *
     * What this cannot model, said plainly, is the failure a *durable* adapter has and this one does
     * not: a write that never reached stable storage. There is no medium here to lose it to, so the
     * reference passes the restart properties the way it passes everything else — structurally. The
     * obligation is on the adapters, which is the point of putting it in the TCK rather than in a
     * backend's own tests.
     */
    override suspend fun reopen(storage: RaftStorage): RaftStorage {
        val restarted = InMemoryRaftStorage()
        restarted.saveTermAndVotedFor(storage.term(), storage.votedFor())
        storage.leaderForTerm()?.let { restarted.saveLeaderForTerm(it.term, it.leaderId) }
        storage.loadSnapshot()?.let { restarted.saveSnapshot(it.meta, it.state) }
        restarted.appendEntries(storage.entries())
        return restarted
    }
}
