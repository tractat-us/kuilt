// `Cbor` and its builder options are all still `@ExperimentalSerializationApi`, and this file
// touches them in three places (the two codecs and every encode/decode call). File-level, so a
// fourth cannot be added without one — the same shape :kuilt-crdt's canonical serializers use.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey

/**
 * A [RaftStorage] that keeps a node's term, vote, established leader, log and snapshot in a
 * [DurableStore], so they are all still there after the process restarts.
 *
 * This is the reference durable adapter — the answer to the "inject a persistent [RaftStorage] in
 * production" line every other doc in this module points at. Give it whichever [DurableStore] the
 * platform provides (`FileChannelDurableStore` on JVM/Android, `NSFileManagerDurableStore` on
 * iOS/macOS, `IndexedDbDurableStore` in a browser) and the node comes back holding what it knew:
 *
 * ```kotlin
 * val storage = DurableStoreRaftStorage.open(FileChannelDurableStore(nodeDirectory))
 * val node = scope.raftNode(cluster, transport, storage)
 * ```
 *
 * @sample us.tractat.kuilt.raft.sampleDurableRaftStorage
 *
 * ## One store per node
 *
 * The three keys are **fixed** ([META_KEY], [LOG_KEY], [SNAPSHOT_KEY]), so **one [DurableStore]
 * belongs to one node**, exactly as one directory belongs to one `MappedBolt`. Point two nodes at
 * the same store — the same directory, the same IndexedDB database — and each will overwrite the
 * other's term, vote and log, which is a Raft §5.2 election-safety violation with no symptom until
 * the cluster splits. Give every node its own store.
 *
 * There is deliberately **no** namespace or key-prefix parameter to make that sharing safe. A
 * prefix built out of data — a game id, a room id — walks straight into [StoreKey]'s length,
 * case-folding and surrogate caveats, and `kuilt-store/module.md` is explicit that keys are meant
 * to be a handful of fixed, hand-written names. Separate stores, not separate prefixes.
 *
 * The keys are public constants because [DurableStore] has no enumeration: without them a consumer
 * that wants to wipe a node's state — re-provisioning it, clearing a corrupt record — has nothing
 * to delete and no way to find out. That is the gap #2208 recorded, so they are named here rather
 * than hidden behind an `erase()` this class would then have to define the semantics of.
 *
 * ## Durability, and what a write returning means
 *
 * Every mutator builds the state it is about to have, encodes it, calls [DurableStore.write], and
 * only then updates its in-memory copy. A write that throws leaves memory holding the last
 * **committed** state and propagates the failure: the engine is never told a term or an entry is
 * durable when it is not. [DurableStore.write] returns only once the bytes are committed, so
 * "the write returned" is the whole of this adapter's durability claim — what that costs, and where
 * each platform's guarantee stops short, is the store's business and its own KDoc says so.
 *
 * [saveTerm], [saveVotedFor], [saveTermAndVotedFor] and [saveLeaderForTerm] all write **one**
 * [META_KEY] record carrying all of term, vote and the leader pin. `DurableStore.write` is atomic
 * per key, which is exactly what [RaftStorage.saveTermAndVotedFor]'s §5.1/§5.2 requirement and
 * [RaftStorage.saveLeaderForTerm]'s "MUST write term and leaderId as one record" both ask for.
 * [saveSnapshot] writes [SNAPSHOT_KEY] and the three log mutators rewrite [LOG_KEY]; the engine
 * already sequences `saveSnapshot` before `discardLogPrefix`, so the ordering across those two keys
 * is the caller's and is already right.
 *
 * All state is guarded by a [Mutex] rather than by confining the work to a single-threaded
 * dispatcher, so the type is correct under a genuinely multi-threaded scope.
 *
 * ## Cost, and what this is not
 *
 * The log is one blob. An append therefore costs one encode plus one committed write of the
 * **whole retained log** — O(retained entries), not O(appended entries) — and so does a truncate or
 * a prefix discard. What bounds it is how often the consumer compacts: publish a state-machine
 * snapshot into [RaftNode.snapshots] and the engine saves it and drops the covered prefix, after
 * which the retained log is only what has accumulated since.
 *
 * That makes this the reference adapter for a **bounded** log — a game, a room, a small cluster
 * that snapshots regularly — and not a high-throughput log engine. A cluster committing thousands
 * of entries a second between snapshots wants a segmented, append-only backend, which is a
 * different piece of work.
 *
 * `Bolt` (`:kuilt-bolt`) is the obvious-looking medium for a log and is the wrong one here, for a
 * reason its own KDoc states: it is a **write-only** archive that forbids authoring from a replay,
 * kept *beside* a live replica rather than being one. A Raft log has to be restored from on start-up
 * and truncated at the tail on conflict resolution ([truncateFrom]), and a bolt does neither.
 *
 * ## Format
 *
 * The stored bytes are a **private, versioned** format that mirrors the public types rather than
 * borrowing their serializers, and is encoded by this file's own [Cbor] instance. The wire format
 * and the storage format are independent on purpose: a change to how the engine frames a
 * `RaftMessage` must not silently move the bytes on a consumer's disk. Changing the encoding means
 * bumping [FORMAT_VERSION] — `DurableStoreRaftStorageGoldenVectorTest` pins the current bytes, and
 * a diff there is the signal, not a nuisance.
 *
 * A record that does not decode, or that carries a [FORMAT_VERSION] this build does not know,
 * raises [CorruptDurableStateException] from [open], naming the key. [open] performs **no** range
 * validation of its own — a term outside `0..2^60`, a log with a gap, terms that decrease — because
 * the adapter's job is faithfulness and the engine is the validation point (#1887).
 */
