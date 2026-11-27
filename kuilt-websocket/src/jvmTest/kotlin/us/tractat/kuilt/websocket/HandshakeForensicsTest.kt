package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.test.assertAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

private typealias NettyServer = EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

/**
 * The arms of [HandshakeForensics]'s verdict, each driven by a real failed dial. A forensic record
 * that cannot tell its two cases apart would read as a diagnosis while naming nothing, so every arm
 * asserts the verdict it should reach and the evidence it should carry.
 */
class HandshakeForensicsTest {

    private val path = "/ws/forensics"
    private val forensics = HandshakeForensics(path)
    private val servers = mutableListOf<NettyServer>()
    private val httpClient = HttpClient(CIO) { install(ClientWebSockets) }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        servers.forEach { it.stop(gracePeriodMillis = 0, timeoutMillis = 500) }
    }

    /** The rig's own server: bound to the wildcard on port 0, as `WebSocketConformanceTest` binds it. */
    private fun startRigServer(): Int {
        val server = embeddedServer(Netty, port = 0) {
            forensics.install(this)
            KtorServerLoom(this, path)
            forensics.routeInstalled()
        }
        servers += server
        server.start(wait = false)
        val connector = runBlocking { server.engine.resolvedConnectors().first() }
        forensics.serverStarted(connector)
        return connector.port
    }

    /** A live Ktor application with no routes: what answers a dial with a 404 when it is not ours. */
    private fun startForeignServer(host: String, port: Int): Int {
        val server = embeddedServer(Netty, configure = { connector { this.host = host; this.port = port } }, module = Application::noRoutes)
        servers += server
        server.start(wait = false)
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private suspend fun dialFailure(url: String): Throwable {
        val loom = ForensicLoom(KtorClientLoom(httpClient), forensics) { url }
        val advertisement = WebSocketAdvertisement(url = url, serverPeerId = PeerId("forensics-server"), sessionName = "forensics")
        // Through `async`, as the suite's `connectedPair` dials: the await is a resumption, so the
        // throwable that arrives is a stack-trace-recovered copy, and the report has to survive that.
        return assertFailsWith<Throwable> {
            coroutineScope { async { loom.weave(Rendezvous.Existing(advertisement)) }.await() }
        }
    }

    /** The report as a test-results XML shows it: in the printed trace, not merely on some object. */
    private fun Throwable.report(): String {
        val printed = stackTraceToString()
        val reports = forensicReports()
        assertAll(
            { assertEquals(1, reports.size, "the failed dial must carry exactly one forensic report:\n$printed") },
            { assertContains(printed, "Suppressed: ${HandshakeForensicsReport::class.qualifiedName}: Handshake forensics") },
        )
        return reports.single().message.orEmpty()
    }

    @Test
    fun aDialAnsweredByAnotherServerSaysThisServerNeverSawIt(): Unit = runBlocking {
        val rigPort = startRigServer()
        val foreignPort = startForeignServer(host = "127.0.0.1", port = 0)
        val report = dialFailure("ws://127.0.0.1:$foreignPort$path").report()
        assertAll(
            { assertContains(report, "THIS server received no request after the dial") },
            { assertContains(report, "server.started bound=0.0.0.0:$rigPort") },
            { assertContains(report, "server.route-installed path=$path") },
            { assertContains(report, "client.dial target=ws://127.0.0.1:$foreignPort$path") },
            { assertContains(report, "client.dial-failed") },
            { assertContains(report, "listeners on :$rigPort") },
        )
    }

    @Test
    fun aDialThisServerRefusedCarriesItsOwnAnswer(): Unit = runBlocking {
        val rigPort = startRigServer()
        val report = dialFailure("ws://127.0.0.1:$rigPort/ws/not-the-route").report()
        assertAll(
            { assertContains(report, "THIS server received 1 request(s) after the dial") },
            { assertContains(report, "server.request GET /ws/not-the-route?peer=") },
            { assertContains(report, "server.response /ws/not-the-route?peer=") },
            { assertContains(report, "404") },
        )
    }

    /**
     * The shape #3754 and #3603 recorded, reproduced on purpose. On macOS a socket bound to
     * 127.0.0.1:P is accepted while the rig's server holds the wildcard on P, and it takes every
     * loopback dial to P. A `localhost` dial then gets a 404 from an application that never had
     * the route, and the rig's server sees nothing. The arm runs on macOS only, because it rests on a
     * BSD binding rule and Linux's rule differs. There it is also the receipt that the steal happens:
     * if a macOS release stops permitting the second bind, this reds on the bind, not on the report.
     */
    @Test
    fun aLoopbackListenerOnTheRigPortIsNamedAsTheThief(): Unit = runBlocking {
        Assume.assumeTrue("the loopback-over-wildcard bind is a BSD behaviour", isMacOs())
        val rigPort = startRigServer()
        startForeignServer(host = "127.0.0.1", port = rigPort)
        val failure = dialFailure("ws://localhost:$rigPort$path")
        val report = failure.report()
        assertAll(
            { assertContains("${failure.message}", "404") },
            { assertContains(report, "THIS server received no request after the dial") },
            { assertTrue(report.lines().count { "LISTEN" in it } >= 2, "lsof should list both listeners:\n$report") },
        )
    }

    private fun isMacOs(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")
}

private fun Application.noRoutes() = Unit
