package us.tractat.kuilt.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * What a `flush` found: whether a close from this end can still throw away the frames you already sent.
 *
 * A send that returned has only been *accepted*. It may still be waiting in a queue on this device,
 * and a `close` straight after it can throw it away. `flush` is how a caller waits for that queue to
 * pass the frames it already sent, without paying that wait on every close.
 *
 * **[Drained] never means the other peer received anything.** See it for the one thing it does say. A
 * caller that needs receipt needs an acknowledgement of its own.
 *
 * Every arm except [Drained] means "not proven". None of them says a frame was lost, and [TimedOut]
 * in particular leaves every frame queued and still on its way.
 *
 * @see Seam.flush
 * @see us.tractat.kuilt.core.fabric.Connection.flush
 */
public sealed interface FlushOutcome {
    /**
     * A `close` of this seam or connection that follows goes out behind every send accepted before
     * `flush` was called, so it cannot be what throws those frames away on this device. That is the whole
     * promise, and three things stay outside it:
     *
     * - **The frames may not have left this process.** They can still be in the transport library's own
     *   queue: a WebSocket frame can sit in Ktor's. So close the seam itself. Closing the `HttpClient`,
     *   cancelling the session or exiting straight after can still lose them (read from Ktor's source,
     *   not run).
     * - **The link can still die on the way**, and the far side can still crash before reading.
     * - **A far side that is still sending can lose them after they arrive.** Its own send fails on the
     *   link your close ended, that tears its seam, and a torn seam stops reading what it had not got to.
     *   Measured with a slow consumer sending 1 frame per 100 ms: lost frames in 32 of 32 WebSocket
     *   trials, and 0 of 24 with nothing coming back. TCP lost them 6 of 6. That is macOS loopback, and how
     *   often it happens depends on the machine: a Linux CI runner's TCP trials lost nothing, 3 of 3.
     */
    public data object Drained : FlushOutcome

    /**
     * This implementation has no way to tell, so nothing was proven and nothing should be inferred.
     *
     * The interface default: a [Seam] or [us.tractat.kuilt.core.fabric.Connection] implemented member by
     * member before `flush` existed answers this, because the alternative is reporting success for a
     * queue it never looked at.
     *
     * **Class delegation is different.** `Seam by inner`, once recompiled against this version, forwards
     * `inner`'s answer, and that can be [Drained]. That is right for a decorator with no queue of its own,
     * and wrong for one with a queue, which has to override `flush` (see [Seam.flush]).
     */
    public data object Unsupported : FlushOutcome

    /**
     * The barrier was not confirmed within the timeout. The link stays up, nothing is dropped, and the
     * frames carry on: call `flush` again, or close and accept that they may be lost.
     */
    public data object TimedOut : FlushOutcome

    /**
     * The link ended, or failed, before the barrier was confirmed — including a seam that was already
     * [SeamState.Torn] when `flush` was called. Some frames may have left and some may not.
     *
     * A value rather than a throw, so a teardown path can branch on it without a guard.
     *
     * @property cause what stopped the flush. For diagnostics, not for rethrowing.
     */
    public data class Failed(val cause: Throwable) : FlushOutcome
}

/**
 * Run [drain] under [timeout], with the two things every `flush` implementation has to get right.
 *
 * - **The timeout becomes [FlushOutcome.TimedOut] rather than a cancellation.** A bare `withTimeout`
 *   would throw `TimeoutCancellationException` at the caller without cancelling it, and a caller's
 *   guard would then rethrow that and cancel itself in silence (the obligation on [Seam.close]).
 * - **Anything [drain] throws becomes [FlushOutcome.Failed], and only the caller's own cancellation
 *   propagates.** A cancellation minted by a callee is a failure like any other.
 *
 * [kotlin.time.Duration.INFINITE] runs [drain] with no timer at all, rather than a timer for the
 * end of time: under `runTest` that timer fires as soon as the test goes idle, which would turn an
 * unbounded wait on a real socket into an instant [FlushOutcome.TimedOut]. A timeout of zero or less
 * reports [FlushOutcome.TimedOut] without running [drain].
 *
 * Public because it is the shape a third-party fabric needs too: `SeamStateGate` stayed `internal`
 * and four fabrics outside its module then hand-rolled it, three of them wrongly (#1803).
 *
 * @sample us.tractat.kuilt.core.flushWithinSample
 */
public suspend fun flushWithin(timeout: Duration, drain: suspend () -> FlushOutcome): FlushOutcome =
    try {
        if (timeout == Duration.INFINITE) {
            drain()
        } else {
            withTimeoutOrNull(timeout) { drain() } ?: FlushOutcome.TimedOut
        }
    } catch (failure: Throwable) {
        currentCoroutineContext().ensureActive()
        FlushOutcome.Failed(failure)
    }
