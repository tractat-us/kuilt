package us.tractat.kuilt.warp

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.UnlinkableException
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.MemoryLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.TimeoutException

/**
 * JVM implementation of [WasmRuntime] backed by the Chicory pure-JVM interpreter.
 *
 * [load] parses and instantiates the module once; the returned [Op] drives every invocation
 * over shared linear memory via the warp ABI:
 * - `warp_alloc(len: i32) -> i32`   — guest returns a writable pointer for `len` bytes.
 * - `warp_run(ptr: i32, len: i32) -> i64` — guest processes `memory[ptr..ptr+len)` and
 *   returns a packed pointer/length: `(resPtr.toLong() shl 32) or (resLen.toLong() and 0xFFFF_FFFF)`.
 *
 * **Sandbox guards.**
 *
 * *Load-time (Task 3):*
 * - *Import rejection* — no [withImportValues] is provided; any declared import causes
 *   Chicory's [Instance.builder] to throw [UnlinkableException], caught and rethrown as
 *   [WasmLoadException].
 * - *Memory ceiling* — the uniform declared-memory contract (see [WasmRuntime]): the declared
 *   initial and max page counts are checked against [WasmSandboxConfig.maxMemoryPages] before
 *   build, and a module declaring memory with **no explicit max** is rejected outright —
 *   matching the native and browser runtimes, which cannot clamp a compiled module's limits.
 *   No runtime clamp is applied: every loaded module carries a bounded declared max that
 *   Chicory itself enforces at `memory.grow` time. (The previous clamp was not just
 *   redundant — it *widened* a module's declared max up to the cap, silently granting more
 *   memory than the kernel declared.)
 * - *Load-phase execution bound* — instantiation itself is guest execution: a module's
 *   `(start)` function runs inside `Instance.builder(...).build()`. The build therefore runs on
 *   the same timed guest executor as every invocation, bounded by
 *   [WasmSandboxConfig.executionTimeout]; a CPU-bomb `(start)` is interrupted and surfaces as a
 *   terminal [WasmLoadException] naming the exceeded budget, never a hung `load()`.
 * - *Total load-failure containment* — **every** [ChicoryException] is converted to a
 *   [WasmLoadException]: build-time validation / trapping-`(start)` failures, a module that
 *   exports no memory, and a missing `warp_alloc`/`warp_run` ABI export (Chicory's
 *   `InvalidException`). A raw `ChicoryException` is **not** a [WasmException], so any that
 *   escaped `load` would bypass the executor's terminal-error handling and trigger an
 *   anti-entropy retry storm on a verified-but-broken kernel (a remotely-triggerable DoS).
 *
 * *Run-time (Task 4) — the CPU-bomb defense:*
 * - Every guest interaction (alloc, memory write, `warp_run`, result read) runs on a dedicated
 *   single-thread executor owned by this runtime. A malicious kernel that loops forever in
 *   *any* guest function is bounded by [WasmSandboxConfig.executionTimeout]: on timeout the task
 *   is cancelled with `interrupt = true`, which sets the worker thread's interrupt flag.
 * - **Interpreter-only — never call `withMachineFactory`.** Chicory's interpreter
 *   ([com.dylibso.chicory.runtime.InterpreterMachine], the default machine factory) checks
 *   `Thread.isInterrupted()` at every function-call entry and every backward branch, throwing
 *   [com.dylibso.chicory.runtime.ChicoryInterruptedException]. The AOT machine factory emits
 *   bytecode *without* these checks, which would silently defeat the timeout — so we rely on the
 *   default interpreter and must not opt into AOT. Since unbounded CPU in wasm requires a loop or
 *   recursion (straight-line code over a finite code section always terminates), and those are
 *   exactly the checked sites, the interrupt fully bounds the threat.
 * - Any [ChicoryException] from a guest call (trap, `unreachable`, OOB memory, bad packed result,
 *   or the interrupt itself) surfaces as [WasmExecutionException], preserving the cause.
 *
 * **Deterministic testing via [TimedGuestRunner]:**
 * The [timedRunner] seam replaces the real [GuestWorker] so tests can drive timeout/success
 * behaviour without real wall-clock waits. Production callers omit it (or pass `null`) to get the
 * real worker-backed runner; tests inject a fake. See [ChicoryWasmRuntimeTimingTest] for the
 * false-timeout regression proof. ([GuestWorker]'s own post-timeout drain is pinned deterministically
 * by [GuestWorkerDrainTest], which fakes the worker one level lower.)
 *
 * Construct once and reuse across loads/invokes; call [close] to release the executor thread.
 *
 * @param config Sandbox configuration (memory cap, execution timeout). Must be valid per
 *   [WasmSandboxConfig] constraints.
 * @param timedRunner Override for the timed guest invocation strategy. Pass `null` (default) for
 *   the real wall-clock runner backed by [guestWorker]. Inject a fake for deterministic tests.
 */
