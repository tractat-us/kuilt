package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.MembershipState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Shared fixtures ────────────────────────────────────────────────────────────

private val a = NodeId("a")
private val b = NodeId("b")
private val c = NodeId("c")
private val d = NodeId("d")
private val e = NodeId("e")
private val learner1 = NodeId("learner1")

private val simple3 = ClusterConfig(voters = setOf(a, b, c))
private val simple3WithLearner = ClusterConfig(voters = setOf(a, b, c), learners = setOf(learner1))
private val simpleState = MembershipState.Simple(simple3)
private val simpleStateWithLearner = MembershipState.Simple(simple3WithLearner)

/** Joint: old = {a,b,c}, new = {a,b,c,d,e} */
private val joint35 = MembershipState.Joint(
    old = ClusterConfig(voters = setOf(a, b, c)),
    new = ClusterConfig(voters = setOf(a, b, c, d, e)),
)

/** Joint: old = {a,b,c,d,e}, new = {a,b,c} — shrink */
private val joint53 = MembershipState.Joint(
    old = ClusterConfig(voters = setOf(a, b, c, d, e)),
    new = ClusterConfig(voters = setOf(a, b, c)),
)

/** Joint: old = {a,b,c}, new = {b,c,d} — leader (a) removed */
private val jointLeaderRemoved = MembershipState.Joint(
    old = ClusterConfig(voters = setOf(a, b, c)),
    new = ClusterConfig(voters = setOf(b, c, d)),
)

class MembershipStateTest {

    // ── isVoter ────────────────────────────────────────────────────────────────

    @Test
    fun simple_isVoter_member() = assertTrue(simpleState.isVoter(a))

    @Test
    fun simple_isVoter_nonMember() = assertFalse(simpleState.isVoter(d))

    @Test
    fun joint_isVoter_inOldOnly() = assertTrue(joint35.isVoter(a))

    @Test
    fun joint_isVoter_inNewOnly() = assertTrue(joint35.isVoter(d))

    @Test
    fun joint_isVoter_inBoth() = assertTrue(joint35.isVoter(b))

    @Test
    fun joint_isVoter_inNeither() = assertFalse(joint35.isVoter(learner1))

    // ── isLearner ──────────────────────────────────────────────────────────────

    @Test
    fun simple_isLearner_nonMember() = assertFalse(simpleState.isLearner(learner1))

    @Test
    fun simple_isLearner_actualLearner() = assertTrue(simpleStateWithLearner.isLearner(learner1))

    @Test
    fun simple_isLearner_voterIsNotLearner() = assertFalse(simpleStateWithLearner.isLearner(a))

    @Test
    fun joint_isLearner_voterIsNotLearner() = assertFalse(joint35.isLearner(a))

    @Test
    fun joint_isLearner_learnerInNew() {
        val jointWithLearner = MembershipState.Joint(
            old = ClusterConfig(voters = setOf(a, b, c)),
            new = ClusterConfig(voters = setOf(a, b, c), learners = setOf(learner1)),
        )
        assertTrue(jointWithLearner.isLearner(learner1))
    }

    // ── currentVoters ──────────────────────────────────────────────────────────

    @Test
    fun simple_currentVoters() = assertEquals(setOf(a, b, c), simpleState.currentVoters)

    @Test
    fun joint_currentVoters_returnsNew() = assertEquals(setOf(a, b, c, d, e), joint35.currentVoters)

    // ── replicationTargets ─────────────────────────────────────────────────────

    @Test
    fun simple_replicationTargets_excludesSelf() {
        assertEquals(setOf(b, c), simpleState.replicationTargets(a))
    }

    @Test
    fun simple_replicationTargets_includesLearners() {
        assertEquals(setOf(b, c, learner1), simpleStateWithLearner.replicationTargets(a))
    }

    @Test
    fun joint_replicationTargets_unionBothSides_excludesSelf() {
        // old={a,b,c} new={a,b,c,d,e}: union = {a,b,c,d,e} minus a = {b,c,d,e}
        assertEquals(setOf(b, c, d, e), joint35.replicationTargets(a))
    }

    @Test
    fun joint_replicationTargets_removedNodeIncluded() {
        // During the joint phase, old nodes that are being removed must still receive replication
        // because they vote in the old majority. joint53: old={a,b,c,d,e} new={a,b,c}.
        // Union minus a = {b,c,d,e}. d and e are being removed but must still be replicated to.
        assertEquals(setOf(b, c, d, e), joint53.replicationTargets(a))
    }

    // ── electionTargets ────────────────────────────────────────────────────────

