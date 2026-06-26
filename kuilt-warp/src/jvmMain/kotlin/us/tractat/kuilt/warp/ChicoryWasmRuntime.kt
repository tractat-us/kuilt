package us.tractat.kuilt.warp

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser

/**
 * JVM implementation of [WasmRuntime] backed by the Chicory pure-JVM interpreter.
 *
 * [load] parses and instantiates the module once; the returned [Op] drives every invocation
 * over shared linear memory via the warp ABI:
 * - `warp_alloc(len: i32) -> i32`   — guest returns a writable pointer for `len` bytes.
 * - `warp_run(ptr: i32, len: i32) -> i64` — guest processes `memory[ptr..ptr+len)` and
 *   returns a packed pointer/length: `(resPtr.toLong() shl 32) or (resLen.toLong() and 0xFFFF_FFFF)`.
 *
 * Sandbox guards (import rejection, memory-page cap, execution timeout, trap wrapping) are
 * added in Tasks 3–4. This task wires the happy path and the [WasmSandboxConfig] object only.
 */
public class ChicoryWasmRuntime(
    public val config: WasmSandboxConfig = WasmSandboxConfig(),
) : WasmRuntime {

    override fun load(bytes: ByteArray): Op {
        val module = Parser.parse(bytes)
        val instance = Instance.builder(module).build()
        val memory = instance.memory()
        val allocFn = instance.export("warp_alloc")
        val runFn = instance.export("warp_run")
        return Op { args -> invoke(memory, allocFn, runFn, args) }
    }

    private fun invoke(
        memory: com.dylibso.chicory.runtime.Memory,
        allocFn: com.dylibso.chicory.runtime.ExportFunction,
        runFn: com.dylibso.chicory.runtime.ExportFunction,
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
