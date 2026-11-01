@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.Snapshot
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Whether [SpeculativeGame.apply] refuses `action` against `state`. */
private typealias Rule = (state: List<Int>, action: Int) -> Boolean

private val NOTHING_REJECTED: Rule = { _, _ -> false }

private class Rejected(action: Int) : RuntimeException("rejected $action")

private class ProposeFailed : RuntimeException("propose failed")

/**
 * The state is the ordered list of applied actions, so a double-apply, a lost action and a replay
 * in the wrong order all show up in the value. An additive state would hide the last of those.
 */
private class ListGame(private val rejects: Rule) : SpeculativeGame<List<Int>, Int> {
    override fun apply(state: List<Int>, action: Int): List<Int> {
        if (rejects(state, action)) throw Rejected(action)
        return state + action
    }

    override fun snapshot(state: List<Int>): List<Int> = state
    override fun restore(snapshot: List<Int>): List<Int> = snapshot
    override fun fromSnapshot(bytes: ByteArray): List<Int> =
        Cbor.decodeFromByteArray(ListSerializer(Int.serializer()), bytes)
}

/** One local [SpeculativeSequencer.propose] held open at the quorum, with its outcome in hand. */
private class Call(val value: Int, val gate: CompletableDeferred<LogEntry>, val job: Job, val outcome: () -> Throwable?)

/**
 * A sequencer over a [FakeRaftNode] whose every propose parks until the test decides how it ends.
 * Commits carry explicit keys under one client, so each is fresh unless a test calls [redeliver], and
 * the dedup path stays out of the arms that are not about it.
 */
private class Rig(private val test: TestScope, rejects: Rule = NOTHING_REJECTED) {
    val node = FakeRaftNode(initialRole = RaftRole.Leader)
    private var reached: CompletableDeferred<LogEntry>? = null
    private var nextIndex = 1L

    init {
        node.proposeBehavior = {
            val gate = CompletableDeferred<LogEntry>()
            reached = gate
            gate.await()
        }
    }

    val seq = SpeculativeSequencer(
        sequencer = TurnSequencer(node, Int.serializer()),
        game = ListGame(rejects),
        initialState = emptyList(),
        scope = CoroutineScope(UnconfinedTestDispatcher(test.testScheduler)),
    )

    /** Starts a propose. Returns null when `apply` refused it and it never reached the node. */
    fun propose(value: Int): Call? {
        reached = null
        var failure: Throwable? = null
        val job = test.launch(UnconfinedTestDispatcher(test.testScheduler)) {
            try {
                seq.propose(value)
            } catch (e: ProposeFailed) {
                failure = e
            } catch (e: Rejected) {
                failure = e
            }
        }
        test.runCurrent()
        val gate = reached ?: return null
        return Call(value, gate, job) { failure }
    }

    fun succeed(call: Call) {
        call.gate.complete(entry(call.value))
        test.runCurrent()
    }

    fun fail(call: Call) {
        call.gate.completeExceptionally(ProposeFailed())
        test.runCurrent()
    }

    fun cancel(call: Call) {
        call.job.cancel()
        test.runCurrent()
    }

    /** Commits [value] under a fresh key, and returns the key for [redeliver]. */
    suspend fun commit(value: Int): DedupKey {
        val key = DedupKey(ClientId("commits"), nextIndex)
        redeliver(value, key)
        return key
    }

    /** Commits [value] at a new index under [key], as a new leader appending a retry does. */
    suspend fun redeliver(value: Int, key: DedupKey) {
        node.pushCommitted(entry(value, key))
        test.runCurrent()
    }

    suspend fun install(state: List<Int>) {
        node.pushInstall(
            Snapshot(
                throughIndex = nextIndex++,
                state = Cbor.encodeToByteArray(ListSerializer(Int.serializer()), state),
            ),
        )
        test.runCurrent()
    }

