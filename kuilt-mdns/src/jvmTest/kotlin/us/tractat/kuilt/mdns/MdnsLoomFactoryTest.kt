@file:Suppress("ForbiddenImport") // deliberate: real-socket harness — the seam under test is a live Ktor WebSocket, so its read/write loops cannot run on a virtual TestDispatcher at all.

package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the delegate under the dispatch-counting probe — a live WebSocket's read/write loops need real threads.
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * The `:kuilt-mdns` half of the uniform-construction convention (#1430): [mdnsLoom] surfaces the
 * universal knobs this fabric can actually **honour**, and each one is traced here to the seam it
 * reaches rather than merely to the constructor it is passed to.
 *
 * Two knobs are deliberately absent and so cannot be tested for: `policy`, which no WebSocket-backed
 * seam can carry (`WebSocketSeam` never forwards one to `identified` — #2687), and `weaveTimeout`,
 * which would have to invent a `withTimeout` that does not exist on either path. See [mdnsLoom]'s
 * KDoc.
 *
 * Driven over the **real localhost byte path** — real Netty server, real OkHttp client — with JmDNS
 * absorbed by [CapturingJmDNS], so no multicast and no `-P` gate. `embeddedServer` is called outside
 * any `runBlocking` receiver, and teardown closes seams then clients then servers, both for the
 * reasons spelled out in [MDNSSelfDiscoveryFilterTest].
 */
class MdnsLoomFactoryTest {

    private val servers = mutableListOf<EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>>()
    private val clients = mutableListOf<HttpClient>()
    private val openSeams = mutableListOf<Seam>()

    @AfterTest
    fun tearDown() {
        runBlocking { openSeams.forEach { runCatchingCancellable { it.close(CloseReason.Normal) } } }
        openSeams.clear()
        clients.forEach { it.close() }
        clients.clear()
        servers.forEach { it.stop(gracePeriodMillis = 100, timeoutMillis = 1_000) }
        servers.clear()
    }

    /**
     * The identity handed to [mdnsLoom] is the identity on the wire, at **both** ends.
     *
     * Before #1430 the hosting id was minted inside the factory (`PeerId("mdns-${'$'}{UUID.randomUUID()}")`)
     * and the joining id was minted fresh inside every `join` by [us.tractat.kuilt.websocket.KtorClientLoom]'s
     * own default — so a caller could name neither. The joiner arm is the load-bearing one: it reads the
     * id off the **host's** view of the seam, so it fails unless the value actually travelled as `?peer=`.
     */
    @Test
    fun `selfId reaches the seam on both the hosting and the joining path`() {
        val fixture = fixture()
        val hostId = PeerId("host-under-test")
        val joinerId = PeerId("joiner-under-test")

        val hostLoom = fixture.loomWithId(wsPath = "/ws/identity-host", selfId = hostId)
        val joinerLoom = fixture.loomWithId(wsPath = "/ws/identity-joiner", selfId = joinerId)

        val (hostSeam, joinerSeam) = fixture.pair(hostLoom, joinerLoom, "/ws/identity-host")

        assertAll(
            { assertEquals(hostId, hostLoom.selfPeerId, "the loom reports the id it was given") },
            { assertEquals(hostId, hostSeam.selfId, "the hosting seam is woven under that id") },
            { assertEquals(joinerId, joinerSeam.selfId, "the joining seam is woven under its own id") },
            {
                assertEquals(
                    setOf(joinerId),
                    hostSeam.peers.value - hostId,
                    "the host learned the joiner's id off the wire, so selfId reached the ?peer= query",
                )
            },
        )
    }

    /**
     * One loom, one identity — every `join` from it presents the same `selfId`.
     *
     * This is the property the joining-path change buys and the one a caller reconnecting over mDNS
     * depends on: pre-#1430 each `join` constructed a fresh
     * [us.tractat.kuilt.websocket.KtorClientLoom] whose default minted a **new random** id, so the
     * host saw a different peer on every dial. Distinctness across two *different* looms would not
     * discriminate — the old hardcoded default was random too, so it was distinct as well; identity
     * *stability across two dials from one loom* is what only the new wiring can satisfy.
     */
    @Test
    fun `two joins from one loom present the same identity`() {
        val fixture = fixture()
        val joinerId = PeerId("stable-joiner")

        val hostLoom = fixture.defaultLoom(wsPath = "/ws/stable-host")
        val joinerLoom = fixture.loomWithId(wsPath = "/ws/stable-joiner", selfId = joinerId)

        val advertisement = fixture.advertisement(hostLoom, "/ws/stable-host")
        val (first, second) = runBlocking {
            withTimeout(WINDOW) {
                val firstHost = async { hostLoom.host(Pattern("stable")).also { openSeams += it } }
                val firstJoin = joinerLoom.join(advertisement).also { openSeams += it }
                firstHost.await()
                val secondHost = async { hostLoom.host(Pattern("stable")).also { openSeams += it } }
                val secondJoin = joinerLoom.join(advertisement).also { openSeams += it }
                secondHost.await()
                firstJoin to secondJoin
            }
        }

        assertAll(
            { assertEquals(joinerId, first.selfId, "first dial carries the loom's identity") },
            { assertEquals(joinerId, second.selfId, "second dial carries the same identity") },
        )
    }

    /**
     * The `dispatcher` argument is the one that actually schedules the woven seam's coroutines, on
     * both paths.
     *
     * Falsifiable by construction: were the argument dropped, each loom would fall back to its own
     * default ([Dispatchers.IO] on the server, `Dispatchers.Default` on the client) and these probes
     * would never be dispatched to at all. The zero-before assertion is the rig check — a probe that
     * had already been used would make the after-assertion pass without the seam touching it.
     */
    @Test
    fun `dispatcher schedules the woven seam on both paths`() {
        val fixture = fixture()
        val hostDispatcher = CountingDispatcher(Dispatchers.IO)
        val joinerDispatcher = CountingDispatcher(Dispatchers.IO)

        val hostLoom = fixture.loomWithDispatcher(wsPath = "/ws/dispatch-host", dispatcher = hostDispatcher)
        val joinerLoom = fixture.loomWithDispatcher(wsPath = "/ws/dispatch-joiner", dispatcher = joinerDispatcher)

        val hostBefore = hostDispatcher.dispatches
        val joinerBefore = joinerDispatcher.dispatches
        fixture.pair(hostLoom, joinerLoom, "/ws/dispatch-host")

        assertAll(
            { assertEquals(0, hostBefore, "rig check: the host probe was untouched before weaving") },
            { assertEquals(0, joinerBefore, "rig check: the joiner probe was untouched before weaving") },
            {
                assertTrue(
                    hostDispatcher.dispatches > 0,
                    "the hosting seam's loops ran on the injected dispatcher (saw ${hostDispatcher.dispatches})",
                )
            },
            {
                assertTrue(
                    joinerDispatcher.dispatches > 0,
                    "the joining seam's loops ran on the injected dispatcher (saw ${joinerDispatcher.dispatches})",
                )
            },
        )
    }

    /** Two looms built without a `selfId` do not share one — the default is a mint, not a constant. */
    @Test
    fun `selfId defaults to a distinct identity per loom`() {
        val fixture = fixture()
        assertNotEquals(
            fixture.defaultLoom(wsPath = "/ws/default-a").selfPeerId,
            fixture.defaultLoom(wsPath = "/ws/default-b").selfPeerId,
        )
    }

    // ── harness ──────────────────────────────────────────────────────────────

    /**
     * One live Netty server, its port read back off the resolved connector.
     *
     * Binding 0 and reading the port back has no TOCTOU window; probing with a throwaway
     * `ServerSocket(0)` and re-binding the number does (#1590, #1749). The port is an input to the
     * loom — it goes in the advertised record — so looms are built after `start()`.
     */
    private fun fixture(): Fixture {
        // Outside any runBlocking receiver on purpose: inside one, the `CoroutineScope.embeddedServer`
        // extension parents Netty's SupervisorJob to that runBlocking, which then never completes
        // until the server stops, and the server cannot stop until the test returns — a structural
        // deadlock that surfaces as a task timeout, not a failure.
        val server = embeddedServer(Netty, port = 0) { /* routes mounted post-start */ }
            .also { servers += it }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        return Fixture(server, port)
    }

    private inner class Fixture(
        private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
        val port: Int,
    ) {
        /**
         * A loom on [mdnsLoom]'s own defaults for every universal knob.
         *
         * Deliberately *not* a defaulted-parameter overload of the two below: re-spelling
         * `selfId = freshPeerId()` in the harness would make `selfId defaults to a distinct identity
         * per loom` assert the fixture's mint rather than the factory's, which is the fixture-picks-
         * the-vacuous-case shape.
         */
        fun defaultLoom(wsPath: String): MDNSPeerLinkFactory = mdnsLoom(
            serviceType = MDNSServiceType("_kuilt-test._tcp"),
            application = server.application,
            jmdns = CapturingJmDNS(),
            port = port,
            wsPath = wsPath,
            httpClientFactory = ::freshClient,
        )

        fun loomWithId(wsPath: String, selfId: PeerId): MDNSPeerLinkFactory = mdnsLoom(
            serviceType = MDNSServiceType("_kuilt-test._tcp"),
            application = server.application,
            jmdns = CapturingJmDNS(),
            port = port,
            selfId = selfId,
            wsPath = wsPath,
            httpClientFactory = ::freshClient,
        )

        fun loomWithDispatcher(wsPath: String, dispatcher: CoroutineDispatcher): MDNSPeerLinkFactory = mdnsLoom(
            serviceType = MDNSServiceType("_kuilt-test._tcp"),
            application = server.application,
            jmdns = CapturingJmDNS(),
            port = port,
            dispatcher = dispatcher,
            wsPath = wsPath,
            httpClientFactory = ::freshClient,
        )

        private fun freshClient(): HttpClient =
            HttpClient(OkHttp) { install(ClientWebSockets) }.also { clients += it }

        fun advertisement(host: MDNSPeerLinkFactory, wsPath: String) = MDNSAdvertisement(
            host = "localhost",
            port = port,
            serverPeerId = host.selfPeerId,
            sessionName = "mdns-loom-factory",
            wsPath = wsPath,
        )

        /** Host and joiner, connected over the real socket, inside one bounded window. */
        fun pair(
            hostLoom: MDNSPeerLinkFactory,
            joinerLoom: MDNSPeerLinkFactory,
            hostWsPath: String,
        ): Pair<Seam, Seam> = runBlocking {
            withTimeout(WINDOW) {
                val hosting = async { hostLoom.host(Pattern("mdns-loom-factory")).also { openSeams += it } }
                val joined = joinerLoom.join(advertisement(hostLoom, hostWsPath)).also { openSeams += it }
                val hostSeam = hosting.await()
                // The host learns the joiner off the `?peer=` query at accept time, so `peers` already
                // holds it by the time `nextLink` hands the seam over; no settling needed.
                hostSeam to joined
            }
        }
    }

    /**
     * Counts dispatches and delegates. Not a scheduling policy — it exists only so a test can ask
     * "did the seam's coroutines run *here*", which is the only way to tell a forwarded `dispatcher`
     * argument from a dropped one.
     */
    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private val count = AtomicInteger()

        val dispatches: Int get() = count.get()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            count.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    private companion object {
        /** One bounded window for every real-socket await, sized for a cold, contended CI box. */
        val WINDOW = 30.seconds
    }
}
