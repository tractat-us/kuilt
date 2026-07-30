package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import us.tractat.kuilt.raft.internal.wireTerm
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for #1833: an implausible term must never be adopted.
 *
 * Term adoption had no upper sanity bound — any message carrying a higher term was adopted wholesale
 * via `stepDown(m.term, HigherTermObserved)` — and the election increment is a bare `currentTerm + 1`
 * on a `Long`, which wraps silently. The chain:
 *
 * 1. One frame anywhere carrying `term = Long.MAX_VALUE` is adopted by its recipient.
 * 2. That node's own responses now carry `Long.MAX_VALUE`, so **every peer adopts it in turn** — the
 *    poison propagates on ordinary traffic, no further malice required.
 * 3. The next election computes `Long.MAX_VALUE + 1`, wrapping to `Long.MIN_VALUE`.
 * 4. Every RequestVote/PreVote now proposes a hugely negative term; every recipient compares it
 *    against its stored `Long.MAX_VALUE`, sees a stale term, and denies.
 * 5. **No leader can ever be elected again** — not after a timeout, not after a restart, since
 *    `currentTerm` is persisted.
 *
 * No exception, no log line, no crashed node: the cluster simply stops making progress forever while
 * every node reports itself healthy. That is why the end-to-end half of this test matters more than
 * the arithmetic half — the arithmetic is what breaks, but total silent cluster death is what it
 * costs.
 *
 * Honest terms cannot approach 2^63: they increment once per election, so a real deployment stays
 * many orders of magnitude below a 2^60 ceiling, which leaves ~10^18 elections of headroom. A term
 * outside `0..2^60` is therefore proof of a malformed or foreign frame, and — the #1817 reasoning —
 * there is no conservative in-range reading to clamp to, so the frame is dropped.
 */
internal class TermSanityBoundTest {

    /** Well above any term a real deployment reaches, and the value that makes `+ 1` wrap. */
    private val poisonTerm = Long.MAX_VALUE

    /** Mirrors the engine's plausibility ceiling; anything at or below it must still be honoured. */
    private val maxPlausibleTerm = 1L shl 60

    @Test
    fun implausibleTermIsNotAdopted() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        val termBefore = sim.storages.getValue(victimId).term()
        sim.deliverAppendEntries(to = victimId, from = leaderId, term = poisonTerm)
        delay(10)

