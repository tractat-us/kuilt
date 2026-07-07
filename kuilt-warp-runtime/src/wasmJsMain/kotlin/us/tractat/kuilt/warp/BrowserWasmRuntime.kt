@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.JsFun
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Browser (wasmJs) implementation of [WasmRuntime] backed by the native WebAssembly JS API.
 *
 * [load] compiles the module and runs the load-time sandbox guards **statically** — it never
 * instantiates on the main thread (instantiation runs a module's `(start)` function; see the
 * execution-timeout KDoc below). The returned [Op] drives every invocation over the module's
 * linear memory via the warp ABI:
 * - `warp_alloc(len: i32) -> i32`   — guest returns a writable pointer for `len` bytes.
 * - `warp_run(ptr: i32, len: i32) -> i64` — guest processes `memory[ptr..ptr+len)` and returns a
 *   packed pointer/length: `(resPtr.toLong() shl 32) or (resLen.toLong() and 0xFFFF_FFFF)`.
 *
 * **Sandbox guards (load-time — the `ready` part).** These mirror the JVM [ChicoryWasmRuntime]
 * semantics over the JS API:
 * - *Import rejection* — any declared import is a capability violation. `WebAssembly.Module.imports`
 *   is inspected up front; a non-empty list throws [WasmLoadException]. (Instantiating with an empty
 *   imports object would also fail, but rejecting up front gives a deterministic error.)
 * - *Memory ceiling* — the binary's memory section is parsed and checked against
 *   [WasmSandboxConfig.maxMemoryPages]; a module is rejected with [WasmLoadException] if its declared
 *   initial exceeds the cap, its explicit max exceeds the cap, **or it declares no explicit max at
 *   all**. The JS API exposes no declared memory limits and cannot re-impose a max after compile,
 *   so a no-max module could otherwise `memory.grow` unbounded to ~4 GiB — a memory-exhaustion
 *   DoS. Requiring kernels to declare a bounded max ≤ cap closes that hole (and is the uniform
 *   contract every [WasmRuntime] impl enforces — see [WasmRuntime]'s memory-ceiling KDoc): the
 *   browser then enforces the declared max at runtime (a `memory.grow` past it fails).
 * - *Malformed bytes* — `new WebAssembly.Module(bytes)` throws on invalid WASM → [WasmLoadException].
 * - *Missing ABI export* — a well-formed module lacking `warp_alloc`/`warp_run`/`memory` throws
 *   [WasmLoadException], NOT a raw JS error. This preserves the property [ChicoryWasmRuntime]
 *   documents: a non-[WasmException] escaping `load` would bypass the executor's terminal-error
 *   handling and trigger an anti-entropy retry storm on a verified-but-broken kernel (a
 *   remotely-triggerable DoS). Checked statically via `WebAssembly.Module.exports` — never by
 *   instantiating on the main thread, which would run a hostile `(start)` function outside any
 *   budget (#1290/#1298): a `(start)` CPU bomb would wedge the un-pre-emptible main thread at
 *   `load`. Instantiation happens only inside the worker (below), so a trapping or spinning
 *   `(start)` surfaces as a budget-bounded run-time [WasmExecutionException] on the first
 *   invocation — matching the native runtime, whose engine also runs `(start)` lazily at the
 *   first guest call.
 *
 * **Execution timeout — the CPU-bomb defense.** Browser JS is single-threaded, so a synchronous
 * WASM runaway on the main thread could never be pre-empted. The guest therefore executes in a
 * dedicated **Web Worker** per [Op] (spawned lazily on first invocation from an inline
 * `Blob`-URL script, re-instantiating the already-validated bytes): each ABI round trip is raced
 * against a wall-clock `setTimeout` of [WasmSandboxConfig.executionTimeout] on the main thread,
 * and on expiry the worker is `terminate()`d — a hard pre-emptive kill the guest cannot resist —
 * surfacing [WasmExecutionException] naming the exceeded budget. A later invocation on the same
 * [Op] transparently respawns a fresh worker (guest linear-memory state does not survive a
 * timeout; per the [Op] contract an op must not rely on state outliving a call). The race timer
 * is pure JS wall-clock, deliberately independent of any (possibly virtual-time) coroutine
 * scheduler driving the caller. The timer starts when the run request is posted, so a CPU-bomb in
 * a module's `(start)` function (run at worker instantiation, before the first ABI call) is
 * bounded by the first invocation's budget too.
 *
 * **Run-time trap** — any guest error inside the worker (trap, `unreachable`, OOB) surfaces as
 * [WasmExecutionException], preserving the message.
 *
 * Invocations on one [Op] are serialized by a per-op [Mutex] (mirroring [ChicoryWasmRuntime]'s
 * invoke mutex) so a queued innocent call never has its budget consumed by a predecessor still on
 * the wire. Workers are never explicitly closed — like the JVM runtime's guest executor they live
 * for the op's lifetime (the page session).
 *
 * @param config Sandbox configuration (memory cap, execution timeout). Both are enforced.
 */
public class BrowserWasmRuntime(
    public val config: WasmSandboxConfig = WasmSandboxConfig(),
) : WasmRuntime {

    override fun load(bytes: ByteArray): Op {
        val module = compileModule(bytes)
        rejectImports(module)
        rejectOversizeMemory(bytes)
        requireWarpAbi(module)
        return WorkerBackedOp(bytes.toUint8Array(), config)
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
        if (limits.maxPages == null) {
            throw WasmLoadException(
                "module declares memory with no explicit max (unbounded growth not allowed); " +
                    "declare a max <= ${config.maxMemoryPages} pages",
            )
        }
        if (limits.maxPages > config.maxMemoryPages) {
            throw WasmLoadException(
                "module memory exceeds sandbox cap: declared max ${limits.maxPages} pages > " +
                    "${config.maxMemoryPages} pages",
            )
        }
    }

    private fun requireWarpAbi(module: JsAny) {
        if (!wasmHasWarpAbi(module)) {
            throw WasmLoadException("missing ABI export (warp_alloc/warp_run/memory)")
        }
    }
}

/**
 * An [Op] whose guest executes in a dedicated Web Worker so the main thread can pre-empt it
 * (see [BrowserWasmRuntime]'s execution-timeout KDoc). The worker is spawned lazily on first
 * invocation and respawned after a timeout kill. [moduleBytes] passed the static load-time
 * guards, but worker-side instantiation is the FIRST execution of the module's `(start)`
 * function — a trap there surfaces as [WasmExecutionException] ("guest instantiation failed")
 * and a spinning `(start)` is terminated by the first invocation's budget timer.
 */
private class WorkerBackedOp(
    private val moduleBytes: JsAny,
    private val config: WasmSandboxConfig,
) : Op {

    /** Serializes invocations so a queued call's budget measures execution, not queue wait. */
    private val invokeMutex = Mutex()

    /** The live guest-executor handle, or null before first use / after a timeout kill. */
    private var executor: JsAny? = null

    override suspend fun invoke(args: ByteArray): ByteArray = invokeMutex.withLock {
        val handle = executor?.takeIf { !guestExecutorIsDead(it) }
            ?: guestExecutorCreate(moduleBytes).also { executor = it }
        val timeoutMs = config.executionTimeout.inWholeMilliseconds.coerceAtLeast(1L).toInt()
        val outcome = guestExecutorRun(handle, args.toUint8Array(), timeoutMs).await()
        when (outcomeKind(outcome)) {
            "ok" -> outcomeResult(outcome).toByteArray()
            "timeout" -> {
                executor = null
                throw WasmExecutionException("WASM execution exceeded ${config.executionTimeout}")
            }
            else -> throw WasmExecutionException("WASM kernel trapped: ${outcomeMessage(outcome)}")
        }
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

// ── WebAssembly JS API interop (load-time guards) ───────────────────────────────────────────────

/** Synchronously compile a WASM module from a Uint8Array. Throws on malformed bytes. */
@JsFun("(bytes) => new WebAssembly.Module(bytes)")
private external fun wasmCompile(bytes: JsAny): JsAny

/** Number of declared imports — non-zero means a capability violation. */
@JsFun("(module) => WebAssembly.Module.imports(module).length")
private external fun wasmImportCount(module: JsAny): Int

/**
 * True iff the module's export section declares the full warp ABI: `warp_alloc` and `warp_run`
 * functions plus a `memory`. A **static** check over `WebAssembly.Module.exports` — deliberately
 * no `new WebAssembly.Instance(...)` on the main thread: instantiation runs a module's `(start)`
 * function, and a CPU-bomb `(start)` would wedge the un-pre-emptible main thread. The guest is
 * only ever instantiated inside the terminate()-able worker, under an invocation budget.
 */
@JsFun(
    """(module) => {
        const kinds = {};
        for (const e of WebAssembly.Module.exports(module)) kinds[e.name] = e.kind;
        return kinds['warp_alloc'] === 'function' && kinds['warp_run'] === 'function' &&
            kinds['memory'] === 'memory';
    }""",
)
private external fun wasmHasWarpAbi(module: JsAny): Boolean

// ── Worker guest executor ───────────────────────────────────────────────────────────────────────
// The guest runs OFF the main thread so a runaway kernel can be pre-empted by worker.terminate().
// The worker script is inlined via a Blob URL: it instantiates the (already-validated) module and
// services `run` requests over the warp ABI, posting back {id, ok, result|message}. The main-side
// handle keeps a pending map keyed by request id; each request races a setTimeout that terminates
// the worker, marks the handle dead, and resolves {kind:'timeout'}. Outcomes are always RESOLVED
// (never rejected) as {kind:'ok'|'timeout'|'error'} so Kotlin sees one uniform shape.

/**
 * Spawns the guest Worker for one op and posts the module bytes for instantiation.
 * Returns the executor handle `{worker, pending, nextId, dead}`.
 */
@JsFun(
    """(bytes) => {
        const src =
            "let instance = null;\n" +
            "let loadFailure = null;\n" +
            "onmessage = function (e) {\n" +
            "  const m = e.data;\n" +
            "  if (m.kind === 'load') {\n" +
            "    try { instance = new WebAssembly.Instance(new WebAssembly.Module(m.bytes), {}); }\n" +
            "    catch (err) { loadFailure = String((err && err.message) || err); }\n" +
            "    return;\n" +
            "  }\n" +
            "  try {\n" +
            "    if (instance === null) throw new Error('guest instantiation failed: ' + loadFailure);\n" +
            "    const exports = instance.exports;\n" +
            "    const args = m.args;\n" +
            "    const argPtr = exports.warp_alloc(args.length);\n" +
            "    new Uint8Array(exports.memory.buffer).set(args, argPtr);\n" +
            "    const packed = exports.warp_run(argPtr, args.length);\n" +
            "    const resPtr = Number(BigInt.asUintN(32, packed >> 32n));\n" +
            "    const resLen = Number(BigInt.asUintN(32, packed));\n" +
            "    const result = new Uint8Array(exports.memory.buffer, resPtr, resLen).slice();\n" +
            "    postMessage({ id: m.id, ok: true, result: result });\n" +
            "  } catch (err) {\n" +
            "    postMessage({ id: m.id, ok: false, message: String((err && err.message) || err) });\n" +
            "  }\n" +
            "};\n";
        const url = URL.createObjectURL(new Blob([src], { type: 'application/javascript' }));
        const worker = new Worker(url);
        URL.revokeObjectURL(url);
        const handle = { worker: worker, pending: new Map(), nextId: 0, dead: false };
        worker.onmessage = (e) => {
            const entry = handle.pending.get(e.data.id);
            if (!entry) return;
            handle.pending.delete(e.data.id);
            clearTimeout(entry.timer);
            entry.resolve(e.data.ok
                ? { kind: 'ok', result: e.data.result }
                : { kind: 'error', message: e.data.message });
        };
        worker.postMessage({ kind: 'load', bytes: bytes });
        return handle;
    }""",
)
private external fun guestExecutorCreate(bytes: JsAny): JsAny

/**
 * Posts one ABI round trip to the guest worker, racing a wall-clock timeout that terminates the
 * worker (and marks the handle dead) on expiry. Always resolves — never rejects — with
 * `{kind:'ok', result}` / `{kind:'timeout'}` / `{kind:'error', message}`.
 */
@JsFun(
    """(handle, args, timeoutMs) => new Promise((resolve) => {
        const id = handle.nextId++;
        const timer = setTimeout(() => {
            handle.pending.delete(id);
            handle.dead = true;
            handle.worker.terminate();
            resolve({ kind: 'timeout' });
        }, timeoutMs);
        handle.pending.set(id, { resolve: resolve, timer: timer });
        handle.worker.postMessage({ kind: 'run', id: id, args: args });
    })""",
)
private external fun guestExecutorRun(handle: JsAny, args: JsAny, timeoutMs: Int): Promise<JsAny?>

/** True once the handle's worker has been terminated by a timeout — it must be respawned. */
@JsFun("(handle) => handle.dead")
private external fun guestExecutorIsDead(handle: JsAny): Boolean

@JsFun("(outcome) => outcome.kind")
private external fun outcomeKind(outcome: JsAny?): String

@JsFun("(outcome) => outcome.result")
private external fun outcomeResult(outcome: JsAny?): JsAny

@JsFun("(outcome) => outcome.message")
private external fun outcomeMessage(outcome: JsAny?): String

// ── Uint8Array bridging ─────────────────────────────────────────────────────────────────────────

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
