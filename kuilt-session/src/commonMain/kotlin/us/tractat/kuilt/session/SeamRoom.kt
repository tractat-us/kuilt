package us.tractat.kuilt.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.validFirstHop
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
import us.tractat.kuilt.session.partition.ResumeRefusal
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.session.SeamRoom")

/**
 * The process-wide room counter behind [SeamRoomFactory.mintRoomId] — see its KDoc for why the mint
 * needs one at all, and why it is not per-factory.
 *
 * Atomic because rooms are woven from arbitrary coroutines on arbitrary dispatchers; a plain `var`
 * would lose increments and hand two rooms one id, which is the very bug the counter exists to fix.
 */
private val roomSequence = atomic(0L)

/**
 * Factory for a **host-side** [JoinerReconnectController], invoked once when a host room starts
 * with the room-owned [roomId], [scope], and clock — the three inputs a controller needs but that
 * only exist after the seam is woven (the `roomId` is minted against the host's woven `selfId`), so
 * a caller cannot pre-build the instance and must supply this lambda instead.
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
    override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room {
        val seam = loom.host(pattern)
        val resolvedRoomId = roomId ?: mintRoomId(seam)
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
            roomId = resolvedRoomId,
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
     * (null → peer-id-derived).
     *
     * [reweave] threads resume-after-tear into the adopted room (#1618). When null (the default) a
     * joiner whose host link tears goes terminal ([MembershipEvent.HostLost]) — the pre-#1618
     * behaviour, correct for a seam that cannot heal. When the adopted seam **self-heals in place**
     * (a fabric that re-forms `Woven → Weaving → Woven` on peer loss rather than latching
     * [us.tractat.kuilt.core.SeamState.Torn] — e.g. a redialing radio loom), pass `reweave = { seam }`
     * so a transient blip runs the [JoinerResumeMachine] resume path (wait for `Woven`, re-present the
     * [ResumeToken]) instead of going straight to terminal. See the [SeamRoom] `reweave` KDoc for the
     * same-instance-heal contract this must satisfy.
     *
     * [roomId] is this room's identity ([Room.roomId]), and applies to a **host** adopt only — a
     * joiner learns the room's id from the host's `Welcome`, so a value passed with
     * [SessionRole.Joiner] is ignored rather than rejected (the election lobby adopts with a role
     * decided at runtime, so a caller cannot know its side in advance). Null — the default — mints a
     * fresh one per adopt, which is what stops two rooms one device adopts in a row from colliding
     * (#1594). Supply it to keep **one identity across a host restart**: a host that returns under a
     * freshly minted id invalidates every outstanding [ResumeToken], since the host refuses a token
     * naming any other room.
     */
    public suspend fun adopt(
        seam: Seam,
        role: SessionRole,
        memberName: String? = null,
        roomKey: String? = null,
        reweave: (suspend () -> Seam)? = null,
        roomId: RoomId? = null,
    ): Room {
        // A joiner is never given one — it learns the room's identity from the host's Welcome, so a
        // supplied id here would be a claim this peer is not entitled to make. Ignored, not
        // rejected: the election lobby adopts with a role decided at runtime, so a caller that wants
        // a stable id cannot know in advance which side it will end up on.
        val resolvedRoomId = if (role == SessionRole.Host) roomId ?: mintRoomId(seam) else null
        return SeamRoom(
            seam = seam,
            role = role,
            memberName = memberName,
            scope = scope,
            clock = clock,
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            roomId = resolvedRoomId,
            roomKey = roomKey,
            reweave = reweave,
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

    /**
     * Mints a fresh [RoomId] for a room this factory is about to create — `"<selfId>-room-<ms>-<n>"`.
     *
     * Three parts, each earning its place:
     * - **`selfId`** keeps a log line readable: at a glance, whose room this is. It is also the part
     *   that was *all* there used to be, which is exactly why every room one device hosted collided
     *   (#1594) — `selfId` is stable per device (a durable `DeviceIdStore` value in the field), so it
     *   identifies the host, never the room.
     * - **the clock** — the factory's already-injected [clock], so the id is deterministic under a
     *   virtual test clock and survives a process restart without a durable counter. Deliberately
     *   *not* a [kotlin.random.Random]: a required parameter would be a ~96-call-site mechanical
     *   diff, and a defaulted `Random.Default` would be un-injected randomness, which this repo bans.
     * - **the sequence** — the part that actually carries uniqueness when the clock cannot. Two rooms
     *   minted in the same millisecond, or under a frozen test clock, differ only here. It is
     *   **process-wide** rather than per-factory because two factories sharing one clock and one
     *   `selfId` — an ordinary test shape, and reachable in production wherever a peer builds a
     *   second factory over the same device identity — would otherwise both mint at `0`.
     *
     * Not a security boundary and not globally unique: it is unique among the rooms *this process*
     * mints, which is what [Room.roomId] promises. A caller that needs an identity to outlive the
     * process supplies its own via [host]/[adopt].
     */
    private fun mintRoomId(seam: Seam): RoomId =
        RoomId("${seam.selfId.value}-room-${clock().toEpochMilliseconds()}-${roomSequence.getAndIncrement()}")

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
 * Capacity of **one recipient's** relay lane (#1994; per-recipient since #2048). Deep enough to hold
 * several `Quilter` deltas in flight for that recipient, shallow enough that a wedged link cannot
 * accumulate unboundedly — past which [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST] sheds
 * that recipient's stalest frame, which anti-entropy heals.
 *
 * **The number did not change when the keying did, and that is the point.** Its own KDoc always
 * described a *per-recipient* depth ("several `Quilter` deltas in flight per recipient") while the
 * buffer it sized was shared by the whole room — so on an N-spoke star it was over-provisioned for
 * one wedged spoke and under-provisioned for N healthy ones at the same time. Keeping 64 per lane
 * makes the constant mean what it says. The cost is that the room's worst-case buffered frames are
 * now `64 × spokes` rather than 64; that is the honest price of isolation, it is still bounded, and
 * it is bounded by a quantity (the roster) the room already bounds — `relayLanes.keys` is a **subset
 * of** `admittedById.keys`, which the lane factories' membership check and `discardLanes` maintain
 * jointly. Without that check a departed peer's lane would survive its eviction and the bound would
 * be the roster *plus every peer that ever left*, i.e. no bound at all on a long-lived host.
 */
private const val RELAY_FORWARD_CAPACITY = 64

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
 * `channelViews`, `admitLanes`, `relayLanes`) is guarded by an atomicfu [reentrantLock]. The
 * joiner-side resume state (`resumeToken`, `pendingResume`, `reconnecting`, `reconnectJob`) lives in
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
     * The room identity this room is **born with**. Non-null for hosts (minted — or supplied by the
     * caller — at room creation); null for joiners, which learn it from the host's
     * [AdmitMessage.Welcome].
     *
     * Deliberately a plain constructor parameter, not a property: the property of this name is the
     * [Room.roomId] level, which a joiner *moves* on admission. This seeds it.
     *
     * Defaults to null so existing tests that construct [SeamRoom] directly still compile.
     * [SeamRoomFactory] always passes the host-generated id explicitly.
     */
    roomId: RoomId? = null,
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
     * This room's identity, seeded from the constructor parameter of the same name.
     *
     * A host is born knowing it. A joiner starts null and moves **once**, in [handleWelcome], when
     * the host's `Welcome` carries the id — the same frame that mints the [ResumeToken], so the
     * token and this level always name the same room.
     */
    private val _roomId = MutableStateFlow(roomId)
    override val roomId: StateFlow<RoomId?> = _roomId.asStateFlow()

    /**
     * The id this room was **constructed** with — the host's minted-or-supplied [RoomId], or null
     * for a joiner. Distinct from [roomId], which a joiner moves on admission; this one never
     * moves, so it is what the host-only [reconnectController] is built from.
     */
    private val constructedRoomId: RoomId? = _roomId.value

    /**
     * Guards every mutation of the plain membership state:
     * `admittedById`, `closed`, `hostLost`, `hostPeerId`, `incomingCollectJob`,
     * `detectorJobs`, `channelViews`, `admitLanes`, `relayLanes` — and, shared with
     * [JoinerResumeMachine] (which is handed this same instance), the joiner-side resume state.
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

    /**
     * A **zero-lag** view of the seam's availability, NOT a mirrored copy.
     *
     * A `MutableStateFlow` written by [localFabricLoop] would lag [Seam.capability] by one
     * collector dispatch, and the sites that read this level run on other coroutines — so a radio
     * death could be reported alongside a level still reading `Available`, the headline #1712 case
     * exactly inverted. Projecting the source directly makes every reader and the level agree by
     * construction.
     */
    override val localFabric: StateFlow<FabricAvailability> = MappedAvailability(seam.capability)

    /**
     * [Seam.capability]'s availability as read at **construction** — [localFabricLoop]'s seed.
     *
     * Captured here rather than at loop start so a drop landing in the construction → [start]
     * window still produces a [MembershipEvent.LocalFabricLost]: re-reading the seam at loop start
     * would find `Unavailable` already in place and mistake an unannounced transition for one that
     * had been reported.
     */
    private val constructionAvailability: FabricAvailability = seam.capability.value.availability

    /**
     * Emit a [MembershipEvent] on [events], logging it first (#1618 presence/partition diagnostics).
     *
     * Every membership transition this room announces — `Joined`/`Partitioned`/`WindowOpened`/
     * `Recovered`/`Left`/`HostLost`/… — flows through here, so a single off-device log stream names each
     * event with its peer id, reason, and (for `WindowOpened`) `expiresAt`, via the data-class
     * `toString()`. That is the artifact that makes an NW-mesh presence failure legible without
     * re-running the session: if the host never logs `Partitioned`/`WindowOpened` on a Wi-Fi drop, the
     * break is upstream (detector never started / heartbeat frames never routed), not in event fan-out.
     * Behaviour is unchanged — this is [MutableSharedFlow.tryEmit] with a preceding log.
     */
    private fun emitEvent(event: MembershipEvent) {
        logger.info { "room.event self=${selfId.value} role=${_role.value} $event" }
        _events.tryEmit(event)
    }

    /**
     * One-shot-per-sender latch (#1618): peers from which this room has already logged a first inbound
     * heartbeat frame. Keeps the every-5s ping/pong traffic out of the log while still proving, once,
     * that [seam] delivered a heartbeat frame stamped with that sender. Touched only under [lock].
     */
    private val heartbeatSendersSeen = mutableSetOf<PeerId>()

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

    /**
     * Every channel frame this room should hand to a [RoomChannelSeam] — **both** the ones that
     * arrived directly and the ones the host relayed on a co-spoke's behalf.
     *
     * ## Why this is a second stream rather than more of [rawIncoming]
     *
     * [rawIncoming] would otherwise feed two consumers with different needs: [RoomChannelSeam.incoming],
     * which must see relayed channel frames, and [PerPeerSeam], which feeds each peer's
     * [HeartbeatPartitionDetector] — and that detector treats **any** inbound frame as proof of
     * liveness. Emitting a relayed payload into [rawIncoming] stamped with its origin would let A's
     * relayed *data* refresh B's detector for A.
     *
     * On a pure star that is inert (a joiner has no detector for an unroutable co-joiner), but the
     * send rule relays **everything** once the roster diverges — so on a partial-mesh, composite or
     * tiered topology where B does hold a direct edge to A, a dead A↔B link would be masked by
     * relayed traffic and never mature into [PartitionEvent.PeerUnresponsive]. That is the exact
     * inverse of the documented carve-out: **data is relayed; liveness is not** (#1592/#1576).
     *
     * ## Why the union is made here, by two producers, and never by `merge` (#2104)
     *
     * The obvious spelling of "a channel view sees both streams" is to hand the view
     * `merge(rawIncoming, relayedIncoming)`. It is wrong, and silently: `merge` subscribes to its
     * sources from child coroutines it **launches**, so a collector's subscription lands a dispatch
     * turn after its own coroutine first runs, whereas collecting a [SharedFlow] registers the slot
     * synchronously on first collect. Both streams are `replay = 0`, so every frame emitted inside
     * that widened window is dropped — and for a [us.tractat.kuilt.quilter.Quilter] that means its
     * first delta is lost and the peer converges only via the ~30 s anti-entropy backstop. #2026
     * shipped exactly that; #2104 is the report.
     *
     * Making the union at the **producers** removes the question rather than tightening the window:
     * this flow is live from field initialisation, so there is no subscription for a collector to
     * race. Keep it that way — do not reintroduce a combinator between this field and
     * [RoomChannelSeam], and keep that constructor parameter typed [SharedFlow] so the requirement
     * stays visible at the call site instead of living only in this paragraph.
     */
    private val channelIncoming = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)

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
        if (role == SessionRole.Host && constructedRoomId != null) {
            // Caller-supplied hold policy (#1614) if injected; else the standard fixed-window default.
            reconnectControllerFactory?.invoke(constructedRoomId, scope, clock)
                ?: DefaultJoinerReconnectController(
                    roomId = constructedRoomId,
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
                        // The level, not just the edge (#1723): without this the joiner's roster
                        // reported its host Connected while these two events said Partitioned, and a
                        // subscriber arriving after the edge could recover the state from neither
                        // surface. [hostId] is in [admittedById] on an admitted joiner — the same
                        // lookup [restoreHostDetector] does. Genuine lock acquisition, not a
                        // re-entry: JoinerResumeMachine.runReconnect calls this outside its own
                        // critical sections (the lock is reentrant either way).
                        val wasPartitioned = lock.withLock {
                            val existing = admittedById[hostId]?.liveness as? Liveness.Partitioned
                            updateMemberLiveness(
                                hostId,
                                Liveness.Partitioned(
                                    // First detection, preserved — see the [Liveness.Partitioned.since]
                                    // contract. Reachable double-detection, and the real-hardware
                                    // ordering: the radio dies (heartbeat Timeout → markPartitioned)
                                    // and only then does the socket notice (Torn → here). Overwriting
                                    // would drift `since` past the single Partitioned event a consumer
                                    // heard from the first detection.
                                    since = existing?.since ?: at,
                                    // The deadline, by contrast, genuinely MOVED: the resume machine's
                                    // withTimeoutOrNull budget runs from `at`, so [windowDeadline] is
                                    // the instant the seat is actually held to, and it is announced
                                    // immediately below — level and event cannot disagree.
                                    windowExpiresAt = windowDeadline,
                                ),
                            )
                            existing != null
                        }
                        // Idempotent like [markPartitioned]'s: the PARTITION is announced once, on the
                        // first detection. Without this gate the real-hardware Timeout-then-tear
                        // ordering emits Partitioned twice for one outage, so a consumer treating it as
                        // an edge — start a countdown, log a disconnect, bump a metric — double-counts.
                        // It is also what keeps [Liveness.Partitioned.since]'s contract literally true:
                        // `since` agrees with the single Partitioned event because there is only one.
                        if (!wasPartitioned) {
                            emitEvent(
                                MembershipEvent.Partitioned(
                                    hostId,
                                    at,
                                    ReconnectReason.TransportClosed,
                                    localFabric = localFabric.value,
                                ),
                            )
                        }
                        // WindowOpened stays UNCONDITIONAL — the deadline really did move on the tear,
                        // and a moved deadline must always be announced or a consumer counts down to a
                        // number it can no longer see is stale (the #1777 lesson, same reason as
                        // markPartitioned's).
                        emitEvent(MembershipEvent.WindowOpened(hostId, windowDeadline))
                    }

                    override fun onNoOpResume(hostId: PeerId, at: Instant) {
                        // #1637. The episode closed on the resume machine's dwell, not on a
                        // ResumeAck — the host never partitioned us, so [handleResumeAck] (which
                        // is where a real resume resets liveness and emits its closing edge) never
                        // runs. [markRecovered] does exactly the two things that are owed here:
                        // clear the Partitioned level [onReconnectStarted] applied, and announce
                        // it. Its host-only [propagateUnpaused] branch is unreachable from a
                        // joiner, and its `wasPartitioned` gate makes a double-close silent.
                        //
                        // Recovered(hostId), NOT Resumed(selfId): the arc opened on the HOST, so
                        // the closing edge must name the host or an edge-keying consumer cannot
                        // match the two. It is also the literally true event — "a partitioned
                        // peer's link recovered before the window expired" — where `Resumed` would
                        // claim a resume that did not happen.
                        markRecovered(hostId, at)
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
        // No fan-out writer is launched here any more (#2048): both queues are keyed by [PeerId], so
        // a lane and its writer are created together, under [lock], by the first enqueue for that
        // recipient ([admitLaneFor] / [relayLaneFor]). The #1781 ordering guarantee does not depend on
        // when a writer starts — the lane exists before the frame that created it is enqueued, and an
        // admit lane is unbounded, so anything queued before the writer is first dispatched is
        // drained, not lost.
        //
        // Lane writers are deliberately NOT in `loopJobs`: [leave] closes each lane rather than
        // cancelling its writer, so the loop completes on its own when the lane drains rather than
        // dying mid-item, and frames already enqueued may still be attempted before the seam tears —
        // exactly what the per-call `scope.launch`es this queue replaced did, since [leave] never
        // cancelled those either. (Only *may*: see the close site in [leave].) Otherwise they die
        // with [scope], or with their recipient — see [discardLanes].
        val jobs = mutableListOf(
            scope.launch { runMainLoop() },
            scope.launch { runTornWatcher() },
            scope.launch { runDetectorRouteWatcher() },
            // Deliberately outside every role gate: self-reachability is a fact about this peer's
            // own end of the fabric, so a host needs it exactly as much as a joiner does.
            scope.launch { localFabricLoop() },
        )
        if (reconnectController != null) {
            jobs += scope.launch { runReconnectEventLoop(reconnectController) }
        }
        loopJobs = jobs
    }

    // ── Local fabric: this peer's own reachability, as a level plus edges ──────

    /**
     * Fold [Seam.capability] into [localFabric]'s edges. Because [localFabric] projects the source
     * directly rather than mirroring it, the level is already current by the time an edge is emitted,
     * so it can never be *staler* than an edge. It can be **ahead** of one — events are buffered, so
     * under a rapid flap a consumer may read a newer level than the edge it is handling. See
     * [Room.localFabric] for the guarantee as consumers should rely on it (#1712).
     *
     * [Seam.capability] is a [StateFlow], not `incoming`, so collecting it here does not contend
     * with the ADR-034 single-collection contract.
     */
    private suspend fun localFabricLoop() {
        // Seeded from the value captured at CONSTRUCTION, not at loop start — see
        // constructionAvailability. `lastDecided` holds the last availability that *decided*
        // something (Available or Unavailable); Unknown never decides, so it seeds as null.
        var lastDecided: FabricAvailability? = constructionAvailability
            .takeIf { it !is FabricAvailability.Unknown }
        var previous: FabricAvailability = constructionAvailability
        seam.capability.collect { cap ->
            val next = cap.availability
            // The source conflates on the whole TransportCapability, so a role-only change can
            // re-deliver an unchanged availability. This is a FAST PATH, not a correctness guard:
            // review enumerated the fold's whole state space and `lastDecided` already suppresses
            // every duplicate edge on its own (Unavailable is gated on `lastDecided !is Unavailable`,
            // Available on `lastDecided is Unavailable`, Unknown decides nothing). Kept because it
            // states the intent at the point of delivery — but if you change the fold, `lastDecided`
            // is the invariant to preserve, and note that no test distinguishes this line's presence.
            if (next == previous) return@collect
            previous = next
            // No level write here — `localFabric` reads the source directly and is already current.
            when (next) {
                is FabricAvailability.Unavailable ->
                    if (lastDecided !is FabricAvailability.Unavailable) {
                        emitEvent(MembershipEvent.LocalFabricLost(clock(), next.reason))
                        lastDecided = next
                    }

                is FabricAvailability.Available -> {
                    if (lastDecided is FabricAvailability.Unavailable) {
                        emitEvent(MembershipEvent.LocalFabricRestored(clock()))
                    }
                    lastDecided = next
                }

                // Level only. lastDecided deliberately unchanged, so a recovery THROUGH Unknown
                // still restores.
                is FabricAvailability.Unknown -> Unit
            }
        }
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
     * - [JoinerReconnectEvent.WindowOpened] → [refineWindow], **not** an unconditional
     *   [MembershipEvent.WindowOpened]. [markPartitioned] already announced the window inline, so
     *   this is a refinement: it goes silent when the controller picked the same deadline — which
     *   [DefaultJoinerReconnectController] always does — and moves the level, announces, *and*
     *   re-fans the authoritative `Paused` when it did not. It used to be the *only* emitter, and
     *   that was the bug: the controller opens the window from a `scope.launch`, so its event reached
     *   this loop's `replay = 0` [kotlinx.coroutines.flow.SharedFlow] at an unpredictable later
     *   instant — discarded outright when this loop had not yet subscribed (#1618 Drop B) — and a
     *   joiner has no controller at all, so it never got a window (#1724).
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
                    refineWindow(event.peerId, Instant.fromEpochMilliseconds(event.expiresAt))
                is JoinerReconnectEvent.Resumed ->
                    handleReconnectResumed(event.peerId)
                is JoinerReconnectEvent.WindowExpired -> {
                    // Remotely, the expiry needs the same authoritative fan-out a clean leave gets,
                    // or a peer with no heartbeat edge against the expired member (a star topology's
                    // other joiners) never evicts it (#1557).
                    propagateFarewell(event.peerId, expired = true)
                    // Locally the detector's PeerLost is normally the evictor, and Left(PartitionExpired)
                    // follows from it. But PeerLost is the SOLE evictor, and on a real transport the
                    // detector can stall in Partitioned and never mature to PeerLost — the host then
                    // sticks in Partitioned forever, never emitting Left (#1618 Track C). The reconnect
                    // window is an independent timer; when it expires with the member STILL Partitioned,
                    // back-stop the eviction here. Idempotent against a later PeerLost.
                    evictOnExpiredWindowIfPartitioned(event.peerId)
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
        emitEvent(MembershipEvent.Resumed(updated.id))
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
        emitEvent(MembershipEvent.AdmissionFailed(reason, at))
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
                    // Channel views collect [channelIncoming], never [rawIncoming] — this is the
                    // direct half of the union that field's KDoc describes. Suspending `emit`, not
                    // `tryEmit`: this path can back-pressure, and dropping a directly-delivered
                    // frame here would be a strictly worse contract than the one it replaced.
                    if (RoomChannel.isChannelFrame(swatch)) channelIncoming.emit(swatch)
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
                //
                // #1618 suspect 2 diagnostic: log the FIRST heartbeat frame seen from each sender (once
                // per sender — the every-5s ping/pong traffic stays out of the log). This fires at the
                // point BEFORE PerPeerSeam's `sender == targetPeerId` filter, so it proves the NwSeam
                // stamped this heartbeat frame with a concrete `sender` and delivered it to rawIncoming.
                // Read against detector.first-inbound: if the room logs a heartbeat frame from peer X here
                // but that peer's detector never logs first-inbound, the break is the PerPeerSeam filter /
                // sender mismatch; if this line never fires for X at all, NwSeam is not delivering X's
                // heartbeats to the room.
                val firstFromSender = lock.withLock {
                    if (heartbeatSendersSeen.add(sender)) detectorJobs.containsKey(sender) else null
                }
                if (firstFromSender != null) {
                    val kind = when {
                        bytes.decodeToString().startsWith(HeartbeatPartitionDetector.PONG_PREFIX) -> "pong"
                        else -> "ping"
                    }
                    logger.debug {
                        "room.heartbeat.first-from sender=${sender.value} self=${selfId.value} " +
                            "kind=$kind hasDetector=$firstFromSender"
                    }
                }
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
            // Fires BEFORE the `isAdmittedPeer` arm below, so it inherits none of that arm's
            // gating — which is precisely why [handleRelayFrame] carries its own admission check.
            // This is the "an earlier guard un-pins an older test" shape; the two guards are
            // mutated in combination, not separately.
            RelayEnvelope.isRelayFrame(bytes) -> handleRelayFrame(sender, bytes)
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
                    // Protocol-version gate (#1569, tightened for #1994). A joiner declaring a
                    // version outside this build's supported range is refused at admit time with a
                    // terminal ProtocolMismatch — better than completing the handshake and failing
                    // later on a frame neither side can decode. Since v2 a version-LESS Hello is
                    // refused too: no declared version means the peer predates #1569 and therefore
                    // cannot relay, which is exactly the population the bump excludes. See
                    // ProtocolVersion's KDoc, including why a pre-#1569 HOST is undefendable.
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
            roomId = _roomId.value?.value,
        )
        val welcomeBytes = AdmitMessage.encode(welcome)

        // All sends below are outside the lock — they are suspend calls.

        // Send welcome directly to the joiner. Stays INLINE — it addresses the joiner, not the
        // bystanders, so it shares no recipient with any queued fan-out and has no order to keep
        // against one. Same for the two joiner-directed loops below.
        runCatchingCancellable { seam.sendTo(joinerPeerId, welcomeBytes) }

        // Tell the existing members about the joiner (roster sync) — through the per-recipient
        // fan-out lanes (#1781/#2048), not a loop of direct sends. This is a host-authoritative
        // membership announcement to bystanders, structurally identical to propagateFarewell, so it
        // needs the same ordering: a slow bystander link could leave `Welcome(X)` in flight while X
        // drops and its window expires, and the `Farewell(X)` queued behind it on THAT bystander's
        // lane would then arrive FIRST. handleFarewell removes a peer that bystander does not hold
        // yet — a no-op — and the late Welcome then ADDS X to its roster. On a
        // star that bystander has no heartbeat edge to X, so it holds a ghost member forever with no
        // anti-entropy to correct it. `fanOutToOtherMembers` re-snapshots the same recipient set
        // (`existingMembers` is `admittedById` minus the joiner, taken moments earlier under the same
        // lock) and adds the terminal-room gate, so this is a strict improvement, not a trade.
        fanOutToOtherMembers(joinerPeerId, welcome)

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
     * The **first** Welcome this member accepts identifies its host: every Welcome is minted by
     * the host, so that first sender is the host, and it is recorded for [HostLost] detection
     * (#1994). Waiting for the self-introduction shape instead left the host unidentified across
     * the whole admit burst — see the comment on the assignment below, which also states what
     * that narrowing costs on a flat loom.
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

            // Identify the host from the FIRST Welcome we accept, whatever its shape (#1994).
            //
            // Any Welcome is by definition minted by the host, so the sender of the first one IS
            // the host. Keying on the self-introduction instead (`assignedId == sender`) left
            // `hostPeerId` null across `admitPeer`'s whole K+1-send burst — the roster-sync
            // Welcome, one bootstrap Welcome per pre-existing member, and only THEN the host
            // intro (`SeamRoom.kt:1250-1270`) — while `addToRoster` had already run under lock
            // before the first of those sends (`:1215-1219`). Across that window a joiner holds
            // co-members with no identified host, which is both:
            //   * the capture window for a forged host identity, and
            //   * a transient re-run of #1994 itself: a Quilter collecting `rosterPeers` fires
            //     onPeersChanged -> sendFullStateTo(coJoiner) into PeerNotConnected.
            //
            // This does NOT weaken the #1180 gate above — it strengthens it, by arming it one
            // send earlier. Trade, stated honestly: on a *flat* loom a foreign host whose Welcome
            // arrives first now captures via any Welcome shape rather than only a self-intro. The
            // real host's Welcomes are then rejected by the gate, so that surfaces as a failed
            // join rather than a silent takeover; and on the star fabrics this track targets a
            // joiner has exactly one edge, so it is unreachable there.
            if (establishedHost == null) hostPeerId = sender

            // Learn the room's identity from the same frame that mints the resume token, so
            // [Room.roomId] and [ResumeToken.roomId] can never name different rooms (#1594). Placed
            // above the self-admission branch to cover BOTH Welcome shapes a joiner accepts, exactly
            // as the two mintTokenIfAbsent calls below do. Only the self-admission Welcome actually
            // carries a roomId today — bootstrap and host-intro Welcomes send null — so this is
            // idempotent on the rest rather than dependent on which arrives first.
            adoptRoomIdIfAbsent(welcome.roomId)

            // Self-admission welcome: mint the resume token (once) from the roomId carried here.
            if (assignedId == selfId) {
                resumeMachine?.mintTokenIfAbsent(welcome.roomId)
                // The host explicitly admitted us — disarm the admit deadline (#1178).
                admitted.complete(Unit)
                return@withLock
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
     * **Joiner only.** Record the host's [RoomId] the first time a `Welcome` carries one.
     *
     * Write-once, mirroring [JoinerResumeMachine.mintTokenIfAbsent]: the id is the room's identity
     * for its whole life, so a later frame must not be able to move it. A host never reaches here —
     * its level is non-null from construction, so the `== null` guard is already false.
     *
     * Callers hold [lock]; the write itself is to a [MutableStateFlow] and needs no further guard.
     */
    private fun adoptRoomIdIfAbsent(value: String?) {
        if (value != null && _roomId.value == null) _roomId.value = RoomId(value)
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
        emitEvent(MembershipEvent.Resumed(selfId))
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
     *
     * Goes through [fanOutToOtherMembers] — i.e. each recipient's own [admitLanes] writer — like
     * every other admit fan-out, so at any given member a `Farewell` can never overtake the `Paused`
     * for the same peer (#1781). A FIFO that half the announcements bypass is not a FIFO; this
     * method previously hand-rolled its own copy of the roster-snapshot-then-`scope.launch` shape,
     * which is exactly what created the ordering hole. Roster snapshot still under [lock], sends
     * still outside it.
     */
    private fun propagateFarewell(departed: PeerId, expired: Boolean = false) {
        fanOutToOtherMembers(departed, AdmitMessage.Farewell(departed.value, expired))
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
     * **#1994's relay does not widen this gate**, and the narrowness is the point: the relay moves
     * *data* between spokes at the room layer ([broadcast] / [sendTo]), while a detector sends
     * through [PerPeerSeam], which delegates straight to [seam]. Data is relayed; liveness is not —
     * see [relayedIncoming] for the inbound half of the same carve-out, and
     * `docs/fabric-peer-routing.md`.
     *
     * The gate is keyed on [Seam.peers] membership rather than on catching
     * [us.tractat.kuilt.core.PeerNotConnected]. The original reason — that `TieredSeam.sendTo`
     * *silently dropped* a peer owned by neither tier — is **no longer true**: #1935 is closed and
     * `TieredSeam` now throws. The gate's design is still right, for a better reason: a membership
     * test is a *positive* statement about reachability, while an exception-keyed check infers it
     * from a failure and so cannot distinguish "no route" from "route, send failed".
     *
     * Kept as a worked example of the stale-citation hazard: a claim tied to an issue number
     * silently inverts when that issue is fixed. Verify before resting an argument on one.
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
        if (member.id in detectorJobs) return // steady-state re-evaluation — already monitored, silent
        if (member.id !in seam.peers.value) {
            // #1618 route-gate (#1576) diagnostic: this member is admitted to the roster but the fabric
            // has NOT (yet) reported a route to it in seam.peers, so NO partition detector is started —
            // the member's Wi-Fi drop would then produce no PeerUnresponsive/Partitioned at all. On the
            // NW mesh this is the prime suspect: if this line logs for a peer that is nonetheless in the
            // roster, the break is that NwSeam never surfaced the peer in `peers` (or surfaced then
            // dropped it), not the session heartbeat path. Re-evaluated on every seam.peers emission, so
            // debug-level keeps a persistently-ungated peer from spamming.
            logger.debug {
                "detector.gated peer=${member.id.value} self=${selfId.value} " +
                    "reason=not-in-seam.peers seamPeers=${seam.peers.value.map { it.value }} " +
                    "roster=${admittedById.keys.map { it.value }}"
            }
            return
        }
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
            logger.debug { "detector-collector.start peer=${member.id.value} role=${_role.value}" }
            detector.events.collect { event ->
                if (event is PartitionEvent.PeerLost) {
                    logger.info {
                        "detector-collector.received PeerLost peer=${member.id.value} role=${_role.value} at=${event.at}"
                    }
                }
                handlePartitionEvent(event)
            }
        }
        detectorJobs[member.id] = detectorJob
        // #1618 suspect 1: the per-peer detector is now live. If this logs for every admitted mesh peer,
        // detector startup is NOT the break — attention moves to whether healthy pongs keep it alive
        // (suspect 2) and whether the drop fires PeerUnresponsive.
        logger.info {
            "detector.start peer=${member.id.value} self=${selfId.value} " +
                "seamPeers=${seam.peers.value.map { it.value }} roster=${admittedById.keys.map { it.value }} " +
                "config=[interval=${heartbeatConfig.interval} timeout=${heartbeatConfig.timeout} " +
                "window=${heartbeatConfig.reconnectWindow}]"
        }
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

    /**
     * Test-visibility: the current reconnect episode's most recent host `Reject` of a resume, or
     * null when this joiner has not been refused.
     *
     * Exposed for [us.tractat.kuilt.session] tests that assert a refusal *loop* describes itself —
     * the joiner retries every [us.tractat.kuilt.liveness.HeartbeatConfig.interval] for the whole
     * reconnect window, and before #1637's post-mortem that burned its entire budget silently. No
     * production caller reads this; production observes the same record as the `resume.refused`
     * INFO line the machine logs alongside it.
     */
    internal fun lastResumeRefusal(): ResumeRefusal? = resumeMachine?.lastRefusal()

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
        val isHost = lock.withLock {
            _role.value == SessionRole.Joiner && event.peerId == hostPeerId
        }
        val hostTransportClose = isHost && event.reason == PartitionEvent.Reason.TransportClosed
        logger.info {
            "membership.unresponsive peer=${event.peerId.value} reason=${event.reason} " +
                "isHost=$isHost branch=${if (hostTransportClose) "resume" else "markPartitioned"}"
        }
        // #1618 suspect 2/3: the detector fired PeerUnresponsive — name the peer, the reason
        // (Timeout / TransportClosed / Backpressure), and which fork handles it (host-resume vs
        // markPartitioned). This is the pivot the whole diagnosis turns on: it proving the drop was
        // detected at all is the difference between "the heartbeat path works" and "the peer vanished
        // silently". Its ABSENCE on a Wi-Fi drop (with a detector.start logged earlier) is the signal
        // that pongs never reached the detector.
        logger.info {
            "detector.unresponsive peer=${event.peerId.value} self=${selfId.value} " +
                "reason=${event.reason} fork=${if (hostTransportClose) "host-resume" else "markPartitioned"}"
        }
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
     * **Idempotent** — a member already partitioned emits no second [MembershipEvent.Partitioned]
     * and re-announces nothing. That matters on a mesh, where a peer both detects the drop locally
     * *and* receives the host's [AdmitMessage.Paused] for it (#1557): whichever arrives first wins,
     * the other is a no-op. The **host** re-arms its reconnect window on re-detection (it owns the
     * window); a non-host preserves the deadline it already holds rather than clobbering an
     * authoritative [AdmitMessage.Paused] with a fresh local estimate.
     *
     * [MembershipEvent.WindowOpened] is the exception to that idempotence: it is emitted on **every**
     * call, from the same `expiresAt` that sets the level. This is the *locally-detected* window's
     * emitter for both roles (#1724, #1618 Drop B) — see the comment at the emission site. A
     * deadline that arrives from whoever actually enforces the hold goes through [refineWindow]
     * instead, which announces too.
     *
     * On the **host** the announcement is also a [AdmitMessage.Paused] fan-out to the remaining
     * members — see [propagatePaused] for why local detection alone is not enough.
     */
    private fun markPartitioned(peerId: PeerId, at: Instant, reason: ReconnectReason) {
        // Hoisted above the role gate: previously computed only in the host-only propagatePaused
        // branch, so a joiner had no deadline at all (#1724). The level needs it on both roles.
        // Arithmetic in epoch-millis, converted once — same operands, same order, same truncation
        // as DefaultJoinerReconnectController.openWindow's `at + reconnectWindowMs`, so the
        // deadline on the level *is* the one the controller enforces.
        val localEstimate = Instant.fromEpochMilliseconds(
            at.toEpochMilliseconds() + heartbeatConfig.reconnectWindow.inWholeMilliseconds,
        )
        // Read the role once: the level and the fan-out must agree on which side owns the window.
        val isHost = _role.value == SessionRole.Host
        val (wasPartitioned, level) = lock.withLock {
            val current = admittedById[peerId] ?: return
            val existing = current.liveness as? Liveness.Partitioned
            // `since` is first-detection, so it agrees with the single MembershipEvent.Partitioned
            // emitted below; an idempotent re-detection must not drift it forward.
            //
            // The host owns the window for its joiners and genuinely re-arms it on re-detection, so
            // it always recomputes. A non-host must PRESERVE a deadline it already holds: that value
            // is either the host's authoritative Paused or its own earlier estimate, and a fresh
            // local estimate is no better than either (F4).
            val level = Liveness.Partitioned(
                since = existing?.since ?: at,
                windowExpiresAt = if (isHost || existing == null) localEstimate else existing.windowExpiresAt,
            )
            // Null only for a peer that is not admitted, already excluded above.
            updateMemberLiveness(peerId, level) ?: return
            (existing != null) to level
        }
        // localFabric read outside `lock` (which guards admittedById only) and off the zero-lag
        // projection, never a mirrored copy: this runs on the detector's coroutine, so a mirror
        // written by the capability collector could still read Available on a radio death (#1712).
        if (!wasPartitioned) {
            emitEvent(MembershipEvent.Partitioned(peerId, at, reason, localFabric = localFabric.value))
        }
        // WindowOpened is emitted UNCONDITIONALLY — deliberately outside the !wasPartitioned gate,
        // unlike Partitioned. `reconnectController.onPeerUnresponsive` below is also unconditional,
        // and `openWindow` cancels the existing timer and re-arms. Gating this on !wasPartitioned
        // would silently drop every re-announcement, leaving a consumer that missed (or was created
        // after) the first one counting down to a deadline it never learned.
        //
        // The announced deadline is read back off `level`, NOT from `localEstimate`: on a non-host
        // re-detection the level deliberately preserves the host's authoritative deadline, and
        // announcing the fresh local estimate there would tell every consumer the seat expires
        // EARLIER than it actually does.
        //
        // Inline, so no async hop can lose it. Previously the host's window crossed the controller's
        // replay-0 SharedFlow behind a `scope.launch` (discarded when runReconnectEventLoop had not
        // yet subscribed, #1618 Drop B) and the joiner got none at all (reconnectController is null
        // on a joiner, #1724). markPartitioned is role-agnostic, so one emission serves both.
        emitEvent(MembershipEvent.WindowOpened(peerId, level.windowExpiresAt))
        // Same single source of truth for the fan-out. This carries the room's HeartbeatConfig
        // estimate, which is right for the default controller and a placeholder for an injected one —
        // refineWindow re-fans the enforced deadline when the two differ.
        //
        // ORDER is guaranteed; DELIVERY is best-effort. Both fan-outs are enqueued on the SAME
        // recipient's admit lane (#1781/#2048) — they address the same member set, so this estimate
        // cannot arrive after refineWindow's refinement at any recipient and move a remote level
        // backwards. What is still best-effort is whether a given frame arrives at all:
        // a recipient that tears loses it, and `Paused` carries no episode identity,
        // so a LOST refinement leaves that member holding this estimate with no anti-entropy behind it
        // (the drop is logged for exactly that reason).
        //
        // ENQUEUED **BEFORE** `onPeerUnresponsive` BELOW, and that order is the whole guarantee. The
        // queue promises enqueue order == wire order; it cannot promise which of the two is enqueued
        // first. `onPeerUnresponsive` is the head of the refinement's path — the default controller
        // does `scope.launch { openWindow() }`, `openWindow` emits `WindowOpened`, and
        // runReconnectEventLoop collects it on a DIFFERENT coroutine and calls refineWindow ->
        // propagatePaused -> trySend. Called first, that path could win the race and the remote would
        // latch the estimate: exactly the inversion this queue exists to prevent, reintroduced one
        // line above the fix. Nor is the window nanoseconds wide — both paths contend on this same
        // BLOCKING `lock` (refineWindow's, then fanOutToOtherMembers' own), so a collector that
        // reaches it first blocks this coroutine's thread while the refinement encodes and enqueues
        // ahead of it. With the enqueue hoisted, `trySend(estimate)` has returned before the
        // refinement can be *conceived*, so the ordering is structural rather than probable.
        //
        // Nothing below depends on the old order: `propagatePaused` reads only `level` (already
        // committed under the lock above) and the roster, `emitEvent` is a non-suspending `tryEmit`,
        // and refineWindow's own gate reads the level this function already wrote.
        if (!wasPartitioned && isHost) {
            propagatePaused(peerId, expiresAtMs = level.windowExpiresAt.toEpochMilliseconds())
        }
        reconnectController?.onPeerUnresponsive(peerId, at.toEpochMilliseconds())
    }

    /**
     * **Either role.** Replace [peerId]'s reconnect deadline with a more authoritative number and
     * announce it — the single authority-hop for *"a better deadline arrived"*, from either source:
     *
     * - on the **host**, from its [JoinerReconnectController]'s [JoinerReconnectEvent.WindowOpened]
     *   — the policy that actually enforces the hold ([runReconnectEventLoop]);
     * - on a **non-host**, from the host's [AdmitMessage.Paused] ([handlePaused]) — the host is the
     *   only holder of the enforced window, so its number supersedes a local estimate.
     *
     * One authority relationship, stated once: whoever enforces the window decides it, and a
     * locally-computed value is a placeholder until they speak. (The role-widening is a
     * documentation fix, not a behaviour change — the body was already role-agnostic; the fan-out
     * line below is the one role-conditional part.)
     *
     * [markPartitioned] computes its deadline from [HeartbeatConfig.reconnectWindow], which is what
     * the default [DefaultJoinerReconnectController] enforces — so for that controller this is a
     * no-op and no duplicate [MembershipEvent.WindowOpened] is announced. But the controller is
     * **injectable** (#1614) precisely so a host can implement a different hold policy — a
     * predicate/unbounded hold that keeps a seat while a durable rejoin record exists. Such a
     * controller is the *owner* of the window and its deadline can be arbitrarily far from
     * `at + reconnectWindow`.
     *
     * **It moves the level *and* announces, always.** Announcing is the load-bearing half: a
     * refinement that silently moved the roster deadline would leave the last
     * [MembershipEvent.WindowOpened] a consumer heard permanently false, counting it down to a
     * deadline the seat is no longer held to — the exact defect #1724 fixes on the other lanes, and
     * it does not become acceptable just because the roster is right. Both surfaces must be true so
     * that a consumer keying on *either* is right; the roster stays authoritative, the event stops
     * lying. The symmetric alternative (never announce a refinement, and document
     * `WindowOpened.expiresAt` as silently supersedable) would additionally cost #1614 its
     * observability — an injected hold policy's deadline would be discoverable only by polling
     * [Room.roster].
     *
     * On the **host** the refined deadline is also fanned out as an authoritative
     * [AdmitMessage.Paused], for the same reason [markPartitioned]'s is: without it a remote
     * member's roster keeps `at + reconnectWindow` forever while the host holds the seat to the
     * injected policy's deadline, so the two rosters disagree with no way to converge.
     *
     * **Order is guaranteed; delivery is not.** This fan-out and [markPartitioned]'s address the same
     * member set, so at each recipient both are enqueued on that recipient's one lane and the
     * estimate can no longer land *after* this refinement and
     * move a remote level backwards — that inversion was real on a multi-threaded dispatcher and is
     * now structurally impossible (#1781). It takes **two** properties, not one: the lane makes
     * enqueue order the wire order, and [markPartitioned] enqueues its estimate *before* it calls
     * `onPeerUnresponsive` — the head of this function's own call path. The queue alone would leave
     * which one is enqueued first a race, because that path runs on a different coroutine; see the
     * comment at that call site. What remains best-effort is whether either frame arrives:
     * a recipient that tears between the roster snapshot and the send loses it, and because `Paused`
     * carries no episode identity there is no anti-entropy to re-assert a *lost* refinement, so the
     * remote keeps the estimate. The drop is logged for that reason.
     * **No fan-out loop is possible:** only a host propagates, and the inbound-`Paused` caller
     * ([handlePaused]) is reached only when `role` is [SessionRole.Joiner] (see the dispatch gate in
     * `handleAdmitFrame`), so a member reacting to a `Paused` can never re-send one.
     *
     * A no-op unless [peerId] is currently [Liveness.Partitioned] — a window announcement arriving
     * after the member recovered or was evicted must not resurrect a stale deadline — and a no-op
     * when the deadline did not move.
     *
     * That partition guard is **narrower than it reads**, deliberately: it rejects a deadline for a
     * member that is not partitioned *now*, which covers "recovered, and still recovered" but not
     * "recovered, then partitioned again". This is the one half of #1781 the admit lanes do **not**
     * close, because the reordering happens *before* this function is reached rather than
     * on the wire after it: a controller's [JoinerReconnectEvent.WindowOpened] is emitted from its own
     * `scope.launch`, so an event for episode *N* can in principle land after
     * episode *N+1* opened and move that episode's level backwards. Theoretical — the
     * recovery→re-detection gap is at least one [HeartbeatConfig.timeout], orders of magnitude above
     * launch latency — and a cheap `expiresAt < since` test was considered and rejected as
     * *misleadingly* incomplete: with a window longer than that gap (the controller's 60 s default
     * is), the stale episode's deadline still lands after the new episode's
     * [Liveness.Partitioned.since]. A sound fix needs the detection instant carried on
     * [JoinerReconnectEvent.WindowOpened] so a mismatched episode can be dropped outright — a
     * public-API change, tracked in #1781 rather than smuggled in here.
     */
    private fun refineWindow(peerId: PeerId, expiresAt: Instant) {
        lock.withLock {
            val current = admittedById[peerId]?.liveness as? Liveness.Partitioned ?: return
            if (current.windowExpiresAt == expiresAt) return
            updateMemberLiveness(peerId, current.copy(windowExpiresAt = expiresAt)) ?: return
        }
        emitEvent(MembershipEvent.WindowOpened(peerId, expiresAt))
        if (_role.value == SessionRole.Host) {
            propagatePaused(peerId, expiresAtMs = expiresAt.toEpochMilliseconds())
        }
    }

    /**
     * Restore [Liveness.Connected] for [peerId] and announce it. Idempotent counterpart of
     * [markPartitioned]: a member that was not paused emits nothing and announces nothing.
     */
    private fun markRecovered(peerId: PeerId, at: Instant) {
        val (wasPartitioned, updated) = lock.withLock {
            val current = admittedById[peerId] ?: return
            val wasPartitioned = current.liveness is Liveness.Partitioned
            wasPartitioned to (updateMemberLiveness(peerId, Liveness.Connected) ?: return)
        }
        if (!wasPartitioned) return
        emitEvent(MembershipEvent.Recovered(updated.id, at))
        if (_role.value == SessionRole.Host) propagateUnpaused(peerId)
    }

    /**
     * Host-side: announce that [peerId]'s seat is held open until [expiresAtMs], by fanning an
     * authoritative [AdmitMessage.Paused] to every *other* member (#1557).
     *
     * Liveness is detected locally, per peer. On a mesh that suffices — every member watches
     * every other. On a star/host-relayed topology a member has no heartbeat edge to another
     * member, so the pause would be invisible to it and [MembershipEvent.Partitioned] would
     * silently mean different things on different topologies. Roster snapshot under [lock]; the send
     * is enqueued on each recipient's own lane, so *delivery* is best-effort (like
     * [propagateFarewell]) but *ordering* against every other announcement **to that recipient** is
     * not — see [admitLanes] (#1781/#2048).
     *
     * Two call sites, both host-side: [markPartitioned] on first detection (the room's
     * [HeartbeatConfig] estimate) and [refineWindow] when the enforcing controller's deadline turns
     * out to differ. A remote member treats a re-sent `Paused` for an already-paused peer as a
     * refinement rather than a no-op (#1724), so the second send lands rather than being dropped —
     * without it, an injected hold policy's deadline would never leave this host.
     */
    private fun propagatePaused(peerId: PeerId, expiresAtMs: Long) {
        fanOutToOtherMembers(peerId, AdmitMessage.Paused(peerId.value, expiresAtMs))
    }

    /**
     * Host-side release counterpart of [propagatePaused] — [peerId] recovered in-window.
     *
     * Shares [propagatePaused]'s queue, which is what makes it safe: [handleUnpaused] no-ops for a
     * member it does not currently hold as [Liveness.Partitioned], so an `Unpaused` that overtook its
     * own `Paused` would be discarded and the late `Paused` would then pin a **recovered** member
     * `Partitioned` in that remote roster forever, with nothing to correct it. Enqueue order is wire
     * order, so that inversion cannot happen (#1781).
     */
    private fun propagateUnpaused(peerId: PeerId) {
        fanOutToOtherMembers(peerId, AdmitMessage.Unpaused(peerId.value))
    }

    // ── Star relay (#1994) ────────────────────────────────────────────────────

    /**
     * What a relayed frame resolves to on this host — **the outcome, carried in the type**.
     *
     * The cases name *what happens*, not just *to whom*, because the host is a **recipient as well
     * as a router** and a resolver that only answers "which peers" cannot say so. [admittedById]
     * never contains [selfId] — `addToRoster` is called only for other peers — so a `Set<PeerId>`
     * resolver silently drops `One(host)` as unresolvable and fans `Everyone` past the host. That
     * would stop a joiner's frames reaching the host at all, and no mesh test could see it.
     *
     * Cardinality stays in the type: [Exactly] *cannot* hold two peers, and removing a branch from
     * the `when` that consumes this *cannot* compile. For per-recipient secrets (`:kuilt-deal`'s
     * card deals) the security property **is** cardinality, so it belongs here.
     */
    private sealed interface Resolved {
        /** Unknown, departed, or self-addressed-by-the-origin destination. Drop it. */
        data object None : Resolved

        /** Addressed to this host alone — deliver locally, forward to nobody. */
        data object SelfOnly : Resolved

        /** Forward to exactly one other member; not for us. */
        data class Exactly(val peer: PeerId) : Resolved

        /** Deliver locally **and** forward to [others] (which may legitimately be empty). */
        data class SelfAndEvery(val others: Set<PeerId>) : Resolved
    }

    /**
     * Whether a relayed payload may be honoured — an **allow-list**, deliberately not a deny-list.
     *
     * Honoured only if it is an explicit channel frame, or claims **no** registered prefix at all
     * (a plain application frame). That excludes admit, lobby, heartbeat and a nested
     * [RelayEnvelope] in one predicate — and excludes a *future* frame family by default rather
     * than requiring someone to remember it.
     *
     * **Why not "re-dispatch with a synthesized sender".** That re-enters [dispatchIncoming], which
     * routes any `0x61` payload to [handleAdmitFrame]. [handleWelcome] is host-authoritative only
     * *after* a host exists, so any admitted joiner could relay a crafted `Welcome` naming itself
     * and capture a co-joiner's `hostPeerId` — then drive every host-authoritative gate on the
     * victim. #1180 hardened that on a flat loom; the four star fabrics were protected by
     * *topology*, and a relay removes that protection on all of them.
     *
     * A relayed admit frame has no legitimate sender: the admit protocol is by construction
     * host↔joiner over the direct edge.
     *
     * **Classified by [RoomFramePrefix.classifies], never by `matches`.** The two planes must agree
     * byte-for-byte: whatever [dispatchIncoming] would deliver as application data on a direct edge
     * must survive the relay, or a frame that works on a mesh vanishes on a star with no error.
     * `matches` is a single-byte test and two of the five families are narrower than their byte —
     * a channel frame needs a 3-byte header, a heartbeat needs the whole `"kuilt.heartbeat.ping"`
     * string — so folding `matches` here silently dropped a spoke's `"keepalive"` broadcast (byte
     * `0x6b`) and every 1–2 byte payload leading with `0x63`, both of which the direct path routes
     * to [routeApplicationFrame]. Delegating to the registry's own classifiers keeps the allow-list
     * shape — a *future* family claims a byte and is excluded by default — without letting the
     * allow-list and the dispatcher drift apart.
     */
    private fun isRelayable(payload: ByteArray): Boolean =
        RoomChannel.isChannelFrame(payload) || RoomFramePrefix.entries.none { it.classifies(payload) }

    /** Host-side: forward and/or deliver one relayed frame, or drop it. */
    private fun handleRelayFrame(sender: PeerId, bytes: ByteArray) {
        if (_role.value != SessionRole.Host) {
            handleRelayedDelivery(sender, bytes)
            return
        }
        // FIRST gate. This arm fires before the `isAdmittedPeer(sender)` arm it precedes, so it
        // inherits none of that arm's gating and must carry its own: every other application-data
        // path in dispatchIncoming is admit-gated, and an ungated relay lets a peer that never
        // completed the handshake drive an N-recipient fan-out per frame.
        if (!isAdmittedPeer(sender)) {
            logger.debug {
                "room.relay.drop self=${selfId.value} from=${sender.value} reason=sender-not-admitted"
            }
            return
        }
        val envelope = RelayEnvelope.decode(bytes) ?: run {
            logger.debug { "room.relay.drop self=${selfId.value} from=${sender.value} reason=malformed" }
            return
        }
        // No trusted relayer tier exists at the room layer, so `trusted` is empty by construction
        // and the rule reduces to `origin == sender`: a spoke may speak only for itself. Shared
        // with :kuilt-cluster, which passes its voter core.
        if (!validFirstHop(sender = sender, origin = envelope.origin, trusted = emptySet())) {
            logger.debug {
                "room.relay.drop self=${selfId.value} from=${sender.value} " +
                    "origin=${envelope.origin.value} reason=origin-spoof"
            }
            return
        }
        if (!isRelayable(envelope.payload)) {
            logger.debug {
                "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                    "reason=not-relayable"
            }
            return
        }
        // Forwards carry the ORIGINAL bytes unchanged — `dest` is meaningful on this hop only, so
        // there is no per-recipient re-wrapping and `Everyone` stays `Everyone` on the wire.
        when (val resolved = resolveRecipients(envelope)) {
            Resolved.None ->
                logger.debug {
                    "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                        "dest=${envelope.dest} reason=unresolvable"
                }
            Resolved.SelfOnly -> deliverRelayedPayload(envelope)
            is Resolved.Exactly -> enqueueRelayForward(listOf(resolved.peer), bytes)
            is Resolved.SelfAndEvery -> {
                deliverRelayedPayload(envelope)
                if (resolved.others.isNotEmpty()) enqueueRelayForward(resolved.others.toList(), bytes)
            }
        }
    }

    /**
     * Resolve a relayed frame's destination against the current roster **and this host itself**.
     *
     * A destination the origin addressed to itself, or one naming a peer this room does not hold,
     * resolves to [Resolved.None] and is dropped — never widened into a fan-out, which is how a
     * unicast would leak.
     */
    private fun resolveRecipients(envelope: RelayEnvelope): Resolved = lock.withLock {
        if (closed) return@withLock Resolved.None
        when (val dest = envelope.dest) {
            RelayDest.Everyone ->
                // `others` may legitimately be empty (a 2-peer room): the host is still a
                // recipient, so this is SelfAndEvery(emptySet()), NOT None.
                Resolved.SelfAndEvery(admittedById.keys.filterTo(mutableSetOf()) { it != envelope.origin })

            is RelayDest.One -> when {
                dest.peer == envelope.origin -> Resolved.None
                dest.peer == selfId -> Resolved.SelfOnly
                admittedById.containsKey(dest.peer) -> Resolved.Exactly(dest.peer)
                else -> Resolved.None
            }
        }
    }

    /**
     * Deliver a relayed payload to this member's own consumers, stamped with the **origin**.
     *
     * Shared by the host (which is a recipient of anything addressed to it) and the joiner (after
     * its own gates in [handleRelayedDelivery]). Callers must have already applied [isRelayable].
     *
     * The two surfaces mirror [dispatchIncoming]'s own arms for these payload kinds — channel
     * frames to the channel views, plain application frames to [routeApplicationFrame] — but this
     * is a **narrow, explicit** re-implementation of exactly those two, deliberately *not* a call
     * back into [dispatchIncoming], which would restore the admit-frame path the allow-list exists
     * to remove.
     */
    private fun deliverRelayedPayload(envelope: RelayEnvelope) {
        if (RoomChannel.isChannelFrame(envelope.payload)) {
            val accepted = channelIncoming.tryEmit(Swatch(envelope.payload, sender = envelope.origin))
            if (!accepted) {
                // Relayed delivery is the one place weaker than direct delivery: the direct path
                // uses a suspending `emit` inside the collector, which this cannot. Absence has to
                // be diagnosable off-device (#1781).
                logger.debug {
                    "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                        "reason=inbound-buffer-full"
                }
            }
        } else {
            routeApplicationFrame(envelope.origin, envelope.payload)
        }
    }

    /**
     * Joiner-side: accept a frame the host relayed on a co-member's behalf.
     *
     * Four gates, each of which must independently hold:
     *
     * 1. **The sender is our identified host.** A relay frame from anyone else is a co-joiner
     *    injecting directly, which on a flat loom is reachable. Depends on `hostPeerId` being set
     *    from the *first* Welcome — see [handleWelcome].
     * 2. **`dest` names us.** The host already resolved this, but the leak boundary is re-checked
     *    at the far end rather than trusting the host's routing — cheap, and it means a misrouting
     *    host cannot silently widen a unicast.
     * 3. **The payload is relayable.** The same allow-list the host applied, applied again: a host
     *    is not trusted to have applied it.
     * 4. **The origin is an admitted member.** Otherwise the frame would be credited to a peer
     *    outside the roster, which the channel views' own `isAdmitted(sender)` filter would drop
     *    anyway — failing here keeps the reason loggable.
     */
    private fun handleRelayedDelivery(sender: PeerId, bytes: ByteArray) {
        val host = lock.withLock { hostPeerId }
        if (host == null || sender != host) {
            logger.debug {
                "room.relay.drop self=${selfId.value} from=${sender.value} " +
                    "host=${host?.value} reason=not-from-host"
            }
            return
        }
        val envelope = RelayEnvelope.decode(bytes) ?: run {
            logger.debug { "room.relay.drop self=${selfId.value} reason=malformed" }
            return
        }
        val addressed = when (val dest = envelope.dest) {
            RelayDest.Everyone -> true
            is RelayDest.One -> dest.peer == selfId
        }
        if (!addressed) {
            logger.debug {
                "room.relay.drop self=${selfId.value} dest=${envelope.dest} reason=not-addressed"
            }
            return
        }
        if (!isRelayable(envelope.payload)) {
            logger.debug {
                "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                    "reason=not-relayable"
            }
            return
        }
        if (!isAdmittedPeer(envelope.origin)) {
            logger.debug {
                "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                    "reason=origin-not-admitted"
            }
            return
        }
        deliverRelayedPayload(envelope)
    }

    /**
     * One queued relay forward, for **one** recipient: the original envelope bytes unchanged.
     *
     * [seq] is a gap-detector, not an ordering key — the lane's channel already preserves order. It
     * is assigned at enqueue from the owning [RelayLane]'s own counter, so a hole in the sequence
     * that lane's writer dequeues is *exactly* the set of items [BufferOverflow.DROP_OLDEST]
     * discarded **from that lane**. See [relayForwardsDropped].
     *
     * **Per-lane, not global (#2048).** A single room-wide counter cannot survive per-recipient
     * writers: each writer would dequeue only its own subsequence of it, and every frame stamped for
     * a *different* lane would read as a hole. The old code's counter was correct only because one
     * writer dequeued every item it stamped.
     *
     * [bytes] is shared by reference across the lanes of a multi-recipient forward — the frame is
     * encoded once by the origin and never rewritten (`dest` is meaningful on this hop only), so
     * fanning it out costs one small item per lane rather than a copy.
     */
    private class RelayForward(val bytes: ByteArray, val seq: Long)

    /** Total forwards discarded by relay-lane [BufferOverflow.DROP_OLDEST] overflow, across all lanes. */
    private val relayDropped = atomic(0L)

    /**
     * How many relay forwards this room has silently dropped to overflow.
     *
     * **Why this is counted at all.** [BufferOverflow.DROP_OLDEST] never fails a `trySend`, so the
     * overflow it exists to perform emits *nothing* — the one branch [enqueueRelayForward] can log
     * is a closed channel. A test could therefore "exercise" the bound with a flood and pass
     * identically against a build whose capacity was 4096, and an off-device report of missing
     * frames could not distinguish overflow from any other drop. `deliverRelayedPayload` already
     * argues this case for its own buffer: absence has to be diagnosable off-device (#1781).
     *
     * Exposed `internal` so a test can assert the drop *happened* rather than assuming a flood
     * caused one.
     */
    internal val relayForwardsDropped: Long get() = relayDropped.value

    /** Frames this room refused to put on the wire because their encoded size exceeded the fabric's. */
    private val oversizeDropped = atomic(0L)

    /** One-shot latch so the first oversize drop is a `warn` and the rest are `debug`. */
    private val oversizeWarned = atomic(false)

    /**
     * How many frames this room has dropped for exceeding the fabric's frame ceiling.
     *
     * **Why this is counted, and why the first one is a `warn`.** An oversize drop is *structural,
     * not weather*: the same payload is over the same ceiling every time, so unlike a partition it
     * never heals on its own. A `Quilter` whose anti-entropy frame outgrows the budget as state
     * accretes stops converging **permanently** — roster alive, heartbeats flowing, no data moving
     * — and at `debug` alone there is nothing above the noise floor to say so. This is the same
     * argument [relayForwardsDropped] makes for overflow ("absence has to be diagnosable
     * off-device", #1781), and the one `SeamRaftTransport` makes for logging its own over-budget
     * drop at `warn`: misconfiguration, not weather.
     *
     * Latched rather than repeated so a per-frame drop cannot flood the log; the count carried in
     * every line is what shows the scale.
     *
     * Exposed `internal` so a test can assert the drop *happened* rather than inferring it from
     * absent delivery.
     */
    internal val oversizeFramesDropped: Long get() = oversizeDropped.value

    /**
     * Count an oversize drop and log it — the first at `warn`, subsequent ones at `debug`.
     *
     * [to] is null for a frame this member originated as a broadcast; non-null when this member is
     * the host forwarding somebody else's envelope onward.
     */
    private fun recordOversizeDrop(reason: String, to: PeerId?, sizeBytes: Int, ceilingBytes: Int?) {
        val total = oversizeDropped.incrementAndGet()
        val first = oversizeWarned.compareAndSet(expect = false, update = true)
        val line: () -> String = {
            "room.send.drop self=${selfId.value} " + (to?.let { "to=${it.value} " } ?: "") +
                "reason=$reason size=$sizeBytes ceiling=$ceilingBytes dropped=$total"
        }
        if (first) logger.warn(line) else logger.debug(line)
    }

    /**
     * One recipient's relay lane: the frames waiting for that peer, and the writer draining them.
     *
     * **Separate from [admitLanes], deliberately.** That queue's growth analysis rests on *what
     * enqueues*: membership **transitions**, "on the heartbeat timescale rather than per-frame".
     * Relay traffic is exactly per-frame, so putting it there would invalidate the bound — with
     * [Channel.UNLIMITED] and a `reconnectWindow + timeout` budget, one black-holed spoke would
     * delay every `Paused`/`Unpaused`/`Farewell` *to that same peer* behind it, which is the
     * permanent roster divergence #1781 built that queue to prevent.
     *
     * The policies differ because the contents do. A dropped `Unpaused` pins a recovered member
     * [Liveness.Partitioned] in a remote roster **forever**, so that lane must never drop; a
     * dropped relay frame is loss the [Room] contract already documents (lossy-without-error on a
     * star) and that `Quilter` anti-entropy heals. So this one is **bounded** with
     * [BufferOverflow.DROP_OLDEST] — back-pressure is unavailable here for the same reason it is
     * there (enqueue happens from a non-suspending frame handler), and dropping the *oldest*
     * relayed frame under sustained overload is strictly better than growing without bound.
     *
     * [nextSeq] stamps [RelayForward.seq]; see there for why the counter is per-lane.
     */
    private class RelayLane(val queue: Channel<RelayForward>, val writer: Job) {
        /** Monotonic enqueue counter for **this lane**. First forward on the lane is `1`. */
        val nextSeq = atomic(0L)
    }

    /**
     * Relay lanes, one per recipient (#2048), created on that peer's first forward and torn down
     * when it leaves the roster. Guarded by [lock]; see [relayLaneFor] and [discardLanes].
     *
     * **Why keyed rather than shared.** A single bounded queue drained by a single writer gives a
     * wedged recipient two ways to hurt healthy ones, and the star relay (#1994) made both ordinary
     * rather than exotic. It *delays* them: the writer parks in the wedged `sendTo` for
     * [relaySendBudget], which is [HeartbeatConfig.interval] — 5 s on the shipped defaults — so one
     * black-holed spoke throttled the room's entire relayed data plane to ~0.2 forwards/second. And
     * past that it *evicts* them: [BufferOverflow.DROP_OLDEST] sheds whatever is oldest once the
     * backlog fills, which on a shared buffer includes frames bound for peers that are perfectly
     * healthy. The trigger needs no star topology at all — routing flips to the relay whenever
     * `rosterPeers ⊄ seam.peers`, whose dominant cause on a flat mesh is one member sitting in its
     * reconnect window (#1557/#1614), so a single transiently-partitioned peer could route every
     * other peer's traffic through the host for the whole window. With a lane per recipient, a
     * wedged peer's backlog can only ever cost that peer.
     */
    private val relayLanes = mutableMapOf<PeerId, RelayLane>()

    /**
     * The relay lane for [recipient], created (and its writer launched) on first use — or null when
     * [recipient] is no longer a member, or once the room is terminal.
     *
     * Lazy rather than created at admit time: a peer that is never relayed to costs nothing, and the
     * lane is by construction created before the first frame is enqueued on it, so per-recipient
     * order holds from the first frame without a start-up ordering rule.
     *
     * **The membership re-check is load-bearing, because the relay path takes [lock] TWICE.**
     * [handleRelayFrame] resolves its recipients under one acquisition ([resolveRecipients]), drops
     * the lock, and [enqueueRelayForward] takes it again to get here — so on a multi-threaded scope an
     * eviction can land in the gap, and without this line `getOrPut` would mint a lane, and a writer,
     * for a peer [removeFromRoster] has already reaped. Nothing would ever collect it: the eviction
     * that would have called [discardLanes] has been and gone, and a later duplicate eviction returns
     * early at `removed ?: return`. That is a leaked writer plus a 64-slot channel per departed peer
     * on a long-lived host, and — worse — a re-admit of the same [PeerId] would be handed the *stale*
     * lane, defeating exactly the fresh-lane property [discardLanes] exists to provide. This is not
     * reachable under a single-threaded test dispatcher (no suspension point separates the two
     * acquisitions), so the guard is not test-pinned and must not be "simplified" away.
     *
     * [admitLaneFor] carries the same check even though its one caller resolves membership and the
     * lane in a *single* critical section and so cannot reach it. The invariant
     * `lanes.keys ⊆ admittedById.keys` is worth having as a local property of the factory rather than
     * an emergent property of one caller's locking shape — which is the same reason this file guards
     * state with explicit primitives instead of relying on where coroutines happen to run.
     *
     * Callers must hold [lock] — the same discipline (and the same "launch a coroutine from inside
     * the critical section" shape) as [startDetector]. The launched body's first act is to receive
     * from an empty channel, so even an eager dispatcher runs it only as far as that suspension.
     */
    private fun relayLaneFor(recipient: PeerId): RelayLane? {
        if (closed || recipient !in admittedById) return null
        return relayLanes.getOrPut(recipient) {
            val queue = Channel<RelayForward>(
                capacity = RELAY_FORWARD_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            RelayLane(queue, scope.launch { runRelayForwardWriter(recipient, queue) })
        }
    }

    /**
     * Per-recipient deadline for one relay forward — [HeartbeatConfig.interval], **not**
     * [admitLanes]'s `reconnectWindow + timeout`.
     *
     * That budget is deliberately loose because an announcement stays meaningful for the whole span
     * of the hold it describes. A relayed data frame does not: it is superseded by the next one,
     * and `Quilter` anti-entropy heals the gap. A budget on the order of one heartbeat interval
     * keeps a wedged spoke from consuming its own lane while staying far above what a healthy link
     * needs.
     *
     * Still load-bearing after per-recipient keying, for a *narrower* reason: it no longer protects
     * other recipients (their lanes are independent), it bounds how long **this** recipient's own
     * next frame waits behind a black hole, and with it the lane's own backlog.
     */
    private val relaySendBudget: Duration get() = heartbeatConfig.interval

    /**
     * Drains one recipient's relay lane. Guard discipline is identical to [runAdmitFanOutWriter] —
     * see its KDoc.
     */
    private suspend fun runRelayForwardWriter(recipient: PeerId, queue: Channel<RelayForward>) {
        // Writer-local: this is the only coroutine that ever dequeues this lane, so no atomic is
        // needed. Counting is per-lane; `relayDropped` aggregates across lanes.
        var expectedSeq = 1L
        for (forward in queue) {
            val dropped = forward.seq - expectedSeq
            if (dropped > 0) {
                val total = relayDropped.addAndGet(dropped)
                logger.debug {
                    "room.relay.drop self=${selfId.value} to=${recipient.value} reason=queue-overflow " +
                        "dropped=$dropped total=$total capacity=$RELAY_FORWARD_CAPACITY"
                }
            }
            expectedSeq = forward.seq + 1
            // The origin checked this frame against ITS ceiling; this hop is a different fabric and
            // may be tighter (a `framed()` link with a smaller `maxFrameSize`, or a composite whose
            // min is set by a narrower ply). Unchecked, the overflow is *destructive* rather than
            // lossy: `MeshSeam.sendTo` swallows the throwable into `removePeer`, so a healthy
            // recipient is evicted as though its link died — and the `catch` below never even sees
            // it. A drop is squarely in contract here (the relay is documented lossy), so check.
            val hopCeiling = seam.maxPayloadBytes
            if (hopCeiling != null && forward.bytes.size > hopCeiling) {
                recordOversizeDrop(
                    reason = "forward-over-hop-ceiling",
                    to = recipient,
                    sizeBytes = forward.bytes.size,
                    ceilingBytes = hopCeiling,
                )
                continue
            }
            try {
                val accepted = withTimeoutOrNull(relaySendBudget) {
                    seam.sendTo(recipient, forward.bytes)
                } != null
                if (!accepted) {
                    logger.debug {
                        "room.relay.drop self=${selfId.value} to=${recipient.value} " +
                            "reason=send-budget-exceeded budget=$relaySendBudget"
                    }
                }
            } catch (failure: Throwable) {
                // Genuinely OUR cancellation ends the loop; anything else — including a
                // CancellationException a consumer's `sendTo` minted itself — is that
                // recipient's failure and must not kill this recipient's writer.
                currentCoroutineContext().ensureActive()
                logger.debug {
                    "room.relay.drop self=${selfId.value} to=${recipient.value} " +
                        "cause=${failure::class.simpleName}: ${failure.message}"
                }
            }
        }
    }

    /**
     * Enqueue a relay forward on each recipient's own lane. Never suspends; drops that lane's oldest
     * frame under sustained overload.
     *
     * The overflow itself is invisible here by construction — `DROP_OLDEST` reports success for the
     * very call that displaces an older item — so it is detected downstream as a gap in
     * [RelayForward.seq].
     *
     * **The stamp is exact because the enqueuer is single-threaded**, not because it is atomic:
     * every call reaches here from [handleRelayFrame] on the room's one [Seam.incoming] collector
     * (the ADR-034 single-collection contract), so `incrementAndGet` and the `trySend` that follows
     * it cannot interleave with another enqueue on the same lane. The counter is nonetheless an
     * atomic so the *value* is safely published to the writer coroutine.
     */
    private fun enqueueRelayForward(recipients: List<PeerId>, bytes: ByteArray) {
        if (recipients.isEmpty()) return
        // Lane resolution under the lock, the trySends after it: nothing suspend-capable sits inside
        // the critical section, matching [fanOutToOtherMembers].
        val lanes = lock.withLock { recipients.mapNotNull { peer -> relayLaneFor(peer)?.let { peer to it } } }
        lanes.forEach { (recipient, lane) ->
            val queued = lane.queue.trySend(RelayForward(bytes, lane.nextSeq.incrementAndGet())).isSuccess
            if (!queued) {
                // DROP_OLDEST never refuses, so the only way `trySend` fails is a closed or
                // cancelled lane: the room went terminal, or this recipient left the roster,
                // between the resolution above and here. Logged for the same reason a dropped
                // fan-out is (#1781).
                logger.debug {
                    "room.relay.drop self=${selfId.value} to=${recipient.value} reason=lane-gone"
                }
            }
        }
    }

    /** One queued admit fan-out for one recipient: a frame encoded once for the whole fan-out. */
    private class AdmitFanOut(
        val bytes: ByteArray,
        /** [AdmitMessage] subclass name, carried purely so a dropped frame is nameable in the log. */
        val label: String,
    )

    /** One recipient's admit fan-out lane: the frames waiting for that peer, and its writer. */
    private class AdmitLane(val queue: Channel<AdmitFanOut>, val writer: Job)

    /**
     * Queued admit fan-outs, **one lane per recipient** (#2048), each drained **in enqueue order**
     * by its own [runAdmitFanOutWriter] coroutine (#1781).
     *
     * Every host-authoritative membership announcement *to bystanders* — [AdmitMessage.Paused],
     * [AdmitMessage.Unpaused], [AdmitMessage.Farewell], and the roster-sync [AdmitMessage.Welcome] —
     * is enqueued here instead of being sent from a fresh `scope.launch` per call or an inline loop,
     * so **enqueue order is wire order**. (Joiner-*directed* frames — the welcome, the roster
     * bootstrap, the host's self-introduction, a `ResumeAck` — stay inline: they address the joiner,
     * share no recipient with any fan-out, and so have no order to keep against one.) Two fan-outs
     * raised close together previously had *no ordering relationship at all*: on a multi-threaded
     * dispatcher the second could reach [Seam.sendTo] first, and three of the resulting inversions are
     * silent:
     *
     * - `Paused(estimate)` from [markPartitioned] overtaken by `Paused(refined)` from [refineWindow]
     *   moves a remote member's deadline **backwards**, so it counts an indefinitely-held seat down
     *   to a few seconds and drops it while this host still holds it;
     * - `Unpaused` overtaken by its own `Paused` leaves a **recovered** member pinned
     *   [Liveness.Partitioned] in every remote roster **forever** — [handleUnpaused] no-ops for a
     *   member that is not currently partitioned, and neither frame carries episode identity, so
     *   nothing ever re-asserts the truth. Unbounded, and strictly worse than the wrong deadline.
     * - `Welcome(X)` overtaken by `Farewell(X)` leaves a **departed** member in a bystander's roster
     *   forever: the `Farewell` removes a peer that bystander does not hold yet (a no-op) and the late
     *   `Welcome` then adds it. On a star that bystander has no heartbeat edge to X, so nothing
     *   evicts the ghost. This one is why the welcome fan-out is enqueued here too — see [admitPeer].
     *
     * This is the dedicated-writer-draining-a-[Channel] pattern (`:kuilt-core`'s
     * `CompositeSeam.capabilityWriter` is the sibling), **not** a `limitedParallelism(1)` confinement
     * crutch: ordering is a property of these queues, not of where coroutines happen to run, so it
     * holds on a genuinely multi-threaded dispatcher. The map itself is guarded by [lock] for the
     * same reason — explicit mutual exclusion, never a single-threaded dispatcher standing in for it.
     *
     * **Keyed by [PeerId], not one global FIFO (#2048).** Every inversion above is a statement about
     * what **one** recipient's roster ends up holding, so per-recipient FIFO is the invariant #1781
     * actually needs and a global FIFO was strictly stronger than required. It was also expensive:
     * one wedged recipient delayed every healthy one by up to [fanOutSendBudget] — `reconnectWindow +
     * timeout`, 75 s on the shipped [HeartbeatConfig] defaults — per item ahead of it. Nothing
     * depends on the order two *different* recipients see frames in: each bystander's roster is
     * derived only from the frames it received, and no announcement asserts anything about another
     * bystander's state. So the guarantee is now, exactly: **whatever a recipient receives, it
     * receives in the order this room raised it.**
     *
     * **[Channel.UNLIMITED] per lane, deliberately.** The alternatives, and why not:
     * - [Channel.CONFLATED] is *wrong*. These are distinct announcements about distinct peers, not a
     *   level where only the latest matters — conflation would drop an `Unpaused` for peer A because
     *   a `Paused` for peer B was enqueued behind it, i.e. manufacture the forever-pinned failure
     *   above on purpose.
     * - A **bounded** buffer must drop (or block) on overflow, and a dropped `Unpaused` *is* that
     *   same forever-pinned failure. Bounding would trade the guarantee this queue exists to provide
     *   for a memory ceiling.
     * - **Backpressure** is not available: [fanOutToOtherMembers] is called from non-suspending
     *   detector callbacks and frame handlers, so a suspending `send` would have to be wrapped in a
     *   launch — reintroducing precisely the unordered hop being removed. Blocking a detector
     *   callback on a slow link would also stall liveness detection itself.
     *
     * Unbounded growth is bounded *in practice* by two things. First, by what enqueues: membership
     * **transitions** (admit / pause / refine / recover / evict), which occur on the heartbeat
     * timescale rather than per-frame, and each item is one shared encoded frame. Second, and
     * load-bearing, by the fact that every send is **budgeted** — see [runAdmitFanOutWriter]. Without
     * that budget a wedged link is not a memory question at all: `Seam.sendTo` can suspend forever
     * (`LinkSeam`'s outbox is bounded and backpressured by design, so a black-holed link parks its
     * caller indefinitely — the #1655 shape), and a parked writer never drains its lane. The budget
     * caps each lane's drain rate at one frame per budget instead, so a lane's backlog is bounded by
     * roster churn over that span — and, since #2048, a wedged peer's backlog is the *only* thing its
     * wedge can grow.
     */
    private val admitLanes = mutableMapOf<PeerId, AdmitLane>()

    /**
     * The admit lane for [recipient], created (and its writer launched) on first use — or null when
     * [recipient] is no longer a member, or once the room is terminal.
     *
     * Lazily, for the same reasons as [relayLaneFor]; see there. Ordering does not depend on when the
     * writer starts: the lane exists before the frame that created it is enqueued, and the channel is
     * unbounded, so anything queued before the writer is first dispatched is drained, not lost.
     *
     * The membership check is **unreachable from the one caller** — [fanOutToOtherMembers] reads
     * `admittedById.keys` and resolves every lane inside a *single* [lock] critical section, so a
     * recipient it names is a member by construction. It is here anyway so
     * `lanes.keys ⊆ admittedById.keys` is a local property of the factory rather than a fact about
     * that caller's shape; see [relayLaneFor], whose path takes the lock twice and where the same
     * line is genuinely load-bearing.
     *
     * Callers must hold [lock].
     */
    private fun admitLaneFor(recipient: PeerId): Channel<AdmitFanOut>? {
        if (closed || recipient !in admittedById) return null
        return admitLanes.getOrPut(recipient) {
            val queue = Channel<AdmitFanOut>(Channel.UNLIMITED)
            AdmitLane(queue, scope.launch { runAdmitFanOutWriter(recipient, queue) })
        }.queue
    }

    /**
     * Encode [message] once and **enqueue** it on the lane of every admitted member except [subject].
     * The fan-out is *ordered* here and *sent* by each recipient's [runAdmitFanOutWriter] — see
     * [admitLanes] for why that separation is the fix and not an indirection.
     *
     * The roster snapshot and the lane resolution are taken under [lock] and the enqueues happen
     * after it is released, so the pre-existing invariant that nothing suspend-capable sits inside a
     * critical section is preserved (a [Channel.trySend] into an unbounded channel cannot suspend
     * either way).
     *
     * The frame is encoded **once** and the same [ByteArray] is shared by every lane, so keying by
     * recipient costs one small queue item per recipient rather than a copy of the frame.
     *
     * A **terminal** room enqueues nothing: [leave] flips `closed` under this same [lock], so a
     * fan-out raised by an in-flight handler after [leave] cannot resurrect a send — matching
     * [broadcast]/[sendTo]'s terminal no-op. [leave] also closes every lane, and `trySend` on a
     * closed channel returns a failed result rather than throwing, so a caller that loses the race
     * with that gate still never sees an exception.
     */
    private fun fanOutToOtherMembers(subject: PeerId, message: AdmitMessage) {
        val lanes = lock.withLock {
            if (closed) return
            admittedById.keys
                .filter { it != subject }
                .mapNotNull { peer -> admitLaneFor(peer)?.let { peer to it } }
        }
        if (lanes.isEmpty()) return
        val label = message::class.simpleName ?: "AdmitMessage"
        val bytes = AdmitMessage.encode(message)
        lanes.forEach { (recipient, queue) ->
            val queued = queue.trySend(AdmitFanOut(bytes = bytes, label = label)).isSuccess
            if (!queued) {
                // The lane is UNLIMITED, so the only way `trySend` fails is a closed or cancelled
                // channel: the room went terminal, or this recipient left the roster, between the
                // resolution above and here. Logged for the same reason a dropped send is — absence
                // of an announcement has to be diagnosable off-device (#1781).
                logger.debug {
                    "room.fanout.drop self=${selfId.value} to=${recipient.value} message=$label " +
                        "reason=lane-gone"
                }
            }
        }
    }

    /**
     * One recipient's admit fan-out writer: drains that peer's lane in enqueue order. One coroutine
     * per recipient, so for **that** recipient no later announcement can overtake an earlier one
     * (#1781) — and a peer whose link is wedged holds up nothing but its own lane (#2048).
     *
     * **Survives a throwing recipient — including a cancellation the callee minted itself.**
     * [Seam.sendTo] is the only call in this loop that can throw (a peer may tear between the roster
     * snapshot and the send), and it is guarded *per recipient*. The guard is a `try`/`catch` plus
     * [ensureActive], **not** [runCatchingCancellable]: that helper rethrows *every*
     * `CancellationException`, and a consumer-implemented [Seam] is entitled to hand us one it minted
     * itself. `withTimeout(sendTimeout) { … }` inside a fabric's `sendTo` is the natural way to write
     * one, and it throws `TimeoutCancellationException` — which *is* a `CancellationException` — **to
     * its caller** without cancelling that caller's job. [Loom.weave] documents the obligation not to
     * do that and [Seam.sendTo] now does too, but a contract only some methods carry is exactly what
     * lets this through, so the guard does not rely on it.
     *
     * A rethrow here would be maximally silent: because the throwable *is* a `CancellationException`,
     * the `scope.launch` in [admitLaneFor] is **cancelled rather than failed** — no handler runs,
     * nothing reaches `state`, and there is not even a stack trace. The lane is never closed, so every
     * later `trySend` still reports success while every `Paused`/`Unpaused`/`Farewell` for the room's
     * life is enqueued and never sent: that recipient's roster diverges permanently, with no
     * announcement at all. [ensureActive] is the discriminator (`CompositeSeam.reconcile` in
     * `:kuilt-core` is the sibling): our *own* cancellation still ends the loop, and anything else —
     * cancellation-shaped or not — is that recipient's failure.
     *
     * **Per-[PeerId] lanes shrink this blast radius but do not remove the need for the guard**, and
     * the shrinkage is what makes it easy to under-rate: before #2048 a single mint killed the room's
     * only sender and every remote roster diverged; now it silences exactly one peer, forever, with a
     * queue growing behind it. Forever-silent-and-unbounded for one member is still the #1781 failure,
     * so the guard is load-bearing at the new granularity — see `AdmitFanOutOrderingTest`, which pins
     * it on the recipient that minted the cancellation rather than only on a bystander.
     *
     * **Each send is budgeted.** A wedged recipient must not stall its own later announcements
     * indefinitely. `sendTo` can suspend forever: `LinkSeam`'s outbox is a *bounded*
     * `BufferOverflow.SUSPEND` channel by design, so a black-holed link (one whose `conn.send` never
     * returns and never tears — the #1655 shape) backpressures its caller forever, and a parked writer
     * never drains its lane. [withTimeoutOrNull] bounds it and the frame is dropped and logged like any
     * other undeliverable one. [withTimeoutOrNull] and never bare `withTimeout` — that would hand-mint
     * the very callee-minted cancellation the guard above exists to absorb.
     *
     * The budget is derived, not configured: [HeartbeatConfig.reconnectWindow] plus
     * [HeartbeatConfig.timeout]. Both terms are load-bearing, and the floor is set by what the queue
     * must be able to *carry*, not by what a healthy link needs:
     *
     * - `reconnectWindow`, because an announcement stays meaningful for the whole span of the hold it
     *   describes. A `Paused` may legitimately still be in flight when the `Farewell` that expires the
     *   very same seat is raised — that pair is one of the orderings this queue exists to keep — so a
     *   budget at or below the window would drop the frame it is supposed to be ordering.
     * - plus `timeout`, one detection interval of slack, because the announcement is raised *after*
     *   detection has already elapsed and the window is measured from there.
     *
     * Anything shorter turns the budget into a second, cruder liveness detector that drops frames on a
     * slow-but-alive link the real detector still holds healthy. Anything longer buys nothing: past
     * that point every frame in flight has outlived its own subject. The budget is therefore
     * deliberately **loose** — its job is to make the wedge *finite*, not to be tight.
     *
     * Bounding the send is what makes the growth analysis on [admitLanes] true: a lane drains at no
     * worse than one frame per budget, so a wedged link costs a bounded delay on its own lane rather
     * than an unbounded backlog. Since #2048 that delay is also *all* it costs — a healthy peer's lane
     * is drained by its own writer and never waits on anyone else's.
     *
     * Delivery remains **best-effort** — a torn, wedged, or throwing recipient's frame is dropped and
     * logged, exactly as before. What is guaranteed is *per-recipient order*: whatever a recipient
     * receives, it receives in the order this room raised it.
     */
    private suspend fun runAdmitFanOutWriter(recipient: PeerId, queue: Channel<AdmitFanOut>) {
        for (fanOut in queue) {
            try {
                val accepted = withTimeoutOrNull(fanOutSendBudget) {
                    seam.sendTo(recipient, fanOut.bytes)
                } != null
                if (!accepted) {
                    logger.debug {
                        "room.fanout.drop self=${selfId.value} to=${recipient.value} " +
                            "message=${fanOut.label} reason=send-budget-exceeded " +
                            "budget=$fanOutSendBudget"
                    }
                }
            } catch (failure: Throwable) {
                // Genuinely OUR cancellation → rethrow and end the loop; anything else (including
                // a CancellationException the consumer's `sendTo` minted itself) is this
                // recipient's failure and must not kill this recipient's writer. See the KDoc.
                currentCoroutineContext().ensureActive()
                logger.debug {
                    "room.fanout.drop self=${selfId.value} to=${recipient.value} " +
                        "message=${fanOut.label} cause=${failure::class.simpleName}: " +
                        "${failure.message}"
                }
            }
        }
    }

    /**
     * Tear down both of [recipient]'s lanes — its queue and its writer — because it has left the
     * roster. Callers must hold [lock]; the sole caller is [removeFromRoster], which is the sole
     * place a peer is removed from [admittedById].
     *
     * **Cancelled, not closed, and that difference is load-bearing.** [leave] closes its lanes so each
     * writer completes on drain rather than dying mid-item; that is right for a room going terminal
     * and wrong here, because a peer that leaves can come back. [addToRoster] supports re-admitting
     * the *same* [PeerId] (its `isReadmit` branch), and a departed peer's stale backlog draining into
     * a re-admitted one is exactly the inversion the lane exists to prevent: a `Paused(Y)` queued
     * before the eviction, delivered after the re-admit, pins a recovered Y [Liveness.Partitioned] in
     * that peer's roster forever. Cancelling the writer additionally aborts a send already parked in a
     * wedged `sendTo`, which closing alone would let complete. A re-admit then mints a fresh lane —
     * new channel, new writer, sequence restarted — with no path back to the old one.
     *
     * The frames discarded here are announcements to a peer that is no longer a member, so discarding
     * them costs nothing: nothing in the roster the departed peer holds matters to this room again.
     */
    private fun discardLanes(recipient: PeerId) {
        admitLanes.remove(recipient)?.let { lane ->
            lane.writer.cancel()
            lane.queue.cancel()
        }
        relayLanes.remove(recipient)?.let { lane ->
            lane.writer.cancel()
            lane.queue.cancel()
        }
    }

    /**
     * Per-recipient deadline for one queued fan-out send — see [runAdmitFanOutWriter] for why the
     * send is bounded at all and why this is [HeartbeatConfig.reconnectWindow] rather than a knob.
     */
    private val fanOutSendBudget: Duration
        get() = heartbeatConfig.reconnectWindow + heartbeatConfig.timeout

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
     * **Idempotence is split by kind** (#1724): only [MembershipEvent.Partitioned] is idempotent —
     * a mesh peer that already detected the drop locally does not re-announce the partition. The
     * *level* is always refreshed (a local estimate is a guess; the host is authoritative, so
     * returning early would pin the guess for the rest of the window) **and so is
     * [MembershipEvent.WindowOpened]**: the refinement goes through [refineWindow], which moves the
     * level and announces the new deadline together. An earlier revision returned before both
     * emissions, which silently moved the roster deadline while leaving the last `WindowOpened` a
     * consumer heard permanently false — see [refineWindow] for why announcing is the right side of
     * that trade.
     */
    private fun handlePaused(sender: PeerId, paused: AdmitMessage.Paused) {
        val subject = PeerId(paused.peerId)
        val hostDeadline = Instant.fromEpochMilliseconds(paused.expiresAt)
        // One clock read for the whole partition, so the level's `since` and the emitted
        // Partitioned's `at` are the same instant. Two reads would describe one partition with two
        // timestamps — invisible under virtual time, real under a wall clock.
        val now = clock()
        // Null ⇒ already partitioned locally, so this Paused is a refinement, not a first
        // detection. The level write for that case belongs to refineWindow, below: doing it here
        // too would either duplicate the write or (worse) tempt a caller to skip the announcement
        // that has to accompany it.
        val firstDetection: Member? = lock.withLock {
            val host = hostPeerId
            if (host == null || sender != host || subject == host || subject == selfId) return
            val current = admittedById[subject] ?: return
            if (current.liveness is Liveness.Partitioned) return@withLock null
            updateMemberLiveness(subject, Liveness.Partitioned(since = now, windowExpiresAt = hostDeadline))
                ?: return
        }
        if (firstDetection == null) {
            // The single authority-hop for "a better deadline arrived": it preserves `since`
            // (first-detection, so it must not drift forward), moves `windowExpiresAt` to the
            // host's number, announces it, and no-ops when the host's number is the one already
            // held. Role-agnostic by construction — its host-only fan-out branch is unreachable
            // from here, since this handler runs only on a joiner.
            refineWindow(subject, hostDeadline)
            return
        }
        // The host told us the link dropped; TransportClosed is the honest reason here — we
        // observed no timeout or backpressure ourselves, only the authoritative Paused (#1556).
        // `now` and `hostDeadline` are the single reads hoisted above the lock — not a second
        // `clock()` call and not a re-derivation of `paused.expiresAt`, so one instant and one
        // deadline describe this partition on both the level and the event.
        emitEvent(
            MembershipEvent.Partitioned(
                firstDetection.id,
                now,
                ReconnectReason.TransportClosed,
                localFabric = localFabric.value,
            ),
        )
        emitEvent(MembershipEvent.WindowOpened(firstDetection.id, hostDeadline))
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
            if (current.liveness !is Liveness.Partitioned) return
            updateMemberLiveness(subject, Liveness.Connected) ?: return
        }
        emitEvent(MembershipEvent.Recovered(updated.id, clock()))
    }

    private suspend fun handlePeerLost(peerId: PeerId, at: Instant) {
        logger.info { "handlePeerLost peer=${peerId.value} role=${_role.value} at=$at" }
        val isHostPeer = lock.withLock {
            stopDetector(peerId)
            _role.value == SessionRole.Joiner && peerId == hostPeerId
        }
        if (isHostPeer) {
            logger.info { "handlePeerLost.markHostLost peer=${peerId.value}" }
            markHostLost(at, FailureReason.WindowExpired)
        } else {
            logger.info { "handlePeerLost.evict peer=${peerId.value} reason=PartitionExpired" }
            removeFromRoster(peerId, LeaveReason.PartitionExpired)
        }
    }

    /**
     * Host-eviction backstop for #1618 Track C. Evicts [peerId] iff it is **still**
     * [Liveness.Partitioned] when its reconnect window expires.
     *
     * The per-peer [HeartbeatPartitionDetector]'s [PartitionEvent.PeerLost] is normally the sole
     * evictor (and normally fires around the same virtual instant as [JoinerReconnectEvent.WindowExpired],
     * with the same [heartbeatConfig]-derived window). On a real transport the detector can stall in
     * `Partitioned` and never mature to `PeerLost`, so this covers that gap. Mirrors the non-host branch
     * of [handlePeerLost]: stop the detector, then [removeFromRoster] with [LeaveReason.PartitionExpired].
     *
     * Idempotent — a member already evicted by `PeerLost` (absent from [admittedById]) or recovered
     * ([Liveness.Connected]) is a no-op; and [removeFromRoster] itself guards a duplicate [MembershipEvent.Left],
     * so a subsequent late `PeerLost` emits nothing. This loop runs on the host only ([reconnectController]
     * is null for joiners), so it never pre-empts a joiner's terminal `HostLost` path.
     */
    private fun evictOnExpiredWindowIfPartitioned(peerId: PeerId) {
        val shouldEvict = lock.withLock {
            val current = admittedById[peerId] ?: return
            if (current.liveness !is Liveness.Partitioned) return
            stopDetector(peerId)
            true
        }
        if (shouldEvict) {
            logger.info { "windowExpired.evict peer=${peerId.value} reason=PartitionExpired backstop=PeerLost-absent" }
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
        emitEvent(MembershipEvent.HostLost(at, reason, localFabric = localFabric.value))
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
            emitEvent(MembershipEvent.Joined(member))
            startDetector(member)
        }
    }

    private fun removeFromRoster(peerId: PeerId, reason: LeaveReason) {
        // The fan-out lanes are torn down in the SAME critical section that removes the member. That
        // reaps every lane minted while the peer WAS a member; what stops a new one appearing after
        // is the membership re-check in [admitLaneFor]/[relayLaneFor], not this line — the relay path
        // resolves its recipients under a SEPARATE lock acquisition, so an eviction can land between
        // the two and a check keyed only on `closed` would mint a lane for a peer already gone. The
        // two together give the invariant `lanes.keys ⊆ admittedById.keys` at every point the lock is
        // not held. Tied to `removed != null` so a duplicate eviction cannot discard a lane a re-admit
        // has since installed. See [discardLanes].
        val removed = lock.withLock { admittedById.remove(peerId)?.also { discardLanes(peerId) } }
        removed ?: return // already removed, avoid duplicate Left events
        _roster.update { current -> current.filterNot { it.id == peerId }.toSet() }
        _rosterPeers.update { current -> current - peerId }
        emitEvent(MembershipEvent.Left(peerId, reason))
    }

    private fun isAdmittedPeer(peerId: PeerId): Boolean = lock.withLock { admittedById.containsKey(peerId) }

    /**
     * Returns `true` if [peerId] is an admitted member.
     *
     * Used by [RoomChannelSeam] to filter incoming frames to admitted peers only.
     * Accepting a nullable [PeerId] matches [Swatch.sender], which is nullable.
     */
    internal fun isAdmitted(peerId: PeerId?): Boolean = peerId != null && lock.withLock { admittedById.containsKey(peerId) }

    /**
     * The peer this member has identified as its host, or `null` before identification.
     *
     * Exposed `internal` for tests and for the host-authoritative gates layered above the room; the
     * field itself stays `private var` and is only ever written under [lock].
     *
     * The identically-bodied `hostPeer()` on the anonymous [JoinerResumeHost] above is **not**
     * reusable here: that object is a private member of `resumeMachine`, which is null for a host
     * room, so it can neither be reached from outside nor answer for a host.
     */
    internal fun hostPeer(): PeerId? = lock.withLock { hostPeerId }

    // ── Application frame routing ─────────────────────────────────────────────

    private fun routeApplicationFrame(sender: PeerId, bytes: ByteArray) {
        _incoming.tryEmit(RoomFrame(sender = sender, payload = bytes))
    }

    // ── Room interface ────────────────────────────────────────────────────────

    /**
     * Broadcast [bytes] to all admitted members.
     *
     * On a star fabric a spoke's frame reaches only the host, so once this member's roster diverges
     * from what the transport can address, the frame is wrapped and **relayed via the host**
     * (#1994). [RoomChannelSeam] therefore needs no change: it already delegates here, so
     * `peers = room.rosterPeers` becomes honest for free.
     *
     * Silent no-op when the room is terminal (after [MembershipEvent.HostLost] or [leave]).
     *
     * **Lossy without error, relayed or not — this call never throws [PeerNotConnected], and never
     * throws [us.tractat.kuilt.core.PayloadTooLarge] either.** An unresolvable destination is
     * dropped with a debug log; a payload whose encoded frame will not fit the fabric is dropped by
     * [oversizeOrNull] — **counted in [oversizeFramesDropped] and warned once**, because that one
     * never heals on its own; a torn or wedged recipient's send is dropped by
     * [runRelayForwardWriter]; and if the relay hop itself is unreachable — the host gone from
     * [Seam.peers] while the roster still holds it — the frame degrades to a best-effort direct
     * [Seam.broadcast], which on a spoke that has lost its host link reaches nobody. (That degrade
     * is size-safe by construction: the raw payload is strictly smaller than the envelope that just
     * cleared the ceiling.)
     *
     * That last case is not a narrow window. [runJoinerTornWatcher] responds to [SeamState.Torn] by
     * attempting a reconnect and does **not** set `hostLost`, so it spans the whole reconnect window
     * (60 s by default), and it fires on a plain **2-peer** room too: the host leaving [Seam.peers]
     * diverges the roster, and the relay branch would then try to relay through the very peer it
     * cannot reach. Before #1994 that was a silent no-op, and it must stay one — a `Quilter`'s
     * timer-driven broadcast that threw here would kill the coroutine that drives anti-entropy,
     * which is precisely the mechanism that heals the gap once the host returns.
     *
     * Contrast [sendTo], which is addressed and therefore **does** throw.
     */
    override suspend fun broadcast(bytes: ByteArray) {
        val terminal = lock.withLock { hostLost || closed }
        if (terminal) return
        val host = relayHostOrNull()
        val frame =
            if (host == null) bytes else RelayEnvelope.encode(RelayEnvelope(selfId, RelayDest.Everyone, bytes))
        val tooLarge = oversizeOrNull(bytes.size, frame.size)
        if (tooLarge != null) {
            // Dropped, not thrown: this call is lossy-without-error by contract, and the caller
            // most likely to hit the ceiling is a Quilter's timer-driven anti-entropy broadcast,
            // which a throw would silently kill (#2047). Counted and warned-once all the same —
            // that Quilter would otherwise stop converging permanently and silently.
            recordOversizeDrop(
                reason = "payload-over-budget",
                to = null,
                sizeBytes = frame.size,
                ceilingBytes = seam.maxPayloadBytes,
            )
            return
        }
        if (host == null) return seam.broadcast(frame)
        try {
            seam.sendTo(host, frame)
        } catch (unreachable: PeerNotConnected) {
            // Caught rather than pre-checked `host in seam.peers.value`: the pre-check is a TOCTOU
            // race the send itself resolves authoritatively. Narrow by type — a wedged link throws
            // CancellationException instead, and that must still propagate.
            logger.debug {
                "room.relay.drop self=${selfId.value} to=${unreachable.peer.value} " +
                    "reason=relay-hop-unreachable dest=Everyone"
            }
            seam.broadcast(bytes)
        }
    }

    /**
     * Send [bytes] to one specific admitted member, relaying via the host when the transport cannot
     * address that member directly — see [broadcast] for the routing rule.
     *
     * Silent no-op when the room is terminal (after [MembershipEvent.HostLost] or [leave]).
     *
     * **Throws, unlike [broadcast].** An addressed send that cannot be delivered is reported: this
     * is [Seam.sendTo]'s documented contract, and swallowing it would re-create #1994's own symptom
     * — silent non-delivery — at the send side. The two methods differ deliberately because their
     * contracts differ; a caller that wants best-effort semantics for an addressed send wraps this
     * in `runCatchingCancellable`.
     *
     * The frame is still lossy *after* the first hop: once the host has accepted the envelope, an
     * unresolvable destination or a wedged recipient is dropped with a debug log and nothing is
     * reported back. Only the hop this member performs itself can throw.
     *
     * @throws PayloadTooLarge if the encoded frame will not fit the fabric — measured on the frame,
     *   so a direct send is charged no envelope and a relayed one is charged exactly what its own
     *   envelope cost. See [oversizeOrNull].
     * @throws PeerNotConnected if the hop this member must perform cannot be made. On a relayed
     *   send the peer it names is the **host** — the hop that actually failed — not [peer], which
     *   this member has no direct route to by construction.
     */
    override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
        val terminal = lock.withLock { hostLost || closed }
        if (terminal) return
        val host = relayHostOrNull()
        val frame =
            if (host == null) bytes else RelayEnvelope.encode(RelayEnvelope(selfId, RelayDest.One(peer), bytes))
        // Reported, not dropped: an addressed send names a peer, so an over-budget payload is
        // information the caller asked for — and it gets the budget rather than the fabric's own
        // frame error for framing it never asked for (#2047).
        val tooLarge = oversizeOrNull(bytes.size, frame.size)
        if (tooLarge != null) throw tooLarge
        if (host == null) return seam.sendTo(peer, frame)
        seam.sendTo(host, frame)
    }

    /**
     * The fabric's frame ceiling less what a relay envelope may cost — subtracted
     * **unconditionally**, whatever this member's current routing is.
     *
     * Keying the *published* budget on `relayHostOrNull() != null` would make it a TOCTOU trap:
     * routing flips the instant the roster diverges from [Seam.peers], so a caller that read a
     * mesh-sized budget and then sent could still be relayed, and overflow. A member entering its
     * reconnect window is enough to move it. The stable, conservative bound is the only one a
     * caller can act on — the same reasoning that has `RoutedRaftTransport` subtract its header
     * budget unconditionally. Pinned by `RelayPayloadBudgetTest`'s route-independence test, which
     * a route-conditional revision fails.
     *
     * ## Published conservatively, enforced exactly
     *
     * This number is what a caller may *rely* on; it is **not** the refusal threshold. Refusing
     * against it would charge the envelope to a **direct** send, where nothing wraps the payload —
     * so a full-mesh room on a 32 KiB fabric would silently stop delivering anything in
     * `(32 KiB − 256, 32 KiB]`, and any room on a fabric with a ≤ 256 B ceiling would deliver
     * nothing at all. [oversizeOrNull] therefore measures the **encoded frame**: stable promise,
     * exact refusal.
     *
     * The gap between the two is deliberate slack. A payload above this budget but still fitting
     * the wire is delivered rather than refused — the budget under-promises, which is the safe
     * direction.
     *
     * [RELAY_ENVELOPE_BUDGET] covers `PeerId`s of about 107 bytes each, and nothing in the library
     * bounds a `PeerId`. Past that the promise can over-reach: a payload at exactly this budget may
     * still overflow once wrapped. Because the refusal is measured on the frame, the consequence is
     * a typed [PayloadTooLarge] — **not** the fabric error that would tear the seam (#2047).
     *
     * `null` when the fabric names no ceiling: unknown, not unbounded.
     */
    override val maxPayloadBytes: Int?
        get() = seam.maxPayloadBytes?.let { (it - RELAY_ENVELOPE_BUDGET).coerceAtLeast(0) }

    /**
     * The refusal for a send whose encoded [frameBytes] overflows the fabric, or `null` if it fits.
     *
     * Measures what actually goes on the wire, so a **direct** send is charged nothing and a
     * relayed one is charged exactly what its envelope cost for *these two* peer ids — closing both
     * the silent-drop regression a fixed reservation caused on a full mesh and the long-`PeerId`
     * hole a fixed reservation could not cover. The reported budget is reconstructed the same way
     * (`ceiling − reserved`), so the caller is told a number that would have fitted.
     *
     * The cost is that the frame is encoded before it can be refused. That is inherent to an exact
     * check and is the right trade: the alternative refuses frames the fabric would have carried.
     */
    private fun oversizeOrNull(payloadBytes: Int, frameBytes: Int): PayloadTooLarge? {
        val ceiling = seam.maxPayloadBytes ?: return null
        if (frameBytes <= ceiling) return null
        val reserved = frameBytes - payloadBytes
        return PayloadTooLarge(payloadBytes, (ceiling - reserved).coerceAtLeast(0), reserved)
    }

    /**
     * The host to relay through, or `null` to send directly.
     *
     * Returns `null` — direct — in two cases:
     *
     * - **This member is the host.** Keyed on the role *explicitly*, not on the subset test below.
     *   A host does **not** always satisfy `rosterPeers ⊆ seam.peers`: a member inside its reconnect
     *   window stays in the roster while the transport has dropped it (#1557/#1614), so a host with
     *   one partitioned member would otherwise enter the relay branch and try to relay through
     *   itself. An earlier revision was saved from that only by [hostPeerId] being incidentally
     *   `null` on a host — which a plausible tidy-up would have broken.
     * - **The roster is a subset of what the transport can address**, i.e. a full mesh.
     *
     * **On a plain mesh the dominant divergence trigger is a transient partition, not a star.** The
     * subset test says nothing about topology: on a flat, fully-connected mesh the ordinary way for
     * it to fail is one member sitting in its reconnect window — still in the roster, already gone
     * from [Seam.peers] (#1557/#1614). That single peer routes **everyone's** traffic through the host
     * for the whole window (60 s by default), on a topology with no star in it. Rule I2 below makes
     * that deliberate rather than accidental — mixing hop counts is worse — but it means the relay's
     * cost model has to hold for a mesh under a routine transient drop, not only for a real star. It
     * is why the relay lanes are keyed per recipient (#2048): shared, one wedged spoke would have
     * throttled a whole mesh's data plane every time any member blinked.
     *
     * Otherwise **everything** relays, including frames to a peer that *is* directly reachable.
     * Keying [broadcast] on the roster subset but [sendTo] on the individual peer would, on a
     * partial mesh, give one destination two different hop counts — and a `Quilter`'s ack could
     * then overtake the delta it acknowledges.
     *
     * **A null [hostPeerId] here throws.** It is an invariant violation on a joiner, not a
     * degrade-quietly case: [handleWelcome] sets [hostPeerId] from the first accepted `Welcome`,
     * which necessarily precedes any co-member entering the roster, so a diverged roster with no
     * identified host means that invariant has already been broken upstream. Falling back to a
     * direct send would re-create #1994's own symptom — silent non-delivery — and hide the cause.
     */
    private fun relayHostOrNull(): PeerId? {
        if (_role.value == SessionRole.Host) return null
        val (roster, host) = lock.withLock { _rosterPeers.value to hostPeerId }
        if (roster.all { it in seam.peers.value }) return null
        return requireNotNull(host) {
            "relay required but no host identified — roster=${roster.map { it.value }} " +
                "seamPeers=${seam.peers.value.map { it.value }}; hostPeerId must be set by the " +
                "first accepted Welcome (see handleWelcome)"
        }
    }

    /**
     * Returns a [Seam] view scoped to channel [id].
     *
     * The returned [RoomChannelSeam] sources its peer set from [rosterPeers] (admitted
     * roster + self) and its inbound stream from [channelIncoming] — the union of the directly
     * delivered and the host-relayed channel frames — filtered to the sub-id derived from [id].
     * That union is what lets a channel view see a co-spoke's relayed frames while the per-peer
     * liveness detectors — which collect [rawIncoming] alone — do not (#1994; see
     * [channelIncoming], including why the union is made by its two producers rather than by a
     * combinator here). Idempotent: the same [Seam] instance is returned for each distinct [id].
     */
    override fun channel(id: String): Seam {
        val subId = RoomChannel.channelSubId(id)
        return lock.withLock {
            channelViews.getOrPut(subId) {
                RoomChannelSeam(room = this, subId = subId, sharedRaw = channelIncoming)
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
        // Close EVERY fan-out lane rather than cancelling its writer (#1781, per-recipient since
        // #2048), so each [runAdmitFanOutWriter] / [runRelayForwardWriter] completes when its lane
        // drains instead of dying mid-item. Anything already enqueued MAY still be attempted,
        // best-effort, before the seam tears — but do not read that as a guarantee: the writers are
        // separate coroutines and `seam.close` below is reached without necessarily yielding to them,
        // so in practice they typically do not resume until the seam is already `Torn` and their
        // remaining sends fail fast. That matches the pre-existing behaviour, where [leave] never
        // cancelled the per-call `scope.launch`es either.
        //
        // No lane can leak past this point: `closed` was set under [lock] above, and both
        // [admitLaneFor] and [relayLaneFor] refuse to mint a lane once it is set — so the maps are
        // frozen by the time this second critical section reads them, and clearing them here means a
        // room that somehow re-entered [leave] cannot double-close. A `trySend` that raced the gate
        // fails against a closed channel instead of throwing at its caller.
        val lanesToDrain = lock.withLock {
            (admitLanes.values.map { it.queue } + relayLanes.values.map { it.queue })
                .also { admitLanes.clear(); relayLanes.clear() }
        }
        lanesToDrain.forEach { it.close() }
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

/**
 * Zero-lag `StateFlow<TransportCapability>` → `StateFlow<FabricAvailability>` projection, backing
 * [SeamRoom.localFabric]. Scope-free: it owns no coroutine and stores no copy, so it cannot lag
 * [Seam.capability] the way a `stateIn`/mirrored `MutableStateFlow` would.
 *
 * Mirrors `:kuilt-core`'s `internal MappedStateFlow`, which `:kuilt-session` cannot see. It differs
 * in one respect that matters: that class requires an *injective* transform, and
 * `TransportCapability → availability` is not (a role-only change leaves the availability equal).
 * Conflation is therefore restored explicitly per collector, so this satisfies the [StateFlow]
 * contract that equal consecutive values are never emitted.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedAvailability(
    private val source: StateFlow<TransportCapability>,
) : StateFlow<FabricAvailability> {
    override val value: FabricAvailability get() = source.value.availability
    override val replayCache: List<FabricAvailability> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<FabricAvailability>): Nothing {
        // `last` is confined to this collect call, which the source drives sequentially.
        var last: FabricAvailability? = null
        source.collect { capability ->
            val next = capability.availability
            if (next != last) {
                last = next
                collector.emit(next)
            }
        }
    }
}
