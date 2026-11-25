package us.tractat.kuilt.heddle

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A heddle node acts on what a peer's detector reports only while that detector is still the one
 * watching the peer (#3672). A peer that goes silent long enough is Lost, its detector is terminal,
 * and a committed enrollment replaces it ([HeddleNode.remonitorOnEnrollment]). Whatever the old
 * detector's collector still had in hand then reaches a node whose peer is watched by a new one.
 *
 * On one thread nothing reaches that gap: the collector runs nothing that suspends between taking
 * an event and the node's locked section about it. On a multi-threaded dispatcher a collector that
 * loses its thread there hands the event over late. Two seams reach the gap in virtual time:
 * [HeddleNode.afterLivenessDecisionForTest] runs an enrollment at the point where a collector can
 * lose its thread after the decision, and [HeddleNode.handleDetectorEventForTest] hands over an
 * event with the detector that took it.
 *
 * Each identity test ends with a control: the same event, from the current detector, does what an
 * event does. So a stale event that does nothing is the check refusing it, not a seam that reaches
 * nothing.
 */
class HeddleStaleDetectorEventTest {
    private val root = GroupId("root")
    private val topology = listOf(
        AttachmentRecord(AttachmentId("e1"), root, GroupId("g1"), Weight.ONE),
        AttachmentRecord(AttachmentId("e2"), root, GroupId("g2"), Weight.ONE),
    )

    /**
     * The peer's detector reports it Lost, and its enrollment lands while the collector is between
     * the node's locked section and the rest of its work. The enrollment attaches a fresh detector
     * and clears the peer. Before #3672 the collector then marked the peer unreachable, behind a new
     * detector that is healthy and, reporting only transitions, would never clear it.
     */
    @Test
    fun anEnrollmentLandingAsALossIsAppliedLeavesThePeerReachable() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = staticCluster(root, peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = topology)
            h.pump()
            val observer = h.peers[0].node
            val rejoiner = h.peers[1].id
            val peer = PeerId(rejoiner.value)
            val events = recordEvents(observer)
            h.pump()
            val first = assertNotNull(observer.detectorForTest(peer), "rig: the peer is watched")

            var fired = 0
            var replacement: HeartbeatPartitionDetector? = null
            observer.afterLivenessDecisionForTest = { event ->
                if (event is PartitionEvent.PeerLost && event.peerId == peer && fired++ == 0) {
                    h.heal(1)
                    observer.remonitorOnEnrollment(rejoiner)
                    replacement = observer.detectorForTest(peer)
                }
            }
            h.partition(1)
            h.pump(90.seconds.inWholeMilliseconds)
            observer.afterLivenessDecisionForTest = null
            val unreachableAfter = rejoiner in observer.unreachable.value
            val watchedBy = observer.detectorForTest(peer)

            // Control: the new detector is live. Silence the peer again and it reports.
            h.partition(1)
            h.pump(30.seconds.inWholeMilliseconds)

            assertAll(
                { assertEquals(1, fired, "rig: the enrollment ran once, inside the loss") },
                { assertTrue(events.any { it is PartitionEvent.PeerLost && it.peerId == peer }, "rig: the loss was reported") },
                { assertNotSame(first, replacement, "rig: the enrollment attached a fresh detector") },
                { assertSame(replacement, watchedBy, "rig: and the fresh detector still watches the peer") },
                { assertFalse(unreachableAfter, "the peer is reachable behind its healthy new detector") },
                { assertTrue(rejoiner in observer.unreachable.value, "control: the new detector reports a fresh silence") },
            )
        }

    /** The old detector calls the peer unresponsive. Acted on, that would hold a healthy peer unreachable. */
    @Test
    fun aStaleUnresponsiveLeavesTheReEnrolledPeerReachable() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = reEnrolled()
            val unresponsive = PartitionEvent.PeerUnresponsive(rig.peer, now(), PartitionEvent.Reason.Timeout)

            val mark = rig.events.size
            rig.node.handleDetectorEventForTest(unresponsive, rig.old)
            runCurrent()
            val staleUnreachable = rig.replica in rig.node.unreachable.value
            val staleEvents = rig.events.drop(mark)

            val controlMark = rig.events.size
            rig.node.handleDetectorEventForTest(unresponsive, rig.current)
            runCurrent()

            assertAll(
                { assertFalse(staleUnreachable, "a stale unresponsive leaves the peer reachable") },
                { assertEquals(emptyList(), staleEvents, "and announces nothing") },
                { assertTrue(rig.replica in rig.node.unreachable.value, "control: from the current detector, it is unreachable") },
                { assertEquals(listOf(unresponsive), rig.events.drop(controlMark), "control: and it is announced") },
            )
        }

    /** The current detector holds the peer unreachable, and the old one reports it recovered. */
    @Test
    fun aStaleRecoveryLeavesTheReEnrolledPeerUnreachable() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = reEnrolled()
            rig.node.handleDetectorEventForTest(
                PartitionEvent.PeerUnresponsive(rig.peer, now(), PartitionEvent.Reason.Timeout),
                rig.current,
            )
            runCurrent()
            val heldBefore = rig.replica in rig.node.unreachable.value
            val recovered = PartitionEvent.PeerRecovered(rig.peer, now())

            val mark = rig.events.size
            rig.node.handleDetectorEventForTest(recovered, rig.old)
            runCurrent()
            val staleUnreachable = rig.replica in rig.node.unreachable.value
            val staleEvents = rig.events.drop(mark)

            val controlMark = rig.events.size
            rig.node.handleDetectorEventForTest(recovered, rig.current)
            runCurrent()

            assertAll(
                { assertTrue(heldBefore, "rig: the current detector holds the peer unreachable") },
                { assertTrue(staleUnreachable, "a stale recovery leaves the peer unreachable") },
                { assertEquals(emptyList(), staleEvents, "and announces nothing") },
                { assertFalse(rig.replica in rig.node.unreachable.value, "control: from the current detector, it is reachable") },
                { assertEquals(listOf(recovered), rig.events.drop(controlMark), "control: and it is announced") },
            )
        }

    /**
     * The old detector gives the peer up. Acted on, that would mark it unreachable and record it Lost,
     * so the next enrollment would throw away the healthy detector watching it.
     */
    @Test
    fun aStaleLossLeavesTheReEnrolledPeerWatched() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = reEnrolled()
            val lost = PartitionEvent.PeerLost(rig.peer, now())

            val mark = rig.events.size
            rig.node.handleDetectorEventForTest(lost, rig.old)
            runCurrent()
            val staleUnreachable = rig.replica in rig.node.unreachable.value
            val staleEvents = rig.events.drop(mark)
            rig.node.remonitorOnEnrollment(rig.replica)
            runCurrent()
            val staleWatchedBy = rig.node.detectorForTest(rig.peer)

            val controlMark = rig.events.size
            rig.node.handleDetectorEventForTest(lost, rig.current)
            runCurrent()
            val controlUnreachable = rig.replica in rig.node.unreachable.value
            val controlEvents = rig.events.drop(controlMark)
            rig.node.remonitorOnEnrollment(rig.replica)
            runCurrent()

            assertAll(
                { assertFalse(staleUnreachable, "a stale loss leaves the peer reachable") },
                { assertEquals(emptyList(), staleEvents, "and announces nothing") },
                { assertSame(rig.current, staleWatchedBy, "and an enrollment after it keeps the current detector") },
                { assertTrue(controlUnreachable, "control: from the current detector, it is unreachable") },
                { assertEquals(listOf(lost), controlEvents, "control: and it is announced") },
                {
                    assertNotSame(
                        rig.current,
                        rig.node.detectorForTest(rig.peer),
                        "control: and an enrollment after it replaces the lost detector",
                    )
                },
            )
        }

    private class ReEnrolled(
        val node: HeddleNode,
        val replica: ReplicaId,
        val peer: PeerId,
        val old: HeartbeatPartitionDetector,
        val current: HeartbeatPartitionDetector,
        val events: List<PartitionEvent>,
    )

    /** A peer silenced until Lost, healed and enrolled again: two detectors, one current. */
    private suspend fun TestScope.reEnrolled(): ReEnrolled {
        val h = staticCluster(root, peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = topology)
        h.pump()
        val node = h.peers[0].node
        val replica = h.peers[1].id
        val peer = PeerId(replica.value)
        val events = recordEvents(node)
        h.pump()
        h.partition(1)
        h.pump(90.seconds.inWholeMilliseconds)
        assertTrue(events.any { it is PartitionEvent.PeerLost && it.peerId == peer }, "rig: the peer was lost")
        val old = assertNotNull(node.detectorForTest(peer), "rig: the lost detector is still registered")
        h.heal(1)
        node.remonitorOnEnrollment(replica)
        h.pump(5.seconds.inWholeMilliseconds)
        val current = assertNotNull(node.detectorForTest(peer), "rig: the enrollment attached a detector")
        assertNotSame(old, current, "rig: and it is a fresh one")
        assertFalse(replica in node.unreachable.value, "rig: the re-enrolled peer is reachable")
        return ReEnrolled(node, replica, peer, old, current, events)
    }

    private fun TestScope.recordEvents(node: HeddleNode): List<PartitionEvent> {
        val events = mutableListOf<PartitionEvent>()
        backgroundScope.launch { node.partitionEvents.collect { events += it } }
        return events
    }

    private fun TestScope.now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
}
