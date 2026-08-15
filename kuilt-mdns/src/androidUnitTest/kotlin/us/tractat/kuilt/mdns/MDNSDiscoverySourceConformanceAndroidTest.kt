package us.tractat.kuilt.mdns

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.yield
import us.tractat.kuilt.conformance.DepartureFixture
import us.tractat.kuilt.conformance.DiscoverySourceConformanceSuite
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CONFORMANCE_SERVICE_TYPE = MDNSServiceType("_kuilt-conformance._tcp")
private const val ARRIVING_SERVICE_NAME = "conformance-peer"
private const val ARRIVING_PEER_ID = "peer-conformance-1"
private const val ARRIVING_PORT = 19500
private const val ARRIVING_HOST = "192.168.9.9"

/**
 * Binds androidMain's [MDNSServiceDiscoverer] to [DiscoverySourceConformanceSuite] — the #1903
 * regression harness.
 *
 * Before the fix this class discarded `onServiceLost` and returned `emptyFlow()` from
 * `departures()`, so both of the suite's toothy properties red against it:
 * `departureKeyEqualsThePeerKeyThatWasDiscovered` with "departures() emitted nothing after the
 * fixture's cause ran", and `departuresEmitsWithNoConcurrentDiscoveriesCollector` earlier still, at
 * [RegistryNsdBrowser.awaitRegistrations] — a source whose departure feed is `emptyFlow()` opens no
 * browse registration to announce into.
 *
 * **Why a fake [NsdBrowser] and not a fake `NsdManager`.** `NsdManager` is `final` with a
 * package-private constructor, so it can be neither subclassed nor constructed; `NsdServiceInfo` is
 * `final` too and its accessors throw `Stub!` against `android.jar` in a unit test. A seam in
 * production is the only way an Android unit test reaches this code at all — see [NsdBrowser].
 *
 * **What this harness cannot see.** [NsdManagerBrowser] itself — the real NSD listener wiring and
 * the serialised resolve queue — is not exercised here, because nothing in this repo can drive the
 * Android framework in a unit test. That code moved verbatim out of the previous `discoveries()`
 * body and is unchanged by #1903, so this is a gap held constant rather than a gap opened; closing
 * it needs Robolectric or an instrumented test, neither of which exists in this repo yet.
 *
 * The fake delivers every callback synchronously on the calling thread, so the suite keeps its
 * **virtual** [awaitBudget].
 */
class MDNSDiscoverySourceConformanceAndroidTest : DiscoverySourceConformanceSuite() {

    private val browsersBySource = mutableMapOf<PeerDiscoverySource, RegistryNsdBrowser>()

    override fun newSource(): PeerDiscoverySource {
        val browser = RegistryNsdBrowser()
        return MDNSServiceDiscoverer(CONFORMANCE_SERVICE_TYPE, browser)
            .also { browsersBySource[it] = browser }
    }

    /**
     * Waits for the collectors' browse registrations to be live, then announces one service.
     *
     * The wait is the whole reason this is `suspend`. `callbackFlow` opens its registration inside a
     * separately *launched* producer coroutine, so the suite's `onStart` handshake proves the
     * collection began — not that [NsdBrowser.browse] has run. Announcing into an empty registration
     * list looks exactly like a source that ignores arrivals, and
     * [departuresEmitsWithNoConcurrentDiscoveriesCollector] would then red on the harness's own
     * timing while blaming the source. That is precisely the failure `causeArrival`'s "must not
     * return until the peer is genuinely visible" contract exists to forbid.
     */
    override suspend fun causeArrival(source: PeerDiscoverySource) {
        val browser = browserFor(source)
        browser.awaitRegistrations()
        browser.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)
    }

    /**
     * Closes over the service *name* — a constant [causeArrival] itself established — and nothing
     * the arrival emitted, as [DepartureFixture.Emits] requires: in
     * [departuresEmitsWithNoConcurrentDiscoveriesCollector] no discovered [Tag] exists to reach for.
     */
    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits { browserFor(source).unregister(ARRIVING_SERVICE_NAME) }

    private fun browserFor(source: PeerDiscoverySource): RegistryNsdBrowser =
        browsersBySource[source] ?: error("source was not built by newSource(): $source")
}

