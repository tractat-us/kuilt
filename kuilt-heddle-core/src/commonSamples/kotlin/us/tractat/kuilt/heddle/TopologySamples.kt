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
    check(problem.parent == listOf("pool", "acme") && problem.childIndex == 1)
    check(problem.kind == TopologyProblem.Kind.DUPLICATE_NAME)
}
