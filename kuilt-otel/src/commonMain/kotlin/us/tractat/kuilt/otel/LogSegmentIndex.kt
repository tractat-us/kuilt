package us.tractat.kuilt.otel

import kotlinx.serialization.Serializable

/**
 * The directory of [WarpLogRecordExporter]'s persisted op-log segments.
 *
 * The exporter no longer keeps its whole op-log under one key. It keeps it in
 * segments — `otel.logs.seg.<n>` — and this index, under `otel.logs.idx`, names
 * which of them are live. It is small and is rewritten only when the set of
 * segments changes (a roll, a reclamation, a merge), never on the per-record path.
 *
 * The index is also the **commit point** of the one-time migration off the legacy
 * single-blob `otel.logs` key: its presence means the migration finished, so a
 * legacy key that outlives it is a crashed delete rather than data.
 *
 * @property sealedSegments Numbers of the full segments, ascending. Never written to again.
 * @property active Number of the segment currently being appended to. Written before
 *   any content lands in it, so a crash can leave it naming a segment the store lacks
 *   — recovery treats that as empty.
 * @property next The next segment number to hand out. Monotonic; numbers are never reused.
 * @property retired Segments whose records are all superseded and whose keys are being deleted
 *   — the **sweep ledger**.
 *
 *   There is no key-enumeration API, so a segment the index simply forgets is unreachable and
 *   unsweepable forever. Moving a number here is the **commit point** of a retirement: a crash
 *   between that write and the delete leaves the number named, and the next start re-attempts
 *   the delete idempotently. Numbers leave this list only on an index write that follows a
 *   confirmed delete.
 *
 *   Defaulted so an index written by a build that predates retirement still decodes.
 */
@Serializable
internal data class LogSegmentIndex(
    val sealedSegments: List<Int>,
    val active: Int,
    val next: Int,
    val retired: List<Int> = emptyList(),
)