public class DurableStoreRaftStorage private constructor(
    private val store: DurableStore,
    private var meta: StoredMeta,
    private var log: List<LogEntry>,
    private var snapshot: StoredSnapshot?,
) : RaftStorage {

    /**
     * Guards every field above.
     *
     * A [Mutex] and not `limitedParallelism(1)` confinement: a scope-owning type here must be
     * correct under a multi-threaded dispatcher as a local property of its own state, not as an
     * emergent property of where its coroutines happen to run. It is held across the
     * [DurableStore.write] suspension deliberately — two concurrent mutators must not interleave
     * "encode candidate / write / commit to memory", or the cache would end up holding a state the
     * medium never saw.
     */
    private val mutex = Mutex()

    // ── Term / vote / leader ─────────────────────────────────────────────────

    override suspend fun term(): Long = mutex.withLock { meta.term }

    override suspend fun saveTerm(term: Long): Unit =
        mutex.withLock { commitMeta(meta.copy(term = term)) }

    override suspend fun votedFor(): NodeId? = mutex.withLock { meta.votedFor?.let(::NodeId) }

    override suspend fun saveVotedFor(nodeId: NodeId?): Unit =
        mutex.withLock { commitMeta(meta.copy(votedFor = nodeId?.value)) }

    override suspend fun saveTermAndVotedFor(term: Long, votedFor: NodeId?): Unit =
        mutex.withLock { commitMeta(meta.copy(term = term, votedFor = votedFor?.value)) }

    override suspend fun leaderForTerm(): LeaderForTerm? =
        mutex.withLock { meta.leader?.let { LeaderForTerm(it.term, NodeId(it.nodeId)) } }

    override suspend fun saveLeaderForTerm(term: Long, leaderId: NodeId): Unit =
        mutex.withLock { commitMeta(meta.copy(leader = StoredLeader(term, leaderId.value))) }

    // ── Log ──────────────────────────────────────────────────────────────────

    override suspend fun appendEntries(entries: List<LogEntry>): Unit =
        mutex.withLock { commitLog(log + entries.map { it.defensiveCopy() }) }

    override suspend fun entries(fromIndex: Long): List<LogEntry> =
        mutex.withLock { log.filter { it.index >= fromIndex } }

    override suspend fun truncateFrom(index: Long): Unit =
        mutex.withLock { commitLog(log.filter { it.index < index }) }

    override suspend fun discardLogPrefix(throughIndex: Long): Unit =
        mutex.withLock { commitLog(log.filter { it.index > throughIndex }) }

    // ── Snapshot ─────────────────────────────────────────────────────────────

    override suspend fun saveSnapshot(meta: SnapshotMeta, state: ByteArray): Unit = mutex.withLock {
        val candidate = StoredSnapshot(meta, state.copyOf())
        store.write(SNAPSHOT_KEY, encode(StoredSnapshotRecord.serializer(), candidate.toStored()))
        snapshot = candidate
    }

    override suspend fun loadSnapshot(): StoredSnapshot? =
        mutex.withLock { snapshot?.let { StoredSnapshot(it.meta, it.state.copyOf()) } }

    // ── Write-through ────────────────────────────────────────────────────────

    /**
     * Encode [candidate], commit it to the medium, and only then adopt it in memory.
     *
     * The order is the whole point: if [DurableStore.write] throws, [meta] still holds the last
     * state the medium accepted and the failure reaches the caller, so a reopen and this handle
     * agree. Committing first and writing after would have the engine believe a term is durable
     * that is not, which is the §5.2 hole `RaftStorage`'s KDoc warns about.
     */
    private suspend fun commitMeta(candidate: StoredMeta) {
        store.write(META_KEY, encode(StoredMeta.serializer(), candidate))
        meta = candidate
    }

    /** [commitMeta] for the log. The whole retained log is one record — see the cost note above. */
    private suspend fun commitLog(candidate: List<LogEntry>) {
        store.write(LOG_KEY, encode(StoredLog.serializer(), StoredLog(entries = candidate.map { it.toStored() })))
        log = candidate
    }

    public companion object {
        /**
         * Term, vote and the §5.2 established-leader pin — one record, so
         * [RaftStorage.saveTermAndVotedFor] and [RaftStorage.saveLeaderForTerm] are each a single
         * atomic [DurableStore.write].
         */
        public val META_KEY: StoreKey = StoreKey("raft/meta")

        /** The whole retained log, as one record. */
        public val LOG_KEY: StoreKey = StoreKey("raft/log")

        /** The most recent snapshot's baseline and opaque state bytes. */
        public val SNAPSHOT_KEY: StoreKey = StoreKey("raft/snapshot")

        /**
         * Open a handle onto whatever [store] already holds.
         *
         * Reads all three keys, decodes them, and returns a storage whose in-memory state mirrors
         * the medium. A brand-new store yields the same starting values as [InMemoryRaftStorage]:
         * term `0`, no vote, no established leader, an empty log and no snapshot.
         *
         * Throws [CorruptDurableStateException], naming the key, when a record does not decode or
         * carries a format version this build does not know. It deliberately validates nothing
         * else: a term outside the plausible range, a log with a gap, terms that decrease — all of
         * those are the **engine's** to refuse on restore (#1887), and an adapter that repaired
         * them would be laundering evidence that the durable state is wrong.
         */
        public suspend fun open(store: DurableStore): DurableStoreRaftStorage {
            val meta = store.read(META_KEY)
                ?.let { decode(META_KEY, it, StoredMeta.serializer()) }
                ?: StoredMeta()
            val log = store.read(LOG_KEY)
                ?.let { decode(LOG_KEY, it, StoredLog.serializer()) }
                ?.entries.orEmpty().map { it.toEntry() }
            val snapshot = store.read(SNAPSHOT_KEY)
                ?.let { decode(SNAPSHOT_KEY, it, StoredSnapshotRecord.serializer()) }
                ?.toSnapshot()
            return DurableStoreRaftStorage(store, meta, log, snapshot)
        }
    }
}

