package us.tractat.kuilt.core

/**
 * Thrown by an addressed send that is over its limit. A seam's limit is [Seam.maxPayloadBytes]. A room
 * and its channel views measure the encoded frame instead, because `Room.maxPayloadBytes` is a promise
 * that holds on every route rather than the refusal threshold, so a payload above that number can
 * still be delivered.
 *
 * Reported *before* the frame reaches the fabric, so the caller learns a number it can use rather
 * than the fabric's own frame error for framing it never asked for. That distinction is the
 * whole point: an oversize relayed frame used to surface as a `FrameTooLargeException` naming a
 * limit the caller could not see, from a send that would have succeeded a moment earlier on a
 * different route (#2047).
 *
 * An [IllegalArgumentException] because it is: the payload was too big for a limit the sender
 * publishes and the caller can read. Best-effort sends do not throw it — [Seam.broadcast] and
 * `Room.broadcast` are lossy-without-error by contract and drop an over-budget frame with a log.
 *
 * @param payloadBytes the size of the payload that was refused.
 * @param budgetBytes the largest payload this send would have accepted.
 * @param reservedBytes what the layers above the fabric hold back from the fabric's own frame
 *   limit — envelopes, channel headers. `budgetBytes + reservedBytes` is that frame limit.
 */
public class PayloadTooLarge(
    public val payloadBytes: Int,
    public val budgetBytes: Int,
    public val reservedBytes: Int,
) : IllegalArgumentException(
    "payload of $payloadBytes B exceeds the $budgetBytes B send budget: $reservedBytes B of the " +
        "fabric's ${budgetBytes + reservedBytes} B frame limit is reserved for framing",
)
