package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Everything a [LocalHeddle] needs to carry on after a restart, as one value you can encode in any
 * kotlinx.serialization format and store: the books, the open leases, how many slots the engine
 * was sized for, and what the fair-share allocator remembers. Take one with [LocalHeddle.persist],
 * and start an engine from it with [LocalHeddle.restore].
 *
 * A snapshot that exists is consistent. The engine is the only thing that builds one, and decoding
 * one runs the same check, so a snapshot whose leases name an edge the ledger lacks, a path that
 * does not lead from [root] to the lease's leaf, a path across a retired edge, or a lease from a
 * later epoch than [epoch] never decodes: [LocalHeddle.decode] reports it as
 * [RestoreResult.Corrupt].
 *
 * ## What is kept, and why
 *
 * - **The open leases, each with its own id, its path and its `startedAt`.** A restored engine
 *   keeps answering the ids it handed out before the restart, charges each lease down the very path
 *   it was delegated on, and still knows when each one started, so [LocalHeddle.reconcileLeases]
 *   can say how long an orphan ran.
 * - **The allocator's wake memory** ([AllocatorMemory]). Without it a group that went idle just
 *   before the restart and is busy just after would keep the idle credit the wake clamp exists to
 *   deny, and the next picks would not be the ones this engine would have made.
 * - **The capacity last reconciled, [totalSlots].** So a restored engine tops up its own supply from
 *   its first operation, exactly as the one it replaces did. Calling
 *   [LocalHeddle.reconcileCapacity] again after a restore is harmless.
 *
 * What is **not** kept: the policy, which [LocalHeddle.restore] takes afresh, and the counter lease
 * ids are numbered from. A restored engine runs under a later epoch, and lease ids are scoped by
 * epoch, so it counts from zero again without reusing an id.
 *
 * ## The encoded form
 *
 * Encoded, a snapshot is two fields: `formatVersion`, then `state`. `formatVersion` is
 * [FORMAT_VERSION] for every snapshot this release writes. Each release reads the format the one
 * before it wrote, as well as its own ([SUPPORTED_FORMAT_VERSIONS]), so storage written by the old
 * release survives one upgrade. [LocalHeddle.decode] reads `formatVersion` before it reads anything
 * else, so bytes from a newer release are refused by name, as [RestoreResult.UnsupportedVersion],
 * rather than half-read.
 *
 * Equal snapshots encode to equal bytes: every map inside goes through a canonical serializer and
 * every list is in id order.
 *
 * @property root the tree's root, where supply is minted and every pick starts.
 * @property epoch the epoch of the engine that took this snapshot. A restore must run under a
 *   later one.
 * @property ledger the books: every edge, every grant, every charge.
 * @property allocatorMemory the fair-share allocator's wake memory.
 * @property totalSlots the capacity last given to [LocalHeddle.reconcileCapacity], `0` when none was.
 * @sample us.tractat.kuilt.heddle.sampleLocalPersistence
 */
