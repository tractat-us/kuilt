package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/**
 * Reusable contract test suite for [DurableStore] implementations.
 *
 * Subclass and implement [newStore] and [restart] to bind any store under test. Every [Test] in this
 * class encodes a required invariant of the [DurableStore] contract — a conforming implementation
 * must pass all of them.
 *
 * Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`) so every backend can
 * subclass it from its own test source set, on whichever targets that backend exists for.
 *
 * ```kotlin
 * class SqliteDurableStoreConformanceTest : DurableStoreConformanceSuite() {
 *     private val files = mutableMapOf<DurableStore, File>()
 *     override suspend fun newStore(): DurableStore =
 *         SqliteDurableStore(tempFile()).also { files[it] = it.file }
 *     override suspend fun restart(store: DurableStore): RestartFixture =
 *         RestartFixture.Durable(SqliteDurableStore(requireNotNull(files[store])))
 * }
 * ```
 *
 * ## Why this exists
 *
 * kuilt ships **four** independently written [DurableStore] backends — `InMemoryDurableStore` in
 * `commonMain`, `FileChannelDurableStore` on JVM/Android, `NSFileManagerDurableStore` on Apple,
 * `IndexedDbDurableStore` on wasmJs — behind one three-method interface, and until this suite each
 * was verified only by its own hand-written test file. That is the shape #2240 produced the identical
 * cross-segment defect through twice, in a module where two workers never saw each other's code: a
 * property nobody writes for the shared contract is a property every backend has to invent alone, and
 * most of them invent a different one.
 *
 * ## What this suite deliberately does not require
 *
 * - **Iteration, enumeration or transactions across keys.** The contract has three methods and no
 *   listing surface, so nothing here may depend on one.
 * - **Any particular durability *mechanism*.** `fsync`-then-rename, an IndexedDB transaction reaching
 *   `complete`, an `msync` — the suite asks only that a value written before a restart is readable
 *   after it, through [restart], and a backend that promises no such thing declares
 *   [RestartFixture.KeepsNothing] rather than opting out.
 * - **Concurrency.** Every property here is sequential. A backend's thread-safety obligations are
 *   real (`InMemoryDurableStore` and `FileChannelDurableStore` both take locks for them) but they are
 *   not reachable from a suite that runs under one test dispatcher, so they stay each backend's own
 *   tests' business rather than being half-tested here.
 * - **Key length past [LONG_KEY_CHARS].** See [aLongKeyIsStillAKey] for the bound and the reasoning.
 *
 * ## Mutation receipts
 *
 * Measured over `:kuilt-store:jvmTest` — 53 tests: `InMemoryDurableStoreConformanceTest` (this
 * suite's 16), `FileChannelDurableStoreTest` (those 16 again, plus 6 filename tests of its own),
 * `StoreKeyFilenameTest` (14, the shared encoder's own guards) and `StoreSamplesRunTest` — with
 * `--no-build-cache --rerun-tasks`, the results XML deleted before every run and the log grepped for
 * compile errors, because a mutation that does not compile leaves Gradle serving the previous run's
 * XML and fabricates a plausible copy of the row above it. Each mutation applied alone, reverted, the
 * revert verified with `git status`.
 *
 * **The baseline is all-green — and it was not when this suite was written.** On its first run
 * `distinctKeysAddressDistinctEntries` failed on `FileChannelDurableStore` (5 of its assertions) and
 * on `NSFileManagerDurableStore` (4 of them, a *different* 4): two independently written sanitisers,
 * each folding a different set of distinct keys onto one file, and neither backend's own tests
 * noticing. That was this suite doing the thing it exists for, on the day it landed. #2511 is the
 * fix — one shared lossless encoder, `encodeStoreKeyName`, with no migration — so the exclusion that
 * used to sit here has been retired rather than carried: every "reds" entry below is now measured
 * against a genuinely green baseline, with nothing held out of it, and a row naming a red is naming a
 * red its own mutation caused.
 *
 * Retiring an exclusion re-asserts every row that was measured under it, so the **four file-backend
 * rows were re-measured** rather than inherited — the ones whose "and nothing else" had been stated
 * while a failure on that very backend was being held out. Three came back unchanged. The fourth had
 * drifted: `append rather than atomically replace` now reds a third property, because #2511 added the
 * test that reaches it. The five `InMemoryDurableStore` rows and the six fixture rows are untouched
 * by any of this — the excluded failure was never on their backend, and a mutation to one class
 * cannot red another.
 *
 * | Mutation | Reds, at assertion granularity |
 * |---|---|
 * | **`InMemoryDurableStore.write`:** keep the caller's array | [theStoreDoesNotAliasTheArrayItWasGiven], its one assertion — and nothing else |
 * | **`InMemoryDurableStore.read`:** hand back the stored array | [theStoreDoesNotAliasTheArrayItHandsBack], its one assertion — and nothing else |
 * | **`InMemoryDurableStore.delete`:** no-op | [deleteMakesTheKeyAbsentAndWritableAgain] assertion 1; assertion 2 unreached |
 * | **`InMemoryDurableStore.read`:** absence decodes to `ByteArray(0)` | six properties — [readOfANeverWrittenKeyIsNull]; [deleteOfAnAbsentKeyIsANoOp] a1; [twoFreshStoresDoNotShareState] a1; [deleteMakesTheKeyAbsentAndWritableAgain] a1; both `KeepsNothing` arms of [whatWasWrittenBeforeARestartIsReadableAfterIt]; a2 and a3 of [whatWasDeletedBeforeARestartIsStillAbsentAfterIt]. **Not** [anEmptyValueIsAValueAndNotAnAbsence], which asserts the other direction |
 * | **`InMemoryDurableStore.write`:** mask the high bit off every byte | [everyByteValueSurvivesTheRoundTrip] a2, first mismatch at **index 128**; [aLargeValueRoundTripsWhole] a2. **[writtenBytesComeBackExactly] stays green** — every byte in its payload is below `0x80`, which is why those two are not one property |
 * | **`FileChannelDurableStore.read`:** truncate at 8 KiB | [aLargeValueRoundTripsWhole], both assertions (262144 against 8192) — and nothing else |
 * | **`FileChannelDurableStore.read`:** a zero-length file decodes to `null` | [anEmptyValueIsAValueAndNotAnAbsence], both assertions — and nothing else |
 * | **`FileChannelDurableStore.write`:** append rather than atomically replace | [aSecondWriteReplacesTheFirstWhole] all three; [whatWasWrittenBeforeARestartIsReadableAfterIt] the **`overwritten`** assertion only, not `kept`; and `FileChannelDurableStoreTest.anEntryNeverLandsOnAnotherEntrysTempSidecar` on its `"x"` assertion (`[1, 3]` where `[3]` was written). That third red is **new since this row was first measured** — #2511 added the test that reaches it — which is why the row states its reds rather than claiming "and nothing else" |
 * | **`StoreKey.filename`:** truncate the encoded name to 64 characters | [aLongKeyIsStillAKey] a1 (expected `1`, got `2`) — and nothing else. The one-sided shape is the point: both keys encode to the same 64-character prefix, so the second write lands on the first's entry and only the *first* key's read is wrong. a2 reads what it wrote and stays green |
 * | **`StoreKey.filename`:** `lowercase()` the key name before encoding | [distinctKeysAddressDistinctEntries] the `"a-b"` assertion only (expected `4`, got `5` — `"a-B"` landed on it), plus `FileChannelDurableStoreTest.keysDifferingOnlyInCaseAddressDistinctEntries`. This is the case pair's receipt, and it reds **on every filesystem**, because the fold is the store's own |
 * | **`encodeStoreKeyName`'s safe set:** put `A`–`Z` back in it (undo #2511's uppercase escaping) | the same `"a-b"` assertion — **but only because the measuring box's temp root is APFS.** `a-b` and `a-B` become two distinct *filenames* that a case-folding filesystem makes one *file*; on a case-sensitive one this mutation reds nothing in this suite at all. It does red four of `StoreKeyFilenameTest`'s encoder guards, which is where that boundary is pinned target-independently, and is why the suite is not the place to rely on it |
 * | **`FileChannelDurableStore`:** drop `FileChannel.force(true)` | **nothing.** See the residual below |
 * | **Fixture:** `restart` returns the store it was given | both restart properties, on the [assertNotSame] precondition — no durability assertion is reached |
 * | **Fixture:** `restart` opens an empty directory | [whatWasWrittenBeforeARestartIsReadableAfterIt] both `Durable` assertions; [whatWasDeletedBeforeARestartIsStillAbsentAfterIt] the **sibling** assertion only |
 * | **Fixture:** a durable backend declares [RestartFixture.KeepsNothing] | both `KeepsNothing` assertions of the first restart property, and the `KeepsNothing` assertion of the second |
 * | **Fixture:** the [RestartFixture.KeepsNothing] arm returns the store it was given | both restart properties on [assertNotSame] **and** on their `KeepsNothing` assertions |
 * | **Fixture:** a non-durable backend declares [RestartFixture.Durable] | the mirror — both `Durable` assertions of the first, the sibling assertion of the second |
 * | **Fixture:** `newStore` hands back one shared store | [twoFreshStoresDoNotShareState], both assertions — **and nothing else in the suite** |
 *
 * **The row that reds nothing is the important one, and it is this suite's largest residual.**
 * Dropping the `fsync` moves no assertion at all. A restart modelled inside one process reopens
 * through the operating system's page cache, so the bytes are readable whether or not they ever
 * reached the device — and every [restart] in this tree has that shape. So what this suite actually
 * establishes is that a write reached **the medium's namespace**, not that it reached **stable
 * storage**. Telling those apart needs a real process kill or a fault-injecting filesystem, neither
 * of which a `commonMain` suite can have. Said plainly so nobody reads a green here as a durability
 * proof: `NSFileManagerDurableStore` does not force before its rename at all (#2141), documents that,
 * and passes every property below.
 *
 * **What the fixture rows are and are not.** The six fixture rows mutate a subclass in
 * `:kuilt-store`'s own tests, which nothing else references, so their "and nothing else" is
 * *structural* — no other test could see them. The twelve production rows mutate code that also backs
 * `:kuilt-otel` and everything downstream of it, and were measured **only** within
 * `:kuilt-store:jvmTest`; their true blast radius is larger than the rows say, not smaller. The three
 * filename rows understate it by a whole backend: `StoreKey.filename` and `encodeStoreKeyName` are
 * `commonMain`, so `NSFileManagerDurableStore` is mutated too and none of its tests are in the
 * measured run.
 *
 * **What the suite itself now rests on**, since a fix is only as good as what nothing checks: the
 * fixture, in exactly two places, and both are checked rather than assumed. [newStore] really
 * returning independent stores — the last row is the *only* thing in the suite that notices a shared
 * one — and [restart] really crossing a handle boundary, which [assertNotSame] and the two wrong-arm
 * rows cover. What stays unpinned is a [restart] that hands back a thin delegating wrapper, and the
 * page-cache residual above.
 *
 * And what the newest addition rests on: the case pair in [distinctKeysAddressDistinctEntries] has
 * **one** of its two failure modes pinned unconditionally and the other pinned only by the filesystem
 * the run happens to sit on — the third row above is that dependency, measured rather than asserted.
 * So a green here on a case-sensitive runner is worth exactly the first mode and nothing more, and a
 * reader who wants the second must look at `StoreKeyFilenameTest`, which decides it from the encoded
 * strings and needs no filesystem at all.
 */
