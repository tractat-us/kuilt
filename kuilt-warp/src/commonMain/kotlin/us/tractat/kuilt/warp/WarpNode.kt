@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.warp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.warp.WarpNode")

/**
 * Ties the warp foundation together over a real [Seam].
 *
 * Each peer in the session claims and executes the tasks it owns on the consistent-hash
 * ring — `ring.owner(task) == selfId`. Results land in a shared, replicated [Results]
 * board.
 *
 * **Coordinated-path execution guarantee.** The [CoordinationKind.Coordinated] path
 * achieves exactly-once execution under stable leadership, leadership failover, and the
 * tested roster-churn scenarios. The mechanisms:
 * - Coordinated tasks **bypass the intent register** entirely (#873): consensus — the Raft
 *   log plus the leader-side gates below — is the sole arbiter of who executes, so the
 *   intent path's ad-hoc lowest-PeerId election never runs for them (and can no longer
 *   leak per-task intent entries).
 * - Each ring owner *proposes* the task to [raftNode] with a stable `requestId` (derived
 *   from [TaskId]) — preventing the same node from double-proposing after a retry.
 * - Execution is *driven from the committed log*, not from the `propose()` return. The
 *   background [onCoordinatedCommit] listener fires on every committed entry; only the
 *   current Raft leader proceeds, and it must first pass the **quorum fence** — a
 *   [RaftNode.readIndex] round confirming it still holds a voter quorum at its current
 *   term. A deposed-but-unaware leader cannot pass the fence (any quorum it could
 *   assemble intersects the majority that elected its successor at a higher term), so
 *   the transient dual-leader window (#879 a) closes at the execution decision point.
 * - A committed entry consumed while *no* node held leadership (mid-election — every node
 *   skips it on the role check, and `committed` is replay=0) is **re-driven**: on acquiring
 *   leadership a node re-proposes every coordinated-queue task it has not executed and
 *   that has no replicated result, giving the stranded task a fresh committed entry to
 *   fire from (#879 b).
 * - A local [coordinatedApplied] set plus the replicated coordinated-queue membership and
 *   [Results]-board gates deduplicate when two entries for the same task both committed
 *   (ring churn double-propose, or a re-drive racing the original entry).
 *
 * The residual window is execution *duration*: a leader that passes the fence and is
 * deposed while [coordinatedExecutor] is still running cannot be distinguished from a
 * crashed one, so the next leader's re-drive may run the task again (at-least-once in
 * that narrow case). Duplicate *results* are absorbed by the [Results] LWW ORMap backstop
 * and counted in [duplicates]; a non-idempotent executor should be tolerant of a rerun
 * that follows its own mid-flight interruption.
 *
 * **Roster source.** [WarpNode] drives the consistent-hash ring from [rosterFlow] — a
 * [Flow] of the live peer set. Two pluggable sources are provided:
 *
 * - [Seam.rosterSnapshot] — derives the roster from [Seam.peers]; cheap and eventually
 *   consistent. Preserves the pre-#826 behavior.
 * - [RaftNode.rosterSnapshot] — derives the roster from Raft's agreed [ClusterConfig];
 *   strongly consistent, minimising duplicate executions under stable membership.
 *
 * The roster source is **required** — absence would silently choose a consistency model
 * without the caller knowing. Pass the appropriate adapter for your use case.
 *
 * **Liveness-driven failover.** [WarpNode] maintains two failure-detection signals:
 *
 * 1. *Roster departure* — when a peer disappears from [rosterFlow], the ring is rebuilt
 *    immediately and pending tasks re-home to their new owner.
 * 2. *Heartbeat partition* — a [HeartbeatPartitionDetector] runs per admitted peer
 *    (peers in [rosterFlow] that are not [selfId]). When a peer becomes unresponsive
 *    (heartbeat timeout) or lost (reconnect window expired), it is added to
 *    [partitionedPeers] and excluded from the effective ring. On recovery it is removed.
 *    The effective ring is `rosterPeers - partitionedPeers`; tasks whose former owner
 *    is partitioned re-home to the next peer clockwise on the effective ring.
 *
 * The [Results] backstop ensures correctness under both signals: duplicate executions
 * during a failover window are absorbed, and the board converges to one entry per task.
 *
 * **Incoming fan-out.** [WarpNode] is the **sole collector** of [seam.incoming] (satisfying
 * the kuilt single-collection contract, ADR-034). Every received [Swatch] is fanned to
 * [rawIncoming] before the mux channels consume it. The internal [MuxSeam] subscribes to
 * [rawIncoming] rather than [seam.incoming] directly. Per-peer [HeartbeatPartitionDetector]s
 * subscribe to [rawIncoming] filtered by sender via [PerPeerSeam] — no second collection
 * of [seam.incoming] is ever needed.
 *
 * **Thread-safety.** Shared mutable state (`ring`, `claimed`, `partitionedPeers`,
 * `detectorJobs`, `rosterPeers`) is guarded by an explicit
 * [kotlinx.atomicfu.locks.ReentrantLock] so this type is safe under a multi-threaded
 * dispatcher. No `limitedParallelism(1)` confinement is used — see CLAUDE.md.
 *
 * **Injection contract.** [scope], [rosterFlow], and [clock] are required parameters.
 * Pass [kotlinx.coroutines.test.TestScope.backgroundScope] in tests, and a fixed
 * [Instant]-returning lambda for the clock.
 *
 * @param selfId This peer's identifier on the [seam].
 * @param seam The multi-peer session. [WarpNode] takes sole ownership of [Seam.incoming]
 *   by collecting it once and fanning every frame to [rawIncoming].
 * @param rosterFlow A [Flow] of the current live peer set, used to rebuild the hash ring
 *   on membership change. Use [Seam.rosterSnapshot] for eventual consistency or
 *   [RaftNode.rosterSnapshot] for strong consistency backed by Raft membership.
 * @param scope Coroutine scope for background collection jobs. Required — no default.
 * @param quilterConfig [QuilterConfig] for both internal [Quilter]s. Defaults to the
 *   [QuilterConfig] production defaults. Pass a short-cadence config in tests that need
 *   fast anti-entropy.
 * @param clock Provides the current [Instant] for per-peer [HeartbeatPartitionDetector]s.
 *   Required — never [kotlin.time.Clock.System] by default. Production callers use
 *   `{ Clock.System.now() }`; tests inject a fixed or virtual clock so liveness timeouts
 *   are deterministic.
 * @param heartbeatConfig Timing for per-peer heartbeat ping/pong. A tuning parameter —
 *   defaults to [HeartbeatConfig] production defaults (5 s interval, 15 s timeout,
 *   60 s reconnect window). Tests typically inject a short-cadence config.
 * @param strategy How owned tasks are claimed. [ClaimStrategy.Ring] is pure consistent-hash
 *   assignment; [ClaimStrategy.RingWithIntent] adds the intent-register safety net. Defaults
 *   to [ClaimStrategy.RingWithIntent].
 * @param registry The local op registry that maps symbolic [OpId]s to their [Op]
 *   implementations. A claiming peer resolves the [TaskDescriptor.op] here and runs its own
 *   registered copy — the function never travels; only the name does. An op absent from the
 *   registry leaves the task unclaimed and pending (the "bobbin not loaded yet" state — warp
 *   slices C4/C5 will service it via lazy-fetch; see [TaskDescriptor]).
 * @param coordinatedExecutor Suspending function for [CoordinationKind.Coordinated] tasks.
 *   Invoked **from the committed-log listener** ([onCoordinatedCommit]) on the current Raft
 *   leader, not inline after `propose()` — and only after the leader passes the
 *   [RaftNode.readIndex] quorum fence, so a deposed-but-unaware leader never fires it.
 *   [coordinatedApplied] plus the replicated queue/results gates prevent re-invocation when
 *   two entries for the same task both committed (churn double-propose or a re-drive racing
 *   the original entry). The one residual window is a leader deposed while this function is
 *   *mid-flight* — the next leader's re-drive may then run the task again; see the
 *   class-level guarantee note. Defaults to an explicit error so a caller who provides a
 *   [raftNode] but forgets to supply an executor surfaces the omission immediately rather
 *   than silently doing nothing.
 * @param raftNode [RaftNode] backing the [CoordinationKind.Coordinated] execution path.
 *   When supplied, the ring owner proposes the task to this Raft cluster for total-order
 *   delivery. Execution then fires from [raftNode.committed] on the Raft leader's [WarpNode],
 *   fenced by [RaftNode.readIndex] and backstopped by leadership-acquisition re-drive of
 *   stranded queue entries — exactly-once under leadership failover and warp-roster churn
 *   (see the class-level guarantee note for the one mid-execution-deposition residual).
 *
 *   **Required for coordinated tasks.** If `null`, calling [enqueue] with
 *   [CoordinationKind.Coordinated] throws [IllegalStateException] immediately — fail-loud,
 *   never a silent downgrade to the ring path. Pass a [us.tractat.kuilt.raft.test.FakeRaftNode]
 *   from `:kuilt-raft-test` configured as [us.tractat.kuilt.raft.RaftRole.Leader] for unit
 *   tests, or use [MultiNodeRaftSim][us.tractat.kuilt.raft.test.MultiNodeRaftSim] with real
 *   nodes for consensus-correct cluster tests.
 * @param lazyFetch The all-or-nothing lazy-code-mobility bundle ([Creel] + [WasmRuntime] +
 *   `opToBobbin`). When `null` (a symbolic-only node), an op missing from [registry] leaves the
 *   task pending ("bobbin not loaded yet") and anti-entropy re-evaluates — today's behavior. When
 *   non-null, an unresolved op is **fetched** via a node-owned [BobbinExchange] over a reserved mux
 *   channel, **loaded** via [WasmRuntime], registered for reuse, and run. A verified-but-broken
 *   kernel (load or run failure) records a terminal-error [OpResult] rather than retrying forever.
 * @param target This peer's compilation [Target]. When non-null **and** a [lazyFetch] is present,
 *   a bobbin-backed op resolves the best compiled variant for this target per execution and tiers
 *   up when one gossips in (counted in [executionsCompiled]). When null, resolution is exactly the
 *   C5b lazy-fetch behaviour (no tiering). Required to be explicit — a platform's target is never
 *   guessed.
 */
