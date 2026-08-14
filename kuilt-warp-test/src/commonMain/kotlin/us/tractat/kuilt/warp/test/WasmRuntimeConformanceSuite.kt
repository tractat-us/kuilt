package us.tractat.kuilt.warp.test

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.WasmException
import us.tractat.kuilt.warp.WasmExecutionException
import us.tractat.kuilt.warp.WasmLoadException
import us.tractat.kuilt.warp.WasmRuntime
import us.tractat.kuilt.warp.WasmSandboxConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
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
 *   reject-no-max is a sufficient memory ceiling), while a grow *within* it succeeds and the
 *   bound moves with it (see [resultWrittenIntoNewlyGrownMemoryIsReadBack]);
 * - guest-controlled ABI words (alloc pointer, packed result pointer/length) with the high
 *   bit set are bounds-rejected, never sign-wrapped into host-memory access — **and so is a
 *   window that sets no high bit at all** and simply runs off the end
 *   (see [resultWindowPastMemoryEndIsBounded], which is what separates a real bounds check
 *   from a sign check).
 *
 * **Load-phase execution bound** — a module's `(start)` function runs at instantiation,
 * before any ABI call, so the invocation budget alone cannot bound it. The contract is
 * phase-agnostic: whether an impl runs `(start)` eagerly under a bounded `load` (surfacing
 * [WasmLoadException]) or defers instantiation to the first bounded invocation (surfacing
 * [WasmExecutionException]), a `(start)` CPU bomb must fail terminally near the budget —
 * never hang the host (see [startSectionCpuBombIsBoundedNotHung]).
 *
 * The CPU-bomb vectors burn REAL wall-clock CPU: the sandbox budget is dropped to 250 ms so a
 * non-conforming impl fails fast instead of wedging the test host. The `runTest` ceilings behind
 * that budget are *wedge backstops*, not measurements — a real-millisecond ceiling over real CPU
 * work measures the host as much as the code (#1739) — so they are sized with slack and must not
 * be tightened to "fail faster"; the impl's own budget is the fast detector.
 */
public abstract class WasmRuntimeConformanceSuite {

    /** A fresh runtime under test, honouring [config]. */
    public abstract fun newRuntime(config: WasmSandboxConfig): WasmRuntime

    private fun newRuntime(): WasmRuntime = newRuntime(WasmSandboxConfig())

    // ── Happy path — the guards must not over-reject a well-behaved kernel ──────────────────

    @Test
    public fun wellBehavedKernelRoundTrips(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val op = newRuntime().load(WasmKernelFixtures.REVERSE)
        assertContentEquals(byteArrayOf(4, 3, 2, 1), op.invoke(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    public fun emptyInputReturnsEmpty(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val op = newRuntime().load(WasmKernelFixtures.REVERSE)
        assertContentEquals(byteArrayOf(), op.invoke(byteArrayOf()))
    }

    @Test
    public fun repeatInvokeReusesTheInstance(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
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
    public fun trapSurfacesAsExecutionException(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
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
    public fun cpuBombIsBoundedByExecutionTimeout(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
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
     * an impl may run `(start)` under a bounded `load` (JVM, native: a load-time
     * [WasmLoadException]) or defer instantiation to the first bounded invocation (browser:
     * a run-time [WasmExecutionException]) — so the vector drives load + first invoke
     * together and accepts either [WasmException] arm, as long as it terminates near the
     * budget and names it.
     */
    @Test
    public fun startSectionCpuBombIsBoundedNotHung(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
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
     *
     * ## Why the well-behaved arm asserts eventual success (#1739)
     *
     * Both arms share one runtime and therefore one [WasmSandboxConfig.executionTimeout]. The
     * runaway arm needs it *tight* (250 ms) or a non-conforming impl burns the test host; the
     * well-behaved arm needs it *generous*, because reversing three bytes costs microseconds of
     * guest work but its deadline also covers being scheduled onto a CPU. The well-behaved arm
     * lost that argument twice on 2026-07-27, false-timing-out at 1-minute load averages of 41
     * and 71.6 and passing in isolation both times — a budget on the *innocent* op asserts
     * "this host is not busy", not "the runtime recovered".
     *
     * Since one config cannot be both tight and generous, the scenario is retried instead —
     * and **only** on a budget overrun. Nothing this test protects is weakened, but the reason
     * is *not* that a poisoned runtime reads differently: an unreset deadline is mapped to an
     * overrun message by at least one impl. It is that (i) wrong bytes and a non-firing
     * `assertFailsWith` never reach the retry (they are [AssertionError]s, not
     * [WasmExecutionException]s), (ii) a trap, an interrupt or a dead worker carries different
     * message text, and (iii) any real recovery failure is **persistent**, so every attempt
     * overruns and the bounded retry still fails. See [retryingOnlyBudgetOverruns] for the
     * ordered argument and the one class it deliberately absorbs (#1802).
     *
     * One runtime is reused across attempts deliberately: each extra attempt puts two more
     * timeouts in front of the well-behaved invoke, which strengthens the recovery assertion,
     * and a common-code suite cannot close a [WasmRuntime] to reclaim a discarded one.
     */
    @Test
    public fun timeoutDoesNotPoisonSubsequentInvokes(): TestResult = runTest(timeout = WEDGE_BACKSTOP) {
        // One literal, used both to configure the runtime and to name the budget in the failure
        // message — editing them apart would make the diagnosis lie about what it diagnosed.
        val budget = 250.milliseconds
        val bounded = newRuntime(WasmSandboxConfig(executionTimeout = budget))
        val runaway = bounded.load(WasmKernelFixtures.CPU_BOMB)
        val reverse = bounded.load(WasmKernelFixtures.REVERSE)
        retryingOnlyBudgetOverruns(
            what = "a well-behaved op on a runtime that has just recovered from a timeout",
            budget = budget,
            referenceInvoke = {
                newRuntime(WasmSandboxConfig(executionTimeout = REFERENCE_BUDGET))
                    .load(WasmKernelFixtures.REVERSE)
                    .invoke(byteArrayOf(1, 2, 3))
            },
        ) {
            assertFailsWith<WasmExecutionException> { runaway.invoke(ByteArray(0)) }
            assertFailsWith<WasmExecutionException>("recovered guest must time out again") {
                runaway.invoke(ByteArray(0))
            }
            assertContentEquals(byteArrayOf(3, 2, 1), reverse.invoke(byteArrayOf(1, 2, 3)))
        }
    }

    /**
     * Evidence that reject-no-max is a sufficient memory ceiling (#978): the engine must
     * enforce a module's DECLARED max at grow time. The kernel converts a denied
     * `memory.grow` into `unreachable`; if the engine did NOT honour the declared max the
     * grow would succeed, no trap would fire, and this test would fail — the signal that
     * load-time rejection alone does not bound that target's memory.
     */
    @Test
    public fun growPastDeclaredMaxTraps(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val op = newRuntime().load(WasmKernelFixtures.GROW_PAST_DECLARED_MAX)
        assertFailsWith<WasmExecutionException> { op.invoke(ByteArray(0)) }
    }

    // ── Guest-controlled ABI words (the OOB sandbox-escape vectors) ──────────────────────────

    @Test
    public fun resultPointerWithHighBitSetIsBounded(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val op = newRuntime().load(WasmKernelFixtures.HIGH_BIT_RESULT_POINTER)
        assertFailsWith<WasmExecutionException> { op.invoke(ByteArray(0)) }
    }

    @Test
    public fun resultLengthWithHighBitSetIsBounded(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val op = newRuntime().load(WasmKernelFixtures.HIGH_BIT_RESULT_LENGTH)
        assertFailsWith<WasmExecutionException> { op.invoke(ByteArray(0)) }
    }

    @Test
    public fun allocPointerWithHighBitSetIsBounded(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val op = newRuntime().load(WasmKernelFixtures.HIGH_BIT_ALLOC_POINTER)
        assertFailsWith<WasmExecutionException> { op.invoke(byteArrayOf(1, 2, 3, 4)) }
    }

    /**
     * A result window that is **in range and past the end** — an ordinary positive pointer
     * addressing real linear memory, whose `ptr + len` leaves it — is bounds-rejected.
     *
     * ### Why the three vectors above do not already cover this
     *
     * They all fail the same way: **bit 31 set**. An implementation whose entire guard is
     * `if (word < 0) reject` passes every one of them, and then reads 94 bytes past the end of
     * linear memory on `(resPtr = 65530, resLen = 100)` — a benign-looking pair no fixture in
     * [WasmKernelFixtures] could produce before this one. A bounds-check suite in which every
     * out-of-bounds vector happens to set the sign bit cannot tell a real bounds check from a
     * sign check, and this is a sandbox boundary: it is the thing that stops code another peer
     * sent you from reaching memory it should not.
     *
     * The in-range-past-end case *is* covered one layer down, by `WarpAbiResultTest` over
     * `requireInBounds` in `:kuilt-warp`. That proves the shared decoder is correct; it does not
     * prove any given runtime routes through it, which is precisely what every impl's KDoc
     * promises ("never a hand-rolled unpack") with nothing behind it.
     *
     * Three preconditions, then the claim. **1** neither ABI word has bit 31 set, so a sign-only
     * guard cannot be what rejects this; **2** the pointer itself addresses real memory, so a
     * guard that only clamps the pointer cannot be either; **3** the window really does leave
     * memory. They run **first**, and a failure among them ends the test before the claim, because
     * a claim about the wrong vector is not evidence. Then **4**: the impl surfaces
     * [WasmExecutionException] — a guest runtime fault, never an OOB host access and never a raw
     * error escaping the sealed hierarchy.
     *
     * 1–3 are the fixture's own preconditions, restated here so a reader of the property can see
     * what makes it different from its siblings; [InRangePastEndVector] makes a fixture that fails
     * them unconstructible, and cross-checks all three against the module bytes.
     *
     * **Mutation receipts**, measured on this branch — each mutation applied alone, reverted, and
     * read out of the results XML. Backends: `ChicoryWasmRuntimeConformanceTest` (JVM, `jvmTest`)
     * and `Wasm3WasmRuntimeConformanceTest` (native, `macosArm64Test`).
     *
     * | Mutation | Reds here | Reds elsewhere |
     * |---|---|---|
     * | `requireInBounds` → sign-only (`ptr < 0 \|\| len < 0`) — `:kuilt-warp` | **native only** | 4 `WarpAbiResultTest` cases; no other conformance property, on either backend |
     * | `requireInBounds` → sign-only, and drop the length ceiling too | **native only** | as above |
     * | **Fixture:** move `resultPointer` into the high-bit region | assertion **1**, at construction | nothing |
     * | **Fixture:** shrink `resultLength` so the window fits | assertion **3**, at construction | nothing |
     *
     * **The first row is the finding, and its "native only" is the honest half.** On wasm3 the
     * decode ends in raw pointer arithmetic (`base[ptr + i]` over `m3_GetMemory`), so
     * `requireInBounds` is the *only* thing between a guest word and host memory: sign-only makes
     * this property red and every sibling vector stay green, which is the whole claim of #2314
     * demonstrated in one measurement. On Chicory it stays green, because Chicory's `Memory` API
     * re-checks the narrowed index natively and throws, which the runtime wraps — the guard is
     * layered there, and this property proves the *composite* rather than the outer check. Said
     * plainly rather than left as an absence: **on the JVM backend, no mutation of the shared
     * decoder alone reds this test.** What it holds on that backend is the contract term — the
     * fault surfaces as [WasmExecutionException] rather than a hang or a raw engine error — and
     * what it holds for the *next* backend is everything, because a new impl inherits wasm3's
     * shape (a raw memory view) far more often than Chicory's.
     *
     * The two fixture rows are the vacuity guards, and they are the mutations a fixture author
     * commits by accident: both fail *before* any assertion runs, at
     * [InRangePastEndVector]'s construction, rather than passing quietly.
     */
    @Test
    public fun resultWindowPastMemoryEndIsBounded(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val vector = WasmKernelFixtures.OOB_IN_RANGE_RESULT
        val op = newRuntime().load(vector.bytes)
        assertAll(
            {
                assertTrue(
                    vector.resultPointer < ABI_WORD_SIGN_BIT && vector.resultLength < ABI_WORD_SIGN_BIT,
                    "neither ABI word may set bit 31, or a sign-only guard rejects this vector for " +
                        "the wrong reason and it restates its three siblings — " +
                        "${vector.resultPointer} / ${vector.resultLength}",
                )
            },
            {
                assertTrue(
                    vector.resultPointer in 1 until vector.memoryBytes,
                    "the pointer must address real linear memory, or a guard that only clamps the " +
                        "pointer catches this too — ${vector.resultPointer} against ${vector.memoryBytes}",
                )
            },
            {
                assertTrue(
                    vector.resultPointer + vector.resultLength > vector.memoryBytes,
                    "and the window must leave it, or there is nothing out of bounds here — " +
                        "${vector.resultPointer + vector.resultLength} against ${vector.memoryBytes}",
                )
            },
        )
        assertFailsWith<WasmExecutionException>(
            "an in-range pointer whose window runs past the memory end is a bounds violation like " +
                "any other, and must surface as a guest runtime fault",
        ) { op.invoke(ByteArray(0)) }
    }

    /**
     * A guest that **successfully** grows linear memory mid-call and writes its result into the
     * new region gets those exact bytes back.
     *
     * ### Why a suite of refusals needs one success
     *
     * Every other loadable vector either never calls `memory.grow` or grows past its declared max
     * and traps ([growPastDeclaredMaxTraps]), so no property here has ever asked an implementation
     * to notice that the bound **moved**. A suite that only drives refusals is satisfied by an
     * implementation that refuses everything — and more to the point, the failure a legitimate
     * grow provokes is *silent corruption*, not an exception: a `ByteBuffer` whose backing store
     * moved on the JVM, a detached `ArrayBuffer` in the browser, a base pointer from
     * `m3_GetMemory` that a `realloc` invalidated on wasm3. All three shipped impls re-fetch after
     * the guest call and each says so in a comment; nothing held them to it.
     *
     * So this property asserts **content**, not absence of an exception. A host reading through a
     * stale handle either throws (which reds) or hands back other bytes (which also reds).
     *
     * ### How it proves its own rig fired
     *
     * "A grow happened" is not observable through [us.tractat.kuilt.warp.Op] — the result is
     * bytes — and a vector that quietly stopped growing would still return *something*, in bounds,
     * and pass. The kernel therefore writes the result pointer into the first four bytes of its own
     * result, and assertion 3 demands that pointer sit at or above the pre-grow memory end. It is a
     * self-report rather than a claim because the host read those bytes *from that address*. The
     * kernel also traps on a denied grow instead of falling through, so an engine that refuses the
     * grow reds rather than reverting this vector to a plain in-bounds read.
     *
     * Four assertions: **1** the result is the size the kernel promises; **2** the marker bytes
     * survive the round trip — the content claim; **3** the result was written at or above the
     * pre-grow memory end — the rig-fired claim; **4** and within the grown memory, so the
     * self-report is a real address rather than an arbitrary number.
     *
     * **Mutation receipts**, measured on this branch — each applied alone and reverted. Backends
     * as above.
     *
     * | Mutation | Reds here | Reds elsewhere |
     * |---|---|---|
     * | Read linear-memory size **once at load** instead of after each guest call — `ChicoryWasmRuntime.runAbi` | **2, 3** (as a bounds rejection) | nothing else in `jvmTest` |
     * | The same — `Wasm3WasmRuntime.memoryBaseFor` | **2, 3** | nothing else in `macosArm64Test` |
     * | **Fixture:** write the result at offset 0 instead of the new page | **3 only** | nothing |
     *
     * **Row 3 is the one that matters day to day**: it is the fixture edit that turns this back
     * into an ordinary round-trip property, and assertion 3 is the only thing that notices.
     *
     * **What this cannot detect, said plainly.** It cannot distinguish an impl that re-fetches its
     * memory handle from one that never cached a stale handle to begin with — both pass, and
     * "re-fetch" is an implementation strategy rather than a contract term. Nor can it see a stale
     * *base* that a grow-in-place left valid; that is unobservable from outside by construction.
     * See [GrowThenWriteVector].
     */
    @Test
    public fun resultWrittenIntoNewlyGrownMemoryIsReadBack(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val vector = WasmKernelFixtures.GROW_THEN_WRITE
        val result = newRuntime().load(vector.bytes).invoke(ByteArray(0))
        assertAll(
            {
                assertEquals(
                    vector.resultSize,
                    result.size,
                    "the kernel returns a self-reported pointer plus its marker; a different size " +
                        "means it never reached its return, so nothing below is readable",
                )
            },
            {
                assertContentEquals(
                    vector.marker,
                    vector.markerOf(result),
                    "the marker must survive the round trip — a host reading through a handle the " +
                        "grow invalidated hands back other bytes, and that is silent corruption " +
                        "rather than an exception",
                )
            },
            {
                assertTrue(
                    vector.reportedPointer(result) >= vector.memoryEndBeforeGrow,
                    "the result must have been written at or above the PRE-GROW memory end, or this " +
                        "vector never exercised newly-grown memory and is an ordinary round trip — " +
                        "${vector.reportedPointer(result)} against ${vector.memoryEndBeforeGrow}",
                )
            },
            {
                assertTrue(
                    vector.reportedPointer(result) + vector.resultSize <= vector.memoryEndAfterGrow,
                    "and inside the grown memory, or the self-reported pointer is an arbitrary " +
                        "number rather than the address these bytes came from — " +
                        "${vector.reportedPointer(result)} against ${vector.memoryEndAfterGrow}",
                )
            },
        )
    }

    private companion object {
        /**
         * The `runTest` ceiling for [timeoutDoesNotPoisonSubsequentInvokes]. A wedge backstop only:
         * it catches an impl that hangs where its own [WasmSandboxConfig.executionTimeout] should
         * have fired, and it has to cover several retried attempts on a contended host, so it is
         * deliberately far larger than the work it bounds (#1739).
         */
        val WEDGE_BACKSTOP: Duration = 60.seconds

        /**
         * The generous budget the exhaustion-path reference invocation runs under. Strictly smaller
         * than [WEDGE_BACKSTOP], and by a wide margin: the retried attempts have already spent part
         * of that ceiling by the time the reference runs, so a reference sharing the ceiling's value
         * could never complete before `runTest` fired — yielding an opaque timeout in place of the
         * diagnostic report. Five seconds is ~1400x the measured reference cost on an idle host.
         */
        val REFERENCE_BUDGET: Duration = 5.seconds
    }
}
