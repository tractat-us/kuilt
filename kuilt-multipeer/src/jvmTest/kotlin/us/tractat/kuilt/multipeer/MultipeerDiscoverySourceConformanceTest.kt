@file:Suppress("ForbiddenImport") // deliberate: discoveries() carries its own .flowOn(Dispatchers.IO), so this harness is real-time

package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: production pins discoveries() to Dispatchers.IO, so a virtual budget would expire while the browse session was still starting
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import us.tractat.kuilt.conformance.DepartureFixture
import us.tractat.kuilt.conformance.DiscoverySourceConformanceSuite
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val CONFORMANCE_SERVICE_TYPE = "kuilt-conf2401"
private const val CONFORMANCE_DEVICE = "conformance-device"

/**
 * The peer's dylib handle — what `MultipeerAdvertisement.peerKey` is, and what a departure must
 * carry. Deliberately **not** [ARRIVING_DISPLAY_NAME]; see
 * [MultipeerJvmDepartureRigTest.theArrivalCarriesAHandleAndADisplayNameThatAreDifferentStrings].
 */
private const val ARRIVING_HANDLE = "conformance-peer#a1b2c3d4"

/** The peer's human-facing name — what `MultipeerAdvertisement.sessionName` is, and never a key. */
private const val ARRIVING_DISPLAY_NAME = "conformance-peer"

/** How long a rig waits, in **real** time, for production's browse session to reach the fake. */
private const val BROWSE_WAIT_MILLIS = 2_000L

/** The runtime handle every source in this file is built against. */
private val RUNTIME_HANDLE = Pointer(0x42L)

/**
 * The negative window behind `anArrivalIsNeverReportedAsADeparture`, in **real** time.
 *
 * Real because this harness has no virtual clock to burn (see [MultipeerJvmSuite.awaitBudget]), and
 * this long because the source's own path crosses `Dispatchers.IO` twice — the `callbackFlow`
 * channel and the `flowOn` channel — so a spurious departure can surface a scheduling quantum after
 * `causeArrival` returns rather than inline with it.
 * [MultipeerJvmDepartureRigTest.aSpuriousDepartureEmittedAMomentAfterAnArrivalReds] is the receipt
 * that the window really does catch one.
 */
private val QUIESCENCE_WINDOW = 2.seconds

/** How long after an arrival [LateSpuriousDepartureSource] misbehaves — well inside that window. */
private val SPURIOUS_DEPARTURE_DELAY = 200.milliseconds

/**
 * Binds jvmMain's [MultipeerServiceBrowser] to [DiscoverySourceConformanceSuite] — and records that
 * **two of the four obligations fail**, which is the finding this file exists to make permanent.
 *
 * ## Why the properties are invoked by hand rather than inherited
 *
 * A concrete subclass of [DiscoverySourceConformanceSuite] contributes all four `@Test` methods at
 * once, and two of them red here. `@Ignore` would hide that behind a skip nobody reads; leaving the
 * binding unwritten would leave this backend exactly where #2401 found every non-mDNS source —
 * unchecked. So the suite is subclassed *abstractly* (invisible to test collection, for the reason
 * `DiscoverySourceConformanceSuiteRigTest` records) and every property invoked explicitly: the two
 * that hold are asserted to hold, and the two that do not are asserted to fail **with the message
 * that names the cause**. Fixing the backend therefore reds those two, loudly, rather than passing
 * unnoticed.
 *
 * ## What fails, and why
 *
 * `departures()` is a hot `MutableSharedFlow` fed by the dylib's `peerLost` callback, and that
 * callback is registered inside `discoveries()`' own `callbackFlow`. With no `discoveries()`
 * collector there is no `mc_browser_start`, hence no browse session, hence neither an arrival nor a
 * departure — so **both** obligations that collect `departures()` alone are unreachable:
 *
 *  - [theLoneCollectorObligationFailsBecauseTheBrowseSessionBelongsToDiscoveries] — the headline
 *    one, and the shape `discoveryRoster` actually produces, because `merge` subscribes to the two
 *    feeds in separately-launched coroutines.
 *  - [theArrivalIsNotADepartureObligationCannotEvenStageAnArrival] — collateral: that property
 *    stages an arrival too, and this backend cannot stage one for a lone `departures()` collector.
 *
 * [RefCountedBrowseSource] is the **control**, and it is what makes those two reds a statement
 * about the backend rather than about the harness: a source over this same fake whose one browse
 * session is ref-counted across both feeds passes every obligation. It is also the smallest sketch
 * of the fix — nothing there needs a second concurrent browse session, which the dylib forbids.
 *
 * ## What this harness does not see
 *
 * Everything below the JNA boundary. The fake stands in for `libkuilt.dylib`, so the K/N bridge,
 * the real `MCNearbyServiceBrowser` and the GCD callback threading are untouched — that surface
 * belongs to the real-network path and to [MultipeerBrowserDispatcherTest].
 */
