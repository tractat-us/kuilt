package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.ObligationDeclaration
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Verifies [GossipSeam] satisfies the shared [SeamConformanceSuite]: it is a
 * well-behaved [Seam] — host/join, broadcast delivery, single-collection in-order
 * [Seam.incoming], `peers`, idempotent `close`, the `Torn` lifecycle (incl. incoming
 * completion), and `PeerNotConnected` — when wrapping a real full-membership base.
 *
 * Wiring notes:
 * - **Base is a [peerMesh] over one [connectionPair]** — the same base
 *   `PeerMeshConformanceTest` drives, one end per role. It is *not* the simulation-only
 *   `InMemoryGossipNetwork`: that mesh seam reports a constant `Woven` state with a no-op
 *   `close`, so it can't exercise the suite's `Torn`-lifecycle invariants (tests 9 & 11).
 *   `peerMesh` already passes the suite itself and gives a genuine teardown.
 * - **It is also not [us.tractat.kuilt.core.InMemoryLoom], which is what it used to be, and
 *   that swap is the point of this harness's shape (#2605).** An `InMemoryLoom` owns *one*
 *   roster `StateFlow` that every seam it weaves reads, and `GossipSeam.peers` delegates
 *   straight to its base — so the joiner held the host by construction and the joiner half of
 *   [SeamConformanceSuite.peersReportsSelfIdAndAtLeastTwoAfterJoin] could not fail here however
 *   badly a join path behaved. Two ends of a `connectionPair` give each role its own roster,
 *   grown only by the handshake it ran itself. See [joinerRosterOrigin].
 * - **A fresh pair per [newLoomPair].** A shared base accumulates peers across tests; a
 *   k-regular gossip flood would then pick a subset that need not include the specific joiner
 *   the broadcast test asserts on (a full-mesh base hides this — it reaches all).
 * - **Started on `backgroundScope`.** [GossipSeam] owns perpetually re-arming heartbeat
 *   timers; only `backgroundScope` is cancelled before `runTest`'s terminal time-advance,
 *   so they neither block the test's structured scope nor spin `advanceUntilIdle`.
 * - **`jitter = ZERO`.** With the default jitter window the view recompute would not fire
 *   before the suite's `broadcast`, dropping the frame; a zero window makes the 2-peer
 *   active view (the one other peer) converge synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipSeamConformanceTest : SeamConformanceSuite() {
    private companion object {
        /**
         * The extra peer [injectMembershipDrain] dials into the survivor so the joiner's departure is
         * a roster shrink rather than the survivor's last link dropping. Never woven by [newLoomPair].
         */
        val BYSTANDER = PeerId("drain-bystander")

        /**
         * How long [injectMembershipDrain] waits, in **virtual** time, for the joiner it just
         * unplugged to latch `Torn`. A rig-reach backstop, never an assertion: the healthy path
         * latches as soon as the read loop is scheduled. Sized to match the bound the obligation
         * itself uses on the survivor side.
         */
        val RIG_BOUND = 5.seconds
    }

    /**
     * The two ends of the in-memory link under the current pair, held so [injectMidSessionDeath] can
     * drop the transport out from under a live session. Tests run one pair at a time, sequentially —
     * the same capture `PeerMeshConformanceTest` makes.
     */
    private var link: Pair<Connection, Connection>? = null

    /** The host-role loom of the current pair, held so [injectMembershipDrain] can reach its base [Mesh]. */
    private var hostLoom: GossipLoom? = null

    // availability() never weaves, so a scope-free pair is enough for that one test.
    override fun newLoomPair(): Pair<Loom, Loom> = newPair(testScope = null)

    override fun newLoomPair(testScope: TestScope): Pair<Loom, Loom> = newPair(testScope)

    private fun newPair(testScope: TestScope?): Pair<Loom, Loom> {
        val conns = connectionPair()
        link = conns
        return gossipLoomPair(conns, testScope).also { hostLoom = it.first }
    }

    /**
     * Drop **both** ends of the link under the live pair, so each overlay observes its peer's
     * disconnect rather than a local `close()`. The base `peerMesh` drains to empty and latches
     * `Torn`; the obligation this unlocks is whether [GossipSeam]'s **own** inbound spool — not the
     * base's — completes on that tear (#2605 follow-on: the swap to a `connectionPair` base is what
     * put this handle within reach at all).
     */
    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
        val (hostConn, joinerConn) = link ?: return false
        joinerConn.close()
        hostConn.close()
        return true
    }

    /**
     * [ObligationDeclaration.Proven]: this harness drops the transport under a live pair and the
     * obligation holds. Not [ObligationDeclaration.NotApplicable.NotConstructible] — that arm is for
     * the shared in-process meshes with no 2-peer transport under the pair to drop, which is what this
     * harness was until #2605 gave it a `connectionPair`.
     */
    override fun midSessionDeathDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * Drain the joiner from the survivor's roster **without** tearing it — the distinct event from
     * [injectMidSessionDeath] directly above.
     *
     * **Why this rig is not `joiner.close()`, and why #2623's sketch could not be taken literally.**
     * That sketch was written against this harness's pre-#2605 shape, when the base was one shared
     * [us.tractat.kuilt.core.InMemoryLoom] whose roster survived a peer leaving. Since #2605 the base
     * is a [peerMesh] over **one** [connectionPair], and `peerMesh` latches
     * [us.tractat.kuilt.core.SeamState.Torn] when its **last** link drops — so a bare `joiner.close()`
     * here is a *tear*, not a drain, and the obligation reds on
     * `assertIs<SeamState.Woven>` with `actual <SeamState$Torn>`. Measured, not reasoned: see the PR.
     *
     * So the rig first makes the survivor genuinely N-peer. It dials one **bystander** [peerMesh] in
     * over a fresh [connectionPair] via [Mesh.addLink] — a link that outlives the joiner's departure —
     * and only then drops the joiner's transport. The survivor's mesh removes the joiner peer and
     * keeps serving the bystander: `peers` shrinks from three to two while `state` stays `Woven`.
     * That is the drain-without-tear a strictly-2-peer mesh structurally cannot model, and the
     * bystander exists **only inside this hook**, so no other test in the suite sees a third peer (the
     * hazard this class's "a fresh pair per `newLoomPair`" note warns about).
     *
     * **The rig asserts its own preconditions** — both seams live, and the survivor already *holding*
     * the counterpart, so the obligation cannot pass on a departure this rig did not cause (a survivor
     * that never held the joiner satisfies `peers.first { drained !in it }` from its opening roster and
     * asserts nothing).
     *
     * **It returns what it actually accomplished**, not an unconditional `true`. Two things can fail
     * to land, and each leaves the harness honestly unproven rather than falsely green: the bystander
     * may not have been admitted (checked against the survivor's own roster — without it the next step
     * is a tear, not a drain), and the joiner may not have departed (checked as its reaching `Torn`).
     * The *survivor's* reaction is the obligation's assertion, deliberately not duplicated here.
     */
    override suspend fun injectMembershipDrain(host: Seam, joiner: Seam): Boolean {
        val survivorMesh = hostLoom?.base ?: return false
        val (survivorSideConn, joinerConn) = link ?: return false
        check(host.state.value is SeamState.Woven && joiner.state.value is SeamState.Woven) {
            "membership-drain rig precondition: both seams must be live before the joiner departs, or " +
                "the obligation would pass on a departure this rig did not cause; got " +
                "host=${host.state.value}, joiner=${joiner.state.value}"
        }
        check(joiner.selfId in host.peers.value) {
            "membership-drain rig precondition: the survivor must already hold the counterpart, or " +
                "`peers.first { drained !in it }` is satisfied by the opening roster and asserts " +
                "nothing; got host.peers=${host.peers.value}, joiner=${joiner.selfId}"
        }

        val dispatcher = requireNotNull(currentCoroutineContext()[ContinuationInterceptor]) {
            "membership-drain rig: no dispatcher (ContinuationInterceptor) in coroutine context"
        }
        val (survivorEnd, bystanderEnd) = connectionPair()
        coroutineScope {
            // Concurrent: `addLink` suspends on the preamble exchange the bystander's own handshake
            // completes, exactly as the two construction-time weaves drive each other.
            val bystanderWeave = async { peerMesh(BYSTANDER, listOf(bystanderEnd), dispatcher) }
            survivorMesh.addLink(survivorEnd)
            bystanderWeave.await()
        }
        // Rig reach, checked against the survivor's OWN roster: with no second link the next step
        // drains the survivor to empty and latches Torn, which is the sibling obligation, not this one.
        if (BYSTANDER !in host.peers.value) return false

        // BOTH ends of the joiner's link, as [injectMidSessionDeath] does: closing one end is what the
        // OTHER end's read loop observes, so a single close would drain the survivor while leaving the
        // joiner sitting Woven on a half-open link — measured. A peer leaving closes its whole link.
        joinerConn.close()
        survivorSideConn.close()
        // Bounded, and on the DEPARTING side only: the survivor's reaction is the obligation's
        // assertion and is deliberately not awaited here. `close()` ends the joiner's read loop, but
        // the latch needs the virtual clock to run, so `.value` immediately after is still `Woven`.
        return withTimeoutOrNull(RIG_BOUND) { joiner.state.first { it is SeamState.Torn } } != null
    }

    /** Proven: this harness drains a peer without tearing the survivor, so no gap (#2623). */
    override fun membershipDrainDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    // overlay adds no crypto, inherits its base; dissemination is deliberate
    // multi-hop flood — not direct p2p; and the overlay wires no path observer (#1712).
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(
        securesTransport = false,
        meshDelivery = false,
        reportsLiveCapability = false,
    )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "meshDelivery" to CapabilityGaps.MESH_DELIVERY,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )

    /**
     * #2605: converted from [JoinerRosterOrigin.FilledByConstruction], and the receipt is in the PR
     * — with the joiner's woven base wrapped so its `peers` reports `{ selfId }` (the shape of the
     * `:kuilt-nearby` defect: transport up, counterparty never recorded), the JOINER arm of
     * [SeamConformanceSuite.peersReportsSelfIdAndAtLeastTwoAfterJoin] reds and the host arm does not.
     */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.TheJoinPath(
            "peerMesh's per-link handshake, read through GossipSeam.peers: the joiner's base mesh opens " +
            "holding only its own id and learns the host's PeerId from the handshake it ran itself, and " +
            "the overlay delegates peers straight to that base. Honest weakness: the handshake is a " +
            "PRECONDITION of peerMesh returning, so a base that stopped recording its peer would wedge " +
            "the weave rather than red this arm - what this harness newly proves is that the OVERLAY " +
            "does not manufacture a remote its base never had.",
        )

}

