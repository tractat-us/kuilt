package us.tractat.kuilt.session

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.time.Clock
import kotlin.time.Instant

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
 * @see SeamRoomFactory.systemClock for the production convenience constructor.
 */
public class SeamRoomFactory(
    private val loom: Loom,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
) : RoomFactory {
    override suspend fun host(pattern: Pattern): Room {
        val seam = loom.host(pattern)
        val roomId = RoomId(seam.selfId.value + "-room")
        return SeamRoom(
            seam = seam,
            role = SessionRole.Host,
            displayName = pattern.displayName,
            scope = scope,
            clock = clock,
            heartbeatConfig = heartbeatConfig,
            roomId = roomId,
        ).also { room -> room.start() }
    }

    override suspend fun join(tag: Tag): Room {
        val seam = loom.join(tag)
        return SeamRoom(
            seam = seam,
            role = SessionRole.Joiner,
            displayName = tag.displayName,
            scope = scope,
            clock = clock,
            heartbeatConfig = heartbeatConfig,
            roomId = null,
        ).also { room -> room.start() }
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
        ): SeamRoomFactory = SeamRoomFactory(
            loom = loom,
            scope = scope,
            clock = { Clock.System.now() },
            heartbeatConfig = heartbeatConfig,
        )
    }
}

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
 * 2. **Torn watcher** — observes [Seam.state] for a [SeamState.Torn] transition and emits the
 *    appropriate terminal membership event immediately, without waiting for heartbeat expiry.
 *
 * Additionally, a [HeartbeatPartitionDetector] is launched per admitted peer when the
 * admit handshake completes. The detector subscribes to [rawIncoming] (filtered by sender)
 * so it processes heartbeat ping/pong frames independently of the main loop.
 *
 * Partition event semantics by role:
 * - **Joiner**: [PartitionEvent.PeerLost] of the host → [MembershipEvent.HostLost] + terminal.
 *   [PartitionEvent.PeerLost] of a non-host → [MembershipEvent.Left(PartitionExpired)].
 * - **Host**: [PartitionEvent.PeerLost] of any joiner → [MembershipEvent.Left(PartitionExpired)].
 * - Both roles: [PartitionEvent.PeerUnresponsive] → [MembershipEvent.Partitioned];
 *   [PartitionEvent.PeerRecovered] → [MembershipEvent.Recovered].
 *
 * **Terminal state**: once [MembershipEvent.HostLost] fires, [broadcast] and [sendTo]
 * become silent no-ops. No auto-election is performed.
 *
 * **Thread safety**: all mutable membership state (`admittedById`, `closed`, `hostLost`,
 * `hostPeerId`, `pendingResume`, `resumeToken`, `detectorJobs`, `channelViews`) is
 * guarded by an atomicfu [reentrantLock]. Critical sections perform only synchronous
 * map/field operations; all suspend calls (sends, broadcasts) are made outside the lock.
 *
 * [start] must be called by [SeamRoomFactory] after construction to launch these loops.
 */
/**
 * Size of the [Room.events] replay cache (#692). Large enough to retain the startup-window
 * membership burst (the per-connection host room emits a single [MembershipEvent.Joined]; a
 * mesh room may admit several peers near-simultaneously) so a late subscriber can't miss it,
 * yet bounded so a long-lived room never accumulates unbounded history.
 */
private const val MEMBERSHIP_EVENT_REPLAY = 64

