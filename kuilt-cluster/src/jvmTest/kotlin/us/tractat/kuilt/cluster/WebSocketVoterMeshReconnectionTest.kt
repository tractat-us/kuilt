package us.tractat.kuilt.cluster

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorConnectionSource
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Reconnection of the real inter-server voter mesh (real Netty servers + a real CIO client over
 * loopback sockets, half-open injection via a severable TCP proxy).
 *
 * `voterMeshOverWebSockets` forms a K_M complete graph of voter [us.tractat.kuilt.raft.RaftNode]s
 * over real WebSockets and then keeps it whole: a persistent accept-pump drains each voter's route
 * forever, and a per-voter redial supervisor re-dials any dropped peer under exponential-backoff
 * full jitter. This test proves the loop end-to-end — a **half-open** voter-to-voter link (silently
 * dead TCP, no FIN/RST, the WebSocket ping's job to reap) is detected on both ends, then re-dialed,
 * and the cluster commits across the healed edge.
 *
 * ## Half-open injection
 *
 * One voter's inbound route sits behind a [SeverableTcpProxy] that can stop forwarding bytes without
 * closing either socket — the faithful half-open (an ordinary close would send a clean close frame,
 * which is not half-open at all). The proxy fronts a voter that exactly **one** peer dials (the
 * lowest-id dialer under the lower-id-dials-higher rule), so severing it drops precisely that one
 * edge; [SeverableTcpProxy.restore] lets the redial reconnect once the "network" recovers.
 *
 * ## Real-socket discipline
 *
 * Real engines demand a real dispatcher: the meshes run on the [runBlocking] event-loop dispatcher
 * (pulled from the coroutine context — no `Dispatchers` import, honouring the test-source ban the
 * same way `WebSocketPingHalfOpenTest` does). Every await is a hard `withTimeout`. Teardown (in the
 * test's own `runBlocking` coroutine) cancels the mesh scope, closes the seams (their WebSocket
 * sessions) and the client/servers, then `cancelChildren()` — without it the leaked half-open CIO
 * sessions would park `runBlocking` forever after the assertions pass (the lesson
 * `WebSocketPingHalfOpenTest` documents).
 *
 * ## Not in ci-required — opt-in via `-P`
 *
 * These real-socket tests are a manual smoke, **not** part of the always-run gate: their coverage in
 * ci-required is provided by the deterministic, virtual-time [VoterMeshReconnectionTest] over a
 * severable in-memory fabric (no real sockets, no flake surface). The flag is shared with the sibling
 * [WebSocketVoterMeshFormationTimeoutTest], which is opt-in for the same reason (#2226). To run the
 * real-socket suite (e.g. to sanity-check the actual Ktor CIO ping/redial path):
 *
 * ```
 * ./gradlew :kuilt-cluster:jvmTest -Pcluster.realsocket.tests=true
 * ```
 *
 * Absent the flag every `@Test` here self-skips (a JUnit assumption), so `./gradlew build` compiles
 * the suite but does not run it.
 */
class WebSocketVoterMeshReconnectionTest {

    /**
     * Self-skip unless `-Pcluster.realsocket.tests=true` was forwarded to the test JVM
     * (see the build script). Mirrors the mdns multicast opt-in — keeps the flaky real-socket suite
     * out of ci-required while leaving it runnable on demand.
     */
    private fun assumeRealSocketReconnectionEnabled() =
        assumeTrue(
            "real-socket reconnection suite is opt-in: run with -Pcluster.realsocket.tests=true",
            System.getProperty("cluster.realsocket.tests") == "true",
        )

    // Short so the test is snappy; production defaults to 15s (KtorServerLoom.DEFAULT_PING_PERIOD).
    private val pingPeriod = 500.milliseconds

    // Per-step wall-clock budget: covers a real-loopback election, a half-open ping-reap
    // (~pingPeriod + pong timeout), or a backoff redial + a Raft commit across the healed edge. Sized
    // for a COLD, coverage-instrumented, contended CI runner — locally the whole suite runs in ~13s, but
    // `aDroppedEdgeHealsAndRaftCommitsAcrossIt`'s 3-voter election/commit over real sockets timed out at
    // 25s once under CI load (a timing budget, not a logic fault — the operation completes, just slowly).
    private val window = 60.seconds

    // Wider election windows than the M=1 tests: the voter mesh needs real loopback round-trips for
    // vote grants; a seeded RNG so timeouts differ and one voter actually wins.
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(1450),
    )

    // Per-test resources, torn down inside the test's own runBlocking coroutine (see runMeshTest).
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var httpClient: HttpClient? = null
    private var proxy: SeverableTcpProxy? = null
    private var voterMesh: VoterMesh? = null
    private var hostScope: CoroutineScope? = null

    @Test
    fun aDroppedEdgeHealsAndRaftCommitsAcrossIt() = runMeshTest {
        assumeRealSocketReconnectionEnabled()
        val (a, b, c) = Triple(NodeId("voter-a"), NodeId("voter-b"), NodeId("voter-c"))
        // Only voter-a dials voter-b (a < b, and c is higher than both), so voter-b's route is the one
        // edge frontable by a proxy: severing it drops precisely the a↔b link.
        val mesh = formMesh(
            voterA = a to "/voter-a",
            voterB = b to "/voter-b",   // dialed by voter-a through the proxy
            voterC = c to "/voter-c",
            proxiedNode = b,
        )
        val seamA = requireNotNull(mesh.voterSeams).getValue(a)
        val seamB = requireNotNull(mesh.voterSeams).getValue(b)

        // A leader is elected and a first command commits across the freshly-formed mesh.
        proposeAndAwaitAllCommit(mesh, "before-heal".encodeToByteArray())

        // Half-open the a↔b link: forwarding stops, both sockets stay ESTABLISHED (no FIN/RST).
        requireNotNull(proxy).sever()
        // Ping/pong reaps the dead link on BOTH ends within the window.
        withTimeout(window) { seamA.peers.first { PeerId(b.value) !in it } }
        withTimeout(window) { seamB.peers.first { PeerId(a.value) !in it } }

        // The "network" recovers; the supervisor's redial must reconnect the edge on both ends.
        requireNotNull(proxy).restore()
        withTimeout(window) { seamA.peers.first { PeerId(b.value) in it } }
        withTimeout(window) { seamB.peers.first { PeerId(a.value) in it } }

        // A command proposed AFTER the heal commits on all three voters — across the healed edge.
        proposeAndAwaitAllCommit(mesh, "after-heal".encodeToByteArray())
    }

    @Test
    fun m2ClusterSurvivesATransientBlip() = runMeshTest {
        assumeRealSocketReconnectionEnabled()
        val (a, b) = NodeId("voter-a") to NodeId("voter-b")
        // The single a↔b edge is the whole cluster: dropped ⇒ no quorum, so a post-heal commit
        // directly proves the edge re-linked.
        val mesh = formMesh(
            voterA = a to "/voter-a",
            voterB = b to "/voter-b",
            voterC = null,
            proxiedNode = b,
        )
        val seamA = requireNotNull(mesh.voterSeams).getValue(a)
        val seamB = requireNotNull(mesh.voterSeams).getValue(b)

        proposeAndAwaitAllCommit(mesh, "m2-before".encodeToByteArray())

        requireNotNull(proxy).sever()
        withTimeout(window) { seamA.peers.first { PeerId(b.value) !in it } }
        withTimeout(window) { seamB.peers.first { PeerId(a.value) !in it } }

        requireNotNull(proxy).restore()
        withTimeout(window) { seamA.peers.first { PeerId(b.value) in it } }
        withTimeout(window) { seamB.peers.first { PeerId(a.value) in it } }

        proposeAndAwaitAllCommit(mesh, "m2-after".encodeToByteArray())
    }

    @Test
    fun closeClosesOwnedSeamsAndStopsRedial() = runMeshTest {
        assumeRealSocketReconnectionEnabled()
        val (a, b) = NodeId("voter-a") to NodeId("voter-b")
        val mesh = formMesh(
            voterA = a to "/voter-a",
            voterB = b to "/voter-b",
            voterC = null,
            proxiedNode = b,
        )
        val seamA = requireNotNull(mesh.voterSeams).getValue(a)
        withTimeout(window) { seamA.peers.first { PeerId(b.value) in it } }

        // Close the mesh. Because voterMeshOverWebSockets OWNS its per-voter hubMesh seams, close() must
        // BOTH cancel the mesh scope (redial supervisors + accept-pumps + nodes) AND gracefully close each
        // owned seam. Without the seam close the inter-server WebSocket session would linger ESTABLISHED,
        // still answering pings, and peer-b would hold voter-a in its roster as a zombie forever.
        mesh.close()

        // (a) close() CLOSED the owned seam. A hubMesh latches SeamState.Torn on close (tearDown), so
        // reaching Torn is the observable that the seam is no longer serving — its live WebSocket session
        // has been torn down. On the pre-fix behaviour close() only cancelled the mesh scope and left the
        // seam Woven with its session alive; this assertion is exactly what the fix makes pass.
        withTimeout(window) { seamA.state.first { it is SeamState.Torn } }

        // (b) the redial supervisor is DEAD. Drop the edge then RESTORE the path — the only thing that
        // could re-add the peer is a live supervisor, and close() cancelled it (and the seam is now
        // closed besides), so PeerId(b) must never re-enter seamA's roster. delay past many backoff
        // cycles to give any surviving supervisor ample opportunity, then assert it never redialed.
        requireNotNull(proxy).sever()
        requireNotNull(proxy).restore()
        delay(3.seconds)
        assertFalse(
            PeerId(b.value) in seamA.peers.value,
            "after close() no supervisor should re-dial the dropped peer even once the path recovers",
        )
    }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Run [body] under a real [runBlocking] dispatcher, then tear every resource down **inside the same
     * coroutine** so `cancelChildren()` reaps the mesh scope and any leaked CIO session coroutines that
     * attached to this coroutine — the only place that reclaims them.
     */
    private fun runMeshTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        try {
            body()
        } finally {
            voterMesh?.let { mesh ->
                // close() now cancels the mesh scope AND gracefully closes the owned seams (their live
                // WebSocket sessions), so this is the primary teardown. The explicit per-seam close below
                // is a defensive, idempotent backstop (Seam.close is idempotent) — belt-and-braces against
                // runBlocking parking on a leaked CIO session if any seam slipped close().
                withTimeout(window) { mesh.close() }
                // ALLOW-runCatching: the catch IS the mechanism — this backstop must tolerate its own withTimeout's TimeoutCancellationException so the teardown below still runs. runCatchingCancellable would rethrow it, skip httpClient.close(), and leave a CIO session parking runBlocking forever.
                runCatching { withTimeout(window) { mesh.voterSeams?.values?.forEach { it.close() } } }
            }
            hostScope?.cancel()
            httpClient?.close()
            proxy?.close()
            server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
            this.coroutineContext.cancelChildren()
        }
    }

    /**
     * Mount each voter's [KtorConnectionSource] route on one shared Netty server and form the mesh via
     * [voterMeshOverWebSockets]. [proxiedNode]'s dial URL points at a [SeverableTcpProxy] fronting the
     * server; every other voter dials direct. Runs in the caller's [runBlocking] coroutine (so the
     * mesh scope is a child of it, and `cancelChildren()` in [runMeshTest] reaps it).
     */
    private suspend fun formMesh(
        voterA: Pair<NodeId, String>,
        voterB: Pair<NodeId, String>,
        voterC: Pair<NodeId, String>?,
        proxiedNode: NodeId,
    ): VoterMesh {
        val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val specs = listOfNotNull(voterA, voterB, voterC)
        // Bind 0 and read the port back from the *live* connector. Probing a free port with a
        // throwaway `ServerSocket(0).use { it.localPort }` and re-binding the number is a TOCTOU:
        // the probe closes before Netty binds, so another process on a loaded box can take the port
        // in that window (`BindException: Address already in use` — #1590). Binding 0 has no window.
        // (SeverableTcpProxy below keeps its ServerSocket open for its whole life, so it is not the
        // same pattern — the port it reports is a port it still holds.)
        lateinit var sources: Map<NodeId, KtorConnectionSource>
        val srv = embeddedServer(Netty, port = 0) {
            sources = mountSources(this, specs)
        }.also { server = it }
        srv.start(wait = false)
        val port = srv.engine.resolvedConnectors().first().port

        val proxyPort = SeverableTcpProxy(targetHost = "localhost", targetPort = port)
            .also { proxy = it }
            .port

        val client = HttpClient(CIO) { install(ClientWebSockets) { pingInterval = pingPeriod } }
            .also { httpClient = it }

        val voters = specs.map { (nodeId, path) ->
            val hostPort = if (nodeId == proxiedNode) proxyPort else port
            WebSocketVoter(nodeId, sources.getValue(nodeId), "ws://localhost:$hostPort$path")
        }

        // The mesh lives on a dedicated scope (the extension receiver): voterMeshOverWebSockets derives
        // its lifecycle scope from the receiver's context, so cancelling this (or VoterMesh.close())
        // stops the pumps/supervisors/nodes. Detached from runBlocking (own root Job) so runBlocking
        // does not wait on the long-lived mesh; teardown cancels it explicitly.
        val scope = CoroutineScope(dispatcher + Job()).also { hostScope = it }
        return scope.voterMeshOverWebSockets(
            voters = voters,
            httpClient = client,
            dispatcher = dispatcher,
            raftConfig = raftCfg,
            random = Random(1450),
            formationTimeout = window,
        ).also { voterMesh = it }
    }

    /** Mount one ping-configured [KtorConnectionSource] per voter on [application]. */
    private fun mountSources(
        application: Application,
        specs: List<Pair<NodeId, String>>,
    ): Map<NodeId, KtorConnectionSource> =
        specs.associate { (nodeId, path) ->
            nodeId to KtorConnectionSource(application, path, pingPeriod = pingPeriod)
        }

    /**
     * Propose [command] on the current leader and assert every voter commits it, all bounded. Each
     * command is distinct, so the assertion reads the **replaying** [RaftNode.committedFrom] stream —
     * robust to subscribing after the entry already committed (the live [RaftNode.committed] is
     * replay=0 and would race a fast commit).
     */
    private suspend fun proposeAndAwaitAllCommit(mesh: VoterMesh, command: ByteArray) {
        val leader = withTimeout(window) { mesh.awaitLeader() }
        withTimeout(window) { leader.propose(command) }
        mesh.voterNodes.forEach { (nodeId, node) ->
            val entry = withTimeout(window) {
                node.committedFrom(1).first { it is Committed.Entry && it.entry.command.contentEquals(command) }
            } as Committed.Entry
            assertContentEquals(command, entry.entry.command, "voter $nodeId must commit across the mesh")
        }
    }
}

