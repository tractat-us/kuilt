@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val GNG_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun gngClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.settle() =
    drainAntiEntropy(
        GNG_CONFIG.antiEntropyInterval,
        rounds = 6,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
        postSettleRounds = 6,
    )

/**
 * **Epic D go/no-go.** A weak (interpreting) peer tiers up to a compiled variant produced and
 * gossiped by a compiler-node peer — on a non-iOS target — observed via the durable
 * executionsCompiled counter. Proves the *distribution + swap* mechanism end-to-end. It does
 * NOT prove speedup (the fake compiler is a no-op transform); that is the D4 toolchain epic.
 */
class TieredCompilationGoNoGoTest {

    @Test
    fun weakPeerTiersUpViaGossipedVariant() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamC = loom.host(Pattern("tiered-gng"))   // compiler node
            val seamW = loom.join(InMemoryTag("w"))         // weak node (owns the tasks)
            val op = OpId("square")

            // Each peer has its own Creel seeded with the raw kernel; opToBobbin maps op→rawHash.
            fun lazyFetchFor(): Pair<Creel, WarpLazyFetch> {
                val creel = Creel()
                val rawHash = creel.put(MINIMAL_WASM)
                val lf = WarpLazyFetch(creel, FakeWasmRuntime(Op { args -> args }), { id -> if (id == op) rawHash else null })
                return creel to lf
            }
            val (creelC, lfC) = lazyFetchFor()
            val (_, lfW) = lazyFetchFor()
            val rawHash = creelC.loaded.first() // same content ⇒ same hash on both peers

            // Roster = {W}: the weak node owns and executes every task; C is the compiler node.
            val roster = MutableStateFlow<Set<PeerId>>(setOf(seamW.selfId))

            val compilerNode = WarpNode(
                selfId = seamC.selfId, seam = seamC, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = GNG_CONFIG, clock = gngClock(testScheduler), strategy = ClaimStrategy.Ring,
                registry = OpRegistry(), lazyFetch = lfC, target = Target.Jvm,
            )
            val weakNode = WarpNode(
                selfId = seamW.selfId, seam = seamW, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = GNG_CONFIG, clock = gngClock(testScheduler), strategy = ClaimStrategy.Ring,
                registry = OpRegistry(), lazyFetch = lfW, target = Target.Jvm,
            )

            // Phase 1 — interpret: the weak node runs a task on the raw bobbin.
            weakNode.enqueue(TaskId("g1"), TaskDescriptor(op, byteArrayOf(5)))
            settle()
            val compiledBefore = weakNode.executionsCompiled.value

            // Phase 2 — the compiler node builds + gossips a Jvm variant.
            compilerNode.publishVariant(fakeCompile(MINIMAL_WASM, Target.Jvm), VariantKey(rawHash, Target.Jvm, OptLevel.O2))
            settle()

            // Phase 3 — the weak node runs another task: it must now tier up.
            weakNode.enqueue(TaskId("g2"), TaskDescriptor(op, byteArrayOf(6)))
            settle()

            assertAll(
                { assertTrue(weakNode.executionsInterpreted.value >= 1L, "weak peer interpreted before the variant arrived") },
                { assertTrue(compiledBefore == 0L, "no compiled execution before the variant gossiped in") },
                { assertTrue(weakNode.executionsCompiled.value >= 1L, "GO: weak peer tiered up to compiled via the gossiped variant") },
                { assertTrue(weakNode.results[TaskId("g2")] != null, "result still recorded after tiering") },
            )
        }
}