public abstract class DurableStoreConformanceSuite {

    /**
     * Returns a **fresh, empty** store, sharing no medium with any store returned by a previous call.
     *
     * `suspend` because opening the medium can be — `IndexedDbDurableStore.open` is, and a hook that
     * forced a browser backend to block would have made this suite unimplementable there.
     *
     * The freshness half of that promise is not left to convention: [twoFreshStoresDoNotShareState]
     * checks it. Without that check the whole suite rests on a fixture nobody looks at — a subclass
     * handing back one shared store would let an earlier test's write satisfy a later test's read, and
     * every absence assertion in this file would be reporting on state some other test happened to
     * leave behind.
     */
    protected abstract suspend fun newStore(): DurableStore

    /**
     * Restart [store]: a **new handle onto the same medium** it wrote to, or an honest declaration
     * that this backend has no such medium.
     *
     * Reopening the file, reopening the IndexedDB database, re-running the decoder — whatever "come
     * back after the process exited" means for this backend, performed deterministically.
     *
     * ## Why this is two-armed and not nullable
     *
     * [DurableStore]'s reason to exist is the sentence *"[DurableStore.write] returns only after the
     * bytes are durably committed"*, and no property in this suite outside [RestartFixture.Durable]
     * obtains a second handle onto anything: every other read is off the live object that just took
     * the write. A backend that buffers writes in a `HashMap` and flushes on some `close()` that never
     * runs is green on all of them.
     *
     * But `InMemoryDurableStore` **cannot** survive a process exit, and that is correct rather than a
     * gap — its own KDoc says so. A `restart(): DurableStore?` returning `null` would express that,
     * and would move the vacuity one level up where it is harder to see: `null` reads as "this
     * backend opts out", so a durable backend whose reopen was broken could opt out too and the suite
     * would report nothing.
     *
     * A sealed pair makes the two cases a **decision the subclass states once**, and makes each case
     * checkable:
     *
     * - [RestartFixture.Durable] — the restarted handle must report what was written before it. A
     *   backend that lost it fails.
     * - [RestartFixture.KeepsNothing] — the restarted handle must report **nothing**. A backend that
     *   declared this arm while actually persisting fails just as loudly, because the bytes come back
     *   and the arm asserts they do not.
     *
     * So neither arm is a way out. The wrong arm is a red, in both directions.
     *
     * ## What this cannot detect
     *
     * That the handle handed back is a genuine second view of the medium. Both arms assert it is a
     * **different object** ([assertNotSame]), so a `restart` that returns its argument fails — but a
     * thin wrapper delegating every call to the original is indistinguishable from a real reopen
     * through this contract, because nothing on [DurableStore] exposes the medium. That residual is
     * narrow, it takes deliberate effort to write, and closing it would mean putting a `medium()`
     * accessor on a three-method interface for a test's benefit.
     *
     * On the [RestartFixture.KeepsNothing] arm the residual is wider and worth saying outright: that
     * arm proves only that a fresh handle starts empty. It proves **nothing about durability**,
     * because there is no medium for a write to fail to reach. Everything the durability half of this
     * contract is for is unreachable there by construction, which is the whole reason the obligation
     * belongs in a TCK the durable backends also run rather than in one backend's own tests.
     */
    protected abstract suspend fun restart(store: DurableStore): RestartFixture

