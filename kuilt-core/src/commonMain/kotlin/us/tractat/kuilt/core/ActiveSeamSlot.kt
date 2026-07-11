package us.tractat.kuilt.core

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * A self-healing single-active-session slot for a [Loom]/factory that hosts **one**
 * live [Seam] at a time.
 *
 * In plain terms: some fabrics — Apple Multipeer, a raw radio link — can only drive one
 * session per device. Such a factory must reject a second `weave` while a session is live,
 * yet become reusable the instant that session dies, without the caller having to force-quit
 * or manually reset anything. [ActiveSeamSlot] is that guard, packaged once.
 *
 * ## Torn-aware guard (the structural fix)
 *
 * The naive version of this guard is `check(slot == null)` plus a hand-wired `onTerminated`
 * callback that the seam fires on terminal death to clear the slot. That side-channel is a
 * standing obligation every future single-session fabric can forget — and a fabric that forgets
 * it **wedges**: the slot stays occupied forever, so the factory can never be reused.
 *
 * [ActiveSeamSlot] removes the obligation. A claim succeeds when the slot is empty **or** its
 * current occupant has latched [SeamState.Torn] — the next [occupy] consults the seam's *own*
 * terminal state rather than a side-channel. Because a seam latches `Torn` on self-driven death
 * (a remote drop, a failed join) as well as on an explicit `close()`, the slot heals on the next
 * claim with no per-fabric wiring. A fabric that latches `Torn` but does no explicit slot release
 * is still correctly reusable. (Mirrors `MuxClientLoom`, which treats a `Torn` base seam as free.)
 *
 * ## Ordering — null the slot *before* native teardown
 *
 * [grabAndRelease] atomically clears the slot and returns the previous occupant so the caller can
 * run native teardown (`disconnect()` / `mc_session_close`) *after* the slot is already empty. A
 * re-entrant terminal callback that races the teardown therefore sees an empty (or already-moved-on)
 * slot and short-circuits.
 *
 * ## Thread-safety
 *
 * All slot state is guarded by an atomicfu [reentrantLock] — correct under a genuinely
 * multi-threaded dispatcher, not single-thread confinement. [occupy] performs the guard check and
 * the install as one atomic operation, so two concurrent claims can never both succeed. The
 * `build` lambda runs under the lock; it must not suspend (fabric session construction is
 * synchronous native work, never a coroutine suspension point).
 *
 * @param occupiedMessage the [IllegalStateException] message thrown when a claim is rejected
 *   because a live (non-`Torn`) seam already occupies the slot.
 */
public class ActiveSeamSlot(
    private val occupiedMessage: String = "already has an active session",
) {
    private val lock = reentrantLock()
    private var occupant: Seam? = null

    /**
     * Claims the slot, builds the session, and installs it — as one atomic operation.
     *
     * Succeeds when the slot is empty or its occupant has latched [SeamState.Torn]; otherwise
     * throws [IllegalStateException] with [occupiedMessage] (a live session already holds the
     * slot). If [build] throws, the slot is left exactly as it was — a failed session open never
     * wedges the slot — and the exception propagates unchanged (cancellation is preserved: nothing
     * is caught).
     *
     * @param build constructs the [Seam] to install. Runs under the guard lock, so it must be
     *   synchronous (no suspension) and must not re-enter this slot.
     * @return the freshly installed seam.
     */
    public fun <T : Seam> occupy(build: () -> T): T =
        lock.withLock {
            val current = occupant
            check(current == null || current.state.value is SeamState.Torn) { occupiedMessage }
            build().also { occupant = it }
        }

    /**
     * Clears the slot **only if** [seam] is its current occupant. A stale release from an
     * already-replaced session is a no-op, so it can never evict a newer live session. Idempotent.
     */
    public fun release(seam: Seam): Unit =
        lock.withLock {
            if (occupant === seam) occupant = null
        }

    /**
     * Atomically empties the slot and returns the previous occupant (or `null` if already empty).
     *
     * The slot is nulled **before** the caller runs native teardown, so a re-entrant terminal
     * callback triggered by that teardown finds the slot empty and short-circuits. Idempotent.
     */
    public fun grabAndRelease(): Seam? =
        lock.withLock {
            occupant.also { occupant = null }
        }
}
