package us.tractat.kuilt.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.election.ElectionLobby
import us.tractat.kuilt.session.election.LobbyMessage
import us.tractat.kuilt.session.election.SeamElectionLobby
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.JoinerResumeHost
import us.tractat.kuilt.session.partition.JoinerResumeMachine
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.session.SeamRoom")

/**
 * Factory for a **host-side** [JoinerReconnectController], invoked once when a host room starts
 * with the room-owned [roomId], [scope], and clock — the three inputs a controller needs but that
 * only exist after the seam is woven (the `roomId` is derived from the host's woven `selfId`), so a
 * caller cannot pre-build the instance and must supply this lambda instead.
 *
 * Supply it to [SeamRoomFactory] to replace the default fixed-window hold with a custom policy —
 * for example a **predicate or unbounded hold** that keeps a disconnected joiner's seat open for as
 * long as a durable rejoin record exists, driving [JoinerReconnectController.expire] itself when
 * that record is gone rather than on a fixed timer. When no factory is supplied the room builds the
 * default [DefaultJoinerReconnectController], whose window is [HeartbeatConfig.reconnectWindow].
 */
public typealias JoinerReconnectControllerFactory =
    (roomId: RoomId, scope: CoroutineScope, clock: () -> Instant) -> JoinerReconnectController

/**
 * [Loom]-backed implementation of [RoomFactory].
 *
 * Each call to [host] or [join] weaves a new [Seam] via [loom], wraps it in a
 * [SeamRoom], and drives the admit/identify handshake:
 * - **Host** side: passively collects [AdmitMessage.Hello] from new peers and
 *   replies with [AdmitMessage.Welcome], adding the peer to the roster and
 *   broadcasting the welcome to all other admitted members.
 * - **Joiner** side: immediately sends [AdmitMessage.Hello] via broadcast and
 *   waits for the [AdmitMessage.Welcome] that confirms admission.
 *
 * [scope] is used to launch the per-Room admit loop coroutines. Callers should
 * use a scope whose lifetime matches the room's intended lifetime (e.g.
 * `backgroundScope` in tests, a structured session scope in production).
 *
 * [SeamRoom]'s internal membership state is guarded by a reentrant lock and is
 * safe under any dispatcher, including multi-threaded ones such as
 * `Dispatchers.Default`. Suspend calls (sends, broadcasts) are always performed
 * outside the lock.
 *
 * [clock] is required (not defaulted) so callers must make an explicit choice:
 * use [SeamRoomFactory.systemClock] for production wall-clock time or supply
 * a virtual clock in tests. An accidental epoch-zero default would ship wrong
 * timestamps silently.
 *
 * [heartbeatConfig] controls partition-detection timing.
 *
 * [admitTimeout] bounds the joiner's admit handshake: if no
 * [AdmitMessage.Welcome] arrives within this window after the joiner's `Hello`,
 * the room surfaces a terminal [MembershipEvent.AdmissionFailed] with
 * [AdmissionFailure.TimedOut] instead of waiting forever (#1178). Hosts ignore it.
 *
 * @see SeamRoomFactory.systemClock for the production convenience constructor.
 */
public class SeamRoomFactory(
    private val loom: Loom,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    private val admitTimeout: Duration = DEFAULT_ADMIT_TIMEOUT,
    /**
     * Optional override for the **host-side** reconnect-window controller (#1614). When supplied,
     * every host room this factory creates drives the [JoinerReconnectController] this lambda builds
     * instead of the default fixed-window [DefaultJoinerReconnectController] — letting a host
     * application implement its own hold policy, e.g. a predicate/unbounded hold that keeps a
     * disconnected joiner's seat open while a durable rejoin record exists and drives
     * [JoinerReconnectController.expire] itself. Ignored for joiner rooms. See
     * [JoinerReconnectControllerFactory].
     */
    private val reconnectControllerFactory: JoinerReconnectControllerFactory? = null,
) : RoomFactory {
    override suspend fun host(pattern: Pattern, memberName: String?): Room {
        val seam = loom.host(pattern)
        val roomId = RoomId(seam.selfId.value + "-room")
        return SeamRoom(
            seam = seam,
            role = SessionRole.Host,
            // This peer's own roster name. Null → peer-id-derived (see SeamRoom.resolvedMemberName).
            // Deliberately NOT pattern.sessionName — the session name names the session, not this
            // host (#1177).
            memberName = memberName,
            scope = scope,
            clock = clock,
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            roomId = roomId,
            // Host's own room identity — the value a joiner's Hello.targetRoom must
            // match (or leave null) to be admitted. Null (the Pattern default) means
            // this host declared no room and admits permissively.
            roomKey = pattern.roomKey,
            reconnectControllerFactory = reconnectControllerFactory,
        ).also { room -> room.start() }
    }

    override suspend fun join(tag: Tag, memberName: String?): Room {
        val seam = loom.join(tag)
        return SeamRoom(
            seam = seam,
            role = SessionRole.Joiner,
            // This peer's own roster name. Null → peer-id-derived (see SeamRoom.resolvedMemberName).
            // Deliberately NOT tag.sessionName — the discovered session name names the session being
            // joined, not this joiner (the #1177 latent-bug fix).
            memberName = memberName,
            scope = scope,
            clock = clock,
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            roomId = null,
            // Joiner's declared target room — travels in Hello.targetRoom so the host
            // can reject cross-room admission on a flat fabric. Null (the common case)
            // means the transport already bound the room; the host admits permissively.
            roomKey = tag.roomKey,
            // Re-weave the same tag on tear. For a resumable [Loom] (e.g. [MuxClientLoom])
            // this heals the same [seam] handle onto a fresh base; for a non-resumable Loom
            // it is a no-op with respect to auto-resume (see the `reweave` KDoc contract).
            reweave = { loom.join(tag) },
        ).also { room -> room.start() }
    }

    /**
     * Adopt an **already-woven** [seam] into a [Room] with an explicit [role] — no re-weave.
     *
     * Unlike [host]/[join] (which each weave a fresh seam), [adopt] takes ownership of a seam the
     * caller wove, so the calling layer can weave the mesh once and decide role afterward (the
     * host-election lobby, #1439). The returned [Room] owns the seam's lifetime from here:
     * [Room.leave] closes it — correct, because the seam is handed over exactly once.
     *
     * [role] is fixed for the room's lifetime. [roomKey] is the admit-gate key
     * ([us.tractat.kuilt.core.Pattern.roomKey]); [memberName] is this peer's own roster label
     * (null → peer-id-derived). Resume-after-tear is not wired (no `reweave`): a joiner whose host
     * link tears goes terminal ([MembershipEvent.HostLost]).
     */
    public suspend fun adopt(
        seam: Seam,
        role: SessionRole,
        memberName: String? = null,
        roomKey: String? = null,
    ): Room {
        val roomId = if (role == SessionRole.Host) RoomId(seam.selfId.value + "-room") else null
        return SeamRoom(
            seam = seam,
            role = role,
            memberName = memberName,
            scope = scope,
            clock = clock,
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            roomId = roomId,
            roomKey = roomKey,
            reconnectControllerFactory = reconnectControllerFactory,
        ).also { room -> room.start() }
    }

    /**
     * Symmetric lobby entry both peers call identically: weave the mesh via
     * [us.tractat.kuilt.core.Rendezvous.New] (a constant session name), then return an
     * [ElectionLobby] over the woven seam. Every peer elects the same host (`min(peers)`);
     * on Start the elected host runs the freeze round and each peer adopts a [Room] once (#1439).
     *
     * The seam's lifetime belongs to the lobby until a [Room] adopts it (or [ElectionLobby.leave]).
     *
     * **Weave timeout:** this delegates to the [Loom]'s own `weave`. On a real radio fabric (e.g.
     * `NwLoom`) `weave` blocks until the first peer resolves and may time out if no peer appears —
     * configure the fabric's weave timeout generously for a "wait for players" lobby. The lobby's
     * live membership always reads from [ElectionLobby.peers] (the woven seam), never a discovery roster.
     */
    public suspend fun electLobby(pattern: Pattern): ElectionLobby {
        val seam = loom.weave(Rendezvous.New(pattern))
        return SeamElectionLobby(
            seam = seam,
            factory = this,
            scope = scope,
            clock = clock,
            roomKey = pattern.roomKey,
        )
    }

    public companion object {
        /**
         * Production convenience constructor that wires [kotlin.time.Clock.System.now()]
         * as the clock. Use this for real deployments where wall-clock timestamps are needed.
         *
         * Tests should construct [SeamRoomFactory] directly with a virtual clock so
         * timestamps are deterministic and test-controlled.
         */
        public fun systemClock(
            loom: Loom,
            scope: CoroutineScope,
            heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
            admitTimeout: Duration = DEFAULT_ADMIT_TIMEOUT,
            reconnectControllerFactory: JoinerReconnectControllerFactory? = null,
        ): SeamRoomFactory = SeamRoomFactory(
            loom = loom,
            scope = scope,
            clock = { Clock.System.now() },
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            reconnectControllerFactory = reconnectControllerFactory,
        )

        /**
         * Default joiner admit deadline (#1178). Generous enough to cover a relay round-trip and
         * host-side admit work on a slow link, short enough that a dropped/refused `Hello` fails
         * loudly in seconds rather than hanging. Override per deployment via the [admitTimeout]
         * constructor parameter.
         */
        internal val DEFAULT_ADMIT_TIMEOUT: Duration = 30.seconds
    }
}

/**
 * Size of the [Room.events] replay cache (#692). Large enough to retain the startup-window
 * membership burst (the per-connection host room emits a single [MembershipEvent.Joined]; a
 * mesh room may admit several peers near-simultaneously) so a late subscriber can't miss it,
 * yet bounded so a long-lived room never accumulates unbounded history.
 */
