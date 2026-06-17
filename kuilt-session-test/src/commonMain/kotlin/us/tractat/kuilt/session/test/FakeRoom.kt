package us.tractat.kuilt.session.test

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
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
 *
 * **Stream semantics — a deliberate divergence from the real [Room].** The real
 * [Room] documents [events] and [incoming] as *hot, no-replay* streams (late
 * collectors miss history). For test ergonomics this double backs them with
 * unbounded buffering channels instead, so `deliver(...)` followed by
 * `incoming.first()` works without racing a collector. Two consequences a
 * consumer should not encode as [Room] guarantees:
 * - frames/events emitted before collection are **buffered and replayed** here,
 *   whereas the real [Room] would drop them;
 * - [leave] **completes** [events]/[incoming] (channel close), whereas the real
 *   [Room] cancels its backing scope without completing the flows.
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

    /**
     * Admitted roster as peer ids including self. Kept in sync with [_roster] by
     * [addMember] and [removeMember]. Used by [FakeChannelSeam.peers].
     */
    private val _rosterPeers = MutableStateFlow(initialRoster.mapTo(mutableSetOf()) { it.id } + selfId)

    /** Seam state forwarded to channel views. Use [tearSeam] to simulate transport closure. */
    private val _seamState = MutableStateFlow<SeamState>(SeamState.Woven)

    private val eventsChannel = Channel<MembershipEvent>(capacity = Channel.UNLIMITED)
    override val events: Flow<MembershipEvent> = eventsChannel.receiveAsFlow()

    private val incomingChannel = Channel<RoomFrame>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<RoomFrame> = incomingChannel.receiveAsFlow()

    private var _resumeToken: ResumeToken? = initialResumeToken
    override val resumeToken: ResumeToken? get() = _resumeToken

    private val _broadcasts = mutableListOf<ByteArray>()
    private val _directed = mutableListOf<Pair<PeerId, ByteArray>>()
    private var left = false

    /** Channel views keyed by channel id. Created on demand and cached via [channel]. */
    private val channelViews = mutableMapOf<String, Seam>()

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
        require(member.id != selfId) { "roster must not include selfId ($selfId); see Room.roster" }
        _roster.update { it + member }
        _rosterPeers.update { it + member.id }
        eventsChannel.send(MembershipEvent.Joined(member))
    }

    /**
     * Remove the member with [peerId] from the roster and emit [MembershipEvent.Left].
     * No-op if the peer is not in the roster.
     */
    public suspend fun removeMember(peerId: PeerId, reason: LeaveReason = LeaveReason.Normal) {
        _roster.update { roster -> roster.filterNot { it.id == peerId }.toSet() }
        _rosterPeers.update { it - peerId }
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
     * reconnect window has opened, expiring at [expiresAt]).
     */
    public suspend fun openWindow(peerId: PeerId, expiresAt: Instant) {
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

    // ── channel ───────────────────────────────────────────────────────────────

    /**
     * Returns a [Seam] view scoped to channel [id], backed by [_rosterPeers] for
     * admit-gated peer visibility and a dedicated [Channel<Swatch>] for test-driver
     * frame delivery via [FakeChannelSeam.deliver].
     *
     * Idempotent: the same [Seam] instance is returned for each distinct [id].
     */
    override fun channel(id: String): Seam = channelViews.getOrPut(id) { FakeChannelSeam(id) }

    /**
     * Transition the seam state to [SeamState.Torn]. Channel views forward [state] from
     * [_seamState], so tearing the seam winds down any [us.tractat.kuilt.crdt.replicator.Quilter]
     * subscribed to the channel.
     */
    public fun tearSeam(reason: CloseReason = CloseReason.Normal) {
        _seamState.value = SeamState.Torn(reason)
    }

    /**
     * Returns the [FakeChannelSeam] for [id] if it has been created, or null otherwise.
     *
     * Useful in tests that need to deliver frames into a channel before the production
     * code under test calls [channel].
     */
    public fun channelOrNull(id: String): FakeChannelSeam? = channelViews[id] as? FakeChannelSeam

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun updateLiveness(peerId: PeerId, liveness: Liveness) {
        _roster.update { roster ->
            roster.map { member ->
                if (member.id == peerId) member.copy(liveness = liveness) else member
            }.toSet()
        }
    }

    /**
     * A [Seam] view returned by [FakeRoom.channel].
     *
     * - `peers` reflects the admitted roster (+ self) via [_rosterPeers].
     * - `incoming` is driven by test-driver [deliver] calls.
     * - `broadcast`/`sendTo` delegate to [FakeRoom.broadcast]/[sendTo] with raw
     *   payloads (no channel framing added — the Fake is not a protocol layer).
     * - `state` forwards [_seamState].
     * - `close` is a no-op — the [FakeRoom] owns the lifecycle.
     */
    public inner class FakeChannelSeam(public val id: String) : Seam {
        override val selfId: PeerId get() = this@FakeRoom.selfId
        override val peers: StateFlow<Set<PeerId>> get() = _rosterPeers.asStateFlow()
        override val state: StateFlow<SeamState> get() = _seamState.asStateFlow()

        private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
        override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

        override suspend fun broadcast(payload: ByteArray): Unit = this@FakeRoom.broadcast(payload)
        override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = this@FakeRoom.sendTo(peer, payload)

        /** No-op — [FakeRoom] owns the lifecycle. */
        override suspend fun close(reason: CloseReason): Unit = Unit

        /** Push [payload] from [sender] into this channel's [incoming]. */
        public suspend fun deliver(sender: PeerId, payload: ByteArray) {
            incomingChannel.send(Swatch(payload = payload, sender = sender, sequence = 0L))
        }
    }
}
