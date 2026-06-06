package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Lattice law properties for [MVRegister].
 *
 * Generator design note: causal CRDTs require globally-unique dots. Independent
 * states generated from scratch can collide — the same (replica, seq) pointing to
 * different values — violating the invariant that a dot names exactly one event.
 * We prevent this by restricting each provider call to a single replica, so any
 * independently-generated state occupies a disjoint dot namespace. This correctly
 * models "states from distinct replicas" and makes commutativity/LUB valid to check.
 */
internal class MVRegisterLawsPropertyTest {

    /** State written only by replica A — disjoint from B and C. */
    @Provide
    fun statesA(): Arbitrary<MVRegister<String>> = statesFor(ReplicaId("A"))

    /** State written only by replica B — disjoint from A and C. */
    @Provide
    fun statesB(): Arbitrary<MVRegister<String>> = statesFor(ReplicaId("B"))

    /** State written only by replica C — disjoint from A and B. */
    @Provide
    fun statesC(): Arbitrary<MVRegister<String>> = statesFor(ReplicaId("C"))

    private fun statesFor(replica: ReplicaId): Arbitrary<MVRegister<String>> {
        val valueArb: Arbitrary<String> = Arbitraries.integers().between(0, 9).map { "v-$it" }
        return valueArb.list().ofMinSize(0).ofMaxSize(5).map { values: List<String> ->
            values.fold(MVRegister.empty<String>()) { s: MVRegister<String>, v: String -> s.set(replica, v) }
        }
    }

    @Property
    fun pieceIsIdempotent(@ForAll("statesA") a: MVRegister<String>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(
        @ForAll("statesA") a: MVRegister<String>,
        @ForAll("statesB") b: MVRegister<String>,
    ) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("statesA") a: MVRegister<String>,
        @ForAll("statesB") b: MVRegister<String>,
        @ForAll("statesC") c: MVRegister<String>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(
        @ForAll("statesA") a: MVRegister<String>,
        @ForAll("statesB") b: MVRegister<String>,
    ) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }
}
