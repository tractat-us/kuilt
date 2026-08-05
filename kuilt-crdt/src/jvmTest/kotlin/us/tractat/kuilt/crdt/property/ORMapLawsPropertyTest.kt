package us.tractat.kuilt.crdt.property

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/**
 * Lattice law properties for [ORMap].
 *
 * Generator design note: each provider restricts ops to a single replica so that
 * independently-generated states occupy disjoint dot namespaces. This models
 * valid diverged-replica states and prevents dot collisions across generated values.
 *
 * **And that is why [pieceIsAssociative] passed for as long as it did while [ORMap] was not
 * associative (#2086).** Disjoint namespaces mean no operand's context can ever witness a dot
 * another operand still carries, so the one shape that fails — a remove sitting between two puts of
 * the same key, each state derived from the last — is unreachable here by construction. The fix is
 * not to abandon disjointness, which is protecting something real, but to add a second property
 * over states drawn from **one** history: [pieceIsAssociativeAlongOneTrajectory].
 */
internal class ORMapLawsPropertyTest {

    private data class Op(val key: String, val isPut: Boolean, val weight: Long)

    /** State operated on only by replica A. */
    @Provide
    fun statesA(): Arbitrary<ORMap<String, GCounter>> = statesFor(ReplicaId("A"))

    /** State operated on only by replica B. */
    @Provide
    fun statesB(): Arbitrary<ORMap<String, GCounter>> = statesFor(ReplicaId("B"))

    /** State operated on only by replica C. */
    @Provide
    fun statesC(): Arbitrary<ORMap<String, GCounter>> = statesFor(ReplicaId("C"))

    /** Replica A's running history — see [assertAssociativeAlongTrajectory]. */
    @Provide
    fun trajectories(): Arbitrary<List<ORMap<String, GCounter>>> = trajectoryFor(ReplicaId("A"))

    private fun statesFor(replica: ReplicaId): Arbitrary<ORMap<String, GCounter>> =
        randomOps().map { ops -> ops.fold(ORMap.empty(), applyTo(replica)) }

    /**
     * A trajectory that **begins with the failing shape** and then explores randomly around it.
     *
     * The prefix is not decoration. Left to sampling alone, the shape this property exists to catch
     * — put `k`, remove `k`, put `k` again with a *strictly smaller* weight, so a `GCounter` max
     * merge can show the difference — is a few-percent event per try. At `tries = 100` that misses a
     * reverted `ORMap` outright: **measured 4 reds in 6 independent runs** without the prefix, and
     * 6 in 6 with it. A pin that goes green a third of the time while the bug is present is not a
     * pin, and this one was being quoted as a deterministic red.
     *
     * **Measuring that needed its own hygiene, and getting it wrong inflates the answer.** jqwik
     * defaults to `AfterFailureMode.PREVIOUS_SEED` and records the failing seed in
     * `kuilt-crdt/.jqwik-database`, so every run after the first failure *replays* that seed rather
     * than drawing a fresh one. Repeated runs then look deterministic whatever the generator does —
     * the first pass at this measurement read 6/6 on both arms until the database was deleted
     * between runs. Delete it per run, or the experiment answers a question you did not ask.
     *
     * Constructing the shape rather than pinning a jqwik seed is the sturdier of the two fixes: a
     * seed makes one *stream* reproducible, and a jqwik upgrade or a generator edit silently
     * re-rolls it. Every try here contains the counterexample by construction, so the property fails
     * on the first try against a reverted `ORMap` whatever the seed, and the random tail still
     * explores the region around it.
     */
    private fun trajectoryFor(replica: ReplicaId): Arbitrary<List<ORMap<String, GCounter>>> =
        randomOps().map { tail ->
            (SHAPE + tail).runningFold(ORMap.empty(), applyTo(replica))
        }

    private fun randomOps(): Arbitrary<List<Op>> {
        val keyArb: Arbitrary<String> = Arbitraries.integers().between(0, 3).map { "k-$it" }
        val opArb: Arbitrary<Op> = keyArb.flatMap { key: String ->
            // The value varies per op. A generator that always put the same value would make a lost
            // contribution indistinguishable from a kept one — vacuous on the axis #2086 fails.
            Arbitraries.of(true, false).flatMap { isPut: Boolean ->
                Arbitraries.longs().between(1L, 4L).map { weight: Long -> Op(key, isPut, weight) }
            }
        }
        return opArb.list().ofMinSize(0).ofMaxSize(6)
    }

    private fun applyTo(replica: ReplicaId): (ORMap<String, GCounter>, Op) -> ORMap<String, GCounter> =
        { state, op ->
            if (op.isPut) {
                state.piece { it.put(replica, op.key, GCounter.of(replica to op.weight)) }
            } else {
                state.piece { it.remove(op.key) }
            }
        }

    /**
     * The law over states that are causal *ancestors* of one another, which the three
     * disjoint-replica providers above structurally cannot produce. This is the property that
     * catches #2086, and — see [trajectoryFor] — it catches it on every try rather than on a lucky
     * draw.
     */
    @Property(tries = 100)
    fun pieceIsAssociativeAlongOneTrajectory(
        @ForAll("trajectories") trajectory: List<ORMap<String, GCounter>>,
    ) {
        assertAssociativeAlongTrajectory(trajectory)
    }

    @Property
    fun pieceIsIdempotent(@ForAll("statesA") a: ORMap<String, GCounter>) {
        check(a == a.piece(a)) { "idempotence failed for $a" }
    }

    @Property
    fun pieceIsCommutative(
        @ForAll("statesA") a: ORMap<String, GCounter>,
        @ForAll("statesB") b: ORMap<String, GCounter>,
    ) {
        check(a.piece(b) == b.piece(a)) { "commutativity failed for $a, $b" }
    }

    // 200 tries: 3 generated states per trial is more expensive than 2
    @Property(tries = 200)
    fun pieceIsAssociative(
        @ForAll("statesA") a: ORMap<String, GCounter>,
        @ForAll("statesB") b: ORMap<String, GCounter>,
        @ForAll("statesC") c: ORMap<String, GCounter>,
    ) {
        check(a.piece(b).piece(c) == a.piece(b.piece(c))) { "associativity failed for $a, $b, $c" }
    }

    @Property
    fun pieceIsLeastUpperBound(
        @ForAll("statesA") a: ORMap<String, GCounter>,
        @ForAll("statesB") b: ORMap<String, GCounter>,
    ) {
        val joined = a.piece(b)
        check(joined == joined.piece(a)) { "left absorption failed: $a, $b" }
        check(joined == joined.piece(b)) { "right absorption failed: $a, $b" }
    }

    private companion object {
        /**
         * Put, remove, put again — one key, and the second weight strictly *below* the first so the
         * `GCounter` max merge can tell a resurrected write from a kept one. Every trajectory starts
         * here; see [trajectoryFor].
         */
        val SHAPE = listOf(
            Op(key = "k-0", isPut = true, weight = 4L),
            Op(key = "k-0", isPut = false, weight = 0L),
            Op(key = "k-0", isPut = true, weight = 1L),
        )
    }
}
