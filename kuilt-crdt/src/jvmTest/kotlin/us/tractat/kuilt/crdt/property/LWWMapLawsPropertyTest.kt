package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Lattice law properties for [LWWMap].
 *
 * Generator design note: [LWWMap] composes [us.tractat.kuilt.crdt.LWWRegister] per key.
 * The per-key register assumes that equal `(timestamp, replicaId)` tags imply equal values.
 * The generator derives values deterministically from `(replicaIndex, timestamp, key)` so any
 * two independently-generated maps that share a tag on a key also share the value.
 */
internal class LWWMapLawsPropertyTest {

    private data class Op(val replicaIndex: Int, val timestamp: Long, val key: String)

    @Provide
    fun states(): Arbitrary<LWWMap<String, String>> {
        val tsArb: Arbitrary<Long> = Arbitraries.longs().between(0L, 20L)
        val keyArb: Arbitrary<String> = Arbitraries.integers().between(0, 3).map { "k-$it" }
        val opArb: Arbitrary<Op> = Arbitraries.integers().between(0, 2).flatMap { rIdx: Int ->
            tsArb.flatMap { ts: Long ->
                keyArb.map { key: String -> Op(rIdx, ts, key) }
            }
        }
        return opArb.list().ofMinSize(0).ofMaxSize(6).map { ops: List<Op> ->
            ops.fold(LWWMap.empty<String, String>()) { s: LWWMap<String, String>, op: Op ->
                // value is a pure function of (replicaIndex, timestamp, key) — deterministic
                val value = "v-${op.replicaIndex}-${op.timestamp}-${op.key}"
                s.set(replicas[op.replicaIndex], op.timestamp, op.key, value)
            }
        }
    }

    @Property
    fun pieceIsIdempotent(@ForAll("states") a: LWWMap<String, String>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(
        @ForAll("states") a: LWWMap<String, String>,
        @ForAll("states") b: LWWMap<String, String>,
    ) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("states") a: LWWMap<String, String>,
        @ForAll("states") b: LWWMap<String, String>,
        @ForAll("states") c: LWWMap<String, String>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(
        @ForAll("states") a: LWWMap<String, String>,
        @ForAll("states") b: LWWMap<String, String>,
    ) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }

    private companion object {
        val replicas = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))
    }
}