class MultipeerDiscoverySourceConformanceTest {

    @Test
    fun departureKeyEqualsThePeerKeyThatWasDiscovered() {
        (object : MultipeerJvmSuite() {}).departureKeyEqualsThePeerKeyThatWasDiscovered()
    }

    @Test
    fun cancellingTheCollectorScopeCompletesDepartures() {
        (object : MultipeerJvmSuite() {}).cancellingTheCollectorScopeCompletesDepartures()
    }

    /**
     * **KNOWN FAILURE — the JVM browser violates `PeerDiscoverySource.departures`.**
     *
     * Pinned rather than skipped, so the day somebody gives the browse session a lifetime of its own
     * this test reds and names the pin to delete. The assertion is on the *shape* of the failure,
     * not merely that something threw: the red must come from the rig refusing to pretend a peer
     * arrived (see [ConformanceNativeLib.awaitBrowseSession]), because a red for any other reason
     * would be a harness bug wearing this finding's clothes.
     */
    @Test
    fun theLoneCollectorObligationFailsBecauseTheBrowseSessionBelongsToDiscoveries() {
        val failure = assertFailsWith<IllegalStateException> {
            (object : MultipeerJvmSuite() {}).departuresEmitsWithNoConcurrentDiscoveriesCollector()
        }
        assertTrue(
            failure.message.orEmpty().contains("no browse session"),
            "expected the rig to refuse to stage an arrival with no discoveries() collector, got: ${failure.message}",
        )
    }

    /** **KNOWN FAILURE**, same root cause — this obligation stages an arrival too. */
    @Test
    fun theArrivalIsNotADepartureObligationCannotEvenStageAnArrival() {
        val failure = assertFailsWith<IllegalStateException> {
            (object : MultipeerJvmSuite() {}).anArrivalIsNeverReportedAsADeparture()
        }
        assertTrue(
            failure.message.orEmpty().contains("no browse session"),
            "expected the rig to refuse to stage an arrival with no discoveries() collector, got: ${failure.message}",
        )
    }

    // ── the control: this harness DOES admit a conforming source ─────────────

    @Test
    fun aRefCountedBrowseSessionPassesTheLoneCollectorObligation() {
        (object : RefCountedBrowseSuite() {}).departuresEmitsWithNoConcurrentDiscoveriesCollector()
    }

    @Test
    fun aRefCountedBrowseSessionPassesTheArrivalIsNotADepartureObligation() {
        (object : RefCountedBrowseSuite() {}).anArrivalIsNeverReportedAsADeparture()
    }

    @Test
    fun aRefCountedBrowseSessionPassesTheKeyEqualityObligation() {
        (object : RefCountedBrowseSuite() {}).departureKeyEqualsThePeerKeyThatWasDiscovered()
    }
}

/**
 * Proves the harness above is rigged for the defects it claims to catch.
 *
 * A green property shows a property *can* pass; it cannot show the property would notice anything.
 * Each check below asks what one of the fake's settings switches **off**, and each rig counts its
 * own firings rather than inferring them from a side effect.
 */
class MultipeerJvmDepartureRigTest {

