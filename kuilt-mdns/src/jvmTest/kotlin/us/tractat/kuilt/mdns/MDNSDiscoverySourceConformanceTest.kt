package us.tractat.kuilt.mdns

import kotlinx.coroutines.yield
import us.tractat.kuilt.conformance.DepartureFixture
import us.tractat.kuilt.conformance.DiscoverySourceConformanceSuite
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val CONFORMANCE_SERVICE_TYPE = MDNSServiceType(TEST_SERVICE_TYPE_JVM)
private const val ARRIVING_SERVICE_NAME = "conformance-peer"
private const val ARRIVING_PEER_ID = "peer-conformance-1"
private const val ARRIVING_PORT = 19500
private const val ARRIVING_HOST = "192.168.9.9"

/**
 * Binds jvmMain's [MDNSServiceDiscoverer] to [DiscoverySourceConformanceSuite] — the #1917
 * regression harness.
 *
 * Two of the suite's four properties have teeth here, and each fails against the pre-fix
 * implementation for a *different* reason, which is why both are needed:
 *
 *  - `departureKeyEqualsThePeerKeyThatWasDiscovered` — the old `serviceRemoved` read `peerId` off
 *    the removal event's own TXT, which JmDNS never populates, so nothing was ever emitted.
 *  - `departuresEmitsWithNoConcurrentDiscoveriesCollector` — the trap in #1917's *suggested* fix. A
 *    name→peerId map alone is not enough while `departures()`'s own `serviceAdded` is a no-op:
 *    nothing requests resolution, the map stays empty, and a lone collector still sees nothing.
 *    This is the shape `discoveryRoster` actually produces, because `merge` subscribes to the two
 *    feeds in separately-launched coroutines.
 *
 * Runs against [RegistryJmDNS] rather than real multicast, so the suite keeps its **virtual**
 * [awaitBudget]: the fake delivers every callback synchronously on the calling thread, advancing no
 * wall clock. What it cannot pin — that a real goodbye packet really does arrive with the TXT map
 * gone — is pinned by `MDNSMulticastIntegrationTest`, `-P`-gated.
 */
class MDNSDiscoverySourceConformanceTest : DiscoverySourceConformanceSuite() {

    private val jmdnsBySource = mutableMapOf<PeerDiscoverySource, RegistryJmDNS>()

    override fun newSource(): PeerDiscoverySource {
        val jmdns = RegistryJmDNS()
        return MDNSServiceDiscoverer(CONFORMANCE_SERVICE_TYPE, jmdns).also { jmdnsBySource[it] = jmdns }
    }

    /**
     * Waits for the collectors' listeners to be live, then announces one service.
     *
     * The wait is the whole reason this is `suspend`. `callbackFlow` registers its listener inside a
     * separately *launched* producer coroutine, so the suite's `onStart` handshake proves the
     * collection began — not that `addServiceListener` has run. Announcing into an empty listener
     * list looks exactly like a source that ignores arrivals, and
     * [departuresEmitsWithNoConcurrentDiscoveriesCollector] would then red on the harness's own
     * timing while blaming the source. That is precisely the failure `causeArrival`'s "must not
     * return until the peer is genuinely visible" contract exists to forbid.
     *
     * Once the listeners are live the announcement itself is synchronous: [RegistryJmDNS.register]
     * fans `serviceAdded` out to every one of them, and each listener's own `requestServiceInfo`
     * resolves inline, before `register` returns.
     */
    override suspend fun causeArrival(source: PeerDiscoverySource) {
        val jmdns = jmdnsFor(source)
        jmdns.awaitListenerRegistrations()
        jmdns.register(
            name = ARRIVING_SERVICE_NAME,
            peerId = ARRIVING_PEER_ID,
            port = ARRIVING_PORT,
            host = ARRIVING_HOST,
        )
    }

    /**
     * Closes over the service *name* — a constant [causeArrival] itself established — and nothing
     * the arrival emitted, as [DepartureFixture.Emits] requires: in
     * [departuresEmitsWithNoConcurrentDiscoveriesCollector] no discovered `Tag` exists to reach for.
     */
    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits { jmdnsFor(source).unregister(ARRIVING_SERVICE_NAME) }

    private fun jmdnsFor(source: PeerDiscoverySource): RegistryJmDNS =
        jmdnsBySource[source] ?: error("source was not built by newSource(): $source")
}

/**
 * Proves the harness above is actually rigged for the defect it claims to catch.
 *
 * Without this, [RegistryJmDNS] could quietly drift into handing the full TXT map back on
 * `serviceRemoved` — the one permissive detail that would make the whole conformance binding
 * vacuous, since the pre-fix `serviceRemoved` would then find its `peerId` and pass.
 */
class MDNSDepartureRigTest {

