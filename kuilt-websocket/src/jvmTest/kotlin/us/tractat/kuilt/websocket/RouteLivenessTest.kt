package us.tractat.kuilt.websocket

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Both arms of [awaitPathResolves]. A liveness probe that cannot fail is worse than none — it reads
 * as coverage while proving nothing — so the interesting half here is [refusesAPathThatIsNotRouted],
 * which pins that the guard actually discriminates and that its message names the 404 it saw.
 */
class RouteLivenessTest {

    private val path = "/ws/liveness"

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

    @AfterTest
    fun tearDown() {
        if (this::server.isInitialized) server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    @Test
    fun acceptsALiveWebSocketRoute(): Unit = runBlocking {
        awaitPathResolves(host = "localhost", port = startServer(), path = path)
    }

    @Test
    fun refusesAPathThatIsNotRouted(): Unit = runBlocking {
        val port = startServer()
        val failure = assertFailsWith<AssertionError> {
            awaitPathResolves(host = "localhost", port = port, path = "/no/such/route", timeout = 2.seconds)
        }
        assertContains(failure.message.orEmpty(), "404")
    }

    /** Starts a Netty server carrying exactly one [KtorServerLoom] route at [path]. */
    private suspend fun startServer(): Int {
        server = embeddedServer(Netty, port = 0) { KtorServerLoom(this, path) }
        server.start(wait = false)
        return server.engine.resolvedConnectors().first().port
    }
}
