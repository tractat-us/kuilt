package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: measures whether a real dispatcher lets a burst outrun the Raft reader, which a virtual-time dispatcher cannot show.
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.raftCbor
import us.tractat.kuilt.test.FakeSeam
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Scratch evidence for #2863 question 1: can a burst of real Raft frames outrun the Raft reader on
 * a real dispatcher, and what does the tear do to the node?
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MuxRaftViewBurstProbeTest {
    private val raftTag: Byte = 1
    private val sender = PeerId("b")

    private fun heartbeat(): ByteArray {
        val encoded = raftCbor.encodeToByteArray<RaftMessage>(
            RaftMessage.AppendEntries(
                term = 1,
                prevLogIndex = 0,
                prevLogTerm = 0,
                entries = emptyList(),
                leaderCommit = 0,
            ),
        )
        return byteArrayOf(raftTag) + encoded
    }

    private class Trial(val torn: Boolean, val emitted: Int, val scopeFailure: Throwable?, val reason: Any?)

    private enum class Upstream { BARE_FLOW, THROUGH_SPOOL }

    private fun trial(burst: Int, dispatcher: CoroutineDispatcher, upstream: Upstream, seed: Int): Trial {
        val frame = heartbeat()
        val failure = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, e -> failure.compareAndSet(null, e) }
        val scope = CoroutineScope(dispatcher + Job() + handler)
        val start = CompletableDeferred<Unit>()
        val emitted = AtomicInteger(0)
        val done = CompletableDeferred<Unit>()
        val bare: Flow<Swatch> = flow {
            start.await()
            repeat(burst) {
                emit(Swatch(frame, sender = sender))
                emitted.incrementAndGet()
            }
            done.complete(Unit)
        }
        val inbox = Spool<Swatch>(DeliveryPolicy.Reliable)
        val base = object : Seam by FakeSeam(selfId = PeerId("a")) {
            override val incoming: Flow<Swatch> =
                if (upstream == Upstream.BARE_FLOW) bare else inbox.incoming

            override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
        }
        if (upstream == Upstream.THROUGH_SPOOL) {
            scope.launch {
                start.await()
                repeat(burst) {
                    inbox.deliver(Swatch(frame, sender = sender))
                    emitted.incrementAndGet()
                }
                done.complete(Unit)
            }
        }
        val mux = MuxSeam(base, scope)
        val view = mux.channel(raftTag)
        scope.raftNode(
            clusterConfig = ClusterConfig(voters = setOf(NodeId("a"), NodeId("b"), NodeId("c"))),
            transport = SeamRaftTransport(view),
            storage = InMemoryRaftStorage(),
            raftConfig = RaftConfig(
                electionTimeoutMin = 30.seconds,
                electionTimeoutMax = 60.seconds,
                heartbeatInterval = 10.seconds,
                random = Random(seed),
            ),
        )
        Thread.sleep(20)
        start.complete(Unit)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done.isCompleted && view.state.value !is SeamState.Torn && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
        // Let the engine's next send land on the torn view.
        Thread.sleep(100)
        val torn = view.state.value as? SeamState.Torn
        val result = Trial(torn != null, emitted.get(), failure.get(), torn?.reason)
        scope.cancel()
        return result
    }

    @Test
    fun probe() = runBlocking {
        val dispatchers = listOf(
            "default" to Dispatchers.Default,
            "single" to Dispatchers.Default.limitedParallelism(1),
            "two" to Dispatchers.Default.limitedParallelism(2),
        )
        for ((name, dispatcher) in dispatchers) {
            for (upstream in Upstream.entries) {
                for (burst in listOf(300, 600, 1000, 2000, 5000)) {
                    val trials = (0 until 10).map { trial(burst, dispatcher, upstream, it) }
                    val torn = trials.filter { it.torn }
                    val minEmitted = torn.minOfOrNull { it.emitted }
                    val fail = trials.firstNotNullOfOrNull { it.scopeFailure }
                    println(
                        "w2863 dispatcher=$name upstream=$upstream burst=$burst " +
                            "torn=${torn.size}/10 minEmittedAtTear=$minEmitted " +
                            "reason=${torn.firstOrNull()?.reason} scopeFailure=$fail",
                    )
                }
            }
        }
    }
}
