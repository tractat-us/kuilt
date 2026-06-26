package us.tractat.kuilt.warp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.crdt.Quilted

/**
 * A converging result backed by a join-semilattice — the observation side of
 * monotone (convergent) query execution in `:kuilt-warp`.
 *
 * Think of it as a running tally that can only grow: contributions arrive from any
 * number of peers in any order, duplicates are absorbed automatically, and the
 * result refines monotonically toward the least upper bound of all contributions
 * received so far.
 *
 * **Threshold reads** ([awaitThreshold]) are the LVar-style observation primitive.
 * A caller suspends until the result first satisfies a monotone predicate, then
 * receives that stable snapshot. Because the lattice can only grow, once a threshold
 * is crossed it stays crossed — the result cannot fall back below it, so the returned
 * snapshot is permanently valid.
 *
 * ## Thread safety
 *
 * [contribute] is thread-safe. The internal [MutableStateFlow] uses a CAS loop so
 * concurrent callers from multiple threads or coroutines converge correctly without
 * external synchronisation. [awaitThreshold] and [state] collection are safe across
 * multiple concurrent consumers.
 *
 * ## Lattice contract
 *
 * [L] must satisfy the three laws that [Quilted.piece] requires:
 * - **Idempotent** — `a.piece(a) == a`
 * - **Commutative** — `a.piece(b) == b.piece(a)`
 * - **Associative** — `a.piece(b.piece(c)) == (a.piece(b)).piece(c)`
 *
 * These laws guarantee convergence regardless of contribution order, duplication, or
 * delivery gaps — the same properties that make a kuilt fabric safe to use as a
 * transport.
 *
 * @param L the lattice type — must be [Quilted] (idempotent/commutative/associative join).
 * @param initial the lattice bottom — the result before any contributions arrive.
 */
public class IncrementalResult<L : Quilted<L>>(initial: L) {

    private val _state: MutableStateFlow<L> = MutableStateFlow(initial)

    /** The live converging value — refines monotonically as contributions arrive. */
    public val state: StateFlow<L> get() = _state.asStateFlow()

    /**
     * Join [delta] into the current result.
     *
     * Idempotent, commutative, and thread-safe. Applying the same delta twice, or
     * applying deltas in any order, produces the same converged value — the lattice
     * laws ([Quilted.piece]) guarantee it. The [state] value never decreases after
     * this call.
     */
    public fun contribute(delta: L) {
        _state.update { current -> current.piece(delta) }
    }

    /**
     * Suspend until the current result first satisfies [predicate], then return that
     * stable snapshot.
     *
     * If [predicate] is already satisfied when called, returns immediately without
     * suspending.
     *
     * The predicate should be **monotone**: once it returns `true` for a lattice value
     * `v`, it should return `true` for every `v'` where `v' = v.piece(delta)` for any
     * delta. A monotone predicate holds permanently once crossed, so the returned
     * snapshot is stable — the caller need not recheck it.
     *
     * Safe to call from multiple concurrent coroutines; each will receive the first
     * value satisfying its predicate independently.
     *
     * @param predicate a monotone predicate over the lattice value.
     * @return the first lattice value that satisfies [predicate].
     */
    public suspend fun awaitThreshold(predicate: (L) -> Boolean): L =
        state.first(predicate)
}
