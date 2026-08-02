package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
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
 * that would make a Merkle digest report permanent false divergence between a JVM peer and a
 * Native peer. Only a checked-in byte string catches it, and because `commonTest` compiles and
 * runs on JVM, Android, iOS, macOS and wasmJs, **this file *is* the cross-target check.**
 *
 * **Every construction below is deliberately shaped so that removing its type's canonical
 * serializer changes the bytes.** Two shapes were measured and are vacuous, so neither is used
 * here: a single-entry map or set has exactly one iteration order and pins nothing, and a
 * multi-entry value that both replicas hold *identically* degenerates to `value.piece(value)`,
 * seeding the merge from the same source on both sides. Each vector is therefore built by
 * merging replicas that contribute **different** slices, in a deliberately non-sorted order.
 *
 * **Mutation-verified on `jvmTest`: every canonicalisation site #1957 introduced — the nine
 * `Canonical*Serializer` annotations — plus `DotMapSerializer`'s sort is detected by at least one
 * vector, and no vector is vacuous.** Disabling one site at a time — commenting out the
 * annotation, or deleting the sort from a hand-written serializer — and re-running gives:
 *
 * | site removed | vectors that fail |
 * |---|---|
 * | `GSet.elements` | [GSET] |
 * | `TwoPhaseSet.added` | [TWO_PHASE_SET] |
 * | `TwoPhaseSet.removed` | [TWO_PHASE_SET] |
 * | `GCounter.counts` | [GCOUNTER], [PNCOUNTER], [ORMAP], [BOUNDED_COUNTER] |
 * | `LWWMap.cells` | [LWWMAP] |
 * | `BoundedCounter.transfers` | [BOUNDED_COUNTER] |
 * | `EphemeralMap.entries` | [EPHEMERAL_MAP] |
 * | `MovableTree.seqByReplica` | [MOVABLE_TREE] |
 * | `MovableTree.compactedDots` | [MOVABLE_TREE] |
 * | `DotMapSerializer`'s sort | [ORSET], [ORMAP] |
 *
 * **Not pinned here — the older #713 dot-family sorts.** No vector reaches `DotFunSerializer`
 * (only `MVRegister` and `ResettableCounter` use it), `RgaSerializer`'s or `FugueSerializer`'s op
 * sort, or `DotContextSerializer`'s `cloud` sort (`cloud` is empty in both [ORSET] and [ORMAP]).
 * So `MVRegister`, `ResettableCounter`, `Rga`, `Fugue` and `JsonCrdt` have no cross-target byte
 * pin: adding a type to that family does **not** inherit one from this file — add a vector.
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
        )
    }

    /**
     * A guard against the whole file going vacuous: a construction that collapsed to an empty
     * or single-entry collection would still pin *some* byte string, so the vectors would stay
     * green while proving nothing. Every construction must stay multi-entry.
     */
    @Test
    fun everyConstructionIsMultiEntry() {
        assertAll(
            { assertEquals(4, gSet().elements.size, "GSet elements") },
            { assertEquals(3, twoPhaseSet().added.size, "TwoPhaseSet added") },
            { assertEquals(2, twoPhaseSet().removed.size, "TwoPhaseSet removed") },
            { assertEquals(4, gCounter().replicas().size, "GCounter slots") },
            { assertEquals(15L, pnCounter().totalIncrement, "PNCounter increments") },
            { assertEquals(7L, pnCounter().totalDecrement, "PNCounter decrements") },
            { assertEquals(3, lwwMap().entries.size, "LWWMap live entries") },
            { assertEquals(4, orSet().elements.size, "ORSet elements") },
            { assertEquals(2, orMap().keys.size, "ORMap keys") },
            { assertEquals(4, orMap()["zulu"]?.replicas()?.size, "ORMap nested GCounter slots") },
            { assertEquals(4, orMap()["alpha"]?.replicas()?.size, "ORMap nested GCounter slots") },
            { assertEquals(2L, boundedCounter().quota(delta), "BoundedCounter transfers reached delta") },
            { assertEquals(4, ephemeralMap().entries.size, "EphemeralMap slots") },
            { assertEquals(2, movableTree().compactedDotCount(), "MovableTree compactedDots") },
        )
    }

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
     * This is the one vector whose canonicality comes from the hand-written `DotMapSerializer` /
     * `DotSetSerializer` / `DotContextSerializer` rather than a `Canonical*Serializer` annotation
     * — hence its own row in the mutation table.
     */
    private fun orSet(): ORSet<String> {
        val fromZulu = ORSet.empty<String>().add(zulu, "zulu").add(zulu, "mike").add(zulu, "gone")
        val fromAlpha = ORSet.empty<String>().add(alpha, "alpha").add(alpha, "delta")
        return fromZulu.remove("gone").piece(fromAlpha)
    }

    /**
     * The nested-map case. Both replicas hold **both** keys but contribute a *different* pair
     * of `GCounter` slots for each, so the merge runs `GCounter.piece` — and therefore the
     * `HashMap` build inside `mergeMax` — in opposite insertion orders on the two sides. A
     * value both sides held identically would merge as `value.piece(value)` and be vacuous.
     */
    private fun orMap(): ORMap<String, GCounter> {
        val viewZulu = ORMap.empty<String, GCounter>()
            .put(zulu, "zulu", GCounter.of(zulu to 1L, mike to 3L))
            .put(zulu, "alpha", GCounter.of(zulu to 5L, delta to 7L))
        val viewAlpha = ORMap.empty<String, GCounter>()
            .put(alpha, "alpha", GCounter.of(alpha to 6L, mike to 8L))
            .put(alpha, "zulu", GCounter.of(alpha to 2L, delta to 4L))
        return viewZulu.piece(viewAlpha)
    }

    /**
     * `transfers` is a map of maps: three donor rows absorbed out of order, and `zulu`'s own
     * row holds two receivers absorbed out of order — so both the outer
     * `CanonicalMapSerializer` and the nested `GCounter` one are load-bearing.
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
     * `compactedDots` is private, but every compacted dot is re-emitted through [Quilted.causalDots]
     * alongside the surviving log ops — so the compacted count is the total minus the four ops each
     * replica's log still carries after its own compaction.
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
            "bf6663617573616cbf6573746f7265bf65616c7068619fbf677265706c69636165616c7068616373657101ffff656465" +
                "6c74619fbf677265706c69636165616c7068616373657102ffff646d696b659fbf677265706c696361647a756c756373" +
                "657102ffff647a756c759fbf677265706c696361647a756c756373657101ffffff67636f6e74657874bf627676bf6561" +
                "6c70686102647a756c7503ff65636c6f75649fffffffff"
        const val ORMAP =
            "bf6663617573616cbf6573746f7265bf65616c706861bf64746167739fbf677265706c69636165616c70686163736571" +
                "01ffbf677265706c696361647a756c756373657102ffff6576616c7565bf66636f756e7473bf65616c70686106656465" +
                "6c746107646d696b6508647a756c7505ffffff647a756c75bf64746167739fbf677265706c69636165616c7068616373" +
                "657102ffbf677265706c696361647a756c756373657101ffff6576616c7565bf66636f756e7473bf65616c7068610265" +
                "64656c746104646d696b6503647a756c7501ffffffff67636f6e74657874bf627676bf65616c70686102647a756c7502" +
                "ff65636c6f75649fffffffff"
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
    }
}
