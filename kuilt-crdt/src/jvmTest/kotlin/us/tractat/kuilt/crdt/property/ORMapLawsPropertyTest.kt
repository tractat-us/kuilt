package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Lattice law properties for [ORMap].
 *
 * Generator design note: each provider restricts ops to a single replica so that
 * independently-generated states occupy disjoint dot namespaces. This models
 * valid diverged-replica states and prevents dot collisions across generated values.
 */
internal class ORMapLawsPropertyTest {

    private data class Op(val key: String, val isPut: Boolean)

    /** State operated on only by replica A. */
    @Provide
    fun statesA(): Arbitrary<ORMap<String, GCounter>> = statesFor(ReplicaId("A"))

    /** State operated on only by replica B. */
    @Provide
    fun statesB(): Arbitrary<ORMap<String, GCounter>> = statesFor(ReplicaId("B"))

    /** State operated on only by replica C. */
    @Provide
    fun statesC(): Arbitrary<ORMap<String, GCounter>> = statesFor(ReplicaId("C"))

    private fun statesFor(replica: ReplicaId): Arbitrary<ORMap<String, GCounter>> {
        val keyArb: Arbitrary<String> = Arbitraries.integers().between(0, 3).map { "k-$it" }
        val opArb: Arbitrary<Op> = keyArb.flatMap { key: String ->
            Arbitraries.of(true, false).map { isPut: Boolean -> Op(key, isPut) }
        }
        return opArb.list().ofMinSize(0).ofMaxSize(6).map { ops: List<Op> ->
            ops.fold(ORMap.empty<String, GCounter>()) { s: ORMap<String, GCounter>, op: Op ->
                if (op.isPut) {
                    s.put(replica, op.key, GCounter.of(replica to 1L))
                } else {
                    s.remove(op.key)
                }
            }
        }
    }

    @Property
    fun pieceIsIdempotent(@ForAll("statesA") a: ORMap<String, GCounter>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(
        @ForAll("statesA") a: ORMap<String, GCounter>,
        @ForAll("statesB") b: ORMap<String, GCounter>,
    ) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("statesA") a: ORMap<String, GCounter>,
        @ForAll("statesB") b: ORMap<String, GCounter>,
        @ForAll("statesC") c: ORMap<String, GCounter>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(
        @ForAll("statesA") a: ORMap<String, GCounter>,
        @ForAll("statesB") b: ORMap<String, GCounter>,
    ) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }
}
