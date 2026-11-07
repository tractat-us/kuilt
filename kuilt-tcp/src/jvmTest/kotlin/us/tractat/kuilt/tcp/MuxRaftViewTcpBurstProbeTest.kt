package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-socket loopback measurement of a burst against the Raft reader, which needs real threads.
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Scratch evidence for #2863 question 1, real-socket arm: real frames over real loopback TCP into a
 * real LinkSeam, then a MuxSeam view read by a real RaftNode.
 *
 * The frame is one empty `AppendEntries` heartbeat with its MuxSeam tag, encoded by the engine's own
 * codec and pasted from `MuxRaftViewBurstProbeTest.printFrame`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MuxRaftViewTcpBurstProbeTest {
    private val frame: ByteArray =
        "019f626165bf647465726d016c707265764c6f67496e646578006b707265764c6f675465726d0067656e74726965739fff6c6c6561646572436f6d6d697400ffff"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class Trial(val torn: Boolean, val scopeFailed: Boolean)

    private fun trial(burst: Int, dispatcher: CoroutineDispatcher, seed: Int): Trial = runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        val server = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
        val port = (server.localAddress as InetSocketAddress).port
        val hostLoom = TcpLoom.host(server, PeerId("a"), selector)
        val joinLoom = TcpLoom.join(PeerId("b"), selector)
        val hostDeferred = async(Dispatchers.Default) {
            hostLoom.weave(Rendezvous.New(Pattern("burst")))
        }
        val joiner = joinLoom.weave(Rendezvous.Existing(TcpAddress("127.0.0.1", port)))
        val host = hostDeferred.await()
        host.peers.first { PeerId("b") in it }
        joiner.peers.first { PeerId("a") in it }

        val failure = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, e -> failure.compareAndSet(null, e) }
        val scope = CoroutineScope(dispatcher + Job() + handler)
        val view = MuxSeam(host, scope).channel(1)
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
        val writerScope = CoroutineScope(Dispatchers.Default + Job())
        writerScope.launch { joiner.incoming.collect { } }
        val finished = CompletableDeferred<Unit>()
        writerScope.launch {
            repeat(burst) { joiner.sendTo(PeerId("a"), frame) }
            finished.complete(Unit)
        }
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!finished.isCompleted && view.state.value !is SeamState.Torn && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
        Thread.sleep(200)
        val result = Trial(view.state.value is SeamState.Torn, failure.get() != null)
        withTimeoutOrNull(1.seconds) { joiner.close() }
        withTimeoutOrNull(1.seconds) { host.close() }
        writerScope.cancel()
        scope.cancel()
        server.close()
        selector.close()
        result
    }

    @Test
    fun probe() {
        val dispatchers = listOf(
            "default" to Dispatchers.Default,
            "single" to Dispatchers.Default.limitedParallelism(1),
        )
        for ((name, dispatcher) in dispatchers) {
            for (burst in listOf(300, 1000, 5000, 20000)) {
                val trials = (0 until 10).map { trial(burst, dispatcher, it) }
                println(
                    "w2863 tcp dispatcher=$name burst=$burst torn=${trials.count { it.torn }}/10 " +
                        "scopeFailed=${trials.count { it.scopeFailed }}/10",
                )
            }
        }
    }
}
