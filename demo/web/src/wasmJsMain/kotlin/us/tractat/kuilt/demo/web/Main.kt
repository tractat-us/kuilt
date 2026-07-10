package us.tractat.kuilt.demo.web

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.demo.Cell
import us.tractat.kuilt.demo.Colour
import us.tractat.kuilt.demo.PatchworkSession
import us.tractat.kuilt.demo.RelaySpokeLoom
import us.tractat.kuilt.demo.StitchClock
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The visible board — CLI peers can stitch outside it; those cells just don't render here. */
internal const val GRID_WIDTH = 16
internal const val GRID_HEIGHT = 12

/** Same colours as :demo-cli's named palette, in picker order. */
internal val PALETTE = listOf(
    "#e94f37", "#57a773", "#4062bb", "#f2c14e",
    "#7768ae", "#17bebb", "#f5f5f5", "#222222",
)

/**
 * The Patchwork browser peer — the demo's headline surface.
 *
 * Every open tab is one more stitcher on the shared quilt: it joins the
 * `:demo-relay` hub over a browser WebSocket ([RelaySpokeLoom] on Ktor's Js
 * engine), clicks stitch patches, and remote patches appear live. The
 * **tunnel** toggle tears the socket while the local board keeps accepting
 * stitches; reconnecting merges the offline patches into every peer — and
 * theirs into this one — which is the convergence-under-partition story,
 * animated.
 *
 * Query parameters: `?name=alice` (peer name; random default) and
 * `?relay=ws://host:port/patchwork` (relay URL; defaults to the page's host
 * on port 9190).
 */
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val name = queryParam("name").ifEmpty { "weaver${Random.nextInt(1000)}" }
    val relayUrl = queryParam("relay").ifEmpty { defaultRelayUrl() }
    val httpClient = HttpClient { install(WebSockets) }

    val session = PatchworkSession(
        loom = RelaySpokeLoom(httpClient, scope, peerName = name),
        // The stitcher tags this tab's patches in the LWW map; the random
        // suffix keeps two same-named tabs from colliding on tag identity.
        stitcher = ReplicaId("$name-${Random.nextInt(0x10000).toString(16)}"),
        scope = scope,
        clock = StitchClock { Clock.System.now().toEpochMilliseconds() },
        quilterConfig = QuilterConfig(antiEntropyInterval = 2.seconds, evictionAfter = 30.seconds),
    )
    val relayTag = WebSocketAdvertisement(
        url = relayUrl,
        serverPeerId = PeerId("patchwork-relay"),
        sessionName = name,
    )
    PatchworkPage(session, relayTag, scope, name).start()
}

/**
 * Wires the static page (resources/index.html) to a [PatchworkSession]: the
 * quilt grid renders [PatchworkSession.quilt] live, clicks call
 * [PatchworkSession.stitch], and the tunnel button toggles
 * [PatchworkSession.disconnect] / [PatchworkSession.join]. All convergence
 * behaviour lives in the session (tested in :demo-shared and :demo-cli);
 * this class only moves state onto the screen.
 */
private class PatchworkPage(
    private val session: PatchworkSession,
    private val relayTag: WebSocketAdvertisement,
    private val scope: CoroutineScope,
    private val peerName: String,
) {
    private var selected = Colour(PALETTE.first())
    private var toggling = false

    fun start() {
        buildGrid(GRID_WIDTH, GRID_HEIGHT)
        PALETTE.forEach { hex ->
            addSwatch(hex) {
                selected = Colour(hex)
                markSelectedSwatch(hex)
            }
        }
        markSelectedSwatch(selected.hex)
        // Stitching works online AND in the tunnel — offline patches land on
        // the local board and merge on reconnect. That asymmetry is the demo.
        onCellClick { x, y -> session.stitch(Cell(x, y), selected) }
        onTunnelClick { toggleTunnel() }
        scope.launch { renderQuilt() }
        scope.launch { connect() }
    }

    /** Paints quilt changes as they merge in; repainting replays the stitch flash. */
    private suspend fun renderQuilt() {
        var previous = emptyMap<Cell, Colour>()
        session.quilt.collect { quilt ->
            quilt.forEach { (cell, colour) ->
                if (previous[cell] != colour) paintCell(cell.x, cell.y, colour.hex)
            }
            previous = quilt
            setPatchTally(quilt.size)
        }
    }

    private suspend fun connect() {
        setStatus("joining the quilt at ${relayTag.url}…", "connecting")
        runCatchingCancellable { session.join(relayTag) }
            .onSuccess {
                setStatus("online as $peerName — every tab on this relay stitches one quilt", "online")
                setTunnelButton("Enter the tunnel")
            }
            .onFailure {
                setStatus(
                    "could not reach the relay at ${relayTag.url} — " +
                        "start :demo-relay, then press Reconnect",
                    "error",
                )
                setTunnelButton("Reconnect")
            }
    }

    private fun toggleTunnel() {
        if (toggling) return
        toggling = true
        scope.launch {
            try {
                if (session.connected.value) {
                    session.disconnect()
                    setStatus(
                        "in the tunnel as $peerName — stitches land locally and merge when you reconnect",
                        "tunnel",
                    )
                    setTunnelButton("Reconnect")
                } else {
                    connect()
                }
            } finally {
                toggling = false
            }
        }
    }
}
