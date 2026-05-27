package us.tractat.kuilt.mdns

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Enumeration
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceInfo.Fields
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceTypeListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [MDNSServiceDiscoverer].
 *
 * [MDNSServiceDiscovererParserTest] tests [MDNSServiceDiscoverer.toAdvertisement]
 * directly (no network, fully deterministic).
 *
 * [MDNSServiceDiscovererFlowTest] tests the [Flow] mechanics using [FakeEventJmDNS].
 */
class MDNSServiceDiscovererParserTest {
    private val discoverer = MDNSServiceDiscoverer(CapturingJmDNS())

    @Test
    fun `toAdvertisement maps peerId port and wsPath correctly`() {
        val info = serviceInfoWithTxt("Game1", 19300, peerId = "peer-parser-1", wsPath = "/ws")

        val result = discoverer.toAdvertisement(info, "10.0.0.1")

        assertEquals(PeerId("peer-parser-1"), result?.serverPeerId)
        assertEquals(19300, result?.port)
        assertEquals("/ws", result?.wsPath)
        assertEquals("10.0.0.1", result?.host)
    }

    @Test
    fun `toAdvertisement returns null when peerId TXT entry is absent`() {
        val info =
            ServiceInfo.create(
                MDNSAdvertisement.SERVICE_TYPE,
                "NoPeerId",
                19301,
                0,
                0,
                mapOf("irrelevant" to "value"),
            )

        val result = discoverer.toAdvertisement(info, "10.0.0.2")

        assertNull(result)
    }

    @Test
    fun `toAdvertisement uses DEFAULT_WS_PATH when wsPath TXT is absent`() {
        val info =
            ServiceInfo.create(
                MDNSAdvertisement.SERVICE_TYPE,
                "NoWsPath",
                19302,
                0,
                0,
                mapOf(MDNSAdvertisement.TXT_KEY_PEER_ID to "peer-no-ws"),
            )

        val result = discoverer.toAdvertisement(info, "10.0.0.3")

        assertEquals(MDNSAdvertisement.DEFAULT_WS_PATH, result?.wsPath)
    }

    @Test
    fun `toAdvertisement sets displayName from ServiceInfo name`() {
        val info = serviceInfoWithTxt("Fireworks LAN", 19303, peerId = "peer-display")

        val result = discoverer.toAdvertisement(info, "10.0.0.4")

        assertEquals("Fireworks LAN", result?.displayName)
    }

    // ── v2 TXT field parsing ──────────────────────────────────────────────────

    @Test
    fun `toAdvertisement parses all v2 fields when present`() {
        val info =
            serviceInfoWithTxt(
                name = "V2Game",
                port = 19310,
                peerId = "peer-v2",
                extraTxt =
                    mapOf(
                        MDNSAdvertisement.TXT_KEY_HOST_OS to "jvm",
                        MDNSAdvertisement.TXT_KEY_FABRICS to "ws,mc",
                        MDNSAdvertisement.TXT_KEY_MC_PEER to "MCPeer-xyz",
                        MDNSAdvertisement.TXT_KEY_GAME_MIN_VERSION to "1",
                        MDNSAdvertisement.TXT_KEY_GAME_MAX_VERSION to "3",
                    ),
            )

        val result = discoverer.toAdvertisement(info, "10.0.0.5")

        assertEquals(MDNSAdvertisement.HostOs.Jvm, result?.hostOs)
        assertEquals("ws,mc", result?.fabrics)
        assertEquals("MCPeer-xyz", result?.mcPeer)
        assertEquals(1, result?.gameMinVersion)
        assertEquals(3, result?.gameMaxVersion)
    }

