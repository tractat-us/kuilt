package us.tractat.kuilt.core

/**
 * Opaque message moving between peers. The wire layer does not interpret
 * the bytes; that is :session-protocol's job.
 *
 * `sender` and `sequence` are filled in by the transport when the frame
 * is received; senders leave them unset (null sender, zero sequence) and
 * the local [Seam] stamps them on dispatch.
 */
public data class Swatch(
    val payload: ByteArray,
    val sender: PeerId? = null,
    val sequence: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Swatch) return false
        return payload.contentEquals(other.payload) &&
            sender == other.sender &&
            sequence == other.sequence
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + (sender?.hashCode() ?: 0)
        result = 31 * result + sequence.hashCode()
        return result
    }
}
