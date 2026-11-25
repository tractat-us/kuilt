package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
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
 * one runs the same check, so a snapshot that breaks any of these never decodes:
 * [LocalHeddle.decode] reports it as [RestoreResult.Corrupt].
 *
 * - Each lease names only edges the ledger has, down a path that leads from [root] to the lease's
 *   leaf across no retired edge, and none is from a later epoch than [epoch].
 * - Each edge is charged, returned plus spent, no more than was issued across it, and its
 *   effective spend is not below zero.
 * - Each group holds, for each writer, no less than nothing.
 * - What the leaves have spent, forgotten edges included, and what every group holds come to no
 *   more than was ever minted.
 * - The open leases across an edge reserve no more than is outstanding on it.
 * - Each parent's active children's weights fit the weight budget [LocalHeddle.apply] keeps.
 * - Every total the engine reads stays within `Long`.
 *
 * Each is a bound the engine keeps after every call, and none is a fixed cap: a lease that
 * [LocalHeddle.extend] has grown past [LocalHeddle.MAX_RESERVATION] is one an engine writes, so it
 * restores.
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
 *   has nothing attached right now, such as one with a template before its
 *   first child is attached: both read as leaves in the ledger. A restored engine leases exactly what the persisted
 *   one did, and [LocalHeddle.attach] and a later [LocalHeddle.apply] diff against it.
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
 * checked. ProtoBuf is not: kotlinx ProtoBuf writes an [EntitlementLedger] but cannot read it back,
 * because it fails on the ledger's maps whose values are maps, such as `transfers`. `formatVersion` is
 * [FORMAT_VERSION], 1, for every snapshot this release writes, and it is the only format this
 * release reads ([SUPPORTED_FORMAT_VERSIONS]). [LocalHeddle.decode] reads `formatVersion` before it
 * reads anything else, so bytes in a format this release does not read are refused by name, as
 * [RestoreResult.UnsupportedVersion], rather than half-read: format 2, which only development
 * builds of 0.7.5 wrote, is one of them. A snapshot v0.7.4 wrote also says format 1, in a layout
 * this release does not read, and is not told apart: it fails to decode, or misreads, so a consumer
 * coming from v0.7.4 starts from its topology again rather than restoring (#3489).
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
        // The books balance as the engine keeps them after every call (#3261). A total the engine
        // reads that leaves `Long` is corrupt bytes too, never a thrown `ArithmeticException`.
        try {
            requireBalanced(ledger, open)
        } catch (overflow: ArithmeticException) {
            throw IllegalArgumentException("a total in the ledger leaves Long: ${overflow.message}", overflow)
        }
        // No gauge is folded above its edge's issuance (#3353): only the fold rebase of a development
        // build between #3355 and slice 7 wrote one, and a delegation over it would throw later.
        for (edge in ledger.gaugedEdges()) {
            val folded = checkNotNull(ledger.gauge(edge)).folded
            val issued = ledger.baseIssuance(edge)
            require(folded <= issued) {
                "the gauge on ${edge.value} is folded at $folded, above its base issuance $issued: a fold " +
                    "rebase from a development build, which this release does not restore"
            }
        }
        // A shift remainder is on its edge's own grid (#3416 review): a reweight is a new edge, so an
        // allocator never writes one at another denominator, and a restored one would throw at a pick.
        for ((edge, remainder) in allocatorMemory.shiftRemainders) {
            val weight = ledger.record(edge)?.weight ?: continue
            require(remainder.denominator == weight.denominator) {
                "the shift remainder on ${edge.value} is at denominator ${remainder.denominator}, not its " +
                    "weight's ${weight.denominator}"
            }
        }
    }

    /**
     * The tree's root, where supply is minted and every pick starts: [lastApplied]'s, since a reload
     * never changes the root. It is not stored separately, so the two cannot disagree.
     */
    public val root: GroupId get() = lastApplied.groupId()

    /** Every open lease, in [LeaseId] order. */
    public val leases: List<Lease> get() = open.map { it.lease }

    /**
     * Ask this snapshot what the engine that took it would answer, with no engine: who goes next,
     * why a leaf is waiting, and what share it is owed (see [LocalHeddleView]). Changes nothing,
     * here or anywhere.
     *
     * [policy] is required because a snapshot does not keep one: pass the policy the engine ran
     * with, and the view answers exactly as that engine did when it took this snapshot. It sizes
     * [totalSlots] as [LocalHeddle.restore] does.
     *
     * @throws IllegalArgumentException when [policy] cannot size [totalSlots] (see
     *   [LocalHeddle.reconcileCapacity]).
     * @sample us.tractat.kuilt.heddle.sampleSnapshotView
     */
    public fun view(policy: LocalPolicy): LocalHeddleView {
        val leasable = Leasable.ByTopology(lastApplied)
        return LocalHeddleView(
            root = root,
            policy = policy,
            epoch = epoch,
            ledger = ledger,
            allocator = FairShareAllocator(LocalHeddle.SELF, LocalHeddle.policyConfig(policy), allocatorMemory),
            capacity = Capacity.sized(totalSlots, policy),
            leasable = leasable,
            // The view keeps no leases, only how many are open beneath each capped group.
            openBeneath = ledger.openBeneath(open.asSequence().map { it.path }, leasable),
        )
    }

    /**
     * What [LocalHeddle.apply] of [new] at [generation] would do to the engine that took this
     * snapshot, with no engine: the same [TopologyPreview] as [LocalHeddle.previewApply] on that
     * engine as it stood, but with no [TopologyPreview.basis], since a snapshot keeps no revision.
     * Changes nothing, here or anywhere. The leases it holds move as that engine's would, whatever
     * epoch opened them.
     *
     * [policy] is required because a snapshot does not keep one: pass the policy the engine ran
     * with. Pass the [generation] the apply will use (see [LocalHeddle.previewApply]).
     *
     * @throws IllegalArgumentException when [policy] cannot size [totalSlots] (see
     *   [LocalHeddle.reconcileCapacity]), or as [LocalHeddle.apply] does: [new]'s root is not
     *   [root], or [generation] is negative.
     * @sample us.tractat.kuilt.heddle.samplePreviewApplyFromSnapshot
     */
    public fun previewApply(policy: LocalPolicy, new: Topology, generation: Long): TopologyPreview =
        LocalHeddle.previewOf(this, policy, new, generation)

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
        /**
         * The format this release writes: 1, the first frozen format. Once 0.7.5 ships, no later
         * release changes what format 1's bytes mean without shipping a reader for them. The number
         * restarts here (#3489): format 2, written only by development builds of 0.7.5, is refused,
         * and the format 1 v0.7.4 wrote, a different layout, is not told apart (see "The encoded
         * form").
         */
        public const val FORMAT_VERSION: Int = 1

        /** Every format this release reads, oldest first: only its own. */
        public val SUPPORTED_FORMAT_VERSIONS: List<Int> = listOf(FORMAT_VERSION)
    }
}

