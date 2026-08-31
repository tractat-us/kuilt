package us.tractat.kuilt.session.test

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.session.FailureReason
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Liveness
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MemberIdentity
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.ReconnectReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFrame
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The process-wide room counter shared by [FakeRoom]'s host default and [FakeRoomFactory.host].
 *
 * Atomic because a fake is not exempt from the multi-threaded-dispatcher rule, and process-wide (not
 * per-instance) because the collision this prevents is precisely *between* instances.
 */
private val fakeRoomSequence = atomic(0L)

/**
 * Mint a distinct [RoomId] for a host-shaped fake room — readable (it leads with [selfId]) but never
 * repeated, so no two fakes hand out one identity. See [FakeRoom]'s `initialRoomId` for why the
 * obvious `"<selfId>-room"` is the wrong default.
 */
internal fun mintFakeRoomId(selfId: PeerId): RoomId = RoomId("${selfId.value}-room-${fakeRoomSequence.getAndIncrement()}")

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
 * bounded, backpressured channels instead, so `deliver(...)` followed by
 * `incoming.first()` works without racing a collector (capacity [DeliveryPolicy.DEFAULT_CAPACITY]).
 * [FakeChannelSeam.incoming] is backed by a [Spool] using [channelPolicy].
 * Two consequences a consumer should not encode as [Room] guarantees:
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
    public val channelPolicy: DeliveryPolicy = DeliveryPolicy.Reliable,
    /**
     * The [RoomId] this room reports, mirroring the shape a real room has: a host is born knowing
     * its room, a joiner is null until the host's `Welcome` admits it (#1594).
     *
     * Defaulted from [initialRole] so `FakeRoom()` and `FakeRoom(initialRole = Joiner)` both start
     * in the state their role really starts in. Pass an explicit value — or move it later with
     * [setRoomId] — whenever the test asserts on the id itself. It is **not** a constant: a fake
     * that could not express "no room id yet", or "this specific room id", would make every test
     * written against it pass without checking anything.
     *
     * The host default is **minted**, not derived from [selfId] alone. `"${'$'}{selfId.value}-room"`
     * is character-for-character the expression #1594 removed from production, and it collides on
     * exactly the same axis: two `FakeRoom(selfId = PeerId("host"))` — or two [fakeRoomPair] calls,
     * which default to that very id — would share one [RoomId] and quietly reproduce the bug inside
     * the double built to test around it.
     */
    initialRoomId: RoomId? = if (initialRole == SessionRole.Host) mintFakeRoomId(selfId) else null,
) : Room {
    private val _role = MutableStateFlow(initialRole)
    override val role: StateFlow<SessionRole> = _role.asStateFlow()

    private val _roster = MutableStateFlow(initialRoster)
    override val roster: StateFlow<Set<Member>> = _roster.asStateFlow()

    private val _attestedPrincipals = MutableStateFlow<Map<PeerId, Principal>>(emptyMap())
    override val attestedPrincipals: StateFlow<Map<PeerId, Principal>> = _attestedPrincipals.asStateFlow()

    /** Test hook: set the attested-principals roster this fake room reports. */
    public fun setAttestedPrincipals(principals: Map<PeerId, Principal>) {
        _attestedPrincipals.value = principals
    }

    private val _localFabric = MutableStateFlow<FabricAvailability>(FabricAvailability.Available)
    override val localFabric: StateFlow<FabricAvailability> = _localFabric.asStateFlow()

    /**
     * The last [FabricAvailability] that *decided* something — [FabricAvailability.Available] or
     * [FabricAvailability.Unavailable], never [FabricAvailability.Unknown]. What
     * [setLocalFabric] measures an edge against, so a recovery that passes through `Unknown` still
     * emits [MembershipEvent.LocalFabricRestored], exactly as the real room does.
     */
    private var lastDecidedFabric: FabricAvailability = FabricAvailability.Available

    /**
     * Admitted roster as peer ids including self. Kept in sync with [_roster] by
     * [addMember] and [removeMember]. Used by [FakeChannelSeam.peers].
     */
    private val _rosterPeers = MutableStateFlow(initialRoster.mapTo(mutableSetOf()) { it.id } + selfId)

    /** Seam state forwarded to channel views. Use [tearSeam] to simulate transport closure. */
    private val _seamState = MutableStateFlow<SeamState>(SeamState.Woven)

    private val eventsChannel = Channel<MembershipEvent>(
        capacity = DeliveryPolicy.DEFAULT_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<MembershipEvent> = eventsChannel.receiveAsFlow()

    private val incomingChannel = Channel<RoomFrame>(
        capacity = DeliveryPolicy.DEFAULT_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: Flow<RoomFrame> = incomingChannel.receiveAsFlow()

    private val _roomId = MutableStateFlow(initialRoomId)
    override val roomId: StateFlow<RoomId?> = _roomId.asStateFlow()

    /**
     * Test hook: set (or clear) the [RoomId] this room reports.
     *
     * The driver for the one transition a real room makes — a joiner learning the host's room id on
     * admission. `null` puts it back in the not-yet-admitted state.
     */
    public fun setRoomId(roomId: RoomId?) {
        _roomId.value = roomId
    }

    private var _resumeToken: ResumeToken? = initialResumeToken
    override val resumeToken: ResumeToken? get() = _resumeToken

    private val _broadcasts = mutableListOf<ByteArray>()
    private val _directed = mutableListOf<Pair<PeerId, ByteArray>>()
    /**
     * The leave-once latch. Atomic rather than a plain `var` (#2328): a fake is what a consumer's
     * own concurrency is measured against, so a permissive one is how a defect stops being visible
     * to every suite built on it. `compareAndSet` in [leave] also stops two callers both closing
     * the channels and both running the body.
     */
    private val left = atomic(false)

    /** Channel views keyed by channel id. Created on demand and cached via [channel]. */
    private val channelViews = mutableMapOf<String, Seam>()

    /** All payloads passed to [broadcast], in call order. */
    public val broadcasts: List<ByteArray> get() = _broadcasts.toList()

    /** All (peer, payload) pairs passed to [sendTo], in call order. */
    public val directed: List<Pair<PeerId, ByteArray>> get() = _directed.toList()

    /**
     * Configurable result returned by [resume]. Defaults to [ResumeResult.Success].
     *
     * Typed [ResumeResult.JoinerOutcome], the half of the hierarchy a real `Room.resume` can
     * produce (#2364) — so a fake cannot hand a consumer a host-side verdict no room would ever
     * return, and a test that wants a *refusal* has to state the
     * [us.tractat.kuilt.session.admit.RejectCode] it is refusing with.
     */
    public var resumeResult: ResumeResult.JoinerOutcome = ResumeResult.Success

    /**
     * Optional hook invoked after [broadcast] is recorded (and the left-check passes).
     * Used internally by [fakeRoomPair] to cross-wire delivery. Not part of the public API.
     */
    internal var onBroadcast: (suspend (ByteArray) -> Unit)? = null

    // ── Room interface ────────────────────────────────────────────────────────

    override suspend fun broadcast(bytes: ByteArray) {
        if (left.value) return
        _broadcasts.add(bytes)
        onBroadcast?.invoke(bytes)
    }

    override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
        if (left.value) return
        _directed.add(peer to bytes)
    }

    override suspend fun resume(token: ResumeToken): ResumeResult.JoinerOutcome = resumeResult

    override suspend fun leave(reason: LeaveReason) {
        if (!left.compareAndSet(expect = false, update = true)) return
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
     *
     * [windowExpiresAt] defaults to one minute past [at] — an arbitrary but non-null stand-in,
     * since [Liveness.Partitioned] carries a real deadline in production. Pass an explicit value
     * when a test asserts on the countdown. It is the **last** parameter so existing positional
     * `partition(peerId, at, reason)` call sites keep compiling.
     *
     * The event's [MembershipEvent.Partitioned.localFabric] tag is this fake's **current** level, so
     * a test that drives [setLocalFabric] first gets the same precedence signal a real room would
     * (#1712): drop our own fabric, then partition a peer, and the event says the silence is not
     * evidence about that peer.
     */
    public suspend fun partition(
        peerId: PeerId,
        at: Instant,
        reason: ReconnectReason = ReconnectReason.LinkTimeout,
        windowExpiresAt: Instant = at + 1.minutes,
    ) {
        updateLiveness(peerId, Liveness.Partitioned(since = at, windowExpiresAt = windowExpiresAt))
        eventsChannel.send(MembershipEvent.Partitioned(peerId, at, reason, localFabric = _localFabric.value))
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
     *
     * Tagged with this fake's **current** [Room.localFabric], as [partition] is — so a test can
     * reproduce #1712's headline case (our own radio died, so "the host is gone" is not a claim
     * about the host) by calling [setLocalFabric] with [FabricAvailability.Unavailable] first.
     */
    public suspend fun hostLost(at: Instant, reason: FailureReason = FailureReason.WindowExpired) {
        left.value = true
        eventsChannel.send(MembershipEvent.HostLost(at, reason, localFabric = _localFabric.value))
    }

    /**
     * Drive this fake's own self-reachability ([Room.localFabric]), emitting the matching edge.
     *
     * Starts at [FabricAvailability.Available] so existing tests are unaffected — a fake is exactly
     * the thing that *can* tell, so it does not sit at [FabricAvailability.Unknown] the way a real
     * fabric with no path observer does.
     *
     * Mirrors the real room's rule: an edge only on a transition into [FabricAvailability.Available]
     * or [FabricAvailability.Unavailable]; [FabricAvailability.Unknown] is level-only, because
     * "we stopped being able to tell" is neither a loss nor a recovery. Recovery **through**
     * `Unknown` still restores — the last *decided* level is what a `Restored` is measured against,
     * not the immediately preceding one.
     *
     * No-op when [availability] already equals the current level.
     */
    public suspend fun setLocalFabric(availability: FabricAvailability, at: Instant) {
        val previous = _localFabric.value
        if (previous == availability) return
        _localFabric.value = availability
        when (availability) {
            is FabricAvailability.Unavailable -> {
                if (lastDecidedFabric !is FabricAvailability.Unavailable) {
                    lastDecidedFabric = availability
                    eventsChannel.send(MembershipEvent.LocalFabricLost(at, availability.reason))
                }
            }

            is FabricAvailability.Available -> {
                val restored = lastDecidedFabric is FabricAvailability.Unavailable
                lastDecidedFabric = availability
                if (restored) eventsChannel.send(MembershipEvent.LocalFabricRestored(at))
            }

            is FabricAvailability.Unknown -> Unit
        }
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
     * admit-gated peer visibility and a bounded [Spool] for test-driver frame
     * delivery via [FakeChannelSeam.deliver].
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
     * - `incoming` is driven by test-driver [deliver] calls, buffered via a
     *   bounded [Spool] (policy from [FakeRoom.channelPolicy]).
     * - `broadcast`/`sendTo` delegate to [FakeRoom.broadcast]/[sendTo] with raw
     *   payloads (no channel framing added — the Fake is not a protocol layer).
     * - `state` forwards [_seamState].
     * - `close` is a no-op — the [FakeRoom] owns the lifecycle.
     */
    public inner class FakeChannelSeam(public val id: String) : Seam {
        override val selfId: PeerId get() = this@FakeRoom.selfId
        override val peers: StateFlow<Set<PeerId>> get() = _rosterPeers.asStateFlow()
        override val state: StateFlow<SeamState> get() = _seamState.asStateFlow()

        private val spool = Spool<Swatch>(channelPolicy)
        override val incoming: Flow<Swatch> = spool.incoming

        override suspend fun broadcast(payload: ByteArray): Unit = this@FakeRoom.broadcast(payload)

        /**
         * Refused for `selfId` (#2428), then delegated. The delegate is a **Room**, and `Room.sendTo`
         * carries no such refusal — the real [us.tractat.kuilt.session.RoomChannel] is guarded at
         * exactly this position for exactly that reason, and a fake that accepts what the real
         * channel refuses is how a consumer test goes green on a call production would reject.
         */
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
            this@FakeRoom.sendTo(peer, payload)
        }

        /** No-op — [FakeRoom] owns the lifecycle. */
        override suspend fun close(reason: CloseReason): Unit = Unit

        /** Push [payload] from [sender] into this channel's [incoming]. */
        public suspend fun deliver(sender: PeerId, payload: ByteArray) {
            spool.deliver(Swatch(payload = payload, sender = sender, sequence = 0L))
        }
    }
}
