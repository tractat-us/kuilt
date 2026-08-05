package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.Causal
import us.tractat.kuilt.crdt.DotContext
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.ReplicaId

/**
 * `Causal<DotSet>` — the enable-wins flag, the smallest causal CRDT there is.
 *
 * The alphabet is `DotSet`'s whole vocabulary, and it is the one this type was reached with before,
 * in the JVM-only jqwik surface deleted in #2101 — so the names below will not resolve.
 * `CausalDotSetLawsPropertyTest.trajectoryFor` folded a list of booleans, minting a fresh dot on
 * `true` and dropping **all** dots while keeping the context on `false`. Both branches survive here
 * verbatim; what is added is that the interesting word is now constructed rather than drawn.
 */
internal class CausalDotSetConvergenceTest : LatticeLawSuite<Causal<DotSet>>() {
    override fun newHarness(): LatticeLawHarness<Causal<DotSet>> = LatticeLawHarness(
        initial = Causal(DotSet(), DotContext.EMPTY),
        alphabet = listOf(
            // Mint the replica's next dot and enable the flag under it. `nextDot` reads the
            // replica's own contiguous prefix, so a linear per-replica history — which is what
            // `causalPool` builds — never mints the same dot twice, even after absorbing a peer.
            LatticeOp("enable", OpKind.ASSERT) { state, replicaIndex, _ ->
                val dot = state.context.nextDot(ReplicaId("R$replicaIndex"))
                Causal(DotSet(state.store.dots + dot), state.context.add(dot))
            },
            // Drop every dot; leave the context alone. Keeping the context is the whole mechanism:
            // it is what lets a later merge tell "I saw that dot and dropped it" from "I have not
            // seen it yet", and dropping it here instead would make the removal invisible.
            LatticeOp("disable", OpKind.RETIRE) { state, _, _ ->
                Causal(DotSet(), state.context)
            },
        ),
        // Declared rather than derived, though the derivation would produce the same word today.
        // `DotSet` offers exactly one assert, so `defaultCriticalShapes` falls into its repeat-the-
        // only-assert branch — and that branch is sound here for the reason its KDoc gives: `enable`
        // mints a FRESH dot, so a lattice that let the retired dot survive lands on `{d1, d2}` where
        // a correct one lands on `{d2}`. Writing the word down pins that reasoning to this file
        // rather than to a derivation a later op would silently change.
        criticalShapes = listOf(listOf("enable", "disable", "enable")),
        serializer = Causal.serializer(DotSet.serializer()),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
