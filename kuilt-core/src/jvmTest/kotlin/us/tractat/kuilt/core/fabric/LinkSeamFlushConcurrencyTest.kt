package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the race is two teardown paths on two real threads while senders and flushes run on others. A single virtual-time thread cannot put them in the window; LinkSeamFlushTest.aFlushCannotConfirmInsideAnotherPathsTear forces the same window on one thread.
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.runConcurrencyStress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * `Drained` under senders, flushes, and a local close racing a remote end, on real threads.
 *
 * What a `Drained` promises here is checkable: every frame whose send returned before the flush began
 * reached the connection before the connection closed. The connection is the worst case on purpose. It
 * never answers `Failed`, and it drops a frame after its own close and returns normally, so nothing
 * below the seam stops a false `Drained` from reaching the caller.
 *
 * **A rare race, so a red is strong evidence and a green on one run is weak.** This probe found one that
 * 3,000 local rounds kept passing: a path that lost the race to complete `torn` could close the connection
 * while `torn` still read open. A CI runner saw it at 3 in 115,223 flushes. A 16-core laptop saw 0 in
 * 24,635,342, until the JVM was held to its efficiency cores (`taskpolicy -c background`), where its own
 * threads get preempted: 26 in 6,369,578. Counting a `Drained` answered with `Torn` written and `torn` still
 * open gave 15 and 9 in the two arms counted, against 15 and 9 violations. A flush now decides by reading
 * `state` last, and the same arms read 0 in 41,393,190. Read a green here with that in mind: without that
 * read, 3,000 rounds on those fast cores usually pass.
 *
 * So what `check` runs is `LinkSeamFlushTest`: `aFlushCannotConfirmInsideAnotherPathsTear` forces the
 * window on one thread and reds every time without the read, and `everyPathHasTornBeforeItClosesTheConnection`
 * pins what the read rests on. This one is gated by its name, like every `*ConcurrencyTest`, and runs in
 * the `concurrency-probes` job.
 */
class LinkSeamFlushConcurrencyTest {
    /** Records whether each frame arrived before or after the close. Drops it silently after. */
    private class RecordingConnection : Connection {
        val handed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val closed = AtomicBoolean(false)
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)

        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

        override suspend fun send(frame: ByteArray) {
            if (Random.nextInt(YIELD_ONE_SEND_IN) == 0) yield()
            if (!closed.get()) handed.add(frame.decodeToString())
        }

        override suspend fun flush(timeout: Duration): FlushOutcome = FlushOutcome.Drained

        override suspend fun close() {
            closed.set(true)
            inbound.close()
        }

        fun remoteEnds() {
            inbound.close()
        }
    }

    private class Tally {
        val flushes = AtomicLong()
        val drained = AtomicLong()
        val violations = AtomicLong()
        val lostFrames = AtomicLong()
    }

    @Test
    fun aDrainedFlushHasHandedOverEveryFrameWhileATearRaces() = runConcurrencyStress { stage ->
        val tally = Tally()
        val rounds = AtomicInteger()
        repeat(ROUNDS) { round ->
            stage.at("round=$round")
            racingRound(round, tally)
            rounds.incrementAndGet()
        }
        // Each is a precondition, not decoration: a run that never reached a Drained checked nothing.
        assertEquals(ROUNDS, rounds.get(), "rig: every round ran")
        assertTrue(tally.flushes.get() > 0, "rig: no flush ran")
        assertTrue(tally.drained.get() > 0, "rig: no flush reached Drained, so the promise was never checked")
        assertEquals(
            0L,
            tally.violations.get(),
            "Drained with frames gone: ${tally.violations.get()} of ${tally.flushes.get()} flushes, " +
                "${tally.lostFrames.get()} frames",
        )
    }

    private suspend fun racingRound(round: Int, tally: Tally) {
        val conn = RecordingConnection()
        val seam = identified(conn, PeerId("self"), PeerId("remote"), Dispatchers.Default)
        val returned: MutableSet<String> = ConcurrentHashMap.newKeySet()
        coroutineScope {
            repeat(SENDERS) { sender ->
                launch(Dispatchers.Default) {
                    try {
                        repeat(FRAMES_PER_SENDER) { frame ->
                            val id = "$sender-$frame"
                            seam.broadcast(id.encodeToByteArray())
                            returned.add(id)
                            if (frame % FLUSH_EVERY == 0) flushAndCheck(seam, conn, returned, tally)
                        }
                    } catch (_: IllegalStateException) {
                        // A cancellation is an IllegalStateException too, and only this job's own may pass.
                        currentCoroutineContext().ensureActive()
                        // Otherwise the seam tore, which is the point of the round: this sender is done.
                    }
                }
            }
            launch(Dispatchers.Default) {
                delay(Random(round).nextLong(until = TEAR_WITHIN_MS))
                launch(Dispatchers.Default) { conn.remoteEnds() }
                launch(Dispatchers.Default) { seam.close() }
            }
        }
    }

    private suspend fun flushAndCheck(
        seam: us.tractat.kuilt.core.Seam,
        conn: RecordingConnection,
        returned: Set<String>,
        tally: Tally,
    ) {
        val covered = returned.toList()
        val outcome = seam.flush(Duration.INFINITE)
        tally.flushes.incrementAndGet()
        if (outcome != FlushOutcome.Drained) return
        tally.drained.incrementAndGet()
        // Read after the answer: anything the flush covered must have been handed over by now.
        val missing = covered.count { it !in conn.handed }
        if (missing > 0) {
            tally.violations.incrementAndGet()
            tally.lostFrames.addAndGet(missing.toLong())
        }
    }

    private companion object {
        const val ROUNDS = 3_000
        const val SENDERS = 8
        const val FRAMES_PER_SENDER = 120
        const val FLUSH_EVERY = 7
        const val YIELD_ONE_SEND_IN = 8
        const val TEAR_WITHIN_MS = 3L
    }
}
