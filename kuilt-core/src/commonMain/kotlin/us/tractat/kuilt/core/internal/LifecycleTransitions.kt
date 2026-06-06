package us.tractat.kuilt.core.internal

import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.SeamState

/**
 * Pure, stateless lifecycle-transition functions extracted from `FlakyLifecycleSeam`.
 *
 * Each function takes the current [SeamState] and returns the next [SeamState] —
 * no coroutines, no `StateFlow`, no side effects. The imperative shell
 * (`FlakyLifecycleSeam`) owns the `MutableStateFlow` write, the mutex, and the
 * launched collectors; these functions decide the value to write.
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

/**
 * `* → Torn` terminal transition.
 *
 * Returns [SeamState.Torn] with [reason] when [current] is not already [SeamState.Torn];
 * returns [current] unchanged when the seam is already torn (idempotent).
 */
internal fun onTear(current: SeamState, reason: CloseReason): SeamState =
    if (current is SeamState.Torn) current else SeamState.Torn(reason)
