package us.tractat.kuilt.conformance.convergence

import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId

/**
 * The value half of the tag → write rule that keeps this generator inside [LWWRegister.set]'s
 * precondition.
 *
 * `set` documents that `(replica, timestamp)` MUST uniquely identify a write: at equal tags
 * [LWWRegister.piece]'s `else -> this` assumes equal values, so two states sharing a tag and
 * carrying different content converge to whichever operand is on the left. This generator
 * **assigns** rather than joins — `state.set(…)` replaces the register outright, so a write at a
 * *losing* tag still enters the pool — which is what makes a duplicate tag reachable here at all,
 * and it was: **226 non-commuting pairs in 12,979** over seeds `0..63` (52 in 3,165 over `0..15`)
 * before this rule.
 *
 * The rule is total, so any op below can be read against it on its own:
 *
 * - **even timestamp → a set**, carrying `v-<replica>-<timestamp>`;
 * - **odd timestamp → an unset.**
 *
 * Parity carries the *kind* as well because deriving only the value would still let a `set` and an
 * `unset` share a tag, and those differ in content too. Every op draws from a band of the right
 * parity, so the rule holds by construction rather than by review.
 */
private fun registerValue(replica: ReplicaId, timestamp: Long): String = "v-${replica.value}-$timestamp"

// Binds LWWRegister<String> — the single-cell primitive LWWMap is built from (LWWMap has its own
// convergence test). The convergence property is the same: the (timestamp, replicaId) tie-breaker
// must produce the same winner regardless of merge order.
internal class LWWRegisterConvergenceTest : CrdtConvergenceSuite<LWWRegister<String>>() {
    override fun newHarness(): CrdtConvergenceHarness<LWWRegister<String>> = CrdtConvergenceHarness(
        initial = LWWRegister.empty(),
        // Three bands, disjoint and ascending, so `set-low · unset-high · set-highest` ascends on
        // every seed. That is about what the shape *means*, not whether it runs: an assignment
        // always changes the register, so a shape drawing one shared band would satisfy the
        // harness's no-op check while asserting nothing — the tombstone would often hold the
        // highest tag, and "the register reads null" would then be the correct answer whether or
        // not the join honoured the re-assert. Ascending bands make the re-assert the winner, so a
        // null at the end can only mean the join kept a retirement it should have dropped.
        //
        // Each band stays sparse (5 timestamps) so different replicas still collide on one
        // frequently, which is what exercises `piece`'s `replicaId` tie-break — and the tie stays
        // observable, because `registerValue` differs by replica at the same timestamp.
        alphabet = listOf(
            LatticeOp("set-low", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                val timestamp = 2 * random.nextLong(0L, 5L)
                state.set(replica, timestamp, registerValue(replica, timestamp))
            },
            LatticeOp("set-highest", OpKind.ASSERT) { state, replicaIndex, random ->
                val replica = ReplicaId("R$replicaIndex")
                val timestamp = 2 * random.nextLong(10L, 15L)
                state.set(replica, timestamp, registerValue(replica, timestamp))
            },
            // `LWWRegister.unset` is a last-writer-wins *tombstone*: it competes under `piece`
            // exactly like a set and, once it wins, `value` reads null. That withdraws an
            // observation an earlier op made, which is what OpKind.RETIRE names. The binding
            // measured 0.0% retiring steps until this op existed, though `unset` has always been
            // there — the #2100 vacuity shape. (`OpKind`'s KDoc table still reads "LWWRegister has
            // no removal at all"; that was already inaccurate and this binding now contradicts it
            // out loud — see the PR body.)
            LatticeOp("unset-high", OpKind.RETIRE) { state, replicaIndex, random ->
                state.unset(ReplicaId("R$replicaIndex"), 2 * random.nextLong(5L, 10L) + 1)
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
        // Ancestry reads 48.1%, against a 50% ceiling only a chain can reach.
        floors = VacuityFloors(totalOrder = true),
        serializer = LWWRegister.serializer(String.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