    private fun entry(value: Int, key: DedupKey = DedupKey(ClientId("commits"), nextIndex)): LogEntry =
        LogEntry(
            index = nextIndex++,
            term = 1L,
            command = Cbor.encodeToByteArray(Int.serializer(), value),
            dedupKey = key,
        )
}

private fun runRigTest(body: suspend TestScope.() -> Unit) = runTest(timeout = TEST_WEDGE_BACKSTOP) { body() }

private fun Call?.parked(): Call = assertNotNull(this, "rig: the propose must have reached the node")

/**
 * Which pending entry each proposal's outcome retires. A [FakeRaftNode] cannot reorder or fail
 * anything by itself, so every interleaving here is written out by hand or walked from a seed.
 */
class SpeculativeSequencerPendingTest {

    // ── A failed propose removes its own entry (#2767, first way in) ──────────

    @Test
    fun failedProposeRemovesItsOwnEntryNotTheNewest() = runRigTest {
        val rig = Rig(this)
        val a = rig.propose(1).parked()
        val b = rig.propose(2).parked()
        assertEquals(listOf(1, 2), rig.seq.speculativeState.value)

        rig.fail(a)
        assertIs<ProposeFailed>(a.outcome())
        assertEquals(1, rig.seq.pendingCount)
        assertEquals(listOf(2), rig.seq.speculativeState.value, "only the failed proposal leaves the prediction")

        rig.commit(2)
        rig.succeed(b)
        assertAll(
            { assertEquals(0, rig.seq.pendingCount) },
            { assertEquals(listOf(2), rig.seq.speculativeState.value) },
        )
    }

    // ── A throwing apply is never pending (#2767, second way in) ──────────────

    @Test
    fun actionRejectedAtProposeIsNeitherPendingNorSubmitted() = runRigTest {
        val rig = Rig(this, rejects = { _, action -> action == 9 })

        assertNull(rig.propose(9), "a refused action must not reach the node")

        assertEquals(0, rig.seq.pendingCount)
        assertEquals(emptyList(), rig.node.proposals.toList())
        assertEquals(emptyList(), rig.seq.speculativeState.value)

        // Today's failure surfaces here: the stuck action replays inside the commit collector.
        rig.commit(1)
        assertAll(
            { assertEquals(1, rig.seq.confirmedCount.value, "the commit collector must have processed the commit") },
            { assertEquals(listOf(1), rig.seq.speculativeState.value) },
        )
    }

    // ── Commits out of proposal order (#2767, third way in) ───────────────────

    @Test
    fun commitsArrivingOutOfProposalOrderApplyEachActionOnce() = runRigTest {
        val rig = Rig(this)
        val a = rig.propose(1).parked()
        val b = rig.propose(2).parked()

        rig.commit(2)
        assertEquals(1, rig.seq.pendingCount)
        assertEquals(listOf(2, 1), rig.seq.speculativeState.value, "log order first, then what is still pending")

        rig.commit(1)
        assertEquals(0, rig.seq.pendingCount)
        assertEquals(listOf(2, 1), rig.seq.speculativeState.value)

        rig.succeed(a)
        rig.succeed(b)
        assertEquals(0, rig.seq.pendingCount)
    }

    // ── A rejection in the collector does not end it (#2767, triage escalation) ──

    @Test
    fun replayRejectingAPendingActionKeepsTheCollectorAndTheEntry() = runRigTest {
        // 5 is legal until a 7 is the latest action: legal at propose, refused on the new base.
        val rig = Rig(this, rejects = { state, action -> action == 5 && state.lastOrNull() == 7 })
        val call = rig.propose(5).parked()
        assertEquals(listOf(5), rig.seq.speculativeState.value, "rig: the action passed apply at propose")

        rig.commit(7)
        assertEquals(1, rig.seq.confirmedCount.value)
        assertEquals(listOf(7), rig.seq.speculativeState.value, "the replay refused it, so the state omits it")
        assertEquals(1, rig.seq.pendingCount, "it is still in flight: its own outcome retires it")

        rig.commit(8)
        assertEquals(2, rig.seq.confirmedCount.value, "the collector survived the rejection")
        assertEquals(listOf(7, 8, 5), rig.seq.speculativeState.value, "skipped for that replay, not dropped")

        rig.commit(5)
        rig.succeed(call)
        assertAll(
            { assertEquals(3, rig.seq.confirmedCount.value) },
            { assertEquals(0, rig.seq.pendingCount) },
            { assertEquals(listOf(7, 8, 5), rig.seq.speculativeState.value) },
        )
    }

