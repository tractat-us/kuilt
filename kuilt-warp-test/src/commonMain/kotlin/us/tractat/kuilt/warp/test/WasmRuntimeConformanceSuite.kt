package us.tractat.kuilt.warp.test

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.WasmException
import us.tractat.kuilt.warp.WasmExecutionException
import us.tractat.kuilt.warp.WasmLoadException
import us.tractat.kuilt.warp.WasmRuntime
import us.tractat.kuilt.warp.WasmSandboxConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Conformance TCK for [WasmRuntime] — the uniform safety contract every implementation
 * (JVM Chicory, native wasm3, browser WebAssembly API, and any future impl) must enforce
 * against untrusted kernels. Validate an implementation by subclassing this suite and
 * overriding [newRuntime]:
 *
 * ```kotlin
 * class MyWasmRuntimeConformanceTest : WasmRuntimeConformanceSuite() {
 *     override fun newRuntime(config: WasmSandboxConfig) = MyWasmRuntime(config)
 * }
 * ```
 *
 * Every vector in [WasmKernelFixtures] is byte-identical on every target, so all
 * implementations are held to exactly the same contract:
 *
 * **Load-time guards** — each rejection is a [WasmLoadException], never a raw engine error
 * (a non-[us.tractat.kuilt.warp.WasmException] escaping `load` would bypass the executor's
 * terminal-error handling and trigger an anti-entropy retry storm on a verified-but-broken
 * kernel — a remotely-triggerable DoS):
 * - a declared import (capability violation);
 * - linear memory with no explicit max (the memory-bomb: unbounded `memory.grow`);
 * - a declared max or initial size exceeding [WasmSandboxConfig.maxMemoryPages];
 * - malformed bytes; missing `warp_alloc`/`warp_run` ABI exports; no linear memory at all.
 *
 * **Run-time guards** — each fault is a [WasmExecutionException], never a hang or a raw error:
 * - a CPU-bomb kernel (infinite `loop`/`br`) is terminated near
 *   [WasmSandboxConfig.executionTimeout] with an error naming the exceeded budget, and the
 *   timeout must not poison subsequent invocations;
 * - a trap (`unreachable`) surfaces as an execution failure;
 * - `memory.grow` past the declared max is denied by the engine (the evidence that
 *   reject-no-max is a sufficient memory ceiling);
 * - guest-controlled ABI words (alloc pointer, packed result pointer/length) with the high
 *   bit set are bounds-rejected, never sign-wrapped into host-memory access.
 *
 * **Load-phase execution bound** — a module's `(start)` function runs at instantiation,
 * before any ABI call, so the invocation budget alone cannot bound it. The contract is
 * phase-agnostic: whether an impl runs `(start)` eagerly under a bounded `load` (surfacing
 * [WasmLoadException]) or defers instantiation to the first bounded invocation (surfacing
 * [WasmExecutionException]), a `(start)` CPU bomb must fail terminally near the budget —
 * never hang the host (see [startSectionCpuBombIsBoundedNotHung]).
 *
 * The CPU-bomb vectors burn REAL wall-clock CPU: the sandbox budget is dropped to 250 ms and
 * `runTest` timeouts are kept tight so a non-conforming impl fails fast instead of wedging
 * the test host.
 */
public abstract class WasmRuntimeConformanceSuite {

    /** A fresh runtime under test, honouring [config]. */
    public abstract fun newRuntime(config: WasmSandboxConfig): WasmRuntime

    private fun newRuntime(): WasmRuntime = newRuntime(WasmSandboxConfig())

    // ── Happy path — the guards must not over-reject a well-behaved kernel ──────────────────

