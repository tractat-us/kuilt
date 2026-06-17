package us.tractat.kuilt.cluster.samples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.cluster.clusterClientWithNode
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode

/**
 * Sample usage of [ClusterClient].
 *
 * These functions are compiled as part of `commonTest` (via the `commonSamples` source set
 * wired by the `kuilt.kmp-library` convention plugin) and referenced from `module.md` via
 * `@sample`. They are load-bearing: a rename or API breakage fails the build.
 */
object ClusterClientSample {

    /**
     * Constructs a [ClusterClient] over a caller-managed [us.tractat.kuilt.raft.RaftNode],
     * proposes a command, and collects the committed entry.
     *
     * In production the `RaftNode` is a real node created via
     * `CoroutineScope.raftNode(...)` over a `SeamRaftTransport`. In tests a
     * [FakeRaftNode] avoids real-clock delays (see `:kuilt-raft-test`).
     */
    @Suppress("unused")
    suspend fun connectAndPropose(scope: CoroutineScope) {
        // Tests use FakeRaftNode; production uses a real raftNode over a SeamRaftTransport.
        val fakeNode = FakeRaftNode(
            initialRole = RaftRole.Leader,
            clientId = ClientId("stable-client-id"),
        )

        val client: ClusterClient = clusterClientWithNode(fakeNode)

        // Propose with an auto-minted requestId — at-least-once but survives failover.
        val entry = client.propose("set x=1".encodeToByteArray())

        // Propose with a caller-pinned requestId for cross-crash exactly-once semantics.
        val dedupEntry = client.propose("set y=2".encodeToByteArray(), requestId = 42L)

        // Collect the committed stream and apply through ClientSessionTable for dedup.
        val table = ClientSessionTable()
        val committed = client.committed
            .filterIsInstance<Committed.Entry>()
            .first { table.shouldApply(it.entry.dedupKey) }

        println("Committed at index ${committed.entry.index}")

        // Report current Raft role (always Learner in the relay model).
        println("Role: ${client.role.value}")

        client.close()
    }
}
