package us.tractat.kuilt.conformance

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.discovery.DiscoveryKind
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Proves [DiscoverySourceConformanceSuite]'s properties actually go **red**, by running them
 * against sources rigged with the exact defects each was written for.
 *
 * A green reference subclass ([ReferenceDiscoverySourceConformanceTest]) shows a property *can*
 * pass; it cannot show the property would notice anything. Every obligation below is a quantifier
 * or an equality that a silent source satisfies trivially, so without this file the suite could
 * ship asserting nothing and look identical.
 *
 * **JVM-only, and that is a limitation of the harness, not a choice.** `TestResult` is `Unit` here,
 * so a suite method can be invoked and its failure caught in-process; on wasmJs it is a `Promise`
 * and the same code would assert on a value rather than on an outcome. The suite itself is
 * `commonMain` and runs on every target — only this meta-check is pinned to one.
 *
 * Each rig asserts on the *shape* of the red, not merely on its presence: a source can fail an
 * obligation for the wrong reason, and a rig that only checks "something threw" would tick that off
 * as proof.
 *
 * The rig suites below are `abstract`, instantiated here as anonymous subclasses. That is not
 * style: a concrete subclass of [DiscoverySourceConformanceSuite] inherits four `@Test` methods, so
 * the test runner collects it as a test class of its own and then fails to build it (it is
 * private). `@Ignore` would silence that at the cost of a permanently-skipped row per rig in every
 * report — a skip nobody reads, which is the thing this whole suite is against. Abstract is
 * invisible to collection and still instantiable right here.
 */
class DiscoverySourceConformanceSuiteRigTest {

    @Test
    fun aDepartureCarryingAnIdentifierOtherThanThePeerKeyReds() {
        val failure = assertFailsWith<AssertionError> {
            (object : WrongKeySuite() {}).departureKeyEqualsThePeerKeyThatWasDiscovered()
        }
        assertTrue(
            failure.message.orEmpty().contains("SAME key"),
            "expected the key-equality obligation to red, got: ${failure.message}",
        )
    }

    @Test
    fun aLeaveSignalThatOnlyRunsWhileDiscoveriesIsCollectedReds() {
        val failure = assertFailsWith<AssertionError> {
            (object : ParasiticSuite() {}).departuresEmitsWithNoConcurrentDiscoveriesCollector()
        }
        assertTrue(
            failure.message.orEmpty().contains("collected on its own"),
            "expected the lone-collector obligation to red, got: ${failure.message}",
        )
    }

    /** …and the same source passes the obligation that does collect both feeds — so the red above is specific. */
    @Test
    fun theParasiticSourceStillPassesTheObligationThatCollectsBothFeeds() {
        (object : ParasiticSuite() {}).departureKeyEqualsThePeerKeyThatWasDiscovered()
    }

    /**
     * The negative window is a real window: a source that stays quiet through `causeArrival` and
     * only emits a moment later is still caught.
     *
     * This is the receipt for [DiscoverySourceConformanceSuite.awaitQuiescence]'s default. Without
     * it the hook is unpinned — and a zero-length wait would pass this rig, which is exactly what
     * the previous `awaitBudget?.let { … }` implementation did whenever a harness took the `null`
     * its own KDoc prescribes.
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

    /**
     * …and a harness with no virtual clock to burn cannot end up with a zero-length window by
     * omission: it is told to declare one.
     */
    @Test
    fun aRealIoHarnessThatDeclaresNoSilenceWindowReds() {
        val failure = assertFailsWith<AssertionError> {
            (object : RealIoWithoutQuiescenceSuite() {}).anArrivalIsNeverReportedAsADeparture()
        }
        assertTrue(
            failure.message.orEmpty().contains("override awaitQuiescence()"),
            "expected awaitBudget = null to demand a declared silence window, got: ${failure.message}",
        )
    }

    @Test
    fun aSourceDeclaringNoLeaveSignalWhileEmittingReds() {
        val failure = assertFailsWith<AssertionError> {
            (object : DishonestNoLeaveSignalSuite() {}).anArrivalIsNeverReportedAsADeparture()
        }
        assertTrue(
            failure.message.orEmpty().contains("An arrival is never a departure"),
            "expected the honesty obligation to red, got: ${failure.message}",
        )
    }

    // ── rigs ─────────────────────────────────────────────────────────────────

    private companion object {
        /**
         * How long [LateSpuriousDepartureSource] stays quiet before misbehaving.
         *
         * Comfortably inside the suite's default virtual window, and strictly outside a
         * zero-length one — being the discriminator between those two is the whole job of that rig,
         * so this value is load-bearing rather than arbitrary.
         */
        val SPURIOUS_DEPARTURE_DELAY: Duration = 1.seconds
    }

    /** Emits the peer's *session name* on departure — plausible in a log, useless to `discoveryRoster`. */
    private class WrongKeySource : PeerDiscoverySource {
        override val kind: DiscoveryKind = DiscoveryKind.Mdns
        private val arrivals = MutableSharedFlow<Tag>(extraBufferCapacity = 8)
        private val leaves = MutableSharedFlow<String>(extraBufferCapacity = 8)