/**
 * Throws [IllegalArgumentException] unless [ledger] and the [open] leases over it balance as every
 * engine's books do after every call, and [ArithmeticException] when a total the engine reads
 * leaves `Long` (#3261). Restored past any of these, the engine's first charge would overspend or
 * overflow where no engine call can:
 * - each edge is charged, returned plus spent, no more than was issued across it, and its spend is
 *   not below zero;
 * - each group holds, for each writer, no less than nothing;
 * - what is spent, over every leaf and every forgotten edge, and what every group holds come to no
 *   more than was ever minted;
 * - each parent's active children's weights fit the weight budget [LocalHeddle.apply] keeps;
 * - the leases across each edge reserve no more than is outstanding on it.
 *
 * None of these is a fixed cap. [LocalHeddle.MAX_RESERVATION] bounds one reservation or extension,
 * not a lease, which [LocalHeddle.extend] adds to; and a counter at `Long.MAX_VALUE` is one the
 * lifetime limit lets running work reach, so neither is refused here.
 */
private fun requireBalanced(ledger: EntitlementLedger, open: List<PersistedLease>) {
    val outstanding = HashMap<AttachmentId, Long>()
    for (edge in ledger.allEdges().sorted()) {
        val summary = checkNotNull(ledger.edge(edge)) { "${edge.value} is in the ledger but has no summary" }
        require(summary.spent >= 0L) { "${edge.value} has spent ${summary.spent}, less than nothing" }
        val charged = checkedAdd(summary.returned, summary.spent)
        require(charged <= summary.issued) {
            "${edge.value} is charged $charged, returned and spent, past the ${summary.issued} issued across it"
        }
        outstanding[edge] = summary.issued - charged
    }
    val replicas = ledger.allReplicas().sorted()
    var heldEverywhere = 0L
    for (group in ledger.allGroups().sorted()) {
        for (replica in replicas) {
            val held = ledger.holdings(group, replica)
            require(held >= 0L) { "${group.value} holds $held for ${replica.value}, less than nothing" }
            heldEverywhere = checkedAdd(heldEverywhere, held)
        }
        val weights = ledger.activeChildren(group).mapNotNull { ledger.record(it.attachment)?.weight }
        require(weightsFitTheBudget(weights)) { "the weights of ${group.value}'s active children, $weights, do not fit the weight budget" }
    }
    val minted = ledger.mintedTotal()
    val spent = ledger.leafSpentTotal()
    require(checkedAdd(spent, heldEverywhere) <= minted) {
        "the ledger has spent $spent and holds $heldEverywhere, more than the $minted ever minted"
    }
    val reserved = LinkedHashMap<AttachmentId, Long>()
    for (persisted in open) {
        for (edge in persisted.path) reserved[edge] = checkedAdd(reserved[edge] ?: 0L, persisted.lease.reserved)
    }
    for ((edge, sum) in reserved) {
        val left = outstanding[edge] ?: 0L
        require(sum <= left) { "the leases across ${edge.value} reserve $sum, more than the $left outstanding on it" }
    }
}

