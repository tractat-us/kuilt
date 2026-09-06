package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.composite.CompositeLoom

/**
 * A single-ply [CompositeLoom] over a shared in-memory mesh must satisfy every
 * seam-contract invariant — composing does not weaken the contract.
 *
 * Both host and joiner use the same [CompositeLoom] instance backed by one
 * [InMemoryLoom] ply, matching the in-process radio fabric pattern where the
 * same factory serves all peers.
 *
 * [UnconfinedTestDispatcher] is injected so the composite's internal reconciliation
 * coroutines (Announce round-trip, peer rollup) run eagerly. This lets the
 * conformance suite's synchronous [peers.value] assertions see the completed state
 * without waiting. Production code uses the confined single-thread default, which
 * is race-free but lazy relative to the test's `.value` reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> {
        val composite = CompositeLoom(
            plies = listOf(PlyId("mem") to InMemoryLoom()),
            dispatcher = UnconfinedTestDispatcher(),
        )
        return composite to composite
    }

    /**
     * `meshDelivery = true` here is genuine, not vacuous: [CompositeLoom] bonds
     * plies into an N-peer mesh (mesh evidence tracked in #1408, Task 1.8).
     *
     * `reportsLiveCapability = false`: the composite rollup is reactive to plies going
     * **woven/torn**, which is not a path observer. With no observing ply under it the composite
     * reports the honest [us.tractat.kuilt.core.FabricAvailability.Unknown] floor (#1712/#1545).
     */
    override fun capabilities() =
        SeamCapabilities.FULL.copy(securesTransport = false, reportsLiveCapability = false)

    override fun capabilityGaps() = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )

    /** #2591: the joiner starts at `{ selfId }` and grows only through the join path. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.TheJoinPath(
            "CompositeSeam._peers opens at { selfId } and the reachability fold publishes a peer only once " +
            "an Announce has mapped (plyId, transportPeer) to a composite PeerId, so the joiner's roster is " +
            "the Announce round-trip's output. Honest weakness: the ply underneath is one shared InMemoryLoom, " +
            "so transport reachability IS shared construction — only the composite layer is proven here.",
        )

    /**
     * Drain the joiner from the composite's reachability fold: closing the joiner's composite seam
     * closes its `mem` ply seam, which removes it from the shared [InMemoryLoom] roster the host's
     * own ply seam observes. The host's ply seam is never closed, so the union stays
     * [us.tractat.kuilt.core.SeamState.Woven] and the fold simply un-maps the departed
     * `(plyId, transportPeer)` — `peers` shrinks under a live survivor. That is a membership drain,
     * distinct from a transport tear (where the host's ply would latch `Torn` too), and it is the
     * event [midSessionDeathDeclaration] names as the one this topology CAN produce.
     *
     * **The rig asserts its own precondition** — the survivor must be live *and already holding the
     * counterpart* — so the obligation cannot pass on a departure this rig did not cause (e.g. a
     * joiner that was never mapped into the fold, where `peers.first { drained !in it }` would be
     * satisfied by the opening roster and assert nothing).
     *
     * **It returns what it actually accomplished**, not an unconditional `true`: the stimulus is
     * "the counterpart genuinely departs", so the return keys on the joiner having reached `Torn`.
     * A close that left the joiner live severed nothing, and the harness then reads as honestly
     * unproven rather than falsely green. (The *survivor's* reaction — roster shrink, state stays
     * Woven — is the obligation's assertion, deliberately not duplicated here.)
     */
    override suspend fun injectMembershipDrain(host: Seam, joiner: Seam): Boolean {
        check(host.state.value is SeamState.Woven && joiner.state.value is SeamState.Woven) {
            "membership-drain rig precondition: both composite seams must be live before the joiner " +
                "departs, or the obligation would pass on a departure this rig did not cause; got " +
                "host=${host.state.value}, joiner=${joiner.state.value}"
        }
        check(joiner.selfId in host.peers.value) {
            "membership-drain rig precondition: the survivor must already hold the counterpart, or " +
                "`peers.first { drained !in it }` is satisfied by the opening roster and asserts " +
                "nothing; got host.peers=${host.peers.value}, joiner=${joiner.selfId}"
        }
        joiner.close()
        return joiner.state.value is SeamState.Torn
    }

    /** Proven: this harness drains a peer without tearing the survivor, so no gap. */
    override fun membershipDrainDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * **Not a gap — the event is not constructible here (#2568).** One [CompositeLoom] over one
     * shared [InMemoryLoom] ply plays both roles, so there is no 2-peer transport under the pair to
     * drop. A peer going away leaves the surviving composite's ply seam live and the composite
     * itself [us.tractat.kuilt.core.SeamState.Woven]; the reachability fold simply un-maps that
     * peer. The in-memory transport-death path is covered by [PeerMeshConformanceTest], which holds
     * both ends of a real 2-peer link.
     *
     * The sibling membership-drain obligation is what this harness proves instead — see
     * [injectMembershipDrain].
     */
    override fun midSessionDeathDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "one CompositeLoom over one shared InMemoryLoom ply plays both roles, so no 2-peer " +
                "transport exists under the pair to drop; a peer leaving un-maps it from the " +
                "reachability fold and leaves the surviving composite Woven",
        )
}
