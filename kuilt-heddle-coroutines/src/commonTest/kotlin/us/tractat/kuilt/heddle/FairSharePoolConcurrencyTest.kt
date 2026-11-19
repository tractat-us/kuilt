package us.tractat.kuilt.heddle

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the pool's count is guarded by a lock, and only real threads (JVM, Native) put a complete, a hand-back and a dispatch in the same window; on wasmJs this only witnesses that the body runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
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
 * only that its body runs, and [cancellationOnRealThreadsLeaksNoSlot] records its hand-back counter
 * rather than asserting it ([runsOnRealThreads]). A cancel of a queued waiter needs no interleaving,
 * so that counter is asserted on every target.
 *
 * A hang here gets none of `:kuilt-test`'s JVM-only coroutine dump, so every failure message carries
 * the pool's counters instead.
 *
 * Gated: it runs only under `-Pconcurrency.stress.tests=true` (see this module's build).
 */
class FairSharePoolConcurrencyTest {

    private fun FairSharePool.state(): String =
        "open=${view().open} waiters=$waitersForTest removedWhileQueued=${removedWhileQueuedForTest.value} " +
            "handedBack=${handedBackForTest.value}"

    /**
     * Run [block], and fail with [state] if it has not finished well inside the backstop. A wedged
     * pool would otherwise fail as a bare `UncompletedCoroutinesError`, with none of its counters.
     */
    private suspend fun reportingAWedge(state: () -> String, block: suspend CoroutineScope.() -> Unit) {
        withTimeoutOrNull(WEDGE_REPORT) { coroutineScope(block) } ?: fail("wedged after $WEDGE_REPORT: ${state()}")
    }

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
            reportingAWedge({ "maxSeen=${maxSeen.value} iterations=${iterations.value} ${pool.state()}" }) {
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
                { assertEquals(0, pool.view().open, "the pool counts no open lease: $seen") },
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
            var issuedTotal = 0
            // A missed floor must reach its named assertion, not the wedge report: rounds run on a
            // real-time budget well inside it, as well as under the cap.
            val budget = TimeSource.Monotonic.markNow() + RACE_BUDGET
            // On wasmJs the hand-back window may never open, so a floor there would only spend the cap.
            val roundCap = if (runsOnRealThreads) ROUND_CAP else WASM_ROUNDS
            fun bothRacesFired() = pool.removedWhileQueuedForTest.value >= 1 && pool.handedBackForTest.value >= 1
            reportingAWedge({ "rounds=$rounds ran=$ran cancelled=$cancelled ${pool.state()}" }) {
                do {
                    rounds++
                    val finished = BooleanArray(JOBS)
                    // Per lane, since web is granted three times as often as mobile: index `i` is the
                    // `i / 2`-th job of lane `i % 2`.
                    val granted = List(2) { atomic(0) }
                    coroutineScope {
                        val jobs = List(JOBS) { i ->
                            launch {
                                val lease = pool.acquire(if (i % 2 == 0) web else mobile).granted()
                                granted[i % 2].incrementAndGet()
                                try {
                                    delay(1.milliseconds)
                                } finally {
                                    pool.complete(lease.id, 1L)
                                }
                                finished[i] = true
                            }
                        }
                        // Each third job is cancelled as its lane's grant frontier reaches it, so the
                        // cancels land on queued waiters and on fresh grants alike, all through the round.
                        // Swept from the start with a bare yield between them, nearly all landed on queued
                        // waiters: a hand-back took 36 rounds on macosArm64, 24 s of the 30 s backstop.
                        val cancellers = List(2) { lane ->
                            async {
                                var issued = 0
                                for (i in jobs.indices.filter { it % 2 == lane && it % 3 == 0 }) {
                                    while (granted[lane].value + issued < i / 2) yield()
                                    jobs[i].cancel()
                                    issued++
                                }
                                issued
                            }
                        }
                        issuedTotal += cancellers.awaitAll().sum()
                        jobs.joinAll()
                        // Joined, so every write to `finished` is visible. A cancel landing after a body's
                        // last suspension still marks its job cancelled; that one counts as ran.
                        ran += finished.count { it }
                        cancelled += jobs.indices.count { jobs[it].isCancelled && !finished[it] }
                    }
                } while (rounds < roundCap && budget.hasNotPassedNow() && !bothRacesFired())
            }
            val removed = pool.removedWhileQueuedForTest.value
            val handedBack = pool.handedBackForTest.value
            val open = pool.view().open
            val waiters = pool.waitersForTest
            val leases = engine.snapshot().leases
            val seen = "rounds=$rounds ran=$ran cancelled=$cancelled issued=$issuedTotal ${pool.state()}"
            println("FairSharePoolConcurrencyTest.cancellationOnRealThreadsLeaksNoSlot: realThreads=$runsOnRealThreads $seen")
            // Last: it opens a lease of its own.
            val fresh = pool.acquire(web)
            assertAll(
                { assertEquals(JOBS * rounds, ran + cancelled, "every job ran or was cancelled: $seen") },
                // Only the canceller cancels: a job ended cancelled for any other reason, such as a
                // failure the pool delivered as a CancellationException, breaks these two.
                { assertTrue(cancelled <= issuedTotal, "no job was cancelled but by the canceller ($issuedTotal issued): $seen") },
                { assertTrue(ran >= JOBS * rounds - issuedTotal, "every job not cancelled ran ($issuedTotal issued): $seen") },
                { assertTrue(removed >= 1, "the rig: a queued waiter was cancelled: $seen") },
                { if (runsOnRealThreads) assertTrue(handedBack >= 1, "the rig: a granted waiter handed back: $seen") },
                { assertEquals(0, open, "the pool counts no open lease: $seen") },
                { assertEquals(0, waiters, "no waiter is queued: $seen") },
                { assertEquals(emptyList(), leases, "the engine holds no open lease: $seen") },
                { assertEquals(web, fresh.granted().leaf, "a fresh waiter is granted: $seen") },
            )
        }
    }

    /**
     * [FairSharePool.withEngine] and [FairSharePool.resize] take the pool's lock from a path of their
     * own (#3573). Here a third coroutine alternates `withEngine { e, _ -> e.persist() }` with
     * `resize(SLOTS ± 1)` beside the workers of [neverMoreThanSlotsOpenOnRealThreads].
     *
     * Inside each block, under the lock, the pool and the engine must agree: the engine holds exactly
     * as many leases as the view counts open, and its capacity is the view's slot count. A block that
     * ran outside the lock, or a resize that did, would read them between two writes, and a resize
     * that reconciles the engine unlocked forks its state from under a hand-out, so a lease goes
     * missing and the end state leaks.
     */
    @Test
    fun withEngineAndResizeBesideTheWorkersLeakNoSlot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        withContext(Dispatchers.Default) {
            val engine = farm()
            val pool = realPool(engine)
            val inFlight = atomic(0)
            val maxSeen = atomic(0)
            val iterations = atomic(0)
            val alternations = atomic(0)
            // Blocks that ran while a lease was open or a waiter queued: the arm overlapped the workers.
            val contended = atomic(0)
            val disagreements = atomic(0)
            val firstDisagreement = atomic<String?>(null)
            val workersLeft = atomic(WORKERS)
            fun seen() = "maxSeen=${maxSeen.value} iterations=${iterations.value} alternations=${alternations.value} " +
                "contended=${contended.value} disagreements=${disagreements.value} ${pool.state()}"
            reportingAWedge(::seen) {
                // First, so it is already alternating when the workers start.
                launch {
                    while (workersLeft.value > 0) {
                        pool.withEngine { e, view ->
                            val persisted = e.persist()
                            val held = e.snapshot().leases.size
                            if (held != view.open || persisted.totalSlots != view.slots.toLong()) {
                                disagreements.incrementAndGet()
                                firstDisagreement.compareAndSet(
                                    null,
                                    "engine held=$held capacity=${persisted.totalSlots}, view open=${view.open} slots=${view.slots}",
                                )
                            }
                            if (view.open > 0 || view.waiting.isNotEmpty()) contended.incrementAndGet()
                        }
                        pool.resize(if (alternations.value % 2 == 0) SLOTS + 1 else SLOTS - 1)
                        alternations.incrementAndGet()
                        yield()
                    }
                }
                repeat(WORKERS) { worker ->
                    launch {
                        try {
                            repeat(ROUNDS) { round ->
                                val lease = pool.acquire(if ((worker + round) % 2 == 0) web else mobile).granted()
                                val now = inFlight.incrementAndGet()
                                maxSeen.update { maxOf(it, now) }
                                delay(1.milliseconds)
                                inFlight.decrementAndGet()
                                iterations.incrementAndGet()
                                pool.complete(lease.id, 1L)
                            }
                        } finally {
                            workersLeft.decrementAndGet()
                        }
                    }
                }
            }
            // Every coroutine joined: back to the pool's own count, then read the quiescent engine.
            pool.resize(SLOTS)
            val leases = engine.snapshot().leases
            val capacity = pool.withEngine { e, _ -> e.persist().totalSlots }
            val seen = seen()
            println("FairSharePoolConcurrencyTest.withEngineAndResizeBesideTheWorkersLeakNoSlot: realThreads=$runsOnRealThreads $seen")
            assertAll(
                { assertEquals(WORKERS * ROUNDS, iterations.value, "every round ran: $seen") },
                // The arm's own count: it ran, and ran while the workers did.
                { assertTrue(alternations.value >= MIN_ALTERNATIONS, "the arm alternated at least $MIN_ALTERNATIONS times: $seen") },
                { assertTrue(contended.value >= 1, "the rig: a block ran while the pool was busy: $seen") },
                {
                    assertEquals(
                        0,
                        disagreements.value,
                        "under the lock the engine and the view always agreed; first: ${firstDisagreement.value}: $seen",
                    )
                },
                // Lowering preempts nothing, so a slot granted at SLOTS + 1 can stay held past a lowering.
                { assertTrue(maxSeen.value <= SLOTS + 1, "never more than ${SLOTS + 1} slots held at once: $seen") },
                { assertTrue(maxSeen.value >= SLOTS + 1, "the rig: a raise was granted into: $seen") },
                { assertEquals(SLOTS.toLong(), capacity, "the engine's capacity is the pool's last resize: $seen") },
                { assertEquals(0, pool.view().open, "the pool counts no open lease: $seen") },
                { assertEquals(0, pool.waitersForTest, "no waiter is queued: $seen") },
                { assertEquals(emptyList(), leases, "the engine holds no open lease: $seen") },
            )
        }
    }

    private companion object {
        const val SLOTS = 3
        const val MIN_ALTERNATIONS = 100
        const val WORKERS = 32
        const val ROUNDS = 200
        const val JOBS = 2_000
        const val ROUND_CAP = 50
        const val WASM_ROUNDS = 1
        val WEDGE_REPORT = TEST_WEDGE_BACKSTOP - 5.seconds
        val RACE_BUDGET = 15.seconds
    }
}
