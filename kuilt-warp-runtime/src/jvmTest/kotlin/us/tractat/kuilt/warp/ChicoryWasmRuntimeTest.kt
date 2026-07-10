package us.tractat.kuilt.warp

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.test.WasmKernelFixtures
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * JVM-specific [ChicoryWasmRuntime] tests.
 *
 * The shared safety contract (load guards, execution timeout, trap surfacing, OOB vectors)
 * is verified by [ChicoryWasmRuntimeConformanceTest] via the cross-target
 * [us.tractat.kuilt.warp.test.WasmRuntimeConformanceSuite]; this class keeps only the
 * behaviours specific to the Chicory implementation's shared single-worker guest executor.
 *
 * @see ChicoryWasmRuntimeTimingTest for the deterministic [TimedGuestRunner] regression tests.
 */
class ChicoryWasmRuntimeTest {

    /**
     * Shared-runtime concurrency guard for the per-runtime [invokeMutex] (Defect 2). One runtime
     * serves several concurrently-running ops; a [us.tractat.kuilt.warp.WarpNode] runs owned tasks
     * concurrently over a single shared runtime. A runaway infinite-loop kernel and an innocent
     * `reverse` op are launched concurrently over one runtime; the innocent op must complete with
     * the correct bytes and the runaway must be bounded by the timeout — proving the serialization
     * neither deadlocks nor starves a concurrent caller, and that the innocent op gets its full
     * execution budget from when it actually runs (post-mutex) rather than from when it queued.
     *
     * On the literal false-timeout this defends against: it is a near-simultaneous-submit *race*,
     * not a deterministic failure. With one shared per-runtime timeout `T` and the interpreter's
     * prompt interrupt-freeing, a later-submitted op's deadline (`submit + T`) always trails each
     * earlier runaway's free-time (its own `submit + T`), so it cannot be deterministically
     * false-timed. A reproducible false-timeout would need *completing* slow kernels stacked so
     * cumulative queue-wait exceeds the budget — i.e. a test gated on real wall-clock durations,
     * which this repo's coroutine-determinism policy forbids. So this asserts the post-fix
     * guarantee (concurrent ops over one runtime all succeed) deterministically, instead of
     * chasing a flaky red. The 30 s `runTest` ceiling is a safety bound, not a timed assertion.
     */
    @Test
    fun concurrentOpsOverSharedRuntimeAllSucceed() = runTest(timeout = 30.seconds) {
        ChicoryWasmRuntime(WasmSandboxConfig(executionTimeout = 200.milliseconds)).use { rt ->
            val loop = rt.load(WasmKernelFixtures.CPU_BOMB)
            val reverse = rt.load(WasmKernelFixtures.REVERSE)
            coroutineScope {
                val runaway = async { runCatchingCancellable { loop.invoke(ByteArray(0)) } }
                val innocent = async { reverse.invoke(byteArrayOf(1, 2, 3, 4)) }
                val innocentBytes = innocent.await()
                val runawayOutcome = runaway.await()
                assertAll(
                    { assertContentEquals(byteArrayOf(4, 3, 2, 1), innocentBytes, "innocent op succeeds") },
                    {
                        assertTrue(
                            runawayOutcome.exceptionOrNull() is WasmExecutionException,
                            "runaway op is bounded by the timeout",
                        )
                    },
                )
            }
        }
    }
}
