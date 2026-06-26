package us.tractat.kuilt.warp

/**
 * Marshals the FedAvg training kernel's linear-memory ABI payloads (see the wire layout in the F2
 * plan/spec). All integers and IEEE-754 f64 values are little-endian, so the bytes are
 * bit-deterministic across platforms — matching [FedAvg]'s reproducibility requirement.
 *
 * Dimension is fixed at D = 2 (one feature + bias) for the v1 kernel.
 *
 * @sample us.tractat.kuilt.warp.sampleFedAvgKernelCodec
 */
public object FedAvgKernelCodec {

    private const val MAGIC: Int = 0x46415631
    private const val DIM: Int = 2
    private const val HEADER_BYTES: Int = 40
    private const val EXAMPLE_BYTES: Int = 16

    /** Length in bytes of the kernel's output region. */
    public const val RESULT_LEN: Int = 32

    /** Encodes `(weights, examples, learnRate)` into the kernel input layout. */
    public fun encodeInput(
        weights: List<Double>,
        examples: List<Pair<Double, Double>>,
        learnRate: Double,
    ): ByteArray {
        require(weights.size == DIM) { "v1 kernel requires D=$DIM weights, got ${weights.size}" }
        val out = ByteArray(HEADER_BYTES + examples.size * EXAMPLE_BYTES)
        putU32(out, 0, MAGIC)
        putU32(out, 4, DIM)
        putF64(out, 8, learnRate)
        putU32(out, 16, examples.size)
        putU32(out, 20, 0)
        putF64(out, 24, weights[0])
        putF64(out, 32, weights[1])
        var off = HEADER_BYTES
        for ((x, y) in examples) {
            putF64(out, off, x); putF64(out, off + 8, y); off += EXAMPLE_BYTES
        }
        return out
    }

    /** Decodes the kernel output region into a [TrainingUpdate]; fails loud on a bad shape. */
    public fun decodeOutput(bytes: ByteArray): TrainingUpdate {
        require(bytes.size >= RESULT_LEN) { "output too short: ${bytes.size} < $RESULT_LEN" }
        require(getU32(bytes, 0) == MAGIC) { "bad output magic" }
        val dim = getU32(bytes, 4)
        require(dim == DIM) { "unexpected output dim $dim" }
        val count = getU64(bytes, 8)
        return TrainingUpdate(count, listOf(getF64(bytes, 16), getF64(bytes, 24)))
    }

    /** Test-only: builds an output region matching the kernel's, for round-trip tests. */
    public fun encodeOutputForTest(sampleCount: Long, weights: List<Double>): ByteArray {
        val out = ByteArray(RESULT_LEN)
        putU32(out, 0, MAGIC); putU32(out, 4, DIM); putU64(out, 8, sampleCount)
        putF64(out, 16, weights[0]); putF64(out, 24, weights[1])
        return out
    }

    private fun putU32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte()
        b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }
    private fun putU64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun putF64(b: ByteArray, o: Int, v: Double) = putU64(b, o, v.toRawBits())
    private fun getU32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun getU64(b: ByteArray, o: Int): Long {
        var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v
    }
    private fun getF64(b: ByteArray, o: Int): Double = Double.fromBits(getU64(b, o))
}
