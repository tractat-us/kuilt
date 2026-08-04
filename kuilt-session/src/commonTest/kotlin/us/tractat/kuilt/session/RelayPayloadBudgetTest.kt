@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The relay envelope costs bytes, and nothing used to reserve them (#2047).
 *
 * `SeamRoom` wraps an application payload in a [RelayEnvelope] before handing it to the host, so a
 * payload that fits a framed fabric when sent directly can overflow that fabric once it is
 * relayed. The failure arrives at the moment the roster diverges — the same payload succeeds on a
 * mesh and fails on a star — and before this it arrived as the *fabric's* oversize error, escaping
 * from a send the caller had no way to know was over budget.
 *
 * Both call shapes are covered because their contracts differ and the right answer differs with
 * them: [Room.broadcast] is documented lossy-without-error and must stay a silent no-op (a
 * `Quilter`'s timer-driven broadcast that threw would kill the coroutine driving anti-entropy),
 * while [Room.sendTo] is addressed and reports. What both must *not* do is surface
 * [FabricFrameTooLarge].
 *
 * Every over-budget case is paired with an in-budget positive control in the same test, per the
 * convention `StarRelayTest` sets: a room that dropped every frame would otherwise pass.
 */
class RelayPayloadBudgetTest {

    /**
     * A generous wedge backstop, **not** an assertion. It is wall-clock measured over a
     * virtual-time trajectory, so tightening it measures the host machine rather than this code
     * (#1739, #1891). Fast failure comes from the harness's bounded awaits.
     */
    private val backstop = 30.seconds

    /**
     * The fabric frame ceiling these tests impose on the sending spoke.
     *
     * Small enough to be cheap and far above every protocol frame the star exchanges (admit,
     * heartbeat, lobby), so the limit bites only on the payloads a test chooses.
     */
    private val fabricLimit = 1024

    /**
     * A payload sized **exactly** to the fabric limit: it fits a direct send, and cannot fit once
     * the envelope is wrapped around it. Zero-filled, so its leading byte is not one of
     * [RoomFramePrefix]'s reserved values.
     */
    private fun fabricSizedPayload(): ByteArray = ByteArray(fabricLimit)

    @Test
    fun `a relayed broadcast that no longer fits the fabric is dropped rather than thrown`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.joinerA.wire.limitFrames(fabricLimit)

            star.joinerA.room.broadcast(appPayload("in-budget"))
            testScheduler.runCurrent()

            // Room.broadcast promises never to throw for an undeliverable frame. An oversize one is
            // undeliverable; letting the fabric's error out breaks that promise at the send side.
            star.joinerA.room.broadcast(fabricSizedPayload())
            testScheduler.runCurrent()

            assertEquals(
                listOf("in-budget"),
                star.joinerB.appFramesFrom(star.joinerAId),
                "the in-budget frame still crosses the relay, so the drop is selective",
            )
        }

    @Test
    fun `a relayed sendTo that no longer fits the fabric does not leak the fabric's oversize error`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.joinerA.wire.limitFrames(fabricLimit)

            star.joinerA.room.sendTo(star.joinerBId, appPayload("in-budget"))
            testScheduler.runCurrent()

            val failure = assertFails { star.joinerA.room.sendTo(star.joinerBId, fabricSizedPayload()) }

            assertTrue(
                failure !is FabricFrameTooLarge,
                "an addressed send must fail with an error naming the budget, not with the fabric's " +
                    "own frame error raised from framing the caller never asked for: $failure",
            )
            assertEquals(
                listOf("in-budget"),
                star.joinerB.appFramesFrom(star.joinerAId),
                "the in-budget frame still crosses the relay, so the refusal is selective",
            )
        }

    /** What the addressed send reports instead: the budget the caller could have read beforehand. */
    @Test
    fun `an over-budget addressed send names the payload and the budget`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.joinerA.wire.limitFrames(fabricLimit)
            val budget = assertNotNull(star.joinerA.room.maxPayloadBytes)

            val refusal = assertFailsWith<PayloadTooLarge> {
                star.joinerA.room.sendTo(star.joinerBId, ByteArray(budget + 1))
            }

            assertAll(
                { assertEquals(budget + 1, refusal.payloadBytes, "the payload that was refused") },
                { assertEquals(budget, refusal.budgetBytes, "the budget it should have respected") },
                { assertEquals(RELAY_ENVELOPE_BUDGET, refusal.reservedBytes, "and what the reservation buys") },
            )
        }

    /**
     * The budget is not merely *a* smaller number — a payload filling it exactly must still fit the
     * fabric after the envelope goes on, on **both** call shapes.
     *
     * This is the property the whole surface exists for. A budget that under-reserved would leave
     * this red, and one that reserved absurdly much would leave it green while making the library
     * useless, which is why the reservation itself is pinned separately below.
     */
    @Test
    fun `a payload filling the room's budget still fits the fabric once the envelope is on`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.joinerA.wire.limitFrames(fabricLimit)
            val budget = assertNotNull(star.joinerA.room.maxPayloadBytes, "the fabric named a ceiling")

            star.joinerA.room.sendTo(star.joinerBId, ByteArray(budget))
            star.joinerA.room.broadcast(ByteArray(budget))
            testScheduler.runCurrent()

            val written = star.wireFramesFrom(star.joinerAId)
            assertAll(
                { assertTrue(budget < fabricLimit, "the room reserves room for the envelope: $budget < $fabricLimit") },
                { assertEquals(2, written.size, "both sends reached the wire") },
                {
                    assertTrue(
                        written.all { it.size <= fabricLimit },
                        "every relayed frame fits the fabric: ${written.map { it.size }} vs $fabricLimit",
                    )
                },
                {
                    assertEquals(
                        listOf(budget, budget),
                        star.joinerB.rawAppFramesFrom(star.joinerAId).map { it.size },
                        "and both arrive at the co-spoke intact",
                    )
                },
            )
        }

    /**
     * [RELAY_ENVELOPE_BUDGET] is the arithmetic the property above rests on, measured against the
     * encoding [RelayEnvelope.encode] actually produces rather than against the estimate in its
     * KDoc.
     *
     * [RelayDest.One] is the worst case — it carries a second [PeerId] — and the peer ids are the
     * only unbounded part, so the pathological row is what fixes the margin.
     */
    @Test
    fun `the reserved budget covers the envelope for realistic and pathological peer ids`() {
        val probe = 100
        fun overhead(origin: String, dest: RelayDest): Int =
            RelayEnvelope.encode(RelayEnvelope(PeerId(origin), dest, ByteArray(probe))).size - probe

        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val other = "550e8400-e29b-41d4-a716-446655440001"
        val long = "x".repeat(100)

        assertAll(
            { assertOverheadWithinBudget("short id, Everyone", overhead("a", RelayDest.Everyone)) },
            { assertOverheadWithinBudget("uuid, Everyone", overhead(uuid, RelayDest.Everyone)) },
            { assertOverheadWithinBudget("uuid pair, One", overhead(uuid, RelayDest.One(PeerId(other)))) },
            {
                assertOverheadWithinBudget(
                    "two 100-byte ids, One",
                    overhead(long, RelayDest.One(PeerId("y".repeat(100)))),
                )
            },
        )
    }

    private fun assertOverheadWithinBudget(label: String, overhead: Int) {
        assertTrue(
            overhead <= RELAY_ENVELOPE_BUDGET,
            "$label: the envelope costs $overhead B, past the $RELAY_ENVELOPE_BUDGET B reserved for it",
        )
    }

    /**
     * A channel view is a [us.tractat.kuilt.core.Seam] over a [Room] and both layers add bytes, so
     * it reserves its own header on top of the room's reservation — and reports the overflow with
     * the **caller's** numbers, not the framed ones.
     */
    @Test
    fun `a channel view reserves its own header on top of the room's budget`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.joinerA.wire.limitFrames(fabricLimit)
            val roomBudget = assertNotNull(star.joinerA.room.maxPayloadBytes)
            val view = star.joinerA.room.channel("data")
            val viewBudget = assertNotNull(view.maxPayloadBytes)

            view.sendTo(star.joinerBId, ByteArray(viewBudget))
            testScheduler.runCurrent()
            val refusal = assertFailsWith<PayloadTooLarge> { view.sendTo(star.joinerBId, ByteArray(viewBudget + 1)) }

            assertAll(
                { assertEquals(roomBudget - RoomChannel.HEADER_BYTES, viewBudget, "the channel header is reserved") },
                {
                    assertTrue(
                        star.wireFramesFrom(star.joinerAId).all { it.size <= fabricLimit },
                        "a budget-filling channel frame still fits the fabric once framed and relayed",
                    )
                },
                { assertEquals(viewBudget + 1, refusal.payloadBytes, "the refusal names what the caller passed") },
                { assertEquals(viewBudget, refusal.budgetBytes, "and the budget the caller could have read") },
            )
        }
}
