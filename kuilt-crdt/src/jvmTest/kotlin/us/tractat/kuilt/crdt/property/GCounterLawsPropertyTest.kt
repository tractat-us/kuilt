package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId

internal class GCounterLawsPropertyTest {

    @Provide
    fun states(): Arbitrary<GCounter> =
        Arbitraries.integers().between(0, 100).map { it.toLong() }
            .list().ofMinSize(0).ofMaxSize(3)
            .map { counts: List<Long> ->
                val pairs = counts.mapIndexed { i, c -> replicas[i % replicas.size] to c }
                GCounter.of(*pairs.toTypedArray())
            }

    @Property
    fun pieceIsIdempotent(@ForAll("states") a: GCounter) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(@ForAll("states") a: GCounter, @ForAll("states") b: GCounter) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("states") a: GCounter,
        @ForAll("states") b: GCounter,
        @ForAll("states") c: GCounter,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(@ForAll("states") a: GCounter, @ForAll("states") b: GCounter) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }

    private companion object {
        val replicas = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))
    }
}
