@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [HostMembership], the host's single membership writer (#3135).
 *
 * The end-to-end tests in [GameSpectateTest] depend on one schedule to make two admissions
 * overlap. This one forces the overlap: the node's [RaftNode.changeMembership] holds every change
 * on a gate, so a second admission is started while the first is provably still in flight.
 */
class HostMembershipTest {

    /**
     * A spectator admission started while a voter admission is in flight waits for it, then builds
     * on its result.
     *
     * Without the lock the spectator's change would reach the node while the voter is still a
     * half-admitted learner, and a retry of that target after the promotion would demote the voter.
     */
    @Test
    fun spectatorAdmissionWaitsForAnInFlightVoterAdmission() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val host = NodeId("host")
        val j1 = NodeId("j1")
        val joiner = NodeId("joiner")
        val spectator = NodeId("spectator")
        val voters = setOf(host, j1)
        val node = GatedMembershipNode(ClusterConfig(voters = voters))
        val membership = HostMembership(node, host)

        val voterAdmission = launch { membership.admitVoter(joiner) }
        runCurrent()
        // Rig check: the voter's first change is parked on the gate, so the next admission overlaps it.
        val inFlight = node.requested.value

        val spectatorAdmission = launch { membership.admitSpectator(spectator) }
        runCurrent()
        val requestedWhileHeld = node.requested.value

        node.gate.complete(Unit)
        voterAdmission.join()
        spectatorAdmission.join()

        assertAll(
            { assertEquals(listOf(ClusterConfig(voters, setOf(joiner))), inFlight, "voter's learner step must be in flight") },
            { assertEquals(inFlight, requestedWhileHeld, "spectator must not reach changeMembership while the voter's change is held") },
            {
                assertEquals(
                    ClusterConfig(voters + joiner, setOf(spectator)),
                    node.membership.value,
                    "both admissions must land, the joiner as a voter and the spectator as a learner",
                )
            },
            {
                assertEquals(
                    listOf(
                        ClusterConfig(voters, setOf(joiner)),
                        ClusterConfig(voters + joiner),
                        ClusterConfig(voters + joiner, setOf(spectator)),
                    ),
                    node.requested.value,
                    "changes must arrive one at a time, each built on the last",
                )
            },
        )
    }
}

/**
 * A [RaftNode] whose [changeMembership] adopts the target on arrival, as the engine adopts a config
 * on append, and then suspends on [gate] until the test lets it commit. Every target it receives is
 * recorded in [requested], in arrival order.
 */
private class GatedMembershipNode(
    initial: ClusterConfig,
    delegate: RaftNode = FakeRaftNode(),
) : RaftNode by delegate {
    private val _membership = MutableStateFlow(initial)
    override val membership: StateFlow<ClusterConfig> = _membership.asStateFlow()

    val gate = CompletableDeferred<Unit>()

    private val _requested = MutableStateFlow(emptyList<ClusterConfig>())
    val requested: StateFlow<List<ClusterConfig>> = _requested.asStateFlow()

    override suspend fun changeMembership(target: ClusterConfig): ClusterConfig {
        _requested.update { it + target }
        _membership.value = target
        gate.await()
        return target
    }
}
