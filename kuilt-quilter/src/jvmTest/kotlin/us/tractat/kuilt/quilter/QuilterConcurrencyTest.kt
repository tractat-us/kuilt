@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)


package us.tractat.kuilt.quilter

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real OS-thread concurrency stress harness — needs Dispatchers.Default/IO for genuine parallelism probes
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fakeSeamPair
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The wall-clock ceiling every probe in this file uses — a **wedge backstop**, not a budget.
 *
 * Deliberately *not* [us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP]. That constant is 30 s and its
 * KDoc scopes it to a trajectory running on **virtual** time, where the total quantity of real
 * work is fixed and a wall-clock cap therefore asserts nothing but the host's speed. These probes
 * are the opposite: they run genuinely parallel OS threads doing real work — 16 000 lock-serialised
 * read-modify-writes in the two increment tests — so their wall time scales with machine load for a
 * real reason, and 30 s is not obviously clear of it on a contended box.
 *
 * It is a named constant rather than the `120.seconds` literal these four sites used to repeat
 * because that repetition is precisely how the next one gets written: `forbidTightRunTestTimeout`
 * (#1739) rejects a bare duration literal in a `runTest` timeout, and it caught the fifth copy
 * being added here for #2090. Sweeping the file all-or-none is what the guard asks for, so that a
 * contributor copying a neighbour copies the constant.
 */
private val CONCURRENCY_PROBE_WEDGE_BACKSTOP = 120.seconds

/**
 * How long [WriterArrivingDuringClose] waits for its writer, once while the closer watches it and
 * once to join it. Both ends are reached in milliseconds, so this only keeps a broken rig from
 * holding the lock forever, and two of them fit inside [CONCURRENCY_PROBE_WEDGE_BACKSTOP].
 */
private val WRITER_BOUND = 30.seconds

/**
 * A [Seam] whose [sendTo] stays suspended until it is cancelled, then calls [onCancelled] from the
 * cancellation handler, which runs synchronously on the thread doing the cancelling. That thread is
 * inside [Quilter.close], so this is a hook in the one place nothing else can reach.
 *
 * It is [sendTo] and not `broadcast` because only a local write's `broadcast` holds a drain open. A
 * first-contact `FullState` is launched into the replica's own scope, so it is still parked at the
 * close without having delayed it.
 */
private class ParkingSendSeam(delegate: Seam, private val onCancelled: () -> Unit) : Seam by delegate {
    var parked = 0
        private set

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        parked++
        suspendCancellableCoroutine<Unit> { send -> send.invokeOnCancellation { onCancelled() } }
    }
}

/**
 * From inside a running [Quilter.close], starts a real thread that attempts [write] and reports
 * what that thread was doing while the close was still running.
 *
 * The witness is the JVM's own record of who owns the lock the writer is parked on, not merely
 * [Thread.state]. A state of `BLOCKED` or `WAITING` can come from class loading or any other lock
 * on the writer's way to [write], and would read as a pass. A lock owned by the very thread running
 * the close cannot, so a transient wait just keeps the poll going until the writer finishes.
 */
private class WriterArrivingDuringClose(private val write: () -> Unit) {
    data class Sighting(val state: Thread.State, val lockOwnerId: Long, val lockName: String?, val closerId: Long) {
        val parkedOnTheCloser: Boolean get() = lockOwnerId == closerId
    }

    var firings = 0
        private set
    var sighting: Sighting? = null
        private set
    private var writer: Thread? = null
    private val writeOutcome = AtomicReference("no outcome")

    val outcome: String get() = writeOutcome.get()
    val writerStillAlive: Boolean get() = writer?.isAlive == true

    /** Only the first cancelled send acts: a replica parks more than one on first contact. */
    fun fire() {
        if (firings++ > 0) return
        val closerId = Thread.currentThread().threadId()
        val started = thread(name = "drain-lock-probe-writer", isDaemon = true) {
            try {
                write()
                writeOutcome.set("accepted")
            } catch (_: IllegalStateException) { // ALLOW-ise: a raw Thread, and apply is not suspend, so no cancellation can reach this
                writeOutcome.set("refused")
            }
        }
        writer = started
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = TimeSource.Monotonic.markNow() + WRITER_BOUND
        while (true) {
            val info = threads.getThreadInfo(started.threadId())
            val seen = Sighting(info?.threadState ?: started.state, info?.lockOwnerId ?: -1L, info?.lockName, closerId)
            if (seen.state == Thread.State.TERMINATED || seen.parkedOnTheCloser || deadline.hasPassedNow()) {
                sighting = seen
                return
            }
            Thread.sleep(1)
        }
    }

