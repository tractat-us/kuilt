package us.tractat.kuilt.conformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.tieredSeam

/**
 * Verifies the `TieredSeam` produced by [tieredSeam] satisfies the shared [SeamConformanceSuite].
 *
 * ## Why this harness exists (#1871)
 *
 * [SeamConformanceSuite] drives seams **through a [Loom]**, so its coverage is really
 * *"seams reachable from a Loom that has a bound harness"* — not *"seams that exist."*
 * `TieredSeam` is produced by a composition **function**, not a [Loom], so it fell outside
 * that set and shipped a `peers`-collapse bug (#1869) that a 6/6-correct blast-radius matrix
 * reported as fully covered. [TieredLoomPair] is the missing adapter: a test-local [Loom] over
 * two [InMemoryLoom]s that makes `tieredSeam(...)` reachable from [newLoomPair].
 *
 * ## Shape of the harness
 *
 * The pair is **role-split**, mirroring production. `TieredSeam`'s motivating case is a
 * federated per-game seam on a *server*: the local tier is that server's room, the peer tier is
 * the core mesh of *other servers*. The members of that room are ordinary client seams — they
 * are not themselves tiered. So the host Loom returns the tiered union and the joiner Loom
 * returns a plain member of its local tier.
 *
 * This also keeps the two tiers' rosters **disjoint**, which the primitive assumes. Both tiers
 * are [InMemoryLoom]s that mint `peer-N` ids from their own counters, so a joiner present on
 * *both* tiers would be delivered every broadcast twice (the union tees to both) — an invalid
 * harness rather than a finding. `:kuilt-core`'s `TieredSeamTest` uses exactly this fixture shape
 * (host on each loom so `selfId` agrees, then burn the peer loom's counter so the peer-tier
 * member's id is disjoint); this harness reuses it.
 *
 * [UnconfinedTestDispatcher] backs the union/state/incoming pumps so they run eagerly, letting
 * the suite's synchronous `peers.value` / `state.value` assertions observe the settled union —
 * the same reason [CompositeConformanceTest] injects one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TieredSeamConformanceTest : SeamConformanceSuite() {

    override fun newLoomPair(): Pair<Loom, Loom> =
        TieredLoomPair(testScope = null).let { it.hostLoom to it.joinerLoom }

    override fun newLoomPair(testScope: TestScope): Pair<Loom, Loom> =
        TieredLoomPair(testScope).let { it.hostLoom to it.joinerLoom }

    /**
     * `securesTransport = false`: both tiers are in-memory meshes — nothing is on a wire.
     *
     * `meshDelivery = false`: a tiered union is **not** a mesh. It bonds two *disjoint* transports
     * into one per-node view: a frame arriving from a local-tier member is merged into this seam's
     * `incoming`, never re-flooded to the peer tier, so two members in different tiers cannot reach
     * each other through this seam. (It is not a *relay* either — the union adds no hop of its own —
     * but the flag asks whether every peer in `peers` is directly reachable from every other, and
     * across a tier boundary they are not.) By design, not a defect.
     *
     * `reportsLiveCapability = false`: the union wires no OS path observer, so it inherits the
     * honest `Unknown` floor — the same position [CompositeConformanceTest] is in (#1712/#1545).
     *
     * `collapsesPeersOnTear = true` is the obligation this whole harness exists to pin: it was
     * `false` in fact (not in declaration — nothing declared it at all) until #1869.
     */
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

    /** #2591: this fixture fills the joiner's roster itself, so the joiner arm cannot fail here. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.FilledByConstruction(
            "a shared roster: the joiner side is a plain InMemoryLoom member weaving on the SAME roomLoom the " +
            "host's tiered union folds, so the joiner arm asserts a property of InMemoryLoom's shared registry " +
            "rather than of TieredSeam.",
        )

    /**
     * Drain the joiner from the local tier: closing the joiner's [InMemoryLoom] seam removes it
     * from that loom's shared roster, so the union pump republishes a smaller `peers` while the
     * host's own local-tier seam — and therefore the union's rolled-up `state` — stays
     * [us.tractat.kuilt.core.SeamState.Woven]. That is a membership drain, distinct from a
     * transport tear (where both tiers would latch `Torn`).
     */
    override suspend fun injectMembershipDrain(host: Seam, joiner: Seam): Boolean {
        joiner.close()
        return true
    }

    /** Proven: this harness drains a peer without tearing the survivor, so no gap. */
    override fun membershipDrainDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * **Not a gap — the event is not constructible here (#2568).** Both tiers are in-process meshes
     * over shared looms, so there is no 2-peer transport under the pair to drop. A peer going away
     * leaves the union's rolled-up state [us.tractat.kuilt.core.SeamState.Woven] — the *distinct*
     * [injectMembershipDrain] event this harness proves directly above. The in-memory
     * transport-death path is covered by [PeerMeshConformanceTest].
     *
     * [midSessionDeathDeclarationIsHonest] refutes that claim's cheap failure mode rather than
     * believing it; [ObligationDeclaration] states what the arm still cannot detect.
     */
    override fun midSessionDeathDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "both tiers are in-process meshes over shared looms, so no 2-peer transport exists " +
                "under the pair to drop; a peer leaving shrinks a tier's roster and leaves the " +
                "union's rolled-up state Woven, which is the distinct membership drain this " +
                "harness proves instead",
        )
}

