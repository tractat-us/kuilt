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
import us.tractat.kuilt.crdt.IncarnationClock
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
    /**
     * A per-process-boot **epoch** that seeds the high bits of this peer's demand-board clock,
     * so a restarted replica's demand always out-clocks its dead incarnation's regardless of TTL
     * timing (design §6; the ephemeral demand board rides an [EphemeralMap], whose restart
     * recovery is otherwise only TTL-bounded — see #1666). It MUST be **fresh and strictly
     * increasing on every incarnation** of this peer — a persisted monotonic boot counter is the
     * canonical source. It is a required injected dependency for the same reason H5's
     * `incarnation` is: the node cannot self-generate restart-freshness without durable storage or
     * true entropy, and a test-seedable value derived from a `Random` would defeat it. Must be in
     * `[0, 2^31)` (a boot counter is effectively unbounded here).
     */
    epoch: Long,
) : FairShareExecution {
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

    // The demand-board clock packs the per-boot [epoch] above a monotonic per-boot counter, so
    // a restart is always fresh by clock rather than merely by TTL timing (#1666). See
    // [IncarnationClock]. Advanced only under [lock].
    private var demandClock = IncarnationClock.base(epoch)
    private var reservationSeq = 0L
    private val detectors = HashMap<PeerId, HeartbeatPartitionDetector>()

    /**
     * The §10.6 wake clamps this peer has computed, and the demanding-state each was derived
     * from (design §7.2; issue #1695). Both are keyed by attachment id across the whole tree and
     * are **scheduler-local** — never replicated, never in the ledger: a divergent offset
     * reorders one peer's grants but can never create entitlement. Written and read only under
     * [lock], from [refreshWakeClamps]/[policyEdges].
     *
     * [demandingObserved] is what makes the clamp an *edge* detector rather than a level one:
     * `false` means this peer has seen the child not competing, so the next round in which it
     * competes is a wake. An id that is *absent* has never been observed and is therefore never
     * treated as a wake — a first observation carries no evidence the child was ever idle, and
     * forfeiting a deficit accrued under some other peer's scheduling would be a penalty this
     * peer has no standing to impose. Seating a genuinely new generation is the creation rule's
     * job ([AttachmentRecord.neutral]), not the clamp's.
     */
    private val wakeOffsets = HashMap<AttachmentId, Rational>()
    private val demandingObserved = HashMap<AttachmentId, Boolean>()

    /**
     * Peers whose detector reached the terminal [PartitionEvent.PeerLost] state — the only ones a
     * committed enrollment re-monitors ([remonitorOnEnrollment]). Lock-guarded because it is written
     * from the detector-event collectors and read from the control plane's apply loop.
     */
    private val lostPeers = HashSet<ReplicaId>()

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

    /**
     * The seam the H5 [HeddleControlPlane] uses to report a **committed enrollment** into this
     * node's local liveness state ([remonitorOnEnrollment]) — the rejoin half of membership (#1652).
     * Nothing here is replicated and nothing feeds back into a control-plane gate. `internal` —
     * only [heddleGoverned] wires it.
     */
    internal fun asMembershipSink(): ControlMembershipSink =
        ControlMembershipSink { replica -> remonitorOnEnrollment(replica) }

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
        // §10.6 first: settle who woke since the last round *before* anyone is served, so a
        // waker is clamped to the front it is rejoining rather than to the one it helps set.
        refreshWakeClamps(ledger.value, parent)
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
     * [parent]'s **current virtual time** `V` on this peer — the front a new generation under it
     * must be seated at (design §7.2; issue #1688). `null` when [parent] has no active children
     * and there is therefore no front to take.
     *
     * Round it into a record with [AttachmentRecord.neutral], which applies the one documented
     * rule `initialVirtualTime = ⌈V⌉`; never seat a runtime generation by hand, and never at a
     * literal `0`, which would hand it the parent's whole past as lifetime credit (§10.5).
     *
     * **This is a propose-side read, not a derivation two peers may repeat.** `V` depends on
     * demand that ages out by *local* receive time and on non-replicated wake clamps, so two
     * peers legitimately read different values at the same instant. That is safe only because a
     * generation is agreed by **carriage**: the finished record travels in the control-plane log
     * entry and every peer applies the same bytes. Two peers preparing the *same* attachment id
     * with different values do not merge — the records diverge under one id, [record] resolves to
     * nothing, and the child is starved permanently. One proposer per generation.
     *
     * @see GovernedHeddleNode.prepareNeutral for the governed one-act form.
     */
    public fun parentVirtualTime(parent: GroupId): Rational? =
        lock.withLock { HeddlePolicy.front(policyEdges(ledger.value, parent)) }

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
    override fun reserve(leaf: GroupId, maximumCost: Long): ReservationId? {
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
    override fun complete(id: ReservationId, actualCost: Long) {
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
    override fun cancel(id: ReservationId): Unit = complete(id, 0L)

    /**
     * This peer's total outstanding earmark at leaf [leaf] — reserved-but-not-yet-completed
     * service. Local state (design §4.4): it is *not* replicated and does not appear in any
     * other peer's ledger; the reserved units simply stay `outstanding` on the leaf edge. The
     * spendable-now amount is `holdings(leaf, self) − earmarked(leaf)`.
     */
    override fun earmarked(leaf: GroupId): Long = lock.withLock { earmarks[leaf] ?: 0L }

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
            demandClock = IncarnationClock.next(demandClock)
            val u = EphemeralMap.empty<DemandBoard>().put(self, board, demandClock)
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
        val edges = policyEdges(s, parent)
        if (edges.isEmpty()) return null
        return HeddlePolicy.pick(edges, config.policy, localHoldings)
    }

    /**
     * The policy's view of [parent]'s active children in [s]: each edge's immutable record, its
     * parent-facing summary, the folded **live** demand, and this peer's stored §10.6 wake clamp.
     * An edge whose record has diverged is dropped — the policy refuses inconsistent inputs
     * rather than schedule against them. Always called under [lock] (the [demandTracker] and
     * [wakeOffsets] reads need it).
     */
    private fun policyEdges(s: EntitlementLedger, parent: GroupId): List<PolicyEdge> {
        val live = demandTracker.live()
        return s.activeChildren(parent).mapNotNull { summary ->
            val record = s.record(summary.attachment) ?: return@mapNotNull null
            PolicyEdge(
                record = record,
                summary = summary,
                demand = foldDemand(live, summary.attachment),
                virtualOffset = wakeOffsets[summary.attachment] ?: Rational.ZERO,
            )
        }
    }

    /**
     * Enforce §10.6 — "no unlimited idle credit" — for [parent]'s children (issue #1695).
     *
     * Every child that this peer has previously observed **not** competing and that competes now
     * has crossed the idle→demand edge the design §7.2 clamp is defined on. Each such waker is
     * clamped forward to [HeddlePolicy.front] — the front of the set it is rejoining — so it
     * re-enters level with the children that kept running instead of spending the whole idle
     * interval in one burst. The clamp is a *forward* offset only: it can never advance a child's
     * turn, only give one up.
     *
     * All wakers are excluded from the front together, not just each from its own: two siblings
     * waking in the same round would otherwise average each other's stale virtual service into
     * the front and both keep the credit. A `null` front means nothing survived the exclusion —
     * every competing child is a waker, so nobody ran ahead of anybody and there is nothing to
     * clamp to.
     *
     * The demanding state is sampled **once per scheduling round, at entry**, before any grant
     * lands. Sampling again after the loop would mark a child that its own grants had just
     * satisfied as "idle", making the next round a spurious wake for it.
     *
     * Called under [lock] from [schedule].
     */
    private fun refreshWakeClamps(s: EntitlementLedger, parent: GroupId) {
        val edges = policyEdges(s, parent)
        // Forget edges that have left this parent's active set. Entries for other parents are
        // untouched — both maps are keyed by attachment id across the whole tree.
        val present = edges.mapTo(HashSet()) { it.record.id }
        val departed = demandingObserved.keys.filterTo(HashSet()) { id ->
            id !in present && s.record(id)?.parent == parent
        }
        demandingObserved.keys.removeAll(departed)
        wakeOffsets.keys.removeAll(departed)
        if (edges.isEmpty()) return

        val wakers = edges.filterTo(HashSet()) { edge ->
            HeddlePolicy.isDemanding(edge) && demandingObserved[edge.record.id] == false
        }.mapTo(HashSet()) { it.record.id }
        val front = if (wakers.isEmpty()) null else HeddlePolicy.front(edges, excluding = wakers)
        if (front != null) {
            for (edge in edges) {
                if (edge.record.id !in wakers) continue
                wakeOffsets[edge.record.id] = HeddlePolicy.wakeOffset(
                    front = front,
                    vRaw = HeddlePolicy.virtualService(edge.record, edge.summary),
                    weight = edge.record.weight,
                    sleeperCredit = config.policy.sleeperCredit,
                )
            }
        }
        for (edge in edges) demandingObserved[edge.record.id] = HeddlePolicy.isDemanding(edge)
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

    /**
     * Seam-driven monitoring is **add-only**: a peer newly visible on the seam gets a detector, and
     * one it already has is left alone. It deliberately does *not* re-monitor a peer whose detector
     * reached the terminal Lost state — mere reappearance on the seam is not evidence the peer is a
     * participant again, and churning a live detector on every roster wobble would lose the
     * in-flight unresponsive/recovered transition. Rejoin is **membership**, so it is driven by a
     * committed `Enroll` instead ([remonitorOnEnrollment]).
     */
    private fun reconcileDetectors(peers: Set<PeerId>) {
        for (peer in peers) attachDetector(peer)
    }

    /**
     * Re-monitor [replica] after a committed `ControlCommand.Enroll` — the local, node-side effect
     * of the log-known roster gaining (or reasserting) a participant (#1652).
     *
     * A [PartitionEvent.PeerLost] detector is **terminal**: it closes its event channel and its
     * heartbeat loop returns, so the peer would stay in [unreachable] forever and never be watched
     * again, even after it came back. Reappearing on the seam is not enough to justify a fresh
     * detector — enrolling is, because it is the peer declaring itself a participant again, agreed
     * through the log. Only a **Lost** peer is re-attached: a healthy or merely unresponsive peer
     * has a live detector that is still tracking the truth, and replacing it would throw away the
     * recovery transition it is about to report.
     *
     * Idempotent, and safe to call for an enrollment that races the seam: if the peer is not
     * visible yet, clearing the terminal entry is enough — [reconcileDetectors] attaches when it
     * appears. It reclaims **nothing**: whatever the peer held while gone stays exactly where it is
     * (design §8.1; recovering an absent peer's entitlement is the unshipped `RevocationSeam`'s job).
     */
    internal fun remonitorOnEnrollment(replica: ReplicaId) {
        val peer = PeerId(replica.value)
        if (peer == selfPeer) return
        val terminal = lock.withLock {
            if (replica !in lostPeers) return
            detectors.remove(peer)
        }
        // The Lost detector already closed its channel, but its inbound-frame collector is still
        // running — reap it. `stop()` suspends, so it cannot run under the lock.
        terminal?.let { stopped -> scopeRef.launch { stopped.stop() } }
        if (peer in peersFlow.value) attachDetector(peer)
    }

    /**
     * Attach a detector for [peer] unless one is already live — the single place a detector is
     * created, shared by the seam-driven [reconcileDetectors] and the enrollment-driven
     * [remonitorOnEnrollment], so the two can race without ever producing two detectors for one peer.
     *
     * A fresh detector starts from a clean slate and reports only *transitions*, so it would never
     * emit the [PartitionEvent.PeerRecovered] that clears [unreachable]; attaching is therefore
     * itself the recovery signal for that peer. (On a first-ever attach the peer cannot be in
     * [unreachable] — only its own detector could have put it there — so this clears nothing.)
     */
    private fun attachDetector(peer: PeerId) {
        val replica = ReplicaId(peer.value)
        val detector = lock.withLock {
            if (peer == selfPeer || peer in detectors) return
            val fresh = HeartbeatPartitionDetector(
                link = PerPeerLivenessSeam(livenessSeam, peer, rawLiveness),
                peerId = peer,
                config = config.heartbeat,
                clock = clock,
            )
            detectors[peer] = fresh
            lostPeers -= replica
            fresh
        }
        _unreachable.update { it - replica }
        detector.start(scopeRef)
        scopeRef.launch {
            detector.events.collect { event ->
                _partitionEvents.emit(event)
                applyLivenessEvent(event)
            }
        }
    }

    private fun applyLivenessEvent(event: PartitionEvent) {
        val r = ReplicaId(event.peerId.value)
        // Remember the terminal transition: only a Lost peer is eligible for enrollment-driven
        // re-monitoring, and its detector can never report anything again.
        if (event is PartitionEvent.PeerLost) lock.withLock { lostPeers += r }
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
