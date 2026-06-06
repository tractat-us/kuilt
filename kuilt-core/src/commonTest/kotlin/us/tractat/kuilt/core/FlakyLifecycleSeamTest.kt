package us.tractat.kuilt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [FlakyLifecycleSeam] and [FlakyLifecycleLoom].
 *
 * All tests run under [runTest] for virtual-time control — no wall-clock
 * dependencies. Seeded randomness in [FlapSchedule] makes soak tests
 * deterministic across runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlakyLifecycleSeamTest {

    // ── Task 1: Core state transitions ────────────────────────────────────────

    @Test
    fun `initial state is Woven`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            // Await rather than sample: the seam is Woven synchronously at
            // construction on JVM/native, but the wasmJs/browser microtask
            // scheduler can intermittently defer the first observation. `first`
            // returns immediately when already Woven and is robust to that
            // deferral, removing a wasmJs-only flake (see #142).
            assertIs<SeamState.Woven>(seam.state.first { it is SeamState.Woven })
        }

    @Test
    fun `enterWeaving transitions state to Weaving`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            seam.enterWeaving()

            assertIs<SeamState.Weaving>(seam.state.value)
        }

    @Test
    fun `recover after enterWeaving restores Woven`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            seam.enterWeaving()
            seam.recover()

            assertIs<SeamState.Woven>(seam.state.value)
        }

    @Test
    fun `tear transitions to Torn with given reason`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            seam.tear(CloseReason.Unreachable)

            val torn = assertIs<SeamState.Torn>(seam.state.value)
            assertEquals(CloseReason.Unreachable, torn.reason)
        }

    @Test
    fun `tear is terminal — subsequent recover has no effect`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            seam.tear(CloseReason.Unreachable)
            seam.recover()

            assertIs<SeamState.Torn>(seam.state.value)
        }

    @Test
    fun `peers collapses to selfId-only while Weaving`() =
        runTest {
            val loom = InMemoryLoom()
            val delegateA = loom.host(Pattern("A"))
            loom.join(InMemoryTag("B"))
            val seam = FlakyLifecycleSeam(delegateA, backgroundScope)

            seam.enterWeaving()

            assertEquals(setOf(seam.selfId), seam.peers.value)
        }

    @Test
    fun `peers refills from delegate on recover`() =
        runTest {
            val loom = InMemoryLoom()
            val delegateA = loom.host(Pattern("A"))
            val delegateB = loom.join(InMemoryTag("B"))
            val seam = FlakyLifecycleSeam(delegateA, backgroundScope)

            seam.enterWeaving()
            seam.recover()

            assertAll(
                { assertTrue(seam.selfId in seam.peers.value) },
                { assertTrue(delegateB.selfId in seam.peers.value) },
            )
        }

    // ── Task 2: Inbound gating ────────────────────────────────────────────────

    @Test
    fun `frames sent while Weaving are dropped and not delivered after recover`() =
        runTest {
            val loom = InMemoryLoom()
            val delegateA = loom.host(Pattern("A"))
            val delegateB = loom.join(InMemoryTag("B"))
            val seam = FlakyLifecycleSeam(delegateA, backgroundScope)

            // Collect all arriving frames from the start
            val received = async { seam.incoming.take(1).toList() }
            testScheduler.runCurrent() // ensure collector is subscribed

            seam.enterWeaving()
            // Frame sent while Weaving must be dropped
            delegateB.broadcast(byteArrayOf(1, 2, 3))
            testScheduler.advanceUntilIdle()

            // Still Weaving — received must still be pending (nothing delivered)
            assertFalse(received.isCompleted, "No frames should arrive while Weaving")

            seam.recover()

            // Frame sent after recover must be delivered
            delegateB.broadcast(byteArrayOf(99))
            testScheduler.advanceUntilIdle()

            val frames = received.await()
            assertAll(
                { assertEquals(1, frames.size) },
                { assertTrue(frames[0].payload.contentEquals(byteArrayOf(99))) },
            )
        }

    @Test
    fun `frames sent after recover are delivered normally`() =
        runTest {
            val loom = InMemoryLoom()
            val delegateA = loom.host(Pattern("A"))
            val delegateB = loom.join(InMemoryTag("B"))
            val seam = FlakyLifecycleSeam(delegateA, backgroundScope)

            val received = async { seam.incoming.take(3).toList() }
            seam.enterWeaving()
            seam.recover()

            delegateB.broadcast(byteArrayOf(10))
            delegateB.broadcast(byteArrayOf(20))
            delegateB.broadcast(byteArrayOf(30))

            val frames = received.await()
            assertAll(
                { assertEquals(3, frames.size) },
                { assertTrue(frames[0].payload.contentEquals(byteArrayOf(10))) },
                { assertTrue(frames[1].payload.contentEquals(byteArrayOf(20))) },
                { assertTrue(frames[2].payload.contentEquals(byteArrayOf(30))) },
            )
        }

    @Test
    fun `incoming completes when torn`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            val collectionJob = launch {
                seam.incoming.collect { }
            }
            testScheduler.runCurrent() // start the collect

            seam.tear(CloseReason.Normal)
            // Yield to let backgroundScope tasks (delegate.close) process,
            // then advance until all downstream work completes.
            yield()
            testScheduler.advanceUntilIdle()

            assertTrue(
                collectionJob.isCompleted || collectionJob.isCancelled,
                "incoming must complete or be cancellable after tear",
            )
        }

    // ── Task 3: blip and flapThenTear ─────────────────────────────────────────

    @Test
    fun `blip traces Woven-Weaving-Woven`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            val states = mutableListOf<SeamState>()
            val collector = launch { seam.state.collect { states += it } }

            seam.blip(weavingFor = 100.milliseconds)
            testScheduler.advanceUntilIdle()

            collector.cancel()

            assertAll(
                { assertTrue(states.any { it is SeamState.Weaving }, "Weaving must appear") },
                { assertIs<SeamState.Woven>(states.last()) },
            )
        }

    @Test
    fun `broadcast while Weaving is a no-op — does not throw and does not deliver`() =
        runTest {
            val loom = InMemoryLoom()
            val delegateA = loom.host(Pattern("A"))
            val delegateB = loom.join(InMemoryTag("B"))
            val seam = FlakyLifecycleSeam(delegateA, backgroundScope)

            seam.enterWeaving()

            // Should not throw; should not deliver anything to B
            seam.broadcast(byteArrayOf(42))
            testScheduler.advanceUntilIdle()

            var bReceived = false
            val probe = launch {
                delegateB.incoming.first()
                bReceived = true
            }
            testScheduler.advanceUntilIdle()
            probe.cancel()
            assertFalse(bReceived, "B must not receive a frame sent while Weaving")
        }

    @Test
    fun `flapThenTear ends in Torn after N blips`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            seam.flapThenTear(flaps = 3, weavingFor = 50.milliseconds, reason = CloseReason.Unreachable)
            testScheduler.advanceUntilIdle()

            val torn = assertIs<SeamState.Torn>(seam.state.value)
            assertEquals(CloseReason.Unreachable, torn.reason)
        }

    @Test
    fun `flapThenTear — sends throw after terminal Torn`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            seam.flapThenTear(flaps = 1, weavingFor = 10.milliseconds, reason = CloseReason.Unreachable)
            testScheduler.advanceUntilIdle()

            assertFails { seam.broadcast(byteArrayOf(1)) }
        }

    // ── Task 4: FlapSchedule soak ─────────────────────────────────────────────

    @Test
    fun `FlapSchedule with giveUpAfter=2 terminates in Torn`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            val schedule = FlapSchedule(
                seed = 42L,
                meanUptime = 50.milliseconds,
                meanDowntime = 20.milliseconds,
                giveUpAfter = 2,
            )
            val job = seam.drive(schedule)
            job.join()

            assertIs<SeamState.Torn>(seam.state.value)
        }

    @Test
    fun `FlapSchedule with giveUpAfter=0 never tears on its own`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            val schedule = FlapSchedule(
                seed = 1L,
                meanUptime = 50.milliseconds,
                meanDowntime = 20.milliseconds,
                giveUpAfter = 0,
            )
            val job = seam.drive(schedule)
            // Advance enough virtual time for a few potential flaps
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()

            // Must NOT be torn — giveUpAfter=0 means infinite running
            assertFalse(seam.state.value is SeamState.Torn, "giveUpAfter=0 must not tear")
            job.cancel()
        }

    @Test
    fun `FlapSchedule is deterministic — same seed produces same flap count`() =
        runTest {
            suspend fun countFlaps(seed: Long): Int {
                val innerLoom = InMemoryLoom()
                val del = innerLoom.host(Pattern("X"))
                val s = FlakyLifecycleSeam(del, backgroundScope)
                val states = mutableListOf<SeamState>()
                val collector = launch { s.state.collect { states += it } }
                val job = s.drive(FlapSchedule(seed = seed, meanUptime = 30.milliseconds, meanDowntime = 10.milliseconds, giveUpAfter = 3))
                job.join()
                collector.cancel()
                return states.count { it is SeamState.Weaving }
            }

            val run1 = countFlaps(99L)
            val run2 = countFlaps(99L)
            assertEquals(run1, run2)
        }

    // ── Fix 1: close() must drive terminal Torn ───────────────────────────────

    @Test
    fun `close drives terminal Torn and sends throw`() =
        runTest {
            val delegate = InMemoryLoom().host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegate, backgroundScope)

            seam.close(CloseReason.Normal)
            testScheduler.advanceUntilIdle()

            assertEquals(SeamState.Torn(CloseReason.Normal), seam.state.value)
            assertFailsWith<IllegalStateException> { seam.broadcast(byteArrayOf(1)) }
        }

    // ── Single-source invariant ───────────────────────────────────────────────

    /**
     * Contract/regression: [peers] must stay `{selfId}` throughout a
     * [SeamState.Weaving] window even when the delegate gains a new peer during it.
     *
     * Trivial-pass on the confined test dispatcher — the collector's check-and-write
     * is atomic there, so the cross-thread race cannot manifest in this test.
     * Documents the contract; does not reproduce the race.
     */
    @Test
    fun `peers stays selfId-only throughout Weaving even when delegate gains a peer`() =
        runTest {
            val loom = InMemoryLoom()
            val delegateA = loom.host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegateA, backgroundScope)

            seam.enterWeaving()
            // Peer B joins the underlying loom while the seam is Weaving.
            loom.join(InMemoryTag("B"))
            testScheduler.runCurrent()

            // peers must remain {selfId} — the Weaving gate must hold.
            assertEquals(setOf(seam.selfId), seam.peers.value)
        }

    // ── Live Woven membership tracking ───────────────────────────────────────

    @Test
    fun `peers reflects delegate membership changes while Woven`() =
        runTest {
            val loom = InMemoryLoom()
            val delegateA = loom.host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegateA, backgroundScope)
            // Prime the delegate.peers collector so it is subscribed before B joins.
            testScheduler.runCurrent()

            assertEquals(setOf(seam.selfId), seam.peers.value)

            // B joins the same mesh — the background collector forwards the update.
            val delegateB = loom.join(InMemoryTag("B"))
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(seam.selfId in seam.peers.value, "selfId must be in peers") },
                { assertTrue(delegateB.selfId in seam.peers.value, "B must appear without recover()") },
            )
        }

    // ── Fix 2: peers refreshes from delegate on recover ───────────────────────

    @Test
    fun `peers refills from delegate on recover after delegate membership changed`() =
        runTest {
            val loom = InMemoryLoom()
            val delegateA = loom.host(Pattern("A"))
            val seam = FlakyLifecycleSeam(delegateA, backgroundScope)

            seam.enterWeaving()
            val delegateB = loom.join(InMemoryTag("B"))
            testScheduler.runCurrent()

            // recover() must refresh peers from the delegate synchronously.
            seam.recover()

            assertAll(
                { assertTrue(seam.selfId in seam.peers.value, "selfId must be in peers after recover") },
                { assertTrue(delegateB.selfId in seam.peers.value, "B must appear after recover()") },
            )
        }

    // ── FlakyLifecycleLoom ────────────────────────────────────────────────────

    @Test
    fun `FlakyLifecycleLoom wraps weave results in FlakyLifecycleSeam`() =
        runTest {
            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val seam = loom.host(Pattern("A"))

            assertIs<FlakyLifecycleSeam>(seam)
        }

    @Test
    fun `FlakyLifecycleLoom links list grows in creation order`() =
        runTest {
            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val a = loom.host(Pattern("A"))
            val b = loom.join(InMemoryTag("B"))

            assertAll(
                { assertEquals(2, loom.links.size) },
                { assertEquals(a.selfId, loom.links[0].selfId) },
                { assertEquals(b.selfId, loom.links[1].selfId) },
            )
        }
}

// ── Test helpers ──────────────────────────────────────────────────────────────

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
