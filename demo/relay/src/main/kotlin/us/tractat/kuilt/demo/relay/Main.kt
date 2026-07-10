package us.tractat.kuilt.demo.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.demo.TapWire

/**
 * Runs the Patchwork relay until the process is killed.
 *
 * ```
 * ./gradlew :demo-relay:run                        # hub on 9190, taps on 9191
 * ./gradlew :demo-relay:run --args="--port=9999"
 * ```
 *
 * The relay is also *observable*: it captures its own logs and a few metrics and
 * offers them for extraction on a second port (see [installRelayObservability]), so
 * the `:demo-tap` harness can reach in and pull them live.
 */
fun main(args: Array<String>) {
    val port = args.firstNotNullOfOrNull { it.substringAfter("--port=", "").toIntOrNull() } ?: DEFAULT_PORT
    val tapPort = args.firstNotNullOfOrNull { it.substringAfter("--tap-port=", "").toIntOrNull() } ?: TapWire.DEFAULT_PORT

    // Background work (accept pump, gossip hub, replicator, log/metric taps) runs
    // multi-threaded; runBlocking parks the main thread until the process is killed.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    runBlocking {
        val relay = PatchworkRelay.start(scope, port)
        val boundTapPort = installRelayObservability(relay, scope, tapPort)

        println("Patchwork relay listening on ws://localhost:${relay.port}${PatchworkRelay.DEFAULT_PATH}")
        println("Point CLI peers at it:  ./gradlew :demo-cli:run --args=\"--url=ws://localhost:${relay.port}${PatchworkRelay.DEFAULT_PATH} --name=alice\"")
        println("Reach in and pull its logs + metrics:  ./gradlew :demo-tap:run --args=\"--port=$boundTapPort --tail\"")

        // Peer/quilt status and the metric numbers are logged from installRelayObservability
        // (which also feeds them to the taps); here we simply run until interrupted.
        awaitCancellation()
    }
}

private const val DEFAULT_PORT = 9190
