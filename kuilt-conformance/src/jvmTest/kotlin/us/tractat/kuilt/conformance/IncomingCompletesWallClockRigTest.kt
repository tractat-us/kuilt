package us.tractat.kuilt.conformance

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A fabric whose `close()` returns before its `incoming` completes, and completes it later **on the
 * wall clock**, must pass [SeamConformanceSuite.incomingCompletesWhenSeamCloses] (#3519).
 *
 * That is the shape of a real-IO fabric whose reader completes `incoming` on its own thread after
 * `close()` returns. The obligation used to wait on each end inside `withTimeoutOrNull(5.seconds)`,
 * and under `runTest` that bound is **virtual**: once the collector parks and nothing else is
 * queued, the scheduler jumps the clock to the bound, the wait returns `null`, and the named arm
 * reds in a few ms of real time on a fabric that was about to complete. #3483 was the same shape in
 * `incomingCompletesOnInjectedMidSessionDeath`; [MidSessionDeathWallClockRigTest] pins that one.
 *
 * This rig forces the losing side every time, once per end. The decorated seam's `close()` returns
 * at once and hands the real close to a plain thread. A virtual bound on the wait reds here on every
 * run; an unbounded wait, with `runTest`'s wall-clock ceiling as the backstop, passes.
 *
 * ## When the thread closes the real seam
 *
 * Only once `runTest` is **advancing the virtual clock**, plus [GRACE_MILLIS]. `close()` schedules a
 * [PARK_TICK] virtual delay; that delay runs only when the obligation has parked and the scheduler
 * has started skipping time, and the jump to any later virtual bound follows within microseconds.
 * The thread waits for the tick, then sleeps the grace period, then closes.
 *
 * Anchoring the grace to the obligation merely *collecting* is not enough, and it was measured. The
 * obligation starts each collector `UNDISPATCHED`, so collection begins before `close()` is called.
 * A grace started there also covers `close()` returning and the scheduler's first pass, and on a
 * cold JVM that ate the whole 100 ms: a mutant with a 5 s virtual bound on the joiner's wait passed.
 *
 * ## What the rig asserts about itself
 *
 * The rig counts its own firing, or a tear that landed before the wait began would pass on the old,
 * broken obligation too. The late completion fired exactly once. The virtual tick had run when it
 * did. The collection was still open at that moment. And the obligation was already collecting when
 * it called `close()`, which is the consumer's shape. That last arm reds if the collector goes back
 * to being merely queued.
 *
 * JVM-only because the late completion needs a real thread. The harness is an anonymous object for
 * the reason [SymmetricLifecycleObligationRigTest] gives: a named subclass would be collected as a
 * test class.
 */
class IncomingCompletesWallClockRigTest {

    @Test
    fun aHostWhoseIncomingCompletesOnTheWallClockPassesTheTerminationObligation(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) { assertTheLateEndPasses(LateEnd.HOST) }

    @Test
    fun aJoinerWhoseIncomingCompletesOnTheWallClockPassesTheTerminationObligation(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) { assertTheLateEndPasses(LateEnd.JOINER) }

    private suspend fun TestScope.assertTheLateEndPasses(end: LateEnd) {
        val rig = LateCompletionRig(end, this)
        try {
            rig.harness.runIncomingCompletesWhenSeamCloses(this)
        } finally {
            rig.joinCompleter()
        }
        assertAll(
            { assertEquals(1, rig.lateCompletions.get(), "the rig must complete the $end's incoming exactly once") },
            {
                assertTrue(
                    rig.clockWasAdvancing,
                    "the virtual clock must have started advancing before the rig completes the $end's " +
                        "incoming, or the completion did not land late and this rig proves nothing",
                )
            },
            {
                assertTrue(
                    rig.collectingWhenClosed,
                    "the obligation must already be collecting the $end's incoming when it calls close(); " +
                        "a collector that starts afterwards tests a late subscriber, not the consumer that " +
                        "is mid-collection when its seam closes",
                )
            },
            {
                assertTrue(
                    rig.collectionWasOpenWhenCompleted,
                    "the $end's incoming must still be open when the rig completes it; otherwise something " +
                        "else completed it and nothing arrived on the wall clock",
                )
            },
        )
    }

    private enum class LateEnd { HOST, JOINER }

    private class LateCompletionRig(end: LateEnd, private val scope: TestScope) {
        private val clockAdvancing = CountDownLatch(1)
        private val collectionOpen = AtomicBoolean(false)

        @Volatile var clockWasAdvancing = false
            private set

        @Volatile var collectionWasOpenWhenCompleted = false
            private set

        @Volatile var collectingWhenClosed = false
            private set

        val lateCompletions = AtomicInteger()

        @Volatile private var completer: Thread? = null

        private val loom: Loom = DecoratingOneEndLoom(InMemoryLoom(), decorateHost = end == LateEnd.HOST) { seam ->
            SeamCompletingIncomingLate(seam)
        }

        val harness: SeamConformanceSuite = object : SeamConformanceSuite() {
            override fun newLoomPair(): Pair<Loom, Loom> = loom to loom

            override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL.copy(reportsLiveCapability = false)

            override fun capabilityGaps(): Map<String, String> =
                mapOf("reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY)

            override fun joinerRosterOrigin(): JoinerRosterOrigin =
                JoinerRosterOrigin.FilledByConstruction("a rig over one InMemoryLoom (#3519 positive control)")
        }

        fun joinCompleter() {
            completer?.join(TimeUnit.SECONDS.toMillis(TICK_WAIT_SECONDS) + GRACE_MILLIS * 10)
        }

        /**
         * A seam whose first `close()` returns at once and hands the real close to a plain thread,
         * which runs it only after the [PARK_TICK] has run, plus [GRACE_MILLIS]. Every later
         * `close()` (the fixture's teardown) delegates directly.
         */
        private inner class SeamCompletingIncomingLate(private val delegate: Seam) : Seam by delegate {
            private val closes = AtomicInteger()

            override val incoming: Flow<Swatch> = flow {
                collectionOpen.set(true)
                try {
                    emitAll(delegate.incoming)
                } finally {
                    collectionOpen.set(false)
                }
            }

            override suspend fun close(reason: CloseReason) {
                if (closes.getAndIncrement() > 0) {
                    delegate.close(reason)
                    return
                }
                collectingWhenClosed = collectionOpen.get()
                scope.backgroundScope.launch {
                    delay(PARK_TICK)
                    clockAdvancing.countDown()
                }
                completer = thread(name = "incoming-completes-wall-clock-rig") {
                    clockWasAdvancing = clockAdvancing.await(TICK_WAIT_SECONDS, TimeUnit.SECONDS)
                    Thread.sleep(GRACE_MILLIS)
                    collectionWasOpenWhenCompleted = collectionOpen.get()
                    runBlocking { delegate.close(reason) }
                    lateCompletions.incrementAndGet()
                }
            }
        }
    }

    /**
     * Applies [decorate] to the hosted seam when [decorateHost], else to the joined one, and to
     * nothing else. Implemented rather than delegated for the reason [DecoratingJoinerLoom] gives.
     */
    private class DecoratingOneEndLoom(
        private val inner: Loom,
        private val decorateHost: Boolean,
        private val decorate: (Seam) -> Seam,
    ) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val seam = inner.weave(rendezvous)
            val isHost = rendezvous is Rendezvous.New
            return if (isHost == decorateHost) decorate(seam) else seam
        }

        override fun capability(): TransportCapability = inner.capability()
    }

    private companion object {
        /**
         * The rig's own virtual timer. It runs only once the obligation has parked and `runTest` is
         * skipping time, so it marks the moment a virtual bound would be about to fire.
         */
        val PARK_TICK = 1.milliseconds

        /** Long enough that a virtual bound has certainly fired first; short enough to cost nothing. */
        const val GRACE_MILLIS = 100L

        /** A ceiling on waiting for the tick, so a rig whose tick never runs still ends. */
        const val TICK_WAIT_SECONDS = 10L
    }
}
