package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.LogPosition
import kotlin.test.Test
import kotlin.test.assertAll
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the §5.4.1 "at least as up-to-date" ordering on [LogPosition]:
 * higher term wins; equal term → higher index wins; equal position ⇒ not strictly greater.
 *
 * The type itself is the real fix — `isLogUpToDate` takes two [LogPosition]s, so transposing
 * term/index at a call site is a compile error instead of a silent election-safety violation.
 */
class LogPositionTest {

    @Test
    fun higherTermWins_regardlessOfIndex() {
        val ours = LogPosition(term = 2L, index = 100L)
        val candidate = LogPosition(term = 3L, index = 1L)
        assertAll(
            { assertTrue(candidate > ours) },
            { assertTrue(candidate >= ours) },
            { assertFalse(ours > candidate) },
            { assertFalse(ours >= candidate) },
        )
    }

    @Test
    fun equalTerm_higherIndexWins() {
        val shorter = LogPosition(term = 3L, index = 5L)
        val longer = LogPosition(term = 3L, index = 6L)
        assertAll(
            { assertTrue(longer > shorter) },
            { assertTrue(longer >= shorter) },
            { assertFalse(shorter >= longer) },
        )
    }

    @Test
    fun equalPositions_atLeastAsUpToDate_butNotStrictlyGreater() {
        val a = LogPosition(term = 3L, index = 5L)
        val b = LogPosition(term = 3L, index = 5L)
        assertAll(
            { assertTrue(a >= b) },
            { assertFalse(a > b) },
            { assertTrue(a.compareTo(b) == 0) },
        )
    }

    @Test
    fun termDominatesIndex_swapWouldInvertTheVerdict() {
        // The exact shape a term/index transposition would corrupt: candidate has a huge index
        // but a stale term. §5.4.1 must deny — a swapped comparison would grant.
        val ours = LogPosition(term = 3L, index = 10L)
        val staleCandidate = LogPosition(term = 2L, index = 99L)
        assertFalse(staleCandidate >= ours)
    }

    @Test
    fun emptyLog_zeroPosition_isLeastUpToDate() {
        val empty = LogPosition(term = 0L, index = 0L)
        assertAll(
            { assertTrue(empty >= LogPosition(term = 0L, index = 0L)) },
            { assertTrue(LogPosition(term = 3L, index = 5L) > empty) },
            { assertFalse(empty >= LogPosition(term = 0L, index = 1L)) },
        )
    }
}
