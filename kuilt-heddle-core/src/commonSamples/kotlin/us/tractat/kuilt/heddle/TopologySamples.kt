package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/**
 * An admin's config file, decoded in whatever format the app reads, becomes a checked
 * [Topology], or a list of every problem in it, each with an address a config page can show.
 */
@Suppress("unused")
internal fun sampleTopologyFromDocument() {
    // As decoded from the file: names and weight text, nothing checked yet.
    val written = TopologyDocument(
        root = "pool",
        children = listOf(
            TopologyDocument.Group(
                name = "acme",
                weight = "3",
                children = listOf(
                    TopologyDocument.Lane("interactive", weight = "3/2"),
                    TopologyDocument.Lane("batch"),
                ),
            ),
            TopologyDocument.Group(name = "tenants", runtimeChildren = "1"),
        ),
    )
    val parsed = Topology.from(written)
    check(parsed is TopologyParse.Valid)
    check(parsed.topology.children.first().weight == Weight.of(3))

    // A typo is reported, not thrown: the second child of `acme` reuses a name.
    val typo = written.copy(
        children = listOf(
            TopologyDocument.Group("acme", children = listOf(TopologyDocument.Lane("batch"), TopologyDocument.Lane("batch"))),
        ),
    )
    val problem = (Topology.from(typo) as TopologyParse.Invalid).problems.single()
    // Addressed by child indices: the root's first child, then that child's second.
    check(problem.path == listOf(0, 1) && problem.names == listOf("pool", "acme", "batch"))
    check(problem.kind == TopologyProblem.Kind.DUPLICATE_NAME)
}

/**
 * A tree written in code: [draft] builds the same checked [Topology] the constructor would, and a
 * mistake throws [TopologyException] naming every problem in the tree.
 */
@Suppress("unused")
internal fun sampleTopologyDraft() {
    val fairness = draft("pool") {
        group("acme", Weight.of(3)) {
            lane("interactive", Weight.of(3, 2))
            lane("batch")
        }
        group("tenants") { runtimeChildren() }
    }
    check(fairness.children.map { it.name } == listOf("acme", "tenants"))

    // A repeated lane is not built: the whole tree is refused, with every problem in it.
    val refused = try {
        draft("pool") {
            group("acme") {
                lane("batch")
                lane("batch")
            }
        }
        null
    } catch (e: TopologyException) {
        e
    }
    check(refused?.problems?.single()?.kind == TopologyProblem.Kind.DUPLICATE_NAME)
}

/**
 * A reload: [Topology.diff] reads which edges the tree already has from the ledger itself, not
 * from the tree it had before, and lists the steps to the new one in the order they must run.
 */
@Suppress("unused")
internal fun sampleTopologyDiff() {
    val before = draft("pool") {
        group("acme", Weight.of(3)) { lane("batch") }
    }
    // A ledger with supply and no edges yet: the diff prepares the whole tree, then activates it.
    var ledger = EntitlementLedger.bootstrap(before.groupId(), mapOf(ReplicaId("me") to 100L), "boot")
    for (step in Topology.diff(before, ledger, generation = 0)) {
        val patch = when (step) {
            is TopologyStep.Prepare -> ledger.prepare(step.record)
            is TopologyStep.Activate -> ledger.activate(step.edge)
            else -> error("a new tree is only prepared and activated, not $step")
        }
        ledger = ledger.piece(checkNotNull(patch))
    }
    check(Topology.diff(before, ledger, generation = 0).isEmpty())

    // A new weight is a new edge: prepare it, close and retire the old one, then activate.
    val after = draft("pool") {
        group("acme", Weight.of(4)) { lane("batch") }
    }
    val acme = after.groupId("acme")
    val steps = Topology.diff(after, ledger, generation = 1)
    check(
        steps == listOf(
            TopologyStep.Prepare(AttachmentRecord(AttachmentId("pool/acme@4:1#1"), after.groupId(), acme, Weight.of(4))),
            TopologyStep.Close(AttachmentId("pool/acme@3:1#0"), acme),
            TopologyStep.Retire(AttachmentId("pool/acme@3:1#0"), acme),
            TopologyStep.Activate(AttachmentId("pool/acme@4:1#1"), acme),
        ),
    )
}
