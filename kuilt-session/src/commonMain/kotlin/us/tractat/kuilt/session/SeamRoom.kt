package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken

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
 */
public class SeamRoomFactory(
    private val loom: Loom,
    private val scope: CoroutineScope,
) : RoomFactory {
    override suspend fun host(pattern: Pattern): Room {
        val seam = loom.host(pattern)
        return SeamRoom(
            seam = seam,
            role = SessionRole.Host,
            displayName = pattern.displayName,
            scope = scope,
        ).also { room -> room.start() }
    }

    override suspend fun join(tag: Tag): Room {
        val seam = loom.join(tag)
        return SeamRoom(
            seam = seam,
            role = SessionRole.Joiner,
            displayName = tag.displayName,
            scope = scope,
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
 * 1. **Admit loop** — collects [Seam.incoming], routes admit frames through
 *    the handshake and application frames through to [incoming].
 * 2. **Peers watcher** — observes [Seam.peers] drops and fires [MembershipEvent.Left]
 *    for admitted members whose transport link closed.
 *
 * [start] must be called by [SeamRoomFactory] after construction to launch these loops.
 */
internal class SeamRoom(
    private val seam: Seam,
    role: SessionRole,
    private val displayName: String,
    private val scope: CoroutineScope,
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

    private var loopJobs: List<Job> = emptyList()
    private var closed = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    internal fun start() {
        loopJobs = when (_role.value) {
            SessionRole.Host -> listOf(
                scope.launch { runHostAdmitLoop() },
                scope.launch { runPeersWatcher() },
            )
            SessionRole.Joiner -> listOf(
                scope.launch { runJoinerAdmitLoop() },
                scope.launch { runPeersWatcher() },
            )
        }
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
                removeFromRoster(peerId, LeaveReason.Normal)
            }
        }
    }

    // ── Host-side: process incoming frames, admit new peers ──────────────────

    private suspend fun runHostAdmitLoop() {
        seam.incoming.collect { swatch ->
            val sender = swatch.sender ?: return@collect
            val bytes = swatch.payload
            when {
                AdmitMessage.isAdmitFrame(bytes) -> handleHostAdmitFrame(sender, bytes)
                isAdmittedPeer(sender) -> routeApplicationFrame(sender, bytes)
                else -> { /* drop: application frame from unadmitted peer */ }
            }
        }
    }

    private suspend fun handleHostAdmitFrame(sender: PeerId, bytes: ByteArray) {
        when (val msg = AdmitMessage.decode(bytes)) {
            is AdmitMessage.Hello -> admitPeer(sender, msg)
            is AdmitMessage.Welcome, is AdmitMessage.Reject, null -> { /* host doesn't receive these */ }
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

    // ── Joiner-side: send Hello, handle Welcome responses ────────────────────

    private suspend fun runJoinerAdmitLoop() {
        // Send Hello immediately — broadcast since we don't yet know the host's PeerId
        val hello = AdmitMessage.Hello(
            displayName = displayName,
            sessionId = selfId.value,
        )
        runCatching { seam.broadcast(AdmitMessage.encode(hello)) }

        // Collect incoming: Welcome → roster update; app frames → route (once admitted)
        seam.incoming.collect { swatch ->
            val sender = swatch.sender ?: return@collect
            val bytes = swatch.payload
            when {
                AdmitMessage.isAdmitFrame(bytes) -> handleJoinerAdmitFrame(sender, bytes)
                isAdmittedPeer(sender) -> routeApplicationFrame(sender, bytes)
                else -> { /* drop: application frame from unadmitted peer */ }
            }
        }
    }

    private fun handleJoinerAdmitFrame(sender: PeerId, bytes: ByteArray) {
        when (val msg = AdmitMessage.decode(bytes)) {
            is AdmitMessage.Welcome -> handleWelcome(msg)
            is AdmitMessage.Reject -> { /* TODO: surface as event/error in future slice */ }
            is AdmitMessage.Hello, null -> { /* joiners don't process Hello */ }
        }
    }

    /**
     * Joiner-side: handle a [AdmitMessage.Welcome].
     *
     * The host sends Welcome both for the joiner themselves (confirming their own admission)
     * and for each existing member (bootstrapping the joiner's roster view).
     * Either way, add the described peer to our roster if not already there.
     */
    private fun handleWelcome(welcome: AdmitMessage.Welcome) {
        val assignedId = PeerId(welcome.assignedPeerId)
        if (assignedId == selfId) return // ignore self-admission — we're already "in"
        if (admittedById.containsKey(assignedId)) return // already known
        val identity = MemberIdentity(
            displayName = welcome.displayName,
            sessionId = welcome.sessionId,
            deviceId = welcome.deviceId,
        )
        val member = Member(id = assignedId, identity = identity, liveness = Liveness.Connected)
        addToRoster(member)
    }

    // ── Roster management ────────────────────────────────────────────────────

    private fun addToRoster(member: Member) {
        admittedById[member.id] = member
        _roster.update { current -> current + member }
        _events.tryEmit(MembershipEvent.Joined(member))
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

    override suspend fun broadcast(bytes: ByteArray) {
        seam.broadcast(bytes)
    }

    override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
        seam.sendTo(peer, bytes)
    }

    override suspend fun resume(token: ResumeToken): ResumeResult {
        // TODO(1D): wire JoinerReconnectController here
        return ResumeResult.WindowClosed
    }

    override suspend fun leave(reason: LeaveReason) {
        if (closed) return
        closed = true
        loopJobs.forEach { it.cancel() }
        seam.close(
            when (reason) {
                is LeaveReason.Normal -> CloseReason.Normal
                is LeaveReason.Error -> CloseReason.Error(RuntimeException(reason.message))
            },
        )
    }
}
