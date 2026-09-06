package us.tractat.kuilt.conformance.lattice

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId

/**
 * The value half of the tag → write rule that keeps this generator inside [LWWRegister.set]'s
 * precondition.
 *
 * `set` documents that `(replica, timestamp)` MUST uniquely identify a write: at equal tags
 * [LWWRegister.piece]'s `else -> this` assumes equal values, so two states sharing a tag and
 * carrying different content converge to whichever operand is on the left.
 *
 * The rule is total, so any op below can be read against it on its own:
 *
 * - **even timestamp → a set**, carrying `v-<replica>-<timestamp>`;
 * - **odd timestamp → an unset.**
 *
 * Parity carries the *kind* as well because deriving only the value would still let a `set` and an
 * `unset` share a tag, and those differ in content too. Every op draws from a band of the right
 * parity, so the rule holds by construction rather than by review.
 *
 * **Why it is still load-bearing after #2087**, which is not obvious. The rule was introduced
 * because an *assigning* `set` let a losing-tag write into the pool — **226 non-commuting pairs in
 * 12,979** over seeds `0..63` (52 in 3,165 over `0..15`) before it existed. #2087 made `set` and
 * `unset` write through the join, so a losing tag can no longer enter the pool at all, and the bands
 * below now ascend from the state's own tag, so **one replica never reuses a tag**. Both halves of
 * that original reachability argument are gone. What is *not* gone is [LatticeLawHarness]'s
 * exhaustive small-word walk, which runs many independent words from the bottom on replica 0: two
 * different words can land on the same `(R0, timestamp)`, and without the parity split a `set` word
 * and an `unset` word could do it while disagreeing on content. `registerValue` being a pure
 * function of `(replica, timestamp)` closes the set-vs-set case; parity closes the set-vs-unset one.
 */
private fun registerValue(replica: ReplicaId, timestamp: Long): String = "v-${replica.value}-$timestamp"

/**
 * The slot within a round for each band, and the round size — the three bands are disjoint, ordered
 * `set-low < unset-high < set-highest`, and every slot's parity matches its kind (see
 * [registerValue]).
 */
private const val ROUND = 30L

/**
 * The base timestamp of the round **after** the one [current] sits in — so every op below writes at
 * a tag that strictly dominates the state it was applied to, and therefore lands rather than being
 * dropped by [LWWRegister.piece].
 *
 * This is the "state-derived clock" [LatticeLawHarness]'s vacuity message prescribes, and #2087 is
 * what made it necessary. While `set` assigned, a write at any absolute tag changed the register, so
 * three fixed bands searched fine; once the write goes through the join, a fixed band is a **no-op**
 * whenever the state has already climbed past it — measured at **44.0%** no-op steps against a 13%
 * ceiling, i.e. nearly half the generator's budget spent on writes the type discards. Raising the
 * ceiling would have bought the number without buying the search.
 */
private fun nextRoundBase(current: Long): Long =
    if (current == Long.MIN_VALUE) 0L else (current / ROUND + 1) * ROUND

