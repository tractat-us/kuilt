package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam

/**
 * One ply's failure to attach or detach during a [CompositeLoom] reconciliation, raised through
 * `CompositeLoom(onPlyFailure = …)`.
 *
 * A ply is built from a **consumer-authored** [Loom], so `Loom.capability()`, `Loom.weave()` and the
 * ply [Seam]'s own `close()` can all throw. The composite absorbs those failures — one ply must not be
 * able to stop the reconciliation of the others, still less kill the reconcile pump for the life of the
 * seam (#1784) — but absorbing them *silently* would leave a consumer with a ply that simply never
 * appears and nothing at all to look at. `kuilt-core` is logger-free by contract, so this is the
 * signal: the composite raises it, and the consumer's own logger records it.
 *
 * It carries the ply's [plyId], which [phase] failed, and the originating [cause] — identity and
 * exception, never a bare count, because "a ply failed" is not a diagnosis and "which ply, doing what,
 * and why" is.
 *
 * The composite keeps going regardless: the other plies in the same pass still attach and detach, and a
 * ply that failed to attach is retried on the next desired-set emission (it is left un-live, not
 * recorded as failed). Ignoring this signal therefore costs observability, never liveness.
 */
public class PlyReconcileException(
    public val plyId: PlyId,
    public val phase: Phase,
    override val cause: Throwable,
) : Exception("composite ply '${plyId.value}' failed to ${phase.name.lowercase()}: $cause", cause) {

    /**
     * Which part of the reconciliation the ply failed in.
     *
     * Pre-1.0 this may gain values: a `when` over it should carry an `else`.
     */
    public enum class Phase {
        /** `Loom.capability()`, `Loom.weave()`, or the attach itself threw; the ply is not live. */
        ATTACH,

        /** The ply's teardown threw. Its pumps are stopped and it is out of the composite regardless. */
        DETACH,

        /**
         * The ply was woven but could not be attached — the composite had already been closed — and
         * closing the freshly woven transport back down *also* threw, so it may be leaked.
         *
         * Distinct from [DETACH] deliberately: this ply never entered the live set and never appeared in
         * `Seam.plies`, so there was nothing in the composite to remove and [DETACH]'s "its pumps are
         * stopped and it is out of the composite" would be a false report.
         */
        SALVAGE,
    }
}
