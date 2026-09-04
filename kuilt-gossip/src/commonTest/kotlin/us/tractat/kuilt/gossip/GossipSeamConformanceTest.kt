package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.Duration.Companion.ZERO
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
    /**
     * The two ends of the in-memory link under the current pair, held so [injectMidSessionDeath] can
     * drop the transport out from under a live session. Tests run one pair at a time, sequentially —
     * the same capture `PeerMeshConformanceTest` makes.
     */
    private var link: Pair<Connection, Connection>? = null

    // availability() never weaves, so a scope-free pair is enough for that one test.
    override fun newLoomPair(): Pair<Loom, Loom> = newPair(testScope = null)

    override fun newLoomPair(testScope: TestScope): Pair<Loom, Loom> = newPair(testScope)

    private fun newPair(testScope: TestScope?): Pair<Loom, Loom> {
        val conns = connectionPair()
        link = conns
        return gossipLoomPair(conns, testScope)
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

    /** Proven: this harness drops the transport under a live pair, so no gap. */
    override fun midSessionDeathGap(): String? = null

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

    /**
     * **Not a gap — the event is not constructible here (#2568).** One [GossipLoom] over one shared
     * [InMemoryLoom] base plays both roles, so there is no 2-peer transport under the pair to drop.
     * A peer going away shrinks the base loom's shared roster — which [GossipSeam.peers] delegates
     * straight to — and leaves the survivor [us.tractat.kuilt.core.SeamState.Woven]. The in-memory
     * transport-death path is covered by `PeerMeshConformanceTest`, which holds both ends of a real
     * 2-peer link.
     *
     * This harness does **not** yet prove the sibling membership-drain obligation — that stays a
     * tracked gap under the default — so the departure event is asserted on here only by
     * [SeamConformanceSuite.midSessionDeathDeclarationIsHonest]'s refutation.
     */
    override fun midSessionDeathDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "one GossipLoom over one shared InMemoryLoom base plays both roles, so no 2-peer " +
                "transport exists under the pair to drop; a peer leaving shrinks the base loom's " +
                "shared roster (which GossipSeam.peers delegates to) and leaves the survivor Woven",
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
): Pair<Loom, Loom> {
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
