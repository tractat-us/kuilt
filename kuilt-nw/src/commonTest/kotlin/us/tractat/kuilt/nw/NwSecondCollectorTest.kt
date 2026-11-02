@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Two readers of one live [NwSeam] (#3073).
 *
 * `Seam.incoming` hands each frame to one collector, so a second collector takes frames from the first
 * and neither is told. [NwLoom] answers a repeat of a live rendezvous with the live seam, which is how a
 * second room or lobby comes to read the same seam.
 *
 * Every test uses the virtual clock. A refusal is the one thing a collector here catches, so the
 * [Collector.refusal] of a healthy collector stays `null`.
 */
class NwSecondCollectorTest {

    private val heartbeat = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 5.seconds,
    )

    private val tag = InMemoryTag("session", peerKey = "joiner")

    /** What one collector read, in order, and the refusal that ended it if there was one. */
    private class Collector {
        val frames: MutableList<Int> = mutableListOf()
        var refusal: IllegalStateException? = null
        lateinit var job: Job
    }

    /** Starts collecting at once, inline, so the claim is made before this returns. */
    private fun TestScope.collectorOn(seam: Seam): Collector {
        val collector = Collector()
        collector.job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { collectInto(collector, seam) }
        return collector
    }

    private suspend fun collectInto(collector: Collector, seam: Seam) {
        try {
            seam.incoming.collect { swatch -> collector.frames += swatch.byteAt(0).toInt() }
        } catch (refused: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            collector.refusal = refused
        }
    }

    /** Two peers woven over one radio, handed to [block], and closed whatever [block] does. */
    private suspend fun TestScope.withFormedSeams(block: suspend (seam: Seam, peer: Seam) -> Unit) {
        val radio = FakeNwRadio()
        val loom = NwLoom(FakeNwApi(radio, deviceId = "joiner", serviceName = "joiner"), SERVICE, random = Random(1))
        val peerLoom = NwLoom(FakeNwApi(radio, deviceId = "peer", serviceName = "peer"), SERVICE, random = Random(2))
        val (seam, peer) = coroutineScope {
            val a = async { loom.join(tag) }
            val b = async { peerLoom.join(InMemoryTag("session", peerKey = "peer")) }
            a.await() to b.await()
        }
        testScheduler.runCurrent()
        try {
            block(seam, peer)
        } finally {
            withContext(NonCancellable) {
                seam.close()
                peer.close()
            }
        }
    }

    private suspend fun TestScope.sendFrames(from: Seam) {
        repeat(FRAMES) { index -> from.broadcast(byteArrayOf(index.toByte())) }
        testScheduler.advanceTimeBy(50.milliseconds)
        testScheduler.runCurrent()
    }

    @Test
    fun aSecondCollectorOnALiveSeamIsRefusedAndTheFirstKeepsEveryFrame() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormedSeams { seam, peer ->
                val first = collectorOn(seam)
                val second = collectorOn(seam)

                sendFrames(from = peer)

                assertAll(
                    { assertEquals(FRAMES, first.frames.size + second.frames.size, "the rig's frames did arrive") },
                    { assertNotNull(second.refusal, "split ${first.frames.size}/${second.frames.size}, and no error") },
                    { assertEquals((0 until FRAMES).toList(), first.frames, "the first collector keeps everything") },
                    { assertNull(first.refusal, "the first collector is untouched") },
                )
            }
        }

    /**
     * The order `SeamRoom.restartIncomingCollect` uses: cancel, then start again, with no join between.
     * Starting the second inline means it claims while the first is still suspended and has not unwound,
     * which is what a multi-threaded dispatcher can do to the room's own restart.
     */
    @Test
    fun aCollectorStartedAfterCancellingTheLastWithoutJoiningIsNotRefused() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormedSeams { seam, peer ->
                val cancelled = collectorOn(seam)
                cancelled.job.cancel()
                val stillUnwinding = !cancelled.job.isCompleted

                val restarted = collectorOn(seam)
                sendFrames(from = peer)

                assertAll(
                    { assertTrue(stillUnwinding, "the rig must overlap the two collectors") },
                    { assertNull(restarted.refusal, "the restart was refused: ${restarted.refusal}") },
                    { assertEquals((0 until FRAMES).toList(), restarted.frames, "the restart reads everything") },
                )
            }
        }

    /** The cancelled collector unwinds after the restart has claimed, and must not free the restart's claim. */
    @Test
    fun aCancelledCollectorFinishingLateLeavesTheRestartHoldingTheSeam() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormedSeams { seam, _ ->
                val cancelled = collectorOn(seam)
                cancelled.job.cancel()
                val restarted = collectorOn(seam)
                testScheduler.runCurrent()
                val unwound = cancelled.job.isCompleted

                val third = collectorOn(seam)

                assertAll(
                    { assertTrue(unwound, "the rig must have let the cancelled collector finish") },
                    { assertNull(restarted.refusal, "the restart was refused: ${restarted.refusal}") },
                    { assertNotNull(third.refusal, "the restart still holds the seam") },
                )
            }
        }

    /**
     * Read in one coroutine, because its [Job] is still active when it reads again: only the first collector
     * letting go can free the seam here, not its job ending.
     */
    @Test
    fun aCollectorThatHasFinishedLeavesTheSeamFreeForTheSameCoroutine() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            withFormedSeams { seam, peer ->
                val next = Collector()
                var taken: Int? = null
                next.job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    taken = seam.incoming.first().byteAt(0).toInt()
                    collectInto(next, seam)
                }
                testScheduler.runCurrent()
                peer.broadcast(byteArrayOf(FIRST_FRAME))
                testScheduler.advanceTimeBy(50.milliseconds)
                testScheduler.runCurrent()

                sendFrames(from = peer)

                assertAll(
                    { assertEquals(FIRST_FRAME.toInt(), taken, "the rig's first collector did read") },
                    { assertNull(next.refusal, "the seam was free: ${next.refusal}") },
                    { assertEquals((0 until FRAMES).toList(), next.frames) },
                )
            }
        }

    /**
     * A host and an admitted joiner over rooms whose factory reports what escapes it into [escaped], handed to
     * [block], and left whatever [block] does. What escapes a room's coroutine ends up in the scope it was
     * built with, so that is where a second room's refusal is loud. This scope has a handler so the test can
     * read it. An application's scope with no handler aborts on Kotlin/Native instead.
     */
    private suspend fun TestScope.withAdmittedJoiner(
        escaped: MutableList<Throwable>,
        block: suspend (factory: SeamRoomFactory, host: Room, first: Room) -> Unit,
    ) {
        val radio = FakeNwRadio()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val hostFactory = SeamRoomFactory(
            NwLoom(FakeNwApi(radio, "host", "host"), SERVICE, random = Random(0)),
            backgroundScope,
            clock,
            heartbeat,
        )
        val joinerScope = CoroutineScope(
            backgroundScope.coroutineContext +
                SupervisorJob(backgroundScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, failure -> escaped += failure },
        )
        val factory = SeamRoomFactory(
            NwLoom(FakeNwApi(radio, "joiner", "joiner"), SERVICE, random = Random(1)),
            joinerScope,
            clock,
            heartbeat,
        )
        val (host, first) = coroutineScope {
            val h = async { hostFactory.host(Pattern("session")) }
            val j = async { factory.join(tag) }
            h.await() to j.await()
        }
        try {
            host.roster.first { it.size == 1 }
            first.roster.first { it.size == 1 }
            block(factory, host, first)
        } finally {
            withContext(NonCancellable) {
                first.leave()
                host.leave()
            }
        }
    }

    private fun TestScope.framesOf(room: Room): MutableList<Int> {
        val frames = mutableListOf<Int>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            room.incoming.collect { frame -> frames += frame.payload[0].toInt() }
        }
        return frames
    }

    @Test
    fun aSecondRoomOverTheSameTagIsRefusedLoudlyAndTheFirstRoomKeepsEveryFrame() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val escaped = mutableListOf<Throwable>()
            withAdmittedJoiner(escaped) { factory, host, first ->
                val read = framesOf(first)

                val second = factory.join(tag)
                testScheduler.advanceTimeBy(50.milliseconds)
                testScheduler.runCurrent()
                val heardBeforeFrames = escaped.toList()

                repeat(FRAMES) { index -> host.broadcast(byteArrayOf(index.toByte())) }
                testScheduler.advanceTimeBy(50.milliseconds)
                testScheduler.runCurrent()

                assertAll(
                    { assertIs<IllegalStateException>(heardBeforeFrames.singleOrNull(), "heard: $heardBeforeFrames") },
                    { assertEquals(1, first.roster.value.size, "the first room is still in the session") },
                    { assertEquals((0 until FRAMES).toList(), read, "the first room reads every frame") },
                )
                second.leave()
            }
        }

    private companion object {
        const val SERVICE: String = "_kuilt._tcp"
        const val FRAMES: Int = 20
        const val FIRST_FRAME: Byte = 77
    }
}