/**
 * A host/joiner [Loom] pair, each wrapping its own end of one in-memory [connectionPair] in a
 * started [GossipSeam] — the adapter that drives [GossipSeam] through the seam TCK.
 *
 * Two looms, not one returned twice: each role's roster must be its own, or the joiner is handed
 * the host rather than learning it (#2605). Background work runs on [TestScope.backgroundScope];
 * the virtual clock reads the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun gossipLoomPair(
    conns: Pair<Connection, Connection> = connectionPair(),
    testScope: TestScope?,
): Pair<GossipLoom, GossipLoom> {
    val (hostConn, joinerConn) = conns
    return GossipLoom(PeerId("host"), hostConn, seed = 0, testScope) to
        GossipLoom(PeerId("joiner"), joinerConn, seed = 1, testScope)
}

/**
 * Weaves a [peerMesh] over one [Connection] and wraps it in a started [GossipSeam].
 *
 * [Rendezvous] is ignored: this loom owns exactly one end of one link, so its role is fixed at
 * construction and `host()` and `join()` would build the same seam. Same shape as
 * `PeerMeshConformanceTest`'s loom. The suite still drives the two ends concurrently, which is what
 * the `peerMesh` handshake under each of them needs.
 *
 * @param seed this peer's view-recompute/selection RNG seed. Distinct per role so the two ends
 *   choose independently, exactly as [GossipSeam]'s `random` parameter requires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GossipLoom(
    private val self: PeerId,
    private val conn: Connection,
    private val seed: Int,
    private val testScope: TestScope?,
) : Loom {
    /**
     * The [Mesh] under the seam this loom last wove, published so
     * [GossipSeamConformanceTest.injectMembershipDrain] can grow the survivor to N-peer via
     * [Mesh.addLink]. `null` until [weave] has run.
     */
    var base: Mesh? = null
        private set

    /**
     * [FabricAvailability.Available] is a fact here, not a guess: everything under this loom is
     * in-process — a `peerMesh` over an in-memory `connectionPair` — so it opens no socket, acquires
     * no OS resource and reaches no remote. Nothing is left that could make a weave unattemptable.
     * Stated explicitly because the `Loom` default is the `Unknown` floor, and silently dropping to
     * it here would be a false negative, not caution.
     */
    override fun capability(): TransportCapability =
        TransportCapability(roles = emptySet(), availability = FabricAvailability.Available)

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val scope = testScope ?: error("GossipLoom.weave needs a TestScope — use newLoomPair(testScope)")
        val base = peerMesh(
            selfId = self,
            connections = listOf(conn),
            dispatcher = requireNotNull(currentCoroutineContext()[ContinuationInterceptor]) {
                "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
            },
        )
        this.base = base
        val seam =
            GossipSeam(
                base = base,
                random = Random(seed),
                clock = { Instant.fromEpochMilliseconds(scope.testScheduler.currentTime) },
                jitter = ZERO..ZERO,
            )
        seam.start(scope.backgroundScope)

        // Return only once THIS overlay has converged: every peer the base handshake admitted must
        // be in this seam's active view. At the 2-peer conformance scale that is "this peer sees the
        // other". With jitter = ZERO the recompute is synchronous, so the `first { }` returns as soon
        // as virtual time lets the roster watcher observe the membership the handshake produced.
        // Awaiting only this seam is enough — the suite awaits BOTH weaves before running a test, so
        // the sibling's own convergence is a precondition of the test body too, and iterating a
        // shared list across a suspension point would race the sibling's append.
        seam.activePeers.first { active -> (seam.peers.value - seam.selfId).all { it in active } }
        return seam
    }
}
