package us.tractat.kuilt.warp

// ── Stub — always returns 0 so the test assertion (square(5)==25) fails red. ─
// The real WebAssembly.Module/Instance interop follows in the next commit.

/**
 * Stub [browserSquareOp]: returns zero for any input.
 * The C3·browser red-test gate: [BrowserWasmRuntimeDispatchTest] asserts `square(5) == 25`;
 * this stub makes that assertion fail, proving the test is wired before the real kernel runs.
 */
internal fun browserSquareOp(): Op = Op { _ -> writeI32Le(0) }

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
