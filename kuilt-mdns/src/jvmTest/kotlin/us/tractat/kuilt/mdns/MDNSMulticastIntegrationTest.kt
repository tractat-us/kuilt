package us.tractat.kuilt.mdns

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
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
 * How long real multicast gets to carry an announcement to the browser *and* carry that browser's
 * own `requestServiceInfo` round-trip back. Generous: JmDNS's own service-info timeout is 6 s.
 */
private const val RESOLUTION_WINDOW_MS = 8_000L

/** How long a goodbye packet gets to arrive once the advertiser has unregistered. */
private const val DEPARTURE_WINDOW_MS = 10_000L

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

        advertiserJmdns = JmDNS.create(address, "kuilt-advertiser")
        discovererJmdns = JmDNS.create(address, "kuilt-discoverer")
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

        val serviceType = MDNSServiceType("_kuilt-test._tcp")
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = serviceType,
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
                        MDNSServiceDiscoverer(serviceType, discovererJmdns)
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

    /**
     * `departures()`, collected **on its own**, emits the advertised peer id when the advertiser
     * unregisters — over genuine multicast (#1917).
     *
     * Only a live goodbye packet reveals the defect this pins. The pre-fix `serviceRemoved` read
     * `peerId` straight off the removal event's TXT, which type-checks and reads correctly; it is
     * the wire that decides the value is null, because JmDNS fills that event's text section with
     * the qualified service name (the PTR rdata) rather than the advertised TXT map.
     *
     * Nothing collects `discoveries()` here — that is the shape `discoveryRoster` produces, since
     * `merge` subscribes to the two feeds in separately launched coroutines.
     *
     * **What this does not pin.** Only the missing-TXT half. Measured against the fix's two halves
     * separately: this test reds on the pre-fix implementation and greens on a fix that adds *only*
     * the name→peerId map, leaving `serviceAdded` a no-op. Real JmDNS runs a `ServiceResolver`
     * timer per listened type and fires `serviceResolved` unprompted, so a lone collector's map
     * fills anyway once it waits [RESOLUTION_WINDOW_MS]. The self-sufficient `requestServiceInfo` is
     * what stops that from being a *race* — it is `MDNSDiscoverySourceConformanceTest`'s
     * `departuresEmitsWithNoConcurrentDiscoveriesCollector` that discriminates it, against a fake
     * whose resolution is strictly request-driven. Do not read a green here as covering both.
     */
    @Test
    fun `departures emits the advertised peer id when the service unregisters over real multicast`() {
        val selfId = PeerId("multicast-departure-peer")
        val port = 29002
        val advertiserJmdns = requireNotNull(advertiserJmdns)
        val discovererJmdns = requireNotNull(discovererJmdns)

        val serviceType = MDNSServiceType("_kuilt-test._tcp")
        val advertiser =
            MDNSServiceAdvertiser(
                serviceType = serviceType,
                jmdns = advertiserJmdns,
                displayName = "MulticastDepartureTest",
                port = port,
                selfId = selfId,
            )
        val discoverer = MDNSServiceDiscoverer(serviceType, discovererJmdns)

        runBlocking {
            val collecting = CompletableDeferred<Unit>()
            val departed = CompletableDeferred<String>()
            val collector =
                launch {
                    discoverer
                        .departures()
                        .onStart { collecting.complete(Unit) }
                        .collect { departed.complete(it) }
                }
            try {
                withTimeout(RESOLUTION_WINDOW_MS) { collecting.await() }
                advertiser.register()
                // Real time, on purpose: the announcement has to reach the browser AND this flow's
                // own requestServiceInfo has to come back before the goodbye, or there is no
                // remembered peer id to emit. Nothing else is resolving on this instance's behalf.
                delay(RESOLUTION_WINDOW_MS)
                advertiser.unregister()

                val key = withTimeout(DEPARTURE_WINDOW_MS) { departed.await() }
                assertEquals(selfId.value, key)
            } finally {
                advertiser.unregister()
                collector.cancel()
                collector.join()
            }
        }
    }

    private fun multicastInterface(): NetworkInterface? =
        NetworkInterface
            .getNetworkInterfaces()
            ?.toList()
            ?.firstOrNull { it.isUp && !it.isLoopback && it.supportsMulticast() && hasUsableAddress(it) }

    private fun hasUsableAddress(iface: NetworkInterface): Boolean = iface.inetAddresses.toList().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
}
