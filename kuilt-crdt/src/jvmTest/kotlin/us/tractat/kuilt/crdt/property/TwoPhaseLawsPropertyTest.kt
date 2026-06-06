package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.TwoPhaseSet
import us.tractat.kuilt.crdt.piece

internal class TwoPhaseLawsPropertyTest {

    private data class Op(val elem: String, val isAdd: Boolean)

    @Provide
    fun states(): Arbitrary<TwoPhaseSet<String>> {
        val elemArb: Arbitrary<String> = Arbitraries.integers().between(0, 5).map { "e-$it" }
        val opArb: Arbitrary<Op> = elemArb.flatMap { elem: String ->
            Arbitraries.of(true, false).map { isAdd: Boolean -> Op(elem, isAdd) }
        }
        return opArb.list().ofMinSize(0).ofMaxSize(6).map { ops: List<Op> ->
            ops.fold(TwoPhaseSet.empty()) { s: TwoPhaseSet<String>, op: Op ->
                if (op.isAdd) s.piece(s.add(op.elem)) else s.piece(s.remove(op.elem))
            }
        }
    }

    @Property
    fun pieceIsIdempotent(@ForAll("states") a: TwoPhaseSet<String>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(@ForAll("states") a: TwoPhaseSet<String>, @ForAll("states") b: TwoPhaseSet<String>) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("states") a: TwoPhaseSet<String>,
        @ForAll("states") b: TwoPhaseSet<String>,
        @ForAll("states") c: TwoPhaseSet<String>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(@ForAll("states") a: TwoPhaseSet<String>, @ForAll("states") b: TwoPhaseSet<String>) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }
}
