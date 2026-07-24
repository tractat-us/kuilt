package us.tractat.kuilt.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.EphemeralMapTracker
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.quilter.MonotonicMillis
import us.tractat.kuilt.quilter.Quilter
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** Identity of one local reservation — a leaf earmark awaiting completion (design §4.4). */
@JvmInline
public value class ReservationId internal constructor(public val value: String)

/**
 * One peer's live view of a weighted fair-share session over a [Seam] — the **join
 * point** of the fair-share layer (design §15 Phase 4). It bonds four coordination-free
 * pieces onto one fabric:
 *
 *  - **The replicated ledger** ([ledger]) — an [EntitlementLedger] driven over the seam
 *    by a [Quilter]: delta-exchange while connected, anti-entropy reconciliation on
 *    heal. Every peer converges to one agreed tally by the lattice laws, no referee.
 *  - **The demand board** — each peer advertises, into its own slot of an
 *    [EphemeralMap], how much service each child edge could usefully take
 *    ([advertise]); a peer's demand ages out by **local receive time** if it stops
 *    refreshing, so a crashed peer's stale appetite cannot keep steering entitlement
 *    (design §6). Demand is advisory; it can never authorize a spend.
 *  - **The reservation table** — leaf work reserves against holdings ([reserve]), runs,
 *    then completes ([complete]) charging the ledger exactly once; a second [complete]
 *    for the same [ReservationId] is a no-op, so delivering a completion N times raises
 *    history once (design §4.4).
 *  - **Liveness** — one [HeartbeatPartitionDetector] per peer distinguishes a *partition*
 *    (recoverable) from a *crash*; [partitionEvents] surfaces the signal. v1 ships **no
 *    automatic reclamation**: a crashed peer's holdings and earmarks stay *stranded*,
 *    because a wrong reclaim is an overspend — the one unforgivable failure (design §8.1).
 *
 * Scheduling is an explicit, pure, bounded call ([schedule]) — a consumer drives its own
 * cadence — so the node owns no re-arming allocation loop; the only owned loops are the
 * replicator's anti-entropy, the demand collector, and the liveness detectors.
 *
 * ## Thread-safety
 *
 * The node is correct under a genuinely multi-threaded dispatcher. Its local mutable
 * state — the reservation table, per-leaf earmarks, the self demand slot, and the demand
 * clock — is guarded by one [reentrantLock][kotlinx.atomicfu.locks.reentrantLock] (no
 * suspend call is made while it is held; the [Quilter] mutators it calls under the lock
 * are synchronous). The replicated ledger and the demand tracker each carry their own
 * synchronization. There is **no** `limitedParallelism(1)` confinement.
 *
 * Construct via [heddleStatic] (design §9); the constructor is internal.
 *
 * @sample us.tractat.kuilt.heddle.sampleHeddleNode
 */
