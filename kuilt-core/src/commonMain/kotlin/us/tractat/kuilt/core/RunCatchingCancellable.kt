package us.tractat.kuilt.core

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching] but never swallows coroutine cancellation: a [CancellationException]
 * always propagates, so a cancelled coroutine fails fast instead of being reported as a
 * captured failure. Every other [Throwable] becomes [Result.failure].
 *
 * Use this, not bare `runCatching`, in any suspend or coroutine context. Bare `runCatching`
 * catches [CancellationException] and converts a cancel into a normal `Result`, which hides
 * structured-concurrency cancellation — a silent bug, not a safety measure.
 *
 * ### But NOT on a long-lived pump — use [pumpIn] there (#1803)
 * The name reads as "the cancellation-safe one", and it is, for the case it was built for: a
 * best-effort call with nothing owed behind it. On a **pump** it is a trap. It discriminates on
 * **type**, and type cannot separate *"my job was cancelled"* from *"a callee minted a
 * [CancellationException] and threw it at me"* — which is what a consumer writing
 * `withTimeout(…) { … }` inside `Seam.sendTo`, `Seam.close` or `Loom.weave` does while this job stays
 * alive. Rethrown from a collector, that one **cancels the pump instead of failing it**: no handler
 * runs, no stack trace is printed, and any report you were about to make never happens. Six instances
 * of that shape were found in this repo, one of them written by an author who had read the issues and
 * cited them in the KDoc of the pump they were breaking. Use [pumpIn], which discriminates at runtime
 * with `ensureActive()` and also guards the upstream half this helper cannot reach at all.
 */
public inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
