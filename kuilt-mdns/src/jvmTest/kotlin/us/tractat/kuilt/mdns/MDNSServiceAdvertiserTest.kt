package us.tractat.kuilt.mdns

import us.tractat.kuilt.core.PeerId
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceTypeListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [MDNSServiceAdvertiser] using a [CapturingJmDNS] stub.
 *
 * Verifies that [MDNSServiceAdvertiser] calls [JmDNS.registerService] with the
 * correct [ServiceInfo] shape — without relying on actual multicast or loopback
 * mDNS propagation.
 */
private val TEST_SERVICE_TYPE = MDNSServiceType("_kuilt-test._tcp")

class MDNSServiceAdvertiserTest {
    @Test
    fun `register calls JmDNS registerService with the correct JmDNS-format service type`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "TestSession",
                port = 19001,
                selfId = PeerId("peer-1"),
            )

        advertiser.register()

        val info = fake.lastRegistered
        assertNotNull(info, "registerService should have been called")
        // JmDNS format: canonical + ".local."
        assertEquals("_kuilt-test._tcp.local.", info.type)
    }

    @Test
    fun `register produces ServiceInfo with correct display name`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "MySession",
                port = 19002,
                selfId = PeerId("peer-2"),
            )

        advertiser.register()

        assertEquals("MySession", fake.lastRegistered?.name)
    }

    @Test
    fun `register produces ServiceInfo with correct port`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "PortSession",
                port = 19003,
                selfId = PeerId("peer-3"),
            )

        advertiser.register()

        assertEquals(19003, fake.lastRegistered?.port)
    }

    @Test
    fun `register embeds peerId in TXT record`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "PeerSession",
                port = 19004,
                selfId = PeerId("my-peer-id"),
            )

        advertiser.register()

        val peerId = fake.lastRegistered?.getPropertyString(MDNSAdvertisement.TXT_KEY_PEER_ID)
        assertEquals("my-peer-id", peerId)
    }

    @Test
    fun `register embeds wsPath in TXT record`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "PathSession",
                port = 19005,
                selfId = PeerId("peer-5"),
                wsPath = "/custom",
            )

        advertiser.register()

        val wsPath = fake.lastRegistered?.getPropertyString(MDNSAdvertisement.TXT_KEY_WS_PATH)
        assertEquals("/custom", wsPath)
    }

    @Test
    fun `register embeds protocol version in TXT record`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "VersionSession",
                port = 19006,
                selfId = PeerId("peer-6"),
            )

        advertiser.register()

        val version = fake.lastRegistered?.getPropertyString(MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION)
        assertEquals(MDNSAdvertisement.PROTOCOL_VERSION, version)
    }

    // ── v2 TXT fields ─────────────────────────────────────────────────────────

    @Test
    fun `register embeds hostOs in TXT record when supplied`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "HostOsSession",
                port = 19010,
                selfId = PeerId("peer-10"),
                hostOs = MDNSAdvertisement.HostOs.Jvm,
            )

        advertiser.register()

        val hostOs = fake.lastRegistered?.getPropertyString(MDNSAdvertisement.TXT_KEY_HOST_OS)
        assertEquals("jvm", hostOs)
    }

    @Test
    fun `register omits hostOs from TXT record when not supplied`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "NoHostOs",
                port = 19011,
                selfId = PeerId("peer-11"),
            )

        advertiser.register()

        val hostOs = fake.lastRegistered?.getPropertyString(MDNSAdvertisement.TXT_KEY_HOST_OS)
        assertNull(hostOs)
    }

    @Test
    fun `register embeds fabrics in TXT record when supplied`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "FabricsSession",
                port = 19012,
                selfId = PeerId("peer-12"),
                fabrics = "ws,mc",
            )

        advertiser.register()

        val fabrics = fake.lastRegistered?.getPropertyString(MDNSAdvertisement.TXT_KEY_FABRICS)
        assertEquals("ws,mc", fabrics)
    }

    @Test
    fun `register embeds mcPeer in TXT record when supplied`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "McPeerSession",
                port = 19013,
                selfId = PeerId("peer-13"),
                fabrics = "ws,mc",
                mcPeer = "MCPeer-handle",
            )

        advertiser.register()

        val mcPeer = fake.lastRegistered?.getPropertyString(MDNSAdvertisement.TXT_KEY_MC_PEER)
        assertEquals("MCPeer-handle", mcPeer)
    }

    @Test
    fun `register embeds txtExtensions in TXT record`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "ExtensionsSession",
                port = 19014,
                selfId = PeerId("peer-14"),
                txtExtensions = mapOf("appVersion" to "5", "roomId" to "r1"),
            )

        advertiser.register()

        val info = fake.lastRegistered
        assertNotNull(info)
        assertEquals("5", info.getPropertyString("appVersion"))
        assertEquals("r1", info.getPropertyString("roomId"))
    }

    @Test
    fun `register omits optional v2 fields when not supplied`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "MinimalSession",
                port = 19015,
                selfId = PeerId("peer-15"),
            )

        advertiser.register()

        val info = fake.lastRegistered
        assertNotNull(info)
        assertNull(info.getPropertyString(MDNSAdvertisement.TXT_KEY_HOST_OS))
        assertNull(info.getPropertyString(MDNSAdvertisement.TXT_KEY_FABRICS))
        assertNull(info.getPropertyString(MDNSAdvertisement.TXT_KEY_MC_PEER))
    }

    @Test
    fun `unregister calls JmDNS unregisterService with the registered info`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "TeardownSession",
                port = 19007,
                selfId = PeerId("peer-7"),
            )

        advertiser.register()
        advertiser.unregister()

        assertNotNull(fake.lastUnregistered, "unregisterService should have been called")
        assertEquals("TeardownSession", fake.lastUnregistered?.name)
    }

    @Test
    fun `unregister before register does not call JmDNS unregisterService`() {
        val fake = CapturingJmDNS()
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = TEST_SERVICE_TYPE,
                jmdns = fake,
                displayName = "NeverRegistered",
                port = 19008,
                selfId = PeerId("peer-8"),
            )

        advertiser.unregister()

        assertNull(fake.lastUnregistered, "unregisterService must not be called if register was never called")
    }
}

/**
 * [JmDNS] stub that captures [registerService] / [unregisterService] calls.
 * All other abstract methods are stubbed as no-ops or sensible defaults.
 */
internal class CapturingJmDNS : JmDNS() {
    var lastRegistered: ServiceInfo? = null
    var lastUnregistered: ServiceInfo? = null

    override fun registerService(info: ServiceInfo) {
        lastRegistered = info
    }

    override fun unregisterService(info: ServiceInfo) {
        lastUnregistered = info
    }

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
    ) {}

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

    override fun addServiceListener(
        type: String,
        listener: ServiceListener,
    ) {}

    override fun removeServiceListener(
        type: String,
        listener: ServiceListener,
    ) {}

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
