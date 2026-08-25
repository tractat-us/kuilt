package us.tractat.kuilt.core

import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.internal.LatchingStateFlow

/**
 * A [StateFlow] view that **follows** this flow until [latched] flips to `true`, then reports
 * [terminal] and never changes again.
 *
 * The problem it solves is a fabric's, not a flow library's. A [Seam] must publish `{ selfId }` once
 * it is [SeamState.Torn] — a torn fabric can reach nobody. A seam that owns its roster just writes
 * that value before latching `Torn`. But a seam whose `peers` **is** a registry *shared* with the
 * peers it is leaving cannot: the registry keeps moving after this seam is gone, and removing itself
 * from it drops its own `selfId` too. It also has nowhere to run a mirroring coroutine, because a
 * `Seam` need own no [kotlinx.coroutines.CoroutineScope]. This is the view that closes that gap:
 *
 * ```kotlin
 * private val closed = MutableStateFlow(false)
 * override val peers: StateFlow<Set<PeerId>> =
 *     registry.latchingTo(latched = closed, terminal = setOf(selfId))
 * ```
 *
 * Everything happens inside the *collector's own* coroutine, so the returned view owns no scope
 * either and needs no teardown.
 *
 * ## Why not just map the source to a constant once closed
 *
 * Because [source][StateFlow] keeps changing after the latch. A state-dependent transform would push
 * a **duplicate** [terminal] to every surviving collector on each of those changes, breaking
 * [StateFlow]'s distinct-until-changed guarantee for exactly the consumers watching for the latch.
 * Latching avoids it by construction: after the latch, source changes are not forwarded at all;
 * before it, what is forwarded is the source itself, already distinct-until-changed.
 *
 * The one place that needs care is the **boundary**: if the last value published while following
 * already equals [terminal] — a seam that was alone in its mesh, or whose only remote left before it
 * closed — an unconditional terminal emission would republish a value the collector already holds.
 * This emits [terminal] only when it differs from what that collector was actually handed.
 *
 * That argument is why this is published as a function rather than left to each fabric to
 * re-derive. It is not visible at a call site: a near-miss compiles, passes the obvious tests, and
 * misbehaves only for the collectors watching the latch. (A [Seam] built on a shared registry that
 * gets it wrong is #1849/#2443, twice.) Publishing the *function* rather than the implementing class
 * is deliberate too — the shape can be reimplemented without an API break, and a constructor able to
 * build a view that violates the two preconditions below stays out of a consumer's hands.
 *
 * ## Two preconditions, neither of them enforceable
 *
 * **[latched] must be monotonic** — once `true`, never `false` again. Nothing checks this and nothing
 * can, because a [StateFlow] cannot be asked whether it will move. Violate it and the view does not
 * merely misreport, it becomes **internally inconsistent, permanently**: [StateFlow.value] un-latches
 * with it, while any collector that already emitted [terminal] is parked forever and never will. The
 * value read and the value collected then disagree for the rest of the process. An atomic
 * `compareAndSet(false, true)` on a `MutableStateFlow<Boolean>` is the intended shape.
 *
 * **[terminal] must be effectively immutable.** `T : Any` admits a `MutableSet`, and both the
 * boundary de-duplication above and [StateFlow]'s conflation are equality comparisons against it —
 * so mutating it after construction silently changes what every collector has already been told, and
 * moves the target the de-duplication compares against. Pass a value that is never written again.
 *
 * @param latched monotonic signal for "the terminal value is now in force".
 * @param terminal the constant, effectively immutable value observable forever after [latched].
 */
public fun <T : Any> StateFlow<T>.latchingTo(
    latched: StateFlow<Boolean>,
    terminal: T,
): StateFlow<T> = LatchingStateFlow(source = this, latched = latched, terminal = terminal)
