@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.fabric.Star
import us.tractat.kuilt.test.fabric.connectionPair
import us.tractat.kuilt.test.fabric.inMemoryStarOf
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Gating integration test for the hosted-game hub: prove **prompt, in-order** RGA chat
 * forward-flow under [gameHost] over a star-shaped `meshSeam`, and that the cluster **survives**
 * a client dropping off the fabric (no crash, survivors keep replicating).
 *
 * These are the first tests to drive [gameHost]/[gameJoin] over a star where the hub is the only
 * relay (FullFanout `GossipSeam`). Both exercises hang off one shared bootstrap, [setupStarGame].
 */
class HostedHubReplicationTest {

    @Test
    fun clientChatRgaReachesAllOtherClientsPromptly() = runTest(StandardTestDispatcher(), timeout = 30.seconds) {
        val game = setupStarGame(n = 3)

        // Client 0 appends m0, m1, m2 at the tail so order is preserved.
        repeat(3) { k -> game.clientChats[0].appendChat("m$k") }
        advanceTimeBy(2000)
        runCurrent()

        val expected = listOf("m0", "m1", "m2")
        assertEquals(expected, game.hostChat.state.value.toList(), "host did not converge")
        assertEquals(expected, game.clientChats[1].state.value.toList(), "client 1 did not converge")
        assertEquals(expected, game.clientChats[2].state.value.toList(), "client 2 did not converge")
    }

    @Test
    fun droppedClientSurvivesAndClusterKeepsReplicating() = runTest(StandardTestDispatcher(), timeout = 30.seconds) {
        val game = setupStarGame(n = 3)

        // 1. Establish history; all converge.
        game.clientChats[0].appendChat("before")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf("before"), game.clientChats[1].state.value.toList())

        // 2. Drop client 1 off the fabric. With the SeamRaftTransport fix, the cluster must NOT crash.
        game.dropClient(1)
        advanceTimeBy(1000)
        runCurrent()

