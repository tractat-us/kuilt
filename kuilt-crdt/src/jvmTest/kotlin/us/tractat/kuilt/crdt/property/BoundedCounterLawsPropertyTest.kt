package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

internal class BoundedCounterLawsPropertyTest {

    // opType: 0=spend, 1=transferTo+1, 2=transferTo+2
    private data class Op(val replicaIndex: Int, val opType: Int, val amount: Long)

    /** Seed + random spend/transfer operations, depth 0..8. */
    @Provide
    fun states(): Arbitrary<BoundedCounter> {
        val quotaArb: Arbitrary<Long> = Arbitraries.integers().between(0, 20).map { it.toLong() }
        val seedArb: Arbitrary<BoundedCounter> = quotaArb.flatMap { q0: Long ->
            quotaArb.flatMap { q1: Long ->
                quotaArb.map { q2: Long ->
                    BoundedCounter.init(
                        mapOf(replicas[0] to q0, replicas[1] to q1, replicas[2] to q2),
                    )
                }
            }
        }
        val opArb: Arbitrary<Op> = Arbitraries.integers().between(0, 2).flatMap { rIdx: Int ->
            Arbitraries.integers().between(0, 2).flatMap { opType: Int ->
                Arbitraries.integers().between(1, 5).map { amount: Int -> Op(rIdx, opType, amount.toLong()) }
            }
        }
        return seedArb.flatMap { seed: BoundedCounter ->
            opArb.list().ofMinSize(0).ofMaxSize(8).map { ops: List<Op> ->
                ops.fold(seed) { s: BoundedCounter, op: Op ->
                    when (op.opType) {
                        0 -> s.trySpend(replicas[op.replicaIndex], op.amount)?.let { s.piece(it) } ?: s
                        1 -> {
                            val toIdx = (op.replicaIndex + 1) % replicas.size
                            s.transfer(replicas[op.replicaIndex], replicas[toIdx], op.amount)?.let { s.piece(it) } ?: s
                        }
                        else -> {
                            val toIdx = (op.replicaIndex + 2) % replicas.size
                            s.transfer(replicas[op.replicaIndex], replicas[toIdx], op.amount)?.let { s.piece(it) } ?: s
                        }
                    }
                }
            }
        }
    }

    @Property
    fun pieceIsIdempotent(@ForAll("states") a: BoundedCounter) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(@ForAll("states") a: BoundedCounter, @ForAll("states") b: BoundedCounter) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("states") a: BoundedCounter,
        @ForAll("states") b: BoundedCounter,
        @ForAll("states") c: BoundedCounter,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(@ForAll("states") a: BoundedCounter, @ForAll("states") b: BoundedCounter) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }

    private companion object {
        val replicas = listOf(ReplicaId("A"), ReplicaId("B"), ReplicaId("C"))
    }
}
