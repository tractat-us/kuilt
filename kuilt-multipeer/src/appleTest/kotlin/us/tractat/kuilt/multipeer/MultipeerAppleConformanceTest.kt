package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import platform.MultipeerConnectivity.MCPeerID
import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.multipeer.internal.MCSessionLink

/**
 * Verifies the Apple `MCSessionLink` satisfies the shared [SeamConformanceSuite].
 *
 * ## Why this harness exists (#2441)
 *
 * `MultipeerConformanceTest` is a `jvmTest`, so it resolves the `jvmMain` `BridgePeerLink` and has
 * never touched the link that actually ships to iPhones. Everything holding `MCSessionLink` to the
 * seam contract was inspection plus two single-behaviour `appleTest`s
 * (`MCSessionLinkTearCollapseTest`, `MultipeerPeerLinkFactoryTerminalDropTest`, #2430/#2435).
 * `docs/seam-harness-coverage.md` recorded it as "the real Apple transport is unharnessed"; this
 * is the first conformance coverage it has had.
 *
 * The first run found two obligations it fails, and both are the same story: a hardening fix that
 * landed on the JVM half of this fabric and never crossed to the Apple half, because no harness
 * could see it (see [capabilities] and the self-dial note below).
 *
 * ## Shape of the harness
 *
 * **Role-split: two distinct Looms.** MultipeerConnectivity is architecturally role-split — one
 * device advertises and auto-accepts, another browses and invites — and one `MCSession` is one
 * device's view, so an in-process `loom to loom` would have to hand back the same link twice and
 * could not model a second peer at all. Each Loom here mints its own `MCPeerID` and its own
 * `MCSessionLink`, wired to the other through one [FakeMCSessionBus]. This mirrors
 * `MultipeerConformanceTest`'s two-factory shape, one layer lower: the JVM harness fakes the JNA
 * boundary, this one fakes the `MCSession` itself, which is the only injection point `appleMain`
 * has.
 *
 * The joiner's weave awaits the host's, then completes the virtual handshake — so the ordering
 * does not depend on how [SeamConformanceSuite.connectedPair]'s two `async` bodies interleave.
 *
 * ## Honest limit
 *
 * [FakeMCSessionBus] calls the link's delegate **inline**, where real MC fires it on the
 * framework's private queue. So this proves the link's logic, not its behaviour under a genuinely
 * concurrent delegate — the same limit the JVM delivering fake has, and the price of running
 * under `runTest`'s virtual clock at all.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
class MultipeerAppleConformanceTest : SeamConformanceSuite() {

    /** Retained so [injectMidSessionDeath] can drop the transport under a live pair. */
    private var pair: MCSessionLinkLoomPair? = null

    override fun newLoomPair(): Pair<Loom, Loom> =
        MCSessionLinkLoomPair(testScope = null).let { it.hostLoom to it.joinerLoom }

    override fun newLoomPair(testScope: TestScope): Pair<Loom, Loom> =
        MCSessionLinkLoomPair(testScope).also { pair = it }.let { it.hostLoom to it.joinerLoom }

    /**
     * `securesTransport = false`: a **harness** declaration, not a fabric one. Real MC sessions are
     * built `MCEncryptionRequired` and the production link inherits that, but this suite runs over
     * [FakeMCSessionBus], which is an in-process route with nothing on a wire — and the flag's own
     * KDoc says the only thing that can hold a fabric to it is a harness running the suite over a
     * genuinely encrypted link, so the whole suite passing *is* the evidence. It cannot be here.
     * (Precedent: `NwConformanceTest` declares `false` for a fabric whose real transport is
     * TLS-PSK, because the radio under its harness is a plaintext fake. The sibling
     * `MultipeerConformanceTest` declares `true` over an equally plaintext JVM fake; that predates
     * #2304's tightening of what this flag means and is worth revisiting.)
     *
     * `reportsLiveCapability = false`: `MCSessionLink` overrides no
     * [us.tractat.kuilt.core.Seam.capability], so it sits on the honest
     * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor (#1712/#1542).
     *
     * `throwsOnSendToTorn = false` is a **fabric** gap and a real one, found by this binding: a
     * torn `MCSessionLink` warn-drops a `broadcast` and reports `PeerNotConnected` for an addressed
     * send, where `BridgePeerLink` has opened both sends with `check(state !is Torn)` since #1390.
     * The Apple half never got that fix. Tracked by #2444, kept out of this PR because turning a
     * warn-drop into a throw on a shipping transport wants its own review.
     *
     * `meshDelivery = true` is the fabric's own topology — MC is a true N≤8 peer-to-peer mesh with
     * no relay hop, the same claim `MultipeerConformanceTest` makes. Evidence: the fabric's
     * topology, plus the fact that this bus is 2-endpoint, so the claim is **vacuous under this
     * harness** and rests on the fabric rather than on anything asserted here. A
     * `MeshConformanceSuite` subclass stays deprioritised while `:kuilt-nw` is slated to replace
     * this module (#1403).
     *
     * `collapsesPeersOnTear = true` is a real pass rather than an untested default: it is the
     * #1851 fix, and until now `MCSessionLinkTearCollapseTest` was the only thing asserting it.
     */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(
        securesTransport = false,
        reportsLiveCapability = false,
        throwsOnSendToTorn = false,
    )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
        "throwsOnSendToTorn" to "https://github.com/tractat-us/kuilt/issues/2444",
    )

    /**
     * Kill the transport under both ends with no `close()` anywhere: the bus simply tells each link
     * its remote went `MCSessionStateNotConnected`, which is how a real `MCSession` dies when the
     * radio drops rather than when the application asks. Both links then reach their last-peer
     * self-drop path, latch [us.tractat.kuilt.core.SeamState.Torn] and complete `incoming` — the
     * remote-disconnect half of the `incoming`-completes-on-`Torn` contract, which no `appleTest`
     * covered before.
     */
    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean =
        pair?.bus?.dropTransport() ?: false

    /** Proven: this harness kills the transport under a live session, so no gap. */
    override fun midSessionDeathGap(): String? = null

    /**
     * **Not** the usual harness gap. [FakeMCSessionBus] *can* deliver the host a connection
     * resolving to its own `MCPeerID`, and when it did, the obligation reded on the
     * live-self-loopback arm:
     *
     * ```
     * a rejected self-dial must not register a self-link: the host's own broadcast must never
     * loop back to it attributed to selfId. Expected value to be null, but was:
     *   <Swatch(payloadSize=2, sender=PeerId(value=conformance-host#aaaaaaaa), sequence=0)>
     * ```
     *
     * `MCSessionLink`'s `didChangeState` has no `if (peerId == selfId) return`, the line
     * `BridgePeerLink.peerStateCallback` has carried since #1494 — and on this fabric that line
     * alone would not close it, because the send paths read `session.connectedPeers` directly
     * rather than the roster the delegate maintains. Both halves are needed and neither is
     * provable alone, which is why #2445 owns the fix instead of this PR, and why the injector
     * itself lives in that issue rather than as an uncalled method here.
     *
     * So the URL below is a **fabric** gap, not a harness one: this harness reaches the state and
     * the link fails it.
     */
    override fun selfDialGap(): String = "https://github.com/tractat-us/kuilt/issues/2445"
}