        override fun discoveries(): Flow<Tag> = arrivals

        override fun departures(): Flow<String> = leaves

        suspend fun advertise() {
            arrivals.emit(InMemoryTag(sessionName = REFERENCE_SESSION_NAME, peerKey = REFERENCE_PEER_KEY))
        }

        suspend fun withdraw() {
            leaves.emit(REFERENCE_SESSION_NAME)
        }
    }

    /** Its leave signal only fires while `discoveries()` is being collected — the shape #1917 describes. */
    private class ParasiticSource : PeerDiscoverySource {
        override val kind: DiscoveryKind = DiscoveryKind.Multipeer
        private val arrivals = MutableSharedFlow<Tag>(extraBufferCapacity = 8)
        private val leaves = MutableSharedFlow<String>(extraBufferCapacity = 8)
        private var browsing = false

        override fun discoveries(): Flow<Tag> = flow {
            browsing = true
            try {
                emitAll(arrivals)
            } finally {
                browsing = false
            }
        }

        override fun departures(): Flow<String> = leaves

        suspend fun advertise() {
            arrivals.emit(InMemoryTag(sessionName = REFERENCE_SESSION_NAME, peerKey = REFERENCE_PEER_KEY))
        }

        suspend fun withdraw() {
            if (browsing) leaves.emit(REFERENCE_PEER_KEY)
        }
    }

    /**
     * Declares no leave signal, and stays quiet long enough that only a real window catches it.
     *
     * The delay is the point: it is silent at the instant `causeArrival` returns, so a
     * zero-length negative window sees nothing and the suite goes green.
     */
    private class LateSpuriousDepartureSource : PeerDiscoverySource {
        override val kind: DiscoveryKind = DiscoveryKind.Mdns
        private val arrivals = MutableSharedFlow<Tag>(extraBufferCapacity = 8)

        override fun discoveries(): Flow<Tag> = arrivals

        override fun departures(): Flow<String> = flow {
            arrivals.collect { tag ->
                delay(SPURIOUS_DEPARTURE_DELAY)
                emit(tag.peerKey)
            }
        }

        suspend fun advertise() {
            arrivals.emit(InMemoryTag(sessionName = REFERENCE_SESSION_NAME, peerKey = REFERENCE_PEER_KEY))
        }
    }

    /** Declares no leave signal, then reports every arrival as one. */
    private class DishonestNoLeaveSignalSource : PeerDiscoverySource {
        override val kind: DiscoveryKind = DiscoveryKind.Mdns
        private val arrivals = MutableSharedFlow<Tag>(extraBufferCapacity = 8)

        override fun discoveries(): Flow<Tag> = arrivals

        override fun departures(): Flow<String> = arrivals.map { it.peerKey }

        suspend fun advertise() {
            arrivals.emit(InMemoryTag(sessionName = REFERENCE_SESSION_NAME, peerKey = REFERENCE_PEER_KEY))
        }
    }

    private abstract class WrongKeySuite : DiscoverySourceConformanceSuite() {
        override fun newSource(): PeerDiscoverySource = WrongKeySource()

        override suspend fun causeArrival(source: PeerDiscoverySource) {
            (source as WrongKeySource).advertise()
        }

        override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
            DepartureFixture.Emits { (source as WrongKeySource).withdraw() }
    }

    private abstract class ParasiticSuite : DiscoverySourceConformanceSuite() {
        override fun newSource(): PeerDiscoverySource = ParasiticSource()

        override suspend fun causeArrival(source: PeerDiscoverySource) {
            (source as ParasiticSource).advertise()
        }

        override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
            DepartureFixture.Emits { (source as ParasiticSource).withdraw() }
    }

    private abstract class LateSpuriousDepartureSuite : DiscoverySourceConformanceSuite() {
        override fun newSource(): PeerDiscoverySource = LateSpuriousDepartureSource()

        override suspend fun causeArrival(source: PeerDiscoverySource) {
            (source as LateSpuriousDepartureSource).advertise()
        }

        override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
            DepartureFixture.NoLeaveSignal
    }

    /**
     * A stand-in for a real-I/O harness: it takes the `awaitBudget = null` its KDoc prescribes and
     * stops there, declaring no silence window of its own. Its source is irrelevant — the point is
     * that the omission fails rather than passing.
     */
    private abstract class RealIoWithoutQuiescenceSuite : DiscoverySourceConformanceSuite() {
        override val awaitBudget: Duration? = null

        override fun newSource(): PeerDiscoverySource = NoLeaveSignalDiscoverySource()

        override suspend fun causeArrival(source: PeerDiscoverySource) {
            (source as NoLeaveSignalDiscoverySource).advertise()
        }

        override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
            DepartureFixture.NoLeaveSignal
    }

    private abstract class DishonestNoLeaveSignalSuite : DiscoverySourceConformanceSuite() {
        override fun newSource(): PeerDiscoverySource = DishonestNoLeaveSignalSource()

        override suspend fun causeArrival(source: PeerDiscoverySource) {
            (source as DishonestNoLeaveSignalSource).advertise()
        }

        override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
            DepartureFixture.NoLeaveSignal
    }
}