@OptIn(ExperimentalSerializationApi::class)
public class HeddleNode internal constructor(
    scope: CoroutineScope,
    seam: Seam,
    /** This peer's replica identity (design §9); matches its [Seam.selfId] by string value. */
    public val self: ReplicaId,
    initialLedger: EntitlementLedger,
    private val clock: () -> Instant,
    private val config: HeddleConfig,
) {
    // ── channels over the one physical seam (byte-frugal String namespace) ──────────
    private val peersFlow: StateFlow<Set<PeerId>> = seam.peers
    private val mux = NamedMux(seam, scope)
    private val ledgerSeam: Seam = mux.channel(LEDGER_CHANNEL)
    private val demandSeam: Seam = mux.channel(DEMAND_CHANNEL)
    private val livenessSeam: Seam = mux.channel(LIVENESS_CHANNEL)

    private val ledgerQuilter: Quilter<EntitlementLedger> = Quilter(
        seam = ledgerSeam,
        initial = initialLedger,
        valueSerializer = EntitlementLedger.serializer(),
        scope = scope,
        replica = self,
        config = config.quilter,
        clock = MonotonicMillis { clock().toEpochMilliseconds() },
        random = config.random,
    )

    /**
     * The replicated entitlement ledger as it has converged on this peer. Read
     * [holdings][EntitlementLedger.holdings] / [activeChildren][EntitlementLedger.activeChildren]
     * / [validate][EntitlementLedger.validate] off the latest value; collect the flow to
     * observe convergence.
     */
    public val ledger: StateFlow<EntitlementLedger> get() = ledgerQuilter.state

    // ── demand board: an EphemeralMap broadcast best-effort, aged out by local time ──
    private val demandSerializer = EphemeralMap.serializer(DemandBoard.serializer())
    private val demandTracker =
        EphemeralMapTracker<DemandBoard>(
            ttlMs = config.demandTtl.inWholeMilliseconds,
            clock = { clock().toEpochMilliseconds() },
        )

    // ── liveness ─────────────────────────────────────────────────────────────────
    private val rawLiveness = MutableSharedFlow<Swatch>(extraBufferCapacity = 64)
    private val _partitionEvents = MutableSharedFlow<PartitionEvent>(extraBufferCapacity = 64)

    /**
     * Peer-liveness signals as they are detected (design §8.1). A
     * [PartitionEvent.PeerUnresponsive] is a recoverable partition; a
     * [PartitionEvent.PeerLost] is a crash. **The node takes no ledger action on either**
     * — stranding a crashed peer's holdings is the safe choice (a wrong reclaim is an
     * overspend); recovery is an explicit later feature (design §9).
     */
    public val partitionEvents: Flow<PartitionEvent> get() = _partitionEvents.asSharedFlow()

    private val _unreachable = MutableStateFlow<Set<ReplicaId>>(emptySet())

    /** Peers currently flagged unresponsive or lost by the liveness detectors. */
    public val unreachable: StateFlow<Set<ReplicaId>> get() = _unreachable.asStateFlow()

    // ── local mutable state (single-lock guarded) ───────────────────────────────────
    private val lock = reentrantLock()
    private val reservations = HashMap<ReservationId, Reservation>()
    private val earmarks = HashMap<GroupId, Long>()
    private val selfDemand = HashMap<AttachmentId, Demand>()
    private var demandClock = 0L
    private var reservationSeq = 0L
    private val detectors = HashMap<PeerId, HeartbeatPartitionDetector>()

    private val selfPeer = PeerId(self.value)
    private val scopeRef = scope

    init {
        // Demand board: single-collect our channel, fold each frame into the local TTL tracker.
        scope.launch {
            demandSeam.incoming.collect { swatch ->
                val update = runCatchingCancellable {
                    Cbor.decodeFromByteArray(demandSerializer, swatch.toByteArray())
                }.getOrNull() ?: return@collect
                lock.withLock { demandTracker.received(update) }
            }
        }
        // Liveness: single-collect the liveness channel; fan out to per-peer detectors.
        scope.launch { livenessSeam.incoming.collect { rawLiveness.emit(it) } }
        scope.launch {
            peersFlow.collect { peers -> reconcileDetectors(peers) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Topology — the strict-drain lifecycle transitions, applied through the Quilter so
    // they replicate. Each returns whether it took effect (false = the ledger refused;
    // see the matching EntitlementLedger mutator's contract).
    // ─────────────────────────────────────────────────────────────────────────────

    /** Introduce a new generation ([EntitlementLedger.prepare]); returns whether it applied. */
    public fun prepare(record: AttachmentRecord): Boolean =
        applyIfPresent { it.prepare(record) }

    /** Open delegation across [edge] ([EntitlementLedger.activate]); returns whether it applied. */
    public fun activate(edge: AttachmentId): Boolean = applyIfPresent { it.activate(edge) }

    /** Stop new delegation across [edge] ([EntitlementLedger.close]); returns whether it applied. */
    public fun close(edge: AttachmentId): Boolean = applyIfPresent { it.close(edge) }

    /** Retire a drained [edge] ([EntitlementLedger.retire]); returns whether it applied. */
    public fun retire(edge: AttachmentId): Boolean = applyIfPresent { it.retire(edge) }

    /**
     * The sink the H5 [HeddleControlPlane] uses to **publish an already-approved** control patch
     * into this node's replicated ledger, so data-plane consumers converge over the seam. The
     * accept/refuse *decision* is made upstream against the control plane's log-pure projection —
     * this only replicates a patch the log already ordered and admitted, so a rejected act never
     * reaches the Quilter. Applied under the node lock, keeping lock ordering uniform with
     * [schedule]/[applyIfPresent]. `internal` — only [heddleGoverned] wires it.
     */
    internal fun asControlSink(): ControlLedgerSink = ControlLedgerSink { patch ->
        lock.withLock { ledgerQuilter.mutate { patch } }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Scheduling — one or more EEVDF allocation rounds at [parent], delegating this
    // peer's holdings down toward demanding children. Pure and bounded: each grant
    // reduces this peer's holdings at [parent], so the loop terminates. Returns the
    // number of quanta delegated.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Run allocation rounds at [parent] until nothing more can be delegated: build the
     * policy input from the active children, their [EdgeSummary]s, and the folded **live**
     * demand, then apply each [HeddlePolicy.pick] grant to the ledger before the next
     * round (design §7.3). A bad local decision only misplaces entitlement — it can never
     * create any, so partitioned peers may schedule divergently and still converge on heal.
     *
     * @return the count of grants applied this call.
     */
    public fun schedule(parent: GroupId): Int = lock.withLock {
        var grants = 0
        while (grants < MAX_ROUNDS_PER_CALL) {
            // Peek on the current state: if nothing is delegable, stop *without* entering the
            // Quilter (which would consume a seq and broadcast an empty delta — chatty).
            val peek = pickOne(ledger.value, parent)
            if (peek == null || ledger.value.delegate(self, peek.attachment, peek.amount) == null) break
            var applied = false
            ledgerQuilter.mutate { s ->
                val grant = pickOne(s, parent)
                val patch = grant?.let { s.delegate(self, it.attachment, it.amount) }
                if (patch != null) {
                    applied = true
                    patch
                } else {
                    Patch(EntitlementLedger.ZERO)
                }
            }
            if (!applied) break
            grants++
        }
        grants
    }

    /**
     * The current §8.2 bound metrics at [parent], from the merged ledger, the roster (live
     * peers plus currently-[unreachable] ones, since a partitioned peer is exactly the
     * divergence source the bound must count), and the set of children with live demand.
     */
    public fun boundMetrics(parent: GroupId): BoundMetrics {
        val s = ledger.value
        val demanding = lock.withLock {
            val live = demandTracker.live()
            s.activeChildren(parent)
                .mapNotNullTo(HashSet()) { c -> c.attachment.takeIf { foldDemand(live, it).targetOutstanding > 0L } }
        }
        return BoundMetrics.at(s, parent, roster(), demanding, config)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Reservations — leaf earmark → completion, with local single-writer idempotence.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Earmark up to [maximumCost] service units against this peer's holdings at leaf
     * [leaf], returning a [ReservationId] to complete against, or `null` if the peer's
     * *available* holdings (holdings minus outstanding earmarks) at [leaf] cannot cover
     * it. The earmark is **local state, not replicated** (design §4.4): the reserved
     * units simply stay `outstanding` on the leaf edge until spent, which the accounting
     * already charges. A crashed peer strands its earmarks (design §8.1).
     */
    public fun reserve(leaf: GroupId, maximumCost: Long): ReservationId? {
        require(maximumCost > 0L) { "maximumCost must be positive, was $maximumCost" }
        return lock.withLock {
            val s = ledger.value
            // Reject a non-leaf group up front (else every completion would throw), and
            // capture the entitlement path NOW, while the topology is valid (design §4.4):
            // the completion charges these exact captured edges, never a later recompute.
            if (!s.isLeaf(leaf)) return@withLock null
            val captured = s.lineageOf(leaf) ?: return@withLock null // quarantined lineage → refuse
            if (captured.isEmpty()) return@withLock null // a root leaf has no edge to charge
            val available = s.holdings(leaf, self) - (earmarks[leaf] ?: 0L)
            if (available < maximumCost) return@withLock null
            val id = ReservationId("$self#${reservationSeq++}")
            reservations[id] = Reservation(leaf, maximumCost, captured)
            earmarks[leaf] = (earmarks[leaf] ?: 0L) + maximumCost
            id
        }
    }

    /**
     * Complete reservation [id], charging [actualCost] service (`0 ≤ actualCost ≤` the
     * reserved maximum) against the **path captured at [reserve]** and releasing the
     * earmark. **Idempotent by local single-writer discipline** (design §4.4): the first
     * call charges once and removes the reservation; any later call for the same [id] finds
     * nothing and is a no-op, so delivering a completion N times raises history exactly
     * once. An unknown [id] is silently ignored.
     *
     * Ordering is deliberate (design §4.4 — a validation failure must not corrupt state):
     * the [actualCost] bound is checked **before** the reservation is removed or the earmark
     * decremented, and the earmark/reservation are cleared only **after** the charge lands.
     * The charge uses the captured path, so it succeeds even if the leaf was concurrently
     * reparented, quarantined, or gained a child; a swallowed positive charge would silently
     * lose service, so a null result **fails loud** rather than being dropped.
     *
     * @throws IllegalArgumentException if [actualCost] is out of range (state untouched).
     */
    public fun complete(id: ReservationId, actualCost: Long) {
        lock.withLock {
            val reservation = reservations[id] ?: return
            require(actualCost in 0L..reservation.maximumCost) {
                "actualCost $actualCost must be in 0..${reservation.maximumCost}"
            }
            if (actualCost > 0L) {
                var charged = false
                ledgerQuilter.mutate { s ->
                    val patch = s.spendCaptured(self, reservation.capturedPath, actualCost)
                    if (patch != null) {
                        charged = true
                        patch
                    } else {
                        Patch(EntitlementLedger.ZERO)
                    }
                }
                check(charged) {
                    "spendCaptured returned null for captured path ${reservation.capturedPath} — charge lost"
                }
            }
            reservations.remove(id)
            earmarks[reservation.leaf] = (earmarks[reservation.leaf] ?: 0L) - reservation.maximumCost
        }
    }

    /** Cancel reservation [id] — a completion charging zero service (design §4.4). */
    public fun cancel(id: ReservationId): Unit = complete(id, 0L)

    /**
     * This peer's total outstanding earmark at leaf [leaf] — reserved-but-not-yet-completed
     * service. Local state (design §4.4): it is *not* replicated and does not appear in any
     * other peer's ledger; the reserved units simply stay `outstanding` on the leaf edge. The
     * spendable-now amount is `holdings(leaf, self) − earmarked(leaf)`.
     */
    public fun earmarked(leaf: GroupId): Long = lock.withLock { earmarks[leaf] ?: 0L }

    // ─────────────────────────────────────────────────────────────────────────────
    // Demand board — advertise this peer's per-edge appetite (design §6).
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Advertise that this peer could usefully take [demand] more service down child edge
     * [edge]. Updates this peer's own demand slot, folds it into the local tracker, and
     * broadcasts it best-effort over the demand channel. Advisory only — it can never
     * authorize a spend (design §6). Re-advertise periodically to keep the slot live;
     * a slot that stops refreshing ages out after [HeddleConfig.demandTtl].
     */
    public fun advertise(edge: AttachmentId, demand: Demand) {
        val update = lock.withLock {
            if (demand == Demand.NONE) selfDemand.remove(edge) else selfDemand[edge] = demand
            val board = DemandBoard(selfDemand.toMap())
            val u = EphemeralMap.empty<DemandBoard>().put(self, board, ++demandClock)
            demandTracker.received(u)
            u
        }
        scopeRef.launch {
            runCatchingCancellable {
                demandSeam.broadcast(Cbor.encodeToByteArray(demandSerializer, update))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // internals
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Build the policy input at [parent] from [s] + live demand and run one [HeddlePolicy.pick].
     *
     * The schedulable holdings subtract this peer's **earmark at [parent]**: units reserved at
     * [parent] (as a leaf) via [reserve] must not be delegated down [parent]'s children, or a
     * later [complete] — which charges the captured path *ungated*, relying on the earmark to
     * have kept the units — would overspend and drive holdings negative (the "one unforgivable
     * failure", design §10.1/§10.7). This is the only holdings-reducing path the node exposes
     * (there is no public `delegate`/`transfer`), so this subtraction closes the leak entirely.
     * Always called under [lock] (both [schedule] call sites hold it), so the [earmarks] read is
     * race-free.
     */
    private fun pickOne(s: EntitlementLedger, parent: GroupId): Grant? {
        val localHoldings = s.holdings(parent, self) - (earmarks[parent] ?: 0L)
        if (localHoldings <= 0L) return null
        val live = demandTracker.live()
        val edges = s.activeChildren(parent).mapNotNull { summary ->
            val record = s.record(summary.attachment) ?: return@mapNotNull null
            PolicyEdge(record, summary, foldDemand(live, summary.attachment))
        }
        if (edges.isEmpty()) return null
        return HeddlePolicy.pick(edges, config.policy, localHoldings)
    }

    /** Fold the live slots' demand for [edge] by taking the most optimistic appetite (design §6). */
    private fun foldDemand(live: Map<ReplicaId, DemandBoard>, edge: AttachmentId): Demand {
        var target = 0L
        var maxGrant = 0L
        var any = false
        for (board in live.values) {
            val d = board.perEdge[edge] ?: continue
            any = true
            target = maxOf(target, d.targetOutstanding)
            maxGrant = maxOf(maxGrant, d.maximumUsefulGrant)
        }
        return if (any) Demand(target, maxGrant) else Demand.NONE
    }

    private fun applyIfPresent(op: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean =
        lock.withLock {
            // Fast-refuse on the current state so a refused op does not enter the Quilter and
            // broadcast an empty delta; the real op still runs inside mutate on fresh state.
            if (op(ledger.value) == null) return@withLock false
            var applied = false
            ledgerQuilter.mutate { s ->
                val patch = op(s)
                if (patch != null) {
                    applied = true
                    patch
                } else {
                    Patch(EntitlementLedger.ZERO)
                }
            }
            applied
        }

    /**
     * The replica roster for the §8.2 bound: every peer visible on the seam, plus self, plus
     * any currently-[unreachable] peer. Including the unreachable peers is deliberate — a
     * partitioned peer may have dropped out of [Seam.peers], yet it still holds entitlement
     * that can diverge, so the bound must keep counting it (otherwise `n·E` would *shrink*
     * exactly when the divergence risk appears).
     */
    private fun roster(): Set<ReplicaId> {
        val rs = peersFlow.value.mapTo(HashSet()) { ReplicaId(it.value) }
        rs += self
        rs += _unreachable.value
        return rs
    }

    private fun reconcileDetectors(peers: Set<PeerId>) {
        // Deliberately add-only for v1: once a peer is monitored it stays monitored, and a peer
        // flagged lost is cleared from `unreachable` only by an explicit PeerRecovered event —
        // there is no re-monitoring dance on rejoin. This matches the §8.1/§9 "no automatic
        // reclamation" posture (a crashed peer's holdings stay stranded until an operator
        // recovers them); richer rejoin handling belongs with the control plane (H5).
        for (peer in peers) {
            if (peer == selfPeer || peer in detectors) continue
            val link = PerPeerLivenessSeam(livenessSeam, peer, rawLiveness)
            val detector = HeartbeatPartitionDetector(
                link = link,
                peerId = peer,
                config = config.heartbeat,
                clock = clock,
            )
            detectors[peer] = detector
            detector.start(scopeRef)
            scopeRef.launch {
                detector.events.collect { event ->
                    _partitionEvents.emit(event)
                    applyLivenessEvent(event)
                }
            }
        }
    }

    private fun applyLivenessEvent(event: PartitionEvent) {
        val r = ReplicaId(event.peerId.value)
        _unreachable.update { current ->
            when (event) {
                is PartitionEvent.PeerRecovered -> current - r
                is PartitionEvent.PeerUnresponsive, is PartitionEvent.PeerLost -> current + r
            }
        }
    }

    /**
     * A local earmark awaiting completion. [capturedPath] is the entitlement path (root→leaf
     * edge list) captured at [reserve] time, charged verbatim at [complete] so history follows
     * the generation the work was admitted under, never the topology visible at completion.
     */
    private data class Reservation(
        val leaf: GroupId,
        val maximumCost: Long,
        val capturedPath: List<AttachmentId>,
    )

    public companion object {
        /** NamedMux channel carrying [Quilter] ledger-replication traffic. */
        internal const val LEDGER_CHANNEL: String = "heddle.ledger"

        /** NamedMux channel carrying best-effort demand-board frames (design §6). */
        internal const val DEMAND_CHANNEL: String = "heddle.demand"

        /** NamedMux channel carrying liveness heartbeats (design §8.1). */
        internal const val LIVENESS_CHANNEL: String = "heddle.liveness"

        /** Hard ceiling on grants per [schedule] call — a safety stop, never reached in practice. */
        private const val MAX_ROUNDS_PER_CALL: Int = 100_000
    }
}
