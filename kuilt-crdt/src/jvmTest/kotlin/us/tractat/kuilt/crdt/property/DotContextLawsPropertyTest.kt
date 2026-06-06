package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.ReplicaId

internal class DotContextLawsPropertyTest {

    /**
     * Builds contexts by adding dots with small seq numbers, occasionally
     * introducing gaps so the cloud path is also exercised.
     */
    @Provide
    fun states(): Arbitrary<DotContext> {
        // seq range 1..5 with gaps (not necessarily contiguous per replica)
        val dotArb = Arbitraries.integers().between(0, 2).flatMap { rIdx ->
            Arbitraries.longs().between(1L, 5L).map { seq -> Dot(replicas[rIdx], seq) }
        }
        return dotArb.list().ofMinSize(0).ofMaxSize(8).map { dots ->
            dots.fold(DotContext.EMPTY) { ctx, dot -> ctx.add(dot) }
        }
    }

    @Property
    fun pieceIsIdempotent(@ForAll("states") a: DotContext) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(@ForAll("states") a: DotContext, @ForAll("states") b: DotContext) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("states") a: DotContext,
        @ForAll("states") b: DotContext,
        @ForAll("states") c: DotContext,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(@ForAll("states") a: DotContext, @ForAll("states") b: DotContext) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }

    private companion object {
        val replicas = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))
    }
}