    /**
     * The handle production keys a peer by and the display name it shows are different strings, and
     * production really does publish them in those two roles.
     *
     * This is the one setting that gives `departureKeyEqualsThePeerKeyThatWasDiscovered` teeth: a
     * fixture using one string for both would green a source emitting either, and the equality would
     * assert nothing.
     */
    @Test
    fun theArrivalCarriesAHandleAndADisplayNameThatAreDifferentStrings() {
        val advertisement =
            MultipeerAdvertisement(
                handle = ARRIVING_HANDLE,
                sessionName = ARRIVING_DISPLAY_NAME,
                serviceType = CONFORMANCE_SERVICE_TYPE,
            )
        assertAll(
            {
                assertNotEquals(
                    ARRIVING_DISPLAY_NAME,
                    ARRIVING_HANDLE,
                    "the harness's handle and display name must differ, or the key-equality " +
                        "obligation cannot tell a correct departure from a wrongly-keyed one",
                )
            },
            { assertEquals(ARRIVING_HANDLE, advertisement.peerKey, "peerKey is the handle") },
            { assertEquals(ARRIVING_DISPLAY_NAME, advertisement.sessionName, "sessionName is the display name") },
        )
    }

    /**
     * Production registers a peer-lost callback, and the fake really holds it.
     *
     * [FakeMultipeerNativeLib]'s `mc_browser_set_peer_lost_callback` is a no-op — against *that*
     * fake the departure fixture would fire into nothing while the property reds blaming the source.
     * Asserted by count, so an unreached registration cannot pass as a reached one.
     */
    @Test
    fun theFakeRegistersThePeerLostCallbackProductionInstalls() {
        val lib = ConformanceNativeLib()
        val source = lib.newBrowser()

        collectDiscoveriesWhile(source) {
            lib.awaitBrowseSessionBlocking()
            assertAll(
                { assertEquals(1, lib.browseStarts, "production must start exactly one browse session") },
                { assertTrue(lib.peerLostCallbackRegistered, "production must register a peer-lost callback") },
            )
            lib.fireLostPeer(ARRIVING_HANDLE)
            assertEquals(1, lib.lostFirings, "the rig must count its own firing, not infer it")
        }
    }

    /**
     * With no browse session the rig **throws**; it does not quietly fire into nothing.
     *
     * This is the mechanism behind both pinned failures, and the reason they are findings rather
     * than silent passes: a rig that no-op'd here would green
     * `anArrivalIsNeverReportedAsADeparture` for this backend by never staging the arrival it is
     * supposed to stage.
     */
    @Test
    fun firingAnArrivalWithNoBrowseSessionThrowsRatherThanNoOpping() {
        val lib = ConformanceNativeLib()
        val failure =
            assertFailsWith<IllegalStateException> { lib.fireFoundPeer(ARRIVING_HANDLE, ARRIVING_DISPLAY_NAME) }
        assertAll(
            { assertTrue(failure.message.orEmpty().contains("no browse session")) },
            { assertEquals(0, lib.foundFirings, "a refused firing must not be counted as one") },
        )
    }

    /** A stopped session hears nothing further — the receipt behind `awaitClose { mc_browser_stop }`. */
    @Test
    fun aStoppedBrowseSessionHearsNothingFurther() {
        val lib = ConformanceNativeLib()
        val source = lib.newBrowser()

        collectDiscoveriesWhile(source) { lib.awaitBrowseSessionBlocking() }
        lib.awaitBrowseStopBlocking()

        assertAll(
            { assertEquals(1, lib.browseStops, "cancelling the collection must stop the browse session") },
            { assertNull(lib.peerFoundCallbackOrNull, "a stopped session must drop the found callback") },
            { assertFailsWith<IllegalStateException> { lib.fireLostPeer(ARRIVING_HANDLE) } },
        )
    }

    // ── the properties red against sources broken on this same fake ──────────

    @Test
    fun aDepartureCarryingTheDisplayNameRedsTheKeyEqualityObligation() {
        val failure = assertFailsWith<AssertionError> {
            (object : WrongKeySuite() {}).departureKeyEqualsThePeerKeyThatWasDiscovered()
        }
        assertTrue(
            failure.message.orEmpty().contains("SAME key"),
            "expected the key-equality obligation to red, got: ${failure.message}",
        )
    }

