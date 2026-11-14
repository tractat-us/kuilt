package us.tractat.kuilt.heddle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
            // A ceiling: never more than two batch jobs at once, however idle the pool is.
            lane("batch", maxConcurrent = 2)
        }
        group("tenants") { runtimeChildren() }
    }
    check(fairness.children.map { it.name } == listOf("acme", "tenants"))
    check((fairness.children[0] as Topology.Group).children.map { it.maxConcurrent } == listOf(null, 2L))

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

/**
 * Children the app creates while running: [Topology.attachRuntimeChild] names one by any string and
 * lists the steps that add it, at the weight the group's marker gives, and a reload that keeps the
 * marker keeps the child.
 */
@Suppress("unused")
internal fun sampleRuntimeChildren() {
    val pool = draft("pool") {
        group("tenants") {
            lane("x")
            runtimeChildren(Weight.of(2))
        }
    }
    var ledger = EntitlementLedger.bootstrap(pool.groupId(), mapOf(ReplicaId("me") to 100L), "boot")
    fun apply(steps: List<TopologyStep>) {
        for (step in steps) {
            val patch = when (step) {
                is TopologyStep.Prepare -> ledger.prepare(step.record)
                is TopologyStep.Activate -> ledger.activate(step.edge)
                is TopologyStep.Close -> ledger.close(step.edge)
                is TopologyStep.Retire -> ledger.retire(step.edge)
                is TopologyStep.Reconcile -> error("nothing here carried work")
            }
            ledger = ledger.piece(checkNotNull(patch))
        }
    }
    apply(Topology.diff(pool, ledger, generation = 0))

    // Named like the declared lane `x`, and still its own child: runtime names have their own namespace.
    val tenant = pool.runtimeChildId(listOf("tenants"), "x")
    check(tenant != pool.groupId("tenants", "x"))
    val attach = pool.attachRuntimeChild(listOf("tenants"), "x", ledger, generation = 0)
    check(attach.map { it::class } == listOf(TopologyStep.Prepare::class, TopologyStep.Activate::class))
    check((attach.first() as TopologyStep.Prepare).record.weight == Weight.of(2))
    apply(attach)
    // Asking again is harmless, and a reload that keeps the marker keeps the child.
    check(pool.attachRuntimeChild(listOf("tenants"), "x", ledger, generation = 0).isEmpty())
    check(Topology.diff(pool, ledger, generation = 0).isEmpty())

    // Detached, it is closed and retired; attached again, it gets a fresh edge.
    apply(pool.detachRuntimeChild(listOf("tenants"), "x", ledger))
    check(ledger.lineageOf(tenant) == null)
    check(pool.attachRuntimeChild(listOf("tenants"), "x", ledger, generation = 0).first() is TopologyStep.Prepare)
}

/**
 * A ledger id read back into the names a config file uses, and those names turned into an id,
 * without a lookup table of your own: [Topology.findPath] and [Topology.findGroupId] are exact
 * inverses, and neither throws.
 */
@Suppress("unused")
internal fun sampleGroupPathRoundTrip() {
    val pool = draft("pool") {
        group("tenants") {
            lane("x")
            runtimeChildren()
        }
    }
    // A declared lane and a runtime child share the name `x`, and their paths stay apart.
    val lane = GroupPath.Declared(listOf("tenants", "x"))
    val tenant = GroupPath.RuntimeChild(group = listOf("tenants"), name = "x")
    val laneId = checkNotNull(pool.findGroupId(lane))
    val tenantId = checkNotNull(pool.findGroupId(tenant))
    check(laneId == pool.groupId("tenants", "x") && tenantId == pool.runtimeChildId(listOf("tenants"), "x"))
    check(laneId != tenantId)

    // Back from an id: the arm says whether the last name is a runtime child's.
    check(pool.findPath(laneId) == lane && pool.findPath(tenantId) == tenant)
    check(pool.findPath(tenantId)?.names == listOf("tenants", "x"))
    check(pool.findPath(pool.groupId()) == GroupPath.Declared(emptyList()))

    // Not in this topology: `null`, never an exception.
    check(pool.findGroupId(GroupPath.Declared(listOf("tenants", "y"))) == null)
    check(pool.findGroupId(GroupPath.RuntimeChild(listOf("tenants", "x"), "y")) == null)
    check(pool.findPath(GroupId("elsewhere/tenants")) == null)
}

