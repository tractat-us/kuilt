package us.tractat.kuilt.warp

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * The single-thread worker every guest call of one [ChicoryWasmRuntime] is confined to.
 *
 * Narrower than [ExecutorService] on purpose: these two operations are the whole dependency
 * [GuestWorker] has on real threads, so a test can supply a fake that models a worker which never
 * answers — the state the drain exists to detect — with no thread and no wall clock anywhere.
 */
internal interface GuestExecutor {
    /** Queues [task] on the worker thread. */
    fun submit(task: Callable<ByteArray>): Future<ByteArray>

    /** Interrupts the worker and abandons anything still queued. The executor is unusable after. */
    fun shutdownNow()
}

/** Numbers the guest threads, so an abandoned runaway is identifiable in a thread dump. */
private val guestThreadGeneration = AtomicInteger()

/** The production [GuestExecutor]: one daemon thread, named for its generation. */
internal fun realGuestExecutor(): GuestExecutor = object : GuestExecutor {
    private val delegate: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "warp-wasm-guest-${guestThreadGeneration.getAndIncrement()}")
            .apply { isDaemon = true }
    }

    override fun submit(task: Callable<ByteArray>): Future<ByteArray> = delegate.submit(task)

    override fun shutdownNow() {
        delegate.shutdownNow()
    }
}

/**
 * [ChicoryWasmRuntime]'s production [TimedGuestRunner]: submits a guest task to the runtime's
 * single-thread worker and bounds it by the caller's wall-clock budget.
 *
 * ## The post-timeout drain (#1802)
 *
 * On [TimeoutException] the worker is interrupted — Chicory's interpreter throws
 * `ChicoryInterruptedException` at the next call entry / backward branch, terminating the runaway
 * guest. But `Future.cancel(true)` returns **immediately**: it only sets the interrupt flag. Before
 * #1802 the runner threw straight after, so the timed-out caller unwound out of
 * [ChicoryWasmRuntime]'s `invokeMutex` while the runaway was still on the worker — and the next op
 * acquired the mutex, submitted, and spent part of its own
 * [WasmSandboxConfig.executionTimeout] queued behind a corpse. Its budget then under-measures,
 * which under CPU starvation records a spurious terminal [WasmExecutionException] on a perfectly
 * good kernel; terminal failures are not retried.
 *
 * So before returning, the runner **proves the worker is free**: it queues a no-op barrier behind
 * the cancelled task and waits [drainGrace] for it. The barrier can only run once the worker has
 * finished the runaway, so completing it is direct evidence rather than an inference. If the grace
 * expires the worker is **discarded** — shut down and replaced — and the next op gets a fresh one.
 *
 * An unbounded wait was rejected as worse than the bug: a guest that never honours its interrupt
 * would hold the mutex forever, turning a spurious rejection into a hang.
 *
 * ## What a discard costs, and why it is still right
 *
 * The abandoned thread keeps running: `shutdownNow` re-interrupts a guest that has already ignored
 * one interrupt, so it stops being *waited for*, not stopped. It and the fresh worker can then both
 * touch the same Chicory `Instance`, which is single-threaded. That is a real cost, and it is the
 * lesser one: the alternative is every later op on this runtime inheriting an unbounded queue wait,
 * i.e. the defect above made permanent.
 *
 * It is also a branch the interpreter-only contract says is unreachable. Chicory's
 * `InterpreterMachine` checks `Thread.isInterrupted()` at every function-call entry and every
 * backward branch, and unbounded guest CPU requires a loop or recursion — exactly those sites. A
 * worker that outlasts the grace has therefore already broken that contract (the AOT-machine-factory
 * hazard [ChicoryWasmRuntime]'s KDoc warns against), and a runtime whose worker cannot be
 * interrupted is not reusable no matter what this class does.
 *
 * @param drainGrace How long a cancelled task is given to actually terminate before its worker is
 *   discarded. Derived by [ChicoryWasmRuntime] from the guest budget — see its `guestWorker` field.
 * @param newExecutor Spawns a worker. Injected so tests can drive drain and discard deterministically.
 */
internal class GuestWorker(
    private val drainGrace: Duration,
    private val newExecutor: () -> GuestExecutor = ::realGuestExecutor,
) : TimedGuestRunner, AutoCloseable {

    private val lock = ReentrantLock()

    /** The live worker. Swapped under [lock] when one has to be discarded; never null while open. */
    private var current: GuestExecutor = newExecutor()

    /** Set by [close]; stops a concurrent discard from resurrecting a worker on a closed runtime. */
    private var closed: Boolean = false

    override fun run(timeout: Duration, task: Callable<ByteArray>): ByteArray {
        val executor = lock.withLock { current }
        val future = executor.submit(task)
        try {
            return future.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Interrupt the worker; Chicory's interpreter throws ChicoryInterruptedException at
            // the next call entry / backward branch, terminating the runaway guest.
            future.cancel(true)
            // Do NOT return while the runaway may still hold the worker: the caller is about to
            // release invokeMutex, and the next op would be charged for the wait behind it (#1802).
            if (!awaitFreeWorker(executor)) discard(executor)
            throw e
        } catch (e: ExecutionException) {
            // Unwrap so invoke sees the original exception (ChicoryException, etc.), not a wrapper.
            throw e.cause ?: e
        }
    }

    /**
     * Waits up to [drainGrace] for evidence that [poisoned]'s worker has finished the cancelled
     * task. A no-op barrier queued behind it can only run once the worker is free, so the barrier
     * completing *is* the evidence — nothing here infers freedom from elapsed time.
     *
     * Returns `true` when the worker answered, or when it can no longer accept a task at all;
     * `false` only when the grace expired with the barrier still stuck behind a guest that is
     * ignoring its interrupt. Total by construction: this runs on the throw path of a
     * [TimeoutException] the caller must still see, so nothing may escape it.
     */
    private fun awaitFreeWorker(poisoned: GuestExecutor): Boolean =
        try {
            poisoned.submit(BARRIER).get(drainGrace.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            false
        } catch (_: InterruptedException) {
            // Our own waiting thread was interrupted, so we learned nothing about the worker.
            // Restore the flag for whoever interrupted us and take the safe branch: never reuse a
            // worker that has not been proven free.
            Thread.currentThread().interrupt()
            false
        } catch (_: RejectedExecutionException) {
            // Already shut down by a concurrent close: there is no worker left to discard.
            true
        } catch (_: Exception) {
            // The barrier came back some other way — an ExecutionException, or a
            // java.util.concurrent.CancellationException from a concurrent shutdownNow. Either the
            // worker dequeued it (so it is free) or the executor is already gone. This is a plain
            // blocking method, not a suspend one, so there is no coroutine job here whose
            // cancellation could be swallowed.
            true
        }

    /**
     * Replaces [poisoned] with a fresh worker and shuts it down, unless it has already been
     * replaced or the runner was closed. Only the caller that wins the swap shuts the old worker
     * down, so a concurrent [load] and [run] cannot double-shutdown or leak one.
     */
    private fun discard(poisoned: GuestExecutor) {
        val replaced = lock.withLock {
            if (closed || current !== poisoned) {
                false
            } else {
                current = newExecutor()
                true
            }
        }
        if (replaced) poisoned.shutdownNow()
    }

    /** Shuts down the live worker. The runner is unusable afterwards. */
    override fun close() {
        val doomed = lock.withLock {
            closed = true
            current
        }
        doomed.shutdownNow()
    }

    private companion object {
        /** Queued behind a cancelled task purely to observe when the worker gets to it. */
        val BARRIER: Callable<ByteArray> = Callable { ByteArray(0) }
    }
}