internal class SeamRoom(
    private val seam: Seam,
    role: SessionRole,
    private val displayName: String,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val heartbeatConfig: HeartbeatConfig,
    /**
     * Stable room identifier. Non-null for hosts (generated at room creation);
     * initially null for joiners (received from the host's [AdmitMessage.Welcome]).
     *
     * Defaults to null so existing tests that construct [SeamRoom] directly still compile.
     * [SeamRoomFactory] always passes the host-generated id explicitly.
     */
    private val roomId: RoomId? = null,
    /**
     * **Joiner only.** Re-weaves the underlying fabric after a transport tear, so the joiner
     * can attempt an in-window resume instead of going straight to terminal
     * [MembershipEvent.HostLost] (#1037).
     *
     * **Required Loom contract:** invoking this lambda must *heal the same [seam] instance* —
     * i.e. the [Loom] must return a stable, resumable handle whose [Seam.selfId] is frozen and
     * whose underlying channel is re-pointed onto a freshly-woven base. [MuxClientLoom]'s
     * `ResumableChannel` satisfies this: `loom.join(tag)` on a torn base re-weaves the base once
     * and returns the same handle. A [Loom] that mints a *new* seam per `join` does **not** satisfy
     * the contract — the re-weave would be invisible to this room, which keeps its original [seam]
     * reference, and the resume attempt would time out into [MembershipEvent.HostLost].
     *
     * Null (the default) for hosts and for joiners over non-resumable fabrics: a tear then goes
     * directly to [MembershipEvent.HostLost], the pre-#1037 behavior.
     */
    private val reweave: (suspend () -> Seam)? = null,
) : Room {
    override val selfId: PeerId = seam.selfId

    private val _role = MutableStateFlow(role)
    override val role: StateFlow<SessionRole> = _role.asStateFlow()

    /**
     * Guards every mutation of the plain membership state:
     * `admittedById`, `closed`, `hostLost`, `hostPeerId`, `pendingResume`,
     * `resumeToken`, `detectorJobs`, `channelViews`.
     *
     * Multiple coroutines (`runMainLoop`, `runTornWatcher`,
     * `runReconnectEventLoop`, per-peer detector collectors, `scope.launch { admitPeer }`,
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
            DefaultJoinerReconnectController(
                roomId = roomId,
                clock = { clock().toEpochMilliseconds() },
                scope = scope,
            )
        } else {
            null
        }

    /**
     * **Joiner only.** The [ResumeToken] minted at admit time.
     *
     * Null until the joiner receives its own [AdmitMessage.Welcome] carrying a [RoomId]
     * from the host. Used by [resume] to present credentials to the host.
     *
     * Publicly readable (implements [Room.resumeToken]) so the application layer and
     * the [us.tractat.kuilt.conformance.RoomConformanceSuite] TCK can access it without
     * module-internal visibility.
     */
    override var resumeToken: ResumeToken? = null
        private set

    /**
     * **Joiner only.** Pending [resume] calls waiting for the host's reply.
     *
     * When the joiner sends [AdmitMessage.Resume], it parks a [CompletableDeferred] here.
     * The host replies with [AdmitMessage.Welcome] (success) or [AdmitMessage.Reject]
     * (failure); [handleAdmitFrame] resolves the deferred.
     */
    private var pendingResume: CompletableDeferred<ResumeResult>? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    internal fun start() {
        val jobs = mutableListOf(
            scope.launch { runMainLoop() },
            scope.launch { runTornWatcher() },
        )
        if (reconnectController != null) {
            jobs += scope.launch { runReconnectEventLoop(reconnectController) }
        }
        loopJobs = jobs
    }

    // ── Torn watcher: react to permanent transport closure ────────────────────

    /**
     * Watches [Seam.state] for a [SeamState.Torn] transition and emits the
     * appropriate terminal membership event **immediately**, without waiting for
     * heartbeat expiry.
     *
     * This is faster and more correct than the heartbeat-timeout path for
     * transport-level closures: if the transport signals `Torn`, the session
     * layer should trust it directly.
     *
     * **Joiner:** emits [MembershipEvent.HostLost] and enters terminal state.
     * **Host:** emits [MembershipEvent.Left] for each admitted peer (mirroring the
     * heartbeat-based [LeaveReason.PartitionExpired] eviction path) and closes cleanly.
     */
    private suspend fun handleTorn() {
        val at = clock()
        if (_role.value == SessionRole.Joiner) {
            markHostLost(at)
        } else {
            evictAllOnTear()
            leave(LeaveReason.Normal)
        }
    }

    private suspend fun runTornWatcher() {
        seam.state.filterIsInstance<SeamState.Torn>().first()
        handleTorn()
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
     * - [JoinerReconnectEvent.WindowExpired] → no extra event here; the [HeartbeatPartitionDetector]
     *   drives [PartitionEvent.PeerLost] which produces [MembershipEvent.Left] via [handlePeerLost].
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
                    // Window expired — PeerLost from the detector handles the final eviction.
                    // No extra event emitted here; Left(PartitionExpired) follows from PeerLost.
                }
            }
        }
    }

    /**
     * A joiner successfully resumed (host perspective). Reset their liveness and
     * send a [AdmitMessage.ResumeAck] to the joiner so the joiner's [pendingResume]
     * deferred resolves as [ResumeResult.Success].
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
     * Single coroutine collecting [Seam.incoming]:
     * - Sends [AdmitMessage.Hello] first if this is a joiner.
     * - Fans each swatch to [rawIncoming] (for per-peer detectors).
     * - Routes frames through the admit protocol or to [incoming].
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
        }
        seam.incoming.collect { swatch ->
            rawIncoming.emit(swatch)
            dispatchIncoming(swatch)
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
            isAdmittedPeer(sender) -> routeApplicationFrame(sender, bytes)
            else -> { /* drop: application frame from unadmitted peer */ }
        }
    }

    // ── Admit protocol ────────────────────────────────────────────────────────

    private fun handleAdmitFrame(sender: PeerId, bytes: ByteArray) {
        when (val msg = AdmitMessage.decode(bytes)) {
            is AdmitMessage.Hello -> {
                if (_role.value == SessionRole.Host) {
                    scope.launch { admitPeer(sender, msg) }
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
                }
            }
            is AdmitMessage.Reject -> {
                if (_role.value == SessionRole.Joiner) {
                    lock.withLock {
                        pendingResume?.complete(ResumeResult.WindowClosed)
                        pendingResume = null
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
            principal = (seam as? PrincipalAttested)?.principal,
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
                displayName = displayName,
                sessionId = selfId.value,
            ),
        )
        runCatchingCancellable { seam.sendTo(joinerPeerId, hostIntro) }
    }

    private suspend fun sendHello() {
        val hello = AdmitMessage.Hello(
            displayName = displayName,
            sessionId = selfId.value,
        )
        runCatchingCancellable { seam.broadcast(AdmitMessage.encode(hello)) }
    }

    /**
     * Host-side handler for [AdmitMessage.Resume].
     *
     * Validates the token against the [reconnectController]. On [ResumeResult.Success],
     * [handleReconnectResumed] sends a [AdmitMessage.Welcome] confirmation to the joiner
     * via the reconnect controller's event stream. On failure, replies with [AdmitMessage.Reject].
     */
    private suspend fun handleResume(sender: PeerId, msg: AdmitMessage.Resume) {
        val ctrl = reconnectController ?: return
        val token = ResumeToken(
            peerId = PeerId(msg.tokenPeerId),
            roomId = RoomId(msg.tokenRoomId),
            issuedAt = msg.issuedAt,
        )
        val result = ctrl.tryResume(token, at = clock().toEpochMilliseconds())
        if (result !is ResumeResult.Success) {
            val rejectBytes = AdmitMessage.encode(AdmitMessage.Reject("resume-rejected"))
            runCatchingCancellable { seam.sendTo(sender, rejectBytes) }
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

            // Self-admission welcome: mint the resume token (once) from the roomId carried here.
            if (assignedId == selfId) {
                mintResumeTokenIfAbsent(welcome.roomId)
                return@withLock
            }

            // Host self-intro: the described peer IS the sender.
            if (assignedId == sender && hostPeerId == null) {
                hostPeerId = sender
            }

            // Also mint resume token from host intro welcome if not yet minted.
            mintResumeTokenIfAbsent(welcome.roomId)

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
     * Mints the [resumeToken] if not yet set. Callers must hold [lock].
     */
    private fun mintResumeTokenIfAbsent(roomId: String?) {
        if (resumeToken == null && roomId != null) {
            resumeToken = ResumeToken(
                peerId = selfId,
                roomId = RoomId(roomId),
                issuedAt = clock().toEpochMilliseconds(),
            )
        }
    }

    /**
     * Joiner-side: host confirmed our [AdmitMessage.Resume] was accepted.
     *
     * The host's [JoinerReconnectController] validated the token and the reconnect
     * window was still open. Update liveness, emit [MembershipEvent.Resumed], and
     * resolve the [pendingResume] deferred so [resume] returns [ResumeResult.Success].
     */
    private fun handleResumeAck(sender: PeerId) {
        val deferred = lock.withLock {
            updateMemberLiveness(sender, Liveness.Connected)
            val d = pendingResume
            pendingResume = null
            d
        }
        _events.tryEmit(MembershipEvent.Resumed(selfId))
        deferred?.complete(ResumeResult.Success)
    }

    // ── Partition detection ───────────────────────────────────────────────────

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
     * Callers must hold [lock] when invoking this method.
     */
    private fun startDetector(member: Member) {
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
     * A joiner whose **host** is lost to a definitive transport close goes terminal
     * immediately ([markHostLost]) — there is no host-resume path, so holding a window is
     * pointless delay. Every other case (host watching a joiner; a joiner's non-host peer;
     * a silent [PartitionEvent.Reason.Timeout] partition that may still recover) opens the
     * reconnect window via [markPartitioned].
     */
    private suspend fun handleUnresponsive(event: PartitionEvent.PeerUnresponsive) {
        val hostTransportClose = lock.withLock {
            _role.value == SessionRole.Joiner && event.peerId == hostPeerId
        } && event.reason == PartitionEvent.Reason.TransportClosed
        if (hostTransportClose) {
            markHostLost(event.at)
        } else {
            markPartitioned(event.peerId, event.at)
        }
    }

    private fun markPartitioned(peerId: PeerId, at: Instant) {
        val updated = lock.withLock { updateMemberLiveness(peerId, Liveness.Partitioned) } ?: return
        _events.tryEmit(MembershipEvent.Partitioned(updated.id, at))
        reconnectController?.onPeerUnresponsive(peerId, at.toEpochMilliseconds())
    }

    private fun markRecovered(peerId: PeerId, at: Instant) {
        val updated = lock.withLock { updateMemberLiveness(peerId, Liveness.Connected) } ?: return
        _events.tryEmit(MembershipEvent.Recovered(updated.id, at))
    }

    private suspend fun handlePeerLost(peerId: PeerId, at: Instant) {
        val isHostPeer = lock.withLock {
            stopDetector(peerId)
            _role.value == SessionRole.Joiner && peerId == hostPeerId
        }
        if (isHostPeer) {
            markHostLost(at)
        } else {
            removeFromRoster(peerId, LeaveReason.PartitionExpired)
        }
    }

    private suspend fun markHostLost(at: Instant) {
        val alreadyLost = lock.withLock {
            val was = hostLost
            hostLost = true
            was
        }
        if (alreadyLost) return
        _events.tryEmit(MembershipEvent.HostLost(at))
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
     * State mutation (installing the deferred) is under [lock]; the suspend broadcast is outside.
     * A [CancellationException] from the broadcast propagates — it is not swallowed.
     */
    override suspend fun resume(token: ResumeToken): ResumeResult {
        if (_role.value != SessionRole.Joiner) return ResumeResult.WindowClosed

        // Install the deferred under lock; check terminal flags first.
        val deferred = lock.withLock {
            if (hostLost || closed) return ResumeResult.WindowClosed
            CompletableDeferred<ResumeResult>().also { pendingResume = it }
        }

        val resumeMsg = AdmitMessage.encode(
            AdmitMessage.Resume(
                tokenPeerId = token.peerId.value,
                tokenRoomId = token.roomId.value,
                issuedAt = token.issuedAt,
            ),
        )
        // Suspend send outside the lock. A genuine CancellationException propagates (correct).
        // A non-cancellation send failure becomes WindowClosed.
        val sendResult = runCatchingCancellable { seam.broadcast(resumeMsg) }
        if (sendResult.isFailure) {
            lock.withLock { pendingResume = null }
            return ResumeResult.WindowClosed
        }

        return deferred.await()
    }

    override suspend fun leave(reason: LeaveReason) {
        // Flip closed + snapshot jobs under lock; announce, cancel, and close outside.
        val plan = lock.withLock {
            if (closed) return
            closed = true
            Triple(
                _role.value == SessionRole.Joiner && reason is LeaveReason.Normal,
                loopJobs,
                detectorJobs.values.toList().also { detectorJobs.clear() },
            )
        }
        val (announce, jobsToCancel, detectorJobsToCancel) = plan
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

    /** No-op — lifecycle is owned by [SeamRoom], not this view. */
    override suspend fun close(reason: CloseReason) = Unit
}
