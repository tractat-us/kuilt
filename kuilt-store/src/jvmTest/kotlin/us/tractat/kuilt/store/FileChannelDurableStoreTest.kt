package us.tractat.kuilt.store

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Crash-recovery tests for [FileChannelDurableStore].
 *
 * "Crash" is simulated by constructing a second [FileChannelDurableStore] instance
 * over the same directory — there is no shared in-memory state, so the second
 * instance must read everything from disk.
 */
class FileChannelDurableStoreTest {

    private fun tempStore(): FileChannelDurableStore =
        FileChannelDurableStore(createTempDirectory("kuilt-store-test").toFile())

    private fun storeAt(dir: java.io.File): FileChannelDurableStore =
        FileChannelDurableStore(dir)

    // ---- missing key → null ----

    @Test
    fun readReturnsNullForAbsentKey() = runTest {
        assertNull(tempStore().read(StoreKey("missing")))
    }

    // ---- write then read ----

    @Test
    fun writeAndReadRoundTrips() = runTest {
        val store = tempStore()
        val key = StoreKey("key")
        val bytes = byteArrayOf(1, 2, 3)
        store.write(key, bytes)
        assertContentEquals(bytes, store.read(key))
    }

    // ---- crash recovery ----

    @Test
    fun crashRecoveryRoundTrip() = runTest {
        val dir = createTempDirectory("kuilt-store-crash").toFile()
        val key = StoreKey("spans")
        val bytes = byteArrayOf(10, 20, 30)

        // Write with first store instance.
        storeAt(dir).write(key, bytes)

        // Simulate restart: brand-new instance, same directory.
        val recovered = storeAt(dir).read(key)
        assertContentEquals(bytes, recovered)
    }

    // ---- overwrite ----

    @Test
    fun secondWriteOverwritesFirst() = runTest {
        val dir = createTempDirectory("kuilt-store-overwrite").toFile()
        val key = StoreKey("k")
        storeAt(dir).write(key, byteArrayOf(1))
        storeAt(dir).write(key, byteArrayOf(2))
        assertContentEquals(byteArrayOf(2), storeAt(dir).read(key))
    }

    // ---- overwrite survives simulated restart ----

    @Test
    fun overwritePersistedAfterRestart() = runTest {
        val dir = createTempDirectory("kuilt-store-overwrite2").toFile()
        val key = StoreKey("k")
        storeAt(dir).write(key, byteArrayOf(1))
        storeAt(dir).write(key, byteArrayOf(99))

        val recovered = FileChannelDurableStore(dir).read(key)
        assertContentEquals(byteArrayOf(99), recovered)
    }

    // ---- delete ----

    @Test
    fun deleteRemovesKey() = runTest {
        val store = tempStore()
        val key = StoreKey("k")
        store.write(key, byteArrayOf(42))
        store.delete(key)
        assertNull(store.read(key))
    }

    @Test
    fun deleteIdsNoOpForAbsentKey() = runTest {
        // No exception thrown when deleting a key that never existed.
        tempStore().delete(StoreKey("ghost"))
    }

    @Test
    fun deletePersistedAfterRestart() = runTest {
        val dir = createTempDirectory("kuilt-store-delete").toFile()
        val key = StoreKey("k")
        storeAt(dir).write(key, byteArrayOf(1))
        storeAt(dir).delete(key)

        assertNull(FileChannelDurableStore(dir).read(key))
    }

    // ---- multiple keys are independent ----

    @Test
    fun multipleKeysAreIndependent() = runTest {
        val dir = createTempDirectory("kuilt-store-multi").toFile()
        val k1 = StoreKey("a")
        val k2 = StoreKey("b")
        storeAt(dir).write(k1, byteArrayOf(1))
        storeAt(dir).write(k2, byteArrayOf(2))

        val store = FileChannelDurableStore(dir)
        assertContentEquals(byteArrayOf(1), store.read(k1))
        assertContentEquals(byteArrayOf(2), store.read(k2))
    }

    // ---- a key is stored losslessly: distinct keys are distinct entries (#2506) ----