// Binds LWWRegister<String> — the single-cell primitive LWWMap is built from (LWWMap has its own
// convergence test). The convergence property is the same: the (timestamp, replicaId) tie-breaker
// must produce the same winner regardless of merge order.
internal class LWWRegisterConvergenceTest : LatticeLawSuite<LWWRegister<String>>() {
    override fun newHarness(): LatticeLawHarness<LWWRegister<String>> = LatticeLawHarness(
        initial = LWWRegister.empty(),
        // Three bands, disjoint and ascending *within a round*, so `set-low · unset-high ·
        // set-highest` ascends on every seed. That is about what the shape *means*, not whether it
        // runs: a shape drawing one shared band would satisfy the harness's no-op check while
        // asserting nothing — the tombstone would often hold the highest tag, and "the register
        // reads null" would then be the correct answer whether or not the join honoured the
        // re-assert. Ascending bands make the re-assert the winner, so a null at the end can only
        // mean the join kept a retirement it should have dropped.
        //
        // Each band stays sparse (5 timestamps per round) so different replicas still collide on one
        // frequently, which is what exercises `piece`'s `replicaId` tie-break — and the tie stays
        // observable, because `registerValue` differs by replica at the same timestamp.
        //
        // The bands are offset by `nextRoundBase(state.timestamp)` rather than absolute: see that
        // function for why #2087 requires it. Sparsity, ordering and parity are all preserved,
        // because the offset is a multiple of an even `ROUND`.
        alphabet = listOf(
            LatticeOp("set-low", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                val timestamp = nextRoundBase(state.timestamp) + 2 * random.nextLong(0L, 5L)
                state.set(replica, timestamp, registerValue(replica, timestamp))
            },
            LatticeOp("set-highest", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                val timestamp = nextRoundBase(state.timestamp) + 2 * random.nextLong(10L, 15L)
                state.set(replica, timestamp, registerValue(replica, timestamp))
            },
            // `LWWRegister.unset` is a last-writer-wins *tombstone*: it competes under `piece`
            // exactly like a set and, once it wins, `value` reads null. That withdraws an
            // observation an earlier op made, which is what OpKind.RETIRE names. The binding
            // measured 0.0% retiring steps until this op existed, though `unset` has always been
            // there — the #2100 vacuity shape. (`OpKind`'s KDoc used to read "LWWRegister has no
            // removal at all", which this binding contradicted out loud; #2146 removed the claim
            // and now records why it was wrong.)
            LatticeOp("unset-high", OpKind.RETIRE) { state, replicaIndex, random ->
                val timestamp = nextRoundBase(state.timestamp) + 2 * random.nextLong(5L, 10L) + 1
                state.unset(ReplicaId("R$replicaIndex"), timestamp)
            },
        ),
        // Named rather than defaulted: `defaultCriticalShapes` takes the first two ASSERT ops in
        // declaration order, and a shape that re-asserts from the *lowest* band cannot outrank the
        // tombstone it is meant to survive.
        criticalShapes = listOf(listOf("set-low", "unset-high", "set-highest")),
        // A total order, so the concurrency floor is waived — and the *reason* it is a total order
        // is worth recording, because it was manufactured until very recently. This binding read
        // 1.7% concurrent pairs while it was minting one `(replica, timestamp)` tag for two
        // different values: two states carrying the same tag and disagreeing on the content are
        // incomparable, so every violation of the precondition above showed up here as a
        // concurrent pair. Honouring the precondition removed all of them and the rate went to
        // **0.0%**, which is the truth about a single cell with a totally-ordered tag — any two
        // states of it are comparable, and no generator can change that.
        //
        // So the 1.7% was not concurrency the fix destroyed; it was the violation, being counted.
        // Ancestry reads 49.0%, against a 50% ceiling only a chain can reach.
        //
        // No-op ceiling tightened from the shared 25% default; see `VacuityFloors.maxNoOpSteps` for
        // the rule and for why the shared default cannot do this job. Re-measured after #2087 with
        // `VacuityBreakdownProbe` over seeds `0..15` — the window `generatorIsNotVacuous` runs — on
        // the shipped `EVERY_REPLICA/AS_BOUND` arm. The healthy rate moved **6.6% → 0.0%** (0 of
        // 182 steps) and is now **structural**: every op writes at `nextRoundBase(state.timestamp)`
        // or above, which strictly dominates, so the join can never discard one. The ceiling stays
        // at 13% rather than being tightened onto that zero — it was measured on the JVM only, and
        // pinning a zero from one target is the #2592 shape.
        //
        // What the 13% still catches, on the same run:
        //  - NOT the leading assert: without it this binding reads 0.0%, at its own healthy rate, so
        //    the pin is unreachable here in the direction a ceiling can see (was 2.2%, same verdict).
        //  - retirement dead off replica 0 (#2158's shape): **15.9%**, reds by 2.9 points. That
        //    margin narrowed from 7.3 because the healthy rate fell, not because the shape got
        //    quieter — the ceiling's one live catch is now closer to it than it was.
        // Effective retires read 28.0%, against the 10% floor.
        floors = VacuityFloors(totalOrder = true, maxNoOpSteps = 0.13),
        serializer = LWWRegister.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
