package us.tractat.kuilt.conformance

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
 * private). `@Ignore` would silence that at the cost of three permanently-skipped rows in every
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

    private abstract class DishonestNoLeaveSignalSuite : DiscoverySourceConformanceSuite() {
        override fun newSource(): PeerDiscoverySource = DishonestNoLeaveSignalSource()

        override suspend fun causeArrival(source: PeerDiscoverySource) {
            (source as DishonestNoLeaveSignalSource).advertise()
        }

        override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
            DepartureFixture.NoLeaveSignal
    }
}
