package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.MovableTree
import us.tractat.kuilt.crdt.ReplicaId

/**
 * MovableTree's state is its total-order move-log; piece merges the logs
 * (dedup by `(ts, replica)` identity) and replays deterministically — it obeys
 * every lattice law. Divergent branches use distinct replicas so `(replica, ts)`
 * stays globally unique, as the [MovableTree.addNode] contract requires.
 */
internal class MovableTreeConformanceTest : QuiltedConformanceSuite<MovableTree<String>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<MovableTree<String>> {
        val base = MovableTree.empty<String>()
        val (withDocs, docs, _) = base.addNode(a, ts = 1L, parent = MovableTree.ROOT_ID, value = "docs")
        val (withImg, img, _) = withDocs.addNode(a, ts = 2L, parent = docs, value = "img")
        val moved = withImg.move(a, ts = 3L, node = img, newParent = MovableTree.ROOT_ID).first
        // Concurrent branch: B adds under docs without seeing A's ts=2/3 ops.
        val bBranch = withDocs.addNode(b, ts = 2L, parent = docs, value = "notes").tree
        return listOf(base, withDocs, withImg, moved, bBranch)
    }
}
