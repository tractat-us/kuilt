@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("ForbiddenImport") // deliberate: BinaryenWasmOptimizer needs a real dispatcher for its wasm-opt subprocess — the sanctioned real-threading exception (see BinaryenWasmOptimizerTest / ChicoryWasmRuntime). The sim's gossip still runs on StandardTestDispatcher; only the wasm-opt exec and Chicory executor are real, bridged into virtual time below.

package us.tractat.kuilt.warp

import kotlinx.coroutines.Dispatchers
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
import us.tractat.kuilt.warp.test.WasmKernelFixtures
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

private val REAL_VARIANT_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun realVariantClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

private fun TestScope.settle() =
    drainAntiEntropy(
        REAL_VARIANT_CONFIG.antiEntropyInterval,
        rounds = 6,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
        postSettleRounds = 6,
    )

/** Wall-clock ceiling for the real-IO work (wasm-opt subprocess + Chicory executor) to converge. */
private val REAL_IO_BUDGET = 30.seconds

/**
 * Bridge real-IO completion into the virtual-time sim. The sim's gossip is pumped in virtual
 * time by [settle], but the compile op's `wasm-opt` subprocess and the weak node's Chicory
 * executor complete on **real** threads and post their continuations back to the test
 * dispatcher. Pumping virtual time alone never lets those real completions land — see
 * [drainAntiEntropy]'s KDoc: "a test that must bridge a *real*-IO completion into virtual time
 * needs its own real-time-bounded poll." So we alternate a virtual-time pump with a short real
 * sleep (letting the real work finish and enqueue its continuation) until [predicate] holds,
 * bounded by [REAL_IO_BUDGET] so a genuine stall fails loud, never hangs.
 */
private fun TestScope.bridgeRealIo(describe: String, predicate: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + REAL_IO_BUDGET
    while (true) {
        settle()
        if (predicate()) return
        check(!deadline.hasPassedNow()) {
            "real-IO work ($describe) did not converge within $REAL_IO_BUDGET"
        }
        @Suppress("ForbiddenMethodCall") // deliberate: real-time bridge — give the real wasm-opt/Chicory threads a moment to finish and post continuations to the test dispatcher, then pump virtual time again.
        Thread.sleep(REAL_IO_POLL_MS)
    }
}

private const val REAL_IO_POLL_MS = 20L

/**
 * **D4 real-variant go/no-go.** The full tiered-compilation loop with the *real* toolchain: a
 * compiler node registers the real [BinaryenWasmOptimizer] via [registerBinaryenCompiler]; a
 * weak (interpreting) peer enqueues a compile request; the compiler node runs the bundled
 * `wasm-opt`, publishes the leaner module, and it gossips in; the weak peer then tiers up and
 * runs the **real optimized variant** through the real [ChicoryWasmRuntime] — with the same
 * result the raw kernel produced.
 *
 * This is the counterpart to `TieredCompilationGoNoGoTest` / `CompileOpDispatchTest` in
 * `:kuilt-warp`, which prove the *distribution + swap* mechanism with a no-op fake compiler and
 * fake runtime. Here nothing is faked: real `wasm-opt`, a genuinely smaller/distinct variant,
 * and real WASM execution on both sides — proving the swap is not just to *a* variant but to a
 * **correct, optimized** one. Speedup (a wall-clock assertion) is deliberately out of scope —
 * that is D4-4; this test is deterministic (identical results), not timed.
 *
 * The gossip/consensus is driven in virtual time by the canonical sim harness ([settle] over
 * [drainAntiEntropy]); the real `wasm-opt` subprocess and Chicory executor are bridged into it
 * by [bridgeRealIo].
 */
class RealVariantTieringTest {

    // Real subprocess I/O — the sanctioned real-threading exception; no virtual clock to drive it.
    @Suppress("ForbiddenMethodCall")
    private val optimizer = BinaryenWasmOptimizer(Dispatchers.IO)