public class ChicoryWasmRuntime(
    public val config: WasmSandboxConfig = WasmSandboxConfig(),
    timedRunner: TimedGuestRunner? = null,
) : WasmRuntime, AutoCloseable {

    /**
     * The single-thread worker that runs every guest call. One worker per runtime, reused across
     * invocations: the Chicory instance is single-threaded, so all guest access is confined to one
     * thread at a time. A timed-out invocation interrupts the worker; the executor clears the stale
     * interrupt before the next task, so a timeout does not poison the next invoke.
     *
     * Owned unconditionally, and shut down by [close], whether or not a test injected a
     * [timedRunner] over it.
     */
    private val guestWorker = GuestWorker(drainGrace = config.executionTimeout)

    /**
     * The strategy for running a task under a timeout. Defaults to [guestWorker], the real
     * wall-clock runner; tests inject a fake for deterministic control.
     *
     * Initialized after [guestWorker] so the default can reference it.
     */
    private val timedRunner: TimedGuestRunner = timedRunner ?: guestWorker

    /**
     * Serializes the timed submit+get of every guest invocation. One worker serves all loaded
     * ops, and a [WarpNode] runs owned tasks concurrently over one shared runtime, so without this
     * an op submitted while the worker is busy would have its [WasmSandboxConfig.executionTimeout]
     * clock consumed by *queue wait* — a concurrent innocent task could be cancelled before it ever
     * ran, recording a spurious terminal failure. Holding this for the whole submit+get makes the
     * timeout measure actual execution, not queueing: a waiting op gets its full budget from the
     * moment it starts. (Latency-serialized per runtime; guest calls are short by design. A future
     * perf step could give each instance its own worker thread for true parallelism.)
     */
    private val invokeMutex = Mutex()

    override fun load(bytes: ByteArray): Op {
        val module = parseModule(bytes)
        rejectUnboundedOrOversizeMemory(module)
        val instance = buildInstance(module)
        val memory = instance.memory()
            ?: throw WasmLoadException("module exports no memory")
        val allocFn = exportOrThrow(instance, "warp_alloc")
        val runFn = exportOrThrow(instance, "warp_run")
        return Op { args -> invoke(memory, allocFn, runFn, args) }
    }

    /**
     * Resolves a required ABI export, converting Chicory's raw [ChicoryException] (an
     * `InvalidException` "Unknown export…" for a missing export) into a terminal
     * [WasmLoadException]. Without this, a well-formed module that simply omits an ABI export
     * would throw a non-[WasmException] that escapes the executor's terminal-error handling and
     * triggers an anti-entropy retry storm.
     */
    private fun exportOrThrow(instance: Instance, name: String): ExportFunction =
        try {
            instance.export(name)
        } catch (e: ChicoryException) {
            throw WasmLoadException("missing ABI export $name", e)
        }

    /** Shuts down the dedicated guest-execution thread. The runtime is unusable afterwards. */
    override fun close() {
        guestWorker.close()
    }

    private fun parseModule(bytes: ByteArray): WasmModule =
        try {
            Parser.parse(bytes)
        } catch (e: ChicoryException) {
            throw WasmLoadException("malformed WASM module: ${e.message}", e)
        }

    /**
     * Enforces the uniform declared-memory contract (see [WasmRuntime]) on the module's declared
     * limits before build. A module with no memory section passes here — the missing warp-ABI
     * memory is caught after instantiation ("module exports no memory").
     *
     * Policy (checked in this order, matching the native and browser runtimes):
     * - A declared initial exceeding [WasmSandboxConfig.maxMemoryPages] is rejected.
     * - A missing declared max — [MemoryLimits.MAX_PAGES] (65536) is Chicory's sentinel for "no
     *   max declared in the binary" — is rejected: an unbounded module could `memory.grow` to
     *   ~4 GiB (a memory-bomb DoS), and the browser runtime cannot clamp post-compile, so the
     *   only contract enforceable identically everywhere is a required bounded max.
     * - An explicit max exceeding the cap is rejected.
     *
     * No [MemoryLimits] clamp is applied to the instance: the surviving declared max is bounded
     * and Chicory enforces it at `memory.grow` time. (The previous clamp-to-cap silently
     * *widened* a declared max below the cap — a kernel that declared 1 page could grow to 16.)
     */
    private fun rejectUnboundedOrOversizeMemory(module: WasmModule) {
        val memSection = module.memorySection().orElse(null) ?: return
        if (memSection.memoryCount() == 0) return
        val limits = memSection.getMemory(0).limits()
        val initial = limits.initialPages()
        if (initial > config.maxMemoryPages) {
            throw WasmLoadException(
                "module initial memory $initial pages exceeds sandbox cap ${config.maxMemoryPages} pages",
            )
        }
        val declaredMax = limits.maximumPages()
        if (declaredMax == MemoryLimits.MAX_PAGES) {
            throw WasmLoadException(
                "module declares memory with no explicit max (unbounded growth not allowed); " +
                    "declare a max <= ${config.maxMemoryPages} pages",
            )
        }
        if (declaredMax > config.maxMemoryPages) {
            throw WasmLoadException(
                "module memory exceeds sandbox cap: declared max $declaredMax pages > ${config.maxMemoryPages} pages",
            )
        }
    }

    /**
     * Instantiates the module **on the timed guest executor** — instantiation is guest
     * execution: a module's `(start)` function runs inside `build()`, so building on the
     * caller thread would let a CPU-bomb `(start)` hang `load()` outside any budget (the
     * load-phase sibling of the invoke CPU-bomb defense, #1290/#1298). A `(start)` exceeding
     * [WasmSandboxConfig.executionTimeout] interrupts the worker (the interpreter terminates
     * the runaway at the next backward branch / call entry) and `load` fails terminally with a
     * [WasmLoadException] naming the exceeded budget.
     *
     * The budget here includes any queue wait behind a concurrent invocation on the shared
     * worker — `load` is a plain blocking call, not serialized by the (suspending) invoke
     * mutex. Guest calls are short by design, so the skew is bounded; the failure mode is a
     * spurious terminal rejection, never a hang.
     */
    private fun buildInstance(module: WasmModule): Instance {
        var built: Instance? = null
        try {
            // No withMachineFactory(...): the default InterpreterMachine is required so the
            // execution-time interrupt checks fire (see class KDoc — interpreter-only).
            // No withMemoryLimits(...): the declared limits were validated above and the
            // engine enforces them natively.
            timedRunner.run(
                config.executionTimeout,
                Callable {
                    built = Instance.builder(module).build()
                    NO_RESULT
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutException) {
            throw WasmLoadException(
                "module instantiation exceeded ${config.executionTimeout} ((start)-section execution budget)",
                e,
            )
        } catch (e: UnlinkableException) {
            throw WasmLoadException("module capability violation (imports not allowed): ${e.message}", e)
        } catch (e: ChicoryException) {
            // Any other build-time failure — validation (InvalidException), a trapping (start)
            // function (UninstantiableException), etc. — is also a terminal load failure, never a
            // raw ChicoryException that escapes the executor's WasmException handling.
            throw WasmLoadException("module failed to instantiate: ${e.message}", e)
        }
        return checkNotNull(built) { "timed runner returned without building an instance" }
    }

    /**
     * Runs one ABI round-trip under the execution-time bound.
     *
     * The blocking work (real-executor submit + `Future.get(timeout)`) is **real wall-clock work**
     * — the guest burns real CPU on the dedicated worker thread — so it deliberately runs on
     * [Dispatchers.IO], a real blocking context, NOT the caller's (possibly virtual-time)
     * scheduler. This is the sanctioned real-threading exception to the no-production-dispatcher
     * rule: the timeout is a wall-clock CPU bound and cannot be driven by virtual time.
     * [Dispatchers.IO] only *waits*; the guest itself runs on [guestWorker], whose interrupt
     * flag is what terminates a runaway kernel.
     *
     * Tests inject a [TimedGuestRunner] fake; [Dispatchers.IO] is still used, but the fake
     * executes the callable synchronously (no blocking) so the switch is cheap and harmless.
     */
    private suspend fun invoke(
        memory: Memory,
        allocFn: ExportFunction,
        runFn: ExportFunction,
        args: ByteArray,
    ): ByteArray = withContext(Dispatchers.IO) {
        // The whole timedRunner.run() call is the critical section: only one guest call is timed
        // at a time, so the timeout measures execution, not time spent queued behind another op
        // (see invokeMutex KDoc). withLock is cancellation-cooperative.
        invokeMutex.withLock {
            try {
                timedRunner.run(config.executionTimeout, Callable { runAbi(memory, allocFn, runFn, args) })
            } catch (e: CancellationException) {
                // Structured-concurrency cancellation must propagate, never be swallowed into a
                // WasmExecutionException (the same guard BrowserWasmRuntime.compileModule keeps).
                throw e
            } catch (e: WasmException) {
                // Already terminal — the common decoder's bounds rejection ([requireInBounds])
                // arrives as a WasmExecutionException; rethrow rather than double-wrap.
                throw e
            } catch (e: TimeoutException) {
                throw WasmExecutionException("WASM execution exceeded ${config.executionTimeout}", e)
            } catch (e: ChicoryException) {
                // A trap / unreachable / OOB / bad packed result / interrupt — thrown directly by
                // the real runner (unwrapped from ExecutionException) or by a test fake's task.call().
                throw WasmExecutionException("WASM kernel trapped: ${e.message}", e)
            } catch (e: Exception) {
                throw WasmExecutionException("WASM kernel failed: ${e.message}", e)
            }
        }
    }

    private companion object {
        /** Sentinel for a timed task run for its side effect (instantiation), not for bytes. */
        val NO_RESULT: ByteArray = ByteArray(0)
    }

    /**
     * The ABI marshalling, executed entirely on [guestWorker]'s thread so all guest access is bounded.
     *
     * The `warp_alloc` return and the packed `warp_run` result are fully guest-controlled
     * `i32`/`i64` words, decoded exclusively through the common safe decoder ([requireInBounds] /
     * [decodeWarpResult]) as **unsigned** [Long]s — never a hand-rolled unpack, whose signed
     * [Int] narrowing is exactly the sandbox-escape class the common decoder exists to prevent
     * (see [GuestRegion]). Only after validation are the words narrowed for Chicory's
     * [Int]-addressed [Memory] API, which then re-checks natively.
     */
    private fun runAbi(
        memory: Memory,
        allocFn: ExportFunction,
        runFn: ExportFunction,
        args: ByteArray,
    ): ByteArray {
        val argPtr = allocFn.apply(args.size.toLong())[0] and 0xFFFF_FFFFL
        requireInBounds(argPtr, args.size.toLong(), memorySizeOf(memory))
        memory.write(argPtr.toInt(), args)
        val packed = runFn.apply(argPtr, args.size.toLong())[0]
        return decodeWarpResult(packed, memorySizeOf(memory)) { ptr, len ->
            memory.readBytes(ptr.toInt(), len.toInt())
        }
    }

    /**
     * The *current* linear-memory size in bytes — fetched after each guest call, which may have
     * grown memory (up to its validated declared max).
     */
    private fun memorySizeOf(memory: Memory): Long = memory.pages().toLong() * Memory.PAGE_SIZE
}