    @Test
    fun committedActionThatApplyRejectsLeavesStateUnchangedAndCollectorAlive() = runRigTest {
        val rig = Rig(this, rejects = { state, action -> action == 5 && 7 in state })
        rig.commit(7)

        rig.commit(5)
        assertEquals(2, rig.seq.confirmedCount.value, "the collector processed the rejected commit")
        assertEquals(listOf(7), rig.seq.speculativeState.value)

        rig.commit(8)
        assertAll(
            { assertEquals(3, rig.seq.confirmedCount.value, "and it is still running") },
            { assertEquals(listOf(7, 8), rig.seq.speculativeState.value) },
        )
    }

    @Test
    fun ownCommitRejectedOnTheAuthoritativeBaseRetiresTheEntry() = runRigTest {
        val rig = Rig(this, rejects = { state, action -> action == 5 && 7 in state })
        val call = rig.propose(5).parked()
        rig.commit(7)
        assertEquals(1, rig.seq.pendingCount, "rig: the entry outlived the commit that made it illegal")

        rig.commit(5)
        rig.succeed(call)

        assertAll(
            { assertEquals(2, rig.seq.confirmedCount.value) },
            { assertEquals(0, rig.seq.pendingCount) },
            { assertEquals(listOf(7), rig.seq.speculativeState.value) },
        )
    }

    @Test
    fun failedProposeReportsItsOwnFailureWhenTheReplayRejects() = runRigTest {
        // 6 is legal only on top of 5, so withdrawing 5 makes the rollback's replay refuse 6.
        val rig = Rig(this, rejects = { state, action -> action == 6 && 5 !in state })
        val five = rig.propose(5).parked()
        val six = rig.propose(6).parked()
        assertEquals(listOf(5, 6), rig.seq.speculativeState.value, "rig: both passed apply at propose")

        rig.fail(five)

        assertIs<ProposeFailed>(five.outcome(), "the caller sees why its propose failed, not the replay's rejection")
        assertEquals(emptyList(), rig.seq.speculativeState.value)
        assertEquals(1, rig.seq.pendingCount, "the other proposal is still in flight")

        rig.fail(six)
        assertEquals(0, rig.seq.pendingCount)
    }

    // ── Equal values are interchangeable: the limit of matching without a key ─

    /**
     * The shape a pure identity removal gets wrong. The commit is really the second call's, but it
     * can only claim the oldest entry of equal value, which is the first call's. If the first call's
     * failure then looked only for its own entry, nothing would ever retire the second's.
     */
    @Test
    fun failureAfterACommitClaimedItsEntryRetiresAnEqualOne() = runRigTest {
        val rig = Rig(this)
        val first = rig.propose(4).parked()
        val second = rig.propose(4).parked()

        rig.commit(4)
        assertEquals(1, rig.seq.pendingCount, "rig: the commit claimed one of the two")

        rig.fail(first)
        rig.succeed(second)

        assertAll(
            { assertEquals(0, rig.seq.pendingCount) },
            { assertEquals(listOf(4), rig.seq.speculativeState.value) },
        )
    }

    /**
     * A foreign action equal to a local pending one claims it. The state still converges, because
     * the authoritative fold never consults the buffer; the price is a window in which the prediction
     * omits the local action, stated in the class KDoc.
     */
    @Test
    fun foreignActionEqualToAPendingOneConvergesWhenTheLocalOneCommits() = runRigTest {
        val rig = Rig(this)
        val local = rig.propose(4).parked()

        rig.commit(4)
        assertEquals(0, rig.seq.pendingCount, "value matching cannot tell the foreign commit from its own")
        assertEquals(listOf(4), rig.seq.speculativeState.value, "the window: the local action is not predicted")

        rig.commit(4)
        rig.succeed(local)
        assertAll(
            { assertEquals(0, rig.seq.pendingCount) },
            { assertEquals(listOf(4, 4), rig.seq.speculativeState.value, "both are in the log, so both are applied") },
        )
    }