/**
 * A loopback TCP relay that forwards bytes between a downstream (client) socket and a fresh upstream
 * (server) socket, and can [sever] the flow to simulate a **half-open** link — after [sever] no bytes
 * cross in either direction, but neither socket is closed, so no FIN/RST is ever emitted and only the
 * application-level ping/pong can detect the death. [restore] resumes forwarding so a redial through a
 * fresh connection reconnects. Backed by blocking Java sockets on daemon threads (a real-IO test).
 */
private class SeverableTcpProxy(
    private val targetHost: String,
    private val targetPort: Int,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort

    @Volatile
    private var severed = false
    private val openSockets = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true, name = "severable-proxy-accept") {
            // ALLOW-runCatching: raw daemon Thread body, not a coroutine — the accept loop dies with a SocketException on close() and there is no cancellation to swallow.
            runCatching {
                while (!serverSocket.isClosed) {
                    val downstream = serverSocket.accept()
                    val upstream = Socket(targetHost, targetPort)
                    openSockets += downstream
                    openSockets += upstream
                    pump(downstream, upstream)
                    pump(upstream, downstream)
                }
            }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        thread(isDaemon = true, name = "severable-proxy-pump") {
            // ALLOW-runCatching: raw daemon Thread body, not a coroutine — the blocking read/write pump dies with an IOException on close() and there is no cancellation to swallow.
            runCatching {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    // Drop bytes while severed — read-and-discard keeps TCP looking alive (ACKs flow)
                    // and never closes the socket (true half-open). A fresh redial connection opened
                    // after restore() forwards normally.
                    if (severed) continue
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
        }
    }

    /** Simulate a half-open link: stop forwarding both directions, leaving both sockets ESTABLISHED. */
    fun sever() {
        severed = true
    }

    /** Resume forwarding — a redial through a fresh connection reconnects. */
    fun restore() {
        severed = false
    }

    override fun close() {
        // ALLOW-runCatching: non-suspend AutoCloseable.close() over a blocking java.net socket — no coroutine context, and this close must not stop the ones below.
        runCatching { serverSocket.close() }
        // ALLOW-runCatching: as above — non-suspend socket close, best-effort per socket.
        openSockets.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}
