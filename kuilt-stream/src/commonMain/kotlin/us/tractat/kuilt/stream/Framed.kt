package us.tractat.kuilt.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.EOFException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import us.tractat.kuilt.core.fabric.Connection

/** Default maximum frame size (16 MiB). */
public const val DEFAULT_MAX_FRAME_SIZE: Int = 16 * 1024 * 1024

/**
 * Thrown when a frame exceeds [framed]'s [maxFrameSize], in either direction: an oversize
 * `send` throws before writing, and a received length prefix over the limit throws before any
 * allocation is performed (the flow terminates immediately).
 */
public class FrameTooLargeException(size: Int, max: Int) :
    Exception("frame length $size exceeds max $max")

/**
 * Adapt a kotlinx-io [Source]/[Sink] byte-stream into a message [Connection] using a
 * 4-byte big-endian length prefix per frame.
 *
 * - **Framing:** each `send` writes a 4-byte int (big-endian) followed by [frame.size] bytes.
 * - **Reassembly:** [Source.readByteArray] blocks the collecting coroutine until all bytes
 *   arrive — the stream side is pull-based. Real transports (TCP) collect on an IO dispatcher.
 * - **Oversize protection (symmetric):** both directions are checked against [maxFrameSize].
 *   An oversize `send` throws [FrameTooLargeException] before writing; on read, a hostile
 *   prefix throws [FrameTooLargeException] before any allocation.
 * - **Clean EOF only at a frame boundary:** a stream that ends exactly between two frames closes
 *   [incoming] normally. A stream that ends anywhere else — inside a length prefix as well as
 *   inside a body — is a truncated stream and propagates as [EOFException]. A close at a frame
 *   boundary is the wire's only graceful leave, so a cut stream must never read as one.
 *
 * **Assumption:** the provided [Source] is backed by a hot (buffered) stream
 * (e.g. a Ktor read channel or an in-memory [kotlinx.io.Buffer]). Cold/non-buffered
 * sources that do not allow multiple independent reads would need a single-reader adapter.
 */
public fun framed(
    source: Source,
    sink: Sink,
    maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE,
): Connection = FramedConnection(source, sink, maxFrameSize)

private class FramedConnection(
    private val source: Source,
    private val sink: Sink,
    private val maxFrameSize: Int,
) : Connection {
    // Publishing the ceiling is what lets the seam above report a payload budget instead of
    // leaving every caller to discover it as a FrameTooLargeException (#2047).
    override val maxFrameBytes: Int = maxFrameSize

    override suspend fun send(frame: ByteArray) {
        if (frame.size > maxFrameSize) throw FrameTooLargeException(frame.size, maxFrameSize)
        sink.writeInt(frame.size)
        sink.write(frame)
        sink.flush()
    }

    override val incoming: Flow<ByteArray> = flow {
        while (true) {
            // Clean EOF is decided BEFORE the prefix is read, and only here: `exhausted()` is true
            // exactly when no byte of a next frame exists. Once one has arrived, `readInt` throws
            // EOFException on a stream cut inside the prefix, and that propagates as a truncation.
            // Catching `readInt`'s EOFException instead would also swallow a 1–3 byte prefix.
            if (source.exhausted()) break
            val len = source.readInt()
            if (len < 0 || len > maxFrameSize) throw FrameTooLargeException(len, maxFrameSize)
            // readByteArray throws EOFException if the stream ends mid-frame — surface it loudly.
            emit(source.readByteArray(len))
        }
    }

    override suspend fun close() {
        sink.close()
        source.close()
    }
}
