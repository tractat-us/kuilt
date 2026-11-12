package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopologyLoweringTest {

    /** The tree in `docs/heddle-design.md` §11.1. */
    private val fairness = Topology(
        "root",
        listOf(
            Group("acme", Weight.of(3), listOf(Lane("interactive", Weight.of(3)), Lane("batch"))),
            Group("hobby", Weight.ONE, listOf(Lane("default"))),
        ),
    )

    /**
     * Names that a naive join would fold together: a slash inside a name against two levels, a
     * pre-escaped slash, the id grammar's own punctuation, the runtime marker, a lone surrogate
     * against the `?` a JVM encoder substitutes for it, and a runtime `x` beside a declared `x` and
     * a declared `!x`.
     */
    private val awkward = Topology(
        "root",
        listOf(
            Lane("a/b"),
            Lane("a%2Fb"),
            Group("a", Weight.ONE, listOf(Lane("b"))),
            Lane("é"),
            Lane("x@1:1#0"),
            Lane("!x"),
            Lane("?"),
            Lane("\uD800"),
            Lane("\uDC00"),
            Lane("😀"),
            Group("g", Weight.ONE, listOf(Lane("x"), Lane("!x")), runtimeChildren = Topology.RuntimeChildren(Weight.ONE)),
        ),
    )

    private val declaredNames = listOf("a/b", "a%2Fb", "a", "é", "x@1:1#0", "!x", "?", "\uD800", "\uDC00", "😀", "g")

    @Test
    fun lowerIsSortedAndGolden() {
        val lowered = fairness.lower()
        assertAll(
            {
                assertEquals(
                    listOf(
                        "root/acme/batch@1:1#0",
                        "root/acme/interactive@3:1#0",
                        "root/acme@3:1#0",
                        "root/hobby/default@1:1#0",
                        "root/hobby@1:1#0",
                    ),
                    lowered.map { it.id.value },
                )
            },
            {
                assertEquals(
                    AttachmentRecord(
                        AttachmentId("root/acme/interactive@3:1#0"),
                        GroupId("root/acme"),
                        GroupId("root/acme/interactive"),
                        Weight.of(3),
                    ),
                    lowered[1],
                )
            },
            { assertEquals(GroupId("root"), lowered.single { it.id.value == "root/acme@3:1#0" }.parent) },
            { assertEquals(listOf("root/acme/batch@1:1#7"), fairness.lower(7).take(1).map { it.id.value }) },
            { assertFailsWith<IllegalArgumentException> { fairness.lower(-1) } },
        )
    }

    @Test
    fun flatteningIsInjectiveOverAwkwardNames() {
        val declared = declaredNames.map { awkward.groupId(it) } +
            listOf(awkward.groupId("a", "b"), awkward.groupId("g", "x"), awkward.groupId("g", "!x"))
        val runtime = awkward.runtimeChildId(listOf("g"), "x")
        val all = declared + runtime + awkward.groupId()
        val ids = awkward.lower().map { it.id }
        assertAll(
            { assertEquals(all.size, all.toSet().size, "group ids collide: ${all.map { it.value }}") },
            { assertEquals(ids.size, ids.toSet().size, "edge ids collide: ${ids.map { it.value }}") },
            { assertEquals(GroupId("root/a%2Fb"), awkward.groupId("a/b")) },
            { assertEquals(GroupId("root/a/b"), awkward.groupId("a", "b")) },
            { assertEquals(GroupId("root/a%252Fb"), awkward.groupId("a%2Fb")) },
            { assertEquals(GroupId("root/%C3%A9"), awkward.groupId("é")) },
            { assertEquals(GroupId("root/%21x"), awkward.groupId("!x")) },
            { assertEquals(GroupId("root/%F0%9F%98%80"), awkward.groupId("😀")) },
            { assertEquals(GroupId("root/g/!x"), runtime) },
            { assertEquals(GroupId("root/g/x"), awkward.groupId("g", "x")) },
            { assertEquals(GroupId("root/g/%21x"), awkward.groupId("g", "!x")) },
            // A lone surrogate keeps its own bytes rather than becoming `?` or U+FFFD on some target.
            { assertEquals(GroupId("root/%ED%A0%80"), awkward.groupId("\uD800")) },
            { assertEquals(GroupId("root/%3F"), awkward.groupId("?")) },
            // Only ids the topology declares can be named.
            { assertFailsWith<IllegalArgumentException> { awkward.groupId("nope") } },
            { assertFailsWith<IllegalArgumentException> { awkward.groupId("a", "nope") } },
            { assertFailsWith<IllegalArgumentException> { awkward.runtimeChildId(listOf("a"), "x") } },
            { assertFailsWith<IllegalArgumentException> { awkward.runtimeChildId(listOf("g"), "") } },
        )
    }

    @Test
    fun pathOfInvertsGroupId() {
        val declared = declaredNames.map { listOf(it) } + listOf(listOf("a", "b"), listOf("g", "x"), listOf("g", "!x"))
        val runtimeNames = listOf("x", "a/b", "!x", "é", "\uD800", "x@1:1#0", "%21x")
        assertAll(
            { assertEquals(ParsedPath(listOf("root"), runtime = false), pathOf(awkward.groupId())) },
            *declared.map { path ->
                { assertEquals(ParsedPath(listOf("root") + path, runtime = false), pathOf(awkward.groupId(*path.toTypedArray()))) }
            }.toTypedArray(),
            *runtimeNames.map { name ->
                { assertEquals(ParsedPath(listOf("root", "g", name), runtime = true), pathOf(awkward.runtimeChildId(listOf("g"), name))) }
            }.toTypedArray(),
            // Not the encoding of anything: each must be refused rather than read some other way.
            *listOf(
                "", "/", "root/", "root//x", "!root", "root/!x", "root/!a/b", "root/a/!", "root/a b", "root/a/b@1:1#0",
                "root/%2f", "root/%41", "root/%2", "root/%ZZ", "root/%C3", "root/%C0%AF", "root/%FF",
            ).map { text -> { assertNull(pathOf(GroupId(text)), "pathOf(\"$text\")") } }.toTypedArray(),
        )
    }

    @Test
    fun generationOfParsesOnlyTheGrammar() {
        val parsed = listOf(
            "root/acme@3:1#0" to 0L,
            "root/acme/interactive@3:2#41" to 41L,
            "root/g/!x@1:1#7" to 7L,
            "root/%C3%A9@1:1#${Long.MAX_VALUE}" to Long.MAX_VALUE,
        )
        val refused = listOf(
            "e", "root", "root@1:1#0", "root/acme", "root/acme@3:1", "root/acme@3:1#", "root/acme@3#0",
            "root/acme@3:1#-1", "root/acme@3:1#+1", "root/acme@3:1#01", "root/acme@3:1#1x", "root/acme@3:1#0#1",
            "root/acme@6:2#0", "root/acme@0:1#0", "root/acme@3:0#0", "root/acme@03:1#0", "root/acme@+3:1#0",
            "root/acme@3:1#99999999999999999999", "root/acme@3:1@3:1#0", "root/a%2fb@1:1#0", "root/!a/b@1:1#0",
            "@1:1#0", "root//x@1:1#0", "root/!x@1:1#0",
        )
        assertAll(
            *parsed.map { (text, generation) ->
                { assertEquals(generation, generationOf(AttachmentId(text)), text) }
            }.toTypedArray(),
            *refused.map { text -> { assertNull(generationOf(AttachmentId(text)), text) } }.toTypedArray(),
            // Everything `lower` prints, it reads back.
            *awkward.lower(3).map { record -> { assertEquals(3L, generationOf(record.id), record.id.value) } }.toTypedArray(),
        )
    }

    @Test
    fun floorSeesRetiredEdges() {
        val booted = bootFrom(fairness, generation = 2)
        val stale = AttachmentRecord(
            AttachmentId("root/acme/batch@1:1#9"),
            GroupId("root/acme"),
            GroupId("root/acme/batch"),
            Weight.ONE,
        )
        var retired = booted.piece(assertNotNull(booted.prepare(stale)))
        retired = retired.piece(assertNotNull(retired.close(stale.id)))
        retired = retired.piece(assertNotNull(retired.retire(stale.id)))
        // An id outside the grammar is somebody else's edge, and says nothing about generations.
        val foreign = AttachmentRecord(AttachmentId("e#99"), GroupId("root"), GroupId("elsewhere"), Weight.ONE)
        val withForeign = booted.piece(assertNotNull(booted.prepare(foreign)))
        // A floor at the last generation there is leaves no next one: it must refuse, not wrap.
        val last = stale.copy(id = AttachmentId("root/acme/batch@1:1#${Long.MAX_VALUE}"))
        val exhausted = booted.piece(assertNotNull(booted.prepare(last)))
        assertAll(
            { assertEquals(Lifecycle.RETIRED, retired.lifecycle(stale.id), "the rig retired the stale edge") },
            { assertEquals(-1L, EntitlementLedger.ZERO.generationFloor()) },
            { assertEquals(2L, booted.generationFloor()) },
            { assertEquals(9L, retired.generationFloor()) },
            { assertEquals(2L, withForeign.generationFloor()) },
            { assertEquals(10L, effectiveGeneration(0L, retired)) },
            { assertEquals(12L, effectiveGeneration(12L, retired)) },
            { assertEquals(0L, effectiveGeneration(0L, EntitlementLedger.ZERO)) },
            { assertEquals(3L, effectiveGeneration(3L, EntitlementLedger.ZERO)) },
            { assertFailsWith<IllegalArgumentException> { effectiveGeneration(-1L, EntitlementLedger.ZERO) } },
            { assertEquals(Long.MAX_VALUE, exhausted.generationFloor()) },
            { assertFailsWith<ArithmeticException> { effectiveGeneration(0L, exhausted) } },
        )
    }

    /**
     * The one floor rule `diff` and `attachRuntimeChild` share: every edge counts, retired ones
     * included, except the ones named — which `diff` uses for the prepared edges it adopts.
     */
    @Test
    fun floorLeavesOutOnlyTheEdgesItIsTold() {
        val booted = bootFrom(fairness, generation = 2)
        val atTwo = booted.allEdges().filter { generationOf(it) == 2L }.toSet()
        val stale = AttachmentRecord(AttachmentId("root/acme/batch@1:1#9"), GroupId("root/acme"), GroupId("root/acme/batch"), Weight.ONE)
        var retired = booted.piece(assertNotNull(booted.prepare(stale)))
        retired = retired.piece(assertNotNull(retired.close(stale.id)))
        retired = retired.piece(assertNotNull(retired.retire(stale.id)))
        assertAll(
            { assertEquals(5, atTwo.size, "the rig booted five edges at generation 2") },
            { assertEquals(GenerationTop(2L, 5), booted.generationTop()) },
            { assertEquals(GenerationTop(9L, 1), retired.generationTop()) },
            { assertEquals(3L, effectiveGeneration(0L, booted, except = setOf(atTwo.first()))) },
            { assertEquals(0L, effectiveGeneration(0L, booted, except = atTwo)) },
            { assertEquals(3L, effectiveGeneration(0L, retired, except = setOf(stale.id))) },
            { assertEquals(10L, effectiveGeneration(0L, retired, except = atTwo)) },
            // Naming an edge the ledger does not hold leaves the floor where it is.
            { assertEquals(3L, effectiveGeneration(0L, booted, except = setOf(AttachmentId("root/x@1:1#2")))) },
        )
    }

    @Test
    fun aWeightChangeChangesOnlyThatId() {
        val before = fairness.lower().map { it.id.value }.toSet()
        val leafChanged = fairness.copy(
            children = listOf(
                Group("acme", Weight.of(3), listOf(Lane("interactive", Weight.of(2)), Lane("batch"))),
                fairness.children[1],
            ),
        ).lower().map { it.id.value }.toSet()
        val groupChanged = fairness.copy(
            children = listOf((fairness.children[0] as Group).copy(weight = Weight.of(3, 2)), fairness.children[1]),
        ).lower().map { it.id.value }.toSet()
        assertAll(
            { assertEquals(setOf("root/acme/interactive@3:1#0"), before - leafChanged) },
            { assertEquals(setOf("root/acme/interactive@2:1#0"), leafChanged - before) },
            { assertEquals(setOf("root/acme@3:1#0"), before - groupChanged) },
            { assertEquals(setOf("root/acme@3:2#0"), groupChanged - before) },
        )
    }

    @Test
    fun bootedTopologyIsValid() {
        val ledger = bootFrom(fairness, generation = 4)
        val acme = GroupId("root/acme")
        val hobby = GroupId("root/hobby")
        val root = GroupId("root")
        assertAll(
            { assertEquals(emptyList(), ledger.validate()) },
            { assertEquals(listOf(root), ledger.mintedRoots()) },
            {
                assertEquals(
                    listOf(
                        ActiveTriple(root, acme, Weight.of(3)),
                        ActiveTriple(acme, GroupId("root/acme/batch"), Weight.ONE),
                        ActiveTriple(acme, GroupId("root/acme/interactive"), Weight.of(3)),
                        ActiveTriple(root, hobby, Weight.ONE),
                        ActiveTriple(hobby, GroupId("root/hobby/default"), Weight.ONE),
                    ),
                    ledger.activeTriples(),
                )
            },
            { assertEquals(bootFrom(fairness, generation = 0).activeTriples(), ledger.activeTriples()) },
            { assertTrue(bootFrom(awkward).validate().isEmpty(), "the awkward tree boots clean too") },
        )
    }
}
