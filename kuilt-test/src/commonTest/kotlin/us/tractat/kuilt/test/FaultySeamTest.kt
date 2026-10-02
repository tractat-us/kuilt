package us.tractat.kuilt.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    // ── A self-send is refused under EVERY fault profile (#2428) ──────────────

    /**
     * `Seam.sendTo` refuses `sendTo(selfId)` with `IllegalArgumentException`, and this decorator has
     * to check that **before** it evaluates the fault profile, not after.
     *
     * The `DropAll` arm is the one that matters and it is not the obvious case: a dropping profile
     * never calls the delegate at all, so a decorator that left the check to the wrapped seam would
     * make the refusal a function of the injected fault — refused on a healthy link, silently
     * swallowed on a lossy one. A simulated link is allowed to lose frames; it is not allowed to
     * launder a caller's programming error into one. The `Healthy` arm is the control: it proves the
     * `DropAll` arm's red would come from the profile, not from the seam being broken outright.
     */
    @Test
    fun `sendTo self is refused under both a healthy and a dropping profile`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            factory.join(InMemoryTag("Bob"))

            assertFailsWith<IllegalArgumentException>("healthy profile: a self-send is refused") {
                a.sendTo(a.selfId, byteArrayOf(1))
            }

            a.setFaultProfile(FaultProfile.DropAll())
            val droppedBefore = a.framesDropped
            assertFailsWith<IllegalArgumentException>(
                "DropAll must NOT swallow the refusal — a lossy link may lose frames, but a self-send " +
                    "is a caller error the contract owes an exception for on every profile",
            ) {
                a.sendTo(a.selfId, byteArrayOf(2))
            }
            assertEquals(
                droppedBefore,
                a.framesDropped,
                "the refusal must land BEFORE the fault evaluation — a refused self-send is not a " +
                    "dropped frame, and counting it as one would misreport the simulated link",
            )
        }

    // ── Healthy profile ───────────────────────────────────────────────────────

    @Test
    fun `Healthy profile delivers all frames in order`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            val received = async { b.incoming.take(3).toList() }
            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))
            a.broadcast(byteArrayOf(3))

            val frames = received.await()
            assertAll(
                { assertEquals(3, frames.size) },
                { assertTrue(frames[0].toByteArray().contentEquals(byteArrayOf(1))) },
                { assertTrue(frames[1].toByteArray().contentEquals(byteArrayOf(2))) },
                { assertTrue(frames[2].toByteArray().contentEquals(byteArrayOf(3))) },
            )
        }

    @Test
    fun `Healthy profile has zero drops`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
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
            val a = factory.host(Pattern("Alice"))
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
            val a = factory.host(Pattern("Alice"))
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
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // B drops outbound only — A→B inbound path for B is unaffected
            b.setFaultProfile(FaultProfile.DropAll(Direction.Outbound))

            val received = async { b.incoming.first() }
            a.broadcast(byteArrayOf(42))

            val frame = received.await()
            assertTrue(frame.toByteArray().contentEquals(byteArrayOf(42)))
        }

    // ── Partition / heal ──────────────────────────────────────────────────────

    @Test
    fun `partition drops frames and heal restores delivery`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            a.partition()
            repeat(3) { a.broadcast(byteArrayOf(it.toByte())) }
            assertEquals(3L, a.framesDropped)

            a.heal()
            val received = async { b.incoming.first() }
            a.broadcast(byteArrayOf(99))
            val frame = received.await()
            assertTrue(frame.toByteArray().contentEquals(byteArrayOf(99)))
        }

    // ── DropProbabilistic ─────────────────────────────────────────────────────

    @Test
    fun `DropProbabilistic with probability 0 never drops`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
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
            val a = factory.host(Pattern("Alice"))
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
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // Drop frames 0 and 2; deliver 1, 3, 4
            a.setFaultProfile(FaultProfile.DropSpecific(setOf(0, 2)))
            val received = async { b.incoming.take(3).toList() }
            repeat(5) { a.broadcast(byteArrayOf(it.toByte())) }

            val frames = received.await()
            assertAll(
                { assertEquals(3, frames.size) },
                { assertTrue(frames[0].toByteArray().contentEquals(byteArrayOf(1))) },
                { assertTrue(frames[1].toByteArray().contentEquals(byteArrayOf(3))) },
                { assertTrue(frames[2].toByteArray().contentEquals(byteArrayOf(4))) },
            )
        }

    @Test
    fun `DropSpecific Inbound drops listed incoming frames by index`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // B drops inbound frames at index 1 and 3
            b.setFaultProfile(FaultProfile.DropSpecific(setOf(1, 3), Direction.Inbound))

            val received = async { b.incoming.take(3).toList() }
            repeat(5) { a.broadcast(byteArrayOf(it.toByte())) }
            testScheduler.advanceUntilIdle()

            val frames = received.await()
            assertAll(
                { assertEquals(3, frames.size) },
                { assertTrue(frames[0].toByteArray().contentEquals(byteArrayOf(0))) },
                { assertTrue(frames[1].toByteArray().contentEquals(byteArrayOf(2))) },
                { assertTrue(frames[2].toByteArray().contentEquals(byteArrayOf(4))) },
            )
        }

    // ── DelayAll ──────────────────────────────────────────────────────────────

    @Test
    fun `DelayAll respects virtual time — frame not delivered before delay elapses`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
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
            assertTrue(frame.toByteArray().contentEquals(byteArrayOf(7)))
            assertEquals(1L, a.framesDelayed)
        }

    @Test
    fun `DelayAll Inbound delays incoming frames`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            b.setFaultProfile(FaultProfile.DelayAll(100.milliseconds, Direction.Inbound))

            val received = async { b.incoming.first() }
            a.broadcast(byteArrayOf(7))
            testScheduler.runCurrent()

            assertFalse(received.isCompleted, "Inbound frame should be delayed")

            testScheduler.advanceTimeBy(101)
            testScheduler.runCurrent()

            val frame = received.await()
            assertTrue(frame.toByteArray().contentEquals(byteArrayOf(7)))
        }

    // ── ReorderWindow ─────────────────────────────────────────────────────────

    @Test
    fun `ReorderWindow is deterministic — same seed produces same permutation`() =
        runTest {
            suspend fun collectPayloads(scope: CoroutineScope): List<Int> {
                val innerFactory = FaultyLoom(InMemoryLoom(), scope)
                val sender = innerFactory.host(Pattern("Alice"))
                val receiver = innerFactory.join(InMemoryTag("Bob"))
                sender.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 4, seed = 42L))
                val received = async { receiver.incoming.take(4).toList() }
                repeat(4) { sender.broadcast(byteArrayOf(it.toByte())) }
                return received.await().map { it.byteAt(0).toInt() }
            }

            val run1 = collectPayloads(backgroundScope)
            val run2 = collectPayloads(backgroundScope)
            assertEquals(run1, run2)
        }

    @Test
    fun `ReorderWindow flushes exactly windowSize frames at once`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
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

    /**
     * A held frame keeps its own destination (#2879). The hub interleaves `sendTo(alex)` and
     * `sendTo(sam)` through one six-frame window, so the call that fills the window is a
     * `sendTo(sam)`. Flushing every held frame through *that* call's route would hand sam all six and
     * alex none.
     *
     * **Reordering is proved, not assumed.** Seed 0 permutes a six-frame window to
     * `[2, 1, 5, 4, 3, 0]`, which puts both peers' frames out of send order, and the test asserts
     * the exact order each peer saw. A window that forwarded in send order would fail the order
     * assertions while passing the routing ones. [FaultySeam.framesDelayed] counts the five frames
     * held before the sixth filled the window.
     */
    @Test
    fun `ReorderWindow flushes each held sendTo frame to its own peer — reordered`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val hub = factory.host(Pattern("Hub"))
            val alex = factory.join(InMemoryTag("Hub"))
            val sam = factory.join(InMemoryTag("Hub"))
            val atAlex = collectBytes(alex)
            val atSam = collectBytes(sam)
            testScheduler.runCurrent()

            hub.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 6, seed = 0L, direction = Direction.Outbound))
            // Send order: A0 S0 A1 S1 A2 S2. The sixth call, sendTo(sam), fills the window.
            hub.sendTo(alex.selfId, byteArrayOf(10))
            hub.sendTo(sam.selfId, byteArrayOf(20))
            hub.sendTo(alex.selfId, byteArrayOf(11))
            hub.sendTo(sam.selfId, byteArrayOf(21))
            hub.sendTo(alex.selfId, byteArrayOf(12))
            hub.sendTo(sam.selfId, byteArrayOf(22))
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(listOf(11, 12, 10), atAlex, "alex gets only its own frames, permuted") },
                { assertEquals(listOf(20, 22, 21), atSam, "sam gets only its own frames, permuted") },
                { assertEquals(5L, hub.framesDelayed, "five frames sat in the window before the sixth filled it") },
                { assertEquals(6L, hub.framesDelivered) },
                { assertEquals(0L, hub.framesDropped) },
            )
        }

    /**
     * A held `broadcast` frame is flushed as a broadcast, and a held `sendTo` frame to its one peer,
     * even when the call that fills the window is a `sendTo` (#2879).
     *
     * Send order: B0 (broadcast), A0 (alex), B1 (broadcast), S0 (sam). Seed 0 permutes a four-frame
     * window to `[3, 1, 0, 2]`, so alex sees A0 B0 B1 and sam sees S0 B0 B1 — both out of send order.
     */
    @Test
    fun `ReorderWindow flushes a held broadcast to every peer and a held sendTo to one`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val hub = factory.host(Pattern("Hub"))
            val alex = factory.join(InMemoryTag("Hub"))
            val sam = factory.join(InMemoryTag("Hub"))
            val atAlex = collectBytes(alex)
            val atSam = collectBytes(sam)
            testScheduler.runCurrent()

            hub.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 4, seed = 0L, direction = Direction.Outbound))
            hub.broadcast(byteArrayOf(1))
            hub.sendTo(alex.selfId, byteArrayOf(10))
            hub.broadcast(byteArrayOf(2))
            hub.sendTo(sam.selfId, byteArrayOf(20))
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(listOf(10, 1, 2), atAlex, "alex gets both broadcasts and its own frame, permuted") },
                { assertEquals(listOf(20, 1, 2), atSam, "sam gets both broadcasts and its own frame, permuted") },
                { assertEquals(3L, hub.framesDelayed, "three frames sat in the window before the fourth filled it") },
                { assertEquals(4L, hub.framesDelivered) },
            )
        }

    /**
     * A held `sendTo` frame whose peer left before the flush is dropped, and the flush carries on.
     *
     * Routing each held frame to its own peer (#2879) means the flush can address a peer that is
     * gone, and `sendTo` throws [us.tractat.kuilt.core.PeerNotConnected] for that. The throw must not
     * escape the unrelated call that filled the window. Here that call is a `broadcast`, which the
     * `Seam` contract never lets throw for a missing peer. It must also not cut the burst short, so
     * sam still gets both broadcasts, and the lost frame is counted as dropped.
     *
     * Seed 9 permutes a three-frame window to `[0, 2, 1]`, so alex's frame is flushed **first**: a
     * flush that stopped at the throw would deliver nothing to sam.
     */
    @Test
    fun `ReorderWindow drops a held sendTo whose peer left — and still flushes the rest`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val hub = factory.host(Pattern("Hub"))
            val alex = factory.join(InMemoryTag("Hub"))
            val sam = factory.join(InMemoryTag("Hub"))
            val atSam = collectBytes(sam)
            testScheduler.runCurrent()

            hub.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 3, seed = 9L, direction = Direction.Outbound))
            hub.sendTo(alex.selfId, byteArrayOf(10))
            alex.close()
            testScheduler.runCurrent()
            val alexGone = alex.selfId !in hub.peers.value
            hub.broadcast(byteArrayOf(1))
            val escaped =
                try {
                    hub.broadcast(byteArrayOf(2))
                    null
                } catch (e: us.tractat.kuilt.core.PeerNotConnected) {
                    e
                }
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(alexGone, "precondition: alex has left the hub's roster before the flush") },
                { assertEquals(null, escaped, "the broadcast that filled the window must not throw") },
                { assertEquals(listOf(2, 1), atSam, "sam gets every frame that still has a peer, permuted") },
                { assertEquals(1L, hub.framesDropped, "the frame for the departed alex is dropped") },
                { assertEquals(2L, hub.framesDelayed) },
                { assertEquals(2L, hub.framesDelivered) },
            )
        }

    /**
     * An inbound frame held in the reorder window is delayed, not dropped (#2879). Before the fix the
     * held frames were counted in [FaultySeam.framesDropped] even though every one was delivered.
     */
    @Test
    fun `ReorderWindow Inbound counts held frames as delayed — not dropped`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Alice"))
            val atB = collectBytes(b)
            testScheduler.runCurrent()

            b.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 3, seed = 0L, direction = Direction.Inbound))
            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))
            a.broadcast(byteArrayOf(3))
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(setOf(1, 2, 3), atB.toSet(), "every held frame is delivered once the window fills") },
                { assertEquals(3, atB.size) },
                { assertEquals(0L, b.framesDropped, "a held frame is not a dropped frame") },
                { assertEquals(2L, b.framesDelayed, "two frames sat in the window before the third filled it") },
                { assertEquals(3L, b.framesDelivered) },
            )
        }

    /**
     * A window that never fills is released by the next profile change, each frame to its own route
     * (#2882). Before the fix, `heal()` mid-burst lost both held frames for good and counted neither
     * as dropped.
     *
     * The precondition proves the rig fired: both frames sit in the window and nobody has them yet.
     */
    @Test
    fun `ReorderWindow Outbound — heal releases a partial window to each frame's own route`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val hub = factory.host(Pattern("Hub"))
            val alex = factory.join(InMemoryTag("Hub"))
            val sam = factory.join(InMemoryTag("Hub"))
            val atAlex = collectBytes(alex)
            val atSam = collectBytes(sam)
            testScheduler.runCurrent()

            hub.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 3, seed = 0L, direction = Direction.Outbound))
            hub.sendTo(alex.selfId, byteArrayOf(10))
            hub.broadcast(byteArrayOf(1))
            testScheduler.runCurrent()
            val heldBeforeHeal = atAlex.isEmpty() && atSam.isEmpty() && hub.framesDelayed == 2L

            hub.heal()
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(heldBeforeHeal, "precondition: both frames were held in the window, undelivered") },
                { assertEquals(listOf(10, 1), atAlex, "alex gets its own frame and the broadcast") },
                { assertEquals(listOf(1), atSam, "sam gets only the broadcast") },
                { assertEquals(2L, hub.framesDelivered) },
                { assertEquals(0L, hub.framesDropped) },
            )
        }

    /** The inbound half of the same release (#2882): a held received frame reaches `incoming`. */
    @Test
    fun `ReorderWindow Inbound — heal releases a partial window to incoming`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Alice"))
            val atB = collectBytes(b)
            testScheduler.runCurrent()

            b.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 3, seed = 0L, direction = Direction.Inbound))
            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))
            testScheduler.runCurrent()
            val heldBeforeHeal = atB.isEmpty() && b.framesDelayed == 2L

            b.heal()
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(heldBeforeHeal, "precondition: both frames were held in the window, undelivered") },
                { assertEquals(listOf(1, 2), atB, "both held frames are delivered") },
                { assertEquals(2L, b.framesDelivered) },
                { assertEquals(0L, b.framesDropped) },
            )
        }

    /**
     * Closing a seam with frames still in its windows loses them, and says so (#2882): each one
     * counts in [FaultySeam.framesDropped]. Both directions, on one seam.
     */
    @Test
    fun `ReorderWindow — close counts frames still held in either window as dropped`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Alice"))
            val atA = collectBytes(a)
            val atB = collectBytes(b)
            testScheduler.runCurrent()

            b.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 3, seed = 0L, direction = Direction.Both))
            b.broadcast(byteArrayOf(5))
            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))
            testScheduler.runCurrent()
            val heldBeforeClose = atA.isEmpty() && atB.isEmpty() && b.framesDelayed == 3L && b.framesDropped == 0L

            b.close()
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(heldBeforeClose, "precondition: one outbound and two inbound frames were held") },
                { assertEquals(3L, b.framesDropped, "every frame still held at close is a dropped frame") },
                { assertEquals(0L, b.framesDelivered) },
                { assertEquals(emptyList(), atA, "the held outbound frame never reached alice") },
                { assertEquals(emptyList(), atB) },
            )
        }

    /**
     * A release that lands after the link has closed is a drop, not a throw (#2882). `heal()` cannot
     * suspend, so it hands the held frames to a coroutine; here `close()` runs before that coroutine
     * does. The frame must be counted as dropped, and the closed delegate's refusal must not escape
     * into the scope.
     */
    @Test
    fun `ReorderWindow — a release overtaken by close counts the frames as dropped`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Alice"))
            val atB = collectBytes(b)
            testScheduler.runCurrent()

            a.setFaultProfile(FaultProfile.ReorderWindow(windowSize = 3, seed = 0L, direction = Direction.Outbound))
            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))
            val heldBeforeHeal = a.framesDelayed == 2L

            a.heal()
            a.close()
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(heldBeforeHeal, "precondition: both frames were held in the window") },
                { assertEquals(2L, a.framesDropped, "frames released onto a closed link are dropped") },
                { assertEquals(0L, a.framesDelivered) },
                { assertEquals(emptyList(), atB) },
            )
        }

    private fun kotlinx.coroutines.test.TestScope.collectBytes(seam: FaultySeam): List<Int> {
        val seen = mutableListOf<Int>()
        backgroundScope.launch { seam.incoming.collect { seen += it.byteAt(0).toInt() } }
        return seen
    }

    // ── BufferCeiling ─────────────────────────────────────────────────────────

    @Test
    fun `BufferCeiling drops frames beyond the send quota`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
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
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // Close after frame 0 is sent (i.e. frame index 1 triggers close)
            a.setFaultProfile(FaultProfile.CloseAt(frameIndex = 1))

            val received = async { b.incoming.take(1).toList() }
            a.broadcast(byteArrayOf(10)) // index 0 — delivered
            a.broadcast(byteArrayOf(20)) // index 1 — triggers close instead of send

            val frames = received.await()
            assertAll(
                { assertEquals(1, frames.size) },
                { assertTrue(frames[0].toByteArray().contentEquals(byteArrayOf(10))) },
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
            val a = factory.host(Pattern("Alice"))
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
            assertTrue(frame.toByteArray().contentEquals(byteArrayOf(99)))
        }

    @Test
    fun `asymmetric Inbound partition blocks receives but not sends`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
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
            assertTrue(frameA.toByteArray().contentEquals(byteArrayOf(9)))
        }

    // ── Composite ────────────────────────────────────────────────────────────

    @Test
    fun `Composite DelayAll then DropAll drops everything`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
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
            val a = factory.host(Pattern("Alice"))
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
            assertTrue(frame.toByteArray().contentEquals(byteArrayOf(7)))
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
            val a = factory.host(Pattern("Alice"))
            factory.join(InMemoryTag("Bob"))

            repeat(3) { a.broadcast(byteArrayOf(it.toByte())) }

            assertEquals(3L, a.framesDropped)
        }

    @Test
    fun `setFaultProfileOnAll updates all links simultaneously`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
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
            val a = factory.host(Pattern("Alice"))
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
            val a = factory.host(Pattern("Alice"))
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

    // ── Inbound boundedness (Spool invariant, #701) ─────────────────────────────

    @Test
    fun `inbound buffer is bounded — flooding past capacity backpressures instead of buffering unbounded`() =
        runTest {
            val factory = FaultyLoom(InMemoryLoom(), backgroundScope)
            val a = factory.host(Pattern("Alice"))
            val b = factory.join(InMemoryTag("Bob"))

            // InMemoryLoom rendezvous is synchronous — both sides see the 2-peer mesh
            // immediately — so a broadcast can never fire into an empty peer set.
            assertEquals(2, a.peers.value.size)
            assertEquals(2, b.peers.value.size)

            // Flood past capacity with NO collector on b.incoming. A bounded Spool backpressures
            // the inbound pump at capacity; the old Channel.UNLIMITED delivered every frame.
            val flood = DeliveryPolicy.DEFAULT_CAPACITY + 50
            repeat(flood) { a.broadcast(byteArrayOf(it.toByte())) }
            testScheduler.advanceUntilIdle()

            assertTrue(
                b.framesDelivered <= DeliveryPolicy.DEFAULT_CAPACITY.toLong(),
                "inbound must be bounded at ${DeliveryPolicy.DEFAULT_CAPACITY}; was ${b.framesDelivered}",
            )
        }

    // ── capability() forwards the delegate's verdict (#1936) ──────────────────

    /**
     * A [FaultProfile] describes **link behaviour** — delay, drop, partition — on a link the
     * delegate's fabric already carries. [us.tractat.kuilt.core.FabricAvailability] is a different
     * question: whether that fabric is usable *on this runtime at all*, pre-connect. [FaultyLoom]
     * has no answer to it of its own (it weaves whatever the delegate weaves, faults or not), so the
     * delegate's verdict is the only established one and must survive the wrap.
     */
    @Test
    fun `FaultyLoom capability forwards the delegate's established verdict`() =
        runTest {
            val declared = TransportCapability(
                roles = setOf(TransportRole.Discovery, TransportRole.WifiLan),
                availability = FabricAvailability.Unavailable("delegate fabric unusable on this runtime"),
            )
            val delegate = FixedCapabilityLoom(declared)
            val loom = FaultyLoom(delegate, backgroundScope)

            assertAll(
                { assertEquals(declared, loom.capability(), "FaultyLoom must forward its delegate's capability()") },
                {
                    assertEquals(
                        declared.availability,
                        loom.availability(),
                        "availability() derives from the forwarded capability()",
                    )
                },
                { assertEquals(0, delegate.weaveCount, "capability() is a pre-connect surface — reading it must not weave") },
            )
        }

    /**
     * An injected fault must not be laundered into a fabric-availability verdict either: the
     * canonical partition profile ([FaultProfile.DropAll]) still reports whatever the delegate does.
     */
    @Test
    fun `FaultyLoom capability is unaffected by the injected fault profile`() =
        runTest {
            val declared = TransportCapability(
                roles = setOf(TransportRole.Data),
                availability = FabricAvailability.Available,
            )
            val loom = FaultyLoom(FixedCapabilityLoom(declared), backgroundScope, FaultProfile.DropAll())

            assertEquals(declared, loom.capability(), "a FaultProfile describes link behaviour, not fabric availability")
        }
}

// ── Test helpers ──────────────────────────────────────────────────────────────

private suspend fun droppedIndexesForSeed(
    seed: Long,
    scope: CoroutineScope,
): Set<Int> {
    val factory = FaultyLoom(InMemoryLoom(), scope)
    val a = factory.host(Pattern("Alice"))
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

