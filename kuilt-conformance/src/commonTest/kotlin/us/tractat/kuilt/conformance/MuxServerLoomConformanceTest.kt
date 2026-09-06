package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random

/**
 * Verifies the `RoomHubSeam` that `MuxServerLoom.host` returns satisfies the shared
 * [SeamConformanceSuite].
 *
 * ## Why this harness exists (#1871)
 *
 * `MuxServerLoom` **is** a [Loom] and `:kuilt-conformance` already wires it — for a *different*
 * suite ([MuxServerLoomFanoutIsolationTest]). What it never had was a [SeamConformanceSuite]
 * subclass, so the `RoomHubSeam` behind `host()` was never driven through the seam contract at
 * all. It shipped a `peers`-collapse bug (#1869) that #1859's blast-radius matrix reported as
 * fully covered, because the matrix enumerates *harnesses*, not *seams*. This binds the missing
 * one.
 *
 * ## Shape of the harness
 *
 * Role-split, because `MuxServerLoom` is server-only (`join` throws): `.first` is the server
 * loom whose `host(Pattern("host"))` returns the room hub under test; `.second` is a client
 * loom that dials a fresh in-memory connection, muxes the room's channel over it, and returns
 * that channel view.
 *
 * **The joiner announces itself.** A hub admits a connection on its *first frame* on that room's
 * channel (authorization then registration), so a client that merely connects is not yet a
 * member and the hub's roster would still be `{ selfId }`. The client loom therefore sends one
 * empty frame and awaits its own registration before returning, exactly as a real client does
 * and exactly as [RoomFanoutIsolationConformanceSuite] drives it. Waiting on the hub's observable
 * roster (rather than advancing virtual time) keeps `join()` deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MuxServerLoomConformanceTest : SeamConformanceSuite() {

    /** Retained so [injectMembershipDrain] can tear the client's transport out from under the hub. */
    private var pair: RoomHubLoomPair? = null

    override fun newLoomPair(): Pair<Loom, Loom> =
        RoomHubLoomPair(testScope = null).let { it.serverLoom to it.clientLoom }

    override fun newLoomPair(testScope: TestScope): Pair<Loom, Loom> =
        RoomHubLoomPair(testScope).also { pair = it }.let { it.serverLoom to it.clientLoom }

    /**
     * The joiner must route to the room the suite hosts, and a client picks its room by the
     * rendezvous **session name** — so this has to be `Pattern("host")`'s name, not the default
     * `"joiner"`.
     */
    override fun joinTag(): Tag = InMemoryTag(ROOM)

    /**
     * `securesTransport = false`: the harness runs over in-process connections — nothing is on a
     * wire. (The production `MuxServerLoom` inherits whatever its `ConnectionSource` provides.)
     *
     * `meshDelivery = false`: a room hub is a server-centred **star**. The hub is an endpoint for
     * each spoke, but spoke-to-spoke traffic is relayed by the hub — there is no direct
     * peer-to-peer path between two members of a room.
     *
     * `reportsLiveCapability = false`: `RoomHubSeam` wires no OS path observer, so it reports the
     * honest `Unknown` floor rather than a fabricated verdict (#1712). The flag stays `false`
     * because of *this harness's base*, not because of the mux: since #1546 a `NamedMux` channel
     * view forwards its base seam's live capability verbatim, so a mux over an observing fabric
     * reports that fabric's verdict. A mux over `RoomHubSeam` has no verdict to forward, and
     * publishing `true` here would subscribe the harness to obligations its base cannot satisfy.
     *
     * `collapsesPeersOnTear = true` is the obligation this harness exists to pin — it was untrue
     * of `RoomHubSeam` until #1869, and nothing in the suite could see it.
     *
     * `reportsPeerLoss = false` and it understates the fabric — `RoomHubSeam` reports peer loss
     * correctly, and this very harness proves it: its [injectMembershipDrain] closes the client's
     * underlying connection and [SeamConformanceSuite.peersDrainWithoutTearOnInjectedMembershipDrain]
     * then observes the hub deregister the peer while staying Woven. What cannot be shown is
     * [SeamConformanceSuite.survivorStopsAdvertisingADepartedPeer], because that obligation departs by
     * closing the **joiner seam this harness hands back**, which is a `NamedMux` *channel view*.
     *
     * **The reason changed under #2372 and the flag did not, which is the interesting part.** It used
     * to be that a closed view never reached [us.tractat.kuilt.core.SeamState.Torn] at all, so the
     * obligation's Torn precondition fired and named the cause. #2372 fixed that: a closed view now
     * latches its own `Torn` and collapses its own roster. Dropping the flag was then measured, and
     * the obligation **wedges** — 30 of 31 green, `survivorStopsAdvertisingADepartedPeer` running to
     * `runTest`'s ceiling. The precondition passes and the obligation itself never becomes true,
     * because a per-channel close has **no wire representation**: nothing is emitted, the hub
     * deregisters a spoke only when the underlying link tears, and so it is *correctly* still
     * advertising a client that told it nothing. That residue is #2665, and this gap points there
     * rather than at #2372, whose half is done.
     *
     * (Precedent for a flag describing the *harness* rather than the fabric: `NwConformanceTest`
     * declares `securesTransport = false` for a fabric whose real transport is TLS-PSK, because the
     * radio under its harness is a plaintext fake.)
     */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(
        securesTransport = false,
        meshDelivery = false,
        reportsLiveCapability = false,
        reportsPeerLoss = false,
    )

    override fun capabilityGaps(): Map<String, String> = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "meshDelivery" to CapabilityGaps.MESH_DELIVERY,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
        // A *fabric's own* blocking issue at the declaration site, which is what
        // `CapabilityGaps`' KDoc asks for; the shared constants stay docs anchors.
        "reportsPeerLoss" to "https://github.com/tractat-us/kuilt/issues/2665",
    )

    /**
     * One: [RoomHubLoomPair]'s client announces itself with `channel.broadcast(byteArrayOf())`
     * because a hub admits a connection on its **first frame** for a room. That empty frame is a
     * real delivery and it is sitting at the head of the hub's `incoming` before any test body
     * starts — so #2601's joiner→host delivery rows would otherwise read it as the joiner's payload
     * and red with `Got []` on a fabric doing exactly what it should.
     *
     * The count is self-checking in both directions ([joinHandshakeFramesAtHost]): raise it and the
     * rows eat the frame the test sent, lower it and they read the announce frame again.
     */
    override fun joinHandshakeFramesAtHost(): Int = 1

    /** #2591: the joiner starts at `{ selfId }` and grows only through the join path. */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.TheJoinPath(
            "the mux hub's mesh Hello: the client seam's roster opens without the host and is grown by the " +
            "handshake the client itself performed over the shared in-memory room fabric.",
        )

    /**
     * Drain the client from the hub without tearing the hub: closing the client's **underlying
     * connection seam** (not the channel view) completes the server's per-connection read loop,
     * whose teardown deregisters the peer from every room it joined. The hub keeps every other
     * spoke and stays [us.tractat.kuilt.core.SeamState.Woven] — a hub cannot die of one link — so
     * this is a genuine membership drain rather than a transport tear.
     */
    override suspend fun injectMembershipDrain(host: Seam, joiner: Seam): Boolean {
        val base = pair?.clientBase ?: return false
        base.close()
        return true
    }

    /** Proven: this harness drains a peer without tearing the survivor, so no gap. */
    override fun membershipDrainDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

    /**
     * **Not a gap — the event is not constructible here (#2568).** A room hub does not die of one
     * link: killing the client's base seam deregisters that spoke and leaves the hub
     * [us.tractat.kuilt.core.SeamState.Woven] with every other spoke intact, which is precisely why
     * that same injection is this harness's [injectMembershipDrain] directly above. The obligation
     * needs *both* ends to latch `Torn`, and the survivor here structurally cannot — a fact about the
     * star's topology, so it is unaffected by what the joiner does.
     *
     * [midSessionDeathDeclarationIsHonest] refutes that claim's cheap failure mode rather than
     * believing it; [ObligationDeclaration] states what the arm still cannot detect.
     */
    /**
     * Depart the client by closing its **base seam**, not the `NamedMux` channel view this harness
     * hands back as the joiner — the stimulus [midSessionDeathDeclaration]'s reason actually names,
     * and the same one [injectMembershipDrain] performs.
     *
     * **Without this override the refutation is vacuous here, and that nearly shipped (#2568 review).**
     * A per-channel close is a local unsubscribe with **no wire representation**: since #2372 it does
     * latch the view's own `Torn` and collapse the view's own roster, but nothing leaves the machine,
     * so the base connection carries on and the hub deregisters nobody (#2665). The default
     * `joiner.close()` therefore departs nobody, the hub stays `Woven` because *nothing reached it*,
     * and the arm's no-tear conclusion would have been green by absence rather than by topology.
     * Closing the base seam is a real departure: the hub deregisters the spoke from every room it
     * joined.
     */
    override suspend fun departCounterpart(host: Seam, joiner: Seam): Boolean {
        val base = pair?.clientBase ?: return false
        base.close()
        return true
    }

    override fun midSessionDeathDeclaration(): ObligationDeclaration =
        ObligationDeclaration.NotApplicable.NotConstructible(
            "a room hub does not die of one link: killing the client's base seam deregisters that " +
                "spoke and leaves the hub Woven with every other spoke intact, so the survivor " +
                "cannot latch Torn. That same injection is this harness's membership drain, which " +
                "this harness proves. The claim is about the star's topology alone, so it holds " +
                "whatever the joiner does: the obligation needs BOTH ends to latch Torn and the " +
                "star's survivor never can. Stated that way deliberately - it used to also name " +
                "#2372 (the channel view not reaching Torn on its own close), and that half is now " +
                "fixed without the declaration needing to change",
        )

    private companion object {
        /** Must match the `Pattern` [SeamConformanceSuite.connectedPair] hosts with. */
        const val ROOM = "host"
    }
}

