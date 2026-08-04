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

    /**
     * What the addressed send reports instead: the frame's **actual** envelope cost, and a budget
     * reconstructed from it that really would have fitted.
     *
     * The reservation reported here is what *this* envelope cost for *these two* peer ids, not the
     * flat [RELAY_ENVELOPE_BUDGET] — refusal is measured on the encoded frame, so the numbers
     * handed back are the ones the wire actually saw.
     */
    @Test
    fun `an over-budget addressed send names the payload and the budget`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.joinerA.wire.limitFrames(fabricLimit)
            val envelope = relayOverhead(star.joinerAId, RelayDest.One(star.joinerBId), fabricLimit)

            val refusal = assertFailsWith<PayloadTooLarge> {
                star.joinerA.room.sendTo(star.joinerBId, ByteArray(fabricLimit))
            }

            assertAll(
                { assertEquals(fabricLimit, refusal.payloadBytes, "the payload that was refused") },
                { assertEquals(envelope, refusal.reservedBytes, "what this envelope actually cost") },
                {
                    assertEquals(
                        fabricLimit - envelope,
                        refusal.budgetBytes,
                        "and a budget reconstructed from it, which would have fitted",
                    )
                },
            )
        }

    /**
     * The published budget does **not** move with routing — the design call `SeamRoom.maxPayloadBytes`
     * argues for, and the one every other test in this file is blind to.
     *
     * Every `relayStar` spoke has a diverged roster by construction, so `relayHostOrNull()` is
     * non-null for all of them and a route-conditional budget would pass unnoticed. The **host** is
     * the counter-example: it never relays (`relayHostOrNull()` returns `null` on role alone), so a
     * budget that subtracted only while relaying would report the raw ceiling here. Asserting host
     * and spoke agree over the same fabric ceiling is the property itself.
     *
     * Mutation-checked: making the subtraction conditional on `relayHostOrNull() != null` reddens
     * this test and leaves the other six green.
     */
    @Test
    fun `the published budget is the same whether or not this member relays`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.host.wire.limitFrames(fabricLimit)
            star.joinerA.wire.limitFrames(fabricLimit)

            val hostBudget = assertNotNull(star.host.room.maxPayloadBytes, "the host's fabric named a ceiling")
            val spokeBudget = assertNotNull(star.joinerA.room.maxPayloadBytes, "the spoke's fabric named a ceiling")

            // Observed, not assumed: prove the host really is the non-relaying member by watching
            // its wire carry a raw payload rather than an envelope. Snapshotted BEFORE the spoke
            // sends, because the host's wire also carries the forwards it makes *as* the relay —
            // those are the host relaying for somebody else, not for itself.
            star.host.room.broadcast(appPayload("host-direct"))
            testScheduler.runCurrent()
            val hostFrames = star.wireFramesFrom(star.hostId)

            star.joinerA.room.broadcast(appPayload("spoke-relayed"))
            testScheduler.runCurrent()
            val spokeFrames = star.wireFramesFrom(star.joinerAId)

            assertAll(
                {
                    assertTrue(
                        hostFrames.isNotEmpty() && hostFrames.none { RelayEnvelope.isRelayFrame(it) },
                        "the host wrote only direct frames: ${hostFrames.size} frame(s)",
                    )
                },
                {
                    assertTrue(
                        spokeFrames.isNotEmpty() && spokeFrames.all { RelayEnvelope.isRelayFrame(it) },
                        "while the spoke relayed every one: ${spokeFrames.size} frame(s)",
                    )
                },
                {
                    assertEquals(
                        fabricLimit - RELAY_ENVELOPE_BUDGET,
                        hostBudget,
                        "the host reserves the envelope it will never send",
                    )
                },
                { assertEquals(hostBudget, spokeBudget, "so a relaying spoke publishes the same number") },
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
     * it reserves its own header on top of the room's reservation — and re-expresses the room's
     * refusal with the **caller's** numbers, not the framed ones.
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
            // Sized to overflow the wire, not merely to exceed the published budget — the published
            // number under-promises deliberately, so a payload one byte past it still fits.
            val refusal = assertFailsWith<PayloadTooLarge> { view.sendTo(star.joinerBId, ByteArray(fabricLimit)) }

            assertAll(
                { assertEquals(roomBudget - RoomChannel.HEADER_BYTES, viewBudget, "the channel header is reserved") },
                {
                    assertTrue(
                        star.wireFramesFrom(star.joinerAId).all { it.size <= fabricLimit },
                        "a budget-filling channel frame still fits the fabric once framed and relayed",
                    )
                },
                { assertEquals(fabricLimit, refusal.payloadBytes, "the refusal names what the caller passed") },
                {
                    assertTrue(
                        refusal.reservedBytes > RoomChannel.HEADER_BYTES,
                        "and charges the channel header on top of the room's own reservation: " +
                            "${refusal.reservedBytes} B",
                    )
                },
                {
                    assertEquals(
                        fabricLimit - refusal.reservedBytes,
                        refusal.budgetBytes,
                        "so the budget it reports is one the caller could have filled",
                    )
                },
            )
        }

    /**
     * The exactness that keeps a **non-relaying** room working: a payload above the published budget
     * but still inside the fabric's ceiling is delivered, because nothing wraps a direct send.
     *
     * Enforcing the published reservation on this path would silently stop a full-mesh room — the
     * whole band `(ceiling − RELAY_ENVELOPE_BUDGET, ceiling]` would vanish, and on any fabric whose
     * ceiling is under the reservation, everything would.
     */
    @Test
    fun `a direct send is charged no envelope it does not pay`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.host.wire.limitFrames(fabricLimit)
            val budget = assertNotNull(star.host.room.maxPayloadBytes)
            val overBudgetButOnTheWire = ByteArray(fabricLimit)

            star.host.room.sendTo(star.joinerAId, overBudgetButOnTheWire)
            star.host.room.broadcast(overBudgetButOnTheWire)
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(fabricLimit > budget, "the payload is past the published budget: $fabricLimit > $budget") },
                {
                    assertEquals(
                        listOf(fabricLimit, fabricLimit),
                        star.wireFramesFrom(star.hostId).map { it.size },
                        "yet both sends reached the wire, unwrapped and unrefused",
                    )
                },
                {
                    assertEquals(
                        listOf(fabricLimit, fabricLimit),
                        star.joinerA.rawAppFramesFrom(star.hostId).map { it.size },
                        "and were delivered",
                    )
                },
            )
        }

    /** The envelope's real cost for [origin]/[dest] at a payload of exactly [payloadBytes]. */
    private fun relayOverhead(origin: PeerId, dest: RelayDest, payloadBytes: Int): Int =
        RelayEnvelope.encode(RelayEnvelope(origin, dest, ByteArray(payloadBytes))).size - payloadBytes
}
