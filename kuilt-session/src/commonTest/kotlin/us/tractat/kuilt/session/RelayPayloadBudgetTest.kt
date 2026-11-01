@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
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
@OptIn(ExperimentalSerializationApi::class)
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
     * Every payload in this file is filled with this byte, and the choice decides whether the file can
     * fail (#2958). Encoded as an integer array, a byte in `0..23` or `-24..-1` costs 1 B and the rest
     * cost 2 B, so a zero-filled payload hid an envelope whose real cost doubled with its contents.
     * `0x62` costs 2 B there, which a control below keeps proving, and it is not one of
     * [RoomFramePrefix]'s reserved leading bytes, unlike `0x61`.
     */
    private fun filler(size: Int): ByteArray = ByteArray(size) { 0x62 }

    /**
     * A payload sized **exactly** to the fabric limit: it fits a direct send, and cannot fit once
     * the envelope is wrapped around it.
     */
    private fun fabricSizedPayload(): ByteArray = filler(fabricLimit)

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
                star.joinerA.room.sendTo(star.joinerBId, filler(fabricLimit))
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

            star.joinerA.room.sendTo(star.joinerBId, filler(budget))
            star.joinerA.room.broadcast(filler(budget))
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
            RelayEnvelope.encode(RelayEnvelope(PeerId(origin), dest, filler(probe))).size - probe

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

            view.sendTo(star.joinerBId, filler(viewBudget))
            testScheduler.runCurrent()
            // Sized to overflow the wire, not merely to exceed the published budget — the published
            // number under-promises deliberately, so a payload one byte past it still fits.
            val refusal = assertFailsWith<PayloadTooLarge> { view.sendTo(star.joinerBId, filler(fabricLimit)) }

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
            val overBudgetButOnTheWire = filler(fabricLimit)

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

    /**
     * A `broadcast` drop is **structural, not weather** — the same payload is over the same ceiling
     * every time — so it has to be countable off-device, not merely `debug`-logged.
     *
     * The failure this guards is a `Quilter` whose anti-entropy frame outgrows the budget as state
     * accretes: it stops converging permanently, with a live roster and flowing heartbeats and
     * nothing above the noise floor to say why. Same reasoning as [SeamRoom.relayForwardsDropped]
     * (#1781): absence has to be diagnosable.
     */
    @Test
    fun `an over-budget broadcast is counted rather than merely dropped`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.joinerA.wire.limitFrames(fabricLimit)

            star.joinerA.room.broadcast(appPayload("in-budget"))
            testScheduler.runCurrent()
            val beforeAnyDrop = star.joinerA.oversizeFramesDropped()

            star.joinerA.room.broadcast(filler(fabricLimit))
            star.joinerA.room.broadcast(filler(fabricLimit))
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(0L, beforeAnyDrop, "a delivered frame counts as nothing") },
                { assertEquals(2L, star.joinerA.oversizeFramesDropped(), "both refusals are counted") },
            )
        }

    /**
     * The host forwards a spoke's envelope **verbatim** onto its own fabric, which can be tighter
     * than the one the origin checked against — a `framed()` link with a smaller `maxFrameSize`, or
     * a composite whose minimum is set by a narrower ply.
     *
     * Unchecked, that overflow is *destructive* rather than lossy: `MeshSeam.sendTo` routes the
     * failure into `removePeer`, so a healthy recipient is evicted as though its link had died —
     * and swallows the throwable, so the forward writer's own `catch` never fires and nothing is
     * logged as oversize. If the origin retries (`Quilter` anti-entropy re-sending the same delta)
     * the recipient is kicked again on every reconnect. A named drop is in contract; an eviction is
     * not.
     */
    @Test
    fun `the host drops a forward its own hop cannot carry`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            // Only the HOST's fabric is bounded, so the spoke's own check passes and the frame
            // reaches the host — which is exactly the heterogeneous-ceiling case.
            val star = relayStar(coJoiners = 2)
            val hopCeiling = 512
            star.host.wire.limitFrames(hopCeiling)

            star.joinerA.room.broadcast(filler(hopCeiling))
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(star.host.oversizeFramesDropped() > 0, "the host refused the forward it could not carry") },
                {
                    // The discriminator. Without the guard the host still *attempts* the send —
                    // the tap records the frame and only then raises — so an empty log is what
                    // proves the refusal happened before the fabric ever saw it.
                    assertTrue(
                        star.wireFramesTo(star.joinerBId).isEmpty(),
                        "and never handed it to the fabric: ${star.wireFramesTo(star.joinerBId).map { it.size }}",
                    )
                },
            )
        }

    /**
     * The rig for everything in this file, kept honest against the encoding that defect lived in: if
     * [filler] stopped costing double there, each test here could pass with the defect back.
     */
    @Test
    fun `the filler would cost double as an integer array`() {
        val integerArrays = Cbor { ignoreUnknownKeys = true }
        val size = 100
        fun encoded(payload: ByteArray) =
            integerArrays.encodeToByteArray(RelayEnvelope(PeerId("a"), RelayDest.Everyone, payload)).size

        assertEquals(size, encoded(filler(size)) - encoded(ByteArray(size)), "one extra byte per payload byte")
    }

    /** What an envelope costs must not depend on what it carries, or no flat reservation can cover it. */
    @Test
    fun `an envelope costs the same whatever bytes it carries`() {
        val size = 100
        fun encoded(payload: ByteArray) =
            RelayEnvelope.encode(RelayEnvelope(PeerId("a"), RelayDest.Everyone, payload)).size

        assertEquals(encoded(ByteArray(size)), encoded(filler(size)), "zeros and non-zeros must cost the same")
    }

    /** The issue's reproducer: a relayed member sizes channel chunks to the budget its view publishes. */
    @Test
    fun `a relayed channel payload filling the budget is delivered and the refusal is exact`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            assertBudgetHolds(star, star.joinerA, channelSurfaceOf(star.joinerA, star.joinerB, star.joinerBId))
        }

    @Test
    fun `a relayed room payload filling the budget is delivered and the refusal is exact`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            assertBudgetHolds(star, star.joinerA, roomSurfaceOf(star.joinerA, star.joinerB, star.joinerBId))
        }

    @Test
    fun `a direct channel payload filling the budget is delivered and the refusal is exact`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            assertBudgetHolds(star, star.host, channelSurfaceOf(star.host, star.joinerA, star.joinerAId))
        }

    @Test
    fun `a direct room payload filling the budget is delivered and the refusal is exact`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            assertBudgetHolds(star, star.host, roomSurfaceOf(star.host, star.joinerA, star.joinerAId))
        }

    /** One sending surface and how to read what its recipient got. */
    private class BudgetSurface(
        val budget: () -> Int?,
        val send: suspend (ByteArray) -> Unit,
        val delivered: () -> List<Int>,
    )

    private fun TestScope.channelSurfaceOf(sender: StarMember, receiver: StarMember, peer: PeerId): BudgetSurface {
        val view = sender.room.channel("data")
        val sizes = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            receiver.room.channel("data").incoming.collect { sizes += it.payloadSize }
        }
        return BudgetSurface({ view.maxPayloadBytes }, { view.sendTo(peer, it) }, { sizes.toList() })
    }

    private fun TestScope.roomSurfaceOf(sender: StarMember, receiver: StarMember, peer: PeerId): BudgetSurface {
        val senderId = sender.room.selfId
        return BudgetSurface(
            { sender.room.maxPayloadBytes },
            { sender.room.sendTo(peer, it) },
            { receiver.rawAppFramesFrom(senderId).map { it.size } },
        )
    }

    /**
     * The contract a caller chunking to the budget relies on, at the fabric's ceiling and on a real
     * route. A payload of exactly the published budget is delivered. The published number is
     * deliberately a promise rather than the refusal threshold (see `SeamRoom.maxPayloadBytes`), so
     * "one byte more is refused" is asserted at the threshold the refusal itself reports: filling that
     * delivers, and one byte past it is refused.
     *
     * Refusals are caught and asserted on, so a defect reds as an assertion here rather than as a
     * [PayloadTooLarge] escaping the test.
     */
    private suspend fun TestScope.assertBudgetHolds(star: RelayStar, sender: StarMember, surface: BudgetSurface) {
        star.host.wire.limitFrames(fabricLimit)
        sender.wire.limitFrames(fabricLimit)
        testScheduler.runCurrent()

        suspend fun refusalOf(size: Int): PayloadTooLarge? = try {
            surface.send(filler(size))
            null
        } catch (refused: PayloadTooLarge) {
            refused
        }

        val budget = assertNotNull(surface.budget(), "the fabric named a ceiling")
        val atBudget = refusalOf(budget)
        testScheduler.runCurrent()
        val threshold = assertNotNull(refusalOf(fabricLimit + 1), "a payload past the ceiling is over on any route").budgetBytes
        val atThreshold = refusalOf(threshold)
        val pastThreshold = refusalOf(threshold + 1)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(null, atBudget, "a payload of exactly the published budget ($budget B) must be accepted") },
            { assertTrue(budget <= threshold, "the budget must be a promise the route can keep: $budget B vs $threshold B") },
            { assertEquals(null, atThreshold, "the threshold a refusal reports ($threshold B) must fit") },
            { assertNotNull(pastThreshold, "and one byte past it must be refused") },
            {
                assertEquals(
                    listOf(budget, threshold),
                    surface.delivered(),
                    "both accepted payloads must arrive intact and nothing else",
                )
            },
        )
    }

    /** The envelope's real cost for [origin]/[dest] at a payload of exactly [payloadBytes]. */
    private fun relayOverhead(origin: PeerId, dest: RelayDest, payloadBytes: Int): Int =
        RelayEnvelope.encode(RelayEnvelope(origin, dest, filler(payloadBytes))).size - payloadBytes
}
