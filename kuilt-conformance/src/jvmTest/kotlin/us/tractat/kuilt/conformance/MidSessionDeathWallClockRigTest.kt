package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fabric whose joiner hears about a transport death **on the wall clock** must pass
 * [SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath] (#3483).
 *
 * That is every real-IO harness. `WebSocketConformanceTest` stops its Netty server to inject the
 * death; the server seam tears inside that blocking `stop`, but the client seam tears only when its
 * own reader thread sees the socket close, a few milliseconds later. The obligation used to bound
 * each wait with `withTimeout(5.seconds)`, and under `runTest` that bound is **virtual**: when the
 * test coroutine parks on the joiner's `state` and nothing else is queued, the scheduler jumps the
 * clock straight to the bound. So the obligation passed only when the client had already torn by
 * the time the wait began, and otherwise reddened with "Timed out after 5s of _virtual_ time" in
 * about 16 ms of real time. Whether the client won that race moved with JIT warmth and machine
 * load, which is why it looked like an ordering effect.
 *
 * This rig removes the race by forcing the losing side every time. The host tears inside
 * `injectMidSessionDeath`; the joiner is torn from a plain thread only **after** the obligation has
 * subscribed to the joiner's `state`, plus a grace period. A virtual-time bound on that wait reds
 * here on every run; an unbounded wait, with `runTest`'s wall-clock ceiling as the backstop, passes.
 *
 * ## What the rig asserts about itself
 *
 * Green by absence is the failure this file exists to rule out, so the rig counts its own firing:
 * the joiner was torn exactly once, it was still live at that moment, and the obligation was
 * already waiting on it. Without those, a rig whose tear landed before the wait began would pass
 * on the old, broken obligation too.
 *
 * JVM-only because the late tear needs a real thread, and the property is about `runTest`'s clock,
 * which behaves the same on every target. The harness is an anonymous object for the reason
 * [SymmetricLifecycleObligationRigTest] gives: a named subclass would be collected as a test class.
 */
class MidSessionDeathWallClockRigTest {

    @Test
    fun aJoinerThatTearsOnTheWallClockPassesTheMidSessionDeathObligation(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = WallClockDeathRig()
            try {
                rig.harness.runIncomingCompletesOnInjectedMidSessionDeath(this)
            } finally {
                rig.joinTearer()
            }
            assertAll(
                { assertEquals(1, rig.lateTears.get(), "the rig must tear the joiner exactly once") },
                {
                    assertTrue(
                        rig.suiteWasWaiting,
                        "the obligation must already be waiting on the joiner's state when the rig tears it, " +
                            "or the tear did not land late and this rig proves nothing",
                    )
                },
                {
                    assertTrue(
                        rig.joinerWasLiveWhenTorn,
                        "the joiner must still be live when the rig tears it; otherwise the host's close " +
                            "already tore it and nothing arrived on the wall clock",
                    )
                },
            )
        }

    private class WallClockDeathRig {
        private val suiteSubscribed = CountDownLatch(1)

        @Volatile private var injected = false

        @Volatile var suiteWasWaiting = false
            private set

        @Volatile var joinerWasLiveWhenTorn = false
            private set

        val lateTears = AtomicInteger()

        @Volatile private var tearer: Thread? = null

        private val loom: Loom = DecoratingJoinerLoom(InMemoryLoom()) { joiner ->
            SeamWithWatchedState(joiner) { if (injected) suiteSubscribed.countDown() }
        }

        val harness: SeamConformanceSuite = object : SeamConformanceSuite() {
            override fun newLoomPair(): Pair<Loom, Loom> = loom to loom

            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(reportsLiveCapability = false)

            override fun capabilityGaps(): Map<String, String> = mapOf("reportsLiveCapability" to GAP_URL)

            override fun joinerRosterOrigin(): JoinerRosterOrigin =
                JoinerRosterOrigin.FilledByConstruction("a rig over one InMemoryLoom (#3483 positive control)")

            override fun midSessionDeathDeclaration(): ObligationDeclaration = ObligationDeclaration.Proven

            override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
                check(host.state.value !is SeamState.Torn && joiner.state.value !is SeamState.Torn) {
                    "rig precondition: both seams must be live before the injection; " +
                        "got host=${host.state.value}, joiner=${joiner.state.value}"
                }
                host.close()
                injected = true
                tearer = thread(name = "mid-session-death-wall-clock-rig") {
                    suiteWasWaiting = suiteSubscribed.await(SUBSCRIBE_WAIT_SECONDS, TimeUnit.SECONDS)
                    Thread.sleep(GRACE_MILLIS)
                    joinerWasLiveWhenTorn = joiner.state.value !is SeamState.Torn
                    runBlocking { joiner.close() }
                    lateTears.incrementAndGet()
                }
                return true
            }
        }

        fun joinTearer() {
            tearer?.join(TimeUnit.SECONDS.toMillis(SUBSCRIBE_WAIT_SECONDS) + GRACE_MILLIS * 10)
        }
    }

    /**
     * A joiner whose `state` reports each new collection to [onCollect]. `value` and everything else
     * delegate unchanged, so the seam behaves exactly as the reference does; the rig only learns
     * when someone starts waiting on it.
     */
    private class SeamWithWatchedState(delegate: Seam, onCollect: () -> Unit) : Seam by delegate {
        override val state: StateFlow<SeamState> = WatchedStateFlow(delegate.state, onCollect)
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class WatchedStateFlow<T>(
        private val delegate: StateFlow<T>,
        private val onCollect: () -> Unit,
    ) : StateFlow<T> by delegate {
        override suspend fun collect(collector: FlowCollector<T>): Nothing {
            onCollect()
            delegate.collect(collector)
        }
    }

    private companion object {
        const val GAP_URL = "https://github.com/tractat-us/kuilt/issues/3483"

        /** Long enough that a virtual bound has certainly fired first; short enough to cost nothing. */
        const val GRACE_MILLIS = 100L

        /** A ceiling on waiting for the obligation to subscribe, so a rig that never sees it still ends. */
        const val SUBSCRIBE_WAIT_SECONDS = 10L
    }
}
