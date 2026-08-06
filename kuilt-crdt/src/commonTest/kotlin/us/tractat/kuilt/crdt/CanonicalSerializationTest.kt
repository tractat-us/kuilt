package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cross-replica byte-stability tests: two replicas that reach the same logical
 * state via **different delivery orders** must serialize to **identical bytes**
 * (JSON and CBOR). Any ordering dependency in the serialized form silently breaks
 * content-addressing, digest equality, and Quilter delta fingerprinting.
 *
 * Issue #713 — audit of Set-backed CRDT serializers for delivery-order byte instability.
 */
@OptIn(ExperimentalSerializationApi::class)
class CanonicalSerializationTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private val json = Json { allowStructuredMapKeys = true }
    private val cbor = Cbor {}

    // ── DotContext ────────────────────────────────────────────────────────────

    /**
     * Two DotContext replicas absorb the same dots in different orders.
     * Their serialized bytes must be identical.
     */
    @Test
    fun dotContextSerializationIsDeliveryOrderIndependent() {
        val dotA1 = Dot(a, 1L)
        val dotB1 = Dot(b, 1L)

        // Replica 1: sees A first, then B
        val ctx1 = DotContext.EMPTY.add(dotA1).add(dotB1)
        // Replica 2: sees B first, then A
        val ctx2 = DotContext.EMPTY.add(dotB1).add(dotA1)

        assertEquals(ctx1, ctx2) // sanity: same logical state

        val jsonBytes1 = json.encodeToString(DotContext.serializer(), ctx1)
        val jsonBytes2 = json.encodeToString(DotContext.serializer(), ctx2)
        assertEquals(jsonBytes1, jsonBytes2, "DotContext JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(DotContext.serializer(), ctx1)
        val cborBytes2 = cbor.encodeToByteArray(DotContext.serializer(), ctx2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "DotContext CBOR must be delivery-order-independent")
    }

    /**
     * `DotContext` with a **non-empty, multi-entry** cloud is canonical — in both formats.
     *
     * This was the weakest of the canonical probes (#2038): it asserted JSON only, and it took no
     * position on the cloud actually holding anything. A dot only stays in the cloud while a gap
     * sits below it, so the whole property hangs off [DotContext]'s compaction rule — and if a
     * change to that rule ever folded these dots into `vv`, an assertion comparing two encodings
     * of an *empty* cloud would still pass, having tested nothing. The two guards below say what
     * the construction must keep: both dots present, and the two clouds iterating differently.
     *
     * CBOR matters here beyond breadth: it is the format `CanonicalGoldenVectorTest` pins, so a
     * JSON-only assertion left the cloud sort unchecked in the encoding that actually ships.
     */
    @Test
    fun dotContextWithCloudIsCanonical() {
        // Each dot sits above a gap — no (A,1)/(A,2), no (B,1) — so compaction cannot fold either
        // into `vv`. Two of them, because a one-dot cloud has exactly one order and pins nothing.
        val dotA3 = Dot(a, 3L)
        val dotB2 = Dot(b, 2L)

        val ctx1 = DotContext.EMPTY.add(dotA3).add(dotB2)
        val ctx2 = DotContext.EMPTY.add(dotB2).add(dotA3)
        val ser = DotContext.serializer()

        assertAll(
            { assertEquals(ctx1, ctx2, "sanity: the two contexts must be the same logical state") },
            {
                assertEquals(
                    setOf(dotA3, dotB2),
                    ctx1.cloud,
                    "vacuity guard: both gapped dots must still be in the cloud, else the sort " +
                        "under test never runs",
                )
            },
            {
                assertNotEquals(
                    ctx1.cloud.toList(),
                    ctx2.cloud.toList(),
                    "vacuity guard: the two clouds must iterate differently, else the sort is not " +
                        "what makes the bytes agree",
                )
            },
            {
                assertEquals(
                    json.encodeToString(ser, ctx1),
                    json.encodeToString(ser, ctx2),
                    "DotContext cloud JSON must be delivery-order-independent",
                )
            },
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, ctx1).toList(),
                    cbor.encodeToByteArray(ser, ctx2).toList(),
                    "DotContext cloud CBOR must be delivery-order-independent",
                )
            },
        )
    }

    // ── ORSet (via DotFun / DotSet) ───────────────────────────────────────────

    /**
     * Two ORSet replicas add elements in opposite orders and then merge.
     * Their serialized bytes after merge must be identical.
     */
    @Test
    fun orSetSerializationIsDeliveryOrderIndependent() {
        // Replica 1: A adds "x", B adds "y"
        val s1a = ORSet.empty<String>().piece { it.add(a, "x") }
        val s1b = ORSet.empty<String>().piece { it.add(b, "y") }
        val merged1 = s1a.piece(s1b)

        // Replica 2: B adds "y", A adds "x"
        val s2b = ORSet.empty<String>().piece { it.add(b, "y") }
        val s2a = ORSet.empty<String>().piece { it.add(a, "x") }
        val merged2 = s2b.piece(s2a)

        assertEquals(merged1, merged2)

        val ser = ORSet.serializer(String.serializer())
        val jsonBytes1 = json.encodeToString(ser, merged1)
        val jsonBytes2 = json.encodeToString(ser, merged2)
        assertEquals(jsonBytes1, jsonBytes2, "ORSet JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, merged1)
        val cborBytes2 = cbor.encodeToByteArray(ser, merged2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "ORSet CBOR must be delivery-order-independent")
    }

    // ── ResettableCounter (via DotFun) ────────────────────────────────────────

    /**
     * Two ResettableCounter replicas absorb increments in different orders.
     * Their serialized bytes must be identical.
     */
    @Test
    fun resettableCounterSerializationIsDeliveryOrderIndependent() {
        val incA = ResettableCounter.ZERO.increment(a, 3L)
        val incB = ResettableCounter.ZERO.increment(b, 5L)

        // Replica 1: absorbs A's increment, then B's
        val counter1 = ResettableCounter.ZERO.piece(incA).piece(incB)

        // Replica 2: absorbs B's increment, then A's
        val counter2 = ResettableCounter.ZERO.piece(incB).piece(incA)

        assertEquals(counter1.value, counter2.value)
        assertEquals(counter1, counter2)

        val ser = ResettableCounter.serializer()
        val jsonBytes1 = json.encodeToString(ser, counter1)
        val jsonBytes2 = json.encodeToString(ser, counter2)
        assertEquals(jsonBytes1, jsonBytes2, "ResettableCounter JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, counter1)
        val cborBytes2 = cbor.encodeToByteArray(ser, counter2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "ResettableCounter CBOR must be delivery-order-independent")
    }

    // ── ORMap (via DotMap) ────────────────────────────────────────────────────

    /**
     * Two ORMap replicas put keys in opposite orders and then merge.
     * Their serialized bytes after merge must be identical.
     *
     * This test catches a regression where DotMapSerializer sorted by [key.toString()]
     * instead of the structural key encoding — a toString-based sort is fragile for
     * non-injective types (Double, ByteArray, etc.).  The String key here is the
     * minimal exercise; the structural-sort property is proven by insertion-order
     * independence (issue #752).
     *
     * **The values are deliberately multi-entry *and split across both replicas* (#1957).**
     * Each side holds both keys, but contributes a different pair of [GCounter] slots, so the
     * merge runs [GCounter.piece] — and therefore the `HashMap` build inside `mergeMax` — in
     * *opposite insertion orders* on the two sides. Two weaker shapes were measured and both
     * are vacuous: a single-entry value has exactly one iteration order, and a multi-entry value
     * *shared* by both replicas is worse than it looks — a key only one side holds is joined as
     * `value.piece(value)`, seeding `mergeMax`'s `HashMap` from the identical source on both
     * sides. Only divergent slices make the value map's order observable.
     *
     * Mutation-checked on `macosArm64`: dropping `@Serializable(with = CanonicalMapSerializer::class)`
     * from `GCounter.counts` makes this fail with the counter slots reordered. (Neither weaker
     * shape fails under the same mutation.)
     */
    @Test
    fun orMapSerializationIsDeliveryOrderIndependent() {
        val c = ReplicaId("C")
        val d = ReplicaId("D")

        // Divergent slices of the same two logical values: A knows about {A, C}, B about {B, D}.
        val alphaFromA = GCounter.of(a to 1L, c to 3L)
        val alphaFromB = GCounter.of(b to 2L, d to 4L)
        val betaFromA = GCounter.of(a to 5L, d to 7L)
        val betaFromB = GCounter.of(b to 6L, c to 8L)

        // A's view puts "alpha" then "beta"; B's view puts them the other way round.
        val viewA = ORMap.empty<String, GCounter>()
            .piece { it.put(a, "alpha", alphaFromA) }
            .piece { it.put(a, "beta", betaFromA) }
        val viewB = ORMap.empty<String, GCounter>()
            .piece { it.put(b, "beta", betaFromB) }
            .piece { it.put(b, "alpha", alphaFromB) }

        // Replica 1 hears A first, replica 2 hears B first.
        val merged1 = viewA.piece(viewB)
        val merged2 = viewB.piece(viewA)

        assertEquals(merged1, merged2)

        val ser = ORMap.serializer(String.serializer(), GCounter.serializer())
        val jsonBytes1 = json.encodeToString(ser, merged1)
        val jsonBytes2 = json.encodeToString(ser, merged2)
        assertEquals(jsonBytes1, jsonBytes2, "ORMap JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, merged1)
        val cborBytes2 = cbor.encodeToByteArray(ser, merged2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "ORMap CBOR must be delivery-order-independent")
    }

    /**
     * DotMap structural sort must be independent of the order entries were inserted,
     * not just of merge order.  This directly validates that the comparator is a
     * pure function of key content — the structural-sort invariant (#752).
     *
     * We construct two DotMap instances with the same keys but in different insertion
     * orders (without merging) and assert identical serialized bytes.
     */
    @Test
    fun dotMapSortIsInsertionOrderIndependent() {
        val dotA = Dot(a, 1L)
        val dotB = Dot(b, 1L)

        // Build two DotMaps with the same entries but in reversed insertion order.
        val map1 = DotMap(linkedMapOf("zebra" to DotSet(setOf(dotA)), "aardvark" to DotSet(setOf(dotB))))
        val map2 = DotMap(linkedMapOf("aardvark" to DotSet(setOf(dotB)), "zebra" to DotSet(setOf(dotA))))

        // They must be equal (same entries) …
        assertEquals(map1, map2)

        // … and serialize to the same bytes regardless of insertion order.
        val ser = DotMap.serializer(String.serializer(), DotSet.serializer())
        val jsonBytes1 = json.encodeToString(ser, map1)
        val jsonBytes2 = json.encodeToString(ser, map2)
        assertEquals(jsonBytes1, jsonBytes2, "DotMap JSON must be insertion-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, map1)
        val cborBytes2 = cbor.encodeToByteArray(ser, map2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "DotMap CBOR must be insertion-order-independent")
    }

    /**
     * Regression guard for the toString-sort fragility (#752): two structurally
     * distinct keys that share a common [toString] prefix must still sort
     * deterministically by their structural encoding.
     *
     * We use [ReplicaId] keys (inline value class over [String]) to confirm that
     * the structural-key comparator works for non-primitive serializable types.
     */
    @Test
    fun dotMapStructuralSortWorksForInlineValueClassKeys() {
        val r1 = ReplicaId("alice")
        val r2 = ReplicaId("bob")

        val dotR1 = Dot(a, 1L)
        val dotR2 = Dot(b, 1L)

        val map1 = DotMap(linkedMapOf(r1 to DotSet(setOf(dotR1)), r2 to DotSet(setOf(dotR2))))
        val map2 = DotMap(linkedMapOf(r2 to DotSet(setOf(dotR2)), r1 to DotSet(setOf(dotR1))))

        assertEquals(map1, map2)

        val ser = DotMap.serializer(ReplicaId.serializer(), DotSet.serializer())
        val jsonBytes1 = json.encodeToString(ser, map1)
        val jsonBytes2 = json.encodeToString(ser, map2)
        assertEquals(jsonBytes1, jsonBytes2, "DotMap with ReplicaId keys must be insertion-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, map1)
        val cborBytes2 = cbor.encodeToByteArray(ser, map2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "DotMap with ReplicaId keys CBOR must be canonical")
    }

    // ── Rga ───────────────────────────────────────────────────────────────────

    /**
     * Two Rga replicas absorb ops in different orders and arrive at the same
     * logical sequence. Their serialized bytes must be identical.
     */
    @Test
    fun rgaSerializationIsDeliveryOrderIndependent() {
        // A inserts "hello", B inserts "world"
        val (rgaA, opA) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "hello")
        val (rgaB, opB) = Rga.empty<String>().insertAfter(b, RgaId.HEAD, "world")

        // Replica 1: starts from A's state, applies B's op
        val rga1 = rgaA.apply(opB)

        // Replica 2: starts from B's state, applies A's op
        val rga2 = rgaB.apply(opA)

        assertEquals(rga1, rga2) // same logical op-set

        val ser = Rga.wireSerializer(String.serializer())
        val jsonBytes1 = json.encodeToString(ser, rga1)
        val jsonBytes2 = json.encodeToString(ser, rga2)
        assertEquals(jsonBytes1, jsonBytes2, "Rga JSON must be delivery-order-independent")

        val cborBytes1 = cbor.encodeToByteArray(ser, rga1)
        val cborBytes2 = cbor.encodeToByteArray(ser, rga2)
        assertEquals(cborBytes1.toList(), cborBytes2.toList(), "Rga CBOR must be delivery-order-independent")
    }

    // ── Fugue lamport-invariance (#779) ───────────────────────────────────────

    /**
     * Two Fugue replicas with identical op-sets but different [Fugue.lamport] high-water
     * marks must serialize to **identical bytes**.
     *
     * The divergence is natural: [Fugue.piece] (and [Rga.piece]) advances the lamport
     * to `max(left, right)`. A replica that merged with a peer holding a higher lamport
     * carries that higher clock even when the op-sets are otherwise identical. Before
     * this fix [FugueSerializer] encoded the raw [Fugue.lamport] field, so two
     * logically-equal replicas produced different bytes — breaking content-addressing
     * and Quilter delta fingerprinting (issue #779).
     *
     * We use [Fugue.fromOps] (internal) to construct the divergent-lamport scenario
     * directly, which is the cleanest way to set up two Fugue instances with the same
     * op-set but different clock high-waters without relying on a specific sequence of
     * [piece]/[apply] calls.
     */
    @Test
    fun fugueSerializationIsLamportInvariant() {
        val (fugueA, opA) = Fugue.empty<String>().insertAt(a, 0, "x")
        val ops = fugueA.ops

        // Two replicas: identical op-set, different lamport high-water marks.
        val normalLamport = Fugue.fromOps(ops, opA.id.lamport)          // lamport == 1
        val advancedLamport = Fugue.fromOps(ops, opA.id.lamport + 99L)  // lamport == 100

        val ser = Fugue.wireSerializer(String.serializer())

        assertAll(
            { assertEquals(normalLamport, advancedLamport, "Fugue.equals must be lamport-invariant") },
            {
                val json1 = json.encodeToString(ser, normalLamport)
                val json2 = json.encodeToString(ser, advancedLamport)
                assertEquals(json1, json2, "Fugue JSON must be lamport-invariant")
            },
            {
                val cbor1 = cbor.encodeToByteArray(ser, normalLamport)
                val cbor2 = cbor.encodeToByteArray(ser, advancedLamport)
                assertEquals(cbor1.toList(), cbor2.toList(), "Fugue CBOR must be lamport-invariant")
            },
        )
    }

    /**
     * Two Rga replicas with identical op-sets but different [Rga.lamport] high-water
     * marks must be considered equal **and** serialize to identical bytes.
     *
     * Before this fix [Rga.equals] included [Rga.lamport], so two converged replicas
     * (same op-set, different clock) were not equal. [RgaSerializer] also encoded the
     * raw [Rga.lamport] field, so bytes differed. Both are fixed together: [Rga.equals]
     * and [RgaSerializer] become lamport-invariant, consistent with [Fugue]'s rule.
     *
     * Issue #779.
     */
    @Test
    fun rgaSerializationIsLamportInvariant() {
        val (rgaA, opA) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "x")
        val ops = rgaA.ops

        // Two replicas: identical op-set, different lamport high-water marks.
        val normalLamport = Rga.fromOps(ops, opA.id.lamport)          // lamport == 1
        val advancedLamport = Rga.fromOps(ops, opA.id.lamport + 99L)  // lamport == 100

        val ser = Rga.wireSerializer(String.serializer())

        assertAll(
            { assertEquals(normalLamport, advancedLamport, "Rga.equals must be lamport-invariant") },
            {
                val json1 = json.encodeToString(ser, normalLamport)
                val json2 = json.encodeToString(ser, advancedLamport)
                assertEquals(json1, json2, "Rga JSON must be lamport-invariant")
            },
            {
                val cbor1 = cbor.encodeToByteArray(ser, normalLamport)
                val cbor2 = cbor.encodeToByteArray(ser, advancedLamport)
                assertEquals(cbor1.toList(), cbor2.toList(), "Rga CBOR must be lamport-invariant")
            },
        )
    }

    // ── MovableTree compacted dots (#1957) ────────────────────────────────────

    /**
     * A tree owned by [replica] with a compaction applied, so `compactedDots` holds exactly the
     * dot of the superseded `ts=3` move.
     */
    private fun compactedTreeFor(replica: ReplicaId, tag: String): MovableTree<String> {
        val (afterFirst, first) =
            MovableTree.empty<String>().addNode(replica, ts = 1L, parent = MovableTree.ROOT_ID, value = "${tag}1")
        val (afterSecond, second) =
            afterFirst.addNode(replica, ts = 2L, parent = MovableTree.ROOT_ID, value = "${tag}2")
        val (afterMove, _) = afterSecond.move(replica, ts = 3L, node = first, newParent = second)
        val (afterSupersede, _) = afterMove.move(replica, ts = 4L, node = first, newParent = MovableTree.ROOT_ID)

        val cut = VersionVector.of(mapOf(replica to 4L))
        val (compacted, _) = afterSupersede.compact(stableCut = cut, frontierMax = cut, delivered = cut)
            ?: error("compact() must succeed for $replica — the ts=3 move is stable and superseded")
        return compacted
    }

    /**
     * Two replicas that each compacted their own move-log, then merged with the other in opposite
     * orders, must serialize to identical bytes.
     *
     * The `:kuilt-conformance` convergence suite cannot reach this field: its operation generators
     * never call [MovableTree.compact], so `compactedDots` is always empty and an empty set encodes
     * canonically on every target. A *compacted* tree is the state that diverges — `piece` merges the
     * two sides with `Set.plus`, whose `LinkedHashSet` result is in merge order.
     *
     * Mutation-checked: dropping the `@Serializable(with = CanonicalSetSerializer::class)` annotation
     * on `compactedDots` makes this test fail with `compactedDots` reversed and every other field
     * byte-identical.
     */
    @Test
    fun movableTreeCompactedDotsAreDeliveryOrderIndependent() {
        val alice = compactedTreeFor(ReplicaId("alice"), "a")
        val bob = compactedTreeFor(ReplicaId("bob"), "b")

        val ser = MovableTree.serializer(String.serializer())
        val aliceFirst = json.encodeToString(ser, alice.piece(bob))
        val bobFirst = json.encodeToString(ser, bob.piece(alice))

        assertAll(
            {
                assertTrue(
                    aliceFirst.contains("""{"replica":"alice","seq":3}"""),
                    "the probe is vacuous unless compactedDots is actually populated: $aliceFirst",
                )
            },
            { assertEquals(aliceFirst, bobFirst, "MovableTree JSON must be delivery-order-independent") },
            {
                val cbor1 = cbor.encodeToByteArray(ser, alice.piece(bob))
                val cbor2 = cbor.encodeToByteArray(ser, bob.piece(alice))
                assertEquals(cbor1.toList(), cbor2.toList(), "MovableTree CBOR must be delivery-order-independent")
            },
        )
    }

    // ── Rga / Fugue Compact.positions (#1978) ─────────────────────────────────

    /**
     * A cut that covers the single dot each of [a] and [b] mints below, used as
     * `stableCut`, `frontierMax` and `delivered` at once: both replicas have
     * delivered everything, so the compaction barrier's conditions 2 and 3 hold.
     */
    private val bothDelivered = VersionVector.of(mapOf(a to 1L, b to 1L))

    /**
     * An [Rga] holding exactly one tombstoned element authored by [replica], inserted
     * directly after [RgaId.HEAD] so no *other* insert names it as a predecessor —
     * compaction's condition 4.
     */
    private fun tombstonedRga(replica: ReplicaId, value: String): Rga<String> {
        val (inserted, _) = Rga.empty<String>().insertAfter(replica, RgaId.HEAD, value)
        return inserted.removeAt(0)?.first ?: error("the freshly-inserted element must be removable")
    }

    /**
     * Two replicas that merged each other's tombstone in **opposite orders** and then
     * each compacted locally must serialize to identical bytes.
     *
     * `Rga.compact` derives `positions` from [Rga.tombstones], which `piece` builds with
     * `Set.plus` — a `LinkedHashSet` in merge order. `RgaOp.Compact` is a `data class`, so
     * the two ops are `equal` (map equality ignores order) and the two `Rga`s are `equal`,
     * while a plain `MapSerializer` writes the entries in that merge order. `Compact` ops
     * ride inside the serialized `ops` set, so this is converged *state* on the wire.
     *
     * The `:kuilt-conformance` convergence suite cannot reach this: no operation generator
     * calls [Rga.compact], so `positions` is always absent and every op-set encodes
     * canonically. Same blind spot #1957 hit with `MovableTree.compactedDots`.
     *
     * Two sites are fixed and each is pinned by its own assertion below, because they are reached
     * by different callers and neither shadows the other:
     *
     * - `RgaOpSerializer.positionsSerializer` — the **wire** path, via [Rga.wireSerializer].
     *   Mutation-checked: reverting it to a plain `MapSerializer` fails the `Rga JSON`/`Rga CBOR`
     *   assertions with the two `pos` entries transposed and every other byte identical, and
     *   leaves the standalone-op assertion green.
     * - the `@Serializable(with = CanonicalMapSerializer::class)` annotation on
     *   [RgaOp.Compact.positions] — the **compiler-generated** serializer, which is public API a
     *   consumer reaches through `RgaOp.Compact.serializer()` to ship one op on its own.
     *   Mutation-checked: dropping the annotation fails only the standalone-op assertion.
     */
    @Test
    fun rgaCompactPositionsAreDeliveryOrderIndependent() {
        val alice = tombstonedRga(a, "a1")
        val bob = tombstonedRga(b, "b1")

        val (aliceCompacted, aliceOp) = alice.piece(bob)
            .compact(bothDelivered, bothDelivered, bothDelivered)
            ?: error("both tombstones are causally stable and unreferenced — compact() must succeed")
        val (bobCompacted, bobOp) = bob.piece(alice)
            .compact(bothDelivered, bothDelivered, bothDelivered)
            ?: error("both tombstones are causally stable and unreferenced — compact() must succeed")

        val ser = Rga.wireSerializer(String.serializer())

        assertAll(
            {
                assertEquals(
                    2, aliceOp.positions.size,
                    "the probe is vacuous unless positions carries both replicas' ids: ${aliceOp.positions}",
                )
            },
            {
                assertTrue(
                    aliceOp.positions.keys.toList() != bobOp.positions.keys.toList(),
                    "the probe is vacuous unless the two maps are built in opposite orders: ${aliceOp.positions}",
                )
            },
            { assertEquals(aliceCompacted, bobCompacted, "sanity: both replicas reached the same state") },
            {
                assertEquals(
                    json.encodeToString(ser, aliceCompacted),
                    json.encodeToString(ser, bobCompacted),
                    "compacted Rga JSON must be delivery-order-independent",
                )
            },
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, aliceCompacted).toList(),
                    cbor.encodeToByteArray(ser, bobCompacted).toList(),
                    "compacted Rga CBOR must be delivery-order-independent",
                )
            },
            {
                assertEquals(
                    json.encodeToString(RgaOp.Compact.serializer(), aliceOp),
                    json.encodeToString(RgaOp.Compact.serializer(), bobOp),
                    "a standalone RgaOp.Compact must be delivery-order-independent",
                )
            },
        )
    }

    /**
     * A [Fugue] holding exactly one tombstoned element authored by [replica]. Inserted into
     * an empty sequence, so its tree parent is [FugueId.HEAD] and its `rightOrigin` is null —
     * neither replica's id anchors the other, which is compaction's condition 4.
     */
    private fun tombstonedFugue(replica: ReplicaId, value: String): Fugue<String> {
        val (inserted, _) = Fugue.empty<String>().insertAt(replica, 0, value)
        return inserted.removeAt(0)?.first ?: error("the freshly-inserted element must be removable")
    }

    /**
     * [Fugue] carries the same defect byte for byte — `FugueOp.Compact.positions` is a
     * `Map<FugueId, FugueId>` built from a merge-ordered tombstone set and written by a plain
     * `MapSerializer`. See [rgaCompactPositionsAreDeliveryOrderIndependent] for the full argument
     * and the two-site mutation matrix; mutation-checked the same way against `FugueOpSerializer`
     * and [FugueOp.Compact.positions]'s annotation.
     */
    @Test
    fun fugueCompactPositionsAreDeliveryOrderIndependent() {
        val alice = tombstonedFugue(a, "a1")
        val bob = tombstonedFugue(b, "b1")

        val (aliceCompacted, aliceOp) = alice.piece(bob)
            .compact(bothDelivered, bothDelivered, bothDelivered)
            ?: error("both tombstones are causally stable and unreferenced — compact() must succeed")
        val (bobCompacted, bobOp) = bob.piece(alice)
            .compact(bothDelivered, bothDelivered, bothDelivered)
            ?: error("both tombstones are causally stable and unreferenced — compact() must succeed")

        val ser = Fugue.wireSerializer(String.serializer())

        assertAll(
            {
                assertEquals(
                    2, aliceOp.positions.size,
                    "the probe is vacuous unless positions carries both replicas' ids: ${aliceOp.positions}",
                )
            },
            {
                assertTrue(
                    aliceOp.positions.keys.toList() != bobOp.positions.keys.toList(),
                    "the probe is vacuous unless the two maps are built in opposite orders: ${aliceOp.positions}",
                )
            },
            { assertEquals(aliceCompacted, bobCompacted, "sanity: both replicas reached the same state") },
            {
                assertEquals(
                    json.encodeToString(ser, aliceCompacted),
                    json.encodeToString(ser, bobCompacted),
                    "compacted Fugue JSON must be delivery-order-independent",
                )
            },
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, aliceCompacted).toList(),
                    cbor.encodeToByteArray(ser, bobCompacted).toList(),
                    "compacted Fugue CBOR must be delivery-order-independent",
                )
            },
            {
                assertEquals(
                    json.encodeToString(FugueOp.Compact.serializer(), aliceOp),
                    json.encodeToString(FugueOp.Compact.serializer(), bobOp),
                    "a standalone FugueOp.Compact must be delivery-order-independent",
                )
            },
        )
    }

    /**
     * Two replicas that each compacted **before** merging must serialize to identical bytes.
     *
     * A second, independent mechanism from the one
     * [fugueCompactPositionsAreDeliveryOrderIndependent] pins — that one canonicalises the map
     * *within* one op, this is the ordering *between* ops. `Fugue.sortedOps` is
     * `ops.sortedWith(compareBy { it.id })` and **every** [FugueOp.Compact] reports
     * [FugueId.HEAD] as its id, so two `Compact` ops compare equal; `sortedWith` is stable, so
     * their relative order falls back to the iteration order of `ops` — a `LinkedHashSet` in
     * merge order. [Rga] has no such hole: `RgaSerializer.opComparator` keys on the op *type*
     * before delegating `Compact`-vs-`Compact` to [compareCompactPositions].
     *
     * Reachable on the plain `piece` path with no adversary and no out-of-order delivery, and
     * `FugueGcCoordinator.compactUntilStable` loops until `compact` returns null, so one replica
     * mints several `Compact` ops per pass.
     *
     * Deliberately disjoint from the within-op probe: each map here holds a **single** entry, so
     * a one-entry map has only one order and reverting the `positionsSerializer` fix cannot make
     * this test fail. Conversely the within-op probe holds a single `Compact` op, so reverting
     * this fix cannot make *it* fail. Neither can shadow the other.
     */
    @Test
    fun fugueMultipleCompactOpsAreDeliveryOrderIndependent() {
        val aliceCut = VersionVector.of(mapOf(a to 1L))
        val bobCut = VersionVector.of(mapOf(b to 1L))
        val (alice, _) = tombstonedFugue(a, "a1").compact(aliceCut, aliceCut, aliceCut)
            ?: error("alice's own tombstone is causally stable and unanchored — compact() must succeed")
        val (bob, _) = tombstonedFugue(b, "b1").compact(bobCut, bobCut, bobCut)
            ?: error("bob's own tombstone is causally stable and unanchored — compact() must succeed")

        val aliceFirst = alice.piece(bob)
        val bobFirst = bob.piece(alice)
        val ser = Fugue.wireSerializer(String.serializer())

        assertAll(
            {
                assertEquals(
                    2, aliceFirst.ops.filterIsInstance<FugueOp.Compact>().size,
                    "the probe is vacuous unless the merged log holds two Compact ops: ${aliceFirst.ops}",
                )
            },
            {
                assertTrue(
                    aliceFirst.ops.toList() != bobFirst.ops.toList(),
                    "the probe is vacuous unless the two op-sets iterate in opposite orders",
                )
            },
            { assertEquals(aliceFirst, bobFirst, "sanity: both replicas reached the same state") },
            {
                assertEquals(
                    json.encodeToString(ser, aliceFirst),
                    json.encodeToString(ser, bobFirst),
                    "a Fugue holding two Compact ops must be delivery-order-independent",
                )
            },
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, aliceFirst).toList(),
                    cbor.encodeToByteArray(ser, bobFirst).toList(),
                    "a Fugue holding two Compact ops must be delivery-order-independent (CBOR)",
                )
            },
        )
    }

    // ── VersionVector (#2010) ─────────────────────────────────────────────────

    /**
     * Two replicas that reached the **same** [VersionVector] by merging the same authors in
     * different orders must serialize to identical bytes.
     *
     * `VersionVector.combine` builds its result from `entries.keys + other.entries.keys` — a
     * `LinkedHashSet` in **merge order** — and `VersionVector` is a `data class`, so `equals` is
     * order-insensitive over the map and every equality test in the zoo stays green while the
     * bytes differ. Unlike the `HashMap` cases of #1979 this reproduces on **every** target,
     * including the JVM, because the offending order is deliberate insertion order rather than
     * hash order.
     *
     * It is on the wire: `QuiltMessage.Delivered.vector` gossips this vector on every local apply
     * and every anti-entropy tick, and #1986 proposes keying dot-family anti-entropy on a
     * version-vector *diff* — a diff over a non-canonical encoding reports permanent false
     * divergence.
     *
     * **Deliberately three authors.** Both pre-existing canonical-suite uses of [VersionVector]
     * are single-entry (`VersionVector.of(mapOf(replica to 4L))`), and a one-entry map has exactly
     * one iteration order, so neither pins anything — the same blind spot #1983 found for the
     * ORSET vector's singleton `DotSet`s. Two authors would be pinned by the two merge orders
     * below; three makes the middle author's placement load-bearing too.
     *
     * Mutation-checked: dropping `@Serializable(with = CanonicalMapSerializer::class)` from
     * [VersionVector.entries] fails the JSON and CBOR assertions with the three author slots
     * transposed and every other byte identical.
     */
    @Test
    fun versionVectorIsMergeOrderIndependent() {
        val alpha = VersionVector.of(mapOf(ReplicaId("alpha") to 3L))
        val zulu = VersionVector.of(mapOf(ReplicaId("zulu") to 5L))
        val mike = VersionVector.of(mapOf(ReplicaId("mike") to 7L))

        val alphaFirst = alpha.ceilWith(zulu).ceilWith(mike)
        val mikeFirst = mike.ceilWith(zulu).ceilWith(alpha)

        val ser = VersionVector.serializer()

        assertAll(
            {
                assertEquals(
                    3, alphaFirst.entries.size,
                    "the probe is vacuous unless the vector is multi-entry: ${alphaFirst.entries}",
                )
            },
            {
                assertTrue(
                    alphaFirst.entries.keys.toList() != mikeFirst.entries.keys.toList(),
                    "the probe is vacuous unless the two vectors are built in different orders: " +
                        "${alphaFirst.entries.keys} vs ${mikeFirst.entries.keys}",
                )
            },
            { assertEquals(alphaFirst, mikeFirst, "sanity: both replicas reached the same vector") },
            {
                assertEquals(
                    json.encodeToString(ser, alphaFirst),
                    json.encodeToString(ser, mikeFirst),
                    "VersionVector JSON must be merge-order-independent",
                )
            },
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, alphaFirst).toList(),
                    cbor.encodeToByteArray(ser, mikeFirst).toList(),
                    "VersionVector CBOR must be merge-order-independent",
                )
            },
        )
    }

    /**
     * A [VersionVector] built **directly** from a differently-ordered map must encode identically
     * too — the fix has to sit in the serializer, not in [VersionVector.combine].
     *
     * Not redundant with [versionVectorIsMergeOrderIndependent], and deliberately so: sorting the
     * `authors` set inside `combine` is the obvious cheaper-looking alternative fix, and it would
     * turn that test green while leaving this one red. It would also be **wrong on the shipped
     * path**, which does not go through `combine` at all — `Quilter`'s delivered vector is built by
     * `contiguousFrontier(dots, floor)`, whose author order comes from `dots.groupBy { it.replica }`
     * — a `LinkedHashMap` in the iteration order of a merge-ordered `Set<Dot>`, with any
     * floor-only author appended after it. Every public entry point that can mint a
     * vector — the constructor, [VersionVector.of], `combine` — has to land on the same bytes, and
     * only canonicalising at the encoder achieves that for all of them at once.
     *
     * Mutation-checked: replacing the `CanonicalMapSerializer` annotation with a sort inside
     * `combine` leaves [versionVectorIsMergeOrderIndependent] green and fails this.
     */
    @Test
    fun versionVectorIsInsertionOrderIndependent() {
        val forward = VersionVector.of(linkedMapOf(ReplicaId("alpha") to 3L, ReplicaId("zulu") to 5L))
        val reverse = VersionVector.of(linkedMapOf(ReplicaId("zulu") to 5L, ReplicaId("alpha") to 3L))
        val ser = VersionVector.serializer()

        assertAll(
            {
                assertTrue(
                    forward.entries.keys.toList() != reverse.entries.keys.toList(),
                    "the probe is vacuous unless the two maps iterate differently",
                )
            },
            { assertEquals(forward, reverse, "sanity: the two vectors are the same value") },
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, forward).toList(),
                    cbor.encodeToByteArray(ser, reverse).toList(),
                    "VersionVector CBOR must be insertion-order-independent",
                )
            },
        )
    }

    /**
     * A replica that received `Remove(x)` **before** `Insert(x)` must serialize identically to one
     * that received them in causal order.
     *
     * The same `compareBy { it.id }` tie: [FugueOp.Insert] and [FugueOp.Remove] for one element
     * share an id, so the stable sort falls back to `ops` iteration order — which is arrival order.
     *
     * Reachability verified rather than assumed: [Fugue.apply] is public, `applyRemove` has no
     * guard requiring the insert to be present (it tombstones the id and appends the op), and
     * [Fugue.causalDots]' own KDoc contemplates the case — "would over-claim when a Remove arrives
     * before its Insert". So an op-based consumer delivering out of causal order reaches this on
     * shipped API. [Rga] is already immune via its op-type-ordinal primary key.
     *
     * Disjoint from both `Compact` probes above: this log holds no `Compact` op at all.
     */
    @Test
    fun fugueInsertAndRemoveOfOneElementAreArrivalOrderIndependent() {
        val (afterInsert, insertOp) = Fugue.empty<String>().insertAt(a, 0, "x")
        val (_, removeOp) = afterInsert.removeAt(0) ?: error("the freshly-inserted element must be removable")

        val causalOrder = Fugue.empty<String>().apply(insertOp).apply(removeOp)
        val reversedOrder = Fugue.empty<String>().apply(removeOp).apply(insertOp)
        val ser = Fugue.wireSerializer(String.serializer())

        assertAll(
            {
                assertTrue(
                    causalOrder.ops.toList() != reversedOrder.ops.toList(),
                    "the probe is vacuous unless the two op-sets iterate in opposite orders",
                )
            },
            { assertEquals(causalOrder, reversedOrder, "sanity: both replicas hold the same op-set") },
            {
                assertEquals(
                    json.encodeToString(ser, causalOrder),
                    json.encodeToString(ser, reversedOrder),
                    "Fugue must encode an Insert/Remove pair independently of arrival order",
                )
            },
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, causalOrder).toList(),
                    cbor.encodeToByteArray(ser, reversedOrder).toList(),
                    "Fugue must encode an Insert/Remove pair independently of arrival order (CBOR)",
                )
            },
        )
    }
}
