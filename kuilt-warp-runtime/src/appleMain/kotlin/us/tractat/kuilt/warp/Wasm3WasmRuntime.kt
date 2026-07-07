@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import wasm3.IM3Function
import wasm3.IM3FunctionVar
import wasm3.IM3Module
import wasm3.IM3ModuleVar
import wasm3.IM3Runtime
import wasm3.m3_FindFunction
import wasm3.m3_FreeModule
import wasm3.m3_FreeRuntime
import wasm3.m3_GetMemory
import wasm3.m3_GetMemorySize
import wasm3.m3_LoadModule
import wasm3.m3_NewEnvironment
import wasm3.m3_NewRuntime
import wasm3.m3_ParseModule
import wasm3.warp_call_alloc
import wasm3.warp_call_run
import wasm3.warp_clear_execution_deadline
import wasm3.warp_module_has_memory
import wasm3.warp_module_init_pages
import wasm3.warp_module_max_pages
import wasm3.warp_module_memory_imported
import wasm3.warp_module_num_func_imports
import wasm3.warp_module_num_global_imports
import wasm3.warp_set_execution_deadline_ns

/**
 * Kotlin/Native implementation of [WasmRuntime] backed by the wasm3 C interpreter (Apple targets).
 *
 * [load] parses + instantiates a module under the capability sandbox once; the returned [Op] drives
 * every invocation over the module's linear memory via the warp ABI:
 * - `warp_alloc(len: i32) -> i32`   — guest returns a writable pointer for `len` bytes.
 * - `warp_run(ptr: i32, len: i32) -> i64` — guest processes `memory[ptr..ptr+len)` and returns a
 *   packed pointer/length: `(resPtr.toLong() shl 32) or (resLen.toLong() and 0xFFFF_FFFF)`.
 *
 * **Sandbox guards — mirrors [ChicoryWasmRuntime] semantics.**
 *
 * *Load-time (fully implemented):*
 * - *Import rejection* — wasm3 links imports lazily (a missing import only errors when the
 *   importing function is compiled/called), so this cannot rely on `m3_LoadModule` failing. The
 *   parsed module's declared shape is inspected up front: any imported function, imported global,
 *   or imported memory is a capability violation → [WasmLoadException].
 * - *Memory ceiling* — a declared initial or maximum linear-memory page count exceeding
 *   [WasmSandboxConfig.maxMemoryPages] → [WasmLoadException]. A module declaring memory with **no
 *   explicit max** is also rejected: wasm3 defaults the runtime ceiling to 65536 pages (~4 GiB) for
 *   such a module, so its kernel could `memory.grow` unbounded — a memory-bomb DoS. Requiring a
 *   bounded `max <= cap` closes it; wasm3 enforces the declared max at grow time. A module declaring
 *   no linear memory at all is rejected too — the warp ABI requires memory to marshal args/results.
 * - *Malformed bytes* — `m3_ParseModule` failure → [WasmLoadException].
 * - *Missing ABI export* — `m3_FindFunction` failure for `warp_alloc`/`warp_run` →
 *   [WasmLoadException]. This is terminal, not a transient error: a verified-but-broken kernel
 *   that escaped `load` as a raw error would bypass the executor's terminal-error handling and
 *   trigger an anti-entropy retry storm on every peer (a remotely-triggerable DoS).
 *
 * *Run-time:* any `M3Result` error from `warp_alloc`/`warp_run`/the result read — a trap,
 * `unreachable`, out-of-bounds access, or a bad packed result — surfaces as [WasmExecutionException].
 *
 * **Execution timeout — the CPU-bomb defense.** The vendored wasm3 interpreter is patched (grep
 * `WARP PATCH` under `src/nativeInterop/wasm3/source/`) to poll its `m3_Yield` hook on every loop
 * backward branch in addition to upstream's every-function-call-entry poll — the two sites an
 * unbounded computation must pass through, mirroring where [ChicoryWasmRuntime]'s interpreter
 * checks `Thread.isInterrupted()`. `warp_deadline.c` supplies the strong `m3_Yield`: a
 * thread-local wall-clock deadline armed around each ABI round trip
 * ([WasmSandboxConfig.executionTimeout]) and cleared after, trapping a runaway guest
 * cooperatively on its own thread — no cross-thread abort, no abandoned worker. The deadline trap
 * surfaces as [WasmExecutionException] naming the exceeded budget. The deadline is armed around
 * **every** guest execution, including load: wasm3 runs a module's `(start)` function lazily
 * inside the first `m3_FindFunction` — the ABI-export lookup in [load] — so that lookup runs
 * under the armed deadline too, and a CPU-bomb `(start)` fails as a terminal [WasmLoadException]
 * naming the exceeded budget instead of hanging `load()`.
 *
 * **Thread-safety.** wasm3 is not thread-safe: the shared [environment] is guarded by [loadLock]
 * across [load], and each [Op]'s runtime is guarded by its own lock across an invocation. Both are
 * real [reentrantLock]s, not dispatcher confinement. The execution deadline is thread-local in the
 * C shim and armed/cleared inside the [Op]'s lock on the invoking thread, so concurrent runtimes
 * never see each other's budgets.
 *
 * @param config Sandbox configuration (memory cap, execution timeout).
 */
