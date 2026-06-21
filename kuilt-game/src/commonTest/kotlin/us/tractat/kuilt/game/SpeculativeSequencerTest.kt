@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.Snapshot
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        // Push foreign commits with explicit distinct dedup keys so they don't collide with
        // in-flight proposal keys (FakeRaftNode stamps stampForNextCommit on foreign pushes
        // during the proposal window; explicit LogEntry bypasses that).
        val foreignClient = ClientId("peer-2")
        node.pushCommitted(LogEntry(index = node.commitIndex.value + 1, term = 1L, command = encodeMove(Move(player = 3, value = 1)), dedupKey = DedupKey(foreignClient, 1L)))
        seq.awaitConfirmedCount(1)
        assertEquals(17, seq.speculativeState.value.total) // 1 + 5+3+8

        node.pushCommitted(LogEntry(index = node.commitIndex.value + 1, term = 1L, command = encodeMove(Move(player = 3, value = 2)), dedupKey = DedupKey(foreignClient, 2L)))
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

    // ── Snapshot install (Reset) rehydrates from the embedded state ───────────

    /** Encodes a [GameState] as a snapshot envelope — here the whole blob is the state's total. */
    private fun encodeSnapshotState(state: GameState): ByteArray =
        format.encodeToByteArray(serializer<Int>(), state.total)

    /** A [SpeculativeGame] that can rebuild its [GameState] from snapshot bytes. */
    private val rehydratingHarness = object : SpeculativeGame<GameState, Move> {
        override fun apply(state: GameState, action: Move): GameState =
            GameState(state.total + action.value)

        override fun snapshot(state: GameState): GameState = state.copy()

        override fun restore(snapshot: GameState): GameState = snapshot

        override fun fromSnapshot(bytes: ByteArray): GameState =
            GameState(format.decodeFromByteArray(serializer<Int>(), bytes))
    }

    private fun rehydrating(
        node: FakeRaftNode,
        initial: GameState = GameState(0),
        scope: CoroutineScope,
    ): SpeculativeSequencer<GameState, Move> = SpeculativeSequencer(
        sequencer = TurnSequencer(node, serializer<Move>(), format),
        game = rehydratingHarness,
        initialState = initial,
        scope = scope,
    )

    @Test
    fun snapshotInstallResetRehydratesAuthoritativeState() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = rehydrating(node, initial = GameState(0), scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // A foreign commit advances state to 4, then a snapshot install replaces it wholesale with 100.
        node.pushCommitted(encodeMove(Move(player = 1, value = 4)))
        seq.awaitConfirmedCount(1)
        assertEquals(4, seq.speculativeState.value.total)

        node.pushInstall(Snapshot(throughIndex = 5L, state = encodeSnapshotState(GameState(100))))
        seq.awaitConfirmedCount(2)

        // State is the snapshot's embedded value — the prior 4 is discarded, not added to.
        assertEquals(100, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    @Test
    fun snapshotInstallResetClearsPendingBuffer() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)

        // Hold a propose in-flight so a pending entry exists when the install arrives.
        val commitGate = CompletableDeferred<LogEntry>()
        node.proposeBehavior = { _ -> commitGate.await() }
        val seq = rehydrating(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        launch(UnconfinedTestDispatcher(testScheduler)) { seq.propose(Move(player = 1, value = 10)) }
        assertEquals(10, seq.speculativeState.value.total)
        assertEquals(1, seq.pendingCount)

        node.pushInstall(Snapshot(throughIndex = 3L, state = encodeSnapshotState(GameState(50))))
        seq.awaitConfirmedCount(1)

        // The pending optimistic apply is discarded; state is purely the rehydrated snapshot.
        assertEquals(50, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)

        commitGate.complete(makeLogEntry(4L, Move(player = 1, value = 10)))
    }

    @Test
    fun commitsAfterSnapshotInstallApplyOnTopOfRehydratedState() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = rehydrating(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        node.pushInstall(Snapshot(throughIndex = 5L, state = encodeSnapshotState(GameState(100))))
        seq.awaitConfirmedCount(1)
        assertEquals(100, seq.speculativeState.value.total)

        // Subsequent committed actions fold onto the rehydrated baseline.
        node.pushCommitted(encodeMove(Move(player = 2, value = 7)))
        seq.awaitConfirmedCount(2)
        assertEquals(107, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    // ── Part 1: Internal exactly-once dedup ───────────────────────────────────

    @Test
    fun duplicateDedupKeyAppliedOnlyOnce() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Push the same dedup key twice — simulating a duplicate forwarded commit
        val clientId = ClientId("test-client")
        val key = DedupKey(clientId, requestId = 1L)
        val entry1 = LogEntry(index = 1L, term = 1L, command = encodeMove(Move(player = 1, value = 10)), dedupKey = key)
        val entry2 = LogEntry(index = 2L, term = 1L, command = encodeMove(Move(player = 1, value = 10)), dedupKey = key)

        node.pushCommitted(entry1)
        seq.awaitConfirmedCount(1)
        node.pushCommitted(entry2)
        seq.awaitConfirmedCount(2)

        // Despite two commits, only applied once
        assertEquals(10, seq.speculativeState.value.total)
    }

    @Test
    fun distinctDedupKeysAreEachApplied() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        val clientId = ClientId("test-client")
        val entry1 = LogEntry(index = 1L, term = 1L, command = encodeMove(Move(player = 1, value = 5)), dedupKey = DedupKey(clientId, 1L))
        val entry2 = LogEntry(index = 2L, term = 1L, command = encodeMove(Move(player = 1, value = 7)), dedupKey = DedupKey(clientId, 2L))

        node.pushCommitted(entry1)
        seq.awaitConfirmedCount(1)
        node.pushCommitted(entry2)
        seq.awaitConfirmedCount(2)

        // Both distinct serials applied: 5 + 7 = 12
        assertEquals(12, seq.speculativeState.value.total)
    }

    @Test
    fun localPendingConfirmsCorrectlyWithDedup() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Local propose: FakeRaftNode auto-stamps a dedup key, commits once
        seq.propose(Move(player = 1, value = 42))

        // Confirmed exactly once — state = 42, no phantom double-apply
        assertEquals(42, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    @Test
    fun foreignDuplicateOfLocallyConfirmedKeyIsNotDoubleApplied() = runTest(timeout = 5.seconds) {
        // Regression: a locally-proposed action confirmed via the pending buffer must still record
        // its key in the dedup table, so a later forwarded duplicate of that SAME key (arriving with
        // no matching pending entry → foreign path) is dropped, not double-applied.
        val node = FakeRaftNode(clientId = ClientId("durable-x"))
        node.setRole(RaftRole.Leader)
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Local propose under a known key (clientId durable-x, requestId 1) → confirmed via pending.
        seq.propose(Move(player = 1, value = 5), requestId = 1L)
        seq.awaitConfirmedCount(1)
        assertEquals(5, seq.speculativeState.value.total)

        // A forwarded/reconnect duplicate of the SAME key commits again (separate log entry).
        node.pushCommitted(
            LogEntry(
                index = 99L,
                term = 1L,
                command = encodeMove(Move(player = 1, value = 5)),
                dedupKey = DedupKey(ClientId("durable-x"), 1L),
            ),
        )
        seq.awaitConfirmedCount(2)

        // Still 5, not 10 — the duplicate was deduped despite the original being a local confirm.
        assertEquals(5, seq.speculativeState.value.total)
    }

    // ── Part 2: propose(action, requestId) overload ───────────────────────────

    @Test
    fun proposeWithRequestIdSucceeds() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        val indexed = seq.propose(Move(player = 1, value = 99), requestId = 1L)

        assertEquals(99, indexed.action.value)
        assertEquals(99, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    @Test
    fun proposeWithRequestIdRollsBackOnFailure() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val cause = LeadershipLostException("lost")
        node.proposeBehavior = { _ -> throw cause }
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        assertFailsWith<LeadershipLostException> {
            seq.propose(Move(player = 1, value = 77), requestId = 1L)
        }

        assertEquals(0, seq.speculativeState.value.total)
        assertEquals(0, seq.pendingCount)
    }

    // ── Part 4: awaitConfirmedCount uses StateFlow suspension ─────────────────

    @Test
    fun confirmedCountExposedAsStateFlow() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // confirmedCount is a StateFlow — verify it starts at 0 and advances
        assertEquals(0, seq.confirmedCount.value)

        node.pushCommitted(encodeMove(Move(player = 1, value = 1)))
        seq.confirmedCount.first { it >= 1 }

        assertEquals(1, seq.confirmedCount.value)
    }

    @Test
    fun awaitConfirmedCountSuspendsUntilThresholdReached() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = speculative(node, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Push two commits; awaitConfirmedCount(2) must suspend until both arrive
        node.pushCommitted(encodeMove(Move(player = 1, value = 3)))
        node.pushCommitted(encodeMove(Move(player = 2, value = 4)))
        seq.awaitConfirmedCount(2)

        assertEquals(7, seq.speculativeState.value.total)
    }
}