public class WarpNode(
    public val selfId: PeerId,
    private val seam: Seam,
    rosterFlow: Flow<Set<PeerId>>,
    private val scope: CoroutineScope,
    quilterConfig: QuilterConfig = QuilterConfig(),
    private val clock: () -> Instant,
    private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    private val strategy: ClaimStrategy = ClaimStrategy.RingWithIntent(),
    private val registry: OpRegistry,
    private val coordinatedExecutor: suspend (TaskId) -> String = { taskId ->
        error("No coordinatedExecutor provided for task $taskId — supply one to WarpNode")
    },
    private val raftNode: RaftNode? = null,
    private val lazyFetch: WarpLazyFetch? = null,
    private val target: Target? = null,
) {
    private val replica = ReplicaId(selfId.value)

    /**
     * Owned child scope for **all** of this node's background coroutines — the seam fan-out,
     * the roster / queue / coordination collectors, the per-peer detectors, and every
     * claim/execute launch. Its [SupervisorJob] is a structural child of the injected [scope]
     * (so one failing coroutine neither tears down its siblings nor propagates to the caller's
     * scope), and [close] cancels it so a closed node goes fully inert.
     *
     * Launching into the injected [scope] directly (the pre-fix behaviour) let these collectors
     * outlive [close]: a roster emission arriving after the node's Quilters were closed drove a
     * claim into an already-closed Quilter, whose fail-fast [Quilter.apply] threw
     * `IllegalStateException` from a scope with no exception handler. That uncaught coroutine
     * exception was buffered by the coroutines-test exception collector and surfaced as an
     * `UncaughtExceptionsBeforeTest` against whichever `runTest` ran next (issue #1334).
     */
    private val ownJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + ownJob)

    /**
     * Broadcast bus for every raw inbound [Swatch] received on [seam].
     *
     * [WarpNode] is the **single collector** of [seam.incoming] (ADR-034). The init
     * block launches one coroutine that collects [seam.incoming] and emits each [Swatch]
     * here. All downstream consumers — the internal [MuxSeam] channels and per-peer
     * [HeartbeatPartitionDetector]s — subscribe to this flow rather than to
     * [seam.incoming] directly.
     *
     * Capacity 256 absorbs burst traffic before subscribers are scheduled. Frames emitted
     * before a subscriber starts are not replayed — for heartbeat liveness this is
     * acceptable; the next heartbeat cycle catches up.
     */
    internal val rawIncoming = MutableSharedFlow<Swatch>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // MuxSeam consumes a proxy seam whose incoming is rawIncoming, not seam.incoming
    // directly. This keeps seam.incoming single-collection: only the init fan-out loop
    // below ever collects it.
    private val mux = MuxSeam(RawIncomingProxy(seam, rawIncoming.asSharedFlow()), scope)
    private val queueSeam = mux.channel(CHANNEL_QUEUE)
    private val resultsSeam = mux.channel(CHANNEL_RESULTS)

    /**
     * Quilter replicating the pending task queue.
     *
     * Each entry maps a [TaskId] to a [LWWRegister] holding its [TaskDescriptor].
     * The descriptor is the unit of work that travels: it carries the op name and args
     * so claiming peers can resolve and execute the op locally.
     */
    private val queueQuilter: Quilter<ORMap<TaskId, LWWRegister<TaskDescriptor>>> = Quilter(
        replica = replica,
        seam = queueSeam,
        initial = ORMap.empty(),
        messageSerializer = QuiltMessage.serializer(
            ORMap.serializer(serializer<TaskId>(), LWWRegister.serializer(serializer<TaskDescriptor>()))
        ),
        scope = scope,
        config = quilterConfig,
        random = kotlin.random.Random(selfId.value.hashCode().toLong()),
    )

    /** Quilter replicating the results board. */
    private val resultsQuilter: Quilter<ORMap<TaskId, LWWRegister<OpResult>>> = Quilter(
        replica = replica,
        seam = resultsSeam,
        initial = ORMap.empty(),
        messageSerializer = QuiltMessage.serializer(
            ORMap.serializer(serializer<TaskId>(), LWWRegister.serializer(serializer<OpResult>()))
        ),
        scope = scope,
        config = quilterConfig,
        random = kotlin.random.Random(selfId.value.hashCode().toLong() xor 0x5555L),
    )

    private val intentSeam = mux.channel(CHANNEL_INTENT)

    /** Quilter replicating per-task claimant sets — the intent register. */
    private val intentQuilter: Quilter<ORMap<TaskId, GSet<PeerId>>> = Quilter(
        replica = replica,
        seam = intentSeam,
        initial = ORMap.empty(),
        messageSerializer = QuiltMessage.serializer(
            ORMap.serializer(serializer<TaskId>(), GSet.serializer(serializer<PeerId>()))
        ),
        scope = scope,
        config = quilterConfig,
        random = kotlin.random.Random(selfId.value.hashCode().toLong() xor 0xAAAAL),
    )

    private val coordQueueSeam = mux.channel(CHANNEL_COORD_QUEUE)

    /**
     * Quilter replicating the set of pending [CoordinationKind.Coordinated] task IDs.
     *
     * Kept entirely separate from [queueQuilter] so the coordination-free queue is
     * byte-for-byte unchanged. The ring owner drains from this queue, proposes to [raftNode],
     * and [onCoordinatedCommit] drives [coordinatedExecutor] from the committed log.
     */
    private val coordQueueQuilter: Quilter<ORSet<TaskId>> = Quilter(
        replica = replica,
        seam = coordQueueSeam,
        initial = ORSet.empty(),
        messageSerializer = QuiltMessage.serializer(ORSet.serializer(serializer<TaskId>())),
        scope = scope,
        config = quilterConfig,
        random = kotlin.random.Random(selfId.value.hashCode().toLong() xor 0xCCCCL),
    )

    /**
     * Node-owned lazy-bobbin exchange, present only when a [lazyFetch] capability was supplied.
     *
     * **Single-collection correct (ADR-034).** The exchange decorates the node's *own* [mux]
     * over a reserved channel ([CHANNEL_BOBBIN]) — it never collects [seam.incoming] a second
     * time. Handing [WarpNode] a ready-made [BobbinExchange] over the raw seam would create a
     * second collector; instead the node builds it here so every channel shares the node's one
     * event loop.
     */
    private val bobbinExchange: BobbinExchange? =
        lazyFetch?.let { BobbinExchange(mux.channel(CHANNEL_BOBBIN), it.creel, scope, quilterConfig) }

    // --- Shared mutable state (guarded by lock) ---
    private val lock = reentrantLock()

    /** Current consistent-hash ring, rebuilt whenever the effective roster changes. */
    private var ring: TaskRing = TaskRing(setOf(selfId))

    /** Wall-clock instant of the last effective-ring change. Guarded by [lock]. */
    private var lastRingChangeAt: Instant = Instant.fromEpochMilliseconds(0L)

    /** Task IDs we have already started executing; prevents double-execution on this node. */
    private val claimed = mutableSetOf<TaskId>()

    /**
     * Task IDs for which an [announceAndResolve] coroutine is currently in flight.
     *
     * Guarded by [lock]. Prevents duplicate settle coroutines spawned by repeated
     * [claimOwned] calls (triggered on every queue-state emission and ring rebuild) while
     * the first coroutine is waiting out the settle window. A task is added here when the
     * coroutine is launched and removed in the terminal `finally` block so a standing-down
     * task can be re-evaluated on a later [claimOwned] call.
     */
    private val inFlight = mutableSetOf<TaskId>()

    /**
     * Task IDs for which [coordinatedExecutor] has been invoked via [onCoordinatedCommit]
     * (or the leadership-acquisition re-drive's re-proposal thereof).
     *
     * Guarded by [lock]. Prevents double-execution when the same task's command commits
     * more than once — possible under warp-roster churn when two ring owners each proposed
     * the same task, or when a re-drive's re-proposal races the original entry.
     *
     * Only the quorum-fenced Raft leader calls [coordinatedExecutor] (checked in
     * [onCoordinatedCommit]), so this set is the local safety net for the same leader
     * seeing two committed entries for the same [TaskId]. Cross-node dedup rides the
     * replicated coordinated-queue removal and [Results]-board gates.
     */
    private val coordinatedApplied = mutableSetOf<TaskId>()

    /** Peers currently known from [rosterFlow]. Guarded by [lock]. */
    private var rosterPeers: Set<PeerId> = emptySet()

    /**
     * Peers detected as unresponsive or lost by their [HeartbeatPartitionDetector].
     *
     * The effective ring = `rosterPeers - partitionedPeers`. When a peer is added here
     * (on [PartitionEvent.PeerUnresponsive] or [PartitionEvent.PeerLost]), the ring is
     * rebuilt and [claimOwned] re-evaluates ownership so the partitioned peer's tasks
     * re-home to the successor. Removed on [PartitionEvent.PeerRecovered].
     *
     * Guarded by [lock].
     */
    private val partitionedPeers = mutableSetOf<PeerId>()

    /**
     * Per-peer umbrella [Job]s, keyed by [PeerId].
     *
     * Each entry is the [Job] returned by the top-level `scope.launch { }` that owns ALL
     * three coroutines belonging to a detector:
     * - the [HeartbeatPartitionDetector]'s ping loop (started via `detector.start(this)`)
     * - the [HeartbeatPartitionDetector]'s incoming-collection loop (ditto)
     * - the events-collector coroutine (`detector.events.collect { … }`)
     *
     * Because `detector.start(this)` is passed the same [CoroutineScope] as the outer
     * `launch` body, the two detector coroutines become structured children of the umbrella
     * Job. Calling `cancel()` on the umbrella Job atomically tears down all three.
     *
     * Storing only the events-collector Job was the previous (buggy) approach — it left
     * the detector's ping and incoming loops running as siblings in [scope] forever.
     *
     * Guarded by [lock].
     */
    private val detectorJobs = mutableMapOf<PeerId, Job>()

    // Monotonic timestamp counter for LWWRegister tags.
    private var timestampCounter = 0L

    // CRDT counters — guarded by [lock], incremented at the execution sites.
    private var _executions: GCounter = GCounter.ZERO
    private var _failovers: GCounter = GCounter.ZERO
    private var _duplicates: GCounter = GCounter.ZERO
    private var _executionsInterpreted: GCounter = GCounter.ZERO
    private var _executionsCompiled: GCounter = GCounter.ZERO

    /** Loaded ops keyed by the BobbinHash actually executed — lets the chosen tier change per
     *  execution without the OpId-registry short-circuit pinning the raw bobbin. Guarded by [lock]. */
    private val bobbinToOp = mutableMapOf<BobbinHash, Op>()

    /**
     * Cumulative count of tasks this node drove to completion — including terminal
     * failures recorded via [recordTerminalError]. A terminal [OpResult.failure]
     * (broken or malicious kernel) is a completion, not a retry: the task converges
     * to an error result and is not re-attempted.
     *
     * A [GCounter] snapshot — safe to merge with a remote peer's counter via
     * [GCounter.piece]. Use [us.tractat.kuilt.warp.otel.recordWarp] to forward
     * this into a [us.tractat.kuilt.otel.WarpMetricExporter] SUM series.
     */
    public val executions: GCounter get() = lock.withLock { _executions }

    /**
     * Cumulative count of partition-driven ring-rebuild events on this node.
     *
     * Incremented in [markPartitioned] — once per peer that triggers a failover.
     * A [GCounter] snapshot suitable for merging across replicas.
     */
    public val failovers: GCounter get() = lock.withLock { _failovers }

    /**
     * Cumulative count of task executions whose result was already present on the
     * Results board when [recordResult] was called — i.e. duplicates absorbed by
     * the LWW ORMap backstop.
     *
     * A non-zero value here indicates the dual-leader window or ring-disagreement
     * window was hit. A [GCounter] snapshot suitable for merging across replicas.
     */
    public val duplicates: GCounter get() = lock.withLock { _duplicates }

    /**
     * Cumulative count of task executions this node ran by **interpreting the raw bobbin** —
     * the un-tiered path. A [GCounter] snapshot; forward via [recordWarp] into a SUM series.
     *
     * Together with [executionsCompiled] this is a **subset** of [executions] — see that
     * property's note: a dashboard must not assume `interpreted + compiled == executed`.
     */
    public val executionsInterpreted: GCounter get() = lock.withLock { _executionsInterpreted }

    /**
     * Cumulative count of task executions this node ran on a **compiled variant** after tiering
     * up. Goes from 0 to ≥1 the first time a target-matching variant gossips in. The durable
     * tiered-compilation signal — the same counter measures real tiering once D4 lands a real
     * compiler. A [GCounter] snapshot; forward via [recordWarp] into a SUM series.
     *
     * `executionsInterpreted + executionsCompiled` is a **subset** of [executions]: it counts only
     * successful bobbin-backed executions (lazy-fetch present, `target != null`) and excludes
     * terminal-error executions and symbolic-registry / `target == null` runs — so a dashboard must
     * not assume `interpreted + compiled == executed`.
     */
    public val executionsCompiled: GCounter get() = lock.withLock { _executionsCompiled }

    // ---------------------------------------------------------------------------
    // Lifecycle — fan-out loop, roster, and queue changes
    // ---------------------------------------------------------------------------

    init {
        // Single collector of seam.incoming — fans every frame to rawIncoming.
        // All downstream consumers (MuxSeam channels, per-peer detectors)
        // subscribe to rawIncoming; nobody else collects seam.incoming.
        ownScope.launch {
            seam.incoming.collect { swatch ->
                rawIncoming.emit(swatch)
            }
        }

        // Rebuild the ring whenever the roster changes, then re-evaluate ownership.
        rosterFlow
            .onEach { peers -> onPeersChanged(peers) }
            .launchIn(ownScope)

        // Claim newly-owned free tasks whenever the pending map changes.
        queueQuilter.state
            .onEach { pendingMap -> claimOwned(pendingMap.keys, CoordinationKind.Free) }
            .launchIn(ownScope)

        // Claim newly-owned coordinated tasks whenever the coordinated pending set changes.
        coordQueueQuilter.state
            .onEach { pendingSet -> claimOwned(pendingSet.elements, CoordinationKind.Coordinated) }
            .launchIn(ownScope)

        // Drive coordinated execution from the committed Raft log. Only the quorum-fenced
        // Raft leader executes; [coordinatedApplied] and the replicated queue/results gates
        // prevent double-execution if two entries for the same task both committed.
        raftNode?.committed
            ?.onEach { committed -> onCoordinatedCommit(committed) }
            ?.launchIn(ownScope)

        // Re-drive coordinated tasks stranded by a commit that fired while no node held
        // leadership (#879 window b) whenever this node acquires leadership.
        raftNode?.role
            ?.onEach { role -> if (role is RaftRole.Leader) redriveStrandedCoordinated() }
            ?.launchIn(ownScope)
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Add a task to the distributed work queue on the [CoordinationKind.Free] path.
     *
     * The [descriptor] — carrying the op name and serialised args — is the unit of work
     * that travels: claiming peers resolve [TaskDescriptor.op] in their own [OpRegistry]
     * and run their registered copy locally. The function never crosses the fabric; only
     * its name and args do.
     *
     * The enqueue is replicated immediately to all current peers via the Quilter.
     * Idempotent at the ORMap level: a concurrent enqueue of the same [taskId] on
     * another peer survives merge (add-wins on the key; LWW picks one descriptor if
     * two concurrent enqueues race on the same ID).
     */
    public fun enqueue(taskId: TaskId, descriptor: TaskDescriptor) {
        lock.withLock {
            val ts = ++timestampCounter
            queueQuilter.apply(
                Patch(
                    queueQuilter.state.value.put(
                        replica = replica,
                        key = taskId,
                        value = LWWRegister.empty<TaskDescriptor>().set(replica, ts, descriptor),
                    )
                )
            )
        }
    }

    /**
     * Add a task to the work queue **pinned to this peer** — the headline pinned-execution
     * affordance.
     *
     * Pinning decouples *who-runs* from the consistent-hash ring: this node, and only this
     * node, claims and runs the task. No other peer ever executes it, and if this peer is
     * partitioned the task stays pending (it does **not** re-home to a survivor) until this
     * peer returns. This makes inherently data-local workloads expressible — e.g. federated
     * learning, where only the data owner can run its own training step on its private local
     * data — and doubles as task affinity / sticky placement.
     *
     * The task is enqueued with [TaskDescriptor.pinnedOwner] forced to [selfId]; any owner the
     * caller may have set on [descriptor] is overridden so "local" always means *this* peer.
     * It then travels and is claimed exactly like any free-path task via [enqueue] — the pin is
     * just the descriptor field the ring-owner filter consults (see [effectiveOwner]).
     *
     * @param taskId The identifier of the task to enqueue.
     * @param descriptor The unit of work; its [TaskDescriptor.pinnedOwner] is replaced with
     *   [selfId] before replication.
     */
    public fun enqueueLocal(taskId: TaskId, descriptor: TaskDescriptor) {
        enqueue(
            taskId,
            TaskDescriptor(
                op = descriptor.op,
                args = descriptor.args,
                traceparent = descriptor.traceparent,
                pinnedOwner = selfId,
            ),
        )
    }

    /**
     * Add [taskId] to the distributed work queue on the [CoordinationKind.Coordinated] path.
     *
     * Routes to the Raft-backed escalation path. The ring owner proposes the task to [raftNode]
     * for total-order delivery, then calls [coordinatedExecutor] exactly once per committed log
     * entry. The task is replicated in a separate queue so the free path is completely unaffected.
     *
     * Requires [raftNode] to be non-null — throws [IllegalStateException] immediately
     * if none was supplied to the constructor.
     *
     * Note: [CoordinationKind.Free] is not accepted here — use [enqueue(taskId, descriptor)]
     * for free-path tasks. A [TaskDescriptor] is required to carry the op name and args.
     *
     * Idempotent at the ORSet level: a concurrent add of the same ID on another peer
     * survives merge (add-wins).
     */
    public fun enqueue(taskId: TaskId, kind: CoordinationKind) {
        when (kind) {
            CoordinationKind.Free -> error(
                "WarpNode($selfId): use enqueue(taskId, descriptor) for free-path tasks — " +
                    "CoordinationKind.Free requires a TaskDescriptor carrying op and args"
            )
            CoordinationKind.Coordinated -> {
                checkNotNull(raftNode) {
                    "WarpNode($selfId): raftNode is required to enqueue coordinated tasks — " +
                        "no RaftNode was supplied at construction time"
                }
                coordQueueQuilter.apply(Patch(coordQueueQuilter.state.value.add(replica, taskId)))
            }
        }
    }

    /**
     * Publish a compiled bobbin **variant** through this node's own [BobbinExchange] so it gossips
     * to the mesh. This is what a *compiler node* calls after building a variant (in the spike, via
     * the fake compiler). Requires a [lazyFetch] capability — throws [IllegalStateException] otherwise,
     * fail-loud rather than silently dropping the variant.
     *
     * @return the [BobbinHash] of the published variant bytes.
     */
    public suspend fun publishVariant(bytes: ByteArray, variantOf: VariantKey): BobbinHash =
        checkNotNull(bobbinExchange) {
            "WarpNode($selfId): publishVariant requires a lazyFetch capability (no BobbinExchange)"
        }.putVariant(bytes, variantOf)

    /**
     * Register this node as a **compiler node**: installs the well-known [CompileOp.ID] op in
     * [registry] so `compile(sourceHash, target, optLevel)` requests reach this node as
     * ordinary **ring-dispatched tasks** rather than requiring a direct imperative
     * [publishVariant] call.
     *
     * Any peer enqueues a compile task with [CompileOp.descriptor]; when this node claims it,
     * the registered op:
     *
     * 1. decodes the [CompileRequest] from the task args,
     * 2. resolves the source bytes — from the local [Creel] if cached, otherwise lazily
     *    fetched from the mesh (bounded by [WarpLazyFetch.fetchTimeout]; a timeout throws,
     *    which unclaims the task so it is retried on a later cycle — transient, not terminal),
     * 3. invokes [compile] to build the variant bytes, and
     * 4. publishes them via [publishVariant] so the variant gossips to the mesh exactly as an
     *    imperative publish would.
     *
     * The task's result on the [results] board is the variant's [BobbinHash] hex value as
     * UTF-8 bytes — `compile(sourceHash, target, optLevel) → variantHash`.
     *
     * Requires a [lazyFetch] capability (the compiler must be able to fetch sources and
     * publish variants) — throws [IllegalStateException] otherwise, fail-loud like
     * [publishVariant]. Call once at startup; a duplicate registration throws (see
     * [OpRegistry.register]).
     *
     * @param compile Builds the variant bytes from the source kernel bytes for the requested
     *   [Target] and [OptLevel]. Must be deterministic and pure — same input, same output,
     *   same content hash on every compiler node.
     */
    public fun registerCompiler(
        compile: suspend (source: ByteArray, target: Target, optLevel: OptLevel) -> ByteArray,
    ) {
        val exchange = checkNotNull(bobbinExchange) {
            "WarpNode($selfId): registerCompiler requires a lazyFetch capability (no BobbinExchange)"
        }
        val fetchTimeout = checkNotNull(lazyFetch).fetchTimeout
        registry.register(
            CompileOp.ID,
            Op { args ->
                val request = CompileRequest.decode(args)
                val source = withTimeoutOrNull(fetchTimeout) { exchange.fetch(request.sourceHash) }
                    ?: error(
                        "WarpNode($selfId): compile source ${request.sourceHash.value} not served " +
                            "within $fetchTimeout — transient; the task is retried on a later cycle"
                    )
                val compiled = compile(source, request.target, request.optLevel)
                val variantKey = VariantKey(request.sourceHash, request.target, request.optLevel)
                publishVariant(compiled, variantKey).value.encodeToByteArray()
            },
        )
    }

    /**
     * A snapshot of the current results board, as seen by this peer.
     *
     * Each entry maps a [TaskId] to the raw [ByteArray] returned by [Op.invoke] (free path)
     * or the UTF-8 encoding of the string returned by the coordinated executor (coordinated
     * path). The board is eventually consistent: it reflects all results this peer has
     * received from the replication layer so far. All peers converge to the same board once
     * the network is quiescent.
     */
    public val results: Results<TaskId, OpResult>
        get() = Results.from(resultsQuilter.state.value)

    /**
     * The [TaskId]s currently holding an entry in the intent register, as seen by this peer.
     *
     * Test observability only: lets module tests assert that completed tasks' intent entries
     * are tombstoned and that coordinated tasks never write one (#873).
     */
    internal fun intentTaskIds(): Set<TaskId> = intentQuilter.state.value.keys

    /**
     * Close this node's Quilter connections and stop all detectors. Idempotent.
     */
    public fun close() {
        // Stop this node's own background coroutines FIRST, before closing the Quilters: cancelling
        // [ownJob] tears down the seam fan-out, the roster/queue/coordination collectors, the
        // per-peer detectors, and any in-flight claim/execute launches together. Ordering the
        // cancel ahead of the Quilter closes means no collector can drive a claim into a
        // Quilter that is about to be — or has just been — closed (issue #1334).
        ownJob.cancel()
        val jobs = lock.withLock {
            val snapshot = detectorJobs.values.toList()
            detectorJobs.clear()
            snapshot
        }
        jobs.forEach { it.cancel() }
        queueQuilter.close()
        resultsQuilter.close()
        intentQuilter.close()
        coordQueueQuilter.close()
    }

    // ---------------------------------------------------------------------------
    // Internal — ring management and task claiming
    // ---------------------------------------------------------------------------

    private fun onPeersChanged(newRoster: Set<PeerId>) {
        // A closed node is inert: swallow the trailing roster emission that structured
        // cancellation of [ownJob] has not yet delivered, so it cannot drive a claim into a
        // closed Quilter (issue #1334). [ownJob] cancellation stops the collector; this guard
        // closes the synchronous window between [close] and that cancellation taking effect.
        if (!ownJob.isActive) return
        val (added, removed) = lock.withLock {
            val prev = rosterPeers
            rosterPeers = newRoster
            val added = newRoster - prev - selfId
            val removed = prev - newRoster
            added to removed
        }

        // Cancel the umbrella Job for each departed peer. Cancelling the Job tears down
        // the detector's ping loop, its incoming-collection loop, and the events-collector
        // coroutine together — no coroutines leak into the parent scope.
        removed.forEach { peerId ->
            val job = lock.withLock {
                partitionedPeers.remove(peerId)
                detectorJobs.remove(peerId)
            }
            job?.cancel()
        }

        // Start detectors for newly-appeared peers.
        added.forEach { peerId -> startDetector(peerId) }

        rebuildRingAndClaim()
    }

    /**
     * Starts a [HeartbeatPartitionDetector] for [peerId] inside a dedicated umbrella coroutine.
     *
     * The detector receives a [PerPeerSeam] — a thin adapter that filters [rawIncoming]
     * to frames from [peerId] only and delegates sends to the underlying [seam]. This
     * lets the detector subscribe to per-peer ping/pong traffic without competing for
     * the single-consumer [seam.incoming] channel (which the init fan-out loop holds).
     *
     * `scope.launch { }` creates an umbrella [Job] that is a structured child of [scope].
     * `detector.start(this)` passes the umbrella coroutine's [CoroutineScope] to the
     * detector, so both the ping loop and the incoming-collection loop are launched as
     * children of the umbrella Job. The events-collector (`detector.events.collect { }`)
     * runs inside the same umbrella body. Cancelling the umbrella Job (via [detectorJobs])
     * atomically tears down all three coroutines. The umbrella Job is stored in [detectorJobs].
     */
    private fun startDetector(peerId: PeerId) {
        val perPeerSeam = PerPeerSeam(seam, peerId, rawIncoming)
        val detector = HeartbeatPartitionDetector(
            link = perPeerSeam,
            peerId = peerId,
            config = heartbeatConfig,
            clock = clock,
        )
        // The umbrella launch body is `this` CoroutineScope.
        // `detector.start(this)` makes the ping loop and incoming-collection loop children
        // of this coroutine so they are cancelled when this Job is cancelled.
        val umbrellaJob = ownScope.launch {
            detector.start(this)
            detector.events.collect { event -> handlePartitionEvent(event) }
        }
        lock.withLock { detectorJobs[peerId] = umbrellaJob }
    }

    private fun handlePartitionEvent(event: PartitionEvent) {
        when (event) {
            is PartitionEvent.PeerUnresponsive -> markPartitioned(event.peerId)
            is PartitionEvent.PeerLost -> markPartitioned(event.peerId)
            is PartitionEvent.PeerRecovered -> markRecovered(event.peerId)
        }
    }

    private fun markPartitioned(peerId: PeerId) {
        lock.withLock {
            partitionedPeers.add(peerId)
            _failovers = _failovers.piece(_failovers.inc(replica).delta)
        }
        logger.info { "WarpNode($selfId): peer $peerId partitioned — excluding from ring" }
        rebuildRingAndClaim()
    }

    private fun markRecovered(peerId: PeerId) {
        lock.withLock { partitionedPeers.remove(peerId) }
        logger.info { "WarpNode($selfId): peer $peerId recovered — restoring to ring" }
        rebuildRingAndClaim()
    }

    /**
     * Recomputes the effective ring from `rosterPeers - partitionedPeers` and re-evaluates
     * task ownership. Tasks whose former owner is now absent or partitioned re-home to
     * their new ring successor.
     */
    private fun rebuildRingAndClaim() {
        val effectiveRoster = lock.withLock { rosterPeers - partitionedPeers }
        val newRing = RosterSnapshot(effectiveRoster).toTaskRing()
        lock.withLock {
            ring = newRing
            lastRingChangeAt = clock()
        }
        claimOwned(queueQuilter.state.value.keys, CoordinationKind.Free)
        claimOwned(coordQueueQuilter.state.value.elements, CoordinationKind.Coordinated)
    }

    private fun claimOwned(taskIds: Collection<TaskId>, kind: CoordinationKind) {
        // Coordinated tasks bypass the intent register entirely (#873): "claiming" one only
        // *proposes* it to Raft, and consensus — the log plus the leader-side commit gates —
        // is the sole arbiter of who executes. Routing them through the intent path's ad-hoc
        // lowest-PeerId election was redundant with that arbitration and leaked one
        // never-tombstoned intent-map entry per coordinated task.
        if (kind is CoordinationKind.Coordinated) {
            claimOwnedRing(taskIds, kind)
            return
        }
        when (val s = strategy) {
            is ClaimStrategy.Ring -> claimOwnedRing(taskIds, kind)
            is ClaimStrategy.RingWithIntent -> claimOwnedWithIntent(taskIds, s, kind)
        }
    }

    /**
     * The owner of [taskId] honouring pinned execution.
     *
     * A free-path task whose [TaskDescriptor.pinnedOwner] is set is owned by exactly that
     * peer, decoupling who-runs from the consistent-hash ring; an absent pin (or a
     * coordinated task, which carries no descriptor) falls back to the ring assignment
     * `ring.owner(taskId)`. Because only the pinned owner passes the `== selfId` filter in
     * [claimOwnedRing] / [claimOwnedWithIntent], it is the sole claimant and the winner;
     * and if it is partitioned or absent from the effective roster, no node claims, so the
     * task stays pending and runs only when the owner returns (it never re-homes).
     *
     * Caller holds [lock].
     */
    private fun effectiveOwner(taskId: TaskId): PeerId? =
        queueQuilter.state.value[taskId]?.value?.pinnedOwner ?: ring.owner(taskId)

    /** Execute every owned, unclaimed task immediately on the given [kind] path. */
    private fun claimOwnedRing(taskIds: Collection<TaskId>, kind: CoordinationKind) {
        val toExecute = lock.withLock {
            taskIds
                .filter { taskId -> taskId !in claimed && effectiveOwner(taskId) == selfId }
                .also { tasks -> claimed.addAll(tasks) }
        }
        toExecute.forEach { taskId -> executeAsync(taskId, kind) }
    }

    private fun claimOwnedWithIntent(taskIds: Collection<TaskId>, strategy: ClaimStrategy.RingWithIntent, kind: CoordinationKind) {
        // Owned (or previously claimed — see [isBindingClaimant]), not-yet-claimed,
        // not-already-in-flight tasks under this peer's current ring view.
        // The inFlight check is a cheap early exit: announceAndResolve re-checks under the lock,
        // but filtering here avoids redundant lock acquisitions on every queue/ring emission.
        val owned = lock.withLock {
            taskIds.filter { taskId ->
                taskId !in claimed && taskId !in inFlight &&
                    (effectiveOwner(taskId) == selfId || isBindingClaimant(taskId))
            }
        }
        owned.forEach { taskId -> announceAndResolve(taskId, strategy, kind) }
    }

    /**
     * Whether this peer has announced an intent claim on [taskId] that it must follow
     * through on.
     *
     * Claims live in a grow-only [GSet]: once announced they cannot be retracted, and every
     * peer resolves the same [winner] — the lowest live claimant. A claim announced under a
     * transient ring view (the startup window where the seeded self-only ring says "mine"
     * before the first roster emission is processed) therefore keeps this peer in every
     * other peer's winner computation until the task's intent entry is tombstoned. If the
     * claimant simply dropped such a task once its ring converged (the pre-#931 behaviour),
     * the true ring owner would stand down to a winner that never executes — a permanent
     * standoff in which nobody runs the task. So a past claim is **binding**: the claimant
     * stays eligible in [claimOwnedWithIntent] and re-resolves on every queue/ring change,
     * executing iff it is the winner; losers still stand down deterministically.
     *
     * A task pinned to another peer is never followed through — the pin outranks both the
     * ring and the intent register (a foreign claim on a pinned task cannot arise from
     * [claimOwnedWithIntent], which honours the pin, but stays excluded defensively).
     *
     * Caller holds [lock].
     */
    private fun isBindingClaimant(taskId: TaskId): Boolean {
        val pinned = queueQuilter.state.value[taskId]?.value?.pinnedOwner
        if (pinned != null && pinned != selfId) return false
        return selfId in (intentQuilter.state.value[taskId]?.elements ?: emptySet())
    }

    /**
     * Announce this peer's claim to [taskId], then resolve the winner. Steady state executes
     * immediately; inside the disagreement window we wait [ClaimStrategy.RingWithIntent.settleWindow]
     * for competing claims, then execute only if this peer is the winner.
     *
     * At most one coroutine per task is in flight at a time — [inFlight] guards against the
     * duplicate launches that would otherwise arise from repeated [claimOwned] calls during
     * the settle window.
     *
     * The [kind] is carried through to [doExecute] so the correct executor ([executor] for
     * [CoordinationKind.Free] or [coordinatedExecutor] for [CoordinationKind.Coordinated])
     * is invoked at the leaf without any change to the claiming or settlement logic.
     */
    private fun announceAndResolve(taskId: TaskId, strategy: ClaimStrategy.RingWithIntent, kind: CoordinationKind) {
        // Skip if already settling — one coroutine per task is enough.
        val alreadyInFlight = lock.withLock { !inFlight.add(taskId) }
        if (alreadyInFlight) return

        // Announce (free): union selfId into the claimant set. ORMap.put is additive.
        lock.withLock {
            intentQuilter.apply(Patch(intentQuilter.state.value.put(replica, taskId, GSet.of(selfId))))
        }

        val mustSettle = lock.withLock {
            val sinceChange = clock() - lastRingChangeAt
            val competing = (intentQuilter.state.value[taskId]?.elements ?: emptySet()).any { it != selfId }
            sinceChange < strategy.settleWindow || competing
        }

        ownScope.launch {
            var stoodDown = false
            try {
                if (mustSettle) delay(strategy.settleWindow) // suspend OUTSIDE the lock
                val execute = lock.withLock {
                    if (taskId in claimed) return@withLock false
                    if (winner(taskId) == selfId) {
                        claimed.add(taskId)
                        true
                    } else {
                        false // stand down — stay eligible to re-home later
                    }
                }
                stoodDown = !execute
                if (execute) {
                    runCatchingCancellable { doExecute(taskId, kind) }
                        .onFailure { e ->
                            logger.warn(e) { "WarpNode($selfId): executor failed for $taskId — unclaiming" }
                            lock.withLock { claimed.remove(taskId) }
                        }
                }
            } finally {
                val followThrough = lock.withLock {
                    inFlight.remove(taskId)
                    // Re-evaluation is normally driven by queue emissions and ring rebuilds,
                    // but a trigger that fired *while this coroutine was still in flight*
                    // skipped the task (the inFlight guard). If that trigger was the last one
                    // — e.g. the first roster emission landed between the stand-down decision
                    // and this cleanup — the task would deadlock. Recheck once: relaunch only
                    // when this peer stood down yet is NOW the winner, so the relaunched
                    // resolution executes (or re-decides against fresher state). Executor
                    // failures are excluded — their retry stays on the next natural trigger,
                    // never a tight relaunch loop.
                    stoodDown && taskId !in claimed &&
                        (effectiveOwner(taskId) == selfId || isBindingClaimant(taskId)) &&
                        winner(taskId) == selfId
                }
                if (followThrough) claimOwned(listOf(taskId), kind)
            }
        }
    }

    /**
     * The winner of [taskId]: the lowest-`PeerId` claimant that is still in the effective
     * roster. Every peer computes the same value from the converged claimant set, so losers
     * deterministically stand down. Returns `null` if no live claimant remains.
     */
    private fun winner(taskId: TaskId): PeerId? {
        // Caller holds [lock].
        val claimants = intentQuilter.state.value[taskId]?.elements ?: emptySet()
        val effectiveRoster = rosterPeers - partitionedPeers
        return claimants.intersect(effectiveRoster).minByOrNull { it.value }
    }

    private fun executeAsync(taskId: TaskId, kind: CoordinationKind) {
        ownScope.launch {
            runCatchingCancellable { doExecute(taskId, kind) }
                .onFailure { e ->
                    logger.warn(e) { "WarpNode($selfId): executor failed for $taskId — unclaiming" }
                    lock.withLock { claimed.remove(taskId) }
                }
        }
    }

    /**
     * Invoke the correct executor for [taskId] based on [kind], record the result, and
     * remove the task from its queue.
     *
     * [CoordinationKind.Free] resolves the [TaskDescriptor] from the queue map, looks up
     * [TaskDescriptor.op] in [registry], and invokes it with [TaskDescriptor.args]. A null
     * descriptor (CRDT replication race) or an unresolved op ("bobbin not loaded yet") causes
     * early return with the task unclaimed — anti-entropy retries on the next cycle.
     *
     * [CoordinationKind.Coordinated] only *proposes* to [raftNode] — it does **not**
     * invoke [coordinatedExecutor] here. Execution happens asynchronously in
     * [onCoordinatedCommit] when the committed log entry is observed by the Raft leader,
     * behind the [RaftNode.readIndex] quorum fence — so exactly one quorum-confirmed
     * leader fires [coordinatedExecutor] per committed entry.
     */
    private suspend fun doExecute(taskId: TaskId, kind: CoordinationKind) {
        when (kind) {
            CoordinationKind.Free -> {
                val result = executeViaRegistry(taskId) ?: return
                recordResult(taskId, result)
                removeFromQueue(taskId, kind)
                lock.withLock { _executions = _executions.piece(_executions.inc(replica).delta) }
            }
            CoordinationKind.Coordinated -> executeViaRaft(taskId)
            // Execution, result recording, and queue removal for Coordinated tasks
            // are handled by onCoordinatedCommit on the Raft leader.
        }
    }

    /**
     * Resolve the [TaskDescriptor] for [taskId], look up its op in [registry], invoke it,
     * and return the [ByteArray] result.
     *
     * **Null descriptor:** if the descriptor is missing from the queue state (e.g. a CRDT
     * replication race during which the map entry has not yet arrived), the task is unclaimed
     * and `null` is returned. This is a transient condition — the Quilter's anti-entropy will
     * deliver the descriptor on the next cycle and [claimOwned] will re-evaluate.
     *
     * **Unresolved op:** if [registry] has no entry for [TaskDescriptor.op] the outcome depends
     * on whether a [lazyFetch] capability is present:
     *
     * - **[lazyFetch] present:** the op is fetched via the node-owned [BobbinExchange]
     *   ([BobbinExchange.fetch] suspends until a peer serves the bytes), **bounded** by
     *   [WarpLazyFetch.fetchTimeout], loaded under [WasmRuntime.load], registered in [registry]
     *   for reuse, and invoked. A [WasmException] at either load or run time is **terminal** — it
     *   records an [OpResult.failure] that converges on every peer and removes the task from the
     *   queue; the task is never retried.
     * - **[lazyFetch] absent, [WarpLazyFetch.opToBobbin] returns null, the fetch has not yet been
     *   served, or the fetch exceeds [WarpLazyFetch.fetchTimeout]:** the task is unclaimed and
     *   `null` is returned (stand-by). A fetch timeout is **transient, not terminal** — an absent
     *   bobbin may still be in flight from a peer that has not finished joining — so it joins the
     *   "bobbin not loaded yet" state and anti-entropy re-evaluates on the next cycle, rather than
     *   hanging forever holding the claim or permanently failing the task on every peer.
     */
    private suspend fun executeViaRegistry(taskId: TaskId): ByteArray? {
        val descriptor = queueQuilter.state.value[taskId]?.value ?: run {
            logger.debug { "WarpNode($selfId): no descriptor for $taskId — unclaiming (CRDT replication race; anti-entropy will retry)" }
            lock.withLock { claimed.remove(taskId) }
            return null
        }
        // Symbolic op already in the registry: run it directly (non-tiered path, unchanged).
        registry.resolve(descriptor.op)?.let { op ->
            return runOpOrTerminal(taskId, op, descriptor.args)
        }

        val lf = lazyFetch ?: return standBy(taskId, descriptor.op)
        val source = lf.opToBobbin(descriptor.op) ?: return standBy(taskId, descriptor.op)

        // Tiering disabled (target == null): preserve C5b behaviour — load once, register under OpId.
        if (target == null) {
            val bytes = withTimeoutOrNull(lf.fetchTimeout) { checkNotNull(bobbinExchange).fetch(source) }
                ?: return standBy(taskId, descriptor.op) // no peer served the bytes in time — transient
            val loaded = try {
                lf.runtime.load(bytes)
            } catch (e: WasmException) {
                return recordTerminalError(taskId, e) // verified bytes, but broken/malicious — terminal
            }
            val op = registerOrResolve(descriptor.op, loaded)
            return runOpOrTerminal(taskId, op, descriptor.args)
        }

        // Tiering enabled: resolve best variant per execution, cache loaded ops by BobbinHash.
        val hash = bestBobbin(source)
        val cached = lock.withLock { bobbinToOp[hash] }
        val op = cached ?: run {
            // suspends, outside lock — bounded by fetchTimeout; a timeout stands by (transient).
            val bytes = withTimeoutOrNull(lf.fetchTimeout) { checkNotNull(bobbinExchange).fetch(hash) }
                ?: return standBy(taskId, descriptor.op)
            val loaded = try {
                lf.runtime.load(bytes)
            } catch (e: WasmException) {
                return recordTerminalError(taskId, e) // verified bytes, but broken/malicious — terminal
            }
            lock.withLock { bobbinToOp.getOrPut(hash) { loaded } }
        }
        val isCompiled = hash != source
        val result = runOpOrTerminal(taskId, op, descriptor.args) ?: return null
        lock.withLock {
            if (isCompiled) {
                _executionsCompiled = _executionsCompiled.piece(_executionsCompiled.inc(replica).delta)
            } else {
                _executionsInterpreted = _executionsInterpreted.piece(_executionsInterpreted.inc(replica).delta)
            }
        }
        return result
    }

    /**
     * Invoke [op] with [args], returning its [ByteArray] result. A [WasmException] (trap/timeout
     * at run time) is **terminal** — it records an [OpResult.failure] via [recordTerminalError]
     * and returns `null` so the caller short-circuits. Never swallows [WasmException] silently.
     */
    private suspend fun runOpOrTerminal(taskId: TaskId, op: Op, args: ByteArray): ByteArray? =
        try {
            op.invoke(args)
        } catch (e: WasmException) {
            recordTerminalError(taskId, e) // trap/timeout at run time — terminal
        }

    /**
     * The best bobbin to run for this node's [target]: the highest-[OptLevel] compiled variant of
     * [source] advertised on the manifest, or [source] itself when none exists.
     *
     * [source] is the op's already-resolved raw bobbin hash, passed by the caller — this never
     * re-derives it via [WarpLazyFetch.opToBobbin]. Only ever called from the tiering branch where
     * [target] is non-null; a null [target] returns [source] unchanged.
     */
    private fun bestBobbin(source: BobbinHash): BobbinHash {
        val t = target ?: return source
        val variants = bobbinExchange?.manifest?.value.orEmpty()
            .mapNotNull { meta -> meta.variantOf?.let { key -> meta to key } }
            .filter { (_, key) -> key.sourceHash == source && key.target == t }
        val best = variants.maxByOrNull { (_, key) -> key.optLevel.ordinal }
        return best?.first?.hash ?: source
    }

    /**
     * Stand by on an unresolved op — the transient "bobbin not loaded yet" state. The task is
     * unclaimed and left pending; anti-entropy re-evaluates on the next cycle. Reached when no
     * [lazyFetch] capability is configured, or [WarpLazyFetch.opToBobbin] names no bobbin for the
     * op (nothing to fetch). Distinct from a terminal error: we simply *can't run it yet*.
     */
    private fun standBy(taskId: TaskId, op: OpId): ByteArray? {
        logger.debug { "WarpNode($selfId): op '${op.value}' not resolvable for $taskId — standing by (transient; anti-entropy will retry)" }
        lock.withLock { claimed.remove(taskId) }
        return null
    }

    /**
     * Registers [loaded] under [op] so subsequent tasks resolve it locally, returning the [Op]
     * now in the registry. If a concurrent fetch-load on another coroutine won the register race
     * ([OpRegistry.register] throws [IllegalStateException] on a duplicate), the winner's [Op] is
     * resolved and used instead.
     */
    private fun registerOrResolve(op: OpId, loaded: Op): Op =
        try {
            registry.register(op, loaded)
            loaded
        } catch (e: IllegalStateException) {
            registry.resolve(op) ?: throw e
        }

    /**
     * Proposes the task to the Raft cluster with a stable [requestId] derived from [taskId].
     *
     * The call suspends until a quorum has committed the log entry, guaranteeing durability.
     * [coordinatedExecutor] is **not** invoked here — execution is driven from the committed
     * log in [onCoordinatedCommit] on the current Raft leader. This separation prevents the
     * double-execution that occurred when two ring owners both called `coordinatedExecutor`
     * inline after their respective `propose()` calls returned under warp-roster churn.
     *
     * The stable [requestId] (hash of [TaskId.value]) ensures that if this node retries the
     * same proposal after a leadership failover, the Raft dedup table recognises the serial
     * and avoids appending a duplicate entry under this node's own client identity.
     */
    private suspend fun executeViaRaft(taskId: TaskId) {
        val requestId = taskId.value.hashCode().toLong()
        checkNotNull(raftNode).propose(taskId.value.encodeToByteArray(), requestId)
        // Execution driven from raftNode.committed in onCoordinatedCommit.
    }

    /**
     * Called for each [Committed] event emitted by [raftNode]. Executes a coordinated task
     * exactly once across the cluster per committed entry.
     *
     * **The execution gates, in order:**
     * - Only a node that believes it is the current Raft leader proceeds (cheap pre-filter;
     *   a follower/candidate never executes — an entry stranded by that filter firing on
     *   every node mid-election is recovered by [redriveStrandedCoordinated]).
     * - The **quorum fence** (#879 window a): [RaftNode.readIndex] confirms this node still
     *   holds a voter quorum at its current term before anything runs. A deposed-but-unaware
     *   leader cannot pass it — any quorum it could assemble intersects the majority that
     *   elected its successor at a higher term, and that intersection rejects the stale-term
     *   round — so it fails with [us.tractat.kuilt.raft.NotLeaderException] /
     *   [us.tractat.kuilt.raft.LeadershipLostException] and stands down without executing.
     * - [coordinatedApplied] plus queue-membership and [Results]-board checks (under [lock])
     *   prevent re-execution when two entries for the same [TaskId] committed — warp-roster
     *   churn double-propose, or a re-drive re-proposal racing the original entry.
     * - [Committed.Install] (snapshot installs) are ignored — coordinated tasks are not part
     *   of the persistent Raft state machine.
     */
    private fun onCoordinatedCommit(committed: Committed) {
        val entry = (committed as? Committed.Entry)?.entry ?: return
        val raft = raftNode ?: return
        if (raft.role.value !is RaftRole.Leader) return

        val taskId = runCatchingCancellable { TaskId(entry.command.decodeToString()) }.getOrNull() ?: return

        // Quick gate before paying for a quorum round: skip entries whose task has already
        // been executed here or is no longer queued. Re-checked under the lock after the fence.
        val worthFencing = lock.withLock {
            taskId in coordQueueQuilter.state.value.elements && taskId !in coordinatedApplied
        }
        if (!worthFencing) return

        ownScope.launch {
            val fence = runCatchingCancellable { raft.readIndex() }
            if (fence.isFailure) {
                logger.debug {
                    "WarpNode($selfId): quorum fence failed for $taskId — deposed; standing down " +
                        "(${fence.exceptionOrNull()?.message})"
                }
                return@launch
            }
            val shouldExecute = lock.withLock {
                taskId in coordQueueQuilter.state.value.elements &&
                    resultsQuilter.state.value[taskId] == null &&
                    coordinatedApplied.add(taskId)
            }
            if (!shouldExecute) return@launch
            runCatchingCancellable {
                val result = coordinatedExecutor(taskId).encodeToByteArray()
                recordResult(taskId, result)
                removeFromQueue(taskId, CoordinationKind.Coordinated)
                lock.withLock { _executions = _executions.piece(_executions.inc(replica).delta) }
            }.onFailure { e ->
                logger.warn(e) { "WarpNode($selfId): coordinatedExecutor failed for $taskId — resetting" }
                lock.withLock { coordinatedApplied.remove(taskId) }
            }
        }
    }

    /**
     * Re-drives coordinated tasks stranded by a commit that fired while no node held
     * leadership (#879 window b). Launched on every transition of [raftNode]'s role into
     * [RaftRole.Leader].
     *
     * [RaftNode.committed] is replay=0: an entry whose commit every node consumed as a
     * `Follower`/`Candidate` (mid-election) is skipped by every [onCoordinatedCommit] role
     * check and never re-fires. On acquiring leadership this node therefore **re-proposes**
     * every task still in the replicated coordinated queue that it has not executed and that
     * has no replicated result — giving the stranded task a fresh committed entry to fire
     * from. Execution stays solely in [onCoordinatedCommit]: the re-proposal flows through
     * the same fence and dedup gates, which keep the task exactly-once when the original
     * entry also committed (the gates skip whichever entry fires second).
     *
     * Fenced by [RaftNode.readIndex] like execution itself: a node that lost leadership
     * between the role emission and the scan stands down; the fence on a fresh leader also
     * waits for its current-term no-op to commit, so the scan runs against a settled view.
     * A re-propose failure (e.g. leadership lost mid-re-drive) is logged and dropped — the
     * next leader's re-drive picks the task up again.
     */
    private fun redriveStrandedCoordinated() {
        val raft = raftNode ?: return
        ownScope.launch {
            val fence = runCatchingCancellable { raft.readIndex() }
            if (fence.isFailure) return@launch
            val stranded = lock.withLock {
                coordQueueQuilter.state.value.elements.filter { taskId ->
                    taskId !in coordinatedApplied && resultsQuilter.state.value[taskId] == null
                }
            }
            stranded.forEach { taskId ->
                logger.info { "WarpNode($selfId): re-driving stranded coordinated task $taskId" }
                runCatchingCancellable { executeViaRaft(taskId) }
                    .onFailure { e ->
                        logger.debug { "WarpNode($selfId): re-drive propose failed for $taskId: ${e.message}" }
                    }
            }
        }
    }

    private fun recordResult(taskId: TaskId, result: ByteArray) = putResult(taskId, OpResult(result))

    /**
     * Records a terminal-error [OpResult] for [taskId] and removes it from the Free queue.
     *
     * A *verified* kernel that fails to load (imports/oversize/malformed) or fails to run
     * (trap/timeout) will never succeed, so retrying it forever via anti-entropy is exactly the
     * churn the OpResult/Quilter storm guardrail warns against. The failure is therefore
     * **terminal**: an [OpResult.failure] entry converges on every peer (diagnosable, no hot
     * loop) and the task leaves the queue.
     *
     * Composes with [doExecute]'s Free branch: this records the result, removes the task, and
     * bumps [_executions] itself, then returns `null` so the caller's `?: return` short-circuits
     * without recording a second time.
     */
    private fun recordTerminalError(taskId: TaskId, e: WasmException): ByteArray? {
        putResult(taskId, OpResult.failure(e.message ?: e.toString()))
        removeFromQueue(taskId, CoordinationKind.Free)
        lock.withLock { _executions = _executions.piece(_executions.inc(replica).delta) }
        return null
    }

    private fun putResult(taskId: TaskId, opResult: OpResult) {
        // Hold the WarpNode lock across the read-compute-apply so every concurrent
        // executor coroutine mints a *unique* dot from the latest state. Without this,
        // two concurrent `put` calls on the same base state each call `nextDot(replica)`,
        // producing duplicate dots. When those patches are joined, the causal CRDT
        // tombstoning logic silently drops one entry (the entry whose dot the other's
        // context already witnesses). Bug: concurrent result recordings were losing
        // results non-deterministically.
        lock.withLock {
            // A non-null existing entry means a peer (or this node in the dual-leader window)
            // already recorded a result for this task. The LWW ORMap backstop absorbs this
            // duplicate; the counter tracks how often the backstop fires.
            if (resultsQuilter.state.value[taskId] != null) {
                _duplicates = _duplicates.piece(_duplicates.inc(replica).delta)
            }
            val ts = ++timestampCounter
            resultsQuilter.apply(
                Patch(
                    resultsQuilter.state.value.put(
                        replica = replica,
                        key = taskId,
                        value = LWWRegister.empty<OpResult>().set(replica, ts, opResult),
                    )
                )
            )
        }
    }

    private fun removeFromQueue(taskId: TaskId, kind: CoordinationKind) {
        // Hold the WarpNode lock so concurrent `remove` calls always operate on the
        // latest state. Without this, two concurrent removes on the same base state
        // would each produce a `remove` delta, both of which are correct (idempotent),
        // but the key invariant below is that the state read happens atomically with
        // the apply so the delta is always a subset of the current state.
        lock.withLock {
            when (kind) {
                CoordinationKind.Free -> {
                    queueQuilter.apply(Patch(queueQuilter.state.value.remove(taskId)))
                    intentQuilter.apply(Patch(intentQuilter.state.value.remove(taskId)))
                }
                CoordinationKind.Coordinated -> {
                    coordQueueQuilter.apply(Patch(coordQueueQuilter.state.value.remove(taskId)))
                    // Coordinated tasks no longer write intent entries (#873), but reclaim any
                    // stray entry a peer running a pre-#873 build may have replicated to us —
                    // a no-op delta when the key is absent.
                    intentQuilter.apply(Patch(intentQuilter.state.value.remove(taskId)))
                }
            }
        }
    }

    private companion object {
        const val CHANNEL_QUEUE: Byte = 0x01
        const val CHANNEL_RESULTS: Byte = 0x02

        /** Mux-channel tag reserved for per-peer heartbeat ping/pong frames. */
        @Suppress("unused")
        const val CHANNEL_HEARTBEAT: Byte = 0x03

        const val CHANNEL_INTENT: Byte = 0x04

        /** Mux-channel for the [CoordinationKind.Coordinated] task queue. */
        const val CHANNEL_COORD_QUEUE: Byte = 0x05

        /** Mux-channel reserved for the node-owned [BobbinExchange] (lazy fetch-and-run). */
        const val CHANNEL_BOBBIN: Byte = 0x06
    }
}