    @Test
    fun `toAdvertisement tolerates v1 record without v2 keys — backward compatibility`() {
        val info =
            ServiceInfo.create(
                MDNSAdvertisement.SERVICE_TYPE,
                "V1Game",
                19311,
                0,
                0,
                mapOf(
                    MDNSAdvertisement.TXT_KEY_PEER_ID to "peer-v1",
                    MDNSAdvertisement.TXT_KEY_WS_PATH to "/peer",
                    MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION to "1",
                ),
            )

        val result =
            checkNotNull(discoverer.toAdvertisement(info, "10.0.0.6")) {
                "v1 records must parse successfully"
            }

        assertEquals(PeerId("peer-v1"), result.serverPeerId)
        assertNull(result.hostOs)
        assertNull(result.fabrics)
        assertNull(result.mcPeer)
        assertNull(result.gameMinVersion)
        assertNull(result.gameMaxVersion)
    }

    @Test
    fun `toAdvertisement handles unknown hostOs value gracefully`() {
        val info =
            serviceInfoWithTxt(
                name = "UnknownOsGame",
                port = 19312,
                peerId = "peer-unknown-os",
                extraTxt = mapOf(MDNSAdvertisement.TXT_KEY_HOST_OS to "windows"),
            )

        val result = checkNotNull(discoverer.toAdvertisement(info, "10.0.0.7"))

        assertNull(result.hostOs, "Unknown hostOs value should parse to null, not throw")
    }

    @Test
    fun `toAdvertisement handles non-integer game version gracefully`() {
        val info =
            serviceInfoWithTxt(
                name = "BadVersionGame",
                port = 19313,
                peerId = "peer-bad-version",
                extraTxt =
                    mapOf(
                        MDNSAdvertisement.TXT_KEY_GAME_MIN_VERSION to "not-a-number",
                        MDNSAdvertisement.TXT_KEY_GAME_MAX_VERSION to "also-not-a-number",
                    ),
            )

        val result = checkNotNull(discoverer.toAdvertisement(info, "10.0.0.8"))

        assertNull(result.gameMinVersion)
        assertNull(result.gameMaxVersion)
    }
}

class MDNSServiceDiscovererFlowTest {
    @Test
    fun `discoveries emits an MDNSAdvertisement when serviceResolved fires`() {
        val fake = FakeEventJmDNS()
        val discoverer = MDNSServiceDiscoverer(fake)

        val info =
            serviceInfoWithHost(
                name = "ResolveMe",
                port = 19400,
                host = "192.168.1.5",
                peerId = "peer-resolved",
                wsPath = "/ws",
            )

        // The flow is cold — start collection first, then fire the event.
        val discovered =
            runBlocking {
                coroutineScope {
                    val deferred =
                        async {
                            withTimeout(2_000) { discoverer.discoveries().first() }
                        }
                    // Wait until the listener is actually registered before firing.
                    withTimeout(2_000) { fake.awaitListener() }
                    fake.simulateResolved(info)
                    deferred.await()
                }
            }

        assertEquals(PeerId("peer-resolved"), discovered.serverPeerId)
        assertEquals(19400, discovered.port)
        assertEquals("/ws", discovered.wsPath)
    }

    @Test
    fun `discoveries drops ServiceInfo with no peerId`() {
        val fake = FakeEventJmDNS()
        val discoverer = MDNSServiceDiscoverer(fake)

        val malformedInfo =
            serviceInfoWithHost(
                name = "Malformed",
                port = 19401,
                host = "192.168.1.6",
                txtMap = mapOf("irrelevant" to "true"),
            )
        val validInfo =
            serviceInfoWithHost(
                name = "Valid",
                port = 19402,
                host = "192.168.1.7",
                peerId = "peer-valid",
            )

        val discovered =
            runBlocking {
                coroutineScope {
                    val deferred =
                        async {
                            withTimeout(2_000) { discoverer.discoveries().first() }
                        }
                    withTimeout(2_000) { fake.awaitListener() }
                    fake.simulateResolved(malformedInfo)
                    fake.simulateResolved(validInfo)
                    deferred.await()
                }
            }

        assertEquals(PeerId("peer-valid"), discovered.serverPeerId)
    }

    @Test
    fun `discoveries removes listener when flow collection is cancelled`() {
        val fake = FakeEventJmDNS()
        val discoverer = MDNSServiceDiscoverer(fake)

        runBlocking {
            val flow = discoverer.discoveries()
            val job = launch { flow.first() }
            job.cancel()
            job.join()
        }

        assertEquals(0, fake.listenerCount, "Listener should be removed after flow cancellation")
    }
}

