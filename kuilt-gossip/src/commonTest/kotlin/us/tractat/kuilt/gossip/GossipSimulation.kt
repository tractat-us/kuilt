@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.gossip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Virtual-time simulation harness for the partial-mesh gossip overlay — the
 * `:kuilt-gossip` analogue of `RaftSimulation`/`MultiNodeRaftSim` required by
 * Phase 3 (#658).
 *
 * Stands up [n] in-memory peers over a shared full-mesh [InMemoryGossipNetwork],
 * each wrapped in a real [GossipSeam] with a **per-peer seeded** RNG and the
 * test's injected [clock]. Every node's [GossipSeam.incoming] is collected into a
 * per-node sink so a broadcast's reach is observable.
 *
 * **Determinism contract** (same as the Raft harness): all gossip/heartbeat timers
 * run on the [StandardTestDispatcher][kotlinx.coroutines.test.StandardTestDispatcher]
 * that [runTest][kotlinx.coroutines.test.runTest] installs; all randomness comes from
 * the per-peer seeds; time only advances through the bounded [awaitTrue]/[settle]
 * helpers, which `delay`-poll under a `withTimeout` so a non-converging overlay
 * **fails fast with a [dump]** rather than hanging. Never `advanceUntilIdle` — the
 * heartbeat timers re-arm forever.
 *
 * Pass the test's [TestScope] as [scope] and its [TestScope.backgroundScope] as
 * [nodeScope] so the infinite per-node loops cancel cleanly at teardown.
 */
class GossipSimulation(
    n: Int,
    private val scope: TestScope,
    private val nodeScope: CoroutineScope,
    private val scheduler: TestCoroutineScheduler,
    private val seedBase: Int = 1,
    private val config: HeartbeatConfig =
        HeartbeatConfig(interval = 1.seconds, timeout = 2.seconds, reconnectWindow = 2.seconds),
) {
    val nodeIds: List<PeerId> = (1..n).map { PeerId("node-$it") }
    val network = InMemoryGossipNetwork(nodeIds.toSet())

    private val clock: () -> Instant = { Instant.fromEpochMilliseconds(scheduler.currentTime) }
    private val seamsById = mutableMapOf<PeerId, GossipSeam>()
    private val scopes = mutableMapOf<PeerId, CoroutineScope>()
    private val sinks = mutableMapOf<PeerId, MutableList<Swatch>>()

    /** The active-view size *k* this overlay derives for its current membership. */
    val activeViewSize: Int get() = recommendedActiveViewSize(network.online.value.size)

    /** Count of gossip relay frames put on the wire (heartbeats excluded). */
    val relaySendCount: Int get() = network.relaySends

    fun seam(id: PeerId): GossipSeam = seamsById.getValue(id)

    private fun start(id: PeerId, index: Int) {
        val child = CoroutineScope(nodeScope.coroutineContext + Job(nodeScope.coroutineContext[Job]))
        scopes[id] = child
        val seam =
            GossipSeam(
                base = network.seam(id),
                random = Random(seedBase + index),
                clock = clock,
                config = config,
            )
        seamsById[id] = seam
        seam.start(child)
        val sink = mutableListOf<Swatch>()
        sinks[id] = sink
        child.launch { seam.incoming.collect { sink += it } }
    }

    /** Take [id] offline: drop it from the roster and cancel its node coroutines. */
    fun disconnect(id: PeerId) {
        scopes.remove(id)?.cancel()
        network.disconnect(id)
    }

    /** Payloads node [id] has delivered to its application sink, in arrival order. */
    fun received(id: PeerId): List<ByteArray> = sinks.getValue(id).map { it.toByteArray() }

    /** Clears every node's delivery sink so a subsequent broadcast can be measured in isolation. */
    fun clearSinks() = sinks.values.forEach { it.clear() }

    /** Originate a broadcast of [payload] from [from]. */
    suspend fun broadcastFrom(from: PeerId, payload: ByteArray) = seam(from).broadcast(payload)

    /** Advance virtual time past the view-recompute jitter window and run pending work. */
    suspend fun settle() {
        scheduler.advanceTimeBy(GossipView.DEFAULT_JITTER.endInclusive.inWholeMilliseconds + 1)
        scheduler.runCurrent()
    }

    /** True iff every node in [among] (default: all online except [origin]) has delivered [payload]. */
    fun allReceived(payload: ByteArray, origin: PeerId, among: Set<PeerId> = network.online.value - origin): Boolean =
        among.all { id -> received(id).any { it.contentEquals(payload) } }

    /**
     * Suspend until [cond] holds, polling each virtual millisecond under a [within] bound; on timeout
     * throw an [AssertionError] carrying a [dump]. The only way a gossip test should wait on overlay
     * state — a non-converging overlay fails fast instead of hanging.
     */
    suspend fun awaitTrue(what: String, within: Duration = DEFAULT_AWAIT, cond: () -> Boolean) {
        try {
            withTimeout(within) { while (!cond()) delay(1) }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError(dump("$what timed out after $within"))
        }
    }

    /** Per-node diagnostic: active view + delivered-frame count. */
    fun dump(reason: String): String = buildString {
        appendLine("GossipSimulation state dump — $reason")
        appendLine("  online=${network.online.value.map { it.value }.sorted()} relaySends=${network.relaySends}")
        nodeIds.forEach { id ->
            val active = seamsById[id]?.activePeers?.value?.map { it.value }?.sorted()
            appendLine("  ${id.value}: active=$active delivered=${sinks[id]?.size ?: 0}")
        }
    }

    init {
        nodeIds.forEachIndexed { index, id -> start(id, index) }
    }

    private companion object {
        val DEFAULT_AWAIT = 5.seconds
    }
}

/**
 * In-memory full-mesh transport beneath the gossip overlay: every connected peer
 * can [Seam.sendTo] every other, frames land in the target's [Seam.incoming] flow
 * stamped with the sender and a receiver-local sequence. Single-threaded —
 * mutated only from the test dispatcher — so it needs no locks.
 */
class InMemoryGossipNetwork(ids: Set<PeerId>) {
    private val channels = mutableMapOf<PeerId, Channel<Swatch>>()
    internal val online = MutableStateFlow<Set<PeerId>>(emptySet())
    private val seqByTarget = mutableMapOf<PeerId, Long>()

    /** Total relay (gossip-broadcast) frames put on the wire; heartbeat pings are not counted. */
    var relaySends: Int = 0
        private set

    init {
        ids.forEach { connect(it) }
    }

    fun connect(id: PeerId) {
        channels.getOrPut(id) { Channel(Channel.UNLIMITED) }
        online.update { it + id }
    }

    fun disconnect(id: PeerId) {
        online.update { it - id }
        channels.remove(id)?.close()
    }

    fun seam(id: PeerId): Seam = MeshSeam(id)

    private fun deliver(from: PeerId, to: PeerId, payload: ByteArray) {
        val channel = channels[to] ?: return
        val seq = (seqByTarget[to] ?: 0L) + 1
        seqByTarget[to] = seq
        channel.trySend(Swatch(payload = payload, sender = from, sequence = seq))
    }

    private fun recordSend(payload: ByteArray) {
        if (GossipFrame.tryDecode(Swatch(payload)) != null) relaySends++
    }

    private inner class MeshSeam(override val selfId: PeerId) : Seam {
        override val peers: StateFlow<Set<PeerId>> = online.asStateFlow()
        override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven).asStateFlow()
        override val incoming: Flow<Swatch> = channels.getValue(selfId).receiveAsFlow()

        override suspend fun broadcast(payload: ByteArray) {
            for (peer in online.value - selfId) {
                recordSend(payload)
                deliver(selfId, peer, payload)
            }
        }

        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (peer !in online.value) throw PeerNotConnected(peer)
            recordSend(payload)
            deliver(selfId, peer, payload)
        }

        override suspend fun close(reason: CloseReason) = Unit
    }
}
