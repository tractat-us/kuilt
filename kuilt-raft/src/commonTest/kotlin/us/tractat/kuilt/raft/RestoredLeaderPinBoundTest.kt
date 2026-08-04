@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for #2000: the `init` restore must keep a `leaderForTerm` record only when it is a fact
 * about the term it restored. A record for a **higher** term is dropped, not clamped.
 *
 * `leaderForTerm` decides relevance by **equality** with `currentTerm`, and the restore used to seed
 * the durable record raw on the strength of that — "a record for any term but this one is invisible
 * at the read". It is invisible only *at first*. A record restored at `(T + 1, L)` while
 * `currentTerm` is `T` lies **dormant** and arms itself the moment this node reaches `T + 1`, which
 * it does on its own: by adopting the term from a peer, or by winning its own election. The restore's
 * stated worst case — "a wrong pin costs liveness for the remainder of one term" — is the bound for
 * the term the node restores *at*, and this is a term it has not seen yet.
 *
 * Only a storage adapter that lost a write can produce the input. `pinLeaderForTerm` writes
 * `state.currentTerm`, which `persistTermAndVote` has already made durable, so on a store that
 * honours its own writes the record's term never exceeds the persisted term. A torn write that landed
 * the pin and dropped the accompanying term is what these tests replay — hence
 * [InMemoryRaftStorage.saveLeaderForTerm] called directly on a crashed node's storage, ahead of its
 * restart.
 *
 * ### Why the assertions are on attribution
 *
 * [aTornPinAboveTheRestoredTermCannotAuthoriseATimeoutNowAtTheTermItNames] asserts the
 * [RefusalGate] rather than "still a Follower, term unchanged". Two guards can produce the same term
 * and the same role; they cannot produce the same gate (#1989, and #1980 for the measurement that
 * made this necessary). [tornFutureTermPin] additionally asserts, as **preconditions**, that the
 * other four `onTimeoutNow` guards are structurally inert on this trajectory — the frame is at
 * exactly `currentTerm` (guards 1 and 3), the target is a Follower (guard 2) and a voter (guard 5) —
 * so guard 4 is the only one that can fire and the gate assertion is not standing in for a sibling's
 * refusal.
 *
 * [theSameTrajectoryStillAcceptsATimeoutNowOnceThatTermIsLegitimatelyPinned] is the positive
 * control: green before and after the fix, it proves the refusal above is attributable to the missing
 * pin and not to something structural about a restarted, term-walked, partitioned node.
 *
 * ### Discard and clamp are told apart by exactly one test
 *
 * The three tests built on [tornFutureTermPin] move the node **onto** the term the record names, so a
 * clamp back to the restored term lands the pin on a term the node has already left and reads as
 * inert — all three stay green against a clamping mutant, which is measured below, not assumed.
 * [aTornPinAboveTheRestoredTermIsDiscardedRatherThanClampedDownOntoIt] is the one that kills it: it
 * asserts at the term a clamp would move the record *onto*, with the record naming a voter that has
 * never led anything.
 *
 * The two variants of "reaches `T + 1`" differ in what contains them. Reached as a **Follower**
 * (these tests) the torn pin authorises a pre-vote-less election on demand, which is what
 * [aTornPinAboveTheRestoredTermCannotAuthoriseATimeoutNowAtTheTermItNames] closes. Reached by the
 * node's **own election** it puts a Candidate on a live pin for its own term — a state
 * `RaftEngine.startRealElection` otherwise makes unreachable — and today only `onTimeoutNow`'s
 * `|| Candidate` disjunct contains it (#1999). Either way the term's real leader is refused, which is
 * [aTornPinAboveTheRestoredTermDoesNotRefuseThatTermsRealLeader].
 *
 * Each test inherits [raftRunTest]'s `TEST_WEDGE_BACKSTOP` ceiling — a **generous wedge backstop,
 * not an assertion**: it is wall-clock over a virtual-time trajectory, so it measures the host rather
 * than the code (#1891). Fast failure comes from the bounded `await*` / [RaftSimulation.settle]
 * helpers, which are bounded in *virtual* time and so are load-independent.
 */
internal class RestoredLeaderPinBoundTest {

    /** The state [tornFutureTermPin] leaves behind, named so the assertions read as prose. */
    private class TornPin(
        val sim: RaftSimulation,
        /** The node under test: restored at [restoredTerm], walked to [reachedTerm]. */
        val targetId: NodeId,
        /** The node the torn durable record names as leader of [reachedTerm]. */
        val recordedLeaderId: NodeId,
        /** The third voter — never named by the record, so it is the "real leader" of [reachedTerm]. */
        val otherId: NodeId,
        /** `T` — the term the target's storage durably held when it crashed. */
        val restoredTerm: Long,
        /** `T + 1` — the term the torn record names, and the term the target reaches after restore. */
        val reachedTerm: Long,
    )

    /**
     * Build the torn-storage trajectory: a follower that pinned `L` at `T`, crashed with a durable
     * `leaderForTerm` record for `T + 1` and a durable term still at `T`, restarted, and then walked
     * to `T + 1` **without any leader contact there**.
     *
     * The walk is a disrupt-flagged `RequestVote` from the third voter: it moves the term without
     * pinning anyone, so the only identity that can be established for `T + 1` is the restored record.
     * The target is partitioned off first, so nothing else reaches it and every injection below is the
     * only thing it reacts to; nothing here or in a caller advances virtual time after the restart, so
     * no election timer can launder a result either way.
     */
    private suspend fun TestScope.tornFutureTermPin(): TornPin {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderId = sim.idOf(awaitLeader(sim))
        val targetId = sim.nodeIds.first { it != leaderId }
        val otherId = sim.nodeIds.first { it != leaderId && it != targetId }
        sim.awaitTrue("$targetId recognises $leaderId") {
            sim.nodes.getValue(targetId).leader.value == leaderId
        }
        val restoredTerm = sim.storages.getValue(targetId).term()
        val reachedTerm = restoredTerm + 1

        sim.partitionOff(targetId)
        sim.crash(targetId)

        // The torn write: the pin for `reachedTerm` landed, its accompanying term write did not.
        val storage = sim.storages.getValue(targetId)
        storage.saveLeaderForTerm(reachedTerm, leaderId)
        assertEquals(
            restoredTerm, storage.term(),
            "precondition: the torn write leaves the durable term BEHIND the pin — that is the whole input",
        )

        sim.restart(targetId)
        sim.settle()
        assertEquals(
            restoredTerm, sim.storages.getValue(targetId).term(),
            "precondition: the node restores at the durable term, below the term its record names",
        )

        // Walk to `reachedTerm` with no leader contact there. The disrupt flag bypasses leader
        // stickiness; the term is adopted whether or not the vote is granted.
        sim.deliverRequestVote(
            to = targetId,
            from = otherId,
            term = reachedTerm,
            lastLogIndex = Long.MAX_VALUE / 2,
            lastLogTerm = restoredTerm,
            leadershipTransfer = true,
        )
        sim.settle()

        val target = sim.nodes.getValue(targetId)
        val termAfterWalk = sim.storages.getValue(targetId).term()
        assertAll(
            {
                assertEquals(
                    reachedTerm, termAfterWalk,
                    "precondition: the target must have reached the term its record names — and reached it " +
                        "WITHOUT hearing from a leader in it, so nothing legitimately pins one",
                )
            },
            {
                assertTrue(
                    target.role.value is RaftRole.Follower,
                    "precondition: a Follower, so onTimeoutNow guard 2 (self is Leader/Candidate) is " +
                        "structurally inert and cannot stand in for guard 4 — role was ${target.role.value}",
                )
            },
            {
                assertNull(
                    target.leader.value,
                    "precondition: no live-leader belief for the term reached, so an accepted AppendEntries " +
                        "below is visible as `leader` becoming non-null",
                )
            },
        )
        return TornPin(sim, targetId, leaderId, otherId, restoredTerm, reachedTerm)
    }

    /**
     * The lane this bound closes. A `TimeoutNow` from the node the torn record names, at the term the
     * record names, must be refused — the record was never evidence about that term.
     *
     * Seeded raw, the record is live by the time this frame lands and guard 4 passes it, so the node
     * campaigns at `reachedTerm + 1` with the §4.2.3 disrupt flag set, repeatably, on nothing but a
     * lost storage write.
     *
     * The frame carries **exactly** `currentTerm`, which is what makes guards 1 and 3 structurally
     * inert here; the cluster has no learners, so guard 5 is too; [tornFutureTermPin] asserts the
     * Follower role that makes guard 2 inert. Guard 4 is the only gate that can fire, so the gate
     * assertion is a real discrimination rather than a sibling's refusal wearing its name.
     */
    @Test
    fun aTornPinAboveTheRestoredTermCannotAuthoriseATimeoutNowAtTheTermItNames() = raftRunTest {
        val torn = tornFutureTermPin()
        val refusals = collectRefusals(torn.sim)
        torn.sim.settle()   // let the collectors subscribe before the frame is injected
        refusals.clear()

        torn.sim.deliverTimeoutNow(
            to = torn.targetId,
            from = torn.recordedLeaderId,
            term = torn.reachedTerm,
        )
        torn.sim.settle()

        val target = torn.sim.nodes.getValue(torn.targetId)
        val termAfter = torn.sim.storages.getValue(torn.targetId).term()
        // State effects first, so a regression reports the campaign it caused rather than only the
        // absence of a trace event; the gate is the assertion that says WHICH guard stopped it.
        assertAll(
            {
                assertTrue(
                    target.role.value is RaftRole.Follower,
                    "the frame must not campaign the node — role was ${target.role.value}",
                )
            },
            {
                assertEquals(
                    torn.reachedTerm, termAfter,
                    "a refused TimeoutNow must not bump the durable term",
                )
            },
            {
                assertEquals(
                    RefusalGate.TimeoutNowSenderNotEstablishedLeader, refusals.only(torn.targetId).gate,
                    "${torn.recordedLeaderId} was never established as leader of term ${torn.reachedTerm} — a " +
                        "durable record for a term this node had not reached is not evidence that it was",
                )
            },
            {
                assertEquals(
                    torn.recordedLeaderId, refusals.only(torn.targetId).from,
                    "the sender is the very node the torn record names, which is the point",
                )
            },
        )
    }

    /**
     * The positive control, and the honest path the bound must not break: once `L` legitimately
     * establishes itself as leader of `reachedTerm` — by an `AppendEntries` at that term — its
     * `TimeoutNow` is accepted on the very same trajectory.
     *
     * Green before and after the fix, deliberately. That is what makes it a control: it proves the
     * sibling's refusal is attributable to the absent pin, and not to the restart, the term walk, or
     * the partition, any of which would refuse here too.
     */
    @Test
    fun theSameTrajectoryStillAcceptsATimeoutNowOnceThatTermIsLegitimatelyPinned() = raftRunTest {
        val torn = tornFutureTermPin()

        // Real leader contact at `reachedTerm`: this is what pins an identity for it, restore or no.
        torn.sim.deliverAppendEntries(
            to = torn.targetId,
            from = torn.recordedLeaderId,
            term = torn.reachedTerm,
        )
        torn.sim.settle()
        assertEquals(
            torn.recordedLeaderId, torn.sim.nodes.getValue(torn.targetId).leader.value,
            "precondition: the AppendEntries must be accepted, so a leader IS established for " +
                "term ${torn.reachedTerm}",
        )

        torn.sim.deliverTimeoutNow(
            to = torn.targetId,
            from = torn.recordedLeaderId,
            term = torn.reachedTerm,
        )
        torn.sim.settle()
        val termAfter = torn.sim.storages.getValue(torn.targetId).term()

        assertAll(
            {
                assertEquals(
                    RaftRole.Candidate, torn.sim.nodes.getValue(torn.targetId).role.value,
                    "an honest §3.10 TimeoutNow from the term's established leader must still be accepted",
                )
            },
            {
                assertEquals(
                    torn.reachedTerm + 1, termAfter,
                    "and the accepted transfer must campaign at the next term",
                )
            },
        )
    }

    /**
     * The other cost of an armed record, on a different path: `adoptLeaderForTerm` is
     * must-match-once-established, so a torn record naming `L` for `reachedTerm` makes this node drop
     * every `AppendEntries` from whoever *actually* leads that term.
     *
     * Asserted as an **acceptance**, which needs no gate to be attributable: `_leader` is assigned
     * only after `adoptLeaderForTerm` admits the frame (returns `null` — since #2033 it returns the
     * [RefusalGate] that refused, or nothing), so seeing the real leader's id there means every guard
     * on the path passed. [tornFutureTermPin] pins `leader == null` beforehand so the transition is
     * unambiguous.
     *
     * This is the variant that also reaches a node which walked to `reachedTerm` by winning its own
     * pre-vote — the Candidate-on-a-live-pin state (#1999) — since `demoteToFollowerOnLeaderContact`
     * runs before `adoptLeaderForTerm` and the drop happens either way.
     */
    @Test
    fun aTornPinAboveTheRestoredTermDoesNotRefuseThatTermsRealLeader() = raftRunTest {
        val torn = tornFutureTermPin()

        // `otherId` is NOT the node the record names, and it is the one leading `reachedTerm`.
        torn.sim.deliverAppendEntries(
            to = torn.targetId,
            from = torn.otherId,
            term = torn.reachedTerm,
        )
        torn.sim.settle()

        assertEquals(
            torn.otherId, torn.sim.nodes.getValue(torn.targetId).leader.value,
            "a torn record naming ${torn.recordedLeaderId} for term ${torn.reachedTerm} must not make this " +
                "node refuse the AppendEntries of the node that actually leads it — §5.2 says nothing " +
                "about a term the record's writer had not reached",
        )
    }

    /**
     * Discard, **not** clamp — and this is the only test in the suite that can tell the two apart.
     *
     * The three above all put the record's term one above the restored term and then move the node
     * *onto* that term, so a clamp to `currentTerm` lands the pin back on a term the node has already
     * left and reads as inert: every one of them stays green against a clamping mutant. What a clamp
     * actually does is visible only at the term it clamps *onto*, and only when the record names
     * someone the node has no other reason to hold — so the record here names the third voter, which
     * has never led any term.
     *
     * Clamped, this node would hold `neverLeaderId` as the **established leader of the term it
     * restored** and hand it a pre-vote-less election on demand: an authorization invented out of a
     * lost write, which is [`clamp a quantity, discard a nonce`] (#1817) exactly. A leader identity has
     * no conservative in-range reading, so there is no value to clamp *to*.
     *
     * No walk to a later term, and none needed. The positive control is
     * `TimeoutNowAuthorityPinTest.timeoutNowSurvivesARestartOfTheTransferTarget`: the same crash and
     * restart with an **intact** record for the restored term still accepts its leader's `TimeoutNow`,
     * so the refusal here is attributable to the record being about a term the node had not reached,
     * not to the restart.
     */
    @Test
    fun aTornPinAboveTheRestoredTermIsDiscardedRatherThanClampedDownOntoIt() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderId = sim.idOf(awaitLeader(sim))
        val targetId = sim.nodeIds.first { it != leaderId }
        val neverLeaderId = sim.nodeIds.first { it != leaderId && it != targetId }
        sim.awaitTrue("$targetId recognises $leaderId") {
            sim.nodes.getValue(targetId).leader.value == leaderId
        }
        val restoredTerm = sim.storages.getValue(targetId).term()

        sim.partitionOff(targetId)
        sim.crash(targetId)

        // A record for the NEXT term naming a node that has never led any term. Clamping it onto
        // `restoredTerm` would establish $neverLeaderId as that term's leader on this node alone.
        val storage = sim.storages.getValue(targetId)
        storage.saveLeaderForTerm(restoredTerm + 1, neverLeaderId)
        assertEquals(
            restoredTerm, storage.term(),
            "precondition: the torn write leaves the durable term BEHIND the pin",
        )

        sim.restart(targetId)
        sim.settle()

        val target = sim.nodes.getValue(targetId)
        val termOnRestore = sim.storages.getValue(targetId).term()
        assertAll(
            { assertEquals(restoredTerm, termOnRestore, "precondition: the node restores at the durable term") },
            {
                assertTrue(
                    target.role.value is RaftRole.Follower,
                    "precondition: a Follower, so onTimeoutNow guard 2 is structurally inert — role was " +
                        "${target.role.value}",
                )
            },
        )

        val refusals = collectRefusals(sim)
        sim.settle()   // let the collectors subscribe before the frame is injected
        refusals.clear()

        // At EXACTLY the restored term — the term a clamp would have moved the record down onto.
        sim.deliverTimeoutNow(to = targetId, from = neverLeaderId, term = restoredTerm)
        sim.settle()
        val termAfter = sim.storages.getValue(targetId).term()

        assertAll(
            {
                assertTrue(
                    target.role.value is RaftRole.Follower,
                    "a node that never saw $neverLeaderId lead term $restoredTerm must not campaign on its " +
                        "TimeoutNow — role was ${target.role.value}",
                )
            },
            { assertEquals(restoredTerm, termAfter, "a refused TimeoutNow must not bump the durable term") },
            {
                assertEquals(
                    RefusalGate.TimeoutNowSenderNotEstablishedLeader, refusals.only(targetId).gate,
                    "the discarded record must leave term $restoredTerm with NO established leader, so guard 4 " +
                        "refuses every sender — a clamp would have made $neverLeaderId pass it",
                )
            },
        )
    }

    // ── Local copies of FrameRefusedTest's trace helpers ─────────────────────
    // Deliberately duplicated rather than hoisted: they are three lines each, and hoisting them into
    // RaftTestFixtures would widen this change's blast radius past the one restore line it is about.

    /** Every node's [RaftTraceEvent.FrameRefused] events, in arrival order, into one list. */
    private fun TestScope.collectRefusals(sim: RaftSimulation): MutableList<RaftTraceEvent.FrameRefused> {
        val out = mutableListOf<RaftTraceEvent.FrameRefused>()
        sim.nodes.values.forEach { node ->
            backgroundScope.launch {
                node.trace.collect { if (it is RaftTraceEvent.FrameRefused) out += it }
            }
        }
        return out
    }

    /** The one refusal recorded at [node] — asserting there is exactly one, so a second gate cannot hide. */
    private fun List<RaftTraceEvent.FrameRefused>.only(node: NodeId): RaftTraceEvent.FrameRefused {
        val hits = filter { it.node == node }
        assertEquals(1, hits.size, "expected exactly one FrameRefused at $node, all refusals were $this")
        return hits.first()
    }

    private fun RaftSimulation.idOf(node: RaftNode): NodeId = nodeIds.first { nodes[it] === node }
}
