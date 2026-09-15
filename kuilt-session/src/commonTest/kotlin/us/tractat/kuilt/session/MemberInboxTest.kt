package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MemberInbox] at the two boundaries a room-level property cannot place precisely (#2802).
 *
 * The first is a collection cancelled at the instant a frame is offered. A `Channel`-backed inbox handed
 * the frame to the waiting receiver and then lost it to the cancellation, so the next collection skipped
 * it. The second is the other side of the same boundary: once a frame is handed to the collector it is
 * the consumer's, and `take(n)` — which aborts by throwing *after* delivering — must not see it again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberInboxTest {
    private fun frame(text: String) = RoomFrame(sender = PeerId("member"), payload = text.encodeToByteArray())

    @Test
    fun `a collection cancelled as a frame is offered leaves that frame for the next collection`() =
        runTest {
            val inbox = MemberInbox(PeerId("member"), capacity = 4)
            val frames = inbox.claim()
            val delivered = mutableListOf<String>()
            val first = launch { frames.collect { delivered += it.payload.decodeToString() } }
            runCurrent() // parked, waiting for a frame

            inbox.offer(frame("a")) // wakes the parked collection...
            first.cancel() // ...which is cancelled before it runs
            runCurrent()
            inbox.offer(frame("b"))
            inbox.close()

            val next = frames.toList().map { it.payload.decodeToString() }
            assertAll(
                { assertTrue(first.isCancelled, "rig: the first collection was cancelled") },
                { assertEquals(emptyList<String>(), delivered, "rig: the cancelled collection delivered nothing") },
                {
                    assertEquals(
                        listOf("a", "b"),
                        next,
                        "the next collection receives the frame the cancelled one never delivered, then the rest, then completes",
                    )
                },
            )
        }

    /**
     * Runs dispatched tasks only when told to, one at a time, so a test can place a cancellation between
     * two steps of a collector — a window the test scheduler cannot open, because it runs everything that
     * is ready before returning.
     */
    private class StepDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        val pending: Int get() = tasks.size

        fun step(): Boolean {
            val task = tasks.removeFirstOrNull() ?: return false
            task.run()
            return true
        }

        fun drain() {
            while (step()) Unit
        }
    }

    @Test
    fun `a collection cancelled right after the step that took a frame still delivers every frame exactly once`() =
        runTest {
            val inbox = MemberInbox(PeerId("member"), capacity = 4)
            val frames = inbox.claim()
            val steps = StepDispatcher()
            val delivered = mutableListOf<String>()
            val first = CoroutineScope(steps + Job()).launch { frames.collect { delivered += it.payload.decodeToString() } }
            steps.drain() // started, parked waiting for a frame

            inbox.offer(frame("a")) // wakes the parked collection: one task
            val wakeTasks = steps.pending
            steps.step() // the collection runs exactly the step that takes the frame...
            first.cancel() // ...and is cancelled at whatever suspension that step ended on
            steps.drain()
            inbox.offer(frame("b"))
            inbox.close()

            val rest = frames.toList().map { it.payload.decodeToString() }
            assertAll(
                { assertEquals(1, wakeTasks, "rig: the offer woke the parked collection with exactly one task") },
                { assertTrue(first.isCompleted, "rig: the first collection has ended") },
                {
                    assertEquals(
                        listOf("a", "b"),
                        delivered + rest,
                        "a frame taken by the cancelled collection is either delivered by it or left for the next — never lost",
                    )
                },
            )
        }

    @Test
    fun `a frame take(n) received is not delivered again to the next collection`() =
        runTest {
            val inbox = MemberInbox(PeerId("member"), capacity = 4)
            listOf("f0", "f1", "f2").forEach { inbox.offer(frame(it)) }
            inbox.close()
            val frames = inbox.claim()

            val first = frames.take(1).toList().map { it.payload.decodeToString() }
            val rest = frames.toList().map { it.payload.decodeToString() }
            assertAll(
                { assertEquals(listOf("f0"), first, "take(1) receives the first frame") },
                { assertEquals(listOf("f1", "f2"), rest, "take(1) aborts by throwing after f0 was delivered, so f0 must not come back") },
            )
        }
}
