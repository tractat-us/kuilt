package us.tractat.kuilt.session.test

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MemberIdentity
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFrame
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import kotlin.time.Instant

/**
 * A test double for [Room] with test-driver helpers for roster manipulation,
 * event delivery, and outgoing-frame inspection.
 *
 * Defaults make `FakeRoom()` a ready-to-use, host-role, empty-roster room
 * in one line:
 *
 * ```kotlin
 * val room = FakeRoom()
 * room.addMember(Member(PeerId("alice"), identity("alice"), Liveness.Connected))
 * room.deliver(PeerId("alice"), byteArrayOf(1, 2, 3))
 * val frame = room.incoming.first()
 * ```
 *
 * For wired two-room scenarios, prefer [fakeRoomPair] which cross-wires
 * broadcast delivery between two rooms.
 *
 * **Send semantics** (matching the [Room] contract):
 * - [broadcast] and [sendTo] after [leave] are silent no-ops (matching the
 *   contract's behaviour after [MembershipEvent.HostLost]).
 */
public class FakeRoom(
    override val selfId: PeerId = PeerId("self"),
    initialRole: SessionRole = SessionRole.Host,
    initialRoster: Set<Member> = emptySet(),
    initialResumeToken: ResumeToken? = null,
) : Room {
    private val _role = MutableStateFlow(initialRole)
    override val role: StateFlow<SessionRole> = _role.asStateFlow()

    private val _roster = MutableStateFlow(initialRoster)
    override val roster: StateFlow<Set<Member>> = _roster.asStateFlow()

    private val eventsChannel = Channel<MembershipEvent>(capacity = Channel.UNLIMITED)
    override val events: Flow<MembershipEvent> = eventsChannel.receiveAsFlow()

    internal val incomingChannel = Channel<RoomFrame>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<RoomFrame> = incomingChannel.receiveAsFlow()

    private var _resumeToken: ResumeToken? = initialResumeToken
    override val resumeToken: ResumeToken? get() = _resumeToken

    private val _broadcasts = mutableListOf<ByteArray>()
    private val _directed = mutableListOf<Pair<PeerId, ByteArray>>()
    private var left = false

    /** All payloads passed to [broadcast], in call order. */
    public val broadcasts: List<ByteArray> get() = _broadcasts.toList()

    /** All (peer, payload) pairs passed to [sendTo], in call order. */
    public val directed: List<Pair<PeerId, ByteArray>> get() = _directed.toList()

    /** Configurable result returned by [resume]. Defaults to [ResumeResult.Success]. */
    public var resumeResult: ResumeResult = ResumeResult.Success

    /**
     * Optional hook invoked after [broadcast] is recorded (and the left-check passes).
     * Used internally by [fakeRoomPair] to cross-wire delivery. Not part of the public API.
     */
    internal var onBroadcast: (suspend (ByteArray) -> Unit)? = null

    // ── Room interface ────────────────────────────────────────────────────────

    override suspend fun broadcast(bytes: ByteArray) {
        if (left) return
        _broadcasts.add(bytes)
        onBroadcast?.invoke(bytes)
    }

    override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
        if (left) return
        _directed.add(peer to bytes)
    }

    override suspend fun resume(token: ResumeToken): ResumeResult = resumeResult

    override suspend fun leave(reason: LeaveReason) {
        if (left) return
        left = true
        eventsChannel.close()
        incomingChannel.close()
    }

    // ── Test-driver helpers ───────────────────────────────────────────────────

    /**
     * Add [member] to the roster and emit [MembershipEvent.Joined].
     *
     * ```kotlin
     * room.addMember(Member(PeerId("alice"), identity("alice"), Liveness.Connected))
     * ```
     */
    public suspend fun addMember(member: Member) {
        _roster.update { it + member }
        eventsChannel.send(MembershipEvent.Joined(member))
    }

    /**
     * Remove the member with [peerId] from the roster and emit [MembershipEvent.Left].
     * No-op if the peer is not in the roster.
     */
    public suspend fun removeMember(peerId: PeerId, reason: LeaveReason = LeaveReason.Normal) {
        _roster.update { roster -> roster.filterNot { it.id == peerId }.toSet() }
        eventsChannel.send(MembershipEvent.Left(peerId, reason))
    }

    /**
     * Flip the named member's [Liveness] to [Liveness.Partitioned] and emit
     * [MembershipEvent.Partitioned].
     */
    public suspend fun partition(peerId: PeerId, at: Instant) {
        updateLiveness(peerId, Liveness.Partitioned)
        eventsChannel.send(MembershipEvent.Partitioned(peerId, at))
    }

    /**
     * Flip the named member's [Liveness] back to [Liveness.Connected] and emit
     * [MembershipEvent.Recovered].
     */
    public suspend fun recover(peerId: PeerId, at: Instant) {
        updateLiveness(peerId, Liveness.Connected)
        eventsChannel.send(MembershipEvent.Recovered(peerId, at))
    }

    /**
     * Emit [MembershipEvent.WindowOpened] (host-side signal that a joiner's
     * reconnect window has opened, expiring at epoch-millis [expiresAt]).
     */
    public suspend fun openWindow(peerId: PeerId, expiresAt: Long) {
        eventsChannel.send(MembershipEvent.WindowOpened(peerId, expiresAt))
    }

    /**
     * Emit [MembershipEvent.Resumed] for [peerId] (a partitioned joiner has
     * reconnected within the window).
     */
    public suspend fun emitResumed(peerId: PeerId) {
        eventsChannel.send(MembershipEvent.Resumed(peerId))
    }

    /**
     * Emit [MembershipEvent.HostLost] (terminal event on a joiner's room).
     * After this, [broadcast] and [sendTo] become silent no-ops per the contract.
     */
    public suspend fun hostLost(at: Instant) {
        left = true
        eventsChannel.send(MembershipEvent.HostLost(at))
    }

    /**
     * Push [payload] from [from] into [incoming] as a [RoomFrame].
     *
     * ```kotlin
     * room.deliver(PeerId("alice"), byteArrayOf(1, 2, 3))
     * val frame = room.incoming.first()   // RoomFrame(sender=PeerId("alice"), payload=[1,2,3])
     * ```
     */
    public suspend fun deliver(from: PeerId, payload: ByteArray) {
        incomingChannel.send(RoomFrame(sender = from, payload = payload))
    }

    /**
     * Push a raw [MembershipEvent] onto the events channel.
     * Useful for events not covered by the named helpers.
     */
    public suspend fun emit(event: MembershipEvent) {
        eventsChannel.send(event)
    }

    /** Transition [role] to [newRole]. */
    public fun setRole(newRole: SessionRole) {
        _role.value = newRole
    }

    /** Update [resumeToken]. */
    public fun setResumeToken(token: ResumeToken?) {
        _resumeToken = token
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun updateLiveness(peerId: PeerId, liveness: Liveness) {
        _roster.update { roster ->
            roster.map { member ->
                if (member.id == peerId) member.copy(liveness = liveness) else member
            }.toSet()
        }
    }
}
