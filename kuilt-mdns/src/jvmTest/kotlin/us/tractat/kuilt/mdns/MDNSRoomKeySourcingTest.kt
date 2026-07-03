package us.tractat.kuilt.mdns

import io.ktor.client.HttpClient
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.test.assertAll
import java.net.ServerSocket
import javax.jmdns.ServiceInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies that the mDNS **host entry points** — [MDNSMultiAcceptHost] and
 * [MDNSPeerLinkFactory] — source the room identity from the host's [Pattern]
 * and write it into the advertised TXT record, so the room-bound admission
 * guard (#1172) is **default-on** for any host that declared a room (#1189).
 *
 * The policy wired here (fable second opinion, 2026-07-03): [Pattern.roomKey]
 * is now nullable and defaults to `null`, and the entry points source it
 * **unconditionally**. A plain `Pattern(displayName)` therefore advertises **no**
 * room and stays permissive; a `Pattern(displayName, roomKey = …)` advertises
 * the room and a discovering joiner ends up with a matching non-null
 * `Tag.roomKey` (its `Hello.targetRoom`). No `roomKey != displayName` sentinel —
 * the null default carries the permissive semantics directly, without adding a
 * third meaning to `displayName` (see #1177).
 *
 * All JmDNS multicast is absorbed by [CapturingJmDNS] (defined in
 * [MDNSServiceAdvertiserTest]), so these are **not** `-P`-gated. The byte path
 * is only exercised as far as the advertised [ServiceInfo]; the round-trip back
 * to a joiner's `Tag` is closed by parsing that record through the real
 * [MDNSServiceDiscoverer.toAdvertisement].
 */
class MDNSRoomKeySourcingTest {

    private val servers = mutableListOf<EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>>()
    private val jobs = mutableListOf<Job>()
    private val clients = mutableListOf<HttpClient>()

    @AfterTest
    fun tearDown() {
        jobs.forEach { it.cancel() }
        jobs.clear()
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
     * registers the mDNS service and then blocks awaiting the first joiner, so it runs
     * on a tracked [Job] and we wait (bounded) for the registration to land.
     */
    private suspend fun advertisePeerLink(pattern: Pattern): ServiceInfo {
        val port = ServerSocket(0).use { it.localPort }
        val jmdns = CapturingJmDNS()
        lateinit var factory: MDNSPeerLinkFactory
        val server = embeddedServer(Netty, port = port) {
            factory = MDNSPeerLinkFactory(
                serviceType = MDNSServiceType("_kuilt-test._tcp"),
                application = this,
                jmdns = jmdns,
                port = port,
                wsPath = "/ws/room-source-link",
                httpClientFactory = { HttpClient().also { clients += it } },
            )
        }.also { servers += it }
        server.start(wait = false)

        // weave(New) registers, then suspends forever awaiting a joiner — run it detached.
        jobs += CoroutineScope(Dispatchers.IO).launch { factory.weave(Rendezvous.New(pattern)) }
        val registered = withTimeoutOrNull(3_000) {
            while (jmdns.lastRegistered == null) delay(10)
            jmdns.lastRegistered
        }
        return assertNotNull(registered, "weave(New) must register the mDNS service")
    }

    /** Closes the round-trip: parse the advertised record back into the Tag a joiner would discover. */
    private fun parseBack(info: ServiceInfo): MDNSAdvertisement? =
        MDNSServiceDiscoverer(MDNSServiceType("_kuilt-test._tcp"), CapturingJmDNS())
            .toAdvertisement(info, host = "localhost")
}
