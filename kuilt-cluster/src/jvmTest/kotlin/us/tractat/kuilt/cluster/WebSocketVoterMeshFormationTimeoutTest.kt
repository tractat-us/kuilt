package us.tractat.kuilt.cluster

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorConnectionSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Formation-timeout teardown over the **real** inter-server WebSocket fabric — an opt-in smoke that
 * `voterMeshOverWebSockets` reaches [assembleVoterMesh]'s failure path with real Netty/CIO sockets
 * under it, rather than only in-memory ones.
 *
 * ## Not in ci-required — opt-in via `-P` (#2226)
 *
 * The assertion here sits **downstream of a live loopback WebSocket upgrade**: the lower voter must
 * complete its dial to `/higher`, because a dial that throws fails formation *fast* rather than via the
 * timeout under test. A saturated box loses that race — the server closes before the client parses the
 * upgrade response — and the test then reported a bare exception-type mismatch, naming the timeout
 * logic for a failure that never reached it. That is a property of the box, not of the code, so it must
 * not gate a merge.
 *
 * The behaviour is guarded in ci-required by the deterministic, virtual-time
 * [VoterMeshFormationTimeoutTest], which pins **both** halves of the failure-path obligation (cancel the
 * accept-pumps; close the partially-formed seams) with no socket on the assertion path. This test adds
 * only the real-transport dimension. Run it with:
 *
 * ```
 * ./gradlew :kuilt-cluster:jvmTest -Pcluster.realsocket.tests=true
 * ```
 *
 * Absent the flag it self-skips (a JUnit assumption), so `./gradlew build` compiles it but does not run
 * it. When it *is* run, a lost dial is reported as a lost dial — see [failNamingTheRealEvent].
 *
 * ## What the rig does
 *
 * One real Netty route (`/higher`) exists only so the lower voter's dial upgrades cleanly. The higher
 * voter's accept-source is a [NeverYieldingConnectionSource] — the same rig the deterministic test uses
 * — so the higher voter's roster never fills, `withTimeout(formationTimeout)` fires, and the source's
 * `accept()` cancellation `finally` makes "was the accept-pump torn down?" directly observable.
 */
class WebSocketVoterMeshFormationTimeoutTest {

    private val pingPeriod = 500.milliseconds

    // Short so the timeout fires fast; the never-yielding source guarantees it fires at all.
    private val formationTimeout = 2.seconds

    // Generous bound for observing the post-failure cancellation — far longer than the teardown needs,
    // but it MUST expire on code that never cancels the pump, so it is the RED/GREEN pivot.
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

    /**
     * Self-skip unless `-Pcluster.realsocket.tests=true` was forwarded to the test JVM (see the build
     * script). Shared with [WebSocketVoterMeshReconnectionTest] — one flag for the whole real-socket
     * voter-mesh smoke.
     */
    private fun assumeRealSocketEnabled() =
        assumeTrue(
            "real-socket voter-mesh suite is opt-in: run with -Pcluster.realsocket.tests=true",
            System.getProperty("cluster.realsocket.tests") == "true",
        )

    @Test
    fun formationTimeoutCancelsTheAcceptPumps() {
        assumeRealSocketEnabled()
        runMeshTest { assertFormationTimeoutTearsDownItsPumps() }
    }

    private suspend fun CoroutineScope.assertFormationTimeoutTearsDownItsPumps() {
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
        val higherSource = NeverYieldingConnectionSource()
        val voters = listOf(
            // lower's own inbound route is never dialed (higher dials nothing), so a never-yielding
            // source here is harmless; lower still dials the higher below.
            WebSocketVoter(lower, NeverYieldingConnectionSource(), "ws://localhost:$port/higher"),
            WebSocketVoter(higher, higherSource, "ws://localhost:$port/higher"),
        )

        val scope = CoroutineScope(dispatcher + Job()).also { hostScope = it }

        // Formation must throw the FORMATION TIMEOUT — the caller gets no VoterMesh handle back. Any
        // other throwable means the dial lost its race with the box and formation never got far enough
        // for the timeout to be the thing that fired; that is reported as itself, not as a type
        // mismatch, so a reader sees the real event (#2226).
        // Classify first, assert after — a `fail()` written inside the `try` would be swallowed by the
        // catch-all below and re-reported as a lost dial.
        val failure: Throwable? = try {
            scope.voterMeshOverWebSockets(
                voters = voters,
                httpClient = client,
                dispatcher = dispatcher,
                raftConfig = raftCfg,
                random = Random(1450),
                formationTimeout = formationTimeout,
            )
            null
        } catch (thrown: Throwable) {
            thrown
        }
        when (failure) {
            null -> fail("formation was expected to time out after $formationTimeout, but it succeeded")
            is TimeoutCancellationException -> Unit   // the path under test
            else -> failNamingTheRealEvent(failure)
        }

        // The teardown must have cancelled the accept-pump: the never-yielding source's accept()
        // suspension is cancelled, completing `cancelled`. `accepting` first, so a green cannot come
        // from a pump that never started.
        withTimeout(observeWindow) { higherSource.accepting.await() }
        withTimeout(observeWindow) { higherSource.cancelled.await() }
    }

    /**
     * Fail with the *cause* rather than a bare exception-type mismatch. The distinction #2226 asks this
     * test to preserve is "formation timeout is broken" versus "the box could not complete a loopback
     * WebSocket handshake in time" — the second is an environment condition and says so.
     */
    private fun failNamingTheRealEvent(actual: Throwable): Nothing = fail(
        "the voter dial failed before formation could time out, so the formation timeout was never " +
            "exercised — this is an environment condition (a lost loopback WebSocket upgrade), not a " +
            "formation-timeout regression; the deterministic VoterMeshFormationTimeoutTest is what " +
            "guards the behaviour. Actual failure: $actual",
        actual,
    )

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
}
