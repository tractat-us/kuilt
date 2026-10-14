package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlin.jvm.JvmName

/**
 * Which half of a [pumpIn] pump failed — and, decisively, whether the pump is still running.
 *
 * The two are not variants of one event. [ITEM] is the failure *of an item*; [UPSTREAM] is the failure
 * *of the pump*, and a consumer that folds them together reports a permanently dead pump as a transient
 * hiccup.
 */
public enum class PumpFailure {
    /**
     * The collector body threw on one item. Only this item's work was lost, and **the pump survives**
     * unless [onFailure][pumpIn] answers [PumpVerdict.STOP].
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
 * What a [pumpIn] pump does after [onFailure][pumpIn] has been told about a failure — the consumer's
 * policy, stated as the callback's return value rather than built beside it (#2829).
 *
 * It decides something only for [PumpFailure.ITEM]. After [PumpFailure.UPSTREAM] the flow has already
 * ended, so both answers mean the same thing: the pump is over.
 *
 * @sample us.tractat.kuilt.core.samplePumpInStop
 */
public enum class PumpVerdict {
    /** Keep pumping: the next item is consumed. What every pump did before this verdict existed. */
    CONTINUE,

    /**
     * End this pump now. The body is never called again, nothing further is reported, and the scope — with
     * every sibling in it — is untouched. The pump's [Job] completes **normally** once the flow lets the
     * stop through; a flow that catches it around `emit` never reaches the body again, but the pump ends
     * only when that flow does, or when its scope is cancelled — as with `take` on the same flow.
     *
     * The answer for a pump whose items are not independent, where carrying on past a half-applied item
     * would build on state the failure left inconsistent.
     */
    STOP,
}

/**
 * Collect this flow in [scope] as a **long-lived pump** that an exception cannot kill — neither one raised
 * *in* [body] nor one raised *by the flow* — reporting either through [onFailure]. An [Error] is the one
 * exception to that: it is rethrown, never reported, and the pump dies with it (see below).
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
 * discriminator, already built in. The body guard below absorbs everything except our own cancellation
 * and an [Error], so what reaches the upstream guard is a genuine upstream failure, which it reports, or
 * an [Error] on its way out, which it rethrows.
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
 * ### [onFailure] decides whether the pump goes on: [PumpVerdict]
 * Some pumps cannot carry on past a failed item — the item was half-applied, and the next one would build
 * on what it left behind. [onFailure] says so by returning [PumpVerdict.STOP]; a pump whose items are
 * independent returns [PumpVerdict.CONTINUE]. Stopping is a return value rather than a handle for a
 * reason: the obvious handle, the [Job] this function returns, does not exist yet when the first item
 * fails under [CoroutineStart.UNDISPATCHED] or an immediate dispatcher, because that item runs inside the
 * `pumpIn` call. A `lateinit var job` cancelled from [onFailure] then throws instead of cancelling, the
 * throw is absorbed below, and the pump consumes the next item it was meant to stop before (#2829). A
 * verdict is acted on in the pump's own frame, before the next item, with nothing to capture.
 *
 * [PumpVerdict.STOP] ends the collection the way `take` does, not by cancelling anything: the body is not
 * called again, a hot flow is unsubscribed from, the pump's [Job] completes normally, and nothing is
 * reported — no [PumpFailure.UPSTREAM] for the stop, and no cancellation for the scope or its siblings to
 * see. After [PumpFailure.UPSTREAM] the verdict changes nothing, because the flow has already ended.
 *
 * Like `take`, it rests on the flow letting the stop through. A flow that catches it around `emit` — a
 * relay that logs and carries on whatever `emit` threw — never reaches the body again and reports
 * nothing more, but the pump ends only when that flow does, or when its scope is cancelled, so a `join`
 * on it waits until then.
 *
 * ### [onFailure] cannot kill the pump by throwing — except with an [Error]
 * It is invoked inside a guard that absorbs everything it throws short of an [Error] —
 * `CancellationException` included — and a throw is read as [PumpVerdict.CONTINUE]. It is
 * **non-suspending** and called outside any cancellation contract, so there is no cancellation of ours
 * for it to be reporting and nothing to preserve by rethrowing; and a rethrow here would escape the very
 * guard this exists to be. A consumer's logger must never be able to kill a pump by accident, and a pump
 * that stopped because its logger threw would be a dead pump nothing had reported — the defect this
 * function exists to prevent. So a pump whose policy is [PumpVerdict.STOP] must not let anything it does
 * before returning the verdict throw.
 *
 * ### An [Error] is not a pump failure: it escapes, and the pump dies with it
 * An `OutOfMemoryError`, a `StackOverflowError`, an `AssertionError` or a consumer's own `Error` says the
 * process — not this item, not this flow — is in a state nothing downstream can reason about. Handing
 * one to [onFailure] gives a consumer exactly two moves, log it or map it into a recoverable state, and
 * both carry on past it. So an [Error] raised by [body], by the flow, or by [onFailure] itself is
 * **rethrown, never reported**: it leaves the pump by the route an unguarded launch would take, the
 * pump's [Job] fails with it, and it reaches the scope's handler — on Kotlin/Native, the abort above.
 * That is deliberate. Every guard here exists so that an *exception* cannot end the process; none of
 * them exists so that an [Error] cannot (#2890).
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
 * ### Subscribing before the call returns: [start]
 * By default the pump is launched like any other coroutine, so it has not collected anything — has not
 * even *subscribed* — when `pumpIn` returns. Over a hot flow with no replay, such as a
 * `MutableSharedFlow(replay = 0)`, whatever is emitted in that gap is gone. A caller that must be
 * listening before it hands control back (a channel relaying `Room.incoming` from its `init`, so a reply
 * sent the instant a peer reads its hello is not lost) passes [CoroutineStart.UNDISPATCHED]: the pump then
 * runs in the caller's frame up to its first suspension, which for a `SharedFlow` is *after* it has
 * subscribed (#2826).
 *
 * That guarantee is only as strong as the flow's own first suspension. `UNDISPATCHED` runs the
 * collection up to wherever the flow first suspends; a flow that suspends **before** it subscribes to
 * its source (a `flow { delay(…); emitAll(shared) }`, an operator that hands collection to another
 * coroutine) need not be subscribed when `pumpIn` returns, and nothing here can make it so.
 *
 * [start] means exactly what it means for `launch`, including the part that is easy to forget: an
 * `UNDISPATCHED` pump in an **already-cancelled** scope still runs up to its first suspension, so a
 * cold flow that never suspends is consumed in full before the pump ends cancelled. Nothing about the
 * exception discipline changes with [start]: a failure raised during the undispatched stretch goes
 * through the same two guards, our own cancellation is still never reported, and an [Error] still
 * fails the pump's [Job] rather than being thrown at the caller of `pumpIn`.
 *
 * Unlike [onFailure] and [name], [start] has a default, because no discipline rides on it: forgetting it
 * loses the early items of a hot flow, and never kills a pump in silence.
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
 * @param onFailure invoked with which half failed and the throwable, and returns whether the pump goes
 *   on — see [PumpVerdict]. Required, not defaulted — a pump that absorbs in silence is what this class
 *   of defect is made of, and every caller should have to decide what to do about a dead pump.
 *   `:kuilt-core` is logger-free by contract, so this is the only signal there is. Best-effort and
 *   non-suspending.
 * @param name what this pump is called in a coroutine dump or census — see the section above. Make it
 *   **distinct per pump instance**, not per call site: where several pumps of one kind run side by side,
 *   qualify it with whatever tells them apart (`"composite-ply-peers[$plyId]"`), so a census can
 *   group by kind *and* still name the instance.
 * @param start how the pump's coroutine is started — see the section above. It exists for
 *   [CoroutineStart.UNDISPATCHED], which leaves the pump subscribed to a hot flow before `pumpIn` returns.
 * @param body the per-item work.
 * @return the pump's [Job]. After [PumpFailure.UPSTREAM] it completes **normally** — the pump is over,
 *   but it ended with a diagnosis rather than a `SIGABRT`. After [PumpVerdict.STOP] it completes
 *   normally too, once the flow lets the stop through — see above. After an [Error] it **fails** with
 *   that error.
 */
// The JVM name differs from the hidden `Unit`-callback overload below only because both callbacks erase
// to `Function2`; on every other target the two already have distinct signatures.
@JvmName("pumpInWithVerdict")
public fun <T> Flow<T>.pumpIn(
    scope: CoroutineScope,
    onFailure: (PumpFailure, Throwable) -> PumpVerdict,
    name: String,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    body: suspend (T) -> Unit,
): Job {
    val guarded = guardedItems(body, onFailure)
        // The half the item guard structurally cannot see. Applied AFTER the item guard so it is
        // downstream of it, and therefore sees the flow's own failures — the item guard has already
        // absorbed everything [body] can raise except an `Error`. An `Error` passes through untouched,
        // whether the flow raised it or the body guard rethrew it. The verdict is discarded: the flow
        // has already ended, so STOP and CONTINUE are the same outcome here.
        .catch { failure ->
            if (failure is Error) throw failure
            reportPumpFailure(onFailure, PumpFailure.UPSTREAM, failure)
        }

    // `launchIn(scope)` is exactly `scope.launch { collect() }` — with no context parameter, which is
    // why the launch is spelled out here instead: [name] has to land on the launched coroutine's OWN
    // context, the only place a dump or census can read it back from (#1811). Nothing else about the
    // launch changes except [start], which is the caller's to choose (#2826).
    return scope.launch(CoroutineName(name), start) { guarded.collect() }
}

/**
 * [pumpIn]'s item guard: [body] on each item, a failure reported through [onFailure], and its verdict
 * acted on before the next item.
 *
 * `transformWhile` rather than `onEach`, so a STOP ends the collection where it was decided: returning
 * `false` aborts the upstream the way `take` does, and the abort is consumed inside that operator — the
 * upstream guard downstream of this never sees it, so a stop is not reported as an UPSTREAM failure.
 *
 * The abort only ends a flow that lets it through. One that catches it and emits again — a `Flow`
 * implemented directly, or a `flow {}` that wraps each `emit` in a `catch` — would reach the body again,
 * so `stopped` latches the verdict and every later item is refused before the body. The latch lives
 * inside the `flow {}`, so each collection gets its own.
 */
private fun <T> Flow<T>.guardedItems(
    body: suspend (T) -> Unit,
    onFailure: (PumpFailure, Throwable) -> PumpVerdict,
): Flow<Nothing> = flow {
    var stopped = false
    emitAll(
        transformWhile<T, Nothing> { value ->
            if (stopped) return@transformWhile false
            try {
                body(value)
                true
            } catch (error: Error) {
                // Never an item failure — the process is in trouble, and the pump dies with it. See the
                // KDoc on [pumpIn].
                throw error
            } catch (failure: Throwable) {
                // Genuinely our own cancellation → rethrow, so the pump stops as structured concurrency
                // intends; anything else — INCLUDING a `CancellationException` the callee minted itself —
                // is this item's failure. See the KDoc on [pumpIn].
                currentCoroutineContext().ensureActive()
                stopped = reportPumpFailure(onFailure, PumpFailure.ITEM, failure) == PumpVerdict.STOP
                !stopped
            }
        },
    )
}

/**
 * The signature [pumpIn] had before [onFailure] returned a [PumpVerdict] (#2829), kept so a binary compiled
 * against it still links. Hidden, so source sees one `pumpIn`; it is [pumpIn] with every failure answered
 * [PumpVerdict.CONTINUE], which is exactly what that signature did.
 */
@Deprecated(
    "Kept for binary compatibility; call pumpIn, whose onFailure returns a PumpVerdict.",
    level = DeprecationLevel.HIDDEN,
)
public fun <T> Flow<T>.pumpIn(
    scope: CoroutineScope,
    onFailure: (PumpFailure, Throwable) -> Unit,
    name: String,
    // Load-bearing although source never calls this: the default is what still emits `pumpIn$default`,
    // which every binary that omitted `start` links against.
    start: CoroutineStart = CoroutineStart.DEFAULT,
    body: suspend (T) -> Unit,
): Job = pumpIn(scope, alwaysContinue(onFailure), name, start, body)

/**
 * The signature [pumpIn] had before it took a [CoroutineStart] (#2826), kept so a binary compiled against
 * it still links. Hidden, so source sees one `pumpIn`; it is [pumpIn] with [CoroutineStart.DEFAULT] and
 * every failure answered [PumpVerdict.CONTINUE].
 */
@Deprecated(
    "Kept for binary compatibility; call pumpIn, whose onFailure returns a PumpVerdict.",
    level = DeprecationLevel.HIDDEN,
)
public fun <T> Flow<T>.pumpIn(
    scope: CoroutineScope,
    onFailure: (PumpFailure, Throwable) -> Unit,
    name: String,
    body: suspend (T) -> Unit,
): Job = pumpIn(scope, alwaysContinue(onFailure), name, CoroutineStart.DEFAULT, body)

/** A pre-#2829 `Unit` callback, answering [PumpVerdict.CONTINUE] — the only behaviour it ever had. */
private fun alwaysContinue(onFailure: (PumpFailure, Throwable) -> Unit): (PumpFailure, Throwable) -> PumpVerdict =
    { phase, failure ->
        onFailure(phase, failure)
        PumpVerdict.CONTINUE
    }

/**
 * Hand a pump failure to a consumer callback and return its verdict, absorbing whatever the callback
 * throws short of an [Error] and reading that as [PumpVerdict.CONTINUE] — see [pumpIn]'s KDoc for why,
 * and why this is not the banned bare `runCatching`.
 */
private fun reportPumpFailure(
    onFailure: (PumpFailure, Throwable) -> PumpVerdict,
    phase: PumpFailure,
    failure: Throwable,
): PumpVerdict =
    try {
        onFailure(phase, failure)
    } catch (error: Error) {
        throw error
    } catch (_: Throwable) {
        // Deliberately total short of an `Error`, `CancellationException` included: this runs inside the
        // pump's own guard, so a rethrow escapes it and kills the pump silently — the very defect the hook
        // exists to report. CONTINUE, not STOP, for the same reason: a pump stopped by its own logger's
        // throw is a dead pump nothing reported.
        PumpVerdict.CONTINUE
    }
