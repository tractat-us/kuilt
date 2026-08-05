package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-target encoding pin (issue #1957), following the `HeddlePolicyGoldenVectorTest`
 * and SRA wire byte-parity precedent.
 *
 * The `:kuilt-conformance` convergence harness proves each target encodes a converged value
 * the same way regardless of merge order. It cannot prove that **two different targets** agree
 * — every comparison it makes happens in one process on one target, so a platform-dependent
 * encoding is self-consistent on each side while the bytes differ. That is exactly the failure
 * that would make `Quilter`'s root digest (#1955 — a hash over the encoded state) report permanent
 * false divergence between a JVM peer and a Native peer: the two would never agree, so every
 * anti-entropy round between them would fall back to shipping the whole state, forever.
 * Only a checked-in byte string catches it, and because `commonTest` compiles and
 * runs on JVM, Android, iOS, macOS and wasmJs, **this file *is* the cross-target check.**
 *
 * **Every construction below is deliberately shaped so that removing its type's canonical
 * serializer changes the bytes.** Two shapes were measured and are vacuous, so neither is used
 * here: a single-entry map or set has exactly one iteration order and pins nothing, and a
 * multi-entry value that both replicas hold *identically* degenerates to `value.piece(value)`,
 * seeding the merge from the same source on both sides. Each vector is therefore built by
 * merging replicas that contribute **different** slices, in a deliberately non-sorted order.
 * The `*_DELTA` vectors are the one place a single-entry store appears, because that *is* the
 * shape a delta has; what they exist to pin is the causal context riding with it, and every one
 * of those is multi-dot and non-sorted on arrival.
 *
 * **Mutation-verified on `jvmTest`: every canonicalisation site #1957 introduced — the nine
 * `Canonical*Serializer` annotations — plus the three hand-written dot-family sorts the vectors do
 * reach is detected by at least one vector, and no vector is vacuous.** Disabling one site at a
 * time — commenting out the annotation, or deleting the sort from a hand-written serializer — and
 * re-running gives:
 *
 * | site removed | vectors that fail |
 * |---|---|
 * | `GSet.elements` | [GSET] |
 * | `TwoPhaseSet.added` | [TWO_PHASE_SET] |
 * | `TwoPhaseSet.removed` | [TWO_PHASE_SET] |
 * | `GCounter.counts` | [GCOUNTER], [PNCOUNTER], [ORMAP], [BOUNDED_COUNTER], [ORMAP_PUT_DELTA] |
 * | `LWWMap.cells` | [LWWMAP] |
 * | `BoundedCounter.transfers` | [BOUNDED_COUNTER] |
 * | `EphemeralMap.entries` | [EPHEMERAL_MAP] |
 * | `MovableTree.seqByReplica` | [MOVABLE_TREE] |
 * | `MovableTree.compactedDots` | [MOVABLE_TREE] |
 * | `DotMapSerializer`'s sort | [ORSET], [ORMAP] |
 * | `DotSetSerializer`'s sort | [ORSET] |
 * | `DotFunSerializer`'s sort | [MV_REGISTER] |
 * | `ORMapEntrySerializer`'s sort | [ORMAP], [JSON_CRDT] |
 * | `DotContextSerializer`'s `vv` sort | [ORSET], [ORMAP], [MV_REGISTER], [DOT_CONTEXT], [JSON_CRDT] |
 * | `DotContextSerializer`'s `cloud` sort | [DOT_CONTEXT], [ORSET_ADD_DELTA], [ORSET_REMOVE_DELTA] |
 * | `VersionVector.entries` | [VERSION_VECTOR] |
 * | `RgaSerializer`'s op sort | [RGA], [JSON_CRDT] |
 * | `FugueSerializer`'s op sort | [FUGUE] |
 *
 * The `cloud`, `RgaSerializer` and `FugueSerializer` rows closed #2038: `cloud` is empty in
 * [ORSET], [ORMAP] and [MV_REGISTER], so its sort had no vector at all, and `Rga`, `Fugue` and
 * `JsonCrdt` had no cross-target byte pin of any kind — only same-target delivery-order probes,
 * which by construction cannot see two targets disagree.
 * **Adding a type to that family does not inherit a pin from this file — add a vector.**
 *
 * The three `*_DELTA` rows closed #2044's half of that gap. A `DotContext` reaches a non-empty
 * `cloud` on an `ORSet`/`ORMap` frame only when the frame is a **delta**: a delta's context starts
 * empty and witnesses just the dots the operation asserts or retires, so any of them above the
 * sender's first seq has a gap below it and cannot compact into the vector. Until the delta
 * mutators landed, that shape could not be produced by any mutator at all, and the `cloud` sort was
 * pinned only by [DOT_CONTEXT] — a `DotContext` standing alone, never one riding a CRDT frame.
 *
 * **Still not pinned here: the `Compact` op sorts.** No construction below calls `Rga.compact` or
 * `Fugue.compact`, so neither `Compact.positions` nor the Compact-vs-Compact tiebreak is reached;
 * `rgaCompactPositionsAreDeliveryOrderIndependent` and its `Fugue` sibling cover them same-target.
 *
 * **What the delta vectors deliberately do *not* claim.** `DotMapSerializer` and
 * `DotSetSerializer` are unreachable on them and always will be: a delta's store is one entry or
 * none, and that entry carries one dot, so there is exactly one order to choose from. [orSet] and
 * [orMap] are what pin those. The `vv` sort is subtler and worth stating plainly — deleting it
 * leaves all three delta vectors **green on `jvmTest`**. On [ORSET_REMOVE_DELTA] that is
 * structural, because its vector is empty; on the other two it is an accident of one target, whose
 * `HashMap` happens to iterate their two entries in canonical order anyway. Neither is evidence
 * the sort is unnecessary, and neither earns a place in the `vv` row above.
 *
 * **#2086 moved three vectors and reassigned two rows above; both are deliberate.** An `ORMapEntry`
 * is no longer a `DotSet` of tags beside one value — it maps each tag to the write made under it —
 * so [ORMAP], [ORMAP_PUT_DELTA] and [JSON_CRDT] all encode differently, `ORMapEntrySerializer` is a
 * new canonicalisation site with its own row, and `ORMap` stops reaching `DotSetSerializer`
 * entirely. That sort would have been left with no vector at all, which is the silent-coverage-loss
 * failure this file exists to prevent, so [orSet] now adds one element concurrently from two
 * replicas to take the pin over. [ORMAP_PUT_DELTA] likewise leaves the `cloud`-sort row: a put
 * delta's context now names only the sender's own tags, so it can never hold two replicas' dots and
 * has nothing to order. Its cloud is still non-empty — the vacuity guard checks that — but the sort
 * is pinned by the three vectors that remain on the row.
 *
 * **Regenerate only on a deliberate encoding change, and expect every vector to move together.**
 * A single vector changing on one target and not another is the exact defect this file exists to
 * catch — investigate, do not re-record. Recording a per-target vector would defeat the file's
 * entire purpose.
 */
@OptIn(ExperimentalSerializationApi::class)
class CanonicalGoldenVectorTest {

    private val cbor = Cbor {}

    // Deliberately not in sorted order relative to how the constructions below insert them.
    private val zulu = ReplicaId("zulu")
    private val mike = ReplicaId("mike")
    private val delta = ReplicaId("delta")
    private val alpha = ReplicaId("alpha")

    /** A fifth replica, so the delta constructions can reach a four- and five-dot cloud. */
    private val bravo = ReplicaId("bravo")

    /** The element/key five replicas all hold — what the add- and put-delta vectors touch. */
    private val shared = "shared"

    /** A second five-replica element, minted *after* [shared] so every dot on it sits at seq ≥ 2. */
    private val retired = "retired"

    @Test
    fun everyVectorMatchesOnEveryTarget() {
        assertAll(
            { assertEquals(GSET, hex(GSet.serializer(String.serializer()), gSet()), "GSet") },
            {
                assertEquals(
                    TWO_PHASE_SET,
                    hex(TwoPhaseSet.serializer(String.serializer()), twoPhaseSet()),
                    "TwoPhaseSet",
                )
            },
            { assertEquals(GCOUNTER, hex(GCounter.serializer(), gCounter()), "GCounter") },
            { assertEquals(PNCOUNTER, hex(PNCounter.serializer(), pnCounter()), "PNCounter") },
            {
                assertEquals(
                    LWWMAP,
                    hex(LWWMap.serializer(String.serializer(), Int.serializer()), lwwMap()),
                    "LWWMap",
                )
            },
            { assertEquals(ORSET, hex(ORSet.serializer(String.serializer()), orSet()), "ORSet") },
            {
                assertEquals(
                    ORMAP,
                    hex(ORMap.serializer(String.serializer(), GCounter.serializer()), orMap()),
                    "ORMap",
                )
            },
            { assertEquals(BOUNDED_COUNTER, hex(BoundedCounter.serializer(), boundedCounter()), "BoundedCounter") },
            {
                assertEquals(
                    EPHEMERAL_MAP,
                    hex(EphemeralMap.serializer(String.serializer()), ephemeralMap()),
                    "EphemeralMap",
                )
            },
            {
                assertEquals(
                    MOVABLE_TREE,
                    hex(MovableTree.serializer(String.serializer()), movableTree()),
                    "MovableTree",
                )
            },
            {
                assertEquals(
                    MV_REGISTER,
                    hex(MVRegister.serializer(String.serializer()), mvRegister()),
                    "MVRegister",
                )
            },
            { assertEquals(VERSION_VECTOR, hex(VersionVector.serializer(), versionVector()), "VersionVector") },
            { assertEquals(DOT_CONTEXT, hex(DotContext.serializer(), dotContext()), "DotContext") },
            {
                assertEquals(
                    ORSET_ADD_DELTA,
                    hex(ORSet.serializer(String.serializer()), orSetAddDelta()),
                    "ORSet addDelta",
                )
            },
            {
                assertEquals(
                    ORSET_REMOVE_DELTA,
                    hex(ORSet.serializer(String.serializer()), orSetRemoveDelta()),
                    "ORSet removeDelta",
                )
            },
            {
                assertEquals(
                    ORMAP_PUT_DELTA,
                    hex(ORMap.serializer(String.serializer(), GCounter.serializer()), orMapPutDelta()),
                    "ORMap putDelta",
                )
            },
            { assertEquals(RGA, hex(Rga.wireSerializer(String.serializer()), rga()), "Rga") },
            { assertEquals(FUGUE, hex(Fugue.wireSerializer(String.serializer()), fugue()), "Fugue") },
            { assertEquals(JSON_CRDT, hex(JsonCrdt.serializer(), jsonCrdt()), "JsonCrdt") },
        )
    }

    /**
     * A guard against the whole file going vacuous — specifically, against a **re-record**
     * laundering a collapse.
     *
     * Editing a construction on its own does not slip past anything: the bytes change, and
     * [everyVectorMatchesOnEveryTarget] goes red first. The dangerous sequence is the one the
     * class KDoc warns against — someone collapses a construction, sees the byte assertion fail,
     * reads it as "the encoding moved", and re-records the constant. That re-record makes the
     * vectors green again around a construction that now pins nothing, and no other assertion in
     * this file would notice. **This test is what notices**: it names the shape each construction
     * must keep, so a collapse has to be argued with rather than re-recorded away.
     *
     * Each assertion therefore pins *cardinality* (or a per-replica read standing in for it) —
     * never a total, which a single-entry collapse can reproduce.
     */
    @Test
    fun everyConstructionIsMultiEntry() {
        assertAll(
            { assertEquals(4, gSet().elements.size, "GSet elements") },
            { assertEquals(3, twoPhaseSet().added.size, "TwoPhaseSet added") },
            { assertEquals(2, twoPhaseSet().removed.size, "TwoPhaseSet removed") },
            { assertEquals(4, gCounter().replicas().size, "GCounter slots") },
            { assertEquals(15L, pnCounter().totalIncrement, "PNCounter increments") },
            { assertEquals(0L, pnCounter().incrementShortfall(zulu, 5L), "PNCounter increment slot zulu") },
            { assertEquals(0L, pnCounter().incrementShortfall(alpha, 7L), "PNCounter increment slot alpha") },
            { assertEquals(0L, pnCounter().incrementShortfall(mike, 3L), "PNCounter increment slot mike") },
            { assertEquals(7L, pnCounter().totalDecrement, "PNCounter decrements") },
            { assertEquals(0L, pnCounter().decrementShortfall(mike, 2L), "PNCounter decrement slot mike") },
            { assertEquals(0L, pnCounter().decrementShortfall(zulu, 1L), "PNCounter decrement slot zulu") },
            { assertEquals(0L, pnCounter().decrementShortfall(alpha, 4L), "PNCounter decrement slot alpha") },
            { assertEquals(3, lwwMap().entries.size, "LWWMap live entries") },
            { assertEquals(4, orSet().elements.size, "ORSet elements") },
            { assertEquals(2, orMap().keys.size, "ORMap keys") },
            { assertEquals(4, orMap()["zulu"]?.replicas()?.size, "ORMap nested GCounter slots") },
            { assertEquals(4, orMap()["alpha"]?.replicas()?.size, "ORMap nested GCounter slots") },
            { assertEquals(83L, boundedCounter().quota(zulu), "BoundedCounter zulu row") },
            { assertEquals(49L, boundedCounter().quota(mike), "BoundedCounter mike row") },
            { assertEquals(28L, boundedCounter().quota(alpha), "BoundedCounter alpha row") },
            { assertEquals(2L, boundedCounter().quota(delta), "BoundedCounter transfers reached delta") },
            { assertEquals(4, ephemeralMap().entries.size, "EphemeralMap slots") },
            { assertEquals(2, movableTree().compactedDotCount(), "MovableTree compactedDots") },
            { assertEquals(4, mvRegister().values.size, "MVRegister concurrent values") },
            { assertEquals(4, versionVector().entries.size, "VersionVector authors") },
            { assertEquals(4, dotContext().cloud.size, "DotContext cloud") },
            { assertEquals(2, dotContext().vv.size, "DotContext vv") },
            { assertEquals(5, rga().ops.size, "Rga ops") },
            { assertEquals(4, rga().insertAuthors().size, "Rga insert authors") },
            { assertEquals(1, rga().tombstoneCount(), "Rga tombstone") },
            { assertEquals(5, fugue().ops.size, "Fugue ops") },
            { assertEquals(4, fugue().insertAuthors().size, "Fugue insert authors") },
            { assertEquals(1, fugue().tombstoneCount(), "Fugue tombstone") },
            { assertEquals(2, jsonCrdt().keys.size, "JsonCrdt keys") },
            { assertEquals(4, jsonCrdt().arrayAt("list").ops.size, "JsonCrdt nested Rga ops") },
            { assertEquals(2, jsonCrdt().arrayAt("list").insertAuthors().size, "JsonCrdt nested Rga authors") },
            { assertEquals(4, orSetAddDelta().wireContext().cloud.size, "ORSet addDelta cloud") },
            { assertEquals(2, orSetAddDelta().wireContext().vv.size, "ORSet addDelta vv") },
            { assertEquals(setOf(shared), orSetAddDelta().elements, "ORSet addDelta store") },
            { assertEquals(5, orSetRemoveDelta().wireContext().cloud.size, "ORSet removeDelta cloud") },
            { assertEquals(0, orSetRemoveDelta().wireContext().vv.size, "ORSet removeDelta vv") },
            { assertEquals(emptySet(), orSetRemoveDelta().elements, "ORSet removeDelta store") },
            // A put delta's context names the sender's own prior tags on the key plus the fresh one,
            // and nothing else (#2086) — so it is one replica's dots, and `zulu` has two private
            // puts behind it, which puts both above the first seq and therefore in the cloud.
            { assertEquals(2, orMapPutDelta().wireContext().cloud.size, "ORMap putDelta cloud") },
            { assertEquals(0, orMapPutDelta().wireContext().vv.size, "ORMap putDelta vv") },
            { assertEquals(4, orMapPutDelta()[shared]?.replicas()?.size, "ORMap putDelta value slots") },
        )
    }

    /**
     * The [DotContext] this frame carries, **decoded back out of its own encoded bytes** rather
     * than read off the value — [ORSet] keeps its `Causal` private, and re-deriving the context in
     * the test would guard the test's idea of the delta rather than the delta.
     *
     * [CausalFrame] is a structural stand-in: CBOR carries field names, not class names, so a
     * one-field `causal` class decodes any `Causal`-backed CRDT's frame.
     */
    private fun ORSet<String>.wireContext(): DotContext =
        cbor.decodeFromByteArray(
            CausalFrame.serializer(DotMap.serializer(String.serializer(), DotSet.serializer())),
            cbor.encodeToByteArray(ORSet.serializer(String.serializer()), this),
        ).causal.context

    /** The [ORMap] mirror of [ORSet.wireContext]. */
    private fun ORMap<String, GCounter>.wireContext(): DotContext =
        cbor.decodeFromByteArray(
            CausalFrame.serializer(
                DotMap.serializer(String.serializer(), ORMapEntry.serializer(GCounter.serializer())),
            ),
            cbor.encodeToByteArray(ORMap.serializer(String.serializer(), GCounter.serializer()), this),
        ).causal.context

    /** The distinct authors of this log's [RgaOp.Insert]s — a single-replica collapse drops to 1. */
    private fun Rga<*>.insertAuthors(): Set<ReplicaId> =
        ops.mapNotNull { op -> (op as? RgaOp.Insert)?.id?.replicaId }.toSet()

    /** How many [RgaOp.Remove]s this log carries; `0` if the tombstone is ever dropped. */
    private fun Rga<*>.tombstoneCount(): Int = ops.count { it is RgaOp.Remove<*> }

    /** The [Fugue] mirror of [insertAuthors]. */
    private fun Fugue<*>.insertAuthors(): Set<ReplicaId> =
        ops.mapNotNull { op -> (op as? FugueOp.Insert)?.id?.replicaId }.toSet()

    /** The [Fugue] mirror of [tombstoneCount]. */
    private fun Fugue<*>.tombstoneCount(): Int = ops.count { it is FugueOp.Remove }

    /** The [Rga] behind array-valued key [key] — the nested op-log the [JSON_CRDT] vector reaches. */
    private fun JsonCrdt.arrayAt(key: String): Rga<JsonNode> {
        val node = this[key] ?: error("the $key key must be present")
        return (node as? JsonNode.Array)?.rga ?: error("the $key key must hold an Array")
    }

    /**
     * How far the increment half is *short* of carrying `replica → by`: zero when the slot is
     * already there at (at least) `by`, and the missing amount when it is absent or smaller.
     *
     * This is the only per-replica read `PNCounter`'s public API allows — it exposes the two
     * halves' totals and nothing else — and widening that API so a test can look inside is not a
     * trade worth making. `piece` is an idempotent max-join, so absorbing a slot the state already
     * dominates cannot move the total; a total that *does* move is measuring exactly what was
     * missing.
     *
     * The three shortfalls and the [PNCounter.totalIncrement] assertion beside them are a pair,
     * and **neither half is redundant** — they fail on disjoint mutations:
     *
     * | mutation | total | shortfalls |
     * |---|---|---|
     * | collapse both halves to `zulu` | green | **red** (`alpha`, `mike`) |
     * | add a fourth slot `delta → 100` | **red** | green |
     *
     * The shortfalls say the state dominates all three named slots; the total says it weighs no
     * more than those three. Together they admit exactly one state. Same construction for
     * [decrementShortfall] on the decrement half.
     */
    private fun PNCounter.incrementShortfall(replica: ReplicaId, by: Long): Long =
        piece(PNCounter.ZERO.increment(replica, by)).totalIncrement - totalIncrement

    /** The decrement-half mirror of [incrementShortfall]. */
    private fun PNCounter.decrementShortfall(replica: ReplicaId, by: Long): Long =
        piece(PNCounter.ZERO.decrement(replica, by)).totalDecrement - totalDecrement

    private fun <S> hex(ser: KSerializer<S>, value: S): String =
        cbor.encodeToByteArray(ser, value).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(radix = 16).padStart(2, '0')
        }

    // ── Constructions ─────────────────────────────────────────────────────────
    //
    // Each merges slices contributed by different replicas, in an order that is NOT the
    // canonical sort order — so the encoding is only stable because the canonical serializers
    // reorder it.

    /**
     * `elements` reaches insertion order `zulu, mike, alpha, delta`; canonical order is
     * `alpha, delta, mike, zulu`.
     */
    private fun gSet(): GSet<String> =
        GSet.of("zulu")
            .piece(GSet.of("mike", "alpha"))
            .piece(GSet.of("delta"))

    /**
     * Both component sets are multi-entry and out of order: `added` reaches
     * `zulu, mike, alpha` and `removed` reaches `zulu, delta` — so **both** of the type's
     * `CanonicalSetSerializer` annotations are load-bearing, not just the first.
     */
    private fun twoPhaseSet(): TwoPhaseSet<String> =
        TwoPhaseSet.empty<String>()
            .piece(TwoPhaseSet.empty<String>().add("zulu"))
            .piece(TwoPhaseSet.empty<String>().add("mike"))
            .piece(TwoPhaseSet.empty<String>().add("alpha"))
            .piece(TwoPhaseSet.empty<String>().remove("zulu"))
            .piece(TwoPhaseSet.empty<String>().remove("delta"))

    /**
     * Four replicas each contribute one slot, absorbed out of sorted order, so `counts` is a
     * four-entry `HashMap` — hash-bucket order on the JVM, insertion order on Kotlin/Native.
     */
    private fun gCounter(): GCounter =
        GCounter.ZERO
            .piece(GCounter.ZERO.inc(zulu, 3L))
            .piece(GCounter.ZERO.inc(mike, 1L))
            .piece(GCounter.ZERO.inc(alpha, 4L))
            .piece(GCounter.ZERO.inc(delta, 2L))

    /** Both `GCounter` halves are three-entry and absorbed in different, non-sorted orders. */
    private fun pnCounter(): PNCounter =
        PNCounter.ZERO
            .piece(PNCounter.ZERO.increment(zulu, 5L))
            .piece(PNCounter.ZERO.decrement(mike, 2L))
            .piece(PNCounter.ZERO.increment(alpha, 7L))
            .piece(PNCounter.ZERO.decrement(zulu, 1L))
            .piece(PNCounter.ZERO.increment(mike, 3L))
            .piece(PNCounter.ZERO.decrement(alpha, 4L))

    /**
     * Four keys written by three replicas — including one tombstone, so a `null`-valued
     * register is on the wire too. `cells` reaches `zulu, mike, alpha, delta`.
     */
    private fun lwwMap(): LWWMap<String, Int> =
        LWWMap.empty<String, Int>()
            .piece(LWWMap.empty<String, Int>().set(zulu, 3L, "zulu", 3))
            .piece(LWWMap.empty<String, Int>().set(mike, 1L, "mike", 1))
            .piece(LWWMap.empty<String, Int>().set(alpha, 2L, "alpha", 2))
            .piece(LWWMap.empty<String, Int>().remove(delta, 4L, "delta"))

    /**
     * Two replicas contribute disjoint element slices; one element is added and then removed,
     * so the `DotContext` carries a dot the `DotMap` does not.
     *
     * Canonicality here comes from the hand-written dot-family serializers rather than a
     * `Canonical*Serializer` annotation — but only two of the three are load-bearing: the
     * `DotMapSerializer` sort over the four element keys, and the `DotContextSerializer` sort
     * over `vv`, which the merge reaches in insertion order `zulu, alpha`. **`DotSetSerializer`
     * is not exercised by this vector** — every element here is added by exactly one replica
     * exactly once, so every `DotSet` on the wire is a singleton and has only one order. See
     * [orMap] for the vector that does pin it.
     */
    /**
     * **This is the vector that pins `DotSetSerializer`** — it took that job over from [orMap] when
     * #2086 replaced `ORMapEntry`'s `DotSet` with a dot-keyed map of contributions, so `ORMap` stops
     * reaching that serializer at all. Both replicas add `"mike"` concurrently, so its dot set is a
     * **two-dot** one carrying `zulu`'s dot and `alpha`'s, and the merge reaches it in insertion
     * order `zulu` first against a canonical order of `alpha, zulu`. Every other element here is a
     * singleton, so drop the shared add and that sort silently loses its only cross-target pin while
     * every assertion in this file stays green after a re-record.
     */
    private fun orSet(): ORSet<String> {
        val fromZulu = ORSet.empty<String>()
            .piece { it.add(zulu, "zulu") }
            .piece { it.add(zulu, "mike") }
            .piece { it.add(zulu, "gone") }
        val fromAlpha = ORSet.empty<String>()
            .piece { it.add(alpha, "alpha") }
            .piece { it.add(alpha, "delta") }
            .piece { it.add(alpha, "mike") }
        return fromZulu.piece { it.remove("gone") }.piece(fromAlpha)
    }

    /**
     * The nested-map case. Both replicas hold **both** keys but contribute a *different* pair
     * of `GCounter` slots for each, so the merge runs `GCounter.piece` — and therefore the
     * `HashMap` build inside `mergeMax` — in opposite insertion orders on the two sides. A
     * value both sides held identically would merge as `value.piece(value)` and be vacuous.
     *
     * This is also the vector that pins `ORMapEntrySerializer`. Because both replicas write both
     * keys, each key's entry holds **two contributions** — key `"alpha"` carries `zulu`'s dot and
     * `alpha`'s, each with its own `GCounter` — and the merge reaches them in insertion order `zulu`
     * first against a canonical order of `alpha, zulu`.
     *
     * **So "both replicas write both keys" is a requirement, not incidental phrasing** (#2038).
     * Give each key a single writer and every entry on the wire becomes a single contribution — one
     * pair, one order — and `ORMapEntrySerializer` silently loses its only cross-target pin while
     * every assertion in this file stays green after a re-record.
     *
     * Before #2086 this vector pinned `DotSetSerializer` for the same reason, an entry's tags being
     * a `DotSet` back then. It no longer reaches that serializer at all; [orSet] took the job.
     */
    private fun orMap(): ORMap<String, GCounter> {
        val viewZulu = ORMap.empty<String, GCounter>()
            .piece { it.put(zulu, "zulu", GCounter.of(zulu to 1L, mike to 3L)) }
            .piece { it.put(zulu, "alpha", GCounter.of(zulu to 5L, delta to 7L)) }
        val viewAlpha = ORMap.empty<String, GCounter>()
            .piece { it.put(alpha, "alpha", GCounter.of(alpha to 6L, mike to 8L)) }
            .piece { it.put(alpha, "zulu", GCounter.of(alpha to 2L, delta to 4L)) }
        return viewZulu.piece(viewAlpha)
    }

    /**
     * `transfers` is a map of maps: three donor rows absorbed out of order, and `zulu`'s own
     * row holds two receivers absorbed out of order — so both the outer
     * `CanonicalMapSerializer` and the nested `GCounter` one are load-bearing.
     *
     * `transfers` is private, so the guard reads the matrix back through [quota], which nets
     * `initial + received − given − spent` per replica. Four reads cover it: `zulu`'s and
     * `mike`'s pin their own rows, `alpha`'s pins the second receiver in `zulu`'s nested row,
     * and `delta`'s — which holds nothing else — pins that `alpha`'s row still points at `delta`.
     * That last one is not shadowed by the other three: the four quotas sum to a fixed
     * `totalBudget`, but only while every quota-holding replica is one of the four, so
     * re-pointing `alpha`'s transfer at a fifth replica moves `delta`'s read alone.
     */
    private fun boundedCounter(): BoundedCounter {
        val seeded = BoundedCounter.init(mapOf(zulu to 100L, mike to 50L, alpha to 25L))
        fun move(from: ReplicaId, to: ReplicaId, amount: Long): BoundedCounter =
            requireNotNull(seeded.transfer(from, to, amount)) { "$from has quota for $amount" }.delta
        fun spend(who: ReplicaId, amount: Long): BoundedCounter =
            requireNotNull(seeded.trySpend(who, amount)) { "$who has quota for $amount" }.delta
        return seeded
            .piece(move(zulu, mike, 10L))
            .piece(move(mike, zulu, 7L))
            .piece(move(alpha, delta, 2L))
            .piece(move(zulu, alpha, 5L))
            .piece(spend(mike, 4L))
            .piece(spend(zulu, 9L))
    }

    /** Four replica slots — three present, one departed — published out of sorted order. */
    private fun ephemeralMap(): EphemeralMap<String> =
        EphemeralMap.empty<String>()
            .put(zulu, "z", 3L)
            .put(mike, "m", 1L)
            .put(alpha, "a", 2L)
            .leave(delta, 4L)

    /**
     * Two replicas that each compacted their own move-log, merged **`zulu` first** so
     * `compactedDots` reaches insertion order `zulu, alpha` against a canonical order of
     * `alpha, zulu`. `seqByReplica` is likewise a two-entry `HashMap`, so both of the type's
     * canonical serializers are load-bearing.
     */
    private fun movableTree(): MovableTree<String> =
        compactedTreeFor(zulu, "z").piece(compactedTreeFor(alpha, "a"))

    /**
     * A tree owned by [replica] with a compaction applied, so `compactedDots` holds exactly the
     * dot of the superseded `ts=3` move. Mirrors `CanonicalSerializationTest.compactedTreeFor`.
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
     * Four replicas write concurrently and are absorbed out of sorted order, so the backing
     * `DotFun` is a four-dot map reached in insertion order `zulu, mike, alpha, delta` against a
     * canonical order of `alpha, delta, mike, zulu`.
     *
     * **This is the only vector that reaches [DotFunSerializer]** — the file previously had none,
     * so that sort had no cross-target pin and, more to the point here, no byte-parity anchor for
     * #1964's collapse of it onto the shared canonical sort.
     */
    private fun mvRegister(): MVRegister<String> =
        MVRegister.empty<String>()
            .piece(MVRegister.empty<String>().set(zulu, "z"))
            .piece(MVRegister.empty<String>().set(mike, "m"))
            .piece(MVRegister.empty<String>().set(alpha, "a"))
            .piece(MVRegister.empty<String>().set(delta, "d"))

    /**
     * Four authors merged in a deliberately non-sorted order, so `entries` reaches insertion order
     * `zulu, mike, alpha, delta` against a canonical order of `alpha, delta, mike, zulu`.
     *
     * `VersionVector.combine` builds its result from `entries.keys + other.entries.keys`, a
     * `LinkedHashSet` in merge order, so this vector is stable only because
     * [VersionVector.entries] is encoded through [CanonicalMapSerializer] (#2010). It is on the
     * wire as `QuiltMessage.Delivered.vector`, and #1986 keys anti-entropy on a diff of it.
     */
    private fun versionVector(): VersionVector =
        VersionVector.of(mapOf(zulu to 3L))
            .ceilWith(VersionVector.of(mapOf(mike to 1L)))
            .ceilWith(VersionVector.of(mapOf(alpha to 4L)))
            .ceilWith(VersionVector.of(mapOf(delta to 2L)))

    /**
     * The only construction here with a **non-empty cloud** — the gap `DotContextSerializer`'s
     * second sort exists for, and which [orSet], [orMap] and [mvRegister] all leave empty (#2038).
     *
     * A dot stays in the cloud exactly while a gap sits below it, so every cloud dot here is minted
     * above one: `zulu`'s `seq=3` over a vector at `1`, and `mike`, `alpha`, `delta` each at a seq
     * their vector never reaches. Four of them, contributed by two replicas and merged `zulu` first,
     * so `cloud` arrives in insertion order `zulu:3, mike:7, alpha:4, delta:2` against a canonical
     * order of `alpha:4, delta:2, mike:7, zulu:3` — no two adjacent.
     *
     * `vv` is load-bearing too: a two-entry map that `DotContext`'s own `compact` rebuilds as a
     * [HashMap], reached in insertion order `zulu, alpha` against a canonical order `alpha, zulu`.
     */
    private fun dotContext(): DotContext =
        DotContext.of(Dot(zulu, 1L), Dot(zulu, 3L), Dot(mike, 7L))
            .piece(DotContext.of(Dot(alpha, 1L), Dot(alpha, 4L), Dot(delta, 2L)))

    /**
     * Four replicas each insert one element after `HEAD`, and `zulu` also tombstones its own —
     * merged `zulu` first, so `ops` reaches insertion order
     * `I(zulu), R(zulu), I(mike), I(alpha), I(delta)` against a canonical order of
     * `I(alpha), I(delta), I(mike), I(zulu), R(zulu)`.
     *
     * **This is [Rga]'s first cross-target byte pin.** `rgaSerializationIsDeliveryOrderIndependent`
     * and its `Compact.positions` siblings compare two values in *one process on one target*, so a
     * platform-dependent encoding is self-consistent on each side while the bytes differ between
     * them — the exact defect that would make #1955's root digest report permanent false divergence
     * between a JVM peer and a Native one (#2038).
     *
     * The tombstone is minted **before** the merge on purpose. `RgaSerializer.opComparator` keys on
     * the op type first, so every `Remove` sorts after every `Insert`; a `Remove` appended to the
     * merged log would already be last and its position would pin nothing. Minted first, it arrives
     * second and has to move to fifth.
     */
    private fun rga(): Rga<String> {
        fun slice(replica: ReplicaId, value: String): Rga<String> =
            Rga.empty<String>().insertAfter(replica, RgaId.HEAD, value).first
        val fromZulu = slice(zulu, "z").let { inserted ->
            inserted.removeAt(0)?.first ?: error("the freshly-inserted element must be removable")
        }
        return fromZulu.piece(slice(mike, "m")).piece(slice(alpha, "a")).piece(slice(delta, "d"))
    }

    /**
     * [Fugue]'s mirror of [rga], and its first cross-target byte pin for the same reason.
     *
     * The comparator differs — [fugueOpComparator] keys on the **id** first and the op type only as
     * a tiebreak — so `zulu`'s `Remove` stays adjacent to its `Insert` and the canonical order is
     * `I(alpha), I(delta), I(mike), I(zulu), R(zulu)` reached from insertion order
     * `I(zulu), R(zulu), I(mike), I(alpha), I(delta)`. Both terms of that comparator are load-bearing
     * here: the four ids order the log, and the type tiebreak orders `zulu`'s two ops, which share one.
     */
    private fun fugue(): Fugue<String> {
        fun slice(replica: ReplicaId, value: String): Fugue<String> =
            Fugue.empty<String>().insertAt(replica, 0, value).first
        val fromZulu = slice(zulu, "z").let { inserted ->
            inserted.removeAt(0)?.first ?: error("the freshly-inserted element must be removable")
        }
        return fromZulu.piece(slice(mike, "m")).piece(slice(alpha, "a")).piece(slice(delta, "d"))
    }

    /**
     * The recursive case: a two-key document whose `"list"` key holds a [JsonNode.Array], so this
     * vector reaches [RgaSerializer]'s op sort *through* [JsonCrdt]'s `ORMap` — the composition that
     * had no cross-target pin at any level (#2038).
     *
     * Both replicas write both keys, following [orMap]'s rule, so the outer `ORMap`'s per-key
     * `DotSet`s are two-dot and its `DotMap` is multi-entry. The nested `Rga` merges `zulu`'s
     * two-element slice against `alpha`'s two-element slice **`zulu` first**, against a canonical
     * order that puts `alpha`'s ops first.
     */
    private fun jsonCrdt(): JsonCrdt {
        fun view(replica: ReplicaId, tag: String): JsonCrdt {
            val rga = listOf("${tag}1", "${tag}2").fold(Rga.empty<JsonNode>()) { acc, value ->
                val after = acc.sequence.lastOrNull() ?: RgaId.HEAD
                acc.insertAfter(replica, after, JsonNode.Leaf(MVRegister.empty<JsonValue>()
                    .set(replica, JsonValue.Str(value)))).first
            }
            return JsonCrdt.empty(replica)
                .set("list", JsonNode.Array(rga))
                .set("name", JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, JsonValue.Str(tag))))
        }
        return view(zulu, "z").piece(view(alpha, "a"))
    }

    // ── Delta-shaped frames (#2044) ───────────────────────────────────────────
    //
    // What a delta mutator puts on the wire is a *different shape* from every construction
    // above: a one-entry store, and a `DotContext` holding only the dots the operation asserts
    // or retires. Those dots are the sender's *current* seqs, so they arrive in a context that
    // starts empty — and a dot with a gap below it stays in the `cloud`. That is how `cloud`
    // becomes non-empty on an `ORSet`/`ORMap` frame at all, which it never was here before.

    /**
     * Five replicas that each hold both [shared] and [retired], each minting its two dots at a
     * *different* seq — `zulu` at 1–2, `delta` at 1–2, `alpha` at 2–3, `mike` at 3–4, `bravo` at
     * 4–5 — because each has a different number of private adds behind it.
     *
     * The spread is the point. A delta's context witnesses exactly the dots the operation touches,
     * starting from empty, so a dot lands in `vv` only when it is seq 1 (or contiguous with what is
     * already there) and in `cloud` otherwise. Give every replica the same seq and the delta
     * contexts below collapse to all-`vv` or all-`cloud` with nothing to order.
     *
     * The merge runs `zulu` first and then out of sorted order, so each element's `DotSet` — and
     * therefore the cloud the delta builds from it — arrives in insertion order `zulu, mike, alpha,
     * delta, bravo` against a canonical order of `alpha, bravo, delta, mike, zulu`.
     */
    private fun sharedByFive(): ORSet<String> {
        fun view(replica: ReplicaId, priorAdds: Int): ORSet<String> =
            (1..priorAdds)
                .fold(ORSet.empty<String>()) { set, n ->
                    set.piece { it.add(replica, "${replica.value}$n") }
                }
                .piece { it.add(replica, shared) }
                .piece { it.add(replica, retired) }
        return view(zulu, priorAdds = 0)
            .piece(view(mike, priorAdds = 2))
            .piece(view(alpha, priorAdds = 1))
            .piece(view(delta, priorAdds = 0))
            .piece(view(bravo, priorAdds = 3))
    }

    /**
     * `zulu` re-adds [shared] on the converged [sharedByFive]. The delta's context is the four
     * dots the re-add supersedes plus the one it mints, and nothing else.
     *
     * Reached: `vv` = `{zulu:1, delta:1}` — the two replicas whose [shared] dot *is* their first —
     * and `cloud` = insertion order `mike:3, alpha:2, bravo:4, zulu:3` against a canonical order of
     * `alpha:2, bravo:4, mike:3, zulu:3`. **Four cloud dots, no two adjacent in canonical order**,
     * so neither `sorted()` nor its reverse nor insertion order coincide — the shape a two-dot
     * cloud cannot pin, because two elements have only one wrong order and `sorted()` and
     * `reversed()` agree on it.
     *
     * `zulu:3` is the *minted* dot and it lands in the cloud, above `zulu`'s `vv` entry of 1: a
     * delta announces the sender's current seq, not a history, so its own dot is normally gapped.
     *
     * The store is one element carrying one dot — that is what a delta *is* — so
     * [DotMapSerializer] and [DotSetSerializer] are not exercised here. [orSet] and [orMap] pin
     * those; this vector exists for the context.
     */
    private fun orSetAddDelta(): ORSet<String> = sharedByFive().add(zulu, shared).delta

    /**
     * The other delta shape: an **empty store** with a non-empty context — "these dots are
     * retired, I assert nothing else". No construction above puts an empty `DotMap` on the wire.
     *
     * [retired] is minted after [shared] on every replica, so every one of its five dots sits at
     * seq ≥ 2 and none of them can compact. That gives the third context shape: **`vv` empty,
     * `cloud` holding all five** — reached in insertion order `zulu:2, mike:4, alpha:3, delta:2,
     * bravo:5` against a canonical order of `alpha:3, bravo:5, delta:2, mike:4, zulu:2`.
     * [dotContext] pins a cloud beside a populated vector; this pins one standing alone.
     */
    private fun orSetRemoveDelta(): ORSet<String> = sharedByFive().remove(retired).delta

    /**
     * The [ORMap] mirror, with a value on the wire. Five replicas put [shared] at five different
     * seqs, then `zulu` puts over it: `vv` = `{zulu:2, delta:1}` and `cloud` = insertion order
     * `mike:3, alpha:2, bravo:4` against a canonical order of `alpha:2, bravo:4, mike:3`.
     *
     * The supplied value is a four-slot `GCounter` built out of sorted order, so this vector also
     * pins `GCounter.counts`' canonical sort *inside a delta frame*. It pins one more thing for
     * free: a put delta ships **the caller's value, not the locally merged one**. Ship the merged
     * value instead and these bytes change — the frame would carry every replica's slice rather
     * than the four written here.
     */
    private fun orMapPutDelta(): ORMap<String, GCounter> {
        fun view(replica: ReplicaId, priorPuts: Int): ORMap<String, GCounter> =
            (1..priorPuts)
                .fold(ORMap.empty<String, GCounter>()) { map, n ->
                    map.piece { it.put(replica, "${replica.value}$n", GCounter.of(replica to n.toLong())) }
                }
                .piece { it.put(replica, shared, GCounter.of(replica to 1L)) }
        val converged = view(zulu, priorPuts = 2)
            .piece(view(mike, priorPuts = 2))
            .piece(view(alpha, priorPuts = 1))
            .piece(view(delta, priorPuts = 0))
            .piece(view(bravo, priorPuts = 3))
        return converged
            .put(zulu, shared, GCounter.of(mike to 8L, alpha to 6L, delta to 4L, zulu to 2L))
            .delta
    }

    /**
     * `compactedDots` is private, but every compacted dot is re-emitted through [Quilted.causalDots]
     * alongside the surviving log ops — so the compacted count is the total minus [moveLogSize],
     * the merged log. Each replica records four ops (`ts=1, 2, 3, 4`) and its own compaction drops
     * the superseded `ts=3`, leaving **three** per replica and six in the merge.
     */
    private fun MovableTree<String>.compactedDotCount(): Int = causalDots().size - moveLogSize

    /**
     * CBOR bytes, lower-case hex. Captured once on `jvmTest` and verified unchanged on
     * `macosArm64Test`, `wasmJsTest` and `iosSimulatorArm64Test` — see the class KDoc before
     * touching any of them.
     */
    private companion object {
        const val GSET =
            "bf68656c656d656e74739f65616c7068616564656c7461646d696b65647a756c75ffff"
        const val TWO_PHASE_SET =
            "bf6561646465649f65616c706861646d696b65647a756c75ff6772656d6f7665649f6564656c7461647a756c75ffff"
        const val GCOUNTER =
            "bf66636f756e7473bf65616c706861046564656c746102646d696b6501647a756c7503ffff"
        const val PNCOUNTER =
            "bf63696e63bf66636f756e7473bf65616c70686107646d696b6503647a756c7505ffff63646563bf66636f756e7473bf" +
                "65616c70686104646d696b6502647a756c7501ffffff"
        const val LWWMAP =
            "bf6563656c6c73bf65616c706861bf6974696d657374616d7002666f726967696e65616c7068616576616c756502ff65" +
                "64656c7461bf6974696d657374616d7004666f726967696e6564656c74616576616c7565f6ff646d696b65bf6974696d" +
                "657374616d7001666f726967696e646d696b656576616c756501ff647a756c75bf6974696d657374616d7003666f7269" +
                "67696e647a756c756576616c756503ffffff"
        const val ORSET =
            "bf6663617573616cbf6573746f7265bf65616c7068619fbf677265706c69636165616c7068616373657101ffff6564656c74" +
                "619fbf677265706c69636165616c7068616373657102ffff646d696b659fbf677265706c69636165616c7068616373657103" +
                "ffbf677265706c696361647a756c756373657102ffff647a756c759fbf677265706c696361647a756c756373657101ffffff" +
                "67636f6e74657874bf627676bf65616c70686103647a756c7503ff65636c6f75649fffffffff"
        const val ORMAP =
            "bf6663617573616cbf6573746f7265bf65616c706861bfbf677265706c69636165616c7068616373657101ffbf66636f756e" +
                "7473bf65616c70686106646d696b6508ffffbf677265706c696361647a756c756373657102ffbf66636f756e7473bf656465" +
                "6c746107647a756c7505ffffff647a756c75bfbf677265706c69636165616c7068616373657102ffbf66636f756e7473bf65" +
                "616c706861026564656c746104ffffbf677265706c696361647a756c756373657101ffbf66636f756e7473bf646d696b6503" +
                "647a756c7501ffffffff67636f6e74657874bf627676bf65616c70686102647a756c7502ff65636c6f75649fffffffff"
        const val BOUNDED_COUNTER =
            "bf67696e697469616cbf66636f756e7473bf65616c7068611819646d696b651832647a756c751864ffff697472616e73" +
                "66657273bf65616c706861bf66636f756e7473bf6564656c746102ffff646d696b65bf66636f756e7473bf647a756c75" +
                "07ffff647a756c75bf66636f756e7473bf65616c70686105646d696b650affffff657370656e74bf66636f756e7473bf" +
                "646d696b6504647a756c7509ffffff"
        const val EPHEMERAL_MAP =
            "bf67656e7472696573bf65616c706861bf6576616c7565616165636c6f636b02ff6564656c7461bf6576616c7565f665" +
                "636c6f636b04ff646d696b65bf6576616c7565616d65636c6f636b01ff647a756c75bf6576616c7565617a65636c6f63" +
                "6b03ffffff"
        const val MOVABLE_TREE =
            "bf636c6f679fbf62747301677265706c69636165616c706861646e6f64656a61313a616c7068613a31696e6577506172" +
                "656e74685f5f726f6f745f5f6576616c75656261316373657101ffbf62747301677265706c696361647a756c75646e6f" +
                "6465697a313a7a756c753a31696e6577506172656e74685f5f726f6f745f5f6576616c7565627a316373657101ffbf62" +
                "747302677265706c69636165616c706861646e6f64656a61323a616c7068613a32696e6577506172656e74685f5f726f" +
                "6f745f5f6576616c75656261326373657102ffbf62747302677265706c696361647a756c75646e6f6465697a323a7a75" +
                "6c753a32696e6577506172656e74685f5f726f6f745f5f6576616c7565627a326373657102ffbf62747304677265706c" +
                "69636165616c706861646e6f64656a61313a616c7068613a31696e6577506172656e74685f5f726f6f745f5f6576616c" +
                "7565f66373657104ffbf62747304677265706c696361647a756c75646e6f6465697a313a7a756c753a31696e65775061" +
                "72656e74685f5f726f6f745f5f6576616c7565f66373657104ffff6c73657142795265706c696361bf65616c70686104" +
                "647a756c7504ff6d636f6d706163746564446f74739fbf677265706c69636165616c7068616373657103ffbf67726570" +
                "6c696361647a756c756373657103ffffff"
        const val MV_REGISTER =
            "bf6663617573616cbf6573746f7265bfbf677265706c69636165616c7068616373657101ff6161bf677265706c6963616564" +
                "656c74616373657101ff6164bf677265706c696361646d696b656373657101ff616dbf677265706c696361647a756c756373" +
                "657101ff617aff67636f6e74657874bf627676bf65616c706861016564656c746101646d696b6501647a756c7501ff65636c" +
                "6f75649fffffffff"
        const val VERSION_VECTOR =
            "bf67656e7472696573bf65616c706861046564656c746102646d696b6501647a756c7503ffff"
        const val DOT_CONTEXT =
            "bf627676bf65616c70686101647a756c7501ff65636c6f75649fbf677265706c69636165616c7068616373657104ffbf" +
                "677265706c6963616564656c74616373657102ffbf677265706c696361646d696b656373657107ffbf677265706c6963" +
                "61647a756c756373657103ffffff"
        const val RGA =
            "bf636f70739fbf617400626964bf676c616d706f727401697265706c696361496465616c7068616373657101ff617661" +
                "616161bf676c616d706f72743b7fffffffffffffff697265706c6963614964606373657100ffffbf617400626964bf67" +
                "6c616d706f727401697265706c69636149646564656c74616373657101ff617661646161bf676c616d706f72743b7fff" +
                "ffffffffffff697265706c6963614964606373657100ffffbf617400626964bf676c616d706f727401697265706c6963" +
                "614964646d696b656373657101ff6176616d6161bf676c616d706f72743b7fffffffffffffff697265706c6963614964" +
                "606373657100ffffbf617400626964bf676c616d706f727401697265706c6963614964647a756c756373657101ff6176" +
                "617a6161bf676c616d706f72743b7fffffffffffffff697265706c6963614964606373657100ffffbf617401626964bf" +
                "676c616d706f727401697265706c6963614964647a756c756373657101ffffffff"
        const val FUGUE =
            "bf636f70739fbf617400626964bf676c616d706f727401697265706c696361496465616c7068616373657101ff617661" +
                "616170bf676c616d706f72743b7fffffffffffffff697265706c6963614964606373657100ff6173655269676874ffbf" +
                "617400626964bf676c616d706f727401697265706c69636149646564656c74616373657101ff617661646170bf676c61" +
                "6d706f72743b7fffffffffffffff697265706c6963614964606373657100ff6173655269676874ffbf617400626964bf" +
                "676c616d706f727401697265706c6963614964646d696b656373657101ff6176616d6170bf676c616d706f72743b7fff" +
                "ffffffffffff697265706c6963614964606373657100ff6173655269676874ffbf617400626964bf676c616d706f7274" +
                "01697265706c6963614964647a756c756373657101ff6176617a6170bf676c616d706f72743b7fffffffffffffff6972" +
                "65706c6963614964606373657100ff6173655269676874ffbf617401626964bf676c616d706f727401697265706c6963" +
                "614964647a756c756373657101ffffffff"
        const val JSON_CRDT =
            "bf6663617573616cbf6573746f7265bf646c697374bfbf677265706c69636165616c7068616373657101ffbf6174016161bf" +
                "636f70739fbf617400626964bf676c616d706f727401697265706c696361496465616c7068616373657101ff6176bf617402" +
                "616cbf6663617573616cbf6573746f7265bfbf677265706c69636165616c7068616373657101ff9f63737472bf6576616c75" +
                "65626131ffffff67636f6e74657874bf627676bf65616c70686101ff65636c6f75649fffffffffff6161bf676c616d706f72" +
                "743b7fffffffffffffff697265706c6963614964606373657100ffffbf617400626964bf676c616d706f727402697265706c" +
                "696361496465616c7068616373657102ff6176bf617402616cbf6663617573616cbf6573746f7265bfbf677265706c696361" +
                "65616c7068616373657101ff9f63737472bf6576616c7565626132ffffff67636f6e74657874bf627676bf65616c70686101" +
                "ff65636c6f75649fffffffffff6161bf676c616d706f727401697265706c696361496465616c7068616373657101ffffffff" +
                "ffbf677265706c696361647a756c756373657101ffbf6174016161bf636f70739fbf617400626964bf676c616d706f727401" +
                "697265706c6963614964647a756c756373657101ff6176bf617402616cbf6663617573616cbf6573746f7265bfbf67726570" +
                "6c696361647a756c756373657101ff9f63737472bf6576616c7565627a31ffffff67636f6e74657874bf627676bf647a756c" +
                "7501ff65636c6f75649fffffffffff6161bf676c616d706f72743b7fffffffffffffff697265706c69636149646063736571" +
                "00ffffbf617400626964bf676c616d706f727402697265706c6963614964647a756c756373657102ff6176bf617402616cbf" +
                "6663617573616cbf6573746f7265bfbf677265706c696361647a756c756373657101ff9f63737472bf6576616c7565627a32" +
                "ffffff67636f6e74657874bf627676bf647a756c7501ff65636c6f75649fffffffffff6161bf676c616d706f727401697265" +
                "706c6963614964647a756c756373657101ffffffffffff646e616d65bfbf677265706c69636165616c7068616373657102ff" +
                "bf617402616cbf6663617573616cbf6573746f7265bfbf677265706c69636165616c7068616373657101ff9f63737472bf65" +
                "76616c75656161ffffff67636f6e74657874bf627676bf65616c70686101ff65636c6f75649fffffffffffbf677265706c69" +
                "6361647a756c756373657102ffbf617402616cbf6663617573616cbf6573746f7265bfbf677265706c696361647a756c7563" +
                "73657101ff9f63737472bf6576616c7565617affffff67636f6e74657874bf627676bf647a756c7501ff65636c6f75649fff" +
                "ffffffffffff67636f6e74657874bf627676bf65616c70686102647a756c7502ff65636c6f75649fffffffff"
        const val ORSET_ADD_DELTA =
            "bf6663617573616cbf6573746f7265bf667368617265649fbf677265706c696361647a756c756373657103ffffff6763" +
                "6f6e74657874bf627676bf6564656c746101647a756c7501ff65636c6f75649fbf677265706c69636165616c70686163" +
                "73657102ffbf677265706c69636165627261766f6373657104ffbf677265706c696361646d696b656373657103ffbf67" +
                "7265706c696361647a756c756373657103ffffffffff"
        const val ORSET_REMOVE_DELTA =
            "bf6663617573616cbf6573746f7265bfff67636f6e74657874bf627676bfff65636c6f75649fbf677265706c69636165" +
                "616c7068616373657103ffbf677265706c69636165627261766f6373657105ffbf677265706c6963616564656c746163" +
                "73657102ffbf677265706c696361646d696b656373657104ffbf677265706c696361647a756c756373657102ffffffff" +
                "ff"
        const val ORMAP_PUT_DELTA =
            "bf6663617573616cbf6573746f7265bf66736861726564bfbf677265706c696361647a756c756373657104ffbf66636f756e" +
                "7473bf65616c706861066564656c746104646d696b6508647a756c7502ffffffff67636f6e74657874bf627676bfff65636c" +
                "6f75649fbf677265706c696361647a756c756373657103ffbf677265706c696361647a756c756373657104ffffffffff"
    }
}

/**
 * A structural stand-in for any `Causal`-backed CRDT — one field named `causal`, exactly how
 * [ORSet] and [ORMap] encode. CBOR carries field names and not class names, so decoding either
 * one's frame through this class recovers its [Causal] without widening a production API.
 *
 * Used only by the vacuity guards, to read a delta frame's `vv` and `cloud` cardinality off the
 * **bytes** rather than off a re-derivation of what the mutator was supposed to have built.
 */
@Serializable
private class CausalFrame<S : DotStore<S>>(val causal: Causal<S>)
