package us.tractat.kuilt.multipeer

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.yield
import platform.MultipeerConnectivity.MCNearbyServiceBrowser
import platform.MultipeerConnectivity.MCNearbyServiceBrowserDelegateProtocol
import platform.MultipeerConnectivity.MCPeerID
import us.tractat.kuilt.conformance.DepartureFixture
import us.tractat.kuilt.conformance.DiscoverySourceConformanceSuite
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import us.tractat.kuilt.multipeer.internal.MultipeerPeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CONFORMANCE_SERVICE_TYPE = "kuilt-conf2410"
private const val CONFORMANCE_DEVICE = "conformance-device"

/**
 * The arriving peer's `MCPeerID.displayName` — the decorated wire name, which is what
 * [MultipeerAdvertisement.peerKey] is and what a departure must carry.
 *
 * Decorated deliberately: [MultipeerPeerId.humanName] of it is a *different* string, which is the
 * one setting that lets the key-equality obligation tell a correct departure from one carrying the
 * name a human would recognise. See
 * [MultipeerAppleDepartureRigTest.theArrivingPeerHasAWireNameAndAHumanNameThatDiffer].
 */
private const val ARRIVING_HANDLE = "conformance-peer#a1b2c3d4"

/** How many scheduler turns [awaitBrowseDelegate] drains before declaring the session absent. */
private const val BROWSE_DELEGATE_YIELDS = 64

/**
 * Binds the production browser to the departure contract. The JVM drives fake JNA callbacks;
 * Apple drives the real browser delegate. Neither proves that the radio emits a departure.
 * Same-fixture controls below validate the harness independently of production.
 */
@OptIn(ExperimentalForeignApi::class)
class MultipeerAppleDiscoverySourceConformanceTest {

    @Test
    fun departureKeyEqualsThePeerKeyThatWasDiscovered() =
        onSuite(object : MultipeerAppleSuite() {}) { departureKeyEqualsThePeerKeyThatWasDiscovered() }

    @Test
    fun cancellingTheCollectorScopeCompletesDepartures() =
        onSuite(object : MultipeerAppleSuite() {}) { cancellingTheCollectorScopeCompletesDepartures() }

    @Test
    fun departuresEmitsWithNoConcurrentDiscoveriesCollector() =
        onSuite(object : MultipeerAppleSuite() {}) { departuresEmitsWithNoConcurrentDiscoveriesCollector() }

    @Test
    fun anArrivalIsNeverReportedAsADeparture() =
        onSuite(object : MultipeerAppleSuite() {}) { anArrivalIsNeverReportedAsADeparture() }

    // ── the control: this harness DOES admit a conforming source ─────────────

    @Test
    fun aSharedBrowseSessionPassesTheLoneCollectorObligation() =
        onSuite(object : SharedBrowseSuite() {}) { departuresEmitsWithNoConcurrentDiscoveriesCollector() }

    @Test
    fun aSharedBrowseSessionPassesTheArrivalIsNotADepartureObligation() =
        onSuite(object : SharedBrowseSuite() {}) { anArrivalIsNeverReportedAsADeparture() }

    @Test
    fun aSharedBrowseSessionPassesTheKeyEqualityObligation() =
        onSuite(object : SharedBrowseSuite() {}) { departureKeyEqualsThePeerKeyThatWasDiscovered() }
}

/**
 * Proves the harness above is rigged for the defects it claims to catch, and that the coupling it
 * pins is a property of the production factory rather than of the way this file drives it.
 */
@OptIn(ExperimentalForeignApi::class)
class MultipeerAppleDepartureRigTest {

    /**
     * The peer's wire name and its human name are different strings, and production publishes them
     * in those two roles.
     *
     * This is the one setting that gives `departureKeyEqualsThePeerKeyThatWasDiscovered` teeth here:
     * `browser(_:lostPeer:)` emits the wire name, and a fixture whose two names were the same string
     * would green a source emitting either.
     */
    @Test
    fun theArrivingPeerHasAWireNameAndAHumanNameThatDiffer() {
        val advertisement =
            MultipeerAdvertisement(
                handle = ARRIVING_HANDLE,
                sessionName = MultipeerPeerId.humanName(ARRIVING_HANDLE),
                serviceType = CONFORMANCE_SERVICE_TYPE,
            )
        assertAll(
            {
                assertNotEquals(
                    MultipeerPeerId.humanName(ARRIVING_HANDLE),
                    ARRIVING_HANDLE,
                    "the arriving peer's name must be decorated, or the key-equality obligation " +
                        "cannot tell a correct departure from one carrying the human name",
                )
            },
            { assertEquals(ARRIVING_HANDLE, advertisement.peerKey, "peerKey is the wire name") },
        )
    }

