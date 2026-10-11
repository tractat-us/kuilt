package us.tractat.kuilt.quilter

import kotlinx.coroutines.flow.StateFlow

internal const val STALLED_CALLBACK: String =
    "applyCompaction did not update state; wire it to the Quilter.apply that publishes state"

/**
 * Fails a GC coordinator's compact-until-null loop when its `applyCompaction` callback left
 * [state] at [before].
 *
 * Each coordinator ([RgaGcCoordinator], [FugueGcCoordinator], [MovableTreeGcCoordinator]) re-reads
 * [state] after every pass, and the CRDT's `compact` is a function of that state and the cut. A
 * callback that does not update [state] synchronously — a no-op, one that launches the apply
 * elsewhere, or one wired to a different `Quilter` than the one publishing [state] — hands the
 * next pass the same state, so `compact` returns the same op and the loop never ends (#2816).
 *
 * With this check, a callback that does what the contract asks ([Quilter.apply], a join into
 * [state]) makes the loop terminate: every pass strictly raises [state] in the lattice, and the
 * joined compaction deltas name only ids already in the state the evaluation started from, so
 * there are finitely many states to climb through. Concurrent writers can keep the loop busy, but
 * only by supplying new work.
 */
internal fun <S> checkCompactionApplied(state: StateFlow<S>, before: S) {
    check(state.value != before) { STALLED_CALLBACK }
}
