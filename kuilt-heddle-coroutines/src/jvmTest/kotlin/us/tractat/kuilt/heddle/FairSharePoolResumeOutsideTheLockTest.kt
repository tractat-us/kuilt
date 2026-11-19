package us.tractat.kuilt.heddle

import kotlin.concurrent.thread
import kotlin.test.Test

/** [ResumeOutsideTheLockProbe] on JVM threads (#3569). */
class FairSharePoolResumeOutsideTheLockTest {

    @Test
    fun aResumedWaiterParkedInItsBodyHoldsNoLock() = ResumeOutsideTheLockProbe(
        startThread = { name, block -> thread(name = name, isDaemon = true, block = block) },
        sleepMillis = Thread::sleep,
    ).run()
}
