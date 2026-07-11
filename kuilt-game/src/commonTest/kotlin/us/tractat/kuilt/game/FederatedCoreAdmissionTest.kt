@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.Snapshot
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-server learner admission ([launchFederatedCoreAdmission]) — the Task-2.5 mechanism that lets a
 * federated leader admit a player behind a *different* server, closing the gap that
 * [launchCoreLearnerAdmission] (local-roster-only) leaves open.
 *
 * ## Harness shape
 *
 * These are focused unit tests of the admission coroutine, not a live Raft election, so there is no
 * cluster to converge and no timer to spin — a hang is structurally impossible. Each core member is
 * modelled by:
 * - a [TestCoreNode] pinned to a chosen role, recording every `changeMembership` and applying it to
 *   its own membership flow (a real leader would replicate it), and
 * - a **real** [InMemoryLoom]-backed roster channel (so roster frames genuinely flow with a genuine
 *   `Swatch.sender`, exercising the first-hop authenticity check), paired with a [FakeSeam] whose
 *   `peers` we drive to control each server's *local* roster independently of who is actually on the
 *   shared loom (a player behind one server must be invisible to the other's `seam.peers`).
 *
 * [StandardTestDispatcher] (FIFO virtual time), a tight 5 s timeout, bounded `first { }` awaits, all
 * coroutines on `backgroundScope` — never `advanceUntilIdle()`.
 */
class FederatedCoreAdmissionTest {

    /**
     * (1) admission-from-remote-roster — the G1 prerequisite. The leader S1 admits a player P2 it
     * learns of **only** from follower S2's roster frame; P2 is never in S1's own `seam.peers`.
     */
    @Test
    fun leaderAdmitsAPlayerKnownOnlyViaARemoteCoreRoster() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val s1Seat = loom.host(Pattern("fed-admit"))
        val s2Seat = loom.join(InMemoryTag("fed-admit"))
        val s1 = NodeId(s1Seat.selfId.value)
        val s2 = NodeId(s2Seat.selfId.value)
        val core = setOf(s1, s2)
        val p2 = NodeId("p2")

        // S1 (leader) sees only the core — it has no local players. S2 (follower) holds local player P2.
        val s1Seam = FakeSeam(selfId = s1Seat.selfId, initialPeers = setOf(s1Seat.selfId, s2Seat.selfId))
        val s2Seam = FakeSeam(selfId = s2Seat.selfId, initialPeers = setOf(s2Seat.selfId, s1Seat.selfId, PeerId(p2.value)))

        val s1Node = TestCoreNode(RaftRole.Leader, ClusterConfig(voters = core))
        val s2Node = TestCoreNode(RaftRole.Follower, ClusterConfig(voters = core))

        backgroundScope.launchFederatedCoreAdmission(s1Node, s1Seam, rosterChannelOver(s1Seat), core)
        backgroundScope.launchFederatedCoreAdmission(s2Node, s2Seam, rosterChannelOver(s2Seat), core)

        // P2 is admitted by the leader although P2 is not in the leader's own roster — proof the
        // union crosses servers.
        s1Node.membership.first { p2 in it.learners }

        assertAll(
            { assertTrue(p2 in s1Node.membership.value.learners, "leader admits the remote player") },
            { assertEquals(core, s1Node.membership.value.voters, "the voter core is never touched — add-only, learners-only") },
        )
    }

    /**
     * (2) sender ∉ core rejection — a spoke must not be able to inject membership. A well-formed roster
     * frame from a **non-core** sender naming P-spoof is ignored, while a legitimate frame from a core
     * member naming P-legit is admitted — so the pipeline provably works and only the spoof is filtered.
     */
    @Test
    fun aRosterFrameFromANonCoreSenderIsRejected() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val s1Seat = loom.host(Pattern("fed-spoof"))
        val s2Seat = loom.join(InMemoryTag("fed-spoof"))
        val xSeat = loom.join(InMemoryTag("fed-spoof")) // a spoke — NOT in the core
        val s1 = NodeId(s1Seat.selfId.value)
        val s2 = NodeId(s2Seat.selfId.value)
        val core = setOf(s1, s2)
        val pLegit = NodeId("p-legit")
        val pSpoof = NodeId("p-spoof")

        // S1's own view has no local players; X is not a local player of S1 — purely a roster attacker.
        val s1Seam = FakeSeam(selfId = s1Seat.selfId, initialPeers = setOf(s1Seat.selfId, s2Seat.selfId))
        val s2Seam = FakeSeam(selfId = s2Seat.selfId, initialPeers = setOf(s2Seat.selfId, s1Seat.selfId, PeerId(pLegit.value)))

        val s1Node = TestCoreNode(RaftRole.Leader, ClusterConfig(voters = core))
        val s2Node = TestCoreNode(RaftRole.Follower, ClusterConfig(voters = core))

        backgroundScope.launchFederatedCoreAdmission(s1Node, s1Seam, rosterChannelOver(s1Seat), core)
        backgroundScope.launchFederatedCoreAdmission(s2Node, s2Seam, rosterChannelOver(s2Seat), core)

        // The spoke forges a well-formed roster naming P-spoof and unicasts it to the leader.
        rosterChannelOver(xSeat).sendTo(PeerId(s1.value), encodeRoster(setOf(pSpoof)))

        // The legit player flows through and is admitted — proof the receive loop is alive and processing.
        s1Node.membership.first { pLegit in it.learners }

        // The spoofed player is never admitted, even given a bounded window to appear.
        val spoofAdmitted = withTimeoutOrNull(2.seconds) { s1Node.membership.first { pSpoof in it.learners } }

        assertAll(
            { assertTrue(pLegit in s1Node.membership.value.learners, "the legitimate core-sourced player is admitted") },
            { assertNull(spoofAdmitted, "a roster from a non-core sender must never inject membership") },
            { assertTrue(s1Node.membershipChanges.none { pSpoof in it.learners }, "no changeMembership ever named the spoofed player") },
        )
    }

    /**
     * (3) add-once idempotence — a re-published (identical) roster does not churn membership. S2 reports
     * P-only, the leader admits it once, then the identical roster is re-sent; no second admit.
     */
    @Test
    fun aRepublishedIdenticalRosterDoesNotReAdmit() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val s1Seat = loom.host(Pattern("fed-idem"))
        val s2Seat = loom.join(InMemoryTag("fed-idem"))
        val s1 = NodeId(s1Seat.selfId.value)
        val s2 = NodeId(s2Seat.selfId.value)
        val core = setOf(s1, s2)
        val pOnly = NodeId("p-only")

        val s1Seam = FakeSeam(selfId = s1Seat.selfId, initialPeers = setOf(s1Seat.selfId, s2Seat.selfId))
        val s2Seam = FakeSeam(selfId = s2Seat.selfId, initialPeers = setOf(s2Seat.selfId, s1Seat.selfId, PeerId(pOnly.value)))

        val s1Node = TestCoreNode(RaftRole.Leader, ClusterConfig(voters = core))
        val s2Node = TestCoreNode(RaftRole.Follower, ClusterConfig(voters = core))

        // Hoist S2's roster channel so the test can re-send the identical roster by hand.
        val s2Roster = rosterChannelOver(s2Seat)
        backgroundScope.launchFederatedCoreAdmission(s1Node, s1Seam, rosterChannelOver(s1Seat), core)
        backgroundScope.launchFederatedCoreAdmission(s2Node, s2Seam, s2Roster, core)

        s1Node.membership.first { pOnly in it.learners }

        // Re-send the byte-identical roster frame; the leader must not re-admit P-only.
        s2Roster.sendTo(PeerId(s1.value), encodeRoster(setOf(pOnly)))
        s2Roster.sendTo(PeerId(s1.value), encodeRoster(setOf(pOnly)))

        // Given a bounded window, no second admission occurs (learners never grows past {P-only}).
        val churned = withTimeoutOrNull(2.seconds) { s1Node.membership.first { it.learners.size > 1 } }

        assertAll(
            { assertNull(churned, "a re-published identical roster must not grow the learner set") },
            { assertEquals(1, s1Node.membershipChanges.size, "exactly one changeMembership — add-once") },
        )
    }

    /**
     * (4) new-leader roster-amnesia (H2) — after leadership moves, the new leader still admits a player
     * behind the *other* server. Rosters flow to every core member continuously, so the new leader is
     * never blind. S1 holds P-far, S2 holds P-near; leadership moves S1 → S2, and S2 admits P-far.
     */
    @Test
    fun aNewLeaderStillAdmitsThePlayerBehindTheOtherServer() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val s1Seat = loom.host(Pattern("fed-failover"))
        val s2Seat = loom.join(InMemoryTag("fed-failover"))
        val s1 = NodeId(s1Seat.selfId.value)
        val s2 = NodeId(s2Seat.selfId.value)
        val core = setOf(s1, s2)
        val pFar = NodeId("p-far")   // behind S1
        val pNear = NodeId("p-near") // behind S2

        val s1Seam = FakeSeam(selfId = s1Seat.selfId, initialPeers = setOf(s1Seat.selfId, s2Seat.selfId, PeerId(pFar.value)))
        val s2Seam = FakeSeam(selfId = s2Seat.selfId, initialPeers = setOf(s2Seat.selfId, s1Seat.selfId, PeerId(pNear.value)))

        val s1Node = TestCoreNode(RaftRole.Leader, ClusterConfig(voters = core))
        val s2Node = TestCoreNode(RaftRole.Follower, ClusterConfig(voters = core))

        backgroundScope.launchFederatedCoreAdmission(s1Node, s1Seam, rosterChannelOver(s1Seat), core)
        backgroundScope.launchFederatedCoreAdmission(s2Node, s2Seam, rosterChannelOver(s2Seat), core)

        // S1 (initial leader) admits the near player behind S2 — rosters already flowing both ways.
        s1Node.membership.first { pNear in it.learners }

        // Leadership moves to S2. Because rosters flow to every core member continuously, S2 already
        // holds S1's roster and is not blind to the far player behind S1.
        s1Node.setRole(RaftRole.Follower)
        s2Node.setRole(RaftRole.Leader)

        s2Node.membership.first { pFar in it.learners }

        assertTrue(pFar in s2Node.membership.value.learners, "the new leader admits the far player behind S1")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Carve the [CORE_ROSTER_CHANNEL] over [seat] exactly as [gameNode] does over the session seam.
     * The mux rides `backgroundScope` so its long-lived collector cancels cleanly at teardown.
     */
    private fun TestScope.rosterChannelOver(seat: Seam): Seam =
        MuxSeam(seat, backgroundScope).channel(CORE_ROSTER_CHANNEL)
}

