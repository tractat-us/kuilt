package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotMap
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Lattice law properties for [Causal]<[DotMap]<String, [DotSet]>> — the shape underlying ORSet.
 *
 * Generator design note: each provider restricts ops to a single replica so that
 * independently-generated states occupy disjoint dot namespaces. This models
 * valid diverged-replica states and prevents dot collisions across generated values.
 */
internal class CausalDotMapLawsPropertyTest {

    private data class Op(val key: String, val isAdd: Boolean)

    /** State operated on only by replica A. */
    @Provide
    fun statesA(): Arbitrary<Causal<DotMap<String, DotSet>>> = statesFor(ReplicaId("A"))

    /** State operated on only by replica B. */
    @Provide
    fun statesB(): Arbitrary<Causal<DotMap<String, DotSet>>> = statesFor(ReplicaId("B"))

    /** State operated on only by replica C. */
    @Provide
    fun statesC(): Arbitrary<Causal<DotMap<String, DotSet>>> = statesFor(ReplicaId("C"))

    private fun statesFor(replica: ReplicaId): Arbitrary<Causal<DotMap<String, DotSet>>> {
        val keyArb: Arbitrary<String> = Arbitraries.integers().between(0, 3).map { "k-$it" }
        val opArb: Arbitrary<Op> = keyArb.flatMap { key: String ->
            Arbitraries.of(true, false).map { isAdd: Boolean -> Op(key, isAdd) }
        }
        return opArb.list().ofMinSize(0).ofMaxSize(6).map { ops: List<Op> ->
            ops.fold(Causal(DotMap<String, DotSet>(), DotContext.EMPTY)) { causal: Causal<DotMap<String, DotSet>>, op: Op ->
                if (op.isAdd) {
                    val dot = causal.context.nextDot(replica)
                    val existingDots = causal.store.entries[op.key]?.dots ?: emptySet()
                    val updatedEntries = causal.store.entries + (op.key to DotSet(existingDots + dot))
                    Causal(DotMap(updatedEntries), causal.context.add(dot))
                } else {
                    // remove key: drop its dots, context unchanged
                    Causal(DotMap(causal.store.entries - op.key), causal.context)
                }
            }
        }
    }

    @Property
    fun pieceIsIdempotent(@ForAll("statesA") a: Causal<DotMap<String, DotSet>>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(
        @ForAll("statesA") a: Causal<DotMap<String, DotSet>>,
        @ForAll("statesB") b: Causal<DotMap<String, DotSet>>,
    ) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("statesA") a: Causal<DotMap<String, DotSet>>,
        @ForAll("statesB") b: Causal<DotMap<String, DotSet>>,
        @ForAll("statesC") c: Causal<DotMap<String, DotSet>>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(
        @ForAll("statesA") a: Causal<DotMap<String, DotSet>>,
        @ForAll("statesB") b: Causal<DotMap<String, DotSet>>,
    ) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }
}
