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
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.fabric.Star
import us.tractat.kuilt.test.fabric.connectionPair
import us.tractat.kuilt.test.fabric.inMemoryStarOf
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    fun clientChatRgaReachesAllOtherClientsPromptly() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
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
    fun droppedClientSurvivesAndClusterKeepsReplicating() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
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
     * RECONNECT (stretch) — currently blocked by the deferred roster-full reconnect gap.
     *
     * The hub hosts with `peerCount = 4` / [ReturnPolicy.FullMembership] and **no** `livenessConfig`,
     * so once all four voters are admitted the host publishes admission-closed and its admit loop
     * exits. Dropping `client-1` does **not** free its seat: with no liveness monitoring the leader
     * never evicts the dead voter, so the roster stays full and admission stays closed. A
     * reconnecting peer with a fresh identity (`client-1-recon`) is therefore rejected with
     * [RosterFullException] inside [gameJoin] — the FullState chat heal never gets a chance because
     * we never obtain a [GameSession] to hang the chat Quilter on.
     *
     * This is the [gameHost]-documented deferred concern: "A peer arriving once the roster is
     * already full is the separate, deferred concern in #587." Healing a post-quorum reconnect needs
     * one of: (a) the host running with `livenessConfig` so the dropped voter is evicted and its seat
     * re-opened (then a fresh `gameJoin` succeeds and FullState heals), or (b) a reconnect entry point
     * that re-occupies the *same* seat under the original identity. Neither exists on this base, and
     * the brief forbids hand-wiring around `gameJoin`, so this test is ignored pending #587.
     */
    @Ignore(
        "roster-full reconnect (deferred #587): a fresh-identity gameJoin after the roster fills " +
            "throws RosterFullException; needs host liveness-eviction or a same-seat reconnect path.",
    )
    @Test
    fun droppedClientReconnectsViaFullState() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val game = setupStarGame(n = 3)

        game.clientChats[0].appendChat("before")
        advanceTimeBy(1000)
        runCurrent()

        game.dropClient(1)
        advanceTimeBy(1000)
        runCurrent()

        game.clientChats[0].appendChat("during")
        advanceTimeBy(1000)
        runCurrent()

        // RECONNECT: client 1 rejoins via a fresh link + gameJoin, heals to full history via FullState.
        val rejoined = game.reconnectClient(1)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(
            listOf("before", "during"),
            rejoined.state.value.toList(),
            "reconnecting client healed via FullState",
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
            return chatQuilter(reconSession, scope)
        }
    }

    /**
     * Build a started star of [n] clients around one hub, host a game (`peerCount = n + 1`,
     * FullMembership), join every client, and stand up a chat [Quilter] on each session's
     * `chat` app-channel. Reuses the exact concurrent host+joins bootstrap idiom across tests.
     */
    private suspend fun TestScope.setupStarGame(n: Int): HostedStarGame {
        val dispatcher = coroutineContext[ContinuationInterceptor]!!
        val star = backgroundScope.inMemoryStarOf(n = n)
        advanceTimeBy(300)
        runCurrent()

        // Host on the hub seam; each client joins on its own seam. The host's admit loop and the
        // joiners' admission waits must run concurrently, so launch them all before awaiting.
        val hostDeferred = async {
            backgroundScope.gameHost(star.hub, peerCount = n + 1, raftConfig = fastRaftConfig(seed = 1L))
        }
        val joinDeferreds = star.clients.mapIndexed { i, client ->
            async { backgroundScope.gameJoin(client, raftConfig = fastRaftConfig(seed = (i + 2).toLong())) }
        }
        advanceTimeBy(2000)
        runCurrent()

        val hostSession = hostDeferred.await()
        val clientSessions = joinDeferreds.map { it.await() }

        val hostChat = chatQuilter(hostSession, backgroundScope)
        val clientChats = clientSessions.map { chatQuilter(it, backgroundScope) }
        advanceTimeBy(300)
        runCurrent()

        return HostedStarGame(this, star, backgroundScope, dispatcher, clientSessions, hostChat, clientChats)
    }

    private fun chatQuilter(
        session: GameSession,
        scope: CoroutineScope,
    ): Quilter<Rga<String>> =
        Quilter(
            seam = session.appChannel("chat"),
            initial = Rga.empty(),
            valueSerializer = Rga.wireSerializer(String.serializer()),
            scope = scope,
            config = QuilterConfig(expectVirtualTime = true),
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
