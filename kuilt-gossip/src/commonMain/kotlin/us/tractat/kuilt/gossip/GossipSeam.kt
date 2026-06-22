package us.tractat.kuilt.gossip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import kotlin.random.Random
import kotlin.time.Instant

/**
 * A partial-mesh [Seam] over a [base] full-membership seam, exposing **two views
 * of the endpoints** (`docs/gossip-mesh-design.md`):
 *
 * - **active-neighbour view** ([activePeers]) — the ~k peers this node pushes
 *   deltas to and GCs against. Maintained by an internal [GossipView].
 * - **full-membership view** ([peers]) — everyone in the room, the pool
 *   anti-entropy samples. Delegated straight from [base].
 *
 * For a full-mesh base seam the active view is a strict subset, so [broadcast]
 * floods only to the ~k active neighbours rather than the whole room — the
 * O(N)-fan-out win. (Multi-hop relay + dedup is Phase 3; this slice does the
 * one-hop eager flood to neighbours.) [sendTo] is delegated to [base], which on a
 * full-mesh transport can reach any connected peer directly.
 *
 * **Single-collection [incoming] (ADR-034).** [GossipSeam] is the *single*
 * collector of `base.incoming`. Its [start] loop fans every inbound [Swatch] to
 * an internal `rawIncoming` bus that the per-neighbour detectors subscribe to,
 * and re-publishes only **non-heartbeat** frames to [incoming] — ping/pong frames
 * are consumed by the detectors and never surface to the application. Collect
 * [incoming] exactly once; wrap with `shareIn` for fan-out.
 *
 * **Lifecycle.** Call [start] once with a scope you own; it launches the inbound
 * loop and the [GossipView]. All timing/scheduling runs on that scope, all
 * randomness on the injected seeded [random], time via the injected [clock].
 *
 * @param base the underlying full-membership seam.
 * @param random seeded RNG, seeded per-peer by the caller (drives view selection + jitter).
 * @param clock injected time source for the per-neighbour detectors; never the wall clock.
 */
public class GossipSeam(
    private val base: Seam,
    random: Random,
    clock: () -> Instant,
    config: HeartbeatConfig = HeartbeatConfig(),
    spareCount: Int = GossipView.DEFAULT_SPARE_COUNT,
) : Seam {
    // Broadcast bus for raw inbound frames; per-neighbour detectors subscribe here
    // so they never contend for the single-consumer base.incoming channel.
    private val rawIncoming = MutableSharedFlow<Swatch>(extraBufferCapacity = RAW_BUFFER)

    private val _incoming = MutableSharedFlow<Swatch>(extraBufferCapacity = APP_BUFFER)

    private val view =
        GossipView(
            selfId = base.selfId,
            seam = base,
            roster = base.peers,
            rawIncoming = rawIncoming.asSharedFlow(),
            random = random,
            clock = clock,
            config = config,
            spareCount = spareCount,
        )

    /** The active-neighbour view — deltas/GC target set. Strict subset of [peers]. */
    public val activePeers: StateFlow<Set<PeerId>> get() = view.active

    /** Ordered standby neighbours promoted on active-neighbour loss. */
    public val spares: StateFlow<List<PeerId>> get() = view.spares

    override val selfId: PeerId get() = base.selfId

    /** Full-membership view (includes [selfId]); the anti-entropy sampling pool. */
    override val peers: StateFlow<Set<PeerId>> get() = base.peers

    override val state: StateFlow<SeamState> get() = base.state

    /** Application frames only — heartbeat ping/pong frames are filtered out. */
    override val incoming: Flow<Swatch> = _incoming.asSharedFlow()

    /**
     * Starts the inbound loop (sole collector of `base.incoming`) and the
     * [GossipView]. Idempotent only if called once per scope; call exactly once.
     */
    public fun start(scope: CoroutineScope) {
        view.start(scope)
        scope.launch {
            base.incoming.collect { swatch ->
                rawIncoming.emit(swatch)
                if (!swatch.isHeartbeat()) _incoming.emit(swatch)
            }
        }
    }

    /**
     * Eager-flood to the active neighbours only. A defined no-op when the active
     * view is empty (alone in the session), matching the [Seam] broadcast contract.
     *
     * Best-effort per neighbour: a send that fails (e.g. a neighbour that just left
     * the base roster) is swallowed so one stale edge can't drop the broadcast to
     * the rest — anti-entropy (Phase 1) re-delivers anything missed. Cancellation
     * still propagates ([runCatchingCancellable]).
     */
    override suspend fun broadcast(payload: ByteArray) {
        for (peer in view.active.value) {
            runCatchingCancellable { base.sendTo(peer, payload) }
        }
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ): Unit = base.sendTo(peer, payload)

    override suspend fun close(reason: CloseReason): Unit = base.close(reason)

    private fun Swatch.isHeartbeat(): Boolean {
        val decoded = decodeToString()
        return decoded.startsWith(HeartbeatPartitionDetector.PING_PREFIX) ||
            decoded.startsWith(HeartbeatPartitionDetector.PONG_PREFIX)
    }

    private companion object {
        const val RAW_BUFFER = 256
        const val APP_BUFFER = 64
    }
}
