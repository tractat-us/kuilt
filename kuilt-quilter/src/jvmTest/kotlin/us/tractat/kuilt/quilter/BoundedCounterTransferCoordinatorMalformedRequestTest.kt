@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.quilter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import org.slf4j.LoggerFactory
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A peer asking a donor for 0 or fewer units costs the donor nothing, not a WARN with a stack trace.
 *
 * Any peer can send a request, once per frame, and before the bound moved onto
 * [BoundedCounterCoordMessage.TransferRequest] such a frame reached
 * [BoundedCounter.transfer]'s `require` inside the request collector: one WARN and one trace each.
 * Now the frame is refused while it decodes and is dropped at DEBUG, naming its sender.
 *
 * The log is asserted through a Logback [ListAppender], so this is a `jvmTest`. The subject is
 * `commonMain`, so no target-specific path goes unchecked.
 */
class BoundedCounterTransferCoordinatorMalformedRequestTest {

    private val donor = ReplicaId("malformed-donor")
    private val attacker = ReplicaId("malformed-attacker")
    private val donorPeer = PeerId(donor.value)
    private val attackerPeer = PeerId(attacker.value)

    @Test
    fun aRequestForNoUnitsIsDroppedWithoutAWarnAndAValidOneIsStillServed() = runTest(UnconfinedTestDispatcher()) {
        val seam = FakeSeam(selfId = donorPeer, initialPeers = setOf(donorPeer, attackerPeer))
        val initial = BoundedCounter.init(mapOf(donor to 100L, attacker to 0L))
        val state = MutableStateFlow(initial)
        val applied = mutableListOf<Patch<BoundedCounter>>()

        withCapture { appender ->
            BoundedCounterTransferCoordinator(
                coordSeam = seam,
                state = state,
                self = donor,
                applyTransfer = { patch ->
                    applied += patch
                    state.value = state.value.piece(patch.delta)
                },
                scope = backgroundScope,
                // The attacker holds 0, at the low-water mark, but it is not this coordinator's self,
                // so no borrow is started; only the request collector is under test.
                config = BoundedCounterTransferConfig(
                    lowWaterThreshold = 0L,
                    requestedAmount = 10L,
                    surplusFloor = 0L,
                    maxRetries = 1,
                    initialRetryDelay = 1.seconds,
                ),
            )
            runCurrent()

            seam.deliver(attackerPeer, unvalidatedTransferRequestFrame(attacker, 0L))
            seam.deliver(attackerPeer, unvalidatedTransferRequestFrame(attacker, -1L))
            runCurrent()
            val afterMalformed = state.value
            val warnsAfterMalformed = appender.at(Level.WARN)
            val debugsAfterMalformed = appender.at(Level.DEBUG).filter { it.contains(attacker.value) }

            val valid = BoundedCounterCoordMessage.TransferRequest(attacker, 10L)
            seam.deliver(attackerPeer, Cbor.encodeToByteArray(BoundedCounterCoordMessage.serializer(), valid))
            runCurrent()

            assertAll(
                { assertEquals(initial, afterMalformed, "the malformed requests left the donor's state alone") },
                { assertEquals(emptyList(), warnsAfterMalformed, "no WARN for a malformed request: ${appender.dump()}") },
                {
                    assertEquals(
                        2,
                        debugsAfterMalformed.size,
                        "each malformed frame is dropped at DEBUG, naming its sender: ${appender.dump()}",
                    )
                },
                { assertEquals(1, applied.size, "the valid request was still served") },
                { assertEquals(10L, state.value.quota(attacker), "the attacker received what it asked for") },
                { assertEquals(90L, state.value.quota(donor), "the donor gave exactly that") },
                { assertEquals(emptyList(), appender.at(Level.WARN), "nothing at WARN at all: ${appender.dump()}") },
            )
        }
    }

    // ── capture plumbing ────────────────────────────────────────────────────────

    /** Lines at [level] that name this test's peers, so a sibling test's coordinator stays out of the count. */
    private fun ListAppender<ILoggingEvent>.at(level: Level): List<String> =
        list.filter { it.level == level }.map { it.formattedMessage }.filter { it.contains("malformed-") }

    private fun ListAppender<ILoggingEvent>.dump(): String =
        list.joinToString("\n") { "${it.level} ${it.formattedMessage}" }.ifEmpty { "<nothing was logged>" }

    private inline fun withCapture(block: (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger("us.tractat.kuilt.quilter.BoundedCounterTransferCoordinator") as Logger
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = previousLevel
        }
    }
}