// ── Storage format ───────────────────────────────────────────────────────────
//
// Private mirrors of the public types, NOT the public types themselves. `LogEntry`, `ConfigPayload`
// and `ClusterConfig` all carry `@Serializable` for the *wire*, and `SnapshotMeta` /
// `LeaderForTerm` / `StoredSnapshot` carry none at all. Borrowing the wire serializers would tie a
// consumer's on-disk bytes to the engine's framing, so a PR that changes how a RaftMessage is
// encoded would silently move what is already written on disk. These records are this file's alone
// and move only when FORMAT_VERSION does.
//
// `ClusterConfig`'s voters and learners are `Set<NodeId>`, whose iteration order is not the same on
// every target. They are stored as SORTED lists so the encoding is a function of the value and not
// of the platform's hash layout — which is also what makes the golden vector meaningful, since an
// unsorted set would encode differently on JVM and Kotlin/Native from the same input.
//
// ⚠ A null *structured* field is written as the empty DEFINITE map `0xA0`, not as CBOR null `0xF6`
// (which this encoder uses only for a null primitive or list) — visible in the golden vectors, where
// a null `dedupKey` is `a0` and a null `oldVoters` is `f6`. What keeps that unambiguous is the
// **framing byte**, and it is worth writing down because the obvious reading of it is wrong.
//
// Measured on kotlinx-serialization-cbor 1.11.0, by encoding and decoding a two-record fixture:
//
//   present record, any field values     -> `bf … ff`   (indefinite map)
//   present record, ALL fields defaulted -> `bfff`      (empty INDEFINITE map) — decodes as PRESENT
//   null                                 -> `a0`        (empty DEFINITE map)   — decodes as NULL
//
// So a present record can never collide with the null marker: the writer never emits a definite map
// for one. And the converse — an `a0` on the wire decoding as a present-but-empty record — is not
// reachable either, at any shape of the record: the reader tests for null before the class is
// consulted at all, so a forged `a0` came back as `null` both for a nested class whose every field
// has a default and for one with none. **Field defaults are therefore irrelevant to this**, which is
// what an earlier version of this comment got backwards: it claimed each nullable record had to keep
// one field undefaulted, and no measurement supports that.
//
// The property this actually rests on is a behaviour of the LIBRARY, not of the records below —
// that its writer frames a present record indefinitely and its reader reads `0xA0` as null. A
// kotlinx-serialization version bump could move either. The golden vectors are the tripwire, since
// any change to the framing moves all three of them at once.