// ── A consumer's own settings in the same file ──────────────────────────────────────────────────

/** The consumer's file: heddle's fields, plus its own `rules` on every node. */
@Serializable
internal data class PoolFile(val root: String, val children: List<PoolNode>)

/** A node of the consumer's file: heddle's shape, plus the consumer's own `rules`. */
@Serializable
internal sealed interface PoolNode {
    val name: String
    val weight: String
    val rules: List<String>
}

@Serializable
@SerialName("group")
internal data class PoolGroup(
    override val name: String,
    override val weight: String = "1",
    val children: List<PoolNode> = emptyList(),
    val runtimeChildren: String? = null,
    override val rules: List<String> = emptyList(),
) : PoolNode

@Serializable
@SerialName("lane")
internal data class PoolLane(
    override val name: String,
    override val weight: String = "1",
    override val rules: List<String> = emptyList(),
) : PoolNode

/**
 * A file that carries the consumer's own settings beside the tree: decode it once, strictly, into
 * the consumer's own type, build the [TopologyDocument] from it, and report the consumer's
 * problems in the same list as heddle's, under [TopologyProblem.Kind.CONSUMER].
 */
@Suppress("unused")
internal fun sampleConsumerProblems() {
    val text = """
        {"root": "pool", "children": [
          {"type": "group", "name": "acme", "weight": "3", "children": [
            {"type": "lane", "name": "batch", "rules": ["nightly"]},
            {"type": "lane", "name": "batch", "rules": ["hourly"]}
          ]}
        ]}
    """
    // One decode, under a default `Json`: a key neither heddle nor the app knows is an error.
    val file = Json.decodeFromString<PoolFile>(text)

    fun PoolNode.document(): TopologyDocument.Node = when (this) {
        is PoolGroup -> TopologyDocument.Group(name, weight, children.map { it.document() }, runtimeChildren)
        is PoolLane -> TopologyDocument.Lane(name, weight)
    }
    val document = TopologyDocument(file.root, file.children.map { it.document() })

    // The app's own check, addressed the same way: child indices from the root, in written order.
    fun MutableList<TopologyProblem>.checkRules(nodes: List<PoolNode>, path: List<Int>, names: List<String>) {
        nodes.forEachIndexed { index, node ->
            node.rules.filter { it !in listOf("nightly", "weekly") }.forEach { rule ->
                add(TopologyProblem(path + index, names + node.name, TopologyProblem.Kind.CONSUMER, "no rule is called \"$rule\""))
            }
            if (node is PoolGroup) checkRules(node.children, path + index, names + node.name)
        }
    }
    val ours = buildList { checkRules(file.children, emptyList(), listOf(file.root)) }
    val heddles = (Topology.from(document) as? TopologyParse.Invalid)?.problems.orEmpty()
    val problems = heddles + ours
    // One list, one addressing: the second `batch` is both a duplicate and has an unknown rule.
    check(problems.map { it.path to it.kind } == listOf(listOf(0, 1) to TopologyProblem.Kind.DUPLICATE_NAME, listOf(0, 1) to TopologyProblem.Kind.CONSUMER))

    // Why one strict decode and not two lenient ones: to decode the same file into heddle's type,
    // the app's `rules` key must be ignored, and so is a misspelt `weigth`, which then reads as "1".
    val typo = """{"root": "pool", "children": [{"type": "lane", "name": "batch", "weigth": "3"}]}"""
    check(Json { ignoreUnknownKeys = true }.decodeFromString<TopologyDocument>(typo).children.single().weight == "1")
    val refused = try {
        Json.decodeFromString<PoolFile>(typo)
        null
    } catch (e: SerializationException) {
        e
    }
    check(refused != null)
}