/**
 * A thin [Seam] adapter that replaces [incoming] with a caller-supplied [SharedFlow].
 *
 * Used by [WarpNode] to satisfy the single-collection contract (ADR-034): [WarpNode]
 * collects [delegate.incoming] exactly once and fans every [Swatch] to [rawIncoming];
 * [MuxSeam] then subscribes to [rawIncoming] via this proxy, never touching
 * [delegate.incoming] a second time.
 *
 * All other [Seam] members delegate to [delegate] unchanged.
 */
private class RawIncomingProxy(
    private val delegate: Seam,
    override val incoming: Flow<Swatch>,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override suspend fun broadcast(payload: ByteArray) = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = delegate.sendTo(peer, payload)
    override suspend fun close(reason: CloseReason) = delegate.close(reason)
}

/**
 * A thin [Seam] view that presents only frames from [targetPeerId] via [rawIncoming].
 *
 * [HeartbeatPartitionDetector] subscribes to [incoming] to process pings/pongs for a
 * specific peer. Since [Seam.incoming] is a channel-backed flow (single-consumer), we
 * cannot let every detector collect it directly. Instead, [WarpNode]'s init block fans
 * each inbound swatch to [rawIncoming] (a [MutableSharedFlow]) and each [PerPeerSeam]
 * filters to its assigned [targetPeerId].
 *
 * [broadcast] and [sendTo] delegate to [delegate] unchanged — heartbeat pings/pongs are
 * sent as raw frames on the underlying seam, bypassing [MuxSeam] channel wrapping. This
 * matches how [us.tractat.kuilt.session.SeamRoom] wires its per-peer detectors.
 *
 * [close] is a no-op — the [PerPeerSeam] does not own the link lifecycle.
 */
