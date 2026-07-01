package us.tractat.kuilt.warp

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser

/**
 * Loads `square.wasm` via Chicory and returns a warp [Op] that calls the kernel once per invoke.
 *
 * The Chicory [Instance] is created eagerly when [chicorySquareOp] is called (once per [WarpNode]
 * registration) and reused across invocations. Args and result are 4-byte little-endian i32.
 *
 * The kernel: `square(n: i32) = n * n` — the C3 go/no-go function.
 */
internal fun chicorySquareOp(): Op {
    val wasmBytes = checkNotNull(
        ChicoryResources::class.java.getResourceAsStream("/us/tractat/kuilt/warp/square.wasm"),
    ) { "square.wasm not found on classpath at /us/tractat/kuilt/warp/square.wasm" }
        .readBytes()
    val module = Parser.parse(wasmBytes)
    val instance = Instance.builder(module).build()
    val squareFn = instance.export("square")
    return Op { args -> writeI32Le(squareFn.apply(readI32Le(args).toLong())[0].toInt()) }
}

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

// Named anchor for classpath-relative resource loading (avoids anonymous-class name instability).
private object ChicoryResources
