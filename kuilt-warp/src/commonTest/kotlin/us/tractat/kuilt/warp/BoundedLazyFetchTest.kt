/**
 * C5b: a lazy fetch that no peer ever serves must be **bounded** — it stands the task by on
 * timeout (transient), not hang forever holding the claim.
 *
 * The defining property: a fetch suspends until a peer serves the bytes. When the bytes are
 * never served, an *unbounded* fetch parks the executing coroutine forever — the task stays
 * **claimed**, so [WarpNode] never re-evaluates it even after the op later becomes resolvable.
 * Bounding the fetch with [WarpLazyFetch.fetchTimeout] turns this into the existing transient
 * stand-by path: on timeout the task is unclaimed (no result, no terminal error), and the next
 * [claimOwned] cycle re-evaluates it.
 *
 * This test pins that re-evaluation. A node owns a task whose op is missing and whose bobbin is
 * served by nobody. After the fetch times out and the task stands by, the op is registered
 * locally and a second (unrelated) enqueue re-triggers claim evaluation; the originally-stuck
 * task is then resolved from the local registry and a result is recorded. Without the timeout the
 * task is wedged in `claimed`, never re-evaluated, and no result ever appears.
 *
 * Coroutine discipline mirrors [SymbolicDispatchTest]: [UnconfinedTestDispatcher] with bounded
 * [advanceTimeBy] via [settle] — never [advanceUntilIdle] (anti-entropy timers re-arm forever).
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val BOUNDED_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun boundedClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.settle() =
    drainAntiEntropy(
        BOUNDED_QUILTER_CONFIG.antiEntropyInterval,
        rounds = 6,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
        postSettleRounds = 6,
    )

/** A [WasmRuntime] that must never be invoked — the op resolves locally on re-evaluation. */
private val neverLoadsRuntime = object : WasmRuntime {
    override fun load(bytes: ByteArray): Op =
        error("runtime.load must not be called — op resolves from the local registry on re-evaluation")
}

class BoundedLazyFetchTest {

    @Test
    fun unservedFetchTimesOutStandsByAndIsReEvaluated() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("c5b-bounded-fetch"))

            // Single-peer roster — A owns every task it enqueues.
            val roster = MutableStateFlow<Set<PeerId>>(setOf(seamA.selfId))

            val op = OpId("wasm:unserved")
            // The op names a bobbin, but no peer ever serves its bytes ⇒ fetch suspends.
            val opToBobbin = { id: OpId -> if (id == op) Creel().put(ByteArray(8)) else null }

            val registry = OpRegistry() // initially lacks the op — forces the lazy-fetch branch
            val nodeA = WarpNode(
                selfId = seamA.selfId, seam = seamA, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = BOUNDED_QUILTER_CONFIG, clock = boundedClock(testScheduler),
                strategy = ClaimStrategy.Ring, registry = registry,
                lazyFetch = WarpLazyFetch(
                    Creel(), neverLoadsRuntime, opToBobbin, fetchTimeout = 200.milliseconds,
                ),
            )

            val task = TaskId("stuck")
            val input = "lazy".encodeToByteArray()
            nodeA.enqueue(task, TaskDescriptor(op, input))
            settle()

            // The unserved fetch must have timed out and stood by — no result yet, no terminal error.
            assertNull(nodeA.results[task], "unserved fetch must stand by, not record a result")

            // The op now becomes locally resolvable; a fresh enqueue re-triggers claim evaluation.
            registry.register(op, Op { it.reversedArray() })
            nodeA.enqueue(TaskId("nudge"), TaskDescriptor(op, ByteArray(0)))
            settle()

            val result = nodeA.results[task]
            assertAll(
                { assertNotNull(result, "task must be re-evaluated after stand-by and produce a result") },
                { assertFalse(checkNotNull(result).isError, "a fetch timeout is transient, not a terminal error") },
                { assertContentEquals(input.reversedArray(), checkNotNull(result).bytes, "reversed bytes") },
            )
        }
}