/**
 * Proves the harness above is actually rigged for the defects it claims to catch.
 *
 * A green binding shows a property *can* pass; it cannot show the property would notice anything. A
 * fake permissive in the wrong place — one that handed the peer id back on removal, or replayed to a
 * registration that was never open — would green a broken source while looking identical, which is
 * the vacuity this plan exists to remove.
 *
 * The rig suites below are `abstract` and instantiated as anonymous subclasses for the reason
 * `DiscoverySourceConformanceSuiteRigTest` records: a concrete subclass inherits four `@Test`
 * methods, so the runner would collect it as a test class of its own.
 */
class MDNSAndroidDepartureRigTest {

    // ── the fake is honest about what a removal carries ──────────────────────

    /**
     * The removal path carries the service **name**, never the peer id — the #1903 defect itself.
     *
     * [NsdBrowseSink.onLost] takes only a `String`, so half of this is enforced by the compiler.
     * What is pinned here is the other half: that the harness's name and peer id are *different
     * values*. A fixture using one string for both would green a source emitting either, and the
     * key-equality obligation would assert nothing.
     */
    @Test
    fun theRemovalTheHarnessFiresCarriesTheServiceNameAndNotThePeerId() {
        val browser = RegistryNsdBrowser()
        val seen = RecordingSink()
        browser.browse(CONFORMANCE_SERVICE_TYPE.forNsd(), seen)

        browser.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)
        browser.unregister(ARRIVING_SERVICE_NAME)