private const val MEMBERSHIP_EVENT_REPLAY = 64

/**
 * [Seam]-backed [Room] implementation.
 *
 * Owns the admit-protocol state: a map of admitted peers (keyed by [PeerId]),
 * the mutable [roster], and [events]/[incoming] shared flows.
 *
 * Two coroutines run per room (both parented to the provided [scope]):
 * 1. **Main loop** — the single collector of [Seam.incoming]. Handles the admit protocol
 *    (Hello/Welcome), routes application frames to [incoming], filters heartbeat frames
 *    from application delivery, and fans incoming swatches out to [rawIncoming] so that
 *    per-peer [HeartbeatPartitionDetector]s can subscribe without contending for the channel.
 * 2. **Torn watcher** — observes [Seam.state] for a [SeamState.Torn] transition. On the **host**
 *    it evicts all peers and closes cleanly; on a **joiner** it hands off to the
 *    [JoinerResumeMachine], which either resumes over a re-woven base within the reconnect
 *    window or falls to terminal [MembershipEvent.HostLost] — see the `reweave` constructor
 *    parameter (#1037).
 *
 * The joiner's main-loop [Seam.incoming] collector runs in its own child job so a reconnect can
 * cancel it and re-subscribe on the healed generation (`ResumableChannel.incoming` binds the base
 * once at collection start, so the old collector goes silent after a re-weave).
 *
 * Additionally, a [HeartbeatPartitionDetector] is launched per admitted peer when the
 * admit handshake completes. The detector subscribes to [rawIncoming] (filtered by sender)
 * so it processes heartbeat ping/pong frames independently of the main loop.
 *
 * Partition event semantics by role:
 * - **Joiner**: a host transport close (transport `Torn`, or a heartbeat `TransportClosed`)
 *   attempts an in-window resume before falling to [MembershipEvent.HostLost] (#1037).
 *   [PartitionEvent.PeerLost] of the host (window expired) → [MembershipEvent.HostLost] + terminal.
 *   [PartitionEvent.PeerLost] of a non-host → [MembershipEvent.Left(PartitionExpired)].
 * - **Host**: [PartitionEvent.PeerLost] of any joiner → [MembershipEvent.Left(PartitionExpired)].
 * - Both roles: [PartitionEvent.PeerUnresponsive] → [MembershipEvent.Partitioned];
 *   [PartitionEvent.PeerRecovered] → [MembershipEvent.Recovered]. On the host these also fan
 *   out an authoritative [AdmitMessage.Paused] / [AdmitMessage.Unpaused] to the other members,
 *   so a member with no heartbeat edge to the paused peer (a star topology's other joiners)
 *   sees the same events a mesh peer detects for itself (#1557). Receipt is idempotent, so a
 *   mesh peer that detects locally *and* receives the fan-out emits once.
 * - **Clean leave** (a peer's [AdmitMessage.Goodbye]): the host evicts with
 *   [MembershipEvent.Left(Normal)] and propagates an authoritative [AdmitMessage.Farewell]
 *   to every remaining member ([propagateFarewell]), so joiners also evict promptly with
 *   `Normal` instead of waiting out the heartbeat window (#1292). The detector paths above
 *   remain the fallback for peers that vanish without a Goodbye.
 *
 * **Terminal state**: once [MembershipEvent.HostLost] fires, [broadcast] and [sendTo]
 * become silent no-ops. No auto-election is performed.
 *
 * **Thread safety**: all mutable membership state (`admittedById`, `closed`, `hostLost`,
 * `hostPeerId`, `incomingCollectJob`, `admissionFailed`, `admitDeadlineJob`, `detectorJobs`,
 * `channelViews`) is guarded by an atomicfu [reentrantLock]. The joiner-side resume state
 * (`resumeToken`, `pendingResume`, `reconnecting`, `reconnectJob`) lives in
 * [JoinerResumeMachine], which **shares the same lock instance** (see [resumeMachine]).
 * Critical sections perform only synchronous map/field operations; all suspend calls (sends,
 * broadcasts, re-weave, awaiting `Woven`, resume) are made outside the lock.
 *
 * [start] must be called by [SeamRoomFactory] after construction to launch these loops.
 */
// Shared constant roster for a room whose seam carries no attestation concept — never mutated.
private val EMPTY_ATTESTED_ROSTER: StateFlow<Map<PeerId, Principal>> =
    MutableStateFlow<Map<PeerId, Principal>>(emptyMap())

