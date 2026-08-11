package us.tractat.kuilt.bolt

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The stickiness rule both mmap backends share, pinned where it can be driven exhaustively.
 *
 * `BoltConformanceSuite.aBoltThatCannotFlushReportsDegradedExactlyWhenItPromisedDurability` reaches
 * this class through a backend, but only along one trajectory: its fixture's flush can **never**
 * succeed, so every recovery path — the whole point of "sticky until a later flush covers the same
 * range" — is unreachable from there. This drives the other half.
 */
class DurabilityLedgerTest {

    @Test
    fun aFreshLedgerIsMeetingItsPromise() {
        assertEquals(DurabilityState.AsPromised, DurabilityLedger().state(), "nothing has failed yet")
    }

    @Test
    fun aSuccessfulFlushOverAnUndoubtedArchiveChangesNothing() {
        val ledger = DurabilityLedger()
        ledger.flushSucceeded(0L, 100L)

        assertEquals(DurabilityState.AsPromised, ledger.state(), "a success cannot create a doubt")
    }

    /**
     * A failure widens rather than replacing, and that is the whole reason this is state rather than
     * an event: a flush covers a range, so the frames at risk after two failures are **everything**
     * since the last good flush, not the newest one's.
     *
     * **Mutation receipt:** making `flushFailed` overwrite instead of taking `min`/`max` reddens the
     * first two assertions (the range becomes `[100, 300)`), and leaves the third green — which is
     * why the reason is checked separately rather than folded in.
     */
    @Test
    fun successiveFailuresWidenTheRangeAndKeepTheNewestReason() {
        val ledger = DurabilityLedger()
        ledger.flushFailed(0L, 100L, "first")
        ledger.flushFailed(100L, 300L, "second")
        val degraded = assertIs<DurabilityState.Degraded>(ledger.state())

        assertAll(
            { assertEquals(0L, degraded.fromOffset, "the doubt still starts where it first started") },
            { assertEquals(300L, degraded.toOffset, "and now reaches the newest failure's end") },
            { assertEquals("second", degraded.reason, "the reason is the most recent — the RANGE says how much") },
        )
    }

    /**
     * An out-of-order failure widens **downwards** too.
     *
     * Not hypothetical bookkeeping: a segment roll flushes the retiring segment (offsets below the
     * cursor) *after* the new segment's header has been recorded on some backends, and a page-aligned
     * `msync` reaches below the offset it was asked about by construction.
     */
    @Test
    fun aFailureBelowTheOpenRangeWidensDownwards() {
        val ledger = DurabilityLedger()
        ledger.flushFailed(200L, 300L, "later")
        ledger.flushFailed(0L, 50L, "earlier")
        val degraded = assertIs<DurabilityState.Degraded>(ledger.state())

        assertAll(
            { assertEquals(0L, degraded.fromOffset, "the range grows to cover both") },
            { assertEquals(300L, degraded.toOffset, "in both directions") },
        )
    }

    /**
     * A success clears the doubt **only** if it covers all of it — the asymmetry that points the safe
     * way.
     *
     * This ledger may report a doubt that has since been resolved; it must never report a confidence
     * it has not earned. A flush of the newest frame's pages says nothing about a page ten segments
     * back, and clearing on it would launder exactly the once-and-then-cleared `EIO` this whole signal
     * exists to preserve.
     *
     * **Mutation receipts.** Dropping the covering test from `flushSucceeded` (clear unconditionally)
     * reddens assertions 1 and 2. Inverting it to `>=`/`<=` the wrong way round reddens 4. Making it
     * clear nothing at all — an early `return` — reddens 4 alone, which is the pairing that stops 4
     * from riding on 1–3.
     */
    @Test
    fun aSuccessClearsTheDoubtOnlyWhenItCoversAllOfIt() {
        val partial = DurabilityLedger().apply {
            flushFailed(0L, 300L, "failed")
            flushSucceeded(100L, 300L)
        }
        val alsoPartial = DurabilityLedger().apply {
            flushFailed(0L, 300L, "failed")
            flushSucceeded(0L, 299L)
        }
        val exact = DurabilityLedger().apply {
            flushFailed(0L, 300L, "failed")
            flushSucceeded(0L, 300L)
        }
        val wider = DurabilityLedger().apply {
            flushFailed(100L, 200L, "failed")
            flushSucceeded(0L, 300L)
        }

        assertAll(
            {
                assertEquals(
                    DurabilityState.Degraded(0L, 300L, "failed"),
                    partial.state(),
                    "a success that misses the START of the doubt leaves all of it standing, unshrunk",
                )
            },
            {
                assertEquals(
                    DurabilityState.Degraded(0L, 300L, "failed"),
                    alsoPartial.state(),
                    "and so does one that misses the end by a single byte",
                )
            },
            { assertEquals(DurabilityState.AsPromised, exact.state(), "an exactly covering success clears it") },
            { assertEquals(DurabilityState.AsPromised, wider.state(), "and so does a wider one") },
        )
    }

    /**
     * An **empty** failure range is recorded rather than dropped, and a later covering success clears
     * it.
     *
     * A segment header's flush carries no frame of its own, so its failure has no records to name —
     * but the segment behind it is not durable either, and dropping the fact because the range is
     * empty would lose the only notification of it. Both mapped backends record exactly this at a
     * roll, and both resolve it with the first frame's flush, which starts in the same page or the
     * same mapping.
     */
    @Test
    fun anEmptyRangeIsStillADoubtAndIsStillClearable() {
        val recorded = DurabilityLedger().apply { flushFailed(500L, 500L, "header") }
        val resolved = DurabilityLedger().apply {
            flushFailed(500L, 500L, "header")
            flushSucceeded(500L, 634L)
        }

        assertAll(
            {
                assertEquals(
                    DurabilityState.Degraded(500L, 500L, "header"),
                    recorded.state(),
                    "no frame is at risk YET, but this archive's durability is in doubt from 500",
                )
            },
            { assertEquals(DurabilityState.AsPromised, resolved.state(), "and the next frame's flush settles it") },
        )
    }
}
