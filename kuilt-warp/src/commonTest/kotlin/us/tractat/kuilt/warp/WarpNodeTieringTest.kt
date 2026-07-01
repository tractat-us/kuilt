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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val TIER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun tierClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.settle() =
    drainAntiEntropy(
        TIER_CONFIG.antiEntropyInterval,
        rounds = 6,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
        postSettleRounds = 6,
    )

class WarpNodeTieringTest {

    /**
     * A single tiering node, target = Jvm: it interprets the raw bobbin first
     * (executionsInterpreted ≥ 1), then after a Jvm variant is published it tiers up
     * (executionsCompiled ≥ 1) — results identical throughout.
     */
    @Test
    fun nodeTiersUpFromInterpretedToCompiledWhenVariantAppears() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("tiering-solo"))
            val op = OpId("square")
            val creel = Creel()

            // The raw bobbin's bytes; opToBobbin maps the op to its hash.
            val rawHash = creel.put(MINIMAL_WASM)
            val squareOp = Op { args -> args } // identity is enough — FakeWasmRuntime returns it
            val lazyFetch = WarpLazyFetch(
                creel = creel,
                runtime = FakeWasmRuntime(squareOp),
                opToBobbin = { id -> if (id == op) rawHash else null },
            )

            // Roster = {self}: this node owns every task.
            val roster = MutableStateFlow<Set<PeerId>>(setOf(seam.selfId))
            val node = WarpNode(
                selfId = seam.selfId, seam = seam, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = TIER_CONFIG, clock = tierClock(testScheduler),
                strategy = ClaimStrategy.Ring,
                registry = OpRegistry(), // op NOT registered ⇒ resolved via lazyFetch
                lazyFetch = lazyFetch, target = Target.Jvm,
            )

            // Interpret phase: run a task; only the raw bobbin exists.
            node.enqueue(TaskId("t1"), TaskDescriptor(op, byteArrayOf(7)))
            settle()
            val interpretedAfterT1 = node.executionsInterpreted.value
            val compiledAfterT1 = node.executionsCompiled.value

            // Publish a Jvm variant, then run another task: it must tier up.
            node.publishVariant(fakeCompile(MINIMAL_WASM, Target.Jvm), VariantKey(rawHash, Target.Jvm, OptLevel.O2))
            settle()
            node.enqueue(TaskId("t2"), TaskDescriptor(op, byteArrayOf(8)))
            settle()

            assertAll(
                { assertTrue(interpretedAfterT1 >= 1L, "first task interpreted (raw bobbin), was $interpretedAfterT1") },
                { assertEquals(0L, compiledAfterT1, "no compiled execution before any variant exists") },
                { assertTrue(node.executionsCompiled.value >= 1L, "second task tiered up to compiled, was ${node.executionsCompiled.value}") },
                { assertTrue(node.results[TaskId("t2")] != null, "result still recorded after tiering") },
            )
        }
}
