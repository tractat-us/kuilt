@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.fabric.identified
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.raftCbor
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real [RaftNode] whose seam tears must carry on: a torn seam is an unreachable peer (#3022).
 *
 * [RaftTransport.sendTo] promises it "may silently drop if peer is unreachable", and the engine calls
 * it unguarded, so anything a transport lets out fails the actor and with it the node's scope. A torn
 * seam refuses a send with a plain [IllegalStateException], which the [Seam] contract requires.
 */
class TornSeamRaftNodeTest {
    private val frames = 8

    private fun requestVote(): ByteArray =
        raftCbor.encodeToByteArray<RaftMessage>(RaftMessage.RequestVote(term = 1L, lastLogIndex = 0L, lastLogTerm = 0L))

    @Test
    fun inMemorySeamTornWithFramesBuffered() = raftRunTest {
        val loom = InMemoryLoom()
        val inner = loom.host(Pattern("test"))
        val remote = loom.join(InMemoryTag("joiner"))
        val rig = startNode(inner, NodeId(remote.selfId.value))
        try {
            repeat(frames) { remote.sendTo(inner.selfId, requestVote()) }
            inner.close()
            assertAll(
                { assertEquals(0, rig.seam.framesRead, "the reader must not have run before the tear") },
                { assertTrue(inner.state.value is SeamState.Torn, "the seam must be torn with frames queued") },
            )
            runCurrent()
            assertSurvivedBufferedTear(rig)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun linkSeamTornWithFramesBuffered() = raftRunTest {
        val (mine, theirs) = connectionPair()
        val self = PeerId("self")
        val inner = identified(mine, self, PeerId("remote"), StandardTestDispatcher(testScheduler))
        val rig = startNode(inner, NodeId("remote"))
        try {
            repeat(frames) { theirs.send(requestVote()) }
            theirs.close()
            assertEquals(0, rig.seam.framesRead, "the reader must not have run before the wire ended")
            runCurrent()
            assertTrue(inner.state.value is SeamState.Torn, "a clean end of the wire must tear the seam")
            assertSurvivedBufferedTear(rig)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun inMemorySeamTornWithNothingBuffered() = raftRunTest {
        val loom = InMemoryLoom()
        val inner = loom.host(Pattern("test"))
        val remote = loom.join(InMemoryTag("joiner"))
        val rig = startNode(inner, NodeId(remote.selfId.value))
        try {
            inner.close()
            runCurrent()
            assertEquals(0, rig.seam.sendsAfterTear, "the rig: nothing may have tried to send yet")
            pastTheElectionTimeout()
            assertSurvivedElectionTimerAfterTear(rig)
        } finally {
            rig.scope.cancel()
        }
    }

    @Test
    fun linkSeamTornWithNothingBuffered() = raftRunTest {
        val (mine, theirs) = connectionPair()
        val inner = identified(mine, PeerId("self"), PeerId("remote"), StandardTestDispatcher(testScheduler))
        val rig = startNode(inner, NodeId("remote"))
        try {
            theirs.close()
            runCurrent()
            assertTrue(inner.state.value is SeamState.Torn, "a clean end of the wire must tear the seam")
            assertEquals(0, rig.seam.sendsAfterTear, "the rig: nothing may have tried to send yet")
            pastTheElectionTimeout()
            assertSurvivedElectionTimerAfterTear(rig)
        } finally {
            rig.scope.cancel()
        }
    }

    /** A view's own close: the base stays live, and the view alone refuses the election timer's send. */
    @Test
    fun muxViewClosedWithNothingBuffered() = raftRunTest {
        val loom = InMemoryLoom()
        val base = loom.host(Pattern("test"))
        val remote = loom.join(InMemoryTag("joiner"))
        val muxScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val view = MuxSeam(base, muxScope).channel(RAFT_TAG)
        val rig = startNode(view, NodeId(remote.selfId.value))
        try {
            view.close()
            runCurrent()
            assertAll(
                { assertEquals(SeamState.Woven, base.state.value, "the rig: the base must stay live") },
                { assertEquals(0, rig.seam.sendsAfterTear, "the rig: nothing may have tried to send yet") },
            )
            pastTheElectionTimeout()
            assertSurvivedElectionTimerAfterTear(rig)
        } finally {
            rig.scope.cancel()
            muxScope.cancel()
        }
    }

    /**
     * The check-then-send race, forced: the base tears at the moment the node sends, so the view's own
     * liveness check still passes and the refusal comes from the base underneath.
     */
    @Test
    fun muxViewWhoseBaseTearsAtTheSend() = raftRunTest {
        val loom = InMemoryLoom()
        val base = loom.host(Pattern("test"))
        val remote = loom.join(InMemoryTag("joiner"))
        val muxScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val view = MuxSeam(base, muxScope).channel(RAFT_TAG)
        val remoteView = MuxSeam(remote, muxScope).channel(RAFT_TAG)
        runCurrent()
        var viewStateAtTheSend: SeamState? = null
        val tearing = object : Seam by view {
            override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                if (viewStateAtTheSend == null) {
                    base.close()
                    viewStateAtTheSend = view.state.value
                }
                view.sendTo(peer, payload)
            }
        }
        val rig = startNode(tearing, NodeId(remote.selfId.value))
        try {
            remoteView.sendTo(base.selfId, requestVote())
            runCurrent()
            assertAll(
                { assertEquals(SeamState.Woven, viewStateAtTheSend, "the rig: the view must not know yet") },
                { assertEquals(1, rig.seam.refusals.size, "the rig: the base must have refused the send") },
                { assertEquals(emptyList(), rig.escapes, "a refused send must not reach the node's scope") },
                { assertTrue(rig.job.isActive, "the node's scope must survive") },
            )
        } finally {
            rig.scope.cancel()
            muxScope.cancel()
        }
    }

    private fun assertSurvivedBufferedTear(rig: Rig) = assertAll(
        { assertTrue(rig.seam.framesReadAfterTear > 0, "the rig: frames must be read after the tear") },
        { assertEquals(frames, rig.seam.sendsAfterTear, "the engine must answer every queued frame") },
        { assertEquals(frames, rig.seam.refusals.size, "the rig: the seam must refuse every one of those") },
        { assertEquals(emptyList(), rig.escapes, "a refused reply must not reach the node's scope") },
        { assertTrue(rig.job.isActive, "the node's scope must survive") },
    )

    private fun assertSurvivedElectionTimerAfterTear(rig: Rig) = assertAll(
        { assertTrue(rig.seam.sendsAfterTear > 0, "the rig: the election timer must try to send") },
        { assertTrue(rig.seam.refusals.isNotEmpty(), "the rig: the seam must refuse that send") },
        { assertEquals(emptyList(), rig.escapes, "a refused send must not reach the node's scope") },
        { assertTrue(rig.job.isActive, "the node's scope must survive") },
    )

    private fun TestScope.pastTheElectionTimeout() {
        testScheduler.advanceTimeBy(PAST_THE_ELECTION_TIMEOUT_MS)
        runCurrent()
    }

    /** The node, on a scope shaped like a consumer's, and the seam that watched it. */
    private class Rig(val scope: CoroutineScope, val job: Job, val seam: WatchedSeam, val escapes: List<Throwable>)

    /**
     * A plain [Job] and a recording handler, the shape [UndecodableFrameTest] uses: the damage under
     * test is the node's scope failing, and `backgroundScope`'s supervisor would mask it.
     */
    private fun TestScope.startNode(inner: Seam, other: NodeId): Rig {
        val escapes = mutableListOf<Throwable>()
        val job = Job()
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + job + CoroutineExceptionHandler { _, e -> escapes += e },
        )
        val seam = WatchedSeam(inner)
        val transport = SeamRaftTransport(seam)
        scope.raftNode(
            ClusterConfig(voters = setOf(transport.selfId, other)),
            transport,
            InMemoryRaftStorage(),
            fastRaftConfig(),
        )
        runCurrent()
        return Rig(scope, job, seam, escapes)
    }

    /** Counts what reaches Raft after [inner] tore: the frames it reads and the sends it attempts. */
    private class WatchedSeam(private val inner: Seam) : Seam by inner {
        var framesRead = 0
        var framesReadAfterTear = 0
        var sendsAfterTear = 0
        val refusals = mutableListOf<IllegalStateException>()

        override val incoming: Flow<Swatch> = inner.incoming.onEach {
            framesRead++
            if (inner.state.value is SeamState.Torn) framesReadAfterTear++
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (inner.state.value is SeamState.Torn) sendsAfterTear++
            try {
                inner.sendTo(peer, payload)
            } catch (refused: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                refusals += refused
                throw refused
            }
        }
    }

    private companion object {
        const val RAFT_TAG: Byte = 1

        /** Well past `fastRaftConfig`'s election timeout maximum, and a few re-armings of it. */
        const val PAST_THE_ELECTION_TIMEOUT_MS = 50L
    }
}