        val termAfter = sim.storages.getValue(victimId).term()
        assertTrue(
            termAfter <= maxPlausibleTerm,
            "an implausible term must be dropped, not adopted: $victimId went from $termBefore to $termAfter",
        )
    }

    /**
     * The end-to-end consequence, and the half that would still fail if the bound were placed
     * somewhere a frame can route around. The poison is delivered, allowed to propagate on ordinary
     * traffic, then the incumbent is removed so the cluster is forced to run a real election.
     *
     * On unfixed code every surviving node has already adopted `Long.MAX_VALUE` by this point, so the
     * election proposes `Long.MIN_VALUE`, every pre-vote is denied as stale, and `awaitLeader` fails
     * fast with the harness state dump. This is exactly the transition the arithmetic assertion above
     * cannot see — the poisoned term is only *inert* until a term bump is attempted.
     */
    @Test
    fun clusterStillElectsAfterAnImplausibleTermFrame() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        sim.deliverAppendEntries(to = victimId, from = leaderId, term = poisonTerm)
        delay(20)   // ~10 heartbeat intervals: ample time for the poison to reach every peer

        sim.crash(leaderId)
        val survivors = sim.nodeIds.filter { it != leaderId }.toSet()
        val next = sim.awaitLeader(among = survivors)

        val nextTerm = sim.storages.getValue(sim.nodes.entries.first { it.value === next }.key).term()
        assertTrue(
            nextTerm in 1L..maxPlausibleTerm,
            "the new leader's term must be plausible and positive, not a wrapped one: $nextTerm",
        )
    }

    /**
     * The other direction: the ceiling must not reject ordinary traffic. A cluster that runs normally
     * and re-elects after a crash keeps every term well inside the bound — so a regression that set
     * the ceiling too low, or applied it to the wrong field, shows up here rather than as a mystery
     * partition much later.
     */
    @Test
    fun ordinaryTermsAreUnaffectedByTheBound() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val first = awaitLeader(sim)
        val firstId = sim.nodes.entries.first { it.value === first }.key
        sim.awaitCommit(1L)

        sim.crash(firstId)
        val survivors = sim.nodeIds.filter { it != firstId }.toSet()
        sim.awaitLeader(among = survivors)
        val entry = sim.proposeOnLeader(byteArrayOf(4, 2), among = survivors)
        sim.awaitCommit(entry.index, on = survivors)

        val terms = survivors.map { sim.storages.getValue(it).term() }
        assertTrue(
            terms.all { it in 1L..maxPlausibleTerm },
            "a normally-operating cluster must stay far inside the plausibility bound; terms=$terms",
        )
    }

    // ── The boundary value itself (#1886) ─────────────────────────────────────
    // The tests above use Long.MAX_VALUE, which the bound rejects outright — so they are
    // structurally blind to the one value it ADMITS. A term of exactly `maxPlausibleTerm` passes
    // `wireTerm > MAX_PLAUSIBLE_TERM`, is adopted, and propagates on ordinary traffic; the
    // `currentTerm + 1` that every election necessarily computes then lands at `2^60 + 1`, which the
    // same bound drops on every recipient including its author. No wrap, and #1833's symptom exactly.
    //
    // Measured (#1886): moving the bound to `>=` does NOT fix this — it moves the boundary down by
    // one and the attacker sends `2^60 - 1` instead. A probe run with the exclusive bound wedged
    // identically, with all three voters at `1152921504606846975` and the trace showing
    // `PreVoteStarted(proposedTerm=1152921504606846976)` repeating forever. No absolute ceiling `A`
    // can satisfy `T <= A ==> T + 1 <= A`, so the boundary is inherent to bounding an incremented
    // value against a constant.
    //
    // What IS fixable is the silence. The two assertions below are the containment contract: a node
    // pinned at the ceiling must neither EMIT a frame above it (which every peer, itself included,
    // drops) nor PERSIST one (which #1888's restore guard then refuses to start on). Liveness is
    // deliberately NOT asserted — a cluster pinned at the ceiling still cannot elect, and pretending
    // otherwise here would pin the bug rather than the containment.

    /**
     * The safety half, and the one that outlives a restart: adopting a term at exactly the ceiling
     * must never write `ceiling + 1` to durable storage.
     *
     * `TimeoutNow` is the fast route to the increment — `RaftEngine.onTimeoutNow` calls
     * `startRealElection` directly, deliberately skipping pre-vote since the leader has already
     * validated the target's log, so nothing stalls the term bump the way an ordinary election
     * timeout's pre-vote quorum does.
     *
     * **This is the honest §3.10 lane, not a forgery.** An earlier version of this test reached the
     * ceiling with a single higher-term `TimeoutNow` from a non-leader; #1889 closed that lane (a
     * `TimeoutNow` strictly ahead of our term now carries no checkable authority and is dropped without
     * adopting), which left the test passing *vacuously* — measured: with the `startRealElection` guard
     * disabled the assertion still held, because the victim never left term 1. So the vector is now
     * built from two frames that are each individually **authorised** after #1889: a heartbeat at exactly
     * the ceiling (the wire bound is inclusive, so it is admitted and also sets `_leader`), then a
     * same-term `TimeoutNow` from that same recognised leader. Nothing here is spoofed, and a real
     * cluster reaches this state whenever a leader legitimately sits at the ceiling and transfers away.
     *
     * The persisted value is the durable half of the defect: `ceiling + 1` is above the ceiling, so
     * `checkedRestoredTerm` refuses to start the node on its next boot — a graceful transfer that bricks
     * a restart.
     */
    @Test
    fun aTimeoutNowAtExactlyTheCeilingNeverPersistsATermAboveIt() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        // Adopt the ceiling term from the recognised leader, which also sets `_leader = leaderId` — both
        // preconditions the same-term TimeoutNow below needs to pass #1889's authority checks.
        sim.deliverAppendEntries(to = victimId, from = leaderId, term = maxPlausibleTerm)
        delay(10)
        sim.deliverTimeoutNow(to = victimId, from = leaderId, term = maxPlausibleTerm)
        delay(20)

        val persisted = sim.storages.getValue(victimId).term()
        assertTrue(
            persisted <= maxPlausibleTerm,
            "a term at the ceiling must not be incremented past it into durable storage: " +
                "$victimId persisted $persisted (ceiling $maxPlausibleTerm)",
        )
    }

    /**
     * The liveness half's *containment*: a node pinned at the ceiling must not put a frame on the
     * wire that every recipient — itself included — is contractually obliged to drop.
     *
     * The poison is delivered at exactly the ceiling and allowed to propagate on ordinary traffic, so
     * every voter pins itself; then several election timeouts are allowed to fire. Each one computes
     * `currentTerm + 1 = 2^60 + 1` and, unfixed, broadcasts it as a `PreVote` — a frame with no
     * possible recipient. Recording the network captures the sender's *decision*, before the drop
     * filter, so this sees the emission itself rather than its (non-)delivery.
     */
    @Test
    fun aNodePinnedAtTheCeilingNeverEmitsAFrameAboveIt() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leaderNode = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        val victimId = sim.nodeIds.first { it != leaderId }
        sim.awaitCommit(1L)

        sim.network.recording = true
        sim.deliverAppendEntries(to = victimId, from = leaderId, term = maxPlausibleTerm)
        delay(50)   // several election timeouts: every self-pinned voter gets to try to elect

        val overCeiling = sim.network.sent.filter { (it.message.wireTerm ?: 0L) > maxPlausibleTerm }
        assertTrue(
            overCeiling.isEmpty(),
            "a term above the ceiling must never be emitted — every peer drops it, including its " +
                "author: ${overCeiling.take(3)}",
        )
    }

    /**
     * The diagnosability the containment actually buys, asserted on the engine's observable surface.
     *
     * A single voter is used because it removes every other variable: it needs no quorum, so absent the
     * guard it wins its own election immediately and drives its durable term to `2^60 + 1` in one step.
     * The term is supplied by **restore** rather than by a frame on purpose — it is the other of the two
     * provenances a ceiling term can have, and it is why `checkedRestoredTerm`'s bound did not need to
     * move alongside the wire bound: both provenances converge on this one emission-site check.
     *
     * What is asserted is *not* that the node recovers. It cannot, and must not appear to: the term is
     * pinned, the metric repeats, and the durable term stays exactly where it was. The whole delta over
     * unfixed code is that the failure now has a name.
     */
    @Test
    fun aNodeAtTheCeilingReportsTheSuppressedElectionInsteadOfFailingSilently() = raftRunTest {
        val metrics = mutableListOf<RaftMetric>()
        val storage = InMemoryRaftStorage()
        storage.saveTermAndVotedFor(maxPlausibleTerm, null)
        val self = NodeId("solo")
        val network = InMemoryRaftNetwork()
        backgroundScope.raftNode(
            clusterConfig = ClusterConfig(voters = setOf(self)),
            transport = network.transport(self),
            storage = storage,
            raftConfig = FAST_RAFT_CONFIG,
            onMetric = { metrics += it },
        )
        delay(50)   // several election timeouts: the condition must report as a level, not once

        val suppressed = metrics.filterIsInstance<RaftMetric.ElectionSuppressedTermCeiling>()
        val persisted = storage.term()
        // Hoisted OUT of assertAll deliberately. `assertAll` collects AssertionErrors but lets any other
        // throwable propagate immediately, so an empty `suppressed` would surface as a bare
        // NoSuchElementException from the `first()` calls below and DISCARD this message — losing the
        // `metrics` dump in exactly the run where it is the only useful evidence.
        assertTrue(suppressed.isNotEmpty(), "the suppressed election must be observable; metrics=$metrics")
        assertAll(
            { assertEquals(maxPlausibleTerm, suppressed.first().term, "the metric must name the pinned term") },
            { assertEquals(maxPlausibleTerm, suppressed.first().ceiling, "the metric must name the ceiling") },
            {
                assertTrue(
                    suppressed.size > 1,
                    "the condition is permanent and must re-report on each attempt; got ${suppressed.size}",
                )
            },
            {
                assertTrue(
                    metrics.none { it is RaftMetric.ElectionStarted },
                    "no election may start from the ceiling; metrics=$metrics",
                )
            },
            {
                assertEquals(
                    maxPlausibleTerm,
                    persisted,
                    "a suppressed election must leave the durable term exactly where it was",
                )
            },
        )
    }

    /**
     * A node can land exactly **on** the ceiling as a Candidate, and must not be stranded reporting
     * `Candidate` forever when it does.
     *
     * An election run from `ceiling - 1` persists `ceiling` — a value the wire bound admits — so this is a
     * legitimately reachable state, not a forged one. If that election then fails to win, the *next*
     * election timeout is the first moment anything is wrong. Suppressing it before the role write would
     * leave `role` pinned at `Candidate` for the life of the process: a permanently false reading on the
     * one flow a consumer watches, on the very change whose entire product is diagnosability, and flatly
     * contradicted by what the suppression metric says about the node.
     *
     * Peers are removed first so the triggered election cannot reach quorum — that is what holds the node
     * at the ceiling as a Candidate long enough for the guard to see it. The election is triggered through
     * the same-term `TimeoutNow` lane #1889 leaves open (a heartbeat at `ceiling - 1` establishes both the
     * term and `_leader` first); a higher-term `TimeoutNow` would now be dropped without adopting, which
     * is what made an earlier version of this test fail outright rather than exercise the guard.
     *
     * Also pins the metric lifecycle. The guard returns before the `ElectionTimedOut` that
     * `startRealElection` would have emitted, and no `ElectionStarted` can ever follow, so without an
     * explicit close the `ElectionStarted → ElectionWon`/`ElectionTimedOut` pair documented on `RaftMetric`
     * would dangle for good.
     */
    @Test
    fun aCandidateThatLandsOnTheCeilingDropsBackToFollowerAndClosesItsElectionMetric() = raftRunTest {
        val metricsBy = mutableMapOf<NodeId, MutableList<RaftMetric>>()
        val ids = (1..3).map { NodeId("v$it") }
        val cluster = ClusterConfig(voters = ids.toSet())
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            raftConfig = FAST_RAFT_CONFIG,
            nodeScope = backgroundScope,
            nodeFactory = { id, transport, storage, childScope ->
                childScope.raftNode(
                    cluster,
                    transport,
                    storage,
                    FAST_RAFT_CONFIG,
                    onMetric = { metricsBy.getOrPut(id) { mutableListOf() } += it },
                )
            },
        )
        val leaderNode = sim.awaitLeader()
        val leaderId = sim.nodes.entries.first { it.value === leaderNode }.key
        sim.awaitCommit(1L)
        val (victimId, otherId) = sim.nodeIds.filter { it != leaderId }

        sim.crash(leaderId)
        sim.crash(otherId)
        sim.deliverAppendEntries(to = victimId, from = leaderId, term = maxPlausibleTerm - 1L)
        delay(10)
        sim.deliverTimeoutNow(to = victimId, from = leaderId, term = maxPlausibleTerm - 1L)
        delay(50)   // several election timeouts after the one the TimeoutNow forced

        val role = sim.nodes.getValue(victimId).role.value
        val metrics = metricsBy[victimId].orEmpty()
        val persisted = sim.storages.getValue(victimId).term()
        assertAll(
            {
                assertEquals(
                    maxPlausibleTerm,
                    persisted,
                    "an election off `ceiling - 1` must land exactly ON the ceiling, which is admissible",
                )
            },
            {
                assertTrue(
                    role is RaftRole.Follower,
                    "a Candidate pinned at the ceiling must drop back to Follower, not report Candidate " +
                        "forever: role=$role",
                )
            },
            {
                assertTrue(
                    metrics.any { it is RaftMetric.ElectionSuppressedTermCeiling },
                    "the pinned term must be reported; metrics=$metrics",
                )
            },
            {
                assertTrue(
                    metrics.any { it is RaftMetric.ElectionTimedOut && it.term == maxPlausibleTerm },
                    "a suppressed election must close the ElectionStarted/ElectionTimedOut pair; " +
                        "metrics=$metrics",
                )
            },
        )
    }
}