/**
 * A [RaftNode] test double for a core member: pinned to a chosen [RaftRole], it records every
 * [changeMembership] target and applies it to its own membership flow (a real leader would replicate
 * the change). Everything else is inert — these tests drive only role and membership. Access is serial
 * under `runTest`.
 */
private class TestCoreNode(
    role: RaftRole,
    initialMembership: ClusterConfig,
) : RaftNode {
    private val _role = MutableStateFlow(role)
    override val role: StateFlow<RaftRole> = _role.asStateFlow()
    override val leader: StateFlow<NodeId?> = MutableStateFlow(null)
    private val _membership = MutableStateFlow(initialMembership)
    override val membership: StateFlow<ClusterConfig> = _membership.asStateFlow()
    override val commitIndex: StateFlow<Long> = MutableStateFlow(0L)
    override val committed: Flow<Committed> = emptyFlow()
    override fun committedFrom(fromIndex: Long): Flow<Committed> = emptyFlow()
    override val snapshots: MutableStateFlow<Snapshot?> = MutableStateFlow(null)
    override val compactionFloor: StateFlow<Long> = MutableStateFlow(0L)
    override val trace: Flow<RaftTraceEvent> = emptyFlow()

    /** Every membership target passed to [changeMembership], in call order. */
    val membershipChanges: MutableList<ClusterConfig> = mutableListOf()

    override suspend fun changeMembership(target: ClusterConfig): ClusterConfig {
        membershipChanges += target
        _membership.value = target
        return target
    }

    override suspend fun propose(command: ByteArray): LogEntry = error("TestCoreNode.propose is unused")
    override suspend fun propose(command: ByteArray, requestId: Long): LogEntry = error("TestCoreNode.propose is unused")
    override suspend fun close(): Unit = Unit

    /** Drive a role transition (e.g. a simulated leadership change). */
    fun setRole(newRole: RaftRole) {
        _role.value = newRole
    }
}