    /**
     * Obtaining either flow without collecting it leaves the factory idle.
     */
    @Test
    fun obtainingAFlowDoesNotStartBrowsing() {
        val factory = MultipeerPeerLinkFactory(CONFORMANCE_DEVICE, CONFORMANCE_SERVICE_TYPE)
        val source = MultipeerServiceBrowser(factory)

        assertAll(
            { assertNull(factory.activeBrowserDelegate, "a fresh factory is not browsing") },
            {
                // Obtaining the flow is not collecting it.
                source.departures()
                assertNull(factory.activeBrowserDelegate, "departures() opens no browse session of its own")
            },
        )
        factory.close()
    }

    /** A wrongly-keyed departure reds the key-equality obligation on this same harness. */
    @Test
    fun aDepartureCarryingTheHumanNameRedsTheKeyEqualityObligation() {
        val failure = assertFailsWith<AssertionError> {
            onSuite(object : WrongKeySuite() {}) { departureKeyEqualsThePeerKeyThatWasDiscovered() }
        }
        assertTrue(
            failure.message.orEmpty().contains("SAME key"),
            "expected the key-equality obligation to red, got: ${failure.message}",
        )
    }
}

/**
 * Run [property] against [suite], then close every factory it built.
 *
 * Each factory owns a real `MCPeerID` and, once either feed is collected, a real
 * `MCNearbyServiceBrowser`. Collection cleanup already tears the browser down on the
 * paths that opened one, so this is belt-and-braces rather than a leak fix — but a property that
 * fails part-way still needs cleanup, and a test binary that
 * leaves browsers advertising their interest is a poor neighbour on a shared CI host.
 */
@OptIn(ExperimentalForeignApi::class)
private fun <T> onSuite(
    suite: MultipeerAppleSuite,
    property: MultipeerAppleSuite.() -> T,
): T =
    try {
        suite.property()
    } finally {
        suite.closeFactories()
    }

// ── the bindings ──────────────────────────────────────────────────────────────

/**
 * The suite bound to production's [MultipeerServiceBrowser], driving the real `BrowserDelegate`.
 *
 * `abstract` so the runner does not collect it — see [MultipeerAppleDiscoverySourceConformanceTest].
 */
@OptIn(ExperimentalForeignApi::class)
private abstract class MultipeerAppleSuite : DiscoverySourceConformanceSuite() {

    /**
     * A plain map, where the JVM twin uses a `ConcurrentHashMap`: Kotlin/Native has no such class,
     * and it would buy nothing here anyway. Every write ([newSource]) and every read ([factoryFor])
     * happens on the test coroutine itself — the hooks the suite calls are all invoked from the test
     * body, never from a source's callback — so the map is confined to one coroutine rather than
     * merely to one dispatcher, which is a local property and not an emergent one.
     */
    private val factories = mutableMapOf<PeerDiscoverySource, MultipeerPeerLinkFactory>()

    /**
     * A peer id for the arriving peer, and a throwaway browser to pass the delegate.
     *
     * `BrowserDelegate` reads only `displayName` off the peer and ignores the browser argument
     * entirely, so neither has to be the one Apple would have handed it.
     */
    private val arrivingPeer = MCPeerID(displayName = ARRIVING_HANDLE)
    private val unusedBrowser =
        MCNearbyServiceBrowser(
            peer = MCPeerID(displayName = "unused-argument"),
            serviceType = CONFORMANCE_SERVICE_TYPE,
        )

    override fun newSource(): PeerDiscoverySource {
        val factory = MultipeerPeerLinkFactory(CONFORMANCE_DEVICE, CONFORMANCE_SERVICE_TYPE)
        return register(MultipeerServiceBrowser(factory), factory)
    }

    /**
     * Waits for the collectors' browse session to be live, then delivers one `foundPeer`.
     *
     * The suite's `onStart` handshake proves collection began, not that its upstream has
     * installed the delegate yet. Yield until the source has acquired its session before
     * delivering a callback; delivering into no delegate would test the harness instead.
     *
     * [awaitBrowseDelegate] throws rather than returning quietly when no delegate ever appears,
     * which is what turns this backend's coupling into a named failure instead of a silent pass.
     */
    override suspend fun causeArrival(source: PeerDiscoverySource) {
        val delegate = awaitBrowseDelegate(factoryFor(source))
        delegate.browser(unusedBrowser, foundPeer = arrivingPeer, withDiscoveryInfo = null)
    }

    /**
     * Closes over the peer id [causeArrival] itself established, and nothing the arrival emitted, as
     * [DepartureFixture.Emits] requires: in `departuresEmitsWithNoConcurrentDiscoveriesCollector` no
     * discovered [Tag] exists to reach for.
     *
     * Reads the delegate rather than re-deriving it, and fails loudly if the session has gone — a
     * departure fired into a torn-down browser would emit nothing and the property would blame the
     * source.
     */
    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits {
            val delegate = awaitBrowseDelegate(factoryFor(source))
            delegate.browser(unusedBrowser, lostPeer = arrivingPeer)
        }

