package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Lattice law properties for [Causal]<[DotSet]> — the enable-wins flag / presence CRDT.
 *
 * Generator design note: each provider restricts ops to a single replica so that
 * independently-generated states occupy disjoint dot namespaces. This models
 * valid diverged-replica states and prevents dot collisions across generated values.
 */
internal class CausalDotSetLawsPropertyTest {

    /** State operated on only by replica A. */
    @Provide
    fun statesA(): Arbitrary<Causal<DotSet>> = statesFor(ReplicaId("A"))

    /** State operated on only by replica B. */
    @Provide
    fun statesB(): Arbitrary<Causal<DotSet>> = statesFor(ReplicaId("B"))

    /** State operated on only by replica C. */
    @Provide
    fun statesC(): Arbitrary<Causal<DotSet>> = statesFor(ReplicaId("C"))

    private fun statesFor(replica: ReplicaId): Arbitrary<Causal<DotSet>> =
        Arbitraries.of(true, false).list().ofMinSize(0).ofMaxSize(6).map { ops: List<Boolean> ->
            ops.fold(Causal(DotSet(), DotContext.EMPTY)) { causal: Causal<DotSet>, isAdd: Boolean ->
                if (isAdd) {
                    val dot = causal.context.nextDot(replica)
                    Causal(DotSet(causal.store.dots + dot), causal.context.add(dot))
                } else {
                    // remove: drop all dots, context unchanged
                    Causal(DotSet(), causal.context)
                }
            }
        }

    @Property
    fun pieceIsIdempotent(@ForAll("statesA") a: Causal<DotSet>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(@ForAll("statesA") a: Causal<DotSet>, @ForAll("statesB") b: Causal<DotSet>) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("statesA") a: Causal<DotSet>,
        @ForAll("statesB") b: Causal<DotSet>,
        @ForAll("statesC") c: Causal<DotSet>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(@ForAll("statesA") a: Causal<DotSet>, @ForAll("statesB") b: Causal<DotSet>) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }
}
