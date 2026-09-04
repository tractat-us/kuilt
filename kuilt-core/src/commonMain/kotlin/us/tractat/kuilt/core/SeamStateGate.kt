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
 * ## One kind of `Torn` — the latch keys on the DECISION, and `Torn` is terminal
 *
 * `Torn` is **unconditionally terminal** (see [SeamState]): its only producer is [tear] — the local
 * close *decision* — plus self-driven transport death. Derived rollups never publish `Torn`; a
 * fully-degraded multipath seam (all plies/tiers currently down) publishes recoverable [SeamState.Weaving]
 * through [update], not a revivable `Torn` (#1367). The gate still latches on the *decision*, not the
 * value: [tear] latches, and thereafter every [update] no-ops. This is what guards the close-vs-pump
 * write race — a late derived `Woven`/`Weaving` [update] (an in-flight rollup resuming after the
 * pump-cancel) must not clobber a [tear]-latched `Torn`.
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
 *
 * ## Why this is `public`, and in `:kuilt-core` (#1803)
 *
 * Stated rather than left to inference, because this type spent its first year `internal` and the
 * cost is measured. Every `Seam` implementation publishes a `SeamState`, and the ones that own their
 * own state flow rather than composing a core seam live *outside* this module: `:kuilt-multipeer`
 * (twice), `:kuilt-nearby`, `:kuilt-nw`, `:kuilt-webrtc`, plus any out-of-tree fabric.
 * (`:kuilt-websocket` is not among them — it composes [LinkSeam]/[MeshSeam], which is why it never
 * had to solve this at all, and is the shape to prefer.) While the remedy was unreachable to them,
 * four such fabrics hand-rolled their own latch and **three wrote precisely the check-then-set this
 * KDoc bans**, three lines below a comment describing the race. The remedy was unreachable, not
 * ignored; the sibling lesson is written up on [pumpIn], whose helper was made `public` for exactly
 * this reason after the same thing happened to it.
 *
 * So the visibility is load-bearing, not incidental: an `internal` correctness primitive whose
 * defect class lives in every downstream module is a known-failed design. `:kuilt-core` is the
 * lowest module every fabric already depends on.
 *
 * ### What being `public` does NOT buy
 *
 * Reachability is not adoption. Nothing here stops the next fabric declaring a bare
 * `MutableStateFlow<SeamState>` and racing it — that needs a *lexical* guard ("a `MutableStateFlow`
 * field in a lock-owning class must be a `SeamStateGate`"), which #1803 records as viable only
 * *after* this packaging step and which is **not yet written**. Until it is, the enforcement for a
 * new fabric is `SeamConformanceSuite.stateStaysTornAfterClose` (a deterministic ordering check,
 * necessary but not sufficient) plus review. Do not read this paragraph as a promise that the class
 * is now closed.
 *
 * ### When you do NOT need this
 *
 * Two shapes are already safe and should not be churned onto the gate:
 *  - **One shared mutual-exclusion primitive** covering *every* write to the state flow, terminal
 *    and derived alike (`NwSeam` takes both under its own `lock`). The gate would be redundant.
 *  - **A single-threaded target**, where a check-then-act has no window because nothing can run
 *    between the read and the write (`WebRTCPeerLink` on `wasmJs`, which documents exactly this).
 *
 * What the gate is *for* is the third shape: two or more writers on genuinely concurrent threads
 * with no shared lock between them — a transport callback racing `close()`.
 */
public class SeamStateGate(initial: SeamState) {

    private val lock = reentrantLock()

    // `latched` and the `_state` write are mutated ONLY under `lock`, so the latch-check and the
    // publish are a single atomic step — the property every ad-hoc `if (!closed)` flag lacked.
    private var latched = false
    private val _state = MutableStateFlow(initial)

    /** The seam's live lifecycle. A seam exposes this directly as its `state`. */
    public val state: StateFlow<SeamState> = _state.asStateFlow()

    /**
     * The normal / derived write path — a pump publishing an aggregate (e.g. a rollup of
     * [SeamState.Woven]/[SeamState.Weaving]). A no-op once [tear] has latched, so an in-flight derived
     * write can never overwrite the terminal `Torn`. Derived rollups publish only recoverable states
     * ([SeamState.Woven]/[SeamState.Weaving]); the terminal `Torn` comes solely from [tear].
     */
    public fun update(next: SeamState) {
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
    public fun tear(reason: CloseReason): Boolean = lock.withLock {
        if (latched) return false
        latched = true
        _state.value = SeamState.Torn(reason)
        true
    }
}
