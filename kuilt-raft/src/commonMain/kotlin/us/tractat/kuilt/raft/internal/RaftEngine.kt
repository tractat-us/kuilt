@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft.internal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientIdCollisionException
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.ConfigPayload
import us.tractat.kuilt.raft.CorruptDurableStateException
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.DenyReason
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LeadershipTransferAbandonReason
import us.tractat.kuilt.raft.LeadershipTransferException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.MembershipChangeInProgressException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.NotLeaderException
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftMetric
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.RefusalGate
import us.tractat.kuilt.raft.Snapshot
import us.tractat.kuilt.raft.SnapshotMeta
import us.tractat.kuilt.raft.StepDownReason
import us.tractat.kuilt.raft.asCommitted
import kotlin.time.Duration
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger("us.tractat.kuilt.raft.RaftEngine")

/**
 * Codec for the [RaftMessage] wire envelope. [Cbor.ignoreUnknownKeys] is `true` so a peer running an
 * OLDER build tolerates a field a NEWER peer added — e.g. [RaftMessage.RequestVote.leadershipTransfer].
 * Without it the default `Cbor` throws on the unknown key and the transport-collect coroutine (which
 * only guards [ClosedSendChannelException]) would take the node's scope down. Adding a defaulted field
 * to any `RaftMessage` is therefore forward- and backward-compatible: an old peer that receives it just
 * drops the field and behaves as if it were absent (for the disrupt flag: denies under stickiness — the
 * correct graceful degradation). [Cbor.encodeDefaults] stays at its default `false`, so a new peer with
 * `leadershipTransfer = false` omits the field entirely and the byte stream is unchanged for old peers.
 */
private val raftCbor = Cbor { ignoreUnknownKeys = true }

/**
 * How long a run of refused leader→peer frames has to get before [noteRefusedLeaderFrame]
 * calls it a wedge (#1898).
 *
 * It has to clear the longest *benign* run, which is the leader-removal transient: a leader
 * this node has already stopped counting as a voter (membership is adopt-on-append, so an
 * uncommitted config entry already arms the §5.2 gate) keeps heartbeating until it learns it
 * is out, and every one of those frames is refused. That window is bounded by an election
 * timeout and the frames arrive at `heartbeatInterval`, so its length is
 * `electionTimeoutMax / heartbeatInterval` — 6 at [RaftConfig]'s defaults (300 ms / 50 ms),
 * and 5 under the raft suite's `fastRaftConfig` (10 ms / 2 ms).
 *
 * 64 is an order of magnitude clear of both, which is the right shape of margin for a
 * threshold whose false positive is a misleading operator diagnosis. It costs nothing at the
 * other end: a genuine wedge refuses a frame per heartbeat *forever*, so the report is late by
 * `64 × heartbeatInterval` — about 3 s at the defaults — against a condition that never clears.
 *
 * A count rather than a duration, deliberately: it scales with the leader's own retry cadence,
 * which is the cadence "sustained" is measured in. Not on [RaftConfig] because there is no
 * deployment for which a different value is right — the quantity it is calibrated against is
 * itself derived from the config.
 */
internal const val WEDGE_SUSPECTED_RUN: Int = 64

