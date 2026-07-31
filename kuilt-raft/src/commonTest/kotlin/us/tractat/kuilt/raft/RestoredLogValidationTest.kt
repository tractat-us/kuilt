@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for #1887: the restore must validate **everything** it reads from [RaftStorage], not just
 * the term.
 *
 * #1855 bounded `state.currentTerm = checkedRestoredTerm(storage.term())`. Every other value the
 * init-restore reads was still adopted verbatim — `SnapshotMeta.lastIncludedIndex`/`lastIncludedTerm`,
 * and each restored [LogEntry]'s `index`/`term`. Two distinct faults follow:
 *
 * - **§5.4.1 Leader Completeness.** `RaftState.lastLogPosition` is built from the snapshot baseline or
 *   the last entry, and §5.4.1 orders positions by `(term, index)` **lexicographically**. A node restored
 *   with a huge `lastLogTerm` is therefore unbeatable in every election: `isLogUpToDate` tells every
 *   honest peer *its own* log is less current, so all of them grant the vote. The node becomes leader
 *   carrying a corrupt log and overwrites the cluster's. This is #1832 (AppendEntries) and #1868
 *   (InstallSnapshot) reached through storage instead of the wire — and #1855's bound does **not** cover
 *   it, because a `RequestVote`'s `wireTerm` is the *candidate's* term, never its `lastLogTerm`.
 * - **Positional log math.** `RaftLogMath` resolves `entryAt(i)` as `log[i - snapshotIndex - 1]`, valid
 *   only while the restored list begins at `snapshotIndex + 1` and increases by 1 with no gaps. A gap
 *   silently returns the **wrong entry for every lookup** above it — the failure
 *   `SnapshotRecoveryTest.nodeRestart_filtersCompactedPrefix_whenLogDiscardCrashedMidWay` already pins
 *   for the compacted-*prefix* shape, which the `entries(snapshotIndex + 1)` filter handles; nothing
 *   pinned a gap the filter cannot see.
 *
 * ## Reachability
 *
 * Identical to #1855's: kuilt ships **no** durable [RaftStorage] ([InMemoryRaftStorage] is the only
 * implementation in the library), and `RaftStorageConformanceSuite` constrains none of these fields — it
 * checks that entries round-trip and that `truncateFrom`/`entries(fromIndex)` filter correctly, nothing
 * about ranges or contiguity. Every persistent adapter is consumer code that can pass the whole TCK and
 * still return garbage. No attacker and no pre-fix binary required.
 *
 * ## Disposition: refuse to start
 *
 * Same as #1855, and deliberately not the "truncate the corrupt suffix back to the snapshot baseline"
 * alternative the issue floats. That repair is safe *iff* the truncation point is at or below
 * `commitIndex` — but `commitIndex` is itself seeded from the same `loadSnapshot()` already deemed
 * untrustworthy, so the safety predicate is computed from the corrupt input it is meant to police. A node
 * that refuses to start on a poisoned durable **term** but silently repairs a poisoned **entry term**
 * would also be incoherent.
 *
 * ## Test discipline
 *
 * [checkedRestoredTerm] runs **first**, so every case below seeds a *plausible* persisted term — otherwise
 * the node would refuse on #1855's bound and the check under test would never execute (a vacuous pass).
 */
internal class RestoredLogValidationTest {

    /** Mirrors `RaftEngine.MAX_PLAUSIBLE_TERM` / `MAX_PLAUSIBLE_INDEX` (private); both bounds are inclusive. */
    private val maxPlausible = 1L shl 60

    /** A term high enough that every seeded entry term below is comfortably inside `0..currentTerm`. */
    private val restoredTerm = 5L

    // ── SnapshotMeta.lastIncludedTerm ─────────────────────────────────────────

