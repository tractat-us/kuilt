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
import wasm3.warp_module_has_memory
import wasm3.warp_module_init_pages
import wasm3.warp_module_max_pages
import wasm3.warp_module_memory_imported
import wasm3.warp_module_num_func_imports
import wasm3.warp_module_num_global_imports

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
 *   [WasmSandboxConfig.maxMemoryPages] → [WasmLoadException] (a `max` of 0 means "no maximum
 *   declared" and is allowed). A module declaring no linear memory at all is also rejected — the
 *   warp ABI requires memory to marshal args/results.
 * - *Malformed bytes* — `m3_ParseModule` failure → [WasmLoadException].
 * - *Missing ABI export* — `m3_FindFunction` failure for `warp_alloc`/`warp_run` →
 *   [WasmLoadException]. This is terminal, not a transient error: a verified-but-broken kernel
 *   that escaped `load` as a raw error would bypass the executor's terminal-error handling and
 *   trigger an anti-entropy retry storm on every peer (a remotely-triggerable DoS).
 *
 * *Run-time:* any `M3Result` error from `warp_alloc`/`warp_run`/the result read — a trap,
 * `unreachable`, out-of-bounds access, or a bad packed result — surfaces as [WasmExecutionException].
 *
 * **Execution timeout — NOT enforced on this runtime (known soft spot).** wasm3 exposes no safe
 * cooperative or cross-thread abort surface in its public API, so a CPU-bomb kernel that loops
 * forever inside `warp_run` cannot be bounded here the way [ChicoryWasmRuntime] bounds it (Chicory's
 * interpreter checks `Thread.isInterrupted()` at every call entry / backward branch). Abandoning a
 * runaway worker thread is unsafe on Kotlin/Native (no thread kill), so no fake timeout is wired:
 * [WasmSandboxConfig.executionTimeout] is accepted for API parity but is a no-op here. The hard
 * CPU-bound defense needs a design decision (e.g. a wasm3 fork with an interrupt flag, or an
 * out-of-process executor) — tracked as a `needs-design` follow-up.
 *
 * **Thread-safety.** wasm3 is not thread-safe: the shared [environment] is guarded by [loadLock]
 * across [load], and each [Op]'s runtime is guarded by its own lock across an invocation. Both are
 * real [reentrantLock]s, not dispatcher confinement.
 *
 * @param config Sandbox configuration (memory cap; [WasmSandboxConfig.executionTimeout] is a no-op
 *   here — see above).
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
     * Rejects a module whose declared linear memory exceeds [WasmSandboxConfig.maxMemoryPages], or
     * that declares no memory at all (the warp ABI needs memory to marshal args/results). The caller
     * ([loadLocked]) owns freeing the module on the thrown path.
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
        if (declaredMax != 0u && declaredMax > cap) {
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
     */
    private fun findFunctionOrThrow(runtime: IM3Runtime, name: String): IM3Function = memScoped {
        val funcRef = alloc<IM3FunctionVar>()
        val result = m3_FindFunction(funcRef.ptr, runtime, name)
        if (result != null) {
            throw WasmLoadException("missing ABI export $name: ${result.toKString()}")
        }
        checkNotNull(funcRef.value) { "wasm3: $name resolved to a null function pointer" }
    }

    /**
     * One ABI round-trip: marshal args into linear memory, run, read the packed result back.
     *
     * The `warp_alloc` return and the packed `warp_run` result are fully guest-controlled `i32`/`i64`
     * words. They are kept as **unsigned** [Long] offsets (`0..0xFFFF_FFFF`) and bounds-validated in
     * [memoryBaseFor] before any indexing — never narrowed to a signed [Int], which would let a value
     * with bit 31 set wrap negative, slip past the bounds check, and index host memory (a sandbox
     * escape) or hit `ByteArray(negative)` (a raw exception escaping [WasmException]).
     */
    private fun runAbi(runtime: IM3Runtime, allocFn: IM3Function, runFn: IM3Function, args: ByteArray): ByteArray {
        val argPtr = callAlloc(allocFn, args.size)
        writeMemory(runtime, argPtr, args)
        val packed = callRun(runFn, argPtr, args.size.toLong())
        val resPtr = (packed ushr 32) and 0xFFFF_FFFFL
        val resLen = packed and 0xFFFF_FFFFL
        return readMemory(runtime, resPtr, resLen)
    }

    /** Calls `warp_alloc(args.size)`, returning the guest pointer as an unsigned [Long]. */
    private fun callAlloc(allocFn: IM3Function, len: Int): Long = memScoped {
        val out = alloc<IntVar>()
        val result = warp_call_alloc(allocFn, len, out.ptr)
        if (result != null) {
            throw WasmExecutionException("warp_alloc trapped: ${result.toKString()}")
        }
        out.value.toUInt().toLong()
    }

    private fun callRun(runFn: IM3Function, ptr: Long, len: Long): Long = memScoped {
        val out = alloc<LongVar>()
        val result = warp_call_run(runFn, ptr.toInt(), len.toInt(), out.ptr)
        if (result != null) {
            throw WasmExecutionException("warp_run trapped: ${result.toKString()}")
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
     * earlier pointer. Validates the guest-controlled window in [Long] space — `ptr`/`len` are
     * non-negative, the result fits a [ByteArray], and `ptr + len` is within the live memory size —
     * so a malicious pointer/length traps as a [WasmExecutionException] (a guest runtime fault),
     * never an OOB host-memory access or a raw non-[WasmException].
     */
    private fun memoryBaseFor(runtime: IM3Runtime, ptr: Long, len: Long): CPointer<UByteVar> {
        val size = m3_GetMemorySize(runtime).toLong()
        if (ptr < 0L || len < 0L || len > Int.MAX_VALUE.toLong() || ptr + len > size) {
            throw WasmExecutionException(
                "WASM memory access out of bounds: window [$ptr, ${ptr + len}) outside [0, $size)",
            )
        }
        return checkNotNull(m3_GetMemory(runtime, null, 0u)) {
            "wasm3: m3_GetMemory returned null after load"
        }
    }

    private companion object {
        /** wasm3 operand-stack size (bytes) = 64 KiB. Independent of the linear-memory page cap. */
        private const val RUNTIME_STACK_BYTES: UInt = 65536u
    }
}
