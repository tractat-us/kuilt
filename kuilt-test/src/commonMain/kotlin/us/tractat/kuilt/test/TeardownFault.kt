package us.tractat.kuilt.test

import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/**
 * What a [FaultySeam]'s **teardown** should do — a different axis from [FaultProfile], which
 * describes what happens to *frames*.
 *
 * Before #2501 [FaultySeam.close] was an unconditional passthrough to the delegate, so the one
 * component in the tree whose job is to make a transport misbehave could not make a teardown slow,
 * suspend, or fail. Every obligation that only becomes falsifiable when `close` misbehaves —
 * `Room.leave`'s idempotency and its no-minted-cancellation obligation (#1826), reached through
 * `SeamRoom.leave`'s `seam.close(...)` — was therefore unreachable rather than merely unwritten.
 *
 * ## Why a separate knob rather than a [FaultProfile] arm
 *
 * [FaultProfile]'s model is **per-frame** evaluation carrying a [Direction]. A teardown has neither
 * a frame nor a direction, so a teardown arm would be three dead branches in `FaultState`'s outbound
 * / inbound / inbound-delay evaluators plus a teardown-extraction walker alongside
 * `inboundDelay` for [FaultProfile.Composite] — conflating two axes to reuse one sealed hierarchy.
 *
 * Honouring [FaultProfile.DelayAll] inside `close` was the other option and is worse: it silently
 * changes teardown at every existing [FaultySeam] call site, and a delay can only make a close
 * *slow*, never *fail*, so the minted-cancellation obligation would still have no failure to
 * classify.
 *
 * Orthogonal and defaulting to [None] means **no existing call site changes behaviour at all**, and
 * slow and failing teardowns compose freely with any frame profile.
 *
 * **Determinism:** [Slow] suspends through [kotlinx.coroutines.delay], so
 * [kotlinx.coroutines.test.runTest] controls it in virtual time — no wall clock is consumed.
 */
public sealed interface TeardownFault {

    /** Teardown is delegated straight through — the pre-#2501 behaviour, and the default. */
    public data object None : TeardownFault

    /**
     * Suspend for [delay], **then** delegate the close.
     *
     * Models a transport whose teardown does real work before it returns — a flush, a goodbye
     * frame, a socket shutdown that waits for its peer. This is the arm that makes a
     * `withTimeout`-bounded `close` reachable, which is the #2286 mechanism: the caller sees a
     * `TimeoutCancellationException` its own job never asked for.
     */
    public data class Slow(val delay: Duration) : TeardownFault

    /**
     * Delegate the close, **then** throw [cause].
     *
     * **Delegate-first is deliberate.** It models the common real shape — the close threw while
     * flushing, but the link underneath is dead anyway — and it leaves every other invariant of the
     * harness settled, so a test can keep asserting about rosters, peers and seam state after the
     * failure instead of unwinding into an unknown half-closed world. The alternative (throw
     * without delegating) models a transport whose close is a pure no-op failure, which is both
     * rarer and strictly less useful to assert against.
     *
     * ## [cause] must not be a [CancellationException], and that is enforced
     *
     * Injecting a seam-minted cancellation would test whether a consumer **defends against a
     * contract-violating [us.tractat.kuilt.core.Seam]** — a strictly stronger demand than the
     * contract makes today. `Seam.close` itself carries the no-minting obligation (#1826), and
     * `SeamRoom.leave` calls `seam.close(...)` unguarded, so such an arm would ship a suite that
     * fails rather than a guard that holds. Whether `Room.leave` *should* defend against it is a
     * real question, deliberately deferred: see #2518.
     */
    public data class Fails(val cause: Throwable) : TeardownFault {
        init {
            require(cause !is CancellationException) {
                "TeardownFault.Fails must not inject a CancellationException: a seam that mints one " +
                    "from close() is already violating Seam.close's own obligation (#1826), so this " +
                    "arm would be asking consumers to defend against a non-conforming Seam rather " +
                    "than exercising a conforming one. See #2518. Got: $cause"
            }
        }
    }
}