    @Test
    fun keysWithSimilarNamesDontCollide() = runTest {
        val dir = createTempDirectory("kuilt-store-similar").toFile()
        val k1 = StoreKey("otel.spans")
        val k2 = StoreKey("otel.metrics")
        storeAt(dir).write(k1, byteArrayOf(10))
        storeAt(dir).write(k2, byteArrayOf(20))

        val store = FileChannelDurableStore(dir)
        assertContentEquals(byteArrayOf(10), store.read(k1))
        assertContentEquals(byteArrayOf(20), store.read(k2))
    }

    /**
     * Five distinct keys the legacy filename mapping folded onto **one** file.
     *
     * `Regex("[^a-zA-Z0-9_-]") → "_"` sent `a.b`, `a/b`, `a b` and `a:b` all to
     * `a_b` — which is itself a key. So four of the five writes below destroyed a
     * value written under a *different* key, silently, and every read but the last
     * returned another key's bytes. There is no listing surface on [DurableStore]
     * through which a caller could have noticed.
     */
    @Test
    fun keysThatFoldedOntoOneFilenameAddressDistinctEntries() = runTest {
        val dir = createTempDirectory("kuilt-store-fold").toFile()
        val names = listOf("a.b", "a/b", "a b", "a:b", "a_b")
        names.forEachIndexed { index, name ->
            storeAt(dir).write(StoreKey(name), byteArrayOf(index.toByte()))
        }

        val store = FileChannelDurableStore(dir)
        val readBack = names.map { store.read(StoreKey(it)) }
        assertAll(
            *names.mapIndexed { index, name ->
                { assertContentEquals(byteArrayOf(index.toByte()), readBack[index], "key \"$name\"") }
            }.toTypedArray(),
        )
    }

    /**
     * Two keys differing only in a non-ASCII letter.
     *
     * This backend's `[^a-zA-Z0-9_-]` folded every Cyrillic letter to `_`, so `мир`
     * and `миг` both became `___`. The Apple backend's `Char.isLetterOrDigit()` did
     * *not* — two sanitisers that each read as correct, disagreeing on non-ASCII.
     * That divergence is why the encoding is now one shared thing rather than two
     * that must agree by inspection.
     */
    @Test
    fun keysDifferingOnlyInANonAsciiLetterAddressDistinctEntries() = runTest {
        val dir = createTempDirectory("kuilt-store-nonascii").toFile()
        val peace = StoreKey("мир")
        val moment = StoreKey("миг")
        storeAt(dir).write(peace, byteArrayOf(1))
        storeAt(dir).write(moment, byteArrayOf(2))

        val store = FileChannelDurableStore(dir)
        val first = store.read(peace)
        val second = store.read(moment)
        assertAll(
            { assertContentEquals(byteArrayOf(1), first, "key \"мир\"") },
            { assertContentEquals(byteArrayOf(2), second, "key \"миг\"") },
        )
    }

    /**
     * Two keys differing only in case.
     *
     * `StoreKey("a")` and `StoreKey("A")` are distinct keys, and the legacy mapping
     * passed both letters straight through — so on a **case-insensitive**
     * filesystem they shared one file. APFS is case-insensitive by default, as are
     * exFAT and NTFS; ext4 is not, which is exactly why nobody measured this: the
     * defect is invisible on the filesystem most CI runs on.
     *
     * After the fix it holds on every filesystem, because an uppercase letter is
     * escaped and never reaches a filename at all.
     */
    @Test
    fun keysDifferingOnlyInCaseAddressDistinctEntries() = runTest {
        val dir = createTempDirectory("kuilt-store-case").toFile()
        val lower = StoreKey("a")
        val upper = StoreKey("A")
        storeAt(dir).write(lower, byteArrayOf(1))
        storeAt(dir).write(upper, byteArrayOf(2))

        val store = FileChannelDurableStore(dir)
        val first = store.read(lower)
        val second = store.read(upper)
        assertAll(
            { assertContentEquals(byteArrayOf(1), first, "key \"a\"") },
            { assertContentEquals(byteArrayOf(2), second, "key \"A\"") },
        )
    }

