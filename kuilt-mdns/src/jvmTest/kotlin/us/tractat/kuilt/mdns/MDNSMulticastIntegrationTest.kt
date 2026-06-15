package us.tractat.kuilt.mdns

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import us.tractat.kuilt.core.PeerId
import java.net.NetworkInterface
import javax.jmdns.JmDNS

/**
 * Real-multicast integration tests for [MDNSServiceAdvertiser] + [MDNSServiceDiscoverer].
 *
 * These tests bind two real [JmDNS] instances to the first non-loopback, up,
 * multicast-capable network interface and exercise the full mDNS advertisement
 * and discovery path over genuine multicast traffic.
 *
 * **Opt-in:** The tests are skipped unless the system property
 * `mdns.multicast.tests` is set to `true`. Run with:
 *
 * ```
 * ./gradlew :transport-mdns:jvmTest -Pmdns.multicast.tests=true
 * ```
 *
 * (The `build.gradle.kts` for `:transport-mdns` forwards the Gradle project
 * property to a JVM system property so this flag works without `-D`.)
 *
 * **Why skipped by default:** multicast is unreliable in CI environments
 * (containers, VMs, or machines without an active LAN interface). These tests
 * are meant to be run manually on a developer machine with a real NIC.
 */
class MDNSMulticastIntegrationTest {
    private var advertiserJmdns: JmDNS? = null
    private var discovererJmdns: JmDNS? = null

    @Before
    fun setUp() {
        Assume.assumeTrue(
            "Skipped: set -Pmdns.multicast.tests=true to run real-multicast integration tests",
            System.getProperty("mdns.multicast.tests") == "true",
        )
        val iface =
            multicastInterface() ?: run {
                Assume.assumeTrue(
                    "Skipped: no non-loopback, up, multicast-capable network interface found",
                    false,
                )
                return
            }
        val address =
            iface.inetAddresses
                .toList()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?: run {
                    Assume.assumeTrue(
                        "Skipped: interface ${iface.name} has no usable non-loopback address",
                        false,
                    )
                    return
                }

        advertiserJmdns = JmDNS.create(address, "fireworks-advertiser")
        discovererJmdns = JmDNS.create(address, "fireworks-discoverer")
    }

    @After
    fun tearDown() {
        advertiserJmdns?.close()
        discovererJmdns?.close()
    }

    /**
     * One [JmDNS] instance registers a [MDNSServiceAdvertiser] service; a second
     * instance discovers it via [MDNSServiceDiscoverer]. Asserts that the emitted
     * [MDNSAdvertisement] matches the advertised peerId, port, wsPath, and host.
     *
     * Allows up to 10 seconds for mDNS announcement propagation.
     */
    @Test
    fun `advertiser registers and discoverer finds the service via real multicast`() {
        val selfId = PeerId("multicast-test-peer")
        val port = 29001
        val wsPath = "/peer"
        val advertiserJmdns = requireNotNull(advertiserJmdns)
        val discovererJmdns = requireNotNull(discovererJmdns)

        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = "_kuilt-test._tcp.local.",
                jmdns = advertiserJmdns,
                displayName = "MulticastIntegrationTest",
                port = port,
                selfId = selfId,
                wsPath = wsPath,
            )
        advertiser.register()

        try {
            val discovered =
                runBlocking {
                    withTimeout(10_000) {
                        MDNSServiceDiscoverer("_kuilt-test._tcp.local.", discovererJmdns)
                            .discoveries()
                            .first { it.serverPeerId == selfId }
                    }
                }

            assertEquals(selfId, discovered.serverPeerId)
            assertEquals(port, discovered.port)
            assertEquals(wsPath, discovered.wsPath)
        } finally {
            advertiser.unregister()
        }
    }

    private fun multicastInterface(): NetworkInterface? =
        NetworkInterface
            .getNetworkInterfaces()
            ?.toList()
            ?.firstOrNull { it.isUp && !it.isLoopback && it.supportsMulticast() && hasUsableAddress(it) }

    private fun hasUsableAddress(iface: NetworkInterface): Boolean = iface.inetAddresses.toList().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
}