internal class RaftEngine(
    private val bootstrapConfig: ClusterConfig,
    private val transport: RaftTransport,
    private val storage: RaftStorage,
    private val raftConfig: RaftConfig,
    private val scope: CoroutineScope,
    private val onMetric: ((RaftMetric) -> Unit)?,
    identity: ClientIdentity = ClientIdentity.Auto,
) : RaftNode {

    // ── Raft §8 client-serial dedup ───────────────────────────────────────────
    /** Whether the caller supplied a stable durable id (collision ⇒ fail loud) vs an auto id (re-mint). */
    private val isDurableId: Boolean = identity is ClientIdentity.Durable

    /** This node's dedup identity. `var` because an auto id re-mints on a detected collision. */
    private var myClientId: ClientId = when (identity) {
        is ClientIdentity.Durable -> identity.clientId
        ClientIdentity.Auto -> ClientId.auto(transport.selfId, raftConfig.random)
    }

    /** Monotonic per-client serial for the auto [propose] form. Confined to the actor loop. */
    private var serial: Long = 0L

    /** Best-effort, non-durable leader-side dedup of recently-committed proposals. */
    private val dedupCache = LeaderDedupCache()

    /** Detects another live writer committing under [myClientId]. Replaced on auto-id re-mint. */
    private var collisions = CollisionDetector(myClientId)

    private val cmd = Channel<EngineCommand>(Channel.UNLIMITED)

    private val _role = MutableStateFlow<RaftRole>(RaftRole.Follower)
    override val role: StateFlow<RaftRole> = _role.asStateFlow()

    private val _leader = MutableStateFlow<NodeId?>(null)
    override val leader: StateFlow<NodeId?> = _leader.asStateFlow()

    private val _commitIndex = MutableStateFlow(0L)
    override val commitIndex: StateFlow<Long> = _commitIndex.asStateFlow()

    private val _membership = MutableStateFlow(bootstrapConfig)
    override val membership: StateFlow<ClusterConfig> = _membership.asStateFlow()

    /**
     * Emits every committed [Committed] in index order. The overflow policy is [BufferOverflow.SUSPEND]
     * so the actor backpressures rather than silently dropping entries. Callers that fall behind will
     * slow the cluster — this is the correct trade-off for a consensus log where every entry must be
     * delivered.
     */
    private val _committed = MutableSharedFlow<Committed>(
        extraBufferCapacity = Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val committed: Flow<Committed> = _committed

    override val snapshots = MutableStateFlow<Snapshot?>(null)

    private val _compactionFloor = MutableStateFlow(0L)
    override val compactionFloor: StateFlow<Long> = _compactionFloor.asStateFlow()

    override fun committedFrom(fromIndex: Long): Flow<Committed> = flow {
        coroutineScope {
            // Subscribe to the live tail BEFORE the actor captures the cut. UNDISPATCHED
            // runs the collector synchronously up to its first suspension (subscriber
            // registration), so by the time we send CommitCut we're guaranteed registered
            // — no entry committed after the cut can slip through the gap.
            val buffer = Channel<Committed>(Channel.UNLIMITED)
            val tail = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    _committed.collect { buffer.send(it) }
                } finally {
                    buffer.close()
                }
            }
            val result = CompletableDeferred<CommitCutResult>()
            // The engine command channel is closed by actor teardown (the node's scope was cancelled, or
            // a Close command was processed). A committedFrom collection that races that teardown must end
            // cleanly rather than leak a raw ClosedSendChannelException to its collector (#1465): the leak
            // was this `cmd.send` inside the coroutineScope block — NOT the tail's `buffer.send`, which
            // can never send-after-close within its own coroutine. trySend never suspends on the UNLIMITED
            // cmd channel; isClosed ⇒ the node is gone, so cancel the live-tail subscriber and finish the
            // (empty) stream.
            if (cmd.trySend(EngineCommand.CommitCut(fromIndex, result)).isClosed) {
                tail.cancel()
                return@coroutineScope
            }
            val cut = result.await()
            cut.install?.let { emit(Committed.Install(it)) }
            cut.replay.forEach { emit(it) }
            // Tail live, deduped against the replayed prefix: anything at or below cutIndex was
            // already replayed. An Install is never index-deduped — it is a reset, not a position.
            for (committed in buffer) {
                when (committed) {
                    is Committed.Entry -> if (committed.entry.index > cut.cutIndex) emit(committed)
                    is Committed.Internal -> if (committed.index > cut.cutIndex) emit(committed)
                    is Committed.Install -> emit(committed)
                }
            }
            tail.cancel()
        }
    }

    private var traceClock = 0L
    private val _trace = MutableSharedFlow<RaftTraceEvent>(
        extraBufferCapacity = 512,
        // trace is losable debug data — drop oldest on overflow rather than backpressuring consensus
        // (contrast with _committed above, which SUSPENDs to guarantee delivery)
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val trace: Flow<RaftTraceEvent> = _trace

    // ── Shared consensus core (see [RaftState], the `state` field) ────────────
    // Its `membershipState` is named to avoid shadowing the `membership: StateFlow<ClusterConfig>` override.

    // ── Peer-set helpers ─────────────────────────────────────────────────────

    /**
     * Voters other than self — the RequestVote recipients.
     * Joint: union of both voter sets minus self.
     */
    private val otherVoters: Set<NodeId>
        get() = state.membershipState.electionTargets(transport.selfId)

    /**
     * All members (voters + learners) other than self — the AppendEntries recipients.
     * Joint: union of all members from both configs minus self.
     */
    private val otherMembers: Set<NodeId>
        get() = state.membershipState.replicationTargets(transport.selfId)

    // ── Actor-only mutable state ──────────────────────────────────────────────

    // Candidate state
    private val votesGranted = mutableSetOf<NodeId>()

    // Pre-vote probe state — set while a pre-vote round is in flight, null otherwise
    private var preVoteTerm: Long? = null
    private var preVoteRound: Long = 0L
    private val preVotesGranted = mutableSetOf<NodeId>()

    // Leader state
    private val pending = mutableListOf<Pair<Long, CompletableDeferred<LogEntry>>>()

    /**
     * At most one membershipState change in flight at a time (one-change-at-a-time rule).
     * Completed when C_new commits; failed on any leadership relinquish or close.
     * Not on [pending] — config entries are internal, withheld from [RaftNode.committed].
     */
    private var pendingConfigChange: CompletableDeferred<ClusterConfig>? = null

    // §7 InstallSnapshot transfer — leader-only chunked send, extracted to SnapshotSender (one chunk in
    // flight per peer; await-ack-then-next). Declared before `init` per the machine-field ordering rule
    // (#1077). chunkBytes stays in the engine (it reads transport/raftConfig); the machine's only
    // side-effect is loading the stored snapshot bytes.
    private val snapshotSender = SnapshotSender(storage, ::chunkBytes)

    // §7 InstallSnapshot reassembly state — follower-only (in-order chunk accumulation), bounded by
    // raftConfig.snapshotTotalCeiling so a peer choosing `done` can't grow the buffer forever (#1881).
    private val snapshotReceiver = SnapshotReceiver(raftConfig.snapshotTotalCeiling)

    // Timer jobs (cancelled/restarted by actor)
    // timer jobs are children of scope and die with it; Close only stops the actor loop
    private var electionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var quorumCheckJob: Job? = null

    // CheckQuorum: voters (other than self) from whom any response arrived in the current window.
    // Reset each tick. Leader-only.
    private val recentVoterContacts = mutableSetOf<NodeId>()

    // §6.4 ReadIndex state — leader-only, extracted to ReadIndexTracker. It owns the pending-read queue,
    // the per-voter last-ACK round, the heartbeat round nonce (the engine stamps sends via
    // readIndexTracker.round and bumps it in onHeartbeat), the current-term no-op gate, and the
    // freshness/quorum arithmetic; the engine keeps all send/trace/deferred.complete side-effects at the
    // call site. Reset on becomeLeader; failed on relinquishToFollower. Declared before `init` per the
    // machine-field ordering rule (#1077) — the actor's teardown calls readIndexTracker.failAll(...).
    private val readIndexTracker = ReadIndexTracker()

    // Leader-lease state: true while this node has heard from a live leader recently enough
    // that triggering an election would be disruptive. Cleared on stepDown and after electionTimeoutMin.
    private var leaderAlive = false
    private var leaderLeaseJob: Job? = null

    // §3.10 Leadership transfer state — leader-only, extracted to LeadershipTransferMachine. It owns the
    // tri-state (target/deferred/auto-timeout-job) as one all-or-none InFlight record. Cleared on
    // becomeLeader (reset) and relinquishToFollower (onLeadershipRelinquished). Declared before `init` per
    // the machine-field ordering rule (#1077) — the actor's teardown calls transfer.fail(...). The machine
    // launches only the auto-timeout timer, which re-enters the actor via cmd.trySend(TransferTimeout).
    private val transfer = LeadershipTransferMachine(
        scope = scope,
        raftConfig = raftConfig,
        signalTimeout = { epoch -> cmd.trySend(EngineCommand.TransferTimeout(epoch)) },
    )

    // ── Metric instrumentation state (actor-only) ─────────────────────────────

    /** Start mark for each in-flight propose, keyed by log index. */
    private val proposeStartTimes = mutableMapOf<Long, TimeSource.Monotonic.ValueTimeMark>()

    /**
     * Start mark for the current election term, or `null` if this node is not a candidate.
     * Reset to `null` on [becomeLeader] or when a new election fires (the old term timed out).
     */
    private var electionStartTime: TimeSource.Monotonic.ValueTimeMark? = null

    /** Term for which [electionStartTime] was recorded — used to emit [RaftMetric.ElectionTimedOut]. */
    private var electionStartTerm: Long = 0L

    /**
     * `true` once [reportTermPinnedAtCeiling] has logged its `warn`, latching it to **once per node**.
     *
     * The condition it reports is permanent (terms never decrease) and re-checked on every election
     * timeout, so an unlatched `warn` would fire for the life of the process — at the default
     * [RaftConfig] timings roughly 4 lines/second, forever, on a node that by construction never
     * recovers. That buries every other diagnostic, which is the exact opposite of what #1886 buys, and
     * is worse still on a mobile target. Every other `warn` in this engine reports a *transient* event.
     *
     * The **metric** is deliberately NOT latched: [RaftMetric.ElectionSuppressedTermCeiling] is a level a
     * consumer samples, so it must keep being emitted. Log once, measure continuously.
     */
    private var ceilingWarnLogged: Boolean = false

    /**
     * The `(sender, position)` of the most recent snapshot chunk rejected by `snapshotTotalCeiling`,
     * or `null` if none has been. Read by [reportSnapshotRejectedSizeCeiling] to tell a *repeat* from
     * a first occurrence.
     *
     * One pair, not a set: the only thing needed is whether the previous rejection was the same, and a
     * per-peer map would be unbounded state a remote peer controls the keys of.
     */
    private var lastSnapshotSizeRejection: Pair<NodeId, SnapshotMeta>? = null

    /**
     * `true` once [reportSnapshotRejectedSizeCeiling] has logged its `warn`, latching it to **once per
     * node** — the same "log once, measure continuously" split as [ceilingWarnLogged].
     *
     * Both halves are load-bearing here. The `warn` waits for a *repeat* because a single rejection is
     * the resource bound working as designed against a peer feeding an unbounded `done = false` stream
     * (#1881), which is not an operator's problem; it is the *sustained* case — an honest leader whose
     * snapshot genuinely exceeds this ceiling, restarting from `0` forever — that is a
     * misconfiguration and needs a name above `debug`. And it latches because otherwise a hostile peer
     * that alternates snapshot positions in pairs mints a fresh `warn` per streak, one frame each: a
     * log-amplification lever handed to the very sender this bound exists to contain. Once per node
     * costs an operator nothing, since the condition the `warn` reports does not clear on its own —
     * `snapshotTotalCeiling` is read when the node is built, so raising it means a restart anyway.
     */
    private var snapshotSizeRejectionWarnLogged: Boolean = false

    // ── Wedge detection (#1898) ───────────────────────────────────────────────
    /**
     * How many leader→peer frames this node has refused in a row, without accepting one and without
     * its own commit index moving. Reset by both of those; read by [noteRefusedLeaderFrame].
     *
     * Deliberately **not** per sender. A per-sender run would let a peer alternating between two
     * identities keep every individual run below the threshold and suppress the report entirely —
     * the mirror image of the amplification the latch prevents.
     */
    private var refusedLeaderFrameRun: Int = 0

    /**
     * The commit index the current [refusedLeaderFrameRun] was accumulated at. A change means this
     * node made progress of its own, so the run restarts.
     */
    private var refusedLeaderFrameRunCommitIndex: Long = 0L

    /**
     * The voter set held when [noteRefusedLeaderFrame] last emitted, or `null` if it never has —
     * the latch key, giving one report per voter-set epoch.
     *
     * The set itself rather than a counter, because it is also what the report *says*: a second
     * report under the same voter set would carry the same diagnosis. A node whose voter set changes
     * has, by definition, accepted a frame carrying a config, which is precisely the thing that ends
     * this wedge and is worth reporting again if it recurs.
     */
    private var wedgeReportedAtVoters: Set<NodeId>? = null

    // ── Per-term leader identity (#1906) ──────────────────────────────────────
    /**
     * The term [leaderForTermId] was recorded for, or `-1` before any leader has been recorded.
     * Read only through [leaderForTerm] / [adoptLeaderForTerm] — a bare read is meaningless without
     * the term comparison.
     *
     * **Why two fields rather than a bare `NodeId?`.** The pin is a *fact about a term*, so it has to
     * carry the term it belongs to; a lone identity would have to be explicitly cleared at every site
     * that moves `currentTerm`, and would silently go wrong the day one of them is missed. Storing the
     * term makes staleness self-evident at the read: a pin whose term is not the current one simply
     * is not a pin. That holds for all **three** `currentTerm` writers — [persistTermAndVote] (from
     * [startRealElection] and [stepDown]) and the init-restore in the `init` block, which the #1906
     * enumeration did not count and which raises the term without touching `_leader` at all. A `Pair`
     * or a small holder would read the same; two plain fields just avoid an allocation per election.
     *
     * The same argument is why the durable record is [LeaderForTerm] and not a bare id, and why it is
     * written as one unit: these two fields ARE that record, held in memory.
     *
     * Plain `var`s are correct here: every read and write is on the engine's actor loop, which is
     * single-threaded by construction ([ceilingWarnLogged] and `serial` are the precedent).
     */
    private var leaderForTermTerm: Long = -1L

    /** The node established as leader for [leaderForTermTerm]. See [leaderForTermTerm]. */
    private var leaderForTermId: NodeId? = null

    /**
     * The node established as leader for the **current** term, or `null` when this term's leader is
     * genuinely still unknown (a fresh node, or a node that just bumped its own term to campaign).
     *
     * Deliberately **not** the same value as `_leader`, and the two answer different questions:
     *
     * | | Question | Lifecycle |
     * |---|---|---|
     * | `_leader` | who do I believe is a live leader I can **talk to** | cleared on step-down |
     * | [leaderForTerm] | who was **established** as leader for this term | sticky for the term, durable |
     *
     * §5.2 Election Safety permits at most one leader per term, so the second answer cannot change
     * once known — which is exactly what makes it usable as an authority, and why [onTimeoutNow]
     * authenticates its sender against this rather than `_leader` (#1900). Proposal forwarding keeps
     * reading `_leader`: it must not route a proposal to a leader that has just stood down.
     *
     * **Durable since #1900.** [pinLeaderForTerm] writes through to [RaftStorage.saveLeaderForTerm] and
     * the `init` restore reads it back, so a node that restarts inside a term comes back holding the
     * identity it established for it. That is what lets [onTimeoutNow] require a match outright
     * instead of admitting the whole no-known-leader window; restoring it does **not** weaken the
     * read-time staleness check below — a restored record for a term other than `currentTerm` is
     * exactly as invisible as one left over from an earlier term in-process.
     */
    private val leaderForTerm: NodeId?
        get() = leaderForTermId.takeIf { leaderForTermTerm == state.currentTerm }

    /**
     * Record [id] as this term's leader, overwriting any pin left over from an earlier term, and
     * persist it so a restart inside this term recovers it (#1900).
     *
     * **Storage-first**, like [persistTermAndVote]: a crash between the durable write and the
     * in-memory assignment loses nothing (the restore reads the record back), while the reverse order
     * would let this node act on a pin it has no memory of after a restart — which is the state this
     * change exists to remove. Called at most once per term from each of its two sites, so the write
     * cadence matches [RaftStorage.saveTermAndVotedFor]'s rather than the heartbeat's: the
     * [adoptLeaderForTerm] caller reaches it only when the term holds no pin yet, and [becomeLeader]
     * runs once per term by construction.
     */
    private suspend fun pinLeaderForTerm(id: NodeId) {
        storage.saveLeaderForTerm(state.currentTerm, id)
        leaderForTermTerm = state.currentTerm
        leaderForTermId = id
    }

    /**
     * Adopt-if-unset-else-must-match: `true` if [from] may act as this term's leader, `false` if some
     * *other* node was already established as leader for this term — in which case, by §5.2, one of
     * the two frames is forged and the caller must drop the one it is holding.
     *
     * This is the whole of #1906. `_leader` used to be assigned straight from whichever leader→peer
     * frame arrived last, so an ordinary same-term `AppendEntries` from any voter redirected a
     * victim's belief to its sender — and [onTimeoutNow] authenticated its sender against `_leader`
     * (it reads this pin instead since #1900), so the poisoned belief turned a second frame into a
     * pre-vote-less election on demand. Two frames, from a peer that was never leader.
     *
     * **The rule rejects nothing honest**, which is the load-bearing claim and is driven rather than
     * argued (`LeaderForTermPinTest`): a term's pin is set exactly once, on the first leader-contact
     * of that term or on winning it, and a term change invalidates it. So every legitimate `null → L`
     * adoption still happens — on first contact, in the next term, and from a Candidate that lost (a
     * Candidate at term `T` got there by bumping to `T`, so it holds no pin for `T`). A mismatch
     * requires two same-term leaders, which Election Safety forbids.
     *
     * Since #1900 a restart *inside* a term is no longer one of those adoptions: the pin is durable, so
     * the restarted node comes back holding `L` and takes the `established == from` path instead. That
     * is the point — it is what lets [onTimeoutNow] require a match outright. A restart across a term
     * boundary is unchanged: the restored record is for the older term, so it reads as no pin at all.
     *
     * **Disposition: drop the frame.** Not a clamp — an identity has no conservative in-range reading,
     * and clamping one launders a forgery into the most favourable valid value (#1817). Not a throw —
     * this runs inside the engine's actor loop, whose `try`/`finally` has no `catch`, so a throw would
     * convert one remote frame into permanent node death (#1818). Not a reply either — but for a weaker
     * reason than [clearsCommittedTermFloor]'s: there, no honest sender can emit the frame at all, so
     * there is nobody to answer. Here the dropped frame may well be the honest leader's (see below), and
     * the reason not to answer is simply that no reply helps — the recipient cannot tell the two senders
     * apart, so it has nothing to say that the honest one could act on.
     *
     * **Residual exposure: the pin is first-claim-wins, and that is not authorization.** Which of the
     * two candidates gets established is decided by arrival order, not by evidence. A forger that lands
     * the *first* leader-contact of a term pins itself, and this node then drops the real leader's
     * frames for the rest of that term — quietly, since neither side is told. That is not an escalation
     * over the pre-fix behaviour (before this, the forger simply overwrote the belief whenever it liked,
     * and additionally unlocked `onTimeoutNow`), and it is inherent to deciding between two
     * unauthenticated claims from local state alone: **a local predicate cannot substitute for
     * authorization** when the attack value is also a reachable legitimate value (#1880). Closing it
     * needs the mechanism tracked in #1907, not a better local check.
     *
     * Logged at `debug`, like its sibling drops, deliberately: the caller is an unauthenticated remote
     * peer that can repeat the frame at will, so a `warn` here would be a log-flood lever. The
     * Election-Safety-violating case that *is* locally attributable — this node still believing it is
     * Leader — already emits `BecomeFollower(AppendEntriesFromLeader)` on the trace via
     * [demoteToFollowerOnLeaderContact].
     */
    private suspend fun adoptLeaderForTerm(from: NodeId, rpc: String): Boolean {
        val established = leaderForTerm
        if (established == null) {
            pinLeaderForTerm(from)
            return true
        }
        if (established != from) {
            debug {
                "$rpc($from): DROP — ${established.value} is already established as leader for term " +
                    "${state.currentTerm}; §5.2 permits one leader per term, so one of the two is " +
                    "forged — which one is not locally decidable, and the pin is first-claim-wins"
            }
            return false
        }
        return true
    }

    // ── Client-proposal forwarding state (§8) — actor-teardown-touched ─────────
    // MUST stay declared BEFORE the `init` block below (which launches the actor).
    // The actor's `finally` teardown calls forwarder.failAll(...), which dereferences
    // the machine's maps; when the scope is cancelled during construction that teardown
    // can run before the constructor reaches a declaration placed after `init` → NPE.
    // Keep alongside the other pending-state fields the teardown touches
    // (pending/pendingConfigChange/readIndexTracker/transfer*). See #1077.
    //
    // Extracted to ProposalForwarder (§8): it owns the outstanding forwards awaiting a
    // ForwardResponse, the ones parked while no leader is known, and the monotonic
    // correlation nonce; the engine keeps all send/deferred.complete side-effects at the
    // call site. Drained by flushWaitingForLeader after every non-Close command; failed on
    // actor teardown.
    private val forwarder = ProposalForwarder()

    /**
     * The shared consensus core — term/vote, log, commit/snapshot boundary, membership, and the
     * leader-side replication indices. Declared BEFORE the `init` block (#1077): the actor's
     * teardown and the init-restore coroutine both dereference these fields, so `state` must be
     * initialized before the launch below.
     *
     * **Ordering foot-gun.** Because `state` is declared this far down the class body, every field or
     * property *above* it that touches `state` must do so **lazily** — the peer-set helpers (`otherVoters`
     * / `otherMembers`) and the log-derived getters are `get()`-computed, so they read `state` only when
     * *invoked* (always after construction), never during field initialization. A new **eager** `val x =
     * state.something` placed above this line would observe an uninitialized `state` and NPE. When adding a
     * field that needs `state` in its initializer, either keep the reference lazy or move `state` up to the
     * top of this pre-`init` block (its only constructor input is the `bootstrapConfig` parameter, so it can
     * be initialized first without depending on any other field).
     */
    private val state = RaftState(bootstrapConfig)

    init {
        scope.launch {
            // Restore persisted state
            state.currentTerm = checkedRestoredTerm(storage.term())
            state.votedFor = storage.votedFor()
            // Recover the per-term leader identity (#1900). Restored RAW, with no comparison to the
            // term above and no bound of its own: [leaderForTerm] decides relevance at every read, and
            // a record for any term but this one is invisible there — including a nonsensical one, for
            // which "not equal to currentTerm" is the whole of the check that matters. The two cases a
            // bound would have to distinguish are both already harmless: a record for an older term is
            // the ordinary restart-across-a-term-boundary case, and one for a term this node has not
            // reached can only come from storage that lost the accompanying term write, where the
            // engine has already restored a term it must not decrease past.
            //
            // Unlike [checkedRestoredTerm] this needs no refuse-to-start bound, because the value is
            // not §5.1/§5.2 safety state: a wrong pin costs *liveness* for the remainder of one term
            // (this node refuses the real leader's AppendEntries and campaigns at the next term), where
            // a wrong term costs a forgotten vote. Storage that can corrupt this can corrupt `votedFor`,
            // and that is the check worth having.
            storage.leaderForTerm()?.let { restored ->
                leaderForTermTerm = restored.term
                leaderForTermId = restored.leaderId
            }
            // Recover the snapshot baseline FIRST: a persisted snapshot is by definition committed, so
            // seed snapshotIndex/Term, the compaction floor, and commitIndex from it. This must happen
            // BEFORE the log load so `snapshotIndex` is known when we filter the persisted entries.
            storage.loadSnapshot()?.let { stored ->
                // Bound the metadata BEFORE adopting any of it (#1887): `state.snapshotIndex` seeds the
                // positional log math, the compaction floor, `commitIndex`, and the `snapshotIndex + 1`
                // filter on the line below, so an unchecked value poisons all four at once.
                val meta = checkedRestoredSnapshotMeta(stored.meta)
                state.snapshotIndex = meta.lastIncludedIndex
                state.snapshotTerm = meta.lastIncludedTerm
                // Seed the membershipState baseline from the snapshot so a node that crashed after compacting
                // past a config change recovers under that change (the config entry is gone from the log).
                state.snapshotConfig = meta.config
                _compactionFloor.value = state.snapshotIndex
                if (state.currentCommitIndex < state.snapshotIndex) {
                    state.currentCommitIndex = state.snapshotIndex
                    _commitIndex.value = state.snapshotIndex
                }
            }
            // Load only entries ABOVE the snapshot floor. `saveSnapshot` is durable-before-discardLogPrefix
            // (#1221): a crash in that window leaves storage holding the snapshot AND the un-discarded prefix
            // (entries with index <= snapshotIndex). Loading `entries()` unfiltered would put the compacted
            // prefix into the in-memory log, and the positional log math (RaftLogMath: log[index - snapshotIndex
            // - 1], which assumes the list begins at snapshotIndex + 1) would then silently return the wrong
            // entry for every lookup. Filtering here restores the invariant regardless of the crash window.
            state.log.addAll(checkedRestoredEntries(storage.entries(state.snapshotIndex + 1)))
            // Recompute effective membershipState from the recovered log + snapshot (restart recovery).
            // This is load-bearing: a node that crashed mid-transition comes back under exactly
            // the config its durable log justifies — no special restart path needed.
            recomputeMembership()
            // Set initial role (consults membershipState.isLearner, so must run after recomputeMembership)
            _role.value = followerRole
            // Start actor and message subscription
            startActor()
            resetElectionTimeout()
            launch {
                transport.incoming.collect {
                    try {
                        cmd.send(EngineCommand.IncomingMessage(it.from, raftCbor.decodeFromByteArray(it.bytes)))
                    } catch (_: ClosedSendChannelException) {
                        return@collect // channel closed — node is shutting down
                    }
                }
            }
            launch { snapshots.collect { cmd.trySend(EngineCommand.Compact) } }
        }
    }

    private fun startActor() {
        scope.launch {
            try {
                for (c in cmd) {
                    val closing = c is EngineCommand.Close
                    when (c) {
                        is EngineCommand.IncomingMessage  -> onMessage(c.from, c.message)
                        is EngineCommand.Propose          -> onLocalPropose(c.command, c.requestId, c.response)
                        is EngineCommand.ChangeMembership -> onChangeMembership(c.target, c.response)
                        is EngineCommand.ElectionTimeout  -> onElectionTimeout()
                        is EngineCommand.HeartbeatTick    -> onHeartbeat()
                        is EngineCommand.LeaseExpired     -> { leaderAlive = false }
                        is EngineCommand.Compact          -> onCompact()
                        is EngineCommand.CommitCut        -> onCommitCut(c)
                        is EngineCommand.QuorumCheck      -> onQuorumCheck()
                        is EngineCommand.RequestReadIndex -> onRequestReadIndex(c.deferred)
                        is EngineCommand.TransferLeadership -> onTransferLeadership(c.target, c.response)
                        is EngineCommand.CancelTransfer   -> onCancelTransfer()
                        is EngineCommand.TransferTimeout  -> onTransferTimeout(c.epoch)
                        is EngineCommand.Close            -> { cmd.close(); break }
                    }
                    if (!closing) flushWaitingForLeader()
                }
            } finally {
                // #1257 bug 2: cancel the leader's timer loops. becomeLeader launches heartbeatJob /
                // quorumCheckJob as `while (true)` coroutines; Close only stops the actor loop, so without
                // this they leak past close() (their re-arming delay keeps them alive forever). electionJob /
                // leaderLeaseJob are already cancelled on becomeLeader/stepDown, but cancel them here too so
                // close() is a complete leader-timer teardown regardless of the role we exit in.
                electionJob?.cancel()
                heartbeatJob?.cancel()
                quorumCheckJob?.cancel()
                leaderLeaseJob?.cancel()
                // Complete any in-flight (already-registered) proposals, config changes, reads, and
                // transfers so their callers don't hang.
                val cause = LeadershipLostException("node scope cancelled")
                failPending(cause)
                failPendingConfigChange(cause)
                readIndexTracker.failAll(cause)
                transfer.fail(LeadershipTransferException("node scope cancelled"))
                forwarder.failAll(cause)
                // #1257 bug 1: drain commands still BUFFERED in `cmd` — enqueued behind Close, or pending
                // when the scope was cancelled — and fail each command-carrying deferred. Without this a
                // Propose/RequestReadIndex/ChangeMembership/TransferLeadership/CommitCut parked in that
                // window would leave its CompletableDeferred uncompleted, hanging the caller's await()
                // forever (the same deferred-parks class as #1235). Close the channel first so no late send
                // can race in behind the drain; completeExceptionally on an already-resolved deferred (e.g.
                // a caller that cancelled) is a harmless no-op, so this is exactly-once by construction.
                cmd.close()
                val closed = NotLeaderException("node is closed")
                while (true) {
                    val buffered = cmd.tryReceive().getOrNull() ?: break
                    when (buffered) {
                        is EngineCommand.Propose            -> buffered.response.completeExceptionally(closed)
                        is EngineCommand.ChangeMembership   -> buffered.response.completeExceptionally(closed)
                        is EngineCommand.RequestReadIndex   -> buffered.deferred.completeExceptionally(closed)
                        is EngineCommand.CommitCut          -> buffered.response.completeExceptionally(closed)
                        is EngineCommand.TransferLeadership -> buffered.response.completeExceptionally(closed)
                        // No caller deferred to fail — enumerated (no `else`) so a future deferred-carrying
                        // EngineCommand variant forces a compile error here rather than silently hanging its
                        // caller on close, exactly like the exhaustive actor-dispatch `when` above.
                        is EngineCommand.IncomingMessage,
                        is EngineCommand.ElectionTimeout,
                        is EngineCommand.HeartbeatTick,
                        is EngineCommand.LeaseExpired,
                        is EngineCommand.Compact,
                        is EngineCommand.Close,
                        is EngineCommand.QuorumCheck,
                        is EngineCommand.CancelTransfer,
                        is EngineCommand.TransferTimeout -> Unit
                    }
                }
            }
        }
    }

    // ── Persistence choke-points ──────────────────────────────────────────────

    /**
     * Returns [restored] if it is a term a real node could hold; otherwise refuses to start (#1855).
     *
     * ### The gap this closes
     *
     * #1846 put `MAX_PLAUSIBLE_TERM` at the [onMessage] dispatch boundary, which covers every term that
     * arrives in a *frame*. It does not cover the third way `state.currentTerm` is written: the restore
     * on the line this guards. A node whose durable term is out of range reloads it verbatim, and from
     * then on every frame it emits is dropped by peers as implausible while every frame it receives looks
     * stale — permanent, silent, single-node isolation with no diagnostic anywhere on the node itself.
     *
     * ### Why this is a refusal, not a clamp, and not a shrug
     *
     * **Not a clamp.** Rewriting a persisted term discards the record of which terms this node has
     * already voted in, so it can vote a second time in a term it has forgotten — §5.2 election safety,
     * a strictly worse fault than the lost liveness. Same reasoning that makes a term a *nonce* and not a
     * *quantity* at the wire boundary: an out-of-range term admits no conservative in-range reading.
     *
     * **Not a shrug.** Two facts make "the node is merely isolated" wrong:
     *
     * 1. The overflow #1833 is about is **still reachable through here**. A one-voter cluster — what
     *    an appoint-the-host bootstrap starts as, before it admits anyone — restores `Long.MAX_VALUE`,
     *    wins its own election, and `startRealElection`'s `currentTerm + 1` wraps to `Long.MIN_VALUE`,
     *    which [persistTermAndVote] then writes to disk. The engine drives its own durable term
     *    *backwards*, against [RaftStorage.term]'s "never safe to decrease it" contract, and a node whose
     *    term went backwards has forgotten every vote it cast.
     * 2. It is **not confined to a migration**. kuilt ships no durable [RaftStorage] — `InMemoryRaftStorage`
     *    is the only implementation in the library — so every persistent one is consumer code, and the
     *    storage TCK constrains `term()` to nothing but "starts at 0" and "round-trips". An out-of-range
     *    term is an ordinary third-party storage bug (a truncated column, a sign-extended `Int`, a torn
     *    read), reachable with no pre-fix binary and no attacker.
     *
     * ### Why throwing here is not the #1818 failure mode
     *
     * The standing rule is that malformed input must be *dropped*, never thrown on, because the actor
     * loop's `try`/`finally` has no `catch` and a `require` would convert one hostile frame into permanent
     * node death. That rule is about *remote* input on the message path. This input is not a frame: it is
     * the consumer's own storage, read once at start-up before [startActor] has run, with no sender.
     *
     * That is a statement about the *shape* of the input, **not** a claim that no remote party can reach
     * this line — one can, a restart later. Measured on this branch *before* #1889: a single
     * [RaftMessage.TimeoutNow] carrying `term == MAX_PLAUSIBLE_TERM` from *any* peer was admitted by
     * [onMessage]'s inclusive ceiling; [onTimeoutNow]'s leader check was scoped to
     * `m.term == state.currentTerm`, so a strictly-higher term bypassed it; the receiver stepped down to
     * `2^60`, and the transfer-driven `startRealElection` it then ran persisted `currentTerm + 1` =
     * `2^60 + 1`, above the ceiling. Its next restart arrived here.
     *
     * The refusal is still the right disposition at *this* site — before it, that same node came back up
     * permanently and *silently* isolated, strictly worse than a loud failure that names the state. What
     * needs fixing is the remote *cause*, and it is not here. #1889 has since closed the TimeoutNow route
     * specifically: a TimeoutNow whose sender is not a voter, or whose term is strictly ahead of ours, is
     * now dropped without adopting its term. The general shape survives it — a node can still come to hold
     * a term whose `+ 1` this engine refuses to perform ([termPinnedAtCeiling]) — so this guard stays the
     * backstop, not the fix.
     *
     * ### Why this bound stays inclusive, and why it no longer agrees with [onMessage] by definition
     *
     * #1886 was expected to make this bound exclusive in lockstep with [onMessage]'s. It does not:
     * measurement showed an exclusive ceiling only relocates the boundary by one (see
     * [termPinnedAtCeiling]), so neither bound moved. The containment went in at the *increment* instead.
     *
     * The two bounds used to stay consistent for the simplest possible reason — they were the same
     * constant. **#1897 ended that.** [onMessage] now bounds the JUMP (`term - currentTerm > maxTermJump`)
     * rather than the value, so it can admit a term this site would refuse: a node at exactly `2^60`
     * adopts `2^60 + 1` off the wire, persists it, and its own next boot lands here. The asymmetry is
     * real and deliberate — it costs an *unreachable* case (reaching `2^60` at all now takes ~10^14
     * accepted frames rather than one) to remove a cliff that was reachable in a single frame, and this
     * site's job is unchanged: an out-of-range durable term surfaces here, loudly, whatever produced it.
     *
     * No argument about where a durable term came from is needed, and none would hold: `storage.term()`
     * is third-party input, which is the entire premise above. A durable term **above** the ceiling
     * therefore remains reachable, by at least three routes this engine cannot police — the #1897
     * asymmetry just described, a **pre-#1886 binary** that persisted `2^60 + 1` through
     * the escalation described above and then upgrades onto this refusal (snapshots publish on every push
     * to `main`), and a storage adapter that simply returns a corrupt value. All three land here, loudly,
     * which is the design: this guard is where an out-of-range durable term is *supposed* to surface.
     *
     * That escalation is now closed **twice over, at different layers**, and the division of labour is worth
     * being precise about because only one half generalises:
     *
     * - **#1889 closes the frame.** A higher-term `TimeoutNow` is dropped before it can step the victim down
     *   at all, so that specific remote route no longer reaches the ceiling term in the first place.
     * - **#1886 closes the increment.** Even when a node *does* hold a ceiling term, `startRealElection`
     *   will not persist above it. This is the half that still holds when the ceiling term arrives by any
     *   other means — a restored durable term (which no frame gate can see), or any future term-adopting
     *   path #1889's TimeoutNow-specific check does not cover.
     *
     * So a node restored at exactly `2^60` still starts, as it must, and then reports
     * [RaftMetric.ElectionSuppressedTermCeiling] on its first election attempt instead of silently failing
     * to elect.
     *
     * Dropping is also not available at this site: the value is not a message to discard but the node's own
     * identity, and continuing without it means either inventing a term (the clamp) or running on a
     * poisoned one (the shrug). The throw surfaces through the caller's scope, which is the documented
     * contract of `CoroutineScope.raftNode` — "any exception in the node propagates to the scope's
     * supervisor" — putting the persisted value in front of the operator, who can then tell a storage-
     * adapter bug from the remote escalation above by whether it is `2^60 + 1`.
     *
     * ### Scope boundary
     *
     * This bounds the restored **term** only. The rest of what the restore reads from the same storage —
     * `SnapshotMeta.lastIncludedTerm`/`lastIncludedIndex` and the terms/indices of restored [LogEntry]s —
     * is bounded by its siblings [checkedRestoredSnapshotMeta] and [checkedRestoredEntries] (#1887), which
     * close the §5.4.1 domination this bound cannot see: a `RequestVote` carries the candidate's *term*,
     * never its `lastLogTerm`, so a node with a perfectly plausible term and a poisoned entry term passes
     * every check on this line. All three refuse rather than repair, for the reason argued above.
     */
    private fun checkedRestoredTerm(restored: Long): Long {
        if (restored < 0L || restored > MAX_PLAUSIBLE_TERM) {
            throw CorruptDurableStateException(
                "RaftStorage returned an implausible persisted term ($restored): a term must be in " +
                    "0..$MAX_PLAUSIBLE_TERM. Refusing to start ${transport.selfId.value} — adopting it " +
                    "would isolate this node silently, and clamping it would let it vote twice in a term " +
                    "it had forgotten (Raft §5.2). Inspect the storage adapter's persisted term, then " +
                    "repair it or re-provision this node from empty state.",
            )
        }
        return restored
    }

    /**
     * Returns [meta] if a real node could have stored it; otherwise refuses to start (#1887).
     *
     * The sibling of [checkedRestoredTerm] for the snapshot baseline. #1855 bounded the restored term and
     * left everything else the init-restore reads adopted verbatim, which leaves the **whole** §5.4.1
     * lever reachable through storage: [RaftState.lastLogPosition] falls back to
     * `(snapshotTerm, snapshotIndex)` when the log is empty, and §5.4.1 orders positions by
     * `(term, index)` **lexicographically**, so a node restored with a huge `lastIncludedTerm` is
     * unbeatable in every election — `isLogUpToDate` tells every honest peer *its own* log is less
     * current, so all of them grant the vote. It then becomes leader carrying a log it cannot justify and
     * overwrites the cluster's. This is exactly #1832 (AppendEntries) and #1868 (InstallSnapshot), reached
     * from storage instead of the wire, and [checkedRestoredTerm] cannot see it: a `RequestVote` carries
     * the candidate's `term`, never its `lastLogTerm`.
     *
     * **Both halves are bounded, for the reason in [MAX_PLAUSIBLE_INDEX]'s KDoc** — tying on term and
     * winning on index dominates just as surely as a huge term does, so bounding only the term half leaves
     * the violation reachable. `lastIncludedIndex` additionally seeds the compaction floor, `commitIndex`,
     * and the `entries(snapshotIndex + 1)` filter, and `Long.MAX_VALUE` there would overflow that `+ 1`
     * to `Long.MIN_VALUE` and load the compacted prefix straight back into the positional log.
     *
     * The ceilings are the same constants the [RaftMessage.InstallSnapshot] lane uses in
     * [isWellFormedSnapshotChunk], and inclusive for the same reason: a pure plausibility filter with no
     * progress obligation creates no fixed point at the boundary.
     *
     * **Refuse, don't repair** — the disposition [checkedRestoredTerm] argues for, and the reason it also
     * applies to the alternative available *here* but not there. A trailing corrupt suffix could in
     * principle be truncated back to the snapshot baseline and re-replicated, which is safe iff the
     * truncation point is at or below `commitIndex`. But `commitIndex` is itself seeded from this very
     * `loadSnapshot()`, so that safety predicate is computed from the corrupt input it is meant to police.
     * A node that refuses to start on a poisoned durable *term* and silently repairs a poisoned *entry
     * term* would be incoherent besides.
     */
    private fun checkedRestoredSnapshotMeta(meta: SnapshotMeta): SnapshotMeta {
        if (meta.lastIncludedTerm < 0L || meta.lastIncludedTerm > MAX_PLAUSIBLE_TERM) {
            throw CorruptDurableStateException(
                "RaftStorage returned a snapshot with an implausible lastIncludedTerm " +
                    "(${meta.lastIncludedTerm}): it must be in 0..$MAX_PLAUSIBLE_TERM. Refusing to start " +
                    "${transport.selfId.value} — §5.4.1 orders log positions by (term, index) " +
                    "lexicographically, so adopting it would make this node unbeatable in every election " +
                    "while carrying a log it cannot justify. Inspect the storage adapter's persisted " +
                    "snapshot metadata, then repair it or re-provision this node from empty state.",
            )
        }
        if (meta.lastIncludedIndex < 0L || meta.lastIncludedIndex > MAX_PLAUSIBLE_INDEX) {
            throw CorruptDurableStateException(
                "RaftStorage returned a snapshot with an implausible lastIncludedIndex " +
                    "(${meta.lastIncludedIndex}): it must be in 0..$MAX_PLAUSIBLE_INDEX. Refusing to start " +
                    "${transport.selfId.value} — it seeds the positional log math, the compaction floor, " +
                    "and commitIndex, so adopting it would corrupt all three at once. Inspect the storage " +
                    "adapter's persisted snapshot metadata, then repair it or re-provision this node from " +
                    "empty state.",
            )
        }
        return meta
    }

    /**
     * Returns [entries] if they are a log a real node could have persisted; otherwise refuses to start
     * (#1887). Runs after [checkedRestoredTerm] and [checkedRestoredSnapshotMeta], whose results it uses.
     *
     * Four properties, each of which the engine's own append paths hold by construction and a third-party
     * [RaftStorage] can break — kuilt ships no durable implementation, and `RaftStorageConformanceSuite`
     * constrains none of these fields, so every persistent adapter is consumer code that can pass the
     * whole TCK and still return garbage.
     *
     * - **Term in `0..state.currentTerm`.** The wire analogue is [isWellFormedBatch]'s
     *   `entry.term > m.term` — "no entry may carry a term above the leader's"; here the ceiling is the
     *   node's *own* persisted term. `storage.term() >= max(log entry terms)` is an invariant of every
     *   append path, because [persistTermAndVote] is storage-first and runs before `storage.appendEntries`.
     *   This subsumes a separate [MAX_PLAUSIBLE_TERM] range check: `state.currentTerm` was already bounded
     *   by [checkedRestoredTerm], so anything above the ceiling is above `currentTerm` too. It is also the
     *   §5.4.1 lever [checkedRestoredSnapshotMeta] closes on the snapshot half —
     *   [RaftState.lastLogPosition] reads the *last entry* when the log is non-empty.
     * - **Index in `0..`[MAX_PLAUSIBLE_INDEX].** Bounded independently of contiguity: with a snapshot at
     *   the ceiling, `snapshotIndex + 1` is a *contiguous* position that is nonetheless out of range.
     * - **Contiguity.** [RaftLogMath] reads positionally (`log[index - snapshotIndex - 1]`, which assumes
     *   the list begins at `snapshotIndex + 1`), so a gap silently returns the wrong entry for **every**
     *   lookup above it — wrong `prevTerm`, wrong conflict checks, wrong applies, with no error anywhere.
     *   Distinct from the compacted-*prefix* case (#1221) that the `entries(snapshotIndex + 1)` filter
     *   handles and `nodeRestart_filtersCompactedPrefix_whenLogDiscardCrashedMidWay` pins: that shape has
     *   entries *below* the baseline, which the filter removes; this one has a gap the filter cannot see.
     * - **Non-decreasing terms (§5.3).** A leader only ever appends at its own `currentTerm`, which never
     *   decreases, and conflict resolution truncates a suffix rather than rewriting the middle — so terms
     *   run non-decreasing along any real log. §5.3's fast-backup search and the Log Matching argument
     *   both assume it.
     *
     * Disposition and its rejected alternative: see [checkedRestoredSnapshotMeta].
     */
    private fun checkedRestoredEntries(entries: List<LogEntry>): List<LogEntry> {
        var previousTerm = state.snapshotTerm
        entries.forEachIndexed { i, entry ->
            if (entry.index < 0L || entry.index > MAX_PLAUSIBLE_INDEX) {
                throw CorruptDurableStateException(
                    "RaftStorage returned a restored log entry with an implausible index " +
                        "(index=${entry.index}, term=${entry.term}): it must be in 0..$MAX_PLAUSIBLE_INDEX. " +
                        "Refusing to start ${transport.selfId.value} — §5.4.1 orders log positions by " +
                        "(term, index) lexicographically, so a log position past any honest one dominates " +
                        "every election. Inspect the storage adapter's persisted log, then repair it or " +
                        "re-provision this node from empty state.",
                )
            }
            if (entry.term < 0L || entry.term > state.currentTerm) {
                throw CorruptDurableStateException(
                    "RaftStorage returned a restored log entry outside 0..${state.currentTerm} " +
                        "(index=${entry.index}, term=${entry.term}): no entry may carry a term above this " +
                        "node's own persisted term, which every append path guarantees by persisting the " +
                        "term before the entry. Refusing to start ${transport.selfId.value} — under §5.4.1 " +
                        "a restored entry term above every honest peer's makes this node unbeatable in " +
                        "every election while carrying a log it cannot justify. Inspect the storage " +
                        "adapter's persisted log, then repair it or re-provision this node from empty state.",
                )
            }
            val expected = state.snapshotIndex + 1L + i
            if (entry.index != expected) {
                throw CorruptDurableStateException(
                    "RaftStorage returned a log that is not contiguous from the snapshot baseline: " +
                        "entry $i has index=${entry.index}, expected $expected (snapshotIndex=" +
                        "${state.snapshotIndex}, entries=${entries.size}). Refusing to start " +
                        "${transport.selfId.value} — the log math reads positionally " +
                        "(log[index - snapshotIndex - 1]), so a gap silently resolves the wrong entry for " +
                        "every lookup above it. Inspect the storage adapter's persisted log, then repair " +
                        "it or re-provision this node from empty state.",
                )
            }
            if (entry.term < previousTerm) {
                throw CorruptDurableStateException(
                    "RaftStorage returned a log whose terms decrease: entry $i (index=${entry.index}, " +
                        "term=${entry.term}) is below the preceding term $previousTerm. Refusing to start " +
                        "${transport.selfId.value} — Raft §5.3 requires log terms to be non-decreasing, " +
                        "and both the fast-backup search and the Log Matching argument assume it. Inspect " +
                        "the storage adapter's persisted log, then repair it or re-provision this node " +
                        "from empty state.",
                )
            }
            previousTerm = entry.term
        }
        return entries
    }

    /** Persist term+vote durably, THEN update in-memory — uniform crash-consistent ordering. */
    private suspend fun persistTermAndVote(term: Long, vote: NodeId?) {
        storage.saveTermAndVotedFor(term, vote)
        state.currentTerm = term
        state.votedFor = vote
    }

    /** Persist a vote grant durably, then in-memory (term unchanged). */
    private suspend fun persistVote(vote: NodeId?) {
        storage.saveVotedFor(vote)
        state.votedFor = vote
    }

    // ── Pending-failure helper ────────────────────────────────────────────────

    /** Complete every in-flight propose() deferred exceptionally and clear the queue. */
    private fun failPending(cause: Throwable) {
        pending.forEach { (_, deferred) -> deferred.completeExceptionally(cause) }
        pending.clear()
    }

    /** Fail the in-flight changeMembership deferred (if any) and clear the change fields. */
    private fun failPendingConfigChange(cause: Throwable) {
        pendingConfigChange?.completeExceptionally(cause)
        pendingConfigChange = null
    }

    // ── Role helper ───────────────────────────────────────────────────────────

    /**
     * The non-leader role for this node: [RaftRole.Learner] if the effective membershipState
     * classifies self as a learner, [RaftRole.Follower] otherwise. Learners replicate the log
     * but never vote, so they must never be promoted to Follower.
     *
     * Consults [membershipState] (not the static bootstrapConfig) so a node whose role changed
     * via a config log entry correctly reflects its new classification.
     */
    private val followerRole: RaftRole
        get() = if (state.membershipState.isLearner(transport.selfId)) RaftRole.Learner else RaftRole.Follower

    // ── Trace helper ──────────────────────────────────────────────────────────

    private suspend fun emitTrace(event: RaftTraceEvent) = _trace.emit(event)

    private fun nextClock() = ++traceClock

    /**
     * The single site at which a dispatch-boundary guard refuses a frame (#1989).
     *
     * Each guard still `return`s on its own line — this only adds the attribution. Before it, a
     * refusal's only observable was the absence of a state change, which several guards produce
     * identically, so an upstream guard's coverage silently went to zero as soon as a downstream one
     * refused the same frames (#1980).
     *
     * **Which of the two observables fires is [RefusalGate.wedgeGate]'s exhaustive `when`, not this
     * function's.** The trace event is emitted for every gate; [noteRefusedLeaderFrame] is called for
     * the gates that map to a [RaftMetric.WedgeSuspected.Gate], which is the two at
     * `RaftEngine.onMessage`'s dispatch boundary and none of [onTimeoutNow]'s. Routing both through
     * one place is what keeps the metric and the trace from ever naming different gates for the same
     * frame; the mapping being a closed `when` is what stops a new guard being wired to one and not
     * the other.
     *
     * `m.wireTerm` is recomputed rather than passed in — it is a pure `when` over the frame, so it is
     * the same value both `onMessage` call sites already hold in a local.
     *
     * `suspend` because [emitTrace] is. That never blocks the actor loop: `_trace` is a
     * `MutableSharedFlow` with `DROP_OLDEST`, on which `emit` is documented never to suspend, which is
     * precisely why it is safe here — every caller is on a path a *remote frame* controls. For the
     * same reason nothing here throws (#1818): a `require` on this path would convert one hostile
     * frame into permanent node death.
     */
    private suspend fun refuseFrame(from: NodeId, m: RaftMessage, gate: RefusalGate) {
        emitTrace(RaftTraceEvent.FrameRefused(nextClock(), transport.selfId, from, m.messageType, gate))
        val wedge = gate.wedgeGate
        if (wedge != null) noteRefusedLeaderFrame(from, m, m.wireTerm, wedge)
    }

    // ── Metric helper ─────────────────────────────────────────────────────────

    /** Emit [metric] to the [onMetric] hook (no-op when null). */
    private fun emitMetric(metric: RaftMetric) = onMetric?.invoke(metric)

    /**
     * Real-sink replication trace at `debug` level via kotlin-logging. Lazy — the message lambda is
     * only built when debug logging is enabled. Unlike [emitTrace] (the [trace] flow), it does not
     * route through a flow, so it stays visible even when a test's virtual clock stalls — the
     * failure mode that hid the post-install AppendEntries reject loop.
     */
    private inline fun debug(crossinline msg: () -> String) {
        logger.debug { "[raft:${transport.selfId}] ${msg()}" }
    }

    // ── Timers ────────────────────────────────────────────────────────────────

    private fun randomElectionTimeoutMillis(): Long = raftConfig.random.nextLong(
        raftConfig.electionTimeoutMin.inWholeMilliseconds,
        raftConfig.electionTimeoutMax.inWholeMilliseconds,
    )

    private fun resetElectionTimeout() {
        electionJob?.cancel()
        if (_role.value is RaftRole.Learner) return
        electionJob = scope.launch {
            delay(randomElectionTimeoutMillis())
            cmd.trySend(EngineCommand.ElectionTimeout)
        }
    }

    /**
     * Mark the leader as alive and start a lease timer. When the timer fires the lease expires and
     * [leaderAlive] is cleared, allowing pre-votes to be granted again.
     */
    private fun armLeaderLease() {
        leaderAlive = true
        leaderLeaseJob?.cancel()
        leaderLeaseJob = scope.launch {
            delay(raftConfig.electionTimeoutMin.inWholeMilliseconds)
            cmd.trySend(EngineCommand.LeaseExpired)
        }
    }

    // ── Election ──────────────────────────────────────────────────────────────

    /**
     * `true` when [state]`.currentTerm` has reached [MAX_PLAUSIBLE_TERM], so an election's
     * `currentTerm + 1` is refused rather than performed.
     *
     * Written as `>=` against the ceiling rather than `currentTerm + 1 > MAX_PLAUSIBLE_TERM` so the
     * guard against an overflowing increment does not itself compute one.
     *
     * ### What it is for, after #1897
     *
     * This predicate was introduced under #1886 with a second justification that **#1897 retired**: that
     * the proposed `currentTerm + 1` *"would be dropped as implausible by every peer including this
     * node"*. [onMessage] no longer bounds a term's value against this constant — it bounds the JUMP —
     * so a step of one is admissible at every term, `2^60 + 1` included. That half of the argument is
     * gone, and with it the boundary the #1886 analysis below was written about.
     *
     * Two reasons survive, and they are now the whole justification:
     *
     * 1. **Overflow headroom.** `currentTerm + 1` on a `Long` wraps near [Long.MAX_VALUE]. Since #1897
     *    made adoption relative, nothing else bounds `currentTerm` above, so this predicate is the
     *    *sole* reason the two increment sites cannot wrap. That is a stronger role than it had.
     * 2. **Never persist a term this node cannot restore.** [checkedRestoredTerm] still refuses a
     *    durable term above [MAX_PLAUSIBLE_TERM], so incrementing past it would brick the next boot.
     *
     * ### The liveness cost, which #1897 changed the shape of
     *
     * It remains **containment, not a fix**, and the containment is now stricter than the surrounding
     * mechanism requires: a node pinned here still cannot become leader, but its peers would no longer
     * refuse the term it wanted to propose. So this suppresses an election that could otherwise have
     * succeeded, in exchange for reasons (1) and (2) above. Reaching the state at all now costs ~10^14
     * accepted frames rather than one, which is why it is contained rather than redesigned; whether the
     * trade is still the right one is open, and deliberately not decided here.
     *
     * What it does buy is the removal of #1833's headline complaint — *"no exception, no log line, every
     * node reports itself healthy"*: the node emits [RaftMetric.ElectionSuppressedTermCeiling] on every
     * attempt and warn-logs once.
     *
     * ### The state this does not warn about: a healthy leader sitting *on* the ceiling
     *
     * A cluster can legitimately be serving with a leader whose term is exactly the ceiling — an election
     * from `2^60 - 1` persists `2^60` and wins. Nothing is suppressed there and nothing should be: a
     * leader never increments its own term, so it replicates normally. But that cluster is **one
     * leader-lifetime from being unable to elect**: the moment that leader dies, is partitioned, or is
     * stepped down by `CheckQuorum`, every voter is pinned and no election can succeed. The guard fires
     * only at that transition — the earliest point at which anything is actually wrong — so a green
     * cluster can already be one failure away with no metric saying so.
     *
     * ### Placement
     *
     * One predicate, two emission sites — [onElectionTimeout] (the `PreVote` at `currentTerm + 1`) and
     * [startRealElection] (the `RequestVote`, and the `persistTermAndVote` that makes it durable).
     * Both are needed: `startRealElection` is entered directly from [onTimeoutNow] without passing
     * through [onElectionTimeout], and [onElectionTimeout] emits a frame before `startRealElection` is
     * ever reached. Guarding here rather than at the three `currentTerm` writers is deliberate — the
     * writers are already bounded (#1833/#1855); it is the *increment off* a bounded value that
     * escapes, and both increments live in these two functions.
     */
    private fun termPinnedAtCeiling(): Boolean = state.currentTerm >= MAX_PLAUSIBLE_TERM

    /**
     * The operator-facing diagnostic for [termPinnedAtCeiling], naming the election [attempt] suppressed.
     *
     * Built by a function rather than inlined so the `warn` and `debug` call sites share one text while
     * both stay lazy — neither builds the string unless its level is enabled.
     *
     * **Names both provenances, and orders the remediation accordingly.** A term at the ceiling is *not*
     * only reachable from a hostile frame: `storage.term()` is third-party input in every deployment (kuilt
     * ships no durable [RaftStorage]), so an adapter that returns a corrupt term — a truncated column, a
     * sign-extended `Int`, a torn read — reaches this state with no attacker and no malformed frame at all.
     * That is the case [checkedRestoredTerm] argues at length, and an operator told to hunt for an attacker
     * who does not exist would be sent to the wrong half of the system by the very line that is supposed to
     * be this change's whole deliverable. Local storage is checked first because it is the cheaper check and
     * the likelier cause.
     */
    private fun ceilingDiagnostic(attempt: String): String =
        "suppressed $attempt: currentTerm=${state.currentTerm} has reached the plausibility ceiling " +
            "$MAX_PLAUSIBLE_TERM, so the term this election must propose would be written to durable " +
            "storage successfully and then REFUSED BY THIS NODE'S OWN RESTORE GUARD ON ITS NEXT START, " +
            "leaving it permanently unbootable. The election is suppressed to keep this node startable, " +
            "and to keep the increment clear of Long overflow (#1886). This node can no longer be " +
            "elected and will not " +
            "recover. A term this high has two possible origins: this node's own durable storage (a " +
            "RaftStorage adapter that returned a corrupt term — no attacker required), or a malformed or " +
            "hostile frame from a peer. Inspect this node's persisted term first, then the peer that last " +
            "raised it; then re-provision this node from empty state."

    /**
     * Report the [termPinnedAtCeiling] condition on the engine's two observable surfaces.
     *
     * Never throws: this is reached from the actor loop on a path a remote frame controls, so a `require`
     * here would convert one hostile frame into permanent node death (#1818).
     *
     * Also closes the election-metric lifecycle. Both call sites return *before* the
     * [RaftMetric.ElectionTimedOut] emit that [startRealElection] would otherwise perform, and no
     * [RaftMetric.ElectionStarted] will ever follow, so a prior unresolved election's `ElectionTimedOut`
     * has to fire here or the `ElectionStarted → ElectionWon`/`ElectionTimedOut` pair documented on
     * [RaftMetric] never terminates. Mirrors `relinquishToFollower`'s identical closure.
     */
    private fun reportTermPinnedAtCeiling(attempt: String) {
        if (electionStartTime != null) {
            emitMetric(RaftMetric.ElectionTimedOut(electionStartTerm))
            electionStartTime = null
        }
        emitMetric(RaftMetric.ElectionSuppressedTermCeiling(state.currentTerm, MAX_PLAUSIBLE_TERM))
        // Latched to once per node — see ceilingWarnLogged. The metric above is not latched.
        if (ceilingWarnLogged) {
            debug { ceilingDiagnostic(attempt) }
        } else {
            ceilingWarnLogged = true
            logger.warn { "[raft:${transport.selfId}] ${ceilingDiagnostic(attempt)}" }
        }
    }

    private suspend fun onElectionTimeout() {
        if (_role.value is RaftRole.Leader) return
        // A re-timing-out Candidate (probe didn't gather quorum) drops back to follower role
        // for the probe phase so the role accurately reflects "not yet a candidate".
        _role.value = followerRole
        // #1886/#1897: the PreVote below would carry `currentTerm + 1`, above the ceiling. Peers no
        // longer refuse that term — #1897 made adoption bound the JUMP, so a peer at our term admits a
        // step of one — but this node must still not reach it: `checkedRestoredTerm` refuses a durable
        // term above the ceiling, so campaigning there ends in a node that cannot boot. Report it and
        // re-arm rather than campaign toward that state — re-arming keeps the metric repeating so the
        // condition reads as a level (the log is latched separately). See termPinnedAtCeiling: this
        // contains, it does not fix.
        //
        // Deliberately placed AFTER the role write, not before it. A node can land exactly ON the
        // ceiling and become a Candidate there (an election from `2^60 - 1` persists `2^60`, which the
        // wire bound admits); if that election then fails to win, guarding earlier would return with the
        // role still `Candidate` FOREVER — a permanently wrong `role` flow on the one change whose entire
        // product is diagnosability, and a direct contradiction of what this state reports. Ordering it
        // here also inherits the normal path's Learner handling: `followerRole` re-derives the role from
        // live `membershipState`, so a demoted node is corrected before `resetElectionTimeout` reads
        // `_role` to decide whether to re-arm at all (a learner must not).
        if (termPinnedAtCeiling()) {
            reportTermPinnedAtCeiling("pre-vote")
            resetElectionTimeout()
            return
        }
        val proposed = state.currentTerm + 1
        preVoteTerm = proposed
        preVoteRound++
        preVotesGranted.clear()
        preVotesGranted += transport.selfId
        resetElectionTimeout()
        // Single-voter (or all other voters already granted): self pre-vote satisfies quorum — skip probe.
        if (state.membershipState.voterQuorumReached(preVotesGranted - transport.selfId, transport.selfId)) { startRealElection(); return }
        emitTrace(RaftTraceEvent.PreVoteStarted(nextClock(), transport.selfId, proposed))
        val pv = RaftMessage.PreVote(proposed, state.lastLogIndex, state.lastLogTerm, preVoteRound)
        otherVoters.forEach { send(it, pv) }
    }

    /**
     * Gate the actual term bump behind a pre-vote quorum. Verbatim body of the old [onElectionTimeout].
     *
     * [leadershipTransfer] is threaded onto every [RaftMessage.RequestVote] this election emits. It is
     * `true` only when the election was triggered by a [RaftMessage.TimeoutNow] (a §3.10 graceful
     * transfer), granting the candidate §4.2.3 "permission to disrupt" a leader the recipients still
     * believe is alive. A normal (pre-vote-gated) election leaves it `false` so leader-stickiness holds.
     */
    private suspend fun startRealElection(leadershipTransfer: Boolean = false) {
        // #1886/#1897: `persistTermAndVote(currentTerm + 1, …)` below would write a term above the
        // ceiling to DURABLE storage, which [checkedRestoredTerm] then refuses to restart on (#1855),
        // bricking the next boot. That is now the WHOLE reason, and it is enough on its own: #1897 made
        // adoption bound the jump, so the RequestVote carrying that term is NOT refused by a peer at the
        // same term — it is admitted, and every voter that receives it persists the term too via
        // `stepDown`, spreading the unbootable state rather than containing it. Reached from
        // [onTimeoutNow] without passing [onElectionTimeout]'s guard, so this is a second, independent
        // check rather than a redundant one. See [termPinnedAtCeiling].
        if (termPinnedAtCeiling()) {
            preVoteTerm = null
            reportTermPinnedAtCeiling("election")
            resetElectionTimeout()
            return
        }
        preVoteTerm = null
        // If a prior election is still pending, it timed out — emit before overwriting the term.
        if (electionStartTime != null) {
            emitMetric(RaftMetric.ElectionTimedOut(electionStartTerm))
            logger.warn { "[raft:${transport.selfId}] election timed out for term $electionStartTerm" }
        }
        persistTermAndVote(state.currentTerm + 1, transport.selfId)
        votesGranted.clear()
        votesGranted += transport.selfId
        _role.value = RaftRole.Candidate
        _leader.value = null
        resetElectionTimeout()
        // Record the election start time and emit the metric.
        electionStartTime = TimeSource.Monotonic.markNow()
        electionStartTerm = state.currentTerm
        emitMetric(RaftMetric.ElectionStarted(state.currentTerm))
        logger.debug { "[raft:${transport.selfId}] election started for term ${state.currentTerm}" }
        // Single-voter cluster: self-vote already satisfies quorum — become leader immediately.
        if (state.membershipState.voterQuorumReached(votesGranted - transport.selfId, transport.selfId)) { becomeLeader(); return }
        emitTrace(RaftTraceEvent.Timeout(nextClock(), transport.selfId, state.currentTerm))
        val rv = RaftMessage.RequestVote(state.currentTerm, state.lastLogIndex, state.lastLogTerm, leadershipTransfer)
        otherVoters.forEach { peer ->
            emitTrace(RaftTraceEvent.RequestVote(nextClock(), transport.selfId, peer, state.currentTerm, state.lastLogIndex, state.lastLogTerm))
            send(peer, rv)
        }
    }

    private suspend fun onRequestVote(from: NodeId, m: RaftMessage.RequestVote) {
        // §4.2.3 leader-stickiness: a node within its leader-lease rejects a higher-term
        // RequestVote without adopting the term, preventing a partitioned voter from deposing
        // a healthy leader the moment it regains connectivity.
        //
        // §3.10 exception: if we are the leader and a transfer to `from` is in flight, we must
        // NOT apply leader-stickiness — the transfer explicitly authorises this candidate to run
        // an election. Step down before normal vote processing so the vote is granted naturally.
        //
        // §4.2.3 exception: a transfer target's RequestVote carries the [leadershipTransfer] "permission
        // to disrupt" flag. Every OTHER voter (not just the old leader) must process it even while it
        // believes a leader is alive — otherwise at n>=4 the target loses its first election to
        // lease-holding voters. The flag ONLY bypasses this stickiness deny; term / §5.4.1 log / already-
        // voted checks below still run.
        val isTransferCandidate = _role.value is RaftRole.Leader && transfer.inFlightTarget == from
        if (!isTransferCandidate && !m.leadershipTransfer && leaderAlive && m.term > state.currentTerm) {
            emitTrace(RaftTraceEvent.VoteDenied(nextClock(), transport.selfId, from, m.term, DenyReason.LeaderAlive))
            send(from, RaftMessage.RequestVoteResponse(state.currentTerm, false))
            return
        }
        if (m.term > state.currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        val logOk = isLogUpToDate(ours = state.lastLogPosition, candidate = m.lastLogPosition)
        // The candidate is [from] — the transport's origin — never a payload field (#1912).
        val grant = m.term == state.currentTerm && logOk && (state.votedFor == null || state.votedFor == from)
        if (grant) {
            persistVote(from)
            resetElectionTimeout()
            emitTrace(RaftTraceEvent.VoteGranted(nextClock(), transport.selfId, from, m.term))
        } else {
            val reason = when {
                m.term < state.currentTerm -> DenyReason.StaleTerm
                state.votedFor != null && state.votedFor != from -> DenyReason.AlreadyVoted
                else -> DenyReason.LogNotUpToDate
            }
            emitTrace(RaftTraceEvent.VoteDenied(nextClock(), transport.selfId, from, m.term, reason))
        }
        send(from, RaftMessage.RequestVoteResponse(state.currentTerm, grant))
    }

    private suspend fun onRequestVoteResponse(from: NodeId, m: RaftMessage.RequestVoteResponse) {
        if (m.term > state.currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Candidate || m.term != state.currentTerm) return
        if (m.voteGranted) {
            votesGranted += from
            if (state.membershipState.voterQuorumReached(votesGranted - transport.selfId, transport.selfId)) becomeLeader()
        }
    }

    /**
     * Respond to a pre-vote request: grant iff the proposed term is higher than ours, the
     * candidate's log is at least as up-to-date, and we have not heard from a live leader recently.
     * Does NOT mutate term, votedFor, or timers — pre-vote is hypothesis-only.
     */
    private suspend fun onPreVote(from: NodeId, m: RaftMessage.PreVote) {
        val logOk = isLogUpToDate(ours = state.lastLogPosition, candidate = m.lastLogPosition)
        val grant = m.term > state.currentTerm && logOk && !leaderAlive
        if (grant) {
            emitTrace(RaftTraceEvent.PreVoteGranted(nextClock(), transport.selfId, from, m.term))
        } else {
            val reason = when {
                leaderAlive    -> DenyReason.LeaderAlive
                !logOk         -> DenyReason.LogNotUpToDate
                else           -> DenyReason.StaleTerm
            }
            emitTrace(RaftTraceEvent.PreVoteDenied(nextClock(), transport.selfId, from, m.term, reason))
        }
        send(from, RaftMessage.PreVoteResponse(state.currentTerm, grant, m.term, m.round))
    }

    private suspend fun onPreVoteResponse(from: NodeId, m: RaftMessage.PreVoteResponse) {
        if (m.term > state.currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (preVoteTerm == null || m.proposedTerm != preVoteTerm || m.round != preVoteRound) return
        if (m.voteGranted) {
            preVotesGranted += from
            if (state.membershipState.voterQuorumReached(preVotesGranted - transport.selfId, transport.selfId)) startRealElection()
        }
    }

    private suspend fun becomeLeader() {
        val elapsed = electionStartTime?.elapsedNow() ?: Duration.ZERO
        electionStartTime = null
        emitMetric(RaftMetric.ElectionWon(state.currentTerm, elapsed))
        logger.debug { "[raft:${transport.selfId}] won election for term ${state.currentTerm} in ${elapsed.inWholeMilliseconds}ms" }

        _role.value = RaftRole.Leader
        _leader.value = transport.selfId
        // Winning the term IS the establishing event for it (#1906), and it cannot collide with an
        // earlier pin: becomeLeader is reachable only from Candidate, and becoming a Candidate bumps
        // the term, which invalidates whatever was pinned for the previous one.
        pinLeaderForTerm(transport.selfId)
        electionJob?.cancel()
        leaderAlive = true
        leaderLeaseJob?.cancel()
        val nextIdx = state.lastLogIndex + 1L
        otherMembers.forEach { p ->
            state.nextIndex[p] = nextIdx
            state.matchIndex[p] = 0L
        }
        emitTrace(RaftTraceEvent.BecomeLeader(nextClock(), transport.selfId, state.currentTerm))
        heartbeatJob = scope.launch {
            while (true) {
                cmd.trySend(EngineCommand.HeartbeatTick)
                // The Duration overload, NOT `inWholeMilliseconds` (#1991). That floors, so any interval
                // under a millisecond became delay(0) and this loop spun as fast as the dispatcher would
                // schedule it — a hot loop on the leader in production, and under virtual time a loop that
                // never yields the clock, so a test hangs rather than fails. delay(Duration) quantises to
                // the same millisecond grid but rounds *up*, so it has no zero to fall into.
                delay(raftConfig.heartbeatInterval)
            }
        }
        recentVoterContacts.clear()
        quorumCheckJob?.cancel()
        quorumCheckJob = scope.launch {
            while (true) {
                delay(randomElectionTimeoutMillis())
                cmd.trySend(EngineCommand.QuorumCheck)
            }
        }
        // ReadIndex state: reset for this leadership term. Any reads queued from a prior term
        // are already failed by relinquishToFollower; start fresh.
        readIndexTracker.reset()
        // Transfer state: a pending transfer can survive a step-down (#1243 — it resolves only on the
        // target's leader-authored message or its auto-timeout). If WE won an election first, the target
        // did not: fail the pending transfer so the resumed leadership doesn't inherit its propose gate.
        transfer.onSelfElected()?.let {
            debug { "becomeLeader: pending leadership transfer to ${it.value} abandoned — this node won instead" }
        }
        // §5.4.2: append a no-op from the new term so the commit guard (entry.term == currentTerm)
        // can advance commitIndex over any prior-term entries inherited from a previous leader.
        // appendNoOp arms readIndexTracker's no-op gate (onNoOpAppended) so readIndex() knows when to gate.
        appendNoOp()
        // §4.1: complete a transition this leader inherited already-committed (see below). Runs after the
        // no-op is appended so a single-voter leader that commits the no-op inline in appendNoOp has already
        // advanced commitIndex — and after it so the Simple(C_new) lands at lastLogIndex+1 above the no-op.
        finalizeInheritedCommittedJoint()
    }

    /**
     * §4.1 orphaned-Joint completion on election. The normal Joint→Simple(C_new) auto-append fires ONLY
     * from [advanceCommit]'s commit-window scan (via [onConfigCommitted]). A newly-elected leader can
     * inherit a Joint whose entry is ALREADY committed — commit was seeded DIRECTLY, bypassing any commit
     * window: an InstallSnapshot cut at/after the Joint ([finalizeInstalledSnapshot]) or restart recovery
     * from such a snapshot (`init`). No future commit window then contains the Joint entry, so
     * `Simple(C_new)` is never appended and the cluster stays in Joint forever — every [onChangeMembership]
     * rejected by the settled-Simple guard, the dual quorum persisting indefinitely.
     *
     * When we become leader holding a committed Joint, append `Simple(C_new)` here — exactly what
     * [onConfigCommitted]'s Joint branch would have done — reusing [appendConfigEntry] so the behaviour
     * matches the normal path (the trailing `Simple(C_new)` commits, [onConfigCommitted]'s Simple branch
     * then wakes any pending change and runs the removed-leader step-down).
     *
     * Two guards keep this from stepping on the normal path:
     *  - `membershipState is Joint` ⇒ no `Simple(C_new)` follows the Joint in the log yet (membership always
     *    reflects the last config entry). This is the same double-append guard [onConfigCommitted] uses; a
     *    leader that inherited the Joint's trailing Simple already resolves to Simple and is skipped.
     *  - The Joint must already be committed (`currentCommitIndex >= jointIndex`). A Joint still in flight
     *    (its entry above `commitIndex`) is finished by the commit-window path when the no-op carries it to
     *    commit — pre-empting that here would double-append.
     */
    private suspend fun finalizeInheritedCommittedJoint() {
        val joint = state.membershipState as? MembershipState.Joint ?: return
        // The Joint entry's index: its position in the live log, or — when it was compacted into the
        // snapshot (no config entry survives in the log) — the snapshotIndex, which is committed by
        // definition. Since membershipState is Joint, the last config entry (when present) IS the Joint.
        val jointIndex = state.log.lastOrNull { it.config != null }?.index ?: state.snapshotIndex
        if (state.currentCommitIndex < jointIndex) return // still in flight — the commit-window path finishes it
        debug { "becomeLeader: inherited a committed Joint (index=$jointIndex) — appending Simple(C_new=${joint.new})" }
        appendConfigEntry(ConfigPayload(old = null, new = joint.new))
    }

    private suspend fun appendNoOp() {
        val noOpIndex = state.lastLogIndex + 1L
        readIndexTracker.onNoOpAppended(noOpIndex)   // gate for readIndex(): must not return before this commits
        val noOp = LogEntry(noOpIndex, state.currentTerm, byteArrayOf(), isNoOp = true)
        state.log += noOp
        storage.appendEntries(listOf(noOp))
        otherMembers.forEach { sendAppendEntries(it) }
        // Single-voter: no peers will ACK — check for immediate commit.
        tryAdvanceLeaderCommit()
    }

    private suspend fun stepDown(newTerm: Long, reason: StepDownReason) {
        // higher term: adopt it, then relinquish leadership
        persistTermAndVote(newTerm, null)
        relinquishToFollower(reason)
    }

    /**
     * Same-term step-down: relinquish leadership without bumping the term (CheckQuorum path).
     * The term is already current — no persistence required.
     */
    private suspend fun stepDownToFollower(reason: StepDownReason) = relinquishToFollower(reason)

    /**
     * Demote to the appropriate follower/learner role on contact from a live leader — a valid
     * AppendEntries or InstallSnapshot ([onAppendEntries] / [onInstallSnapshot]). The common case, a
     * Follower/Candidate/Learner recognising the leader, is a cheap role assignment.
     *
     * Defense-in-depth (#1250): if this node is somehow STILL [RaftRole.Leader] when a same-term
     * leader-contact message arrives, Election Safety was violated (two leaders in one term — an
     * F1-class bug, or storage misbehaviour). A bare `_role.value = followerRole` would leave the
     * deposed leader's heartbeat/quorum-check/lease jobs running, its pending proposals/config-changes/
     * reads unresolved, and its dedup cache populated. Route that demotion through the same-term
     * relinquish path so all of it tears down. Unreachable while Election Safety holds; free otherwise.
     * (A higher-term message already relinquished via [stepDown] before we get here, so this only ever
     * observes Leader in the same-term, Election-Safety-violating case.)
     */
    private suspend fun demoteToFollowerOnLeaderContact() {
        if (_role.value is RaftRole.Leader) {
            stepDownToFollower(StepDownReason.AppendEntriesFromLeader)
        } else {
            _role.value = followerRole
        }
    }

    /**
     * Shared leadership-relinquish body: cancel all leader jobs, fail pending proposals,
     * reset follower state, emit the trace event, and restart the election timer.
     * Called by both [stepDown] (after a term adoption) and [stepDownToFollower] (same-term).
     *
     * An in-flight leadership transfer is deliberately NOT resolved here (#1243): the sender of the
     * message that triggered the step-down identifies neither the election winner nor even a campaigner,
     * so completing (or failing) the transfer on step-down mis-resolves it on degraded networks. The
     * transfer stays pending; it completes only via `transfer.onLeaderElected` — a leader-authored
     * message *sent by* the target at a higher term ([onAppendEntries]/[onInstallSnapshot]) — and
     * otherwise fails on its auto-timeout, an explicit cancel, or this node's own re-election
     * ([becomeLeader] → `transfer.onSelfElected`).
     */
    private suspend fun relinquishToFollower(reason: StepDownReason) {
        if (_role.value is RaftRole.Leader) {
            heartbeatJob?.cancel()
            quorumCheckJob?.cancel()
            val cause = LeadershipLostException()
            failPending(cause)
            failPendingConfigChange(cause)
            readIndexTracker.failAll(LeadershipLostException("lost leadership before read confirmed"))
            debug { "relinquishToFollower($reason): failed in-flight proposals, config change, and pending reads" }
            snapshotSender.abandonAll()   // leader-only transfer state — abandon any in-flight snapshot sends
            dedupCache.clear()     // leader-only best-effort dedup cache — a new leader starts cold
            // An in-flight leadership transfer intentionally survives this step-down — see the KDoc.
        }
        leaderAlive = false
        leaderLeaseJob?.cancel()
        preVoteTerm = null          // discard any in-flight pre-vote probe when stepping down
        // If we were a candidate, the election for the prior term implicitly timed out.
        if (_role.value is RaftRole.Candidate && electionStartTime != null) {
            emitMetric(RaftMetric.ElectionTimedOut(electionStartTerm))
            electionStartTime = null
        }
        _role.value = followerRole
        _leader.value = null
        emitTrace(RaftTraceEvent.BecomeFollower(nextClock(), transport.selfId, state.currentTerm, reason))
        resetElectionTimeout()
    }

    /**
     * CheckQuorum tick: count voters that reached us this window (+1 for self).
     * If fewer than quorumSize, the leader is on the minority side of a partition — step down
     * at the same term (no term bump).
     */
    private suspend fun onQuorumCheck() {
        if (_role.value !is RaftRole.Leader) return
        val contacted = recentVoterContacts.toSet()
        recentVoterContacts.clear()
        // membershipState.quorumOfContacts credits self per voter set (only when self ∈ that set).
        if (!state.membershipState.quorumOfContacts(contacted, transport.selfId)) {
            debug { "onQuorumCheck: lost quorum — contacted=$contacted membershipState=${state.membershipState}" }
            stepDownToFollower(StepDownReason.LostQuorum)
        }
    }

    // ── ReadIndex ─────────────────────────────────────────────────────────────

    /**
     * Handle a readIndex() request from the actor channel.
     *
     * Non-leader: complete exceptionally with [NotLeaderException] immediately. The leadership check is
     * engine state; the freshness/gate arithmetic is delegated to [readIndexTracker]. On a
     * [ReadIndexTracker.ReadDecision.ResolveNow] (self alone is a fresh quorum) the engine completes the
     * deferred here; on [ReadIndexTracker.ReadDecision.Queued] it logs and awaits a later quorum ACK via
     * [onAppendEntriesResponse] → [ReadIndexTracker.resolve]; on [ReadIndexTracker.ReadDecision.Gated]
     * (the §8 current-term-no-op gate is not yet crossed) the tracker parks the re-invocation below —
     * redelivered from [advanceCommit] via [ReadIndexTracker.onNoOpCommitted] once the no-op commits.
     */
    private fun onRequestReadIndex(deferred: CompletableDeferred<Long>) {
        if (_role.value !is RaftRole.Leader) {
            deferred.completeExceptionally(NotLeaderException("readIndex: not the current leader"))
            return
        }
        val decision = readIndexTracker.request(
            deferred = deferred,
            commitIndex = state.currentCommitIndex,
            membership = state.membershipState,
            selfId = transport.selfId,
            reinvoke = { onRequestReadIndex(deferred) },
        )
        when (decision) {
            ReadIndexTracker.ReadDecision.Gated -> Unit
            is ReadIndexTracker.ReadDecision.ResolveNow -> deferred.complete(decision.readIndex)
            is ReadIndexTracker.ReadDecision.Queued ->
                debug { "onRequestReadIndex: queued ri=${decision.readIndex} sinceRound=${decision.sinceRound} pendingReads=${decision.pendingCount}" }
        }
    }

    // ── Log replication ───────────────────────────────────────────────────────

    private suspend fun onHeartbeat() {
        if (_role.value !is RaftRole.Leader) return
        readIndexTracker.bumpRound()   // bump the round counter before sending so ACKs that arrive back reference a round > any pre-send sinceRound
        otherMembers.forEach { sendAppendEntries(it) }
    }

    private suspend fun sendAppendEntries(peer: NodeId) {
        val ni = state.nextIndex[peer] ?: 1L
        // §7: the prefix the follower still needs has been compacted away — divert to InstallSnapshot.
        if (ni <= state.snapshotIndex) {
            debug { "sendAppendEntries($peer): ni=$ni <= snapshotIndex=${state.snapshotIndex} → divert to InstallSnapshot" }
            // #1222: onHeartbeat diverts here every tick for the whole transfer (nextIndex stays
            // ≤ snapshotIndex until it completes), so [sendSnapshotChunk] must RESUME an in-flight
            // transfer from the follower's acked offset — never restart it from offset 0. It loads a
            // fresh snapshot only when there is no transfer in flight; any rewind is follower-driven
            // via ReAdvertise(0). (A `restart = true` affordance once lived here and caused the
            // livelock — it is deliberately gone so it cannot be reintroduced.)
            sendSnapshotChunk(peer); return
        }
        val prevIndex = ni - 1L
        val prevTerm = if (prevIndex == state.snapshotIndex) {
            state.snapshotTerm
        } else {
            state.entryAt(prevIndex)?.term
                ?: error("prevTerm for in-window index $prevIndex missing (snapshotIndex=${state.snapshotIndex}, lastLogIndex=${state.lastLogIndex})")
        }
        val entries = logSliceFrom(state.log, state.snapshotIndex, ni)
        debug { "sendAppendEntries($peer): ni=$ni prevIndex=$prevIndex prevTerm=$prevTerm entries=${entries.size} commit=${state.currentCommitIndex}" }
        emitTrace(
            RaftTraceEvent.AppendEntries(
                clock = nextClock(),
                from = transport.selfId,
                to = peer,
                term = state.currentTerm,
                prevLogIndex = prevIndex,
                prevLogTerm = prevTerm,
                entryCount = entries.size,
                leaderCommit = state.currentCommitIndex,
            )
        )
        send(
            peer,
            RaftMessage.AppendEntries(
                term = state.currentTerm,
                prevLogIndex = prevIndex,
                prevLogTerm = prevTerm,
                entries = entries,
                leaderCommit = state.currentCommitIndex,
                round = readIndexTracker.round,
            )
        )
    }

    // ── §7 InstallSnapshot ──────────────────────────────────────────────────────

    /**
     * Bytes carried per chunk: the lesser of the transport's payload limit and the configured
     * ceiling, minus a fixed header budget for the CBOR envelope, floored at 1.
     */
    private fun chunkBytes(): Int {
        val cap = transport.maxPayloadBytes?.let { minOf(it, raftConfig.snapshotChunkCeiling) }
            ?: raftConfig.snapshotChunkCeiling
        return maxOf(1, cap - HEADER_BUDGET)
    }

    /**
     * Sends the next snapshot chunk to [peer], resuming its in-flight transfer from the peer's acked
     * offset (loading the stored snapshot fresh from offset 0 only when no transfer is in flight). A
     * restart is never initiated here — the follower drives any rewind via its `ReAdvertise(0)` ack.
     * The load/slice/advance arithmetic lives in [snapshotSender]; the engine keeps the trace/send
     * side-effects.
     */
    private suspend fun sendSnapshotChunk(peer: NodeId) {
        val chunk = snapshotSender.nextChunk(peer) ?: return   // nothing to send yet
        val start = chunk.offset.toInt()
        val end = start + chunk.data.size
        debug { "sendSnapshotChunk($peer): through=${chunk.meta.lastIncludedIndex} offset=$start..$end/${chunk.totalBytes} done=${chunk.done}" }
        emitTrace(
            RaftTraceEvent.InstallSnapshot(
                nextClock(), transport.selfId, peer, chunk.meta.lastIncludedIndex, chunk.offset, chunk.done,
            )
        )
        send(
            peer,
            RaftMessage.InstallSnapshot(
                term = state.currentTerm,
                lastIncludedIndex = chunk.meta.lastIncludedIndex,
                lastIncludedTerm = chunk.meta.lastIncludedTerm,
                offset = chunk.offset,
                data = chunk.data,
                done = chunk.done,
                config = chunk.meta.config,
                round = readIndexTracker.round,
            )
        )
    }

    /** Leader: advance or finish a snapshot transfer in response to a follower's ack. */
    private suspend fun onInstallSnapshotResponse(from: NodeId, m: RaftMessage.InstallSnapshotResponse) {
        if (m.term > state.currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != state.currentTerm) return
        recentVoterContacts += from                // reachability signal for CheckQuorum
        readIndexTracker.recordAck(from, m.echoedRound)   // credit ACK to the round it actually responded to (BLOCKER 1a)
        confirmFreshReads()                        // ReadIndex: snapshot ACKs count as freshness evidence
        when (val outcome = snapshotSender.onAck(from, m.nextOffset)) {
            SnapshotSender.AckOutcome.NoTransfer -> return
            is SnapshotSender.AckOutcome.Complete -> {            // fully received
                state.matchIndex[from] = maxOf(state.matchIndex[from] ?: 0L, outcome.lastIncludedIndex)
                state.nextIndex[from] = outcome.lastIncludedIndex + 1L
                debug { "onInstallSnapshotResponse($from): COMPLETE through=${outcome.lastIncludedIndex} → nextIndex=${state.nextIndex[from]}, resume AppendEntries" }
                sendAppendEntries(from)                           // resume normal replication
                tryAdvanceLeaderCommit()
            }
            SnapshotSender.AckOutcome.SendNext -> {
                debug { "onInstallSnapshotResponse($from): ack offset=${m.nextOffset}, send next chunk" }
                sendSnapshotChunk(from)                            // next chunk
            }
        }
    }

    /**
     * §5.4.1 / §5.3 frame-internal well-formedness of an [RaftMessage.InstallSnapshot] chunk's
     * snapshot metadata (issue #1868) — the sibling of [isWellFormedBatch] on the snapshot lane.
     *
     * `lastIncludedIndex` / `lastIncludedTerm` were inspected by nothing. [isWellFormedBatch] guards
     * [RaftMessage.AppendEntries] and nothing else, and [onMessage]'s term bound guards a frame's own
     * `term` and nothing else — so a single frame carrying the recipient's *own*
     * current term (honest enough to clear both the stale-term check and the §5.2 leader-authority
     * gate) reached [finalizeInstalledSnapshot] with arbitrary metadata:
     *
     * - `lastIncludedIndex <= currentCommitIndex` is false at `Long.MAX_VALUE - 1`, so the etcd-style
     *   restore guard (#1219 / #1220) does not fire.
     * - [RaftState.entryAt] is null there, so the Log Matching check falls to the **discard-whole**
     *   branch: `truncateFrom(0)` and `state.log.clear()`.
     * - `snapshotIndex`, `snapshotTerm`, `currentCommitIndex` and `_commitIndex` are then set from
     *   the frame — a fabricated commit index over an emptied log.
     *
     * [RaftState.lastLogTerm] falls back to `snapshotTerm` when the log is empty, so the victim's
     * [RaftState.lastLogPosition] becomes `(Long.MAX_VALUE, Long.MAX_VALUE - 1)`: the same §5.4.1
     * lexicographic domination (§5.4 / Figure 3.2) [isWellFormedBatch] prevents on its own lane. The
     * victim then denies every vote and wins every election it enters while holding none of the
     * committed entries a legitimate leader must have.
     *
     * Checkable without trust or extra state, exactly as for a batch: a snapshot's `lastIncludedTerm`
     * is the term of a log entry the sender held, and a node's log never carries a term above its own
     * `currentTerm`, which is what the frame states as `term`. So `lastIncludedTerm <= term` — the
     * identical §5.3 argument [isWellFormedBatch] makes about entry terms. The `MAX_PLAUSIBLE_TERM`
     * ceiling is folded into the same bound, and is enforced *here* rather than inherited — which is
     * what makes this check survive #1897 unchanged, since [onMessage] no longer bounds `term` against
     * that constant at all.
     *
     * **Both halves of the position are bounded, because §5.4.1 needs only one of them.**
     * [LogPosition] orders by `(term, index)` lexicographically and [isLogUpToDate] is
     * `candidate >= ours`, so tying on term and winning on index dominates just as surely as a huge
     * term does. Bounding `lastIncludedTerm` alone left the violation fully reachable via
     * `lastIncludedTerm == term` — a value this check must accept — with the attack moved into
     * `lastIncludedIndex`. Hence [MAX_PLAUSIBLE_INDEX] alongside the term bound.
     *
     * **What this does NOT establish.** A plausibility ceiling rules out the *implausible* range only.
     * Nothing in the frame distinguishes a forged in-range snapshot from a legitimate one sent by a
     * far-ahead leader — a snapshot exists precisely to jump a follower past its own log (§7), so
     * "far ahead" is not evidence of anything. Snapshot metadata is **unauthenticated**, and within
     * `0..MAX_PLAUSIBLE_INDEX` a Byzantine voter can still advance a follower's `snapshotIndex`,
     * `commitIndex` and compaction floor to a position it never reached, and wipe its log. That is
     * not fixable at this boundary; it needs authentication or a cross-frame invariant. See #1876.
     * One further unvalidated field of this same frame is out of scope here: `config` (#1880). The
     * reassembly buffer behind `done = false` is no longer unbounded — it is capped by
     * `RaftConfig.snapshotTotalCeiling` in [SnapshotReceiver] (#1881) — but that is a resource bound,
     * not a validity one, and it lives there rather than in this frame-shape check.
     *
     * **Disposition: drop the frame, don't ack it.** No honest leader can emit one —
     * [sendSnapshotChunk] copies [SnapshotMeta] from a snapshot it stored while at its own term — so
     * there is no honest sender to answer, and an [RaftMessage.InstallSnapshotResponse] would hand a
     * forger a free lever on the leader's [SnapshotSender] transfer state. Dropping mirrors
     * [isWellFormedBatch] and the §5.2 leader-authority gate in [onMessage]. It is deliberately not a
     * `require`: this runs inside the engine's actor loop, whose `try`/`finally` has no `catch`, so a
     * throw would convert a malformed frame into permanent node death (#1818).
     *
     * Called before the term check, so a malformed frame never adopts its term or resets the
     * recipient's election timeout either.
     */
    private fun isWellFormedSnapshotChunk(from: NodeId, m: RaftMessage.InstallSnapshot): Boolean {
        if (m.lastIncludedIndex < 0L || m.lastIncludedIndex > MAX_PLAUSIBLE_INDEX) {
            debug {
                "onInstallSnapshot($from): DROP — lastIncludedIndex=${m.lastIncludedIndex} outside 0..$MAX_PLAUSIBLE_INDEX"
            }
            return false
        }
        val termCeiling = minOf(m.term, MAX_PLAUSIBLE_TERM)
        if (m.lastIncludedTerm < 0L || m.lastIncludedTerm > termCeiling) {
            debug {
                "onInstallSnapshot($from): DROP — lastIncludedTerm=${m.lastIncludedTerm} outside 0..$termCeiling " +
                    "(term=${m.term}, MAX_PLAUSIBLE_TERM=$MAX_PLAUSIBLE_TERM; no snapshot may carry a term above the leader's)"
            }
            return false
        }
        return true
    }

    /**
     * §5.4 cross-check of an [RaftMessage.InstallSnapshot]'s `lastIncludedTerm` against **local
     * committed state** (issue #1910) — the tighter sibling of [isWellFormedSnapshotChunk]'s
     * frame-internal bound.
     *
     * [isWellFormedSnapshotChunk] admits any `lastIncludedTerm` in `0..min(term, MAX_PLAUSIBLE_TERM)`,
     * which is everything a *stale, replayed or forged* frame naming a real earlier term needs. There
     * is a tighter bound available from state the recipient already holds:
     *
     * > `lastIncludedTerm` must be at or above the term of our own entry at [RaftState.currentCommitIndex].
     *
     * **Soundness — the floor never rejects a legitimate snapshot.** Let `C` be our commit frontier.
     * The frame cleared the stale-term check, so the sender's term is at least ours, hence at least
     * the term in which we committed `C`; by Leader Completeness (§5.4 / Figure 3.2) the sender's log
     * therefore contains our committed entry at `C` with the same term. A snapshot covering an index
     * above `C` is a compaction of a prefix that includes `C`, and terms are non-decreasing along a
     * log, so its `lastIncludedTerm` is at or above `C`'s term.
     *
     * **The floor is the term at `currentCommitIndex`, NOT [RaftState.lastLogTerm].** Uncommitted
     * entries can legitimately be overwritten by a snapshot carrying a *lower* term — a node holding
     * an uncommitted term-7 entry can be caught up by a legitimate term-8 leader whose log has term 5
     * at that index — so keying to `lastLogTerm` would reject honest snapshots. [RaftState.entryAt]
     * returns null at `currentCommitIndex` exactly when `currentCommitIndex == snapshotIndex` (the
     * index is at or below the compaction floor), hence the [RaftState.snapshotTerm] fallback, which
     * is by definition the term at that index; `snapshotIndex <= currentCommitIndex` always holds —
     * [finalizeInstalledSnapshot] and [onCompact] raise commit to the snapshot boundary, restore
     * seeds it the same way — so there is no third case.
     *
     * **Gated on the snapshot advancing our frontier**, mirroring [finalizeInstalledSnapshot]'s
     * `lastIncludedIndex <= currentCommitIndex` guard. The soundness argument holds only above `C`:
     * an honest *behind-commit* snapshot (a delayed duplicate, §7's retransmission case) has its
     * boundary at an index at or below `C`, where the sender's term is legitimately *lower* than
     * `C`'s. Those frames are inert — [finalizeInstalledSnapshot] acks and ignores them (#1219/#1220)
     * — and must keep being acked rather than dropped, so they are exempted here rather than
     * laundered through a bound that does not apply to them.
     *
     * **Disposition: drop the frame, don't ack it**, as for [isWellFormedSnapshotChunk] — no honest
     * leader can emit one, so there is no honest sender to answer, and a reply would hand a forger a
     * lever on the sender's [SnapshotSender] transfer state. Deliberately not a `require`: this runs
     * inside the engine's actor loop, whose `try`/`finally` has no `catch`, so a throw would convert a
     * remote frame into permanent node death (#1818).
     *
     * Called at chunk 0, *before* [SnapshotReceiver] reassembles a payload we would refuse anyway
     * (composing with the reassembly ceiling of #1881), and before [stepDown]/[resetElectionTimeout],
     * so a refused frame grants its sender no leader authority either. It is called *after* the
     * stale-term reply, though — a frame from a leader at a term below ours fails the soundness
     * argument's premise, and the right disposition for it is the §5.1 term reply that deposes the
     * stale sender, not a silent drop.
     *
     * **What this does NOT establish.** The floor stops a *low*-term forgery and a stale/replayed or
     * buggy sender. The §5.4.1 domination lever wants a *high* `lastIncludedTerm`, and the attacker
     * picks the term — so this is bounded hardening, not a fix for the Byzantine case (#1876/#1907).
     * The decisive check — "only the node I recognise as leader for this term may install a snapshot"
     * — was blocked on there being a trustworthy leader identity to check against. [leaderForTerm]
     * (#1906) is now that identity, and [onInstallSnapshot] enforces adopt-if-unset-else-must-match
     * against it, so a snapshot from a peer that is not this term's established leader is already
     * dropped. Promoting that from an adoption rule to a full authority check — refusing an install
     * while the term's leader is genuinely unknown — is #1910's deferred half and is not done here.
     */
    private fun clearsCommittedTermFloor(from: NodeId, m: RaftMessage.InstallSnapshot): Boolean {
        if (m.lastIncludedIndex <= state.currentCommitIndex) return true
        val floor = state.entryAt(state.currentCommitIndex)?.term ?: state.snapshotTerm
        if (m.lastIncludedTerm < floor) {
            debug {
                "onInstallSnapshot($from): DROP — lastIncludedTerm=${m.lastIncludedTerm} below our committed floor " +
                    "$floor (the term of our own entry at commitIndex=${state.currentCommitIndex}); no snapshot " +
                    "covering ${m.lastIncludedIndex} can predate an entry we have committed"
            }
            return false
        }
        return true
    }

    /** Follower: reassemble chunks in order, then install the snapshot once the final chunk arrives. */
    private suspend fun onInstallSnapshot(from: NodeId, m: RaftMessage.InstallSnapshot) {
        if (!isWellFormedSnapshotChunk(from, m)) return
        if (m.term < state.currentTerm) { send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, 0L)); return }
        if (!clearsCommittedTermFloor(from, m)) return
        if (m.term > state.currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        demoteToFollowerOnLeaderContact()
        // #1906: the demotion above fires FIRST and unconditionally — a same-term leader-contact
        // reaching a node that still believes it is Leader must still tear that leadership down
        // (#1250) — but the sender does not thereby get to install itself as this term's leader. The
        // two concerns are separable and both are kept; see [adoptLeaderForTerm]. Placed before every
        // remaining side-effect so a refused frame also cannot cancel an in-flight pre-vote probe or
        // hold this node's election timer open.
        if (!adoptLeaderForTerm(from, "onInstallSnapshot")) return
        preVoteTerm = null          // a live leader appeared — cancel any in-flight pre-vote probe
        // The leader is [from]: the peer that authored this frame, not a payload claim (#1912).
        _leader.value = from
        // §3.10 (#1243): a leader-authored message from the transfer target at a higher term is the
        // conclusive transfer-success signal — the target actually won its election.
        if (transfer.onLeaderElected(from, m.term)) {
            debug { "leadership transfer confirmed: ${from.value} is leader at term ${m.term}" }
        }
        resetElectionTimeout()
        armLeaderLease()

        val meta = SnapshotMeta(m.lastIncludedIndex, m.lastIncludedTerm, m.config)
        when (val outcome = snapshotReceiver.onChunk(meta, m.offset, m.data, m.done)) {
            is SnapshotReceiver.ChunkOutcome.ReAdvertise -> {
                debug { "onInstallSnapshot($from): out-of-order offset=${m.offset} (have=${outcome.haveOffset}) → re-advertise, await resend" }
                send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, outcome.haveOffset, echoedRound = m.round))
            }
            is SnapshotReceiver.ChunkOutcome.TooLarge -> {
                // The reassembly was discarded before the append, so we hold nothing: re-advertise 0,
                // exactly as the ReAdvertise arm does when the buffer is gone. Unlike a malformed frame
                // this IS answerable — an honest leader can legitimately have an oversized snapshot, and
                // the ack is what tells it to restart rather than keep feeding a buffer that is no longer
                // there. If its snapshot is genuinely above the ceiling the transfer will not converge;
                // that is the intended loud, configurable failure in place of an OOM (#1881) — and
                // `loud` is what reportSnapshotRejectedSizeCeiling makes true (#1926): before it, the
                // whole signal was one `debug` line, so an operator saw a follower that never caught
                // up with nothing naming the knob that would fix it.
                reportSnapshotRejectedSizeCeiling(from, meta, outcome)
                send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, 0L, echoedRound = m.round))
            }
            is SnapshotReceiver.ChunkOutcome.AwaitMore -> {
                debug { "onInstallSnapshot($from): chunk offset=${m.offset} accepted (have=${outcome.haveOffset}), await more" }
                send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, outcome.haveOffset, echoedRound = m.round))
            }
            is SnapshotReceiver.ChunkOutcome.Complete -> finalizeInstalledSnapshot(from, m, outcome.meta, outcome.bytes)
        }
    }

    /**
     * Report a `snapshotTotalCeiling` rejection on the engine's two observable surfaces (#1926).
     *
     * The disposition is not this function's business — [onInstallSnapshot] has already discarded the
     * reassembly and is about to re-advertise `0`, and that is correct either way. What this adds is a
     * *name* for the resulting state, because the two things a rejection can mean look identical in the
     * frame and are told apart only by whether it keeps happening:
     *
     * - **Once** is the bound working. The sender picks `done`, so a peer can hold a follower in
     *   reassembly forever and grow the buffer until the process dies; the ceiling is what stops that
     *   (#1881), and a single rejection is not an operator's problem.
     * - **Repeatedly, for the same peer and the same [SnapshotMeta]**, is a misconfiguration. An honest
     *   leader whose snapshot genuinely exceeds this follower's ceiling restarts from `0`, refills to
     *   the ceiling, is discarded again, and the follower never catches up. Nothing recovers on its
     *   own and the remedy — raise `RaftConfig.snapshotTotalCeiling` — is not derivable from the
     *   symptom.
     *
     * So the `warn` fires on the repeat rather than the first occurrence, and is latched to once per
     * node; the [RaftMetric.SnapshotRejectedSizeCeiling] emit is unlatched, because a consumer samples
     * it as a level and "is this still happening?" is exactly the question that separates the two cases
     * above. See [snapshotSizeRejectionWarnLogged] for why the latch is not merely tidiness.
     *
     * Never throws: this is reached from the actor loop on a path a remote frame controls, so a
     * `require` here would convert one hostile frame into permanent node death (#1818).
     */
    private fun reportSnapshotRejectedSizeCeiling(
        from: NodeId,
        meta: SnapshotMeta,
        outcome: SnapshotReceiver.ChunkOutcome.TooLarge,
    ) {
        emitMetric(RaftMetric.SnapshotRejectedSizeCeiling(outcome.attemptedTotal, outcome.ceiling))
        val rejection = from to meta
        val repeat = rejection == lastSnapshotSizeRejection
        lastSnapshotSizeRejection = rejection
        // Latched to once per node — see snapshotSizeRejectionWarnLogged. The metric above is not latched.
        if (repeat && !snapshotSizeRejectionWarnLogged) {
            snapshotSizeRejectionWarnLogged = true
            logger.warn { "[raft:${transport.selfId}] ${snapshotCeilingDiagnostic(from, outcome)}" }
        } else {
            debug { snapshotCeilingDiagnostic(from, outcome) }
        }
    }

    /**
     * The operator-facing diagnostic for [reportSnapshotRejectedSizeCeiling].
     *
     * Built by a function rather than inlined so the `warn` and `debug` call sites share one text while
     * both stay lazy — neither builds the string unless its level is enabled. Mirrors
     * [ceilingDiagnostic].
     *
     * **Names both cases, and says which one needs an operator.** The `warn` fires only on the repeat,
     * but the text is shared with the `debug` a first occurrence gets, so it cannot assume the reader is
     * looking at a misconfiguration — a line that told every reader to raise the ceiling would send an
     * operator to reconfigure a node whose only event was the bound successfully containing a hostile
     * peer.
     */
    private fun snapshotCeilingDiagnostic(from: NodeId, outcome: SnapshotReceiver.ChunkOutcome.TooLarge): String =
        "onInstallSnapshot($from): DISCARD — reassembly would reach ${outcome.attemptedTotal} bytes, above " +
            "snapshotTotalCeiling=${outcome.ceiling} → discard and re-advertise 0. Repeats for the same peer " +
            "and snapshot position mean this follower cannot install that snapshot at all and will never " +
            "catch up: raise RaftConfig.snapshotTotalCeiling above the leader's snapshot size and restart " +
            "this node (the ceiling is read once, when the node is built). A single occurrence is instead " +
            "the bound containing a peer that would otherwise grow this buffer without limit, and needs no " +
            "action (#1881, #1926)."

    /** Persist + apply a fully-reassembled snapshot, reset the log around it, and emit the install. */
    private suspend fun finalizeInstalledSnapshot(
        from: NodeId,
        m: RaftMessage.InstallSnapshot,
        meta: SnapshotMeta,
        bytes: ByteArray,
    ) {
        // A snapshot at or below our applied frontier can only regress state — ack and ignore it.
        // This is the etcd-style `<= committed` restore guard: Raft Fig 13 rule 6 (when our existing
        // entry already covers the snapshot's last index/term, retain the suffix and *reply* — do NOT
        // fall through to the rule-8 state-machine reset), plus §7's tolerance of retransmitted /
        // out-of-order InstallSnapshot chunks (the paper's figure doesn't spell the `<= committed` case
        // out explicitly). Without it a stale/duplicate snapshot below `snapshotIndex` hits the
        // discard-whole branch (entryAt is null) and wipes the committed suffix (#1219); this also keeps
        // the `Committed.Install` emit below reachable only when the snapshot genuinely advances the
        // frontier, so a behind-commit retain-suffix snapshot never resets the state machine backward
        // (#1220). `currentCommitIndex` is the strictly stronger bound — it covers both `<= snapshotIndex`
        // and `<= commitIndex`, since `snapshotIndex <= currentCommitIndex` always holds. The ack is a
        // safe no-op at the leader: `SnapshotSender.onAck` returns `NoTransfer` when there is no live
        // transfer for this peer (the common case for a delayed duplicate), and it is byte-identical to
        // the ack the normal finalize path sends below. `reset()` releases the fully-reassembled buffer
        // (this path is reached only on `ChunkOutcome.Complete`, so `SnapshotReceiver` is holding the
        // entire snapshot) — honoring the reset-on-every-Complete contract and avoiding a retained-bytes leak.
        if (m.lastIncludedIndex <= state.currentCommitIndex) {
            snapshotReceiver.reset()
            send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, bytes.size.toLong(), echoedRound = m.round))
            return
        }
        // `meta` is the SnapshotReceiver.Complete outcome's meta — identical to the per-chunk meta the
        // receiver reassembled under (SnapshotMeta(m.lastIncludedIndex, m.lastIncludedTerm, m.config)),
        // so we reuse it here instead of rebuilding it from `m`.
        storage.saveSnapshot(meta, bytes)
        // Keep the suffix only if our entry at the boundary matches the snapshot's term (Log Matching);
        // otherwise the whole local log is suspect — discard it and rebuild from the snapshot.
        if (state.entryAt(m.lastIncludedIndex)?.term == m.lastIncludedTerm) {
            storage.discardLogPrefix(m.lastIncludedIndex)
            state.log.removeAll { it.index <= m.lastIncludedIndex }
        } else {
            storage.truncateFrom(0L)
            state.log.clear()
        }
        state.snapshotIndex = m.lastIncludedIndex
        state.snapshotTerm = m.lastIncludedTerm
        if (state.currentCommitIndex < m.lastIncludedIndex) {
            state.currentCommitIndex = m.lastIncludedIndex
            _commitIndex.value = m.lastIncludedIndex
        }
        _compactionFloor.value = state.snapshotIndex
        // Adopt the snapshot's effective config as the recompute baseline, then recompute membershipState:
        // the config entries that produced this membershipState were compacted away on the leader, so the
        // snapshot is the only place the installer can learn them. A non-null joint payload resumes the
        // joint phase. Falls through to log-based or bootstrapConfig when the snapshot carries no config.
        state.snapshotConfig = m.config
        recomputeMembership()
        _committed.emit(Committed.Install(Snapshot(m.lastIncludedIndex, bytes)))
        snapshotReceiver.reset()
        debug { "finalizeInstalledSnapshot($from): INSTALLED through=${m.lastIncludedIndex} term=${m.lastIncludedTerm} commit=${state.currentCommitIndex} logTail=${state.log.firstOrNull()?.index}..${state.log.lastOrNull()?.index} membershipState=${state.membershipState}" }
        emitTrace(RaftTraceEvent.InstallSnapshotAccepted(nextClock(), from, transport.selfId, m.lastIncludedIndex))
        send(from, RaftMessage.InstallSnapshotResponse(state.currentTerm, bytes.size.toLong(), echoedRound = m.round))
    }

    /**
     * §5.3 frame-internal well-formedness of an [RaftMessage.AppendEntries] batch (issue #1832).
     *
     * The protocol implies `entries[i].index == prevLogIndex + 1 + i` — contiguous, ascending,
     * starting immediately after the probed position — and `entries[i].term <= term`, since no entry
     * may carry a term above the leader's own. Nothing enforced either. [RaftState.entryAt] returns
     * null for *any* index past the tail, so the append scan's `existing == null` branch appended
     * whatever index the sender supplied, at any distance from the real tail.
     *
     * Two consequences, one of them a safety violation:
     *
     * - **Contiguity.** [logEntryAt] computes its offset as `index - (snapshotIndex + 1)`, valid only
     *   because indices are monotonically increasing with no gaps. A log holding `[… 7, MAX-1]`
     *   breaks that, so subsequent lookups resolve the wrong slot or fall out of range.
     * - **Leader Completeness (§5.4 / Figure 3.2).** [RaftState.lastLogPosition] is built from the
     *   last entry and §5.4.1 compares `(term, index)` lexicographically, so an entry at
     *   `term = Long.MAX_VALUE` makes the victim unbeatable by any honest node. It then wins every
     *   election it enters while its log does *not* hold the committed entries a legitimate leader
     *   must — and committed entries can be overwritten.
     *
     * **Scope — the AppendEntries lane.** This check guards [RaftMessage.AppendEntries] and nothing
     * else, and [onMessage]'s term bound guards a frame's own `term` and nothing else. [RaftMessage.InstallSnapshot]'s `lastIncludedTerm` / `lastIncludedIndex` are seen by
     * neither and reach the same §5.4.1 domination (plus a wiped log) through a sibling frame; that
     * lane has its own [isWellFormedSnapshotChunk] (issue #1868), which bounds **both** halves of the
     * position. Note this lane needs no index ceiling and that one does: here `entries[i].index` is
     * pinned to `prevLogIndex + 1 + i` and Log Matching pins `prevLogIndex` against the local log, so
     * a forger cannot leap the index, whereas the snapshot lane's analogous term check fails open.
     * Neither check makes the frames trustworthy — in-range metadata stays unauthenticated (#1876).
     *
     * Unlike the in-range `matchIndex` / `nextOffset` lies of #1818, this is checkable without trust
     * or extra state: the leader states `prevLogIndex` in the same message, so the batch's required
     * indices are fully determined by the frame itself.
     *
     * **Disposition: drop the frame, don't reply `success = false`.** An honest leader cannot emit
     * such a batch — [sendAppendEntries] slices a contiguous suffix via [logSliceFrom] and sets
     * `prevIndex = nextIndex - 1`, and log terms never exceed the leader's `currentTerm` — so there
     * is no honest sender to answer, and a rejection would hand a forger a free lever on the leader's
     * §5.3 backup. Dropping mirrors the §5.2 leader-authority gate in [onMessage]. Crucially it is
     * also *not* a `require`: this runs inside the engine's actor loop, whose `try`/`finally` has no
     * `catch`, so a throw would convert a malformed frame into permanent node death (#1818).
     *
     * Called before the term check, so a malformed frame never adopts its term either.
     */
    private fun isWellFormedBatch(from: NodeId, m: RaftMessage.AppendEntries): Boolean {
        // A negative probe index is nonsense, and one within `entries.size + 1` of Long.MAX_VALUE
        // would overflow the expected-index arithmetic below. Neither is reachable honestly.
        if (m.prevLogIndex < 0L || m.prevLogIndex > Long.MAX_VALUE - m.entries.size - 1L) {
            debug { "onAppendEntries($from): DROP — implausible prevLogIndex=${m.prevLogIndex} (entries=${m.entries.size})" }
            return false
        }
        m.entries.forEachIndexed { i, entry ->
            val expected = m.prevLogIndex + 1L + i
            if (entry.index != expected) {
                debug { "onAppendEntries($from): DROP — non-contiguous batch: entries[$i].index=${entry.index} expected=$expected (prevLogIndex=${m.prevLogIndex}, size=${m.entries.size})" }
                return false
            }
            if (entry.term < 0L || entry.term > m.term) {
                debug { "onAppendEntries($from): DROP — entries[$i].term=${entry.term} outside 0..${m.term} (no entry may carry a term above the leader's)" }
                return false
            }
        }
        return true
    }

    private suspend fun onAppendEntries(from: NodeId, m: RaftMessage.AppendEntries) {
        if (!isWellFormedBatch(from, m)) return
        if (m.term < state.currentTerm) {
            // Echo round 0, NOT `m.round` (issue #1831). This reply pairs OUR current term with a
            // request from an older one, and `ReadIndexTracker.round` resets to 0 on every
            // `becomeLeader` — so a round carried by a delayed AppendEntries from an earlier
            // leadership can be arbitrarily larger than anything the current leadership has stamped.
            // Echoing it produces a response that passes the leader's `m.term == currentTerm` guard
            // and reaches `recordAck(from, <foreign round>)`, seating this voter in `resolve`'s fresh
            // set for every read for the rest of the term: a stale read served as linearizable
            // (§3.7), with no forgery — two honest nodes reach it on their own in the async model.
            //
            // A round is a NONCE, so the disposition is to attest to nothing rather than to clamp
            // `m.round` into the current round — clamping would launder a foreign round into the
            // most favourable valid one, which is exactly the #1817 mistake. 0 can never satisfy
            // `ackRound > read.sinceRound` (sinceRound >= 0), which is correct: a stale-term
            // rejection genuinely answers nothing in the current round. Reachability for CheckQuorum
            // is unaffected — that runs off `recentVoterContacts`, which this response still feeds.
            //
            // Matches the sibling InstallSnapshot stale-term rejection, which already replies with
            // the `echoedRound = 0L` default.
            send(from, RaftMessage.AppendEntriesResponse(state.currentTerm, false, echoedRound = 0L))
            return
        }
        if (m.term > state.currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        // higher term: already adopted it via stepDown above, continue processing in new term.
        // Same-term: normally a cheap role flip, but if we were somehow still Leader (Election Safety
        // violated), route through the relinquish path so timers/deferreds/dedup tear down (#1250).
        demoteToFollowerOnLeaderContact()
        // #1906: the demotion above fires FIRST and unconditionally (that is #1250's defense in
        // depth), but the sender does not thereby get to install itself as this term's leader — the
        // two concerns are separable and both are kept. Placed before every remaining side-effect so
        // a refused frame cannot cancel an in-flight pre-vote probe, hold this node's election timer
        // open, resolve a §3.10 transfer, or reach the log. See [adoptLeaderForTerm].
        if (!adoptLeaderForTerm(from, "onAppendEntries")) return
        preVoteTerm = null          // a live leader appeared — cancel any in-flight pre-vote probe
        // The leader is [from]: the peer that authored this frame, not a payload claim (#1912).
        _leader.value = from
        // §3.10 (#1243): a leader-authored message from the transfer target at a higher term is the
        // conclusive transfer-success signal — the target actually won its election.
        if (transfer.onLeaderElected(from, m.term)) {
            debug { "leadership transfer confirmed: ${from.value} is leader at term ${m.term}" }
        }
        resetElectionTimeout()
        armLeaderLease()

        // Log consistency check.
        //
        // The boundary entry at [snapshotIndex] is not in [log] — it was folded into the snapshot —
        // so `entryAt(snapshotIndex)` is null. A normal AppendEntries that resumes right after an
        // install carries `prevLogIndex == snapshotIndex` (the leader supplies `prevLogTerm =
        // snapshotTerm`, see sendAppendEntries). Treating that null as a conflict rejects the resume,
        // the leader backs nextIndex below the floor and re-sends the snapshot, and the
        // install→reject→install loop spins with no delay — freezing virtual time in tests. The
        // snapshot prefix is committed and cluster-agreed, so any `prevLogIndex <= snapshotIndex`
        // already matches; only check entries strictly above the floor.
        if (m.prevLogIndex > state.snapshotIndex) {
            val prev = state.entryAt(m.prevLogIndex)
            if (prev == null || prev.term != m.prevLogTerm) {
                // §5.3 fast backup: report conflict info. Two distinct rejection causes need distinct
                // replies, or the leader can never make progress:
                val conflictTerm: Long?
                val resolvedConflictIndex: Long
                if (prev == null) {
                    // "Log too short" — prevLogIndex is beyond our lastLogIndex, so there is no
                    // conflicting *term* to skip, only a gap to close. Report no term and point the
                    // leader straight at our end (lastLogIndex + 1, snapshot-aware) so it backs
                    // nextIndex to exactly where we can begin appending. Synthesising a term from our
                    // last entry here would make the leader's `lastOfTerm(term)+1` reproduce the SAME
                    // nextIndex every heartbeat — an identical AppendEntries, an identical rejection,
                    // forever: the §5.3 fast-backup livelock (issue #1246).
                    conflictTerm = null
                    resolvedConflictIndex = state.lastLogIndex + 1L
                } else {
                    // A real term conflict at an existing index: report our term there and the first
                    // index carrying it, so the leader skips the whole conflicting term in one step.
                    // `prev` is an element of `state.log` (returned by entryAt), so the scan always
                    // finds at least it — a missing match is an impossible state, so crash loudly
                    // rather than shipping a plausible-but-wrong index.
                    conflictTerm = prev.term
                    resolvedConflictIndex = state.log.first { it.term == prev.term }.index
                }
                debug { "onAppendEntries($from): REJECT prevLogIndex=${m.prevLogIndex} prevLogTerm=${m.prevLogTerm} (have=${prev?.term}) snapshotIndex=${state.snapshotIndex} → conflictIndex=$resolvedConflictIndex conflictTerm=$conflictTerm" }
                emitTrace(
                    RaftTraceEvent.AppendEntriesRejected(
                        clock = nextClock(),
                        from = from,
                        to = transport.selfId,
                        conflictIndex = resolvedConflictIndex,
                        conflictTerm = conflictTerm,
                    )
                )
                send(
                    from,
                    RaftMessage.AppendEntriesResponse(
                        term = state.currentTerm,
                        success = false,
                        conflictIndex = resolvedConflictIndex,
                        conflictTerm = conflictTerm,
                        echoedRound = m.round,
                    )
                )
                return
            }
        }

        // Truncate conflicting entries and append new ones — Fig.2 rule 3, applied to EVERY entry in the
        // batch, not just the first (issue #1248). Scan the batch in index order for the first entry that
        // diverges from our log:
        //   • an entry at index <= snapshotIndex is in the committed, cluster-agreed snapshot prefix — it
        //     can never diverge, and appending it would break `logEntryAt`'s offset invariant (the live log
        //     must begin at snapshotIndex + 1). Skip it — the explicit contiguity guard flagged in #1248.
        //   • an entry whose index exists locally with the SAME term is an exact duplicate — a no-op. Do
        //     NOT truncate on a match, so an idempotent re-delivery never rolls the log back.
        //   • the first entry whose index exists locally with a DIFFERENT term is a conflict: delete it and
        //     every following entry (§6 rollback safety), then append this entry and the rest of the batch.
        //   • the first entry past our tail begins the new suffix: append it and the rest of the batch.
        //
        // The old code checked only `m.entries.first()` for a conflict and then appended any entry whose
        // index we lacked — silently keeping a LATER entry whose index existed locally with a different
        // term, and over-attesting. Safe today only because the leader always ships the full suffix from
        // `nextIndex` (an emergent invariant), so the first batch entry already sits at/above the first
        // divergence and this per-entry scan lands on exactly the same truncate/append. This is
        // behaviour-preserving hardening for every reachable input; it removes the standing proof-obligation.
        if (m.entries.isNotEmpty()) {
            var appendFrom = -1
            for ((i, entry) in m.entries.withIndex()) {
                if (entry.index <= state.snapshotIndex) continue        // committed snapshot prefix — no-op
                val existing = state.entryAt(entry.index)
                if (existing == null) { appendFrom = i; break }         // first index past our tail
                if (existing.term != entry.term) {                      // term conflict — truncate from here
                    storage.truncateFrom(entry.index)
                    state.log.removeAll { it.index >= entry.index }
                    // Adopt-on-append: recompute membershipState after rollback so a truncated config entry
                    // is immediately uneffected (§6 rollback safety).
                    recomputeMembership()
                    appendFrom = i
                    break
                }
                // same index+term: exact duplicate — keep scanning (idempotent)
            }
            if (appendFrom >= 0) {
                // The batch suffix from the first divergence onward. Indices are ascending, so any entry
                // at index <= snapshotIndex can only precede `appendFrom`; the guard is a defensive no-op.
                val toAdd = m.entries.subList(appendFrom, m.entries.size).filter { it.index > state.snapshotIndex }
                if (toAdd.isNotEmpty()) {
                    state.log.addAll(toAdd)
                    storage.appendEntries(toAdd)
                    // Adopt-on-append: recompute membershipState after adding entries — a config entry
                    // in toAdd takes effect immediately on the follower.
                    recomputeMembership()
                }
            }
        }

        // Exact attestation + Fig.2 rule 5 (issues #1248/#1249). `lastNewIndex` is the index of the last
        // entry THIS AppendEntries covered (`prevLogIndex + entries.size`; == prevLogIndex for a heartbeat).
        // Both the follower-commit bound and the success reply's matchIndex are keyed to it — NOT to
        // `state.lastLogIndex`, which can exceed the just-verified prefix when a stale suffix survives beyond
        // the batch. For the currently-reachable full-suffix send `lastNewIndex == state.lastLogIndex`, so
        // this is behaviour-preserving; it removes the proof-obligation that the two always coincide.
        val lastNewIndex = m.prevLogIndex + m.entries.size

        if (m.leaderCommit > state.currentCommitIndex) {
            // Commit only up to what this AE verified. Clamp forward-only (`maxOf(_, currentCommitIndex)`)
            // so a reordered/partial batch whose `lastNewIndex` sits below our committed prefix can never
            // regress commitIndex (advanceCommit assigns it unconditionally). For the reachable full-suffix
            // send `lastNewIndex >= currentCommitIndex`, so the clamp is a no-op and behaviour is unchanged.
            advanceCommit(minOf(m.leaderCommit, maxOf(lastNewIndex, state.currentCommitIndex)))
        }

        val acceptedMatchIndex = lastNewIndex
        debug { "onAppendEntries($from): ACCEPT prevLogIndex=${m.prevLogIndex} +${m.entries.size} entries → matchIndex=$acceptedMatchIndex commit=${state.currentCommitIndex}" }
        emitTrace(
            RaftTraceEvent.AppendEntriesAccepted(
                clock = nextClock(),
                from = from,
                to = transport.selfId,
                matchIndex = acceptedMatchIndex,
            )
        )
        send(from, RaftMessage.AppendEntriesResponse(state.currentTerm, true, acceptedMatchIndex, echoedRound = m.round))
    }

    private suspend fun onAppendEntriesResponse(from: NodeId, m: RaftMessage.AppendEntriesResponse) {
        // stale-term peer response: step down and discard
        if (m.term > state.currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != state.currentTerm) return
        recentVoterContacts += from                 // reachability signal for CheckQuorum (success or failure)
        readIndexTracker.recordAck(from, m.echoedRound)   // credit ACK to the round it actually responded to (BLOCKER 1a)
        confirmFreshReads()                         // ReadIndex: check if any pending reads can now be confirmed
        if (m.success) {
            // Clamp to lastLogIndex: a leader can never have replicated entries it doesn't hold, so a
            // real follower's match never exceeds lastLogIndex; the clamp only bites on a malformed/
            // foreign response, turning a downstream prevTerm-missing crash (#1175) into a benign no-op.
            state.matchIndex[from] = maxOf(state.matchIndex[from] ?: 0L, minOf(m.matchIndex, state.lastLogIndex))
            state.nextIndex[from] = state.matchIndex.getValue(from) + 1L
            tryAdvanceLeaderCommit()
            // §3.10 step 2: if a transfer is in flight and the target's log now fully matches ours
            // (matchIndex == lastLogIndex, not merely commitIndex), send TimeoutNow.
            if (transfer.isTargetCaughtUp(from, state.matchIndex.getValue(from), state.lastLogIndex)) sendTimeoutNow(from)
        } else {
            // §5.3 fast backup: jump nextIndex to reduce O(n) recovery to O(#terms)
            state.nextIndex[from] = nextIndexAfterFailure(state.nextIndex[from] ?: 1L, m, state.log)
            debug { "onAppendEntriesResponse($from): REJECTED → backup nextIndex=${state.nextIndex[from]} (snapshotIndex=${state.snapshotIndex}), resend" }
            sendAppendEntries(from)
        }
    }

    /**
     * ReadIndex: ask [readIndexTracker] which pending reads a fresh voter-quorum now confirms (the
     * round-slip and joint dual-majority freshness arithmetic — BLOCKER 1 and 2 — live in the tracker,
     * documented on [ReadIndexTracker]), then emit the trace and complete each read's deferred here.
     * Called after every AppendEntries/InstallSnapshot ACK is credited via [ReadIndexTracker.recordAck].
     */
    private suspend fun confirmFreshReads() {
        readIndexTracker.resolve(state.membershipState, transport.selfId).forEach {
            emitTrace(RaftTraceEvent.ReadIndexConfirmed(nextClock(), it.readIndex, state.currentTerm))
            it.deferred.complete(it.readIndex)
        }
    }

    private suspend fun tryAdvanceLeaderCommit() {
        // membershipState.committedIndex accounts for Simple vs Joint quorum, self-credit per voter set,
        // and the §5.4.2 term-guard (only entries from currentTerm can be used to advance commit
        // via replica-count — older entries only commit by implication via Log Matching).
        val majorityIdx = state.membershipState.committedIndex(state.matchIndex, state.lastLogIndex, transport.selfId) ?: return
        val entry = state.entryAt(majorityIdx)
        if (entry != null && entry.term == state.currentTerm && majorityIdx > state.currentCommitIndex) {
            advanceCommit(majorityIdx)
        }
    }

    private suspend fun advanceCommit(newCommit: Long) {
        val oldCommit = state.currentCommitIndex
        // Capture only the LAST committed config entry in this advance window; side-effects run AFTER
        // the loop so currentCommitIndex is already bumped and entryAt is not racing a mutation.
        // Keeping only the last is safe even if a Joint and its trailing Simple(C_new) both commit in
        // one window: C_new is then already in the log, so onConfigCommitted's Joint branch would only
        // re-append an entry C_new identical to what already exists — skipping it is harmless, and we
        // run the Simple(C_new) side-effects (complete the deferred, removed-leader step-down) directly.
        var committedConfigEntry: LogEntry? = null
        for (idx in (state.currentCommitIndex + 1)..newCommit) {
            val entry = state.entryAt(idx) ?: continue
            when {
                entry.config != null -> {
                    // Config entry: withhold the payload, but mark the index so a folding consumer's
                    // applied prefix can still cross it (#1718).
                    _committed.emit(Committed.Internal(idx))
                    committedConfigEntry = entry
                }
                entry.isNoOp -> {
                    // §5.4.2 no-op: same — the index is marked, the entry never surfaces.
                    _committed.emit(Committed.Internal(idx))
                }
                else -> {
                    detectCollision(entry)
                    emitProposeCommittedAndApplied(entry)
                    _committed.emit(Committed.Entry(entry))
                    // Best-effort leader cache: record on the leader only (a follower would accumulate
                    // entries it never serves; the cache is leader-scoped and cleared on step-down).
                    if (_role.value is RaftRole.Leader) dedupCache.record(entry.dedupKey, entry)
                }
            }
            _commitIndex.value = idx
            val matches = pending.filter { (i, _) -> i == idx }
            pending.removeAll(matches)
            matches.forEach { (_, d) -> d.complete(entry) }
        }
        state.currentCommitIndex = newCommit
        emitTrace(RaftTraceEvent.AdvanceCommitIndex(nextClock(), transport.selfId, oldCommit, newCommit))
        // ReadIndex leader-completeness gate: if the current-term no-op just committed, re-deliver
        // any readIndex() requests that were parked waiting for it.
        readIndexTracker.onNoOpCommitted(state.currentCommitIndex).forEach { it() }
        // Config-commit side effects AFTER commitIndex is bumped — safe to call appendConfigEntry.
        committedConfigEntry?.let { onConfigCommitted(it) }
    }

    /**
     * §8 collision check on every committed application entry: a committed entry under [myClientId]
     * bearing a serial this node never issued proves another live writer shares the identity. A
     * **durable** id (caller-supplied) is an operational error — throw [ClientIdCollisionException]
     * loud from the actor loop. An **auto** id silently re-mints (fresh suffix, reset serial +
     * detector) and logs a warning; the in-flight writes already committed are unaffected.
     */
    private fun detectCollision(entry: LogEntry) {
        if (!collisions.isForeign(entry.dedupKey)) return
        if (isDurableId) {
            throw ClientIdCollisionException(
                "another writer committed under durable clientId '${myClientId.value}' " +
                    "(foreign serial ${entry.dedupKey?.requestId} at index ${entry.index}); two processes share one id",
            )
        }
        val old = myClientId
        myClientId = ClientId.auto(transport.selfId, raftConfig.random)
        serial = 0L
        collisions = CollisionDetector(myClientId)
        logger.warn {
            "[raft:${transport.selfId}] clientId collision: auto id '${old.value}' seen with a foreign " +
                "serial ${entry.dedupKey?.requestId} — re-minted as '${myClientId.value}'"
        }
    }

    /**
     * Emit [RaftMetric.ProposeCommitted] then [RaftMetric.ProposeApplied] for [entry].
     * Both share the same elapsed time snapshot so the sequence is consistent. Logs at warn
     * when elapsed exceeds [RaftConfig.slowProposeThreshold], debug otherwise.
     * Removes the start-time entry from [proposeStartTimes].
     */
    private fun emitProposeCommittedAndApplied(entry: LogEntry) {
        val startMark = proposeStartTimes.remove(entry.index) ?: return
        val elapsed = startMark.elapsedNow()
        emitMetric(RaftMetric.ProposeCommitted(entry.index, elapsed))
        emitMetric(RaftMetric.ProposeApplied(entry.index, elapsed))
        if (elapsed >= raftConfig.slowProposeThreshold) {
            logger.warn { "[raft:${transport.selfId}] slow propose at index ${entry.index}: ${elapsed.inWholeMilliseconds}ms (threshold ${raftConfig.slowProposeThreshold.inWholeMilliseconds}ms)" }
        } else {
            logger.debug { "[raft:${transport.selfId}] propose at index ${entry.index} applied in ${elapsed.inWholeMilliseconds}ms" }
        }
    }

    /**
     * Snapshot the committed log for [committedFrom]. Runs in the actor, so [currentCommitIndex] and
     * [log] are read at a single consistent point in the commit stream — the caller has already
     * registered a live subscriber, so entries committed after this cut tail through that
     * subscription without a gap.
     *
     * Every committed index in the window is represented exactly once, classified by [asCommitted]:
     * an application entry replays as [Committed.Entry], an internal no-op/config entry as a
     * payload-free [Committed.Internal] marker. Replaying the marker rather than dropping the entry
     * is what lets a resuming consumer's applied prefix cross a withheld index (#1718) — and it is
     * why a config entry can no longer reach a consumer as a [Committed.Entry] it would try to
     * decode as an application command.
     *
     * When [fromIndex] falls at or below [snapshotIndex], loads the stored snapshot and
     * prepends a [Committed.Install] so the subscriber can reset its state machine.
     */
    private suspend fun onCommitCut(c: EngineCommand.CommitCut) {
        val install = if (c.fromIndex <= state.snapshotIndex && state.snapshotIndex > 0L)
            storage.loadSnapshot()?.let { Snapshot(it.meta.lastIncludedIndex, it.state) } else null
        val from = maxOf(c.fromIndex, state.snapshotIndex + 1)
        val replay = logSliceFrom(state.log, state.snapshotIndex, from)
            .filter { it.index <= state.currentCommitIndex }
            .map { it.asCommitted() }
        c.response.complete(CommitCutResult(replay, state.currentCommitIndex, install))
    }

    private suspend fun onCompact() {
        val s = snapshots.value ?: return
        if (s.throughIndex <= state.snapshotIndex || s.throughIndex > state.currentCommitIndex) return
        val term = state.termAt(s.throughIndex) ?: return   // must be a live, committed entry
        // The membershipState the snapshot must carry is the config as of `throughIndex` — the highest-index
        // config entry at or below the cut, else the config the prior snapshot already recorded. It must
        // NOT be the live `membershipState`, which may reflect a later config entry between the cut and the
        // log tail (that would stamp the snapshot with a future config and corrupt an installer's view).
        val configAsOfCut = state.log.lastOrNull { it.config != null && it.index <= s.throughIndex }?.config
            ?: state.snapshotConfig
        storage.saveSnapshot(SnapshotMeta(s.throughIndex, term, configAsOfCut), s.state)   // durable FIRST
        storage.discardLogPrefix(s.throughIndex)                             // then drop prefix
        state.log.removeAll { it.index <= s.throughIndex }
        state.snapshotIndex = s.throughIndex
        state.snapshotTerm = term
        // Retain the compacted config as the snapshot baseline so a subsequent recompute (or restart)
        // still resolves membershipState correctly once the config entry is gone from the live log.
        state.snapshotConfig = configAsOfCut
        _compactionFloor.value = state.snapshotIndex
        emitTrace(RaftTraceEvent.Compacted(nextClock(), transport.selfId, state.snapshotIndex, state.snapshotTerm))
    }

    // ── propose() ─────────────────────────────────────────────────────────────

    /**
     * Actor-loop entry for a **local** proposal: stamp this node's own [DedupKey] (auto-serial when
     * [requestId] is null, else the caller-pinned serial), record it as issued for collision detection,
     * then hand off to [onPropose] which appends-or-forwards with the stamped key. Stamping here (not in
     * [onPropose]) is deliberate: forwarded proposals reach [onPropose] via [onForward] carrying the
     * *originator's* key, which must NOT be re-stamped and must NOT count as a serial this node issued.
     */
    private suspend fun onLocalPropose(command: ByteArray, requestId: Long?, response: CompletableDeferred<LogEntry>) {
        val reqId = requestId ?: ++serial
        collisions.issued(reqId)
        onPropose(command, DedupKey(myClientId, reqId), response)
    }

    private suspend fun onPropose(command: ByteArray, dedupKey: DedupKey?, response: CompletableDeferred<LogEntry>) {
        if (_role.value !is RaftRole.Leader) {
            // Follower/Candidate/Learner: forward to the leader (Raft §8). Wait, cancellably,
            // if none is known yet. Cleanup of cancelled entries is handled on the actor loop
            // in flushWaitingForLeader (isCompleted check) and forwarder.failAll (finally).
            // Do NOT use invokeOnCompletion to mutate the forwarder maps — that runs on the
            // caller's thread and races the actor loop, which is the sole owner of the maps.
            when (val d = forwarder.forward(response, command, dedupKey, _leader.value, transport.selfId)) {
                is ProposalForwarder.ForwardDecision.SendToLeader ->
                    send(d.leaderId, RaftMessage.Forward(d.id, d.command, d.dedupKey))
                ProposalForwarder.ForwardDecision.Queued -> Unit
            }
            return
        }
        // §3.10: while a leadership transfer is in flight, reject new proposals so the target can
        // catch up to our log without racing additional appends. The NotLeaderException is the correct
        // signal — the caller should retry on the new leader once transfer completes.
        val transferInFlight = transfer.inFlightTarget
        if (transferInFlight != null) {
            response.completeExceptionally(NotLeaderException("leadership transfer in flight to ${transferInFlight.value}"))
            return
        }
        // §8 best-effort leader dedup: a retry of an already-committed key coalesces onto the recorded
        // result instead of appending a second entry. The consumer's ClientSessionTable is the durable
        // backstop; this only catches the common lost-ack retry on a still-leading node.
        dedupCache.lookup(dedupKey)?.let { response.complete(it); return }
        val index = state.lastLogIndex + 1L
        val entry = LogEntry(index, state.currentTerm, command, dedupKey = dedupKey)
        state.log += entry
        storage.appendEntries(listOf(entry))
        emitTrace(RaftTraceEvent.ClientRequest(nextClock(), transport.selfId, index, state.currentTerm))
        proposeStartTimes[index] = TimeSource.Monotonic.markNow()
        emitMetric(RaftMetric.ProposeAccepted(index, state.currentTerm))
        logger.debug { "[raft:${transport.selfId}] propose accepted at index $index term ${state.currentTerm}" }
        pending += index to response
        otherMembers.forEach { sendAppendEntries(it) }
        // Single-voter: no peers will ACK — check for immediate commit (peerQuorum == 0).
        tryAdvanceLeaderCommit()
    }

    override suspend fun propose(command: ByteArray): LogEntry = proposeWithRequestId(command, null)

    override suspend fun propose(command: ByteArray, requestId: Long): LogEntry =
        proposeWithRequestId(command, requestId)

    private suspend fun proposeWithRequestId(command: ByteArray, requestId: Long?): LogEntry {
        val d = CompletableDeferred<LogEntry>()
        try {
            cmd.send(EngineCommand.Propose(command, requestId, d))
        } catch (_: ClosedSendChannelException) {
            throw NotLeaderException("node is closed")
        }
        try {
            return d.await()
        } finally {
            // If this coroutine was cancelled before the deferred completed, mark the deferred
            // cancelled so the actor loop's flushWaitingForLeader sees isCompleted==true and
            // drops it rather than forwarding a cancelled request. This is the only mutation of
            // d that touches the caller's thread; all other map/state mutations stay on the actor.
            // cancel() on an already-completed deferred is a harmless no-op.
            d.cancel()
        }
    }

    override suspend fun readIndex(): Long {
        val d = CompletableDeferred<Long>()
        try {
            cmd.send(EngineCommand.RequestReadIndex(d))
        } catch (_: ClosedSendChannelException) {
            throw NotLeaderException("node is closed")
        }
        return d.await()
    }

    // ── Membership ────────────────────────────────────────────────────────────

    /**
     * Recompute [membershipState] from the current log + [snapshotConfig] + [bootstrapConfig].
     *
     * Resolution order: highest-index config entry in the live log, else snapshot config, else
     * Simple(bootstrapConfig). This is a deterministic function of (log, snapshot, bootstrap),
     * plus self-role re-evaluation and a [RaftTraceEvent.ConfigChange] trace event on genuine
     * transitions. The adopt-on-append and rollback model means there is no separate undo path;
     * truncation simply removes entries and recomputing this function produces the correct
     * rolled-back config automatically.
     *
     * Called after every append, truncate, and snapshot install so the engine always operates under
     * the config justified by its current log state. Emits [RaftTraceEvent.ConfigChange] on both
     * the leader (via [appendConfigEntry]) and followers (via [onAppendEntries]), covering rollback
     * on truncate as well.
     */
    private suspend fun recomputeMembership() {
        val prior = state.membershipState
        val configEntry = state.log.lastOrNull { it.config != null }
        val logConfig = configEntry?.config
        val resolved = logConfig ?: state.snapshotConfig
        val newMembership = when {
            resolved == null           -> MembershipState.Simple(bootstrapConfig)
            resolved.old != null       -> MembershipState.Joint(resolved.old, resolved.new)
            else                       -> MembershipState.Simple(resolved.new)
        }
        val branch = when {
            configEntry != null    -> "log[${configEntry.index}]"
            state.snapshotConfig != null -> "snapshot"
            else                   -> "bootstrap"
        }
        val changed = newMembership != prior
        state.membershipState = newMembership
        reevaluateSelfRole()
        if (changed) {
            _membership.value = newMembership.effectiveConfig
            debug { "recomputeMembership: $prior → $newMembership (source=$branch)" }
            // `old` is the prior effective config — on the first ever change that is the
            // bootstrap config, which is more informative than null.
            val configIndex = configEntry?.index ?: if (state.snapshotConfig != null) state.snapshotIndex else state.lastLogIndex
            emitTrace(
                RaftTraceEvent.ConfigChange(
                    nextClock(), transport.selfId, configIndex, prior.effectiveConfig, newMembership.effectiveConfig,
                ),
            )
        }
    }

    /**
     * After recomputing [membershipState], re-evaluate this node's resting role.
     *
     * A previously-learner node whose new config makes it a voter should leave [RaftRole.Learner]
     * and become election-eligible. A voter node whose new config makes it a learner should enter
     * [RaftRole.Learner]. Only adjusts the follower/learner resting role — does not disturb an
     * active [RaftRole.Candidate] or [RaftRole.Leader].
     */
    private fun reevaluateSelfRole() {
        val current = _role.value
        if (current is RaftRole.Leader || current is RaftRole.Candidate) return
        val desired = followerRole
        if (current != desired) {
            debug { "reevaluateSelfRole: $current → $desired" }
            _role.value = desired
            if (desired is RaftRole.Learner) {
                electionJob?.cancel()   // learners do not participate in elections
                electionJob = null
            } else {
                // Promoted INTO a voting role (Learner → Follower). Arm the election timer now — a
                // freshly-promoted voter that never hears from a leader must be able to start an
                // election, rather than staying passive until the next inbound AppendEntries.
                debug { "reevaluateSelfRole: promoted to voter — arming election timer" }
                resetElectionTimeout()
            }
        }
    }

    /**
     * Append a config log entry to the leader's log (adopt-on-append), replicate it, and
     * try to advance commit. This is `onPropose` specialized for internal config entries:
     * it does NOT touch [pending]/[_committed]/[proposeStartTimes].
     *
     * Called by [onChangeMembership] (learner-set-only Simple entry) and by [onConfigCommitted]
     * (the C_new Simple entry that finalises a Joint after it commits).
     */
    private suspend fun appendConfigEntry(payload: ConfigPayload) {
        val index = state.lastLogIndex + 1L
        val entry = LogEntry(index, state.currentTerm, byteArrayOf(), config = payload)
        state.log += entry
        storage.appendEntries(listOf(entry))
        // recomputeMembership() emits the ConfigChange trace event (unified leader+follower path),
        // so we do not emit it here — doing so would double-emit on the leader.
        recomputeMembership()
        debug { "appendConfigEntry: index=$index payload=$payload membershipState=${state.membershipState}" }
        state.membershipState.replicationTargets(transport.selfId).forEach { sendAppendEntries(it) }
        tryAdvanceLeaderCommit()
    }

    /**
     * Leader: validate and initiate a membershipState change request from [changeMembership].
     *
     * A learner-set-only change (voter set unchanged) appends a single `Simple(target)` entry — no
     * quorum shift, so no joint phase. A voter-set change appends a `Joint(old, new)` entry and
     * transitions through §6 joint consensus: dual majorities for commit/election until C_new commits,
     * at which point [onConfigCommitted] appends `Simple(new)` and completes the change. Rejected when
     * not leader, when a change is already in progress, or when the target voter set is empty.
     */
    private suspend fun onChangeMembership(target: ClusterConfig, deferred: CompletableDeferred<ClusterConfig>) {
        if (_role.value !is RaftRole.Leader) {
            debug { "onChangeMembership: rejected — not leader (role=${_role.value})" }
            deferred.completeExceptionally(NotLeaderException())
            return
        }
        // §3.10 step 1: while a leadership transfer is in flight, reject new requests — a membership
        // change is a new request, exactly like a proposal (mirrors the onPropose gate). Appending a
        // config entry mid-transfer would move the lastLogIndex goalpost the target is chasing.
        val transferInFlight = transfer.inFlightTarget
        if (transferInFlight != null) {
            debug { "onChangeMembership: rejected — leadership transfer in flight to ${transferInFlight.value}" }
            deferred.completeExceptionally(NotLeaderException("leadership transfer in flight to ${transferInFlight.value}"))
            return
        }
        if (pendingConfigChange != null) {
            debug { "onChangeMembership: rejected — change already in progress" }
            deferred.completeExceptionally(MembershipChangeInProgressException())
            return
        }
        // One-change-at-a-time, log-grounded: reject while the last config entry is still UNCOMMITTED —
        // a superset of the in-memory `pendingConfigChange` guard above that also covers the
        // inherited-config paths where `pendingConfigChange` is null (no local caller): the Simple(C_new)
        // appended by `finalizeInheritedCommittedJoint` on election, and by `onConfigCommitted` when a
        // leader inherits an in-flight Joint. Adopt-on-append flips `membershipState` to Simple the instant
        // that entry is appended, so without this guard a change arriving in the window before it commits
        // passes BOTH the settled-Simple and `pendingConfigChange` checks, appends a Joint above the
        // uncommitted Simple, and can hand the new caller the wrong committed config. The normal path is
        // unaffected: a genuine in-flight Joint's entry is already > commitIndex (rejected identically to
        // today), and a fully-settled config's last config entry is <= commitIndex (a new change is allowed).
        val lastConfigIndex = state.log.lastOrNull { it.config != null }?.index ?: -1L
        if (lastConfigIndex > state.currentCommitIndex) {
            debug { "onChangeMembership: rejected — last config entry (index=$lastConfigIndex) not yet committed (commit=${state.currentCommitIndex})" }
            deferred.completeExceptionally(MembershipChangeInProgressException())
            return
        }
        if (target.voters.isEmpty()) {
            debug { "onChangeMembership: rejected — target voters is empty" }
            deferred.completeExceptionally(IllegalArgumentException("target voter set must not be empty"))
            return
        }
        // A change may only start from a settled Simple config. An in-flight — or orphaned, after a
        // leader crash mid-transition — Joint config means a §6 transition is still converging; reject
        // until C_new commits. This also makes the `current.config` read below total.
        val current = state.membershipState
        if (current !is MembershipState.Simple) {
            debug { "onChangeMembership: rejected — joint transition in progress ($current)" }
            deferred.completeExceptionally(MembershipChangeInProgressException())
            return
        }
        pendingConfigChange = deferred
        if (target.voters != current.config.voters) {
            // Voter-set change → §6 joint consensus. Append Joint(old=current, new=target); on its
            // commit, onConfigCommitted appends Simple(C_new) and the transition completes when that
            // entry commits. Commit and election require dual majorities throughout the joint phase.
            debug { "onChangeMembership: accepted — voter-set change to $target via joint consensus (old=${current.config})" }
            appendConfigEntry(ConfigPayload(old = current.config, new = target))
        } else {
            // Learner-set-only change: append a Simple(target) entry, no joint phase needed.
            debug { "onChangeMembership: accepted — learner-set-only change to $target" }
            appendConfigEntry(ConfigPayload(old = null, new = target))
        }
    }

    /**
     * Called by [advanceCommit] after a config entry commits.
     *
     * Joint committed (`payload.old != null`): the C_{old,new} transition is now durable under both
     * majorities — append `Simple(new)` to drive the cluster onto C_new alone. The deferred is NOT
     * completed yet; it completes when that `Simple(new)` entry commits (the branch below).
     *
     * Simple committed: the transition is complete — wake the [changeMembership] caller. §6.4.1: if
     * this leader is not a voter in C_new (removed-leader / leader-replace), step down once C_new is
     * durable, so the new cluster elects a leader from its own membershipState.
     */
    private suspend fun onConfigCommitted(entry: LogEntry) {
        val payload = entry.config ?: return
        debug { "onConfigCommitted: entry.index=${entry.index} payload=$payload" }
        if (payload.old != null) {
            // Joint committed → append C_new (Simple) to complete the transition — but ONLY if no
            // later config entry has already superseded the Joint. `membershipState` always reflects the
            // last config entry in the log, so `membershipState is Joint` is exactly the condition "no
            // Simple(C_new) follows the Joint yet." A new leader that inherits a Joint whose trailing
            // Simple(C_new) is already in its log (the original leader appended it before crashing /
            // stepping down) must NOT append a second C_new: that duplicate carries the new leader's
            // term, diverges from the existing C_new, and wedges replication in an infinite
            // AppendEntries backup loop. Skipping is safe — C_new already exists; the Simple branch
            // will complete the deferred and run the step-down when that existing C_new commits.
            if (state.membershipState is MembershipState.Joint) {
                debug { "onConfigCommitted: Joint committed — appending Simple(C_new=${payload.new})" }
                appendConfigEntry(ConfigPayload(old = null, new = payload.new))
            } else {
                debug { "onConfigCommitted: Joint committed but C_new already in log (membershipState=${state.membershipState}) — skip duplicate append" }
            }
        } else {
            // Simple committed → transition complete; wake the changeMembership caller.
            val result = ClusterConfig(payload.new.voters, payload.new.learners)
            debug { "onConfigCommitted: Simple committed — completing pendingConfigChange with $result" }
            pendingConfigChange?.complete(result)
            pendingConfigChange = null
            // §6.4.1: if self is not in the new voter set, step down (removed-leader case).
            if (_role.value is RaftRole.Leader && transport.selfId !in payload.new.voters) {
                debug { "onConfigCommitted: self not in new voters — stepping down (RemovedFromConfig)" }
                stepDownToFollower(StepDownReason.RemovedFromConfig)
            }
        }
    }

    override suspend fun changeMembership(target: ClusterConfig): ClusterConfig {
        val d = CompletableDeferred<ClusterConfig>()
        try {
            cmd.send(EngineCommand.ChangeMembership(target, d))
        } catch (_: ClosedSendChannelException) {
            throw NotLeaderException("node is closed")
        }
        return d.await()
    }

    // ── §3.10 Leadership transfer ─────────────────────────────────────────────

    /**
     * Validate and initiate a leadership transfer to [target].
     *
     * Rejects immediately when: not leader, target is self, target is not a voter.
     * Once accepted: blocks new proposals, sends AppendEntries to sync target, sends [TimeoutNow],
     * and arms a one-election-timeout timer after which the transfer is auto-abandoned.
     */
    private suspend fun onTransferLeadership(target: NodeId, response: CompletableDeferred<Unit>) {
        if (_role.value !is RaftRole.Leader) {
            response.completeExceptionally(NotLeaderException("transferLeadership: not the current leader"))
            return
        }
        if (target == transport.selfId) {
            response.completeExceptionally(IllegalArgumentException("transferLeadership: target must not be this node (${transport.selfId.value})"))
            return
        }
        val currentVoters = state.membershipState.effectiveConfig.voters
        if (target !in currentVoters) {
            response.completeExceptionally(IllegalArgumentException("transferLeadership: target ${target.value} is not a voter in the current config ($currentVoters)"))
            return
        }
        // §3.10 step 1, the reverse direction of the onChangeMembership gate: refuse to start a transfer
        // while a membership change is still converging. pendingConfigChange stays non-null from
        // changeMembership until the resulting Simple entry commits (onConfigCommitted), and the
        // Joint→Simple auto-append fires inside that window — appending an entry that would grow
        // lastLogIndex mid-transfer, moving the goalpost the target is chasing. Transfer and membership
        // change are thus mutually exclusive in both directions, which is what keeps lastLogIndex stable
        // for the duration of the transfer (the isTargetCaughtUp predicate relies on this).
        if (pendingConfigChange != null) {
            response.completeExceptionally(MembershipChangeInProgressException("transferLeadership: a membership change is in progress"))
            return
        }
        // A second concurrent call while one is already in flight: reject the second. `start` arms the
        // auto-timeout timer (one election-timeout window) and parks `response`, returning false iff a
        // transfer is already in flight — in which case `inFlightTarget` is the existing target.
        if (!transfer.start(target, state.currentTerm, response)) {
            response.completeExceptionally(IllegalStateException("transferLeadership: a transfer to ${transfer.inFlightTarget?.value} is already in flight"))
            return
        }
        emitTrace(RaftTraceEvent.LeadershipTransferStarted(nextClock(), transport.selfId, target))
        debug { "onTransferLeadership: transfer started to ${target.value}" }

        // Sync target's log; send TimeoutNow now iff it is already fully caught up (matchIndex >= lastLogIndex,
        // §3.10 step 2). Otherwise the AppendEntries ACK path (onAppendEntriesResponse → transfer.isTargetCaughtUp)
        // sends it once the target catches up. AppendEntries delivery is best-effort — the heartbeat loop retries.
        sendAppendEntries(target)
        if (transfer.isTargetCaughtUp(target, state.matchIndex[target] ?: 0L, state.lastLogIndex)) sendTimeoutNow(target)
    }

    /** Send a [RaftMessage.TimeoutNow] to [target]. */
    private suspend fun sendTimeoutNow(target: NodeId) {
        debug { "sendTimeoutNow: sending TimeoutNow to ${target.value} term=${state.currentTerm}" }
        send(target, RaftMessage.TimeoutNow(state.currentTerm))
    }

    /**
     * Auto-timeout fired: the target did not win an election within one election-timeout window.
     * Resume normal operation (re-enable proposals) and fail the transfer deferred.
     */
    private suspend fun onTransferTimeout(epoch: Long) {
        val target = transfer.onTimeout(epoch) ?: return   // already resolved or superseded — ignore stale timer
        debug { "onTransferTimeout: transfer to ${target.value} timed out — resuming normal operation" }
        emitTrace(RaftTraceEvent.LeadershipTransferAbandoned(
            nextClock(), transport.selfId, target, LeadershipTransferAbandonReason.Timeout,
        ))
    }

    /**
     * Explicit cancel from the application: abort the in-flight transfer and resume proposals.
     */
    private suspend fun onCancelTransfer() {
        val target = transfer.onCancel() ?: return   // nothing in flight — no-op
        debug { "onCancelTransfer: transfer to ${target.value} cancelled" }
        emitTrace(RaftTraceEvent.LeadershipTransferAbandoned(
            nextClock(), transport.selfId, target, LeadershipTransferAbandonReason.Cancelled,
        ))
    }

    /**
     * §3.10 TimeoutNow received: immediately start a real election without waiting for election timeout.
     *
     * Only valid when this node is a voting follower (not a leader, candidate, or learner), only when
     * [from] is a current voter ([onMessage]'s §5.2/§8 authority gate — TimeoutNow is a leader→peer RPC,
     * so a non-voter sender is a forgery), and only when [from] is the node [leaderForTerm] holds as
     * this term's established leader (#1900 — no exceptions since the pin became durable; see the
     * guard below).
     * The message must carry **exactly** our current term: a stale one is ignored, and so is one
     * strictly ahead of us (#1889 — see the guard below for why nothing honest is lost).
     *
     * The pre-vote phase is intentionally skipped: the leader already validated this node's log is
     * up-to-date (it just sent AppendEntries to sync us), so the pre-vote safety check is redundant
     * and would only delay the election. We jump straight to a real RequestVote.
     */
    private suspend fun onTimeoutNow(from: NodeId, m: RaftMessage.TimeoutNow) {
        debug { "onTimeoutNow: from=${from.value} term=${m.term} currentTerm=${state.currentTerm} role=${_role.value}" }
        // Ignore if from a stale leader or if we are already leader/candidate.
        if (m.term < state.currentTerm) {
            debug { "onTimeoutNow: stale term ${m.term} < currentTerm=${state.currentTerm} — ignoring" }
            refuseFrame(from, m, RefusalGate.TimeoutNowStaleTerm)
            return
        }
        // The `Candidate` disjunct is currently dead as a *decision*, and is deliberately retained
        // anyway. Deleting it leaves the module green, and #1980's survey traced that to an equivalent
        // mutant rather than a coverage gap: three invariants together make a Candidate hold no pin for
        // its own term, so the leader-identity guard below would refuse every sender this arm could
        // ever see. (1) [RaftRole.Candidate] is assigned at exactly one site, in [startRealElection],
        // after `persistTermAndVote(currentTerm + 1, …)` has already invalidated the previous term's
        // pin; (2) the pre-vote phase does not set the role — [onElectionTimeout] writes `followerRole`
        // — so a probing node is not a Candidate; (3) [adoptLeaderForTerm], the only pin site a peer
        // can drive, runs only after [demoteToFollowerOnLeaderContact] has cleared `Candidate`, in both
        // [onAppendEntries] and [onInstallSnapshot]. [adoptLeaderForTerm]'s KDoc argues the same thing
        // from the other side. It is additionally conditional on storage honouring its own writes: the
        // `init` restore seeds the pin RAW, so a record for a term above the restored `currentTerm` —
        // the torn write that restore's KDoc contemplates — would put a Candidate on a live pin.
        //
        // Retained precisely because it rests on none of that. An unreachability argument is a smell
        // here (#1965, #1886): a guard that holds regardless outlives the invariants it would otherwise
        // lean on, at the cost of one `is` check on a path that already refuses the frame. If any of
        // the three moves, this arm becomes live — rather than becoming a bug found the hard way.
        if (_role.value is RaftRole.Leader || _role.value is RaftRole.Candidate) {
            debug { "onTimeoutNow: already ${_role.value} — ignoring" }
            refuseFrame(from, m, RefusalGate.TimeoutNowSelfLeaderOrCandidate)
            return
        }
        // A TimeoutNow strictly ahead of our term carries NO authority we can check (#1889). The leader
        // identity below is meaningful only at our own term ([leaderForTerm] is by construction a fact
        // about `currentTerm`), so at a higher term there is nothing to authenticate the sender
        // against — which is precisely why the leader check below used to be scoped to the same-term lane,
        // and precisely why the higher-term lane was a free pre-vote-less election on demand for any peer
        // that could address us, repeatedly.
        //
        // Refusing it costs no honest §3.10 transfer. Unlike AppendEntries, TimeoutNow has no catch-up
        // role: [sendTimeoutNow] emits the *leader's own* currentTerm, and both call sites reach it only
        // once `isTargetCaughtUp` holds — matchIndex[target] >= lastLogIndex. matchIndex advances by
        // exactly two writes, [onAppendEntriesResponse] and [onInstallSnapshotResponse], both gated on
        // `_role is Leader && m.term == currentTerm` and both fed by a responder that replies with its
        // OWN post-adoption currentTerm. So a matchIndex value produced *this term* proves the target
        // already reached our term before the frame existed, and that causality survives arbitrary
        // reordering (the frame does not exist until the ACK lands).
        //
        // The one hole in "produced this term": [becomeLeader] resets matchIndex to 0 only for
        // `otherMembers` — the CURRENT configuration's members — and nothing ever clears the map, so a
        // NodeId outside the config at election time keeps a stale value across the term change. A peer
        // removed while its matchIndex was high, then re-admitted and made a transfer target, can satisfy
        // `isTargetCaughtUp` on that stale index and be sent a TimeoutNow at a term it never adopted.
        // This guard is therefore **fail-safe-then-retry**, not "cannot happen": the premature frame is
        // dropped with no state touched, the in-flight transfer survives, and the next heartbeat ACK from
        // the now-same-term target re-fires [sendTimeoutNow] correctly — well inside the transfer's
        // one-election-timeout auto-abandon window, since heartbeatInterval << electionTimeout.
        //
        // Either way a node that genuinely IS behind must catch up through the ordinary election-timeout
        // path, whose pre-vote round checks its log — not through an immediate election that skips it.
        //
        // Dropped WITHOUT adopting m.term, matching the precedence the §5.2 gate below and the
        // implausible-term bound (#1855/#1886) already set: sender-authority validation comes before
        // §5.1 term adoption, or an unauthenticated frame gets to move durable state on its way to the
        // floor. (This also closes the remote route into [checkedRestoredTerm] documented there.)
        if (m.term > state.currentTerm) {
            debug { "onTimeoutNow: unauthenticated future term ${m.term} > currentTerm=${state.currentTerm} — ignoring" }
            refuseFrame(from, m, RefusalGate.TimeoutNowFutureTerm)
            return
        }
        // Only this term's leader may issue TimeoutNow. m.term is now provably equal to currentTerm (the
        // two guards above), so a per-term leader identity is meaningful and the comparison is sound.
        //
        // Authenticated against [leaderForTerm], NOT `_leader` (#1900). The two differ precisely where it
        // matters here: `_leader` is "a live leader I can talk to" and [relinquishToFollower] nulls it on
        // a SAME-term step-down — reachable from [onQuorumCheck]'s LostQuorum and from RemovedFromConfig,
        // neither of which moves the term. A node that led term T and stood down at T therefore sat at
        // `_leader == null` for the rest of T and accepted a TimeoutNow from ANY voter. [leaderForTerm]
        // is sticky for the term (nothing clears it; staleness is decided at the read), so that node
        // still holds itself as the term's established leader and the frame is refused.
        //
        // The match is required OUTRIGHT — there is no `established == null` carve-out any more, and
        // removing it is the rest of #1900. The carve-out existed because the pin was in-memory: an
        // honest §3.10 target that ACKed at term T, restarted, and came back at T as a caught-up
        // Follower held no pin, so requiring a match would have dropped its TimeoutNow and failed the
        // transfer on its auto-timeout — and admitting that window admitted any voter's forgery with
        // it, since a node cannot locally tell the two apart. [pinLeaderForTerm] now writes through to
        // [RaftStorage.saveLeaderForTerm] and the `init` restore reads it back, so that target comes
        // back holding `L` and this guard passes on the identity rather than on the absence of one.
        //
        // Nothing else honest reaches here with no pin. A `TimeoutNow` is emitted only once
        // `isTargetCaughtUp` holds, i.e. once matchIndex[target] was advanced THIS term, and matchIndex
        // advances only on [onAppendEntriesResponse] / [onInstallSnapshotResponse] — both of which are
        // fed by a responder that ran [adoptLeaderForTerm] BEFORE replying. So a matchIndex value
        // produced this term proves the target pinned this leader for this term, and the pin now
        // outlives the process. The one exception is the stale-matchIndex hole the higher-term guard
        // above documents (a peer outside the config at election time keeps its old matchIndex), which
        // is fail-safe-then-retry in exactly the same way: the frame is dropped with no state touched
        // and the next heartbeat ACK re-fires [sendTimeoutNow], well inside the transfer's
        // one-election-timeout auto-abandon window.
        val established = leaderForTerm
        if (from != established) {
            debug {
                "onTimeoutNow: sender ${from.value} is not term ${state.currentTerm}'s established " +
                    "leader (${established?.value}) — ignoring"
            }
            refuseFrame(from, m, RefusalGate.TimeoutNowSenderNotEstablishedLeader)
            return
        }
        // A learner never votes and must never start an election.
        if (_role.value is RaftRole.Learner) {
            debug { "onTimeoutNow: self is a learner — ignoring" }
            refuseFrame(from, m, RefusalGate.TimeoutNowSelfLearner)
            return
        }
        // Start a real election immediately (skip pre-vote — we are already up-to-date per the leader's sync).
        // §4.2.3: this election's RequestVotes carry the disrupt flag so the OTHER voters grant it despite
        // their leader-lease being live (needed at n>=4, where the transferring leader alone is not a quorum).
        debug { "onTimeoutNow: starting immediate election (skipping pre-vote)" }
        startRealElection(leadershipTransfer = true)
    }

    // ── Client-proposal forwarding (§8) ──────────────────────────────────────
    // Forwarding state (the outstanding forwards, the no-leader queue, and the correlation
    // nonce) lives in `forwarder` (ProposalForwarder), declared before `init` above (#1077).
    // onForward (the LEADER side) stays here — it is a propose-path entry point, not
    // forwarder state.

    /** Leader handles a forwarded proposal: run the normal propose path, reply with its fate. */
    private suspend fun onForward(from: NodeId, m: RaftMessage.Forward) {
        if (_role.value !is RaftRole.Leader) {
            // We are not the leader (stepped down, or stale Forward arrived). Reply NotLeader so
            // the originator's onForwardResponse fails with LeadershipLostException and the caller
            // can retry. Do NOT re-forward or queue — that would create a silent second hop and
            // leave the originator's deferred owned by two mechanisms.
            send(from, RaftMessage.ForwardResponse(m.clientRequestId, ForwardOutcome.NotLeader))
            return
        }
        val d = CompletableDeferred<LogEntry>()
        // Append under the proposer's own dedupKey UNCHANGED — the leader must not re-stamp it (that
        // would defeat exactly-once) and does not count it among the serials it issued itself.
        onPropose(m.command, m.dedupKey, d)
        scope.launch {
            val outcome = try {
                val e = d.await()
                ForwardOutcome.Committed(e.index, e.term)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is NotLeaderException || e is LeadershipLostException) ForwardOutcome.NotLeader
                else ForwardOutcome.Failed
            }
            // Best-effort reply; transport may be tearing down on close.
            runCatchingCancellable { send(from, RaftMessage.ForwardResponse(m.clientRequestId, outcome)) }
        }
    }

    /**
     * Follower handles the leader's reply to a forward it sent.
     *
     * §8 provenance (#1911): a `ForwardResponse` carries no term and no leader identity, and its
     * correlation id is a follower-local nonce starting at `0` on every node — so [from] is the only
     * thing tying the receipt to the peer the `Forward` actually went to. The forwarder drops any
     * receipt whose sender is not that peer (and any receipt at all when no forward is outstanding);
     * without that check any admitted peer could fabricate a commit for a command no node appended,
     * and the consumer's exactly-once bookkeeping would mark that write done — a permanently lost
     * write. A dropped receipt leaves the forward outstanding, exactly as a lost reply would.
     *
     * Each refusal logs the witness it checked against and which predicate fired, never a single
     * phrasing covering all three: `NotYetSent` is a *local* bookkeeping defect (a send site that
     * failed to stamp `sentTo`), and reporting it as "no forward outstanding to that peer" would tell
     * an operator the inverse of reality in precisely the case where a forward IS outstanding to
     * exactly that peer.
     */
    private fun onForwardResponse(from: NodeId, m: RaftMessage.ForwardResponse) {
        val reqId = m.clientRequestId
        val pf = when (val r = forwarder.onResponse(reqId, from)) {
            is ProposalForwarder.ResponseResolution.Resolved -> r.pf
            ProposalForwarder.ResponseResolution.NoSuchForward -> {
                debug { "onForwardResponse: dropped reqId=$reqId from $from — no forward registered under that id" }
                return
            }
            ProposalForwarder.ResponseResolution.NotYetSent -> {
                debug {
                    "onForwardResponse: dropped reqId=$reqId from $from — that forward is parked (sentTo=null), " +
                        "sent to no peer; a genuine reply here would mean a send site failed to stamp sentTo"
                }
                return
            }
            is ProposalForwarder.ResponseResolution.WrongSender -> {
                debug { "onForwardResponse: dropped reqId=$reqId from $from — that forward was sent to ${r.sentTo}" }
                return
            }
        }
        when (val o = m.outcome) {
            // Re-wrap with the proposer's own dedupKey so the returned entry matches what the leader appended.
            is ForwardOutcome.Committed -> pf.deferred.complete(LogEntry(o.index, o.term, pf.command, dedupKey = pf.dedupKey))
            ForwardOutcome.NotLeader, ForwardOutcome.Failed ->
                pf.deferred.completeExceptionally(LeadershipLostException("forwarded proposal was not committed; retry"))
        }
    }

    /**
     * Drain forwards queued while no leader was known. If we are now the leader, propose them
     * locally; otherwise send them to the current leader. No-op when nothing is queued or no
     * leader is known yet. The forwarder decides *which* action per parked entry; the engine
     * keeps the propose/send side-effects here.
     */
    private suspend fun flushWaitingForLeader() {
        val actions = forwarder.flush(_leader.value, transport.selfId, _role.value is RaftRole.Leader)
        for (action in actions) {
            when (action) {
                is ProposalForwarder.FlushAction.ReProposeLocally -> {
                    // The forward stays in the forwarder's map across this suspendable onPropose so teardown
                    // (forwarder.failAll) still owns the deferred until it lands in `pending`; evict it only
                    // AFTER onPropose returns (deferred now in `pending`).
                    onPropose(action.pf.command, action.pf.dedupKey, action.pf.deferred)
                    forwarder.reProposed(action.id)
                }
                is ProposalForwarder.FlushAction.SendToLeader ->
                    send(action.leaderId, RaftMessage.Forward(action.id, action.command, action.dedupKey))
            }
        }
    }

    // ── Message dispatcher ────────────────────────────────────────────────────

    private suspend fun onMessage(from: NodeId, m: RaftMessage) {
        // ── Term jump bound (#1833, made relative by #1897) ──────────────────────
        // Term adoption had no upper bound: any message carrying a higher term was adopted wholesale
        // via `stepDown(m.term, HigherTermObserved)`, and the election increment is a bare
        // `currentTerm + 1` on a `Long`, which wraps silently. One frame carrying
        // `term = Long.MAX_VALUE` is therefore adopted; the victim's own responses then carry it, so
        // every peer adopts it in turn on ordinary traffic; the next election computes
        // `Long.MAX_VALUE + 1` = `Long.MIN_VALUE`; every RequestVote/PreVote proposes a hugely
        // negative term that every recipient denies as stale — and NO LEADER CAN EVER BE ELECTED
        // AGAIN, cluster-wide, surviving restart because `currentTerm` is persisted. No exception, no
        // log line, no crashed node: the cluster silently stops making progress while every node
        // reports itself healthy.
        //
        // Honest terms increment ONCE PER ELECTION, so what makes a term implausible is not its
        // magnitude but its DISTANCE from what this node has already seen. A frame claiming a term
        // more than `maxTermJump` above ours asserts a run of elections that cannot have happened
        // while we were reachable, so it is proof of a malformed or foreign frame — and, the #1817
        // nonce reasoning, it admits no conservative in-range reading to clamp to, so the frame is
        // DROPPED. Negative terms are already nonsense: terms start at 0 and only increase.
        //
        // Placed at the dispatch boundary, before any handler and therefore before any adoption, so
        // one check covers every path on which a FRAME can raise `currentTerm`.
        //
        // Rejecting rather than throwing is required for the usual reason: this runs inside the
        // engine's actor loop, whose `try`/`finally` has no `catch`, so a `require` would convert a
        // malformed frame into permanent node death (#1818).
        //
        // `currentTerm` has exactly three writers — this one, the self-increment, and the init-restore
        // `storage.term()` — and NON-NEGATIVITY, which the subtraction below relies on, needs all three:
        // this check refuses a negative wire term, the self-increment cannot go down, and the restore is
        // bounded by [checkedRestoredTerm] (#1855). The restore was originally left out on the reading
        // that a poisoned durable term only costs that node's liveness — wrong on both halves: a
        // one-voter cluster reaches the wrap from there and PERSISTS `Long.MIN_VALUE`, and since kuilt
        // ships no durable RaftStorage the out-of-range value is an ordinary third-party storage bug,
        // not a migration artefact. See [checkedRestoredTerm] for why that site refuses to start
        // instead of dropping (there is no frame to drop) or clamping (§5.2).
        //
        // What keeps the `currentTerm + 1` INCREMENT sites free of Long wrap is no longer this check —
        // it is [termPinnedAtCeiling], which refuses to increment at or above `MAX_PLAUSIBLE_TERM` and
        // so is now the sole carrier of that guarantee. See the note on it there.
        //
        // ── Why the bound is RELATIVE and not an absolute ceiling (#1897) ────────
        // This check used to compare against a constant, MAX_PLAUSIBLE_TERM (`2^60`), and no constant
        // can work. Any absolute ceiling `A` has a cliff AT `A`: the value it admits is the value
        // whose successor it refuses, so a single frame at `A` propagates on ordinary traffic, every
        // voter pins there, and the `currentTerm + 1` every election must propose is then dropped by
        // every recipient INCLUDING ITS AUTHOR — #1833's symptom reached through the one value the
        // filter let past. #1886 measured that moving the constant does not help: an exclusive `>=`
        // relocated the cliff to `2^60 - 1` and wedged identically. Closing it would need
        // `T <= A ==> T + 1 <= A`, true only for `A = infinity`.
        //
        // Bounding the JUMP has no such value. `currentTerm + 1` is admissible at every term, so no
        // term has an unrepresentable successor and there is no boundary left to contain. It is also
        // the stronger test — implausibility measured against this node's own progress is a local
        // witness, where a constant is a guess about the deployment — and it converts a one-frame
        // attack into an infeasible one: reaching the arithmetic danger zone now costs
        // ~`2^63 / maxTermJump` accepted frames rather than a single frame.
        //
        // Written as a SUBTRACTION rather than `wireTerm > state.currentTerm + maxTermJump` so the
        // guard against an overflowing term cannot itself overflow. `currentTerm` is no longer bounded
        // above by a constant (adoption is relative now), so the additive form could wrap negative near
        // `Long.MAX_VALUE` and then refuse everything. Both operands are non-negative — `wireTerm`
        // because the malformed-term arm below returns first on a negative one (until #1989 split
        // them that was the short-circuiting left half of one `if`), `currentTerm` by
        // [checkedRestoredTerm] and by only ever being raised from an admitted non-negative term —
        // so the difference is representable.
        //
        // ### What MAX_PLAUSIBLE_TERM still does, and the asymmetry that opens
        //
        // It is unchanged at `2^60` and keeps its other jobs: [checkedRestoredTerm],
        // [checkedRestoredSnapshotMeta] and [isWellFormedSnapshotChunk], where a value is read back off
        // a disk or unpacked from a frame rather than compared against our own progress. It also still
        // supplies the OVERFLOW HEADROOM this comment's `currentTerm + 1` argument rests on — but now
        // via [termPinnedAtCeiling] guarding the two increment sites, not via this check.
        //
        // The consequence, stated rather than left to be rediscovered: the two constants no longer
        // agree by definition. `2^60 + 1` is now ADOPTABLE here but still not RESTORABLE, so a node
        // that adopted it would persist a term its own next boot refuses to start on. That requires
        // first reaching `2^60`, which after this change costs ~10^14 accepted frames instead of one —
        // unreachable in practice, and the price of removing a cliff that was reachable in one.
        val wireTerm = m.wireTerm
        // The bound's two arms were one `if` until #1989 split them. They refuse for different
        // reasons — malformed below us vs implausibly far above us — and a shared `return` made them
        // indistinguishable to any test, so [RefusalGate] now names which one fired. The MALFORMED arm
        // must stay FIRST: the jump arm's subtraction is only free of `Long` wrap because a negative
        // `wireTerm` has already been refused here (the "short-circuiting test to its left" the
        // overflow argument above refers to is now this `return`).
        if (wireTerm != null && wireTerm < 0L) {
            debug {
                "onMessage: dropped ${m::class.simpleName} from $from — negative term=$wireTerm " +
                    "(currentTerm=${state.currentTerm})"
            }
            refuseFrame(from, m, RefusalGate.ImplausibleNegativeTerm)
            return
        }
        if (wireTerm != null && wireTerm - state.currentTerm > raftConfig.maxTermJump) {
            debug {
                "onMessage: dropped ${m::class.simpleName} from $from — implausible term=$wireTerm " +
                    "(currentTerm=${state.currentTerm}, maxTermJump=${raftConfig.maxTermJump})"
            }
            refuseFrame(from, m, RefusalGate.ImplausibleTermJump)
            return
        }

        // ── §5.2 / §8 leader-authority gate (#1383, #1889) ───────────────────────
        // [RaftMessage.isLeaderToPeer] is the type test, and the only place that set is written: an
        // exhaustive `when` with no `else`, so a new leader→peer RPC cannot compile without deciding
        // whether this gate applies to it. Until #1973 this site re-enumerated the three types by
        // hand, and a hand-rolled list compiles perfectly well with a type missing from it — which is
        // exactly what #1889 was, a `TimeoutNow` this gate did not test.
        //
        // Only a voter can ever be leader (§5.2: a candidate must win a majority of the voter set), so
        // a leader→peer frame whose *sender* is not a current voter is a forgery — an
        // admitted-but-malicious learner/spoke that reached us over the cross-server
        // relay, which preserves the honest origin (`origin == sender` spoof-checking
        // passes) yet cannot vouch for the RPC type. Drop it BEFORE dispatch: the log
        // path does no `from` validation, so an accepted forged AppendEntries would
        // adopt m.term, set `_leader` from the payload, and truncate-then-append
        // (log corruption), and an InstallSnapshot would overwrite state — not the mere
        // term-inflation a spoof-only view suggests. `from` here is already the true
        // origin (SeamRaftTransport / RoutedRaftTransport / RaftRelayHub unwrap the relay
        // envelope), and `membershipState.voters` is the live committed voter set, so a
        // legitimate leader (always a voter) passes unchanged.
        //
        // TimeoutNow joined the type test in #1889. Its damage is not the log but *liveness*:
        // [onTimeoutNow] bypasses the election timeout AND the pre-vote round, so an ungated
        // frame is an election a non-voter can force on demand and repeat at will — exactly
        // the disruption PreVote exists to deny. Narrowing the attacker set to voters is only
        // half of the fix; the other half (a TimeoutNow ahead of our term has no authority to
        // check, so it is refused rather than adopted) lives in [onTimeoutNow].
        //
        // The gate is skipped while `voters` is empty — the pre-bootstrap learner seed
        // (`ClusterConfig(voters = emptySet(), learners = {self})`) of an appoint-the-host
        // joiner/spectator, which has not yet learned the cluster's config and MUST accept
        // the leader's AppendEntries/InstallSnapshot to catch up and be promoted (dropping
        // them here would deadlock the join). This exposes no voter: a node with no known
        // voters is by definition not a voter, and the issue is a *voter's* log integrity.
        // It exposes no leadership either: such a node has none to transfer, and [onTimeoutNow]
        // refuses to campaign as a Learner regardless.
        // The instant it applies the config entry that seats voters, the gate arms and
        // every subsequent leader→peer frame is validated. Mirrors RoutedRaftTransport's
        // player-side `origin ∈ voters()` check (the relay-side half of #1383).
        //
        // This gate is the module's exemplar of the "defend — the recipient holds a local
        // witness" half of its trust policy; the accepted, unauthenticated exposures on the
        // other half are listed under "Trust between peers" in kuilt-raft/module.md. That
        // section also records where this same predicate has failed in BOTH directions — too
        // narrow (#1889, fixed above) and too strict (#1898) — i.e. that the witness being
        // local and the witness being possibly stale are one property, and the cost of the
        // rule. The too-strict direction is CLOSED-AS-ACCEPTED, not fixed: a node absent
        // across a voter-set rotation still refuses the current leader's frames forever, and
        // this drop stays. What #1898 added is the report below, so the state has a name.
        val voters = state.membershipState.voters
        if (m.isLeaderToPeer && voters.isNotEmpty() && from !in voters) {
            debug { "onMessage: dropped ${m::class.simpleName} from non-voter $from (§5.2 leader-authority gate) membershipState=${state.membershipState}" }
            refuseFrame(from, m, RefusalGate.LeaderAuthority)
            return
        }
        // A leader→peer frame that clears both gates AT OR ABOVE our term ends any refusal run: whatever
        // else is being dropped alongside, a node a current leader is successfully reaching is not
        // jammed. The term condition mirrors the one in [noteRefusedLeaderFrame] and is load-bearing in
        // the same direction: a frame from BEHIND our term is a straggler from a deposed leader, which
        // neither counts toward a run nor should clear one — an ex-voter still in our stale set, still
        // heartbeating at its old term, would otherwise suppress the report for exactly the node this
        // exists to name.
        if (m.isLeaderToPeer && wireTerm != null && wireTerm >= state.currentTerm) refusedLeaderFrameRun = 0
        onValidatedMessage(from, m)
    }

    /**
     * Observe one leader→peer frame that a dispatch-boundary gate just dropped, and name the wedge if
     * this node has been doing that for a sustained run while making no progress of its own (#1898).
     *
     * **Changes no decision.** The gate has already returned the frame to the floor and that stays
     * correct either way; all this adds is the signal. That is what makes detection safe: an honest
     * long absence and a hostile peer produce the same local symptom, so a gate *relaxed* on this
     * predicate would relax for both (`docs/raft-wedge-diagnosis-and-recovery.md`).
     *
     * Reached only through [refuseFrame], for the gates whose [RefusalGate.wedgeGate] is non-null —
     * the two at [onMessage]'s dispatch boundary, which since #1989 are three [RefusalGate] values
     * (the implausible-term bound's two arms and the §5.2 gate). [onTimeoutNow]'s guards map to
     * `null` and never arrive here; a wedge is about frames that could not be *dispatched*.
     *
     * ### The predicate, and why each clause is there
     *
     * - **A leader→peer type.** [RaftMessage.isLeaderToPeer] — the same three types the §5.2 gate
     *   tests. The jump bound has no type test of its own, so this is where the filter lives for that
     *   gate. A refused `PreVote` or `RequestVoteResponse` says nothing about being able to make
     *   progress; a refused `AppendEntries` is exactly the frame that would have carried it.
     * - **[senderTerm] at least ours.** Tested rather than inferred: a frame from *behind* us is an
     *   ordinary stale straggler, and refusing it is the gate working, not a wedge. No gate that
     *   reaches here establishes the relation on its own. The §5.2 leader-authority gate tests the
     *   *sender* and the message *type* and says nothing about the term, so its frames arrive at any
     *   term at all; and [RefusalGate.ImplausibleNegativeTerm] is malformed and lands below us, so it
     *   is excluded here even though it is named as a `TermJump` refusal. Only
     *   [RefusalGate.ImplausibleTermJump] implies a term above ours — [RaftConfig.maxTermJump] is
     *   validated positive since #1972, so `wireTerm - currentTerm > maxTermJump` does now put
     *   `wireTerm` above `currentTerm` — and one gate out of three is not a relation this function
     *   may assume.
     * - **Our commit index standing still.** A node that is committing is making progress, whatever it
     *   is refusing — most concretely a wedged voter that campaigned and won inside its own stale set,
     *   which is a split-brain rather than the silence this reports. The run restarts whenever
     *   [_commitIndex] moves.
     * - **Nothing accepted in between.** [onMessage] zeroes the run on any leader→peer frame that
     *   passes both gates. Without that, a sustained forged stream arriving alongside honest traffic
     *   would report a healthy node as jammed.
     *
     * ### Latched per voter-set epoch
     *
     * The report is emitted once for as long as [MembershipState.voters] stays the same. Everything in
     * it is a function of that set, so a second emission under the same one carries no information —
     * and a finer latch would be a log-amplification lever: a peer alternating between two identities
     * would mint a fresh report per alternation, handed to the exact sender the §5.2 gate exists to
     * contain. It cannot change our voter set, so it cannot re-arm this. Contrast
     * [RaftMetric.SnapshotRejectedSizeCeiling], which is deliberately *un*latched because "is this
     * still happening?" separates its two cases; here that question is already answered by
     * [RaftNode.commitIndex], which a consumer can watch without any help from this engine.
     *
     * Never throws: reached from the actor loop on a path a remote frame controls, so a `require` here
     * would convert one hostile frame into permanent node death (#1818).
     */
    private fun noteRefusedLeaderFrame(
        from: NodeId,
        m: RaftMessage,
        senderTerm: Long?,
        gate: RaftMetric.WedgeSuspected.Gate,
    ) {
        if (!m.isLeaderToPeer) return
        if (senderTerm == null || senderTerm < state.currentTerm) return

        val commit = _commitIndex.value
        if (commit != refusedLeaderFrameRunCommitIndex) {
            refusedLeaderFrameRunCommitIndex = commit
            refusedLeaderFrameRun = 0
        }
        refusedLeaderFrameRun++
        if (refusedLeaderFrameRun < WEDGE_SUSPECTED_RUN) return

        val voters = state.membershipState.voters
        if (voters == wedgeReportedAtVoters) return
        wedgeReportedAtVoters = voters

        emitMetric(RaftMetric.WedgeSuspected(from, senderTerm, state.currentTerm, voters, gate))
        logger.warn { "[raft:${transport.selfId}] ${wedgeDiagnostic(from, m, senderTerm, voters, gate)}" }
    }

    /**
     * The operator-facing diagnostic for [noteRefusedLeaderFrame].
     *
     * Built by a function rather than inlined so the `warn` stays lazy, mirroring [ceilingDiagnostic]
     * and [snapshotCeilingDiagnostic].
     *
     * **Says what to do, and says the one thing not to do.** The route back is a *new identity* — a
     * fresh [NodeId] over empty storage, re-admitted by an ordinary §4 membership change. Wiping
     * storage under the same id looks like the same fix and is the opposite of it: the node returns
     * with no memory of a term it already voted in and votes again, which is two leaders in one term.
     * A reader who is told only "re-provision this node" will reach for the wipe, so the warning is
     * part of the line rather than a footnote elsewhere.
     */
    private fun wedgeDiagnostic(
        from: NodeId,
        m: RaftMessage,
        senderTerm: Long,
        voters: Set<NodeId>,
        gate: RaftMetric.WedgeSuspected.Gate,
    ): String =
        "WEDGE SUSPECTED — refused $refusedLeaderFrameRun leader→peer frames in a row at or above our " +
            "own term (threshold $WEDGE_SUSPECTED_RUN), accepting none and committing nothing in " +
            "between (latest: ${m::class.simpleName} from $from at term=$senderTerm; " +
            "currentTerm=${state.currentTerm}, commitIndex=${_commitIndex.value}, voters=$voters, " +
            "gate=$gate). This node can no longer be caught up in place, and nothing recovers on its " +
            "own. Bring it back as a NEW member — a fresh NodeId over EMPTY storage — admitted by an " +
            "ordinary single-server membership change. Do NOT wipe storage under the same NodeId: it " +
            "returns having forgotten a term it already voted in and votes again, which is a §5.2 " +
            "Election Safety violation (two leaders in one term). See " +
            "docs/raft-wedge-diagnosis-and-recovery.md (#1898)."

    private suspend fun onValidatedMessage(from: NodeId, m: RaftMessage) = when (m) {
        is RaftMessage.RequestVote             -> onRequestVote(from, m)
        is RaftMessage.RequestVoteResponse     -> onRequestVoteResponse(from, m)
        is RaftMessage.AppendEntries           -> onAppendEntries(from, m)
        is RaftMessage.AppendEntriesResponse   -> onAppendEntriesResponse(from, m)
        is RaftMessage.InstallSnapshot         -> onInstallSnapshot(from, m)
        is RaftMessage.InstallSnapshotResponse -> onInstallSnapshotResponse(from, m)
        is RaftMessage.PreVote                 -> onPreVote(from, m)
        is RaftMessage.PreVoteResponse         -> onPreVoteResponse(from, m)
        is RaftMessage.TimeoutNow              -> onTimeoutNow(from, m)
        is RaftMessage.Forward                 -> onForward(from, m)
        is RaftMessage.ForwardResponse         -> onForwardResponse(from, m)
    }

    private suspend fun send(peer: NodeId, m: RaftMessage) =
        transport.sendTo(peer, raftCbor.encodeToByteArray(m))

    override suspend fun transferLeadership(target: NodeId) {
        val d = CompletableDeferred<Unit>()
        try {
            cmd.send(EngineCommand.TransferLeadership(target, d))
        } catch (_: ClosedSendChannelException) {
            throw NotLeaderException("node is closed")
        }
        d.await()
    }

    override fun cancelTransfer() {
        cmd.trySend(EngineCommand.CancelTransfer)
    }

    override suspend fun close() {
        try { cmd.send(EngineCommand.Close) } catch (_: ClosedSendChannelException) { /* already closed */ }
    }

    private companion object {
        /** Reserve for the CBOR envelope around a chunk's [RaftMessage.InstallSnapshot.data] payload. */
        const val HEADER_BUDGET = 256

        /**
         * Upper sanity bound on a term this node **stores or unpacks** — [checkedRestoredTerm],
         * [checkedRestoredSnapshotMeta], [isWellFormedSnapshotChunk] — and the ceiling
         * [termPinnedAtCeiling] refuses to increment past (issue #1833).
         *
         * **It is no longer the term-adoption rule.** #1897 replaced that with a relative bound on the
         * *jump* (`RaftConfig.maxTermJump`, enforced in [onMessage]), because no absolute ceiling can
         * avoid a cliff at its own value: the term it admits is the term whose successor it refuses.
         * What survives here is the job a constant is actually good for — a range check on a value read
         * back off a disk or unpacked from a frame, where there is no local progress to compare against.
         *
         * `2^60` deliberately stays where it is. The obvious tightening — a lower ceiling to catch a
         * storage adapter that loses precision — does not work: `2^60` is a power of two, so a `Double`
         * column round-trips it *exactly* and a ceiling-only test passes the very adapter the tightening
         * was meant to catch (which is why the conformance suite pins a full-mantissa value beside it
         * instead). What the constant is genuinely for is **overflow headroom**: it keeps `currentTerm + 1`
         * a factor of eight clear of the `Long` overflow that would wrap an election term to
         * `Long.MIN_VALUE` and wedge the cluster permanently.
         */
        const val MAX_PLAUSIBLE_TERM = 1L shl 60

        /**
         * Upper sanity bound on a snapshot's `lastIncludedIndex` arriving off the wire (issue #1868) —
         * see [isWellFormedSnapshotChunk]. The index-half counterpart of [MAX_PLAUSIBLE_TERM].
         *
         * §5.4.1 orders positions by `(term, index)` lexicographically, so **tying on term and winning
         * on index dominates** just as surely as a huge term does — bounding only the term half leaves
         * the violation reachable. Unlike the AppendEntries lane, the snapshot lane has no structural
         * check to fall back on: [isWellFormedBatch] pins each entry's index to `prevLogIndex + 1 + i`
         * and Log Matching pins `prevLogIndex` to the local log, whereas
         * `state.entryAt(lastIncludedIndex)?.term == lastIncludedTerm` **fails open** — the `null` for
         * any index past the tail falls through to discard-whole rather than rejecting.
         *
         * `2^60` mirrors [MAX_PLAUSIBLE_TERM] and is unreachable honestly: a leader only ever sends
         * `SnapshotMeta.lastIncludedIndex` for a snapshot it stored, so an honest value is bounded by
         * its own `lastLogIndex` — one per proposal. Indices advance on a much faster clock than terms
         * (per proposal, not per election), which is why the headroom matters: 2^60 is ~10^18
         * proposals, still some 36,000 years at a sustained 1M proposals/second.
         *
         * The bound is **inclusive**, matching [MAX_PLAUSIBLE_TERM]'s own `> MAX_PLAUSIBLE_TERM` test.
         * Inclusive is safe *here* because this is a pure plausibility filter with no progress
         * obligation — contrast [nextIndexAfterFailure]'s ceiling (#1829), which must be **exclusive**
         * because §5.3 backup has to strictly decrease or it livelocks. Accepting exactly `2^60`
         * creates no fixed point, and no honest frame is near either side of the boundary.
         */
        const val MAX_PLAUSIBLE_INDEX = 1L shl 60
    }
}
