package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.meshSeam
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * A room-isolating in-memory fabric for tests: one server that hosts several independent rooms,
 * and clients that join them — all over in-process connections, no network.
 *
 * ## Why this exists — the flat [us.tractat.kuilt.core.InMemoryLoom] cross-admits
 *
 * The obvious in-memory double, [us.tractat.kuilt.core.InMemoryLoom], is a **single flat mesh**:
 * every hosted "room" shares one broadcast domain, and a joiner of one room is silently admitted
 * to every other room hosted on the same loom. That is fine for a single-room test but a trap for
 * any test that stands up **two rooms on one loom** — the cross-admission corrupts membership
 * (and, downstream, consensus state) in ways only a distant error surfaces.
 *
 * [InMemoryRoomFabric] closes that gap. Its [serverLoom] is a [MuxServerLoom], which routes each
 * accepted connection into a room **by channel name** and forwards a room's broadcasts only to the
 * connections admitted to that room. Two rooms hosted over the one [serverLoom] are structurally
 * isolated — this is exactly the Seam-layer fanout isolation pinned by
 * `RoomFanoutIsolationConformanceSuite`, which the flat [us.tractat.kuilt.core.InMemoryLoom] does
 * not satisfy. Reach for this double whenever a test hosts more than one room.
 *
 * ## Rendezvous names must agree
 *
 * A client routes to a server room by the rendezvous **session name**: a joiner reaches the room a
 * host opened with `Pattern(name)` only by joining a `Tag` whose `sessionName` is that same `name`
 * (or, via [clientLoom], by weaving a rendezvous that maps to it). Hosts and joiners of one logical
 * room must therefore agree on the name. This mirrors production: the client selects its room by
 * the name the host advertised.
 *
 * ## Two client surfaces
 *
 * - [clientSeam] returns a **raw multi-channel** [Seam] wired straight to [serverLoom] over one
 *   fresh connection. Wrap it in a [NamedMux] and pick a channel per room name to drive several
 *   rooms over the one link — the low-level handle the fanout conformance suite uses.
 * - [clientLoom] wraps that raw seam in a [MuxClientLoom] so it presents as a real [Loom] and plugs
 *   straight into `SeamRoomFactory` (or any `Loom` consumer). `host(pattern)` / `join(tag)` route by
 *   the rendezvous session name.
 *
 * @param scope owns the server accept pump, per-connection read loops, and the client mux
 *   collectors. **Required** — no real-dispatcher default (test determinism).
 * @param dispatcher coroutine context for the mesh-seam link loops on both ends, so server- and
 *   client-side seams share the test's virtual clock. **Required** — no real-dispatcher default.
 * @param authorizer per-room membership gate on the server. Defaults to [RoomAuthorizer.AllowAll];
 *   pass a rejecting policy to test structural exclusion.
 * @param random seeded [Random] for the server seam's nonce generation. Seeded so a run is
 *   deterministic; per-client nonce randoms are passed to [clientSeam] / [clientLoom].
 */
public class InMemoryRoomFabric(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineContext,
    authorizer: RoomAuthorizer = RoomAuthorizer.AllowAll,
    random: Random = Random(0),
) {
    private val source = InMemoryConnectionSource()

    /** The server-side [Loom]: a [MuxServerLoom] whose per-room `host(pattern)` seams are isolated. */
    public val serverLoom: Loom = MuxServerLoom(
        source = source,
        scope = scope,
        selfId = PeerId("server"),
        authorizer = authorizer,
        dispatcher = dispatcher,
        random = random,
    )

    /**
     * A raw, multi-channel client [Seam] wired to [serverLoom] over one fresh in-memory connection.
     *
     * Wrap in a [NamedMux] and select a channel per room name to participate in several rooms over
     * the single link. The server registers this connection into a room when its first frame
     * arrives on that room's channel.
     *
     * @param peerId this client's identity.
     * @param random seeded [Random] for this client's mesh-seam nonce generation — pass a distinct
     *   seed per client so nonces differ deterministically.
     */
    public suspend fun clientSeam(peerId: PeerId, random: Random): Seam {
        val (serverConn, clientConn) = connectionPair()
        source.offer(serverConn)
        return meshSeam(selfId = peerId, connections = listOf(clientConn), dispatcher = dispatcher, random = random)
    }

    /**
     * A client [Loom] over one shared in-memory link. Multiplexes each hosted/joined room name onto
     * its own channel (via [MuxClientLoom]), so it plugs straight into `SeamRoomFactory`.
     *
     * `host(pattern)` and `join(tag)` route by the rendezvous **session name**, which must match a
     * server room's `Pattern.sessionName`.
     *
     * @param peerId this client's identity.
     * @param random seeded [Random] for this client's mesh-seam nonce generation — pass a distinct
     *   seed per client.
     */
    public fun clientLoom(peerId: PeerId, random: Random): Loom {
        val base = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam = clientSeam(peerId, random)
        }
        return MuxClientLoom(
            base = base,
            baseRendezvous = Rendezvous.New(Pattern(peerId.value)),
            scope = scope,
            nameOf = ::rendezvousName,
        )
    }
}

/** The channel name a [Rendezvous] routes to: the `Pattern` / `Tag` session name. */
internal fun rendezvousName(rendezvous: Rendezvous): String = when (rendezvous) {
    is Rendezvous.New -> rendezvous.pattern.sessionName
    is Rendezvous.Existing -> rendezvous.tag.sessionName
}
