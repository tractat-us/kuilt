package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The cross-version and cross-target byte pin for [DurableStoreRaftStorage]'s storage format,
 * following the `CanonicalGoldenVectorTest` and SRA wire byte-parity precedent.
 *
 * ## What only a checked-in byte string can catch
 *
 * The conformance subclasses prove the adapter round-trips **itself**: whatever it writes, it reads
 * back. That is true of any self-consistent encoding, including one that changed yesterday — so
 * nothing in that suite notices when the bytes move. Two failures follow, and both are silent.
 *
 * **Across versions.** These bytes are a consumer's disk. A node written by one release and read by
 * the next has to decode, and an encoding change that nobody noticed turns every existing node's
 * durable state into a [CorruptDurableStateException] at start-up — or worse, into a record that
 * still decodes and means something else. A diff here is the signal that
 * `DurableStoreRaftStorage`'s `FORMAT_VERSION` must be bumped and a migration considered; it is not
 * a nuisance to re-record.
 *
 * **Across targets.** `commonTest` compiles and runs on JVM, Android, iOS, macOS and wasmJs, so
 * **this file *is* the cross-target check**. It is not hypothetical here: `ClusterConfig.voters` and
 * `.learners` are `Set<NodeId>`, and a set's iteration order is bucket-derived on the JVM and
 * insertion-derived on Kotlin/Native — so a config written on a phone and read on a server would
 * encode differently from the same value if the adapter stored the sets as they came. It sorts them
 * instead, and [aLogRecordWithAConfigAndADedupKey] is what holds that sort in place: its voters and
 * learners are inserted in **non-sorted** order, so the vector is only stable because the mapping
 * reorders them.
 *
 * ## The vectors are taken through the public write path
 *
 * Every constant below is what a real [DurableStoreRaftStorage] actually wrote into a real
 * [DurableStore], read back with the public [DurableStoreRaftStorage.META_KEY] and its siblings —
 * not what a private mirror record encodes to when constructed by hand. A test that reached past
 * the write path would pin the encoding of records the adapter might no longer write, which is the
 * failure it exists to prevent, one level in.
 *
 * ## Deliberately non-default in every field
 *
 * A field sitting at its default contributes nothing to a pin, so each vector drives every field it
 * can off its default: the meta record carries a vote **and** an established leader whose term
 * differs from the current one; the log record carries one entry with a config and a dedup key and
 * one plain application entry with a non-empty command, so `isNoOp`, `config` and `dedupKey` are
 * each exercised present and absent; the snapshot carries a joint configuration and non-empty state
 * bytes. `encodeDefaults` is on, so a field at its default is still *present* — what varies is
 * whether its value is one this vector would notice changing.
 */
class DurableStoreRaftStorageGoldenVectorTest {

    @Test
    fun everyVectorMatchesOnEveryTarget(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        // Encoded before the assertion block, not inside it: `assertAll` takes ordinary lambdas, so
        // a suspending call cannot sit in one.
        val meta = aMetaRecord()
        val log = aLogRecordWithAConfigAndADedupKey()
        val snapshot = aSnapshotRecordWithAJointConfig()
        assertAll(
            { assertEquals(META, meta, "meta") },
            { assertEquals(LOG, log, "log") },
            { assertEquals(SNAPSHOT, snapshot, "snapshot") },
        )
    }

    /**
     * The stored bytes decode back to the values that produced them — on this target, whatever it is.
     *
     * Pairs with [everyVectorMatchesOnEveryTarget], which alone would go green for a format that
     * encoded stably and decoded to something else entirely.
     */
    @Test
    fun everyVectorStillDecodesToWhatWroteIt(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = InMemoryDurableStore()
        val storage = DurableStoreRaftStorage.open(store)
        storage.writeTheMetaVector()
        storage.writeTheLogVector()
        storage.writeTheSnapshotVector()

        val restarted = DurableStoreRaftStorage.open(store)
        val snapshot = assertNotNull(restarted.loadSnapshot(), "the snapshot came back")
        val term = restarted.term()
        val votedFor = restarted.votedFor()
        val leader = restarted.leaderForTerm()
        val entries = restarted.entries()
        assertAll(
            { assertEquals(TERM, term, "term") },
            { assertEquals(NodeId(VOTED_FOR), votedFor, "vote") },
            { assertEquals(LeaderForTerm(LEADER_TERM, NodeId(LEADER)), leader, "leader pin") },
            { assertEquals(logVectorEntries(), entries, "log") },
            { assertEquals(snapshotVectorMeta(), snapshot.meta, "snapshot baseline and config") },
            { assertEquals(SNAPSHOT_STATE.toList(), snapshot.state.toList(), "snapshot state bytes") },
        )
    }

    // ── Constructions ─────────────────────────────────────────────────────────

    private suspend fun aMetaRecord(): String =
        writtenBytes(DurableStoreRaftStorage.META_KEY) { it.writeTheMetaVector() }

    private suspend fun aLogRecordWithAConfigAndADedupKey(): String =
        writtenBytes(DurableStoreRaftStorage.LOG_KEY) { it.writeTheLogVector() }

