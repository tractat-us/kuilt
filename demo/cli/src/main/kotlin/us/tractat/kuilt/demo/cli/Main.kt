package us.tractat.kuilt.demo.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.demo.PatchworkSession
import us.tractat.kuilt.demo.StitchClock
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The Patchwork terminal peer.
 *
 * ```
 * ./gradlew :demo-cli:run --args="--url=ws://localhost:9190/patchwork --name=alice"
 * ```
 *
 * An interactive loop over [PatchworkCli]: stitch cells, `tunnel` offline,
 * keep stitching, `reconnect` and watch the merge. Remote changes re-render
 * the quilt live.
 */
fun main(args: Array<String>) {
    val url = args.firstNotNullOfOrNull { it.substringAfter("--url=", "").ifEmpty { null } }
        ?: "ws://localhost:9190/patchwork"
    val name = args.firstNotNullOfOrNull { it.substringAfter("--name=", "").ifEmpty { null } }
        ?: "peer${Random.nextInt(1000)}"

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val httpClient = HttpClient(CIO) { install(WebSockets) }
    runBlocking {
        val session = PatchworkSession(
            loom = RelaySpokeLoom(httpClient, scope, peerName = name),
            // The stitcher tags this peer's patches in the LWW map; the random
            // suffix keeps two same-named peers from colliding on tag identity.
            stitcher = ReplicaId("$name-${Random.nextInt(0x10000).toString(16)}"),
            scope = scope,
            clock = StitchClock { System.currentTimeMillis() },
            quilterConfig = QuilterConfig(antiEntropyInterval = 2.seconds, evictionAfter = 30.seconds),
        )
        val tag = WebSocketAdvertisement(url = url, serverPeerId = PeerId("patchwork-relay"), sessionName = name)
        session.join(tag)
        println("joined the quilt at $url as $name — 'help' lists commands")

        // Live view: re-render whenever the merged quilt changes.
        scope.launch {
            session.quilt.collect { quilt ->
                if (quilt.isNotEmpty()) println("\n${renderQuilt(quilt)}")
            }
        }

        val cli = PatchworkCli(session, tag)
        while (true) {
            print("patchwork> ")
            val line = readlnOrNull() ?: break
            if (line.trim().lowercase() in setOf("quit", "exit")) break
            val output = cli.execute(line)
            if (output.isNotEmpty()) println(output)
        }

        // Let in-flight broadcasts drain before tearing the socket — quitting the
        // instant after a stitch must not race the frame out of existence.
        if (session.connected.value) delay(500)
        session.disconnect()
    }
    httpClient.close()
    scope.cancel()
}
