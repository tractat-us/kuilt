package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability

/**
 * Verifies the `ResumableChannel` that `MuxClientLoom.weave` returns — and, one hop under it, the
 * `MuxBase.ChannelView` it delegates to — satisfies the shared [SeamConformanceSuite].
 *
 * ## Why this harness exists (#2372, and the #1871 shape again)
 *
 * A channel view had been reachable from this suite since #1937, but only ever as the **joiner**:
 * [MuxServerLoomConformanceTest] hands one back as `.second`, and every core close obligation closes
 * the **host**. So `close()` was never called on a channel view *and then looked at*, and the view
 * went on reporting the base's `Woven` and the base's roster forever — an ungated-core violation
 * (`closeDrivesStateTornNormal`) that a coverage table listing `ChannelView` as bound reported as
 * covered. Same shape as #1869/#1871: the matrix enumerates harnesses, not positions.
 *
 * This harness puts a channel view on **both** ends, so every host-side obligation lands on one.
 * `docs/seam-harness-coverage.md`'s `ResumableChannel` row was `none` for the same reason — #2441
 * built this harness, measured it red on six rows, and left it unlanded because no capability flag
 * may excuse an ungated obligation. #2372's fix is what makes it landable.
 *
 * ## Shape of the harness
 *
 * Two [MuxClientLoom]s over **one** shared [InMemoryLoom], both mapping every rendezvous onto a
 * single channel name — so the two handles are the two ends of one logical session, riding two base
 * seams on one in-memory mesh.
 *
 * [MuxClientLoom] construction is deferred to the first `weave` because its pump scope is a required
 * constructor parameter and the suite's scope-free [newLoomPair] has none — the same device
 * `TieredLoomPair` and `RoomHubLoomPair` use. The pumps run on an [UnconfinedTestDispatcher] so a
 * roster or lifecycle change is visible to the suite's synchronous `.value` reads without an explicit
 * advance, for the reason [TieredSeamConformanceTest] gives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MuxClientLoomConformanceTest : SeamConformanceSuite() {

    /** Retained so the departure hooks can reach the joiner's base rather than its channel view. */
    private var pair: MuxClientLoomPair? = null

    override fun newLoomPair(): Pair<Loom, Loom> =
        MuxClientLoomPair(testScope = null).let { it.hostLoom to it.joinerLoom }

    override fun newLoomPair(testScope: TestScope): Pair<Loom, Loom> =
        MuxClientLoomPair(testScope).also { pair = it }.let { it.hostLoom to it.joinerLoom }

    /**
     * `securesTransport = false`: the base is an in-process mesh — nothing is on a wire.
     *
     * `reportsLiveCapability = false`: a channel view forwards its base's live verdict verbatim
     * (#1546), and [InMemoryLoom] wires no OS path observer, so what is forwarded is the honest
     * `Unknown` floor. The flag describes this harness's *base*, not the mux — a mux over an
     * observing fabric reports that fabric's verdict.
     *
     * `meshDelivery` stays `true`, inherited from [SeamCapabilities.FULL]. Multiplexing adds no hop:
     * a frame leaves this view, is tagged, and is carried by the base mesh directly to the peer that
     * holds the same channel name. So the claim reduces to [InMemoryLoom]'s, which
     * [InMemoryLoomMeshConformanceTest] proves against the shared `MeshConformanceSuite`.
     *
     * `reportsPeerLoss = false` is the one real shortfall, and it is **not** #2372's: a closed view
     * now latches its own `Torn` and collapses its own roster, so the obligation's precondition
     * passes. What it cannot do is make the *survivor* notice — a per-channel close has no wire
     * representation, so the peer on the other end of the channel keeps advertising a view it can no
     * longer deliver to. Measured on [MuxServerLoomConformanceTest] (30 of 31 green, this obligation
     * wedging), tracked as #2665, and the same defect here for the same reason.
     */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(
        securesTransport = false,
        reportsLiveCapability = false,
        reportsPeerLoss = false,
    )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
        // A *fabric's own* blocking issue at the declaration site, which is what `CapabilityGaps`'
        // KDoc asks for; the shared constants stay docs anchors.
        "reportsPeerLoss" to "https://github.com/tractat-us/kuilt/issues/2665",
    )

    /** #2591: one shared [InMemoryLoom] registry fills both ends, so the joiner arm cannot fail here. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.FilledByConstruction(
            "a shared roster: both MuxClientLooms weave their base seam on ONE InMemoryLoom, whose registry " +
                "every seam observes, so weaving the joiner's base fills both ends. The channel view mirrors " +
                "its base's roster, so what the joiner arm reads here is InMemoryLoom's shared registry, not " +
                "anything the mux did.",
        )

    /**
     * Drain the joiner from the shared roster by closing its **base**, not the channel view this
     * harness hands back.
     *
     * That distinction is the whole of #2665 and the reason this override exists: closing the view
     * ends the view (its own `Torn`, its own collapsed roster) and touches nothing the host can see,
     * so under the default hook the drain would never land and
     * [SeamConformanceSuite.peersDrainWithoutTearOnInjectedMembershipDrain] would be green by absence.
     * Closing the base removes that peer from the [InMemoryLoom] registry the host's own view mirrors,
     * so the host watches its roster shrink while its state stays
     * [us.tractat.kuilt.core.SeamState.Woven] — a genuine drain rather than a tear.
     */
    override suspend fun injectMembershipDrain(host: Seam, joiner: Seam): Boolean {
        pair?.closeJoinerBase() ?: return false
        return true
    }

    /** Proven: this harness drains a peer without tearing the survivor, so no gap. */
    override fun membershipDrainDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * Depart the joiner by closing its **base**, for the reason [injectMembershipDrain] gives — the
     * stimulus [midSessionDeathDeclaration]'s reason actually names.
     *
     * Without this override the refutation is vacuous here: the default `joiner.close()` ends only the
     * channel view, the host stays Woven because *nothing reached it*, and the no-tear conclusion
     * would be green by absence rather than by topology (#2568's finding, #2665's mechanism).
     */
    override suspend fun departCounterpart(host: Seam, joiner: Seam): Boolean {
        pair?.closeJoinerBase() ?: return false
        return true
    }

    /**
     * **Not a gap — the event is not constructible here (#2568).** One shared [InMemoryLoom] carries
     * both base seams, so there is no 2-peer transport under the pair to drop out from under it. A
     * peer going away shrinks that loom's shared roster and leaves the survivor
     * [us.tractat.kuilt.core.SeamState.Woven] — the *distinct* [injectMembershipDrain] event this
     * harness proves directly above. The in-memory transport-death path is covered by
     * [PeerMeshConformanceTest], which holds both ends of a real 2-peer link.
     *
     * [midSessionDeathDeclarationIsHonest] refutes that claim's cheap failure mode rather than
     * believing it; [ObligationDeclaration] states what the arm still cannot detect.
     */
    override fun midSessionDeathDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "one shared InMemoryLoom carries both base seams, so no 2-peer transport exists under the " +
                "pair to drop; a peer leaving shrinks the loom's shared roster and leaves the survivor " +
                "Woven, which is the distinct membership drain this harness proves instead",
        )

    private companion object {
        /** Every rendezvous maps here, so host and joiner are the two ends of one logical session. */
        const val CHANNEL = "conformance"
    }
}