// ── Fakes and helpers ─────────────────────────────────────────────────────────

/**
 * A [JmDNS] stub that captures [ServiceListener]s and lets tests inject
 * [ServiceEvent]s directly — no network I/O.
 */
internal class FakeEventJmDNS : JmDNS() {
    private val listeners = mutableListOf<ServiceListener>()
    private val listenerAddedChannel = kotlinx.coroutines.channels.Channel<Unit>(capacity = 1)

    val listenerCount: Int get() = listeners.size

    /** Suspends until at least one [ServiceListener] has been registered. */
    suspend fun awaitListener() {
        if (listeners.isNotEmpty()) return
        listenerAddedChannel.receive()
    }

    fun simulateResolved(info: ServiceInfo) {
        val event = FakeServiceEvent(info)
        listeners.toList().forEach { it.serviceResolved(event) }
    }

    override fun addServiceListener(
        type: String,
        listener: ServiceListener,
    ) {
        listeners += listener
        listenerAddedChannel.trySend(Unit)
    }

    override fun removeServiceListener(
        type: String,
        listener: ServiceListener,
    ) {
        listeners -= listener
    }

    override fun requestServiceInfo(
        type: String,
        name: String,
    ) {}

    override fun close() {}

    override fun getName(): String = "fake"

    override fun getHostName(): String = "localhost"

    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getInterface(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getServiceInfo(
        type: String,
        name: String,
    ): ServiceInfo? = null

    override fun getServiceInfo(
        type: String,
        name: String,
        timeout: Long,
    ): ServiceInfo? = null

    override fun getServiceInfo(
        type: String,
        name: String,
        persistent: Boolean,
    ): ServiceInfo? = null

    override fun getServiceInfo(
        type: String,
        name: String,
        persistent: Boolean,
        timeout: Long,
    ): ServiceInfo? = null

    override fun requestServiceInfo(
        type: String,
        name: String,
        persistent: Boolean,
    ) {}

    override fun requestServiceInfo(
        type: String,
        name: String,
        timeout: Long,
    ) {}

    override fun requestServiceInfo(
        type: String,
        name: String,
        persistent: Boolean,
        timeout: Long,
    ) {}

    override fun addServiceTypeListener(listener: ServiceTypeListener) {}

    override fun removeServiceTypeListener(listener: ServiceTypeListener) {}

    override fun registerService(info: ServiceInfo) {}

    override fun unregisterService(info: ServiceInfo) {}

    override fun registerServiceType(type: String): Boolean = false

    override fun unregisterAllServices() {}

    override fun printServices() {}

    override fun list(type: String): Array<ServiceInfo> = emptyArray()

    override fun list(
        type: String,
        timeout: Long,
    ): Array<ServiceInfo> = emptyArray()

    override fun listBySubtype(type: String): Map<String, Array<ServiceInfo>> = emptyMap()

    override fun listBySubtype(
        type: String,
        timeout: Long,
    ): Map<String, Array<ServiceInfo>> = emptyMap()

    override fun getDelegate(): JmDNS.Delegate? = null

    override fun setDelegate(delegate: JmDNS.Delegate?): JmDNS.Delegate? = null
}

/**
 * Builds a [ServiceInfo] with map-based TXT properties, for use in parser tests.
 * [extraTxt] merges additional TXT entries on top of the mandatory v1 fields.
 */
private fun serviceInfoWithTxt(
    name: String,
    port: Int,
    peerId: String,
    wsPath: String = MDNSAdvertisement.DEFAULT_WS_PATH,
    extraTxt: Map<String, String> = emptyMap(),
): ServiceInfo =
    ServiceInfo.create(
        MDNSAdvertisement.SERVICE_TYPE,
        name,
        port,
        0,
        0,
        buildMap {
            put(MDNSAdvertisement.TXT_KEY_PEER_ID, peerId)
            put(MDNSAdvertisement.TXT_KEY_WS_PATH, wsPath)
            putAll(extraTxt)
        },
    )

/**
 * Builds a [ServiceInfo] that returns [host] from [getInetAddresses] and
 * uses map-based TXT properties for reliable [getPropertyString] lookups.
 */
private fun serviceInfoWithHost(
    name: String,
    port: Int,
    host: String,
    peerId: String? = null,
    wsPath: String = MDNSAdvertisement.DEFAULT_WS_PATH,
    txtMap: Map<String, String>? = null,
): ServiceInfo {
    val props: Map<String, String> =
        txtMap ?: buildMap {
            if (peerId != null) {
                put(MDNSAdvertisement.TXT_KEY_PEER_ID, peerId)
                put(MDNSAdvertisement.TXT_KEY_WS_PATH, wsPath)
            }
        }
    val delegate =
        ServiceInfo.create(
            MDNSAdvertisement.SERVICE_TYPE,
            name,
            port,
            0,
            0,
            props,
        )
    val inetAddress = InetAddress.getByName(host)
    @Suppress("OVERRIDE_DEPRECATION")
    return object : ServiceInfo() {
        override fun hasData(): Boolean = delegate.hasData()

        override fun getType(): String = delegate.type

        override fun getTypeWithSubtype(): String = delegate.typeWithSubtype

        override fun getName(): String = delegate.name

        override fun getKey(): String = delegate.key

        override fun getQualifiedName(): String = delegate.qualifiedName

        override fun getServer(): String = delegate.server

        override fun hasServer(): Boolean = delegate.hasServer()

        override fun getHostAddress(): String = host

        override fun getHostAddresses(): Array<String> = arrayOf(host)

        override fun getAddress(): InetAddress = inetAddress

        override fun getInetAddress(): InetAddress = inetAddress

        override fun getInet4Address(): Inet4Address? = inetAddress.takeIf { it is Inet4Address } as? Inet4Address

        override fun getInet6Address(): Inet6Address? = null

        override fun getInetAddresses(): Array<InetAddress> = arrayOf(inetAddress)

        override fun getInet4Addresses(): Array<Inet4Address> = getInet4Address()?.let { arrayOf(it) } ?: emptyArray()

        override fun getInet6Addresses(): Array<Inet6Address> = emptyArray()

        override fun getPort(): Int = delegate.port

        override fun getPriority(): Int = delegate.priority

        override fun getWeight(): Int = delegate.weight

        override fun getTextBytes(): ByteArray = delegate.textBytes

        @Suppress("DEPRECATION")
        override fun getTextString(): String? = delegate.textString

        override fun getURL(): String = "tcp://$host:$port"

        override fun getURLs(): Array<String> = arrayOf(getURL())

        override fun getURL(protocol: String): String = "$protocol://$host:$port"

        override fun getURLs(protocol: String): Array<String> = arrayOf(getURL(protocol))

        override fun getPropertyBytes(name: String): ByteArray? = delegate.getPropertyBytes(name)

        override fun getPropertyString(name: String): String? = delegate.getPropertyString(name)

        override fun getPropertyNames(): Enumeration<String> = delegate.propertyNames

        override fun getNiceTextString(): String = delegate.niceTextString

        override fun setText(text: ByteArray) {}

        override fun setText(props: Map<String, *>) {}

        override fun isPersistent(): Boolean = delegate.isPersistent

        override fun getDomain(): String = delegate.domain

        override fun getProtocol(): String = delegate.protocol

        override fun getApplication(): String = delegate.application

        override fun getSubtype(): String = delegate.subtype

        override fun getQualifiedNameMap(): Map<Fields, String> = delegate.qualifiedNameMap

        override fun hasSameAddresses(other: ServiceInfo): Boolean = false
    }
}

private class FakeServiceEvent(
    private val info: ServiceInfo,
) : ServiceEvent(info) {
    override fun getDNS(): JmDNS? = null

    override fun getType(): String = info.type

    override fun getName(): String = info.name

    override fun getInfo(): ServiceInfo = info
}
