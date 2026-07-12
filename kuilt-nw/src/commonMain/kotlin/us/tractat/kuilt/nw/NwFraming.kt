package us.tractat.kuilt.nw

import us.tractat.kuilt.stream.DEFAULT_MAX_FRAME_SIZE
import us.tractat.kuilt.stream.FrameTooLargeException

/**
 * Encode [payload] as `[len_be32][payload]` — a 4-byte big-endian length prefix followed by
 * the payload bytes. Byte-identical to what `:kuilt-stream`'s `framed()` writes via
 * `sink.writeInt(frame.size)`, so the two framings are wire-compatible.
 *
 * Throws [FrameTooLargeException] before allocating the output array if [payload] exceeds
 * [maxFrameSize].
 */
public fun encodeFrame(payload: ByteArray, maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE): ByteArray {
    if (payload.size > maxFrameSize) throw FrameTooLargeException(payload.size, maxFrameSize)
    val len = payload.size
    val out = ByteArray(4 + len)
    out[0] = (len ushr 24 and 0xFF).toByte()
    out[1] = (len ushr 16 and 0xFF).toByte()
    out[2] = (len ushr 8 and 0xFF).toByte()
    out[3] = (len and 0xFF).toByte()
    payload.copyInto(out, destinationOffset = 4)
    return out
}

/**
 * Stateful, incremental decoder for the 4-byte-big-endian-length-prefixed frame format
 * produced by [encodeFrame] (wire-compatible with `:kuilt-stream`'s `framed()`).
 *
 * Push-based, unlike `framed()`'s pull-based `Source.readInt`/`readByteArray`: feed arbitrary
 * byte chunks — as delivered off the wire, e.g. from `NwApi.bytesReceived` — to [decode], which
 * returns whichever frames that chunk completed. A chunk may contain a partial frame, exactly
 * one frame, several frames, or a frame split across many chunks; bytes that don't yet complete
 * a frame are buffered internally and carried forward to the next [decode] call.
 *
 * **Single-reader contract:** [NwFramer] holds mutable buffer state and is deliberately NOT
 * thread-safe — it is designed to be driven by a single reader coroutine (one [NwFramer] per
 * connection, fed serially from that connection's receive path). Do not call [decode]
 * concurrently or share one instance across connections.
 */
public class NwFramer(private val maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE) {

    private var buffered: ByteArray = ByteArray(0)

    /**
     * Feed the next [chunk] of bytes off the wire. Returns the frames this chunk completed, in
     * order (possibly empty). Throws [FrameTooLargeException] before allocating the payload if a
     * decoded length prefix is negative or exceeds [maxFrameSize].
     */
    public fun decode(chunk: ByteArray): List<ByteArray> {
        buffered = if (buffered.isEmpty()) chunk else buffered + chunk
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (buffered.size - offset >= 4) {
            val len = ((buffered[offset].toInt() and 0xFF) shl 24) or
                ((buffered[offset + 1].toInt() and 0xFF) shl 16) or
                ((buffered[offset + 2].toInt() and 0xFF) shl 8) or
                (buffered[offset + 3].toInt() and 0xFF)
            if (len < 0 || len > maxFrameSize) throw FrameTooLargeException(len, maxFrameSize)
            if (buffered.size - offset < 4 + len) break
            frames += buffered.copyOfRange(offset + 4, offset + 4 + len)
            offset += 4 + len
        }
        if (offset > 0) buffered = buffered.copyOfRange(offset, buffered.size)
        return frames
    }
}
