package us.tractat.kuilt.demo.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Runs the Patchwork relay until the process is killed.
 *
 * ```
 * ./gradlew :demo-relay:run                        # port 9190
 * ./gradlew :demo-relay:run --args="--port=9999"
 * ```
 */
fun main(args: Array<String>) {
    val port = args.firstNotNullOfOrNull { it.substringAfter("--port=", "").toIntOrNull() } ?: DEFAULT_PORT

    // Background work (accept pump, gossip hub, replicator) runs multi-threaded;
    // runBlocking parks the main thread on the status log.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    runBlocking {
        val relay = PatchworkRelay.start(scope, port)
        println("Patchwork relay listening on ws://localhost:${relay.port}${PatchworkRelay.DEFAULT_PATH}")
        println("Point CLI peers at it:  ./gradlew :demo-cli:run --args=\"--url=ws://localhost:${relay.port}${PatchworkRelay.DEFAULT_PATH} --name=alice\"")

        scope.launch {
            relay.peers.collect { peers ->
                println("peers: ${peers.size - 1} connected") // minus the relay itself
            }
        }
        relay.quilt.collect { board ->
            println("quilt: ${board.entries.size} patches")
        }
    }
}

private const val DEFAULT_PORT = 9190