        val resolved = seen.resolved ?: error("a registration that asks must be resolved")
        assertAll(
            { assertEquals(ARRIVING_SERVICE_NAME, seen.lost, "a removal is keyed by service name") },
            {
                assertEquals(
                    ARRIVING_PEER_ID,
                    resolved.attributes[MDNSAdvertisement.TXT_KEY_PEER_ID],
                    "resolution is the only place the peer id is ever visible",
                )
            },
            {
                assertTrue(
                    ARRIVING_SERVICE_NAME != ARRIVING_PEER_ID,
                    "the harness's service name and peer id must differ, or the key-equality " +
                        "obligation cannot tell a correct departure from a wrongly-keyed one",
                )
            },
        )
    }

    /**
     * A registration that never calls `requestResolve` is never resolved, however many siblings do.
     *
     * This is the fixture setting that keeps the lone-collector obligation from being vacuous. Were
     * the fake to resolve unprompted, a `departures()` that free-rides on a concurrent
     * `discoveries()` collector's resolutions would pass every property here — the exact shape
     * #1917 named on the JVM.
     */
    @Test
    fun aRegistrationThatNeverAsksIsNeverResolvedEvenWhileASiblingAsks() {
        val browser = RegistryNsdBrowser()
        val asks = RecordingSink()
        val neverAsks = RecordingSink(resolves = false)
        browser.browse(CONFORMANCE_SERVICE_TYPE.forNsd(), asks)
        browser.browse(CONFORMANCE_SERVICE_TYPE.forNsd(), neverAsks)

        browser.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)

        assertAll(
            { assertEquals(ARRIVING_SERVICE_NAME, neverAsks.found, "every registration is told the name") },
            { assertNull(neverAsks.resolved, "a registration that never asks must never be resolved") },
            { assertEquals(ARRIVING_PEER_ID, asks.resolved?.attributes?.get(MDNSAdvertisement.TXT_KEY_PEER_ID)) },
        )
    }

    /**
     * A registration opened *after* an announcement sees nothing.
     *
     * This is what gives the lone-collector obligation its teeth on this harness: the fake never
     * replays, so a `departures()` that opened no registration of its own — or opened one late —
     * has no way to learn anything.
     */
    @Test
    fun aRegistrationOpenedAfterTheAnnouncementSeesNothing() {
        val browser = RegistryNsdBrowser()
        browser.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)

        val late = RecordingSink()
        browser.browse(CONFORMANCE_SERVICE_TYPE.forNsd(), late)

        assertNull(late.resolved, "the fake must not replay to a registration opened afterwards")
    }

    /** A stopped registration stops hearing — the receipt behind `awaitClose { handle.stop() }`. */
    @Test
    fun aStoppedRegistrationHearsNothingFurther() {
        val browser = RegistryNsdBrowser()
        val seen = RecordingSink()
        browser.browse(CONFORMANCE_SERVICE_TYPE.forNsd(), seen).stop()

        browser.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)

        assertNull(seen.resolved, "a stopped registration must hear nothing further")
    }

    // ── the properties red against sources broken on this same fake ──────────

    @Test
    fun aDepartureCarryingTheServiceNameRedsTheKeyEqualityObligation() {
        val failure = assertFailsWith<AssertionError> {
            (object : WrongKeySuite() {}).departureKeyEqualsThePeerKeyThatWasDiscovered()
        }
        assertTrue(
            failure.message.orEmpty().contains("SAME key"),
            "expected the key-equality obligation to red, got: ${failure.message}",
        )
    }

    @Test
    fun aLeaveSignalOnlyRunningWhileDiscoveriesIsCollectedRedsTheLoneCollectorObligation() {
        val failure = assertFailsWith<AssertionError> {
            (object : ParasiticSuite() {}).departuresEmitsWithNoConcurrentDiscoveriesCollector()
        }
        assertTrue(
            failure.message.orEmpty().contains("collected on its own"),
            "expected the lone-collector obligation to red, got: ${failure.message}",
        )
    }

    /** …and that same source passes the obligation collecting both feeds, so the red above is specific. */
    @Test
    fun theParasiticSourceStillPassesTheObligationThatCollectsBothFeeds() {
        (object : ParasiticSuite() {}).departureKeyEqualsThePeerKeyThatWasDiscovered()
    }

    /**
     * The canonical free-riding shape reds the lone-collector obligation: a peer-id map hoisted to a
     * field that only `discoveries()`' sink ever writes.
     *
     * This is the rig that pins the sub-property the seam boundary was chosen to keep testable — a
     * `departures()` that opens its own registration but never requests its own resolutions. Under a
     * seam that resolved on the caller's behalf this source would be unwritable, and the obligation
     * would be discharged by construction rather than by test.
     */
    @Test
    fun aDeparturesFeedThatNeverRequestsItsOwnResolutionsRedsTheLoneCollectorObligation() {
        val failure = assertFailsWith<AssertionError> {
            (object : FreeRidingSuite() {}).departuresEmitsWithNoConcurrentDiscoveriesCollector()
        }
        assertTrue(
            failure.message.orEmpty().contains("collected on its own"),
            "expected the lone-collector obligation to red, got: ${failure.message}",
        )
    }

    /** …and it too passes the obligation that collects both feeds, so that red is specific. */
    @Test
    fun theFreeRidingSourceStillPassesTheObligationThatCollectsBothFeeds() {
        (object : FreeRidingSuite() {}).departureKeyEqualsThePeerKeyThatWasDiscovered()
    }
}

// ── rig sources ───────────────────────────────────────────────────────────────

/** Captures the last of each callback, for the fake-honesty assertions. */
private class RecordingSink(
    /** Whether to ask for resolution, so a rig can model a listener that never does. */
    private val resolves: Boolean = true,
) : NsdBrowseSink {
    var found: String? = null
    var resolved: NsdRecord? = null
    var lost: String? = null

    override fun onFound(
        serviceName: String,
        requestResolve: () -> Unit,
    ) {
        found = serviceName
        if (resolves) requestResolve()
    }

    override fun onResolved(record: NsdRecord) {
        resolved = record
    }

    override fun onLost(serviceName: String) {
        lost = serviceName
    }

    override fun onFailed(cause: Throwable) = Unit
}

/**
 * Emits the mDNS **service name** on departure — plausible in a log, useless to `discoveryRoster`,
 * and exactly what a name-keyed feed produces when the peer id is never remembered at resolution.
 */
