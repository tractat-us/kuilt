package us.tractat.kuilt.core

import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy

/**
 * Opaque message moving between peers. The wire layer does not interpret
 * the bytes; that is the consumer's job.
 *
 * `sender` and `sequence` are filled in by the transport when the frame
 * is received; senders leave them unset (null sender, zero sequence) and
 * the local [Seam] stamps them on dispatch.
 *
 * ## Encapsulated offset-view
 *
 * Internally a [Swatch] is backed by a `(ByteArray, offset, length)` triple
 * so that header-stripping in [MuxSeam] and [NamedMux] creates a zero-copy
 * view via [dropFirst]. The backing array **never escapes publicly** — there
 * is no accessor that returns the live backing array. All copies are explicit
 * and named:
 *
 * - [toByteArray] — always allocates; the only way to obtain a `ByteArray`.
 * - [decode] — hands the backing array directly to [BinaryFormat.decodeFromByteArray]
 *   for full-array frames (zero-copy); copies only for sub-views (unavoidable,
 *   same cost as [toByteArray]).
 * - [decodeToString] — zero-copy slice decode via [ByteArray.decodeToString].
 * - [byteAt] / [payloadSize] — zero-copy indexed access.
 *
 * [equals] and [hashCode] compare the logical slice, so a [dropFirst] view
 * equals a freshly-constructed [Swatch] of the same bytes.
 *
 * ## Construction
 *
 * Pass a `ByteArray` to the primary constructor. The [Swatch] takes ownership
 * of that array — callers must not mutate it after construction.
 */
public class Swatch internal constructor(
    /** Backing array — may be larger than the logical payload. */
    internal val data: ByteArray,
    /** Start of the logical payload within [data]. */
    internal val offset: Int,
    /** Length of the logical payload. */
    internal val length: Int,
    public val sender: PeerId?,
    public val sequence: Long,
) {
    /**
     * Primary public constructor: takes ownership of [payload] so the whole
     * array is the logical payload. Normal construction path for senders and
     * for any code that already has a correctly-sized [ByteArray].
     *
     * The caller must not mutate [payload] after construction.
     */
    public constructor(
        payload: ByteArray,
        sender: PeerId? = null,
        sequence: Long = 0,
    ) : this(data = payload, offset = 0, length = payload.size, sender = sender, sequence = sequence)

    /** The number of logical payload bytes. Does not allocate. */
    public val payloadSize: Int get() = length

    /** Returns the byte at logical index [index] without allocating. */
    public fun byteAt(index: Int): Byte = data[offset + index]

    /**
     * Decodes the logical payload using [format] and [deserializer].
     *
     * For a full-array [Swatch] the backing array is handed directly to
     * [BinaryFormat.decodeFromByteArray] — zero allocation. For a sub-view
     * created by [dropFirst] the logical slice is copied first, which is
     * unavoidable.
     */
    public fun <T> decode(format: BinaryFormat, deserializer: DeserializationStrategy<T>): T =
        format.decodeFromByteArray(deserializer, sliceOrData())

    /**
     * Decodes the logical payload bytes as a UTF-8 string without allocating
     * an intermediate [ByteArray] (uses [ByteArray.decodeToString] over the
     * internal slice).
     */
    public fun decodeToString(): String = data.decodeToString(offset, offset + length)

    /**
     * Returns a copy of the logical payload as a fresh [ByteArray].
     *
     * This always allocates. It is the only public way to obtain a [ByteArray]
     * from a [Swatch]; the explicit name makes the allocation visible at the
     * call site.
     */
    public fun toByteArray(): ByteArray = data.copyOfRange(offset, offset + length)

    /**
     * Returns the backing array directly for a full-array frame, or a copy of
     * the logical slice for a sub-view. Used internally by [decode].
     */
    private fun sliceOrData(): ByteArray =
        if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)

    /**
     * Returns a view of this [Swatch] with the first [n] bytes of the logical
     * payload removed, sharing the same backing array without copying.
     *
     * Intended for mux/framing layers that prefix frames with a header: on
     * receive they call `dropFirst(headerSize)` instead of `copyOfRange`, so
     * the strip is zero-copy. The returned [Swatch] has the same [sender] and
     * [sequence].
     *
     * @throws IllegalArgumentException if [n] is negative or exceeds [payloadSize].
     */
    public fun dropFirst(n: Int): Swatch {
        require(n >= 0 && n <= length) { "n=$n out of range [0..$length]" }
        return Swatch(data = data, offset = offset + n, length = length - n, sender = sender, sequence = sequence)
    }

    /**
     * Creates a copy with a different [sequence], sharing the same backing array.
     * Equivalent to the data-class `copy(sequence = …)`.
     */
    public fun copy(sequence: Long): Swatch =
        Swatch(data = data, offset = offset, length = length, sender = sender, sequence = sequence)

    /**
     * Creates a copy with a different [sender], sharing the same backing array.
     */
    public fun copy(sender: PeerId?): Swatch =
        Swatch(data = data, offset = offset, length = length, sender = sender, sequence = sequence)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Swatch) return false
        if (length != other.length) return false
        if (sender != other.sender) return false
        if (sequence != other.sequence) return false
        for (i in 0 until length) {
            if (data[offset + i] != other.data[other.offset + i]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = 1
        for (i in 0 until length) result = 31 * result + data[offset + i].toInt()
        result = 31 * result + (sender?.hashCode() ?: 0)
        result = 31 * result + sequence.hashCode()
        return result
    }

    override fun toString(): String =
        "Swatch(payloadSize=$payloadSize, sender=$sender, sequence=$sequence)"
}

