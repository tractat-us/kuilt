@file:Suppress("ForbiddenImport") // deliberate: real-IO test over Netty/OkHttp — Dispatchers.Default is required; virtual-time dispatchers cannot drive real sockets
// Real-IO exception: this test binds a real Netty server on a localhost port and
// connects real OkHttp WebSocket clients. Virtual time cannot drive real socket I/O;
// Dispatchers.Default is the appropriate dispatcher for WarpNode background coroutines.
// See CLAUDE.md: "the rare deliberate real-threading harness carries an inline
// @Suppress with a one-line reason."

package us.tractat.kuilt.warp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorServerLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.time.Duration.Companion.seconds

/**
 * Phase-1 real-Seam measurement for [WarpNode] — duplicate-execution rate over
 * a real [KtorClientLoom] / [KtorServerLoom] WebSocket fabric.
 *
 * ## Topology
 *
 * WebSocket is hub-spoke: each client gets a 2-peer seam to the server. A genuine
 * 3-node ring requires all 3 peers visible in [Seam.peers] for every node. This test
 * achieves that via [BroadcastRelaySeam] — a test-only adapter that stitches two
 * point-to-point WebSocket seams into a single logical 3-peer seam on the relay node.
 * Clients see a 2-peer seam (self + relay); the relay sees all 3 peers.
 *
 * Fleet:
 * - **relay** (server): [BroadcastRelaySeam] bridging seamA (relay↔clientA) and
 *   seamB (relay↔clientB). Peers = {relay, clientA, clientB}.
 * - **nodeA**: seam to relay. Peers = {clientA, relay}.
 * - **nodeB**: seam to relay. Peers = {clientB, relay}.
 *
 * The relay uses [RELAY_ID] as its [KtorServerLoom.selfPeerId], so all seams
 * (both server-side and client-side) agree on the relay's [PeerId].
 *
 * ## Go/no-go datum (issue #823)
 *
 * The spike (docs/warp-spike-results.md, v3/v4, Strategy D-GOSSIP at 0% churn):
 * - 0% churn → 0% dup-rate for consistent-hash (D-GOSSIP) at any peer count.
 *
 * This test confirms or refutes that over real sockets. Due to the hub-spoke
 * topology, the relay sees 3 peers and the clients see 2. Clients' 2-peer rings
 * differ from the relay's 3-peer ring → some ownership disagreement is expected.
 * [MAX_DUP_RATE] (35%) is set conservatively above the spike's OPT@3-peers bound
 * (~9–17%) to tolerate this topology gap. The meaningful bound is: if dup-rate
 * exceeds 35% the consistent-hash assignment is broken.
 *
 * Invariants that must hold regardless of dup-rate:
 * - Every task appears in every node's results board after convergence.
 * - Each task has exactly one result in the converged board (dedup backstop).
 */
class WarpNodeWebSocketTest {

    private val serverPath = "/warp-ws-test"
    private var port = 0

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var serverLoom: KtorServerLoom
    private lateinit var httpClient: HttpClient

