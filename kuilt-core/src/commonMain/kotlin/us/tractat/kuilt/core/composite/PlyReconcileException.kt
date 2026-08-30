package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam

/**
 * One ply's failure inside a live [CompositeLoom] session — attaching, detaching, processing an inbound
 * frame, or running one of its pumps — raised through `CompositeLoom(onPlyFailure = …)`.
 *
 * A ply is built from a **consumer-authored** [Loom], so `Loom.capability()`, `Loom.weave()` and the
 * ply [Seam]'s own `close()` can all throw; and a ply's inbound frames are **peer-supplied bytes**, so
 * a merely buggy or version-skewed peer can send one that does not decode. The composite absorbs all of
 * those — one ply must not be able to stop the reconciliation of the others, still less kill a pump for
 * the life of the seam (#1784), and no peer may crash the process with a short frame (#1788) — but
 * absorbing them *silently* would leave a consumer with a ply that never appears, or frames that quietly
 * vanish, and nothing at all to look at. `kuilt-core` is logger-free by contract, so this is the signal:
 * the composite raises it, and the consumer's own logger records it.
 *
 * It carries the ply's [plyId], which [phase] failed, and the originating [cause] — identity and
 * exception, never a bare count, because "a ply failed" is not a diagnosis and "which ply, doing what,
 * and why" is.
 *
 * The composite keeps going regardless: the other plies in the same pass still attach and detach, a ply
 * that failed to attach is retried on the next desired-set emission (it is left un-live, not recorded as
 * failed), and a ply whose inbound frame failed keeps delivering the frames after it. Ignoring this
 * signal therefore costs observability, never liveness.
 *
 * (The name predates [Phase.INBOUND], which is not a reconciliation step. It is kept because this is the
 * one type `onPlyFailure` carries and the whole point of that hook is a single per-ply failure signal.)
 */
public class PlyReconcileException(
    public val plyId: PlyId,
    public val phase: Phase,
    override val cause: Throwable,
) : Exception("composite ply '${plyId.value}' ${clauseFor(phase)}: $cause", cause) {

    /**
     * Which part of the ply's life failed.
     *
     * Pre-1.0 this may gain values: a `when` over it should carry an `else`.
     */
    public enum class Phase {
        /** `Loom.capability()`, `Loom.weave()`, or the attach itself threw; the ply is not live. */
        ATTACH,

        /** The ply's teardown threw. Its pumps are stopped and it is out of the composite regardless. */
        DETACH,

        /**
         * An inbound frame on this ply could not be processed — most often a malformed [PlyFrame] from a
         * peer (#1788). **That frame is dropped and the ply keeps delivering**; the ply is neither torn
         * nor detached, deliberately, because tearing would hand any peer a one-frame way to remove a ply
         * from someone else's composite.
         */
        INBOUND,

        /**
         * One of the ply's mirror/announce pumps failed on a single delivery. **That pump survives** and
         * keeps collecting; only this delivery's work was lost.
         *
         * Distinct from [PUMP_ENDED] because the two are not degrees of the same event: this one is a
         * hiccup and that one is permanent, and a consumer folding them together reports a dead strand
         * as a transient blip (#1803).
         */
        PUMP,

        /**
         * One of the ply's mirror/announce pumps is **over**: the ply `Seam`'s own `state`, `capability`
         * or `peers` flow failed, which *ends* it, so that strand of this ply will never update again —
         * the composite may go on folding a stale mirrored value into `capability`/`peers` while the ply
         * stays `Woven`.
         *
         * The ply is neither torn nor detached, for the same reason a malformed frame does not tear it
         * ([INBOUND]). Nothing re-subscribes: a `Seam`'s flows are collected once, at attach.
         *
         * No `onEach`-body guard can see this — it is the upstream half [us.tractat.kuilt.core.pumpIn]
         * exists to close, and on Kotlin/Native it aborted the process before it was closed (#1788).
         */
        PUMP_ENDED,

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

/**
 * The message clause for [phase], rather than `phase.name.lowercase()`: the phases are not all verbs
 * ("failed to inbound"), and the message a consumer's logger records is the diagnosis. Exhaustive on
 * purpose — a new [PlyReconcileException.Phase] must choose its own wording.
 */
private fun clauseFor(phase: PlyReconcileException.Phase): String =
    when (phase) {
        PlyReconcileException.Phase.ATTACH -> "failed to attach"
        PlyReconcileException.Phase.DETACH -> "failed to detach"
        PlyReconcileException.Phase.INBOUND -> "failed to process an inbound frame"
        PlyReconcileException.Phase.PUMP -> "failed on one pump delivery"
        PlyReconcileException.Phase.PUMP_ENDED -> "lost a pump for the life of the ply"
        PlyReconcileException.Phase.SALVAGE -> "failed to salvage"
    }