@Serializable(with = LocalHeddleSnapshotSerializer::class)
public class LocalHeddleSnapshot internal constructor(
    public val root: GroupId,
    public val epoch: Long,
    public val ledger: EntitlementLedger,
    public val allocatorMemory: AllocatorMemory,
    public val totalSlots: Long,
    internal val open: List<PersistedLease>,
) {
    init {
        require(epoch >= 0L) { "epoch must be non-negative, was $epoch" }
        require(totalSlots >= 0L) { "totalSlots must be non-negative, was $totalSlots" }
        open.zipWithNext { earlier, later ->
            require(earlier.lease.id < later.lease.id) {
                "leases must be in strictly increasing id order, but ${later.lease.id} follows ${earlier.lease.id}"
            }
        }
        for (persisted in open) persisted.requireConsistent(root, epoch, ledger)
    }

    /** Every open lease, in [LeaseId] order. */
    public val leases: List<Lease> get() = open.map { it.lease }

    override fun equals(other: Any?): Boolean =
        other is LocalHeddleSnapshot &&
            root == other.root &&
            epoch == other.epoch &&
            totalSlots == other.totalSlots &&
            open == other.open &&
            allocatorMemory == other.allocatorMemory &&
            ledger == other.ledger

    override fun hashCode(): Int {
        var h = root.hashCode()
        h = 31 * h + epoch.hashCode()
        h = 31 * h + totalSlots.hashCode()
        h = 31 * h + open.hashCode()
        h = 31 * h + allocatorMemory.hashCode()
        return 31 * h + ledger.hashCode()
    }

    override fun toString(): String =
        "LocalHeddleSnapshot(root=${root.value}, epoch=$epoch, totalSlots=$totalSlots, leases=${open.size}, $allocatorMemory)"

    public companion object {
        /** The format this release writes. */
        public const val FORMAT_VERSION: Int = 1

        /**
         * Every format this release reads, oldest first: its own, and the one the release before it
         * wrote. Format 1 is the first, so today that is format 1 alone.
         */
        public val SUPPORTED_FORMAT_VERSIONS: List<Int> = listOf(1)
    }
}

/** An open lease as a snapshot keeps it: the [lease] and the [path] it was delegated down, root edge first. */
internal data class PersistedLease(val lease: Lease, val path: List<AttachmentId>) {

    /**
     * Throws [IllegalArgumentException] unless this lease is one an engine at [epoch], rooted at
     * [root], could hold open over [ledger]: an id from no later epoch, a positive reservation, and
     * a path of known edges leading from [root] to the leaf, none of them retired.
     */
    fun requireConsistent(root: GroupId, epoch: Long, ledger: EntitlementLedger) {
        val id = lease.id
        require(id.epoch in 0L..epoch) { "lease $id is from epoch ${id.epoch}, outside 0..$epoch" }
        require(id.seq >= 0L) { "lease $id has a negative sequence number" }
        require(lease.reserved > 0L) { "lease $id reserves ${lease.reserved}, not a positive amount" }
        require(path.isNotEmpty()) { "lease $id has an empty path" }
        var at = root
        for (edge in path) {
            val record = requireNotNull(ledger.record(edge)) { "lease $id crosses ${edge.value}, which the ledger does not hold" }
            require(record.parent == at) {
                "lease $id's path is broken at ${edge.value}: it leaves ${record.parent.value}, not ${at.value}"
            }
            require(ledger.lifecycle(edge) != Lifecycle.RETIRED) { "lease $id crosses ${edge.value}, which is retired" }
            at = record.child
        }
        require(at == lease.leaf) { "lease $id's path ends at ${at.value}, not its leaf ${lease.leaf.value}" }
    }
}

/** What [LocalHeddle.decode] found in a stored snapshot. */
public sealed interface RestoreResult {
    /** The bytes held a snapshot this release reads. Start an engine from it with [LocalHeddle.restore]. */
    public data class Restored(public val snapshot: LocalHeddleSnapshot) : RestoreResult

    /**
     * The bytes say they are format [found], which this release does not read; [supported] lists
     * the ones it does. Nothing past the version was read. A format newer than this release's own is
     * from a later release; one older than every supported format skipped a release on the way.
     */
    public data class UnsupportedVersion(public val found: Int, public val supported: List<Int>) : RestoreResult

    /** The bytes are not a snapshot of a supported format, or hold one that is inconsistent: [reason] says which. */
    public data class Corrupt(public val reason: String) : RestoreResult
}

/**
 * Format 1, field for field and in wire order. Each format keeps its own surrogate, so changing
 * [LocalHeddleSnapshot] cannot change what an earlier format's bytes mean, and the next format adds
 * a surrogate beside this one rather than editing it. The ledger and the allocator memory are
 * nested as one value each, through their own serializers.
 */
