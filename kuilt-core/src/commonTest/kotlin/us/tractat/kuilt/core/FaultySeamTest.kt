package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [FaultySeam] and [FaultyLoom].
 *
 * All tests run under [runTest] for virtual-time control — no wall-clock
 * dependencies. Every probabilistic profile uses a fixed seed so results
 * are deterministic across runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FaultySeamTest {
    // ── Healthy profile ───────────────────────────────────────────────────────

    @Test
    fun `Healthy profile delivers all frames in order`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            val received = async { b.incoming.take(3).toList() }
            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))
            a.broadcast(byteArrayOf(3))

            val frames = received.await()
            assertAll(
                { assertEquals(3, frames.size) },
                { assertTrue(frames[0].payload.contentEquals(byteArrayOf(1))) },
                { assertTrue(frames[1].payload.contentEquals(byteArrayOf(2))) },
                { assertTrue(frames[2].payload.contentEquals(byteArrayOf(3))) },
            )
        }

    @Test
    fun `Healthy profile has zero drops`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            val received = async { b.incoming.take(5).toList() }
            repeat(5) { i -> a.broadcast(byteArrayOf(i.toByte())) }
            received.await()

            assertEquals(0L, a.framesDropped)
        }

    // ── DropAll ───────────────────────────────────────────────────────────────

    @Test
    fun `DropAll Both drops all outbound frames and records drops`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            factory.join(InMemoryTag("Bob"))

            a.setFaultProfile(FaultProfile.DropAll(Direction.Both))
            repeat(5) { a.broadcast(byteArrayOf(it.toByte())) }

            assertEquals(5L, a.framesDropped)
            assertEquals(0L, a.framesDelivered)
        }

    @Test
    fun `DropAll Inbound drops incoming frames`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            b.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            // Send 3 frames from A, which would normally arrive at B
            repeat(3) { a.broadcast(byteArrayOf(it.toByte())) }

            // Give coroutines time to process
            testScheduler.advanceUntilIdle()

            // B's incoming channel should be empty — all inbound frames dropped
            var received = false
            val job =
                launch {
                    b.incoming.first()
                    received = true
                }
            testScheduler.advanceUntilIdle()
            job.cancel()

            assertFalse(received, "B should not have received any frames when Inbound is dropped")
        }

    @Test
    fun `DropAll Outbound does not affect inbound frames`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // B drops outbound only — A→B inbound path for B is unaffected
            b.setFaultProfile(FaultProfile.DropAll(Direction.Outbound))

            val received = async { b.incoming.first() }
            a.broadcast(byteArrayOf(42))

            val frame = received.await()
            assertTrue(frame.payload.contentEquals(byteArrayOf(42)))
        }

    // ── Partition / heal ──────────────────────────────────────────────────────

    @Test
    fun `partition drops frames and heal restores delivery`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            a.partition()
            repeat(3) { a.broadcast(byteArrayOf(it.toByte())) }
            assertEquals(3L, a.framesDropped)

            a.heal()
            val received = async { b.incoming.first() }
            a.broadcast(byteArrayOf(99))
            val frame = received.await()
            assertTrue(frame.payload.contentEquals(byteArrayOf(99)))
        }

    // ── DropProbabilistic ─────────────────────────────────────────────────────

    @Test
    fun `DropProbabilistic with probability 0 never drops`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            a.setFaultProfile(FaultProfile.DropProbabilistic(probability = 0.0, seed = 42L))
            val received = async { b.incoming.take(5).toList() }
            repeat(5) { a.broadcast(byteArrayOf(it.toByte())) }

            val frames = received.await()
            assertEquals(5, frames.size)
            assertEquals(0L, a.framesDropped)
        }

    @Test
    fun `DropProbabilistic with probability 1 drops all`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            factory.join(InMemoryTag("Bob"))

            a.setFaultProfile(FaultProfile.DropProbabilistic(probability = 1.0, seed = 42L))
            repeat(10) { a.broadcast(byteArrayOf(it.toByte())) }

            assertEquals(10L, a.framesDropped)
        }

    @Test
    fun `DropProbabilistic is deterministic — same seed produces same drop set`() =
        runTest {
            val run1 = droppedIndexesForSeed(seed = 123L, scope = backgroundScope)
            val run2 = droppedIndexesForSeed(seed = 123L, scope = backgroundScope)
            assertEquals(run1, run2)
        }

    @Test
    fun `DropProbabilistic different seeds produce different results`() =
        runTest {
            val run1 = droppedIndexesForSeed(seed = 1L, scope = backgroundScope)
            val run2 = droppedIndexesForSeed(seed = 9999L, scope = backgroundScope)
            // Not guaranteed to differ for every seed pair, but overwhelmingly likely
            // with probability 0.5 and 100 frames.
            assertFalse(run1 == run2, "Different seeds should produce different drop patterns")
        }

    // ── DropSpecific ──────────────────────────────────────────────────────────

    @Test
    fun `DropSpecific drops only listed frame indexes`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // Drop frames 0 and 2; deliver 1, 3, 4
            a.setFaultProfile(FaultProfile.DropSpecific(setOf(0, 2)))
            val received = async { b.incoming.take(3).toList() }
            repeat(5) { a.broadcast(byteArrayOf(it.toByte())) }

            val frames = received.await()
            assertAll(
                { assertEquals(3, frames.size) },
                { assertTrue(frames[0].payload.contentEquals(byteArrayOf(1))) },
                { assertTrue(frames[1].payload.contentEquals(byteArrayOf(3))) },
                { assertTrue(frames[2].payload.contentEquals(byteArrayOf(4))) },
            )
        }

    @Test
    fun `DropSpecific Inbound drops listed incoming frames by index`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // B drops inbound frames at index 1 and 3
            b.setFaultProfile(FaultProfile.DropSpecific(setOf(1, 3), Direction.Inbound))

            val received = async { b.incoming.take(3).toList() }
            repeat(5) { a.broadcast(byteArrayOf(it.toByte())) }
            testScheduler.advanceUntilIdle()

            val frames = received.await()
            assertAll(
                { assertEquals(3, frames.size) },
                { assertTrue(frames[0].payload.contentEquals(byteArrayOf(0))) },
                { assertTrue(frames[1].payload.contentEquals(byteArrayOf(2))) },
                { assertTrue(frames[2].payload.contentEquals(byteArrayOf(4))) },
            )
        }

    // ── DelayAll ──────────────────────────────────────────────────────────────

    @Test
    fun `DelayAll respects virtual time — frame not delivered before delay elapses`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            a.setFaultProfile(FaultProfile.DelayAll(100.milliseconds))

            val received = async { b.incoming.first() }
            launch { a.broadcast(byteArrayOf(7)) }

            // Advance by less than the delay — frame should not have arrived yet
            testScheduler.advanceTimeBy(99)
            assertFalse(received.isCompleted, "Frame should not be delivered before 100ms delay")

            // Advance past the delay — frame should now be delivered
            testScheduler.advanceTimeBy(2)
            testScheduler.runCurrent()

            val frame = received.await()
            assertTrue(frame.payload.contentEquals(byteArrayOf(7)))
            assertEquals(1L, a.framesDelayed)
        }

    @Test
    fun `DelayAll Inbound delays incoming frames`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            b.setFaultProfile(FaultProfile.DelayAll(100.milliseconds, Direction.Inbound))

            val received = async { b.incoming.first() }
            a.broadcast(byteArrayOf(7))
            testScheduler.runCurrent()

            assertFalse(received.isCompleted, "Inbound frame should be delayed")

            testScheduler.advanceTimeBy(101)
            testScheduler.runCurrent()

            val frame = received.await()
            assertTrue(frame.payload.contentEquals(byteArrayOf(7)))
        }

    // ── ReorderWindow ─────────────────────────────────────────────────────────

    @Test
    fun `ReorderWindow is deterministic — same seed produces same permutation`() =
        runTest {
            suspend fun collectPayloads(scope: CoroutineScope): List<Int> {
                val innerFactory = FaultyLoom(InMemoryLoom(), scope)
                val sender = innerFactory.open(Pattern("Alice"))
                val receiver = innerFactory.join(InMemoryTag("Bob"))
                sender.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 4, seed = 42L))
                val received = async { receiver.incoming.take(4).toList() }
                repeat(4) { sender.broadcast(byteArrayOf(it.toByte())) }
                return received.await().map { it.payload[0].toInt() }
            }

            val run1 = collectPayloads(backgroundScope)
            val run2 = collectPayloads(backgroundScope)
            assertEquals(run1, run2)
        }

    @Test
    fun `ReorderWindow flushes exactly windowSize frames at once`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            a.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 3, seed = 1L))

            // Only 2 frames — window not yet full, nothing delivered
            a.broadcast(byteArrayOf(10))
            a.broadcast(byteArrayOf(20))
            testScheduler.advanceUntilIdle()

            var received = false
            val probe =
                launch {
                    b.incoming.first()
                    received = true
                }
            testScheduler.advanceUntilIdle()
            probe.cancel()
            assertFalse(received, "Window not full — no frames should have been flushed yet")

            // 3rd frame fills the window — all 3 should now arrive
            val all3 = async { b.incoming.take(3).toList() }
            a.broadcast(byteArrayOf(30))
            val frames = all3.await()
            assertEquals(3, frames.size)
        }

    // ── BufferCeiling ─────────────────────────────────────────────────────────

    @Test
    fun `BufferCeiling drops frames beyond the send quota`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // maxOutbound=2 means frames 0 and 1 are delivered; frame 2+ are dropped
            a.setFaultProfile(FaultProfile.BufferCeiling(maxOutbound = 2))

            val received = async { b.incoming.take(2).toList() }

            a.broadcast(byteArrayOf(1)) // index 0 → delivered
            a.broadcast(byteArrayOf(2)) // index 1 → delivered
            a.broadcast(byteArrayOf(3)) // index 2 → dropped (quota exhausted)

            received.await()

            assertAll(
                { assertEquals(1L, a.framesDropped) },
                { assertEquals(2L, a.framesDelivered) },
            )
        }

    // ── CloseAt ───────────────────────────────────────────────────────────────

    @Test
    fun `CloseAt closes link at the specified outbound frame index`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // Close after frame 0 is sent (i.e. frame index 1 triggers close)
            a.setFaultProfile(FaultProfile.CloseAt(frameIndex = 1))

            val received = async { b.incoming.take(1).toList() }
            a.broadcast(byteArrayOf(10)) // index 0 — delivered
            a.broadcast(byteArrayOf(20)) // index 1 — triggers close instead of send

            val frames = received.await()
            assertAll(
                { assertEquals(1, frames.size) },
                { assertTrue(frames[0].payload.contentEquals(byteArrayOf(10))) },
            )

            // A is no longer in B's peer set after close
            testScheduler.advanceUntilIdle()
            assertFalse(a.selfId in b.peers.value)
        }

    // ── Asymmetric partition ──────────────────────────────────────────────────

    @Test
    fun `asymmetric Outbound partition blocks sends but not receives`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // A cannot send to B, but B can still send to A
            a.partition(Direction.Outbound)

            // A→B blocked
            a.broadcast(byteArrayOf(1))
            assertEquals(1L, a.framesDropped)

            // B→A still works
            val receivedByA = async { a.incoming.first() }
            b.broadcast(byteArrayOf(99))
            val frame = receivedByA.await()
            assertTrue(frame.payload.contentEquals(byteArrayOf(99)))
        }

    @Test
    fun `asymmetric Inbound partition blocks receives but not sends`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // B drops all inbound — A→B frames disappear at B
            b.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            // Confirm A→B inbound is blocked
            a.broadcast(byteArrayOf(5))
            testScheduler.advanceUntilIdle()

            // B's channel must still be empty after the frame was sent and processed
            var bReceived = false
            val probe =
                backgroundScope.launch {
                    b.incoming.first()
                    bReceived = true
                }
            testScheduler.advanceUntilIdle()
            probe.cancel()
            assertFalse(bReceived, "B should not receive frames when Inbound is partitioned")

            // B can still send to A (B's outbound is healthy)
            val receivedByA = async { a.incoming.first() }
            b.broadcast(byteArrayOf(9))
            val frameA = receivedByA.await()
            assertTrue(frameA.payload.contentEquals(byteArrayOf(9)))
        }

    // ── Composite ────────────────────────────────────────────────────────────

    @Test
    fun `Composite DelayAll then DropAll drops everything`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            factory.join(InMemoryTag("Bob"))

            a.setFaultProfile(
                FaultProfile.Composite(
                    listOf(
                        FaultProfile.DelayAll(50.milliseconds),
                        FaultProfile.DropAll(),
                    ),
                ),
            )

            repeat(5) { a.broadcast(byteArrayOf(it.toByte())) }
            testScheduler.advanceUntilIdle()

            assertEquals(5L, a.framesDropped)
            assertEquals(0L, a.framesDelivered)
        }

    @Test
    fun `Composite delays accumulate`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            a.setFaultProfile(
                FaultProfile.Composite(
                    listOf(
                        FaultProfile.DelayAll(50.milliseconds),
                        FaultProfile.DelayAll(60.milliseconds),
                    ),
                ),
            )

            val received = async { b.incoming.first() }
            launch { a.broadcast(byteArrayOf(7)) }

            testScheduler.advanceTimeBy(109)
            assertFalse(received.isCompleted, "Not delivered before 110ms total delay")

            testScheduler.advanceTimeBy(2)
            testScheduler.runCurrent()

            val frame = received.await()
            assertTrue(frame.payload.contentEquals(byteArrayOf(7)))
        }

    // ── FaultyLoom — default profile propagation ─────────────────────────────

    @Test
    fun `factory defaultProfile applies to all created links`() =
        runTest {
            val factory =
                FaultyLoom(
                    InMemoryLoom(),
                    backgroundScope,
                    defaultProfile = FaultProfile.DropAll(),
                )
            val a = factory.open(Pattern("Alice"))
            factory.join(InMemoryTag("Bob"))

            repeat(3) { a.broadcast(byteArrayOf(it.toByte())) }

            assertEquals(3L, a.framesDropped)
        }

    @Test
    fun `setFaultProfileOnAll updates all links simultaneously`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            factory.setFaultProfileOnAll(FaultProfile.DropAll())

            repeat(3) { a.broadcast(byteArrayOf(it.toByte())) }
            assertEquals(3L, a.framesDropped)

            // B's outbound drops too
            repeat(2) { b.broadcast(byteArrayOf(it.toByte())) }
            assertEquals(2L, b.framesDropped)
        }

    @Test
    fun `factory links list contains all created links in order`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))
            val c = factory.join(InMemoryTag("Charlie"))

            assertAll(
                { assertEquals(3, factory.links.size) },
                { assertEquals(a.selfId, factory.links[0].selfId) },
                { assertEquals(b.selfId, factory.links[1].selfId) },
                { assertEquals(c.selfId, factory.links[2].selfId) },
            )
        }

    // ── Counter accuracy ──────────────────────────────────────────────────────

    @Test
    fun `counters sum correctly under healthy profile`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.open(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            val received = async { b.incoming.take(10).toList() }
            repeat(10) { a.broadcast(byteArrayOf(it.toByte())) }
            received.await()

            assertAll(
                { assertEquals(10L, a.framesDelivered) },
                { assertEquals(0L, a.framesDropped) },
                { assertEquals(0L, a.framesDelayed) },
            )
        }
}

// ── Test helpers ──────────────────────────────────────────────────────────────

private suspend fun droppedIndexesForSeed(
    seed: Long,
    scope: CoroutineScope,
): Set<Int> {
    val factory = FaultyLoom(InMemoryLoom(), scope)
    val a = factory.open(Pattern("Alice"))
    factory.join(InMemoryTag("Bob"))

    a.setFaultProfile(FaultProfile.DropProbabilistic(probability = 0.5, seed = seed))
    val dropped = mutableSetOf<Int>()
    repeat(100) { i ->
        val before = a.framesDropped
        a.broadcast(byteArrayOf(i.toByte()))
        if (a.framesDropped > before) dropped += i
    }
    return dropped
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
