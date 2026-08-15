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
     * `reportsPeerLoss = false` is a **harness** gap, and unusually it understates the fabric —
     * `RoomHubSeam` reports peer loss correctly, and this very harness proves it: its
     * [injectMembershipDrain] closes the client's underlying connection and
     * [SeamConformanceSuite.peersDrainWithoutTearOnInjectedMembershipDrain] then observes the hub
     * deregister the peer while staying Woven. What cannot be shown is
     * [SeamConformanceSuite.survivorStopsAdvertisingADepartedPeer], because that obligation departs
     * by closing the **joiner seam this harness hands back**, which is a `NamedMux` *channel view*:
     * `MuxBase.ChannelView.close` closes its own delivery spool and returns, while `state` and `peers`
     * keep delegating to a base connection that is still very much alive. So no peer departs, the hub
     * is right to keep advertising it, and the obligation's Torn precondition names that rather than
     * wedging. Declaring the gap here is the honest record; #2372 tracks the underlying question —
     * whether a channel view that never reaches [us.tractat.kuilt.core.SeamState.Torn] on `close()`
     * satisfies `Seam` at all, given `closeDrivesStateTornNormal` is ungated core.
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
        "reportsPeerLoss" to "https://github.com/tractat-us/kuilt/issues/2372",
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
    override fun membershipDrainGap(): String? = null

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
