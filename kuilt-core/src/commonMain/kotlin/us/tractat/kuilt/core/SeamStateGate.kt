package us.tractat.kuilt.core

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Terminal-latching holder for a seam's [SeamState] — the primitive that makes the
 * "lost terminal transition" bug class unrepresentable.
 *
 * ## The problem it solves
 *
 * Every seam publishes its lifecycle through a `MutableStateFlow<SeamState>`. When more than one
 * coroutine writes that flow — a `close()` path racing an internal pump that *derives* the aggregate
 * state — a `close()` that publishes the terminal [SeamState.Torn] can be immediately overwritten by
 * an in-flight pump write, wedging the state at a non-terminal value **forever**. Every consumer
 * doing `state.first { it is Torn }` then hangs. Guarding each pump write with a plain
 * `if (!closed) …` flag does **not** fix it: the flag read and the flow write are not atomic, so a
 * pump can read `closed == false`, be preempted by a complete `close()`, then resume and clobber the
 * terminal write. Check-a-flag-then-write **is** the race.
 *
 * [SeamStateGate] fuses the close decision and the flow write into one atomic step under a single
 * [reentrantLock]: once [tear] has latched, no later [update] can move the state off `Torn`, so
 * teardown ordering (cancel before/after publishing, join or not) becomes irrelevant to correctness.
 *
 * ## Two kinds of `Torn` — the latch keys on the DECISION, not the value
 *
 * A seam may *derive* `Torn` (e.g. a composite whose every ply is currently torn) through [update];
 * that `Torn` is **revivable** — a later attach can bring the aggregate back to `Woven`. Only [tear]
 * — the local close *decision* — latches. So [update] is free to publish and later leave `Torn`; it
 * is a plain derived write. This is why the gate latches on [tear], never on the `Torn` *value*: a
 * "once any `Torn`, freeze" rule would wedge a legitimately-revivable multipath seam.
 *
 * ## Thread-safety
 *
 * Correct under a **multi-threaded** dispatcher: the latch-check and the flow write are one atomic
 * critical section. The lock is a real mutual-exclusion primitive, never dispatcher confinement, and
 * nothing suspends inside it ([MutableStateFlow]'s `value` setter is non-suspending). The lock is a
 * leaf — the gate never calls back out while holding it — so it composes safely inside a seam's own
 * lock (a seam may [tear] while holding its roster lock to keep "collapse roster, then publish Torn"
 * atomic).
 *
 * ## Quiesce guidance (Option B of the design doc)
 *
 * With the gate in place, a `close()` never *needs* to join its internal pumps for **state**
 * correctness — a late pump [update] is a harmless no-op. Join a pump only when a specific *resource*
 * genuinely needs quiescence before release (e.g. a write pump that must drain before a socket
 * closes); it is a per-site optimization, not the correctness mechanism.
 */
internal class SeamStateGate(initial: SeamState) {

    private val lock = reentrantLock()

    // `latched` and the `_state` write are mutated ONLY under `lock`, so the latch-check and the
    // publish are a single atomic step — the property every ad-hoc `if (!closed)` flag lacked.
    private var latched = false
    private val _state = MutableStateFlow(initial)

    /** The seam's live lifecycle. A seam exposes this directly as its `state`. */
    val state: StateFlow<SeamState> = _state.asStateFlow()

    /**
     * The normal / derived write path — a pump publishing an aggregate (e.g. a rollup). A no-op once
     * [tear] has latched, so an in-flight derived write can never overwrite the terminal `Torn`.
     * A derived [SeamState.Torn] passed here is **not** latched and may later be superseded.
     */
    fun update(next: SeamState) {
        lock.withLock {
            if (latched) return
            _state.value = next
        }
    }

    /**
     * The single-shot terminal transition — the close *decision*. Publishes [SeamState.Torn] with
     * [reason] and latches the gate so every subsequent [update] no-ops. Returns `true` for the one
     * winning caller and `false` if the gate was already torn — subsuming each seam's ad-hoc
     * single-shot `closed` atomic (migrating seams delete a field rather than gain one).
     */
    fun tear(reason: CloseReason): Boolean = lock.withLock {
        if (latched) return false
        latched = true
        _state.value = SeamState.Torn(reason)
        true
    }
}
