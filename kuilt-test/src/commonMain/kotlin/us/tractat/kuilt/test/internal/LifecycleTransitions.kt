package us.tractat.kuilt.test.internal

import us.tractat.kuilt.core.SeamState

/**
 * Pure, stateless lifecycle-transition functions extracted from `FlakyLifecycleSeam`.
 *
 * Each function takes the current [SeamState] and returns the next [SeamState] —
 * no coroutines, no `StateFlow`, no side effects. The imperative shell
 * (`FlakyLifecycleSeam`) owns the state publish, the mutex, and the launched
 * collectors; these functions decide the value to publish.
 *
 * **There is no `onTear` here.** The terminal transition is not a pure function of the current
 * state: it is a *decision* that must be fused with its publish, or a concurrent recoverable write
 * can land after it and erase it. `us.tractat.kuilt.core.SeamStateGate.tear` owns it, atomically and
 * single-shot, and returns whether this caller won (#2633, part of #1803).
 *
 * **Non-transitions return the argument ITSELF, not an equal copy.** `FlakyLifecycleSeam` reads that
 * identity (`next === current`) to decide whether to publish at all, and that is what keeps a `Torn`
 * out of `SeamStateGate.update`, which rejects one. `assertSame` in the tests, never `assertEquals`:
 * [SeamState.Torn] is a data class, so an equality assertion here passes on a fresh copy and would
 * leave the property the caller depends on unpinned.
 */

/**
 * Starting state for a [FlakyLifecycleSeam] based on its delegate's current state.
 *
 * A delegate that is already [SeamState.Torn] propagates that terminal state immediately;
 * any other delegate state resolves to [SeamState.Woven] (the wrapper begins connected).
 */
internal fun initialLifecycleState(delegateState: SeamState): SeamState =
    if (delegateState is SeamState.Torn) delegateState else SeamState.Woven

/**
 * `Woven → Weaving` transition.
 *
 * Returns [SeamState.Weaving] when [current] is [SeamState.Woven]; returns [current]
 * unchanged for all other states ([SeamState.Weaving] already, or terminal [SeamState.Torn]).
 */
internal fun onEnterWeaving(current: SeamState): SeamState =
    if (current is SeamState.Woven) SeamState.Weaving else current

/**
 * `Weaving → Woven` transition.
 *
 * Returns [SeamState.Woven] when [current] is [SeamState.Weaving]; returns [current]
 * unchanged for all other states ([SeamState.Woven] already, or terminal [SeamState.Torn]).
 */
internal fun onRecover(current: SeamState): SeamState =
    if (current is SeamState.Weaving) SeamState.Woven else current