public class Wasm3WasmRuntime(
    public val config: WasmSandboxConfig = WasmSandboxConfig(),
) : WasmRuntime {

    /** Shared wasm3 environment; modules are parsed into it and runtimes are created from it. */
    private val environment = checkNotNull(m3_NewEnvironment()) {
        "wasm3: m3_NewEnvironment returned null"
    }

    /** Serializes [load] — `m3_ParseModule` mutates the shared [environment]. */
    private val loadLock = reentrantLock()

    /**
     * Retains each loaded module's pinned backing bytes for the runtime's lifetime. wasm3 holds a
     * pointer into the wasm bytes for the module's lifetime, so the pin must outlive every [Op]
     * invocation; Kotlin/Native offers no `Op.close` hook to unpin deterministically.
     */
    private val retainedPins = mutableListOf<Pinned<ByteArray>>()

    override fun load(bytes: ByteArray): Op = loadLock.withLock { loadLocked(bytes) }

    /**
     * Parses + instantiates [bytes] under the sandbox, freeing every native resource on each
     * rejection path. Bad kernels (malformed / import / oversize / missing-ABI) are exactly what the
     * guards reject and arrive from untrusted peers, so a leak here would be a remotely-triggerable
     * unbounded native-memory leak. The pin is retained (and the runtime kept alive via the [Op]
     * closure) ONLY once load fully succeeds; until then every failure unpins, frees the module if it
     * is still standalone, and frees the runtime if one was created.
     */
    private fun loadLocked(bytes: ByteArray): Op {
        val pinnedBytes = bytes.pin()

        val module = try {
            parseModule(pinnedBytes, bytes.size)
        } catch (e: Throwable) {
            pinnedBytes.unpin()
            throw e
        }

        // Pre-instantiation guards: the module is standalone, so free it (not a runtime) on reject.
        val runtime = try {
            rejectCapabilityViolations(module)
            rejectOversizeMemory(module)
            checkNotNull(m3_NewRuntime(environment, RUNTIME_STACK_BYTES, null)) {
                "wasm3: m3_NewRuntime returned null"
            }
        } catch (e: Throwable) {
            m3_FreeModule(module)
            pinnedBytes.unpin()
            throw e
        }

        // m3_LoadModule failure detaches the module (runtime = NULL) without adding it to the
        // runtime's list, so free BOTH the still-standalone module and the runtime.
        try {
            loadModuleOrThrow(runtime, module)
        } catch (e: Throwable) {
            m3_FreeModule(module)
            m3_FreeRuntime(runtime)
            pinnedBytes.unpin()
            throw e
        }

        // Module is now owned by the runtime; m3_FreeRuntime frees it too. Do NOT free it separately.
        val allocFn: IM3Function
        val runFn: IM3Function
        try {
            allocFn = findFunctionOrThrow(runtime, "warp_alloc")
            runFn = findFunctionOrThrow(runtime, "warp_run")
        } catch (e: Throwable) {
            m3_FreeRuntime(runtime)
            pinnedBytes.unpin()
            throw e
        }

        retainedPins.add(pinnedBytes)
        val invokeLock = reentrantLock()
        return Op { args -> invokeLock.withLock { runAbi(runtime, allocFn, runFn, args) } }
    }

    private fun parseModule(pinned: Pinned<ByteArray>, size: Int): IM3Module = memScoped {
        val moduleRef = alloc<IM3ModuleVar>()
        val result = m3_ParseModule(
            environment,
            moduleRef.ptr,
            pinned.addressOf(0).reinterpret<UByteVar>(),
            size.toUInt(),
        )
        if (result != null) {
            throw WasmLoadException("malformed WASM module: ${result.toKString()}")
        }
        checkNotNull(moduleRef.value) { "wasm3: m3_ParseModule returned null module" }
    }

    /**
     * Rejects any module declaring an import — a host capability the compute sandbox does not grant.
     * The caller ([loadLocked]) owns freeing the module on the thrown path.
     */
    private fun rejectCapabilityViolations(module: IM3Module) {
        val funcImports = warp_module_num_func_imports(module)
        val globalImports = warp_module_num_global_imports(module)
        val memoryImported = warp_module_memory_imported(module) != 0
        if (funcImports > 0u || globalImports > 0u || memoryImported) {
            throw WasmLoadException(
                "module capability violation (imports not allowed): " +
                    "$funcImports function, $globalImports global imports, memoryImported=$memoryImported",
            )
        }
    }

    /**
     * Rejects a module whose declared linear memory exceeds [WasmSandboxConfig.maxMemoryPages],
     * declares memory with no explicit max, or declares no memory at all (the warp ABI needs memory
     * to marshal args/results). The caller ([loadLocked]) owns freeing the module on the thrown path.
     *
     * A no-max module is rejected because wasm3 defaults the runtime ceiling to 65536 pages (~4 GiB)
     * when the module declares no max, so its kernel could `memory.grow` unbounded — a memory-bomb
     * DoS (the native counterpart of the browser fix). Requiring a bounded `max <= cap` closes it:
     * wasm3 then enforces that declared max at grow time.
     */
    private fun rejectOversizeMemory(module: IM3Module) {
        if (warp_module_has_memory(module) == 0) {
            throw WasmLoadException("module declares no linear memory (warp ABI requires it)")
        }
        val cap = config.maxMemoryPages.toUInt()
        val initial = warp_module_init_pages(module)
        if (initial > cap) {
            throw WasmLoadException("module initial memory $initial pages exceeds sandbox cap $cap pages")
        }
        val declaredMax = warp_module_max_pages(module)
        if (declaredMax == 0u) {
            throw WasmLoadException(
                "module declares memory with no explicit max (unbounded growth not allowed); " +
                    "declare a max <= $cap pages",
            )
        }
        if (declaredMax > cap) {
            throw WasmLoadException("module memory exceeds sandbox cap: declared max $declaredMax pages > $cap pages")
        }
    }

    /**
     * Links [module] into [runtime]. The caller ([loadLocked]) owns freeing both on the thrown path
     * — on failure wasm3 detaches the module from the runtime, so neither is reachable for cleanup
     * from anywhere else.
     */
    private fun loadModuleOrThrow(runtime: IM3Runtime, module: IM3Module) {
        val result = m3_LoadModule(runtime, module)
        if (result != null) {
            throw WasmLoadException("module failed to instantiate: ${result.toKString()}")
        }
    }

    /**
     * Resolves a required ABI export, converting wasm3's `M3Result` for a missing function into a
     * terminal [WasmLoadException]. Without this, a well-formed module that simply omits an ABI
     * export would surface a non-[WasmException] that escapes terminal-error handling and triggers
     * an anti-entropy retry storm on a verified-but-broken kernel.
     *
     * `m3_FindFunction` is **guest execution**, not just a lookup: wasm3 lazily runs the module's
     * `(start)` function on the first successful find (see `m3_FindFunction` → `m3_RunStart` in
     * `m3_env.c`). The execution deadline is therefore armed around the call — the load-phase
     * sibling of [runAbi]'s CPU-bomb defense: a `(start)` spinning forever would otherwise hang
     * `load()` outside any budget. A deadline trap here surfaces as a terminal [WasmLoadException]
     * naming the exceeded budget.
     */
    private fun findFunctionOrThrow(runtime: IM3Runtime, name: String): IM3Function = memScoped {
        val funcRef = alloc<IM3FunctionVar>()
        warp_set_execution_deadline_ns(config.executionTimeout.inWholeNanoseconds.toULong())
        val result = try {
            m3_FindFunction(funcRef.ptr, runtime, name)
        } finally {
            warp_clear_execution_deadline()
        }
        if (result != null) {
            val message = result.toKString()
            if (message == DEADLINE_EXCEEDED_TRAP) {
                throw WasmLoadException(
                    "module instantiation exceeded ${config.executionTimeout} ((start)-section execution budget)",
                )
            }
            throw WasmLoadException("missing ABI export $name: $message")
        }
        checkNotNull(funcRef.value) { "wasm3: $name resolved to a null function pointer" }
    }

    /**
     * One ABI round-trip: marshal args into linear memory, run, read the packed result back —
     * all under the armed execution deadline (see the class KDoc). The deadline covers the whole
     * round trip (`warp_alloc` + `warp_run`; the module's `(start)` function already ran, bounded,
     * inside [findFunctionOrThrow] at load), matching [ChicoryWasmRuntime]'s whole-invocation
     * budget, and is cleared even on a trap so the next invocation on this thread starts disarmed.
     *
     * The `warp_alloc` return and the packed `warp_run` result are fully guest-controlled `i32`/`i64`
     * words, decoded exclusively through the common safe decoder ([unpackWarpResult] +
     * [requireInBounds], via [memoryBaseFor]) — never a hand-rolled unpack, whose signed narrowing
     * is exactly the sandbox-escape class the common decoder exists to prevent (see [GuestRegion]).
     */
    private fun runAbi(runtime: IM3Runtime, allocFn: IM3Function, runFn: IM3Function, args: ByteArray): ByteArray {
        warp_set_execution_deadline_ns(config.executionTimeout.inWholeNanoseconds.toULong())
        try {
            val argPtr = callAlloc(allocFn, args.size)
            writeMemory(runtime, argPtr, args)
            val packed = callRun(runFn, argPtr, args.size.toLong())
            val result = unpackWarpResult(packed)
            return readMemory(runtime, result.ptr, result.len)
        } finally {
            warp_clear_execution_deadline()
        }
    }

    /**
     * Translates a non-null `M3Result` from a guest call into [WasmExecutionException],
     * distinguishing the sandbox's own deadline trap (see `warp_deadline.c`) from a guest fault.
     */
    private fun guestTrap(phase: String, message: String): WasmExecutionException =
        if (message == DEADLINE_EXCEEDED_TRAP) {
            WasmExecutionException("WASM execution exceeded ${config.executionTimeout}")
        } else {
            WasmExecutionException("$phase trapped: $message")
        }

    /** Calls `warp_alloc(args.size)`, returning the guest pointer as an unsigned [Long]. */
    private fun callAlloc(allocFn: IM3Function, len: Int): Long = memScoped {
        val out = alloc<IntVar>()
        val result = warp_call_alloc(allocFn, len, out.ptr)
        if (result != null) {
            throw guestTrap("warp_alloc", result.toKString())
        }
        out.value.toUInt().toLong()
    }

    private fun callRun(runFn: IM3Function, ptr: Long, len: Long): Long = memScoped {
        val out = alloc<LongVar>()
        val result = warp_call_run(runFn, ptr.toInt(), len.toInt(), out.ptr)
        if (result != null) {
            throw guestTrap("warp_run", result.toKString())
        }
        out.value
    }

    private fun writeMemory(runtime: IM3Runtime, ptr: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val base = memoryBaseFor(runtime, ptr, bytes.size.toLong())
        for (i in bytes.indices) {
            base[ptr + i] = bytes[i].toUByte()
        }
    }

    private fun readMemory(runtime: IM3Runtime, ptr: Long, len: Long): ByteArray {
        if (len == 0L) return ByteArray(0)
        val base = memoryBaseFor(runtime, ptr, len)
        return ByteArray(len.toInt()) { base[ptr + it].toByte() }
    }

    /**
     * Returns the current linear-memory base pointer for a `[ptr, ptr+len)` window, re-fetched on
     * every access because `warp_alloc` may have grown (reallocated) memory and invalidated an
     * earlier pointer. The guest-controlled window is validated by the common [requireInBounds]
     * against the live memory size, so a malicious pointer/length traps as a
     * [WasmExecutionException] (a guest runtime fault), never an OOB host-memory access or a raw
     * non-[WasmException].
     */
    private fun memoryBaseFor(runtime: IM3Runtime, ptr: Long, len: Long): CPointer<UByteVar> {
        requireInBounds(ptr, len, m3_GetMemorySize(runtime).toLong())
        return checkNotNull(m3_GetMemory(runtime, null, 0u)) {
            "wasm3: m3_GetMemory returned null after load"
        }
    }

    private companion object {
        /** wasm3 operand-stack size (bytes) = 64 KiB. Independent of the linear-memory page cap. */
        private const val RUNTIME_STACK_BYTES: UInt = 65536u

        /**
         * The exact `M3Result` text the patched interpreter's deadline check traps with — must
         * match `warp_err_deadline_exceeded` in `warp_deadline.c`.
         */
        private const val DEADLINE_EXCEEDED_TRAP: String = "warp execution deadline exceeded"
    }
}
