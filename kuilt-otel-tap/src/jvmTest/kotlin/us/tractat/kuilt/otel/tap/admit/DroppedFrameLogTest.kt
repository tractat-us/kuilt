@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap.admit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A peer on the roster whose replication frames the gate drops is how a roster blip seen only by the
 * puller shows up (see "The honest limitations" in `module.md`), so the gate says so, once per peer
 * and not once per frame.
 */
class DroppedFrameLogTest {
    private val pullerId = PeerId("puller")
    private val strangerId = PeerId("stranger")
    private val outsiderId = PeerId("outsider")
    private val gateLogger = LoggerFactory.getLogger("us.tractat.kuilt.otel.tap.admit.TokenGatedSeam") as Logger

    @Test
    fun aRosterPeerThatIsNotAdmittedIsLoggedOnceNotOncePerFrame() = runTest(UnconfinedTestDispatcher()) {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = gateLogger.level
        gateLogger.level = Level.DEBUG
        gateLogger.addAppender(appender)
        try {
            val seam = FakeSeam(pullerId, setOf(pullerId, strangerId))
            seam.tokenGated(GateRole.Prover("CODE1234", Random(11)), backgroundScope)
            runCurrent()
            repeat(FRAMES) { seam.deliver(strangerId, byteArrayOf(1, 2, 3)) }
            runCurrent()
            seam.deliver(outsiderId, byteArrayOf(1, 2, 3))
            runCurrent()
        } finally {
            gateLogger.detachAppender(appender)
            gateLogger.level = previousLevel
        }

        val drops = appender.list.filter { it.level == Level.WARN }
        assertAll(
            { assertEquals(1, drops.size, "one line for $FRAMES frames; got ${drops.map { it.formattedMessage }}") },
            { assertEquals(true, drops.single().formattedMessage.contains("stranger"), "it names the peer") },
        )
    }

    private companion object {
        const val FRAMES = 50
    }
}
