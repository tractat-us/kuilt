package us.tractat.kuilt.heddle

import platform.posix.usleep
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.test.Test

/**
 * [ResumeOutsideTheLockProbe] on Kotlin/Native threads (#3569). Each thread is a [Worker] that runs
 * one job and then terminates.
 */
class FairSharePoolResumeOutsideTheLockTest {

    @OptIn(ObsoleteWorkersApi::class)
    @Test
    fun aResumedWaiterParkedInItsBodyHoldsNoLock() = ResumeOutsideTheLockProbe(
        startThread = { name, block ->
            val worker = Worker.start(name = name)
            worker.executeAfter(0L, block)
            // Terminates once the job above has run, so a finished probe leaves no worker behind.
            worker.requestTermination(processScheduledJobs = true)
        },
        sleepMillis = { millis -> usleep((millis * 1_000L).toUInt()) },
    ).run()
}