    @Test
    fun `the removal event the harness fires has a non-null info carrying no peerId TXT`() {
        val jmdns = RegistryJmDNS()
        val seen = RecordingServiceListener(jmdns)
        jmdns.addServiceListener(TEST_SERVICE_TYPE_JVM, seen)

        jmdns.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)
        jmdns.unregister(ARRIVING_SERVICE_NAME)

        val removed = assertNotNull(seen.removed, "the harness must fire serviceRemoved on unregister")
        // #1917's probe: `PROBE removed name=probe-service infoNull=false peerId=null`.
        val info = assertNotNull(removed.info, "JmDNS's removal event carries a non-null ServiceInfo")
        assertEquals(ARRIVING_SERVICE_NAME, removed.name)
        assertNull(
            info.getPropertyString(MDNSAdvertisement.TXT_KEY_PEER_ID),
            "the removal event must NOT carry the advertised TXT map — a harness that supplies it " +
                "greens the pre-fix implementation and makes the whole conformance binding vacuous",
        )
    }

    @Test
    fun `the arrival event the harness fires also carries no peerId TXT, so only resolution can supply it`() {
        val jmdns = RegistryJmDNS()
        val seen = RecordingServiceListener(jmdns)
        jmdns.addServiceListener(TEST_SERVICE_TYPE_JVM, seen)

        jmdns.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)

        val added = assertNotNull(seen.added, "the harness must fire serviceAdded on register")
        assertNull(
            assertNotNull(added.info).getPropertyString(MDNSAdvertisement.TXT_KEY_PEER_ID),
            "serviceAdded announces a PTR, not a TXT map: a listener that never resolves must have " +
                "no way to learn the peer id",
        )
        val resolved = assertNotNull(seen.resolved, "requestServiceInfo must deliver serviceResolved")
        assertEquals(ARRIVING_PEER_ID, assertNotNull(resolved.info).getPropertyString(MDNSAdvertisement.TXT_KEY_PEER_ID))
    }
}

// ── Harness ───────────────────────────────────────────────────────────────────

/**
 * A [FakeEventJmDNS] that models JmDNS's registration lifecycle faithfully enough to reach #1917 —
 * which the base fake structurally cannot, because its `requestServiceInfo` is a no-op and its
 * `serviceRemoved` is never fired at all. Against that fake, a listener that resolves for itself
 * and one that free-rides on a sibling listener are indistinguishable.
 *
 * Three behaviours are load-bearing, each read off the real-multicast probe recorded in #1917:
 *
 *  1. **`serviceAdded` carries no TXT.** Real JmDNS announces the PTR first; the advertised TXT map
 *     arrives only with resolution. So a listener that never calls [requestServiceInfo] can never
 *     learn a peer id, which is exactly the free-riding this suite must be able to see.
 *  2. **Resolution happens only on request**, and is then delivered to every listener of the type
 *     rather than to the requester alone (real JmDNS fans it out the same way; modelling it as
 *     requester-only would overstate how isolated the two flows are).
 *
 *     Request-only is deliberately **stricter than real JmDNS**, which also runs a `ServiceResolver`
 *     timer per listened type and resolves unprompted. Measured: the `-P`-gated multicast test
 *     greens against a fix that adds the name→peerId map and leaves `serviceAdded` a no-op, because
 *     that timer fills the map anyway. Strictness is the safe direction for a fixture — it can turn
 *     a race into a reliable red, never a defect into a green — and it is the only setting at which
 *     `departuresEmitsWithNoConcurrentDiscoveriesCollector` can see a source that free-rides on a
 *     sibling collector's resolution at all. Relaxing it to resolve automatically would green that
 *     property for every source, which is exactly the fixture-configuration vacuity this repo keeps
 *     rediscovering.
 *  3. **`serviceRemoved` carries a non-null `ServiceInfo` whose text is the qualified service name**
 *     — `props=[probe-service._fireworks._tcp.local.]` in the probe — never the advertised TXT map.
 *     This is the defect itself. [MDNSDepartureRigTest] asserts it, because a fake that handed the
 *     TXT back here would green the pre-fix code.
 *
 * Callbacks fire synchronously on the caller's thread, so the whole harness runs in virtual time.
 * That is a deliberate simplification of real JmDNS, which dispatches on its own threads — the
 * threading is what the `-P`-gated multicast test covers.
 */
internal class RegistryJmDNS : FakeEventJmDNS() {

    private val registered = mutableMapOf<String, ServiceInfo>()

