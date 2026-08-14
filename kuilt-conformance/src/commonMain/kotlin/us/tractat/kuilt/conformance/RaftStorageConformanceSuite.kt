package us.tractat.kuilt.conformance

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.LeaderForTerm
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.SnapshotMeta
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reusable contract test suite for [RaftStorage] implementations.
 *
 * Subclass and implement [newStorage] and [reopen] to bind any storage under test.
 * Every [Test] in this class encodes a required invariant of the [RaftStorage]
 * contract — a conforming implementation must pass all of them.
 *
 * Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`)
 * so every storage adapter can subclass it from its own test source set.
 *
 * ```kotlin
 * class SqliteRaftStorageConformanceTest : RaftStorageConformanceSuite() {
 *     override fun newStorage(): RaftStorage = SqliteRaftStorage(inMemoryDb())
 *     override suspend fun reopen(storage: RaftStorage): RaftStorage =
 *         SqliteRaftStorage((storage as SqliteRaftStorage).closeAndReopenTheSameFile())
 * }
 * ```
 *
 * [newStorage] must return a **fresh, empty** instance on every call — term `0`,
 * no vote, no established leader, empty log. [reopen] must return a **second handle onto the
 * medium the given instance wrote to**, which is what makes the durability half of this contract
 * checkable at all (#2301).
 *
 * ## Faithfulness, not validation (#1922)
 *
 * kuilt ships **no** durable [RaftStorage] — `InMemoryRaftStorage` is the only implementation in the
 * library, so every persistent adapter is consumer code. Since #1887 the engine refuses to start
 * (`CorruptDurableStateException`) on durable state it cannot believe: a term, snapshot baseline, or
 * restored entry outside `0..2^60`, a log with a gap, or terms that decrease along the log. This suite
 * exists so an adapter bug of that class surfaces at the adapter's own test time rather than at a
 * consumer's startup.
 *
 * The line this suite holds: it may require an adapter to **round-trip faithfully** the values the
 * engine's restore checks make load-bearing; it may **not** require it to **reject garbage it was never
 * given**. The adapter is not the validation point — the engine is. So every assertion below writes a
 * value the engine's checks *admit*, and requires it back unchanged. None asserts that an adapter
 * rejects, clamps, or repairs anything.
 */
public abstract class RaftStorageConformanceSuite {

    /**
     * Returns a fresh, empty [RaftStorage] instance.
     *
     * Called once per test — each test drives its own independent storage.
     * The instance must start with `term() == 0L`, `votedFor() == null`,
     * `leaderForTerm() == null`, and `entries() == emptyList()`.
     */
    public abstract fun newStorage(): RaftStorage

    /**
     * A **new handle onto the same durable medium** [storage] wrote to — what a process restart
     * hands back. Reopening the file, re-running the decoder, re-issuing the `SELECT`: whatever
     * "come back after a crash" means for this implementation, performed deterministically.
     *
     * ## Why this is not nullable (#2301)
     *
     * [RaftStorage]'s own contract opens with *"durable state that a Raft node must persist to
     * survive restarts"*, and until this hook existed **no property in this suite obtained a second
     * handle onto anything**. Every read-back above is off the same live object that just took the
     * write, so an adapter that buffers each write in a `HashMap` and flushes on `close()` — or
     * never — passes all of them, as does one whose write is an `INSERT` with no `COMMIT`, as does
     * the `NSUserDefaults`-without-`synchronize()` shape [RaftStorage.saveTermAndVotedFor]'s KDoc
     * warns about **by name**.
     *
     * An `if (cannotReopen) return` opt-out would move that vacuity one level up, where it is
     * harder to see: the suite would go green for an adapter that never crossed its own
     * encode/decode boundary at all. There is no adapter for which the state is unreachable —
     * a durable one reopens its medium, and an in-memory one models a restart by rebuilding a
     * fresh instance from what its **public read surface** reports.
     *
     * ## What the reference must not do, and what this suite can and cannot check
     *
     * **Rebuild through the public read surface; never copy fields.** A reopen that aliases the
     * original's internals makes both handles move together — the two sides agree by construction
     * and the property is a tautology dressed as durability, which is precisely the shape #2240
     * shipped twice one module over. `InMemoryRaftStorage` keeps all five of its fields private, so
     * a field-copying reopen is not even writable from a subclass here; that is structure doing the
     * work rather than a convention.
     *
     * The suite checks the one half of that it can: every property below asserts, before anything
     * else, that what came back is a **different object** (see [reopened]), so an implementation
     * returning `this` fails loudly instead of passing quietly. What it **cannot** detect, stated
     * plainly: a thin wrapper delegating every call to the original object is indistinguishable
     * from a genuine reopen through this contract, because nothing in [RaftStorage] exposes the
     * medium. That residual is narrow — it takes deliberate effort to write — and the alternative
     * (a `medium()` accessor on the contract, invented so a test could check it) would put a knob
     * in the interface that no consumer asked for.
     *
     * ## Mutation receipt
     *
     * Measured over `:kuilt-conformance:jvmTest --tests "*InMemoryRaftStorageConformanceTest*"`
     * (36 tests, green at baseline). Each mutation applied alone, reverted, the revert verified with
     * `git status`; the results XML deleted before every run and the log grepped for compile errors,
     * because a mutation that does not compile leaves Gradle serving the previous run's XML and
     * fabricates a plausible copy of the row above it.
     *
     * | Mutation | Reds |
     * |---|---|
     * | **Fixture:** `reopen` returns the instance it was given | the **precondition** in all five — no durability assertion is reached |
     * | **Fixture:** `reopen` returns a fresh empty storage | [termAndVoteSurviveAReopen] (3 of its 4), [theEstablishedLeaderSurvivesAReopen], [theLogSurvivesAReopenWhole], [theSnapshotSurvivesAReopen] |
     * | **Fixture:** drop the term/vote restore | [termAndVoteSurviveAReopen] only (3 of 4) |
     * | **Fixture:** drop the pin restore | [theEstablishedLeaderSurvivesAReopen] only |
     * | **Fixture:** drop the log restore | [theLogSurvivesAReopenWhole] only |
     * | **Fixture:** drop the snapshot restore | [theSnapshotSurvivesAReopen] only |
     * | **Fixture:** resurrect a cleared vote (`storage.votedFor() ?: NodeId("node-a")`) | one assertion in [termAndVoteSurviveAReopen], one in [anUnwrittenMediumReopensEmpty] |
     * | **Production:** drop `currentTerm = term` in `InMemoryRaftStorage.saveTermAndVotedFor` | [termAndVoteSurviveAReopen] **and four pre-existing properties** |
     *
     * **The fixture rows are the load-bearing ones, and that is the finding rather than a dodge.**
     * kuilt ships no durable [RaftStorage], so the reference's own `reopen` is the closest this tree
     * has to an adapter's persistence layer — mutating it *is* mutating the thing under test. Rows 3
     * to 6 are what make these five properties per-record rather than one property counted five
     * times.
     *
     * **The last row is blast radius, not discrimination, and no production row could be otherwise.**
     * The reference's reopen reads through the same public surface the same-handle properties read,
     * so every mutation of `InMemoryRaftStorage` that reaches a restart property reaches an older one
     * first. Said outright: over `InMemoryRaftStorage` *alone*, these five properties add no
     * discriminating power at all. They discriminate over the adapters that do not exist in this
     * tree — which is the whole of #2247's thesis, and the reason the obligation belongs in the TCK
     * rather than in a backend's own tests.
     *
     * **Row 2 reds 3 of 4 assertions, not 4** — the cleared-vote arm survives it, because a fresh
     * empty storage happens to have no vote either. Row 7 is the mutation that reds *that* arm, and
     * it is in the table for exactly that reason: without it the arm would be an assertion no
     * measurement had ever moved.
     */
    protected abstract suspend fun reopen(storage: RaftStorage): RaftStorage

    // ── Term / vote ──────────────────────────────────────────────────────────

    @Test
    public fun initialTermIsZero(): TestResult = runTest {
        assertEquals(0L, newStorage().term())
    }

    @Test
    public fun initialVotedForIsNull(): TestResult = runTest {
        assertNull(newStorage().votedFor())
    }

    @Test
    public fun savesAndLoadsTerm(): TestResult = runTest {
        val storage = newStorage()
        storage.saveTerm(7L)
        assertEquals(7L, storage.term())
    }

    @Test
    public fun savesAndLoadsVotedFor(): TestResult = runTest {
        val storage = newStorage()
        storage.saveVotedFor(NodeId("node-1"))
        assertEquals(NodeId("node-1"), storage.votedFor())
    }

    @Test
    public fun saveVotedForNull_clearsVote(): TestResult = runTest {
        val storage = newStorage()
        storage.saveVotedFor(NodeId("node-1"))
        storage.saveVotedFor(null)
        assertNull(storage.votedFor())
    }

    // ── saveTermAndVotedFor atomicity ────────────────────────────────────────

    /**
     * Verifies the §5.1/§5.2 atomicity contract: after [RaftStorage.saveTermAndVotedFor]
     * both `term` and `votedFor` are visible together. Persistent implementations
     * must write both in a single transaction so a mid-write crash cannot leave
     * a node with an advanced term but stale vote (or vice-versa), which would
     * allow it to vote twice in the same term.
     */
    @Test
    public fun saveTermAndVotedFor_persistsBoth(): TestResult = runTest {
        val storage = newStorage()
        storage.saveTermAndVotedFor(5L, NodeId("node-a"))
        val term = storage.term()
        val votedFor = storage.votedFor()
        assertAll(
            { assertEquals(5L, term, "term must be persisted") },
            { assertEquals(NodeId("node-a"), votedFor, "votedFor must be persisted") },
        )
    }

    @Test
    public fun saveTermAndVotedFor_withNullVote(): TestResult = runTest {
        val storage = newStorage()
        storage.saveTermAndVotedFor(7L, null)
        val term = storage.term()
        val votedFor = storage.votedFor()
        assertAll(
            { assertEquals(7L, term, "term must be persisted") },
            { assertNull(votedFor, "votedFor must be null") },
        )
    }

    @Test
    public fun saveTermAndVotedFor_overwritesPriorVote(): TestResult = runTest {
        val storage = newStorage()
        storage.saveTermAndVotedFor(3L, NodeId("node-a"))
        storage.saveTermAndVotedFor(4L, null)
        val term = storage.term()
        val votedFor = storage.votedFor()
        assertAll(
            { assertEquals(4L, term, "term must be updated") },
            { assertNull(votedFor, "prior vote must be cleared") },
        )
    }

    // ── Log ─────────────────────────────────────────────────────────────────

    @Test
    public fun entriesOnEmptyLog_isEmpty(): TestResult = runTest {
        assertEquals(emptyList(), newStorage().entries())
    }

    @Test
    public fun appendsAndRetrievesEntries(): TestResult = runTest {
        val storage = newStorage()
        val toAppend = listOf(
            LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
            LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
            LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
        )
        storage.appendEntries(toAppend)
        val retrieved = storage.entries()
        // List-shaped, not `retrieved[0]`/`[1]`/`[2]`: a storage that drops an entry makes those
        // throw IndexOutOfBoundsException. Since #2283 that no longer costs the sibling diagnoses —
        // `assertAll` carries them along on it — but the implementor still reads the throw first,
        // where a named count/content failure prints both sides outright.
        assertAll(
            { assertEquals(3, retrieved.size, "should have 3 entries") },
            { assertEquals(listOf(1L, 2L, 3L), retrieved.map { it.index }, "every appended index, in order") },
            { assertEquals(toAppend, retrieved, "every appended entry round-trips whole, in order") },
        )
    }

    @Test
    public fun entriesFromIndex_filters(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
                LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
            )
        )
        val fromTwo = storage.entries(fromIndex = 2L)
        assertAll(
            { assertEquals(2, fromTwo.size, "entries from index 2 should have 2 items") },
            { assertEquals(listOf(2L, 3L), fromTwo.map { it.index }, "entries(fromIndex = 2) yields indices 2 and 3, in order") },
        )
    }

    @Test
    public fun truncateFrom_removesTailEntries(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
                LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
            )
        )
        storage.truncateFrom(2L)
        val remaining = storage.entries()
        assertAll(
            { assertEquals(1, remaining.size, "only index 1 should remain") },
            { assertEquals(listOf(1L), remaining.map { it.index }, "the surviving entry is index 1") },
        )
    }

    @Test
    public fun truncateFrom_belowAllEntries_clearsLog(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
                LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
            )
        )
        storage.truncateFrom(1L)
        assertEquals(emptyList(), storage.entries())
    }

    @Test
    public fun appendAfterTruncate_works(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
                LogEntry(index = 3L, term = 2L, command = byteArrayOf(3)),
            )
        )
        storage.truncateFrom(2L)
        val replacement = LogEntry(index = 2L, term = 3L, command = byteArrayOf(99))
        storage.appendEntries(listOf(replacement))
        val entries = storage.entries()
        assertAll(
            { assertEquals(2, entries.size, "should have 2 entries after re-append") },
            { assertEquals(listOf(1L, 2L), entries.map { it.index }, "the head survives and the tail is re-appended") },
            { assertEquals(listOf(replacement), entries.drop(1), "the replacement entry sits at index 2") },
        )
    }

    // ── Numeric faithfulness at the engine's plausibility edges (#1922) ───────

    /**
     * Mirrors `RaftEngine.checkedRestoredTerm` (#1855): the engine admits a restored
     * `storage.term()` in `0..`[MAX_PLAUSIBLE] and refuses to start outside it.
     *
     * A term is a `Long` and both endpoints are admitted, so an adapter whose term column cannot
     * hold one — a 32-bit integer, a `REAL`/`Double`, a JSON number in a JS-backed store — silently
     * hands the engine a different node identity than the one it persisted, which is a double-vote
     * in a term the node has forgotten (§5.2).
     */
    @Test
    public fun savesAndLoadsTerm_atPlausibilityEdges(): TestResult = runTest {
        val zero = newStorage().also { it.saveTerm(0L) }.term()
        val belowCeiling = newStorage().also { it.saveTerm(MAX_PLAUSIBLE - 1L) }.term()
        val ceiling = newStorage().also { it.saveTerm(MAX_PLAUSIBLE) }.term()
        assertAll(
            { assertEquals(0L, zero, "term 0 must round-trip") },
            { assertEquals(MAX_PLAUSIBLE - 1L, belowCeiling, "term ${MAX_PLAUSIBLE - 1L} must round-trip exactly") },
            { assertEquals(MAX_PLAUSIBLE, ceiling, "term $MAX_PLAUSIBLE must round-trip exactly") },
        )
    }

    /**
     * The [RaftStorage.saveTermAndVotedFor] half of [savesAndLoadsTerm_atPlausibilityEdges] — the
     * atomic writer the engine actually uses at every term-advance site, so it is the path a lossy
     * term column is reached through in practice.
     */
    @Test
    public fun saveTermAndVotedFor_persistsTermAtPlausibilityEdges(): TestResult = runTest {
        val zero = newStorage().also { it.saveTermAndVotedFor(0L, null) }.term()
        val belowCeiling = newStorage()
            .also { it.saveTermAndVotedFor(MAX_PLAUSIBLE - 1L, NodeId("node-a")) }
            .term()
        val ceiling = newStorage().also { it.saveTermAndVotedFor(MAX_PLAUSIBLE, NodeId("node-a")) }.term()
        assertAll(
            { assertEquals(0L, zero, "term 0 must round-trip") },
            { assertEquals(MAX_PLAUSIBLE - 1L, belowCeiling, "term ${MAX_PLAUSIBLE - 1L} must round-trip exactly") },
            { assertEquals(MAX_PLAUSIBLE, ceiling, "term $MAX_PLAUSIBLE must round-trip exactly") },
        )
    }

    // ── Per-term leader identity (#1900) ─────────────────────────────────────

    @Test
    public fun leaderForTermBeforeAnySave_isNull(): TestResult = runTest {
        assertNull(
            newStorage().leaderForTerm(),
            "a storage that has never established a leader must report none, not a zero-term sentinel",
        )
    }

    /**
     * The round trip §3.10 sender-authentication rests on: `RaftEngine` restores this record on start-up
     * and refuses a `TimeoutNow` from anyone but the [LeaderForTerm.leaderId] it names, so **both** halves
     * must come back exactly as written.
     *
     * The term half is the one an adapter is most likely to lose, and losing it is worse than losing the
     * whole record: the engine decides relevance by comparing the stored term to its own, so a term that
     * comes back wrong either hides a live pin (the honest transfer target refuses its own leader) or
     * exposes a stale one (an identity from an older term is admitted as this term's authority).
     */
    @Test
    public fun savesAndLoadsLeaderForTerm(): TestResult = runTest {
        val storage = newStorage()
        storage.saveLeaderForTerm(7L, NodeId("node-a"))
        val restored = assertNotNull(storage.leaderForTerm(), "the established leader must be readable back")
        assertAll(
            { assertEquals(7L, restored.term, "the term the leader was established for must round-trip") },
            { assertEquals(NodeId("node-a"), restored.leaderId, "the leader identity must round-trip") },
        )
    }

    /**
     * The record is **one** value, not two independent keys. Overwriting it must replace both halves
     * together: an adapter that writes the term and the identity to separate rows can leave the new term
     * beside the old identity, which is precisely the mismatched pin
     * [RaftStorage.saveLeaderForTerm]'s single-write requirement exists to prevent — and the engine has
     * no way to detect it, since a pin at the current term is exactly what it is looking for.
     *
     * Written with both halves changing at once so a per-field write survives neither.
     */
    @Test
    public fun saveLeaderForTerm_replacesBothHalvesTogether(): TestResult = runTest {
        val storage = newStorage()
        storage.saveLeaderForTerm(3L, NodeId("node-a"))
        storage.saveLeaderForTerm(4L, NodeId("node-b"))
        val restored = assertNotNull(storage.leaderForTerm())
        assertAll(
            { assertEquals(4L, restored.term, "the term must be updated") },
            { assertEquals(NodeId("node-b"), restored.leaderId, "the identity must be updated with it") },
        )
    }

    /**
     * The [savesAndLoadsTerm_atPlausibilityEdges] argument, applied to the pin's term: it is compared
     * for **equality** with `currentTerm`, which the engine admits anywhere in `0..`[MAX_PLAUSIBLE].
     * A term column that cannot hold a `Long` therefore does not merely round-trip a slightly different
     * number — it makes the pin permanently invisible at the one term it was written for, and §3.10
     * transfer to that node fails on its auto-timeout with nothing to point at.
     *
     * `0` is included because it is a real term for this record even though no leader is ever established
     * at term 0: an adapter that encodes absence as a `0` sentinel fails
     * [leaderForTermBeforeAnySave_isNull] and this together.
     */
    @Test
    public fun savesAndLoadsLeaderForTerm_atPlausibilityEdges(): TestResult = runTest {
        val zero = newStorage().also { it.saveLeaderForTerm(0L, NodeId("node-a")) }.leaderForTerm()
        val belowCeiling = newStorage()
            .also { it.saveLeaderForTerm(MAX_PLAUSIBLE - 1L, NodeId("node-a")) }
            .leaderForTerm()
        val ceiling = newStorage().also { it.saveLeaderForTerm(MAX_PLAUSIBLE, NodeId("node-a")) }.leaderForTerm()
        assertAll(
            { assertEquals(LeaderForTerm(0L, NodeId("node-a")), zero, "a pin at term 0 must round-trip") },
            {
                assertEquals(
                    LeaderForTerm(MAX_PLAUSIBLE - 1L, NodeId("node-a")), belowCeiling,
                    "a pin at term ${MAX_PLAUSIBLE - 1L} must round-trip exactly",
                )
            },
            {
                assertEquals(
                    LeaderForTerm(MAX_PLAUSIBLE, NodeId("node-a")), ceiling,
                    "a pin at term $MAX_PLAUSIBLE must round-trip exactly",
                )
            },
        )
    }

    /**
     * The pin and the term/vote pair are **independent** records. The engine writes them at different
     * moments by construction — the pin on first leader-contact of a term, the term/vote at every
     * term-advance — so an adapter that stores them in one slot, or that clears one when the other is
     * written, loses the pin on the very next heartbeat-driven term observation.
     */
    @Test
    public fun saveTermAndVotedFor_doesNotDisturbTheEstablishedLeader(): TestResult = runTest {
        val storage = newStorage()
        storage.saveLeaderForTerm(5L, NodeId("node-a"))
        storage.saveTermAndVotedFor(6L, NodeId("node-b"))
        val restored = storage.leaderForTerm()
        val votedFor = storage.votedFor()
        assertAll(
            { assertEquals(LeaderForTerm(5L, NodeId("node-a")), restored, "the pin must survive a term/vote write") },
            { assertEquals(NodeId("node-b"), votedFor, "and the vote must not have been overwritten by the pin") },
        )
    }

    // ── Snapshot ─────────────────────────────────────────────────────────────

    @Test
    public fun loadSnapshotBeforeAnySave_isNull(): TestResult = runTest {
        assertNull(newStorage().loadSnapshot(), "a storage with no saved snapshot must report none")
    }

    /**
     * A snapshot saved at the **zero baseline** must come back *present*, with both metadata fields
     * intact.
     *
     * `0` is the lower bound `RaftEngine.checkedRestoredSnapshotMeta` admits on both halves, and the
     * engine distinguishes "no snapshot" (`loadSnapshot() == null`) from "a snapshot at index 0" —
     * the latter still seeds `state.snapshotConfig`, the membership baseline a node that compacted
     * past a config change recovers under. An adapter that encodes absence as a `0` sentinel, or
     * whose metadata columns are nullable-with-default, loses that config and comes back under the
     * wrong cluster configuration.
     */
    @Test
    public fun snapshotAtZeroBaseline_roundTrips(): TestResult = runTest {
        val storage = newStorage()
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 0L, lastIncludedTerm = 0L), byteArrayOf(7, 8, 9))
        val stored = storage.loadSnapshot()
        assertAll(
            { assertNotNull(stored, "a snapshot saved at index 0 is a snapshot, not an absent one") },
            { assertEquals(0L, stored?.meta?.lastIncludedIndex, "lastIncludedIndex 0 must round-trip") },
            { assertEquals(0L, stored?.meta?.lastIncludedTerm, "lastIncludedTerm 0 must round-trip") },
            { assertContentEquals(byteArrayOf(7, 8, 9), stored?.state, "snapshot bytes must round-trip") },
        )
    }

    /**
     * Mirrors `RaftEngine.checkedRestoredSnapshotMeta` (#1887): both halves of [SnapshotMeta] are
     * admitted up to and including [MAX_PLAUSIBLE], so both must survive a save/load cycle exactly.
     *
     * The engine adopts `lastIncludedTerm` as `state.snapshotTerm` and `lastIncludedIndex` as
     * `state.snapshotIndex`, which seeds the positional log math, the compaction floor, `commitIndex`,
     * and the `entries(snapshotIndex + 1)` filter. §5.4.1 orders log positions by `(term, index)`
     * lexicographically, so a value that drifts *upward* through a lossy column makes the node
     * unbeatable in every election while carrying a log it cannot justify.
     *
     * **Why `MAX_PLAUSIBLE - 1` and not just the ceiling.** The ceiling is `2^60`, a power of two and
     * therefore *exactly* representable as an IEEE-754 `Double` — the adapter persisting a `Long`
     * through a `Double` that this suite exists to catch round-trips it byte-for-byte. `2^60 - 1`
     * needs 60 mantissa bits, so it is the value that actually probes precision. Both are asserted:
     * a 32-bit column fails on either, a `Double` column only on `MAX_PLAUSIBLE - 1`.
     */
    @Test
    public fun snapshotMeta_roundTripsAtPlausibilityCeiling(): TestResult = runTest {
        val storage = newStorage()
        storage.saveSnapshot(
            SnapshotMeta(lastIncludedIndex = MAX_PLAUSIBLE, lastIncludedTerm = MAX_PLAUSIBLE),
            byteArrayOf(1),
        )
        val meta = storage.loadSnapshot()?.meta
        assertAll(
            { assertNotNull(meta, "snapshot must be present") },
            { assertEquals(MAX_PLAUSIBLE, meta?.lastIncludedIndex, "lastIncludedIndex must round-trip exactly") },
            { assertEquals(MAX_PLAUSIBLE, meta?.lastIncludedTerm, "lastIncludedTerm must round-trip exactly") },
        )
    }

    /** The precision probe described in [snapshotMeta_roundTripsAtPlausibilityCeiling]. */
    @Test
    public fun snapshotMeta_roundTripsBelowPlausibilityCeiling(): TestResult = runTest {
        val storage = newStorage()
        val edge = MAX_PLAUSIBLE - 1L
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = edge, lastIncludedTerm = edge), byteArrayOf(1))
        val meta = storage.loadSnapshot()?.meta
        assertAll(
            { assertNotNull(meta, "snapshot must be present") },
            { assertEquals(edge, meta?.lastIncludedIndex, "lastIncludedIndex $edge must round-trip exactly") },
            { assertEquals(edge, meta?.lastIncludedTerm, "lastIncludedTerm $edge must round-trip exactly") },
        )
    }

    // ── Log entry field faithfulness ─────────────────────────────────────────

    /**
     * Mirrors the range half of `RaftEngine.checkedRestoredEntries` (#1887): a restored
     * [LogEntry.index] is admitted in `0..`[MAX_PLAUSIBLE] and a [LogEntry.term] in
     * `0..currentTerm`, itself bounded by the same ceiling.
     *
     * Same precision reasoning as [snapshotMeta_roundTripsAtPlausibilityCeiling] — the run spans
     * `MAX_PLAUSIBLE - 2 .. MAX_PLAUSIBLE`, so it covers the inclusive boundary *and* the two
     * neighbours a `Double` cannot represent, while staying contiguous (which is separately
     * required, below). The final read also exercises [RaftStorage.entries] with a `fromIndex` past
     * `Int.MAX_VALUE` — the shape the engine's own `entries(snapshotIndex + 1)` takes on a compacted
     * node, and one an adapter comparing through a 32-bit column gets wrong.
     */
    @Test
    public fun logEntryIndexAndTerm_roundTripAtPlausibilityCeiling(): TestResult = runTest {
        val storage = newStorage()
        val top = MAX_PLAUSIBLE
        val written = listOf(
            LogEntry(index = top - 2L, term = MAX_PLAUSIBLE - 1L, command = byteArrayOf(1)),
            LogEntry(index = top - 1L, term = MAX_PLAUSIBLE - 1L, command = byteArrayOf(2)),
            LogEntry(index = top, term = MAX_PLAUSIBLE, command = byteArrayOf(3)),
        )
        storage.appendEntries(written)
        val read = storage.entries()
        val fromTop = storage.entries(fromIndex = top)
        assertAll(
            { assertEquals(3, read.size, "all three entries must be readable") },
            { assertEquals(written, read, "indices and terms must round-trip exactly at the ceiling") },
            { assertEquals(1, fromTop.size, "entries(fromIndex = $top) must select exactly the last entry") },
            { assertEquals(top, fromTop.firstOrNull()?.index, "the selected entry's index must be $top") },
        )
    }

    /**
     * The lower edge of the term half of `RaftEngine.checkedRestoredEntries` — `term = 0` is
     * admitted, and an adapter treating `0` as "unset" (a nullable column, a sentinel) corrupts it.
     *
     * The index half is deliberately **not** probed at `0`: the engine's own contiguity check
     * requires the restored log to begin at `snapshotIndex + 1`, which is `>= 1` because
     * `lastIncludedIndex >= 0`, and the restore reads `entries(snapshotIndex + 1)` — so an entry at
     * index `0` is never written by the engine and never visible to it. Requiring an adapter to
     * store one would test past faithfulness.
     */
    @Test
    public fun logEntryTerm_roundTripsAtZero(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(listOf(LogEntry(index = 1L, term = 0L, command = byteArrayOf(1))))
        assertEquals(0L, storage.entries().singleOrNull()?.term, "term 0 must round-trip")
    }

    // ── Contiguity and ordering across a save/load cycle ─────────────────────

    /**
     * Mirrors the contiguity half of `RaftEngine.checkedRestoredEntries` (#1887): entries written
     * contiguously must come back contiguous, in ascending index order, with no gap and no
     * reordering — across however many [RaftStorage.appendEntries] calls produced them.
     *
     * `RaftLogMath` resolves `entryAt(i)` positionally as `log[i - snapshotIndex - 1]`, valid only
     * while the restored list begins at `snapshotIndex + 1` and steps by one. A gap or a swap
     * silently returns the **wrong entry for every lookup** above it — wrong `prevTerm`, wrong
     * conflict resolution, wrong applies, no error anywhere. The adapter shape that produces it is
     * ordinary: a `SELECT` with no `ORDER BY`, or a hash-keyed store iterated in bucket order.
     */
    @Test
    public fun entries_areContiguousAndAscending_acrossSeparateAppends(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
            )
        )
        storage.appendEntries(listOf(LogEntry(index = 3L, term = 2L, command = byteArrayOf(3))))
        storage.appendEntries(
            listOf(
                LogEntry(index = 4L, term = 2L, command = byteArrayOf(4)),
                LogEntry(index = 5L, term = 3L, command = byteArrayOf(5)),
            )
        )
        val read = storage.entries()
        assertAll(
            { assertEquals(5, read.size, "all five entries must be readable") },
            { assertEquals((1L..5L).toList(), read.map { it.index }, "indices must be contiguous and ascending") },
            { assertEquals(listOf(1L, 1L, 2L, 2L, 3L), read.map { it.term }, "terms must stay with their entries") },
        )
    }

    /**
     * The filtered view the engine actually restores from — `entries(snapshotIndex + 1)` — must be
     * contiguous and ascending too, not just the unfiltered log.
     */
    @Test
    public fun entriesFromIndex_areContiguousAndAscending(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries((1L..5L).map { LogEntry(index = it, term = 1L, command = byteArrayOf(it.toByte())) })
        val suffix = storage.entries(fromIndex = 3L)
        assertEquals(listOf(3L, 4L, 5L), suffix.map { it.index }, "suffix must be contiguous and ascending")
    }

    // ── The #1221 crash window ───────────────────────────────────────────────

    /**
     * [RaftStorage.saveSnapshot] must be durable **before** [RaftStorage.discardLogPrefix] runs, so a
     * crash between the two legally leaves the snapshot *plus* the un-discarded prefix (#1221). An
     * adapter is **not** required to have discarded anything at this point, and this suite does not
     * assert that it has.
     *
     * What it does assert is the read the engine performs regardless of which side of that window a
     * node crashed on: `entries(snapshotIndex + 1)` returns exactly the suffix above the baseline.
     * The unfiltered log is checked only for the property that holds either way — still contiguous,
     * still ascending, still ending at the tail — so an adapter that keeps the prefix passes and one
     * that mangles the log on `saveSnapshot` does not.
     */
    @Test
    public fun snapshotSavedWithoutDiscard_leavesSuffixReadable(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries((1L..5L).map { LogEntry(index = it, term = 1L, command = byteArrayOf(it.toByte())) })
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 3L, lastIncludedTerm = 1L), byteArrayOf(42))
        val suffix = storage.entries(fromIndex = 4L)
        val all = storage.entries()
        assertAll(
            { assertEquals(listOf(4L, 5L), suffix.map { it.index }, "entries above the baseline must be exactly 4,5") },
            // Deliberately NOT assertEquals(listOf(4L, 5L), all.map { it.index }): keeping the prefix
            // is the legal #1221 crash-window state. Only shape-preservation is required here.
            { assertTrue(all.isNotEmpty(), "saveSnapshot must not empty the log") },
            { assertEquals(5L, all.lastOrNull()?.index, "saveSnapshot must not drop the log tail") },
            { assertContiguousAscending(all) },
        )
    }

    /**
     * The other side of the #1221 window: once [RaftStorage.discardLogPrefix] does run it removes
     * everything at or below its floor and leaves a contiguous suffix — and, per its contract, it is
     * idempotent and tolerates a floor below the first retained entry (the repeat a node performs
     * after recovering from a crash inside the window).
     */
    @Test
    public fun discardLogPrefix_isIdempotentAndToleratesLowFloor(): TestResult = runTest {
        val storage = newStorage()
        storage.appendEntries((1L..5L).map { LogEntry(index = it, term = 1L, command = byteArrayOf(it.toByte())) })
        storage.discardLogPrefix(3L)
        val afterFirst = storage.entries().map { it.index }
        storage.discardLogPrefix(3L)
        val afterRepeat = storage.entries().map { it.index }
        storage.discardLogPrefix(1L)
        val afterLowFloor = storage.entries().map { it.index }
        assertAll(
            { assertEquals(listOf(4L, 5L), afterFirst, "prefix at or below 3 must be gone, suffix contiguous") },
            { assertEquals(listOf(4L, 5L), afterRepeat, "discardLogPrefix must be idempotent") },
            { assertEquals(listOf(4L, 5L), afterLowFloor, "a floor below the first retained entry must be a no-op") },
        )
    }

    // ── Durability across a restart (#2301) ──────────────────────────────────

    /**
     * The §5.1/§5.2 pair, on the far side of a restart — the property [RaftStorage] exists for.
     *
     * [saveTermAndVotedFor_persistsBoth] proves the two are visible *together*; it cannot prove
     * either was written anywhere, because it reads them back off the object that took the write.
     * A node that comes back having forgotten a vote it cast votes **twice in one term**, which is
     * the §5.2 Election Safety violation the whole interface is arranged around. A node that comes
     * back having forgotten only the *term* is worse still: it re-enters an election at a term it
     * already resolved.
     *
     * The second arm is the shape a durable adapter gets wrong in the other direction. A cleared
     * vote must come back **cleared**, not resurrected from the row that preceded it — the
     * `UPDATE … SET voted_for = NULL` an adapter writes as an `INSERT` that simply omits the column,
     * or the key-value store whose `remove` is a no-op on a missing key. That costs liveness rather
     * than safety (the node believes it already voted this term and refuses everyone), but it is
     * indistinguishable from a wedge at the cluster level and unattributable without this.
     *
     * **The knob here is the term value, and a small literal would switch this property's sharpest
     * detection off.** A restart is the only path in this suite that crosses an encode/decode
     * boundary, which is exactly where a term column too narrow to hold a `Long` bites — so the
     * term is written at `MAX_PLAUSIBLE - 1`, the value [snapshotMeta_roundTripsAtPlausibilityCeiling]
     * explains needs 60 mantissa bits and therefore survives neither a 32-bit column nor a `Double`.
     * `7L` would be round-tripped correctly by both.
     */
    @Test
    public fun termAndVoteSurviveAReopen(): TestResult = runTest {
        val storage = newStorage()
        storage.saveTermAndVotedFor(DURABLE_TERM, NodeId("node-a"))
        val restarted = reopened(storage)
        val cleared = newStorage()
        cleared.saveTermAndVotedFor(DURABLE_TERM, NodeId("node-a"))
        cleared.saveTermAndVotedFor(DURABLE_TERM + 1L, null)
        val afterClear = reopened(cleared)
        val term = restarted.term()
        val votedFor = restarted.votedFor()
        val clearedTerm = afterClear.term()
        val clearedVote = afterClear.votedFor()
        assertAll(
            { assertEquals(DURABLE_TERM, term, "the term must survive a restart, exactly") },
            { assertEquals(NodeId("node-a"), votedFor, "and the vote with it — or the node votes twice") },
            { assertEquals(DURABLE_TERM + 1L, clearedTerm, "the later term must survive too") },
            { assertNull(clearedVote, "a vote CLEARED before the restart must not come back") },
        )
    }

    /**
     * The §3.10 sender-authority pin, on the far side of a restart — and the restart is the only
     * moment it matters at all.
     *
     * [saveLeaderForTerm]'s KDoc makes the argument in full: a node that comes back holding no
     * leader for a term it durably restored cannot tell the real leader's `TimeoutNow` from any
     * other voter's. Every other property in this suite reads the pin back off the handle that
     * wrote it, so an adapter that keeps this record in memory and writes only the term/vote pair —
     * a very natural omission, since the pin is *not* a §5.2 safety requirement — is green across
     * all of them and loses the record on every restart.
     *
     * Both halves are asserted through one [LeaderForTerm] equality rather than separately, for the
     * reason [savesAndLoadsLeaderForTerm] gives: an identity paired with a term it was never
     * established for is worse than no record, and it is the *pair* that has to cross the boundary
     * intact. Same term-value reasoning as [termAndVoteSurviveAReopen] — the pin's term is compared
     * for **equality** with `currentTerm`, so a column that narrows it makes the pin permanently
     * invisible at the one term it was written for.
     */
    @Test
    public fun theEstablishedLeaderSurvivesAReopen(): TestResult = runTest {
        val storage = newStorage()
        storage.saveLeaderForTerm(DURABLE_TERM, NodeId("node-a"))
        val restarted = reopened(storage)
        assertEquals(
            LeaderForTerm(DURABLE_TERM, NodeId("node-a")),
            restarted.leaderForTerm(),
            "the leader established for a term must survive the restart that is the only reason to store it",
        )
    }

    /**
     * The log, whole and in order, on the far side of a restart — including the filtered read the
     * engine actually restores from.
     *
     * The fixture is [logEntryIndexAndTerm_roundTripAtPlausibilityCeiling]'s, deliberately: that
     * property can be satisfied by a live field holding the entries, so it says nothing about the
     * column they were written to. Here the same values cross the encode/decode boundary, which is
     * where a 32-bit index, a `Double` term, or a `SELECT` with no `ORDER BY` actually bites —
     * `RaftLogMath` resolves `entryAt(i)` positionally, so a restored log that is short, reordered,
     * or gapped returns the wrong entry for every lookup above the defect, with no error anywhere.
     *
     * The `entries(fromIndex = top)` read is the shape `RaftEngine`'s own restore takes on a
     * compacted node (`entries(snapshotIndex + 1)`), and `top` is past `Int.MAX_VALUE`, so an
     * adapter whose reopened handle rebuilds its index through a 32-bit comparison fails here and
     * nowhere else.
     */
    @Test
    public fun theLogSurvivesAReopenWhole(): TestResult = runTest {
        val storage = newStorage()
        val top = MAX_PLAUSIBLE
        val written = listOf(
            LogEntry(index = top - 2L, term = MAX_PLAUSIBLE - 1L, command = byteArrayOf(1)),
            LogEntry(index = top - 1L, term = MAX_PLAUSIBLE - 1L, command = byteArrayOf(2)),
            LogEntry(index = top, term = MAX_PLAUSIBLE, command = byteArrayOf(3)),
        )
        storage.appendEntries(written)
        val restarted = reopened(storage)
        val restored = restarted.entries()
        val fromTop = restarted.entries(fromIndex = top)
        assertAll(
            { assertEquals(3, restored.size, "every appended entry must survive the restart") },
            { assertEquals(written, restored, "whole and in order — indices, terms and commands alike") },
            { assertEquals(listOf(written.last()), fromTop, "the filtered read the engine restores from must work too") },
        )
    }

    /**
     * The snapshot — metadata and bytes — on the far side of a restart.
     *
     * A node whose log was compacted has nothing else: the entries the snapshot covers were
     * discarded, so an adapter that persists the log but keeps the snapshot in memory comes back
     * with a *hole* rather than a short history, and the engine's contiguity check refuses to start
     * (`CorruptDurableStateException`) if it is lucky. The metadata halves seed `state.snapshotIndex`
     * / `state.snapshotTerm`, which §5.4.1 orders elections by, so a value that drifts upward through
     * a lossy column across the restart makes the node unbeatable while carrying a log it cannot
     * justify.
     *
     * [SnapshotMeta.config] is deliberately **not** exercised here — that round trip is #2302's, on
     * this same suite, and pinning it in two places would leave two things to keep in step.
     */
    @Test
    public fun theSnapshotSurvivesAReopen(): TestResult = runTest {
        val storage = newStorage()
        val edge = MAX_PLAUSIBLE - 1L
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = edge, lastIncludedTerm = edge), byteArrayOf(7, 8, 9))
        val restarted = reopened(storage)
        val stored = restarted.loadSnapshot()
        assertAll(
            { assertNotNull(stored, "a saved snapshot must still be there after a restart") },
            { assertEquals(edge, stored?.meta?.lastIncludedIndex, "lastIncludedIndex must survive exactly") },
            { assertEquals(edge, stored?.meta?.lastIncludedTerm, "lastIncludedTerm must survive exactly") },
            { assertContentEquals(byteArrayOf(7, 8, 9), stored?.state, "and the state bytes with them") },
        )
    }

    /**
     * The **decode-of-absence** half, which none of the four properties above can reach: reopening a
     * medium nothing was ever written to must yield the same empty state [newStorage] promises, not
     * a fabricated one.
     *
     * Every property above writes a value first, so all of them are blind to an adapter whose
     * *decoder* invents state from nothing — the `SELECT … LIMIT 1` over an empty table that returns
     * a default row, the JSON store whose missing document decodes to `SnapshotMeta(0, 0)`, the
     * key-value read that maps a missing key to `0`. Each of those is the sentinel confusion
     * [leaderForTermBeforeAnySave_isNull] and [snapshotAtZeroBaseline_roundTrips] guard on the write
     * path, arriving through the one door only a restart opens. A node that starts believing it
     * established a leader, or holds a snapshot at index 0 with an empty config, adopts the wrong
     * membership baseline on its very first read.
     *
     * **Measured: green under every mutation that LOSES state — including the one that returns a
     * fresh empty storage — and red under the one that FABRICATES it.** That is the split it exists
     * for, and it is worth stating both halves rather than only the flattering one. A reopen rigged
     * to resurrect a cleared vote (`storage.votedFor() ?: NodeId("node-a")`, the shape of an adapter
     * whose `UPDATE … SET voted_for = NULL` is written as an `INSERT` that omits the column) reds
     * exactly one assertion here and one in [termAndVoteSurviveAReopen], and nothing else in the
     * suite. Every property above writes a value first, so none of them can be handed an empty
     * medium to decode — this is the only place that door is opened.
     */
    @Test
    public fun anUnwrittenMediumReopensEmpty(): TestResult = runTest {
        val restarted = reopened(newStorage())
        val term = restarted.term()
        val votedFor = restarted.votedFor()
        val pin = restarted.leaderForTerm()
        val entries = restarted.entries()
        val snapshot = restarted.loadSnapshot()
        assertAll(
            { assertEquals(0L, term, "an unwritten medium must decode to term 0, not a fabricated one") },
            { assertNull(votedFor, "and to no vote") },
            { assertNull(pin, "and to no established leader — absence is not a zero-term sentinel") },
            { assertEquals(emptyList(), entries, "and to an empty log") },
            { assertNull(snapshot, "and to no snapshot — not one at index 0 with an empty config") },
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * [reopen], with its precondition checked **eagerly** — a handle that is not a second handle
     * must fail as a fixture, before any durability assertion is evaluated, rather than surfacing as
     * a green.
     *
     * Returning `this` is the way to make every property in the restart section hold vacuously while
     * looking implemented, and it is the *plausible* mistake rather than a contrived one: an adapter
     * whose writes really are synchronous has no work to do here and `return storage` reads like the
     * honest answer. It is not — it re-reads the object that took the writes, which is what the
     * other 24 properties already do.
     */
    private suspend fun reopened(storage: RaftStorage): RaftStorage {
        val restarted = reopen(storage)
        assertNotSame(
            storage,
            restarted,
            "reopen() handed back the SAME instance it was given, so every durability property below " +
                "would read the object that took the writes — which is what every other property in " +
                "this suite already does. Return a new handle onto the same medium; an in-memory " +
                "implementation " +
                "rebuilds one from its own public read surface.",
        )
        return restarted
    }

    /** Asserts [entries] step by exactly one index at a time, in ascending order. */
    private fun assertContiguousAscending(entries: List<LogEntry>) {
        entries.zipWithNext { previous, next ->
            assertEquals(
                previous.index + 1L,
                next.index,
                "log must be contiguous and ascending: ${previous.index} is followed by ${next.index}",
            )
        }
    }

    private companion object {
        /**
         * Mirrors `RaftEngine.MAX_PLAUSIBLE_TERM` / `MAX_PLAUSIBLE_INDEX` (both `1L shl 60`, both
         * private to the engine, both **inclusive** bounds). Duplicated here for the same reason
         * `RestoredLogValidationTest` / `TermRestoreBoundTest` duplicate it: the engine's constants
         * are not public API, and this suite must pin the exact values those checks admit.
         */
        const val MAX_PLAUSIBLE = 1L shl 60

        /**
         * The term the restart properties write, one below [MAX_PLAUSIBLE].
         *
         * A named constant rather than a literal at each site because it is a **knob**, and the
         * setting that switches the detection off is the comfortable one: a small term round-trips
         * through a 32-bit column and through a `Double` alike, so a restart property written with
         * `7L` proves only that *something* crossed the boundary. `2^60 - 1` needs 60 mantissa bits
         * and survives neither. [termAndVoteSurviveAReopen] carries the argument.
         *
         * `+ 1` is applied at the one site that needs a second, higher term; the ceiling itself is
         * left free for that, which is why this is the ceiling minus one rather than the ceiling.
         */
        const val DURABLE_TERM = MAX_PLAUSIBLE - 1L
    }
}

