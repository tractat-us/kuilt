package us.tractat.kuilt.warp

// Stub — returns zeros; will be replaced by the real Chicory-backed implementation.
// Exists to give the red test a target that compiles but produces wrong results (0 ≠ 25).
internal fun chicorySquareOp(): Op = Op { _ -> writeI32Le(0) }

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
