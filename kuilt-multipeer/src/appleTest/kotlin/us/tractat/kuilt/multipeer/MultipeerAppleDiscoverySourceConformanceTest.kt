package us.tractat.kuilt.multipeer

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
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
 * The substring the two #2410 pins match on.
 *
 * Carries the refusing class's name, not the bare phrase "no browse session": that phrase is not
 * unique in this repo — `:kuilt-mdns`'s `RegistryBonjourBrowser` raises a differently-caused
 * `IllegalStateException` containing it — so matching the phrase alone would make the pins accept a
 * red they were not written for the moment such a rig came within reach of this classpath.
 */
private const val NO_SESSION_RED = "MultipeerAppleSuite: no browse session"

/**
 * Binds appleMain's [MultipeerServiceBrowser] to [DiscoverySourceConformanceSuite] — the
 * implementation the discovery survey recorded as believed-correct and never checked — and records
 * that **two of the four obligations fail**. The defect is kuilt #2410.
 *
 * ## What is real here, and what is driven by hand
 *
 * The source, the factory, the `MCNearbyServiceBrowser` and its `BrowserDelegate` are all
 * production. What a test cannot produce is the *stimulus*: a `foundPeer` comes from Apple's
 * framework and needs a second physical device on the same Wi-Fi, so the delegate's callbacks are
 * invoked directly (through [MultipeerPeerLinkFactory.activeBrowserDelegate]) with an `MCPeerID` of
 * this test's making. Everything between that entry point and the emitted key — the known-peer map,
 * the visible-peer snapshot, the advertisement, `lostPeerHandles`, the flows — is the shipping code
 * path.
 *
 * What this cannot see, therefore, is whether Apple ever *calls* those methods, and the run-loop
 * thread it would call them on. That is real-device territory.
 *
 * ## What fails, and why
 *
 * `departures()` is `factory.lostPeerHandles`, and the only thing that ever writes it is the
 * `BrowserDelegate` installed by `startBrowsing` — which only `discoveries()` calls. So with no
 * `discoveries()` collector there is no delegate, no arrival and no departure, and both obligations
 * that collect `departures()` alone are unreachable:
 *
 *  - [theLoneCollectorObligationFailsBecauseTheBrowseSessionBelongsToDiscoveries]
 *  - [theArrivalIsNotADepartureObligationCannotEvenStageAnArrival]
 *
 * This is the same defect, in the same shape, as jvmMain's — see
 * `MultipeerDiscoverySourceConformanceTest`, which reaches it through the dylib instead.
 * [SharedBrowseSource] is the **control**: the same factory, the same fixture, one browse session
 * ref-counted across both feeds, and every obligation passes. That is what makes the two reds a
 * statement about the backend rather than about this harness — and it is the fix's shape, needing
 * one shared session rather than the second concurrent one `startBrowsing` refuses.
 *
 * The four properties are invoked by hand rather than inherited so the two failures can be pinned
 * instead of skipped; `DiscoverySourceConformanceSuiteRigTest` records why the suites below are
 * `abstract`.
 */
@OptIn(ExperimentalForeignApi::class)
class MultipeerAppleDiscoverySourceConformanceTest {

    @Test
    fun departureKeyEqualsThePeerKeyThatWasDiscovered() =
        onSuite(object : MultipeerAppleSuite() {}) { departureKeyEqualsThePeerKeyThatWasDiscovered() }

    @Test
    fun cancellingTheCollectorScopeCompletesDepartures() =
        onSuite(object : MultipeerAppleSuite() {}) { cancellingTheCollectorScopeCompletesDepartures() }

    /**
     * **KNOWN FAILURE — the Apple browser violates `PeerDiscoverySource.departures`. Tracked by
     * kuilt #2410; delete this pin there.**
     *
     * Pinned rather than skipped, so a fix reds this test and names the pin to delete. The assertion
     * is on the *shape* of the red: it must come from **this suite's own rig** refusing to pretend a
     * peer arrived with no browse session, because a red for any other reason would be a harness bug
     * wearing this finding's clothes. Matching the rig's class prefix and not the bare phrase is
     * what keeps that true — `RegistryBonjourBrowser` in `:kuilt-mdns` raises a
     * differently-caused failure carrying the same words, and a sibling could yet do so here.
     */
    @Test
    fun theLoneCollectorObligationFailsBecauseTheBrowseSessionBelongsToDiscoveries() {
        val suite = object : MultipeerAppleSuite() {}
        val failure = assertFailsWith<IllegalStateException> {
            onSuite(suite) { departuresEmitsWithNoConcurrentDiscoveriesCollector() }
        }
        assertTrue(
            failure.message.orEmpty().contains(NO_SESSION_RED),
            "expected the rig to refuse to stage an arrival with no discoveries() collector, got: ${failure.message}",
        )
    }

