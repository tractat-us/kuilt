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
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
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
 * Construct once and reuse across loads/invokes; call [close] to release the executor thread.
 */
public class ChicoryWasmRuntime(
    public val config: WasmSandboxConfig = WasmSandboxConfig(),
) : WasmRuntime, AutoCloseable {

    /**
     * Single-thread executor that runs every guest call. One worker per runtime, reused across
     * invocations: the Chicory instance is single-threaded, so all guest access is confined to
     * this one thread. A timed-out invocation interrupts the worker; the executor clears the
     * stale interrupt before the next task, so a timeout does not poison the next invoke.
     */
    private val guestExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "warp-wasm-guest").apply { isDaemon = true }
    }

    override fun load(bytes: ByteArray): Op {
        val module = parseModule(bytes)
        val memLimits = resolvedMemoryLimits(module)
        val instance = buildInstance(module, memLimits)
        val memory = instance.memory()
        val allocFn = instance.export("warp_alloc")
        val runFn = instance.export("warp_run")
        return Op { args -> invoke(memory, allocFn, runFn, args) }
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
        }

    /**
     * Runs one ABI round-trip under the execution-time bound.
     *
     * The blocking `future.get(timeout)` is **real wall-clock work** — the guest burns real CPU on
     * the dedicated worker thread — so it deliberately runs on [Dispatchers.IO], a real blocking
     * context, NOT the caller's (possibly virtual-time) scheduler. This is the sanctioned
     * real-threading exception to the no-production-dispatcher rule: the timeout is a wall-clock CPU
     * bound and cannot be driven by virtual time. [Dispatchers.IO] only *waits*; the guest itself
     * runs on [guestExecutor], whose interrupt flag is what terminates a runaway kernel.
     */
    private suspend fun invoke(
        memory: Memory,
        allocFn: ExportFunction,
        runFn: ExportFunction,
        args: ByteArray,
    ): ByteArray = withContext(Dispatchers.IO) {
        val future = guestExecutor.submit(Callable { runAbi(memory, allocFn, runFn, args) })
        try {
            future.get(config.executionTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Interrupt the worker; Chicory's interpreter throws ChicoryInterruptedException at the
            // next call entry / backward branch, terminating the runaway guest.
            future.cancel(true)
            throw WasmExecutionException("WASM execution exceeded ${config.executionTimeout}", e)
        } catch (e: ExecutionException) {
            // A trap / unreachable / OOB / bad packed result inside a guest call.
            val cause = e.cause
            if (cause is ChicoryException) {
                throw WasmExecutionException("WASM kernel trapped: ${cause.message}", cause)
            }
            throw WasmExecutionException("WASM kernel failed: ${cause?.message ?: e.message}", cause ?: e)
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