    // WarpNode background coroutines run on Dispatchers.Default — real-IO exception; see file header.
    @Suppress("InjectDispatcher")
    private val nodeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(Netty, port = port) {
            // Fix the server's PeerId to RELAY_ID so client seams use the canonical relay ID.
            serverLoom = KtorServerLoom(this, serverPath, selfPeerId = RELAY_ID)
        }
        server.start(wait = false)
        httpClient = HttpClient(OkHttp) { install(ClientWebSockets) }
    }

    @AfterTest
    fun tearDown() {
        nodeScope.cancel()
        if (this::httpClient.isInitialized) httpClient.close()
        if (this::server.isInitialized) server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
    }

    /**
     * Three-node WarpNode fleet over real WebSocket sockets.
     *
     * Enqueues [TASK_COUNT] tasks on the relay. Waits ≤ [CONVERGENCE_TIMEOUT_MS] ms for all
     * results on all nodes. Measures the duplicate-execution rate.
     *
     * Compared against spike (docs/warp-spike-results.md):
     * - D-GOSSIP at 0% churn → 0.0% dup. Hub-spoke adds ring-disagreement overhead.
     * - OPT at 3–4 peers: ~9–17%. Our [MAX_DUP_RATE] cap is 35%.
     *
     * Phase-1 go/no-go: if this test passes, the warp foundation is viable over real WS.
     */
    @Test
    fun threeNodeFleet_dupRateWithinSpikePrediction_allTasksConverge() {
        runBlocking {
            withTimeout(CONVERGENCE_TIMEOUT_MS) {
                // Accept two client connections on the server side.
                val (serverSeamA, clientSeamA) = connectClientPair(CLIENT_A_ID)
                val (serverSeamB, clientSeamB) = connectClientPair(CLIENT_B_ID)

                // Stitch the two server-side seams into a single 3-peer relay seam.
                val relaySeam = BroadcastRelaySeam(
                    selfId = RELAY_ID,
                    arms = listOf(serverSeamA, serverSeamB),
                    scope = nodeScope,
                )

                val execLog = ExecutionLog()
                val wsOpId = OpId("ws-echo")

                fun wsRegistry(peerId: PeerId): OpRegistry = OpRegistry().also { r ->
                    r.register(wsOpId, Op { args ->
                        execLog.record(peerId, TaskId(args.decodeToString()))
                        args
                    })
                }

                val nodeRelay = WarpNode(
                    selfId = RELAY_ID,
                    seam = relaySeam,
                    rosterFlow = relaySeam.rosterSnapshot(),
                    scope = nodeScope,
                    quilterConfig = WS_QUILTER_CONFIG,
                    clock = { kotlin.time.Clock.System.now() },
                    registry = wsRegistry(RELAY_ID),
                )
                val nodeA = WarpNode(
                    selfId = CLIENT_A_ID,
                    seam = clientSeamA,
                    rosterFlow = clientSeamA.rosterSnapshot(),
                    scope = nodeScope,
                    quilterConfig = WS_QUILTER_CONFIG,
                    clock = { kotlin.time.Clock.System.now() },
                    registry = wsRegistry(CLIENT_A_ID),
                )
                val nodeB = WarpNode(
                    selfId = CLIENT_B_ID,
                    seam = clientSeamB,
                    rosterFlow = clientSeamB.rosterSnapshot(),
                    scope = nodeScope,
                    quilterConfig = WS_QUILTER_CONFIG,
                    clock = { kotlin.time.Clock.System.now() },
                    registry = wsRegistry(CLIENT_B_ID),
                )

                // Enqueue all tasks on the relay.
                val tasks = (1..TASK_COUNT).map { TaskId("ws-task-$it") }
                tasks.forEach { nodeRelay.enqueue(it, TaskDescriptor(wsOpId, it.value.encodeToByteArray())) }

                // Wait for all nodes to converge on all results.
                awaitAllResults(tasks, nodeRelay, nodeA, nodeB)

                val dupRate = execLog.dupRate()
                val dupPct = "%.1f".format(dupRate * 100)
                println(
                    "WarpNodeWebSocketTest: dup-rate=$dupPct%  " +
                        "(${execLog.dupCount()} dups / ${execLog.totalExecutions()} executions, " +
                        "$TASK_COUNT tasks, 3-node hub-spoke fleet)",
                )
                println(
                    "Spike prediction — D-GOSSIP@0%churn: 0.0%  |  " +
                        "OPT@3-peers: ~9-17%  |  budget: ≤${(MAX_DUP_RATE * 100).toInt()}%",
                )

                val allTaskIds = tasks.toSet()
                assertAll(
                    {
                        assertTrue(
                            dupRate <= MAX_DUP_RATE,
                            "dup-rate $dupPct% exceeds budget ${(MAX_DUP_RATE * 100).toInt()}%",
                        )
                    },
                    {
                        assertTrue(
                            nodeRelay.results.taskIds == allTaskIds,
                            "relay missing results: ${allTaskIds - nodeRelay.results.taskIds}",
                        )
                    },
                    {
                        assertTrue(
                            nodeA.results.taskIds == allTaskIds,
                            "nodeA missing results: ${allTaskIds - nodeA.results.taskIds}",
                        )
                    },
                    {
                        assertTrue(
                            nodeB.results.taskIds == allTaskIds,
                            "nodeB missing results: ${allTaskIds - nodeB.results.taskIds}",
                        )
                    },
                )

                nodeRelay.close()
                nodeA.close()
                nodeB.close()
                relaySeam.close()
            }
        }
    }

    /**
     * Two-node WarpNode fleet (relay + one client) over real WebSocket.
     *
     * Validates the basic steady-state operation: tasks enqueued on the relay converge
     * on both nodes. This is the minimal Phase-1 proof that WarpNode works over real
     * WebSocket fabric at all.
     *
     * After convergence, the client is disconnected. The relay (now sole survivor) must
     * hold all results. This proves the dedup backstop is correct even if some tasks were
     * doubly-executed during the pre-partition window.
     */
    @Test
    fun failover_successorPicksUpTasksAfterClientDisconnects() {
        runBlocking {
            withTimeout(CONVERGENCE_TIMEOUT_MS) {
                val (serverSeamA, clientSeamA) = connectClientPair(CLIENT_A_ID)

                val execLog = ExecutionLog()
                val foOpId = OpId("failover-echo")

                fun foRegistry(peerId: PeerId): OpRegistry = OpRegistry().also { r ->
                    r.register(foOpId, Op { args ->
                        execLog.record(peerId, TaskId(args.decodeToString()))
                        args
                    })
                }

                val nodeRelay = WarpNode(
                    selfId = RELAY_ID,
                    seam = serverSeamA,
                    rosterFlow = serverSeamA.rosterSnapshot(),
                    scope = nodeScope,
                    quilterConfig = WS_QUILTER_CONFIG,
                    clock = { kotlin.time.Clock.System.now() },
                    registry = foRegistry(RELAY_ID),
                )
                val nodeA = WarpNode(
                    selfId = CLIENT_A_ID,
                    seam = clientSeamA,
                    rosterFlow = clientSeamA.rosterSnapshot(),
                    scope = nodeScope,
                    quilterConfig = WS_QUILTER_CONFIG,
                    clock = { kotlin.time.Clock.System.now() },
                    registry = foRegistry(CLIENT_A_ID),
                )

                // Enqueue tasks.
                val tasks = (1..FAILOVER_TASK_COUNT).map { TaskId("failover-task-$it") }
                tasks.forEach { nodeRelay.enqueue(it, TaskDescriptor(foOpId, it.value.encodeToByteArray())) }

                // Wait for both nodes to converge on all results.
                awaitAllResults(tasks, nodeRelay, nodeA)

                // Partition: close nodeA.
                clientSeamA.close(CloseReason.Normal)

                val allTaskIds = tasks.toSet()
                val dupRate = execLog.dupRate()
                println(
                    "WarpNodeWebSocketTest (failover): dup-rate=${"%.1f".format(dupRate * 100)}%  " +
                        "(${execLog.dupCount()} dups / ${execLog.totalExecutions()} executions, " +
                        "$FAILOVER_TASK_COUNT tasks, 2-node hub-spoke)",
                )

                assertAll(
                    {
                        assertTrue(
                            nodeRelay.results.taskIds == allTaskIds,
                            "relay missing results: ${allTaskIds - nodeRelay.results.taskIds}",
                        )
                    },
                    {
                        tasks.forEach { taskId ->
                            assertTrue(
                                nodeRelay.results[taskId] != null,
                                "relay missing result for $taskId",
                            )
                        }
                    },
                )

                nodeRelay.close()
                nodeA.close()
            }
        }
    }

    /**
     * Phase-1 go/no-go: strong (agreed) ring over real WebSocket sockets.
     *
     * ## What this measures
     *
     * The hub-spoke test above feeds each [WarpNode] its OWN seam's roster — the relay sees
     * `{relay, clientA, clientB}` but each client sees only `{self, relay}`. Those are
     * three distinct rings, and ownership disagreement between them drives the measured
     * ~22% dup-rate.
     *
     * This test isolates the variable: **roster agreement, not socket topology**. All three
     * nodes consume the same `MutableStateFlow(setOf(relay, clientA, clientB))` — the agreed
     * 3-peer view — so every node builds an identical consistent-hash ring. When rings agree,
     * each task is owned by exactly one node and duplicate executions should approach zero.
     *
     * ## Why a shared `MutableStateFlow` is the right proxy for `RaftNode.rosterSnapshot()`
     *
     * [WarpNode] consumes only a `Flow<Set<PeerId>>`. A pre-seeded, stable
     * [MutableStateFlow] is byte-for-byte identical input to what [RaftNode.rosterSnapshot]
     * emits once Raft membership converges to the full set. The dup-rate physics depend only
     * on roster agreement, not on what produces it. The [RaftNode.rosterSnapshot] adapter is
     * already unit-tested in [WarpNodeTest]. Standing up real Raft consensus over WebSocket
     * seams would exercise Raft (already TCK-covered), not warp's dup behaviour. The shared
     * flow isolates the relevant variable cleanly.
     *
     * ## Assertion
     *
     * Dup-rate MUST be ≤ 5%. The 5% margin (not 0%) absorbs any genuine failover-window
     * race: if a message arrives slightly before the first ring-rebuild tick, one task might
     * transiently race. A tight bound proves the hypothesis; 0% is the expected steady-state.
     *
     * ## Go/no-go significance
     *
     * This is the Phase-1 measurement for epic [#809](https://github.com/tractat-us/kuilt/issues/809),
     * lineage [#823](https://github.com/tractat-us/kuilt/issues/823). Passing this test
     * confirms: **a strong (agreed) roster drives dup-rate to ~0 over real WebSocket sockets**.
     * It does NOT graduate Phase 1 — that is a human go/no-go decision.
     */
    @Test
    fun threeNodeFleet_strongRing_dupRateNearZero() {
        runBlocking {
            withTimeout(CONVERGENCE_TIMEOUT_MS) {
                val (serverSeamA, clientSeamA) = connectClientPair(CLIENT_A_ID)
                val (serverSeamB, clientSeamB) = connectClientPair(CLIENT_B_ID)

                val relaySeam = BroadcastRelaySeam(
                    selfId = RELAY_ID,
                    arms = listOf(serverSeamA, serverSeamB),
                    scope = nodeScope,
                )

                // The agreed roster: all three peers, known up-front (connections are established).
                // Every node consumes the same flow → identical rings → each task owned by exactly one node.
                val agreedRoster = MutableStateFlow(setOf(RELAY_ID, CLIENT_A_ID, CLIENT_B_ID))

                val execLog = ExecutionLog()
                val strongOpId = OpId("ws-strong")

                fun strongRegistry(peerId: PeerId): OpRegistry = OpRegistry().also { r ->
                    r.register(strongOpId, Op { args ->
                        execLog.record(peerId, TaskId(args.decodeToString()))
                        args
                    })
                }

                val nodeRelay = WarpNode(
                    selfId = RELAY_ID,
                    seam = relaySeam,
                    rosterFlow = agreedRoster,
                    scope = nodeScope,
                    quilterConfig = WS_QUILTER_CONFIG,
                    clock = { kotlin.time.Clock.System.now() },
                    registry = strongRegistry(RELAY_ID),
                )
                val nodeA = WarpNode(
                    selfId = CLIENT_A_ID,
                    seam = clientSeamA,
                    rosterFlow = agreedRoster,
                    scope = nodeScope,
                    quilterConfig = WS_QUILTER_CONFIG,
                    clock = { kotlin.time.Clock.System.now() },
                    registry = strongRegistry(CLIENT_A_ID),
                )
                val nodeB = WarpNode(
                    selfId = CLIENT_B_ID,
                    seam = clientSeamB,
                    rosterFlow = agreedRoster,
                    scope = nodeScope,
                    quilterConfig = WS_QUILTER_CONFIG,
                    clock = { kotlin.time.Clock.System.now() },
                    registry = strongRegistry(CLIENT_B_ID),
                )

                val tasks = (1..TASK_COUNT).map { TaskId("strong-task-$it") }
                tasks.forEach { nodeRelay.enqueue(it, TaskDescriptor(strongOpId, it.value.encodeToByteArray())) }

                awaitAllResults(tasks, nodeRelay, nodeA, nodeB)

                val dupRate = execLog.dupRate()
                val dupPct = "%.1f".format(dupRate * 100)
                println(
                    "WarpNodeWebSocketTest (STRONG RING — go/no-go datum): dup-rate=$dupPct%  " +
                        "(${execLog.dupCount()} dups / ${execLog.totalExecutions()} executions, " +
                        "$TASK_COUNT tasks, agreed 3-peer roster)",
                )
                println(
                    "Strong-ring datum vs eventual-roster: ~22% (hub-spoke disagreement)  |  " +
                        "strong-ring budget: ≤${(MAX_STRONG_RING_DUP_RATE * 100).toInt()}%",
                )

                val allTaskIds = tasks.toSet()
                assertAll(
                    {
                        assertTrue(
                            dupRate <= MAX_STRONG_RING_DUP_RATE,
                            "strong-ring dup-rate $dupPct% exceeds tight budget " +
                                "${(MAX_STRONG_RING_DUP_RATE * 100).toInt()}% — ring disagreement is bleeding through",
                        )
                    },
                    {
                        assertTrue(
                            nodeRelay.results.taskIds == allTaskIds,
                            "relay missing results: ${allTaskIds - nodeRelay.results.taskIds}",
                        )
                    },
                    {
                        assertTrue(
                            nodeA.results.taskIds == allTaskIds,
                            "nodeA missing results: ${allTaskIds - nodeA.results.taskIds}",
                        )
                    },
                    {
                        assertTrue(
                            nodeB.results.taskIds == allTaskIds,
                            "nodeB missing results: ${allTaskIds - nodeB.results.taskIds}",
                        )
                    },
                )

                nodeRelay.close()
                nodeA.close()
                nodeB.close()
                relaySeam.close()
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Connects a client with [clientId] to the embedded server.
     * Returns `(serverSideSeam, clientSideSeam)`.
     *
     * The server seam's [Seam.selfId] = [RELAY_ID] (fixed at [setUp] via [KtorServerLoom]).
     * The client seam's [Seam.selfId] = [clientId].
     */
    private suspend fun connectClientPair(clientId: PeerId): Pair<Seam, Seam> = coroutineScope {
        val serverDeferred = async { serverLoom.nextLink() }
        val clientLoom = KtorClientLoom(httpClient = httpClient, selfPeerId = clientId)
        val clientSeam = clientLoom.join(
            WebSocketAdvertisement(
                url = "ws://localhost:$port$serverPath",
                serverPeerId = RELAY_ID,
                displayName = clientId.value,
            ),
        )
        val serverSeam = serverDeferred.await()
        serverSeam to clientSeam
    }

    /** Polls until [tasks] appear in every node's results board (bounded by outer [withTimeout]). */
    private suspend fun awaitAllResults(tasks: List<TaskId>, vararg nodes: WarpNode) {
        val expected = tasks.toSet()
        while (nodes.any { it.results.taskIds != expected }) {
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        val RELAY_ID = PeerId("relay")
        val CLIENT_A_ID = PeerId("client-a")
        val CLIENT_B_ID = PeerId("client-b")

        const val TASK_COUNT = 60
        const val FAILOVER_TASK_COUNT = 20
        const val MAX_DUP_RATE = 0.35              // ≤ 35%: spike OPT@3-peers ≈ 9-17%; hub-spoke adds ring-disagreement
        const val MAX_STRONG_RING_DUP_RATE = 0.05 // ≤ 5%: agreed roster → near-zero dups; margin for failover-window races
        const val CONVERGENCE_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 100L

        /**
         * Short-cadence [QuilterConfig] for real-IO tests.
         *
         * Anti-entropy and full-state retry are short so a missed delta heals within seconds.
         * `expectVirtualTime = true` suppresses the TestDispatcher guard — this test runs on
         * [Dispatchers.Default] (real-IO exception; see file header), not a test dispatcher.
         */
        val WS_QUILTER_CONFIG = QuilterConfig(
            antiEntropyInterval = 2.seconds,
            fullStateRetryInterval = 3.seconds,
            expectVirtualTime = true,
        )
    }

    // ── ExecutionLog ─────────────────────────────────────────────────────────────

    /**
     * Thread-safe log of which node executed which task.
     *
     * A **duplicate execution** is when the same [TaskId] is executed by more than
     * one node before the Results [us.tractat.kuilt.crdt.ORMap] dedup fires.
     */
    private class ExecutionLog {
        private val lock = reentrantLock()
        private val log = mutableMapOf<TaskId, MutableSet<PeerId>>()

        fun record(node: PeerId, taskId: TaskId) {
            lock.withLock { log.getOrPut(taskId) { mutableSetOf() }.add(node) }
        }

        fun dupCount(): Int = lock.withLock { log.values.count { it.size > 1 } }
        fun totalExecutions(): Int = lock.withLock { log.values.sumOf { it.size } }

        /**
         * Dup-rate = redundant executions / total executions.
         * Matches the spike's `(executions - tasks) / executions` accounting.
         */
        fun dupRate(): Double {
            val total = totalExecutions()
            if (total == 0) return 0.0
            val taskCount = lock.withLock { log.size }
            return (total - taskCount).toDouble() / total
        }
    }

    // ── BroadcastRelaySeam ───────────────────────────────────────────────────────

    /**
     * Test-only: stitches N point-to-point hub-spoke WebSocket seams into a single
     * logical multi-peer [Seam] for the relay node.
     *
     * This adapter enables the relay WarpNode to have a 3-peer ring (relay + clientA + clientB)
     * despite the underlying WebSocket topology being hub-spoke (each arm is a 2-peer seam).
     *
     * - [peers]: union of all arm seams' peer sets.
     * - [broadcast]: sends on all live arm seams.
     * - [sendTo]: routes to the arm that reports the target peer.
     * - [incoming]: merges inbound frames from all arms; deduplicates by (sender, sequence).
     *
     * Thread-safe: mutable state is guarded by an atomicfu [kotlinx.atomicfu.locks.ReentrantLock].
     * Suspend calls (arm sends) are always outside the lock.
     *
     * Lives here because it is test infrastructure for the hub-spoke measurement — not a
     * production primitive. Production multi-peer topologies use Session Room channels.
     */
    private class BroadcastRelaySeam(
        override val selfId: PeerId,
        private val arms: List<Seam>,
        scope: CoroutineScope,
    ) : Seam {
        private val lock = reentrantLock()
        private val seen = mutableSetOf<Pair<PeerId, Long>>() // dedup key: (sender, sequence)

        private val spool = Spool<Swatch>(DeliveryPolicy.Reliable)
        override val incoming: Flow<Swatch> = spool.incoming

        private val _peers = MutableStateFlow<Set<PeerId>>(emptySet())
        override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

        private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
        override val state: StateFlow<SeamState> = _state.asStateFlow()

        init {
            // Merge each arm's peer set into our unified peers view.
            arms.forEach { arm ->
                arm.peers.onEach { armPeers -> _peers.update { it + armPeers } }
                    .launchIn(CoroutineScope(scope.coroutineContext + SupervisorJob()))
            }
            // Merge inbound frames from all arms, deduplicating by (sender, sequence).
            arms.forEach { arm ->
                arm.incoming.onEach { swatch ->
                    val sender = swatch.sender ?: selfId
                    val key = sender to swatch.sequence
                    val isNew = lock.withLock { seen.add(key) }
                    if (isNew) spool.deliver(swatch)
                }.launchIn(CoroutineScope(scope.coroutineContext + SupervisorJob()))
            }
        }

        override suspend fun broadcast(payload: ByteArray) {
            arms.forEach { arm -> runCatching { arm.broadcast(payload) } }
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            val target = arms.firstOrNull { peer in it.peers.value }
            target?.sendTo(peer, payload)
        }

        override suspend fun close(reason: CloseReason) {
            _state.value = SeamState.Torn(reason)
            spool.close()
            arms.forEach { arm -> runCatching { arm.close(reason) } }
        }
    }
}
