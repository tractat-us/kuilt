package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Lattice law properties for [ORSet].
 *
 * Generator design note: each provider restricts ops to a single replica so that
 * independently-generated states occupy disjoint dot namespaces. This models
 * valid diverged-replica states and prevents dot collisions across generated values.
 */
internal class ORSetLawsPropertyTest {

    private data class Op(val elem: String, val isAdd: Boolean)

    /** State operated on only by replica A. */
    @Provide
    fun statesA(): Arbitrary<ORSet<String>> = statesFor(ReplicaId("A"))

    /** State operated on only by replica B. */
    @Provide
    fun statesB(): Arbitrary<ORSet<String>> = statesFor(ReplicaId("B"))

    /** State operated on only by replica C. */
    @Provide
    fun statesC(): Arbitrary<ORSet<String>> = statesFor(ReplicaId("C"))

    private fun statesFor(replica: ReplicaId): Arbitrary<ORSet<String>> {
        val elemArb: Arbitrary<String> = Arbitraries.integers().between(0, 3).map { "elem-$it" }
        val opArb: Arbitrary<Op> = elemArb.flatMap { elem: String ->
            Arbitraries.of(true, false).map { isAdd: Boolean -> Op(elem, isAdd) }
        }
        return opArb.list().ofMinSize(0).ofMaxSize(6).map { ops: List<Op> ->
            ops.fold(ORSet.empty<String>()) { s: ORSet<String>, op: Op ->
                if (op.isAdd) s.add(replica, op.elem) else s.remove(op.elem)
            }
        }
    }

    @Property
    fun pieceIsIdempotent(@ForAll("statesA") a: ORSet<String>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(@ForAll("statesA") a: ORSet<String>, @ForAll("statesB") b: ORSet<String>) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("statesA") a: ORSet<String>,
        @ForAll("statesB") b: ORSet<String>,
        @ForAll("statesC") c: ORSet<String>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(@ForAll("statesA") a: ORSet<String>, @ForAll("statesB") b: ORSet<String>) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }
}
