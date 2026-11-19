package us.tractat.kuilt.heddle

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A hand-out failure with no waiter queued has nobody to throw to, so the pool logs it at error with
 * its throwable attached. One that does reach a waiter is that waiter's to report, and is not logged
 * as well.
 *
 * The rig is the engine refusing a hand-back: A holds the one slot, B queues behind it, and B is
 * cancelled just before its resume, so the pool hands B's lease back and the hook throws where the
 * engine's cancel would run. With nobody else queued that failure reaches no one. With C queued
 * behind B, C's `acquire` throws it.
 *
 * The log is asserted through a Logback [ListAppender], so this is a `jvmTest`. The decision to log
 * is taken in `commonMain`; the JVM is only where a kotlin-logging backend that a test can tap
 * exists.
 */
class FairSharePoolUnclaimedFailureLogTest {

    @Test
    fun aRefusedHandBackWithNobodyQueuedIsLoggedAtErrorWithItsThrowable() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val boom = Boom()
        val (rig, logged) = capturing { refuseHandBack(boom, alsoQueueC = false) }
        val errors = logged.filter { it.level == Level.ERROR }
        assertAll(
            { assertEquals(1, rig.faults, "the rig: the hand-back reached the fault once") },
            { assertEquals(0, rig.waitersAtFault, "the rig: nobody was queued when it failed") },
            { assertEquals(1, logged.size, "the failure is logged once: ${logged.dump()}") },
            { assertEquals(1, errors.size, "at error: ${logged.dump()}") },
            { assertSame(boom, errors.singleOrNull()?.thrown(), "with the failure itself attached") },
        )
    }

    @Test
    fun aRefusedHandBackWithAWaiterQueuedGoesToTheWaiterAndIsNotLogged() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val boom = Boom()
        val (rig, logged) = capturing { refuseHandBack(boom, alsoQueueC = true) }
        assertAll(
            { assertEquals(1, rig.faults, "the rig: the hand-back reached the fault once") },
            { assertEquals(1, rig.waitersAtFault, "the rig: C was queued when it failed") },
            // assertIs, not assertSame: stack-trace recovery may hand C a copy of the throwable.
            { assertIs<Boom>(rig.cFailure, "C's acquire throws the failure") },
            { assertTrue(logged.isEmpty(), "nothing is logged as well: ${logged.dump()}") },
        )
    }

    private class Rig(val faults: Int, val waitersAtFault: Int, val cFailure: Throwable?)

    /** Run the refused hand-back described on the class, and report what the rig did. */
    private suspend fun TestScope.refuseHandBack(boom: Boom, alsoQueueC: Boolean): Rig {
        val p = pool(slots = 1)
        val a = p.acquire(web).granted()
        val b = launch { p.acquire(web) }
        val c = if (alsoQueueC) {
            async {
                try {
                    p.acquire(web)
                    null
                } catch (failure: Boom) {
                    failure
                }
            }
        } else {
            null
        }
        runCurrent()
        check(p.waitersForTest == if (alsoQueueC) 2 else 1) { "the rig: the waiters did not queue" }
        var faults = 0
        var waitersAtFault = -1
        p.handBackFaultForTest = {
            faults++
            // Read under the pool's lock, where the hook runs: B has been granted and left its queue.
            waitersAtFault = p.view().queues.values.sumOf { it.size }
            throw boom
        }
        p.beforeResumeForTest = { b.cancel() }
        p.complete(a.id, 3L)
        p.beforeResumeForTest = {}
        p.handBackFaultForTest = null
        runCurrent()
        assertTrue(b.isCancelled, "the rig: B was cancelled before its resume")
        val cFailure = c?.let { if (it.isCompleted) it.getCompleted() else null }
        // A regression strands C: free it, so the test reds instead of hanging.
        c?.cancel()
        return Rig(faults, waitersAtFault, cFailure)
    }

    /** Run [block] with a [ListAppender] on the pool's logger, and answer its result and every event logged. */
    private inline fun <T> capturing(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val slf4j: org.slf4j.Logger = LoggerFactory.getLogger(POOL_LOGGER)
        val logger = slf4j as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.TRACE
        logger.addAppender(appender)
        try {
            val result = block()
            return result to appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = previousLevel
        }
    }

    private fun ILoggingEvent.thrown(): Throwable? = (throwableProxy as? ThrowableProxy)?.throwable

    private fun List<ILoggingEvent>.dump(): String =
        joinToString("\n") { "${it.level} ${it.formattedMessage} ${it.thrown()}" }.ifEmpty { "<nothing was logged>" }

    private companion object {
        const val POOL_LOGGER = "us.tractat.kuilt.heddle.FairSharePool"
    }
}