private class WrongKeySource(
    private val browser: RegistryNsdBrowser,
) : PeerDiscoverySource {
    private val real = MDNSServiceDiscoverer(CONFORMANCE_SERVICE_TYPE, browser)

    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    override fun discoveries(): Flow<Tag> = real.discoveries()

    override fun departures(): Flow<String> =
        callbackFlow {
            val handle =
                browser.browse(
                    CONFORMANCE_SERVICE_TYPE.forNsd(),
                    object : NsdBrowseSink {
                        // Asks, like a correct listener: this rig is broken in the KEY it emits,
                        // not in whether it resolves, so it must red property 1 and not property 2.
                        override fun onFound(
                            serviceName: String,
                            requestResolve: () -> Unit,
                        ) = requestResolve()

                        override fun onResolved(record: NsdRecord) = Unit

                        override fun onLost(serviceName: String) {
                            trySend(serviceName)
                        }

                        override fun onFailed(cause: Throwable) = Unit
                    },
                )
            awaitClose { handle.stop() }
        }
}

/**
 * Opens a browse registration of its own — so the harness's own rig check passes — but only emits
 * while a `discoveries()` collection happens to be live. The free-riding shape #1917 named, which
 * every other property in the suite passes.
 */
private class ParasiticSource(
    private val browser: RegistryNsdBrowser,
) : PeerDiscoverySource {
    private val real = MDNSServiceDiscoverer(CONFORMANCE_SERVICE_TYPE, browser)
    private var browsing = false

    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    override fun discoveries(): Flow<Tag> =
        real.discoveries()
            .onStart { browsing = true }
            .onCompletion { browsing = false }

    override fun departures(): Flow<String> =
        callbackFlow {
            val peerIdsByServiceName = mutableMapOf<String, String>()
            val handle =
                browser.browse(
                    CONFORMANCE_SERVICE_TYPE.forNsd(),
                    object : NsdBrowseSink {
                        // Asks on its own account: this rig free-rides on the discoveries()
                        // COLLECTOR being live, not on its resolutions — a distinct defect from
                        // FreeRidingSource, and both must red the same obligation.
                        override fun onFound(
                            serviceName: String,
                            requestResolve: () -> Unit,
                        ) = requestResolve()

                        override fun onResolved(record: NsdRecord) {
                            record.attributes[MDNSAdvertisement.TXT_KEY_PEER_ID]
                                ?.let { peerIdsByServiceName[record.serviceName] = it }
                        }

                        override fun onLost(serviceName: String) {
                            val peerId = peerIdsByServiceName.remove(serviceName) ?: return
                            if (browsing) trySend(peerId)
                        }

                        override fun onFailed(cause: Throwable) = Unit
                    },
                )
            awaitClose { handle.stop() }
        }
}

/**
 * The canonical free-riding source: its `departures()` opens a browse registration of its own, but
 * never asks for a resolution — it reads a peer-id map that only `discoveries()`' sink ever fills.
 *
 * With both feeds collected it looks perfect. Collected alone, the map is empty and it emits
 * nothing, which is precisely what `discoveryRoster` produces: `merge` subscribes to the two feeds
 * in separately-launched coroutines, so the departure feed can attach without the arrival feed
 * having resolved anything yet.
 */