internal class SeamRoom(
    private val seam: Seam,
    role: SessionRole,
    /**
     * This peer's **own** roster name — the label other members see for this peer. Null means
     * "derive from the peer id" ([resolvedMemberName]); it is never derived from the session name
     * ([us.tractat.kuilt.core.Pattern.sessionName] / [us.tractat.kuilt.core.Tag.sessionName]),
     * which names the *session*, not this member (#1177).
     */
    private val memberName: String? = null,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val heartbeatConfig: HeartbeatConfig,
    /**
     * **Joiner only.** Admit deadline: if no [AdmitMessage.Welcome] admits this joiner within
     * this window after its `Hello`, the room fails loudly with
     * [MembershipEvent.AdmissionFailed] ([AdmissionFailure.TimedOut]) instead of hanging (#1178).
     *
     * Defaulted so tests that construct [SeamRoom] directly still compile; [SeamRoomFactory]
     * always passes its configured value. Ignored on the host side.
     */
    private val admitTimeout: Duration = SeamRoomFactory.DEFAULT_ADMIT_TIMEOUT,
    /**
     * Stable room identifier. Non-null for hosts (generated at room creation);
     * initially null for joiners (received from the host's [AdmitMessage.Welcome]).
     *
     * Defaults to null so existing tests that construct [SeamRoom] directly still compile.
     * [SeamRoomFactory] always passes the host-generated id explicitly.
     */
    private val roomId: RoomId? = null,
    /**
     * Room identity for the admit gate (A2, #1172). Its meaning depends on [role]:
     *
     * - **Host:** this room's own stable key ([us.tractat.kuilt.core.Pattern.roomKey]).
     *   A joiner's [AdmitMessage.Hello.targetRoom] is admitted only if it is null
     *   (permissive — the transport bound the room) or equal to this key; a non-null
     *   mismatch is rejected with [AdmitMessage.Reject] `"room-mismatch: …"`. This is
     *   the structural guard against cross-room admission on a flat fabric (a shared
     *   [us.tractat.kuilt.core.InMemoryLoom] where one mesh carries several rooms).
     * - **Joiner:** the target room to advertise in [AdmitMessage.Hello.targetRoom]
     *   ([us.tractat.kuilt.core.Tag.roomKey]).
     *
     * Null (the default) is permissive on both sides: a host without a declared room
     * key can't gate; a joiner without one names no target and is admitted anywhere.
     */
    private val roomKey: String? = null,
    /**
     * **Joiner only.** Re-weaves the underlying fabric after a transport tear, so the joiner
     * can attempt an in-window resume instead of going straight to terminal
     * [MembershipEvent.HostLost] (#1037).
     *
     * **Required Loom contract — same-instance heal.** Invoking this lambda must *heal the same
     * [seam] instance*: the [Loom] must return a stable, resumable handle whose [Seam.selfId] is
     * frozen and whose underlying channel is re-pointed onto a freshly-woven base. [MuxClientLoom]'s
     * `ResumableChannel` satisfies this: `loom.join(tag)` on a torn base re-weaves the base once and
     * returns the same handle, so this room's [seam] transitions back out of [SeamState.Torn].
     *
     * The [JoinerResumeMachine]'s reconnect loop checks exactly that — after re-weave, whether
     * [seam] left `Torn` — to tell a resumable loom from a non-conforming one; the [reweave] lambda
     * being non-null is **not** that signal. A [Loom] that mints a *new* seam per `join` does
     * **not** satisfy the contract: the heal is invisible to this room (it keeps its original
     * [seam]), so the re-wove seam is closed and the room falls to [MembershipEvent.HostLost].
     *
     * Null (the default) only for hosts and for joiners constructed directly without resume support.
     * **[SeamRoomFactory.join] always supplies a `reweave`** (`{ loom.join(tag) }`) regardless of
     * whether the loom is resumable, so for factory-created joiners the same-instance-heal check —
     * not `reweave == null` — is what decides resumable vs. non-resumable at tear time.
     */
    private val reweave: (suspend () -> Seam)? = null,
    /**
     * **Host only.** Optional override for the per-joiner reconnect-window controller (#1614).
     *
     * When non-null (and this room is a host with a [roomId]), invoked once here with [roomId],
     * [scope], and [clock] to build the [JoinerReconnectController] this room drives. Lets a host
     * application substitute its own hold policy — e.g. a predicate/unbounded hold that keeps a
     * seat while a durable rejoin record exists. When null (the default), the room builds the
     * standard [DefaultJoinerReconnectController] with the [heartbeatConfig]-derived window.
     */
    private val reconnectControllerFactory: JoinerReconnectControllerFactory? = null,
) : Room {
    override val selfId: PeerId = seam.selfId

    /** This peer's roster name: the caller-supplied [memberName], else its own peer id. */
    private val resolvedMemberName: String get() = memberName ?: selfId.value

    private val _role = MutableStateFlow(role)
    override val role: StateFlow<SessionRole> = _role.asStateFlow()

    /**
     * Guards every mutation of the plain membership state:
     * `admittedById`, `closed`, `hostLost`, `hostPeerId`, `incomingCollectJob`,
     * `detectorJobs`, `channelViews` — and, shared with [JoinerResumeMachine] (which is
     * handed this same instance), the joiner-side resume state.
     *
     * Multiple coroutines (`runMainLoop`, `runTornWatcher`, the resume machine's reconnect
     * attempt, `runReconnectEventLoop`, per-peer detector collectors, `scope.launch { admitPeer }`,
     * `scope.launch { handleResume }`) may run under a multithreaded dispatcher and all
     * read-modify-write that state. This reentrant lock serialises them.
     *
     * Critical sections are pure synchronous map/field operations (µs); all suspend calls
     * (`seam.sendTo`, `seam.broadcast`) run outside the lock — the lock is never held
     * across a suspension point.
     */
    private val lock = reentrantLock()

    // Admitted members (excluding self), keyed by PeerId for O(1) lookup.
    private val admittedById = mutableMapOf<PeerId, Member>()
    private val _roster = MutableStateFlow<Set<Member>>(emptySet())
    override val roster: StateFlow<Set<Member>> = _roster.asStateFlow()

    /**
     * Roster-first read (mirroring `GameSession.attestedPrincipals`): when the underlying [seam]
     * carries a [PrincipalRoster] (the mux-hub `RoomHubSeam`), expose its live map; otherwise a
     * constant empty roster (the 2-peer relay path surfaces principals via [Member.principal]).
     */
    override val attestedPrincipals: StateFlow<Map<PeerId, Principal>>
        get() = (seam as? PrincipalRoster)?.attestedPrincipals ?: EMPTY_ATTESTED_ROSTER

    /**
     * Admitted roster as a [StateFlow] of [PeerId]s, including self.
     *
     * Updated in lock-step with [_roster] by [syncRosterPeers]. Used by [RoomChannelSeam.peers]
     * to provide the admit-gated peer set to consumers such as
     * [us.tractat.kuilt.quilter.Quilter].
     */
    private val _rosterPeers = MutableStateFlow<Set<PeerId>>(setOf(selfId))
    internal val rosterPeers: StateFlow<Set<PeerId>> = _rosterPeers.asStateFlow()

    /** The underlying seam's state — forwarded to channel views. */
    internal val seamState: StateFlow<SeamState> get() = seam.state

    /** Channel views keyed by sub-id. Created on demand via [channel]. */
    private val channelViews = mutableMapOf<Short, Seam>()

    /**
     * Membership events carry a **bounded replay cache** (#692). A `replay = 0` flow drops
     * any [MembershipEvent] emitted while no one is collecting — and the room starts admitting
     * (emitting [MembershipEvent.Joined]) *before* a `host { onRoom }` consumer can subscribe,
     * so the join was lost into the void. The replay cache retains the most recent
     * [MEMBERSHIP_EVENT_REPLAY] events for a late subscriber, closing that startup race.
     *
     * Replay is **best-effort, not a membership log**: a subscriber to a long-lived room only
     * sees the recent tail, not the full history. [roster] remains the authoritative,
     * replay-safe source of current membership; treat [events] as idempotent notifications.
     */
    private val _events = MutableSharedFlow<MembershipEvent>(
        replay = MEMBERSHIP_EVENT_REPLAY,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<MembershipEvent> = _events.asSharedFlow()

    private val _incoming = MutableSharedFlow<RoomFrame>(extraBufferCapacity = 64)
    override val incoming: Flow<RoomFrame> = _incoming.asSharedFlow()

    /**
     * Broadcast bus for raw incoming [Swatch]es. The main loop fans every received
     * swatch here so per-peer [HeartbeatPartitionDetector] instances can subscribe
     * independently without contending for the [Seam.incoming] channel.
     *
     * Capacity 256 absorbs burst traffic before detectors are scheduled. Subscribers
     * that join after a frame is emitted will miss that frame; for heartbeat
     * liveness this is acceptable — the next heartbeat cycle catches up.
     */
    private val rawIncoming = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)

    private var loopJobs: List<Job> = emptyList()

    // Per-admitted-peer detector collection jobs, keyed by PeerId.
    private val detectorJobs = mutableMapOf<PeerId, Job>()

    private var closed = false
    private var hostLost = false

    /**
     * **Joiner only.** The child job running the current [seam]-`incoming` collect.
     *
     * After a re-weave heals [seam] onto a fresh generation, the old collect is still bound to
     * the dead generation (`ResumableChannel.incoming` binds `current()` once at collection start),
     * so it must be cancelled and relaunched or the host's `ResumeAck` is never delivered. Tracked
     * here so the [JoinerResumeMachine]'s reconnect (via [restartIncomingCollect]) can restart it
     * and [leave] can cancel it.
     */
    private var incomingCollectJob: Job? = null

    /**
     * **Joiner only.** Completed once this joiner is admitted (its own self-admission
     * [AdmitMessage.Welcome] arrives). The admit-deadline watcher awaits it: completion
     * means "admitted in time" and disarms the timeout; a completion that never comes
     * within [admitTimeout] fires [MembershipEvent.AdmissionFailed] ([AdmissionFailure.TimedOut]).
     * Never completed on a host (hosts don't wait to be admitted).
     */
    private val admitted = CompletableDeferred<Unit>()

    /**
     * **Joiner only.** Latches once the admit handshake has failed terminally
     * ([MembershipEvent.AdmissionFailed] emitted), so the two failure paths — a `Reject`
     * during initial join and the admit-deadline elapsing — emit exactly one event. Guarded by [lock].
     */
    private var admissionFailed = false

    /**
     * **Joiner only.** The child job running the admit-deadline watcher ([watchAdmitDeadline]),
     * or null on a host / after teardown. Tracked so [leave] cancels it. The watcher itself
     * hands the actual failure off to a *detached* coroutine so [leave]'s cancellation of this
     * job can never cancel [failAdmission] mid-teardown (same discipline as the
     * [JoinerResumeMachine]'s reconnect job).
     */
    private var admitDeadlineJob: Job? = null

    /**
     * The host's [PeerId] as seen from a [SessionRole.Joiner].
     *
     * Identified when the joiner receives a [AdmitMessage.Welcome] whose
     * [AdmitMessage.Welcome.assignedPeerId] matches the swatch sender's PeerId —
     * the host's self-introduction. Null for hosts (hosts don't watch themselves).
     */
    private var hostPeerId: PeerId? = null

    // ── Reconnect / resume state ───────────────────────────────────────────────

    /**
     * **Host only.** Manages per-joiner reconnect windows.
     *
     * Null when this room's [role] is [SessionRole.Joiner] — the host doesn't
     * reconnect to itself, and the joiner doesn't manage windows for others.
     *
     * Constructed lazily at room start so the scope and clock are guaranteed ready.
     */
    private val reconnectController: JoinerReconnectController? =
        if (role == SessionRole.Host && roomId != null) {
            // Caller-supplied hold policy (#1614) if injected; else the standard fixed-window default.
            reconnectControllerFactory?.invoke(roomId, scope, clock)
                ?: DefaultJoinerReconnectController(
                    roomId = roomId,
                    // Honor the configured window rather than the controller's 60 s default, so the
                    // host-side window matches the joiner-side window (the JoinerResumeMachine's
                    // reconnect also budgets on heartbeatConfig.reconnectWindow) — symmetric by
                    // construction.
                    reconnectWindowMs = heartbeatConfig.reconnectWindow.inWholeMilliseconds,
                    clock = { clock().toEpochMilliseconds() },
                    scope = scope,
                )
        } else {
            null
        }

    /**
     * **Joiner only.** The reconnect/resume state machine (#1122): owns the [ResumeToken]
     * minted at admit time, the single-flight resume slot (#1280), the reconnect guard, and
     * the auto-reconnect loop (#1037). Null when this room's [role] is [SessionRole.Host] —
     * the host doesn't resume against itself; its side of the protocol is
     * [reconnectController].
     *
     * The machine shares this room's [lock] (see the [lock] KDoc) and drives room-side
     * effects — detector silence/restore, incoming-collect restart, membership events,
     * host-lost teardown — through the [JoinerResumeHost] callbacks below, each of which
     * takes the (reentrant) lock itself.
     */
    private val resumeMachine: JoinerResumeMachine? =
        if (role == SessionRole.Joiner) {
            JoinerResumeMachine(
                seam = seam,
                scope = scope,
                clock = clock,
                heartbeatConfig = heartbeatConfig,
                reweave = reweave,
                lock = lock,
                host = object : JoinerResumeHost {
                    override fun hostPeer(): PeerId? = lock.withLock { hostPeerId }

                    override fun isTerminal(): Boolean = lock.withLock { closed || hostLost }

                    override fun isClosed(): Boolean = lock.withLock { closed }

                    override fun silenceHostDetector(hostId: PeerId) {
                        lock.withLock { stopDetector(hostId) }
                    }

                    override fun restoreHostDetector(hostId: PeerId) {
                        lock.withLock { admittedById[hostId]?.let { startDetector(it) } }
                    }

                    override fun restartIncomingCollect() =
                        this@SeamRoom.restartIncomingCollect()

                    override fun onReconnectStarted(hostId: PeerId, at: Instant, windowDeadline: Instant) {
                        _events.tryEmit(MembershipEvent.Partitioned(hostId, at, ReconnectReason.TransportClosed))
                        _events.tryEmit(MembershipEvent.WindowOpened(hostId, windowDeadline))
                    }

                    override suspend fun onReconnectFailed(at: Instant, reason: FailureReason) =
                        markHostLost(at, reason)
                },
            )
        } else {
            null
        }

    /**
     * **Joiner only.** The [ResumeToken] minted at admit time — held by [resumeMachine];
     * null on hosts and until the joiner receives its own [AdmitMessage.Welcome] carrying
     * a [RoomId] from the host.
     *
     * Publicly readable (implements [Room.resumeToken]) so the application layer and
     * the [us.tractat.kuilt.conformance.RoomConformanceSuite] TCK can access it without
     * module-internal visibility.
     */
    override val resumeToken: ResumeToken? get() = resumeMachine?.resumeToken

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    internal fun start() {
        val jobs = mutableListOf(
            scope.launch { runMainLoop() },
            scope.launch { runTornWatcher() },
            scope.launch { runDetectorRouteWatcher() },
        )
        if (reconnectController != null) {
            jobs += scope.launch { runReconnectEventLoop(reconnectController) }
        }
        loopJobs = jobs
    }

    // ── Torn watcher: react to permanent transport closure ────────────────────

    /**
     * Watches [Seam.state] for a [SeamState.Torn] transition and reacts **immediately**,
     * without waiting for heartbeat expiry — if the transport signals `Torn`, the session
     * layer trusts it directly.
     *
     * - **Host:** emits [MembershipEvent.Left] for each admitted peer (mirroring the
     *   heartbeat-based [LeaveReason.PartitionExpired] eviction path) and closes cleanly.
     * - **Joiner:** hands off to [JoinerResumeMachine.attemptReconnect], which either resumes
     *   over a re-woven base within the reconnect window or falls to terminal
     *   [MembershipEvent.HostLost].
     */
    private suspend fun runTornWatcher() {
        if (_role.value == SessionRole.Host) {
            seam.state.filterIsInstance<SeamState.Torn>().first()
            evictAllOnTear()
            leave(LeaveReason.Normal)
        } else {
            runJoinerTornWatcher()
        }
    }

    /**
     * **Joiner only.** On a transport `Torn`, trigger an in-window resume over a re-woven base
     * ([JoinerResumeMachine.attemptReconnect]) instead of going straight to terminal. Races the
     * heartbeat `TransportClosed` path in [handleUnresponsive]; the machine's reconnect guard
     * ensures only one wins.
     *
     * Single-shot — it fires only for the **first** tear. A subsequent in-session tear (after a
     * successful resume) is re-triggered by the host-liveness detector the machine restarts:
     * its `TransportClosed` funnels through [handleUnresponsive] into a fresh
     * [JoinerResumeMachine.attemptReconnect].
     */
    private suspend fun runJoinerTornWatcher() {
        seam.state.filterIsInstance<SeamState.Torn>().first()
        resumeMachine?.attemptReconnect(clock())
    }

    private fun evictAllOnTear() {
        val peerIds = lock.withLock { admittedById.keys.toList() }
        for (peerId in peerIds) {
            lock.withLock { stopDetector(peerId) }
            removeFromRoster(peerId, LeaveReason.Normal)
        }
    }

    // ── Reconnect event loop (host only) ─────────────────────────────────────

    /**
     * Collects [JoinerReconnectController] events and maps them to [MembershipEvent]s.
     *
     * - [JoinerReconnectEvent.WindowOpened] → [MembershipEvent.WindowOpened] (host events).
     * - [JoinerReconnectEvent.Resumed] → [MembershipEvent.Resumed] (host events; liveness reset).
     * - [JoinerReconnectEvent.WindowExpired] → no extra *local* event here; the
     *   [HeartbeatPartitionDetector] drives [PartitionEvent.PeerLost] which produces
     *   [MembershipEvent.Left] via [handlePeerLost]. It does, however, propagate an
     *   authoritative [AdmitMessage.Farewell] (`expired = true`) to the remaining members —
     *   see [propagateFarewell] (#1557).
     */
    private suspend fun runReconnectEventLoop(ctrl: JoinerReconnectController) {
        ctrl.events.collect { event ->
            when (event) {
                is JoinerReconnectEvent.WindowOpened ->
                    _events.tryEmit(
                        MembershipEvent.WindowOpened(
                            event.peerId,
                            Instant.fromEpochMilliseconds(event.expiresAt),
                        ),
                    )
                is JoinerReconnectEvent.Resumed ->
                    handleReconnectResumed(event.peerId)
                is JoinerReconnectEvent.WindowExpired -> {
                    // Locally, PeerLost from the detector handles the final eviction — no extra
                    // event here; Left(PartitionExpired) follows from PeerLost. Remotely, the
                    // expiry needs the same authoritative fan-out a clean leave gets, or a peer
                    // with no heartbeat edge against the expired member (a star topology's other
                    // joiners) never evicts it (#1557).
                    propagateFarewell(event.peerId, expired = true)
                }
            }
        }
    }

    /**
     * A joiner successfully resumed (host perspective). Reset their liveness and
     * send a [AdmitMessage.ResumeAck] to the joiner so the joiner's pending resume flight
     * ([JoinerResumeMachine]) resolves as [ResumeResult.Success].
     *
     * State mutation under lock; suspend send outside lock.
     */
    private suspend fun handleReconnectResumed(peerId: PeerId) {
        val updated = lock.withLock { updateMemberLiveness(peerId, Liveness.Connected) } ?: return
        _events.tryEmit(MembershipEvent.Resumed(updated.id))
        val ackBytes = AdmitMessage.encode(AdmitMessage.ResumeAck)
        runCatchingCancellable { seam.sendTo(peerId, ackBytes) }
    }

    // ── Main loop ──────────────────────────────────────────────────────────────

    /**
     * Sends [AdmitMessage.Hello] (joiner) once the fabric is [SeamState.Woven], then launches the
     * single [seam]-`incoming` collector via [restartIncomingCollect].
     *
     * The collect runs in its own child job ([incomingCollectJob]) rather than inline so a joiner
     * reconnect ([JoinerResumeMachine]) can cancel it and relaunch on the healed generation.
     */
    private suspend fun runMainLoop() {
        if (_role.value == SessionRole.Joiner) {
            // Wait for the fabric to reach Woven before sending Hello.
            // Some transports (MultipeerConnectivity, WebRTC) hand back a Seam
            // before the underlying connection is established. Broadcasting Hello
            // while the fabric is still Weaving reaches no one, leaving the admit
            // handshake permanently stuck. SeamState.Woven is the fabric-agnostic
            // signal that the link is live and the broadcast will be carried.
            seam.state.first { it is SeamState.Woven }
            sendHello()
            // Arm the admit deadline (#1178): a dropped/refused Hello must fail loudly, not hang.
            lock.withLock { admitDeadlineJob = scope.launch { watchAdmitDeadline() } }
        }
        restartIncomingCollect()
    }

    /**
     * **Joiner only.** Waits up to [admitTimeout] for admission ([admitted]); if it never
     * completes, fails the room loudly with [AdmissionFailure.TimedOut].
     *
     * The failure is handed to a **detached** `scope.launch` rather than run inline: [failAdmission]
     * calls [leave], and [leave] cancels [admitDeadlineJob] — the very coroutine this runs on — which
     * would otherwise cancel the teardown mid-flight (the reconnect-job discipline of
     * [JoinerResumeMachine]). Detaching makes the failure a sibling of this job, immune to that
     * cancellation.
     */
    private suspend fun watchAdmitDeadline() {
        val admittedInTime = withTimeoutOrNull(admitTimeout) { admitted.await() } != null
        if (!admittedInTime) {
            scope.launch { failAdmission(AdmissionFailure.TimedOut, clock()) }
        }
    }

    /**
     * **Joiner only.** Terminally fail the admit handshake: emit [MembershipEvent.AdmissionFailed]
     * (once) and tear the room down via [leave]. No-op if the room already went terminal or the
     * joiner was admitted after all (a late [admitted] completion or a stray post-admission `Reject`).
     *
     * Always invoked on a detached [scope] coroutine (see [watchAdmitDeadline] and the `Reject`
     * handler), so [leave] cancelling [admitDeadlineJob] never cancels this call.
     */
    private suspend fun failAdmission(reason: AdmissionFailure, at: Instant) {
        lock.withLock {
            if (closed || hostLost || admissionFailed || admitted.isCompleted) return
            admissionFailed = true
        }
        _events.tryEmit(MembershipEvent.AdmissionFailed(reason, at))
        leave(LeaveReason.Error("admission failed: $reason"))
    }

    /**
     * (Re)starts the single collector of [seam]-`incoming`, fanning each swatch to [rawIncoming]
     * (for per-peer detectors) and routing it through the admit protocol / to [incoming].
     *
     * Cancels any prior collector first. After a re-weave the previous collector is bound to the
     * dead generation (see [incomingCollectJob]); restarting binds a fresh collector to the healed
     * generation, so the host's `ResumeAck` — buffered in the new channel's spool — is delivered.
     * The flip is under [lock]; the coroutine body's suspend work runs on the launched child.
     */
    private fun restartIncomingCollect() {
        lock.withLock {
            incomingCollectJob?.cancel()
            incomingCollectJob = scope.launch {
                seam.incoming.collect { swatch ->
                    rawIncoming.emit(swatch)
                    dispatchIncoming(swatch)
                }
            }
        }
    }

    private fun dispatchIncoming(swatch: Swatch) {
        val sender = swatch.sender ?: return
        val bytes = swatch.toByteArray()
        when {
            HeartbeatPartitionDetector.isHeartbeatFrame(bytes) -> {
                // Heartbeat frames are consumed by per-peer detectors via rawIncoming.
                // No further action needed here — the detector's incomingJob handles them.
            }
            AdmitMessage.isAdmitFrame(bytes) -> handleAdmitFrame(sender, bytes)
            RoomChannel.isChannelFrame(bytes) -> {
                // Channel frames are routed to [RoomChannelSeam] subscribers via rawIncoming.
                // Admit gating is applied per-subscriber in [RoomChannelSeam.incoming].
                // No additional routing needed here.
            }
            LobbyMessage.isLobbyFrame(bytes) -> {
                // A freeze-round tail frame that crossed the adopt boundary (#1439): on a mesh with no
                // total delivery order, a peer's broadcast FreezeAck (or a late Reopen) can arrive after
                // this peer received Commit and adopted. Drop it — without this it would fall through to
                // [routeApplicationFrame] and surface as a bogus application [RoomFrame]. The lobby's own
                // collector was cancel-and-joined before adopt, so nothing else consumes it.
            }
            isAdmittedPeer(sender) -> routeApplicationFrame(sender, bytes)
            else -> { /* drop: application frame from unadmitted peer */ }
        }
    }

    // ── Admit protocol ────────────────────────────────────────────────────────

    private fun handleAdmitFrame(sender: PeerId, bytes: ByteArray) {
        when (val msg = AdmitMessage.decode(bytes)) {
            is AdmitMessage.Hello -> {
                if (_role.value == SessionRole.Host) {
                    val target = msg.targetRoom
                    // Protocol-version gate (#1569). A joiner declaring a version outside this
                    // build's supported range is refused at admit time with a terminal
                    // ProtocolMismatch — better than completing the handshake and failing later on
                    // a frame neither side can decode. A version-less Hello (a peer predating the
                    // field) is legacy and stays permissive: ProtocolVersion.isSupported(null) is
                    // true, so older peers are never locked out.
                    if (!ProtocolVersion.isSupported(msg.protocolVersion)) {
                        logger.debug {
                            "Rejecting Hello from $sender: protocol-mismatch (${msg.protocolVersion})"
                        }
                        scope.launch {
                            val rejectBytes = AdmitMessage.encode(
                                AdmitMessage.Reject(
                                    "protocol-mismatch: ${msg.protocolVersion} not in " +
                                        "${ProtocolVersion.MIN_SUPPORTED}..${ProtocolVersion.MAX_SUPPORTED}",
                                    RejectCode.ProtocolMismatch,
                                ),
                            )
                            runCatchingCancellable { seam.sendTo(sender, rejectBytes) }
                        }
                    } else if (target != null && roomKey != null && target != roomKey) {
                        // Room-bound admission gate (A2, #1172). Reject only a *positive*
                        // mismatch — both sides name a room and they differ. A null target
                        // (transport already bound the room) or a host without a declared
                        // room key stays permissive, preserving existing single-room fabrics.
                        logger.debug {
                            "Rejecting Hello from $sender: room-mismatch ($target != $roomKey)"
                        }
                        scope.launch {
                            val rejectBytes = AdmitMessage.encode(
                                AdmitMessage.Reject(
                                    "room-mismatch: $target != $roomKey",
                                    RejectCode.RoomMismatch,
                                ),
                            )
                            runCatchingCancellable { seam.sendTo(sender, rejectBytes) }
                        }
                    } else {
                        scope.launch { admitPeer(sender, msg) }
                    }
                }
            }
            is AdmitMessage.Welcome -> {
                if (_role.value == SessionRole.Joiner) {
                    handleWelcome(sender, msg)
                }
            }
            is AdmitMessage.Resume -> {
                if (_role.value == SessionRole.Host) {
                    scope.launch { handleResume(sender, msg) }
                }
            }
            is AdmitMessage.ResumeAck -> {
                if (_role.value == SessionRole.Joiner) {
                    handleResumeAck(sender)
                }
            }
            is AdmitMessage.Goodbye -> {
                if (_role.value == SessionRole.Host) {
                    lock.withLock { stopDetector(sender) }
                    removeFromRoster(sender, LeaveReason.Normal)
                    propagateFarewell(sender)
                }
            }
            is AdmitMessage.Farewell -> {
                if (_role.value == SessionRole.Joiner) {
                    handleFarewell(sender, msg)
                }
            }
            is AdmitMessage.Paused -> {
                if (_role.value == SessionRole.Joiner) {
                    handlePaused(sender, msg)
                }
            }
            is AdmitMessage.Unpaused -> {
                if (_role.value == SessionRole.Joiner) {
                    handleUnpaused(sender, msg)
                }
            }
            is AdmitMessage.Reject -> {
                if (_role.value == SessionRole.Joiner) {
                    // A Reject means one of two things depending on where we are:
                    //  - resume in flight  → resolve the parked resume as WindowClosed (existing behavior);
                    //  - initial join      → the host refused admission; fail loudly (#1178) instead of
                    //                         swallowing it and leaving join()'s consumer hanging.
                    val hadPendingResume = resumeMachine?.rejectFlight(msg.reason, msg.code) ?: false
                    if (!hadPendingResume) {
                        scope.launch {
                            failAdmission(AdmissionFailure.Rejected(msg.reason, msg.code), clock())
                        }
                    }
                }
            }
            null -> { /* malformed frame — ignore */ }
        }
    }

    /**
     * Host-side: admit a peer that sent [AdmitMessage.Hello].
     *
     * Steps:
     * 1. Add peer to roster (under lock).
     * 2. Send [AdmitMessage.Welcome] back to the joiner with their [PeerId].
     * 3. Broadcast the welcome to all other admitted members (roster sync).
     * 4. Send each already-known member's welcome to the new joiner (bootstrap their view).
     * 5. Send self-introduction (host identity) to the new joiner.
     *
     * State mutation is under lock; all seam sends happen outside the lock.
     */
    private suspend fun admitPeer(joinerPeerId: PeerId, hello: AdmitMessage.Hello) {
        val identity = MemberIdentity(
            displayName = hello.displayName,
            sessionId = hello.sessionId,
            deviceId = hello.deviceId,
        )
        val member = Member(
            id = joinerPeerId,
            identity = identity,
            liveness = Liveness.Connected,
            // Prefer the map-keyed roster (hub path: RoomHubSeam / Mesh keys a principal per peer),
            // falling back to the single-value marker (2-peer relay path: Seam.withPrincipal).
            principal = (seam as? PrincipalRoster)?.attestedPrincipals?.value?.get(joinerPeerId)
                ?: (seam as? PrincipalAttested)?.principal,
        )

        // Snapshot current members and mutate roster under lock; no I/O inside.
        val existingMembers = lock.withLock {
            val existing = admittedById.values.toList()
            addToRoster(member)
            existing
        }

        val welcome = AdmitMessage.Welcome(
            assignedPeerId = joinerPeerId.value,
            displayName = hello.displayName,
            sessionId = hello.sessionId,
            deviceId = hello.deviceId,
            roomId = roomId?.value,
        )
        val welcomeBytes = AdmitMessage.encode(welcome)

        // All sends below are outside the lock — they are suspend calls.

        // Send welcome directly to the joiner
        runCatchingCancellable { seam.sendTo(joinerPeerId, welcomeBytes) }

        // Broadcast welcome to all other admitted members (roster sync)
        for (existing in existingMembers) {
            runCatchingCancellable { seam.sendTo(existing.id, welcomeBytes) }
        }

        // Bootstrap the joiner's view: send welcomes for all pre-existing members
        for (existing in existingMembers) {
            val existingWelcome = AdmitMessage.encode(
                AdmitMessage.Welcome(
                    assignedPeerId = existing.id.value,
                    displayName = existing.identity.displayName,
                    sessionId = existing.identity.sessionId,
                    deviceId = existing.identity.deviceId,
                ),
            )
            runCatchingCancellable { seam.sendTo(joinerPeerId, existingWelcome) }
        }

        // Send self-introduction (host introduces itself to the new joiner)
        val hostIntro = AdmitMessage.encode(
            AdmitMessage.Welcome(
                assignedPeerId = selfId.value,
                displayName = resolvedMemberName,
                sessionId = selfId.value,
            ),
        )
        runCatchingCancellable { seam.sendTo(joinerPeerId, hostIntro) }
    }

    private suspend fun sendHello() {
        val hello = AdmitMessage.Hello(
            displayName = resolvedMemberName,
            sessionId = selfId.value,
            targetRoom = roomKey,
            protocolVersion = ProtocolVersion.CURRENT,
        )
        runCatchingCancellable { seam.broadcast(AdmitMessage.encode(hello)) }
    }

    /**
     * Host-side handler for [AdmitMessage.Resume].
     *
     * Validates the token against the [reconnectController]. On [ResumeResult.Success],
     * [handleReconnectResumed] sends a [AdmitMessage.Welcome] confirmation to the joiner
     * via the reconnect controller's event stream. On failure, replies with [AdmitMessage.Reject]
     * carrying both a human-readable reason and a structured [RejectCode], so the joiner can
     * surface it as `FailureReason.Refused(message, code)` — and, when the code is terminal, stop
     * retrying immediately instead of waiting out its window (#1572).
     */
    private suspend fun handleResume(sender: PeerId, msg: AdmitMessage.Resume) {
        val ctrl = reconnectController ?: return
        val token = ResumeToken(
            peerId = PeerId(msg.tokenPeerId),
            roomId = RoomId(msg.tokenRoomId),
            issuedAt = msg.issuedAt,
        )
        val reject = when (val result = ctrl.tryResume(token, at = clock().toEpochMilliseconds())) {
            // handleReconnectResumed fires via the controller event stream.
            ResumeResult.Success -> null
            // Transient: the host's own detector has not fired yet (fast-reconnect race). The
            // joiner must keep retrying — coding this terminal would break that recovery.
            ResumeResult.WindowNotYetOpen ->
                AdmitMessage.Reject("resume-window-not-yet-open", RejectCode.ResumeWindowNotYetOpen)
            ResumeResult.WindowClosed ->
                AdmitMessage.Reject("resume-window-expired", RejectCode.ResumeWindowExpired)
            is ResumeResult.TokenInvalid ->
                AdmitMessage.Reject("resume-token-invalid: ${result.reason}", RejectCode.ResumeTokenInvalid)
            // Host-side validation is synchronous and always renders a verdict; TimedOut is the
            // joiner's await-side outcome only (#1587) and cannot originate here.
            ResumeResult.TimedOut ->
                error("tryResume must not return TimedOut: it is a joiner await outcome, not a host verdict")
        }
        if (reject != null) {
            runCatchingCancellable { seam.sendTo(sender, AdmitMessage.encode(reject)) }
        }
        // On Success: handleReconnectResumed fires via the controller's event stream
        // (runReconnectEventLoop collects JoinerReconnectEvent.Resumed and calls it).
    }

    /**
     * Joiner-side: handle a [AdmitMessage.Welcome].
     *
     * The host sends Welcome both for the joiner themselves (confirming their own admission)
     * and for each existing member (bootstrapping the joiner's roster view).
     * Either way, add the described peer to our roster if not already there.
     *
     * If [AdmitMessage.Welcome.assignedPeerId] matches [sender]'s value, this is the host's
     * self-introduction — record [sender] as the host peer for [HostLost] detection.
     *
     * If [welcome.roomId] is set and [resumeToken] is not yet minted, mint it now using
     * [selfId] as the peer identifier and the received [RoomId].
     *
     * Note: the self-admission welcome (`assignedPeerId == selfId`) is used ONLY to mint
     * the resume token; it does not add self to the roster. Resume confirmations arrive as
     * [AdmitMessage.ResumeAck], not as Welcome.
     */
    private fun handleWelcome(sender: PeerId, welcome: AdmitMessage.Welcome) {
        lock.withLock {
            val assignedId = PeerId(welcome.assignedPeerId)

            // Joiner-side cross-admit hardening (#1180): once we've identified our host, ignore
            // Welcomes from anyone else — a foreign host on a flat loom must not pollute our roster
            // or hijack hostPeerId. The host sends *every* legitimate Welcome (self-admission,
            // bootstrap, host-intro), so they all share the host's sender; a differing sender is
            // foreign. (Cannot gate on Welcome.roomId — bootstrap/host-intro Welcomes carry null.)
            // Complements the #1172 Change A host-side gate that stops foreign Welcomes at the source.
            val establishedHost = hostPeerId
            if (establishedHost != null && sender != establishedHost) return@withLock

            // Self-admission welcome: mint the resume token (once) from the roomId carried here.
            if (assignedId == selfId) {
                resumeMachine?.mintTokenIfAbsent(welcome.roomId)
                // The host explicitly admitted us — disarm the admit deadline (#1178).
                admitted.complete(Unit)
                return@withLock
            }

            // Host self-intro: the described peer IS the sender.
            if (assignedId == sender && hostPeerId == null) {
                hostPeerId = sender
            }

            // Also mint resume token from host intro welcome if not yet minted.
            resumeMachine?.mintTokenIfAbsent(welcome.roomId)

            if (admittedById.containsKey(assignedId)) return@withLock // already known
            val identity = MemberIdentity(
                displayName = welcome.displayName,
                sessionId = welcome.sessionId,
                deviceId = welcome.deviceId,
            )
            val member = Member(id = assignedId, identity = identity, liveness = Liveness.Connected)
            addToRoster(member)
        }
    }

    /**
     * Joiner-side: host confirmed our [AdmitMessage.Resume] was accepted.
     *
     * The host's [JoinerReconnectController] validated the token and the reconnect
     * window was still open. Update liveness, emit [MembershipEvent.Resumed], and
     * resolve the pending resume flight ([JoinerResumeMachine.takePendingFlight]) so
     * [resume] returns [ResumeResult.Success].
     *
     * **Host-authoritative gate + idempotence latch:** mirroring [handleFarewell],
     * only a ResumeAck from the identified host ([hostPeerId]) is honored — a forged
     * ack from another admitted peer, or one arriving before the host is identified,
     * is dropped. The pending resume flight is the idempotence latch: a genuine ack
     * takes it and fires exactly one [MembershipEvent.Resumed]; a duplicate or stray
     * host ack finds no pending flight ([JoinerResumeMachine.takePendingFlight]
     * returns `null`) and emits nothing.
     */
    private fun handleResumeAck(sender: PeerId) {
        val deferred = lock.withLock {
            val host = hostPeerId
            if (host == null || sender != host) return
            val flight = resumeMachine?.takePendingFlight() ?: return
            updateMemberLiveness(sender, Liveness.Connected)
            flight
        }
        _events.tryEmit(MembershipEvent.Resumed(selfId))
        deferred.complete(ResumeResult.Success)
    }

    /**
     * Host-side: propagate an authoritative [AdmitMessage.Farewell] for [departed] to every
     * remaining member, so joiners evict the departed peer promptly instead of waiting out
     * their own heartbeat window (#1292).
     *
     * [expired] selects the eviction reason members apply: `false` for a clean
     * [AdmitMessage.Goodbye] ([LeaveReason.Normal] — the #1292 case, which otherwise gets
     * mislabelled as a partition), `true` for a [JoinerReconnectEvent.WindowExpired]
     * ([LeaveReason.PartitionExpired] — the #1557 case, which otherwise never arrives at all
     * on a topology where members have no heartbeat edge to each other).
     *
     * The eviction counterpart of the [AdmitMessage.Welcome] roster-sync broadcast in
     * [admitPeer]: the host is the membership authority for joins *and* leaves. Best-effort —
     * a lost Farewell degrades to that member's heartbeat-window eviction *where such a window
     * exists* (a mesh; on a star it does not, which is why the expiry fan-out matters).
     * Roster snapshot under [lock]; the suspend sends run on a launched child, outside the lock.
     */
    private fun propagateFarewell(departed: PeerId, expired: Boolean = false) {
        val remaining = lock.withLock { admittedById.keys.filter { it != departed } }
        if (remaining.isEmpty()) return
        val farewellBytes = AdmitMessage.encode(AdmitMessage.Farewell(departed.value, expired))
        scope.launch {
            for (peerId in remaining) {
                runCatchingCancellable { seam.sendTo(peerId, farewellBytes) }
            }
        }
    }

    /**
     * Joiner-side: handle an authoritative [AdmitMessage.Farewell] — the host's notification
     * that [farewell]'s peer departed. Stop that peer's liveness detector and remove it from
     * the roster with [LeaveReason.Normal] for a clean leave (#1292), or
     * [LeaveReason.PartitionExpired] when the host is propagating a reconnect-window expiry
     * ([AdmitMessage.Farewell.expired], #1557).
     *
     * **Host-authoritative gate:** only a Farewell from the identified host ([hostPeerId]) is
     * honored. Anything else — a forged Farewell from another joiner, or one arriving before
     * the host is identified — is dropped; the [HeartbeatPartitionDetector] fallback still
     * evicts genuinely-vanished peers. A Farewell naming the host itself is also ignored:
     * host departure is transport-level ([runJoinerTornWatcher] / host-liveness detection),
     * never a roster eviction.
     */
    private fun handleFarewell(sender: PeerId, farewell: AdmitMessage.Farewell) {
        val departed = PeerId(farewell.peerId)
        lock.withLock {
            val host = hostPeerId
            if (host == null || sender != host || departed == host) return
            stopDetector(departed)
        }
        removeFromRoster(
            departed,
            if (farewell.expired) LeaveReason.PartitionExpired else LeaveReason.Normal,
        )
    }

    // ── Partition detection ───────────────────────────────────────────────────

    /**
     * Watches [Seam.peers] and starts a detector for any admitted member that **gains** a route
     * (#1576).
     *
     * Roster admission and fabric connectivity are independent events: a member can be admitted
     * to the room before the fabric has finished registering it in [Seam.peers] (a late mesh link
     * forming, a re-woven base repopulating). Evaluating the route gate only at admit time would
     * silently forfeit partition detection in those cases, so the gate is re-evaluated on every
     * [Seam.peers] emission. [startDetector] is idempotent, so a member that already has one is
     * untouched.
     *
     * Deliberately start-only. A peer *disappearing* from [Seam.peers] is the definitive
     * transport close that [HeartbeatPartitionDetector] reports as
     * [PartitionEvent.Reason.TransportClosed] — tearing its detector down here would suppress the
     * very eviction the detector exists for.
     *
     * [Seam.peers] is a [StateFlow], so collecting it here does not contend with the
     * single-collection [Seam.incoming] contract (ADR-034) held by [runMainLoop].
     */
    private suspend fun runDetectorRouteWatcher() {
        seam.peers.collect { reachable ->
            lock.withLock {
                if (closed) return@withLock
                for (member in admittedById.values.toList()) {
                    if (member.id in reachable) startDetector(member)
                }
            }
        }
    }

    /**
     * Launches a [HeartbeatPartitionDetector] for [member].
     *
     * The detector is given a [PerPeerSeam] — a thin adapter that filters [rawIncoming]
     * to frames from [member.id] only. This lets the detector subscribe to per-peer
     * ping/pong traffic without competing for the single-consumer [Seam.incoming] channel
     * that the main loop already holds.
     *
     * A separate coroutine collects the detector's events and maps them to [MembershipEvent]s.
     *
     * All of the detector's coroutines — its heartbeat loop, its inbound collector (which
     * subscribes to the never-completing [rawIncoming]), and our event collector — are owned
     * by one child [Job] stored in [detectorJobs]. Cancelling that job via [stopDetector]
     * (or [leave]) tears the whole detector down; without this single owner the heartbeat and
     * inbound coroutines would outlive the evicted member (#1001).
     *
     * ## Route gate (#1576)
     *
     * A detector is started **only for a peer this member can actually reach** — one present in
     * the seam's own [Seam.peers]. That set is exactly the directly-connected peers on every
     * shipped fabric (`docs/fabric-peer-routing.md`); [CompositeSeam] even recomputes it as
     * precisely the reachable set.
     *
     * On a host-relayed **star** (`RoomHubSeam`/`MuxServerLoom`, `MeshSeam` as a hub spoke,
     * `:kuilt-multipeer`, `:kuilt-nearby`) a joiner has no route to a co-joiner, so its pings
     * could never be answered — and the detector's *timeout* branch is not gated on the peer
     * being in `link.peers` (only its `TransportClosed` branch is). Silence from an unroutable
     * peer therefore matured into [PartitionEvent.PeerLost] and evicted a healthy member. That
     * peer's presence is instead derived from the host's authoritative fan-out
     * ([AdmitMessage.Paused] / [AdmitMessage.Unpaused] / [AdmitMessage.Farewell], #1557).
     *
     * The gate must not be keyed off catching [us.tractat.kuilt.core.PeerNotConnected]:
     * `TieredSeam.sendTo` silently *drops* a peer owned by neither tier, so an exception-keyed
     * check would miss it entirely.
     *
     * Seam membership is dynamic, so [runDetectorRouteWatcher] re-runs this for every admitted
     * member whenever [Seam.peers] grows. A peer *leaving* [Seam.peers] deliberately does **not**
     * stop its detector — that transition is precisely the definitive transport close the
     * detector exists to report.
     *
     * Idempotent: a member that already has a live detector is left alone, so a re-evaluation
     * (or [JoinerResumeHost.restoreHostDetector] racing the watcher) cannot orphan a detector's
     * coroutines.
     *
     * Callers must hold [lock] when invoking this method.
     */
    private fun startDetector(member: Member) {
        if (member.id in detectorJobs) return
        if (member.id !in seam.peers.value) return
        val perPeerSeam = PerPeerSeam(seam, member.id, rawIncoming)
        val detector = HeartbeatPartitionDetector(
            link = perPeerSeam,
            peerId = member.id,
            config = heartbeatConfig,
            clock = clock,
        )
        val detectorJob = Job(scope.coroutineContext[Job])
        val detectorScope = CoroutineScope(scope.coroutineContext + detectorJob)
        detector.start(detectorScope)
        detectorScope.launch {
            detector.events.collect { event -> handlePartitionEvent(event) }
        }
        detectorJobs[member.id] = detectorJob
    }

    /**
     * Cancels the per-peer detector — its heartbeat loop, inbound collector, and event
     * collector — for [peerId]. Callers must hold [lock].
     */
    private fun stopDetector(peerId: PeerId) {
        detectorJobs.remove(peerId)?.cancel()
    }

    /**
     * Test-visibility: is a live per-peer liveness detector currently registered for [peerId]?
     *
     * Exposed for [us.tractat.kuilt.session] tests that assert the host-liveness detector is
     * **silenced for the duration of a reconnect** (so it can't race an in-flight resume) and
     * **restarted on success** ([JoinerResumeMachine], #1037). No production caller reads this.
     */
    internal fun hasDetector(peerId: PeerId): Boolean = lock.withLock { detectorJobs.containsKey(peerId) }

    /**
     * Test-visibility: is a [resume] attempt currently in flight (its reply slot installed)?
     *
     * Exposed for [us.tractat.kuilt.session] tests that interleave a public [resume] with the
     * internal auto-reconnect's own resume (#1280). No production caller reads this.
     */
    internal fun hasPendingResume(): Boolean = resumeMachine?.hasPendingFlight() ?: false

    private suspend fun handlePartitionEvent(event: PartitionEvent) {
        when (event) {
            is PartitionEvent.PeerUnresponsive -> handleUnresponsive(event)
            is PartitionEvent.PeerRecovered -> markRecovered(event.peerId, event.at)
            is PartitionEvent.PeerLost -> handlePeerLost(event.peerId, event.at)
        }
    }

    /**
     * Maps a [PartitionEvent.PeerUnresponsive] to a membership event by role + reason.
     *
     * A joiner whose **host** link is lost to a definitive transport close attempts an in-window
     * resume via [JoinerResumeMachine.attemptReconnect] (#1037) — re-weaving the base and
     * presenting the [resumeToken] rather than immediately going terminal. This path races the
     * transport-`Torn` watcher ([runJoinerTornWatcher]); the machine's reconnect guard funnels
     * both into one attempt. Every other case (host watching a joiner; a joiner's non-host peer;
     * a silent [PartitionEvent.Reason.Timeout] partition that may still recover) opens the
     * reconnect window via [markPartitioned].
     */
    private suspend fun handleUnresponsive(event: PartitionEvent.PeerUnresponsive) {
        val hostTransportClose = lock.withLock {
            _role.value == SessionRole.Joiner && event.peerId == hostPeerId
        } && event.reason == PartitionEvent.Reason.TransportClosed
        if (hostTransportClose) {
            resumeMachine?.attemptReconnect(event.at)
        } else {
            markPartitioned(event.peerId, event.at, event.reason.toReconnectReason())
        }
    }

    /**
     * Apply [Liveness.Partitioned] to [peerId] and announce it, with [reason] saying why the
     * link dropped (#1556).
     *
     * **Idempotent** — a member already partitioned re-arms its reconnect window but emits no
     * second [MembershipEvent.Partitioned] and re-announces nothing. That matters on a mesh,
     * where a peer both detects the drop locally *and* receives the host's
     * [AdmitMessage.Paused] for it (#1557): whichever arrives first wins, the other is a no-op.
     *
     * On the **host** the announcement is a [AdmitMessage.Paused] fan-out to the remaining
     * members — see [propagatePaused] for why local detection alone is not enough.
     */
    private fun markPartitioned(peerId: PeerId, at: Instant, reason: ReconnectReason) {
        val (wasPartitioned, updated) = lock.withLock {
            val current = admittedById[peerId] ?: return
            val wasPartitioned = current.liveness == Liveness.Partitioned
            wasPartitioned to (updateMemberLiveness(peerId, Liveness.Partitioned) ?: return)
        }
        if (!wasPartitioned) _events.tryEmit(MembershipEvent.Partitioned(updated.id, at, reason))
        reconnectController?.onPeerUnresponsive(peerId, at.toEpochMilliseconds())
        if (!wasPartitioned && _role.value == SessionRole.Host) {
            propagatePaused(
                peerId,
                expiresAtMs = at.toEpochMilliseconds() + heartbeatConfig.reconnectWindow.inWholeMilliseconds,
            )
        }
    }

    /**
     * Restore [Liveness.Connected] for [peerId] and announce it. Idempotent counterpart of
     * [markPartitioned]: a member that was not paused emits nothing and announces nothing.
     */
    private fun markRecovered(peerId: PeerId, at: Instant) {
        val (wasPartitioned, updated) = lock.withLock {
            val current = admittedById[peerId] ?: return
            val wasPartitioned = current.liveness == Liveness.Partitioned
            wasPartitioned to (updateMemberLiveness(peerId, Liveness.Connected) ?: return)
        }
        if (!wasPartitioned) return
        _events.tryEmit(MembershipEvent.Recovered(updated.id, at))
        if (_role.value == SessionRole.Host) propagateUnpaused(peerId)
    }

    /**
     * Host-side: announce that [peerId]'s seat is held open until [expiresAtMs], by fanning an
     * authoritative [AdmitMessage.Paused] to every *other* member (#1557).
     *
     * Liveness is detected locally, per peer. On a mesh that suffices — every member watches
     * every other. On a star/host-relayed topology a member has no heartbeat edge to another
     * member, so the pause would be invisible to it and [MembershipEvent.Partitioned] would
     * silently mean different things on different topologies. Best-effort, like
     * [propagateFarewell]; roster snapshot under [lock], sends on a launched child.
     */
    private fun propagatePaused(peerId: PeerId, expiresAtMs: Long) {
        fanOutToOtherMembers(peerId, AdmitMessage.Paused(peerId.value, expiresAtMs))
    }

    /** Host-side release counterpart of [propagatePaused] — [peerId] recovered in-window. */
    private fun propagateUnpaused(peerId: PeerId) {
        fanOutToOtherMembers(peerId, AdmitMessage.Unpaused(peerId.value))
    }

    /** Encode [message] once and send it to every admitted member except [subject]. */
    private fun fanOutToOtherMembers(subject: PeerId, message: AdmitMessage) {
        val recipients = lock.withLock { admittedById.keys.filter { it != subject } }
        if (recipients.isEmpty()) return
        val bytes = AdmitMessage.encode(message)
        scope.launch {
            for (recipient in recipients) {
                runCatchingCancellable { seam.sendTo(recipient, bytes) }
            }
        }
    }

    /**
     * Member-side: the host says [paused]'s peer dropped its link and its seat is held open.
     * Apply [Liveness.Partitioned] and emit the same [MembershipEvent.Partitioned] +
     * [MembershipEvent.WindowOpened] pair a locally-detecting peer emits (#1557).
     *
     * **Host-authoritative gate**, identical to [handleFarewell]'s: only a [AdmitMessage.Paused]
     * from the identified host is honored — a forgery from another joiner, or one arriving
     * before the host is identified, is dropped. A Paused naming the host itself is ignored too;
     * host liveness is the [JoinerResumeMachine]'s business, not a roster pause.
     *
     * **Idempotent**: a no-op when the member is already partitioned, so a mesh peer that
     * detected the drop locally does not emit a second event on receiving this.
     */
    private fun handlePaused(sender: PeerId, paused: AdmitMessage.Paused) {
        val subject = PeerId(paused.peerId)
        val updated = lock.withLock {
            val host = hostPeerId
            if (host == null || sender != host || subject == host || subject == selfId) return
            val current = admittedById[subject] ?: return
            if (current.liveness == Liveness.Partitioned) return
            updateMemberLiveness(subject, Liveness.Partitioned) ?: return
        }
        // The host told us the link dropped; TransportClosed is the honest reason here — we
        // observed no timeout or backpressure ourselves, only the authoritative Paused (#1556).
        _events.tryEmit(MembershipEvent.Partitioned(updated.id, clock(), ReconnectReason.TransportClosed))
        _events.tryEmit(
            MembershipEvent.WindowOpened(updated.id, Instant.fromEpochMilliseconds(paused.expiresAt)),
        )
    }

    /**
     * Member-side release counterpart of [handlePaused]: the host says [unpaused]'s peer
     * recovered inside its window. Same host-authoritative gate; idempotent (a no-op when the
     * member is not currently paused).
     */
    private fun handleUnpaused(sender: PeerId, unpaused: AdmitMessage.Unpaused) {
        val subject = PeerId(unpaused.peerId)
        val updated = lock.withLock {
            val host = hostPeerId
            if (host == null || sender != host || subject == host || subject == selfId) return
            val current = admittedById[subject] ?: return
            if (current.liveness != Liveness.Partitioned) return
            updateMemberLiveness(subject, Liveness.Connected) ?: return
        }
        _events.tryEmit(MembershipEvent.Recovered(updated.id, clock()))
    }

    private suspend fun handlePeerLost(peerId: PeerId, at: Instant) {
        val isHostPeer = lock.withLock {
            stopDetector(peerId)
            _role.value == SessionRole.Joiner && peerId == hostPeerId
        }
        if (isHostPeer) {
            markHostLost(at, FailureReason.WindowExpired)
        } else {
            removeFromRoster(peerId, LeaveReason.PartitionExpired)
        }
    }

    private suspend fun markHostLost(at: Instant, reason: FailureReason) {
        val alreadyLost = lock.withLock {
            val was = hostLost
            hostLost = true
            was
        }
        if (alreadyLost) return
        _events.tryEmit(MembershipEvent.HostLost(at, reason))
        leave(LeaveReason.Error("host lost"))
    }

    /**
     * Updates the in-memory [Member] for [peerId] to reflect [liveness].
     *
     * Returns the updated [Member], or null if [peerId] is not an admitted member.
     * Callers must hold [lock] when invoking this method.
     */
    private fun updateMemberLiveness(peerId: PeerId, liveness: Liveness): Member? {
        val current = admittedById[peerId] ?: return null
        val updated = current.copy(liveness = liveness)
        admittedById[peerId] = updated
        _roster.update { current -> current.map { if (it.id == peerId) updated else it }.toSet() }
        return updated
    }

    // ── Roster management ────────────────────────────────────────────────────

    /**
     * Adds (or refreshes) [member] in [admittedById], [_roster], and [_rosterPeers].
     * Callers must hold [lock].
     *
     * Idempotent re-admit: when [member]'s id is already admitted (e.g. a dropped
     * joiner reconnects mid-window and re-broadcasts [AdmitMessage.Hello]), this
     * refreshes the roster entry but does **not** re-emit [MembershipEvent.Joined]
     * or restart the detector — the existing per-peer detector is still alive and
     * recovers on its own via [PartitionEvent.PeerRecovered] when frames resume.
     * Restarting it would orphan the prior detector's coroutines (a leak).
     */
    private fun addToRoster(member: Member) {
        // Terminal-latch check, folded into the SAME lock critical section that mutates the roster.
        // Both callers ([admitPeer], [handleWelcome]) hold [lock] across this, and [leave] flips
        // `closed` under the same lock — so a `closed == true` here is authoritative. Without this,
        // an in-flight admit (host: `scope.launch { admitPeer }`, which [leave] does NOT cancel —
        // it is not the inbound-collect job) can re-register a peer AFTER the room went terminal,
        // resurrecting `_roster`/`_rosterPeers` and leaking a fresh detector (#1368; the
        // RoomHubSeam.deliver resurrection, #1364, one module over).
        if (closed) return
        val isReadmit = admittedById.containsKey(member.id)
        admittedById[member.id] = member
        _roster.update { current -> current.filterNot { it.id == member.id }.toSet() + member }
        _rosterPeers.update { current -> current + member.id }
        if (!isReadmit) {
            _events.tryEmit(MembershipEvent.Joined(member))
            startDetector(member)
        }
    }

    private fun removeFromRoster(peerId: PeerId, reason: LeaveReason) {
        val removed = lock.withLock { admittedById.remove(peerId) }
        removed ?: return // already removed, avoid duplicate Left events
        _roster.update { current -> current.filterNot { it.id == peerId }.toSet() }
        _rosterPeers.update { current -> current - peerId }
        _events.tryEmit(MembershipEvent.Left(peerId, reason))
    }

    private fun isAdmittedPeer(peerId: PeerId): Boolean = lock.withLock { admittedById.containsKey(peerId) }

    /**
     * Returns `true` if [peerId] is an admitted member.
     *
     * Used by [RoomChannelSeam] to filter incoming frames to admitted peers only.
     * Accepting a nullable [PeerId] matches [Swatch.sender], which is nullable.
     */
    internal fun isAdmitted(peerId: PeerId?): Boolean = peerId != null && lock.withLock { admittedById.containsKey(peerId) }

    // ── Application frame routing ─────────────────────────────────────────────

    private fun routeApplicationFrame(sender: PeerId, bytes: ByteArray) {
        _incoming.tryEmit(RoomFrame(sender = sender, payload = bytes))
    }

    // ── Room interface ────────────────────────────────────────────────────────

    /**
     * Broadcast [bytes] to all admitted members.
     *
     * Silent no-op when the room is terminal (after [MembershipEvent.HostLost] or [leave]).
     */
    override suspend fun broadcast(bytes: ByteArray) {
        val terminal = lock.withLock { hostLost || closed }
        if (terminal) return
        seam.broadcast(bytes)
    }

    /**
     * Send [bytes] to one specific admitted member.
     *
     * Silent no-op when the room is terminal (after [MembershipEvent.HostLost] or [leave]).
     */
    override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
        val terminal = lock.withLock { hostLost || closed }
        if (terminal) return
        seam.sendTo(peer, bytes)
    }

    /**
     * Returns a [Seam] view scoped to channel [id].
     *
     * The returned [RoomChannelSeam] sources its peer set from [rosterPeers] (admitted
     * roster + self) and its inbound stream from [rawIncoming] filtered to channel frames
     * with the sub-id derived from [id]. Idempotent: the same [Seam] instance is returned
     * for each distinct [id].
     */
    override fun channel(id: String): Seam {
        val subId = RoomChannel.channelSubId(id)
        return lock.withLock {
            channelViews.getOrPut(subId) {
                RoomChannelSeam(room = this, subId = subId, sharedRaw = rawIncoming)
            }
        }
    }

    /**
     * Attempt to resume this room from a [ResumeToken] after a transport drop.
     *
     * **Joiner only.** The host's [JoinerReconnectController] holds the reconnect window;
     * this method sends [AdmitMessage.Resume] to the host and awaits the reply:
     * - Host replies [AdmitMessage.ResumeAck] → [ResumeResult.Success]; [MembershipEvent.Resumed] fires.
     * - Host replies [AdmitMessage.Reject] → [ResumeResult.WindowClosed].
     *
     * **Not valid** after [MembershipEvent.HostLost] — the room is terminal at that point.
     * Callers should guard with [hostLost] before calling.
     *
     * **Not valid** on the host side — returns [ResumeResult.WindowClosed] immediately.
     *
     * **Concurrent calls coalesce (#1280)** — delegated to [JoinerResumeMachine.resume], which
     * owns the single-flight reply slot and the joining semantics; see its KDoc for the full
     * contract.
     */
    override suspend fun resume(token: ResumeToken): ResumeResult =
        resumeMachine?.resume(token) ?: ResumeResult.WindowClosed

    override suspend fun leave(reason: LeaveReason) {
        // Flip closed + snapshot jobs under lock; announce, cancel, and close outside.
        val plan = lock.withLock {
            if (closed) return
            closed = true
            Triple(
                _role.value == SessionRole.Joiner && reason is LeaveReason.Normal,
                loopJobs + listOfNotNull(
                    incomingCollectJob,
                    resumeMachine?.inFlightReconnectJob(),
                    admitDeadlineJob,
                ),
                detectorJobs.values.toList().also { detectorJobs.clear() },
            )
        }
        val (announce, jobsToCancel, detectorJobsToCancel) = plan
        // Resolve any in-flight resume as WindowClosed — the room is terminal, so its reply can
        // never arrive; without this, every caller awaiting the flight (#1280) hangs forever.
        // Taken after `closed` is set, so no new flight can install.
        resumeMachine?.takePendingFlight()?.complete(ResumeResult.WindowClosed)
        // Announce a graceful leave on the still-live seam before tearing it down, so the
        // host evicts with Normal rather than treating the close as a transport drop.
        if (announce) {
            runCatchingCancellable { seam.broadcast(AdmitMessage.encode(AdmitMessage.Goodbye)) }
        }
        jobsToCancel.forEach { it.cancel() }
        detectorJobsToCancel.forEach { it.cancel() }
        seam.close(
            when (reason) {
                is LeaveReason.Normal -> CloseReason.Normal
                is LeaveReason.Error -> CloseReason.Error(RuntimeException(reason.message))
                is LeaveReason.PartitionExpired -> CloseReason.Normal
            },
        )
    }
}

/**
 * A thin [Seam] view that presents only frames from [targetPeerId] via [rawIncoming].
 *
 * [HeartbeatPartitionDetector] subscribes to [incoming] to process pings/pongs for
 * a specific peer. Since [Seam.incoming] is a channel-backed flow (single-consumer),
 * we cannot let every detector collect it directly. Instead, [SeamRoom.runMainLoop]
 * fans each inbound swatch to [rawIncoming] (a [MutableSharedFlow]) and each
 * [PerPeerSeam] filters to its assigned [targetPeerId].
 *
 * [broadcast] and [sendTo] delegate to [delegate] unchanged.
 * [close] is a no-op — the [PerPeerSeam] does not own the link lifecycle.
 *
 * `internal` (not `private`) so [us.tractat.kuilt.session.election.SeamElectionLobby] can reuse the
 * one impl for its lobby-phase [HeartbeatPartitionDetector]s (#1480) rather than forking it.
 */
internal class PerPeerSeam(
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

    /** No-op — lifecycle is owned by [SeamRoom], not this view. */
    override suspend fun close(reason: CloseReason) = Unit
}
