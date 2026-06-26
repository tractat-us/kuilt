/**
 * C3 go/no-go: real wasm execution via wasm3 on Apple Kotlin/Native targets.
 *
 * Loads the `square` kernel (43-byte wasm binary, same kernel as [ChicorySquareOp])
 * via wasm3 and returns an [Op] that calls it once per invocation. Args and result
 * are 4-byte little-endian i32.
 *
 * The kernel: `square(n: i32) = n * n`. SHA-256 pin:
 * `c853de413e6d8e118a0fd95e3ff2aafdbd65b311744a5731d4256362d41fe897`
 *
 * Kernel provenance: same `square.wat` as the JVM proof (#918). Embedded as a
 * `byteArrayOf(...)` literal (no K/N classpath resources; K/N doesn't support
 * resource loading the way JVM does).
 *
 * wasm3 lifecycle: the [IM3Environment] and [IM3Runtime] are created once when
 * [wasm3SquareOp] is called (once per [WarpNode] registration) and retained for
 * the lifetime of the [Op]. The wasm bytes are pinned via [ByteArray.pin] so the
 * wasm3 runtime can hold a stable pointer into them for the module lifetime
 * (wasm3 docs: "i_wasmBytes data must be persistent during the lifetime of the
 * module"). The pin is intentionally not released — test-only, process-lifetime.
 *
 * @see Wasm3RuntimeDispatchTest
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.warp

import kotlinx.cinterop.*
import wasm3.IM3Function
import wasm3.IM3FunctionVar
import wasm3.IM3ModuleVar
import wasm3.m3_FindFunction
import wasm3.m3_LoadModule
import wasm3.m3_NewEnvironment
import wasm3.m3_NewRuntime
import wasm3.m3_ParseModule
import wasm3.wasm3_call_i32_ret_i32

// 43 bytes — square(n: i32) = n * n; SHA-256 pin:
// c853de413e6d8e118a0fd95e3ff2aafdbd65b311744a5731d4256362d41fe897
private val SQUARE_WASM_BYTES = byteArrayOf(
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x06, 0x01, 0x60,
    0x01, 0x7f, 0x01, 0x7f, 0x03, 0x02, 0x01, 0x00, 0x07, 0x0a, 0x01, 0x06,
    0x73, 0x71, 0x75, 0x61, 0x72, 0x65, 0x00, 0x00, 0x0a, 0x09, 0x01, 0x07,
    0x00, 0x20, 0x00, 0x20, 0x00, 0x6c, 0x0b,
)

/**
 * Returns an [Op] backed by wasm3 that executes `square(n) = n * n`.
 *
 * The wasm3 environment, runtime, and function handle are created eagerly and
 * retained for the [Op]'s lifetime. The wasm bytes are pinned to a stable
 * native address (wasm3 requirement) and the pin is never released (test-only).
 *
 * Red → green: first commit returned zeros; this commit wires real wasm3.
 */
internal fun wasm3SquareOp(): Op {
    val env = checkNotNull(m3_NewEnvironment()) { "wasm3: m3_NewEnvironment returned null" }
    val runtime = checkNotNull(m3_NewRuntime(env, 64u * 1024u, null)) {
        "wasm3: m3_NewRuntime returned null"
    }

    // Pin wasm bytes so wasm3 can hold a stable pointer into them for the
    // module's lifetime (wasm3 docs require i_wasmBytes to be persistent).
    // The pin is intentionally not released — test-only, process-lifetime.
    val pinnedBytes = SQUARE_WASM_BYTES.pin()

    val func: IM3Function = memScoped {
        val moduleRef = alloc<IM3ModuleVar>()
        val r1 = m3_ParseModule(
            env,
            moduleRef.ptr,
            pinnedBytes.addressOf(0).reinterpret<UByteVar>(),
            SQUARE_WASM_BYTES.size.toUInt(),
        )
        check(r1 == null) { "wasm3: m3_ParseModule failed: ${r1?.toKString()}" }

        val r2 = m3_LoadModule(runtime, moduleRef.value)
        check(r2 == null) { "wasm3: m3_LoadModule failed: ${r2?.toKString()}" }

        val funcRef = alloc<IM3FunctionVar>()
        val r3 = m3_FindFunction(funcRef.ptr, runtime, "square")
        check(r3 == null) { "wasm3: m3_FindFunction failed: ${r3?.toKString()}" }
        checkNotNull(funcRef.value) { "wasm3: square function pointer is null after FindFunction" }
    }

    return Op { args ->
        val n = readI32Le(args)
        memScoped {
            val result = alloc<IntVar>()
            val r = wasm3_call_i32_ret_i32(func, n, result.ptr)
            check(r == null) { "wasm3: square($n) failed: ${r?.toKString()}" }
            writeI32Le(result.value)
        }
    }
}

// 4-byte little-endian i32 codec — duplicated from the JVM proof to keep this
// apple-only source set self-contained (no shared commonTest source for LE codecs).

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