private class PerPeerSeam(
    private val delegate: Seam,
    private val targetPeerId: PeerId,
    private val rawIncoming: MutableSharedFlow<Swatch>,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state

    override val incoming: Flow<Swatch>
        get() = rawIncoming.filter { it.sender == targetPeerId }

    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = delegate.sendTo(peer, payload)

    /** No-op — lifecycle is owned by [WarpNode], not this view. */
    override suspend fun close(reason: CloseReason) = Unit
}

// ---------------------------------------------------------------------------
// Roster source adapters
// ---------------------------------------------------------------------------

/**
 * Returns a [Flow] of the current peer set derived from [Seam.peers], suitable for
 * passing to [WarpNode] as its [rosterFlow].
 *
 * This is the **eventual** roster source: the ring tracks who is currently connected
 * to the seam. Under hub-spoke / churn the seam's peer view differs between nodes,
 * introducing some ring disagreement and duplicate executions (backstopped by the
 * Results ORMap). Use [RaftNode.rosterSnapshot] for a strongly-consistent alternative.
 */
public fun Seam.rosterSnapshot(): Flow<Set<PeerId>> = peers

/**
 * Returns a [Flow] of voter [PeerId]s derived from this [RaftNode]'s current
 * [us.tractat.kuilt.raft.ClusterConfig], suitable for passing to [WarpNode] as its [rosterFlow].
 *
 * This is the **strong** roster source: the ring tracks the agreed Raft voter set.
 * Under stable membership the ring is identical on every peer, eliminating duplicate
 * executions from ring disagreement. Use [Seam.rosterSnapshot] for a cheaper,
 * eventually-consistent alternative when you don't already have a Raft cluster.
 *
 * The emitted [PeerId] values are derived from [us.tractat.kuilt.raft.NodeId.value] by
 * wrapping each in a [PeerId] — callers must ensure the [WarpNode.selfId] passed to
 * [WarpNode] matches the corresponding voter's [us.tractat.kuilt.raft.NodeId.value].
 */
public fun RaftNode.rosterSnapshot(): Flow<Set<PeerId>> =
    membership.map { config -> config.voters.mapTo(mutableSetOf()) { PeerId(it.value) } }
