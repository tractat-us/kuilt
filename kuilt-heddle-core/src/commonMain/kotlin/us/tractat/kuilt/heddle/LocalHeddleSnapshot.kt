package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeCollection

/**
 * Everything a [LocalHeddle] needs to carry on after a restart, as one value you can encode, as
 * JSON or CBOR, and store: the books, the open leases, how many slots the engine
 * was sized for, what the fair-share allocator remembers, and the topology it last applied. Take one with [LocalHeddle.persist],
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
 * - **The topology last applied, [lastApplied].** Only the topology tells a lane from a group that
 *   has nothing attached right now, such as one marked for runtime children before the first
 *   arrives: both read as leaves in the ledger. A restored engine leases exactly what the persisted
 *   one did, and [LocalHeddle.attachRuntimeChild] and a later [LocalHeddle.apply] diff against it.
 *   It is the topology of the last [LocalHeddle.apply], even one with refusals: applying it again
 *   after a restore lands what was left.
 *
 * What is **not** kept: the policy, which [LocalHeddle.restore] takes afresh, and the counter lease
 * ids are numbered from. A restored engine runs under a later epoch, and lease ids are scoped by
 * epoch, so it counts from zero again without reusing an id.
 *
 * ## The encoded form
 *
 * Encoded, a snapshot is a two-element sequence, `[formatVersion, state]`: a JSON array, a CBOR
 * array. It is a sequence and not an object so that a store which reorders an object's keys, as
 * Postgres `jsonb` does, cannot put `state` ahead of `formatVersion`. JSON and CBOR are both
 * checked; ProtoBuf cannot carry an [EntitlementLedger] at all. `formatVersion` is
 * [FORMAT_VERSION] for every snapshot this release writes. Each release reads the format the one
 * before it wrote, as well as its own ([SUPPORTED_FORMAT_VERSIONS]), so storage written by the old
 * release survives one upgrade. [LocalHeddle.decode] reads `formatVersion` before it reads anything
 * else, so bytes from a newer release are refused by name, as [RestoreResult.UnsupportedVersion],
 * rather than half-read.
 *
 * Equal snapshots encode to equal bytes: every map inside goes through a canonical serializer and
 * every list is in id order.
 *
 * @property epoch the epoch of the engine that took this snapshot. A restore must run under a
 *   later one.
 * @property ledger the books: every edge, every grant, every charge.
 * @property allocatorMemory the fair-share allocator's wake memory.
 * @property totalSlots the capacity last given to [LocalHeddle.reconcileCapacity], `0` when none was.
 * @property lastApplied the topology the engine last applied: the one it booted from, or the last
 *   one passed to [LocalHeddle.apply].
 * @sample us.tractat.kuilt.heddle.sampleLocalPersistence
 */
