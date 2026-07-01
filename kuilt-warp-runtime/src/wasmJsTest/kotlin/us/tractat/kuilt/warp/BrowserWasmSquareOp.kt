@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.warp

import kotlin.JsFun
import kotlin.js.JsAny

// ── Square wasm kernel bytes ───────────────────────────────────────────────────
// Same 43-byte binary as jvmTest/resources/us/tractat/kuilt/warp/square.wasm (#918).
// Embedded as a literal — classpath-resource loading is not available in the browser.
//
// Provenance: square(n: i32) = n * n
//   (func $square (export "square") (param i32) (result i32)
//     local.get 0 / local.get 0 / i32.mul)
//
// To reproduce: wat2wasm square.wat -o square.wasm
// To verify:    wasm-objdump -x square.wasm
private val SQUARE_WASM: ByteArray = byteArrayOf(
    // magic + version
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
    // type section: (func (param i32) (result i32))
    0x01, 0x06, 0x01, 0x60, 0x01, 0x7f, 0x01, 0x7f,
    // function section: 1 function → type 0
    0x03, 0x02, 0x01, 0x00,
    // export section: "square" (6 bytes) → function 0
    0x07, 0x0a, 0x01, 0x06, 0x73, 0x71, 0x75, 0x61, 0x72, 0x65, 0x00, 0x00,
    // code section: local.get 0 / local.get 0 / i32.mul / end
    0x0a, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x00, 0x6c, 0x0b,
)

// ── Uint8Array bridge helpers ──────────────────────────────────────────────────
// Kotlin/Wasm JS interop only accepts external, primitive, string, and function types
// across the boundary — not ByteArray. We fill a JS Uint8Array byte-by-byte
// (same pattern as RtcExternals.kt in kuilt-webrtc).

@JsFun("(length) => new Uint8Array(length)")
private external fun newUint8Array(length: Int): JsAny

@JsFun("(view, index, byte) => { view[index] = byte; }")
private external fun uint8ArraySet(view: JsAny, index: Int, byte: Byte)

private fun ByteArray.toUint8Array(): JsAny {
    val view = newUint8Array(size)
    for (i in indices) uint8ArraySet(view, i, this[i])
    return view
}

// ── WebAssembly JS API interop ─────────────────────────────────────────────────
// Synchronous Module + Instance constructors are available in all modern browsers
// for small kernels. Avoids Promise/await complexity for this 43-byte proof kernel.

/** Synchronously compile and instantiate a wasm module from a Uint8Array [bytes]. */
@JsFun("(bytes) => { const m = new WebAssembly.Module(bytes); return new WebAssembly.Instance(m); }")
private external fun wasmInstantiate(bytes: JsAny): JsAny

/** Call the exported `square` function on a wasm [instance] with argument [n]. */
@JsFun("(instance, n) => instance.exports.square(n)")
private external fun wasmCallSquare(instance: JsAny, n: Int): Int

// ── Op ────────────────────────────────────────────────────────────────────────

/**
 * Loads the embedded [SQUARE_WASM] kernel via the browser [WebAssembly] JS API and returns
 * a warp [Op] that calls `square(n: i32) → i32` (`n * n`).
 *
 * The wasm [Instance] is created eagerly when [browserSquareOp] is called (once per
 * [WarpNode] registration) and reused across invocations. Args and result are 4-byte
 * little-endian i32 — identical shape to the JVM Chicory variant (#918).
 */
internal fun browserSquareOp(): Op {
    val instance = wasmInstantiate(SQUARE_WASM.toUint8Array())
    return Op { args -> writeI32Le(wasmCallSquare(instance, readI32Le(args))) }
}

// ── Little-endian i32 encode/decode ───────────────────────────────────────────
// Same helpers as ChicorySquareOp.kt (jvmTest); duplicated per source-set isolation.

internal fun readI32Le(bytes: ByteArray): Int =
    (bytes[0].toInt() and 0xFF) or
        ((bytes[1].toInt() and 0xFF) shl 8) or
        ((bytes[2].toInt() and 0xFF) shl 16) or
        ((bytes[3].toInt() and 0xFF) shl 24)

internal fun writeI32Le(n: Int): ByteArray = byteArrayOf(
    (n and 0xFF).toByte(),
    ((n shr 8) and 0xFF).toByte(),
    ((n shr 16) and 0xFF).toByte(),
    ((n shr 24) and 0xFF).toByte(),
)