    // ── Presence and absence ─────────────────────────────────────────────────

    /**
     * The one thing a caller does at startup: ask for state the previous session may never have
     * written. Absence is `null`, not an empty array and not a throw.
     *
     * A backend that answered `ByteArray(0)` here would be indistinguishable from one holding a
     * genuinely empty value, which [anEmptyValueIsAValueAndNotAnAbsence] is the other half of.
     */
    @Test
    public fun readOfANeverWrittenKeyIsNull(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        assertNull(
            newStore().read(StoreKey("never-written")),
            "a key that was never written must read as absent",
        )
    }

    /**
     * [DurableStore.delete]'s contract is *"No-op if the key is absent"* — so deleting a key nothing
     * ever wrote must return normally, and must leave the store as it found it.
     *
     * The second assertion is the one with teeth: a backend whose delete writes a tombstone, or
     * creates the entry in order to remove it, passes "did not throw" and fails this.
     */
    @Test
    public fun deleteOfAnAbsentKeyIsANoOp(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val ghost = StoreKey("never-written")
        val neighbour = StoreKey("neighbour")
        store.write(neighbour, byteArrayOf(1, 2, 3))

        store.delete(ghost)

        val ghostAfter = store.read(ghost)
        val neighbourAfter = store.read(neighbour)
        assertAll(
            { assertNull(ghostAfter, "deleting an absent key must not bring it into existence") },
            { assertContentEquals(byteArrayOf(1, 2, 3), neighbourAfter, "and must not disturb anything else") },
        )
    }

