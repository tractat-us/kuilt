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

    private val base = MovableTree.empty<String>()
    private val withDocsResult = base.addNode(a, ts = 1L, parent = MovableTree.ROOT_ID, value = "docs")
    private val withDocs = withDocsResult.tree
    private val docs = withDocsResult.nodeId
    private val withImgResult = withDocs.addNode(a, ts = 2L, parent = docs, value = "img")
    private val withImg = withImgResult.tree
    private val img = withImgResult.nodeId

    /**
     * A move retires: `img` stops being under `docs` and the move-log carries no replacement claim
     * that it ever was — the case [us.tractat.kuilt.conformance.lattice.OpKind] names explicitly,
     * and the reading `MovableTreeConvergenceTest`'s `move-last-under-first` op already takes.
     */
    private val moved = withImg.move(a, ts = 3L, node = img, newParent = MovableTree.ROOT_ID).first

    /** A later move puts `img` back under `docs`; replay is by `(ts, replica)`, so ts=4 wins. */
    private val movedBack = moved.move(a, ts = 4L, node = img, newParent = docs).first

    // Concurrent branch: B adds under docs without seeing A's ts=2/3 ops.
    private val bBranch = withDocs.addNode(b, ts = 2L, parent = docs, value = "notes").tree

    override fun samples(): List<MovableTree<String>> =
        listOf(base, withDocs, withImg, moved, bBranch, movedBack)

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<MovableTree<String>> =
        RetirementReAssertion(
            subject = "`img` being a child of `docs`",
            asserted = withImg,
            retired = moved,
            reAsserted = movedBack,
            shows = { img in it.childrenOf(docs) },
        )
}
