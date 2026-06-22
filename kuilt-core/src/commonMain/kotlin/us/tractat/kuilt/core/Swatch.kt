package us.tractat.kuilt.core

/**
 * Opaque message moving between peers. The wire layer does not interpret
 * the bytes; that is :session-protocol's job.
 *
 * `sender` and `sequence` are filled in by the transport when the frame
 * is received; senders leave them unset (null sender, zero sequence) and
 * the local [Seam] stamps them on dispatch.
 *
 * ## Offset-view
 *
 * Internally, a [Swatch] may be backed by a larger array with an [offset],
 * so that header-stripping in [MuxSeam] and [NamedMux] creates a view rather
 * than a copy. The [payload] property always returns a fresh [ByteArray]
 * containing only the logical bytes. [equals] and [hashCode] compare the
 * logical slice, so a viewed [Swatch] equals a freshly-copied one of the
 * same bytes.
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
     * Primary public constructor: wraps [payload] with no offset so the whole
     * array is the logical payload. Normal construction path for senders and
     * for any code that already has a correctly-sized [ByteArray].
     */
    public constructor(
        payload: ByteArray,
        sender: PeerId? = null,
        sequence: Long = 0,
    ) : this(data = payload, offset = 0, length = payload.size, sender = sender, sequence = sequence)

    /**
     * The logical payload bytes. Each access allocates a fresh [ByteArray]
     * containing exactly the logical slice. For hot paths that only need
     * byte-level access (index, length, comparison), prefer [byteAt] and
     * [payloadSize] to avoid the allocation.
     */
    public val payload: ByteArray get() = data.copyOfRange(offset, offset + length)

    /** The number of logical payload bytes. Does not allocate. */
    public val payloadSize: Int get() = length

    /** Returns the byte at logical index [index] without allocating. */
    public fun byteAt(index: Int): Byte = data[offset + index]

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
        "Swatch(payload=${payload.contentToString()}, sender=$sender, sequence=$sequence)"
}

