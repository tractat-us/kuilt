package us.tractat.kuilt.heddle

import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the probe needs a waiter resumed inline on the thread that granted it, which only Unconfined does; real threads come from the platform test that runs it
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [FairSharePool] resumes its waiters only once it has released its lock (#3569). Under `runTest`
 * nothing can tell: everything is serial, and a resume under the lock looks the same as one after
 * it. On `Dispatchers.Default` a resume only enqueues, so the lock is held for the enqueue alone.
 * This probe makes it visible on real threads, in three steps:
 *
 * 1. A waiter is launched on `Dispatchers.Unconfined` and queues behind the pool's one slot.
 * 2. A thread completes the slot's lease. The grant resumes the waiter inline on that thread, and
 *    the waiter's body parks it, blocking the thread in a real sleep.
 * 3. A second thread calls [FairSharePool.cancel] for a lease the pool never issued. It needs only
 *    the lock. If the parked waiter holds it, the cancel cannot return.
 *
 * The bound on step 3 is real time ([BOUND], from `TimeSource.Monotonic` and a blocking sleep). It is
 * not `runTest`'s `withTimeout`, which counts virtual time. So the defect fails as an assertion after
 * [BOUND], never as a hang: the waiter is released afterwards whatever the verdict, and every thread
 * unwinds.
 *
 * It cannot live in `commonTest` as a test: on wasmJs's one thread the parked body would block the
 * thread the cancel needs, forever. So this class holds the body, and `jvmTest` and `appleTest` run
 * it with their own threads.
 *
 * @param startThread starts [block] on a new platform thread named `name`, and returns at once.
 * @param sleepMillis blocks the calling thread for that many milliseconds.
 */
internal class ResumeOutsideTheLockProbe(
    private val startThread: (name: String, block: () -> Unit) -> Unit,
    private val sleepMillis: (Long) -> Unit,
) {
    fun run() {
        val engine = farm()
        val pool = FairSharePool(engine, 1, TimeSource.Monotonic, 1.seconds)
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            run(engine, pool, scope)
        } finally {
            scope.cancel()
        }
    }

    private fun run(engine: LocalHeddle, pool: FairSharePool, scope: CoroutineScope) {
        // The one slot. On Unconfined, with a slot free, acquire returns without suspending.
        var holder: Lease? = null
        scope.launch { holder = pool.acquire(web).granted() }
        val held = holder ?: error("the first acquire did not complete inline")

        val insideComplete = atomic(false)
        val resumedInsideComplete = atomic(false)
        val parked = atomic(false)
        val release = atomic(false)
        val waiterDone = atomic(false)
        scope.launch {
            val lease = pool.acquire(mobile).granted()
            resumedInsideComplete.value = insideComplete.value
            parked.value = true
            // A blocking sleep, not a delay: the body keeps the thread that resumed it.
            while (!release.value) sleepMillis(1L)
            pool.complete(lease.id, 1L)
            waiterDone.value = true
        }
        val queued = pool.waitersForTest

        val completerDone = atomic(false)
        startThread("resume-probe-completer") {
            insideComplete.value = true
            pool.complete(held.id, 1L)
            insideComplete.value = false
            completerDone.value = true
        }
        val parkedInTime = parked.awaitTrue(BOUND)

        var strangerAnswer: LeaseOutcome? = null
        val strangerDone = atomic(false)
        startThread("resume-probe-stranger") {
            strangerAnswer = pool.cancel(STRANGER)
            strangerDone.value = true
        }
        val cancelledWhileParked = strangerDone.awaitTrue(BOUND)

        // Whatever the verdict, let the waiter go, so every thread unwinds.
        release.value = true
        val unwound = completerDone.awaitTrue(BOUND) && waiterDone.awaitTrue(BOUND) && strangerDone.awaitTrue(BOUND)

        val seen = "queued=$queued parked=$parkedInTime resumedInsideComplete=${resumedInsideComplete.value} " +
            "cancelledWhileParked=$cancelledWhileParked unwound=$unwound open=${pool.view().open} " +
            "waiters=${pool.waitersForTest}"
        assertAll(
            { assertEquals(1, queued, "the rig: the waiter queued behind the one slot: $seen") },
            { assertTrue(parkedInTime, "the rig: the waiter was granted and parked within $BOUND: $seen") },
            // Inline on the completer's thread, inside its complete: the case where the lock matters.
            { assertTrue(resumedInsideComplete.value, "the rig: the waiter was resumed inside the completer's complete: $seen") },
            {
                assertTrue(
                    cancelledWhileParked,
                    "a stranger's cancel returned within $BOUND while a resumed waiter was parked in its body, " +
                        "so the waiter was not holding the pool's lock: $seen",
                )
            },
            { assertEquals(LeaseOutcome.Unknown, strangerAnswer, "the stranger's lease is unknown to the engine: $seen") },
            { assertTrue(unwound, "every thread finished once the waiter was released: $seen") },
            { assertEquals(0, pool.view().open, "the pool counts no open lease: $seen") },
            { assertEquals(emptyList(), engine.snapshot().leases, "the engine holds no open lease: $seen") },
        )
    }

    /** Wait in real time, up to [bound], for this to read `true`; answer whether it did. */
    private fun AtomicBoolean.awaitTrue(bound: Duration): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + bound
        while (!value) {
            if (deadline.hasPassedNow()) return false
            sleepMillis(1L)
        }
        return true
    }

    private companion object {
        /** Real time. Green, the cancel returns in well under a millisecond; a red costs this once. */
        val BOUND: Duration = 5.seconds

        /** A lease the pool never issued: cancelling it only takes the lock. */
        val STRANGER: LeaseId = LeaseId(epoch = 0L, seq = 1_000_000L)
    }
}