/** The version stamped into every record. Bump it on any change to the encodings below. */
private const val FORMAT_VERSION: Int = 1

/**
 * The storage codec.
 *
 * `alwaysUseByteString` so a `command` and a snapshot's `state` cost their own length rather than
 * CBOR's default array-of-integers rendering, which is roughly a byte and a half per byte.
 *
 * `encodeDefaults` because the format is **self-describing or it is nothing**. It is `false` by
 * default in kotlinx-serialization, which would drop every field sitting at its default — including
 * `version` itself, so a freshly-opened node's meta record would encode as an empty map and the
 * version probe below would have nothing to read. Every field present on every record is also what
 * keeps the golden vector a pin on the whole shape rather than on whichever fields happened to be
 * non-default in the vector's construction.
 *
 * Its own instance, not the engine's `raftCbor`: the storage format and the wire format are
 * independent, and an instance shared with the wire would carry a wire-motivated setting change
 * onto disk.
 */
private val storageCbor = Cbor {
    alwaysUseByteString = true
    encodeDefaults = true
}

/**
 * [storageCbor] plus `ignoreUnknownKeys`, used only to read a record's [FORMAT_VERSION] before
 * anything else is decoded.
 *
 * Without this pass an unknown version would be indistinguishable from a corrupt record: a future
 * record with an extra field fails the strict decode, and the report would say "did not decode"
 * when the truth is "written by a newer build". Reading the version leniently first lets the two be
 * told apart, which is the difference between a consumer downgrading a deployment and a consumer
 * with a damaged disk.
 */
