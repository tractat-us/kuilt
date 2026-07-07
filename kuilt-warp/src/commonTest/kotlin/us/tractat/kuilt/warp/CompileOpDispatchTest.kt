@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val COMPILE_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun compileClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.settle() =
    drainAntiEntropy(
        COMPILE_CONFIG.antiEntropyInterval,
        rounds = 6,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
        postSettleRounds = 6,
    )

/**
 * **Compile as a ring-dispatched op.** The producer half of tiered
 * compilation runs through the ordinary task ring: a compile *task* — not a direct
 * imperative [WarpNode.publishVariant] call — is enqueued as a [TaskDescriptor], claimed by
 * the compiler-node peer (the one that [WarpNode.registerCompiler]ed), executed (calling
 * [WarpNode.publishVariant] internally), and the gossiped variant tiers the weak peer up.
 * Mirrors [TieredCompilationGoNoGoTest], which keeps pinning the imperative path.
 */
class CompileOpDispatchTest {

    @Test
    fun compileTaskDispatchedThroughRingProducesGossipedVariant() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamC = loom.host(Pattern("compile-op"))    // compiler node
            val seamW = loom.join(InMemoryTag("w"))          // weak node (owns the square tasks)
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

            // Roster = {W}: the weak node ring-owns every task; the compile task is pinned to C.
            val roster = MutableStateFlow<Set<PeerId>>(setOf(seamW.selfId))

            val compilerNode = WarpNode(
                selfId = seamC.selfId, seam = seamC, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = COMPILE_CONFIG, clock = compileClock(testScheduler), strategy = ClaimStrategy.Ring,
                registry = OpRegistry(), lazyFetch = lfC, target = Target.Jvm,
            )
            compilerNode.registerCompiler { source, target, _ -> fakeCompile(source, target) }
            val weakNode = WarpNode(
                selfId = seamW.selfId, seam = seamW, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = COMPILE_CONFIG, clock = compileClock(testScheduler), strategy = ClaimStrategy.Ring,
                registry = OpRegistry(), lazyFetch = lfW, target = Target.Jvm,
            )

            // Phase 1 — interpret: the weak node runs a task on the raw bobbin.
            weakNode.enqueue(TaskId("c1"), TaskDescriptor(op, byteArrayOf(5)))
            settle()
            val compiledBefore = weakNode.executionsCompiled.value

            // Phase 2 — enqueue a compile TASK (no imperative publishVariant call anywhere):
            // it rides the replicated work queue, C claims it (pinned), runs its registered
            // compile op, and the variant gossips out.
            val request = CompileRequest(rawHash, Target.Jvm, OptLevel.O2)
            val compileTask = TaskId("compile-1")
            weakNode.enqueue(compileTask, CompileOp.descriptor(request, compiler = seamC.selfId))
            settle()

            // Phase 3 — the weak node runs another task: it must now tier up.
            weakNode.enqueue(TaskId("c2"), TaskDescriptor(op, byteArrayOf(6)))
            settle()

            // compile(sourceHash, target, optLevel) → variantHash, verifiable by re-deriving
            // the content address of the deterministic fake compiler's output.
            val expectedVariantHash = Creel().put(fakeCompile(MINIMAL_WASM, Target.Jvm))

            assertAll(
                { assertTrue(weakNode.executionsInterpreted.value >= 1L, "weak peer interpreted before the variant arrived") },
                { assertTrue(compiledBefore == 0L, "no compiled execution before the compile task ran") },
                { assertTrue(weakNode.executionsCompiled.value >= 1L, "weak peer tiered up via the ring-dispatched compile task") },
                {
                    assertEquals(
                        expectedVariantHash.value,
                        weakNode.results[compileTask]?.bytes?.decodeToString(),
                        "compile task's replicated result is the variant hash",
                    )
                },
                { assertTrue(weakNode.results[TaskId("c2")] != null, "result still recorded after tiering") },
            )
        }

    @Test
    fun registerCompilerWithoutLazyFetchFailsLoud() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("compile-op-no-lf"))
            val roster = MutableStateFlow(setOf(seam.selfId))
            val node = WarpNode(
                selfId = seam.selfId, seam = seam, rosterFlow = roster, scope = backgroundScope,
                quilterConfig = COMPILE_CONFIG, clock = compileClock(testScheduler),
                registry = OpRegistry(), // no lazyFetch ⇒ cannot fetch sources or publish variants
            )
            assertFailsWith<IllegalStateException> {
                node.registerCompiler { source, _, _ -> source }
            }
        }

    @Test
    fun compileRequestRoundTripsThroughDescriptorArgs() {
        val request = CompileRequest(BobbinHash("ab".repeat(32)), Target.MacosArm64, OptLevel.O0)
        val descriptor = CompileOp.descriptor(request, compiler = PeerId("compiler-1"))
        assertAll(
            { assertEquals(CompileOp.ID, descriptor.op) },
            { assertEquals(PeerId("compiler-1"), descriptor.pinnedOwner) },
            { assertEquals(request, CompileRequest.decode(descriptor.args)) },
        )
    }
}
