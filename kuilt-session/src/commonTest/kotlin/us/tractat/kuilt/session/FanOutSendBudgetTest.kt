@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A black hole may hold its own next frame for exactly the lane's send budget (#2068).
 *
 * Unlike a healthy bystander on another lane, the same recipient observes the deadline.
 * [WireTapSeam.unwedge] heals future sends without releasing the parked one. Each test
 * checks both sides of the deadline in virtual time, so either a shorter or a longer budget
 * fails an assertion. These are scheduler-time bounds, not real-network latency guarantees.
 */
class FanOutSendBudgetTest {
    @Test
    fun `the same relay recipient waits one heartbeat interval behind a black hole`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val star = relayStar(wedge = setOf("joiner-b"))
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("first"))
            testScheduler.runCurrent()
            assertAll(
                {
                    assertEquals(1, star.wireFramesTo(star.joinerBId).count { RelayEnvelope.isRelayFrame(it) },
                        "sanity: the first relay send reached the wedge")
                },
                { assertTrue(star.joinerB.appFramesFrom(star.joinerAId).isEmpty(), "sanity: the wedge fired") },
            )

            star.host.wire.unwedge(star.joinerBId)
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("second"))
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(relayHeartbeat.interval - 1.milliseconds)
            testScheduler.runCurrent()
            assertAll(
                {
                    assertTrue(star.joinerB.appFramesFrom(star.joinerAId).isEmpty(),
                        "the second relay frame must not arrive before the heartbeat interval")
                },
                {
                    assertEquals(1, star.wireFramesTo(star.joinerBId).count { RelayEnvelope.isRelayFrame(it) },
                        "the second relay send must still be queued behind the parked first send")
                },
            )

            testScheduler.advanceTimeBy(1.milliseconds)
            testScheduler.runCurrent()
            assertEquals(listOf("second"), star.joinerB.appFramesFrom(star.joinerAId),
                "the second relay frame must arrive at the heartbeat interval; the first is dropped")
        }

    @Test
    fun `the same membership recipient waits reconnect window plus timeout behind a black hole`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val star = relayStar(coJoiners = 3, wedge = setOf("joiner-b"))
            star.joinerA.room.leave()
            testScheduler.runCurrent()
            assertAll(
                {
                    assertEquals(listOf(star.joinerAId.value), star.farewellsToB(),
                        "sanity: the first farewell reached the wedge")
                },
                {
                    assertTrue(star.joinerB.room.roster.value.any { it.id == star.joinerAId },
                        "sanity: the wedged farewell was not delivered")
                },
            )

            star.host.wire.unwedge(star.joinerBId)
            star.joinerC.room.leave()
            testScheduler.runCurrent()
            assertFalse(star.host.room.roster.value.any { it.id == star.joinerCId },
                "sanity: the host processed the second departure")
            testScheduler.advanceTimeBy(relayHeartbeat.reconnectWindow + relayHeartbeat.timeout - 1.milliseconds)
            testScheduler.runCurrent()
            assertAll(
                {
                    assertTrue(star.joinerB.room.roster.value.any { it.id == star.joinerCId },
                        "the second farewell must not arrive before reconnect window plus timeout")
                },
                {
                    assertEquals(listOf(star.joinerAId.value), star.farewellsToB(),
                        "the second farewell must still be queued behind the parked first send")
                },
            )

            testScheduler.advanceTimeBy(1.milliseconds)
            testScheduler.runCurrent()
            assertAll(
                {
                    assertFalse(star.joinerB.room.roster.value.any { it.id == star.joinerCId },
                        "the second farewell must arrive at reconnect window plus timeout")
                },
                {
                    assertEquals(listOf(star.joinerAId.value, star.joinerCId.value), star.farewellsToB(),
                        "both farewells must have reached the writer in order")
                },
                {
                    assertTrue(star.joinerB.room.roster.value.any { it.id == star.joinerAId },
                        "unwedging future sends must not deliver the parked first farewell")
                },
            )
        }

    private fun RelayStar.farewellsToB(): List<String> = wireFramesTo(joinerBId)
        .mapNotNull { AdmitMessage.decode(it) }
        .filterIsInstance<AdmitMessage.Farewell>()
        .map { it.peerId }
}