    @Test
    fun simple_electionTargets_excludesSelf() {
        assertEquals(setOf(b, c), simpleState.electionTargets(a))
    }

    @Test
    fun simple_electionTargets_excludesLearners() {
        // Learners are not voters and are never election targets.
        assertEquals(setOf(b, c), simpleStateWithLearner.electionTargets(a))
    }

    @Test
    fun joint_electionTargets_unionBothVoterSets() {
        // old={a,b,c} new={a,b,c,d,e}: election targets = {a,b,c,d,e} minus a = {b,c,d,e}
        assertEquals(setOf(b, c, d, e), joint35.electionTargets(a))
    }

    // ── voterQuorumReached ─────────────────────────────────────────────────────

    @Test
    fun simple_voterQuorumReached_selfInSet_countsSelf() {
        // 3 voters, quorum=2. Self (a) is a voter. grantingVoters = {b} → 1+1=2 >= 2. Quorum.
        assertTrue(simpleState.voterQuorumReached(setOf(b), a))
    }

    @Test
    fun simple_voterQuorumReached_noGrants_selfAlone() {
        // 3 voters, quorum=2. Self alone = 1. Not enough.
        assertFalse(simpleState.voterQuorumReached(emptySet(), a))
    }

    @Test
    fun simple_voterQuorumReached_allGranting() {
        assertTrue(simpleState.voterQuorumReached(setOf(b, c), a))
    }

    @Test
    fun simple_voterQuorumReached_selfNotInVoterSet() {
        // self=d is NOT a voter in simple3 (voters={a,b,c}). d does not count.
        // grantingVoters={a,b} — a and b are both voters → 2 >= 2. Quorum.
        assertTrue(simpleState.voterQuorumReached(setOf(a, b), d))
    }

    @Test
    fun simple_voterQuorumReached_selfNotInVoterSet_shortfall() {
        // self=d not a voter. grantingVoters={a} — 1 < 2. Not quorum.
        assertFalse(simpleState.voterQuorumReached(setOf(a), d))
    }

    @Test
    fun joint_voterQuorumReached_requiresBothMajorities() {
        // joint35: old={a,b,c} quorum=2, new={a,b,c,d,e} quorum=3.
        // Self=a is in both. granting={b,d,e}. old: a+b=2 ✓. new: a+d+e=3 ✓.
        assertTrue(joint35.voterQuorumReached(setOf(b, d, e), a))
    }

    @Test
    fun joint_voterQuorumReached_oldMajorityMissing() {
        // joint35. Self=a. granting={d,e} — only new voters. old: a=1 < 2. No quorum.
        assertFalse(joint35.voterQuorumReached(setOf(d, e), a))
    }

    @Test
    fun joint_voterQuorumReached_newMajorityMissing() {
        // joint35. Self=a. granting={b} — old: a+b=2 ✓. new: a+b=2 < 3. No quorum.
        assertFalse(joint35.voterQuorumReached(setOf(b), a))
    }

    @Test
    fun joint_voterQuorumReached_removedLeaderNotCreditedInNew() {
        // jointLeaderRemoved: old={a,b,c}, new={b,c,d}. Self=a (the removed leader).
        // a is in old but NOT in new. granting={b,c,d} (all of new).
        // old: a+b+c=3 ≥ 2 ✓. new: b+c+d=3 ≥ 2 ✓. Self NOT credited toward new.
        assertTrue(jointLeaderRemoved.voterQuorumReached(setOf(b, c, d), a))
    }

    @Test
    fun joint_voterQuorumReached_removedLeaderShortfallInNew() {
        // jointLeaderRemoved: old={a,b,c}, new={b,c,d}. Self=a.
        // granting={b,c} — new: b+c=2 ≥ 2 ✓. old: a+b+c=3 ✓. Quorum.
        assertTrue(jointLeaderRemoved.voterQuorumReached(setOf(b, c), a))
    }

    @Test
    fun joint_voterQuorumReached_removedLeaderNewShortfall() {
        // jointLeaderRemoved: old={a,b,c}, new={b,c,d}. Self=a.
        // granting={b} only. old: a+b=2 ✓. new: b=1 < 2. No quorum.
        assertFalse(jointLeaderRemoved.voterQuorumReached(setOf(b), a))
    }

    // ── committedIndex ─────────────────────────────────────────────────────────

    @Test
    fun simple_committedIndex_twoOfThreeAck() {
        // 3 voters, quorum=2. Leader=a, matchIndex={b:5, c:3}. leaderLast=5.
        // self(a) in voters → peerQuorum=1. peerMatches=[5,3] sorted desc → [5,3][0]=5.
        assertEquals(5L, simpleState.committedIndex(mapOf(b to 5L, c to 3L), 5L, a))
    }