    /**
     * A file left behind by the legacy scheme must never be readable as a
     * **different** key.
     *
     * The fix ships no migration, so legacy files stay on disk in the same
     * directory. `otel.logs` was stored as `otel_logs`; a scheme that treated `_` as
     * a safe character would hand a future `StoreKey("otel_logs")` the abandoned
     * buffer of `otel.logs` — silent wrong-key data, strictly worse than the loss
     * orphaning already accepts. Escaping `_` (and `.`, and uppercase) is what makes
     * the two namespaces provably disjoint.
     */
    @Test
    fun aKeyNeverAdoptsAnotherKeysLegacyOrphan() = runTest {
        val dir = createTempDirectory("kuilt-store-orphan").toFile()
        // Exactly the filenames the legacy sanitiser produced for "otel.logs" and "otel.spans".
        java.io.File(dir, "otel_logs").writeBytes(byteArrayOf(11))
        java.io.File(dir, "otel_spans").writeBytes(byteArrayOf(22))

        val store = FileChannelDurableStore(dir)
        val logs = store.read(StoreKey("otel_logs"))
        val spans = store.read(StoreKey("otel_spans"))
        assertAll(
            { assertNull(logs, "StoreKey(\"otel_logs\") must not adopt otel.logs' orphaned file") },
            { assertNull(spans, "StoreKey(\"otel_spans\") must not adopt otel.spans' orphaned file") },
        )
    }

    /**
     * The one case where reading a legacy file *is* correct: the key was already
     * inside the safe set, so both schemes are the identity on it and the "orphan"
     * is that key's own file. `spans` and `span-state` carry over for free.
     */
    @Test
    fun aKeyAlreadyInsideTheSafeSetStillFindsItsOwnFile() = runTest {
        val dir = createTempDirectory("kuilt-store-carryover").toFile()
        java.io.File(dir, "spans").writeBytes(byteArrayOf(33))
        java.io.File(dir, "span-state").writeBytes(byteArrayOf(44))

        val store = FileChannelDurableStore(dir)
        val spans = store.read(StoreKey("spans"))
        val spanState = store.read(StoreKey("span-state"))
        assertAll(
            { assertContentEquals(byteArrayOf(33), spans, "key \"spans\"") },
            { assertContentEquals(byteArrayOf(44), spanState, "key \"span-state\"") },
        )
    }

    /**
     * An entry's filename can never equal another entry's `.tmp` sidecar.
     *
     * [FileChannelDurableStore] writes `<name>.tmp` beside `<name>`, so if a key
     * could encode to a name ending in `.tmp` its entry would sit exactly where
     * another key's in-flight write lands. Escaping `.` closes it: no encoded name
     * contains a dot. The pair below is the smallest witness — `x` owns the sidecar
     * `x.tmp`, and `x.tmp` is a key in its own right.
     */
    @Test
    fun anEntryNeverLandsOnAnotherEntrysTempSidecar() = runTest {
        val dir = createTempDirectory("kuilt-store-sidecar").toFile()
        val plain = StoreKey("x")
        val sidecarShaped = StoreKey("x.tmp")
        storeAt(dir).write(plain, byteArrayOf(1))
        storeAt(dir).write(sidecarShaped, byteArrayOf(2))
        storeAt(dir).write(plain, byteArrayOf(3))

        val store = FileChannelDurableStore(dir)
        val first = store.read(plain)
        val second = store.read(sidecarShaped)
        assertAll(
            { assertContentEquals(byteArrayOf(3), first, "key \"x\"") },
            { assertContentEquals(byteArrayOf(2), second, "key \"x.tmp\" survived x's write") },
        )
    }

    // ---- read returns defensive copy ----

    @Test
    fun readReturnsCopy() = runTest {
        val store = tempStore()
        val key = StoreKey("k")
        store.write(key, byteArrayOf(1, 2, 3))
        val first = requireNotNull(store.read(key)) { "expected non-null bytes after write" }
        first[0] = 99
        assertContentEquals(byteArrayOf(1, 2, 3), store.read(key))
    }

    // ---- large payload ----

    @Test
    fun largePayloadSurvivesRoundTrip() = runTest {
        val dir = createTempDirectory("kuilt-store-large").toFile()
        val key = StoreKey("big")
        val big = ByteArray(512 * 1024) { it.toByte() } // 512 KiB
        storeAt(dir).write(key, big)
        assertContentEquals(big, FileChannelDurableStore(dir).read(key))
    }

    // ---- empty payload ----

    @Test
    fun emptyPayloadRoundTrips() = runTest {
        val store = tempStore()
        val key = StoreKey("empty")
        store.write(key, ByteArray(0))
        val read = store.read(key)
        assertEquals(0, read?.size)
    }
}
