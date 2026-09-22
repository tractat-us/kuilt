@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression for #2676 case 3: `raftNode` refuses a `bootstrapConfig` that leaves the node outside its
 * own cluster.
 *
 * `onMessage`'s §5.2 leader-authority gate is conditioned on `membershipState.voters.isNotEmpty()`, a
 * carve-out for the pre-bootstrap **learner seed** (`voters = ∅, learners = {self}`) that `gameJoin`
 * and `gameSpectate` start from: it must accept a leader's frames to catch up at all. Two other
 * voterless bootstraps were measured booting into the same unarmed state with no seed's excuse —
 * `(∅, ∅)` and `(∅, {someone else})` — where any peer in no configuration anywhere could send one
 * `AppendEntries`, move the node's term and install itself as leader. Both now fail at construction.
 *
 * The discriminator is `voters.isNotEmpty() || self ∈ learners`, not "has members": the foreign-learner
 * arm passes `voters ∪ learners ≠ ∅` and still boots disarmed, which is why it is its own arm.
 *
 * ## Test discipline
 *
 * The admitted arms are a **discriminator**, not a state read: the identical forged `AppendEntries`,
 * from the identical peer that is in no configuration anywhere, is injected at `termBefore + 9`, and
 * the witness is the victim's **persisted** term (`persistTermAndVote` is storage-first), exactly as
 * [RestoredConfigValidationTest] measures it. The seated arms are the positive control that the rig
 * can see an armed gate; the seed arm is the negative control that it can see a disarmed one.
 */
internal class BootstrapConfigValidationTest {

    private val attacker = NodeId("attacker-not-a-voter")
    private val v1 = NodeId("v1")
    private val v2 = NodeId("v2")
    private val v3 = NodeId("v3")
    private val ids = listOf(v1, v2, v3)

    /** What the victim believed at the moment of the forgery, and what the forgery did to it. */
    private data class Observed(
        val membership: ClusterConfig,
        val role: RaftRole,
        val termBefore: Long,
        val termAfter: Long,
        val leader: NodeId?,
    )

    private fun sim(scope: TestScope, bootstrapFor: (NodeId) -> ClusterConfig): RaftSimulation {
        val cfg = fastRaftConfig()
        return RaftSimulation(
            nodeIds = ids,
            scope = scope,
            nodeScope = scope.backgroundScope,
            nodeFactory = { id, transport, storage, child -> child.raftNode(bootstrapFor(id), transport, storage, cfg) },
        )
    }

    private suspend fun RaftSimulation.forgeAgainst(victim: NodeId): Observed {
        settle()
        val node = nodes.getValue(victim)
        val storage = storages.getValue(victim)
        val membership = node.membership.value
        val role = node.role.value
        val termBefore = storage.term()
        deliverAppendEntries(to = victim, from = attacker, term = termBefore + 9L)
        settle()
        settle()
        return Observed(membership, role, termBefore, storage.term(), node.leader.value)
    }

    private fun assertDisarmed(o: Observed) = assertAll(
        { assertEquals(o.termBefore + 9L, o.termAfter, "forged frame moved the persisted term (gate disarmed)") },
        { assertEquals(attacker, o.leader, "the non-member attacker was installed as leader") },
    )

    private fun assertArmed(o: Observed) = assertAll(
        { assertEquals(o.termBefore, o.termAfter, "forged frame refused (gate armed)") },
        { assertNotEquals(attacker, o.leader, "attacker not installed") },
    )

    /** Construct [v3] alone over [bootstrap]; the refused shapes never get as far as a transport frame. */
    private fun TestScope.bootstrapAlone(bootstrap: ClusterConfig): RaftNode =
        backgroundScope.raftNode(bootstrap, InMemoryRaftNetwork().transport(v3), InMemoryRaftStorage(), fastRaftConfig())

    private fun assertNamesTheRule(failure: IllegalArgumentException) {
        val message = failure.message.orEmpty()
        assertAll(
            { assertTrue(message.contains(v3.value), "the diagnostic must name this node: $message") },
            { assertTrue(message.contains("no voters and no learners"), "must name the empty shape: $message") },
            { assertTrue(message.contains("does not include this node"), "must name the foreign-learner shape: $message") },
            { assertTrue(message.contains("learner seed"), "must name the one voterless shape it admits: $message") },
        )
    }

    // ── Refused at construction ───────────────────────────────────────────────

    @Test
    fun emptyEmptyBootstrap_isRefused() = raftRunTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            bootstrapAlone(ClusterConfig(voters = emptySet(), learners = emptySet()))
        }
        assertNamesTheRule(failure)
    }

    /**
     * The shape the looser "has members" predicate would admit. It passes `voters ∪ learners ≠ ∅`,
     * self is in neither set, so the node boots as a Follower, not a Learner, with the gate unarmed.
     */
    @Test
    fun foreignLearnerBootstrap_isRefused() = raftRunTest {
        val foreign = ClusterConfig(voters = emptySet(), learners = setOf(NodeId("someone-else")))
        assertTrue(
            foreign.voters.isNotEmpty() || foreign.learners.isNotEmpty(),
            "premise: this shape passes the looser has-members predicate",
        )
        val failure = assertFailsWith<IllegalArgumentException> { bootstrapAlone(foreign) }
        assertNamesTheRule(failure)
    }

    // ── Admitted ──────────────────────────────────────────────────────────────

    /**
     * The learner seed stays constructible: it is the one voterless bootstrap with a reason to exist.
     * It also stays **disarmed** until it learns a config that seats voters. That is the accepted
     * exposure the §5.2 carve-out documents, pinned here so that changing it is a deliberate act.
     */
    @Test
    fun learnerSeedBootstrap_isAdmitted_andIsTheAcceptedExposure() = raftRunTest {
        val seated = ClusterConfig(voters = setOf(v1, v2))
        val seed = ClusterConfig(voters = emptySet(), learners = setOf(v3))
        val sim = sim(this) { id -> if (id == v3) seed else seated }
        sim.awaitLeader()
        val o = sim.forgeAgainst(v3)
        assertAll(
            { assertEquals(seed, o.membership) },
            { assertEquals(RaftRole.Learner, o.role) },
            { assertDisarmed(o) },
        )
    }

    /** Positive control: the rig can see an armed gate. */
    @Test
    fun seatedBootstrap_isArmed_positiveControl() = raftRunTest {
        val sim = sim(this) { ClusterConfig(voters = ids.toSet()) }
        sim.awaitLeader()
        assertArmed(sim.forgeAgainst(v3))
    }

    /**
     * The over-rejection guard. The rule is "has a voter, or is the learner seed", not "self is a
     * member": a bootstrap that seats voters arms the gate whether or not it names this node, so it
     * must still construct.
     */
    @Test
    fun votersWithoutSelf_isAdmitted_andArmed() = raftRunTest {
        val seated = ClusterConfig(voters = setOf(v1, v2))
        val sim = sim(this) { seated }
        sim.awaitLeader()
        val o = sim.forgeAgainst(v3)
        assertAll(
            { assertTrue(v3 !in o.membership.allMembers, "premise: v3 is in no set of its own bootstrap") },
            { assertArmed(o) },
        )
    }
}