        // 3. SURVIVAL: host-side activity while client 1 is away still replicates to the survivors.
        game.clientChats[0].appendChat("during")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(
            listOf("before", "during"),
            game.hostChat.state.value.toList(),
            "host kept progressing after a client dropped",
        )
        assertEquals(
            listOf("before", "during"),
            game.clientChats[2].state.value.toList(),
            "surviving client converged after a peer dropped",
        )
    }

    /**
     * RECONNECT: a dropped voter's seat is freed by host liveness-eviction, so a fresh-identity
     * reconnect is admitted and converges to the full chat history.
     *
     * The hub hosts with a [fastLivenessConfig] and a virtual-time clock, so the leader runs a
     * [us.tractat.kuilt.liveness.HeartbeatPartitionDetector] per voter. When `client-1` drops it
     * stops answering heartbeat pings; after [HeartbeatConfig.reconnectWindow] the leader observes
     * `PeerLost`, evicts the dead voter (roster 4 → 3) and **re-opens admission**. A reconnecting
     * peer with a fresh identity (`client-1-recon`) is then admitted and obtains a [GameSession].
     *
     * **Healing path — anti-entropy, not prompt FullState (a known limitation).** The design's
     * intended gap-healer is Quilter's first-contact FullState (`sendFullStateTo`). It does NOT
     * deliver here: the hub fires that FullState during `gameJoin`, before the reconnecting chat
     * Quilter's collector has subscribed, and `MuxSeam` is `replay = 0` so the frame is dropped;
     * the deterministic retry is then cancelled because the reconnecting peer's own (empty) FullState
     * reaching the hub triggers `cancelFullStateRetry(sender)` (`Quilter.dispatch`). So the heal
     * actually lands on the hub's **anti-entropy backstop** (`reconcileWithRandomPeer`). At the 1-min
     * production interval that would blow the 5 s bound, so this test tightens `antiEntropyInterval`
     * to 25 ms to let the backstop fire inside the budget. Making this prompt is the deferred
     * escalation (design §Escalations #1 / follow-up); this test verifies *eventual* convergence and
     * the full drop → evict → re-open → rejoin path, not prompt FullState.
     */
    @Test
    fun reconnectingClientConvergesAfterLivenessEviction() = runTest(StandardTestDispatcher(), timeout = 30.seconds) {
        // Virtual-time clock the host's heartbeat detectors read; the test advances `nowMs`
        // alongside advanceTimeBy so silence is measured against the same virtual clock.
        val liveness = fastLivenessConfig()
        var nowMs = 0L
        // The hub pushes the reconnecting peer its first-contact FullState during gameJoin — before
        // the reconnecting chat Quilter is collecting — so that initial frame is dropped (MuxSeam is
        // replay = 0). The hub's anti-entropy backstop re-pushes the full state on its next round, so
        // the heal converges. With production defaults (anti-entropy = 1 min) that would exceed the
        // 5 s virtual-time bound; tightening the interval lets the backstop fire inside the budget.
        val chatConfig = QuilterConfig(
            expectVirtualTime = true,
            antiEntropyInterval = 25.milliseconds,
        )
        val game = setupStarGame(
            n = 3,
            livenessConfig = liveness,
            clock = { Instant.fromEpochMilliseconds(nowMs) },
            chatConfig = chatConfig,
        )

        game.clientChats[0].appendChat("before")
        advanceTimeBy(1000)
        runCurrent()
        nowMs += 1000

        game.dropClient(1)
        nowMs += 500 // dropClient advances 500 ms of virtual time internally.

        game.clientChats[0].appendChat("during")
        advanceTimeBy(1000)
        runCurrent()
        nowMs += 1000

        // Advance past timeout + reconnectWindow (+ a few intervals) so the host's detector for
        // client-1 fires PeerLost, the leader evicts it, and admission re-opens for a replacement.
        val windowMs = liveness.timeout.inWholeMilliseconds +
            liveness.reconnectWindow.inWholeMilliseconds +
            liveness.interval.inWholeMilliseconds * 4
        nowMs += windowMs
        advanceTimeBy(windowMs)
        runCurrent()

        // RECONNECT: client 1 rejoins via a fresh link + gameJoin, then heals to full history once the
        // hub's anti-entropy backstop re-pushes the FullState. Advance in small bounded steps so the
        // backstop rounds fire (never advanceUntilIdle — the heartbeat/election timers re-arm forever).
        val rejoined = game.reconnectClient(1)
        repeat(30) {
            advanceTimeBy(20)
            runCurrent()
            nowMs += 20
        }
        assertEquals(
            listOf("before", "during"),
            rejoined.state.value.toList(),
            "reconnecting client converged to full history after eviction + rejoin (anti-entropy heal)",
        )
    }

    // ── bootstrap ────────────────────────────────────────────────────────────

    /**
     * One holder for a started star + hosted game: the [hub] GossipSeam, its [hubMesh] (so reconnect
     * tests can admit a fresh link), the chat [Quilter]s on the host and each client, and the
     * dispatcher (so reconnect can build a fresh `meshSeam` on the same virtual clock).
     */
    private inner class HostedStarGame(
        val test: TestScope,
        val star: Star,
        val scope: CoroutineScope,
        val dispatcher: CoroutineContext,
        val clientSessions: List<GameSession>,
        val hostChat: Quilter<Rga<String>>,
        val clientChats: List<Quilter<Rga<String>>>,
        val chatConfig: QuilterConfig,
    ) {
        val hub get() = star.hub
        val hubMesh get() = star.hubMesh

        /**
         * Drop client [i] off the fabric exactly as a departing process would: tear down its whole
         * session via [GameSession.close] — the node's election/heartbeat loops stop *before* the
         * seam closes, so the dropped client never fires raft sends onto a closed seam. Closing the
         * client's GossipSeam closes its base mesh and the underlying connection; the hub's read loop
         * for that link then completes and `removePeer`s `client-i` from [hubMesh]'s roster. Asserts
         * the drop took effect so the test is deterministic.
         */
        suspend fun dropClient(i: Int) {
            clientSessions[i].close(CloseReason.Normal)
            test.advanceTimeBy(500)
            test.runCurrent()
            assertTrue(
                PeerId("client-$i") !in star.hub.peers.value,
                "hub still lists client-$i after the drop",
            )
        }

        /**
         * Reconnect the dropped client [i] with a **fresh identity** (`client-$i-recon`): admit a new
         * link to the hub mesh, wrap the new client end in a fresh default-policy [GossipSeam], join
         * the game via [gameJoin], and stand up a fresh chat [Quilter]. The invariant under test is
         * FullState-on-first-contact healing — not raft membership-identity continuity — so the new
         * peer is deliberately a stranger to the cluster. Returns the reconnecting chat Quilter.
         */
        suspend fun reconnectClient(i: Int): Quilter<Rga<String>> {
            val reconId = PeerId("client-$i-recon")
            val (hubEnd, clientEnd) = connectionPair()
            // Admit the link on both ends concurrently — the mesh preambles must cross in parallel.
            val clientMesh = coroutineScope {
                async { star.hubMesh.addLink(hubEnd) }
                async { meshSeam(reconId, listOf(clientEnd), dispatcher, Random(900L + i)) }.await()
            }
            val reconGossip = GossipSeam(
                base = clientMesh,
                random = Random(910L + i),
                clock = { Instant.fromEpochMilliseconds(0) },
            ).also { it.start(scope) }
            val reconSession = scope.gameJoin(reconGossip, raftConfig = fastRaftConfig(seed = 900L + i))
            return chatQuilter(reconSession, scope, chatConfig, Random(920L + i))
        }
    }

    /**
     * Build a started star of [n] clients around one hub, host a game (`peerCount = n + 1`,
     * FullMembership), join every client, and stand up a chat [Quilter] on each session's
     * `chat` app-channel. Reuses the exact concurrent host+joins bootstrap idiom across tests.
     *
     * When [livenessConfig] is supplied (with a virtual-time [clock] driven by the test's
     * scheduler), the host runs per-voter [HeartbeatConfig] liveness monitoring: a dropped voter
     * that stops answering heartbeat pings is evicted and its seat re-opened. The forward-flow and
     * drop-survival tests pass `livenessConfig = null` so they carry no heartbeat traffic; only the
     * reconnect test enables it.
     */
    private suspend fun TestScope.setupStarGame(
        n: Int,
        livenessConfig: HeartbeatConfig? = null,
        clock: () -> Instant = { Instant.fromEpochMilliseconds(0) },
        chatConfig: QuilterConfig = QuilterConfig(expectVirtualTime = true),
    ): HostedStarGame {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val star = backgroundScope.inMemoryStarOf(n = n)
        advanceTimeBy(300)
        runCurrent()

        // Host on the hub seam; each client joins on its own seam. The host's admit loop and the
        // joiners' admission waits must run concurrently, so launch them all before awaiting.
        val hostDeferred = async {
            backgroundScope.gameHost(
                star.hub,
                peerCount = n + 1,
                raftConfig = fastRaftConfig(seed = 1L),
                livenessConfig = livenessConfig,
                clock = clock,
            )
        }
        val joinDeferreds = star.clients.mapIndexed { i, client ->
            async { backgroundScope.gameJoin(client, raftConfig = fastRaftConfig(seed = (i + 2).toLong())) }
        }
        advanceTimeBy(2000)
        runCurrent()

        val hostSession = hostDeferred.await()
        val clientSessions = joinDeferreds.map { it.await() }

        val hostChat = chatQuilter(hostSession, backgroundScope, chatConfig, Random(700L))
        val clientChats = clientSessions.mapIndexed { i, session ->
            chatQuilter(session, backgroundScope, chatConfig, Random(710L + i))
        }
        advanceTimeBy(300)
        runCurrent()

        return HostedStarGame(
            this, star, backgroundScope, dispatcher, clientSessions, hostChat, clientChats, chatConfig,
        )
    }

    private fun chatQuilter(
        session: GameSession,
        scope: CoroutineScope,
        config: QuilterConfig = QuilterConfig(expectVirtualTime = true),
        random: Random = Random(700L),
    ): Quilter<Rga<String>> =
        Quilter(
            seam = session.appChannel("chat"),
            initial = Rga.empty(),
            valueSerializer = Rga.wireSerializer(String.serializer()),
            scope = scope,
            config = config,
            random = random,
        )

    /** Append [text] at the RGA tail so chat order is preserved. */
    private fun Quilter<Rga<String>>.appendChat(text: String) {
        val current = state.value
        val (_, op) = current.insertAt(
            replica = replica,
            index = current.toList().size,
            value = text,
        )
        apply(Patch(Rga.empty<String>().apply(op)))
    }
}
