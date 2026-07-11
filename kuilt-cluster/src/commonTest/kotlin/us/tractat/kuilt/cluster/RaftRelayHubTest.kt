@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The server-side fan-in point where M in-process voters share one spoke set:
 * [RaftRelayHub] is the sole collector of each learner spoke, routes a decoded
 * [RaftRelay] to the *named* voter's inbound by `dest`, preserves the true origin
 * across both legs, and rejects a first-hop origin forgery before it can reach any
 * engine.
 *
 * Like [RoutedRaftTransportTest] these drive real [Seam]s ([InMemoryLoom]) under
 * `UnconfinedTestDispatcher` with a tight timeout — no Raft cluster, so no
 * `MultiNodeRaftSim`; the hub itself is the unit under test. Each voter inbound is
 * a plain [MutableSharedFlow] a test collects, so every routing decision is
 * asserted structurally.
 */
class RaftRelayHubTest {

    @Test
    fun destRoutingHitsExactlyTheNamedVoterAndNoOther() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val v1 = NodeId("v1")
            val v2 = NodeId("v2")
            val hub = RaftRelayHub(voters = setOf(v1, v2))
            val at1 = registerVoter(hub, v1)
            val at2 = registerVoter(hub, v2)

            val (hubSide, learnerSide) = spokePair()
            val learnerId = NodeId(learnerSide.selfId.value)
            hub.addSpoke(learnerId, hubSide, backgroundScope)
            testScheduler.advanceUntilIdle()

            // A spoke speaks only for itself: origin == its own id.
            learnerSide.broadcast(RaftRelay.encode(RaftRelay(learnerId, v1, "for-v1".encodeToByteArray())))
            testScheduler.advanceUntilIdle()