    @Test
    fun simple_committedIndex_oneAck_sufficesForQuorum() {
        // quorum=2: peerQuorum=1 (self+1 peer). matchIndex={c:3}, leaderLast=5.
        // peerMatches=[3], sorted=[3][0]=3. Commits at 3.
        assertEquals(3L, simpleState.committedIndex(mapOf(c to 3L), 5L, a))
    }

    @Test
    fun simple_committedIndex_noPeersAck_returnsNull() {
        // peerQuorum=1 but no peers in matchIndex → null.
        assertNull(simpleState.committedIndex(emptyMap(), 5L, a))
    }

    @Test
    fun simple_committedIndex_selfNotVoter_purelyPeerCount() {
        // Self=d is not a voter in simple3 (voters={a,b,c}). Leader=d.
        // peerQuorum=2 (quorumSize=2, self not a voter so peerQuorum=2).
        // matchIndex={a:5, b:5} → sorted=[5,5], [5,5][1]=5.
        assertEquals(5L, simpleState.committedIndex(mapOf(a to 5L, b to 5L), 10L, d))
    }

    @Test
    fun simple_committedIndex_selfNotVoter_oneShort() {
        // Self=d not a voter. peerQuorum=2. Only one peer → null.
        assertNull(simpleState.committedIndex(mapOf(a to 5L), 10L, d))
    }

    @Test
    fun joint_committedIndex_minOfBothSides() {
        // joint35: old={a,b,c} quorum=2, new={a,b,c,d,e} quorum=3.
        // Self=a, leaderLast=10. matchIndex={b:8, c:7, d:6, e:5}.
        // old peerQuorum=1: peerMatches in old={b:8,c:7} → [8,7][0]=8. Leader(a) in old → simpleIdx=8.
        // new peerQuorum=2: peerMatches in new={b:8,c:7,d:6,e:5} → [8,7,6,5][1]=7. simpleIdx=7.
        // min(8,7)=7.
        assertEquals(7L, joint35.committedIndex(mapOf(b to 8L, c to 7L, d to 6L, e to 5L), 10L, a))
    }

    @Test
    fun joint_committedIndex_oldSideLacks_returnsNull() {
        // joint35. Self=a. old needs 1 peer ACK from {b,c}. matchIndex has only new-side peers.
        assertNull(joint35.committedIndex(mapOf(d to 9L, e to 9L), 10L, a))
    }

    @Test
    fun joint_committedIndex_newSideLacks_returnsNull() {
        // joint35: old={a,b,c} quorum=2 peerQuorum=1, new={a,b,c,d,e} quorum=3 peerQuorum=2.
        // Self=a in both. matchIndex={b:8} only.
        // old: peerMatches=[8], size=1 >= 1 ✓ → oldIdx=8.
        // new: peerMatches=[8] (only b in new), size=1 < peerQuorum=2 → newIdx=null → overall null.
        assertNull(joint35.committedIndex(mapOf(b to 8L), 10L, a))
    }

    // ── quorumOfContacts (CheckQuorum) ─────────────────────────────────────────

    @Test
    fun simple_quorumOfContacts_selfInSet_twoContacted() {
        // 3 voters, quorum=2. Self=a. contacted={b}. a+b=2 ≥ 2. True.
        assertTrue(simpleState.quorumOfContacts(setOf(b), a))
    }

    @Test
    fun simple_quorumOfContacts_selfInSet_noneContacted() {
        // Self=a alone = 1 < 2. False.
        assertFalse(simpleState.quorumOfContacts(emptySet(), a))
    }

    @Test
    fun simple_quorumOfContacts_allContacted() {
        assertTrue(simpleState.quorumOfContacts(setOf(b, c), a))
    }

    @Test
    fun joint_quorumOfContacts_requiresBothSides() {
        // joint35. Self=a in both. contacted={b,d,e}: old: a+b=2 ✓, new: a+d+e=3 ✓.
        assertTrue(joint35.quorumOfContacts(setOf(b, d, e), a))
    }

    @Test
    fun joint_quorumOfContacts_onlySatisfiesOld() {
        // joint35. Self=a. contacted={b,c}: old: a+b+c=3 ✓. new: a+b+c=3 ✓ (quorum=3). True.
        assertTrue(joint35.quorumOfContacts(setOf(b, c), a))
    }

    @Test
    fun joint_quorumOfContacts_newSideShortfall() {
        // joint35. Self=a. contacted={b}: old: a+b=2 ✓. new: a+b=2 < 3. False.
        assertFalse(joint35.quorumOfContacts(setOf(b), a))
    }
}
