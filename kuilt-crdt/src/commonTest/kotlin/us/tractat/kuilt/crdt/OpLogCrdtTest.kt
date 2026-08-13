package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [OpLogCrdt] contract — the public op-log view `:kuilt-bolt` archives through.
 *
 * Three properties, asserted for **both** implementations:
 *  1. [OpLogCrdt.operations] yields exactly the ops the log holds.
 *  2. [OpLogCrdt.classify] returns the [LogOp] shape matching the concrete op type, carrying
 *     the concrete op's own ids.
 *  3. [OpLogCrdt.dotOf] agrees with the id's own `dot`.
 *
 * Plus the serializer half of the contract: [OpLogCrdt.opSerializer] must hand back the
 * **canonical** op serializer — the one with golden vectors behind it — not a compiler-generated
 * sealed serializer, whose `class-discriminator` wire format an archive could never read back
 * under the guarantee the format promises.
 *
 * [Rga] and [Fugue] are covered separately throughout, because they are **not symmetric**: [Rga]
 * carries a [Rga.compactedBelow] floor that suppresses dots with no [RgaOp.Compact] to name them,
 * and [Fugue] has no floor at all. A suite written against [Rga] alone would let a [Fugue]-shaped
 * bug through.
 */
@OptIn(ExperimentalSerializationApi::class)
class OpLogCrdtTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val cbor = Cbor {}

    // ── operations() ──────────────────────────────────────────────────────────

    @Test
    fun rgaOperationsYieldEveryOpInTheLog() {
        val (r1, i1) = Rga.empty<String>().insertAt(a, 0, "x")
        val (r2, i2) = r1.insertAt(b, 1, "y")
        val (r3, rm) = assertNotNull(r2.removeAt(0), "removeAt(0) must find the first visible element")

        val yielded = r3.operations().toList()
        assertAll(
            // Independent of the internal field: these are the ops the mutators handed back.
            { assertEquals(setOf<RgaOp<String>>(i1, i2, rm), yielded.toSet(), "operations() must yield every minted op") },
            // And exactly the log — no extras, no duplicates.
            { assertEquals(r3.ops, yielded.toSet(), "operations() must be exactly the op-log") },
            { assertEquals(r3.ops.size, yielded.size, "operations() must not repeat an op") },
        )
    }

    @Test
    fun fugueOperationsYieldEveryOpInTheLog() {
        val (f1, i1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, i2) = f1.insertAt(b, 1, "y")
        val (f3, rm) = assertNotNull(f2.removeAt(0), "removeAt(0) must find the first visible element")

        val yielded = f3.operations().toList()
        assertAll(
            { assertEquals(setOf<FugueOp<String>>(i1, i2, rm), yielded.toSet(), "operations() must yield every minted op") },
            { assertEquals(f3.ops, yielded.toSet(), "operations() must be exactly the op-log") },
            { assertEquals(f3.ops.size, yielded.size, "operations() must not repeat an op") },
        )
    }

    @Test
    fun operationsOnAnEmptyLogIsEmptyForBoth() {
        assertAll(
            { assertEquals(emptyList(), Rga.empty<String>().operations().toList(), "empty Rga has no ops") },
            { assertEquals(emptyList(), Fugue.empty<String>().operations().toList(), "empty Fugue has no ops") },
        )
    }

    // ── classify() ────────────────────────────────────────────────────────────

    @Test
    fun rgaClassifiesInsertRemoveAndCompact() {
        val (r3, compactOp) = rgaWithACompaction()
        val byShape = r3.operations().associateBy({ it }, { r3.classify(it) })

        assertAll(
            {
                byShape.forEach { (op, shape) ->
                    when (op) {
                        is RgaOp.Insert -> assertEquals(LogOp.Insert(op.id), shape, "Insert must classify as LogOp.Insert(id)")
                        is RgaOp.Remove -> assertEquals(LogOp.Remove(op.id), shape, "Remove must classify as LogOp.Remove(id)")
                        is RgaOp.Compact -> assertEquals(
                            LogOp.Compact(op.positions.keys),
                            shape,
                            "Compact must classify as LogOp.Compact carrying its recorded ids",
                        )
                    }
                }
            },
            // The Compact op is retained in the log and is the shape the archive must never discard.
            {
                assertEquals(
                    LogOp.Compact(compactOp.positions.keys),
                    r3.classify(compactOp),
                    "the retained Compact must classify as LogOp.Compact",
                )
            },
            { assertTrue(byShape.values.any { it is LogOp.Compact }, "the compacted log must contain a Compact op") },
            { assertTrue(byShape.values.any { it is LogOp.Insert }, "the log must still contain an Insert") },
        )
    }

    @Test
    fun fugueClassifiesInsertRemoveAndCompact() {
        val (f3, compactOp) = fugueWithACompaction()
        val byShape = f3.operations().associateBy({ it }, { f3.classify(it) })

        assertAll(
            {
                byShape.forEach { (op, shape) ->
                    when (op) {
                        is FugueOp.Insert -> assertEquals(LogOp.Insert(op.id), shape, "Insert must classify as LogOp.Insert(id)")
                        is FugueOp.Remove -> assertEquals(LogOp.Remove(op.id), shape, "Remove must classify as LogOp.Remove(id)")
                        is FugueOp.Compact -> assertEquals(
                            LogOp.Compact(op.positions.keys),
                            shape,
                            "Compact must classify as LogOp.Compact carrying its recorded ids",
                        )
                    }
                }
            },
            {
                assertEquals(
                    LogOp.Compact(compactOp.positions.keys),
                    f3.classify(compactOp),
                    "the retained Compact must classify as LogOp.Compact",
                )
            },
            { assertTrue(byShape.values.any { it is LogOp.Compact }, "the compacted log must contain a Compact op") },
            { assertTrue(byShape.values.any { it is LogOp.Insert }, "the log must still contain an Insert") },
        )
    }

    /**
     * The whole point of the contract: a consumer that names neither concrete type can still
     * split a log into the ops it keeps and the compaction records it discards. `:kuilt-bolt`'s
     * append path is this function; if it cannot be written generically, the interface is wrong.
     */
    @Test
    fun aGenericConsumerCanPartitionEitherLogThroughTheInterfaceAlone() {
        val (rga, _) = rgaWithACompaction()
        val (fugue, _) = fugueWithACompaction()

        val rgaSplit = partitionContentFromCompaction(rga)
        val fugueSplit = partitionContentFromCompaction(fugue)

        assertAll(
            { assertEquals(1, rgaSplit.second.size, "Rga: exactly one Compact op was recorded") },
            { assertEquals(1, fugueSplit.second.size, "Fugue: exactly one Compact op was recorded") },
            { assertTrue(rgaSplit.first.isNotEmpty(), "Rga: content ops survive the compaction") },
            { assertTrue(fugueSplit.first.isNotEmpty(), "Fugue: content ops survive the compaction") },
            {
                assertEquals(
                    rga.ops.size,
                    rgaSplit.first.size + rgaSplit.second.size,
                    "Rga: the split must be total — every op lands on one side",
                )
            },
            {
                assertEquals(
                    fugue.ops.size,
                    fugueSplit.first.size + fugueSplit.second.size,
                    "Fugue: the split must be total — every op lands on one side",
                )
            },
        )
    }

    /** Content ops to `first`, compaction records to `second` — written against [OpLogCrdt] only. */
    private fun <Id : Any, V, Op : Any> partitionContentFromCompaction(
        crdt: OpLogCrdt<Id, V, Op>,
    ): Pair<List<Op>, List<Op>> = crdt.operations().toList().partition { crdt.classify(it) !is LogOp.Compact }

    // ── dotOf() ───────────────────────────────────────────────────────────────

    @Test
    fun rgaDotOfAgreesWithRgaIdDot() {
        val (r3, _) = rgaWithACompaction()
        val ids = r3.operations().flatMap { op ->
            when (val shape = r3.classify(op)) {
                is LogOp.Insert -> sequenceOf(shape.id)
                is LogOp.Remove -> sequenceOf(shape.id)
                is LogOp.Compact -> shape.compactedIds.asSequence()
            }
        }.toList()

        assertAll(
            { assertTrue(ids.isNotEmpty(), "the log must expose at least one id to project") },
            { ids.forEach { id -> assertEquals(id.dot, r3.dotOf(id), "dotOf must agree with RgaId.dot for $id") } },
            { assertEquals(Dot(a, 1L), r3.dotOf(RgaId(lamport = 7L, replicaId = a, seq = 1L)), "dot is (replicaId, seq)") },
        )
    }

    @Test
    fun fugueDotOfAgreesWithFugueIdDot() {
        val (f3, _) = fugueWithACompaction()
        val ids = f3.operations().flatMap { op ->
            when (val shape = f3.classify(op)) {
                is LogOp.Insert -> sequenceOf(shape.id)
                is LogOp.Remove -> sequenceOf(shape.id)
                is LogOp.Compact -> shape.compactedIds.asSequence()
            }
        }.toList()

        assertAll(
            { assertTrue(ids.isNotEmpty(), "the log must expose at least one id to project") },
            { ids.forEach { id -> assertEquals(id.dot, f3.dotOf(id), "dotOf must agree with FugueId.dot for $id") } },
            { assertEquals(Dot(a, 1L), f3.dotOf(FugueId(lamport = 7L, replicaId = a, seq = 1L)), "dot is (replicaId, seq)") },
        )
    }

    /**
     * The dots [OpLogCrdt] projects out of the classification must be the fixture's **actual**
     * delivered frontier — inserts contribute their own dot, removes contribute none, and a
     * `Compact` re-contributes the dots it swallowed. This is the property `:kuilt-bolt`'s
     * insert-only dot field rests on.
     *
     * **Asserted against a literal, not against `causalDots()`.** Comparing the two would be
     * structurally green: `causalDots()` routes through the same `classifyOp` and the same
     * `id.dot` that [deliveredDotsVia] does, so any mutation to either perturbs both sides
     * identically and the equality survives. That comparison is still worth making — it catches
     * the public [OpLogCrdt.classify] drifting from the internal engine — but it is a *different*
     * property, and it is asserted separately below under its own name.
     */
    @Test
    fun insertAndCompactDotsReconstructTheDeliveredFrontierForBoth() {
        val (rga, _) = rgaWithACompaction()
        val (fugue, _) = fugueWithACompaction()

        // Both fixtures are the same shape: a's first insert, b's first insert, a remove of b's
        // element, then a compaction swallowing it. The remove mints no dot of its own and the
        // Compact re-contributes the one it swallowed, so the frontier is both replicas at seq 1.
        val expected = setOf(Dot(a, 1L), Dot(b, 1L))

        assertAll(
            { assertEquals(expected, deliveredDotsVia(rga), "Rga: classification must yield the delivered frontier") },
            { assertEquals(expected, deliveredDotsVia(fugue), "Fugue: classification must yield the delivered frontier") },
        )
    }

    /**
     * The public [OpLogCrdt.classify] must not drift from the internal engine's own view.
     *
     * Both sides share `classifyOp` today, which is the point — this is the regression pin on
     * that sharing, and it reddens the moment someone gives the public contract a second
     * classifier. It deliberately proves nothing about the frontier *itself*; that is the test
     * above.
     */
    @Test
    fun classificationAgreesWithTheInternalEngineForBoth() {
        val (rga, _) = rgaWithACompaction()
        val (fugue, _) = fugueWithACompaction()

        assertAll(
            { assertEquals(rga.causalDots(), deliveredDotsVia(rga), "Rga: classification must reconstruct causalDots()") },
            { assertEquals(fugue.causalDots(), deliveredDotsVia(fugue), "Fugue: classification must reconstruct causalDots()") },
        )
    }

    /**
     * [OpLogCrdt.operations] promises a sequence that may be iterated more than once, with every
     * iteration observing the same ops. Pinned because the KDoc *reserves* that guarantee against
     * a future streaming backing — and a one-shot `Sequence` would break consumers at runtime with
     * no compile-time signal. Iterates a **single returned instance** twice; re-calling
     * `operations()` each time would pass even against `constrainOnce()`.
     */
    @Test
    fun operationsMayBeIteratedMoreThanOnceForBoth() {
        val (rga, _) = rgaWithACompaction()
        val (fugue, _) = fugueWithACompaction()
        val rgaOps = rga.operations()
        val fugueOps = fugue.operations()

        val rgaFirst = rgaOps.toList()
        val rgaSecond = rgaOps.toList()
        val fugueFirst = fugueOps.toList()
        val fugueSecond = fugueOps.toList()

        assertAll(
            { assertTrue(rgaFirst.isNotEmpty(), "the fixture must hold ops for this to mean anything") },
            { assertEquals(rgaFirst, rgaSecond, "Rga: a second pass must observe the same ops") },
            { assertTrue(fugueFirst.isNotEmpty(), "the fixture must hold ops for this to mean anything") },
            { assertEquals(fugueFirst, fugueSecond, "Fugue: a second pass must observe the same ops") },
        )
    }

    /** The delivered frontier, derived from [OpLogCrdt] alone — inserts and compaction records only. */
    private fun <Id : Any, V, Op : Any> deliveredDotsVia(crdt: OpLogCrdt<Id, V, Op>): Set<Dot> =
        crdt.operations().flatMap { op ->
            when (val shape = crdt.classify(op)) {
                is LogOp.Insert -> sequenceOf(crdt.dotOf(shape.id))
                is LogOp.Remove -> emptySequence()
                is LogOp.Compact -> shape.compactedIds.asSequence().map(crdt::dotOf)
            }
        }.toSet()

    // ── opSerializer() ────────────────────────────────────────────────────────

    @Test
    fun rgaOpSerializerIsTheCanonicalRgaOpSerializer() {
        val (rga, _) = rgaWithACompaction()
        val fromContract: KSerializer<RgaOp<String>> = rga.opSerializer(serializer<String>())
        val canonical: KSerializer<RgaOp<String>> = RgaOpSerializer(serializer<String>())

        assertAll(
            { assertEquals(canonical.descriptor.serialName, fromContract.descriptor.serialName, "must be the canonical descriptor") },
            {
                rga.operations().forEach { op ->
                    assertContentEquals(
                        cbor.encodeToByteArray(canonical, op),
                        cbor.encodeToByteArray(fromContract, op),
                        "opSerializer must encode byte-identically to RgaOpSerializer for $op",
                    )
                }
            },
            {
                rga.operations().forEach { op ->
                    assertEquals(op, cbor.decodeFromByteArray(canonical, cbor.encodeToByteArray(fromContract, op)), "round-trip via the canonical reader")
                }
            },
        )
    }

    @Test
    fun fugueOpSerializerIsTheCanonicalFugueOpSerializer() {
        val (fugue, _) = fugueWithACompaction()
        val fromContract: KSerializer<FugueOp<String>> = fugue.opSerializer(serializer<String>())
        val canonical: KSerializer<FugueOp<String>> = FugueOpSerializer(serializer<String>())

        assertAll(
            { assertEquals(canonical.descriptor.serialName, fromContract.descriptor.serialName, "must be the canonical descriptor") },
            {
                fugue.operations().forEach { op ->
                    assertContentEquals(
                        cbor.encodeToByteArray(canonical, op),
                        cbor.encodeToByteArray(fromContract, op),
                        "opSerializer must encode byte-identically to FugueOpSerializer for $op",
                    )
                }
            },
            {
                fugue.operations().forEach { op ->
                    assertEquals(op, cbor.decodeFromByteArray(canonical, cbor.encodeToByteArray(fromContract, op)), "round-trip via the canonical reader")
                }
            },
        )
    }

    /**
     * The trap this method exists to close: the canonical serializers carry a mandatory `t`
     * discriminator as the **first** element, which is what lets a polymorphic `V` ride through
     * CBOR at all. A compiler-generated sealed serializer would name a different descriptor and
     * write a different shape, putting the archive outside the golden-vector guarantee.
     */
    @Test
    fun opSerializerDescriptorsCarryTheCanonicalTagForBoth() {
        val rgaDescriptor = Rga.empty<String>().opSerializer(serializer<String>()).descriptor
        val fugueDescriptor = Fugue.empty<String>().opSerializer(serializer<String>()).descriptor

        assertAll(
            { assertEquals("us.tractat.kuilt.crdt.RgaOp", rgaDescriptor.serialName, "canonical Rga op descriptor") },
            { assertEquals("us.tractat.kuilt.crdt.FugueOp", fugueDescriptor.serialName, "canonical Fugue op descriptor") },
            { assertEquals("t", rgaDescriptor.getElementName(0), "Rga ops lead with the canonical type tag") },
            { assertEquals("t", fugueDescriptor.getElementName(0), "Fugue ops lead with the canonical type tag") },
        )
    }

    // ── the Rga/Fugue asymmetry ───────────────────────────────────────────────

    /**
     * [Rga] alone can forget an op with **no** `Compact` op naming it: [Rga.dropWindow] raises the
     * O(authors) [Rga.compactedBelow] floor and purges the ops beneath it. So `operations()` on an
     * [Rga] is *not* a complete history of what the replica ever held — which is the entire reason
     * an archive has to be fed at append time rather than reconstructed from a live replica later.
     */
    @Test
    fun rgaOperationsShrinkPastTheFloorWithNoCompactOpNamingTheDroppedIds() {
        val (r1, i1) = Rga.empty<String>().insertAt(a, 0, "x")
        val (r2, i2) = r1.insertAt(a, 1, "y")
        val (r3, _) = assertNotNull(r2.removeAt(0), "removeAt(0) must find the first visible element")

        val (dropped, _) = assertNotNull(r3.dropWindow(a, setOf(i1.id)), "dropWindow must accept a non-empty set")
        val remaining = dropped.operations().toList()

        assertAll(
            { assertTrue(remaining.none { it is RgaOp.Insert && it.id == i1.id }, "the floored insert leaves operations()") },
            { assertTrue(remaining.any { it is RgaOp.Insert && it.id == i2.id }, "the surviving insert stays") },
            {
                assertTrue(
                    remaining.none { op -> dropped.classify(op).let { it is LogOp.Compact && i1.id in it.compactedIds } },
                    "no Compact op names the floored id — the floor records it in compactedBelow, not in the log",
                )
            },
            { assertTrue(dropped.compactedBelow.contains(i1.id.dot), "the floor is where the dropped dot went") },
        )
    }

    /**
     * [Fugue] has no floor, so every id it forgets is named by a retained `Compact` op — the
     * archive can always see what was suppressed. Pinned as the counterpart to the [Rga] case
     * above so the asymmetry is documented from both sides.
     */
    @Test
    fun fugueAlwaysNamesEveryForgottenIdInARetainedCompactOp() {
        val (f3, compactOp) = fugueWithACompaction()
        val namedByCompaction = f3.operations()
            .map { f3.classify(it) }
            .filterIsInstance<LogOp.Compact<FugueId>>()
            .flatMap { it.compactedIds.asSequence() }
            .toSet()

        // The ids the log no longer carries as content — what the compaction actually forgot.
        val goneFromContent = namedByCompaction.filter { id ->
            f3.operations().none { op -> f3.classify(op).let { it is LogOp.Insert && it.id == id } }
        }.toSet()

        assertAll(
            { assertTrue(compactOp.positions.keys.isNotEmpty(), "the compaction must have swallowed at least one id") },
            { assertEquals(compactOp.positions.keys, namedByCompaction, "every compacted id is recoverable from the log") },
            { assertEquals(namedByCompaction, goneFromContent, "and every id it names really did leave the content ops") },
        )
    }

    // ── compactedIds ──────────────────────────────────────────────────────────

    /**
     * The concrete `compactedIds` accessor must agree with the set derived through the [OpLogCrdt]
     * contract, on **both** implementations.
     *
     * This is what makes publishing that accessor on both types (#2223) a convenience rather than
     * a new commitment: the same set is already reachable by unioning the
     * [LogOp.Compact.compactedIds] of every classified op, so the accessor adds a cached O(1) read
     * of something a consumer could always have walked the log for.
     *
     * It is not a tautology. The accessor reads the **threaded cache** — `compact` folds the GC'd
     * ids forward into it — while [compactedIdsVia] re-reads the **retained `Compact` ops**. A
     * cache that drifted from the log would fail here and nowhere else.
     */
    @Test
    fun compactedIdsAgreesWithTheContractDerivedSetForBoth() {
        val (rga, _) = rgaWithACompaction()
        val (fugue, _) = fugueWithACompaction()

        assertAll(
            { assertTrue(rga.compactedIds.isNotEmpty(), "Rga: the fixture must have compacted something") },
            { assertTrue(fugue.compactedIds.isNotEmpty(), "Fugue: the fixture must have compacted something") },
            { assertEquals(compactedIdsVia(rga), rga.compactedIds, "Rga: accessor must equal the contract-derived union") },
            { assertEquals(compactedIdsVia(fugue), fugue.compactedIds, "Fugue: accessor must equal the contract-derived union") },
        )
    }

    /** The compacted-id set, derived from [OpLogCrdt] alone — the union of every compaction record. */
    private fun <Id : Any, V, Op : Any> compactedIdsVia(crdt: OpLogCrdt<Id, V, Op>): Set<Id> =
        crdt.operations().flatMap { op ->
            when (val shape = crdt.classify(op)) {
                is LogOp.Compact -> shape.compactedIds.asSequence()
                is LogOp.Insert, is LogOp.Remove -> emptySequence()
            }
        }.toSet()

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     * An [Rga] whose log holds a surviving Insert, plus a retained [RgaOp.Compact].
     *
     * The compacted element is the **last** one, deliberately: both CRDTs refuse to GC an id
     * that a live insert still anchors on (`after` for [Rga], `parent`/`rightOrigin` for
     * [Fugue]), so compacting the *first* element of a two-element sequence silently yields
     * `null` and no compaction at all.
     */
    private fun rgaWithACompaction(): Pair<Rga<String>, RgaOp.Compact> {
        val (r1, i1) = Rga.empty<String>().insertAt(a, 0, "x")
        val (r2, i2) = r1.insertAt(b, 1, "y")
        val (r3, _) = assertNotNull(r2.removeAt(1), "removeAt(1) must find the trailing element")

        val cut = VersionVector.of(mapOf(a to i1.id.seq, b to i2.id.seq))
        val (compacted, compactOp) = assertNotNull(
            r3.compact(stableCut = cut, frontierMax = cut, delivered = cut),
            "the tombstoned, causally-stable, unanchored insert must be GC-eligible",
        )
        assertIs<RgaOp.Compact>(compactOp, "compact must hand back a Compact op")
        return compacted to compactOp
    }

    /** A [Fugue] whose log holds a surviving Insert, plus a retained [FugueOp.Compact]. */
    private fun fugueWithACompaction(): Pair<Fugue<String>, FugueOp.Compact> {
        val (f1, i1) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (f2, i2) = f1.insertAt(b, 1, "y")
        val (f3, _) = assertNotNull(f2.removeAt(1), "removeAt(1) must find the trailing element")

        val cut = VersionVector.of(mapOf(a to i1.id.seq, b to i2.id.seq))
        val (compacted, compactOp) = assertNotNull(
            f3.compact(stableCut = cut, frontierMax = cut, delivered = cut),
            "the tombstoned, causally-stable, unanchored insert must be GC-eligible",
        )
        assertIs<FugueOp.Compact>(compactOp, "compact must hand back a Compact op")
        return compacted to compactOp
    }
}
