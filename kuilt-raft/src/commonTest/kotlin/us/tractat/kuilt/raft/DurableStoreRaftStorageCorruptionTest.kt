package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [DurableStoreRaftStorage.open] refuses durable state it cannot read, and says which key.
 *
 * ## Why both arms, and why they must be distinguishable
 *
 * The two failures below reach a consumer through the same exception type and are repaired
 * differently, so a message that folded them together would be worse than none. **Garbage** under a
 * key means the medium is damaged — a truncated file, a half-written record, the wrong directory —
 * and the remedy is to inspect or re-provision the node. An **unknown version** means the record is
 * intact and was written by a *newer build of kuilt* than the one now reading it, and the remedy is
 * to stop downgrading. Nothing else in the tree distinguishes them, which is why the adapter reads
 * the version through a lenient probe before its strict decode rather than letting the strict decode
 * report both as "did not decode".
 *
 * ## Why the second arm forges bytes rather than mutating a real record
 *
 * `FORMAT_VERSION` is a private constant with one value, so there is no supported way to make the
 * adapter *write* a record carrying a different one. The unknown-version record is therefore built
 * by hand as CBOR — and because a hand-built record is a fixture that can rot silently into
 * something the version probe never even reaches, [anUnknownVersionIsRejectedAsSuchNotAsGarbage]
 * asserts on the message that only the version branch can produce. A forged record that had stopped
 * being decodable would fail that assertion rather than pass as a version rejection.
 */
class DurableStoreRaftStorageCorruptionTest {

    @Test
    fun garbageUnderMetaIsRefusedAndNamesTheKey(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = InMemoryDurableStore()
        store.write(DurableStoreRaftStorage.META_KEY, "not a raft meta record".encodeToByteArray())

        val failure = assertFailsWith<CorruptDurableStateException> { DurableStoreRaftStorage.open(store) }

        assertAll(
            { assertContains(failure.message.orEmpty(), "raft/meta", message = "names the key that failed") },
            {
                assertEquals(
                    "raft/meta",
                    DurableStoreRaftStorage.META_KEY.name,
                    "the key is a public constant, so a consumer can delete exactly what the message names",
                )
            },
        )
    }

    @Test
    fun garbageUnderTheLogAndTheSnapshotAreRefusedAndNameTheirOwnKeys(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            // Both arms in one test deliberately: what is under test is that the message names the
            // key that actually failed, and a single-key test cannot tell a correct message from one
            // that hardcodes "raft/meta".
            val logStore = InMemoryDurableStore()
            logStore.write(DurableStoreRaftStorage.LOG_KEY, byteArrayOf(0xFF.toByte(), 0x00, 0x7F))
            val logFailure =
                assertFailsWith<CorruptDurableStateException> { DurableStoreRaftStorage.open(logStore) }

            val snapshotStore = InMemoryDurableStore()
            snapshotStore.write(DurableStoreRaftStorage.SNAPSHOT_KEY, byteArrayOf(0xFF.toByte(), 0x00, 0x7F))
            val snapshotFailure =
                assertFailsWith<CorruptDurableStateException> { DurableStoreRaftStorage.open(snapshotStore) }

            assertAll(
                { assertContains(logFailure.message.orEmpty(), "raft/log", message = "the log failure names the log") },
                {
                    assertContains(
                        snapshotFailure.message.orEmpty(),
                        "raft/snapshot",
                        message = "the snapshot failure names the snapshot",
                    )
                },
            )
        }

    @Test
    fun anUnknownVersionIsRejectedAsSuchNotAsGarbage(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = InMemoryDurableStore()
        store.write(DurableStoreRaftStorage.META_KEY, metaRecordAtVersion(FROM_THE_FUTURE))

        val failure = assertFailsWith<CorruptDurableStateException> { DurableStoreRaftStorage.open(store) }

        val message = failure.message.orEmpty()
        assertAll(
            { assertContains(message, "raft/meta", message = "names the key") },
            // The three assertions below are what separate this from the garbage arm. Only the
            // version branch says "storage format version <n>" and names a newer build; the garbage
            // branch says "is not a readable record". If the forged record ever stopped being
            // decodable by the version probe, this arm would red rather than quietly re-testing the
            // garbage path.
            { assertContains(message, "version $FROM_THE_FUTURE", message = "names the version it found") },
            { assertContains(message, "newer build", message = "says what to do about it") },
            {
                assertEquals(
                    false,
                    message.contains("is not a readable record"),
                    "an unknown version is not reported as unreadable bytes — the remedies differ",
                )
            },
        )
    }

    /**
     * A `raft/meta` record, valid in every way except that its `version` is [version].
     *
     * Hand-built CBOR: a definite-length map of four entries, matching the field order the adapter's
     * own encoder emits (`version`, `term`, `votedFor`, `leader`), with a null vote and a null
     * leader. `0xA4` is a 4-entry map; each key is a definite-length text string
     * (`0x60 + length`); `0xF6` is CBOR null.
     */
    private fun metaRecordAtVersion(version: Int): ByteArray = byteArrayOf(0xA4.toByte()) +
        cborText("version") + byteArrayOf(version.toByte()) +
        cborText("term") + byteArrayOf(0x00) +
        cborText("votedFor") + byteArrayOf(0xF6.toByte()) +
        cborText("leader") + byteArrayOf(0xF6.toByte())

    /** [text] as a CBOR definite-length text string. Every key here is under 24 bytes. */
    private fun cborText(text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        check(bytes.size < CBOR_SHORT_LIMIT) { "'$text' needs a longer CBOR length prefix than this helper writes" }
        return byteArrayOf((CBOR_TEXT_MAJOR + bytes.size).toByte()) + bytes
    }

    private companion object {
        /** Any version this build is not; small enough to encode as one CBOR immediate byte. */
        const val FROM_THE_FUTURE = 9

        /** CBOR major type 3 (text string) with an immediate length. */
        const val CBOR_TEXT_MAJOR = 0x60

        /** Above this an immediate CBOR length needs a following length byte. */
        const val CBOR_SHORT_LIMIT = 24
    }
}
