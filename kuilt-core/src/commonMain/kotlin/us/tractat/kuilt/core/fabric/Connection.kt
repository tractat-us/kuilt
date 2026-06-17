package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * A point-to-point, message-oriented duplex link between exactly two peers.
 *
 * The minimal SPI a message transport (WebSocket, gRPC bidi stream, Multipeer,
 * Nearby) implements to become a kuilt fabric. Stream transports (TCP) do not
 * implement this directly — they provide a kotlinx-io Source/Sink and use
 * `:kuilt-stream`'s `framed()` to obtain a `Connection`.
 *
 * Each frame is a whole message; the link preserves frame boundaries and FIFO order.
 */
public interface Connection {
    /** Send one whole message. Suspends until the transport accepts it (backpressure). */
    public suspend fun send(frame: ByteArray)

    /** Whole messages received from the peer, in order. Single-collection. */
    public val incoming: Flow<ByteArray>

    /** Close the link. Idempotent. Completes [incoming]. */
    public suspend fun close()
}

/** Await the first inbound frame (the identity preamble). */
internal suspend fun Connection.firstFrame(): ByteArray = incoming.first()