    /**
     * The fixture precondition the rest of this file rests on: [newStore] hands back a store that
     * shares no medium with the last one.
     *
     * Every absence assertion in this suite — [readOfANeverWrittenKeyIsNull],
     * [deleteOfAnAbsentKeyIsANoOp], the deleted-key half of [deleteMakesTheKeyAbsentAndWritableAgain]
     * — is a claim about a *fresh* store. Against a subclass returning one shared instance those
     * assertions would be reporting on whatever an earlier test left behind, and since tests run in
     * an unspecified order the suite would be green or red by accident. This is the property that
     * makes the fixture prove itself rather than being taken on trust.
     *
     * Deliberately **not** implied by [restart]: that hook exists to return a store that *does* share
     * the medium, so the two hooks are asserting opposite things and neither substitutes for the
     * other.
     */
    @Test
    public fun twoFreshStoresDoNotShareState(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val key = StoreKey("shared")
        val first = newStore()
        first.write(key, byteArrayOf(1, 2, 3))
        val second = newStore()

        val seenBySecond = second.read(key)
        second.write(key, byteArrayOf(9))
        val stillSeenByFirst = first.read(key)

        assertAll(
            { assertNull(seenBySecond, "a fresh store must not see what a previous fresh store wrote") },
            {
                assertContentEquals(
                    byteArrayOf(1, 2, 3), stillSeenByFirst,
                    "and writing through the second must not reach back into the first",
                )
            },
        )
    }

    // ── Round trip ───────────────────────────────────────────────────────────

    /**
     * The write/read round trip, plus the fact that a [StoreKey] addresses by its **name** and not by
     * object identity.
     *
     * The second assertion looks like a formality and is not: `StoreKey` is a `value class`, so on
     * some targets it is erased to a bare `String` and on others it is boxed — a backend keying a map
     * on the wrapper rather than on [StoreKey.name] can work on one platform and fail on another,
     * which is precisely the kind of difference four independently-written backends produce.
     */
    @Test
    public fun writtenBytesComeBackExactly(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val bytes = byteArrayOf(4, 8, 15, 16, 23, 42)
        store.write(StoreKey("k"), bytes)

        val sameKey = store.read(StoreKey("k"))
        assertAll(
            { assertContentEquals(bytes, sameKey, "the bytes written must come back unchanged") },
            {
                assertNotNull(
                    sameKey,
                    "a DIFFERENT StoreKey instance with the same name is the SAME key — the store must " +
                        "address by name, not by the wrapper's identity",
                )
            },
        )
    }

    /**
     * Values are opaque **binary**, so all 256 byte values must survive — the high half especially.
     *
     * The knob here is the byte range, and narrowing it switches this property off entirely: a
     * payload of `1, 2, 3` is what the pre-existing per-backend tests mostly used, and every byte in
     * it is below `0x80`. A backend crossing a boundary that widens a byte — a JS `Uint8Array` read
     * back without sign extension, a text encoding applied to what is not text, a `Char`-based
     * conversion — round-trips `1, 2, 3` perfectly and mangles everything from `0x80` up.
     */
    @Test
    public fun everyByteValueSurvivesTheRoundTrip(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val everyValue = ByteArray(BYTE_VALUES) { it.toByte() }
        store.write(StoreKey("full-range"), everyValue)

        val read = store.read(StoreKey("full-range"))
        assertAll(
            { assertEquals(BYTE_VALUES, read?.size, "all $BYTE_VALUES bytes must come back") },
            {
                assertContentEquals(
                    everyValue, read,
                    "every byte value must survive, including the whole high half above 0x7F",
                )
            },
        )
    }

    /**
     * An empty value is a **value**. Writing one and reading it back must give an empty array, not
     * `null` — the other half of [readOfANeverWrittenKeyIsNull].
     *
     * This is the one place the two states are told apart, and the backends most likely to conflate
     * them are the file-backed ones, where "the key is present" is spelled as a file existing and a
     * zero-length file is easy to read as nothing at all. A caller that stores an empty buffer and
     * gets `null` back re-runs whatever expensive recovery the absent case triggers, every start-up,
     * forever.
     */
    @Test
    public fun anEmptyValueIsAValueAndNotAnAbsence(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        store.write(StoreKey("empty"), ByteArray(0))

        val read = store.read(StoreKey("empty"))
        assertAll(
            { assertNotNull(read, "an empty value that was WRITTEN is present, not absent") },
            { assertEquals(0, read?.size, "and it is empty") },
        )
    }

