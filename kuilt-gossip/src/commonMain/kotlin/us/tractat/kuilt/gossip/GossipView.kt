package us.tractat.kuilt.gossip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The live, self-healing partial view of the gossip overlay for one peer.
 *
 * [GossipView] turns a roster [StateFlow] into a continuously-maintained
 * [active]-neighbour set (the ~k peers this node gossips deltas with and GCs
 * against) plus an ordered [spares] standby list. It is the "membership/view
 * manager" of `docs/gossip-mesh-design.md` Phase 2 — the runtime counterpart of
 * the pure [partialView] selection function.
 *
 * **Derivation.** On every roster change the view is recomputed as a seeded
 * random k-out sample (`k = recommendedActiveViewSize(N)`), after a per-peer
 * **jitter** drawn from [jitter] so peers don't recompute in lockstep and storm
 * the overlay. Recompute is *churn-minimising*: healthy active neighbours are
 * retained across recomputes and only freed slots are filled (from [spares]
 * first, then a fresh random draw), so a single join/leave does not reshuffle
 * the whole overlay.
 *
 * **Liveness.** One [HeartbeatPartitionDetector] runs per active neighbour over a
 * shared [rawIncoming] fan-out (the SeamRoom-style composer the design calls for;
 * see [PerPeerSeam]). When a detector reports [PartitionEvent.PeerUnresponsive] or
 * [PartitionEvent.PeerLost] the edge is treated as down: the neighbour is dropped,
 * the next spare is promoted immediately (reactive healing), and the view is
 * reconciled. A failed peer is excluded from re-selection until it leaves the
 * roster; transient blips are healed by anti-entropy (Phase 1), not by this
 * manager re-admitting the peer (a documented v1 simplification — see
 * `gossip-mesh-design.md`).
 *
 * **Determinism (required).** All scheduling runs on the [start] scope's
 * dispatcher; all randomness draws from the single injected [random]; the clock
 * is the injected [clock]. State is confined to a single command-processing
 * coroutine (an actor over [commands]) so there are no locks and event ordering
 * is deterministic. Tests drive virtual time with `StandardTestDispatcher` +
 * bounded `advanceTimeBy`/`runCurrent` and **never** `advanceUntilIdle` (the
 * heartbeat timers re-arm forever).
 *
 * @param selfId this peer's id; never selected into the view.
 * @param seam the base multi-peer seam; per-peer detectors send ping/pong through it.
 * @param roster the live full-membership set (includes [selfId]); the view's source of truth.
 * @param rawIncoming a fan-out of the base seam's inbound frames. The owner (e.g.
 *   `GossipSeam`) is the single collector of `seam.incoming` and re-publishes each
 *   [Swatch] here, so the per-peer detectors can subscribe without contending for
 *   the single-consumer `seam.incoming` channel (ADR-034).
 * @param random a **seeded** RNG, seeded per-peer by the caller so peers choose
 *   independently. Drives both jitter and neighbour selection.
 * @param clock injected time source for the per-peer detectors; never the wall clock.
 */