private val versionProbeCbor = Cbor {
    alwaysUseByteString = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** Just enough of any record to read its version. */
@Serializable
private class StoredVersion(val version: Int)

/** The §5.2 established-leader pin. Nested so the term and the id cannot exist apart. */
@Serializable
private data class StoredLeader(val term: Long, val nodeId: String)

/** [DurableStoreRaftStorage.META_KEY]'s record: term, vote and the leader pin together. */
@Serializable
private data class StoredMeta(
    val version: Int = FORMAT_VERSION,
    val term: Long = 0L,
    val votedFor: String? = null,
    val leader: StoredLeader? = null,
)

/** A membership payload, with both member sets sorted so the encoding is target-independent. */
@Serializable
private data class StoredConfig(
    val oldVoters: List<String>? = null,
    val oldLearners: List<String>? = null,
    val newVoters: List<String>,
    val newLearners: List<String>,
)

/** A proposal's §8 dedup identity. */
@Serializable
private data class StoredDedupKey(val clientId: String, val requestId: Long)

/** One log entry. */
@Serializable
private data class StoredEntry(
    val index: Long,
    val term: Long,
    val command: ByteArray,
    val isNoOp: Boolean,
    val config: StoredConfig? = null,
    val dedupKey: StoredDedupKey? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is StoredEntry && index == other.index && term == other.term &&
                    command.contentEquals(other.command) && isNoOp == other.isNoOp &&
                    config == other.config && dedupKey == other.dedupKey
                )

    override fun hashCode(): Int {
        var result = index.hashCode()
        result = 31 * result + term.hashCode()
        result = 31 * result + command.contentHashCode()
        result = 31 * result + isNoOp.hashCode()
        result = 31 * result + config.hashCode()
        result = 31 * result + dedupKey.hashCode()
        return result
    }
}

/** [DurableStoreRaftStorage.LOG_KEY]'s record: the whole retained log. */
@Serializable
private data class StoredLog(val version: Int = FORMAT_VERSION, val entries: List<StoredEntry>)

/** [DurableStoreRaftStorage.SNAPSHOT_KEY]'s record. */
@Serializable
private data class StoredSnapshotRecord(
    val version: Int = FORMAT_VERSION,
    val lastIncludedIndex: Long,
    val lastIncludedTerm: Long,
    val config: StoredConfig? = null,
    val state: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is StoredSnapshotRecord && version == other.version &&
                    lastIncludedIndex == other.lastIncludedIndex &&
                    lastIncludedTerm == other.lastIncludedTerm && config == other.config &&
                    state.contentEquals(other.state)
                )

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + lastIncludedIndex.hashCode()
        result = 31 * result + lastIncludedTerm.hashCode()
        result = 31 * result + config.hashCode()
        result = 31 * result + state.contentHashCode()
        return result
    }
}

// ── Mapping ──────────────────────────────────────────────────────────────────

private fun ClusterConfig.storedVoters(): List<String> = voters.map { it.value }.sorted()

private fun ClusterConfig.storedLearners(): List<String> = learners.map { it.value }.sorted()

private fun clusterConfig(voters: List<String>, learners: List<String>): ClusterConfig =
    ClusterConfig(voters.map(::NodeId).toSet(), learners.map(::NodeId).toSet())

private fun ConfigPayload.toStored(): StoredConfig = StoredConfig(
    oldVoters = old?.storedVoters(),
    oldLearners = old?.storedLearners(),
    newVoters = new.storedVoters(),
    newLearners = new.storedLearners(),
)

/**
 * The inverse of [ConfigPayload.toStored].
 *
 * `oldVoters` decides whether the configuration is joint: [ConfigPayload.old] is `null` for a
 * simple C_new and non-null for a joint C_{old,new}, and a joint config always has voters, so the
 * two halves of `old` are read together off that one nullable field rather than independently.
 */
private fun StoredConfig.toPayload(): ConfigPayload = ConfigPayload(
    old = oldVoters?.let { clusterConfig(it, oldLearners.orEmpty()) },
    new = clusterConfig(newVoters, newLearners),
)

private fun LogEntry.toStored(): StoredEntry = StoredEntry(
    index = index,
    term = term,
    command = command,
    isNoOp = isNoOp,
    config = config?.toStored(),
    dedupKey = dedupKey?.let { StoredDedupKey(it.clientId.value, it.requestId) },
)

