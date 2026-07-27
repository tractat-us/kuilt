package us.tractat.kuilt.cluster

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorConnectionSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Formation-timeout teardown for the real inter-server voter mesh.
 *
 * `voterMeshOverWebSockets` launches a persistent accept-pump per voter on a mesh lifecycle scope
 * *before* formation, then awaits the full K_M roster under a `formationTimeout`. If a voter never
 * completes its roster (a crashed/stalled peer), formation throws — and the caller never receives a
 * [VoterMesh], so it has **no handle** to close the mesh scope. This test proves that on that failure
 * path the function itself tears down what it started: the accept-pumps are cancelled and the
 * partially-formed seams are closed, so nothing is orphaned.
 *
 * ## Deterministic timeout with minimal sockets
 *
 * One real Netty route (`/higher`) exists only so the lower voter's dial completes its WebSocket
 * upgrade cleanly (no connect throw — that would fail formation *fast*, not via the timeout under
 * test). The higher voter's [WebSocketVoter.source] is a [NeverYieldingSource] whose `accept()`
 * suspends forever, so the higher voter's roster never fills and `withTimeout(formationTimeout)`
 * fires deterministically. The never-yielding source completes a [CompletableDeferred] in its
 * `accept()` cancellation `finally`, so "was the accept-pump cancelled?" is directly observable.
 *
 * On the pre-fix code the timeout propagates out with the mesh scope never cancelled — the pump keeps
 * draining forever and the deferred never completes; this test times out awaiting it (RED). The fix's
 * try/catch cancels the mesh scope and closes the partial seams, completing the deferred (GREEN).
 */
class WebSocketVoterMeshFormationTimeoutTest {

    private val pingPeriod = 500.milliseconds

    // Short so the timeout fires fast; the never-yielding source guarantees it fires deterministically.
    private val formationTimeout = 2.seconds

    // Generous bound for observing the post-failure cancellation — far longer than the fix needs, but
    // it MUST time out on the unfixed code (which never cancels the pump), so it is the RED/GREEN pivot.
    private val observeWindow = 20.seconds

    private val raftCfg = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(1450),
    )

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var httpClient: HttpClient? = null
    private var hostScope: CoroutineScope? = null

    @Test
    fun formationTimeoutCancelsPumpsAndClosesPartialSeams() = runMeshTest {
        val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher)
        // A real route at /higher so the lower voter's dial upgrades cleanly (no connect throw).
        // Bind 0 and read the port back from the *live* connector. Probing a free port with a
        // throwaway `ServerSocket(0).use { it.localPort }` and re-binding the number is a TOCTOU:
        // the probe closes before Netty binds, so another process on a loaded box can take the port
        // in that window (`BindException: Address already in use` — #1590). Binding 0 has no window.
        val srv = embeddedServer(Netty, port = 0) {
            KtorConnectionSource(this, "/higher", pingPeriod = pingPeriod)
        }.also { server = it }
        srv.start(wait = false)
        val port = srv.engine.resolvedConnectors().first().port

        val client = HttpClient(CIO) { install(ClientWebSockets) { pingInterval = pingPeriod } }
            .also { httpClient = it }

        // lower < higher: the lower voter dials the higher; the higher accepts. The higher's source
        // never yields, so the higher's roster never fills and formation times out.
        val lower = NodeId("voter-a")
        val higher = NodeId("voter-b")
        val higherSource = NeverYieldingSource()
        val voters = listOf(
            // lower's own inbound route is never dialed (higher dials nothing), so a never-yielding
            // source here is harmless; lower still dials the higher below.
            WebSocketVoter(lower, NeverYieldingSource(), "ws://localhost:$port/higher"),
            WebSocketVoter(higher, higherSource, "ws://localhost:$port/higher"),
        )

        val scope = CoroutineScope(dispatcher + Job()).also { hostScope = it }

        // Formation must throw a timeout — the caller gets no VoterMesh handle back.
        assertFailsWith<TimeoutCancellationException> {
            scope.voterMeshOverWebSockets(
                voters = voters,
                httpClient = client,
                dispatcher = dispatcher,
                raftConfig = raftCfg,
                random = Random(1450),
                formationTimeout = formationTimeout,
            )
        }

        // The fix must have cancelled the accept-pump: the never-yielding source's accept() suspension
        // is cancelled, completing this deferred. On the unfixed code the pump keeps running forever and
        // this await times out (the RED signal).
        withTimeout(observeWindow) { higherSource.accepting.await() }
        withTimeout(observeWindow) { higherSource.cancelled.await() }
        assertTrue(higherSource.cancelled.isCompleted, "formation timeout must cancel the accept-pump")
    }

    /**
     * Run [body] under a real [runBlocking] dispatcher and tear every resource down inside the same
     * coroutine, so `cancelChildren()` reaps any leaked CIO session coroutines that attached here —
     * without it a leaked dial session would park `runBlocking` forever after the assertions pass.
     */
    private fun runMeshTest(body: suspend CoroutineScope.() -> Unit): Unit = kotlinx.coroutines.runBlocking {
        try {
            body()
        } finally {
            hostScope?.cancel()
            httpClient?.close()
            server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
            this.coroutineContext.cancelChildren()
        }
    }

    /**
     * A [ConnectionSource] whose [accept] suspends forever. It records when it was entered
     * ([accepting]) and when its suspension was cancelled ([cancelled]) — the latter is exactly the
     * "the accept-pump was torn down" signal.
     */
    private class NeverYieldingSource : ConnectionSource {
        val accepting = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun accept(): Connection {
            accepting.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
    }
}