    /**
     * The negative window is a real window: a source silent at the instant `causeArrival` returns
     * and misbehaving [SPURIOUS_DEPARTURE_DELAY] later is still caught.
     *
     * This is the receipt for [MultipeerJvmSuite.awaitQuiescence]'s real-time override. The suite's
     * virtual default is unusable here — `awaitBudget` is `null` — and a real window that was too
     * short would green a source emitting a moment late, which is the direction that fails silently.
     */
    @Test
    fun aSpuriousDepartureEmittedAMomentAfterAnArrivalReds() {
        val failure = assertFailsWith<AssertionError> {
            (object : LateSpuriousDepartureSuite() {}).anArrivalIsNeverReportedAsADeparture()
        }
        assertTrue(
            failure.message.orEmpty().contains("An arrival is never a departure"),
            "expected the silence window to catch the late emission, got: ${failure.message}",
        )
    }
}

/**
 * Collect [source]'s `discoveries()` on a real dispatcher for the duration of [block], then cancel.
 *
 * The collector must not share a thread with [block]: production's browse session is opened from a
 * separately-launched producer, and [block] blocks while it waits for that to happen.
 */
private fun collectDiscoveriesWhile(
    source: PeerDiscoverySource,
    block: () -> Unit,
) = runBlocking {
    val collecting = launch(Dispatchers.IO) { source.discoveries().collect { } }
    try {
        block()
    } finally {
        collecting.cancelAndJoin()
    }
}

// ── the bindings ──────────────────────────────────────────────────────────────

/**
 * The suite bound to production's [MultipeerServiceBrowser], over a fake dylib.
 *
 * `abstract` so the runner does not collect it — see [MultipeerDiscoverySourceConformanceTest].
 */
private abstract class MultipeerJvmSuite : DiscoverySourceConformanceSuite() {

    private val libs = ConcurrentHashMap<PeerDiscoverySource, ConformanceNativeLib>()

    /**
     * **Real time, not virtual.** `MultipeerServiceBrowser.discoveries()` ends in
     * `.flowOn(Dispatchers.IO)` — production code this harness cannot inject around — so the browse
     * session opens on a thread the test scheduler knows nothing about. A virtual budget would be
     * fast-forwarded to expiry while that thread was still starting, failing a working backend for
     * the harness's own reasons (kuilt #2069 / #2115).
     */
    override val awaitBudget: Duration? = null

    /** The declared real-time silence window `awaitBudget = null` obliges this harness to supply. */
    override suspend fun awaitQuiescence() {
        withContext(Dispatchers.Default) { delay(QUIESCENCE_WINDOW) }
    }

    override fun newSource(): PeerDiscoverySource {
        val lib = ConformanceNativeLib()
        return register(lib.newBrowser(), lib)
    }

    /**
     * Waits — in real time — for production's browse session to reach the fake, then fires one
     * `foundPeer`.
     *
     * The wait is the whole reason this is `suspend`. `callbackFlow` calls `mc_browser_start` from a
     * separately launched producer coroutine dispatched to `Dispatchers.IO`, so the suite's
     * `onStart` handshake proves the collection began — not that the session exists. Firing into an
     * unregistered callback would look exactly like a source that ignores arrivals.
     *
     * [ConformanceNativeLib.awaitBrowseSession] throws rather than returning quietly when no session
     * ever appears, which is what turns this backend's coupling into a named failure instead of a
     * silent pass.
     */
    override suspend fun causeArrival(source: PeerDiscoverySource) {
        val lib = libFor(source)
        lib.awaitBrowseSession()
        lib.fireFoundPeer(ARRIVING_HANDLE, ARRIVING_DISPLAY_NAME)
    }

    /**
     * Closes over the peer **handle** — a constant [causeArrival] itself established — and nothing
     * the arrival emitted, as [DepartureFixture.Emits] requires: in
     * `departuresEmitsWithNoConcurrentDiscoveriesCollector` no discovered [Tag] exists to reach for.
     */
    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits { libFor(source).fireLostPeer(ARRIVING_HANDLE) }

    protected fun libFor(source: PeerDiscoverySource): ConformanceNativeLib =
        libs[source] ?: error("source was not built by newSource(): $source")

    /** Bind a source to the fake it was built against, so [libFor] resolves it. */
    protected fun register(
        source: PeerDiscoverySource,
        lib: ConformanceNativeLib,
    ): PeerDiscoverySource = source.also { libs[it] = lib }
}