private fun StoredEntry.toEntry(): LogEntry = LogEntry(
    index = index,
    term = term,
    command = command,
    isNoOp = isNoOp,
    config = config?.toPayload(),
    dedupKey = dedupKey?.let { DedupKey(ClientId(it.clientId), it.requestId) },
)

private fun StoredSnapshot.toStored(): StoredSnapshotRecord = StoredSnapshotRecord(
    lastIncludedIndex = meta.lastIncludedIndex,
    lastIncludedTerm = meta.lastIncludedTerm,
    config = meta.config?.toStored(),
    state = state,
)

private fun StoredSnapshotRecord.toSnapshot(): StoredSnapshot = StoredSnapshot(
    SnapshotMeta(lastIncludedIndex, lastIncludedTerm, config?.toPayload()),
    state,
)

/**
 * A copy holding no array the caller can still reach.
 *
 * [LogEntry.command] is a mutable [ByteArray] owned by whoever built the entry. Encoding it takes a
 * snapshot of the bytes at write time, so retaining the caller's array in the cache would let a
 * later mutation of it make this handle disagree with the medium — the one invariant the
 * write-through order exists to hold. Copying on the way in keeps `cache == medium` a property of
 * this class rather than a promise about the caller's behaviour.
 *
 * The read side is deliberately *not* copied: [entries] hands back the cached entries, sharing
 * their `command` arrays exactly as `InMemoryRaftStorage` shares its own. [RaftStorage] says
 * nothing about it, both implementations behave alike, and copying every command on every read
 * would put an allocation proportional to the log on the engine's hot path for no contract gain.
 */
private fun LogEntry.defensiveCopy(): LogEntry = copy(command = command.copyOf())

// ── Decoding ─────────────────────────────────────────────────────────────────

/**
 * Decode [bytes] read from [key], or raise [CorruptDurableStateException] naming it.
 *
 * ## Why a broad catch is safe here without an `ensureActive()`
 *
 * This is deliberately **not** a `suspend` function, and deliberately not an inline one taking a
 * lambda. Both would let a suspension point sit inside the `try`, and a broad catch around one is
 * the cancellation swallow this repo bans: `CancellationException` would arrive as an ordinary
 * `Exception` and be reported as a corrupt record. With no suspension point in the body there is no
 * cancellation to catch, so the structure is standing in for the `ensureActive()` an ordinary broad
 * catch in a coroutine would need — and cannot silently stop being true, because adding a suspend
 * call here would not compile.
 *
 * The catch is broad on purpose. The input is arbitrary bytes off a medium this code does not
 * control, and CBOR's failure surface on garbage is not one type: a bad major type and a wrong
 * field type raise `SerializationException` (an `IllegalArgumentException`), while a truncated
 * length prefix can surface as an index error instead. Naming a subset would let the shapes it
 * missed escape [open] as something other than [CorruptDurableStateException], which is the one
 * thing a consumer is told to expect.
 */
private fun <T> decode(key: StoreKey, bytes: ByteArray, serializer: DeserializationStrategy<T>): T {
    val version = try {
        versionProbeCbor.decodeFromByteArray(StoredVersion.serializer(), bytes).version
    } catch (failure: Exception) {
        throw CorruptDurableStateException(
            "durable Raft state under '${key.name}' is not a readable record " +
                "(${bytes.size} bytes): $failure",
        )
    }
    if (version != FORMAT_VERSION) {
        throw CorruptDurableStateException(
            "durable Raft state under '${key.name}' carries storage format version $version, " +
                "which this build does not know (it writes and reads version $FORMAT_VERSION). " +
                "It was most likely written by a newer build of kuilt.",
        )
    }
    return try {
        storageCbor.decodeFromByteArray(serializer, bytes)
    } catch (failure: Exception) {
        throw CorruptDurableStateException(
            "durable Raft state under '${key.name}' claims storage format version $version but " +
                "does not decode as one (${bytes.size} bytes): $failure",
        )
    }
}

private fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
    storageCbor.encodeToByteArray(serializer, value)
