@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.Snapshot
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [SpeculativeSequencer]. Each test uses [FakeRaftNode] with explicit
 * [UnconfinedTestDispatcher] so all coroutines run on the virtual-time scheduler.
 */
class SpeculativeSequencerTest {

    @Serializable
    private data class Move(val player: Int, val value: Int)

    /** Additive game state: total is the sum of all applied move values. */
    private data class GameState(val total: Int)

    private val format: BinaryFormat = Cbor

    private val harness = object : SpeculativeGame<GameState, Move> {
        override fun apply(state: GameState, action: Move): GameState =
            GameState(state.total + action.value)

        override fun snapshot(state: GameState): GameState = state.copy()

        override fun restore(snapshot: GameState): GameState = snapshot
    }

    private fun encodeMove(move: Move): ByteArray =
        format.encodeToByteArray(serializer<Move>(), move)

    private fun makeLogEntry(index: Long, move: Move): LogEntry =
        LogEntry(index = index, term = 1L, command = encodeMove(move))

    private fun speculative(
        node: FakeRaftNode,
        initial: GameState = GameState(0),
        scope: CoroutineScope,
    ): SpeculativeSequencer<GameState, Move> = SpeculativeSequencer(
        sequencer = TurnSequencer(node, serializer<Move>(), format),
        game = harness,
        initialState = initial,
        scope = scope,
    )

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun initialSpeculativeStateMatchesInitialState() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = speculative(node, initial = GameState(42), scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals(42, seq.speculativeState.value.total)
    }

    // ── Speculative apply visible before commit ───────────────────────────────

