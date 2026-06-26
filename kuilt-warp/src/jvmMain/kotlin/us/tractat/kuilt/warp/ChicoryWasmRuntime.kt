package us.tractat.kuilt.warp

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.UnlinkableException
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.MemoryLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * - *Memory cap* — the declared initial and max page counts are checked against
 *   [WasmSandboxConfig.maxMemoryPages] before build, and the runtime limit is clamped.
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
 * The [timedRunner] seam replaces the real `guestExecutor.submit + Future.get(timeout)` so tests
 * can drive timeout/success behaviour without real wall-clock waits. Production callers omit it
 * (or pass `null`) to get the real executor-backed runner; tests inject a fake. See
 * [ChicoryWasmRuntimeTimingTest] for the false-timeout regression proof.
 *
 * Construct once and reuse across loads/invokes; call [close] to release the executor thread.
 *
 * @param config Sandbox configuration (memory cap, execution timeout). Must be valid per
 *   [WasmSandboxConfig] constraints.
 * @param timedRunner Override for the timed guest invocation strategy. Pass `null` (default) for
 *   the real wall-clock runner backed by [guestExecutor]. Inject a fake for deterministic tests.
 */
public class ChicoryWasmRuntime(
    public val config: WasmSandboxConfig = WasmSandboxConfig(),
    timedRunner: TimedGuestRunner? = null,
) : WasmRuntime, AutoCloseable {

    /**
     * Single-thread executor that runs every guest call. One worker per runtime, reused across
     * invocations: the Chicory instance is single-threaded, so all guest access is confined to
     * this one thread. A timed-out invocation interrupts the worker; the executor clears the
     * stale interrupt before the next task, so a timeout does not poison the next invoke.
     */
    private val guestExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "warp-wasm-guest").apply { isDaemon = true }
    }

    /**
     * The strategy for running a task under a timeout. Defaults to the real wall-clock runner
     * backed by [guestExecutor]; tests inject a fake for deterministic control.
     *
     * The real default: submits the callable to [guestExecutor], calls `Future.get(timeout)`, and
     * on [TimeoutException] interrupts the worker (so Chicory's interpreter terminates the runaway
     * guest at the next function-call entry / backward branch). The [ExecutionException] wrapper
     * from the executor is unwrapped before rethrowing so callers see the original exception type.
     *
     * Initialized after [guestExecutor] so the default lambda can safely capture it.
     */
    private val timedRunner: TimedGuestRunner = timedRunner ?: TimedGuestRunner { timeout, task ->
        val future = guestExecutor.submit(task)
        try {
            future.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Interrupt the worker; Chicory's interpreter throws ChicoryInterruptedException at
            // the next call entry / backward branch, terminating the runaway guest.
            future.cancel(true)
            throw e
        } catch (e: ExecutionException) {
            // Unwrap so invoke sees the original exception (ChicoryException, etc.), not a wrapper.
            throw e.cause ?: e
        }
    }

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
        val memLimits = resolvedMemoryLimits(module)
        val instance = buildInstance(module, memLimits)
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
        guestExecutor.shutdownNow()
    }

    private fun parseModule(bytes: ByteArray): WasmModule =
        try {
            Parser.parse(bytes)
        } catch (e: ChicoryException) {
            throw WasmLoadException("malformed WASM module: ${e.message}", e)
        }

    /**
     * Returns [MemoryLimits] to apply via [Instance.Builder.withMemoryLimits], or null if the
     * module declares no memory section.
     *
     * Policy:
     * - If the declared initial exceeds [WasmSandboxConfig.maxMemoryPages], reject immediately.
     *   This guards against `MemoryLimits(initial, cap)` throwing Chicory's raw `InvalidException`
     *   ("size minimum must not be greater than maximum") when initial > cap.
     * - [MemoryLimits.MAX_PAGES] (65536) is Chicory's sentinel for "no max declared in the
     *   binary". Such a module is allowed — its maximum is clamped to [WasmSandboxConfig.maxMemoryPages].
     * - Any other (explicit) max that exceeds the cap is rejected immediately.
     */
    private fun resolvedMemoryLimits(module: WasmModule): MemoryLimits? {
        val memSection = module.memorySection().orElse(null) ?: return null
        if (memSection.memoryCount() == 0) return null
        val limits = memSection.getMemory(0).limits()
        val initial = limits.initialPages()
        if (initial > config.maxMemoryPages) {
            throw WasmLoadException(
                "module initial memory $initial pages exceeds sandbox cap ${config.maxMemoryPages} pages",
            )
        }
        val declaredMax = limits.maximumPages()
        if (declaredMax != MemoryLimits.MAX_PAGES && declaredMax > config.maxMemoryPages) {
            throw WasmLoadException(
                "module memory exceeds sandbox cap: declared max $declaredMax pages > ${config.maxMemoryPages} pages",
            )
        }
        return MemoryLimits(initial, config.maxMemoryPages)
    }

    private fun buildInstance(module: WasmModule, memLimits: MemoryLimits?): Instance =
        try {
            // No withMachineFactory(...): the default InterpreterMachine is required so the
            // execution-time interrupt checks fire (see class KDoc — interpreter-only).
            Instance.builder(module)
                .apply { if (memLimits != null) withMemoryLimits(memLimits) }
                .build()
        } catch (e: UnlinkableException) {
            throw WasmLoadException("module capability violation (imports not allowed): ${e.message}", e)
        } catch (e: ChicoryException) {
            // Any other build-time failure — validation (InvalidException), a trapping (start)
            // function (UninstantiableException), etc. — is also a terminal load failure, never a
            // raw ChicoryException that escapes the executor's WasmException handling.
            throw WasmLoadException("module failed to instantiate: ${e.message}", e)
        }

    /**
     * Runs one ABI round-trip under the execution-time bound.
     *
     * The blocking work (real-executor submit + `Future.get(timeout)`) is **real wall-clock work**
     * — the guest burns real CPU on the dedicated worker thread — so it deliberately runs on
     * [Dispatchers.IO], a real blocking context, NOT the caller's (possibly virtual-time)
     * scheduler. This is the sanctioned real-threading exception to the no-production-dispatcher
     * rule: the timeout is a wall-clock CPU bound and cannot be driven by virtual time.
     * [Dispatchers.IO] only *waits*; the guest itself runs on [guestExecutor], whose interrupt
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

    /** The ABI marshalling, executed entirely on [guestExecutor] so all guest access is bounded. */
    private fun runAbi(
        memory: Memory,
        allocFn: ExportFunction,
        runFn: ExportFunction,
        args: ByteArray,
    ): ByteArray {
        val argPtr = allocFn.apply(args.size.toLong())[0].toInt()
        memory.write(argPtr, args)
        val packed = runFn.apply(argPtr.toLong(), args.size.toLong())[0]
        val resPtr = (packed ushr 32).toInt()
        val resLen = (packed and 0xFFFF_FFFFL).toInt()
        return memory.readBytes(resPtr, resLen)
    }
}
