@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [ClusterClient.close] ends everything [clusterClient] started (#3017): the reconnect loop stops
 * dialling and the room it joined is left, so the fabric sees no new dials and no open seam.
 *
 * One [us.tractat.kuilt.raft.RaftNode], a learner with no voter reachable, so there is no cluster to
 * simulate: these tests are about the fabric underneath it, read off [DialRecordingLoom].
 */
class ClusterClientCloseTest {

    /** Long enough for a tear to reach the loop, the re-dial to land, and the room's own resume to try. */
    private val settle = 10.seconds

    @Test
    fun `close leaves the room and closes its seam`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val client = rig.client()
            joined()
            assertAll(
                { assertEquals(1, rig.loom.dials, "rig: the loop dialled once") },
                { assertEquals(1, rig.server.roster.value.size, "rig: the server admitted the client") },
                { assertIs<SeamState.Woven>(rig.loom.seams.single().state.value, "rig: the link is up") },
            )

            client.close()
            runCurrent()

            assertAll(
                { assertIs<SeamState.Torn>(rig.loom.seams.single().state.value, "the room's seam must be closed") },
                { assertEquals(emptySet(), rig.server.roster.value, "and the server must have seen the client go") },
            )
        }

    /**
     * The control for the next test: an open client re-dials on a tear, so a count of one means
     * something. Exactly once, because the torn room is left rather than left to resume by itself, which
     * would dial again and could leave this client holding two rooms.
     */
    @Test
    fun `a tear re-dials once while the client is open`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            rig.client()
            joined()

            rig.loom.seams.single().close(CloseReason.Unreachable)
            advanceTimeBy(settle)
            runCurrent()

            assertEquals(2, rig.loom.dials, "the loop re-dials, and the torn room must not dial as well")
        }

    @Test
    fun `a tear after close dials nothing`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val client = rig.client()
            joined()

            client.close()
            rig.loom.seams.single().close(CloseReason.Unreachable)
            advanceTimeBy(settle)
            runCurrent()

            assertEquals(1, rig.loom.dials, "a closed client must not dial again")
        }

    /** The room a tear left behind is the client's too: it would otherwise resume on its own. */
    @Test
    fun `close after a failover leaves no seam open and nothing dialling`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val client = rig.client()
            joined()
            rig.loom.seams.single().close(CloseReason.Unreachable)
            advanceTimeBy(settle)
            runCurrent()
            assertTrue(rig.loom.seams.size > 1, "rig: the failover produced a second seam")

            client.close()
            val dialsAtClose = rig.loom.dials
            advanceTimeBy(settle)
            runCurrent()

            assertAll(
                { assertEquals(dialsAtClose, rig.loom.dials, "nothing may dial after close") },
                {
                    assertEquals(
                        emptyList(),
                        rig.loom.seams.filter { it.state.value !is SeamState.Torn },
                        "every seam the client ever held must be closed",
                    )
                },
            )
        }

    @Test
    fun `close with a dial in flight abandons it`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val gate = CompletableDeferred<Unit>()
            rig.loom.gate = gate
            val client = rig.client()
            runCurrent()
            assertEquals(1, rig.loom.dials, "rig: the dial is parked in the loom")

            client.close()
            gate.complete(Unit)
            advanceTimeBy(settle)
            runCurrent()

            assertAll(
                { assertEquals(emptyList(), rig.loom.seams, "the dial must not complete into a seam") },
                { assertEquals(1, rig.loom.dials, "and nothing may dial after it") },
            )
        }

    /**
     * A fabric can finish a weave its caller has stopped wanting. The seam it hands back is still this
     * client's to close: the loop must take the room, then find it is cancelled, and `close` must wait.
     */
    @Test
    fun `a weave that completes through the cancel has its seam closed`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val gate = CompletableDeferred<Unit>()
            rig.loom.uncancellableGate = gate
            val client = rig.client()
            runCurrent()
            assertEquals(1, rig.loom.dials, "rig: the dial is parked in the loom")

            val closer = launch { client.close() }
            runCurrent()
            val returnedBeforeWeaveEnded = closer.isCompleted
            gate.complete(Unit)
            closer.join()
            runCurrent()

            assertAll(
                { assertFalse(returnedBeforeWeaveEnded, "close must wait for a weave it cannot cancel") },
                { assertIs<SeamState.Torn>(rig.loom.seams.single().state.value, "the late seam must be closed") },
                { assertEquals(1, rig.loom.dials, "and nothing may dial after it") },
            )
        }

    /**
     * A tear starts a leave that parks in a slow seam close, and `close` lands during it. That leave
     * cannot be interrupted, and returning from it does not check for cancellation, so the loop must
     * check before it dials.
     */
    @Test
    fun `close during the leave of a torn room dials nothing more`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val seamClose = CompletableDeferred<Unit>()
            rig.loom.closeGate = seamClose
            val client = rig.client()
            joined()

            rig.loom.seams.single().close(CloseReason.Unreachable)
            advanceTimeBy(settle)
            runCurrent()
            val dialsDuringLeave = rig.loom.dials

            val closer = launch { client.close() }
            runCurrent()
            val returnedDuringLeave = closer.isCompleted
            seamClose.complete(Unit)
            closer.join()
            advanceTimeBy(settle)
            runCurrent()

            assertAll(
                { assertEquals(1, dialsDuringLeave, "rig: the loop is parked in the leave, not yet re-dialling") },
                { assertFalse(returnedDuringLeave, "rig: close waits for that leave") },
                { assertEquals(1, rig.loom.dials, "a closed client must not dial again") },
            )
        }

    /**
     * The relay transport's two pumps are this client's background work as well. A bare node over a
     * transport with no pumps is the reference, so this does not hard-code how many collectors a node
     * keeps parked until its scope ends.
     */
    @Test
    fun `close ends the transport's pumps`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val (client, scopeJob) = rig.clientInOwnScope()
            joined()
            val activeBeforeClose = scopeJob.activeChildren()
            val nodeAlone = bareNodeActiveChildrenAfterClose()

            client.close()
            runCurrent()

            assertAll(
                { assertTrue(activeBeforeClose > nodeAlone, "rig: an open client runs more than a closed node") },
                { assertEquals(nodeAlone, scopeJob.activeChildren(), "only the node's own collectors may remain") },
            )
        }

    /** A loop cancelled before its first dispatch never runs a line of its body, `finally` included. */
    @Test
    fun `close before the loop first runs still ends the transport's pumps`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val (client, scopeJob) = rig.clientInOwnScope()

            client.close()
            advanceTimeBy(settle)
            runCurrent()
            val nodeAlone = bareNodeActiveChildrenAfterClose()

            assertAll(
                { assertEquals(0, rig.loom.dials, "rig: the loop never ran") },
                { assertEquals(nodeAlone, scopeJob.activeChildren(), "only the node's own collectors may remain") },
            )
        }

    /**
     * The real engine's half of what `close` promises. [FakeRaftNode] completes [ClusterClient.committed]
     * when closed; the engine's is a shared flow that never does, so `close` must not be read as ending
     * a collector.
     */
    @Test
    fun `on the real engine close refuses a propose and leaves committed open`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val client = rig.client()
            joined()
            val collector = backgroundScope.launch { client.committed.collect {} }
            runCurrent()

            client.close()
            advanceTimeBy(settle)
            runCurrent()

            val refusal = assertFailsWith<NotLeaderException> { client.propose(byteArrayOf(1)) }
            assertAll(
                { assertEquals("node is closed", refusal.message) },
                { assertTrue(collector.isActive, "committed does not complete, so its collector stays parked") },
            )
        }

    /**
     * The shield, seen where it can be. The reconnect loop ends itself once cancelled, so the fabric
     * cannot tell whether `close` ran to the end. A node whose close suspends and a connection that
     * takes time to finish can: without the shield the first throws at its suspension and the second
     * is not waited for.
     */
    @Test
    fun `close finishes the node and waits for the connection when its caller is cancelled`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            var nodeClosed = false
            val node = object : RaftNode by FakeRaftNode() {
                override suspend fun close() {
                    yield()
                    nodeClosed = true
                }
            }
            // Takes time to end after it is cancelled, as a seam slow to close does.
            val release = CompletableDeferred<Unit>()
            val connection = backgroundScope.launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { release.await() }
                }
            }
            runCurrent()
            val client = ClusterClient(node, connection)

            val caller = launch {
                coroutineContext[Job]?.cancel()
                client.close()
            }
            runCurrent()
            val returnedBeforeConnectionEnded = caller.isCompleted
            release.complete(Unit)
            caller.join()

            assertAll(
                { assertTrue(nodeClosed, "the node's close must run to its end") },
                { assertFalse(returnedBeforeConnectionEnded, "close must wait for the connection to end") },
                { assertTrue(connection.isCancelled, "rig: close cancelled the connection") },
            )
        }

    /** A host closes its clients from a teardown path that is usually already cancelled. */
    @Test
    fun `close survives its caller being cancelled`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val client = rig.client()
            joined()

            val caller = launch {
                coroutineContext[Job]?.cancel()
                client.close()
            }
            caller.join()
            runCurrent()

            assertAll(
                { assertTrue(caller.isCancelled, "rig: the caller really was cancelled") },
                { assertIs<SeamState.Torn>(rig.loom.seams.single().state.value, "the room's seam must still be closed") },
            )
            rig.loom.seams.single().close(CloseReason.Unreachable)
            advanceTimeBy(settle)
            assertEquals(1, rig.loom.dials, "and the loop must still be stopped")
        }

    @Test
    fun `close is idempotent`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val client = rig.client()
            joined()

            client.close()
            client.close()
            runCurrent()

            assertIs<SeamState.Torn>(rig.loom.seams.single().state.value)
        }

    /**
     * The loom is consumer code running inside the reconnect loop, so `close` can arrive from there.
     * Joining the loop from inside it would wait on itself, under a shield nothing can cancel.
     */
    @Test
    fun `close called from inside the reconnect loop returns and still stops it`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val client = rig.client()
            joined()
            var closeReturned = false
            rig.loom.onDial = {
                client.close()
                closeReturned = true
            }

            rig.loom.seams.single().close(CloseReason.Unreachable)
            advanceTimeBy(settle)
            runCurrent()
            rig.loom.onDial = null
            val dialsAfterClose = rig.loom.dials
            advanceTimeBy(settle)
            runCurrent()

            assertAll(
                { assertTrue(closeReturned, "close must not wait on the loop it was called from") },
                { assertEquals(dialsAfterClose, rig.loom.dials, "the loop must have stopped") },
                {
                    assertEquals(
                        emptyList(),
                        rig.loom.seams.filter { it.state.value !is SeamState.Torn },
                        "and every seam must be closed",
                    )
                },
            )
        }

    /** The loop owns its room, so the scope ending does what `close` does, for a caller that never closed. */
    @Test
    fun `cancelling the client's scope closes the room's seam`(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val rig = rig()
            val clientScope = CoroutineScope(coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            with(rig) { clientScope.client() }
            joined()
            assertFalse(rig.loom.seams.single().state.value is SeamState.Torn, "rig: the link is up")

            clientScope.cancel()
            runCurrent()

            assertIs<SeamState.Torn>(rig.loom.seams.single().state.value)
        }

    // ── Harness ───────────────────────────────────────────────────────────────

    private fun TestScope.joined() {
        runCurrent()
        advanceTimeBy(100.milliseconds)
        runCurrent()
    }

    private suspend fun TestScope.rig(): Rig {
        val inMemory = InMemoryLoom()
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val server = SeamRoomFactory(inMemory, backgroundScope, clock).host(Pattern(SERVER))
        return Rig(DialRecordingLoom(inMemory), server, backgroundScope, clock)
    }

    private fun Job.activeChildren(): Int = children.count { it.isActive }

    /** What a node leaves running in its scope after `close`, over a transport with no pumps of its own. */
    private suspend fun TestScope.bareNodeActiveChildrenAfterClose(): Int {
        val job = Job(backgroundScope.coroutineContext[Job])
        val node = CoroutineScope(backgroundScope.coroutineContext + job).raftNode(
            clusterConfig = CLUSTER_CONFIG,
            transport = PumplessTransport,
            storage = InMemoryRaftStorage(),
            raftConfig = RaftConfig(random = Random(0)),
        )
        runCurrent()
        node.close()
        runCurrent()
        return job.activeChildren().also { job.cancel() }
    }

    /** Its `incoming` parks like the relay transport's, so a node's collector of it stays parked too. */
    private object PumplessTransport : RaftTransport {
        override val selfId: NodeId = CLIENT
        override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(emptySet())
        override val incoming: Flow<RaftEnvelope> = flow { awaitCancellation() }
        override suspend fun sendTo(peer: NodeId, message: ByteArray) = Unit
    }

    private class Rig(
        val loom: DialRecordingLoom,
        val server: Room,
        private val scope: CoroutineScope,
        private val clock: () -> Instant,
    ) {
        fun client(): ClusterClient = scope.client()

        /** A client in a scope of its own, returned with that scope's job so a test can read its children. */
        fun clientInOwnScope(): Pair<ClusterClient, Job> {
            val job = Job(scope.coroutineContext[Job])
            return CoroutineScope(scope.coroutineContext + job).client() to job
        }

        fun CoroutineScope.client(): ClusterClient = clusterClient(
            loom = loom,
            clusterEndpoints = ClusterEndpoints(listOf(InMemoryTag(SERVER))),
            clientNodeId = CLIENT,
            clusterConfig = CLUSTER_CONFIG,
            raftConfig = RaftConfig(random = Random(0)),
            clock = clock,
        )
    }

    /**
     * Implements [Loom] rather than delegating: `Loom by delegate` would forward `join` to the delegate's
     * own default, which calls the delegate's `weave`, and no dial would ever be seen here.
     */
    private class DialRecordingLoom(private val delegate: Loom) : Loom {
        var dials = 0
            private set
        val seams = mutableListOf<Seam>()

        /** Parks each dial until completed, the shape of a slow network. */
        var gate: CompletableDeferred<Unit>? = null

        /** Parks each dial until completed and finishes it even if cancelled: a weave a cancel cannot stop. */
        var uncancellableGate: CompletableDeferred<Unit>? = null

        /** Parks the close of each seam handed out until completed. [seams] holds the seam underneath. */
        var closeGate: CompletableDeferred<Unit>? = null

        /** Runs inside the dial, so inside whichever coroutine is dialling. */
        var onDial: (suspend () -> Unit)? = null

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            dials++
            onDial?.invoke()
            gate?.await()
            val seam = uncancellableGate?.let { uncancellable ->
                withContext(NonCancellable) {
                    uncancellable.await()
                    delegate.weave(rendezvous)
                }
            } ?: delegate.weave(rendezvous)
            seams += seam
            return closeGate?.let { SlowClosingSeam(seam, it) } ?: seam
        }
    }

    private class SlowClosingSeam(private val delegate: Seam, private val gate: CompletableDeferred<Unit>) :
        Seam by delegate {
        override suspend fun close(reason: CloseReason) {
            gate.await()
            delegate.close(reason)
        }
    }

    private companion object {
        const val SERVER = "server"
        val CLIENT = NodeId("client")
        val CLUSTER_CONFIG = ClusterConfig(voters = setOf(NodeId("voter")), learners = setOf(CLIENT))
    }
}