    @Test
    fun speculativeStateVisibleBeforeCommitArrives() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)

        // Block the commit so we can observe speculative state mid-flight
        val commitGate = CompletableDeferred<LogEntry>()
        node.proposeBehavior = { _ -> commitGate.await() }

        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Launch propose (it will suspend at commitGate.await())
        launch(UnconfinedTestDispatcher(testScheduler)) {
            seq.propose(Move(player = 1, value = 10))
        }

        // UnconfinedTestDispatcher runs eagerly — propose has applied speculative state
        assertEquals(10, seq.speculativeState.value.total)
        assertEquals(1, seq.pendingCount)

        // Unblock — cleanup
        commitGate.complete(makeLogEntry(1L, Move(player = 1, value = 10)))
    }

    // ── Matching commit confirms pending (happy path) ─────────────────────────

    @Test
    fun matchingCommitConfirmsPendingAndLeavesStateUnchanged() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        seq.propose(Move(player = 1, value = 5))

        // Commit matched: state stays 5, pending buffer empty
        assertEquals(5, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    @Test
    fun multipleMatchingCommitsAccumulateWithNoPendingRemaining() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        seq.propose(Move(player = 1, value = 3))
        seq.propose(Move(player = 2, value = 7))
        seq.propose(Move(player = 1, value = 2))

        assertEquals(12, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    // ── Foreign commit triggers rollback + replay ─────────────────────────────

    @Test
    fun foreignCommitWithNoPendingAdvancesAuthoritativeState() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        node.pushCommitted(encodeMove(Move(player = 2, value = 7)))
        seq.awaitConfirmedCount(1)

        assertEquals(7, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    @Test
    fun foreignCommitWhilePendingExistsTriggerRollbackThenReplay() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)

        val commitGate = CompletableDeferred<LogEntry>()
        node.proposeBehavior = { _ -> commitGate.await() }

        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        launch(UnconfinedTestDispatcher(testScheduler)) {
            seq.propose(Move(player = 1, value = 10))
        }

        assertEquals(10, seq.speculativeState.value.total)
        assertEquals(1, seq.pendingCount)

        // A foreign peer's action commits first
        node.pushCommitted(encodeMove(Move(player = 2, value = 3)))
        seq.awaitConfirmedCount(1)

        // Authoritative=3, replay pending(10) → 13
        assertEquals(13, seq.speculativeState.value.total)
        assertEquals(1, seq.pendingCount)

        commitGate.complete(makeLogEntry(2L, Move(player = 1, value = 10)))
    }

    @Test
    fun multiplePendingAreReplayed_afterForeignCommit() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)

        val gates = (0..1).map { CompletableDeferred<LogEntry>() }
        var idx = 0
        node.proposeBehavior = { _ -> gates[idx++].await() }

        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        launch(UnconfinedTestDispatcher(testScheduler)) { seq.propose(Move(player = 1, value = 10)) }
        launch(UnconfinedTestDispatcher(testScheduler)) { seq.propose(Move(player = 1, value = 20)) }

        assertEquals(30, seq.speculativeState.value.total)
        assertEquals(2, seq.pendingCount)

        node.pushCommitted(encodeMove(Move(player = 2, value = 1)))
        seq.awaitConfirmedCount(1)

        // Authoritative=1, replay(10+20) → 31
        assertEquals(31, seq.speculativeState.value.total)
        assertEquals(2, seq.pendingCount)

        gates[0].complete(makeLogEntry(2L, Move(player = 1, value = 10)))
        gates[1].complete(makeLogEntry(3L, Move(player = 1, value = 20)))
    }

    // ── Propose failure does not corrupt speculative state ────────────────────

    @Test
    fun notLeaderRejectionDoesNotApplySpeculativeState() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode() // Follower — FakeRaftNode throws NotLeaderException
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        assertFailsWith<NotLeaderException> {
            seq.propose(Move(player = 1, value = 99))
        }

        assertEquals(0, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    @Test
    fun leadershipLostRejectionDoesNotLeavePhantomPendingInput() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val raftCause = LeadershipLostException("lost during test")
        node.proposeBehavior = { _ -> throw raftCause }
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        assertFailsWith<LeadershipLostException> {
            seq.propose(Move(player = 1, value = 42))
        }

        assertEquals(0, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    // ── Deterministic replay produces correct state across rounds ─────────────

    @Test
    fun consecutiveForeignCommitsProduceDeterministicFinalState() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)

        val gates = (0..2).map { CompletableDeferred<LogEntry>() }
        var gIdx = 0
        node.proposeBehavior = { _ -> gates[gIdx++].await() }

        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        launch(UnconfinedTestDispatcher(testScheduler)) { seq.propose(Move(player = 1, value = 5)) }
        launch(UnconfinedTestDispatcher(testScheduler)) { seq.propose(Move(player = 2, value = 3)) }
        launch(UnconfinedTestDispatcher(testScheduler)) { seq.propose(Move(player = 1, value = 8)) }

        assertEquals(16, seq.speculativeState.value.total)

        // Foreign commit #1: rollback, replay all 3 pending
        node.pushCommitted(encodeMove(Move(player = 3, value = 1)))
        seq.awaitConfirmedCount(1)
        assertEquals(17, seq.speculativeState.value.total) // 1 + 5+3+8

        // Foreign commit #2: rollback again, replay all 3 pending
        node.pushCommitted(encodeMove(Move(player = 3, value = 2)))
        seq.awaitConfirmedCount(2)
        assertEquals(19, seq.speculativeState.value.total) // 1+2 + 5+3+8

        gates.forEach { it.complete(makeLogEntry(1L, Move(player = 99, value = 0))) }
    }

    // ── speculativeState emits on each change ─────────────────────────────────

    @Test
    fun speculativeStateFlowEmitsOnEveryStateChange() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        val emissions = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            seq.speculativeState.collect { emissions.add(it.total) }
        }

        // propose commits immediately with FakeRaftNode in Leader mode
        seq.propose(Move(player = 1, value = 5))
        seq.propose(Move(player = 2, value = 3))

        job.cancel()

        // Should see: initial(0), speculative(5), confirm-same(5 not re-emitted), speculative(8), confirm-same
        // StateFlow deduplicates equal consecutive values — but GameState uses data class equality,
        // so equal totals at different steps won't re-emit.
        // Expected: 0 → 5 → 8 (propose applies then commit matches, so no rollback change)
        assertEquals(listOf(0, 5, 8), emissions)
    }

    // ── Snapshot install (Reset) is unsupported — fail loud ───────────────────

    @Test
    fun snapshotInstallResetCausesCollectorToThrow() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val caught = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, e -> caught.complete(e) }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + handler + Job())
        speculative(node, scope = scope) // launches the committed-event collector

        // SpeculativeSequencer cannot replay across a snapshot install (no-compaction constraint),
        // so a TurnEvent.Reset must fail loud rather than silently corrupt the pending buffer.
        node.pushInstall(Snapshot(throughIndex = 1L, state = byteArrayOf(1, 2, 3)))

        val error = caught.await()
        assertTrue(error is IllegalStateException, "Reset must surface as IllegalStateException, got $error")
    }
}
