package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Which half of a [pumpIn] pump failed — and, decisively, whether the pump is still running.
 *
 * The two are not variants of one event. [ITEM] is the failure *of an item*; [UPSTREAM] is the failure
 * *of the pump*, and a consumer that folds them together reports a permanently dead pump as a transient
 * hiccup.
 */
public enum class PumpFailure {
    /**
     * The collector body threw on one item. **The pump survives** and consumes the next item; only this
     * item's work was lost.
     */
    ITEM,

    /**
     * The collected flow itself failed, which *ends* it. **The pump is over** — nothing will be consumed
     * from this flow again, and whatever the pump maintained is frozen at its last value.
     *
     * This is the half no `onEach`-body guard can see, and the reason [pumpIn] is one call rather than a
     * guarded-launch helper someone composes with a `.catch` they have to remember (#1788).
     */
    UPSTREAM,
}

/**
 * Collect this flow in [scope] as a **long-lived pump** that a throw cannot kill — neither one raised
 * *in* [body] nor one raised *by the flow* — reporting either through [onFailure].
 *
 * Use this instead of `onEach { … }.launchIn(scope)` for any pump that has to keep running for the life
 * of a session and whose flow or body reaches consumer-authored or peer-supplied code.
 *
 * @sample us.tractat.kuilt.core.samplePumpIn
 *
 * ### What it is for: one throw must end the operation, not the pump
 * A pump is a collector with no restart and no backstop, so an escaping throw is permanent: the thing it
 * maintained freezes at its last value while everything around it goes on claiming to be healthy. It is
 * also, on Kotlin/Native, **fatal**. An unhandled coroutine exception reaches
 * `handleUncaughtCoroutineException` → the runtime default → `Uncaught Kotlin exception` → **abort**, and
 * kuilt installs no `setUnhandledExceptionHook` anywhere in its non-test sources. A
 * [kotlinx.coroutines.SupervisorJob] is not protection from this, it is the *mechanism*: suppressing
 * parent propagation is exactly what routes the throw to the global handler. A short frame from a peer
 * crashed a shipped iOS app this way (#1788).
 *
 * ### Both halves, in one call, deliberately
 * `flow.onEach { … }.launchIn(scope)` desugars to `scope.launch { flow.onEach { … }.collect() }`, so a
 * `try` written inside the `onEach` body is **inside** the collector: it sees only what [body] throws. A
 * throw raised by the flow itself propagates out of `collect`, out of the `launch`, and down the abort
 * route above without ever entering that `try`. A guarded launch *alone* therefore leaves process death
 * open while reading, at every call site, as though the hazard were handled — which is worse than no
 * helper at all, because it stops anyone looking. So this is **one** function owning both halves rather
 * than a guarded launch plus a `.catch` a caller can forget, and [PumpFailure] puts the upstream half in
 * the signature of every call site.
 *
 * The upstream guard needs no `ensureActive` of its own: [kotlinx.coroutines.flow.catch] rethrows when
 * the throwable is this coroutine's own cancellation cause and catches otherwise — the same
 * discriminator, already built in. And because the body guard below absorbs everything except our own
 * cancellation, the only thing that guard can ever see is a genuine upstream failure.
 *
 * ### `ensureActive`, not `runCatchingCancellable`, in the body
 * `runCatchingCancellable` discriminates on **type**, and type cannot separate *"my job was cancelled"*
 * (must propagate) from *"a callee minted a `CancellationException` and threw it at me"* — which is what
 * a consumer writing `withTimeout(…) { … }` inside `Seam.sendTo` or `Loom.weave` does, while this job
 * stays perfectly alive. Rethrowing that one is the most silent failure available: the escaping throwable
 * *is* a `CancellationException`, so the pump is **cancelled rather than failed** — no handler runs, no
 * stack trace is printed, and [onFailure] is never invoked. `currentCoroutineContext().ensureActive()`
 * is the discriminator that decides it at runtime; it throws only when this job really was cancelled and
 * falls through on a callee-minted one, which then becomes an ordinary [PumpFailure.ITEM].
 *
 * ### [onFailure] cannot kill the pump either
 * It is invoked inside a total `catch (Throwable)` — `CancellationException` included. It is
 * **non-suspending** and called outside any cancellation contract, so there is no cancellation of ours
 * for it to be reporting and nothing to preserve by rethrowing; and a rethrow here would escape the very
 * guard this exists to be. A consumer's logger must never be able to kill a pump.
 *
 * ### Why this is `public`, and in `:kuilt-core`
 * Stated rather than left to inference, because the *identical* remedy for the sibling defect class was
 * written, documented, correct — and `internal` to this module. Four fabrics outside `:kuilt-core` then
 * hand-rolled their own, and three wrote precisely the race that helper's own KDoc bans ([SeamStateGate],
 * #1803). The remedy was unreachable, not ignored — [SeamStateGate] has since been made `public` for
 * this same reason, so it is now a resolved precedent rather than a live one. Pumps of this shape
 * live in every fabric and in
 * `:kuilt-raft`, `:kuilt-session` and `:kuilt-quilter` — all outside this module, all depending on it —
 * so an `internal` helper here is a known-failed design. `:kuilt-core` is the lowest module every one of
 * them already depends on.
 *
 * ### What it does NOT do
 * It cannot make a pump exist. Nothing stops a new site being written as a bare
 * `onEach { … }.launchIn(scope)`; that is a separate, lexical guard ("no bare `launchIn` outside a pump
 * helper"), sized in #1803 at 26 production sites and not yet written.
 *
 * ### A pump also has to be nameable, or a wedge cannot be attributed to one
 * The failure this guards against is permanent, so the next question after "a pump is stuck" is always
 * *which* pump — and by default that question has no answer. `launchIn` keeps the `onEach` lambda out of
 * the suspended continuation chain, so every pump of this shape parks at the same frame
 * (`StateFlowImpl.collect`, `ChannelCoroutine.receive`) with **no kuilt frame in its stack at all**. A
 * grouped coroutine census then renders a peer's whole per-ply pump set as one indistinguishable blob,
 * which is the healthy resting state *and* the wedged one (#1811).
 *
 * [name] is therefore attached as a [CoroutineName] on the launch, where anything walking a coroutine's
 * context — `DebugProbes.dumpCoroutinesInfo()`, a `CoroutineInfo.context` lookup — reads it straight
 * off, with no dependence on the `kotlinx.coroutines.debug` flag being set. It is **required for the
 * same reason [onFailure] is**: an anonymous pump is a pump whose death cannot be attributed, this exists
 * precisely so the discipline is a property of *how a pump is launched* rather than a convention each
 * site remembers, and a default would hand every site the anonymous case back.
 *
 * @param scope the scope the pump runs in. Cancelling it still cancels the pump: a genuine cancellation
 *   of *this* job propagates through both guards untouched.
 * @param onFailure invoked with which half failed and the throwable. Required, not defaulted — a pump
 *   that absorbs in silence is what this class of defect is made of, and every caller should have to
 *   decide what to do about a dead pump. `:kuilt-core` is logger-free by contract, so this is the only
 *   signal there is. Best-effort and non-suspending.
 * @param name what this pump is called in a coroutine dump or census — see the section above. Make it
 *   **distinct per pump instance**, not per call site: where several pumps of one kind run side by side,
 *   qualify it with whatever tells them apart (`"composite-ply-peers[$plyId]"`), so a census can
 *   group by kind *and* still name the instance.
 * @param body the per-item work.
 * @return the pump's [Job]. After [PumpFailure.UPSTREAM] it completes **normally** — the pump is over,
 *   but it ended with a diagnosis rather than a `SIGABRT`.
 */
