package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.Tuple
import net.jqwik.api.Tuple.Tuple2
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Property-based lattice laws and convergence properties for [Rga].
 *
 * RGA is op-based: its [Rga.piece] is an idempotent set-union of op-logs.
 * The standard lattice laws hold by construction of set-union:
 * - Idempotent: `a.piece(a) == a`
 * - Commutative: `a.piece(b) == b.piece(a)`
 * - Associative: `a.piece(b.piece(c)) == a.piece(b).piece(c)`
 *
 * The convergence property verifies that N replicas receiving the same ops in
 * different orders all agree on [Rga.toList].
 */
internal class RgaLawsPropertyTest {

    // ---- Lattice law properties ----

    @Property
    fun pieceIsIdempotent(@ForAll("statesA") a: Rga<String>) {
        check(a.piece(a) == a) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(
        @ForAll("statesA") a: Rga<String>,
        @ForAll("statesB") b: Rga<String>,
    ) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed" }
    }

    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("statesA") a: Rga<String>,
        @ForAll("statesB") b: Rga<String>,
        @ForAll("statesC") c: Rga<String>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed" }
    }

    @Property
    fun pieceIsLeastUpperBound(
        @ForAll("statesA") a: Rga<String>,
        @ForAll("statesB") b: Rga<String>,
    ) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed" }
        check(joined == joined.piece(b)) { "right absorption failed" }
    }

    // ---- Convergence: different delivery orders → same toList() ----

    /**
     * Three replicas independently apply the same random op sequences (in
     * potentially different orders, delivered all-at-once via [Rga.piece]).
     * All must converge to the same visible list.
     */
    @Property(tries = 300)
    fun threeReplicasConverge(
        @ForAll("opsA") opsA: List<RgaOp<String>>,
        @ForAll("opsB") opsB: List<RgaOp<String>>,
        @ForAll("opsC") opsC: List<RgaOp<String>>,
    ) {
        // Build each replica's local state from its own ops.
        val stateA = opsA.fold(Rga.empty<String>()) { s, op -> s.apply(op) }
        val stateB = opsB.fold(Rga.empty<String>()) { s, op -> s.apply(op) }
        val stateC = opsC.fold(Rga.empty<String>()) { s, op -> s.apply(op) }

        // Merge all ops: each replica should converge to the same state.
        val convergedA = stateA.piece(stateB).piece(stateC)
        val convergedB = stateB.piece(stateC).piece(stateA)
        val convergedC = stateC.piece(stateA).piece(stateB)

        check(convergedA.toList() == convergedB.toList()) {
            "A and B diverged: ${convergedA.toList()} vs ${convergedB.toList()}"
        }
        check(convergedB.toList() == convergedC.toList()) {
            "B and C diverged: ${convergedB.toList()} vs ${convergedC.toList()}"
        }
    }

    /**
     * Concurrent inserts after the same predecessor always produce a consistent
     * order: larger id wins the slot immediately after the predecessor.
     */
    @Property
    fun concurrentInsertsAfterSamePredecessorAreOrdered(
        @ForAll("twoInserts") pair: Tuple2<RgaOp.Insert<String>, RgaOp.Insert<String>>,
    ) {
        val insertA = pair.get1()
        val insertB = pair.get2()
        // insertA.after == insertB.after (same predecessor)
        val replicaA = Rga.empty<String>().apply(insertA)
        val replicaB = Rga.empty<String>().apply(insertB)

        val merged = replicaA.piece(replicaB)
        val list = merged.toList()

        // Larger id should appear first (immediately after the shared predecessor).
        if (insertA.id > insertB.id) {
            check(list.indexOf(insertA.value) < list.indexOf(insertB.value)) {
                "Expected ${insertA.value} (larger id) before ${insertB.value}: $list"
            }
        } else {
            check(list.indexOf(insertB.value) < list.indexOf(insertA.value)) {
                "Expected ${insertB.value} (larger id) before ${insertA.value}: $list"
            }
        }
    }

    /**
     * Tombstones exclude elements from [Rga.toList]: removing at index 0 shrinks
     * the list by exactly one. The element's id remains in the op-log so future
     * causal references (inserts after it) remain resolvable.
     */
    @Property
    fun removedElementsAreExcludedFromToList(@ForAll("statesA") state: Rga<String>) {
        val visible = state.toList()
        if (visible.isEmpty()) return
        val (afterRemove, _) = state.removeAt(0)!!
        val newVisible = afterRemove.toList()
        // The list must shrink by exactly one — even if the same value appears
        // multiple times, one positional occurrence is gone.
        check(newVisible.size == visible.size - 1) {
            "Expected ${visible.size - 1} elements after remove, got ${newVisible.size}"
        }
        // The remaining list is the tail: elements at indices 1..end of the original.
        check(newVisible == visible.drop(1)) {
            "List mismatch after remove: expected ${visible.drop(1)}, got $newVisible"
        }
    }

    /**
     * [Rga.insertAt] and [Rga.insertAfter] produce consistent results:
     * inserting at index i places the value at that visible position.
     */
    @Property
    fun insertAtPlacesElementAtCorrectIndex(@ForAll("statesA") state: Rga<String>) {
        val visible = state.toList()
        val index = visible.size / 2 // middle
        val (newState, _) = state.insertAt(ReplicaId("test"), index, "X")
        val newVisible = newState.toList()
        check(newVisible[index] == "X") {
            "Expected 'X' at index $index in $newVisible"
        }
        check(newVisible.size == visible.size + 1) {
            "Expected ${visible.size + 1} elements, got ${newVisible.size}"
        }
    }

    // ---- Providers ----

    @Provide
    fun statesA(): Arbitrary<Rga<String>> = statesFor(ReplicaId("A"))

    @Provide
    fun statesB(): Arbitrary<Rga<String>> = statesFor(ReplicaId("B"))

    @Provide
    fun statesC(): Arbitrary<Rga<String>> = statesFor(ReplicaId("C"))

    @Provide
    fun opsA(): Arbitrary<List<RgaOp<String>>> = opsFor(ReplicaId("A"))

    @Provide
    fun opsB(): Arbitrary<List<RgaOp<String>>> = opsFor(ReplicaId("B"))

    @Provide
    fun opsC(): Arbitrary<List<RgaOp<String>>> = opsFor(ReplicaId("C"))

    /** Generates two Insert ops from different replicas that share the same predecessor (HEAD). */
    @Provide
    fun twoInserts(): Arbitrary<Tuple2<RgaOp.Insert<String>, RgaOp.Insert<String>>> {
        return Arbitraries.integers().between(1, 100).map { lamport ->
            val insertA = RgaOp.Insert(
                id = RgaId(lamport = lamport.toLong(), replicaId = ReplicaId("A")),
                value = "valA-$lamport",
                after = RgaId.HEAD,
            )
            val insertB = RgaOp.Insert(
                id = RgaId(lamport = lamport.toLong() + 1, replicaId = ReplicaId("B")),
                value = "valB-${lamport + 1}",
                after = RgaId.HEAD,
            )
            Tuple.of(insertA, insertB)
        }
    }

    private fun statesFor(replica: ReplicaId): Arbitrary<Rga<String>> =
        opsFor(replica).map { ops -> ops.fold(Rga.empty()) { s, op -> s.apply(op) } }

    private fun opsFor(replica: ReplicaId): Arbitrary<List<RgaOp<String>>> {
        val valueArb: Arbitrary<String> = Arbitraries.integers().between(0, 9).map { "v$it" }
        return valueArb.list().ofMinSize(0).ofMaxSize(6).map { values ->
            val ops = mutableListOf<RgaOp<String>>()
            var state = Rga.empty<String>()
            for (value in values) {
                val size = state.size
                if (size > 0 && ops.size % 3 == 0) {
                    // Occasionally remove an existing element to exercise tombstones.
                    val idx = (size / 2).coerceIn(0, size - 1)
                    val pair = state.removeAt(idx)
                    if (pair != null) {
                        val (newState, op) = pair
                        state = newState
                        ops += op
                    }
                } else {
                    val insertAt = state.size
                    val (newState, op) = state.insertAt(replica, insertAt, value)
                    state = newState
                    ops += op
                }
            }
            ops
        }
    }
}
