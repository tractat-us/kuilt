package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [HeddleNode.schedule] at the lifetime limit (design §7.1, #3475): a grant that would carry an
 * edge's effective issuance past the watermark, `Long.MAX_VALUE − LIFETIME_HEADROOM`, on this peer's
 * view, is answered [ScheduleOutcome.LifetimeExhausted] and publishes nothing, where it used to throw.
 *
 * The counter is rigged rather than run up by grants: a merged write ([rigIssuance]) gives `e1` an
 * issuance slot from a replica that hands every unit straight back, so the edge's effective issuance
 * reads high while no peer's holdings, and no position, move. Every row asserts the issuance it
 * rigged before it schedules.
 */
class HeddleNodeLifetimeTest {

    private val root = GroupId("root")
    private val e1 = AttachmentId("e1")
    private val e2 = AttachmentId("e2")
    private val topology = listOf(
        AttachmentRecord(e1, root, GroupId("g1"), Weight.ONE),
        AttachmentRecord(e2, root, GroupId("g2"), Weight.ONE),
    )

    /** One grant of the harness's quantum, and a demand for exactly one. */
    private val oneGrant = Demand(targetOutstanding = QUANTUM, maximumUsefulGrant = QUANTUM)

    private val hungry = Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 1_000L)

    /** Give `e1` an issuance of [issued] from a replica that returned all of it, through [peer]'s merged ledger. */
    private fun rigIssuance(h: StaticCluster, peer: Int, issued: Long) {
        h.peers[peer].node.asControlSink().publish(
            Patch(
                EntitlementLedger.of(
                    issued = mapOf(e1 to GCounter.of(FAR to issued)),
                    returned = mapOf(e1 to GCounter.of(FAR to issued)),
                ),
            ),
        )
        h.pump()
    }

    private fun HeddleNode.issued(): Long = checkNotNull(ledger.value.edge(e1)) { "e1 is active" }.issued

    private suspend fun TestScope.cluster(peers: Int): StaticCluster =
        staticCluster(root, peers, mint = (0 until peers).associateWith { 100L }, topology = topology, quantum = QUANTUM)
            .also { it.pump() }

    /** At the watermark: the first grant would pass it, so nothing is written, and the next call says the same. */
    @Test
    fun aScheduleAtTheWatermarkAnswersLifetimeExhaustedAndPublishesNothing() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val h = cluster(peers = 1)
        val node = h.peers[0].node
        rigIssuance(h, 0, issued = WATERMARK - QUANTUM + 1L)
        node.advertise(e1, hungry)
        val rigged = node.issued()
        val before = node.ledger.value
        val outcome = node.schedule(root)
        val again = node.schedule(root)
        h.pump()
        assertAll(
            { assertEquals(WATERMARK - QUANTUM + 1L, rigged, "the rig: one grant would pass the watermark by a unit") },
            { assertEquals(ScheduleOutcome.LifetimeExhausted(root), outcome) },
            { assertEquals(ScheduleOutcome.LifetimeExhausted(root), again, "and again on the next call") },
            { assertEquals(before, node.ledger.value, "nothing was published") },
        )
    }

    /** Three grants fit exactly: the call makes them and answers `Delegated`, and the next call answers the refusal. */
    @Test
    fun aRefusalMetAfterGrantsAnswersDelegatedAndTheNextCallTheRefusal() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val h = cluster(peers = 1)
        val node = h.peers[0].node
        rigIssuance(h, 0, issued = WATERMARK - 3 * QUANTUM)
        node.advertise(e1, hungry)
        val rigged = node.issued()
        val first = node.schedule(root)
        val second = node.schedule(root)
        h.pump()
        assertAll(
            { assertEquals(WATERMARK - 3 * QUANTUM, rigged, "the rig: room for three grants exactly") },
            { assertEquals(ScheduleOutcome.Delegated(3), first, "the grants that fit landed") },
            { assertEquals(WATERMARK, node.issued(), "and carried the edge to the watermark") },
            { assertEquals(ScheduleOutcome.LifetimeExhausted(root), second) },
        )
    }

    /**
     * Two peers cut off from each other each grant `e1` once under their own view, which has room for
     * one. Merged on heal, the edge reads past the watermark: the headroom absorbs it, every read
     * still answers, and the next schedule is refused.
     */
    @Test
    fun twoPeersGrantingUnderTheirOwnViewsMergePastTheWatermarkAndEveryReadAnswers() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val h = cluster(peers = 2)
        val (a, b) = h.peers.map { it.node }
        rigIssuance(h, 0, issued = WATERMARK - QUANTUM)
        val riggedOnB = b.issued()
        h.partition(1)
        a.advertise(e1, oneGrant)
        b.advertise(e1, oneGrant)
        val onA = a.schedule(root)
        val onB = b.schedule(root)
        h.pump()
        h.heal(1)
        h.pump(600)
        a.advertise(e1, hungry)
        val merged = a.issued()
        assertAll(
            { assertEquals(WATERMARK - QUANTUM, riggedOnB, "the rig: both peers saw room for one grant") },
            { assertEquals(ScheduleOutcome.Delegated(1), onA) },
            { assertEquals(ScheduleOutcome.Delegated(1), onB) },
            { assertEquals(WATERMARK + QUANTUM, merged, "the merge is past the watermark") },
            { assertEquals(merged, b.issued(), "on both peers") },
            { assertTrue(a.parentVirtualTime(root) != null, "the virtual time still reads") },
            { a.boundMetrics(root) },
            { assertEquals(ScheduleOutcome.LifetimeExhausted(root), a.schedule(root), "and the next schedule is refused") },
        )
    }

    private companion object {
        const val QUANTUM: Long = 10L
        val WATERMARK: Long = Long.MAX_VALUE - LocalHeddle.LIFETIME_HEADROOM
        val FAR: ReplicaId = ReplicaId("far")
    }
}