@Serializable
private class SnapshotFormat1(
    val root: GroupId,
    val epoch: Long,
    val totalSlots: Long,
    val ledger: EntitlementLedger,
    val allocatorMemory: AllocatorMemory,
    val leases: List<LeaseFormat1>,
) {
    fun toSnapshot(): LocalHeddleSnapshot =
        LocalHeddleSnapshot(root, epoch, ledger, allocatorMemory, totalSlots, leases.map { it.toPersisted() })

    companion object {
        fun of(snapshot: LocalHeddleSnapshot): SnapshotFormat1 = SnapshotFormat1(
            root = snapshot.root,
            epoch = snapshot.epoch,
            totalSlots = snapshot.totalSlots,
            ledger = snapshot.ledger,
            allocatorMemory = snapshot.allocatorMemory,
            leases = snapshot.open.map(LeaseFormat1::of),
        )
    }
}

/** One open lease in format 1: its id, leaf, reservation so far, start time, and path, root edge first. */
@Serializable
private class LeaseFormat1(
    val epoch: Long,
    val seq: Long,
    val leaf: GroupId,
    val reserved: Long,
    val startedAt: Long,
    val path: List<AttachmentId>,
) {
    fun toPersisted(): PersistedLease = PersistedLease(Lease(LeaseId(epoch, seq), leaf, reserved, startedAt), path)

    companion object {
        fun of(persisted: PersistedLease): LeaseFormat1 = with(persisted.lease) {
            LeaseFormat1(id.epoch, id.seq, leaf, reserved, startedAt, persisted.path)
        }
    }
}

/** A `formatVersion` this release does not read: thrown by the serializer, answered by [LocalHeddle.decode]. */
internal class UnsupportedSnapshotFormat(val found: Int) :
    SerializationException("snapshot format $found is not one of ${LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS}")

/**
 * The envelope: `formatVersion`, then `state` in that version's surrogate. The version is read
 * first and decides how `state` is read, so a format this release does not know is refused before
 * any of its state is decoded. A `state` that arrives ahead of its version is refused as malformed,
 * since there is then no way to know how to read it; this serializer always writes the version first.
 */
internal object LocalHeddleSnapshotSerializer : KSerializer<LocalHeddleSnapshot> {
    private const val VERSION = 0
    private const val STATE = 1

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("us.tractat.kuilt.heddle.LocalHeddleSnapshot") {
        element<Int>("formatVersion")
        element("state", SnapshotFormat1.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: LocalHeddleSnapshot) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, VERSION, LocalHeddleSnapshot.FORMAT_VERSION)
            encodeSerializableElement(descriptor, STATE, SnapshotFormat1.serializer(), SnapshotFormat1.of(value))
        }
    }

    override fun deserialize(decoder: Decoder): LocalHeddleSnapshot = decoder.decodeStructure(descriptor) {
        var version: Int? = null
        var state: LocalHeddleSnapshot? = null
        if (decodeSequentially()) {
            val found = decodeIntElement(descriptor, VERSION).also(::requireSupported)
            version = found
            state = decodeState(this, found)
        } else {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    VERSION -> version = decodeIntElement(descriptor, VERSION).also(::requireSupported)
                    STATE -> state = decodeState(this, version ?: throw SerializationException("state precedes formatVersion"))
                    CompositeDecoder.DECODE_DONE -> break
                    else -> throw SerializationException("unexpected element $index in a snapshot")
                }
            }
        }
        if (version == null) throw SerializationException("a snapshot has no formatVersion")
        state ?: throw SerializationException("a snapshot of format $version has no state")
    }

    private fun requireSupported(found: Int) {
        if (found !in LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS) throw UnsupportedSnapshotFormat(found)
    }

    /** `state` as format [version] wrote it. One arm per entry of [LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS]. */
    private fun decodeState(decoder: CompositeDecoder, version: Int): LocalHeddleSnapshot = when (version) {
        1 -> decoder.decodeSerializableElement(descriptor, STATE, SnapshotFormat1.serializer()).toSnapshot()
        else -> throw UnsupportedSnapshotFormat(version)
    }
}
