package us.tractat.kuilt.warp

/**
 * A guest-controlled window `[ptr, ptr + len)` of WASM linear memory, decoded from a packed
 * `warp_run` result by [unpackWarpResult].
 *
 * Both words are **unsigned** 32-bit values carried as non-negative [Long]s (`0..0xFFFF_FFFF`).
 * They must never be narrowed to a signed [Int] before bounds validation: a value with bit 31
 * set would wrap negative, slip past a naive bounds check, and index host memory (a sandbox
 * escape) or hit `ByteArray(negative)` (a raw exception escaping [WasmException]).
 *
 * @see unpackWarpResult
 * @see requireInBounds
 * @see decodeWarpResult
 */
public class GuestRegion(public val ptr: Long, public val len: Long)

/**
 * Unpacks a `warp_run` result — `(resPtr << 32) | (resLen & 0xFFFF_FFFF)` — into its unsigned
 * pointer/length words.
 *
 * This is the one shared implementation of the warp-ABI result unpack: every [WasmRuntime]
 * implementation whose decode runs in Kotlin must use it (plus [requireInBounds] or the
 * composed [decodeWarpResult]) rather than re-deriving the arithmetic, because the unsigned
 * treatment is the safety-critical part and has been hand-rolled wrong before.
 */
public fun unpackWarpResult(packed: Long): GuestRegion =
    GuestRegion(ptr = packed ushr 32, len = packed and 0xFFFF_FFFFL)

/**
 * Validates a guest-controlled window `[ptr, ptr + len)` against the live linear-memory size,
 * in [Long] space so no operand can have sign-wrapped.
 *
 * Rejects — as a [WasmExecutionException], a guest runtime fault, never an OOB host-memory
 * access or a raw non-[WasmException] — any window where `ptr`/`len` is negative, `len` cannot
 * fit a [ByteArray] (`len > Int.MAX_VALUE`), or `ptr + len` exceeds [memorySize].
 *
 * @param memorySize the *current* linear-memory size in bytes, fetched after the guest call
 *   that produced the window (`warp_alloc` or `warp_run` may have grown memory).
 */
public fun requireInBounds(ptr: Long, len: Long, memorySize: Long) {
    if (ptr < 0L || len < 0L || len > Int.MAX_VALUE.toLong() || ptr + len > memorySize) {
        throw WasmExecutionException(
            "WASM memory access out of bounds: window [$ptr, ${ptr + len}) outside [0, $memorySize)",
        )
    }
}

/**
 * The bounds-checked warp-ABI result decode: [unpackWarpResult] + [requireInBounds] + one
 * [read] of the validated window.
 *
 * [read] receives an already-validated `(ptr, len)` — both non-negative, `len <= Int.MAX_VALUE`,
 * `ptr + len <= memorySize` — and supplies only the platform-specific memory access. It is never
 * invoked for an out-of-bounds window.
 *
 * @param packed the raw `i64` returned by `warp_run`.
 * @param memorySize the current linear-memory size in bytes (see [requireInBounds]).
 */
public inline fun decodeWarpResult(
    packed: Long,
    memorySize: Long,
    read: (ptr: Long, len: Long) -> ByteArray,
): ByteArray {
    val region = unpackWarpResult(packed)
    requireInBounds(region.ptr, region.len, memorySize)
    return read(region.ptr, region.len)
}
