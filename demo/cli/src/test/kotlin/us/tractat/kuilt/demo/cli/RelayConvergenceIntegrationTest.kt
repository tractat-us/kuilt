package us.tractat.kuilt.demo.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: end-to-end test over a REAL WebSocket relay — real sockets need a real dispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.demo.Cell
import us.tractat.kuilt.demo.Colour
import us.tractat.kuilt.demo.PatchworkSession
import us.tractat.kuilt.demo.RelaySpokeLoom
import us.tractat.kuilt.demo.StitchClock
import us.tractat.kuilt.demo.relay.PatchworkRelay
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Slice 3 end-to-end: two CLI-level [PatchworkSession]s converge over a REAL
 * `:demo-relay` — a Netty WebSocket hub on an ephemeral loopback port — and a
 * tunnel-mode disconnect → stitch → reconnect merges over the real fabric.
 *
 * Real IO, so real time: `runBlocking` + real dispatchers (never `runTest` —
 * real sockets cannot ride a virtual clock), and every expectation **awaits
 * the actual condition** with bounded polling under a generous ceiling — no
 * fixed sleeps. Port races are impossible: the relay binds port 0 and reports
 * the kernel-chosen port back.
 */
class RelayConvergenceIntegrationTest {

    private val red = Colour("#e94f37")
    private val green = Colour("#57a773")
    private val blue = Colour("#4062bb")
    private val gold = Colour("#f2c14e")

    @Test
    fun cliPeersConvergeAndTunnelStitchesMergeOverTheRealRelay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = HttpClient(CIO) { install(WebSockets) }
        val relay = PatchworkRelay.start(scope, port = 0)
        try {
            val tag = WebSocketAdvertisement(
                url = "ws://127.0.0.1:${relay.port}${PatchworkRelay.DEFAULT_PATH}",
                serverPeerId = PeerId("patchwork-relay"),
                sessionName = "integration",
            )

            fun cliSession(name: String) = PatchworkSession(
                loom = RelaySpokeLoom(httpClient, scope, peerName = name),
                stitcher = ReplicaId(name),
                scope = scope,
                clock = StitchClock { System.currentTimeMillis() },
                quilterConfig = QuilterConfig(antiEntropyInterval = 1.seconds, evictionAfter = 30.seconds),
            )

            val alice = cliSession("alice")
            val bob = cliSession("bob")
            alice.join(tag)
            bob.join(tag)

            // Live convergence over the real fabric.
            alice.stitch(Cell(0, 0), red)
            bob.stitch(Cell(1, 0), green)
            val phase1 = mapOf(Cell(0, 0) to red, Cell(1, 0) to green)
            awaitCondition("both peers converge on the first two stitches") {
                alice.quilt.value == phase1 && bob.quilt.value == phase1
            }

            // Bob enters the tunnel; both sides keep stitching across the partition.
            bob.disconnect()
            assertFalse(bob.connected.value, "bob is offline")
            bob.stitch(Cell(2, 2), blue)
            alice.stitch(Cell(0, 1), gold)
            awaitCondition("alice's stitch reaches the relay while bob is away") {
                relay.quilt.value.entries[Cell(0, 1)] == gold
            }
            // Bob disconnected BEFORE stitching, so his patch cannot have crossed —
            // and alice's patch cannot have reached him. Safe to assert directly.
            assertAll(
                { assertNull(alice.quilt.value[Cell(2, 2)], "alice must not see bob's tunnel stitch") },
                { assertNull(bob.quilt.value[Cell(0, 1)], "bob must not see alice's stitch while offline") },
            )

            // Bob leaves the tunnel: his offline patch merges into everyone,
            // everyone's patches merge into him.
            bob.join(tag)
            val merged = phase1 + mapOf(Cell(2, 2) to blue, Cell(0, 1) to gold)
            awaitCondition("the tunnel stitches merge on every peer") {
                alice.quilt.value == merged && bob.quilt.value == merged
            }
            assertTrue(bob.connected.value, "bob is back online")
        } finally {
            relay.close()
            httpClient.close()
            scope.cancel()
        }
    }

    /**
     * Awaits [condition] by bounded polling (50 ms cadence) under a generous
     * [timeout] ceiling — real IO converges when it converges; the ceiling only
     * bounds a genuine failure, and the failure message says what never happened.
     */
    private suspend fun awaitCondition(
        what: String,
        timeout: Duration = 30.seconds,
        condition: () -> Boolean,
    ) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            check(started.elapsedNow() < timeout) { "timed out after $timeout awaiting: $what" }
            delay(50)
        }
    }
}