/**
 * A test-local [Loom] pair binding [MuxClientLoom]'s resumable channel handles to
 * [SeamConformanceSuite].
 *
 * Both looms weave their single base seam on the shared [mesh] and serve one channel name over it, so
 * `hostLoom.host(…)` and `joinerLoom.join(…)` land on the two ends of one logical session.
 *
 * @param testScope owns the per-generation mux collectors and the two channel-view lifecycle pumps.
 *   `null` is legal for the scope-free `newLoomPair()` used by the suite's `availability()`
 *   obligation, which never weaves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MuxClientLoomPair(private val testScope: TestScope?) {

    /** The one in-process mesh both bases live on. */
    private val mesh = InMemoryLoom()

    private val host = LazyMuxLoom(Rendezvous.New(Pattern(BASE)))
    private val joiner = LazyMuxLoom(Rendezvous.Existing(InMemoryTag(BASE)))

    val hostLoom: Loom get() = host
    val joinerLoom: Loom get() = joiner

    /**
     * Tear the joiner's **base** out from under its channel view — the departure stimulus the
     * conformance harness's declarations name. Returns `null` if the joiner never wove, so a hook can
     * report that it did not perform the injection rather than claiming one it did not make.
     */
    suspend fun closeJoinerBase(): Unit? = joiner.woven()?.closeBase()

    /**
     * Defers [MuxClientLoom] construction to the first `weave`: the loom takes its pump scope as a
     * required constructor parameter, and the suite's scope-free `newLoomPair()` has none. Every
     * rendezvous maps to one channel name, so host and join resolve to the same logical session.
     */
    private inner class LazyMuxLoom(private val baseRendezvous: Rendezvous) : Loom {
        private var delegate: MuxClientLoom? = null

        override suspend fun weave(rendezvous: Rendezvous): Seam = loom().weave(rendezvous)

        /** The base's verdict, verbatim — what [MuxClientLoom.capability] itself forwards. */
        override fun capability(): TransportCapability = mesh.capability()

        /** The constructed loom, or `null` if nothing has been woven through this end yet. */
        fun woven(): MuxClientLoom? = delegate

        private fun loom(): MuxClientLoom = delegate ?: MuxClientLoom(
            base = mesh,
            baseRendezvous = baseRendezvous,
            // `backgroundScope`'s job so the mux's perpetual collectors cancel at teardown rather
            // than blocking `runTest`; an `UnconfinedTestDispatcher` so a roster or lifecycle change
            // is settled by the time the suite reads `.value`.
            scope = CoroutineScope(
                pumpScope().coroutineContext + UnconfinedTestDispatcher(requireScope().testScheduler),
            ),
            nameOf = { CHANNEL },
        ).also { delegate = it }
    }

    private fun pumpScope(): CoroutineScope = requireScope().backgroundScope

    private fun requireScope(): TestScope = requireNotNull(testScope) {
        "MuxClientLoomPair.weave needs a TestScope — use newLoomPair(testScope)"
    }

    private companion object {
        /** Cosmetic: [InMemoryLoom] is one shared registry and ignores the session name. */
        const val BASE = "mux-base"

        /** Must match `MuxClientLoomConformanceTest`'s channel name. */
        const val CHANNEL = "conformance"
    }
}