/**
 * A test-local [Loom] pair binding `MuxServerLoom`'s room hub to [SeamConformanceSuite].
 *
 * [serverLoom] is the `MuxServerLoom` from the packaged [InMemoryRoomFabric] double; its
 * `host(pattern)` returns the `RoomHubSeam` under test. [clientLoom] connects one client over a
 * fresh in-memory connection, wraps it in a [NamedMux], announces on the room's channel so the
 * hub admits it, and returns that channel view.
 *
 * @param testScope owns the server's accept pump, per-connection read loops, and the client's mux
 *   collectors. `null` is legal for the scope-free `newLoomPair()` used by the suite's
 *   `availability()` obligation, which never weaves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomHubLoomPair(private val testScope: TestScope?) {

    private var fabric: InMemoryRoomFabric? = null

    /**
     * The client's raw multi-channel seam — the transport *under* the returned channel view.
     * Closing it is how the harness injects a membership drain; the channel view's own `close`
     * is per-channel and invisible to the server.
     */
    var clientBase: Seam? = null
        private set

    val serverLoom: Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = fabric().serverLoom.weave(rendezvous)
    }

    val clientLoom: Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val scope = requireNotNull(testScope) {
                "RoomHubLoomPair.weave needs a TestScope — use newLoomPair(testScope)"
            }
            val name = rendezvousName(rendezvous)
            val f = fabric()
            // `host` is idempotent per room name, so this resolves the SAME RoomHubSeam the
            // suite's own `host()` call returns — the hub whose roster we wait on below.
            val room = f.serverLoom.host(Pattern(name))

            val base = f.clientSeam(CLIENT, Random(99L)).also { clientBase = it }
            val channel = NamedMux(base, scope.backgroundScope).channel(name)
            // A hub admits a connection on its FIRST frame for that room. Announce, then wait on
            // the hub's observable roster so `join()` returns a genuinely-admitted member.
            channel.broadcast(byteArrayOf())
            room.peers.first { CLIENT in it }
            return channel
        }
    }

    private fun fabric(): InMemoryRoomFabric = fabric ?: run {
        val scope = requireNotNull(testScope) {
            "RoomHubLoomPair.weave needs a TestScope — use newLoomPair(testScope)"
        }
        InMemoryRoomFabric(
            scope = scope.backgroundScope,
            dispatcher = requireNotNull(scope.coroutineContext[ContinuationInterceptor]) {
                "TestScope has no dispatcher (ContinuationInterceptor)"
            },
            authorizer = RoomAuthorizer.AllowAll,
            random = Random(4242L),
        ).also { fabric = it }
    }

    private fun rendezvousName(rendezvous: Rendezvous): String = when (rendezvous) {
        is Rendezvous.New -> rendezvous.pattern.sessionName
        is Rendezvous.Existing -> rendezvous.tag.sessionName
    }

    private companion object {
        val CLIENT = PeerId("conformance-client")
    }
}
