package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Lattice law properties for [LWWRegister].
 *
 * Generator design note: [LWWRegister.piece] assumes that equal `(timestamp, replicaId)` tags
 * imply equal values — two replicas that applied the same set operation agree on the value.
 * The generator must honour this: it derives the value deterministically from
 * `(replicaId, timestamp)` so any two independently-generated states that share a tag
 * will always share the corresponding value.
 */
internal class LWWRegisterLawsPropertyTest {

    private data class Op(val replicaIndex: Int, val timestamp: Long)

    /**
     * Sparse timestamp space (0..20) forces many tie-breaks resolved by replicaId,
     * exercising the full comparator in [LWWRegister.piece].
     * Value is derived as "v-{replicaIndex}-{timestamp}" — deterministic so that
     * any two independently-generated registers agree on value when they share a tag.
     */
    @Provide
    fun states(): Arbitrary<LWWRegister<String>> {
        val tsArb: Arbitrary<Long> = Arbitraries.longs().between(0L, 20L)
        val opArb: Arbitrary<Op> = Arbitraries.integers().between(0, 2).flatMap { rIdx: Int ->
            tsArb.map { ts: Long -> Op(rIdx, ts) }
        }
        return opArb.list().ofMinSize(0).ofMaxSize(5).map { ops: List<Op> ->
            ops.fold(LWWRegister.empty<String>()) { s: LWWRegister<String>, op: Op ->
                // value is a pure function of tag — same tag always produces same value
                val value = "v-${op.replicaIndex}-${op.timestamp}"
                s.set(replicas[op.replicaIndex], op.timestamp, value)
            }
        }
    }

    @Property
    fun pieceIsIdempotent(@ForAll("states") a: LWWRegister<String>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(@ForAll("states") a: LWWRegister<String>, @ForAll("states") b: LWWRegister<String>) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("states") a: LWWRegister<String>,
        @ForAll("states") b: LWWRegister<String>,
        @ForAll("states") c: LWWRegister<String>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(@ForAll("states") a: LWWRegister<String>, @ForAll("states") b: LWWRegister<String>) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }

    private companion object {
        val replicas = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))
    }
}
