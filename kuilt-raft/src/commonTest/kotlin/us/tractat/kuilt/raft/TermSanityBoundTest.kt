package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlin.test.Test
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
}
