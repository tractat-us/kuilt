package us.tractat.kuilt.core

/**
 * Thrown by an addressed send whose payload exceeds the published send budget — [Seam.maxPayloadBytes],
 * or a budgeted derivative of it such as `Room.maxPayloadBytes`.
 *
 * Reported *before* the payload is framed, so the caller learns the number it should have respected
 * rather than the fabric's own frame error for framing it never asked for. That distinction is the
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
