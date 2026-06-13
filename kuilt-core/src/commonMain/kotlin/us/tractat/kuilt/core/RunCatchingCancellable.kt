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
 */
public inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
