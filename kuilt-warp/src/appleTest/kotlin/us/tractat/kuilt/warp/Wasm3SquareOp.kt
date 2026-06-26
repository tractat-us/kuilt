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
 * Coroutine discipline: [UnconfinedTestDispatcher] + bounded [advanceTimeBy] steps
 * — never [advanceUntilIdle] (anti-entropy timers re-arm forever).
 *
 * NOTE: [wasm3SquareOp] is intentionally a stub (returns zeros) in the *first* commit
 * of this PR to give CI a visibly red proof that the test catches the bug. The real
 * wasm3 wiring lands in the second commit.
 *
 * @see Wasm3RuntimeDispatchTest
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.warp

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
 * The [Op] is created once per [WarpNode] registration and reused across
 * invocations. The wasm3 environment, runtime, and function handle are created
 * eagerly on the first call and retained for the lifetime of the [Op].
 *
 * Intentionally a stub in the first commit: returns a zeroed result. The real
 * wasm3 wiring (m3_ParseModule / m3_LoadModule / m3_FindFunction) lands in the
 * second commit, turning the test green.
 */
internal fun wasm3SquareOp(): Op {
    // RED STUB: return zeros so the test assertion `25 == 0` fails immediately,
    // proving the test catches the defect before wasm3 is wired.
    return Op { args ->
        // Consume args to avoid "unused variable" lint, then return four zero bytes.
        @Suppress("UNUSED_EXPRESSION")
        args
        writeI32Le(0)
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
