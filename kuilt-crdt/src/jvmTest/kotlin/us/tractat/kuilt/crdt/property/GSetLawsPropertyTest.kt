package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.piece

internal class GSetLawsPropertyTest {

    @Provide
    fun states(): Arbitrary<GSet<String>> =
        Arbitraries.integers().between(0, 5).map { "e-$it" }
            .list().ofMinSize(0).ofMaxSize(6)
            .map { elems ->
                elems.fold(GSet.empty<String>()) { s, e -> s.piece(s.add(e)) }
            }

    @Property
    fun pieceIsIdempotent(@ForAll("states") a: GSet<String>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(@ForAll("states") a: GSet<String>, @ForAll("states") b: GSet<String>) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("states") a: GSet<String>,
        @ForAll("states") b: GSet<String>,
        @ForAll("states") c: GSet<String>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(@ForAll("states") a: GSet<String>, @ForAll("states") b: GSet<String>) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }
}
