package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.net.ServerSocket
import javax.jmdns.ServiceInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Verifies that the mDNS **host entry points** — [MDNSMultiAcceptHost] and
 * [MDNSPeerLinkFactory] — source the room identity from the host's [Pattern]
 * and write it into the advertised TXT record, so the room-bound admission
 * guard (#1172) is **default-on** for any host that declared a room (#1189).
 *
 * The policy wired here (fable second opinion, 2026-07-03): [Pattern.roomKey]
 * is nullable and defaults to `null`, and the entry points source it
 * **unconditionally**. A plain `Pattern(displayName)` therefore advertises **no**
 * room and stays permissive; a `Pattern(displayName, roomKey = …)` advertises
 * the room and a discovering joiner ends up with a matching non-null
 * `Tag.roomKey` (its `Hello.targetRoom`). No `roomKey != displayName` sentinel —
 * the null default carries the permissive semantics directly, without adding a
 * third meaning to `displayName` (see #1177).
 *
 * All JmDNS multicast is absorbed by [CapturingJmDNS] (defined in
 * [MDNSServiceAdvertiserTest]), so these are **not** `-P`-gated. Both entry points
 * are driven over the **real localhost byte path** (real Netty server + real OkHttp
 * client) exactly as [MDNSConformanceTest] does. `MDNSPeerLinkFactory.weave(New)`
 * blocks until a joiner connects, so a real joiner is driven concurrently to let it
 * complete — rather than cancelling a blocked coroutine, which deadlocks under a
 * constrained CI worker pool. The round-trip back to a joiner's `Tag` is closed by
 * parsing the advertised record through the real [MDNSServiceDiscoverer.toAdvertisement].
 *
 * Teardown mirrors [MDNSConformanceTest]: accepted seams closed ([CloseReason.Normal])
 * first, then the [HttpClient]s, then the servers — avoiding the documented
 * EOF-in-next-test flake.
 */
class MDNSRoomKeySourcingTest {

    private val servers = mutableListOf<EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>>()
    private val clients = mutableListOf<HttpClient>()
    private val openSeams = mutableListOf<Seam>()

    @AfterTest
    fun tearDown() {
        runBlocking { openSeams.forEach { runCatching { it.close(CloseReason.Normal) } } }
        openSeams.clear()
        clients.forEach { it.close() }
        clients.clear()
        servers.forEach { it.stop(gracePeriodMillis = 100, timeoutMillis = 1_000) }
        servers.clear()
    }

    // ── MDNSMultiAcceptHost ──────────────────────────────────────────────────

    @Test
    fun `MultiAcceptHost sources explicit roomKey from Pattern into the advertised TXT`() = runBlocking {
        val info = advertiseMultiAccept(Pattern("Alice's game", roomKey = "room-A"))

        val roundTripped = parseBack(info)
        assertAll(
            { assertEquals("room-A", info.getPropertyString(MDNSAdvertisement.TXT_KEY_ROOM), "TXT carries the room") },
            { assertEquals("room-A", roundTripped?.roomKey, "a discovering joiner ends up with a matching non-null targetRoom") },
        )
    }

    @Test
    fun `MultiAcceptHost with a default Pattern advertises no room and stays permissive`() = runBlocking {
        val info = advertiseMultiAccept(Pattern("Alice's game"))

        assertAll(
            { assertNull(info.getPropertyString(MDNSAdvertisement.TXT_KEY_ROOM), "no room in TXT for a default Pattern") },
            { assertNull(parseBack(info)?.roomKey, "the discovered Tag names no room (permissive)") },
        )
    }

    // ── MDNSPeerLinkFactory ──────────────────────────────────────────────────

    @Test
    fun `PeerLinkFactory sources explicit roomKey from the New Pattern into the advertised TXT`() = runBlocking {
        val info = advertisePeerLink(Pattern("Bob's game", roomKey = "room-B"))
        assertEquals("room-B", info.getPropertyString(MDNSAdvertisement.TXT_KEY_ROOM))
    }

    @Test
    fun `PeerLinkFactory with a default Pattern advertises no room`() = runBlocking {
        val info = advertisePeerLink(Pattern("Bob's game"))
        assertNull(info.getPropertyString(MDNSAdvertisement.TXT_KEY_ROOM))
    }

    // ── Harness ──────────────────────────────────────────────────────────────

    /** Stands up a [MDNSMultiAcceptHost] for [pattern], advertises once, returns the captured record. */
    private suspend fun advertiseMultiAccept(pattern: Pattern): ServiceInfo {
        val port = ServerSocket(0).use { it.localPort }
        val jmdns = CapturingJmDNS()
        lateinit var host: MDNSMultiAcceptHost
        val server = embeddedServer(Netty, port = port) {
            host = MDNSMultiAcceptHost(
                serviceType = MDNSServiceType("_kuilt-test._tcp"),
                application = this,
                jmdns = jmdns,
                port = port,
                pattern = pattern,
                wsPath = "/ws/room-source",
            )
        }.also { servers += it }
        server.start(wait = false)
        host.advertise()
        return assertNotNull(jmdns.lastRegistered, "advertise() must register a service")
    }

    /**
     * Drives [MDNSPeerLinkFactory.weave] on a `Rendezvous.New` for [pattern]. `weave`
     * registers the mDNS service and then blocks until the first joiner connects, so a real
     * OkHttp joiner is driven concurrently to let `weave` complete. The registration lands
     * during `weave` (before the block); the captured record is returned once both sides settle.
     */
    private suspend fun advertisePeerLink(pattern: Pattern): ServiceInfo {
        val port = ServerSocket(0).use { it.localPort }
        val jmdns = CapturingJmDNS()
        val wsPath = "/ws/room-source-link"
        lateinit var factory: MDNSPeerLinkFactory
        val server = embeddedServer(Netty, port = port) {
            factory = MDNSPeerLinkFactory(
                serviceType = MDNSServiceType("_kuilt-test._tcp"),
                application = this,
                jmdns = jmdns,
                port = port,
                wsPath = wsPath,
                httpClientFactory = { HttpClient(OkHttp) { install(ClientWebSockets) }.also { clients += it } },
            )
        }.also { servers += it }
        server.start(wait = false)

        return coroutineScope {
            // weave(New) registers then blocks awaiting a joiner — connect one so it completes.
            val hostSeam = async { factory.weave(Rendezvous.New(pattern)) }
            val joinerClient = HttpClient(OkHttp) { install(ClientWebSockets) }.also { clients += it }
            val joinerSeam = KtorClientLoom(joinerClient).join(
                WebSocketAdvertisement(
                    url = "ws://localhost:$port$wsPath",
                    serverPeerId = factory.selfPeerId,
                    displayName = "joiner",
                ),
            )
            openSeams += joinerSeam
            openSeams += hostSeam.await()
            assertNotNull(jmdns.lastRegistered, "weave(New) must register the mDNS service")
        }
    }

    /** Closes the round-trip: parse the advertised record back into the Tag a joiner would discover. */
    private fun parseBack(info: ServiceInfo): MDNSAdvertisement? =
        MDNSServiceDiscoverer(MDNSServiceType("_kuilt-test._tcp"), CapturingJmDNS())
            .toAdvertisement(info, host = "localhost")
}
