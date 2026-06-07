package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

internal class PNCounterLawsPropertyTest {

    /**
     * Generate a PNCounter by folding random increment/decrement operations onto
     * ZERO. Uses three replicas with independent slots — any delivery order
     * converges by the lattice laws.
     */
    @Provide
    fun states(): Arbitrary<PNCounter> =
        Arbitraries.integers().between(0, 30).map { it.toLong() }
            .list().ofMinSize(0).ofMaxSize(6)
            .map { deltas: List<Long> ->
                deltas.foldIndexed(PNCounter.ZERO) { i, acc, delta ->
                    val replica = replicas[i % replicas.size]
                    if (i % 2 == 0) acc.piece(acc.increment(replica, delta + 1L))
                    else acc.piece(acc.decrement(replica, delta + 1L))
                }
            }

    @Property
    fun pieceIsIdempotent(@ForAll("states") a: PNCounter) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(@ForAll("states") a: PNCounter, @ForAll("states") b: PNCounter) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("states") a: PNCounter,
        @ForAll("states") b: PNCounter,
        @ForAll("states") c: PNCounter,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(@ForAll("states") a: PNCounter, @ForAll("states") b: PNCounter) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }

    @Property
    fun valueEqualsIncMinusDec(@ForAll("states") a: PNCounter) {
        // The defining invariant: value == total incremented - total decremented.
        check(a.value == a.totalIncrement - a.totalDecrement) {
            "value invariant failed: value=${a.value}, inc=${a.totalIncrement}, dec=${a.totalDecrement}"
        }
    }

    private companion object {
        val replicas = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))
    }
}