/**
 * The control: [RefCountedBrowseSource], whose one browse session is shared by both feeds.
 *
 * Every obligation passes here, on the same fake and through the same fixture, which is what
 * distinguishes "this backend is broken" from "this harness cannot satisfy the property".
 */
private abstract class RefCountedBrowseSuite : MultipeerJvmSuite() {
    override fun newSource(): PeerDiscoverySource {
        val lib = ConformanceNativeLib()
        return register(RefCountedBrowseSource(lib), lib)
    }
}

/** Emits the peer's *display name* on departure — plausible in a log, useless to `discoveryRoster`. */
private abstract class WrongKeySuite : MultipeerJvmSuite() {
    override fun newSource(): PeerDiscoverySource {
        val lib = ConformanceNativeLib()
        return register(RefCountedBrowseSource(lib, departureKey = { ARRIVING_DISPLAY_NAME }), lib)
    }
}

/** Declares no leave signal, then reports an arrival as one [SPURIOUS_DEPARTURE_DELAY] later. */
private abstract class LateSpuriousDepartureSuite : MultipeerJvmSuite() {
    override fun newSource(): PeerDiscoverySource {
        val lib = ConformanceNativeLib()
        return register(LateSpuriousDepartureSource(lib), lib)
    }

    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture = DepartureFixture.NoLeaveSignal
}

// ── rig sources ───────────────────────────────────────────────────────────────

/**
 * A [PeerDiscoverySource] over [ConformanceNativeLib] whose single browse session is opened by
 * whichever feed subscribes first and stopped when the last one goes away.
 *
 * @param departureKey what a `peerLost` handle is published as. The identity default is correct; a
 *   rig overrides it to model a source that emits the wrong identifier.
 */
private class RefCountedBrowseSource(
    private val lib: ConformanceNativeLib,
    private val departureKey: (String) -> String = { it },
) : PeerDiscoverySource {

    override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    private val lock = ReentrantLock()
    private var refs = 0
    private var browser: Pointer? = null
    private val arrivalSinks = mutableListOf<(MultipeerAdvertisement) -> Unit>()
    private val departureSinks = mutableListOf<(String) -> Unit>()

    // Fields, not locals: JNA may release a trampoline whose only reference is a stack slot, and
    // production holds these for the session's lifetime for exactly that reason.
    private val foundCallback =
        MultipeerNativeLib.PeerFoundCallback { handle, displayName ->
            val advertisement =
                MultipeerAdvertisement(
                    handle = handle,
                    sessionName = displayName,
                    serviceType = CONFORMANCE_SERVICE_TYPE,
                )
            lock.withLock { arrivalSinks.toList() }.forEach { it(advertisement) }
        }

    private val lostCallback =
        MultipeerNativeLib.PeerLostCallback { handle ->
            val key = departureKey(handle)
            lock.withLock { departureSinks.toList() }.forEach { it(key) }
        }

    override fun discoveries(): Flow<Tag> =
        callbackFlow {
            val sink: (MultipeerAdvertisement) -> Unit = { trySend(it) }
            attach(arrival = sink, departure = null)
            awaitClose { detach(arrival = sink, departure = null) }
        }

    override fun departures(): Flow<String> =
        callbackFlow {
            val sink: (String) -> Unit = { trySend(it) }
            attach(arrival = null, departure = sink)
            awaitClose { detach(arrival = null, departure = sink) }
        }

    private fun attach(
        arrival: ((MultipeerAdvertisement) -> Unit)?,
        departure: ((String) -> Unit)?,
    ) = lock.withLock {
        arrival?.let { arrivalSinks += it }
        departure?.let { departureSinks += it }
        if (refs++ == 0) {
            browser = lib
                .mc_browser_start(RUNTIME_HANDLE, foundCallback)
                .also { lib.mc_browser_set_peer_lost_callback(it, lostCallback) }
        }
    }

    private fun detach(
        arrival: ((MultipeerAdvertisement) -> Unit)?,
        departure: ((String) -> Unit)?,
    ) = lock.withLock {
        arrival?.let { arrivalSinks -= it }
        departure?.let { departureSinks -= it }
        if (--refs == 0) {
            browser?.let { lib.mc_browser_stop(it) }
            browser = null
        }
    }
}