    /**
     * Suspend until every collection already under way has registered its `ServiceListener`.
     *
     * `callbackFlow` calls `addServiceListener` from a separately launched producer coroutine, which
     * under a `StandardTestDispatcher` is sitting in the scheduler's ready queue when the suite's
     * `onStart` handshake completes. `yield()` drains that queue; the loop keeps draining until a
     * pass registers nobody new, so it covers however many collectors a property opened without
     * having to be told the number.
     *
     * The closing [check] is the rig assertion: firing an arrival at an empty listener list would
     * make the arrival unobservable, and every property that starts from one would then pass or
     * fail for reasons that have nothing to do with the source. Failing loudly here is the only
     * outcome that stays honest.
     */
    suspend fun awaitListenerRegistrations() {
        var previous = -1
        while (previous != listeners.size) {
            previous = listeners.size
            yield()
        }
        check(listeners.isNotEmpty()) {
            "RegistryJmDNS: no ServiceListener had registered by the time causeArrival fired. An " +
                "arrival nobody is listening for is unobservable, so the property would report on " +
                "the harness's timing rather than on the source."
        }
    }

    /** Announce a service: `serviceAdded` to every listener, with no TXT yet. */
    fun register(
        name: String,
        peerId: String,
        port: Int,
        host: String,
    ) {
        registered[name] = serviceInfoWithHost(name = name, port = port, host = host, peerId = peerId)
        val added = FakeServiceEvent(txtlessInfo(name, port))
        listeners.toList().forEach { it.serviceAdded(added) }
    }

    /**
     * Withdraw a service: `serviceRemoved` to every listener, with the TXT map gone (#1917).
     *
     * Unknown names throw rather than returning quietly. A silent return fires no `serviceRemoved`
     * at all, so the property reds with "departures() emitted nothing" and blames the source for a
     * typo in the fixture — the same rig-honesty failure [awaitListenerRegistrations] exists to
     * prevent, one method away.
     */
    fun unregister(name: String) {
        val info = registered.remove(name)
            ?: error(
                "RegistryJmDNS: no service named $name is registered; the departure fixture would " +
                    "fire nothing and the property would blame the source",
            )
        val removed = FakeServiceEvent(txtlessInfo(name, info.port))
        listeners.toList().forEach { it.serviceRemoved(removed) }
    }

    // All four `requestServiceInfo` overloads land on one implementation, exactly as JmDNSImpl
    // routes its three convenience forms into `requestServiceInfo(type, name, persistent, timeout)`.
    // Overriding only the arity production happens to call today would leave the others inheriting
    // FakeEventJmDNS's no-op, so a later change of overload — e.g. adopting the timeout-bounded form
    // to stop blocking JmDNS's single dispatch thread — would silently stop resolving here and red
    // `departuresEmitsWithNoConcurrentDiscoveriesCollector` as if the source had regressed.

    override fun requestServiceInfo(
        type: String,
        name: String,
    ): Unit = resolve(name)

    override fun requestServiceInfo(
        type: String,
        name: String,
        persistent: Boolean,
    ): Unit = resolve(name)

    override fun requestServiceInfo(
        type: String,
        name: String,
        timeout: Long,
    ): Unit = resolve(name)

    override fun requestServiceInfo(
        type: String,
        name: String,
        persistent: Boolean,
        timeout: Long,
    ): Unit = resolve(name)

    /**
     * Deliver `serviceResolved` for [name] to every listener of the type, if it is registered.
     *
     * Unlike [unregister] this stays quiet on an unknown name: JmDNS resolves whatever the browser
     * has seen announced, and a request for something that was never registered is a normal miss,
     * not a rigging mistake.
     */
    private fun resolve(name: String) {
        val info = registered[name] ?: return
        val resolved = FakeServiceEvent(info)
        listeners.toList().forEach { it.serviceResolved(resolved) }
    }
}

/**
 * The [ServiceInfo] shape JmDNS hands to `serviceAdded` and `serviceRemoved`: correct name, type
 * and port, with a text section holding the qualified service name instead of the advertised TXT
 * map — so `getPropertyString("peerId")` on it is null.
 */
private fun txtlessInfo(
    name: String,
    port: Int,
): ServiceInfo =
    ServiceInfo.create(
        TEST_SERVICE_TYPE_JVM,
        name,
        port,
        0,
        0,
        mapOf("$name.$TEST_SERVICE_TYPE_JVM" to ""),
    )

/**
 * Captures the last event of each kind, for the rig assertions above.
 *
 * Resolves on `serviceAdded` exactly as a real listener must, so that `resolved` is reachable at
 * all — the point being that it is reachable *only* that way.
 */
private class RecordingServiceListener(
    private val jmdns: JmDNS,
) : ServiceListener {
    var added: ServiceEvent? = null
    var resolved: ServiceEvent? = null
    var removed: ServiceEvent? = null

    override fun serviceAdded(event: ServiceEvent) {
        added = event
        jmdns.requestServiceInfo(event.type, event.name)
    }

    override fun serviceResolved(event: ServiceEvent) {
        resolved = event
    }

    override fun serviceRemoved(event: ServiceEvent) {
        removed = event
    }
}
