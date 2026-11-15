package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Every clock that mints a successor refuses at [Long.MAX_VALUE] instead of wrapping (#3417).
 *
 * A local replica reaches the ceiling only after 2⁶³ operations. The real path is remote: a
 * decoded version vector, op-log or peer Lamport value at [Long.MAX_VALUE] makes the next local
 * mint wrap to [Long.MIN_VALUE], which breaks dot uniqueness and causal order. So each arm here
 * decodes a state carrying the ceiling, asserts the rig (the decoded state really carries it),
 * and then asserts the next mint throws [ArithmeticException], as [GCounter.inc] does.
 */
class ClockWrapTest {

    private val local = ReplicaId("local")
    private val remote = ReplicaId("remote")
    private val max = Long.MAX_VALUE

    // ── DotContext ────────────────────────────────────────────────────────────

    private fun contextAt(seq: Long): DotContext =
        Json.decodeFromString(DotContext.serializer(), """{"vv":{"local":$seq},"cloud":[]}""")

    /**
     * A decoded version vector at the ceiling makes [DotContext.nextDot] refuse. Before the fix the
     * sum wrapped to [Long.MIN_VALUE], and only [Dot]'s own `seq >= 1` check happened to stop it,
     * with an [IllegalArgumentException] that named a negative seq nobody wrote.
     */
    @Test
    fun nextDotRefusesAtTheCeiling() {
        val full = contextAt(max)
        assertAll(
            { assertEquals(true, full.contains(Dot(local, max)), "the rig's context covers seq Long.MAX_VALUE") },
            { assertFailsWith<ArithmeticException> { full.nextDot(local) } },
        )
    }

    /** The boundary on the other side: the dot that lands exactly on the ceiling is minted. */
    @Test
    fun nextDotReachesTheCeiling() {
        assertEquals(Dot(local, max), contextAt(max - 1L).nextDot(local))
    }

    /**
     * [CausalMap.Slot.mint] steps past dots the context already holds, one at a time, so it has its
     * own increment. Minting the ceiling is allowed; minting past it refuses. Before the fix the
     * second mint wrapped and [Dot]'s check threw an [IllegalArgumentException] instead.
     */
    @Test
    fun slotMintRefusesPastTheCeiling() {
        val minter = CausalMap.Minter(contextAt(max - 1L), local)
        assertAll(
            { assertEquals(Dot(local, max), minter.mint(), "the rig mints the ceiling itself") },
            { assertFailsWith<ArithmeticException> { minter.mint() } },
        )
    }

    /**
     * A context already at the ceiling still lets a mutator that mints nothing run: the refusal is
     * the mint's, not the slot's. Before the fix the [CausalMap.Minter] computed its first dot
     * eagerly, so even a pure removal under [CausalMap.apply] threw.
     */
    @Test
    fun aMinterAtTheCeilingRefusesOnlyWhenItMints() {
        val minter = CausalMap.Minter(contextAt(max), local)
        assertAll(
            { assertEquals(emptyList(), minter.minted, "the rig's minter has minted nothing") },
            { assertFailsWith<ArithmeticException> { minter.mint() } },
        )
    }

    // ── Fugue ─────────────────────────────────────────────────────────────────

    private val fugueSerializer = Fugue.wireSerializer(String.serializer())

    /** One insert by [author], encoded, then its `field` rewritten to [Long.MAX_VALUE] and decoded. */
    private fun fugueWith(author: ReplicaId, field: String): Fugue<String> {
        val (one, _) = Fugue.empty<String>().insertAt(author, 0, "a")
        val valid = Json.encodeToString(fugueSerializer, one)
        val rewritten = valid.replace("\"$field\":1", "\"$field\":$max")
        assertNotEquals(valid, rewritten, "the rig found $field to rewrite in $valid")
        return Json.decodeFromString(fugueSerializer, rewritten)
    }

    /**
     * A peer's insert at Lamport [Long.MAX_VALUE] makes the next local insert refuse. Before the fix
     * the clock wrapped to [Long.MIN_VALUE], the Lamport of [FugueId.HEAD], so the new element sorted
     * before every real one.
     */
    @Test
    fun fugueInsertRefusesAtTheLamportCeiling() {
        val full = fugueWith(remote, "lamport")
        assertAll(
            { assertEquals(max, full.lamport, "the rig's decoded clock is at the ceiling") },
            { assertFailsWith<ArithmeticException> { full.insertAt(local, 0, "b") } },
            { assertFailsWith<ArithmeticException> { full.insertOpViaRebuildOracle(local, 0, "b") } },
        )
    }

    /**
     * An op-log holding this replica's own seq at [Long.MAX_VALUE] makes its next insert refuse.
     * Before the fix the dense per-replica seq wrapped and the op carried a seq of
     * [Long.MIN_VALUE].
     */
    @Test
    fun fugueInsertRefusesAtTheSeqCeiling() {
        val full = fugueWith(local, "seq")
        assertAll(
            { assertEquals(listOf(max), full.ops.map { it.id.seq }, "the rig's decoded op carries seq Long.MAX_VALUE") },
            { assertFailsWith<ArithmeticException> { full.insertAt(local, 0, "b") } },
            { assertFailsWith<ArithmeticException> { full.insertOpViaRebuildOracle(local, 0, "b") } },
        )
    }

    /**
     * A refused insert leaves the [Fugue] usable: it must hand back the sequence state it borrowed.
     * Another replica's insert still goes through afterwards.
     */
    @Test
    fun aRefusedFugueInsertLeavesTheStateUsable() {
        val full = fugueWith(local, "seq")
        assertFailsWith<ArithmeticException> { full.insertAt(local, 0, "b") }
        val (after, _) = full.insertAt(remote, 1, "c")
        assertEquals(listOf("a", "c"), after.toList())
    }

    // ── Rga ───────────────────────────────────────────────────────────────────

    private val rgaSerializer = Rga.wireSerializer(String.serializer())

    private fun rgaWith(author: ReplicaId, field: String): Rga<String> {
        val (one, _) = Rga.empty<String>().insertAt(author, 0, "a")
        val valid = Json.encodeToString(rgaSerializer, one)
        val rewritten = valid.replace("\"$field\":1", "\"$field\":$max")
        assertNotEquals(valid, rewritten, "the rig found $field to rewrite in $valid")
        return Json.decodeFromString(rgaSerializer, rewritten)
    }

    /** [Rga] has the same two clocks as [Fugue], and the same wrap, on both of its insert paths. */
    @Test
    fun rgaInsertRefusesAtTheLamportCeiling() {
        val full = rgaWith(remote, "lamport")
        assertAll(
            { assertEquals(max, full.lamport, "the rig's decoded clock is at the ceiling") },
            { assertFailsWith<ArithmeticException> { full.insertAt(local, 0, "b") } },
            { assertFailsWith<ArithmeticException> { full.insertAllAfter(local, RgaId.HEAD, listOf("b")) } },
        )
    }

    @Test
    fun rgaInsertRefusesAtTheSeqCeiling() {
        val full = rgaWith(local, "seq")
        assertAll(
            { assertEquals(listOf(max), full.ops.filterIsInstance<RgaOp.Insert<String>>().map { it.id.seq }, "the rig's decoded op carries seq Long.MAX_VALUE") },
            { assertFailsWith<ArithmeticException> { full.insertAt(local, 0, "b") } },
            { assertFailsWith<ArithmeticException> { full.insertAllAfter(local, RgaId.HEAD, listOf("b")) } },
        )
    }

    /**
     * [Rga.insertAllAfter] advances both clocks once per value, so a run that starts below the
     * ceiling can still cross it part-way. The whole run is refused rather than minting a prefix.
     */
    @Test
    fun rgaInsertAllAfterRefusesARunThatCrossesTheCeiling() {
        val nearlyFull = rgaWith(remote, "lamport").let { full ->
            // One below the ceiling: a run of one fits, a run of two does not.
            val valid = Json.encodeToString(rgaSerializer, full)
            Json.decodeFromString(rgaSerializer, valid.replace("\"lamport\":$max", "\"lamport\":${max - 1L}"))
        }
        assertAll(
            { assertEquals(max - 1L, nearlyFull.lamport, "the rig's clock is one below the ceiling") },
            { assertEquals(max, nearlyFull.insertAllAfter(local, RgaId.HEAD, listOf("b")).first.lamport) },
            { assertFailsWith<ArithmeticException> { nearlyFull.insertAllAfter(local, RgaId.HEAD, listOf("b", "c")) } },
        )
    }

    // ── MovableTree ───────────────────────────────────────────────────────────

    /**
     * [MovableTree]'s dense per-replica seq wraps the same way: an op-log holding this replica's
     * seq at [Long.MAX_VALUE] makes its next [MovableTree.addNode] and [MovableTree.move] refuse.
     *
     * The tree carries the seq twice, on the op and in its per-replica high-water map, and the mint
     * reads the map. So the rig rewrites both, and checks that the high-water it decoded is the
     * ceiling: rewriting the op alone leaves a tree whose next mint is a valid `2`.
     */
    @Test
    fun movableTreeRefusesAtTheSeqCeiling() {
        val ser = MovableTree.serializer(String.serializer())
        val one = MovableTree.empty<String>().addNode(local, ts = 7L, parent = MovableTree.ROOT_ID, value = "a").tree
        val valid = Json.encodeToString(ser, one)
        val rewritten = valid.replace("\"seq\":1", "\"seq\":$max").replace("\"local\":1", "\"local\":$max")
        val full = Json.decodeFromString(ser, rewritten)
        assertAll(
            { assertEquals(2, rewritten.split("$max").size - 1, "the rig rewrote the op's seq and the high-water in $valid") },
            { assertEquals(true, Json.encodeToString(ser, full).contains("\"local\":$max"), "the decoded high-water is the ceiling") },
            { assertFailsWith<ArithmeticException> { full.addNode(local, ts = 8L, parent = MovableTree.ROOT_ID, value = "b") } },
            { assertFailsWith<ArithmeticException> { full.move(local, ts = 9L, node = "a:local:7", newParent = MovableTree.ROOT_ID) } },
        )
    }
}
