@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)
@file:Suppress("ForbiddenImport") // deliberate: real OS-thread concurrency stress harness — needs Dispatchers.Default/IO for genuine parallelism probes


package us.tractat.kuilt.quilter

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Thread-safety probes for [SeamReplicator] (#288).
 *
 * `SeamReplicator` keeps plain mutable state (`nextSeq`, `pendingDeltas`, `knownPeers`,
 * `expectedReceiveSeq`, `pendingInbound`, the matrix-clock fields, …) that four entry
 * points read-modify-write: public [SeamReplicator.apply] plus three `launchIn(scope)`
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
class SeamReplicatorConcurrencyTest {

    private val gsetSer = ReplicatorMessage.serializer(GSet.serializer(serializer<String>()))

    private fun gsetReplicator(seam: Seam, scope: CoroutineScope) = SeamReplicator(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GSet.empty<String>(),
        messageSerializer = gsetSer,
        scope = scope,
        // Real-clock anti-entropy is fine: these tests run on real time, not virtual time.
        // 250ms gives in-flight deltas time to land before a gap triggers a resend, so the
        // system actually quiesces under the apply flood rather than generating a resend storm.
        config = SeamReplicatorConfig(resendRetryInterval = 250.milliseconds),
    )

    /**
     * Hammer [SeamReplicator.apply] from many [Dispatchers.Default] coroutines while a churner
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
    fun concurrentApplyAndPeerChurnDoNotCorruptState() = runTest(timeout = 120.seconds) {
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
                val rep = SeamReplicator(
                    replica = ReplicaId(seam.selfId.value),
                    seam = seam,
                    initial = GSet.empty<String>(),
                    messageSerializer = gsetSer,
                    scope = scope,
                    config = SeamReplicatorConfig(
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
     * [Dispatchers.Default] coroutines. Local [SeamReplicator.apply] races its own
     * `seam.incoming` collector applying the peer's deltas. A dropped/torn delta leaves a
     * replica permanently short an element; under the lock both converge to the full union.
     */
    @Test
    fun concurrentApplyAndInboundDeltasConverge() = runTest(timeout = 120.seconds) {
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
}