    /**
     * A value far larger than any single buffer on the write or read path, with **position-dependent**
     * content.
     *
     * Both knobs here select whether this property can fail at all:
     *
     * - **[LARGE_VALUE_BYTES]**, at 256 KiB, is 64 pages at 4 KiB and 16 at 16 KiB (APFS), well past
     *   the JVM's 8 KiB default stream buffer and past any plausible one-shot copy. A "large" payload
     *   of a few KiB sits inside a single page and inside every buffer in the stack, so a truncation
     *   at a boundary is not reachable and the property degenerates into a slower copy of
     *   [writtenBytesComeBackExactly].
     * - **The fill is seeded pseudo-random, not a constant.** `ByteArray(n) { 0xA5 }` is byte-for-byte
     *   equal to a *truncated-then-zero-padded* version of itself only in the tail — but it is equal
     *   to a version with any interior block **duplicated**, or with two blocks swapped, and those are
     *   exactly the defects a chunked write path produces. A seeded fill makes every such rearrangement
     *   visible. Seeded, not `Random.Default`: a test's randomness is a dependency like any other.
     *
     * The size assertion is separate from the content assertion on purpose — a truncation and a
     * corruption are different diagnoses, and `assertContentEquals` alone reports the first mismatched
     * index rather than the length.
     */
    @Test
    public fun aLargeValueRoundTripsWhole(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val big = largeValue()
        store.write(StoreKey("big"), big)

        val read = store.read(StoreKey("big"))
        assertAll(
            { assertEquals(LARGE_VALUE_BYTES, read?.size, "a $LARGE_VALUE_BYTES-byte value must come back whole") },
            { assertContentEquals(big, read, "and unchanged, in order") },
        )
    }

    // ── Overwrite ────────────────────────────────────────────────────────────

