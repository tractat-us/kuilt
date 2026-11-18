package us.tractat.kuilt.heddle

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the pool's count is guarded by a lock, and only real threads (JVM, Native) put a complete, a hand-back and a dispatch in the same window; on wasmJs this only witnesses that the body runs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The pool's one lock, on real threads (#3558). Every other pool test runs under `runTest`, where
 * everything is serial, so none of them can show that a [FairSharePool.complete], a cancelled
 * waiter's hand-back and a hand-out racing on different threads still never open more than the
 * pool's slots, or leak one. These two run on `Dispatchers.Default`.
 *
 * What each target shows: on the JVM and Kotlin/Native, `Dispatchers.Default` is a thread pool, and
 * the lock claim rests on those two. On wasmJs it is the browser's one event loop, and the pool's
 * critical sections never suspend, so nothing can interleave inside one: there the probe witnesses
 * only that its body runs, and [cancellationOnRealThreadsLeaksNoSlot] records its race counters
 * rather than asserting them ([runsOnRealThreads]).
 *
 * A hang here gets none of `:kuilt-test`'s JVM-only coroutine dump, so every failure message carries
 * the pool's counters instead.
 *
 * Gated: it runs only under `-Pconcurrency.stress.tests=true` (see this module's build).
 */
class FairSharePoolConcurrencyTest {

    private fun FairSharePool.state(): String =
        "open=$openForTest waiters=$waitersForTest removedWhileQueued=${removedWhileQueuedForTest.value} " +
            "handedBack=${handedBackForTest.value}"

    /** A pool on the real monotonic clock: these tests run on real threads, where no virtual one advances. */
    private fun realPool(engine: LocalHeddle): FairSharePool = FairSharePool(engine, SLOTS, TimeSource.Monotonic, 1.seconds)

    @Test
    fun neverMoreThanSlotsOpenOnRealThreads() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        withContext(Dispatchers.Default) {
            val engine = farm()
            val pool = realPool(engine)
            val inFlight = atomic(0)
            val maxSeen = atomic(0)
            val iterations = atomic(0)
            coroutineScope {
                repeat(WORKERS) { worker ->
                    launch {
                        repeat(ROUNDS) { round ->
                            val lease = pool.acquire(if ((worker + round) % 2 == 0) web else mobile).granted()
                            val now = inFlight.incrementAndGet()
                            maxSeen.update { maxOf(it, now) }
                            // Held for a millisecond, well past a hand-off between threads. Held for
                            // one yield, a slot was mostly in hand-off and the rig read maxSeen=2.
                            delay(1.milliseconds)
                            inFlight.decrementAndGet()
                            iterations.incrementAndGet()
                            pool.complete(lease.id, 1L)
                        }
                    }
                }
            }
            // Read only after every worker joined: the engine is the pool's, and quiescent now.
            val leases = engine.snapshot().leases
            val seen = "maxSeen=${maxSeen.value} iterations=${iterations.value} ${pool.state()}"
            assertAll(
                { assertEquals(WORKERS * ROUNDS, iterations.value, "every round ran: $seen") },
                { assertTrue(maxSeen.value <= SLOTS, "never more than $SLOTS slots held at once: $seen") },
                // The rig, apart from the property, so the property does not lean on scheduling.
                { assertTrue(maxSeen.value >= SLOTS, "the rig: every slot was held at once at least once: $seen") },
                { assertEquals(0, pool.openForTest, "the pool counts no open lease: $seen") },
                { assertEquals(0, pool.waitersForTest, "no waiter is queued: $seen") },
                { assertEquals(emptyList(), leases, "the engine holds no open lease: $seen") },
            )
        }
    }

    @Test
    fun cancellationOnRealThreadsLeaksNoSlot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        withContext(Dispatchers.Default) {
            val engine = farm()
            val pool = realPool(engine)
            var ran = 0
            var cancelled = 0
            var rounds = 0
            // On wasmJs the hand-back window may never open, so a floor there would only spend the cap.
            val roundCap = if (runsOnRealThreads) ROUND_CAP else WASM_ROUNDS
            fun bothRacesFired() = pool.removedWhileQueuedForTest.value >= 1 && pool.handedBackForTest.value >= 1
            do {
                rounds++
                val finished = BooleanArray(JOBS)
                val granted = atomic(0)
                coroutineScope {
                    val jobs = List(JOBS) { i ->
                        launch {
                            val lease = pool.acquire(if (i % 2 == 0) web else mobile).granted()
                            granted.incrementAndGet()
                            try {
                                delay(1.milliseconds)
                            } finally {
                                pool.complete(lease.id, 1L)
                            }
                            finished[i] = true
                        }
                    }
                    // Each third job is cancelled as the grant frontier reaches it, so the cancels land
                    // on queued waiters and on fresh grants alike, all through the round. Swept from
                    // the start with a bare yield between them, nearly all landed on queued waiters:
                    // a hand-back took 36 rounds of 2,000 on macosArm64, 24 s of the 30 s backstop.
                    launch {
                        var issued = 0
                        for (i in jobs.indices step 3) {
                            while (granted.value + issued < i) yield()
                            jobs[i].cancel()
                            issued++
                        }
                    }.join()
                    jobs.joinAll()
                    // Joined, so every write to `finished` is visible. A cancel landing after a body's
                    // last suspension still marks its job cancelled; that one counts as ran.
                    ran += finished.count { it }
                    cancelled += jobs.indices.count { jobs[it].isCancelled && !finished[it] }
                }
            } while (rounds < roundCap && !bothRacesFired())
            val removed = pool.removedWhileQueuedForTest.value
            val handedBack = pool.handedBackForTest.value
            val open = pool.openForTest
            val waiters = pool.waitersForTest
            val leases = engine.snapshot().leases
            val seen = "rounds=$rounds ran=$ran cancelled=$cancelled ${pool.state()}"
            println("FairSharePoolConcurrencyTest.cancellationOnRealThreadsLeaksNoSlot: realThreads=$runsOnRealThreads $seen")
            // Last: it opens a lease of its own.
            val fresh = pool.acquire(web)
            assertAll(
                { assertEquals(JOBS * rounds, ran + cancelled, "every job ran or was cancelled: $seen") },
                { if (runsOnRealThreads) assertTrue(removed >= 1, "the rig: a queued waiter was cancelled: $seen") },
                { if (runsOnRealThreads) assertTrue(handedBack >= 1, "the rig: a granted waiter handed back: $seen") },
                { assertEquals(0, open, "the pool counts no open lease: $seen") },
                { assertEquals(0, waiters, "no waiter is queued: $seen") },
                { assertEquals(emptyList(), leases, "the engine holds no open lease: $seen") },
                { assertEquals(web, fresh.granted().leaf, "a fresh waiter is granted: $seen") },
            )
        }
    }

    private companion object {
        const val SLOTS = 3
        const val WORKERS = 32
        const val ROUNDS = 200
        const val JOBS = 2_000
        const val ROUND_CAP = 50
        const val WASM_ROUNDS = 1
    }
}
