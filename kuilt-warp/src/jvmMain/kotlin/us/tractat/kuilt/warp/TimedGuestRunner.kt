package us.tractat.kuilt.warp

import java.util.concurrent.Callable
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/**
 * Runs a [task] callable under a wall-clock [timeout].
 *
 * Returns the task's result on success; throws [TimeoutException] if the deadline is exceeded;
 * throws any exception raised by [task] directly (callers see the original exception, not an
 * [java.util.concurrent.ExecutionException] wrapper).
 *
 * **Production:** [ChicoryWasmRuntime] provides a default backed by its dedicated single-thread
 * executor. The real implementation submits [task] to the guest executor, calls
 * `Future.get(timeout)`, interrupts the worker on [TimeoutException] so Chicory's interpreter
 * terminates the runaway guest, and unwraps any [java.util.concurrent.ExecutionException] before
 * rethrowing.
 *
 * **Tests:** inject a fake implementation that is deterministically controllable without relying on
 * real wall-clock timing — the primary purpose of this seam. See [ChicoryWasmRuntimeTimingTest].
 *
 * @see ChicoryWasmRuntime
 */
public fun interface TimedGuestRunner {
    /** @throws TimeoutException if [task] does not finish within [timeout]. */
    public fun <T> run(timeout: Duration, task: Callable<T>): T
}
