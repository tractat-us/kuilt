package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.PayloadTooLarge

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
     *
     * **Fixed for the life of the link.** Unlike [us.tractat.kuilt.core.Seam.maxPayloadBytes] — which
     * a mesh derives from a link set that grows and shrinks, so it moves — this number is a property
     * of one transport and must not change once the link exists. That is what makes it the sound
     * thing to pre-check a payload against: a seam reading it immediately before [send] cannot be
     * overtaken by a tightening it did not see (#2069). A transport whose real ceiling is negotiated
     * publishes the settled value, or `null` until it settles.
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

/**
 * [PayloadTooLarge] if [payload] will not fit this link's [maxFrameBytes], else `null`.
 *
 * The pre-check the fabric seams owe a caller once they publish a budget (#2069), and the reason it
 * is written against the **connection** rather than [us.tractat.kuilt.core.Seam.maxPayloadBytes]:
 * the seam's number is an aggregate that moves (a mesh reports the minimum across a live link set),
 * so checking it would leave a check-then-send window a tightening could slip through, whereas this
 * one is fixed for the life of the link. It is also the only number that knows which link the frame
 * is actually going down — on a mesh the seam-level minimum would refuse a payload the chosen link
 * could carry perfectly well.
 *
 * `reservedBytes = 0`: both fabric seams hand the caller's payload to [send] byte for byte, with no
 * per-frame header of their own, so the payload budget *is* the frame ceiling. Reservation happens
 * further up, in the layers that wrap a payload before it gets this far (`RoomChannel`, `SeamRoom`).
 *
 * A link that names no ceiling returns `null` — unknown is not a refusal.
 */
internal fun Connection.oversizeOrNull(payload: ByteArray): PayloadTooLarge? {
    val ceiling = maxFrameBytes ?: return null
    return if (payload.size > ceiling) {
        PayloadTooLarge(payloadBytes = payload.size, budgetBytes = ceiling, reservedBytes = 0)
    } else {
        null
    }
}