public fun <T> Flow<T>.pumpIn(
    scope: CoroutineScope,
    onFailure: (PumpFailure, Throwable) -> Unit,
    name: String,
    body: suspend (T) -> Unit,
): Job {
    val guarded = onEach { value ->
        try {
            body(value)
        } catch (failure: Throwable) {
            // Genuinely our own cancellation → rethrow, so the pump stops as structured concurrency
            // intends; anything else — INCLUDING a `CancellationException` the callee minted itself — is
            // this item's failure. See the KDoc.
            currentCoroutineContext().ensureActive()
            reportPumpFailure(onFailure, PumpFailure.ITEM, failure)
        }
    }
        // The half the `try` above structurally cannot see. Applied AFTER the body guard so it is
        // downstream of it, and therefore sees the flow's own failures — the guard above has already
        // absorbed everything [body] can raise.
        .catch { failure -> reportPumpFailure(onFailure, PumpFailure.UPSTREAM, failure) }

    // `launchIn(scope)` is exactly `scope.launch { collect() }` — with no context parameter, which is
    // why the launch is spelled out here instead: [name] has to land on the launched coroutine's OWN
    // context, the only place a dump or census can read it back from (#1811). Nothing else about the
    // launch changes.
    return scope.launch(CoroutineName(name)) { guarded.collect() }
}

/**
 * Hand a pump failure to a consumer callback, absorbing whatever the callback throws — see [pumpIn]'s
 * KDoc for why the absorption is total, and why this is not the banned bare `runCatching`.
 */
private fun reportPumpFailure(
    onFailure: (PumpFailure, Throwable) -> Unit,
    phase: PumpFailure,
    failure: Throwable,
) {
    try {
        onFailure(phase, failure)
    } catch (_: Throwable) {
        // Deliberately total, `CancellationException` included: this runs inside the pump's own guard, so
        // a rethrow escapes it and kills the pump silently — the very defect the hook exists to report.
    }
}
