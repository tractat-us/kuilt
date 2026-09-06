package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A [ConfigPayload] arriving **over the wire** may not leave a currently-active configuration with an
 * empty voter set (#2663).
 *
 * ### What the bound is for
 *
 * The §5.2 leader-authority gate in `RaftEngine.onMessage` is conditioned on
 * `membershipState.voters.isNotEmpty()`, a deliberate carve-out for the pre-bootstrap learner seed
 * (`ClusterConfig(voters = emptySet(), learners = {self})`), which must accept a leader's frames to
 * catch up at all. The carve-out was reasoned about in one direction only — arming — and the reverse
 * direction *disarms* it: a wire config that un-seats every voter returns the node to the
 * pre-bootstrap state permanently, for **every** subsequent sender.
 *
 * The consequence is not term inflation. With the gate disarmed the log path does no `from`
 * validation at all, so an arbitrary non-voter's `AppendEntries` truncates the follower's committed
 * log and replaces it (`log = [(1, 6)]` in #2663's reproduction), and installs itself as `_leader`.
 * The `old` half is a liveness bound rather than a §5.2 one:
 * `MembershipState.Joint.voterQuorumReached` takes **independent** majorities of `old.voters` and
 * `new.voters`, and a majority of the empty set is unreachable (`quorumSize(∅) == 1`, and nothing
 * can be granted from it), so an empty side stalls commit and election for good.
 *
 * ### Why this is a *well-formedness* bound and not the one #1880 rejected
 *
 * #1880 asked whether a recipient could check a wire config's **content** — that it is reachable
 * from what this node last committed — and closed as accepted-unauthenticated, because a
 * long-absent node must be catchable-up to an arbitrarily distant config, so any content predicate
 * has to accept and reject the same value. That resolution says the remaining gap is
 * *authorization*, and explicitly that "well-formedness **is** locally checkable here". This is that
 * check, and nothing more: it reads only the payload, never local state, so #1898's staleness
 * relaxation (a node holding `{A,B,X}` accepts `{X,C,D}` after a full rotation) is untouched — which
 * [aVoterSetRotationThatRemovesUsEntirely_isStillAdopted] pins.
 *
 * ### The carve-out is the risk, so it is tested too
 *
 * A predicate over `ClusterConfig` rather than over the wire payload would make the learner seed
 * **unconstructible**, which is the fix #2663's body originally proposed and its investigation
 * refuted. [aPreBootstrapLearnerSeed_stillCatchesUpAndThenArmsTheGate] drives that seed end to end:
 * the gate is unarmed while the node knows no voters, a genuine config seating voters is still
 * adopted from the wire, and the gate arms behind it.
 *
 * Every test holds the target still by [RaftSimulation.settle]ing after each injection and never
 * advancing virtual time, so no election timer fires and the injected frame is the only thing the
 * node reacts to.
 */
class WireConfigVoterSetTest {

    private val attacker = NodeId("attacker-not-a-voter")
    private val controlCmd = byteArrayOf(0xC0.toByte(), 0x11, 0x70)
    private val attackCmd = byteArrayOf(0xBA.toByte(), 0xD0.toByte(), 0xDE.toByte())

    /**
     * One partitioned follower whose committed log the injections act on, plus the ids of the leader
     * it last heard from and the term everything is stamped at.
     *
     * Partitioned so the real leader's heartbeats cannot repair the state under test between an
     * injection and its assertion — the same isolation #2663's reproduction used.
     */
    private class Target(
        val sim: RaftSimulation,
        val leaderId: NodeId,
        val followerId: NodeId,
        val term: Long,
        val committedIndex: Long,
    ) {
        val node: RaftNode get() = sim.nodes.getValue(followerId)
        val storage: InMemoryRaftStorage get() = sim.storages.getValue(followerId)
        val voters: Set<NodeId> get() = node.membership.value.voters
        suspend fun tail(): LogEntry = storage.entries().last()
    }

    private suspend fun TestScope.partitionedFollower(): Target {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodeIds.first { sim.nodes[it] === leader }
        val followerId = sim.nodeIds.first { it != leaderId }
        val committed = sim.proposeOnLeader("legit".encodeToByteArray())
        sim.awaitCommit(committed.index, on = setOf(followerId))
        sim.partitionOff(followerId)
        val target = Target(sim, leaderId, followerId, sim.storages.getValue(followerId).term(), committed.index)
        assertTrue(target.voters.isNotEmpty(), "premise: the §5.2 gate must start ARMED")
        return target
    }

    /**
     * Deliver the forged non-voter frame the whole issue turns on, and report whether it landed.
     *
     * The *identical* frame is delivered twice per attack test — once with the gate armed (the
     * control, which must be refused) and once after the poisoned config (the discriminator). Reusing
     * one function is what makes the two arms bit-identical, so the only difference between them is
     * the config that arrived in between.
     */
    private suspend fun Target.injectForgedFrame(command: ByteArray): Boolean {
        sim.deliverAppendEntries(
            to = followerId, from = attacker, term = term + 5L,
            prevLogIndex = 0L, prevLogTerm = 0L,
            entries = listOf(LogEntry(index = 1L, term = term + 5L, command = command)),
        )
        sim.settle()
        return storage.entries().any { it.command.contentEquals(command) }
    }

    // ── Route 1: AppendEntries.entries[].config ──────────────────────────────

    @Test
    fun anEmptyVoterSetInAnAppendEntriesConfigEntry_doesNotDisarmTheLeaderAuthorityGate() = raftRunTest {
        val t = partitionedFollower()
        val votersBefore = t.voters

        // CONTROL: with the gate armed the forged frame must be refused. Without this arm a green
        // discriminator would be indistinguishable from a broken injector.
        val controlLanded = t.injectForgedFrame(controlCmd)
        val termAfterControl = t.storage.term()

        // The poisoned config, from a peer that IS in the target's voter set — the only sender the
        // armed gate admits, and the reason an ex-voter the target has not rotated out qualifies.
        val tail = t.tail()
        t.sim.deliverAppendEntries(
            to = t.followerId, from = t.leaderId, term = t.term,
            prevLogIndex = tail.index, prevLogTerm = tail.term,
            entries = listOf(
                LogEntry(
                    index = tail.index + 1L, term = t.term, command = byteArrayOf(),
                    config = ConfigPayload(old = null, new = ClusterConfig(voters = emptySet())),
                ),
            ),
        )
        t.sim.settle()
        // Read the voter set HERE, not after the attack frame. On this lane the disarm is
        // self-healing: the attacker's own truncation removes the poisoned config entry, no snapshot
        // config exists, and `recomputeMembership` falls back to `bootstrapConfig` — so the same read
        // taken afterwards is `{v1,v2,v3}` on the buggy code too, and asserts nothing. Measured.
        val votersAfterPoison = t.voters

        // DISCRIMINATOR: the same forged frame again.
        val attackLanded = t.injectForgedFrame(attackCmd)
        val committedEntrySurvives = t.storage.entries().any { it.index == t.committedIndex }
        val termAfterAttack = t.storage.term()

        assertAll(
            { assertFalse(controlLanded, "CONTROL: an armed gate must refuse the forged frame") },
            { assertEquals(t.term, termAfterControl, "CONTROL: a refused frame adopts no term") },
            {
                assertEquals(
                    votersBefore, votersAfterPoison,
                    "the empty-voters payload must be refused, leaving the voter set untouched",
                )
            },
            { assertFalse(attackLanded, "DISCRIMINATOR: the gate must still refuse the identical frame") },
            { assertEquals(t.term, termAfterAttack, "a refused frame adopts no term") },
            { assertEquals(t.leaderId, t.node.leader.value, "a non-voter must not become the leader") },
            {
                assertTrue(
                    committedEntrySurvives,
                    "the follower's committed entry must survive — #2663's log truncation is the damage",
                )
            },
        )
    }

    /**
     * The `old` half of a joint payload, which the §5.2 gate itself cannot see.
     *
     * `MembershipState.Joint.voters` is the **union** of both sides, so an empty `old` leaves the
     * gate armed. What it takes out is quorum: commit and election need independent majorities of
     * *each* side, and the empty side can never reach one. The node stops committing and can never
     * win an election, permanently, off one frame — so the bound covers both sides rather than only
     * the one that reaches §5.2.
     */
    @Test
    fun aJointPayloadWithAnEmptyOldVoterSet_isRefused() = raftRunTest {
        val t = partitionedFollower()
        val votersBefore = t.voters
        val tail = t.tail()

        t.sim.deliverAppendEntries(
            to = t.followerId, from = t.leaderId, term = t.term,
            prevLogIndex = tail.index, prevLogTerm = tail.term,
            entries = listOf(
                LogEntry(
                    index = tail.index + 1L, term = t.term, command = byteArrayOf(),
                    config = ConfigPayload(
                        old = ClusterConfig(voters = emptySet()),
                        new = ClusterConfig(voters = setOf(attacker)),
                    ),
                ),
            ),
        )
        t.sim.settle()
        val entryReachedTheLog = t.storage.entries().any { it.index == tail.index + 1L }

        assertAll(
            { assertEquals(votersBefore, t.voters, "a joint payload with an empty side must be refused") },
            {
                assertFalse(
                    entryReachedTheLog,
                    "the refusal drops the frame, so the entry never reaches the log",
                )
            },
        )
    }

    // ── Route 2: InstallSnapshot.config ──────────────────────────────────────

    /**
     * The worse of the two routes: `finalizeInstalledSnapshot` assigns `state.snapshotConfig =
     * m.config` straight off the wire, and `storage.saveSnapshot` writes it **durably** first — so
     * before this bound the poisoned config outlived a restart, with `checkedRestoredSnapshotMeta`
     * bounding index and term but never `config`.
     */
    @Test
    fun anEmptyVoterSetInAnInstallSnapshotConfig_doesNotDisarmTheLeaderAuthorityGate() = raftRunTest {
        val t = partitionedFollower()
        val votersBefore = t.voters
        val controlLanded = t.injectForgedFrame(controlCmd)

        t.sim.deliverInstallSnapshot(
            to = t.followerId, from = t.leaderId, term = t.term,
            lastIncludedIndex = t.committedIndex + 3L, lastIncludedTerm = t.term,
            data = byteArrayOf(1, 2, 3),
            config = ConfigPayload(old = null, new = ClusterConfig(voters = emptySet())),
        )
        t.sim.settle()
        val snapshotAfter = t.storage.loadSnapshot()?.meta

        val attackLanded = t.injectForgedFrame(attackCmd)

        assertAll(
            { assertFalse(controlLanded, "CONTROL: an armed gate must refuse the forged frame") },
            { assertEquals(votersBefore, t.voters, "the snapshot's empty-voters payload must be refused") },
            { assertEquals(null, snapshotAfter, "a refused chunk must not reach durable storage") },
            { assertFalse(attackLanded, "DISCRIMINATOR: the gate must still refuse the identical frame") },
            { assertEquals(t.leaderId, t.node.leader.value, "a non-voter must not become the leader") },
        )
    }

    // ── The carve-outs the bound must not break ──────────────────────────────

    /**
     * #1898's staleness relaxation, on the wire: a node holding `{v1,v2,v3}` must still adopt a
     * config that removes it entirely, because that is the endpoint of a legal sequence of §4
     * single-server removals and the snapshot is the only carrier once the intermediate entries are
     * compacted away. A content predicate would reject this; a well-formedness one must not.
     */
    @Test
    fun aVoterSetRotationThatRemovesUsEntirely_isStillAdopted() = raftRunTest {
        val t = partitionedFollower()
        val rotated = ClusterConfig(voters = setOf(NodeId("c1"), NodeId("c2")))
        val tail = t.tail()

        t.sim.deliverAppendEntries(
            to = t.followerId, from = t.leaderId, term = t.term,
            prevLogIndex = tail.index, prevLogTerm = tail.term,
            entries = listOf(
                LogEntry(
                    index = tail.index + 1L, term = t.term, command = byteArrayOf(),
                    config = ConfigPayload(old = null, new = rotated),
                ),
            ),
        )
        t.sim.settle()

        assertEquals(rotated.voters, t.voters, "a rotation away from us is legitimate and must be adopted")
    }

    /** The same, on the snapshot lane — the one `MembershipTest` covers via the leader's own transfer. */
    @Test
    fun aLegitimateConfigCarriedByAnInstallSnapshot_isStillAdopted() = raftRunTest {
        val t = partitionedFollower()
        val carried = ClusterConfig(voters = setOf(NodeId("c1"), NodeId("c2")), learners = setOf(t.followerId))

        t.sim.deliverInstallSnapshot(
            to = t.followerId, from = t.leaderId, term = t.term,
            lastIncludedIndex = t.committedIndex + 3L, lastIncludedTerm = t.term,
            data = byteArrayOf(1, 2, 3),
            config = ConfigPayload(old = null, new = carried),
        )
        t.sim.settle()
        val storedConfig = t.storage.loadSnapshot()?.meta?.config

        assertAll(
            { assertEquals(carried.voters, t.voters, "a well-formed snapshot config must still be adopted") },
            {
                assertEquals(
                    ConfigPayload(old = null, new = carried), storedConfig,
                    "and must still reach durable storage",
                )
            },
        )
    }

    /**
     * The bootstrap window, end to end — the carve-out a `require` on [ClusterConfig] would destroy.
     *
     * The seed node boots knowing no voters, so the gate is unarmed and it accepts the leader's
     * frames (it must, or the join deadlocks). The config that seats voters is itself a wire
     * `ConfigPayload`, so it has to clear the new bound; the gate then arms behind it and the same
     * non-voter is refused.
     */
    @Test
    fun aPreBootstrapLearnerSeed_stillCatchesUpAndThenArmsTheGate() = raftRunTest {
        val voterIds = listOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
        val seedId = NodeId("joiner")
        val cluster = ClusterConfig(voters = voterIds.toSet())
        val seedConfig = ClusterConfig(voters = emptySet(), learners = setOf(seedId))
        val cfg = fastRaftConfig()
        val sim = RaftSimulation(
            nodeIds = voterIds + seedId,
            scope = this,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, childScope: CoroutineScope ->
                childScope.raftNode(if (id == seedId) seedConfig else cluster, transport, storage, cfg)
            },
        )
        val leaderId = sim.nodeIds.first { sim.nodes[it] === awaitLeader(sim) }
        val seed = sim.nodes.getValue(seedId)
        val seedTerm = sim.storages.getValue(seedId).term()
        assertTrue(seed.membership.value.voters.isEmpty(), "premise: the seed knows no voters, so the gate is UNARMED")

        // Unarmed: an ordinary heartbeat from a node the seed cannot yet authenticate is admitted.
        sim.deliverAppendEntries(to = seedId, from = leaderId, term = seedTerm)
        sim.settle()
        val leaderWhileUnarmed = seed.leader.value

        // The config that seats voters — a wire ConfigPayload, so it must clear the bound.
        val admitted = ClusterConfig(voters = voterIds.toSet(), learners = setOf(seedId))
        sim.deliverAppendEntries(
            to = seedId, from = leaderId, term = seedTerm,
            prevLogIndex = 0L, prevLogTerm = 0L,
            entries = listOf(
                LogEntry(
                    index = 1L, term = seedTerm, command = byteArrayOf(),
                    config = ConfigPayload(old = null, new = admitted),
                ),
            ),
        )
        sim.settle()
        val votersAfterAdmit = seed.membership.value.voters

        // Armed: the same non-voter is now refused.
        sim.deliverAppendEntries(
            to = seedId, from = attacker, term = seedTerm + 5L,
            prevLogIndex = 0L, prevLogTerm = 0L,
            entries = listOf(LogEntry(index = 1L, term = seedTerm + 5L, command = attackCmd)),
        )
        sim.settle()
        val attackLanded = sim.storages.getValue(seedId).entries().any { it.command.contentEquals(attackCmd) }

        assertAll(
            { assertEquals(leaderId, leaderWhileUnarmed, "the unarmed seed must accept the leader's frames") },
            { assertEquals(voterIds.toSet(), votersAfterAdmit, "the admitting config must still be adopted") },
            { assertFalse(attackLanded, "once armed, the gate refuses a non-voter") },
        )
    }
}