    @Test
    fun foreignActionEqualToAPendingOneConvergesWhenTheLocalOneFails() = runRigTest {
        val rig = Rig(this)
        val local = rig.propose(4).parked()

        rig.commit(4)
        rig.fail(local)

        assertAll(
            { assertEquals(0, rig.seq.pendingCount) },
            { assertEquals(listOf(4), rig.seq.speculativeState.value) },
        )
    }

    // ── A duplicate key: the retry path ───────────────────────────────────────

    /**
     * A commit claims the entry, the attempt fails, the caller retries, and a new leader appends the
     * retry under the key that already committed. The fold must not move, and the retry's entry must
     * go wherever it sits: first from behind other entries, then from the head, which is the position
     * where a fresh commit would need no replay and a duplicate still does.
     */
    @Test
    fun duplicateKeyRetiresTheRetryWhereverItSitsAndLeavesTheFoldAlone() = runRigTest {
        val rig = Rig(this)
        val firstFour = rig.propose(4).parked()
        val fourKey = rig.commit(4)
        rig.fail(firstFour)
        val firstFive = rig.propose(5).parked()
        val fiveKey = rig.commit(5)
        rig.fail(firstFive)
        assertEquals(0, rig.seq.pendingCount, "rig: each commit claimed its entry before the attempt failed")

        val retryFour = rig.propose(4).parked()
        val other = rig.propose(2).parked()
        val retryFive = rig.propose(5).parked()
        assertEquals(listOf(4, 5, 4, 2, 5), rig.seq.speculativeState.value, "rig: both retries are predicted again")

        rig.redeliver(5, fiveKey)
        rig.succeed(retryFive)
        assertAll(
            { assertEquals(2, rig.seq.pendingCount, "the twin behind the head is retired") },
            { assertEquals(listOf(4, 5, 4, 2), rig.seq.speculativeState.value, "and the replay drops its prediction") },
        )

        rig.redeliver(4, fourKey)
        rig.succeed(retryFour)
        assertAll(
            { assertEquals(1, rig.seq.pendingCount, "the twin at the head is retired") },
            { assertEquals(listOf(4, 5, 2), rig.seq.speculativeState.value, "a head duplicate still has to replay") },
        )

        rig.commit(2)
        rig.succeed(other)
        assertAll(
            { assertEquals(5, rig.seq.confirmedCount.value) },
            { assertEquals(0, rig.seq.pendingCount) },
            { assertEquals(listOf(4, 5, 2), rig.seq.speculativeState.value) },
        )
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    fun cancelledProposeThatStillCommitsAppliesOnce() = runRigTest {
        val rig = Rig(this)
        val a = rig.propose(1).parked()
        val b = rig.propose(2).parked()

        rig.cancel(a)
        assertEquals(1, rig.seq.pendingCount)
        assertEquals(listOf(2), rig.seq.speculativeState.value, "the cancelled call withdrew its own entry")

        // It had already reached the leader, so its commit still arrives: foreign, applied once.
        rig.commit(1)
        assertEquals(1, rig.seq.pendingCount)
        assertEquals(listOf(1, 2), rig.seq.speculativeState.value)

        rig.commit(2)
        rig.succeed(b)
        assertAll(
            { assertEquals(0, rig.seq.pendingCount) },
            { assertEquals(listOf(1, 2), rig.seq.speculativeState.value) },
        )
    }

    // ── Any interleaving converges ────────────────────────────────────────────

    @Test
    fun anyInterleavingConverges() = walkSeeds(NOTHING_REJECTED)

    /** Refuses by position and value, so a replay on a new base often disagrees with the propose. */
    @Test
    fun anyInterleavingConvergesWhenApplyRejects() = walkSeeds { state, action ->
        action == 2 && state.size % 2 == 1
    }

    private fun walkSeeds(rejects: Rule) = runRigTest {
        var rejected = 0
        val counting: Rule = { state, action -> rejects(state, action).also { if (it) rejected++ } }
        for (seed in 0 until SEEDS) walk(seed, counting, rejects)
        if (rejects !== NOTHING_REJECTED) assertTrue(rejected > 0, "rig: the rule must have refused something")
    }

    /**
     * Drives one seeded interleaving of proposals, outcomes, foreign commits, late commits and
     * snapshot installs, then lets everything land. Values come from a small domain so equal values
     * meet constantly.
     *
     * [reference] is deliberately not the game under test: the expected state is folded here over
     * the commits in delivery order, without the sequencer's help. Every step also checks the bound
     * that makes the convergence argument work: there are never more pending entries than proposals
     * whose own outcome has yet to retire one.
     */
    private suspend fun TestScope.walk(seed: Int, rule: Rule, reference: Rule) {
        val random = Random(seed)
        val rig = Rig(this, rule)
        val inFlight = mutableListOf<Call>()
        val lateFromSuccess = mutableListOf<Int>()
        val lateFromFailure = mutableListOf<Int>()
        var expected = emptyList<Int>()

        suspend fun commit(value: Int) {
            rig.commit(value)
            if (!reference(expected, value)) expected = expected + value
        }

        suspend fun resolve(call: Call, mode: Int) {
            inFlight.remove(call)
            when (mode) {
                0 -> {
                    commit(call.value)
                    rig.succeed(call)
                }
                1 -> {
                    rig.succeed(call)
                    lateFromSuccess += call.value
                }
                2 -> rig.fail(call)
                3 -> {
                    // The outcome was unknown: it failed locally and committed anyway.
                    rig.fail(call)
                    lateFromFailure += call.value
                }
                else -> {
                    rig.cancel(call)
                    lateFromFailure += call.value
                }
            }
        }

        suspend fun deliverLate(from: MutableList<Int>) {
            if (from.isNotEmpty()) commit(from.removeAt(random.nextInt(from.size)))
        }

        repeat(STEPS) {
            when (random.nextInt(11)) {
                0, 1, 2, 3 -> rig.propose(random.nextInt(1, 4))?.let { inFlight += it }
                4, 5, 6 -> if (inFlight.isNotEmpty()) {
                    resolve(inFlight[random.nextInt(inFlight.size)], random.nextInt(5))
                }
                7 -> commit(random.nextInt(1, 4))
                8 -> deliverLate(lateFromSuccess)
                9 -> deliverLate(lateFromFailure)
                else -> if (random.nextInt(4) == 0) {
                    expected = expected + 9
                    rig.install(expected)
                }
            }
            assertTrue(
                rig.seq.pendingCount <= inFlight.size + lateFromSuccess.size,
                "seed=$seed: ${rig.seq.pendingCount} pending with ${inFlight.size} in flight " +
                    "and ${lateFromSuccess.size} commits owed",
            )
            if (rig.seq.pendingCount == 0) {
                assertEquals(expected, rig.seq.speculativeState.value, "seed=$seed: with nothing pending, the state is the fold")
            }
        }
        while (inFlight.isNotEmpty()) resolve(inFlight[random.nextInt(inFlight.size)], random.nextInt(5))
        while (lateFromSuccess.isNotEmpty()) deliverLate(lateFromSuccess)
        while (lateFromFailure.isNotEmpty()) deliverLate(lateFromFailure)

        assertAll(
            { assertEquals(0, rig.seq.pendingCount, "seed=$seed: nothing is pending once everything has landed") },
            { assertEquals(expected, rig.seq.speculativeState.value, "seed=$seed: the state is the log's fold") },
        )
    }

    private companion object {
        const val SEEDS = 300
        const val STEPS = 40
    }
}
