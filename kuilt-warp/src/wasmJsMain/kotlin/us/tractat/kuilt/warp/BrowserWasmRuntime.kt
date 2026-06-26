@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.CancellationException
import kotlin.JsFun
import kotlin.js.JsAny

/**
 * Browser (wasmJs) implementation of [WasmRuntime] backed by the native WebAssembly JS API.
 *
 * [load] compiles + instantiates the module once over an **empty imports object**; the returned
 * [Op] drives every invocation over the module's shared linear memory via the warp ABI:
 * - `warp_alloc(len: i32) -> i32`   — guest returns a writable pointer for `len` bytes.
 * - `warp_run(ptr: i32, len: i32) -> i64` — guest processes `memory[ptr..ptr+len)` and returns a
 *   packed pointer/length: `(resPtr.toLong() shl 32) or (resLen.toLong() and 0xFFFF_FFFF)`.
 *
 * **Sandbox guards (load-time — the `ready` part).** These mirror the JVM [ChicoryWasmRuntime]
 * semantics over the JS API:
 * - *Import rejection* — any declared import is a capability violation. `WebAssembly.Module.imports`
 *   is inspected up front; a non-empty list throws [WasmLoadException]. (Instantiating with an empty
 *   imports object would also fail, but rejecting up front gives a deterministic error.)
 * - *Memory ceiling* — the binary's memory section is parsed and its declared initial / explicit max
 *   page counts checked against [WasmSandboxConfig.maxMemoryPages]; over-cap modules throw
 *   [WasmLoadException]. (The JS API does not expose declared memory limits, so the section is parsed
 *   directly. A module declaring no max cannot be clamped at instantiate — see class limitation below.)
 * - *Malformed bytes* — `new WebAssembly.Module(bytes)` throws on invalid WASM → [WasmLoadException].
 * - *Missing ABI export* — a well-formed module lacking `warp_alloc`/`warp_run`/`memory` throws
 *   [WasmLoadException], NOT a raw JS error. This preserves the property [ChicoryWasmRuntime]
 *   documents: a non-[WasmException] escaping `load` would bypass the executor's terminal-error
 *   handling and trigger an anti-entropy retry storm on a verified-but-broken kernel (a
 *   remotely-triggerable DoS).
 *
 * **Run-time trap** — any error from a guest call (trap, `unreachable`, OOB) surfaces as
 * [WasmExecutionException], preserving the cause.
 *
 * **Execution-timeout limitation (known soft spot).** Browser JS is single-threaded; a synchronous
 * WASM runaway cannot be pre-empted without moving execution to a Web Worker. This impl therefore
 * does NOT enforce [WasmSandboxConfig.executionTimeout] for a CPU-bound guest — the load-time guards
 * above are the `ready` defense. A true synchronous-CPU-bound bound needs a Worker-based redesign
 * (a `needs-design` follow-up); it is deliberately not faked here.
 *
 * No mutable state, no owned scope — safe to share across loads/invokes on the single JS thread.
 *
 * @param config Sandbox configuration (memory cap, execution timeout). The memory cap is enforced;
 *   the timeout is not (see limitation above).
 */
public class BrowserWasmRuntime(
    public val config: WasmSandboxConfig = WasmSandboxConfig(),
) : WasmRuntime {

    override fun load(bytes: ByteArray): Op {
        val module = compileModule(bytes)
        rejectImports(module)
        rejectOversizeMemory(bytes)
        val instance = instantiate(module)
        requireWarpAbi(instance)
        return Op { args -> invoke(instance, args) }
    }

    private fun compileModule(bytes: ByteArray): JsAny =
        try {
            wasmCompile(bytes.toUint8Array())
        } catch (e: Throwable) {
            throw WasmLoadException("malformed WASM module: ${e.message}", e)
        }

    private fun rejectImports(module: JsAny) {
        if (wasmImportCount(module) > 0) {
            throw WasmLoadException("module capability violation (imports not allowed)")
        }
    }

    private fun rejectOversizeMemory(bytes: ByteArray) {
        val limits = declaredMemoryLimits(bytes) ?: return
        if (limits.initialPages > config.maxMemoryPages) {
            throw WasmLoadException(
                "module initial memory ${limits.initialPages} pages exceeds sandbox cap " +
                    "${config.maxMemoryPages} pages",
            )
        }
        if (limits.maxPages != null && limits.maxPages > config.maxMemoryPages) {
            throw WasmLoadException(
                "module memory exceeds sandbox cap: declared max ${limits.maxPages} pages > " +
                    "${config.maxMemoryPages} pages",
            )
        }
    }

    private fun instantiate(module: JsAny): JsAny =
        try {
            wasmInstantiateModule(module)
        } catch (e: Throwable) {
            throw WasmLoadException("module failed to instantiate: ${e.message}", e)
        }

    private fun requireWarpAbi(instance: JsAny) {
        if (!wasmHasWarpAbi(instance)) {
            throw WasmLoadException("missing ABI export (warp_alloc/warp_run/memory)")
        }
    }

    /**
     * Runs one ABI round-trip. The guest call is synchronous on the single JS thread; any thrown JS
     * error surfaces as [WasmExecutionException]. The [CancellationException] rethrow is defensive —
     * no suspension occurs inside, but it keeps the catch honest per the no-swallow-cancellation rule.
     */
    private fun invoke(instance: JsAny, args: ByteArray): ByteArray =
        try {
            wasmRunAbi(instance, args.toUint8Array()).toByteArray()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw WasmExecutionException("WASM kernel trapped: ${e.message}", e)
        }
}

