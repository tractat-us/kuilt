@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that [RaftStorage.saveTermAndVotedFor] is the only path used when
 * the engine advances the term — closing the §5.1/§5.2 crash window where a
 * separate saveTerm + saveVotedFor could leave storage in a half-written state.
 */
class StorageAtomicityTest {

    @Test fun saveTermAndVotedFor_selfVote_isAtomic() = runTest {
        val s = InMemoryRaftStorage()
        s.saveTermAndVotedFor(3L, NodeId("me"))
        assertEquals(3L, s.term())
        assertEquals(NodeId("me"), s.votedFor())
    }

    @Test fun saveTermAndVotedFor_clearVote_isAtomic() = runTest {
        val s = InMemoryRaftStorage()
        s.saveTermAndVotedFor(2L, NodeId("other"))
        s.saveTermAndVotedFor(3L, null)
        assertEquals(3L, s.term())
        assertNull(s.votedFor())
    }

    @Test fun engineTermAdvance_usesSingleAtomicWrite_notTwoSeparateCalls() =
        runTest(UnconfinedTestDispatcher()) {
            val calls = mutableListOf<String>()

            // Spy that records which storage methods are invoked.
            // The composite saveTermAndVotedFor is the ONLY call expected at term-advance sites.
            val spy = object : RaftStorage {
                private val delegate = InMemoryRaftStorage()
                override suspend fun term() = delegate.term()
                override suspend fun saveTerm(term: Long) {
                    calls += "saveTerm($term)"
                    delegate.saveTerm(term)
                }
                override suspend fun votedFor() = delegate.votedFor()
                override suspend fun saveVotedFor(nodeId: NodeId?) {
                    calls += "saveVotedFor($nodeId)"
                    delegate.saveVotedFor(nodeId)
                }
                override suspend fun saveTermAndVotedFor(term: Long, votedFor: NodeId?) {
                    calls += "saveTermAndVotedFor($term,$votedFor)"
                    delegate.saveTermAndVotedFor(term, votedFor)
                }
                override suspend fun appendEntries(entries: List<LogEntry>) =
                    delegate.appendEntries(entries)
                override suspend fun entries(fromIndex: Long) = delegate.entries(fromIndex)
                override suspend fun truncateFrom(index: Long) = delegate.truncateFrom(index)
            }

            val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
            val config = ClusterConfig(voters = ids.toSet())
            // Node "a" uses the spy; others use fresh in-memory storage.
            RaftSimulation(
                nodeIds = ids,
                scope = this,
                raftConfig = FAST_RAFT_CONFIG,
                nodeScope = backgroundScope,
                nodeFactory = { id, transport, storage, nodeScope ->
                    val s = if (id == NodeId("a")) spy else storage
                    nodeScope.raftNode(config, transport, s, FAST_RAFT_CONFIG)
                },
            )

            delay(100) // run through at least one full election cycle

            val separateSaveTermCalls = calls.filter { it.startsWith("saveTerm(") }
            assertTrue(
                separateSaveTermCalls.isEmpty(),
                "saveTerm() called separately — must use saveTermAndVotedFor() at term-advance sites.\n" +
                    "Recorded calls: $calls",
            )
        }

}