            assertAll(
                { assertEquals(1, at1.size, "the named voter's inbound must receive exactly one frame") },
                { assertEquals(learnerId, at1.single().from, "true origin preserved as RaftEnvelope.from") },
                { assertContentEquals("for-v1".encodeToByteArray(), at1.single().bytes) },
                { assertTrue(at2.isEmpty(), "no other voter may receive the frame — dest-routed, never fanned") },
            )
        }

    @Test
    fun spokeSpoofingAnotherOriginReachesNoInbound() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val v1 = NodeId("v1")
            val hub = RaftRelayHub(voters = setOf(v1))
            val at1 = registerVoter(hub, v1)

            val (hubSide, learnerSide) = spokePair()
            val learnerId = NodeId(learnerSide.selfId.value)
            hub.addSpoke(learnerId, hubSide, backgroundScope)
            testScheduler.advanceUntilIdle()

            // Forged: sender = the learner, but origin claims to be another node.
            val forgedOrigin = NodeId("some-other-node")
            learnerSide.broadcast(RaftRelay.encode(RaftRelay(forgedOrigin, v1, "forged".encodeToByteArray())))
            testScheduler.advanceUntilIdle()
            assertTrue(at1.isEmpty(), "a spoofed origin from a spoke must reach no inbound")

            // Positive control: the same spoke speaking for itself does surface.
            learnerSide.broadcast(RaftRelay.encode(RaftRelay(learnerId, v1, "legit".encodeToByteArray())))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(learnerId), at1.map { it.from }, "only the legitimate frame surfaces")
        }

    @Test
    fun frameForANonVoterDestIsDropped() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val v1 = NodeId("v1")
            val hub = RaftRelayHub(voters = setOf(v1))
            val at1 = registerVoter(hub, v1)

            val (hubSide, learnerSide) = spokePair()
            val learnerId = NodeId(learnerSide.selfId.value)
            hub.addSpoke(learnerId, hubSide, backgroundScope)
            testScheduler.advanceUntilIdle()

            learnerSide.broadcast(
                RaftRelay.encode(RaftRelay(learnerId, NodeId("stranger"), "x".encodeToByteArray())),
            )
            testScheduler.advanceUntilIdle()

            assertTrue(at1.isEmpty(), "a dest that is not a voter must be dropped, never re-forwarded")
        }

    @Test
    fun sendToLearnerWrapsWithTheTrueVoterOrigin() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val v1 = NodeId("v1")
            val hub = RaftRelayHub(voters = setOf(v1))
            registerVoter(hub, v1)

            val (hubSide, learnerSide) = spokePair()
            val learnerId = NodeId(learnerSide.selfId.value)
            hub.addSpoke(learnerId, hubSide, backgroundScope)
            val atLearner = collectInto(learnerSide)
            testScheduler.advanceUntilIdle()

            hub.sendToLearner(fromVoter = v1, learnerId = learnerId, bytes = "committed".encodeToByteArray())
            testScheduler.advanceUntilIdle()

            val delivered = RaftRelay.decode(atLearner.single().toByteArray())
            // The client credits the true voter, not the relay id — reconstruct the
            // envelope the far end would present to its engine.
            val envelope = RaftEnvelope(delivered.origin, delivered.bytes)
            assertAll(
                { assertEquals(v1, envelope.from, "the down leg must carry the true voter origin as from") },
                { assertEquals(learnerId, delivered.dest, "dest is the addressed learner") },
                { assertContentEquals("committed".encodeToByteArray(), envelope.bytes) },
            )
        }

    @Test
    fun removeSpokeStopsInboundDelivery() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val v1 = NodeId("v1")
            val hub = RaftRelayHub(voters = setOf(v1))
            val at1 = registerVoter(hub, v1)

            val (hubSide, learnerSide) = spokePair()
            val learnerId = NodeId(learnerSide.selfId.value)
            hub.addSpoke(learnerId, hubSide, backgroundScope)
            testScheduler.advanceUntilIdle()

            hub.removeSpoke(learnerId)
            testScheduler.advanceUntilIdle()

            learnerSide.broadcast(RaftRelay.encode(RaftRelay(learnerId, v1, "after-remove".encodeToByteArray())))
            testScheduler.advanceUntilIdle()

            assertTrue(at1.isEmpty(), "a removed spoke's frames must no longer be delivered")
            // sendToLearner to a removed learner returns silently (no throw).
            hub.sendToLearner(v1, learnerId, "late".encodeToByteArray())
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun learnersFlowReflectsAddAndRemove() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val hub = RaftRelayHub(voters = setOf(NodeId("v1")))
            val (hubSide, learnerSide) = spokePair()
            val learnerId = NodeId(learnerSide.selfId.value)

            assertTrue(hub.learnersFlow.value.isEmpty(), "no learners before any addSpoke")

            hub.addSpoke(learnerId, hubSide, backgroundScope)
            testScheduler.advanceUntilIdle()
            assertEquals(setOf(learnerId), hub.learnersFlow.value, "addSpoke publishes the learner id")

            hub.removeSpoke(learnerId)
            testScheduler.advanceUntilIdle()
            assertTrue(hub.learnersFlow.value.isEmpty(), "removeSpoke withdraws it")
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Register a voter inbound sink on [hub] and collect its emissions into a list. */
    private fun TestScope.registerVoter(hub: RaftRelayHub, voterId: NodeId): List<RaftEnvelope> {
        val inbound = MutableSharedFlow<RaftEnvelope>(extraBufferCapacity = Int.MAX_VALUE)
        hub.registerVoterInbound(voterId, inbound)
        val received = mutableListOf<RaftEnvelope>()
        backgroundScope.launch { inbound.collect { received += it } }
        return received
    }

    /** A 2-peer [InMemoryLoom] spoke: the hub side (host) and the learner side (join). */
    private suspend fun spokePair(): Pair<Seam, Seam> {
        val loom = InMemoryLoom()
        val hubSide = loom.host(Pattern("spoke"))
        val learnerSide = loom.join(InMemoryTag("spoke"))
        return hubSide to learnerSide
    }

    /** Collect a seam's incoming into a growing list on the test's background scope. */
    private fun TestScope.collectInto(seam: Seam): List<Swatch> {
        val received = mutableListOf<Swatch>()
        backgroundScope.launch { seam.incoming.collect { received += it } }
        return received
    }
}
