@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression for #1897: the term-adoption bound is **relative to what this node has already seen**,
 * not an absolute ceiling.
 *
 * #1833 bounded adoption at an absolute `MAX_PLAUSIBLE_TERM` (`2^60`), and #1886 then measured why no
 * absolute ceiling can work: closing the boundary needs `T ≤ A ⟹ T + 1 ≤ A` for a ceiling `A`, true
 * only for `A = ∞`. Whatever constant is chosen, the one value the filter admits is the value whose
 * successor every peer — its own author included — drops, so a single frame at the ceiling propagates
 * on ordinary traffic and then no election can ever be admitted again. Moving the constant, or making
 * the test exclusive, relocates the cliff; it does not remove it.
 *
 * Bounding the **jump** removes it outright. `wireTerm - currentTerm > maxTermJump` admits
 * `currentTerm + 1` at *every* term, so there is no term whose successor is unrepresentable. It is
 * also the stronger test — an implausible term is implausible relative to this node's own progress,
 * which is a local witness, where an absolute constant is a guess about the deployment. And it turns
 * a one-frame attack into an infeasible one: reaching `2^60` costs `2^60 / maxTermJump` accepted
 * frames instead of one.
 *
 * The two halves pinned here are the two the old shape could not hold at once:
 *
 * - **No boundary.** A node sitting at exactly the old absolute ceiling adopts one more, because the
 *   *step* is small — [aNodeAtTheOldAbsoluteCeilingAdoptsTheNextTerm].
 * - **Still bounded.** A jump no honest election sequence could have produced is refused *far below*
 *   that ceiling, where the absolute bound admitted it in silence —
 *   [aJumpBeyondTheBoundIsRefusedFarBelowTheOldCeiling].
 *
 * `MAX_PLAUSIBLE_TERM` is untouched by all of this. It keeps its job on the storage and
 * well-formedness paths (`checkedRestoredTerm`, `checkedRestoredSnapshotMeta`,
 * `isWellFormedSnapshotChunk`), where a value is read back off a disk or unpacked from a frame rather
 * than compared against our own progress.
 *
 * ### Method
 *
 * Each single-node case runs one voter of a two-voter cluster and injects a crafted heartbeat from
 * the absent voter ([InMemoryRaftNetwork.deliver], the channel the [RaftSimulation] `deliver*`
 * helpers use). Two voters rather than one so the frame passes the §5.2 leader-authority gate (an
 * `AppendEntries` sender must be a current voter) and so the running node can never reach quorum
 * alone. Its election timeout is set far beyond the test's horizon, so it never campaigns and never
 * raises its own term — which makes `storage.term()` a clean readout of *adoption* and nothing else.
 */
internal class TermJumpBoundTest {

    /**
     * `RaftEngine.MAX_PLAUSIBLE_TERM` — where the *old* absolute bound put its cliff. Still the
     * storage-path ceiling; no longer the adoption rule.
     */
    private val oldAbsoluteCeiling = 1L shl 60

    /**
     * Mirrors `RaftConfig.maxTermJump`'s default. Written as a literal rather than read off the config
     * so a silent change to the default reddens here instead of quietly rewriting what these tests
     * assert.
     */
    private val defaultMaxTermJump = 10_000L

    private val self = NodeId("self")
    private val peer = NodeId("peer")

    /**
     * Election timeouts far beyond the test horizon: the node under test stays a passive follower for
     * the whole run, so nothing but the injected frame can move its persisted term.
     */
    private val holdConfig = RaftConfig(
        electionTimeoutMin = 30.seconds,
        electionTimeoutMax = 60.seconds,
        heartbeatInterval = 1.seconds,
        expectVirtualTime = true,
        random = Random(RAFT_TEST_SEED),
    )

    /** One running voter of a two-voter cluster, plus the handles needed to inject and to assert. */
    private class LoneVoter(val storage: InMemoryRaftStorage, val network: InMemoryRaftNetwork)

    /** Start [self] with a durable term of [startingTerm], and let its init-restore finish. */
    private suspend fun TestScope.loneVoterAt(startingTerm: Long): LoneVoter {
        val storage = InMemoryRaftStorage()
        if (startingTerm != 0L) storage.saveTermAndVotedFor(startingTerm, null)
        val network = InMemoryRaftNetwork()
        backgroundScope.raftNode(
            ClusterConfig(voters = setOf(self, peer)),
            network.transport(self),
            storage,
            holdConfig,
        )
        runCurrent()   // let init restore the persisted term, start the actor, and subscribe to incoming
        return LoneVoter(storage, network)
    }