// ── Declared-memory parsing ─────────────────────────────────────────────────────────────────────
// The WebAssembly JS API exposes no declared memory limits, so the binary's memory section (id 5) is
// parsed directly to enforce the page cap at load time — mirroring Chicory's reject-over-cap policy.

private const val WASM_HEADER_SIZE = 8
private const val WASM_MEMORY_SECTION_ID = 5
private const val LEB_PAYLOAD_MASK = 0x7F
private const val LEB_CONTINUATION_BIT = 0x80
private const val LEB_SHIFT = 7
private const val LIMITS_HAS_MAX_FLAG = 0x01

private class DeclaredMemoryLimits(val initialPages: Int, val maxPages: Int?)

/** Walks the section list for the memory section, returning its first memory's declared limits. */
private fun declaredMemoryLimits(bytes: ByteArray): DeclaredMemoryLimits? {
    if (bytes.size < WASM_HEADER_SIZE) return null
    val reader = WasmByteReader(bytes, WASM_HEADER_SIZE)
    while (reader.hasMore()) {
        val sectionId = reader.u8()
        val sectionSize = reader.leb()
        if (sectionId == WASM_MEMORY_SECTION_ID) return reader.readFirstMemoryLimits()
        reader.skip(sectionSize)
    }
    return null
}

/** Cursor over the WASM binary with minimal unsigned-LEB128 + section helpers. */
private class WasmByteReader(private val bytes: ByteArray, private var pos: Int) {
    fun hasMore(): Boolean = pos < bytes.size

    fun u8(): Int = bytes[pos++].toInt() and 0xFF

    fun skip(count: Int) {
        pos += count
    }

    fun leb(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = u8()
            result = result or ((byte and LEB_PAYLOAD_MASK) shl shift)
            if (byte and LEB_CONTINUATION_BIT == 0) return result
            shift += LEB_SHIFT
        }
    }

    /** Reads the first memory entry's limits (`[count][flags][min][max?]`), or null if empty. */
    fun readFirstMemoryLimits(): DeclaredMemoryLimits? {
        if (leb() == 0) return null
        val flags = u8()
        val initial = leb()
        val max = if (flags and LIMITS_HAS_MAX_FLAG != 0) leb() else null
        return DeclaredMemoryLimits(initial, max)
    }
}

// ── WebAssembly JS API interop ──────────────────────────────────────────────────────────────────

/** Synchronously compile a WASM module from a Uint8Array. Throws on malformed bytes. */
@JsFun("(bytes) => new WebAssembly.Module(bytes)")
private external fun wasmCompile(bytes: JsAny): JsAny

/** Number of declared imports — non-zero means a capability violation. */
@JsFun("(module) => WebAssembly.Module.imports(module).length")
private external fun wasmImportCount(module: JsAny): Int

/** Instantiate with an EMPTY imports object — a module needing any import fails here. */
@JsFun("(module) => new WebAssembly.Instance(module, {})")
private external fun wasmInstantiateModule(module: JsAny): JsAny

/** True iff the instance exports the full warp ABI: `warp_alloc`, `warp_run`, and `memory`. */
@JsFun(
    "(instance) => (typeof instance.exports.warp_alloc === 'function' && " +
        "typeof instance.exports.warp_run === 'function' && " +
        "instance.exports.memory instanceof WebAssembly.Memory)",
)
private external fun wasmHasWarpAbi(instance: JsAny): Boolean

/**
 * One ABI round-trip: alloc, write args into linear memory, call `warp_run`, unpack the i64 result
 * pointer/length, and copy the result bytes out. A fresh `Uint8Array` view is taken on each access
 * because `warp_alloc`/`warp_run` may grow (and thus detach) the memory buffer. `.slice()` copies the
 * result region out so the returned view survives any later growth.
 */
@JsFun(
    "(instance, argsView) => {" +
        " const exports = instance.exports;" +
        " const len = argsView.length;" +
        " const argPtr = exports.warp_alloc(len);" +
        " new Uint8Array(exports.memory.buffer).set(argsView, argPtr);" +
        " const packed = exports.warp_run(argPtr, len);" +
        " const resPtr = Number(BigInt.asUintN(32, packed >> 32n));" +
        " const resLen = Number(BigInt.asUintN(32, packed));" +
        " return new Uint8Array(exports.memory.buffer, resPtr, resLen).slice();" +
        " }",
)
private external fun wasmRunAbi(instance: JsAny, argsView: JsAny): JsAny

@JsFun("(length) => new Uint8Array(length)")
private external fun newUint8Array(length: Int): JsAny

@JsFun("(view, index, byte) => { view[index] = byte; }")
private external fun uint8ArraySet(view: JsAny, index: Int, byte: Byte)

@JsFun("(view) => view.length")
private external fun uint8ArrayLength(view: JsAny): Int

/** Reads one byte as a SIGNED value (-128..127) so it maps cleanly onto Kotlin [Byte]. */
@JsFun("(view, index) => { const b = view[index]; return b >= 128 ? b - 256 : b; }")
private external fun uint8ArrayGet(view: JsAny, index: Int): Byte

private fun ByteArray.toUint8Array(): JsAny {
    val view = newUint8Array(size)
    for (i in indices) uint8ArraySet(view, i, this[i])
    return view
}

private fun JsAny.toByteArray(): ByteArray {
    val length = uint8ArrayLength(this)
    return ByteArray(length) { uint8ArrayGet(this, it) }
}
