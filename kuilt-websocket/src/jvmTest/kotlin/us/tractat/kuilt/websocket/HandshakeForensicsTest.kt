package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.test.assertAll
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
    private val silentListeners = mutableListOf<ServerSocket>()

    @AfterTest
    fun tearDown() {
        silentListeners.forEach { it.close() }
        httpClient.close()
        servers.forEach { it.stop(gracePeriodMillis = 0, timeoutMillis = 500) }
    }

    /**
     * A rig server on port 0. The default is the wildcard, which the rigs bound before #3774 and the
     * steal arm needs. [host] = [LOOPBACK_HOST] is how the rigs bind now.
     */
    private fun startRigServer(host: String = "0.0.0.0"): Int {
        val server = embeddedServer(Netty, port = 0, host = host) {
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
            { assertContains(report, "This server holds the wildcard") },
        )
    }

    /**
     * The rigs' bind since #3774. No socket can share a loopback-bound server's exact address, so the
     * verdict must not blame a thief on the port. It must point at the dial target instead.
     */
    @Test
    fun aLoopbackRigThatNeverSawTheDialPointsAtTheTarget(): Unit = runBlocking {
        val rigPort = startRigServer(host = LOOPBACK_HOST)
        val foreignPort = startForeignServer(host = LOOPBACK_HOST, port = 0)
        val report = dialFailure("ws://$LOOPBACK_HOST:$foreignPort$path").report()
        assertAll(
            { assertContains(report, "server.started bound=$LOOPBACK_HOST:$rigPort") },
            { assertContains(report, "It holds $LOOPBACK_HOST:$rigPort exactly") },
            { assertContains(report, "Compare the dial target with server.started") },
            { assertFalse("This server holds the wildcard" in report, "a loopback rig was blamed on a thief:\n$report") },
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

    /**
     * A dial that hangs until a bound around it expires (#3783). The listener accepts the connection
     * in the kernel and never reads it, so the handshake waits for an answer that never comes. The
     * cancellation must reach the caller as the cancellation it is, carrying the record.
     */
    @Test
    fun aDialThatHangsUntilItsCallerGivesUpCarriesTheRecord(): Unit = runBlocking {
        startRigServer(host = LOOPBACK_HOST)
        val silentPort = startSilentListener()
        val url = "ws://$LOOPBACK_HOST:$silentPort$path"
        val loom = ForensicLoom(KtorClientLoom(httpClient), forensics) { url }
        val advertisement = WebSocketAdvertisement(url = url, serverPeerId = PeerId("forensics-server"), sessionName = "forensics")
        val hung = assertFailsWith<TimeoutCancellationException> {
            withTimeout(HANG_BOUND) { loom.weave(Rendezvous.Existing(advertisement)) }
        }
        val report = hung.report()
        assertAll(
            { assertContains(report, "client.dial-cancelled by its caller") },
            { assertContains(report, "The dial was still waiting when its caller cancelled it") },
            { assertContains(report, "THIS server received no request after the dial") },
            { assertContains(report, "client.dial target=$url") },
            { assertContains(report, "listeners on :") },
        )
    }

    /**
     * A cancellation the dial throws while its caller is live, such as a bound inside the dial
     * expiring. It is rethrown untouched, still a cancellation, and the record says whose it was.
     */
    @Test
    fun aCancellationTheDialThrowsItselfIsRethrownWithTheRecord(): Unit = runBlocking {
        startRigServer(host = LOOPBACK_HOST)
        val minted = assertFailsWith<TimeoutCancellationException> {
            forensics.dial("ws://$LOOPBACK_HOST:1$path") { withTimeout(1.milliseconds) { awaitCancellation() } }
        }
        val report = minted.report()
        assertAll(
            { assertContains(report, "client.dial-cancelled by the dial itself, its caller still live") },
            { assertContains(report, "The dial threw a cancellation of its own while its caller was still live") },
        )
    }

    /** A loopback listener that never accepts: a dial at it connects, sends, and waits forever. */
    private fun startSilentListener(): Int {
        val listener = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))
        silentListeners += listener
        return listener.localPort
    }

    private fun isMacOs(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

    private companion object {
        val HANG_BOUND = 1.seconds
    }
}

private fun Application.noRoutes() = Unit
