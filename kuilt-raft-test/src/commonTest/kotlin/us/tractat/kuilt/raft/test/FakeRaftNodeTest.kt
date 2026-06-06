package us.tractat.kuilt.raft.test

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftTraceEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeRaftNodeTest {

    // ── Defaults ─────────────────────────────────────────────────────────────

    @Test
    fun `default selfId is 'self'`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        assertEquals(NodeId("self"), node.selfId)
    }

    @Test
    fun `default role is Follower`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        assertIs<RaftRole.Follower>(node.role.value)
    }

    @Test
    fun `default leader is null`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        assertNull(node.leader.value)
    }

    @Test
    fun `default commitIndex is zero`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        assertEquals(0L, node.commitIndex.value)
    }

    @Test
    fun `custom constructor args are respected`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode(
            selfId = NodeId("alice"),
            initialRole = RaftRole.Leader,
            initialLeader = NodeId("alice"),
            initialCommitIndex = 5L,
        )
        assertAll(
            { assertEquals(NodeId("alice"), node.selfId) },
            { assertIs<RaftRole.Leader>(node.role.value) },
            { assertEquals(NodeId("alice"), node.leader.value) },
            { assertEquals(5L, node.commitIndex.value) },
        )
    }

    // ── pushCommitted → committed flow ────────────────────────────────────────

    @Test
    fun `pushCommitted_appearsInCommittedFlow`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        val entry = LogEntry(index = 1, term = 1, command = byteArrayOf(42))
        node.pushCommitted(entry)
        val received = node.committed.first()
        assertAll(
            { assertEquals(1L, received.index) },
            { assertEquals(1L, received.term) },
            { assertContentEquals(byteArrayOf(42), received.command) },
        )
    }

    @Test
    fun `pushCommitted_advancesCommitIndex`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        node.pushCommitted(LogEntry(index = 3, term = 1, command = byteArrayOf()))
        assertEquals(3L, node.commitIndex.value)
    }

    @Test
    fun `pushCommitted convenience overload auto-increments index`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        val first = node.pushCommitted(byteArrayOf(1))
        val second = node.pushCommitted(byteArrayOf(2))
        assertAll(
            { assertEquals(1L, first.index) },
            { assertEquals(2L, second.index) },
        )
    }

    @Test
    fun `fake buffers committed entries delivered before collection`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        node.pushCommitted(byteArrayOf(99))
        // No collector was active when pushCommitted was called — channel buffers it.
        val entry = node.committed.first()
        assertContentEquals(byteArrayOf(99), entry.command)
    }

    // ── setRole ───────────────────────────────────────────────────────────────

    @Test
    fun `setRole_reflectedInRoleFlow`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        assertIs<RaftRole.Leader>(node.role.value)
    }

    @Test
    fun `setLeader updates leader flow`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        node.setLeader(NodeId("leader-1"))
        assertEquals(NodeId("leader-1"), node.leader.value)
    }

    @Test
    fun `setLeader to null clears leader`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode(initialLeader = NodeId("leader-1"))
        node.setLeader(null)
        assertNull(node.leader.value)
    }

    @Test
    fun `setCommitIndex updates commitIndex flow without emitting to committed`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        node.setCommitIndex(10L)
        assertEquals(10L, node.commitIndex.value)
    }

    // ── propose ───────────────────────────────────────────────────────────────

    @Test
    fun `propose_recordsCommand`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode(initialRole = RaftRole.Leader)
        val cmd = "hello".encodeToByteArray()
        node.propose(cmd)
        assertAll(
            { assertEquals(1, node.proposals.size) },
            { assertContentEquals(cmd, node.proposals[0]) },
        )
    }

    @Test
    fun `propose_throwsNotLeader_whenFollower`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        assertIs<RaftRole.Follower>(node.role.value)
        assertFailsWith<NotLeaderException> { node.propose(byteArrayOf(1)) }
    }

    @Test
    fun `propose_throwsNotLeader_whenCandidate`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode(initialRole = RaftRole.Candidate)
        assertFailsWith<NotLeaderException> { node.propose(byteArrayOf(1)) }
    }

    @Test
    fun `propose_succeeds_whenLeader`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val cmd = "set x=1".encodeToByteArray()
        val entry = node.propose(cmd)
        val committed = node.committed.first()
        assertAll(
            { assertContentEquals(cmd, entry.command) },
            { assertEquals(entry, committed) },
        )
    }

    @Test
    fun `proposeBehavior_override_injectsException`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode(initialRole = RaftRole.Leader)
        node.proposeBehavior = { _ -> throw LeadershipLostException("injected") }
        val cmd = byteArrayOf(1)
        assertFailsWith<LeadershipLostException> { node.propose(cmd) }
        // propose still records the command even when behavior throws
        assertContentEquals(cmd, node.proposals[0])
    }

    // ── emitTrace ─────────────────────────────────────────────────────────────

    @Test
    fun `emitTrace_appearsInTraceFlow`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        val event = RaftTraceEvent.BecomeLeader(clock = 1L, node = NodeId("self"), term = 2L)
        node.emitTrace(event)
        val received = node.trace.first()
        assertEquals(event, received)
    }

    @Test
    fun `fake buffers trace events emitted before collection`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        val event = RaftTraceEvent.BecomeLeader(clock = 1L, node = NodeId("self"), term = 1L)
        node.emitTrace(event)
        val received = node.trace.first()
        assertEquals(event, received)
    }

    // ── close ─────────────────────────────────────────────────────────────────

    @Test
    fun `close_isIdempotent`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        node.close()
        node.close() // must not throw
        assertTrue(node.closed)
    }

    @Test
    fun `close completes committed and trace flows`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        node.close()
        val committed = node.committed.toList()
        val trace = node.trace.toList()
        assertAll(
            { assertTrue(committed.isEmpty()) },
            { assertTrue(trace.isEmpty()) },
            { assertTrue(node.closed) },
        )
    }

    @Test
    fun `not closed by default`() = runTest(UnconfinedTestDispatcher()) {
        val node = FakeRaftNode()
        assertFalse(node.closed)
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
