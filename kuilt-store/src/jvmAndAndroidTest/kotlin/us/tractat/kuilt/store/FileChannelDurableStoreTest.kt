package us.tractat.kuilt.store

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.conformance.DurableStoreConformanceSuite
import us.tractat.kuilt.conformance.RestartFixture
import us.tractat.kuilt.test.assertAll
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * Verifies [FileChannelDurableStore] satisfies the whole [DurableStoreConformanceSuite], **on both
 * the JVM and Android**, and keeps the filename-level tests the suite cannot reach.
 *
 * In `jvmAndAndroidTest`, not `jvmTest`, because `FileChannelDurableStore` lives in
 * `jvmAndAndroidMain` and Android is the target an app actually depends on for durable storage.
 * A subclass in `jvmTest` would leave the Android variant compiled and never run — the suite would
 * be green on a target it had never executed against.
 *
 * Every test this class used to hold by hand — absent key, round trip, overwrite, delete, the
 * defensive copy, the large and empty payloads, independent keys, and the "crash" simulated by a
 * second instance over the same directory — is now a property of the shared contract, checked here
 * and on the other three backends alike. Nothing was dropped; the suite states each of them at least
 * as strongly, and several considerably more so (an overwrite that *shrinks*, a key set built to
 * collide, a restart that also has to remember a delete).
 *
 * What stays is what is genuinely about *this backend's filenames* (#2506): the legacy fold, the
 * orphaned legacy files the fix deliberately does not migrate, the `.tmp` sidecar namespace, and the
 * case-insensitive filesystem. None of them are expressible against [DurableStore] — the contract
 * exposes no medium — so they stay here, where the medium is reachable.
 */
class FileChannelDurableStoreTest : DurableStoreConformanceSuite() {

    /**
     * The directory each store was given, so [restart] can reopen it.
     *
     * Keyed on the store instance rather than tracked in a single field because the suite's
     * [twoFreshStoresDoNotShareState] holds two live stores at once, and a single field would hand
     * the second store's directory back for a restart of the first.
     */
    private val directories = mutableMapOf<DurableStore, File>()

    override suspend fun newStore(): DurableStore {
        val dir = freshTempDir("kuilt-store-conformance")
        return FileChannelDurableStore(dir).also { directories[it] = dir }
    }

    /**
     * A restart, modelled the way this store's own contract defines one: a **brand-new instance over
     * the same directory**. It shares no in-memory state with the original, so everything it answers
     * came off the disk the original wrote to.
     */
    override suspend fun restart(store: DurableStore): RestartFixture =
        RestartFixture.Durable(
            FileChannelDurableStore(
                requireNotNull(directories[store]) { "restart() was handed a store this fixture did not create" },
            ),
        )

    private fun storeAt(dir: java.io.File): FileChannelDurableStore =
        FileChannelDurableStore(dir)

    // ---- a key is stored losslessly: distinct keys are distinct entries (#2506) ----

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
        val dir = freshTempDir("kuilt-store-fold")
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
        val dir = freshTempDir("kuilt-store-nonascii")
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
        val dir = freshTempDir("kuilt-store-case")
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
        val dir = freshTempDir("kuilt-store-orphan")
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
        val dir = freshTempDir("kuilt-store-carryover")
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
        val dir = freshTempDir("kuilt-store-sidecar")
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
}

/**
 * A directory named for [prefix], under the system temp root, that no other store in this run shares.
 *
 * `java.io.File`, not `kotlin.io.path.createTempDirectory`: this source set compiles for Android at
 * `minSdk 24` and `java.nio.file.Files.createTempDirectory` is API 26. The unit-test variant runs on
 * the host JVM so it would work in practice, but a test that only compiles by accident of where it
 * runs is not a thing to leave lying in a source set whose whole point is that it targets both.
 */
private fun freshTempDir(prefix: String): File {
    val stem = File.createTempFile(prefix, "")
    check(stem.delete()) { "could not clear the placeholder temp file at $stem" }
    check(stem.mkdirs()) { "could not create the temp directory at $stem" }
    return stem
}
