package us.tractat.kuilt.demo.relay

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.demo.Cell
import us.tractat.kuilt.demo.Colour
import us.tractat.kuilt.gossip.hostedOverlay
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.websocket.KtorConnectionSource
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The Patchwork relay — the table everyone stitches at.
 *
 * A real-WebSocket hub for the Patchwork demo: peers connect through it and
 * every stitch reaches every other peer live. Composition:
 *
 * - **Fabric:** a Netty-embedded Ktor server exposes a WebSocket route at
 *   [path]; each accepted connection is a hub spoke ([KtorConnectionSource]),
 *   bonded into one star [us.tractat.kuilt.core.Seam] by
 *   [hostedOverlay] — a full-fanout [us.tractat.kuilt.gossip.GossipSeam] hub
 *   that relays every spoke's broadcast to every other spoke (with dedup), so
 *   a stitch a peer broadcasts arrives at all peers within a round trip.
 *   Spokes join with [us.tractat.kuilt.websocket.KtorMeshClientLoom] wrapped in
 *   a `GossipSeam` (`:demo-shared`'s `RelaySpokeLoom`).
 * - **Quilt peer:** the relay itself runs a [Quilter] on the hub seam. That
 *   makes it the always-on replica of the shared board: a late joiner (or a
 *   peer returning from tunnel mode) receives the full quilt from the relay
 *   the moment it connects (first-contact `FullState`), and the relay's
 *   anti-entropy heals anything a flood dropped.
 * - **Merge echo:** frames sent point-to-point to the relay (a reconnecting
 *   peer's `FullState`, carrying its offline stitches) are not gossip
 *   broadcasts, so the hub does not relay them by itself. The relay therefore
 *   *echoes* every change to its merged board back out as a delta of its own —
 *   joining a full LWW state is idempotent, so the echo is always safe — which
 *   makes a tunnel-mode merge visible on every peer within a round trip
 *   instead of an anti-entropy interval.
 *
 * Identity is minted fresh per relay start (peer id and, via the [Quilter]
 * factory default, replica id): a rebooted relay reusing an old identity would
 * look stale to peers still tracking the previous incarnation's sequence
 * numbers.
 */
@OptIn(ExperimentalUuidApi::class)
class PatchworkRelay private constructor(
    /** The actual bound port (resolved after bind — pass `port = 0` for ephemeral). */
    val port: Int,
    /** The relay's own merged view of the shared quilt. */
    val quilt: StateFlow<LWWMap<Cell, Colour>>,
    /** Everyone currently connected through this relay (including the relay itself). */
    val peers: StateFlow<Set<PeerId>>,
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
) : AutoCloseable {

    override fun close() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    companion object {
        const val DEFAULT_PATH: String = "/patchwork"

        /**
         * Starts a relay listening on [port] (`0` = ephemeral; read the bound
         * port back from [PatchworkRelay.port]). All background work — the
         * accept pump, the gossip hub, the relay's replicator — runs on
         * [scope]; cancel it (and [close] the relay) to shut down.
         */
        suspend fun start(
            scope: CoroutineScope,
            port: Int,
            path: String = DEFAULT_PATH,
        ): PatchworkRelay {
            lateinit var source: KtorConnectionSource
            val server = embeddedServer(Netty, port = port) {
                source = KtorConnectionSource(this, path)
            }
            server.start(wait = false)
            val boundPort = server.engine.resolvedConnectors().first().port

            val hub = scope.hostedOverlay(
                selfId = PeerId("relay-${Uuid.random()}"),
                source = source,
                dispatcher = Dispatchers.Default,
            )
            val quilter = Quilter(
                seam = hub,
                initial = LWWMap.empty<Cell, Colour>(),
                valueSerializer = LWWMap.serializer(Cell.serializer(), Colour.serializer()),
                scope = scope,
                config = QuilterConfig(
                    // Snappy backstop: the live path is the gossip flood + the merge
                    // echo below; anti-entropy only heals dropped frames.
                    antiEntropyInterval = 1.seconds,
                    // A tunneled peer stops acking; evict it quickly so it does not
                    // pin the relay's pending-delta buffer for the default 5 minutes.
                    evictionAfter = 30.seconds,
                ),
            )

            // The merge echo (see class KDoc). Joining the full state is idempotent
            // and LWWMap equality is structural, so the echo's own mutation leaves
            // the state value unchanged — the StateFlow conflates it and the loop
            // terminates deterministically.
            scope.launch {
                var echoed = LWWMap.empty<Cell, Colour>()
                quilter.state.collect { merged ->
                    if (merged == echoed) return@collect
                    echoed = merged
                    quilter.mutate { Patch(merged) }
                }
            }

            return PatchworkRelay(
                port = boundPort,
                quilt = quilter.state,
                peers = hub.peers,
                server = server,
            )
        }
    }
}