/**
 * A test-local [Loom] pair that makes [tieredSeam] reachable from [SeamConformanceSuite].
 *
 * [hostLoom] weaves the seam under test: a tiered union whose **local tier** is a seam on the
 * shared [roomLoom] (where the suite's joiner also lands) and whose **peer tier** is a seam on a
 * separate [coreLoom] carrying one sibling "server". [joinerLoom] weaves a plain member of
 * [roomLoom].
 *
 * ## Identity and ordering
 *
 * `tieredSeam` requires both tiers to be the same node (`local.selfId == peer.selfId`), and
 * [InMemoryLoom] mints its own `peer-N` ids from a private counter. The tiers therefore agree
 * only if the host is the **first** weave on each loom. That is guaranteed here rather than
 * assumed: [joinerLoom] awaits [hostWoven] before touching [roomLoom], so the ordering does not
 * depend on how `connectedPair`'s two `async` bodies interleave.
 *
 * @param testScope owns the union/state/incoming pumps. `null` is legal for the scope-free
 *   `newLoomPair()` used by the suite's `availability()` obligation, which never weaves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TieredLoomPair(private val testScope: TestScope?) {

    /** The local tier: the "room" both the node under test and the suite's joiner live on. */
    private val roomLoom = InMemoryLoom()

    /** The peer tier: the "other servers" mesh, disjoint from [roomLoom]. */
    private val coreLoom = InMemoryLoom()

    /** Released once the host has taken its `peer-1` id on [roomLoom]; gates the joiner. */
    private val hostWoven = CompletableDeferred<Unit>()

    val hostLoom: Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val scope = pumpScope()
            // FIRST weave on each loom ⇒ both mint `peer-1` ⇒ the two tiers are the same node.
            val local = roomLoom.weave(rendezvous)
            val peer = coreLoom.host(Pattern("core-mesh"))
            // Burn the core loom's counter so the sibling's id cannot collide with the room
            // member's — the disjointness `tieredSeam` assumes. Same device as TieredSeamTest.
            coreLoom.join(InMemoryTag("burn-1")).close()
            coreLoom.join(InMemoryTag("burn-2")).close()
            // Left open on purpose: the peer tier must carry a real remote, or the union roster is
            // just `{ selfId }` and every union assertion reduces to the local tier's.
            coreLoom.join(InMemoryTag("sibling-server"))
            hostWoven.complete(Unit)
            return tieredSeam(local = local, peer = peer, scope = scope)
        }
    }

    val joinerLoom: Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            hostWoven.await()
            return roomLoom.weave(rendezvous)
        }
    }

    /**
     * The scope the union's pumps run on: [TestScope.backgroundScope]'s job (so the perpetual
     * combine/merge collectors cancel at teardown rather than blocking `runTest`) with an
     * [UnconfinedTestDispatcher] (so a roster or state change is visible to the suite's
     * synchronous `.value` reads without an explicit advance).
     */
    private fun pumpScope(): CoroutineScope {
        val scope = requireNotNull(testScope) {
            "TieredLoomPair.weave needs a TestScope — use newLoomPair(testScope)"
        }
        return CoroutineScope(
            scope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(scope.testScheduler),
        )
    }
}