@Serializable(with = LocalHeddleSnapshotSerializer::class)
public class LocalHeddleSnapshot internal constructor(
    public val epoch: Long,
    public val ledger: EntitlementLedger,
    public val allocatorMemory: AllocatorMemory,
    public val totalSlots: Long,
    internal val open: List<PersistedLease>,
    public val lastApplied: Topology,
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

    /**
     * The tree's root, where supply is minted and every pick starts: [lastApplied]'s, since a reload
     * never changes the root. It is not stored separately, so the two cannot disagree.
     */
    public val root: GroupId get() = lastApplied.groupId()

    /** Every open lease, in [LeaseId] order. */
    public val leases: List<Lease> get() = open.map { it.lease }

    override fun equals(other: Any?): Boolean =
        other is LocalHeddleSnapshot &&
            epoch == other.epoch &&
            totalSlots == other.totalSlots &&
            open == other.open &&
            allocatorMemory == other.allocatorMemory &&
            lastApplied == other.lastApplied &&
            ledger == other.ledger

    override fun hashCode(): Int {
        var h = epoch.hashCode()
        h = 31 * h + totalSlots.hashCode()
        h = 31 * h + open.hashCode()
        h = 31 * h + allocatorMemory.hashCode()
        h = 31 * h + lastApplied.hashCode()
        return 31 * h + ledger.hashCode()
    }

    override fun toString(): String =
        "LocalHeddleSnapshot(root=${root.value}, epoch=$epoch, totalSlots=$totalSlots, leases=${open.size}, $allocatorMemory, lastApplied=$lastApplied)"

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
     * a path of known edges leading from [root] to the leaf that is the leaf's one live lineage.
     *
     * That last is the invariant the engine checks after every landed run, so a restored engine
     * starts from a state it could have reached. A live lineage may cross a CLOSING edge, which is a
     * drain in progress, and must still restore; it never crosses a RETIRED or PREPARED edge, and a
     * quarantined lineage (two live inbound edges, a divergent record, a cycle) has none.
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
        val lineage = ledger.lineageOf(lease.leaf)
        require(lineage == path) {
            "lease $id's path ${path.map { it.value }} is not ${lease.leaf.value}'s live lineage ${lineage?.map { it.value }}"
        }
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
 * a surrogate beside this one rather than editing it. The ledger, the allocator memory and the
 * topology are nested as one value each, through their own serializers.
 *
 * **Those nested serializers are part of format 1 too.** A new top-level format keeps format 1's
 * own fields readable, but not what is nested in them: [EntitlementLedger], [AllocatorMemory] and
 * [Topology] are encoded through their live serializers, so a change to one of those types changes
 * what format 1's bytes mean. Once format 1 has shipped:
 * - a field added to any of them, at any depth, takes a default, so bytes written without it still
 *   decode, and so a value holding the default still encodes to the same bytes;
 * - a change that cannot be made that way (a rename, a removal, a new meaning for a field) needs a
 *   frozen surrogate of the nested type under this class, which format 1 then reads through, and
 *   only after that the change to the live type.
 */
@Serializable
private class SnapshotFormat1(
    val epoch: Long,
    val totalSlots: Long,
    val ledger: EntitlementLedger,
    val allocatorMemory: AllocatorMemory,
    val leases: List<LeaseFormat1>,
    val lastApplied: Topology,
) {
    fun toSnapshot(): LocalHeddleSnapshot =
        LocalHeddleSnapshot(epoch, ledger, allocatorMemory, totalSlots, leases.map { it.toPersisted(ledger) }, lastApplied)

    companion object {
        fun of(snapshot: LocalHeddleSnapshot): SnapshotFormat1 = SnapshotFormat1(
            epoch = snapshot.epoch,
            totalSlots = snapshot.totalSlots,
            ledger = snapshot.ledger,
            allocatorMemory = snapshot.allocatorMemory,
            leases = snapshot.open.map(LeaseFormat1::of),
            lastApplied = snapshot.lastApplied,
        )
    }
}

/**
 * One open lease in format 1: its id, reservation so far, start time, and path, root edge first.
 * The leaf is not stored: it is the child of the path's last edge, so the two cannot disagree.
 */
@Serializable
private class LeaseFormat1(
    val epoch: Long,
    val seq: Long,
    val reserved: Long,
    val startedAt: Long,
    val path: List<AttachmentId>,
) {
    /** The lease, with its leaf read off [ledger]: throws [IllegalArgumentException] when [path] does not end at an edge it holds. */
    fun toPersisted(ledger: EntitlementLedger): PersistedLease {
        val last = requireNotNull(path.lastOrNull()) { "lease ${LeaseId(epoch, seq)} has an empty path" }
        val leaf = requireNotNull(ledger.record(last)) { "lease ${LeaseId(epoch, seq)} ends on ${last.value}, which the ledger does not hold" }.child
        return PersistedLease(Lease(LeaseId(epoch, seq), leaf, reserved, startedAt), path)
    }

    companion object {
        fun of(persisted: PersistedLease): LeaseFormat1 = with(persisted.lease) {
            LeaseFormat1(id.epoch, id.seq, reserved, startedAt, persisted.path)
        }
    }
}

/** A `formatVersion` this release does not read: thrown by the serializer, answered by [LocalHeddle.decode]. */
internal class UnsupportedSnapshotFormat(val found: Int) :
    SerializationException("snapshot format $found is not one of ${LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS}")

/**
 * The envelope: a two-element sequence, `[formatVersion, state]`, with `state` in that version's
 * surrogate. It is a sequence rather than an object so that its order is part of its type: a store
 * that reorders an object's keys, as Postgres `jsonb` does, cannot move `state` ahead of
 * `formatVersion`, and every format writes and reads the version first. The version decides how
 * `state` is read, so a format this release does not know is refused before any of its state is
 * decoded. A sequence with its elements the other way round, or with a third, is malformed.
 */
internal object LocalHeddleSnapshotSerializer : KSerializer<LocalHeddleSnapshot> {
    private const val VERSION = 0
    private const val STATE = 1

    /**
     * A LIST-kind descriptor, which every format reads in order. The library's list descriptor
     * names one element type and the envelope has two, so it names the state's; the version is
     * read and written by index as an `Int`, which every format allows inside a list.
     */
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = listSerialDescriptor(SnapshotFormat1.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: LocalHeddleSnapshot) {
        encoder.encodeCollection(descriptor, collectionSize = 2) {
            encodeIntElement(descriptor, VERSION, LocalHeddleSnapshot.FORMAT_VERSION)
            encodeSerializableElement(descriptor, STATE, SnapshotFormat1.serializer(), SnapshotFormat1.of(value))
        }
    }

    override fun deserialize(decoder: Decoder): LocalHeddleSnapshot = decoder.decodeStructure(descriptor) {
        if (decodeSequentially()) {
            val version = decodeIntElement(descriptor, VERSION).also(::requireSupported)
            return@decodeStructure decodeState(this, version)
        }
        var version: Int? = null
        var state: LocalHeddleSnapshot? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                VERSION -> version = decodeIntElement(descriptor, VERSION).also(::requireSupported)
                STATE -> state = decodeState(this, version ?: throw SerializationException("state precedes formatVersion"))
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("a snapshot has an element $index past its state")
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