    /**
     * [DurableStore.write] *"overwrit[es] any previous value"* — wholesale, not in place.
     *
     * The second value is deliberately **much shorter** than the first, and that is the whole design
     * of this property. A backend that writes the new bytes over the old ones without truncating
     * leaves the first value's tail behind, and reading back gives a value that begins correctly and
     * runs on into the previous one — invisible to any overwrite test whose two payloads are the same
     * length, which is what the pre-existing per-backend tests mostly had.
     *
     * The third write grows the value again, so a backend that truncated on the way down and then
     * cannot grow back fails too.
     */
    @Test
    public fun aSecondWriteReplacesTheFirstWhole(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val key = StoreKey("k")
        store.write(key, largeValue())
        store.write(key, byteArrayOf(9))
        val afterShrink = store.read(key)
        store.write(key, byteArrayOf(1, 2, 3, 4, 5))
        val afterGrow = store.read(key)

        assertAll(
            { assertEquals(1, afterShrink?.size, "a shorter value must REPLACE the longer one, leaving no tail") },
            { assertContentEquals(byteArrayOf(9), afterShrink, "and the surviving byte is the new one") },
            { assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), afterGrow, "and the value can grow again afterwards") },
        )
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    /**
     * Delete makes the key absent — genuinely absent, the same state [readOfANeverWrittenKeyIsNull]
     * describes — and leaves it usable again.
     *
     * The re-write half catches the backend whose delete is a tombstone the write path then honours:
     * a key that can be deleted but not resurrected is a slow leak of the key space, and the caller
     * has no listing surface to notice it with.
     */
    @Test
    public fun deleteMakesTheKeyAbsentAndWritableAgain(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val key = StoreKey("k")
        store.write(key, byteArrayOf(42))
        store.delete(key)
        val afterDelete = store.read(key)
        store.write(key, byteArrayOf(7))
        val afterRewrite = store.read(key)

        assertAll(
            { assertNull(afterDelete, "a deleted key reads as absent") },
            { assertContentEquals(byteArrayOf(7), afterRewrite, "and can be written again afterwards") },
        )
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    /**
     * **Distinct keys address distinct entries.** Writing one must never change another.
     *
     * [StoreKey]'s own KDoc says it exists *"so that callers cannot accidentally mix up keys"*, and
     * [DurableStore] describes itself as **key-addressed** with no restriction whatsoever on what a
     * key may contain. A backend that folds two distinct keys onto one entry does not merely confuse
     * them — the second write **destroys** the first value, silently, with no listing surface for the
     * caller to notice it through.
     *
     * The key set is chosen against the shape three of the four in-tree backends have: a key mapped
     * onto a **filesystem path** or an IndexedDB key. So it walks the characters a filename sanitiser
     * reaches for first — `.`, `/`, space, `:` — beside the two an `[a-zA-Z0-9_-]` allowlist keeps.
     * Every pair differs somewhere a lossy sanitiser folds, and nowhere else: same length, same
     * letters, one character apart.
     *
     * The non-ASCII pair is the same argument one step further out. Two sanitisers that both look
     * correct disagree completely on it — a `Regex("[^a-zA-Z0-9_-]")` replacement folds every
     * non-Latin letter to `_`, while a `Char.isLetterOrDigit()` test keeps them, because
     * `isLetterOrDigit` is true for Cyrillic. Keys derived from anything a person typed reach this.
     *
     * ## The case pair, and what a green on it does not prove
     *
     * `a-b` and `a-B` are the pair whose absence let the #2506 defect survive longest on the file
     * backends: every sanitiser in the tree passed a letter through unchanged, so the two keys were
     * distinct *strings* and one *file*. Nobody wrote the pair, so nobody measured it.
     *
     * It is worth stating here rather than only in each file backend's own tests, because there are
     * **two** ways to fail it and only one of them is about filesystems:
     *
     * - A backend that folds case **itself** — a `lowercase()` on the way to a filename, an
     *   IndexedDB key normalised before it is stored — fails this everywhere, on every target and
     *   every filesystem. That is the failure this suite genuinely establishes the absence of, and
     *   it is reachable by any backend, file-backed or not.
     * - A backend that hands both keys to a **case-insensitive filesystem** fails it only where the
     *   filesystem folds: APFS by default, exFAT, NTFS — but not ext4. So a green on a Linux runner
     *   is *no evidence at all* about that second failure, and must not be read as any. Only a run
     *   on a case-folding filesystem discriminates, which is exactly why the defect went unmeasured:
     *   the CI most projects run cannot see it.
     *
     * Stated the other way round: this property is unconditional about the store's own behaviour and
     * conditional about its medium's. It is kept here anyway because the first failure is the one a
     * *new* backend is most likely to introduce, and because the pair costs one entry.
     *
     * Each key gets a distinct one-byte value and every one is read back, so the failure names
     * *which* keys collided rather than only that something did.
     */
    @Test
    public fun distinctKeysAddressDistinctEntries(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val keys = listOf("a.b", "a/b", "a b", "a:b", "a-b", "a-B", "a_b", "мир", "миг")
        keys.forEachIndexed { index, name -> store.write(StoreKey(name), byteArrayOf(index.toByte())) }

        val readBack = keys.map { store.read(StoreKey(it)) }
        assertAll(
            *keys.mapIndexed { index, name ->
                {
                    assertContentEquals(
                        byteArrayOf(index.toByte()), readBack[index],
                        "the entry under \"$name\" must hold what was written under \"$name\" — a store that " +
                            "folds it together with a sibling key silently DESTROYED one of the two values",
                    )
                }
            }.toTypedArray(),
        )
    }

    /**
     * A key that looks like a path is a **key**, not a path: it round-trips, and it does not become
     * some other key.
     *
     * Three of the four in-tree backends put keys on a filesystem, so `..` and a leading `/` are the
     * two strings whose interpretation as *structure* rather than as a name would take a write
     * outside the store's own storage area entirely. A caller deriving key names from anything it did
     * not author is one string away from that.
     *
     * **What this cannot check, said plainly:** that the bytes did not land outside the store's
     * storage area. [DurableStore] exposes no path, no root and no listing, so from inside this suite
     * a write that escaped and a write that was contained are indistinguishable — both read back
     * correctly through the key that wrote them. What is checkable is the *aliasing* half, and it is
     * the half a caller is actually hurt by: `"../evil"` must not resolve to the same entry as
     * `"evil"`, and `"/etc/x"` must not resolve to the same entry as `"etc/x"`. Each backend's own
     * tests are where containment is provable, because only they can look at the medium.
     */
    @Test
    public fun aKeyThatLooksLikeAPathIsJustAKey(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val traversal = StoreKey("../evil")
        val plain = StoreKey("evil")
        val absolute = StoreKey("/etc/x")
        val relative = StoreKey("etc/x")
        store.write(traversal, byteArrayOf(1))
        store.write(plain, byteArrayOf(2))
        store.write(absolute, byteArrayOf(3))
        store.write(relative, byteArrayOf(4))

        val readTraversal = store.read(traversal)
        val readPlain = store.read(plain)
        val readAbsolute = store.read(absolute)
        val readRelative = store.read(relative)
        assertAll(
            { assertContentEquals(byteArrayOf(1), readTraversal, "\"../evil\" round-trips as an ordinary key") },
            { assertContentEquals(byteArrayOf(2), readPlain, "and did not swallow, or get swallowed by, \"evil\"") },
            { assertContentEquals(byteArrayOf(3), readAbsolute, "\"/etc/x\" round-trips as an ordinary key") },
            { assertContentEquals(byteArrayOf(4), readRelative, "and stayed distinct from \"etc/x\"") },
        )
    }

    /**
     * A key considerably longer than the hand-written names a caller normally uses.
     *
     * **The knob is [LONG_KEY_CHARS], and it is bounded from above on purpose.** A POSIX filesystem
     * refuses a single path component past 255 bytes outright, so a backend mapping a key to a
     * filename cannot satisfy an arbitrarily long key without hashing — and the contract never
     * promised one. 128 characters is comfortably past every key any in-tree caller uses and
     * comfortably inside that limit, so what this property checks is that nothing **truncates** a
     * long key (which would fold it together with any sibling sharing its first N characters — the
     * [distinctKeysAddressDistinctEntries] failure arriving by a different route).
     *
     * Said plainly: this does **not** establish that a 4 KiB key works, and a backend should not be
     * read as promising one. The two keys differ only in their **last** character, so a truncating
     * backend fails rather than passing quietly.
     */
    @Test
    public fun aLongKeyIsStillAKey(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val stem = "k".repeat(LONG_KEY_CHARS - 1)
        val first = StoreKey(stem + "1")
        val second = StoreKey(stem + "2")
        store.write(first, byteArrayOf(1))
        store.write(second, byteArrayOf(2))

        val readFirst = store.read(first)
        val readSecond = store.read(second)
        assertAll(
            { assertContentEquals(byteArrayOf(1), readFirst, "a $LONG_KEY_CHARS-character key round-trips") },
            {
                assertContentEquals(
                    byteArrayOf(2), readSecond,
                    "and a sibling differing only in its LAST character is a different key — a store that " +
                        "truncates long keys folded the two together",
                )
            },
        )
    }

    // ── Who owns the arrays ──────────────────────────────────────────────────

    /**
     * The store must **copy** what it is given, not keep a reference to the caller's array.
     *
     * The caller owns the array it passed and is entitled to reuse it — a buffer pooled across
     * writes is the ordinary reason to. A backend that keeps the reference has a stored value that
     * changes underneath it with no write, which is unattributable from the caller's side because
     * nothing appears to have happened.
     *
     * The mutation is applied **before** the read rather than after, so a backend that copies lazily
     * on read is caught too.
     */
    @Test
    public fun theStoreDoesNotAliasTheArrayItWasGiven(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val key = StoreKey("k")
        val mine = byteArrayOf(1, 2, 3)
        store.write(key, mine)
        mine[0] = 99

        assertContentEquals(
            byteArrayOf(1, 2, 3), store.read(key),
            "mutating the caller's array after write must not change what the store holds",
        )
    }

    /**
     * The reverse: the array [DurableStore.read] hands back belongs to the caller, so writing into it
     * must not reach the store.
     *
     * A backend handing out its own buffer gives every reader the power to corrupt every later
     * reader's value, without a write ever being issued.
     */
    @Test
    public fun theStoreDoesNotAliasTheArrayItHandsBack(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val key = StoreKey("k")
        store.write(key, byteArrayOf(1, 2, 3))
        val handedBack = assertNotNull(store.read(key), "the value just written must be readable")
        handedBack[0] = 99

        assertContentEquals(
            byteArrayOf(1, 2, 3), store.read(key),
            "mutating the array read out must not change what the store holds",
        )
    }

    // ── Durability across a restart ──────────────────────────────────────────

    /**
     * The property [DurableStore] exists for: *"A crash after [DurableStore.write] returns implies
     * the bytes survive a restart and are returned by the next [DurableStore.read]."*
     *
     * Every other property in this file reads back off the object that took the write, so all of them
     * are satisfied by a backend that has written nothing anywhere. This is the only place a second
     * handle onto the medium is obtained, and therefore the only place the interface's own headline
     * sentence is checked at all.
     *
     * Three values cross the restart together, each catching a different way a backend loses one:
     *
     * 1. `kept` — written once. The plain case.
     * 2. `overwritten` — written twice. A backend that commits the first write durably and the second
     *    only in memory comes back holding a **stale** value, which is worse than an absent one: the
     *    caller has no reason to distrust it.
     * 3. Both carry the [everyByteValueSurvivesTheRoundTrip] payload rather than `1, 2, 3`, because a
     *    restart is the one path in this suite that crosses an encode/decode boundary, and that is
     *    exactly where a byte that widens on the way through bites.
     *
     * On [RestartFixture.KeepsNothing] the assertions invert: everything must be gone. That arm is
     * not a formality — a durable backend that declared it reds here, because its bytes come back.
     * What it cannot do is prove anything about durability; see [restart].
     */
    @Test
    public fun whatWasWrittenBeforeARestartIsReadableAfterIt(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = newStore()
        val kept = StoreKey("kept")
        val overwritten = StoreKey("overwritten")
        val first = ByteArray(BYTE_VALUES) { it.toByte() }
        val second = ByteArray(BYTE_VALUES) { (BYTE_VALUES - 1 - it).toByte() }
        store.write(kept, first)
        store.write(overwritten, first)
        store.write(overwritten, second)

        val fixture = restart(store)
        val restarted = fixture.store
        val readKept = restarted.read(kept)
        val readOverwritten = restarted.read(overwritten)

        assertAll(
            {
                assertNotSame(
                    store, restarted,
                    "restart must hand back a NEW handle — returning the store it was given makes every " +
                        "assertion below a restatement of the same-handle properties above",
                )
            },
            {
                when (fixture) {
                    is RestartFixture.Durable -> assertAll(
                        { assertContentEquals(first, readKept, "a durably written value must survive the restart") },
                        {
                            assertContentEquals(
                                second, readOverwritten,
                                "and an OVERWRITTEN key must come back holding the SECOND value — a backend " +
                                    "that persisted only the first returns a stale value the caller has no " +
                                    "reason to distrust",
                            )
                        },
                    )

                    is RestartFixture.KeepsNothing -> assertAll(
                        {
                            assertNull(
                                readKept,
                                "this backend declared that it keeps nothing across a restart, so a fresh " +
                                    "handle must be empty — one that answers here is durable and declared the " +
                                    "wrong arm",
                            )
                        },
                        { assertNull(readOverwritten, "and the same for a key written twice") },
                    )
                }
            },
        )
    }

    /**
     * A [DurableStore.delete] that happened before the restart must still have happened after it.
     *
     * The failure this catches is the mirror of the one above and reaches further: a backend whose
     * delete removes the entry from a live map but never from the medium comes back with a value the
     * caller **deleted**. Every same-handle delete property in this file is green against it, because
     * the live map is what they read.
     *
     * A sibling key is written and left alone so the assertion distinguishes "the delete was durable"
     * from "the restart lost everything" — without it, a backend whose reopen returns an empty store
     * passes this and its own bug is what makes it pass.
     *
     * On [RestartFixture.KeepsNothing] the sibling is expected absent instead, and the deleted key's
     * assertion carries no information there — everything is gone either way. That is stated rather
     * than dressed up: on that arm this property discriminates on the sibling alone.
     */
    @Test
    public fun whatWasDeletedBeforeARestartIsStillAbsentAfterIt(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val store = newStore()
            val removed = StoreKey("removed")
            val sibling = StoreKey("sibling")
            store.write(removed, byteArrayOf(1, 2, 3))
            store.write(sibling, byteArrayOf(4, 5, 6))
            store.delete(removed)

            val fixture = restart(store)
            val restarted = fixture.store
            val readRemoved = restarted.read(removed)
            val readSibling = restarted.read(sibling)

            assertAll(
                { assertNotSame(store, restarted, "restart must hand back a NEW handle") },
                { assertNull(readRemoved, "a key deleted before the restart must still be absent after it") },
                {
                    when (fixture) {
                        is RestartFixture.Durable ->
                            assertContentEquals(
                                byteArrayOf(4, 5, 6), readSibling,
                                "and the delete must be the reason it is absent — a sibling written beside it " +
                                    "must survive, or a reopen that simply lost everything would pass the " +
                                    "assertion above on its own bug",
                            )

                        is RestartFixture.KeepsNothing ->
                            assertNull(
                                readSibling,
                                "this backend declared that it keeps nothing across a restart, so the sibling " +
                                    "is gone too — one that answers here is durable and declared the wrong arm",
                            )
                    }
                },
            )
        }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** The payload [aLargeValueRoundTripsWhole] and [aSecondWriteReplacesTheFirstWhole] share. */
    private fun largeValue(): ByteArray = Random(LARGE_VALUE_SEED).nextBytes(LARGE_VALUE_BYTES)
}

