package us.tractat.kuilt.multipeer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: temporary #3075 experiment, the producer under test runs on Dispatchers.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import us.tractat.kuilt.conformance.DepartureFixture
import us.tractat.kuilt.conformance.DiscoverySourceConformanceSuite
import us.tractat.kuilt.core.discovery.PeerDiscoverySource
import us.tractat.kuilt.core.discovery.discoveryRoster
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val HANDLE = "conformance-peer#a1b2c3d4"
private const val NAME = "conformance-peer"

private class Held(val onDepartures: Boolean) {
    val entered = AtomicInteger(0)
    val released = AtomicInteger(0)
    val latch = CountDownLatch(1)

    fun install(browser: MultipeerServiceBrowser) {
        browser.w3075Hold = { isDepartures ->
            if (isDepartures == onDepartures) {
                entered.incrementAndGet()
                check(latch.await(40, TimeUnit.SECONDS)) { "w3075: hold never released" }
                released.incrementAndGet()
            }
        }
    }

    fun release() = latch.countDown()
}

private abstract class ExperimentSuite(val held: Held) : DiscoverySourceConformanceSuite() {
    lateinit var lib: ConformanceNativeLib
    lateinit var browser: MultipeerServiceBrowser
    override val awaitBudget: Duration? = null

    override suspend fun awaitQuiescence() {
        withContext(Dispatchers.Default) { delay(2.seconds) }
    }

    override fun newSource(): PeerDiscoverySource {
        lib = ConformanceNativeLib()
        browser = lib.newBrowser()
        held.install(browser)
        return browser
    }

    override suspend fun causeArrival(source: PeerDiscoverySource) {
        lib.awaitBrowseSession()
        lib.fireFoundPeer(HANDLE, NAME)
    }

    fun state(at: String) = println(
        "W3075 $at: entered=${held.entered.get()} released=${held.released.get()} " +
            "depSubs=${browser.w3075DepartureSubs} founds=${lib.foundFirings} losts=${lib.lostFirings} " +
            "tryEmits=${browser.w3075TryEmits.get()}",
    )
}

class W3075ExperimentTest {
    /** ARM A: the departures producer stays held until AFTER the lost event fired. */
    @Test
    fun armA_departuresHeldPastTheLostEvent() {
        val suite = object : ExperimentSuite(Held(onDepartures = true)) {
            override fun departureFixture(source: PeerDiscoverySource): DepartureFixture = DepartureFixture.Emits {
                state("before fireLost")
                lib.fireLostPeer(HANDLE)
                state("after fireLost, before release")
                held.release()
            }
        }
        suite.departureKeyEqualsThePeerKeyThatWasDiscovered()
    }

    /** ARM B, the control: same hold, released and subscribed BEFORE the lost event. */
    @Test
    fun armB_departuresHeldButReleasedBeforeTheLostEvent() {
        val suite = object : ExperimentSuite(Held(onDepartures = true)) {
            override fun departureFixture(source: PeerDiscoverySource): DepartureFixture = DepartureFixture.Emits {
                state("before release")
                held.release()
                withContext(Dispatchers.Default) {
                    while (browser.w3075DepartureSubs == 0) delay(5)
                }
                state("subscribed, before fireLost")
                lib.fireLostPeer(HANDLE)
                state("after fireLost")
            }
        }
        suite.departureKeyEqualsThePeerKeyThatWasDiscovered()
        assertEquals(1, suite.held.entered.get(), "the hold must have fired")
        assertEquals(1, suite.held.released.get())
    }

    /** ARM C: the DISCOVERIES producer held past the found event. `replay = 1` should carry it. */
    @Test
    fun armC_discoveriesHeldPastTheFoundEvent() {
        val suite = object : ExperimentSuite(Held(onDepartures = false)) {
            override suspend fun causeArrival(source: PeerDiscoverySource) {
                super.causeArrival(source)
                state("after fireFound, before release")
                held.release()
            }

            override fun departureFixture(source: PeerDiscoverySource): DepartureFixture = DepartureFixture.Emits {
                state("before fireLost")
                lib.fireLostPeer(HANDLE)
            }
        }
        suite.departureKeyEqualsThePeerKeyThatWasDiscovered()
        assertEquals(1, suite.held.entered.get(), "the hold must have fired")
    }

    /** ARM D: a real consumer. discoveryRoster over the same held producer: does a ghost remain? */
    @Test
    fun armD_rosterKeepsAGhostWhenTheDeparturesProducerIsLate(): Unit = runBlocking {
        val lib = ConformanceNativeLib()
        val browser = lib.newBrowser()
        val held = Held(onDepartures = true)
        held.install(browser)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val roster = discoveryRoster(listOf(browser), scope)
            lib.awaitBrowseSession()
            lib.fireFoundPeer(HANDLE, NAME)
            withContext(Dispatchers.Default) {
                while (roster.value.isEmpty()) delay(5)
            }
            println(
                "W3075 armD roster after found=${roster.value.map { it.peerKey }} " +
                    "entered=${held.entered.get()} depSubs=${browser.w3075DepartureSubs}",
            )
            lib.fireLostPeer(HANDLE)
            held.release()
            withContext(Dispatchers.Default) {
                while (browser.w3075DepartureSubs == 0) delay(5)
                delay(1.seconds)
            }
            println(
                "W3075 armD roster 1s after loss=${roster.value.map { it.peerKey }} entered=${held.entered.get()} " +
                    "depSubs=${browser.w3075DepartureSubs} tryEmits=${browser.w3075TryEmits.get()}",
            )
            assertTrue(roster.value.isEmpty(), "ghost: ${roster.value.map { it.peerKey }} still in the roster after its loss")
        } finally {
            held.release()
            scope.cancel()
        }
    }
}