    fun joinWriter() {
        writer?.join(WRITER_BOUND.inWholeMilliseconds)
    }
}

/**
 * Thread-safety probes for [Quilter] (#288).
 *
 * `Quilter` keeps plain mutable state (`nextSeq`, `pendingDeltas`, `knownPeers`,
 * `expectedReceiveSeq`, `pendingInbound`, the matrix-clock fields, …) that four entry
 * points read-modify-write: public [Quilter.apply] plus three `launchIn(scope)`
 * collectors (`seam.incoming`, `seam.peers`, the anti-entropy loop). Correctness rested on
 * an undocumented single-coroutine-confinement assumption (ADR-003 §4.6 W2) — pass a
 * multithreaded scope and `apply` races the collectors over those maps with no happens-before.
 *
 * Both tests run the replicator under a genuinely multithreaded [Dispatchers.Default] scope.
 * Under the internal reentrant lock they pass cleanly; without it they throw a
 * `ConcurrentModificationException` (or, in [concurrentApplyAndInboundDeltasConverge], drop a
 * delta and never converge).
 *
 * **JVM-hosted on purpose.** The fix lives in `commonMain`, but the race only manifests under
 * real OS-thread parallelism. wasmJs is single-threaded, so `Dispatchers.Default` collapses to one
 * thread and proves nothing; on Kotlin/Native the eviction-oscillation storm's thousands of
 * blocking-lock acquisitions on the small native thread pool are too slow to finish within
 * `runTest`'s budget. The JVM gives fast, reliable real-thread coverage of the common-code lock,
 * so this suite lives in `jvmTest`.
 */
class QuilterConcurrencyTest {

    private val gsetSer = QuiltMessage.serializer(GSet.serializer(serializer<String>()))

