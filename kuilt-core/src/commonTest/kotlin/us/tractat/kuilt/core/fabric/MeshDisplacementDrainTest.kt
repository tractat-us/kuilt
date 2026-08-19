@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The graceful displacement drain and its receiver ordering hold (#2474) — the `MeshSeam` port of
 * `:kuilt-nw`'s D+ (#2425).
 *
 * ## What was broken
 * #2474 opened on the premise that `MeshSeam` "already drains to EOF on a well-behaved `Connection`",
 * leaving ordering as the only real gap. That is right for the **replace** arm and wrong for the
 * **keep** arm, whose loser never had a read loop at all — `addLink` closed it and launched no
 * reader, so its entire tail went on the floor however orderly its close was. That is the half #2457
 * records as the one everybody misses, and it is pinned by [theKeepArmsLoserIsDrainedToo].
 *
 * The premise is also conditional on the wrapper rather than on the transport: every mesh link is a
 * [singleCollection], whose `close` cancels its republishing pump **before** closing the delegate, so
 * what survives depends on whether that pump had run — not on how carefully the transport flushes.
 * The drain removes the dependency altogether: nothing is closed until the remote's goodbye. Held
 * against a `Connection` that flushes nothing at all by `MeshDisplacementDrainConformanceSuite`.
 *
 * ## Determinism
 * `StandardTestDispatcher` throughout, with the dedup tiebreak steered by hand-picked far-end nonces
 * (all-`0x00` beats all-`0xFF` under `canonicalLinkNonce`). No `advanceUntilIdle()`: `runCurrent()`
 * between steps, and an explicit `advanceTimeBy` only where the drain BOUND is the subject.
 */
class MeshDisplacementDrainTest {

    /**
     * The replace arm: the peer is published on link A, link B displaces it, and everything the
     * remote wrote on A **after** the swap is still delivered — ahead of B's frames, in send order.
     *
     * The interleave is the whole test. `TAIL_1` and `AFTER_B` are handed to their links in that
     * order and a scheduler step is taken between `AFTER_B` and `TAIL_2`, so a seam with the drain but
     * NO ordering hold delivers `AFTER_B` in the middle. A seam with neither delivers no tail at all.
     */
    @Test
    fun aDisplacedLosersTailIsDeliveredBeforeTheWinnersFrames() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = rig()
        val received = rig.collectIncoming()

        rig.admit(rig.linkA, nonce = HIGH_NONCE)
        rig.admit(rig.linkB, nonce = LOW_NONCE) // wins: B displaces A

        assertTrue(rig.drained.isEmpty(), "precondition: the drain must still be open — nothing has said goodbye")

        rig.linkA.farEnd.send(MeshWire.encodeData(TAIL_1))
        rig.linkB.farEnd.send(MeshWire.encodeData(AFTER_B))
        runCurrent()
        rig.linkA.farEnd.send(MeshWire.encodeData(TAIL_2))
        rig.linkA.farEnd.send(MeshWire.encodeGoodbye())
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(TAIL_1.toList(), TAIL_2.toList(), AFTER_B.toList()),
                    received.map { it.toByteArray().toList() },
                    "the drained link's tail must arrive COMPLETE and FIRST: a missing tail frame is the " +
                        "abrupt close, and AFTER_B in the middle is the missing ordering hold",
                )
            },
            { assertTrue(received.all { it.sender == PEER }, "every frame stays attributed to the peer") },
            {
                assertEquals(
                    listOf(1L, 2L, 3L),
                    received.map { it.sequence },
                    "sequence is stamped at RELEASE time, so stamped order is delivery order",
                )
            },
            {
                assertEquals(
                    listOf(MeshDisplacement.Drained(PEER, MeshDisplacement.Arm.Replace, GOODBYE, 2)),
                    rig.drained,
                    "the drain must end on the remote's GOODBYE, on the replace arm, having carried both tail frames",
                )
            },
        )
    }

    /**
     * The keep arm — the half `MeshSeam` failed hardest: its loser was closed with no reader ever
     * attached, so nothing it had sent could be delivered by any close semantics whatsoever.
     *
     * Both ends dedup onto the same physical link, so *our* keep-arm loser is the *remote's*
     * replace-arm loser, with its window frames in flight toward us.
     */
    @Test
    fun theKeepArmsLoserIsDrainedToo() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = rig()
        val received = rig.collectIncoming()

        rig.admit(rig.linkA, nonce = LOW_NONCE) // A wins and stays
        rig.admit(rig.linkB, nonce = HIGH_NONCE) // B loses on arrival — the keep arm

        rig.linkB.farEnd.send(MeshWire.encodeData(TAIL_1))
        rig.linkB.farEnd.send(MeshWire.encodeGoodbye())
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(TAIL_1.toList()),
                    received.map { it.toByteArray().toList() },
                    "a keep-arm loser is READ before it is disposed of; before #2474 nothing ever collected it",
                )
            },
            {
                assertEquals(
                    listOf(MeshDisplacement.Drained(PEER, MeshDisplacement.Arm.Keep, GOODBYE, 1)),
                    rig.drained,
                    "and the drain is reported on the keep arm too",
                )
            },
        )
    }

    /**
     * The bound is a **zombie backstop, not the mechanism**: a remote that never says goodbye must
     * not hold the peer's ordering hold — and so the peer's live traffic — open forever.
     *
     * The frame sent on the winner is buffered for the whole drain and delivered when the bound
     * fires, which is also what proves the hold was armed at all: without it the frame would have
     * been delivered on the first `runCurrent()`.
     */
    @Test
    fun aLoserThatNeverSaysGoodbyeIsReleasedByTheBound() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = rig(drainBound = SHORT_DRAIN_BOUND)
        val received = rig.collectIncoming()

        rig.admit(rig.linkA, nonce = HIGH_NONCE)
        rig.admit(rig.linkB, nonce = LOW_NONCE)

        rig.linkB.farEnd.send(MeshWire.encodeData(AFTER_B))
        runCurrent()
        assertTrue(received.isEmpty(), "rig: the hold is armed, so the winner's frame is held, not delivered")

        testScheduler.advanceTimeBy(SHORT_DRAIN_BOUND + 1.milliseconds)
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(AFTER_B.toList()),
                    received.map { it.toByteArray().toList() },
                    "the bound must release the hold, or one silent peer stalls another peer's live traffic",
                )
            },
            {
                assertEquals(
                    listOf(MeshDisplacement.Drained(PEER, MeshDisplacement.Arm.Replace, BOUND, 0)),
                    rig.drained,
                    "and it must say it was BACKSTOPPED rather than terminated in band",
                )
            },
        )
    }

    /**
     * The hold is bounded, and at the bound it **releases early and accepts the reorder** rather than
     * backpressuring — because suspending there is the one option that would wedge the seam (see
     * `MeshSeam.stageInbound`). The trade is reported, never silent.
     */
    @Test
    fun anOverflowingHoldReleasesEarlyAndSaysSo() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val capacity = 2
        val rig = rig(orderingHoldCapacity = capacity)
        val received = rig.collectIncoming()

        rig.admit(rig.linkA, nonce = HIGH_NONCE)
        rig.admit(rig.linkB, nonce = LOW_NONCE)

        // One more than the hold can take, while the drained link has sent nothing at all.
        repeat(capacity + 1) { i -> rig.linkB.farEnd.send(MeshWire.encodeData(byteArrayOf(0x50, i.toByte()))) }
        runCurrent()

        assertAll(
            {
                assertEquals(
                    (0..capacity).map { listOf(0x50.toByte(), it.toByte()) },
                    received.map { it.toByteArray().toList() },
                    "at the bound the hold must let everything through rather than stall the read loop",
                )
            },
            {
                assertEquals(
                    listOf(MeshDisplacement.OrderingHoldOverflowed(PEER, capacity, capacity)),
                    rig.overflows,
                    "trading the send-order promise for liveness is a reportable condition, not a silent one",
                )
            },
            { assertTrue(rig.drained.isEmpty(), "the drain itself is untouched by the hold giving up") },
        )
    }

    /**
     * The deadlock the buffer-and-continue shape exists to make impossible, driven rather than argued.
     *
     * The hold is armed and **filled to just under its cap** with live-link frames before the drained
     * link says anything. If staging a held frame suspended — the obvious "wait for the drain, then
     * deliver" implementation — it would do so holding the one mutex the drained link's own read loop
     * needs to deliver its tail AND the one `releaseOrderingHold` needs to run, so the goodbye that
     * would release the hold could never be processed. This test then observes an unfinished drain and
     * no delivery, and fails by assertion rather than by hanging.
     */
    @Test
    fun aFullHoldStillLetsThroughTheGoodbyeThatReleasesIt() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val capacity = 8
        val rig = rig(orderingHoldCapacity = capacity)
        val received = rig.collectIncoming()

        rig.admit(rig.linkA, nonce = HIGH_NONCE)
        rig.admit(rig.linkB, nonce = LOW_NONCE)

        repeat(capacity) { i -> rig.linkB.farEnd.send(MeshWire.encodeData(byteArrayOf(0x60, i.toByte()))) }
        runCurrent()
        assertAll(
            { assertTrue(received.isEmpty(), "rig: the hold really is holding — nothing has been delivered yet") },
            {
                assertTrue(
                    rig.overflows.isEmpty(),
                    "rig: and it is FULL but has not overflowed, which is the case under test — an overflow " +
                        "here would have released the hold for an unrelated reason",
                )
            },
        )

        rig.linkA.farEnd.send(MeshWire.encodeData(TAIL_1))
        rig.linkA.farEnd.send(MeshWire.encodeGoodbye())
        runCurrent()

        assertEquals(
            listOf(TAIL_1.toList()) + (0 until capacity).map { listOf(0x60.toByte(), it.toByte()) },
            received.map { it.toByteArray().toList() },
            "a full hold must not be able to block the drained link's own tail or its goodbye — that is a " +
                "permanent wedge, and it is what a suspending hold would produce here",
        )
    }

    /**
     * A **second** drain to the same peer keeps its own hold: ending the first must not flush the
     * second's buffer ahead of the second's tail.
     *
     * This is the shape review found in the first cut of the mechanism. There the release asked
     * `lock` "is any other drain to this peer still running?" and then acted on that answer *after*
     * the suspending close — so a redial that registered a second drain in the gap had its hold
     * adopted and then torn down, its frames delivered out of order, silently and with no overflow
     * report. Keying each claim on the link that armed it makes that unrepresentable, and this is
     * what says so: with two drains in flight, releasing the first delivers nothing at all.
     */
    @Test
    fun endingOneDrainDoesNotReleaseASiblingDrainsHold() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = rig()
        val received = rig.collectIncoming()

        // Three links to one peer, admitted in descending nonce order so each new one displaces the
        // incumbent: A published, B displaces A, C displaces B. A and B are both draining, and both
        // hold a claim on the peer's ONE ordering hold; C is live.
        rig.admit(rig.linkA, nonce = nonceOf(0xFF))
        rig.admit(rig.linkB, nonce = nonceOf(0x80))
        rig.admit(rig.linkC, nonce = nonceOf(0x00))
        assertEquals(
            2,
            rig.mesh.peers.value.size,
            "precondition: one peer over three links — the rig's nonces must have produced two displacements",
        )

        rig.linkC.farEnd.send(MeshWire.encodeData(AFTER_B))
        runCurrent()
        assertTrue(received.isEmpty(), "rig: the hold is armed, so the live link's frame is buffered")

        // End ONE of the two drains. The other is still waiting on its tail, so nothing may be flushed.
        rig.linkA.farEnd.send(MeshWire.encodeGoodbye())
        runCurrent()

        assertAll(
            {
                assertEquals(
                    1,
                    rig.drained.size,
                    "rig: exactly one of the two drains has ended — if both did, this asserts nothing",
                )
            },
            {
                assertTrue(
                    received.isEmpty(),
                    "the surviving drain's hold must still be held: releasing it here would deliver " +
                        "${received.size} live-link frame(s) ahead of a tail that has not arrived, which " +
                        "is precisely the reordering the hold exists to prevent — got $received",
                )
            },
        )

        // ...and when the LAST drain ends, everything comes through.
        rig.linkB.farEnd.send(MeshWire.encodeData(TAIL_1))
        rig.linkB.farEnd.send(MeshWire.encodeGoodbye())
        runCurrent()

        assertEquals(
            listOf(TAIL_1.toList(), AFTER_B.toList()),
            received.map { it.toByteArray().toList() },
            "the last drain to end releases the hold, tail first",
        )
    }

    /** Closing a seam mid-drain must not leak the drained connection, which is absent from `links`. */
    @Test
    fun tearingDownMidDrainStillClosesTheDrainingLink() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val rig = rig()
        rig.admit(rig.linkA, nonce = HIGH_NONCE)
        rig.admit(rig.linkB, nonce = LOW_NONCE)
        assertTrue(rig.drained.isEmpty(), "precondition: the drain is still open when the seam is closed")

        rig.mesh.close()
        runCurrent()

        assertTrue(
            rig.linkA.closed,
            "the draining link is deliberately NOT in `links`, so a teardown that snapshots only that map " +
                "cancels its read loop and leaves the connection open forever",
        )
    }

    // ── rig ─────────────────────────────────────────────────────────────────────

    /**
     * A hub mesh plus two connections to ONE peer, so the dedup can be driven a step at a time.
     *
     * The far ends are hand-driven rather than being a second real mesh: what is under test is this
     * seam's behaviour against an arbitrary remote's byte stream, including streams a conforming
     * remote would not produce (a loser that never says goodbye, a live link that outruns a tail).
     */
    private suspend fun TestScope.rig(
        drainBound: Duration = LONG_DRAIN_BOUND,
        orderingHoldCapacity: Int = DEFAULT_MESH_ORDERING_HOLD_CAPACITY,
    ): Rig {
        val drained = mutableListOf<MeshDisplacement.Drained>()
        val overflows = mutableListOf<MeshDisplacement.OrderingHoldOverflowed>()
        val mesh = hubMesh(
            SELF,
            emptyList(),
            StandardTestDispatcher(testScheduler),
            Random(0),
            drainBound = drainBound,
            orderingHoldCapacity = orderingHoldCapacity,
            onDisplacement = { event ->
                when (event) {
                    is MeshDisplacement.Drained -> drained += event
                    is MeshDisplacement.OrderingHoldOverflowed -> overflows += event
                }
            },
        )
        return Rig(this, mesh, drained, overflows)
    }

    private class Rig(
        private val scope: TestScope,
        val mesh: Mesh,
        val drained: List<MeshDisplacement.Drained>,
        val overflows: List<MeshDisplacement.OrderingHoldOverflowed>,
    ) {
        val linkA = newLink()
        val linkB = newLink()

        /** A third link to the same peer, for the two-drains-in-flight case. */
        val linkC = newLink()

        private fun newLink(): RigLink {
            val (mine, theirs) = connectionPair()
            return RigLink(farEnd = theirs, delegate = mine)
        }

        /** Collect `incoming` for the whole test — `incoming` is single-collection (ADR-034). */
        fun collectIncoming(): List<Swatch> {
            val received = mutableListOf<Swatch>()
            scope.backgroundScope.launch { mesh.incoming.collect { received += it } }
            scope.testScheduler.runCurrent()
            return received
        }

        /** Admit [link] and drive its far end through the handshake, claiming [PEER] with [nonce]. */
        fun admit(link: RigLink, nonce: ByteArray) {
            scope.backgroundScope.launch { mesh.addLink(link) }
            scope.backgroundScope.launch {
                link.farEnd.incoming.collect { frame ->
                    if (MeshWire.decode(frame) is MeshWireFrame.Hello) {
                        link.farEnd.send(MeshWire.encodeHello(PEER, nonce))
                    }
                }
            }
            scope.testScheduler.runCurrent()
        }
    }

    /** One end of a [connectionPair] plus a close flag, so a leaked connection is observable. */
    private class RigLink(val farEnd: Connection, private val delegate: Connection) : Connection {
        var closed: Boolean = false
            private set

        override suspend fun send(frame: ByteArray) = delegate.send(frame)
        override val incoming: Flow<ByteArray> = delegate.incoming
        override val maxFrameBytes: Int? get() = delegate.maxFrameBytes
        override suspend fun close() {
            closed = true
            delegate.close()
        }
    }

    private companion object {
        val SELF = PeerId("self")
        val PEER = PeerId("peer-1")

        /**
         * A full-width nonce of one repeated byte. `canonicalLinkNonce` hex-encodes both endpoints'
         * nonces, sorts the two strings and joins them, and the local nonces come from a seeded
         * `Random(0)` — so a strictly descending sequence of far-end bytes gives a strictly descending
         * sequence of canonical values, i.e. each new link displaces the incumbent.
         */
        fun nonceOf(byte: Int) = ByteArray(MESH_NONCE_BYTES) { byte.toByte() }

        /** All-`0x00` beats all-`0xFF`. */
        val LOW_NONCE = nonceOf(0x00)
        val HIGH_NONCE = nonceOf(0xFF)

        val TAIL_1 = byteArrayOf(0x0a, 1)
        val TAIL_2 = byteArrayOf(0x0a, 2)
        val AFTER_B = byteArrayOf(0x0b, 1)

        val GOODBYE = MeshDisplacement.Outcome.Goodbye
        val BOUND = MeshDisplacement.Outcome.Bound

        /** Long enough that no test reaches it by accident; the bound test injects its own. */
        val LONG_DRAIN_BOUND = 10.seconds
        val SHORT_DRAIN_BOUND = 50.milliseconds
    }
}
