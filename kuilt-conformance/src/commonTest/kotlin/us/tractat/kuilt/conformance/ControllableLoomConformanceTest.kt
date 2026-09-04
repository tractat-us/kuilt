package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.ControllableLoom

/**
 * Verifies the `ControllableSeam` produced by [ControllableLoom] satisfies the shared
 * [SeamConformanceSuite].
 *
 * ## Why this harness exists (#2441)
 *
 * [ControllableLoom] is a genuine [Loom] and its seam is the fixture five `:kuilt-quilter`
 * integration tests lean on to script deterministic interleavings — but no
 * [SeamConformanceSuite] subclass ever wove one, so the seam those tests trust to behave like a
 * real fabric had never been asked to. `docs/seam-harness-coverage.md` recorded it as bindable
 * and unbound; this binds it.
 *
 * ## Shape of the harness
 *
 * **In-process radio: `loom to loom`.** [ControllableLoom] is a shared in-memory mesh — every
 * seam it weaves joins the same peer set — so one instance plays both roles, exactly as
 * [InMemoryLoomConformanceTest] does with the reference [us.tractat.kuilt.core.InMemoryLoom] it
 * is a drop-in replacement for. A role-split pair would be wrong here: two [ControllableLoom]s
 * are two disjoint meshes and could not reach each other at all.
 *
 * Delivery is left at its default (nothing held), so the fabric under test is the same one every
 * consumer gets before it starts scripting holds.
 */
class ControllableLoomConformanceTest : SeamConformanceSuite() {

    private val loom = ControllableLoom()

    override fun newLoomPair(): Pair<Loom, Loom> = loom to loom

    /**
     * `securesTransport = false`: an in-process mesh — nothing is on a wire.
     *
     * `reportsLiveCapability = false`: `ControllableSeam` overrides no
     * [us.tractat.kuilt.core.Seam.capability], so it inherits the roleless
     * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor and must not claim otherwise
     * (#1712). Note this is the *seam's* surface: [ControllableLoom.capability] does report
     * `Available`, but that is the pre-connect [Loom] verdict, which is a different question.
     *
     * `collapsesPeersOnTear` is **true**, and this binding is why. It was declared `false` when the
     * harness landed: `ControllableSeam.peers` **was** the loom's shared registry, unfiltered, and
     * `close()` removes this peer from that registry — so a torn seam advertised every remaining
     * remote and had dropped its own `selfId`, both halves of the #1816 obligation failing at once.
     * That is precisely the defect the reference `InMemoryLoom` carried until #1849; #2443 applied
     * the same `LatchingStateFlow` fix to `ControllableSeam`, which is what lets this read `true`.
     * The *ordering* half the contract also asks for is invisible from here (a conflating
     * `StateFlow` settles before any dispatched collector resumes) and is pinned per-fabric by
     * `ControllableLoomTest` in `:kuilt-test`.
     *
     * `meshDelivery = true` is genuine rather than vacuous — an N-peer shared mesh, the same
     * claim [InMemoryLoomConformanceTest] makes — but it is recorded here as trusted by
     * *inspection plus the shared dispatch path*, not by a `MeshConformanceSuite` subclass: this
     * loom's dispatch is `InMemoryLoom`'s with a per-destination hold queue spliced in, and the
     * N-peer fan-out it inherits is what `InMemoryLoomMeshConformanceTest` covers. A dedicated
     * mesh subclass for the held-delivery variants is worth having and is not this PR's job.
     */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(
        securesTransport = false,
        reportsLiveCapability = false,
    )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )

    /** #2591: this fixture fills the joiner's roster itself, so the joiner arm cannot fail here. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.FilledByConstruction(
            "a shared roster: one ControllableLoom, and every ControllableSeam's peers IS the loom-wide " +
            "registry both ends observe, so registering the second seam fills the first's roster too.",
        )

    /**
     * Drain the joiner from the shared roster: closing the joiner seam removes it from the loom's
     * registry (which every seam observes) while the host seam is never closed — so the host sees
     * `peers` shrink with its own `state` staying [us.tractat.kuilt.core.SeamState.Woven]. That is
     * a membership drain, distinct from a transport tear, and it is the same injection
     * [InMemoryLoomConformanceTest] performs over the same shared-registry shape.
     */
    override suspend fun injectMembershipDrain(host: Seam, joiner: Seam): Boolean {
        joiner.close()
        return true
    }

    /** Proven: this harness drains a peer without tearing the survivor, so no gap. */
    override fun membershipDrainDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * **Not a gap — the event is not constructible here (#2568).** One [ControllableLoom] plays both
     * roles, so there is no 2-peer transport under the pair to drop. A peer going away shrinks the
     * loom-wide registry both seams observe and leaves the survivor
     * [us.tractat.kuilt.core.SeamState.Woven] — the *distinct* [injectMembershipDrain] event this
     * harness proves directly above. The in-memory transport-death path is covered by
     * [PeerMeshConformanceTest], which holds both ends of a real 2-peer link.
     *
     * [midSessionDeathDeclarationIsHonest] refutes that claim's cheap failure mode rather than
     * believing it; [ObligationDeclaration] states what the arm still cannot detect.
     */
    override fun midSessionDeathDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "one shared ControllableLoom plays both roles, so no 2-peer transport exists under the " +
                "pair to drop; a peer leaving shrinks the loom-wide registry both seams observe and " +
                "leaves the survivor Woven, which is the distinct membership drain this harness " +
                "proves instead",
        )
}
