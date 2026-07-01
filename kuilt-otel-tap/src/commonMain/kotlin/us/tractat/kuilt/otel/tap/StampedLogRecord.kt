package us.tractat.kuilt.otel.tap

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.otel.LogRecord

/**
 * A pulled [LogRecord] paired with the ordering stamp it was assigned when the
 * producing device appended it to its log.
 *
 * ## Why this exists
 *
 * A single device's [LogTapClient.pull] already returns its records in the right
 * order. Merging **several** devices' logs into one timeline needs more: for each
 * record you need *who produced it* and *a key that orders it against records from
 * other producers*. A wall clock can't do this — devices drift, and a phone that was
 * offline for an hour has a skewed clock. [rgaId] carries both, minted by the log
 * CRDT rather than read from any clock:
 *
 * - [RgaId.replicaId] — the producing device.
 * - [rgaId] as a whole is the **cross-device total-order key**: [RgaId.compareTo] is
 *   `(lamport, replicaId)`. Sort every record from every device's artifact by [rgaId]
 *   and you get one deterministic merged order; because a single producer's stamps
 *   strictly increase in the order it logged, that producer's own lines stay in
 *   first-in-first-out order for free.
 * - [dot] (`= rgaId.dot`, `(replicaId, seq)`) is the causal handle, kept for
 *   forward-compatibility with happens-before views.
 *
 * The stamp is the record's position in the log CRDT, not part of the record's OTLP
 * content — so it rides *alongside* the [LogRecord] here rather than inside it, and
 * the plain [LogTapClient.pull] / OTLP-shaped artifact stay stamp-free.
 */
@Serializable
public data class StampedLogRecord(
    /** The ordering stamp assigned to [record] by the producing device's log CRDT. */
    public val rgaId: RgaId,
    /** The log record, exactly as [LogTapClient.pull] would return it. */
    public val record: LogRecord,
) {
    /** The causal handle `(replicaId, seq)` for [record] — a convenience for [rgaId]'s [RgaId.dot]. */
    public val dot: Dot get() = rgaId.dot
}
