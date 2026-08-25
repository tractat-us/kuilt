package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract every **file-backed** [DurableStore] must satisfy about the filenames it addresses
 * entries by. Subclass and implement [newDirectory], [newStore] and [plantRawFile].
 *
 * ```kotlin
 * class SqliteFileStoreFilenameTest : DurableStoreFilenameConformanceSuite<File>() {
 *     override fun newDirectory(): File = freshTempDir()
 *     override suspend fun newStore(dir: File): DurableStore = SqliteFileStore(dir)
 *     override fun plantRawFile(dir: File, name: String, bytes: ByteArray) =
 *         File(dir, name).writeBytes(bytes)
 * }
 * ```
 *
 * ## Why this is a suite and not two copies of six tests
 *
 * #2506 existed because two independently written filename mappings had to agree by inspection and
 * did not: `Regex("[^a-zA-Z0-9_-]")` on JVM folded every Cyrillic letter onto `_`, while
 * `Char.isLetterOrDigit()` on Apple did not. #2511 fixed the *mappings* by sharing one encoder —
 * and left the *properties that verify them* duplicated by hand, six of them, verbatim, in each
 * file backend's own test file. A third file backend inherits none of those, so its author
 * re-derives the six by hand: the identical structural setup, one level up (#2515).
 *
 * That is the standing question *"after fixing anything, ask what the fix itself is now unpinned
 * on"*, and this suite is the answer for #2511.
 *
 * ## What subclasses this, and what deliberately does not
 *
 * Only a backend that puts a key on a **filesystem**. `InMemoryDurableStore` and
 * `IndexedDbDurableStore` have no filenames, so they do not subclass this at all — which is a
 * different and more honest answer than a nullable [plantRawFile] returning `null` to mean "not
 * applicable". See [plantRawFile] for why that distinction is load-bearing.
 *
 * This suite is **additive** to [DurableStoreConformanceSuite], not a replacement: that one is
 * where `read`/`write`/`delete`/restart live, stated against the interface and therefore runnable
 * by every backend. A file backend runs both, as two test classes.
 *
 * ## The knobs, and what each of them switches off
 *
 * A fixture's configuration is a prescription too, and it drifts toward the setting where the
 * property cannot fail. This suite has no numeric budget to get wrong, but it has four choices
 * that are just as capable of switching a property off, so they are named here rather than left
 * implicit:
 *
 * - **Every entry carries a distinct byte value.** A fold is *invisible* if the two entries it
 *   merges hold the same bytes: the read comes back with what was expected and nothing is wrong.
 *   Every write and every planted file below carries a value nothing else in that test uses, so a
 *   fold names *which* entry it destroyed rather than only that something is off.
 * - **Reads go through a SECOND store handle**, opened on the same directory after the writes. A
 *   backend caching entries in memory would answer every read from the cache, and a fold that
 *   happened on the medium would be invisible to a suite that never left the writing handle. The
 *   in-tree backends hold no such cache; a future one might, and the cost of not relying on that
 *   is one extra [newStore] call.
 * - **The planted names are LEGACY names, not encoded ones.** A name the current encoder can still
 *   produce would be reachable through [DurableStore.write], which makes [plantRawFile] redundant
 *   and the property a restatement of a round trip. Every name planted below is one the encoder
 *   can no longer emit — that is the whole reason a raw-file hook has to exist.
 * - **[newDirectory] is called per property, not per class.** Properties assert absence, and an
 *   absence assertion against a directory a previous property wrote into is reporting on test
 *   ordering. The properties that can cheaply check it assert their own emptiness precondition.
 *
 * ## What this suite cannot detect, and where
 *
 * **Case.** Two of the properties here concern a filesystem that compares names case-insensitively
 * — APFS by default, exFAT, NTFS; **not** ext4, which is what a Linux CI runner almost always
 * uses. Read plainly:
 *
 * - [keysDifferingOnlyInCaseAddressDistinctEntries] is unconditional about the *store's own*
 *   behaviour (a `lowercase()` anywhere on the way to a filename reds it on every filesystem) and
 *   says nothing at all, on a case-sensitive runner, about the *filesystem* half of the same
 *   defect. Undoing the encoder's uppercase escaping reds it only where the filesystem folds.
 * - [theLegacyOverlapACaseFoldingFilesystemExposesIsExactlyTheDocumentedOne] measures which
 *   filesystem it is running on and asserts the documented outcome **for that filesystem**, in
 *   both arms. It deliberately does not skip: a skipped test and a silently-passing one are the
 *   same colour in a green run, and the case-sensitive arm has real content — it asserts the
 *   residual is *absent* there.
 *
 * So a green from a Linux runner is worth strictly less than a green from a macOS one, and the
 * difference is exactly the second failure mode of those two properties. The target-independent
 * statement of the same boundary lives in `:kuilt-store`'s own `StoreKeyFilenameTest`, which
 * decides it from the encoded strings and needs no filesystem at all — but that test is a *model*
 * of a case-folding filesystem, and this suite is the only place the model is checked against a
 * real one.
 *
 * **Containment.** Nothing here establishes that a write landed *inside* the store's directory.
 * [DurableStore] exposes no root and no listing, and [plantRawFile] is a fixture hook rather than
 * an observation of the medium, so a write that escaped and a write that was contained read back
 * identically. Each backend's own tests are where containment is provable.
 *
 * **Durability.** Every read here happens in the same process as the write it follows, so the
 * operating system's page cache satisfies it whether or not the bytes ever reached a device. See
 * [DurableStoreConformanceSuite] for the same residual stated at length.
 *
 * @param DIR whatever this backend calls a directory — `java.io.File` on JVM/Android, a path
 *   `String` on Apple. Opaque to the suite: it is created by [newDirectory] and handed straight
 *   back to [newStore] and [plantRawFile], so no path-string convention is imposed on a backend.
 */
public abstract class DurableStoreFilenameConformanceSuite<DIR> {

    /**
     * A **fresh, empty** directory, sharing no medium with any directory returned by a previous
     * call.
     *
     * Separate from [newStore] rather than folded into it, because every property here needs to
     * put something into the directory — a planted legacy file — *before* a store opens over it.
     * A `newStore()` that created its own directory internally would leave the fixture no moment
     * at which to do that.
     */
    protected abstract fun newDirectory(): DIR

    /**
     * A store of this backend reading and writing entries in [dir], and nowhere else.
     *
     * Called more than once per property with the same [dir], deliberately: the second handle is
     * what keeps an in-memory cache from answering a read that should have gone to the medium.
     *
     * `suspend` for the same reason [DurableStoreConformanceSuite.newStore] is — opening a medium
     * can suspend, and a hook that forbade it would make this suite unimplementable for a backend
     * whose open is asynchronous.
     */
    protected abstract suspend fun newStore(dir: DIR): DurableStore

    /**
     * Write [bytes] to a file literally named [name] directly inside [dir], bypassing the store
     * and its encoder entirely.
     *
     * This exists because the states these properties are about are **unreachable through
     * [DurableStore]**. A legacy filename is by construction one the current encoder can no longer
     * produce, so no sequence of `write` calls creates one — and yet those files are sitting in
     * consumers' directories right now, because #2511 deliberately shipped no migration. Without
     * this hook, "a new key must never adopt a *different* key's orphan" is a sentence no test in
     * the tree can reach.
     *
     * ## Why this is not nullable
     *
     * An `plantRawFile(...): Boolean` or a nullable variant, meaning "this backend cannot plant a
     * raw file", would move the vacuity one level up, where it is harder to see. `null` reads as
     * *"not applicable to me"*, so a backend that simply had not implemented planting would opt
     * out and this suite would report nothing — the same silence, now wearing a declaration.
     *
     * The honest answer for a backend with no filenames is **not to subclass this suite at all**,
     * and that answer is visible: `InMemoryDurableStore` and `IndexedDbDurableStore` appear in
     * [DurableStoreConformanceSuite]'s subclasses and not in this one, which a reader can check.
     * A `null` hook is invisible in exactly the same run.
     *
     * Every property that depends on planting **asserts that the plant landed**, through a control
     * file the store is required to find, so a subclass whose hook writes to the wrong directory —
     * or does nothing — fails loudly rather than passing quietly on a directory full of nothing.
     *
     * @param name the *raw* filename, used verbatim. The suite passes names the encoder cannot
     *   emit; a subclass must not sanitise, escape or otherwise reinterpret them.
     */
    protected abstract fun plantRawFile(dir: DIR, name: String, bytes: ByteArray)

    // ── A key is stored losslessly: distinct keys are distinct entries (#2506) ───────────────

    /**
     * Five distinct keys the legacy filename mappings folded onto **one** file.
     *
     * `Regex("[^a-zA-Z0-9_-]") → "_"` (JVM) and `Char.isLetterOrDigit()` (Apple) both sent `a.b`,
     * `a/b`, `a b` and `a:b` to `a_b` — which is itself a key. So four of the five writes below
     * destroyed a value written under a *different* key, silently, and every read but the last
     * returned another key's bytes. There is no listing surface on [DurableStore] through which a
     * caller could have noticed.
     */
    @Test
    public fun keysThatFoldedOntoOneFilenameAddressDistinctEntries(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val dir = newDirectory()
            val names = listOf("a.b", "a/b", "a b", "a:b", "a_b")
            val writer = newStore(dir)
            val beforeAnything = writer.read(StoreKey(names.first()))
            names.forEachIndexed { index, name -> writer.write(StoreKey(name), byteArrayOf(index.toByte())) }

            val reader = newStore(dir)
            val readBack = names.map { reader.read(StoreKey(it)) }
            assertAll(
                { assertFreshDirectory(beforeAnything, names.first()) },
                *names.mapIndexed { index, name ->
                    { assertContentEquals(byteArrayOf(index.toByte()), readBack[index], "key \"$name\"") }
                }.toTypedArray(),
            )
        }

    /**
     * Two keys differing only in a non-ASCII letter.
     *
     * This is the pair the two legacy sanitisers **disagreed** on, which is why the encoding is now
     * one shared thing rather than two that must agree by inspection: `[^a-zA-Z0-9_-]` folded `мир`
     * and `миг` both to `___`, while `isLetterOrDigit()` — true for Cyrillic — kept them apart. A
     * key that survived on Apple collided on JVM, and each backend's own tests were green.
     *
     * Keys derived from anything a person typed reach this.
     */
    @Test
    public fun keysDifferingOnlyInANonAsciiLetterAddressDistinctEntries(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val dir = newDirectory()
            val peace = StoreKey("мир")
            val moment = StoreKey("миг")
            val writer = newStore(dir)
            val beforeAnything = writer.read(peace)
            writer.write(peace, byteArrayOf(1))
            writer.write(moment, byteArrayOf(2))

            val reader = newStore(dir)
            val first = reader.read(peace)
            val second = reader.read(moment)
            assertAll(
                { assertFreshDirectory(beforeAnything, "мир") },
                { assertContentEquals(byteArrayOf(1), first, "key \"мир\"") },
                { assertContentEquals(byteArrayOf(2), second, "key \"миг\"") },
            )
        }

    /**
     * Two keys differing only in case.
     *
     * `StoreKey("a")` and `StoreKey("A")` are distinct keys, and every legacy mapping in the tree
     * passed both letters straight through — so on a **case-insensitive** filesystem they shared
     * one file. APFS is case-insensitive by default, as are exFAT and NTFS; ext4 is not, which is
     * exactly why nobody measured this: the defect is invisible on the filesystem most CI runs on.
     *
     * After the fix it holds on every filesystem, because an uppercase letter is escaped and never
     * reaches a filename at all.
     *
     * **What a green here proves depends on where it ran**, and the difference is not small. There
     * are two ways to fail this property and they are pinned unequally:
     *
     * - a store that folds case **itself** — a `lowercase()` on the way to a filename — reds this
     *   on every target and every filesystem. That half is unconditional;
     * - a store that hands both keys to a **case-folding filesystem** reds only where the
     *   filesystem folds. Undoing the encoder's uppercase escaping is exactly that mutation, and
     *   it moves nothing at all in this suite on a case-sensitive runner.
     *
     * [theLegacyOverlapACaseFoldingFilesystemExposesIsExactlyTheDocumentedOne] is where the second
     * half is pinned unconditionally, by a route that does not depend on the filesystem.
     */
    @Test
    public fun keysDifferingOnlyInCaseAddressDistinctEntries(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val dir = newDirectory()
            val lower = StoreKey("a")
            val upper = StoreKey("A")
            val writer = newStore(dir)
            val beforeAnything = writer.read(lower)
            writer.write(lower, byteArrayOf(1))
            writer.write(upper, byteArrayOf(2))

            val reader = newStore(dir)
            val first = reader.read(lower)
            val second = reader.read(upper)
            assertAll(
                { assertFreshDirectory(beforeAnything, "a") },
                { assertContentEquals(byteArrayOf(1), first, "key \"a\"") },
                { assertContentEquals(byteArrayOf(2), second, "key \"A\"") },
            )
        }

    // ── Disjointness from the namespaces that share the directory ────────────────────────────

    /**
     * A file left behind by the legacy scheme must never be readable as a **different** key.
     *
     * The fix ships no migration, so legacy files stay on disk in the same directory forever.
     * `otel.logs` was stored as `otel_logs`; a scheme that treated `_` as a safe character would
     * hand a future `StoreKey("otel_logs")` the abandoned buffer of `otel.logs` — silent wrong-key
     * data, strictly worse than the loss orphaning already accepts. Escaping `_` (and `.`, and
     * uppercase) is what makes the two namespaces provably disjoint.
     *
     * **The control file is not decoration.** Every other assertion here is an *absence*, and an
     * absence is exactly what a [plantRawFile] that wrote nowhere would also produce — the whole
     * property would be green on a broken fixture, which is the failure mode a hook like this
     * invites. `spans` is planted alongside, under a name both schemes leave alone, and the store
     * is required to find it: that is the assertion which fails when the plant did not land.
     */
    @Test
    public fun aKeyNeverAdoptsAnotherKeysLegacyOrphan(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val dir = newDirectory()
        // Exactly the filenames the legacy sanitisers produced for "otel.logs" and "otel.spans".
        plantRawFile(dir, "otel_logs", byteArrayOf(11))
        plantRawFile(dir, "otel_spans", byteArrayOf(22))
        // The control: a name both schemes are the identity on, so the store MUST see it.
        plantRawFile(dir, CONTROL_NAME, byteArrayOf(77))

        val store = newStore(dir)
        val logs = store.read(StoreKey("otel_logs"))
        val spans = store.read(StoreKey("otel_spans"))
        val control = store.read(StoreKey(CONTROL_NAME))
        assertAll(
            { assertPlantLanded(control, byteArrayOf(77)) },
            { assertNull(logs, "StoreKey(\"otel_logs\") must not adopt otel.logs' orphaned file") },
            { assertNull(spans, "StoreKey(\"otel_spans\") must not adopt otel.spans' orphaned file") },
        )
    }

    /**
     * The one case where reading a legacy file *is* correct: the key was already inside the safe
     * set, so both schemes are the identity on it and the "orphan" is that key's own file. `spans`
     * and `span-state` carry over for free.
     *
     * This is the complement of [aKeyNeverAdoptsAnotherKeysLegacyOrphan] and it needs no separate
     * plant-landed assertion: presence *is* what it asserts, so a [plantRawFile] that wrote
     * nothing reds it directly.
     *
     * Note these two are **fixtures, not shipped keys**. Every key kuilt itself stores contains a
     * `.`, so every one of them moves and loses its data; the carry-over benefits consumers whose
     * key names happened to be inside `[a-z0-9-]`.
     */
    @Test
    public fun aKeyAlreadyInsideTheSafeSetStillFindsItsOwnFile(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val dir = newDirectory()
            plantRawFile(dir, "spans", byteArrayOf(33))
            plantRawFile(dir, "span-state", byteArrayOf(44))

            val store = newStore(dir)
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
     * Both in-tree file backends write `<name>.tmp` beside `<name>` and then rename, so if a key
     * could encode to a name ending in `.tmp` its entry would sit exactly where another key's
     * in-flight write lands. Escaping `.` closes it: no encoded name contains a dot.
     *
     * The pair below is the smallest witness — `x` owns the sidecar `x.tmp`, and `x.tmp` is a key
     * in its own right — and the **third write is what gives the property teeth**. Two writes only
     * establish that the two keys can coexist; it is `x` being written *again*, after `x.tmp` has
     * a value, that makes an in-flight temp file collide with a live entry. A version of this test
     * without that third write is green against a backend whose sidecar name is a live key's
     * filename.
     *
     * The `.tmp` suffix is this suite's one backend-shaped assumption. A backend staging its
     * writes some other way (a sibling directory, an `O_TMPFILE`) satisfies this property for free
     * — correctly, since it has no sidecar namespace to collide with — and pays one entry for it.
     */
    @Test
    public fun anEntryNeverLandsOnAnotherEntrysTempSidecar(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val dir = newDirectory()
        val plain = StoreKey("x")
        val sidecarShaped = StoreKey("x.tmp")
        val writer = newStore(dir)
        val beforeAnything = writer.read(plain)
        writer.write(plain, byteArrayOf(1))
        writer.write(sidecarShaped, byteArrayOf(2))
        writer.write(plain, byteArrayOf(3))

        val reader = newStore(dir)
        val first = reader.read(plain)
        val second = reader.read(sidecarShaped)
        assertAll(
            { assertFreshDirectory(beforeAnything, "x") },
            { assertContentEquals(byteArrayOf(3), first, "key \"x\"") },
            { assertContentEquals(byteArrayOf(2), second, "key \"x.tmp\" survived x's write") },
        )
    }

    /**
     * The legacy/new disjointness re-asked under the equality a **case-insensitive filesystem**
     * actually uses — measured against a real filesystem rather than modelled.
     *
     * `:kuilt-store`'s `StoreKeyFilenameTest` already asks this question of the encoder, deciding
     * it with `String.equals(ignoreCase = true)`. That is a *model* of a case-folding filesystem,
     * and it is the only place in the tree the residual is stated. This property is where the
     * model meets the medium: same boundary, decided by the filesystem the run is actually sitting
     * on. It is the one filename property that cannot be stated at the encoder level at all.
     *
     * Three things are asserted, and only the last one is filesystem-dependent:
     *
     * 1. **A moved key's orphan is unreachable, in every case variant.** `otel.logs` was stored as
     *    `otel_logs`; no key — `otel_logs`, `OTEL_LOGS`, `Otel_Logs` — may read it, on any
     *    filesystem. This is the *dangerous* shape: silent wrong-key data.
     * 2. **`StoreKey("Config")` does NOT find its own legacy file `Config`.** Uppercase is escaped,
     *    so that key now encodes to `%43onfig` and its old bytes are orphaned — the documented,
     *    accepted cost of shipping no migration. Stated as an assertion because it is the direct
     *    filesystem-level witness of uppercase escaping: putting `A`–`Z` back into the safe set
     *    reds this on **every** filesystem, which is precisely what
     *    [keysDifferingOnlyInCaseAddressDistinctEntries] cannot do on a case-sensitive runner.
     * 3. **The residual itself.** A consumer that once stored `StoreKey("Config")` has an orphan at
     *    `Config`, and a new, never-written `StoreKey("config")` encodes to `config` — which on
     *    APFS *is* that file. That overlap is real, documented, and deliberately not closed
     *    (closing it would mean forcing an escape into every encoded name, forfeiting the safe-set
     *    carry-over [aKeyAlreadyInsideTheSafeSetStillFindsItsOwnFile] describes).
     *
     * ## Why it measures rather than skips
     *
     * The third assertion has a different expected value on a case-folding filesystem than on a
     * case-sensitive one. **Skipping on ext4 would have been the wrong answer**: a skipped test and
     * a silently-passing one are the same colour in a green run, and the case-sensitive arm has
     * real content of its own — it asserts the residual is *absent* there, which reds if a change
     * ever made an encoded name collide with a legacy one outright.
     *
     * So the suite measures which filesystem it is on, using a probe pair of names **not otherwise
     * used by this property**: `casefold-probe` planted first, then `CASEFOLD-PROBE`. On a folding
     * filesystem the second plant lands on the first file and the store reads the second value; on
     * a case-sensitive one it reads the first. Deriving the branch from an independent pair is what
     * keeps the third assertion from restating its own instrument — a branch computed from
     * `config`'s own read would be true by construction whatever the filesystem did.
     *
     * The probe doubles as this property's plant-landed precondition: a [plantRawFile] that wrote
     * nowhere makes the probe read `null`, which is neither planted value and fails before any
     * conclusion is drawn from it.
     */
    @Test
    public fun theLegacyOverlapACaseFoldingFilesystemExposesIsExactlyTheDocumentedOne(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val dir = newDirectory()
            // The probe: two names differing only in case, planted lower-then-upper. Not used
            // anywhere else in this property, so the branch it decides is an independent
            // measurement of the filesystem rather than a restatement of the subject's own read.
            plantRawFile(dir, "casefold-probe", byteArrayOf(CASE_SENSITIVE_MARK))
            plantRawFile(dir, "CASEFOLD-PROBE", byteArrayOf(CASE_FOLDING_MARK))
            // A different key's legacy orphan, under a name the legacy schemes left alone.
            plantRawFile(dir, "Config", byteArrayOf(55))
            // A moved key's legacy orphan: "otel.logs" was stored here.
            plantRawFile(dir, "otel_logs", byteArrayOf(66))

            val store = newStore(dir)
            val probe = store.read(StoreKey("casefold-probe"))
            val movedVariants = listOf("otel_logs", "OTEL_LOGS", "Otel_Logs")
            val movedReads = movedVariants.map { store.read(StoreKey(it)) }
            val atUppercaseKey = store.read(StoreKey("Config"))
            val atLowercaseKey = store.read(StoreKey("config"))

            val caseFolding = probe.contentEquals(byteArrayOf(CASE_FOLDING_MARK))
            assertAll(
                {
                    assertTrue(
                        caseFolding || probe.contentEquals(byteArrayOf(CASE_SENSITIVE_MARK)),
                        "PRECONDITION: the case-folding probe read back ${probe?.firstOrNull()}, which is " +
                            "neither planted value — plantRawFile must write the name it is given, verbatim, " +
                            "into the directory newStore reads from",
                    )
                },
                *movedVariants.mapIndexed { index, name ->
                    {
                        assertNull(
                            movedReads[index],
                            "StoreKey(\"$name\") must not adopt otel.logs' orphaned file, on any filesystem",
                        )
                    }
                }.toTypedArray(),
                {
                    assertNull(
                        atUppercaseKey,
                        "StoreKey(\"Config\") encodes to an escaped name, so its own legacy file is orphaned " +
                            "rather than adopted — a safe set containing A-Z would find it here",
                    )
                },
                {
                    if (caseFolding) {
                        assertContentEquals(
                            byteArrayOf(55), atLowercaseKey,
                            "on a CASE-FOLDING filesystem StoreKey(\"config\") reads StoreKey(\"Config\")'s " +
                                "orphan — the documented residual overlap, deliberately not closed",
                        )
                    } else {
                        assertNull(
                            atLowercaseKey,
                            "on a CASE-SENSITIVE filesystem there is no overlap at all: StoreKey(\"config\") " +
                                "must not reach a file named \"Config\"",
                        )
                    }
                },
            )
        }

    // ── Shared precondition assertions ───────────────────────────────────────────────────────

    /**
     * [newDirectory] handed back somewhere a previous property had already written, so every
     * absence assertion that follows would be reporting on test ordering.
     */
    private fun assertFreshDirectory(read: ByteArray?, name: String) {
        assertNull(
            read,
            "PRECONDITION: newDirectory() must return a FRESH directory, but \"$name\" already had a value " +
                "in it — every absence assertion in this suite is a claim about an empty directory",
        )
    }

    /**
     * [plantRawFile] wrote nothing the store can see, which would leave every absence assertion in
     * the surrounding property green on a fixture that did nothing at all.
     */
    private fun assertPlantLanded(control: ByteArray?, expected: ByteArray) {
        assertContentEquals(
            expected, assertNotNull(control, "PRECONDITION: the control file plantRawFile wrote is not readable"),
            "PRECONDITION: plantRawFile must write the bytes it is given, under the name it is given, into " +
                "the directory newStore reads from — the absence assertions below are vacuous otherwise",
        )
    }
}

/**
 * A name both legacy sanitisers and the current encoder leave alone, so a file planted under it is
 * readable through the key of the same name. The control [DurableStoreFilenameConformanceSuite]
 * uses to prove a plant landed.
 */
private const val CONTROL_NAME = "spans"

/** The probe value a **case-sensitive** filesystem leaves readable at `casefold-probe`. */
private const val CASE_SENSITIVE_MARK: Byte = 101

/** The probe value a **case-folding** filesystem leaves readable at `casefold-probe`. */
private const val CASE_FOLDING_MARK: Byte = 102