    @Test
    fun implausibleSnapshotLastIncludedTerm_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 7L, lastIncludedTerm = Long.MAX_VALUE), byteArrayOf(1))

        val failure = awaitRestoreFailure(storage)

        assertAll(
            { assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure") },
            {
                assertTrue(
                    failure?.message.orEmpty().contains(Long.MAX_VALUE.toString()),
                    "the diagnostic must name the offending lastIncludedTerm: ${failure?.message}",
                )
            },
        )
    }

    @Test
    fun negativeSnapshotLastIncludedTerm_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 7L, lastIncludedTerm = -1L), byteArrayOf(1))

        val failure = awaitRestoreFailure(storage)

        assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure")
    }

    // ── SnapshotMeta.lastIncludedIndex ────────────────────────────────────────

    @Test
    fun implausibleSnapshotLastIncludedIndex_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.saveSnapshot(
            SnapshotMeta(lastIncludedIndex = maxPlausible + 1L, lastIncludedTerm = restoredTerm),
            byteArrayOf(1),
        )

        val failure = awaitRestoreFailure(storage)

        assertAll(
            { assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure") },
            {
                assertTrue(
                    failure?.message.orEmpty().contains((maxPlausible + 1L).toString()),
                    "the diagnostic must name the offending lastIncludedIndex: ${failure?.message}",
                )
            },
        )
    }

    @Test
    fun negativeSnapshotLastIncludedIndex_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = -1L, lastIncludedTerm = restoredTerm), byteArrayOf(1))

        val failure = awaitRestoreFailure(storage)

        assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure")
    }

    /**
     * The other direction for the metadata bounds: both ceilings are **inclusive**, matching
     * `MAX_PLAUSIBLE_TERM`'s and `MAX_PLAUSIBLE_INDEX`'s own `>` tests on the wire lanes. A snapshot sitting
     * exactly at the index ceiling is admissible and must still start.
     */
    @Test
    fun snapshotMetadataExactlyAtTheCeilings_stillStarts() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.saveSnapshot(
            SnapshotMeta(lastIncludedIndex = maxPlausible, lastIncludedTerm = restoredTerm),
            byteArrayOf(1),
        )

        val failure = awaitRestoreFailure(storage)

        assertNull(failure, "metadata exactly at the ceilings is admissible, matching the wire bounds")
    }

    // ── LogEntry.term ─────────────────────────────────────────────────────────

    /**
     * The negative half of the entry-term bound. Deliberately negative rather than huge: a term *above*
     * `MAX_PLAUSIBLE_TERM` is necessarily also above `state.currentTerm` (which [checkedRestoredTerm] has
     * already bounded), so only a negative term pins the range half independently of the domination half
     * below.
     */
    @Test
    fun negativeRestoredEntryTerm_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.appendEntries(listOf(LogEntry(index = 1L, term = -1L, command = byteArrayOf(1))))

        val failure = awaitRestoreFailure(storage)

        assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure")
    }

    /**
     * The §5.4.1 lever itself: an entry carrying a term the node never reached. `storage.term() >= max(log
     * entry terms)` is a real invariant of every append path — `persistTermAndVote` is storage-first and
     * runs before `storage.appendEntries`, so no node can hold a term-`N+1` entry while recorded at term
     * `N` (the same invariant `CheckQuorumTest` already seeds for, citing #1832).
     *
     * Note the term here is perfectly *plausible* — well inside `MAX_PLAUSIBLE_TERM` — so no range bound,
     * on the wire or at restore, sees it. Only the comparison against the node's own persisted term does.
     */
    @Test
    fun restoredEntryTermAbovePersistedTerm_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.appendEntries(listOf(LogEntry(index = 1L, term = restoredTerm + 1L, command = byteArrayOf(1))))

        val failure = awaitRestoreFailure(storage)

        assertAll(
            { assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure") },
            {
                assertTrue(
                    failure?.message.orEmpty().contains("term=${restoredTerm + 1L}"),
                    "the diagnostic must name the offending entry term: ${failure?.message}",
                )
            },
        )
    }

    // ── LogEntry.index ────────────────────────────────────────────────────────

    /**
     * The index-half bound, pinned independently of contiguity. Seeding a snapshot exactly at the index
     * ceiling makes `snapshotIndex + 1` the *contiguous* position for an entry one past the ceiling — so
     * this shape passes the contiguity check and can only be caught by the range bound itself.
     */
    @Test
    fun implausibleRestoredEntryIndex_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.saveSnapshot(
            SnapshotMeta(lastIncludedIndex = maxPlausible, lastIncludedTerm = restoredTerm),
            byteArrayOf(1),
        )
        storage.appendEntries(listOf(LogEntry(index = maxPlausible + 1L, term = restoredTerm, command = byteArrayOf(1))))

        val failure = awaitRestoreFailure(storage)

        assertAll(
            { assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure") },
            {
                assertTrue(
                    failure?.message.orEmpty().contains((maxPlausible + 1L).toString()),
                    "the diagnostic must name the offending entry index: ${failure?.message}",
                )
            },
        )
    }

    // ── Contiguity ────────────────────────────────────────────────────────────

    /**
     * A gap *inside* the restored log. `entries(snapshotIndex + 1)` cannot see this one — the filter only
     * removes the compacted prefix — so without an explicit check the list `[1, 3]` is adopted and every
     * positional read above the gap resolves the wrong slot.
     */
    @Test
    fun nonContiguousRestoredLog_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 1L, command = byteArrayOf(1)),
                LogEntry(index = 3L, term = 1L, command = byteArrayOf(3)),
            ),
        )

        val failure = awaitRestoreFailure(storage)

        assertAll(
            { assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure") },
            {
                assertTrue(
                    failure?.message.orEmpty().contains("contiguous"),
                    "the diagnostic must name the contiguity violation: ${failure?.message}",
                )
            },
        )
    }

    /**
     * The head-gap shape of the same rule: the restored log must begin at exactly `snapshotIndex + 1`.
     * A log starting *above* the baseline leaves `entryAt(snapshotIndex + 1)` resolving an entry that is
     * not there — the mirror image of the compacted-prefix bug, which starts *below* it.
     */
    @Test
    fun restoredLogStartingAboveSnapshotBaseline_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 7L, lastIncludedTerm = 3L), byteArrayOf(1))
        storage.appendEntries(listOf(LogEntry(index = 9L, term = 3L, command = byteArrayOf(9))))

        val failure = awaitRestoreFailure(storage)

        assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure")
    }

    // ── Term monotonicity (§5.3) ──────────────────────────────────────────────

    /**
     * §5.3: terms in a log never decrease. Both terms here are inside `0..currentTerm`, and the indices are
     * contiguous, so this shape passes every other check — it is the only one that catches a log whose
     * terms run backwards, which would break the binary-search assumptions §5.3 backup relies on.
     */
    @Test
    fun nonMonotonicRestoredEntryTerms_refusesToStart() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.appendEntries(
            listOf(
                LogEntry(index = 1L, term = 3L, command = byteArrayOf(1)),
                LogEntry(index = 2L, term = 1L, command = byteArrayOf(2)),
            ),
        )

        val failure = awaitRestoreFailure(storage)

        assertAll(
            { assertTrue(failure is CorruptDurableStateException, "expected CorruptDurableStateException, got: $failure") },
            {
                assertTrue(
                    failure?.message.orEmpty().contains("decrease"),
                    "the diagnostic must name the monotonicity violation: ${failure?.message}",
                )
            },
        )
    }

    // ── The other direction ───────────────────────────────────────────────────

    /**
     * A well-formed durable state — a snapshot baseline plus a contiguous, non-decreasing tail whose terms
     * are all at or below the persisted term — must still start. Guards the whole check set against
     * over-rejection; `SnapshotRecoveryTest` covers the same shape end-to-end through commit and replay.
     */
    @Test
    fun wellFormedRestoredSnapshotAndLog_startsNormally() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(restoredTerm, null)
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 7L, lastIncludedTerm = 3L), byteArrayOf(1))
        storage.appendEntries(
            listOf(
                LogEntry(index = 8L, term = 3L, command = byteArrayOf(8)),
                LogEntry(index = 9L, term = restoredTerm, command = byteArrayOf(9)),
            ),
        )

        val failure = awaitRestoreFailure(storage)

        assertNull(failure, "a well-formed durable snapshot + log must restore without complaint")
    }
}