/**
 * Declares no leave signal and then reports every arrival as a departure — but only after a **real**
 * delay, so a silence window that is too short greens it.
 *
 * Real rather than virtual because [MultipeerJvmSuite.awaitQuiescence] burns real time: a virtual
 * `delay` here would never fire, and the rig would prove nothing while looking identical.
 */
private class LateSpuriousDepartureSource(
    lib: ConformanceNativeLib,
) : PeerDiscoverySource {

    private val real = RefCountedBrowseSource(lib)

    override val kind: DiscoveryKind = DiscoveryKind.Multipeer

    override fun discoveries(): Flow<Tag> = real.discoveries()

    override fun departures(): Flow<String> =
        real.discoveries().map { tag ->
            withContext(Dispatchers.Default) { delay(SPURIOUS_DEPARTURE_DELAY) }
            tag.peerKey
        }
}

// ── the fake dylib ────────────────────────────────────────────────────────────

/**
 * A [MultipeerNativeLib] modelling the browse half of `libkuilt.dylib` faithfully enough to reach
 * the #2401 finding, and refusing — loudly — every state in which it would otherwise lie.
 *
 * Three behaviours are load-bearing:
 *
 *  1. **`mc_browser_set_peer_lost_callback` really registers.** [FakeMultipeerNativeLib]'s is a
 *     no-op, and against that fake a departure fixture fires into nothing while the property reds
 *     blaming the source.
 *  2. **Firing into no session throws.** No browse session means production could not possibly have
 *     seen a peer, so a rig that fired anyway would assert on an arrival that never happened. This
 *     is what converts the backend's collection coupling into a named failure.
 *  3. **`mc_browser_stop` drops both callbacks**, so a leaked registration cannot look like a
 *     working one.
 *
 * A fourth is a rig assertion rather than a model: [mc_browser_start] refuses a second concurrent
 * session, because the dylib does. A rig source needing two would not be implementable in
 * production, and silently allowing it would let this file prescribe a fix that cannot be built.
 *
 * Callbacks fire synchronously on the caller's thread — the real dylib fires them from Darwin GCD,
 * which is [MultipeerBrowserDispatcherTest]'s subject, not this one. Every field is atomic or
 * lock-guarded: production calls in from `Dispatchers.IO` while the rigs call in from the test
 * thread, so nothing here may rest on which thread happens to arrive.
 */
internal class ConformanceNativeLib : MultipeerNativeLib {

    private val browserHandle = Pointer(0xB0BBL)
    private val found = AtomicReference<MultipeerNativeLib.PeerFoundCallback?>(null)
    private val lost = AtomicReference<MultipeerNativeLib.PeerLostCallback?>(null)
    private val starts = AtomicInteger(0)
    private val stops = AtomicInteger(0)
    private val founds = AtomicInteger(0)
    private val losts = AtomicInteger(0)
    private val sessionReady = CountDownLatch(1)
    private val sessionStopped = CountDownLatch(1)

    val browseStarts: Int get() = starts.get()
    val browseStops: Int get() = stops.get()
    val foundFirings: Int get() = founds.get()
    val lostFirings: Int get() = losts.get()
    val peerFoundCallbackOrNull: MultipeerNativeLib.PeerFoundCallback? get() = found.get()
    val peerLostCallbackRegistered: Boolean get() = lost.get() != null

    /** A production browser wired to this fake, sharing one runtime handle. */
    fun newBrowser(): MultipeerServiceBrowser {
        val factory =
            MultipeerPeerLinkFactory(
                displayName = CONFORMANCE_DEVICE,
                serviceType = CONFORMANCE_SERVICE_TYPE,
                injectedLib = this,
                injectedRuntimeHandle = RUNTIME_HANDLE,
            )
        return MultipeerServiceBrowser(factory, libLoader = { this })
    }

    /**
     * Suspend until a browse session exists, or fail loudly.
     *
     * Bounded in **real** time because the session opens on `Dispatchers.IO`. Unbounded would turn a
     * missing session into a wedge that only `runTest`'s backstop ends, which this repo treats as a
     * stop-and-replan signal rather than as a result.
     */
    suspend fun awaitBrowseSession() {
        val ready = withContext(Dispatchers.IO) { sessionReady.await(BROWSE_WAIT_MILLIS, TimeUnit.MILLISECONDS) }
        requireSession(ready)
    }

