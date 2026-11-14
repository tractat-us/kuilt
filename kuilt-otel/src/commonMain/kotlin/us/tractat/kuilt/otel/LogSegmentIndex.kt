package us.tractat.kuilt.otel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
 *   unsweepable forever.
 *
 *   That constraint is why `WarpLogRecordExporter.clear` is index-driven rather than a
 *   directory sweep, and it still binds a **consumer**: holding a `DurableStore` is not
 *   enough to discover the segment numbers. The exporter's own index is sufficient for the
 *   exporter's own reset, and that is the whole of what #2208 closes.
 *
 *   Moving a number here is the **commit point** of a retirement: a crash
 *   between that write and the delete leaves the number named, and the next start re-attempts
 *   the delete idempotently. Numbers leave this list only on an index write that follows a
 *   confirmed delete.
 *
 *   **A number reaches this list only after a write carrying its covering state was confirmed
 *   durable.** That is the whole warrant for the next start deleting these keys unconditionally,
 *   before it reads anything — the sweep re-checks nothing, so the guarantee has to have been
 *   established when the number was written here. The exporter upholds it by never moving a
 *   number onto its in-memory mirror of this list until the write that publishes it has returned;
 *   see `WarpLogRecordExporter`'s `StoreAction.CommitRetirement`.
 *
 *   Defaulted so an index written by a build that predates retirement still decodes.
 */
@Serializable(with = LogSegmentIndexSerializer::class)
internal data class LogSegmentIndex(
    val sealedSegments: List<Long>,
    val active: Long,
    val next: Long,
    val retired: List<Long> = emptyList(),
) {
    /**
     * A segment number names a key, so an index that could hand one out twice is an index that
     * overwrites a live segment. These are the three ways it could (#3317), refused at
     * construction so the exporter cannot **write** one: it builds every index it writes
     * through this constructor, and a refusal fails the turn, so the export reports
     * [ExportResult.Failure] instead of `Success` over an overwritten key.
     *
     * The last check is also what stops a wrap. The number is a `Long`, so a wrap is out of
     * reach in practice, but if one ever happened the roll that wrapped would open a segment
     * whose `next` lies below it, and this refuses that roll.
     */
    init {
        require(sealedSegments.toSet().size == sealedSegments.size) {
            "otel.logs: index seals a segment twice: $sealedSegments"
        }
        require(active !in sealedSegments) { "otel.logs: index names segment $active as both active and sealed" }
        require((sealedSegments + retired + active).all { it < next }) {
            "otel.logs: index would hand out $next again, but already names a segment at or past it: $this"
        }
    }
}

/**
 * The index as stored. CBOR keys each field by its name, so the names must not change.
 *
 * Every build before #3317 wrote these fields as `Int`. CBOR stores an integer at whatever
 * width it needs, so those bytes decode into `Long` fields unchanged, and so do the segment
 * keys: `otel.logs.seg.<n>` prints the same digits for an `Int` and a `Long` of equal value.
 */
@Serializable
@SerialName("us.tractat.kuilt.otel.LogSegmentIndex")
private class StoredLogSegmentIndex(
    val sealedSegments: List<Long>,
    val active: Long,
    val next: Long,
    val retired: List<Long> = emptyList(),
)

/**
 * Writes a [LogSegmentIndex] as is. Reads one leniently, repairing the shapes a build before
 * #3317 could write.
 *
 * Refusing to read such an index would cost more than the bug did. `recover` would start fresh,
 * the next export would number from the start again, and every segment the old index named
 * would be lost: nothing else names them, and a store cannot list its keys. A wrapped index
 * looks like this one, measured: `next` is `Int.MIN_VALUE`, `Int.MAX_VALUE` is both active and
 * sealed several times, and the key under that number holds the latest active segment. The
 * repair keeps every number it names, so recovery still reads every key:
 *
 * - each sealed number once, and not the active one, since that key is read as the active segment;
 * - `next` raised past every number the index names, as `maxOf(next, active + 1)` in recovery
 *   used to do for `active` alone.
 */
internal object LogSegmentIndexSerializer : KSerializer<LogSegmentIndex> {
    override val descriptor: SerialDescriptor = StoredLogSegmentIndex.serializer().descriptor

    override fun serialize(encoder: Encoder, value: LogSegmentIndex) {
        encoder.encodeSerializableValue(
            StoredLogSegmentIndex.serializer(),
            StoredLogSegmentIndex(value.sealedSegments, value.active, value.next, value.retired),
        )
    }

    override fun deserialize(decoder: Decoder): LogSegmentIndex {
        val stored = decoder.decodeSerializableValue(StoredLogSegmentIndex.serializer())
        val sealed = stored.sealedSegments.distinct().filter { it != stored.active }
        val highest = (sealed + stored.retired + stored.active).max()
        return LogSegmentIndex(
            sealedSegments = sealed,
            active = stored.active,
            next = maxOf(stored.next, highest + 1),
            retired = stored.retired,
        )
    }
}