public class GossipView(
    private val selfId: PeerId,
    private val seam: Seam,
    private val roster: StateFlow<Set<PeerId>>,
    private val rawIncoming: SharedFlow<Swatch>,
    private val random: Random,
    private val clock: () -> Instant,
    private val config: HeartbeatConfig = HeartbeatConfig(),
    private val spareCount: Int = DEFAULT_SPARE_COUNT,
    private val jitter: ClosedRange<Duration> = DEFAULT_JITTER,
) {
    private val _active = MutableStateFlow<Set<PeerId>>(emptySet())

    /** The ~k active neighbours this peer gossips deltas with and GCs against. */
    public val active: StateFlow<Set<PeerId>> = _active.asStateFlow()

    private val _spares = MutableStateFlow<List<PeerId>>(emptyList())

    /** Ordered standby list; [spares]`.first()` is promoted on the next neighbour loss. */
    public val spares: StateFlow<List<PeerId>> = _spares.asStateFlow()

    // ── Single-consumer state (mutated only inside processCommands) ────────────

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val detectors = mutableMapOf<PeerId, DetectorHandle>()

    // Peers whose edge is currently down; excluded from re-selection while still in
    // the roster. Pruned to roster membership on each reconcile.
    private var failed: Set<PeerId> = emptySet()

    private sealed interface Command {
        data class RosterChanged(val roster: Set<PeerId>) : Command

        data class EdgeDown(val peer: PeerId) : Command
    }

    private class DetectorHandle(
        val detector: HeartbeatPartitionDetector,
        val eventsJob: Job,
    )

    /**
     * Starts the view manager on [scope]. Launches the command processor (sole
     * mutator of view state) and the roster watcher (jittered recompute trigger).
     * The view stays empty until the first jittered recompute completes.
     */
    public fun start(scope: CoroutineScope) {
        scope.launch {
            for (command in commands) handle(scope, command)
        }
        scope.launch {
            roster.collectLatest { current ->
                delay(jitterMillis())
                commands.send(Command.RosterChanged(current))
            }
        }
    }

    private suspend fun handle(
        scope: CoroutineScope,
        command: Command,
    ) {
        when (command) {
            is Command.RosterChanged -> reconcile(scope, command.roster)
            is Command.EdgeDown -> {
                failed = failed + command.peer
                reconcile(scope, roster.value)
            }
        }
    }

    /**
     * Recomputes the view against [currentRoster], retaining healthy active
     * neighbours and filling freed slots from spares-then-random, then reconciles
     * the running detector set to match [active].
     */
    private suspend fun reconcile(
        scope: CoroutineScope,
        currentRoster: Set<PeerId>,
    ) {
        failed = failed intersect currentRoster

        val k = recommendedActiveViewSize(currentRoster.size)
        val candidates = currentRoster - selfId - failed

        val keep = _active.value.filter { it in candidates }
        val keepSet = keep.toSet()
        val needed = (k - keep.size).coerceAtLeast(0)

        // Promote existing spares before drawing fresh peers, so a freed slot is
        // healed by the next spare deterministically.
        val spareFirst = _spares.value.filter { it in candidates && it !in keepSet }
        val freshPool = (candidates - keepSet - spareFirst.toSet()).shuffled(random)
        val fillOrder = spareFirst + freshPool

        val promoted = fillOrder.take(needed)
        val newActive = (keep + promoted).toSet()
        val newSpares = fillOrder.drop(needed).take(spareCount)

        reconcileDetectors(scope, newActive)
        _spares.value = newSpares
        _active.value = newActive
    }

    private suspend fun reconcileDetectors(
        scope: CoroutineScope,
        newActive: Set<PeerId>,
    ) {
        val toStop = detectors.keys - newActive
        val toStart = newActive - detectors.keys
        for (peer in toStop) stopDetector(peer)
        for (peer in toStart) startDetector(scope, peer)
    }

    private fun startDetector(
        scope: CoroutineScope,
        peer: PeerId,
    ) {
        val link = PerPeerSeam(seam, peer, rawIncoming)
        val detector = HeartbeatPartitionDetector(link, peer, config, clock)
        detector.start(scope)
        val eventsJob =
            scope.launch {
                detector.events.collect { event ->
                    when (event) {
                        is PartitionEvent.PeerUnresponsive,
                        is PartitionEvent.PeerLost,
                        -> commands.send(Command.EdgeDown(event.peerId))
                        // A still-active peer that recovers needs no overlay change;
                        // anti-entropy backstops any data missed during the blip.
                        is PartitionEvent.PeerRecovered -> Unit
                    }
                }
            }
        detectors[peer] = DetectorHandle(detector, eventsJob)
    }

    private suspend fun stopDetector(peer: PeerId) {
        val handle = detectors.remove(peer) ?: return
        handle.eventsJob.cancel()
        handle.detector.stop()
    }

    private fun jitterMillis(): Long {
        val lo = jitter.start.inWholeMilliseconds
        val hi = jitter.endInclusive.inWholeMilliseconds
        return if (hi <= lo) lo else lo + random.nextLong(hi - lo + 1)
    }

    public companion object {
        /** Default standby-list length kept beyond the active view. */
        public const val DEFAULT_SPARE_COUNT: Int = 2

        /** Default per-peer recompute jitter window. */
        public val DEFAULT_JITTER: ClosedRange<Duration> = 50.milliseconds..200.milliseconds
    }
}

/**
 * A thin [Seam] view presenting only frames from [targetPeerId], drawn from the
 * shared [rawIncoming] fan-out. Lets one [HeartbeatPartitionDetector] per
 * neighbour subscribe to its peer's ping/pong traffic without contending for the
 * single-consumer [Seam.incoming] channel. [broadcast]/[sendTo] delegate
 * unchanged; [close] is a no-op (the view does not own the link lifecycle).
 *
 * Gossip-local twin of `SeamRoom`'s private `PerPeerSeam` (`:kuilt-session`),
 * duplicated because `:kuilt-gossip` deliberately does not depend on
 * `:kuilt-session`.
 */
internal class PerPeerSeam(
    private val delegate: Seam,
    private val targetPeerId: PeerId,
    private val rawIncoming: SharedFlow<Swatch>,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state

    override val incoming: Flow<Swatch>
        get() = rawIncoming.filter { it.sender == targetPeerId }

    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ): Unit = delegate.sendTo(peer, payload)

    override suspend fun close(reason: CloseReason) = Unit
}
