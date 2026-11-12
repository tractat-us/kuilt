package us.tractat.kuilt.heddle

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
