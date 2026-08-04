@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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
}
