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
 *
 * **Neither [send] nor [close] may report a failure as a cancellation** — the obligation
 * [us.tractat.kuilt.core.Seam.close] states in full, for the same reason and with the same
 * `withTimeout` trap. `MeshSeam` closes a whole roster of connections under a best-effort guard, so
 * one callee-minted `CancellationException` here cancels that loop and leaks every remaining link
 * (#1826).
 */
public interface Connection {
    /**
     * Send one whole message. Suspends until the transport accepts it (backpressure).
     *
     * A send failure must **not** be reported as a cancellation — see [us.tractat.kuilt.core.Seam.close].
     */
    public suspend fun send(frame: ByteArray)

    /** Whole messages received from the peer, in order. Single-collection. */
    public val incoming: Flow<ByteArray>

    /**
     * The largest frame [send] accepts, or `null` when this link cannot name a ceiling.
     *
     * This is where a frame limit *enters* kuilt: a length-prefixed transport knows its own bound
     * (`:kuilt-stream`'s `framed()` takes `maxFrameSize` and throws `FrameTooLargeException` past
     * it), and publishing it here is what lets the seam above surface it as
     * [us.tractat.kuilt.core.Seam.maxPayloadBytes] and the layers above that reserve room for their
     * own headers (#2047). `null` means unknown, never "unbounded" — see
     * [us.tractat.kuilt.core.Seam.maxPayloadBytes] for what a caller may infer.
     *
     * A [Connection] decorator that adds no bytes to a frame delegates this unchanged; one that
     * adds bytes subtracts them, floored at zero.
     */
    public val maxFrameBytes: Int? get() = null

    /**
     * Close the link. Idempotent. Completes [incoming].
     *
     * A close failure must **not** be reported as a cancellation — see [us.tractat.kuilt.core.Seam.close].
     */
    public suspend fun close()
}

/** Await the first inbound frame (the identity preamble). */
internal suspend fun Connection.firstFrame(): ByteArray = incoming.first()
