package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.session.partition.HeartbeatConfig
import us.tractat.kuilt.session.partition.HeartbeatPartitionDetector
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.PartitionEvent
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
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
 * [clock] is injected (never [kotlin.time.Clock.System]) so tests can use virtual
 * time. [heartbeatConfig] controls partition-detection timing.
 */
public class SeamRoomFactory(
    private val loom: Loom,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = { Instant.fromEpochMilliseconds(0L) },
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
 * 2. **Peers watcher** — observes [Seam.peers] drops and fires [MembershipEvent.Left]
 *    for admitted members whose transport link closed.
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
 * become silent no-ops. No auto-election is performed (D-010).
 *
 * [start] must be called by [SeamRoomFactory] after construction to launch these loops.
 */
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
) : Room {
    override val selfId: PeerId = seam.selfId

    private val _role = MutableStateFlow(role)
    override val role: StateFlow<SessionRole> = _role.asStateFlow()

    // Admitted members (excluding self), keyed by PeerId for O(1) lookup.
    private val admittedById = mutableMapOf<PeerId, Member>()
    private val _roster = MutableStateFlow<Set<Member>>(emptySet())
    override val roster: StateFlow<Set<Member>> = _roster.asStateFlow()

    private val _events = MutableSharedFlow<MembershipEvent>(extraBufferCapacity = 64)
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
            scope.launch { runPeersWatcher() },
        )
        if (reconnectController != null) {
            jobs += scope.launch { runReconnectEventLoop(reconnectController) }
        }
        loopJobs = jobs
    }

    // ── Peers watcher: detect disconnects ─────────────────────────────────────

    /**
     * Watches [Seam.peers] for removals. When a peer disappears from the mesh
     * and was an admitted member, emits [MembershipEvent.Left].
     *
     * Skips the initial emission (drop(1)) since [Seam.peers] starts with the
     * current connected set — we only care about subsequent changes.
     */
    private suspend fun runPeersWatcher() {
        seam.peers.drop(1).collect { currentPeers ->
            val removedIds = admittedById.keys.filter { it !in currentPeers }
            for (peerId in removedIds) {
                stopDetector(peerId)
                removeFromRoster(peerId, LeaveReason.Normal)
            }
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
                    _events.tryEmit(MembershipEvent.WindowOpened(event.peerId, event.expiresAt))
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
     */
    private fun handleReconnectResumed(peerId: PeerId) {
        updateMemberLiveness(peerId, Liveness.Connected) ?: return
        _events.tryEmit(MembershipEvent.Resumed(peerId))
        val ackBytes = AdmitMessage.encode(AdmitMessage.ResumeAck)
        scope.launch { runCatching { seam.sendTo(peerId, ackBytes) } }
    }

    // ── Main loop ──────────────────────────────────────────────────────────────

    /**
     * Single coroutine collecting [Seam.incoming]:
     * - Sends [AdmitMessage.Hello] first if this is a joiner.
     * - Fans each swatch to [rawIncoming] (for per-peer detectors).
     * - Routes frames through the admit protocol or to [incoming].
     */
    private suspend fun runMainLoop() {
        if (_role.value == SessionRole.Joiner) sendHello()

        seam.incoming.collect { swatch ->
            rawIncoming.emit(swatch)
            dispatchIncoming(swatch)
        }
    }

    private fun dispatchIncoming(swatch: Swatch) {
        val sender = swatch.sender ?: return
        val bytes = swatch.payload
        when {
            HeartbeatPartitionDetector.isHeartbeatFrame(bytes) -> {
                // Heartbeat frames are consumed by per-peer detectors via rawIncoming.
                // No further action needed here — the detector's incomingJob handles them.
            }
            AdmitMessage.isAdmitFrame(bytes) -> handleAdmitFrame(sender, bytes)
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
            is AdmitMessage.Reject -> {
                if (_role.value == SessionRole.Joiner) {
                    pendingResume?.complete(ResumeResult.WindowClosed)
                    pendingResume = null
                }
            }
            null -> { /* malformed frame — ignore */ }
        }
    }

    /**
     * Host-side: admit a peer that sent [AdmitMessage.Hello].
     *
     * Steps:
     * 1. Add peer to roster.
     * 2. Send [AdmitMessage.Welcome] back to the joiner with their [PeerId].
     * 3. Broadcast the welcome to all other admitted members (roster sync).
     * 4. Send each already-known member's welcome to the new joiner (bootstrap their view).
     * 5. Send self-introduction (host identity) to the new joiner.
     */
    private suspend fun admitPeer(joinerPeerId: PeerId, hello: AdmitMessage.Hello) {
        val identity = MemberIdentity(
            displayName = hello.displayName,
            sessionId = hello.sessionId,
            deviceId = hello.deviceId,
        )
        val member = Member(id = joinerPeerId, identity = identity, liveness = Liveness.Connected)

        // Snapshot current members before adding the new one (for bootstrap step)
        val existingMembers = admittedById.values.toList()
        addToRoster(member)

        val welcome = AdmitMessage.Welcome(
            assignedPeerId = joinerPeerId.value,
            displayName = hello.displayName,
            sessionId = hello.sessionId,
            deviceId = hello.deviceId,
            roomId = roomId?.value,
        )
        val welcomeBytes = AdmitMessage.encode(welcome)

        // Send welcome directly to the joiner
        runCatching { seam.sendTo(joinerPeerId, welcomeBytes) }

        // Broadcast welcome to all other admitted members (roster sync)
        for (existing in existingMembers) {
            runCatching { seam.sendTo(existing.id, welcomeBytes) }
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
            runCatching { seam.sendTo(joinerPeerId, existingWelcome) }
        }

        // Send self-introduction (host introduces itself to the new joiner)
        val hostIntro = AdmitMessage.encode(
            AdmitMessage.Welcome(
                assignedPeerId = selfId.value,
                displayName = displayName,
                sessionId = selfId.value,
            ),
        )
        runCatching { seam.sendTo(joinerPeerId, hostIntro) }
    }

    private suspend fun sendHello() {
        val hello = AdmitMessage.Hello(
            displayName = displayName,
            sessionId = selfId.value,
        )
        runCatching { seam.broadcast(AdmitMessage.encode(hello)) }
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
            runCatching { seam.sendTo(sender, rejectBytes) }
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
        val assignedId = PeerId(welcome.assignedPeerId)

        // Self-admission welcome: mint the resume token (once) from the roomId carried here.
        if (assignedId == selfId) {
            if (resumeToken == null && welcome.roomId != null) {
                resumeToken = ResumeToken(
                    peerId = selfId,
                    roomId = RoomId(welcome.roomId),
                    issuedAt = clock().toEpochMilliseconds(),
                )
            }
            return
        }

        // Host self-intro: the described peer IS the sender.
        if (assignedId == sender && hostPeerId == null) {
            hostPeerId = sender
        }

        // Also mint resume token from host intro welcome if not yet minted.
        if (resumeToken == null && welcome.roomId != null) {
            resumeToken = ResumeToken(
                peerId = selfId,
                roomId = RoomId(welcome.roomId),
                issuedAt = clock().toEpochMilliseconds(),
            )
        }

        if (admittedById.containsKey(assignedId)) return // already known
        val identity = MemberIdentity(
            displayName = welcome.displayName,
            sessionId = welcome.sessionId,
            deviceId = welcome.deviceId,
        )
        val member = Member(id = assignedId, identity = identity, liveness = Liveness.Connected)
        addToRoster(member)
    }

    /**
     * Joiner-side: host confirmed our [AdmitMessage.Resume] was accepted.
     *
     * The host's [JoinerReconnectController] validated the token and the reconnect
     * window was still open. Update liveness, emit [MembershipEvent.Resumed], and
     * resolve the [pendingResume] deferred so [resume] returns [ResumeResult.Success].
     */
    private fun handleResumeAck(sender: PeerId) {
        updateMemberLiveness(sender, Liveness.Connected)
        _events.tryEmit(MembershipEvent.Resumed(selfId))
        pendingResume?.complete(ResumeResult.Success)
        pendingResume = null
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
     */
    private fun startDetector(member: Member) {
        val perPeerSeam = PerPeerSeam(seam, member.id, rawIncoming)
        val detector = HeartbeatPartitionDetector(
            link = perPeerSeam,
            peerId = member.id,
            config = heartbeatConfig,
            clock = clock,
        )
        detector.start(scope)

        val job = scope.launch {
            detector.events.collect { event -> handlePartitionEvent(event) }
        }
        detectorJobs[member.id] = job
    }

    private fun stopDetector(peerId: PeerId) {
        detectorJobs.remove(peerId)?.cancel()
    }

    private suspend fun handlePartitionEvent(event: PartitionEvent) {
        when (event) {
            is PartitionEvent.PeerUnresponsive -> markPartitioned(event.peerId, event.at)
            is PartitionEvent.PeerRecovered -> markRecovered(event.peerId, event.at)
            is PartitionEvent.PeerLost -> handlePeerLost(event.peerId, event.at)
        }
    }

    private fun markPartitioned(peerId: PeerId, at: Instant) {
        updateMemberLiveness(peerId, Liveness.Partitioned) ?: return
        _events.tryEmit(MembershipEvent.Partitioned(peerId, at))
        // Open the reconnect window on the host side.
        reconnectController?.onPeerUnresponsive(peerId, at.toEpochMilliseconds())
    }

    private fun markRecovered(peerId: PeerId, at: Instant) {
        updateMemberLiveness(peerId, Liveness.Connected) ?: return
        _events.tryEmit(MembershipEvent.Recovered(peerId, at))
    }

    private suspend fun handlePeerLost(peerId: PeerId, at: Instant) {
        stopDetector(peerId)
        if (_role.value == SessionRole.Joiner && peerId == hostPeerId) {
            markHostLost(at)
        } else {
            removeFromRoster(peerId, LeaveReason.PartitionExpired)
        }
    }

    private suspend fun markHostLost(at: Instant) {
        if (hostLost) return
        hostLost = true
        _events.tryEmit(MembershipEvent.HostLost(at))
        // Room is now terminal. Leave cleanly to cancel loops and close the seam.
        leave(LeaveReason.Error("host lost"))
    }

    private fun updateMemberLiveness(peerId: PeerId, liveness: Liveness): Member? {
        val current = admittedById[peerId] ?: return null
        val updated = current.copy(liveness = liveness)
        admittedById[peerId] = updated
        _roster.update { current -> current.map { if (it.id == peerId) updated else it }.toSet() }
        return updated
    }

    // ── Roster management ────────────────────────────────────────────────────

    private fun addToRoster(member: Member) {
        admittedById[member.id] = member
        _roster.update { current -> current + member }
        _events.tryEmit(MembershipEvent.Joined(member))
        startDetector(member)
    }

    private fun removeFromRoster(peerId: PeerId, reason: LeaveReason) {
        admittedById.remove(peerId) ?: return // already removed, avoid duplicate Left events
        _roster.update { current -> current.filterNot { it.id == peerId }.toSet() }
        _events.tryEmit(MembershipEvent.Left(peerId, reason))
    }

    private fun isAdmittedPeer(peerId: PeerId): Boolean = admittedById.containsKey(peerId)

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
        if (hostLost || closed) return
        seam.broadcast(bytes)
    }

    /**
     * Send [bytes] to one specific admitted member.
     *
     * Silent no-op when the room is terminal (after [MembershipEvent.HostLost] or [leave]).
     */
    override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
        if (hostLost || closed) return
        seam.sendTo(peer, bytes)
    }

    /**
     * Attempt to resume this room from a [ResumeToken] after a transport drop.
     *
     * **Joiner only.** The host's [JoinerReconnectController] holds the reconnect window;
     * this method sends [AdmitMessage.Resume] to the host and awaits the reply:
     * - Host replies [AdmitMessage.Welcome] (self) → [ResumeResult.Success]; [MembershipEvent.Resumed] fires.
     * - Host replies [AdmitMessage.Reject] → [ResumeResult.WindowClosed].
     *
     * **Not valid** after [MembershipEvent.HostLost] — the room is terminal at that point.
     * Callers should guard with [hostLost] before calling.
     *
     * **Not valid** on the host side — returns [ResumeResult.WindowClosed] immediately.
     */
    override suspend fun resume(token: ResumeToken): ResumeResult {
        if (_role.value != SessionRole.Joiner) return ResumeResult.WindowClosed
        if (hostLost || closed) return ResumeResult.WindowClosed

        val deferred = CompletableDeferred<ResumeResult>()
        pendingResume = deferred

        val resumeMsg = AdmitMessage.encode(
            AdmitMessage.Resume(
                tokenPeerId = token.peerId.value,
                tokenRoomId = token.roomId.value,
                issuedAt = token.issuedAt,
            ),
        )
        val sendResult = runCatching { seam.broadcast(resumeMsg) }
        if (sendResult.isFailure) {
            pendingResume = null
            return ResumeResult.WindowClosed
        }

        return deferred.await()
    }

    override suspend fun leave(reason: LeaveReason) {
        if (closed) return
        closed = true
        loopJobs.forEach { it.cancel() }
        detectorJobs.values.forEach { it.cancel() }
        detectorJobs.clear()
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

    override val incoming: Flow<Swatch>
        get() = rawIncoming.filter { it.sender == targetPeerId }

    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = delegate.sendTo(peer, payload)

    /** No-op — lifecycle is owned by [SeamRoom], not this view. */
    override suspend fun close(reason: CloseReason) = Unit
}