    /** [awaitBrowseSession] for a rig that is not inside a coroutine. */
    fun awaitBrowseSessionBlocking() {
        requireSession(sessionReady.await(BROWSE_WAIT_MILLIS, TimeUnit.MILLISECONDS))
    }

    /** Wait for the teardown `mc_browser_stop`, which `awaitClose` runs off the collector's thread. */
    fun awaitBrowseStopBlocking() {
        check(sessionStopped.await(BROWSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            "ConformanceNativeLib: the browse session was never stopped after its collection ended"
        }
    }

    private fun requireSession(ready: Boolean) =
        check(ready) {
            "ConformanceNativeLib: no browse session had started ${BROWSE_WAIT_MILLIS}ms after " +
                "causeArrival fired, so no peer can be made to arrive. On this backend that is not " +
                "a timing accident: mc_browser_start is called inside discoveries()' callbackFlow, " +
                "so a test collecting only departures() opens no session at all."
        }

    /** Deliver one `foundPeer`. Throws if no session could have seen it. */
    fun fireFoundPeer(
        handle: String,
        displayName: String,
    ) {
        val callback = found.get() ?: noSession("foundPeer")
        founds.incrementAndGet()
        callback.invoke(handle, displayName)
    }

    /** Deliver one `peerLost`. Throws if no session could have seen it. */
    fun fireLostPeer(handle: String) {
        val callback = lost.get() ?: noSession("peerLost")
        losts.incrementAndGet()
        callback.invoke(handle)
    }

    private fun noSession(what: String): Nothing =
        error(
            "ConformanceNativeLib: cannot fire $what — no browse session is registered. Firing into " +
                "an empty registration would stage an arrival nobody could have seen, and the " +
                "property would then report on this fake rather than on the source.",
        )

    override fun mc_browser_start(
        runtime: Pointer?,
        peerFoundCb: MultipeerNativeLib.PeerFoundCallback,
    ): Pointer {
        check(found.compareAndSet(null, peerFoundCb)) {
            "ConformanceNativeLib: a second browse session was started while one was live. The " +
                "dylib permits one at a time, so a source needing two is not implementable."
        }
        starts.incrementAndGet()
        return browserHandle
    }

    override fun mc_browser_set_peer_lost_callback(
        browser: Pointer?,
        peerLostCb: MultipeerNativeLib.PeerLostCallback,
    ) {
        lost.set(peerLostCb)
        sessionReady.countDown()
    }

    override fun mc_browser_stop(browser: Pointer?) {
        stops.incrementAndGet()
        found.set(null)
        lost.set(null)
        sessionStopped.countDown()
    }

    // ── below the browse surface: unused here, and inert ─────────────────────

    override fun kuilt_protocol_version(): Int = MultipeerNativeLib.EXPECTED_PROTOCOL_VERSION

    override fun mc_runtime_create(
        displayName: String,
        serviceType: String,
    ): Pointer = RUNTIME_HANDLE

    override fun mc_runtime_destroy(handle: Pointer?) = Unit

    override fun mc_runtime_close(handle: Pointer?) = Unit

    override fun mc_runtime_display_name(
        handle: Pointer?,
        buf: ByteArray,
        bufLen: Int,
    ): Int = 0

    override fun mc_runtime_open(handle: Pointer?): Pointer = Pointer(0x2L)

    override fun mc_session_close(session: Pointer?) = Unit

    override fun mc_session_broadcast(
        session: Pointer?,
        data: ByteArray,
        len: Int,
    ): Int = len

    override fun mc_runtime_join(
        runtime: Pointer?,
        peerHandle: String,
    ): Pointer = Pointer(0x3L)

    override fun mc_session_set_data_callback(
        session: Pointer?,
        cb: MultipeerNativeLib.DataCallback,
    ) = Unit

    override fun mc_session_set_peer_state_callback(
        session: Pointer?,
        cb: MultipeerNativeLib.PeerStateCallback,
    ) = Unit

    override fun mc_session_send_to(
        session: Pointer?,
        peerHandle: String,
        data: ByteArray,
        len: Int,
    ): Int = len
}