/**
 * What [DurableStoreConformanceSuite.restart] hands back: a store reopened onto the same medium, and
 * what this backend **claims** about surviving a process exit.
 *
 * Sealed rather than a store plus a boolean, so the two cannot drift apart: a subclass makes one
 * decision, in one place, and the suite dispatches on it. Neither arm is an opt-out — see
 * [DurableStoreConformanceSuite.restart] for why a nullable hook would have been, and what each arm
 * can and cannot detect.
 *
 * Top-level rather than nested so a fixture helper outside a suite subclass can build one.
 */
public sealed interface RestartFixture {

    /** The reopened store. */
    public val store: DurableStore

    /**
     * This backend commits to a medium that outlives the process, and [store] is a new handle onto
     * the one the restarted store wrote to. Everything durably written before the restart must be
     * readable through it, and everything deleted must still be gone.
     */
    public class Durable(override val store: DurableStore) : RestartFixture

    /**
     * This backend keeps nothing across a process exit, and [store] is the fresh, empty store a
     * restarted process would get — `InMemoryDurableStore`, or any other backend whose own KDoc says
     * it is not crash-safe.
     *
     * The honest arm, and it still asserts something: a fresh handle must read **empty**, so a
     * `restart` returning the store it was given fails, and so does a backend that genuinely persists
     * but declared this arm. What it cannot detect — stated here rather than left implied — is
     * anything at all about durability. There is no medium for a write to fail to reach, so the
     * obligation the [DurableStore] contract is arranged around is unreachable on this arm by
     * construction. That is why the obligation lives in a TCK the durable backends also run.
     */
    public class KeepsNothing(override val store: DurableStore) : RestartFixture
}

/** Every distinct byte value — the payload width [everyByteValueSurvivesTheRoundTrip] needs. */
private const val BYTE_VALUES = 256

/**
 * 256 KiB — see [DurableStoreConformanceSuite.aLargeValueRoundTripsWhole] for why this is not a few
 * KiB, and what a smaller value would switch off.
 */
private const val LARGE_VALUE_BYTES = 256 * 1024

/** Fixed so the large payload is the same on every run and every target. */
private const val LARGE_VALUE_SEED = 0x5D0BE

/**
 * 128 — see [DurableStoreConformanceSuite.aLongKeyIsStillAKey] for why this is bounded from above as
 * well as below.
 */
private const val LONG_KEY_CHARS = 128