/**
 * A test-local [Loom] pair binding `MCSessionLink` to [SeamConformanceSuite] over one
 * [FakeMCSessionBus].
 *
 * [hostLoom] mints the host's `MCPeerID`, session and link; [joinerLoom] awaits that, mints its
 * own, and then completes the virtual MC handshake so both ends reach
 * [us.tractat.kuilt.core.SeamState.Woven] before the suite's `connectedPair` hands them to a test.
 *
 * The display names carry a nonce suffix (`name#hex`) because that is what a real
 * `MultipeerPeerLinkFactory` advertises — `MultipeerPeerId.decorate` bakes a per-device nonce into
 * the name before the `MCPeerID` exists, and the wire [us.tractat.kuilt.core.PeerId] is the whole
 * decorated string. Using undecorated names here would test an identity shape production never
 * produces.
 *
 * @param testScope owns the links' delivery-drain dispatcher. `null` is legal for the scope-free
 *   `newLoomPair()` used by the suite's `availability()` obligation, which never weaves.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
internal class MCSessionLinkLoomPair(private val testScope: TestScope?) {

    val bus: FakeMCSessionBus = FakeMCSessionBus()

    private val hostPeer = MCPeerID(displayName = HOST)
    private val joinerPeer = MCPeerID(displayName = JOINER)

    /** Released once the host link exists, so the joiner's weave can complete the handshake. */
    private val hostWoven = CompletableDeferred<Unit>()

    val hostLoom: Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            link(hostPeer).also { hostWoven.complete(Unit) }
    }

    val joinerLoom: Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            hostWoven.await()
            return link(joinerPeer).also { bus.connect() }
        }
    }

    /**
     * Build one link over a bus endpoint and install its delegate, exactly as
     * `MultipeerPeerLinkFactory.openSession`/`joinSession` do with a real `MCSession`.
     *
     * The drain dispatcher is an [UnconfinedTestDispatcher] over the test scheduler: the link owns
     * its own `SupervisorJob` scope for the delegate→spool drain coroutine, and the production
     * default is `Dispatchers.Default`, which would run that drain off the virtual clock.
     */
    private fun link(peer: MCPeerID): MCSessionLink {
        val scope = requireNotNull(testScope) {
            "MCSessionLinkLoomPair.weave needs a TestScope — use newLoomPair(testScope)"
        }
        val session = bus.session(peer)
        val link = MCSessionLink(
            localPeerId = peer,
            session = session,
            dispatcher = UnconfinedTestDispatcher(scope.testScheduler),
        )
        session.delegate = link.delegate
        return link
    }

    private companion object {
        const val HOST = "conformance-host#aaaaaaaa"
        const val JOINER = "conformance-joiner#bbbbbbbb"
    }
}