private class FreeRidingSource(
    private val browser: RegistryNsdBrowser,
) : PeerDiscoverySource {
    // Hoisted to a field, written only by discoveries()' sink. This is the defect.
    private val peerIdsByServiceName = mutableMapOf<String, String>()

    override val kind: DiscoveryKind = DiscoveryKind.Mdns

    override fun discoveries(): Flow<Tag> =
        callbackFlow {
            val handle =
                browser.browse(
                    CONFORMANCE_SERVICE_TYPE.forNsd(),
                    object : NsdBrowseSink {
                        override fun onFound(
                            serviceName: String,
                            requestResolve: () -> Unit,
                        ) = requestResolve()

                        override fun onResolved(record: NsdRecord) {
                            val peerId =
                                record.attributes[MDNSAdvertisement.TXT_KEY_PEER_ID] ?: return
                            peerIdsByServiceName[record.serviceName] = peerId
                            trySend(
                                MDNSAdvertisement(
                                    host = record.host ?: return,
                                    port = record.port,
                                    serverPeerId = PeerId(peerId),
                                    sessionName = record.serviceName,
                                ),
                            )
                        }

                        override fun onLost(serviceName: String) = Unit

                        override fun onFailed(cause: Throwable) = Unit
                    },
                )
            awaitClose { handle.stop() }
        }

    override fun departures(): Flow<String> =
        callbackFlow {
            val handle =
                browser.browse(
                    CONFORMANCE_SERVICE_TYPE.forNsd(),
                    object : NsdBrowseSink {
                        // Never asks. Whatever it knows, a sibling collector taught it.
                        override fun onFound(
                            serviceName: String,
                            requestResolve: () -> Unit,
                        ) = Unit

                        override fun onResolved(record: NsdRecord) = Unit

                        override fun onLost(serviceName: String) {
                            peerIdsByServiceName.remove(serviceName)?.let { trySend(it) }
                        }

                        override fun onFailed(cause: Throwable) = Unit
                    },
                )
            awaitClose { handle.stop() }
        }
}

private abstract class FreeRidingSuite : DiscoverySourceConformanceSuite() {
    private val browsers = mutableMapOf<PeerDiscoverySource, RegistryNsdBrowser>()

    override fun newSource(): PeerDiscoverySource {
        val browser = RegistryNsdBrowser()
        return FreeRidingSource(browser).also { browsers[it] = browser }
    }

    override suspend fun causeArrival(source: PeerDiscoverySource) {
        val browser = browsers.getValue(source)
        browser.awaitRegistrations()
        browser.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)
    }

    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits { browsers.getValue(source).unregister(ARRIVING_SERVICE_NAME) }
}

private abstract class WrongKeySuite : DiscoverySourceConformanceSuite() {
    private val browsers = mutableMapOf<PeerDiscoverySource, RegistryNsdBrowser>()

    override fun newSource(): PeerDiscoverySource {
        val browser = RegistryNsdBrowser()
        return WrongKeySource(browser).also { browsers[it] = browser }
    }

    override suspend fun causeArrival(source: PeerDiscoverySource) {
        val browser = browsers.getValue(source)
        browser.awaitRegistrations()
        browser.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)
    }

    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits { browsers.getValue(source).unregister(ARRIVING_SERVICE_NAME) }
}

private abstract class ParasiticSuite : DiscoverySourceConformanceSuite() {
    private val browsers = mutableMapOf<PeerDiscoverySource, RegistryNsdBrowser>()

    override fun newSource(): PeerDiscoverySource {
        val browser = RegistryNsdBrowser()
        return ParasiticSource(browser).also { browsers[it] = browser }
    }

    override suspend fun causeArrival(source: PeerDiscoverySource) {
        val browser = browsers.getValue(source)
        browser.awaitRegistrations()
        browser.register(ARRIVING_SERVICE_NAME, ARRIVING_PEER_ID, ARRIVING_PORT, ARRIVING_HOST)
    }

    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits { browsers.getValue(source).unregister(ARRIVING_SERVICE_NAME) }
}

// ── Harness ───────────────────────────────────────────────────────────────────