/** An open lease as a snapshot keeps it: the [lease] and the [path] it was delegated down, root edge first. */
internal data class PersistedLease(val lease: Lease, val path: List<AttachmentId>) {

    /**
     * Throws [IllegalArgumentException] unless this lease is one an engine at [epoch], rooted at
     * [root], could hold open over [ledger]: an id from no later epoch, a reservation that is not
     * negative (`0` for a lease admitted past the lifetime limit, #3475), and
     * a path of known edges leading from [root] to the leaf that is the leaf's one live lineage.
     *
     * That last is the invariant the engine checks after every landed run, so a restored engine
     * starts from a state it could have reached. A live lineage may cross a CLOSING edge, which is a
     * drain in progress, and must still restore; it never crosses a RETIRED or PREPARED edge, and a
     * quarantined lineage (two live inbound edges, a divergent record, a cycle) has none.
     *
     * The empty-path check guards the internal constructor alone: a decoded lease already has a
     * path, since its leaf is read off the path's last edge. Without it a lease at the root, whose
     * live lineage is empty, would pass, and the root is never leasable.
     */
    fun requireConsistent(root: GroupId, epoch: Long, ledger: EntitlementLedger) {
        val id = lease.id
        require(id.epoch in 0L..epoch) { "lease $id is from epoch ${id.epoch}, outside 0..$epoch" }
        require(id.seq >= 0L) { "lease $id has a negative sequence number" }
        require(lease.reserved >= 0L) { "lease $id reserves ${lease.reserved}, a negative amount" }
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

/**
 * What [LocalHeddle.decode] found in a stored snapshot.
 *
 * @sample us.tractat.kuilt.heddle.sampleLocalPersistence
 */
public sealed interface RestoreResult {
    /** The bytes held a snapshot this release reads. Start an engine from it with [LocalHeddle.restore]. */
    public data class Restored(public val snapshot: LocalHeddleSnapshot) : RestoreResult

    /**
     * The bytes say they are format [found], which this release does not read; [supported] lists
     * the ones it does. Nothing past the version was read. A format newer than this release's own is
     * from a later release, or, for format 2, from a development build of 0.7.5.
     */
    public data class UnsupportedVersion(public val found: Int, public val supported: List<Int>) : RestoreResult

    /** The bytes are not a snapshot of a supported format, or hold one that is inconsistent: [reason] says which. */
    public data class Corrupt(public val reason: String) : RestoreResult
}

/**
 * Format 1, field for field and in wire order. Each format keeps its own surrogate, so changing
 * [LocalHeddleSnapshot] cannot change what a format's bytes mean, and a later format adds a
 * surrogate beside this one rather than editing it. The ledger, the allocator memory and the
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
    val ledger: LedgerFormat1,
    val allocatorMemory: AllocatorMemory,
    val leases: List<LeaseFormat1>,
    val lastApplied: Topology,
) {
    fun toSnapshot(): LocalHeddleSnapshot {
        val books = ledger.toLedger()
        return LocalHeddleSnapshot(epoch, books, allocatorMemory, totalSlots, leases.map { it.toPersisted(books) }, lastApplied)
    }

    companion object {
        fun of(snapshot: LocalHeddleSnapshot): SnapshotFormat1 = SnapshotFormat1(
            epoch = snapshot.epoch,
            totalSlots = snapshot.totalSlots,
            ledger = snapshot.ledger.toFormat1(),
            allocatorMemory = snapshot.allocatorMemory,
            leases = snapshot.open.map(LeaseFormat1::of),
            lastApplied = snapshot.lastApplied,
        )
    }
}

/**
 * One open lease in format 1: its id, reservation so far, start time, and path,
 * root edge first. The leaf is not stored: it is the child of the path's last edge, so the two
 * cannot disagree.
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
        require(reserved <= LocalHeddle.MAX_RESERVATION) { "MUTATION: lease reserves $reserved, past the cap" }
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
 * decoded. A sequence with its elements the other way round, with fewer than two, or with a third,
 * is malformed.
 */
internal object LocalHeddleSnapshotSerializer : KSerializer<LocalHeddleSnapshot> {
    private const val VERSION = 0
    private const val STATE = 1
    private const val ELEMENTS = 2

    /**
     * A LIST of exactly two elements, `formatVersion` an `Int` and `state` the surrogate of the
     * format this release writes, [LocalHeddleSnapshot.FORMAT_VERSION]:
     * the fixed-shape list the library's `StructureKind.LIST` documentation allows for. LIST, so
     * that every format reads it in order. Its own name and its own element types, so that schema
     * tooling and descriptor-driven formats see what is actually written.
     */
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("us.tractat.kuilt.heddle.LocalHeddleSnapshot", StructureKind.LIST) {
        element("formatVersion", Int.serializer().descriptor)
        element("state", SnapshotFormat1.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: LocalHeddleSnapshot) {
        encoder.encodeCollection(descriptor, collectionSize = ELEMENTS) {
            encodeIntElement(descriptor, VERSION, LocalHeddleSnapshot.FORMAT_VERSION)
            encodeSerializableElement(descriptor, STATE, SnapshotFormat1.serializer(), SnapshotFormat1.of(value))
        }
    }

    /**
     * Reads the size first, as a size-prefixed format needs: such a format would otherwise hand the
     * size back as the version. JSON, and CBOR's indefinite-length arrays, report no size (`-1`).
     * Then the version, the state and the end, in that order. A LIST is read by position, so the
     * order is the code's and there is no state-before-version case to refuse.
     */
    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): LocalHeddleSnapshot = decoder.decodeStructure(descriptor) {
        val size = decodeCollectionSize(descriptor)
        if (size != -1 && size != ELEMENTS) {
            throw SerializationException("a snapshot is a sequence of $ELEMENTS elements, but this one has $size")
        }
        if (decodeSequentially()) {
            val version = decodeIntElement(descriptor, VERSION).also(::requireSupported)
            return@decodeStructure decodeState(this, version)
        }
        if (!hasNextElement()) throw SerializationException("a snapshot has no formatVersion")
        val version = decodeIntElement(descriptor, VERSION).also(::requireSupported)
        if (!hasNextElement()) throw SerializationException("a snapshot of format $version has no state")
        val state = decodeState(this, version)
        // CBOR looks up an element's annotations in the descriptor before it hands back the
        // element's index, so an element past the state asks about an index the descriptor does not
        // describe, and it throws: that, too, is an element past the state.
        val pastState = try {
            hasNextElement()
        } catch (_: IndexOutOfBoundsException) {
            true
        }
        if (pastState) throw SerializationException("a snapshot has an element past its state")
        state
    }

    /** Whether the sequence holds another element. */
    private fun CompositeDecoder.hasNextElement(): Boolean = decodeElementIndex(descriptor) != CompositeDecoder.DECODE_DONE

    private fun requireSupported(found: Int) {
        if (found !in LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS) throw UnsupportedSnapshotFormat(found)
    }

    /** `state` as format [version] wrote it. One arm per entry of [LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS]. */
    private fun decodeState(decoder: CompositeDecoder, version: Int): LocalHeddleSnapshot = when (version) {
        1 -> decoder.decodeSerializableElement(descriptor, STATE, SnapshotFormat1.serializer()).toSnapshot()
        else -> throw UnsupportedSnapshotFormat(version)
    }
}