    /** **KNOWN FAILURE**, same root cause and same issue (#2410) — this obligation stages an arrival too. */
    @Test
    fun theArrivalIsNotADepartureObligationCannotEvenStageAnArrival() {
        val suite = object : MultipeerAppleSuite() {}
        val failure = assertFailsWith<IllegalStateException> {
            onSuite(suite) { anArrivalIsNeverReportedAsADeparture() }
        }
        assertTrue(
            failure.message.orEmpty().contains(NO_SESSION_RED),
            "expected the rig to refuse to stage an arrival with no discoveries() collector, got: ${failure.message}",
        )
    }

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
     * The factory installs no browser delegate until somebody collects `discoveries()` — the direct
     * receipt for both pinned failures, stated without going through the suite at all.
     *
     * Collecting `departures()` cannot help: it is `lostPeerHandles`, which touches no browser.
     */
    @Test
    fun noBrowserDelegateExistsUntilDiscoveriesIsCollected() {
        val factory = MultipeerPeerLinkFactory(CONFORMANCE_DEVICE, CONFORMANCE_SERVICE_TYPE)
        val source = MultipeerServiceBrowser(factory)

        assertAll(
            { assertNull(factory.activeBrowserDelegate, "a fresh factory is not browsing") },
            {
                // Obtaining the flow is not collecting it, and neither installs a delegate.
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
 * Each factory owns a real `MCPeerID` and, once `discoveries()` has been collected, a real
 * `MCNearbyServiceBrowser`. `awaitClose { stopBrowsing() }` already tears the browser down on the
 * paths that opened one, so this is belt-and-braces rather than a leak fix — but a property that
 * fails part-way (the two #2410 pins do, by design) has no such guarantee, and a test binary that
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
     * The wait is the whole reason this is `suspend`. `callbackFlow` calls `startBrowsing` from a
     * separately *launched* producer coroutine, so the suite's `onStart` handshake proves the
     * collection began — not that the delegate exists. Delivering into a delegate that is not there
     * would look exactly like a source that ignores arrivals.
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
     * Bounded rather than looping until quiescent: a delegate that is never going to appear is the
     * expected outcome on two of this suite's properties, and an unbounded drain would turn that
     * result into a wedge only `runTest`'s backstop ends.
     */
    protected suspend fun awaitBrowseDelegate(
        factory: MultipeerPeerLinkFactory,
    ): MCNearbyServiceBrowserDelegateProtocol {
        repeat(BROWSE_DELEGATE_YIELDS) {
            factory.activeBrowserDelegate?.let { return it }
            yield()
        }
        error(
            "MultipeerAppleSuite: no browse session had started after $BROWSE_DELEGATE_YIELDS " +
                "scheduler turns, so no peer can be made to arrive or depart. On this backend that " +
                "is not a timing accident: the browser delegate is installed by startBrowsing, and " +
                "only discoveries() calls it — so a test collecting only departures() has none.",
        )
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

    /**
     * Built from flow operators rather than a `callbackFlow`, and that is load-bearing rather than
     * stylistic. `callbackFlow` subscribes its producer in a separately *launched* coroutine, so
     * the session would be opened a scheduler turn after the suite's `onStart` handshake resolved —
     * `causeArrival` would find the delegate, return, and fire the departure into a feed nobody had
     * subscribed to yet. `onStart` runs inline before the upstream subscription, with no suspension
     * between, so the attach and the subscription are both done by the time the caller resumes.
     */
    override fun departures(): Flow<String> =
        factory.lostPeerHandles
            .map { departureKey(it) }
            .onStart { attach() }
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