/**
 * A fake [NsdBrowser] that models the NSD lifecycle faithfully enough to reach #1903.
 *
 * Four behaviours are load-bearing, each read off the platform contract:
 *
 *  1. **A removal carries only the service name.** `NsdManager` hands `onServiceLost` an
 *     *unresolved* `NsdServiceInfo` whose `attributes` map is empty, so a source that never
 *     remembered the peer id at resolution time has no way to produce one. That is the defect.
 *  2. **Nothing is replayed.** [register] fans out only to registrations open at that instant, so a
 *     `departures()` that opened none — the pre-fix `emptyFlow()` — learns nothing.
 *  3. **Resolution happens only on request**, and only for the registration that asked. This is what
 *     makes free-riding *visible*: a `departures()` that opens a registration but never calls
 *     `requestResolve` never learns a peer id, however busy a concurrent `discoveries()` collector
 *     is. Relaxing this to resolve automatically would green that source, which is exactly the
 *     fixture-configuration vacuity this repo keeps rediscovering.
 *  4. **Each [browse] call is independent.** Stopping one registration leaves the others hearing,
 *     mirroring one `NsdManager.discoverServices` call per listener.
 *
 * Callbacks fire synchronously on the caller's thread, so the whole harness runs in virtual time —
 * a deliberate simplification of the platform, which dispatches on its own threads.
 */
internal class RegistryNsdBrowser : NsdBrowser {

    private val sinks = mutableListOf<NsdBrowseSink>()
    private val registered = mutableSetOf<String>()

    override fun browse(
        serviceType: String,
        sink: NsdBrowseSink,
    ): NsdBrowseHandle {
        sinks += sink
        return NsdBrowseHandle { sinks -= sink }
    }

    /**
     * Suspend until every collection already under way has opened its browse registration.
     *
     * `callbackFlow` calls [browse] from a separately launched producer coroutine, which under a
     * `StandardTestDispatcher` is sitting in the scheduler's ready queue when the suite's `onStart`
     * handshake completes. `yield()` drains that queue; the loop keeps draining until a pass opens
     * nothing new, so it covers however many collectors a property opened without being told the
     * number.
     *
     * The closing [check] is the rig assertion: announcing into an empty registration list would
     * make the arrival unobservable, and every property that starts from one would then pass or fail
     * for reasons having nothing to do with the source. It also names the pre-#1903 defect
     * directly — a `departures()` returning `emptyFlow()` opens no registration at all.
     */
    suspend fun awaitRegistrations() {
        var previous = -1
        while (previous != sinks.size) {
            previous = sinks.size
            yield()
        }
        check(sinks.isNotEmpty()) {
            "RegistryNsdBrowser: no browse registration had opened by the time causeArrival fired. " +
                "An arrival nobody is browsing for is unobservable, so the property would report on " +
                "the harness's timing rather than on the source — and a departures() that returns " +
                "emptyFlow() (the pre-#1903 shape) opens no registration at all."
        }
    }

    /**
     * Announce a service to every registration open right now, delivering the resolved record only
     * to those that ask for it.
     *
     * NSD's found-then-resolve handshake is modelled rather than collapsed: `onFound` carries the
     * name alone, and the TXT map arrives only through the `requestResolve` the sink chose to call.
     * A sink that never calls it can never learn a peer id — which is the free-riding shape the
     * lone-collector obligation exists to catch.
     */
    fun register(
        serviceName: String,
        peerId: String,
        port: Int,
        host: String,
    ) {
        registered += serviceName
        val record =
            NsdRecord(
                serviceName = serviceName,
                port = port,
                host = host,
                attributes =
                    mapOf(
                        MDNSAdvertisement.TXT_KEY_PEER_ID to peerId,
                        MDNSAdvertisement.TXT_KEY_WS_PATH to MDNSAdvertisement.DEFAULT_WS_PATH,
                    ),
            )
        sinks.toList().forEach { sink -> sink.onFound(serviceName) { sink.onResolved(record) } }
    }

    /**
     * Withdraw a service: `onLost` to every registration, carrying the name and nothing else.
     *
     * Unknown names throw rather than returning quietly. A silent return fires no removal at all, so
     * the property reds with "departures() emitted nothing" and blames the source for a typo in the
     * fixture — the same rig-honesty failure [awaitRegistrations] exists to prevent, one method away.
     */
    fun unregister(serviceName: String) {
        check(registered.remove(serviceName)) {
            "RegistryNsdBrowser: no service named $serviceName is registered; the departure " +
                "fixture would fire nothing and the property would blame the source"
        }
        sinks.toList().forEach { it.onLost(serviceName) }
    }
}