    /** Inject a leader heartbeat carrying [term] from the absent voter, then let the engine drain it. */
    private suspend fun TestScope.heartbeatAt(voter: LoneVoter, term: Long) {
        voter.network.deliver(
            from = peer,
            to = self,
            bytes = Cbor.encodeToByteArray<RaftMessage>(
                RaftMessage.AppendEntries(term, 0L, 0L, emptyList(), 0L, 0L),
            ),
        )
        runCurrent()
    }

    /**
     * **The property the absolute ceiling could not have.** A node sitting at exactly the old ceiling
     * adopts `ceiling + 1`, because the step is one.
     *
     * Under the absolute bound this frame was dropped by every recipient including its own author —
     * #1833's symptom reached through the single value the filter let past, and the reason a cluster
     * that legitimately elected its way to `2^60` could never elect again. Under the relative bound
     * there is nothing special about `2^60` at all.
     *
     * The term is supplied by **restore**, which after this change is the only way to reach the old
     * ceiling that does not cost ~10^14 accepted frames. That also marks the one consequence this
     * change leaves open: `2^60 + 1` is now *adoptable* but still not *restorable*, because
     * `checkedRestoredTerm` keeps the absolute bound — so a node that adopts it persists a term its
     * own next boot will refuse to start on. The two constants no longer agree by definition the way
     * they did while both were the same ceiling. Unreachable in practice; recorded here rather than
     * left for a reader to rediscover.
     */
    @Test
    fun aNodeAtTheOldAbsoluteCeilingAdoptsTheNextTerm() = raftRunTest {
        val voter = loneVoterAt(oldAbsoluteCeiling)
        heartbeatAt(voter, oldAbsoluteCeiling + 1L)

        assertEquals(
            oldAbsoluteCeiling + 1L,
            voter.storage.term(),
            "a step of one must be admissible at every term — the old absolute ceiling dropped this " +
                "frame on every peer including its author, which is the cliff #1897 removes",
        )
    }

    /**
     * The other half, and the one an absolute ceiling could not express: a jump no honest election
     * sequence could have produced is refused **far below** `2^60`, where the absolute bound admitted
     * it without comment.
     *
     * A million-term leap onto a node at term `0` is not a plausible leader; under the old bound it
     * was adopted in silence because a million is a perfectly plausible *value*.
     */
    @Test
    fun aJumpBeyondTheBoundIsRefusedFarBelowTheOldCeiling() = raftRunTest {
        val voter = loneVoterAt(0L)
        heartbeatAt(voter, 1_000_000L)

        assertEquals(
            0L,
            voter.storage.term(),
            "a jump of 1000000 is implausible relative to this node's own progress and must be " +
                "refused, even though the value itself sits far below the old absolute ceiling",
        )
    }

    /**
     * The new bound's own edge, in the one dimension where it still has one — the size of the step.
     *
     * That edge is harmless in the way the absolute one was not: it bounds a *difference*, so
     * admitting a step of exactly `maxTermJump` does not make the next term unrepresentable. There is
     * no successor to strand.
     */
    @Test
    fun aJumpOfExactlyTheBoundIsAdmittedAndOneMoreIsRefused() = raftRunTest {
        val admitted = loneVoterAt(0L)
        heartbeatAt(admitted, defaultMaxTermJump)
        val refused = loneVoterAt(0L)
        heartbeatAt(refused, defaultMaxTermJump + 1L)

        // Read both terms before asserting: `term()` suspends and `assertAll`'s blocks do not.
        val admittedTerm = admitted.storage.term()
        val refusedTerm = refused.storage.term()
        assertAll(
            {
                assertEquals(
                    defaultMaxTermJump,
                    admittedTerm,
                    "the bound is inclusive: a jump of exactly maxTermJump must be adopted",
                )
            },
            {
                assertEquals(
                    0L,
                    refusedTerm,
                    "one past the bound must be refused",
                )
            },
        )
    }

    /**
     * Ordinary traffic is untouched: a real cluster steps its term by one per election, four orders of
     * magnitude inside the bound. A regression that sized the bound wrongly — or applied it to the
     * wrong field — surfaces here as a cluster that cannot elect, rather than as a mystery partition
     * much later.
     */
    @Test
    fun ordinaryElectionsAreUnaffectedByTheBound() = raftRunTest {
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
        assertEquals(
            emptyList(),
            terms.filter { it !in 1L..defaultMaxTermJump },
            "a normally-operating cluster steps its term by one per election and must stay far " +
                "inside the jump bound; terms=$terms",
        )
    }
}
