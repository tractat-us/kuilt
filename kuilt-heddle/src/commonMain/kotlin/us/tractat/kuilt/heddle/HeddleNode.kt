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
     * [lock], from [settleJoiners]/[policyEdges].
     *
     * [demandingObserved] is what makes the clamp an *edge* detector rather than a level one:
     * `false` means this peer has seen the child not competing, so the next round in which it
     * competes is a wake. An id that is *absent* has never been observed and is therefore never
     * treated as a wake — a first observation carries no evidence the child was ever idle, and
     * forfeiting a deficit accrued under some other peer's scheduling would be a penalty this
     * peer has no standing to impose. Seating a genuinely new generation is the seat bump's job
     * ([settleJoiners]), not the clamp's.
     */
    private val wakeOffsets = HashMap<AttachmentId, Rational>()
    private val demandingObserved = HashMap<AttachmentId, Boolean>()

    /**
     * Peers whose detector reached the terminal [PartitionEvent.PeerLost] state — the only ones a
     * committed enrollment re-monitors ([remonitorOnEnrollment]). Lock-guarded because it is written
     * from the detector-event collectors and read from the control plane's apply loop.
     */
    private val lostPeers = HashSet<ReplicaId>()

    /**
     * Edges this peer has been told, through the committed control log, that it must never author a
     * slot on again — the §6.2 step-2 barrier mark ([quiesceLocally]). **Local, in-memory, and
     * deliberately not replicated:** it is a promise this incarnation made, and a restart replays the
     * log to restore it before the boot gate lets any mutator run
     * (`GovernedHeddleNode.isWritable`, relocation design §6.5 residual 3).
     *
     * Written and read only under [lock], which is what makes the mark atomic with respect to
     * [complete] — the whole defeat of the barrier-vs-completion race.
     */
    private val quiescedEdges = HashSet<AttachmentId>()

    /**
     * Completions whose captured path crosses a quiesced edge that had **no live inbound generation**
     * to re-home onto at charge time — the window between a `Close` and the next `Activate`. They are
     * held here and retried on every subsequent control-plane publish and completion
     * ([flushBufferedCharges]): never dropped, and never charged to the dead edge (§6.2 step 2.1).
     *
     * Local state on the same terms as a reservation (`heddle-design.md` §4.4): a crash strands them
     * exactly as it strands an uncompleted reservation, which is the pre-existing, accepted class.
     */
    private val bufferedCharges = ArrayList<BufferedCharge>()

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

    /**
     * Introduce a new generation ([EntitlementLedger.prepare]); returns whether it applied.
     *
     * **Two peers preparing one id starve the child — on this ungoverned path only.** There is no
     * serializer here: both peers pass their own local `isKnown` check, the Quilter merges the
     * results, and the per-id join is a **set union** (§5.2 deliberately refuses last-writer-wins on
     * a parent pointer), so the id ends up bound to a divergent *set*. [EntitlementLedger.record]
     * then resolves to `null`, the policy drops the edge as inconsistent input, and the child never
     * competes again — permanently, on every peer, with no way to re-prepare the id. One proposer
     * per generation is a hard requirement in static mode, not a habit.
     *
     * The governed path does not have this failure mode:
     * [GovernedHeddleNode.prepare][GovernedHeddleNode.prepare] routes through the consensus log,
     * which orders concurrent proposals **first-wins** and answers the loser with a structured
     * `Conflict(Refused)` naming the bound id. That is why this mutator is not re-exposed on
     * [GovernedHeddleNode].
     *
     * **The record carries no seat** (issue #1752), so nothing about *when* it is prepared decides
     * where the child starts competing: [schedule]'s seat bump writes the [Gauge] at the front the
     * edge is actually joining, on every peer that still sees it unseated.
     */
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
        lock.withLock {
            ledgerQuilter.mutate { patch }
            // An Activate published here may be exactly the live inbound generation a buffered
            // charge was waiting for (§6.2 step 2.1), so retry them while the lock is already held.
            flushBufferedCharges()
        }
    }

    /**
     * The seam the H5 [HeddleControlPlane] runs the **§6.2 step-2 barrier** on: mark [edge] locally
     * unwritable and read back this peer's own final base slots there ([ControlBarrierSink]).
     *
     * **Both halves happen under the one mutator [lock], and that is the load-bearing part.** The
     * relocation drains [edge] to zero headroom, so a charge that lands after the read but before the
     * mark would be invisible to the move and would leave a *permanently unclearable*
     * [LedgerConflict.PerEdgeSafety] on a retired edge — the adversarial review's finding 2. Holding
     * the lock across mark-then-read makes that interleaving not exist: a [complete] either finishes
     * first (and its charge is inside the finals this returns) or runs after (and re-homes off the
     * quiesced edge entirely). There is no third case.
     *
     * Idempotent — a replayed `Quiesce` re-marks and re-reads, which is precisely what a restarted
     * peer needs in order to re-ack (relocation design §6.5 residual 2).
     *
     * `internal` — only [heddleGoverned] wires it.
     */
    internal fun asBarrierSink(): ControlBarrierSink = ControlBarrierSink { edge -> quiesceLocally(edge) }

    /** [asBarrierSink]'s body: mark, then read, atomically with respect to every local mutator. */
    private fun quiesceLocally(edge: AttachmentId): SlotFinals = lock.withLock {
        quiescedEdges += edge
        // Any charge already buffered may now re-home differently; retry before declaring finals so
        // the declaration covers everything this peer has actually written.
        flushBufferedCharges()
        ledger.value.baseFinalsOn(edge, self)
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
        // §7.2/§10.6/#1752 first: seat the newborns and clamp the wakers, against one front over
        // the set neither of them is in, *before* anyone is served — so a joiner meets the front it
        // is rejoining rather than one it helped set.
        settleJoiners(parent)
        var grants = 0
        while (grants < MAX_ROUNDS_PER_CALL) {
            // One pick, inside the Quilter's lock, on the state the grant lands on. Nothing
            // delegable ⇒ mutateOrSkip publishes nothing and answers false (#2090). This used to
            // peek on `ledger.value` first, solely so a barren round would not enter the Quilter
            // and broadcast an empty delta — which meant running the policy twice per grant, the
            // peek deciding against a state an inbound delta may already have moved past.
            val applied = ledgerQuilter.mutateOrSkip { s ->
                pickOne(s, parent)?.let { grant -> s.delegate(self, grant.attachment, grant.amount) }
            }
            if (!applied) break
            grants++
        }
        grants
    }

    /**
     * [parent]'s **current virtual time** `V` on this peer (design §7.2; issue #1688) — a
     * **diagnostic** read of this view's front. `null` when [parent] has no active children in
     * *this peer's view* and there is therefore no front to take.
     *
     * **Nothing has to be done with this value any more, and that is the point** (issue #1752).
     * It used to be the number a caller rounded into a newborn's record, which made a local
     * reading of a possibly-partial view into every peer's permanent fact — a `null` was then
     * genuinely dangerous, because "no children" and "this peer has not merged the children yet"
     * wear one face and only the first licenses seating at the origin (#1713). Seating is now
     * [schedule]'s [settleJoiners] bump into the replicated [Gauge], written by every peer that
     * still sees an edge unseated and resolved by `max`, so a low reading is absorbed rather than
     * frozen and there is nothing here to get irrecoverably wrong.
     *
     * `V` still depends on demand that ages out by *local* receive time and on non-replicated wake
     * clamps, so two peers legitimately read different values at the same instant. Treat it as
     * telemetry, not as a value to carry.
     *
     * @see prepare for the divergence hazard of two peers preparing one id on the ungoverned path.
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
     * **The one exception to "charge the captured path": a quiesced edge** (issue #1693;
     * `heddle-design.md` §10 invariant 4 as weakened by §4.4). If the captured path names an edge
     * this peer has applied a `Quiesce` for, the charge re-homes to that edge's child's **live**
     * inbound generation. That is not a rewrite of history — a quiesced edge is drained by
     * construction, so re-homing the completion is the same conserving move the recovery performs,
     * taken at charge time. Charging the dead edge instead is exactly the straggler that would leave
     * a permanently unclearable per-edge-safety violation on it. If no live inbound exists yet, the
     * charge is **buffered** and flushed when one activates ([bufferedCharges]).
     *
     * @throws IllegalArgumentException if [actualCost] is out of range (state untouched).
     */
    override fun complete(id: ReservationId, actualCost: Long) {
        lock.withLock {
            val reservation = reservations[id] ?: return
            require(actualCost in 0L..reservation.maximumCost) {
                "actualCost $actualCost must be in 0..${reservation.maximumCost}"
            }
            if (actualCost > 0L) charge(reservation.capturedPath, actualCost)
            reservations.remove(id)
            earmarks[reservation.leaf] = (earmarks[reservation.leaf] ?: 0L) - reservation.maximumCost
            flushBufferedCharges()
        }
    }

    /**
     * Charge [amount] against [capturedPath], re-homed off any quiesced edge — or buffered when the
     * re-home has nowhere to land yet. Always called under [lock].
     */
    private fun charge(capturedPath: List<AttachmentId>, amount: Long) {
        val path = rehomedPath(ledger.value, capturedPath)
        if (path == null) {
            bufferedCharges += BufferedCharge(capturedPath, amount)
            return
        }
        // A refusal here is an invariant violation, not an outcome — but it must still not
        // publish anything on its way to the throw (#2090).
        val charged = ledgerQuilter.mutateOrSkip { s -> s.spendCaptured(self, path, amount) }
        check(charged) { "spendCaptured returned null for captured path $path — charge lost" }
    }

    /**
     * [capturedPath] with every **quiesced** edge replaced by its child's single live inbound
     * generation, or `null` when some quiesced edge has no unique live inbound to re-home onto — the
     * `Close`-to-`Activate` window, and the quarantined-lineage case. A path with no quiesced edge is
     * returned unchanged, so the ordinary completion path is untouched.
     */
    private fun rehomedPath(s: EntitlementLedger, capturedPath: List<AttachmentId>): List<AttachmentId>? {
        if (capturedPath.none { it in quiescedEdges }) return capturedPath
        val out = ArrayList<AttachmentId>(capturedPath.size)
        for (e in capturedPath) {
            if (e !in quiescedEdges) {
                out += e
                continue
            }
            val child = s.record(e)?.child ?: return null
            out += s.liveInboundEdges(child).singleOrNull() ?: return null
        }
        return out
    }

    /**
     * Retry every buffered charge, keeping the ones that still have nowhere to re-home to. Always
     * called under [lock], from [complete], [quiesceLocally], and the control sink's publish — the
     * three moments at which a live inbound generation can newly exist for a buffered charge.
     */
    private fun flushBufferedCharges() {
        if (bufferedCharges.isEmpty()) return
        val retry = bufferedCharges.toList()
        bufferedCharges.clear()
        for (pending in retry) charge(pending.capturedPath, pending.amount)
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
     *
     * **An unseated edge is not a candidate** (issue #1752). [settleJoiners] seats every active
     * child before the first pick, but it and each pick are separate transactions, and [lock] does
     * not cover the Quilter's *inbound* merge — so a remote `Activate` can put a fresh, gauge-absent
     * edge into [s] partway through the loop. Such an edge reads `ev = baseIssued / w = 0`, which
     * makes it maximally eligible; and if it won, [EntitlementLedger.delegate] would write its
     * checkpoint at that origin, [EntitlementLedger.seat] would then refuse it forever (a gauge
     * exists), and the componentwise join could never lift a floor that is already the minimum. One
     * merge landing at the wrong instant would hand a child permanent lifetime credit. Deferring it
     * to the next [schedule] costs one round and cannot.
     */
    private fun pickOne(s: EntitlementLedger, parent: GroupId): Grant? {
        val localHoldings = s.holdings(parent, self) - (earmarks[parent] ?: 0L)
        if (localHoldings <= 0L) return null
        val edges = policyEdges(s, parent).filter { it.gauge != null }
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
                gauge = s.gauge(summary.attachment),
                baseIssued = s.baseIssuance(summary.attachment),
                virtualOffset = wakeOffsets[summary.attachment] ?: Rational.ZERO,
            )
        }
    }

    /**
     * Seat the **newborns** and clamp the **wakers** under [parent] — the two kinds of joiner —
     * against a single front taken over the set neither of them is in.
     *
     * **One front, one exclusion set, and that is the whole point** (issue #1752). Both joiners are
     * measured against "where the competing set is right now", and each is, in its own way, reading
     * from further back than that: a newborn carries no [Gauge] and so reads from its own origin,
     * the furthest back anything can read, while a waker is still carrying the stale virtual service
     * it slept on and this round's clamp has not been computed yet. Leave *either* in and it drags
     * the front down to meet itself; leave both in and they average each other's staleness and both
     * bank the difference. Seating them in two passes over two fronts is the same defect wearing a
     * schedule: with a runner at `ev = 200` and a waker at `0`, a seat-then-clamp order seats a
     * newborn at `100` and then clamps the waker to `mean(200, 100) = 150`, when both belong at
     * `200`. The newborn's share of that is **permanent** — it is frozen into a [Gauge.floor] the
     * join only ever ratchets up — which is exactly the §10.5 lifetime credit the seat exists to
     * deny. [HeddlePolicy.front]'s KDoc states the rule; this is the one place that has to honour it.
     *
     * **A `null` front means every active child is a joiner**, i.e. nobody is left to be level with,
     * and the seat bump then writes [Rational.ZERO]. That is not a guess: [EntitlementLedger.seat]
     * stores `max(front, baseIssued / w)`, so a zero front seats an edge exactly where it already
     * reads, pinning it without moving it. Pinning is the point — it stops a sibling that happens to
     * be served first from becoming the front the *others* are then seated behind, which is how a
     * cohort of newborns would otherwise be split by nothing but pick order. And if this view is
     * merely stale rather than genuinely empty, the componentwise join repairs it: a better-informed
     * peer's higher floor wins the `max`, so the low reading is absorbed rather than frozen. A
     * `null` front leaves the wakers unclamped, which is the same judgement in the other direction —
     * there is no one to be clamped *to*.
     *
     * Called under [lock] from [schedule]; publishes at most one patch.
     */
    private fun settleJoiners(parent: GroupId) {
        val s = ledger.value
        val edges = policyEdges(s, parent)
        forgetDepartedChildren(edges, s, parent)
        if (edges.isEmpty()) return

        val unseated = edges.filter { it.gauge == null }.mapTo(HashSet()) { it.record.id }
        val wakers = edges
            .filter { HeddlePolicy.isDemanding(it) && demandingObserved[it.record.id] == false }
            .mapTo(HashSet()) { it.record.id }
        val joiners = HashSet(unseated).apply { addAll(wakers) }
        val front = if (joiners.isEmpty()) null else HeddlePolicy.front(edges, excluding = joiners)

        if (unseated.isNotEmpty()) {
            val seatAt = front ?: Rational.ZERO
            ledgerQuilter.mutateOrSkip { state -> seatPatch(state, unseated, seatAt) }
        }
        if (front != null) clampWakers(edges, wakers, front)
        for (edge in edges) demandingObserved[edge.record.id] = HeddlePolicy.isDemanding(edge)
    }

    /**
     * One patch seating each of [unseated] at [front]. [EntitlementLedger.seat] refuses an edge that
     * already carries a gauge, so an id whose seat arrived between the front being read and this
     * patch being built is simply skipped rather than re-seated.
     */
    private fun seatPatch(
        s: EntitlementLedger,
        unseated: Set<AttachmentId>,
        front: Rational,
    ): Patch<EntitlementLedger>? {
        var seated: EntitlementLedger? = null
        for (id in unseated.sorted()) {
            val patch = s.seat(id, front) ?: continue
            seated = seated?.piece(patch.delta) ?: patch.delta
        }
        return seated?.let(::Patch)
    }

    /**
     * Forget the clamp state of edges that have left [parent]'s active set. Entries for other
     * parents are untouched — both maps are keyed by attachment id across the whole tree.
     */
    private fun forgetDepartedChildren(edges: List<PolicyEdge>, s: EntitlementLedger, parent: GroupId) {
        val present = edges.mapTo(HashSet()) { it.record.id }
        val departed = demandingObserved.keys.filterTo(HashSet()) { id ->
            id !in present && s.record(id)?.parent == parent
        }
        demandingObserved.keys.removeAll(departed)
        wakeOffsets.keys.removeAll(departed)
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
     * every **active** child under [parent] is a waker, so nobody ran ahead of anybody and there
     * is nothing to clamp to. Surviving children that are merely *idle* do not produce a `null`:
     * [HeddlePolicy.front] falls back to the maximum effective virtual service across them, the
     * bound that can only ever give a turn up (§7.2).
     *
     * **The stored offset is joined with `max`, never replaced** (issue #1714). [HeddlePolicy.front]
     * is a weighted mean over whoever is competing *right now*, and that mean is not monotone
     * across wake cycles: a child that woke into a busy front, ran, and slept again can re-wake
     * beside a starved sibling and compute a *smaller* offset than the one it is already carrying
     * — handing back credit the earlier clamp forfeited and dropping its effective virtual service.
     * Joining keeps the promise made two paragraphs up (a forward offset can only give a turn up)
     * across every wake, and it is the same invariant CFS/EEVDF hold by materialising vruntime so
     * it never decreases. The join is a high-water mark, not a sum, so a long-lived child cannot
     * accumulate an unbounded penalty; and the one-directional stance of §10.5 — credit forbidden,
     * a sliver of penalty merely undesirable — is what makes keeping the larger offset the safe
     * side when the front genuinely regresses. Nothing else lowers a stored offset: an edge is
     * forgotten only when it leaves the active set, which
     * [EntitlementLedger.activate][EntitlementLedger.activate] makes permanent (closure dominance,
     * §10.10), so a cleared clamp can never come back to an edge that will compete again.
     *
     * The demanding state is sampled **once per scheduling round, at entry**, before any grant
     * lands. Sampling again after the loop would mark a child that its own grants had just
     * satisfied as "idle", making the next round a spurious wake for it.
     *
     * **§10.6 is therefore enforced only modulo that sampling** (issue #1715). §7.2 defines the
     * clamp on an idle→demand *event* this peer has actually observed, and sampling once per
     * [schedule] entry cannot see a window that opens and closes between two samples.
     * Single-peer that is exactly right: the front moves only on this peer's own grants, all of
     * which land after the entry sample, so an idle interval between rounds banks nothing. The
     * escape is multi-peer — a child may idle and re-demand between this peer's rounds while
     * *another* peer's grants advance its siblings, so this peer sees demanding→demanding and does
     * not clamp. Accepted by design: the resulting catch-up burst is capped by this peer's holdings
     * and the §8.2 caps, any peer that did observe the window clamps independently, and wake
     * offsets are scheduler-local anyway, so divergence between peers is already tolerated.
     *
     * Called under [lock] from [schedule].
     */
    private fun clampWakers(edges: List<PolicyEdge>, wakers: Set<AttachmentId>, front: Rational) {
        for (edge in edges) {
            if (edge.record.id !in wakers) continue
            val computed = HeddlePolicy.wakeOffset(
                front = front,
                vRaw = HeddlePolicy.virtualService(edge),
                weight = edge.record.weight,
                sleeperCredit = config.policy.sleeperCredit,
            )
            // Join, never replace: the front is not monotone across wake cycles (#1714).
            val carried = wakeOffsets[edge.record.id] ?: Rational.ZERO
            wakeOffsets[edge.record.id] = Rational.max(carried, computed)
        }
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

    /**
     * The one path the topology mutators take: read the ledger, run [op] against it, publish
     * whatever patch it returns — or nothing at all, if it refuses. Returns whether it applied.
     *
     * [Quilter.mutateOrSkip] is what makes that one step (#2090). The shape it replaced ran [op]
     * **twice**: a fast-refuse against [ledger].value ahead of the Quilter's lock, purely so a
     * refusal would not enter it and broadcast an empty delta, and then the real one inside
     * `mutate`. That pre-lock read could also refuse against a ledger a concurrent inbound delta
     * had already moved past — answering `false` for an op that would have applied on the state
     * its patch was about to land on. Deciding inside the lock costs nothing now, so it decides
     * there, once.
     */
    private fun applyIfPresent(op: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean =
        lock.withLock { ledgerQuilter.mutateOrSkip(op) }

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

    /**
     * A completed charge held back because its captured path crosses a quiesced edge whose child has
     * no live inbound generation yet (§6.2 step 2.1). Retried by [flushBufferedCharges]; the original
     * [capturedPath] is kept, not the failed rewrite, so each retry re-resolves against fresh topology.
     */
    private data class BufferedCharge(val capturedPath: List<AttachmentId>, val amount: Long)

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
