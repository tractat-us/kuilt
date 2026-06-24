package us.tractat.kuilt.crdt.internal

/**
 * Canonical MurmurHash3_x86_32 — the 32-bit, x86-optimised variant authored by
 * Austin Appleby (public domain). Single vendored implementation shared by
 * [us.tractat.kuilt.crdt.HyperLogLog], [us.tractat.kuilt.crdt.BloomFilter], and
 * [us.tractat.kuilt.crdt.CountMinSketch].
 *
 * All output is byte-identical on every Kotlin Multiplatform target (JVM, Android,
 * iOS, macOS, wasmJs) because the implementation operates only on `ByteArray` and
 * uses only portable Kotlin integer arithmetic — no platform APIs.
 *
 * Reference: https://github.com/aappleby/smhasher
 *
 * Canonical verification vectors (seed = 0):
 * - `""` (empty)              → `0x00000000`
 * - `{0x00}` (one byte)       → `0x514E28B7`
 * - `"hello"` (UTF-8)         → `0x248BFA47`
 * - `"test"` (UTF-8)          → `0xBA6BD213`
 * - `"café"` (non-ASCII UTF-8) → `0x241C0F08`
 */
internal object Murmur3 {

    private const val C1 = 0xcc9e2d51.toInt()
    private const val C2 = 0x1b873593

    /**
     * Hash a byte array with the given [seed] (default 0).
     * Returns the canonical MurmurHash3_x86_32 result.
     */
    internal fun hash32(data: ByteArray, seed: Int = 0): Int {
        val len = data.size
        val nblocks = len / 4
        var h1 = seed

        // Body — process 4-byte little-endian blocks
        for (i in 0 until nblocks) {
            val base = i * 4
            var k1 = readLittleEndianInt(data, base)

            k1 = mixKey(k1)
            h1 = mixState(h1, k1)
        }

        // Tail — remaining 1–3 bytes
        val tail = nblocks * 4
        val k1 = readTail(data, tail, len and 3)
        if (k1 != 0) {
            h1 = h1 xor mixKey(k1)
        }

        // Finalization
        return fmix32(h1 xor len)
    }

    /**
     * Hash a [String] by encoding it as UTF-8 bytes, then hashing with the given
     * [seed]. Behaviour is determined by the UTF-8 byte sequence, not the
     * platform's native `Char` encoding — results are identical on all targets.
     */
    internal fun hash32(text: String, seed: Int = 0): Int = hash32(text.encodeToByteArray(), seed)

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun readLittleEndianInt(data: ByteArray, base: Int): Int =
        (data[base].toInt() and 0xFF) or
            ((data[base + 1].toInt() and 0xFF) shl 8) or
            ((data[base + 2].toInt() and 0xFF) shl 16) or
            ((data[base + 3].toInt() and 0xFF) shl 24)

    private fun readTail(data: ByteArray, tail: Int, remaining: Int): Int {
        var k1 = 0
        if (remaining >= 3) k1 = k1 xor ((data[tail + 2].toInt() and 0xFF) shl 16)
        if (remaining >= 2) k1 = k1 xor ((data[tail + 1].toInt() and 0xFF) shl 8)
        if (remaining >= 1) k1 = k1 xor (data[tail].toInt() and 0xFF)
        return k1
    }

    /** Mix a block key through the key schedule. */
    private fun mixKey(k: Int): Int = (k * C1).rotateLeft(15) * C2

    /** Mix the key into the running hash state. */
    private fun mixState(h: Int, k: Int): Int = (h xor k).rotateLeft(13) * 5 + 0xe6546b64.toInt()

    /** Final avalanche mix to eliminate bias. */
    private fun fmix32(h: Int): Int {
        val h1 = (h xor (h ushr 16)) * 0x85ebca6b.toInt()
        val h2 = (h1 xor (h1 ushr 13)) * 0xc2b2ae35.toInt()
        return h2 xor (h2 ushr 16)
    }
}
