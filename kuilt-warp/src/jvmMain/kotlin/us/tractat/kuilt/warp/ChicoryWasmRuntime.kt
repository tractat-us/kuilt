package us.tractat.kuilt.warp

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.UnlinkableException
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.MemoryLimits

/**
 * JVM implementation of [WasmRuntime] backed by the Chicory pure-JVM interpreter.
 *
 * [load] parses and instantiates the module once; the returned [Op] drives every invocation
 * over shared linear memory via the warp ABI:
 * - `warp_alloc(len: i32) -> i32`   — guest returns a writable pointer for `len` bytes.
 * - `warp_run(ptr: i32, len: i32) -> i64` — guest processes `memory[ptr..ptr+len)` and
 *   returns a packed pointer/length: `(resPtr.toLong() shl 32) or (resLen.toLong() and 0xFFFF_FFFF)`.
 *
 * **Sandbox guards (Task 3):**
 * - *Import rejection* — no [withImportValues] is provided; any declared import causes
 *   Chicory's [Instance.builder] to throw [UnlinkableException], which is caught and rethrown
 *   as [WasmLoadException].
 * - *Memory cap* — before build, both the declared initial and max page counts are checked
 *   against [WasmSandboxConfig.maxMemoryPages]. An oversize initial or explicit max is rejected
 *   immediately. A module with no declared max (Chicory sentinel: [MemoryLimits.MAX_PAGES])
 *   is allowed but clamped via [Instance.Builder.withMemoryLimits] so the runtime enforces
 *   the cap at execution time.
 *
 * Execution-time guards (timeout, trap wrapping) are added in Task 4.
 */
public class ChicoryWasmRuntime(
    public val config: WasmSandboxConfig = WasmSandboxConfig(),
) : WasmRuntime {

    override fun load(bytes: ByteArray): Op {
        val module = parseModule(bytes)
        val memLimits = resolvedMemoryLimits(module)
        val instance = buildInstance(module, memLimits)
        val memory = instance.memory()
        val allocFn = instance.export("warp_alloc")
        val runFn = instance.export("warp_run")
        return Op { args -> invoke(memory, allocFn, runFn, args) }
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
            Instance.builder(module)
                .apply { if (memLimits != null) withMemoryLimits(memLimits) }
                .build()
        } catch (e: UnlinkableException) {
            throw WasmLoadException("module capability violation (imports not allowed): ${e.message}", e)
        }

    private fun invoke(
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