    private suspend fun aSnapshotRecordWithAJointConfig(): String =
        writtenBytes(DurableStoreRaftStorage.SNAPSHOT_KEY) { it.writeTheSnapshotVector() }

    /** The leader pin's term differs from the current term, so the two cannot be confused in the bytes. */
    private suspend fun DurableStoreRaftStorage.writeTheMetaVector() {
        saveTermAndVotedFor(TERM, NodeId(VOTED_FOR))
        saveLeaderForTerm(LEADER_TERM, NodeId(LEADER))
    }

    private suspend fun DurableStoreRaftStorage.writeTheLogVector() = appendEntries(logVectorEntries())

    private suspend fun DurableStoreRaftStorage.writeTheSnapshotVector() =
        saveSnapshot(snapshotVectorMeta(), SNAPSHOT_STATE)

    /**
     * Two entries: a membership entry carrying a simple config and no dedup key, and a plain
     * application entry carrying a dedup key and a non-empty command.
     *
     * The voters and learners are listed **out of sorted order** on purpose — `zulu` before `alpha`,
     * `yankee` before `bravo`. The stored form sorts them, so [LOG] is stable across targets only
     * because that sort runs. Drop it and the vector moves on Kotlin/Native, on wasm, or both.
     */
    private fun logVectorEntries(): List<LogEntry> = listOf(
        LogEntry(
            index = 1L,
            term = 3L,
            command = ByteArray(0),
            config = ConfigPayload(
                old = null,
                new = ClusterConfig(
                    voters = setOf(NodeId("zulu"), NodeId("alpha"), NodeId("mike")),
                    learners = setOf(NodeId("yankee"), NodeId("bravo")),
                ),
            ),
        ),
        LogEntry(
            index = 2L,
            term = 3L,
            command = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte()),
            dedupKey = DedupKey(ClientId("client-kilo"), requestId = 42L),
        ),
    )

    /** A **joint** baseline, so both halves of a [ConfigPayload] are on the wire. */
    private fun snapshotVectorMeta(): SnapshotMeta = SnapshotMeta(
        lastIncludedIndex = 9L,
        lastIncludedTerm = 3L,
        config = ConfigPayload(
            old = ClusterConfig(voters = setOf(NodeId("zulu"), NodeId("alpha"))),
            new = ClusterConfig(
                voters = setOf(NodeId("mike"), NodeId("alpha")),
                learners = setOf(NodeId("yankee")),
            ),
        ),
    )

    // ── Plumbing ──────────────────────────────────────────────────────────────

    /**
     * The hex of whatever [write] left under [key], through a real store.
     *
     * A fresh store per vector, so one vector's writes can never leak into another's record.
     */
    private suspend fun writtenBytes(key: StoreKey, write: suspend (DurableStoreRaftStorage) -> Unit): String {
        val store = InMemoryDurableStore()
        write(DurableStoreRaftStorage.open(store))
        return hex(assertNotNull(store.read(key), "the adapter wrote nothing under '${key.name}'"))
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> (byte.toInt() and BYTE_MASK).toString(radix = HEX).padStart(2, '0') }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val HEX = 16

        const val TERM = 7L
        const val VOTED_FOR = "node-alpha"
        const val LEADER_TERM = 6L
        const val LEADER = "node-mike"
        val SNAPSHOT_STATE = byteArrayOf(0x01, 0x02, 0xFE.toByte(), 0xFF.toByte())

        // ── The vectors ───────────────────────────────────────────────────────
        //
        // Regenerate ONLY on a deliberate encoding change, and bump FORMAT_VERSION in the same
        // change. A single vector moving on one target and not another is the defect this file
        // exists to catch — investigate, do not re-record.

        const val META =
            "bf6776657273696f6e01647465726d0768766f746564466f726a6e6f64652d616c706861666c6561646572bf" +
            "647465726d06666e6f64654964696e6f64652d6d696b65ffff"
        const val LOG =
            "bf6776657273696f6e0167656e74726965739fbf65696e64657801647465726d0367636f6d6d616e64406669" +
            "734e6f4f70f466636f6e666967bf696f6c64566f74657273f66b6f6c644c6561726e657273f6696e6577566f" +
            "746572739f65616c706861646d696b65647a756c75ff6b6e65774c6561726e6572739f65627261766f667961" +
            "6e6b6565ffff6864656475704b6579a0ffbf65696e64657802647465726d0367636f6d6d616e6444007f80ff" +
            "6669734e6f4f70f466636f6e666967a06864656475704b6579bf68636c69656e7449646b636c69656e742d6b" +
            "696c6f69726571756573744964182affffffff"
        const val SNAPSHOT =
            "bf6776657273696f6e01716c617374496e636c75646564496e64657809706c617374496e636c756465645465" +
            "726d0366636f6e666967bf696f6c64566f746572739f65616c706861647a756c75ff6b6f6c644c6561726e65" +
            "72739fff696e6577566f746572739f65616c706861646d696b65ff6b6e65774c6561726e6572739f6679616e" +
            "6b6565ffff657374617465440102feffff"
    }
}