    @Test
    public fun wellBehavedKernelRoundTrips(): TestResult = runTest(timeout = 10.seconds) {
        val op = newRuntime().load(WasmKernelFixtures.REVERSE)
        assertContentEquals(byteArrayOf(4, 3, 2, 1), op.invoke(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    public fun emptyInputReturnsEmpty(): TestResult = runTest(timeout = 10.seconds) {
        val op = newRuntime().load(WasmKernelFixtures.REVERSE)
        assertContentEquals(byteArrayOf(), op.invoke(byteArrayOf()))
    }

    @Test
    public fun repeatInvokeReusesTheInstance(): TestResult = runTest(timeout = 10.seconds) {
        val op = newRuntime().load(WasmKernelFixtures.REVERSE)
        val input = "Hello, warp!".encodeToByteArray()
        val expected = input.reversedArray()
        val first = op.invoke(input)
        val second = op.invoke(input)
        assertAll(
            { assertContentEquals(expected, first) },
            { assertContentEquals(expected, second, "second invoke reuses the same instance") },
        )
    }

    // ── Load-time guards ─────────────────────────────────────────────────────────────────────

    /**
     * The import fixture's memory is bounded, so the capability violation is the only possible
     * rejection — the message assertion pins the import guard itself, not another guard firing
     * first.
     */
    @Test
    public fun loadRejectsModuleWithImports() {
        val ex = assertFailsWith<WasmLoadException> { newRuntime().load(WasmKernelFixtures.IMPORTS) }
        assertContains(ex.message ?: "", "capability violation")
    }

    /**
     * The unified no-max rule: a module declaring linear memory with NO explicit max is
     * rejected at load on EVERY target. It is the only memory ceiling enforceable identically
     * everywhere — the browser cannot clamp a compiled module's limits after the fact — and
     * with it every loaded module carries a bounded, engine-enforced max
     * (see [growPastDeclaredMaxTraps]).
     */
    @Test
    public fun loadRejectsModuleDeclaringMemoryWithNoMax() {
        assertFailsWith<WasmLoadException> { newRuntime().load(WasmKernelFixtures.NO_MAX_MEMORY) }
    }

    @Test
    public fun loadRejectsModuleWithOversizeDeclaredMax() {
        assertFailsWith<WasmLoadException> { newRuntime().load(WasmKernelFixtures.OVERSIZE_MAX_MEMORY) }
    }

    @Test
    public fun loadRejectsModuleWithOversizeInitialMemory() {
        assertFailsWith<WasmLoadException> { newRuntime().load(WasmKernelFixtures.OVERSIZE_INITIAL_MEMORY) }
    }

    @Test
    public fun loadRejectsMalformedBytes() {
        assertFailsWith<WasmLoadException> { newRuntime().load(byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x09)) }
    }

    @Test
    public fun loadRejectsModuleMissingAbiExports() {
        assertFailsWith<WasmLoadException> { newRuntime().load(WasmKernelFixtures.MISSING_ABI_EXPORTS) }
    }

    @Test
    public fun loadRejectsModuleMissingMemory() {
        assertFailsWith<WasmLoadException> { newRuntime().load(WasmKernelFixtures.MISSING_MEMORY) }
    }

    // ── Run-time guards ──────────────────────────────────────────────────────────────────────

    @Test
    public fun trapSurfacesAsExecutionException(): TestResult = runTest(timeout = 10.seconds) {
        val op = newRuntime().load(WasmKernelFixtures.TRAP)
        assertFailsWith<WasmExecutionException> { op.invoke(ByteArray(0)) }
    }

    /**
     * The CPU-bomb defense (#965): a kernel spinning forever on a backward branch — no calls,
     * so nothing yields voluntarily — must be terminated near
     * [WasmSandboxConfig.executionTimeout] and surface [WasmExecutionException] naming the
     * exceeded budget, never hang the host. The guest burns real wall-clock CPU, so the
     * `runTest` timeout cannot pre-empt it; only the impl's own bound can.
     */
    @Test
    public fun cpuBombIsBoundedByExecutionTimeout(): TestResult = runTest(timeout = 10.seconds) {
        val bounded = newRuntime(WasmSandboxConfig(executionTimeout = 250.milliseconds))
        val op = bounded.load(WasmKernelFixtures.CPU_BOMB)
        val ex = assertFailsWith<WasmExecutionException> { op.invoke(ByteArray(0)) }
        assertContains(ex.message ?: "", "exceeded", message = "names the budget, not a generic trap")
    }

    /**
     * The load-phase CPU bomb: a `(start)` function spinning forever runs at instantiation —
     * before any ABI call — so the per-invocation budget alone cannot bound it; an impl that
     * instantiates outside its execution budget hangs at `load` (a remotely-triggerable DoS:
     * kernels arrive from untrusted peers via lazy fetch). The contract is phase-agnostic —
     * an impl may run `(start)` eagerly under a bounded `load` (JVM: a load-time
     * [WasmLoadException]) or defer instantiation to the first bounded invocation (native,
     * browser: a run-time [WasmExecutionException]) — so the vector drives load + first
     * invoke together and accepts either [us.tractat.kuilt.warp.WasmException] arm, as long
     * as it terminates near the budget and names it.
     */
    @Test
    public fun startSectionCpuBombIsBoundedNotHung(): TestResult = runTest(timeout = 10.seconds) {
        val bounded = newRuntime(WasmSandboxConfig(executionTimeout = 250.milliseconds))
        val ex = assertFailsWith<WasmException> {
            bounded.load(WasmKernelFixtures.START_CPU_BOMB).invoke(ByteArray(0))
        }
        assertContains(ex.message ?: "", "exceeded", message = "names the budget, not a generic trap")
    }

    /**
     * A timeout must not poison the runtime: the runaway op times out AGAIN on its next
     * invocation (whatever the impl's recovery mechanism — a cleared interrupt, a re-armed
     * deadline, a respawned worker — it must re-enforce the budget), and a well-behaved op on
     * the SAME runtime still produces correct bytes afterwards.
     */
    @Test
    public fun timeoutDoesNotPoisonSubsequentInvokes(): TestResult = runTest(timeout = 15.seconds) {
        val bounded = newRuntime(WasmSandboxConfig(executionTimeout = 250.milliseconds))
        val runaway = bounded.load(WasmKernelFixtures.CPU_BOMB)
        val reverse = bounded.load(WasmKernelFixtures.REVERSE)
        assertFailsWith<WasmExecutionException> { runaway.invoke(ByteArray(0)) }
        assertFailsWith<WasmExecutionException>("recovered guest must time out again") {
            runaway.invoke(ByteArray(0))
        }
        assertContentEquals(byteArrayOf(3, 2, 1), reverse.invoke(byteArrayOf(1, 2, 3)))
    }

    /**
     * Evidence that reject-no-max is a sufficient memory ceiling (#978): the engine must
     * enforce a module's DECLARED max at grow time. The kernel converts a denied
     * `memory.grow` into `unreachable`; if the engine did NOT honour the declared max the
     * grow would succeed, no trap would fire, and this test would fail — the signal that
     * load-time rejection alone does not bound that target's memory.
     */
    @Test
    public fun growPastDeclaredMaxTraps(): TestResult = runTest(timeout = 10.seconds) {
        val op = newRuntime().load(WasmKernelFixtures.GROW_PAST_DECLARED_MAX)
        assertFailsWith<WasmExecutionException> { op.invoke(ByteArray(0)) }
    }

    // ── Guest-controlled ABI words (the OOB sandbox-escape vectors) ──────────────────────────

    @Test
    public fun resultPointerWithHighBitSetIsBounded(): TestResult = runTest(timeout = 10.seconds) {
        val op = newRuntime().load(WasmKernelFixtures.HIGH_BIT_RESULT_POINTER)
        assertFailsWith<WasmExecutionException> { op.invoke(ByteArray(0)) }
    }

    @Test
    public fun resultLengthWithHighBitSetIsBounded(): TestResult = runTest(timeout = 10.seconds) {
        val op = newRuntime().load(WasmKernelFixtures.HIGH_BIT_RESULT_LENGTH)
        assertFailsWith<WasmExecutionException> { op.invoke(ByteArray(0)) }
    }

    @Test
    public fun allocPointerWithHighBitSetIsBounded(): TestResult = runTest(timeout = 10.seconds) {
        val op = newRuntime().load(WasmKernelFixtures.HIGH_BIT_ALLOC_POINTER)
        assertFailsWith<WasmExecutionException> { op.invoke(byteArrayOf(1, 2, 3, 4)) }
    }
}