    @Test
    fun weakPeerTiersUpToRealOptimizedVariant() =
        runTest(StandardTestDispatcher(), timeout = 60.seconds) {
            val raw = WasmKernelFixtures.REVERSE
            val input = byteArrayOf(1, 2, 3, 4)
            val expectedOutput = byteArrayOf(4, 3, 2, 1) // reverse kernel

            // --- Real optimizer + real runtime, outside the sim: the ground truth. ---
            // wasm-opt is deterministic, so this is byte-identical to what the compiler node
            // will produce (letting us re-derive the gossiped variant's content hash), and
            // running both raw and optimized through Chicory proves behaviour is preserved.
            val optimizedExpected = optimizer.optimize(raw, OptLevel.O2)
            val rawOutput = ChicoryWasmRuntime().use { it.load(raw).invoke(input) }
            val optimizedOutput = ChicoryWasmRuntime().use { it.load(optimizedExpected).invoke(input) }
            val expectedVariantHash = Creel().put(optimizedExpected).value

            // --- The sim: a compiler node and a weak node, both on real Chicory runtimes. ---
            val loom = InMemoryLoom()
            val seamC = loom.host(Pattern("real-variant"))    // compiler node
            val seamW = loom.join(InMemoryTag("w"))            // weak node (owns the reverse tasks)
            val op = OpId("reverse")

            // Each peer caches the raw kernel; opToBobbin maps op → rawHash. Real Chicory runtimes
            // so the weak peer genuinely loads + runs whichever bobbin it resolves.
            val runtimeC = ChicoryWasmRuntime()
            val runtimeW = ChicoryWasmRuntime()
            try {
                val creelC = Creel().also { it.put(raw) }
                val lfC = WarpLazyFetch(creelC, runtimeC, { id -> if (id == op) creelC.loaded.first() else null })
                val creelW = Creel().also { it.put(raw) }
                val lfW = WarpLazyFetch(creelW, runtimeW, { id -> if (id == op) creelW.loaded.first() else null })
                val rawHash = creelC.loaded.first() // same content ⇒ same hash on both peers

                // Roster = {W}: the weak node ring-owns every reverse task; the compile task is pinned to C.
                val roster = MutableStateFlow<Set<PeerId>>(setOf(seamW.selfId))

                val compilerNode = WarpNode(
                    selfId = seamC.selfId, seam = seamC, rosterFlow = roster, scope = backgroundScope,
                    quilterConfig = REAL_VARIANT_CONFIG, clock = realVariantClock(testScheduler),
                    strategy = ClaimStrategy.Ring, registry = OpRegistry(), lazyFetch = lfC, target = Target.Jvm,
                )
                compilerNode.registerBinaryenCompiler(optimizer)
                val weakNode = WarpNode(
                    selfId = seamW.selfId, seam = seamW, rosterFlow = roster, scope = backgroundScope,
                    quilterConfig = REAL_VARIANT_CONFIG, clock = realVariantClock(testScheduler),
                    strategy = ClaimStrategy.Ring, registry = OpRegistry(), lazyFetch = lfW, target = Target.Jvm,
                )

                // Phase 1 — interpret: the weak node runs a reverse task on the raw bobbin.
                weakNode.enqueue(TaskId("r1"), TaskDescriptor(op, input))
                bridgeRealIo("phase 1 interpret") { weakNode.results[TaskId("r1")] != null }
                val compiledBefore = weakNode.executionsCompiled.value
                val interpretedResult = weakNode.results[TaskId("r1")]?.bytes

                // Phase 2 — enqueue a compile TASK pinned to C: it rides the replicated work queue,
                // C claims it and runs the REAL wasm-opt via the registered Binaryen compiler, and
                // the leaner variant gossips out.
                val request = CompileRequest(rawHash, Target.Jvm, OptLevel.O2)
                val compileTask = TaskId("compile-real")
                weakNode.enqueue(compileTask, CompileOp.descriptor(request, compiler = seamC.selfId))
                bridgeRealIo("phase 2 compile") { weakNode.results[compileTask] != null }

                // Phase 3 — the weak node runs another reverse task: it must now tier up to the
                // optimized variant and execute it on real Chicory.
                weakNode.enqueue(TaskId("r2"), TaskDescriptor(op, input))
                bridgeRealIo("phase 3 tier-up") {
                    weakNode.results[TaskId("r2")] != null && weakNode.executionsCompiled.value >= 1L
                }
                val compiledResult = weakNode.results[TaskId("r2")]?.bytes

                assertAll(
                    // The real optimizer produced a genuine, distinct, smaller variant.
                    { assertTrue(optimizedExpected.size < raw.size, "wasm-opt must shrink the kernel (${optimizedExpected.size} < ${raw.size})") },
                    { assertTrue(!optimizedExpected.contentEquals(raw), "optimized bytes must differ from the raw source") },
                    // Ground-truth correctness: raw and optimized reverse identically on real Chicory.
                    { assertContentEquals(expectedOutput, rawOutput, "raw kernel reverses the input") },
                    { assertContentEquals(expectedOutput, optimizedOutput, "optimized variant reverses the input identically") },
                    // The swap mechanism: interpret first, then tier up once the variant gossips in.
                    { assertTrue(weakNode.executionsInterpreted.value >= 1L, "weak peer interpreted the raw kernel before the variant arrived") },
                    { assertContentEquals(expectedOutput, interpretedResult, "interpreted (raw) execution result is correct") },
                    { assertEquals(0L, compiledBefore, "no compiled execution before the compile task ran") },
                    { assertTrue(weakNode.executionsCompiled.value >= 1L, "GO: weak peer tiered up to the compiled variant") },
                    // The distributed variant IS the real wasm-opt output (re-derived content hash).
                    {
                        assertEquals(
                            expectedVariantHash,
                            weakNode.results[compileTask]?.bytes?.decodeToString(),
                            "compile task's replicated result is the real optimized variant's hash",
                        )
                    },
                    // The weak peer's own execution of the real optimized variant is correct.
                    { assertContentEquals(expectedOutput, compiledResult, "compiled (optimized) execution result matches the raw result") },
                )
            } finally {
                runtimeC.close()
                runtimeW.close()
            }
        }
}
