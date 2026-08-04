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
 * The gate's *other* arm, `wireTerm < 0L`, is pinned separately by
 * [aNegativeWireTermIsRefusedAtTheDispatchBoundary] (#1980). It is not defence in depth: it is what
 * makes the subtraction above non-overflowing, so deleting it turns `Long.MIN_VALUE` into a term the
 * bound admits.
 *
 * `MAX_PLAUSIBLE_TERM` is untouched by all of this. It keeps its job on the storage and
 * well-formedness paths (`checkedRestoredTerm`, `checkedRestoredSnapshotMeta`,
 * `snapshotChunkRefusal`), where a value is read back off a disk or unpacked from a frame rather
 * than compared against our own progress.
 *
 * ### Method
 *
 * Each single-node case runs one voter of a two-voter cluster and injects a crafted heartbeat from
 * the absent voter ([InMemoryRaftNetwork.deliver], the channel the [RaftSimulation] `deliver*`
 * helpers use). Two voters rather than one so the frame passes the §5.2 leader-authority gate (an
 * `AppendEntries` sender must be a current voter) and so the running node can never reach quorum
 * alone. Its election timeout is set far beyond the test's horizon, so it never campaigns and never
 * raises its own term — which makes `storage.term()` a clean readout of *adoption* and nothing else,
 * and [LoneVoter.emitted] a clean readout of what the injected frame provoked.
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
    private class LoneVoter(val storage: InMemoryRaftStorage, val network: InMemoryRaftNetwork) {
        /**
         * Every frame this voter has attempted to send since it started, decoded and in order.
         *
         * [InMemoryRaftNetwork.recording] is switched on by [loneVoterAt] before the node is built, so
         * the log covers the node's whole life and an empty log means "emitted nothing at all" rather
         * than "emitted nothing after we started watching". Only the running node has a transport
         * here, so anything in the list was authored by [self].
         */
        val emitted: List<InMemoryRaftNetwork.Sent> get() = network.sent
    }

    /** Start [self] with a durable term of [startingTerm], and let its init-restore finish. */
    private suspend fun TestScope.loneVoterAt(startingTerm: Long): LoneVoter {
        val storage = InMemoryRaftStorage()
        if (startingTerm != 0L) storage.saveTermAndVotedFor(startingTerm, null)
        val network = InMemoryRaftNetwork()
        network.recording = true   // on before the node exists, so `emitted` covers its whole life
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
     * The gate's **other** arm — `wireTerm < 0L` — which is not decoration and not defence in depth
     * (#1980).
     *
     * The comparison it guards is written as a subtraction, `wireTerm - currentTerm > maxTermJump`,
     * precisely so the guard against an overflowing term cannot itself overflow; `RaftEngine`'s own
     * note says that rests on **both operands being non-negative**, and `wireTerm` is non-negative
     * only because this arm has already returned on a negative one. Delete the arm and the premise is
     * false, so the subtraction it protects silently stops meaning what it says. (The two were one
     * `if` — `wireTerm < 0L || wireTerm - currentTerm > maxTermJump` — until #1989 split them so a
     * refusal could name which arm fired; the ordering dependency survived the split unchanged, and
     * is why the malformed arm still runs first.)
     *
     * Concretely, and this is the reachable defect: `Long.MIN_VALUE - 0` *is* `Long.MIN_VALUE`, which
     * is not greater than [RaftConfig.maxTermJump]. Without the arm the frame clears the bound, clears
     * the §5.2 leader-authority gate (the sender is a current voter), reaches [onAppendEntries] and
     * draws an `AppendEntriesResponse` — a forged frame answered, where a correct drop is silent.
     *
     * **The starting term must be SMALL, and a future reader must not "simplify" that away.** At a
     * large `currentTerm` the same subtraction wraps *positive* and the frame is refused by accident —
     * arithmetic that happens to land the right way, not the guard working. A test built on a large
     * term would stay green with the arm deleted and would therefore pin nothing.
     *
     * ### What is asserted, and why not the wedge report
     *
     * The observable that separates *refused at the dispatch boundary* from *admitted to dispatch* is
     * whether the sender gets an answer, so that is what this reads: the node emits nothing at all.
     * The positive control alongside it — an admissible term at the same node shape *does* draw a
     * response — is what keeps the negative half from passing vacuously if the tap ever stops
     * observing.
     *
     * Deliberately **not** asserted: a [RaftMetric.WedgeSuspected] naming
     * [RaftMetric.WedgeSuspected.Gate.TermJump]. `noteRefusedLeaderFrame` returns early on
     * `senderTerm < state.currentTerm`, and every negative term is below ours, so this arm's refusals
     * are excluded from that report by construction — the function's own KDoc says so, and
     * [RefusalGate.ImplausibleNegativeTerm]'s repeats it now that the arm has a name. (Since #1989 the
     * arm *does* have a direct attribution observable — a [RaftTraceEvent.FrameRefused] carrying that
     * gate, pinned in `FrameRefusedTest`. This test stays as it is: "the sender gets no answer" is the
     * property that distinguishes refused-at-the-boundary from admitted-to-dispatch, and the trace
     * event does not assert it.) Nor is
     * `storage.term()`: a negative term is not adopted either way (`m.term > currentTerm` is false for
     * `Long.MIN_VALUE`), so the persisted term is 0 with the arm and 0 without it, and an assertion on
     * it would look like coverage while distinguishing nothing.
     */
    @Test
    fun aNegativeWireTermIsRefusedAtTheDispatchBoundary() = raftRunTest {
        // Term 0: the subtraction's non-wrapping case, which is the only one that tests the arm.
        val forged = loneVoterAt(0L)
        heartbeatAt(forged, Long.MIN_VALUE)

        // The control: same node shape, same injection path, an admissible term. Proves the tap below
        // can see a response at all, so "nothing emitted" is a real observation and not a dead assert.
        val honest = loneVoterAt(0L)
        heartbeatAt(honest, 1L)

        assertAll(
            {
                assertEquals(
                    emptyList(),
                    forged.emitted,
                    "a negative wire term must be dropped before dispatch, answering nothing — an " +
                        "emitted frame means it reached the RPC handlers, which is what deleting " +
                        "the `wireTerm < 0L` arm allows: Long.MIN_VALUE - 0 is not > maxTermJump",
                )
            },
            {
                assertEquals(
                    listOf(peer),
                    honest.emitted.map { it.to },
                    "control: an admissible term at the same node reaches dispatch and is answered, " +
                        "so an empty log above is the guard working and not a blind tap",
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
