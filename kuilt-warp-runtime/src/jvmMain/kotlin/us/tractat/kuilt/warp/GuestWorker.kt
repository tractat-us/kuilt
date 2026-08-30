package us.tractat.kuilt.warp

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 * On [TimeoutException] it interrupts the worker — Chicory's interpreter throws
 * `ChicoryInterruptedException` at the next call entry / backward branch, terminating the runaway
 * guest. `Future.cancel(true)` returns *immediately*, though: it only sets the interrupt flag, so
 * without more the runaway is still on the worker when the timed-out caller unwinds. That is
 * #1802 — the next op then queues behind a corpse and is charged for the wait.
 *
 * @param drainGrace How long a cancelled task is given to actually terminate before its worker is
 *   discarded. Derived by [ChicoryWasmRuntime], not invented here.
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
            throw e
        } catch (e: ExecutionException) {
            // Unwrap so invoke sees the original exception (ChicoryException, etc.), not a wrapper.
            throw e.cause ?: e
        }
    }

    /** Shuts down the live worker. The runner is unusable afterwards. */
    override fun close() {
        val doomed = lock.withLock {
            closed = true
            current
        }
        doomed.shutdownNow()
    }
}