    protected fun factoryFor(source: PeerDiscoverySource): MultipeerPeerLinkFactory =
        factories[source] ?: error("source was not built by newSource(): $source")

    /** Bind a source to the factory it was built against, so [factoryFor] resolves it. */
    protected fun register(
        source: PeerDiscoverySource,
        factory: MultipeerPeerLinkFactory,
    ): PeerDiscoverySource = source.also { factories[it] = factory }

    /** Tear down every factory this binding built — see [onSuite]. `close()` is idempotent. */
    fun closeFactories() {
        factories.values.forEach { it.close() }
        factories.clear()
    }

    /**
     * Drain the scheduler until the browse session's delegate appears, or fail with what that
     * absence means.
     *
     * Bounded rather than looping until quiescent so a missing session fails locally instead
     * of wedging until the test backstop ends.
     */
    protected suspend fun awaitBrowseDelegate(
        factory: MultipeerPeerLinkFactory,
    ): MCNearbyServiceBrowserDelegateProtocol {
        repeat(BROWSE_DELEGATE_YIELDS) {
            factory.activeBrowserDelegate?.let { return it }
            yield()
        }
        throw AssertionError("a collector of either feed must install a browser delegate")
    }
}

/**
 * The control: [SharedBrowseSource], whose one browse session is shared by both feeds.
 *
 * Every obligation passes here, on the same factory and through the same fixture, which is what
 * distinguishes "this backend is broken" from "this harness cannot satisfy the property".
 */
@OptIn(ExperimentalForeignApi::class)
private abstract class SharedBrowseSuite : MultipeerAppleSuite() {
    override fun newSource(): PeerDiscoverySource {
        val factory = MultipeerPeerLinkFactory(CONFORMANCE_DEVICE, CONFORMANCE_SERVICE_TYPE)
        return register(SharedBrowseSource(factory), factory)
    }
}

/** Emits the peer's *human* name on departure — plausible in a log, useless to `discoveryRoster`. */
@OptIn(ExperimentalForeignApi::class)
private abstract class WrongKeySuite : MultipeerAppleSuite() {
    override fun newSource(): PeerDiscoverySource {
        val factory = MultipeerPeerLinkFactory(CONFORMANCE_DEVICE, CONFORMANCE_SERVICE_TYPE)
        return register(SharedBrowseSource(factory, departureKey = { MultipeerPeerId.humanName(it) }), factory)
    }
}

// ── rig sources ───────────────────────────────────────────────────────────────

/**
 * A [PeerDiscoverySource] over the real [MultipeerPeerLinkFactory] whose single browse session is
 * opened by whichever feed subscribes first and stopped when the last one goes away.
 *
 * Deliberately built from the same `startBrowsing` / `stopBrowsing` / `lostPeerHandles` production
 * offers, and needing no second concurrent session — `startBrowsing`'s `check(browser == null)` is
 * what forbids `departures()` from simply opening one of its own, and a control that ignored that
 * would prescribe a fix nobody can build.
 *
 * @param departureKey what a lost peer's wire name is published as. The identity default is
 *   correct; the wrong-key rig overrides it.
 */
@OptIn(ExperimentalForeignApi::class)
private class SharedBrowseSource(
    private val factory: MultipeerPeerLinkFactory,
    private val departureKey: (String) -> String = { it },
) : PeerDiscoverySource {

    override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    // A lock rather than an argument about which dispatcher the collectors run on: the ref count and
    // the sink list are shared across both feeds' collections, and this repo does not let
    // correctness rest on where coroutines happen to be scheduled.
    private val lock = reentrantLock()
    private var refs = 0
    private val arrivalSinks = mutableListOf<(MultipeerAdvertisement) -> Unit>()

    override fun discoveries(): Flow<Tag> =
        callbackFlow {
            val sink: (MultipeerAdvertisement) -> Unit = { trySend(it) }
            lock.withLock { arrivalSinks += sink }
            attach()
            awaitClose {
                lock.withLock { arrivalSinks -= sink }
                detach()
            }
        }

    /** Register the subscriber before attach can start a browser and deliver callbacks. */
    override fun departures(): Flow<String> =
        factory.lostPeerHandles
            .onSubscription { attach() }
            .map { departureKey(it) }
            .onCompletion { detach() }

    private fun attach() =
        lock.withLock {
            if (refs++ == 0) {
                factory.startBrowsing { advertisement ->
                    lock.withLock { arrivalSinks.toList() }.forEach { it(advertisement) }
                }
            }
        }

    private fun detach() =
        lock.withLock {
            if (--refs == 0) factory.stopBrowsing()
        }
}