    private fun gsetReplicator(seam: Seam, scope: CoroutineScope) = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GSet.empty<String>(),
        messageSerializer = gsetSer,
        scope = scope,
        // Real-clock anti-entropy is fine: these tests run on real time, not virtual time.
        // 250ms gives in-flight deltas time to land before a gap triggers a resend, so the
        // system actually quiesces under the apply flood rather than generating a resend storm.
        config = QuilterConfig(resendRetryInterval = 250.milliseconds),
    )

    /**
     * Hammer [Quilter.apply] from many [Dispatchers.Default] coroutines while a churner
     * coroutine introduces fresh peers. Each new peer makes the `seam.peers` collector run
     * `onPeersChanged`, which **structurally grows** `knownPeers`; meanwhile every `apply` runs
     * `recomputeCut`, which **iterates** `knownPeers`. Grow-while-iterate with no happens-before
     * is a textbook `ConcurrentModificationException` — this reliably reproduces the W2 violation.
     * Under the lock the two never overlap and the replica's own state stays intact (every
     * applied element present, nothing torn).
     *
     * The churn is bounded ([maxPeers]) and the appliers stop on the first observed race, so the
     * un-raced (post-fix) path stays cheap and the raced path can't OOM by spawning unbounded
     * per-peer FullState retry coroutines.
     */
    @Test
    fun concurrentApplyAndPeerChurnDoNotCorruptState() = runTest(timeout = CONCURRENCY_PROBE_WEDGE_BACKSTOP) {
        withContext(Dispatchers.Default) {
            val lock = reentrantLock()
            val errors = mutableListOf<Throwable>()
            // A plain (racy) stop flag, deliberately NOT lock-guarded: reading it on the hot path
            // must NOT impose a happens-before barrier, or it would serialise the churner against
            // the appliers and mask the very race we are probing. A stale read just costs a few
            // extra iterations. Only the rare exception path takes the lock to append.
            var raced = false
            fun record(t: Throwable) {
                lock.withLock { errors.add(t) }
                raced = true
            }
            val handler = CoroutineExceptionHandler { _, t -> record(t) }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + handler)
            try {
                val loom = InMemoryLoom()
                val raw = loom.host(Pattern("concurrency-churn"))
                val controlledPeers = MutableStateFlow(setOf(raw.selfId))
                val seam = object : Seam by raw {
                    override val peers = controlledPeers
                }
                // Aggressive eviction keeps `knownPeers` SMALL (fast to iterate) while the churner
                // keeps adding: the `seam.peers` collector (adds in onPeersChanged) and the
                // anti-entropy collector (removes in evictStalePeers) both structurally modify
                // `knownPeers` throughout the storm, while every apply iterates it in recomputeCut.
                // fullStateRetryLimit = 0 means a fresh peer schedules no retry coroutine, so the
                // unbounded churn can't OOM. Real clock (default) + evictionAfter 0 ⇒ an absent
                // peer is immediately stale.
                val rep = Quilter(
                    replica = ReplicaId(seam.selfId.value),
                    seam = seam,
                    initial = GSet.empty<String>(),
                    messageSerializer = gsetSer,
                    scope = scope,
                    config = QuilterConfig(
                        evictionAfter = 0.milliseconds,
                        antiEntropyInterval = 1.milliseconds,
                        fullStateRetryLimit = 0,
                        resendRetryInterval = 20.milliseconds,
                    ),
                )

                val workers = 8
                val perWorker = 4000
                val total = workers * perWorker
                coroutineScope {
                    val churner = launch {
                        var n = 0
                        while (isActive && !raced) {
                            controlledPeers.value = setOf(raw.selfId, PeerId("fake-${n++}"))
                            delay(1) // pace so eviction can keep knownPeers small (bounded memory)
                        }
                    }
                    val appliers = (0 until workers).map { w ->
                        launch {
                            for (i in 0 until perWorker) {
                                if (raced) break
                                try {
                                    rep.apply(rep.state.value.add("$w-$i"))
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    record(t)
                                }
                            }
                        }
                    }
                    appliers.joinAll()
                    churner.cancel()
                }

                lock.withLock {
                    assertTrue(errors.isEmpty(), "apply()/peer-churn raced: ${errors.firstOrNull()}")
                }
                if (!raced) {
                    assertEquals(total, rep.state.value.elements.size, "elements lost — state torn under concurrent apply")
                }
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * Two replicators on the same mesh, each hammered with unique [GSet] adds from many
     * [Dispatchers.Default] coroutines. Local [Quilter.apply] races its own
     * `seam.incoming` collector applying the peer's deltas. A dropped/torn delta leaves a
     * replica permanently short an element; under the lock both converge to the full union.
     */
    @Test
    fun concurrentApplyAndInboundDeltasConverge() = runTest(timeout = CONCURRENCY_PROBE_WEDGE_BACKSTOP) {
        withContext(Dispatchers.Default) {
            val lock = reentrantLock()
            val errors = mutableListOf<Throwable>()
            val handler = CoroutineExceptionHandler { _, t -> lock.withLock { errors.add(t) } }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + handler)
            try {
                val loom = InMemoryLoom()
                val seamA = loom.host(Pattern("concurrency-converge"))
                val seamB = loom.join(InMemoryTag("b"))
                val repA = gsetReplicator(seamA, scope)
                val repB = gsetReplicator(seamB, scope)

                val workers = 8
                val perWorker = 150   // 2400 racing applies — ample race coverage; ~3× less backlog than 400
                val aElements = buildSet { repeat(workers) { w -> repeat(perWorker) { i -> add("a-$w-$i") } } }
                val bElements = buildSet { repeat(workers) { w -> repeat(perWorker) { i -> add("b-$w-$i") } } }
                val expected = aElements + bElements

                coroutineScope {
                    listOf(repA to "a", repB to "b").forEach { (rep, tag) ->
                        repeat(workers) { w ->
                            launch {
                                for (i in 0 until perWorker) {
                                    try {
                                        rep.apply(rep.state.value.add("$tag-$w-$i"))
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (t: Throwable) {
                                        lock.withLock { errors.add(t) }
                                    }
                                }
                            }
                        }
                    }
                }

                // Real-time convergence: poll until both sides reach the full union (gaps from
                // out-of-order broadcast heal via Resend) or the budget expires.
                //
                // IMPORTANT: run this on Dispatchers.IO, NOT Dispatchers.Default. The apply flood
                // above saturates Default's small CPU-bound pool; if the observer's delay(50ms)
                // continuation waits behind the replicator coroutines for a Default thread, `waited`
                // advances far slower than wall-clock and the loop never finishes — a hang rather
                // than a fast failure. IO's elastic thread pool is unaffected by Default starvation,
                // so a non-convergence surfaces as an assertion failure within the budget window.
                withContext(Dispatchers.IO) {
                    val deadline = 30.seconds
                    var waited = 0.milliseconds
                    val step = 50.milliseconds
                    while (waited < deadline &&
                        !(repA.state.value.elements == expected && repB.state.value.elements == expected)
                    ) {
                        delay(step)
                        waited += step
                    }
                }

                lock.withLock {
                    assertTrue(errors.isEmpty(), "replicator raced under concurrency: ${errors.firstOrNull()}")
                }
                assertEquals(expected, repA.state.value.elements, "A did not converge to the full union")
                assertEquals(expected, repB.state.value.elements, "B did not converge to the full union")
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * [Quilter.mutate] is the documented atomic read-modify-write entry point — concurrent
     * same-replica increments must all survive (#1270).
     *
     * Uses a **non-idempotent** CRDT deliberately: a [PNCounter] delta carries the
     * replica's absolute new slot value, and join is elementwise max, so two `mutate`
     * calls that both read slot `n` and both write `n + 1` max-join into a single
     * increment — a silently lost (and globally broadcast) update. The GSet used by the
     * other tests here is idempotent under max-join, which is exactly why it masked this
     * class of bug. Before the fix, `mutate` ran the transform on `state.value` *outside*
     * the lock, making the lost update easy to hit under real OS-thread parallelism.
     */
    @Test
    fun concurrentMutateIncrementsAreNotLost() = runTest(timeout = CONCURRENCY_PROBE_WEDGE_BACKSTOP) {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val loom = InMemoryLoom()
                val seam = loom.host(Pattern("mutate-atomicity"))
                val rep = Quilter(
                    replica = ReplicaId(seam.selfId.value),
                    seam = seam,
                    initial = PNCounter.ZERO,
                    messageSerializer = QuiltMessage.serializer(PNCounter.serializer()),
                    scope = scope,
                )

                val workers = 8
                val perWorker = 2000
                coroutineScope {
                    repeat(workers) {
                        launch {
                            repeat(perWorker) {
                                rep.mutate { it.increment(rep.replica) }
                            }
                        }
                    }
                }

                assertEquals(
                    (workers * perWorker).toLong(),
                    rep.state.value.value,
                    "same-replica increments were max-joined away — mutate must run its " +
                        "transform inside the lock to be the atomic RMW its KDoc promises",
                )
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * The [concurrentMutateIncrementsAreNotLost] twin for [Quilter.mutateOrSkip] (#2090) — same
     * CRDT, same shape, same reason.
     *
     * `mutateOrSkip` makes the *same* atomicity promise as [Quilter.mutate], and the whole point
     * of it is that the refusal decision is inside the lock too. That guarantee needs its own
     * real-thread probe: a virtual-time test cannot distinguish the correct implementation from
     * one that reads `state.value` **outside** the lock and only takes it to publish. That variant
     * is not hypothetical — it is exactly the bug [Quilter.mutate] shipped with and was fixed for
     * in #1270, and it survives every single-threaded assertion, including the invocation-count
     * pin in `QuilterMutateOrSkipTest` (it evaluates the transform exactly once, on both branches).
     *
     * [PNCounter] for the same reason as its twin: the delta carries the replica's absolute new
     * slot value and join is elementwise max, so two transforms that both read slot `n` and write
     * `n + 1` collapse into one increment. The `GSet` used elsewhere in this file is idempotent
     * under max-join and would mask it.
     */
    @Test
    fun concurrentMutateOrSkipIncrementsAreNotLost() = runTest(timeout = CONCURRENCY_PROBE_WEDGE_BACKSTOP) {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val loom = InMemoryLoom()
                val seam = loom.host(Pattern("mutate-or-skip-atomicity"))
                val rep = Quilter(
                    replica = ReplicaId(seam.selfId.value),
                    seam = seam,
                    initial = PNCounter.ZERO,
                    messageSerializer = QuiltMessage.serializer(PNCounter.serializer()),
                    scope = scope,
                )

                val workers = 8
                val perWorker = 2000
                coroutineScope {
                    repeat(workers) {
                        launch {
                            repeat(perWorker) {
                                rep.mutateOrSkip { it.increment(rep.replica) }
                            }
                        }
                    }
                }

                assertEquals(
                    (workers * perWorker).toLong(),
                    rep.state.value.value,
                    "same-replica increments were max-joined away — mutateOrSkip must read the " +
                        "state and run its transform INSIDE the lock, not merely publish there",
                )
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * [Quilter.drainAndClose] against writers on other threads (#2616): **a write that returned
     * normally has been handed to the seam by the time the drain returns**, with `apply` and the drain
     * genuinely overlapping, which the virtual-time `QuilterDrainAndCloseTest` cannot arrange.
     *
     * The rig asserts it fired. `racedRounds` counts rounds where a write was accepted **and** a writer
     * met the closed replica. A refusal alone is not enough: a drain that closes before any writer has
     * written refuses all of them, and `accepted == handed == 0` then holds with nothing raced. With
     * no refusal at all, every drain merely outlasted its writers, and the equality below would hold
     * for a drain that waited for nothing.
     *
     * **What this does not reliably pin: that the "nothing in flight" check is atomic with the close.**
     * Moving the close outside the lock opens a gap of a few nanoseconds that a writer must land in, and
     * nothing in that gap can be hooked to force it. Measured with that mutation: red once at round 297
     * of 300, then green in 4 runs of 1 000 rounds. [drainClosesWhileHoldingTheLockAWriteMustTake] pins
     * the close being under the lock without needing the race. A check and a close under two separate
     * holds is the part left: that test cannot see it, and this one only by chance, so it rests on the
     * code's shape and its comment. The mutations this does catch reliably, a drain that stops looking
     * after one snapshot and a write send the drain cannot see, red it within the first 100 rounds.
     */
    @Test
    fun aWriteThatReturnedIsNeverLostToARacingDrain() = runTest(timeout = CONCURRENCY_PROBE_WEDGE_BACKSTOP) {
        val rounds = 300
        val writers = 4
        val perWriter = 300
        var roundsRun = 0
        var racedRounds = 0

        withContext(Dispatchers.Default) {
            repeat(rounds) { round ->
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                try {
                    val accepted = AtomicInteger()
                    val refused = AtomicInteger()
                    val handed = AtomicInteger()
                    val raw = InMemoryLoom().host(Pattern("drain-race-$round"))
                    val seam = object : Seam by raw {
                        override suspend fun broadcast(payload: ByteArray) {
                            yield() // a send that suspends, so sends overlap the drain
                            handed.incrementAndGet()
                        }
                    }
                    val rep = Quilter(
                        replica = ReplicaId(seam.selfId.value),
                        seam = seam,
                        initial = PNCounter.ZERO,
                        messageSerializer = QuiltMessage.serializer(PNCounter.serializer()),
                        scope = scope,
                    )

                    var handedWhenTheDrainReturned = 0
                    coroutineScope {
                        val writerJobs = List(writers) {
                            launch {
                                for (i in 0 until perWriter) {
                                    try {
                                        rep.mutate { it.increment(rep.replica) }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (_: IllegalStateException) {
                                        refused.incrementAndGet()
                                        break
                                    }
                                    accepted.incrementAndGet()
                                    yield()
                                }
                            }
                        }
                        yield()
                        rep.drainAndClose()
                        handedWhenTheDrainReturned = handed.get()
                        writerJobs.joinAll()
                    }

                    assertEquals(
                        accepted.get(),
                        handedWhenTheDrainReturned,
                        "round $round: every accepted write should have been handed to the seam by " +
                            "the time the drain returned",
                    )
                    if (accepted.get() > 0 && refused.get() > 0) racedRounds++
                    roundsRun++
                } finally {
                    scope.cancel()
                }
            }
        }

        println("aWriteThatReturnedIsNeverLostToARacingDrain: racedRounds=$racedRounds of $rounds")
        assertEquals(rounds, roundsRun, "every round should have run")
        assertTrue(
            racedRounds > 0,
            "rig never fired: in no round did a writer get a write accepted and a writer then meet the " +
                "closed replica, so no drain overlapped a write",
        )
    }

    /**
     * [Quilter.drainAndClose]'s "nothing in flight, so close" is one atomic step against a racing
     * write only if the close runs **while holding the lock a write must take** (#2616). The race itself
     * cannot be forced, since nothing in the gap can be hooked, but the lock being held can be observed
     * without it: cancelling the replica's job runs [ParkingSendSeam]'s handler on the closing thread,
     * inside [Quilter.close], and a writer started from there either parks on a lock that thread owns,
     * or finishes.
     *
     * It runs on the test dispatcher so that the test thread is the closer and no replica coroutine
     * can be holding the lock on another thread. The only real thread is the writer.
     *
     * **What this does not catch: `lock { snapshot }; lock { close() }`.** That shape holds the lock
     * during the close and lets a write in between the two blocks, so it is not atomic, and this test
     * stays green on it (measured). Only [aWriteThatReturnedIsNeverLostToARacingDrain] can see that
     * shape, and then only by chance. So this pins that the close is under the lock, not that it is
     * the same hold as the check.
     */
    @Test
    fun drainClosesWhileHoldingTheLockAWriteMustTake() = runTest(timeout = CONCURRENCY_PROBE_WEDGE_BACKSTOP) {
        val probe = closeWithAWriterArriving { drainAndClose() }

        assertAll(
            { assertTrue(probe.firings > 0, "rig: a send cancelled by the close should have started the writer") },
            {
                assertTrue(
                    probe.sighting?.parkedOnTheCloser == true,
                    "while the drain was closing, a writer should wait on a lock the closing thread " +
                        "holds, saw ${probe.sighting}",
                )
            },
            { assertEquals("refused", probe.outcome, "once released, the writer should meet the closed replica") },
            { assertFalse(probe.writerStillAlive, "the writer should finish once the drain returns") },
        )
    }

    /**
     * The positive control for [drainClosesWhileHoldingTheLockAWriteMustTake]: the same rig, with the
     * close run by plain [Quilter.close], which holds no lock while it cancels. If the writer did not
     * finish here, the probe could not tell a held lock from a free one and that test would prove
     * nothing. It is also what the mutation "close outside the lock" looks like to the probe.
     */
    @Test
    fun plainCloseHoldsNoLockSoTheSameWriterFinishes() = runTest(timeout = CONCURRENCY_PROBE_WEDGE_BACKSTOP) {
        val probe = closeWithAWriterArriving { close() }

        assertAll(
            { assertTrue(probe.firings > 0, "rig: a send cancelled by the close should have started the writer") },
            {
                assertEquals(
                    Thread.State.TERMINATED,
                    probe.sighting?.state,
                    "with no lock held during the close, the writer should run to its end, saw ${probe.sighting}",
                )
            },
            { assertEquals("refused", probe.outcome, "the writer should meet the closed replica") },
        )
    }

    private suspend fun TestScope.closeWithAWriterArriving(
        teardown: suspend Quilter<PNCounter>.() -> Unit,
    ): WriterArrivingDuringClose {
        lateinit var rep: Quilter<PNCounter>
        val probe = WriterArrivingDuringClose { rep.apply(rep.state.value.increment(rep.replica)) }
        val (fake, _) = fakeSeamPair(PeerId("self"), PeerId("other"))
        val seam = ParkingSendSeam(fake, probe::fire)
        rep = Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = PNCounter.ZERO,
            messageSerializer = QuiltMessage.serializer(PNCounter.serializer()),
            scope = backgroundScope,
            config = QuilterConfig(expectVirtualTime = true),
        )
        runCurrent()
        assertTrue(seam.parked > 0, "precondition: first contact with the peer should have parked a send")
        assertEquals(emptyList(), rep.writeSendsForTest, "precondition: nothing should be holding the drain open")

        rep.teardown()
        probe.joinWriter()
        return probe
    }
}
