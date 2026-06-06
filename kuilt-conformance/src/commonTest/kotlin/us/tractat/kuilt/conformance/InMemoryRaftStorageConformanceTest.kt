package us.tractat.kuilt.conformance

import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.RaftStorage

/**
 * Verifies the reference [InMemoryRaftStorage] satisfies the full
 * [RaftStorageConformanceSuite]. Mirrors [InMemoryRoomConformanceTest].
 */
class InMemoryRaftStorageConformanceTest : RaftStorageConformanceSuite() {
    override fun newStorage(): RaftStorage = InMemoryRaftStorage()
}
