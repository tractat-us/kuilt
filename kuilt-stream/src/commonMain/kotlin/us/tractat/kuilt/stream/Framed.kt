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
 * Thrown when a received length prefix exceeds [framed]'s [maxFrameSize].
 * The oversized allocation is never performed; the flow terminates immediately.
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
 * - **Oversize protection:** the prefix is validated against [maxFrameSize] before any
 *   allocation. A hostile prefix throws [FrameTooLargeException].
 * - **Clean EOF:** an [EOFException] thrown by [Source.readInt] at a frame boundary
 *   closes [incoming] normally. An EOF mid-frame propagates as [EOFException].
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
    override suspend fun send(frame: ByteArray) {
        require(frame.size <= maxFrameSize) { "frame ${frame.size} exceeds max $maxFrameSize" }
        sink.writeInt(frame.size)
        sink.write(frame)
        sink.flush()
    }

    override val incoming: Flow<ByteArray> = flow {
        while (true) {
            val len = try {
                source.readInt()
            } catch (_: EOFException) {
                // Clean EOF at a frame boundary — incoming completes normally.
                break
            }
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
