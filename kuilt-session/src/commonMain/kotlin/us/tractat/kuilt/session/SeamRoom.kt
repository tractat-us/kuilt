package us.tractat.kuilt.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.FlushOutcome
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
import us.tractat.kuilt.core.flushWithin
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.validFirstHop
import org.kotlincrypto.random.CryptoRand
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.KeyAdmission
import us.tractat.kuilt.session.admit.KeyedHello
import us.tractat.kuilt.session.admit.PendingChallenges
import us.tractat.kuilt.session.admit.keyed
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.admit.ReturnKind
import us.tractat.kuilt.session.admit.Seat
import us.tractat.kuilt.session.admit.SeatDecision
import us.tractat.kuilt.session.admit.SeatLiveness
import us.tractat.kuilt.session.admit.SupersededIds
import us.tractat.kuilt.session.admit.decideSeat
import us.tractat.kuilt.session.election.ElectionLobby
import us.tractat.kuilt.session.election.LobbyMessage
import us.tractat.kuilt.session.election.SeamElectionLobby
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerOutcome
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.JoinerResumeHost
import us.tractat.kuilt.session.partition.JoinerResumeMachine
import us.tractat.kuilt.session.partition.ProvenResume
import us.tractat.kuilt.session.partition.ResumeRefusal
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.RoomId
import kotlin.concurrent.Volatile
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
 * Each call to [host] or [join] weaves a [Seam] via [loom] (new on most looms; one holding a live
 * session may answer a repeat with it), wraps it in a [SeamRoom], and drives the admit/identify
 * handshake:
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
 * [deviceKeyStore] holds this device's key, and is required for the same reason: whether a device
 * is remembered across restarts is the caller's choice, not a default. Pass a platform store to be
 * recognised when the app comes back, or `InMemoryDeviceKeyStore()` to be a new device every run
 * (tests, and apps that choose not to be remembered). Every room this factory makes shares it, since
 * they are all the same device. Its key travels in every `Hello` and on every [Member]. A host
 * admits a joiner only once the joiner has signed a proof under that key, so on a host every
 * member's key is verified; on a joiner the host's own entry is verified (the host signed its
 * challenge under that key) and every other member's key is host-asserted (#3211; see [Member]).
 * [host], [join] and [adopt] each read the key from it once, before the room exists, and throw to
 * their caller when the store cannot produce one; a room never reads the store for its key (see
 * [RoomDevice]).
 *
 * A host signs its room id into every challenge, and a joiner signs its device id into every proof,
 * so [host], [join] and [adopt] throw [IllegalArgumentException] for a `roomId` or `deviceId` no
 * statement could carry — one holding an unpaired surrogate, or whose UTF-8 is longer than 65 535
 * bytes — before they touch the loom. Left to the exchange, it would fail every attempt.
 *
 * [heartbeatConfig] controls partition-detection timing.
 *
 * [admitTimeout] bounds the joiner's admit handshake: if no
 * [AdmitMessage.Welcome] arrives within this window after the joiner's `Hello`,
 * the room surfaces a terminal [MembershipEvent.AdmissionFailed] with
 * [AdmissionFailure.TimedOut] instead of waiting forever (#1178). A host uses it too, as how long
 * a challenge it sent stays open for the joiner's proof (#3211).
 *
 * @see SeamRoomFactory.systemClock for the production convenience constructor.
 */
public class SeamRoomFactory(
    private val loom: Loom,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val deviceKeyStore: DeviceKeyStore,
    private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    private val admitTimeout: Duration = DEFAULT_ADMIT_TIMEOUT,
    /**
     * How many unread frames each room this factory builds holds per member for [Room.incomingFrom]
     * (#2802). Default [DEFAULT_MEMBER_INBOX_CAPACITY].
     *
     * **`0` holds nothing**, and is how a consumer that never reads [Room.incomingFrom] opts out of
     * paying for it. That cost is real on the *joiner* side: a room opens an inbox for every member it
     * admits, and a joiner admits the host — and, on a mesh, every other member it is told about — so a
     * client that only ever reads [Room.incoming] would otherwise hold that many of the host's frames
     * per member until the depth is exceeded. Attach snapshots are exactly the large frames this holds.
     *
     * With `0`, [Room.incomingFrom] throws [IllegalStateException] rather than handing back an
     * immediately-completing flow: a consumer that disabled holding and then reads per-member frames has
     * a configuration error, and completion is a signal ("this admission ended") that such a consumer
     * would act on in a loop.
     */
    private val memberInboxCapacity: Int = DEFAULT_MEMBER_INBOX_CAPACITY,
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
    init {
        // A negative depth would otherwise behave exactly as `0` — no inbox is opened, and
        // [Room.incomingFrom] refuses — while the refusal said `memberInboxCapacity = 0`, which is not
        // what the caller wrote. Refuse at construction instead, where the wrong value is.
        require(memberInboxCapacity >= 0) {
            "memberInboxCapacity must be >= 0, was $memberInboxCapacity: 0 holds nothing and any " +
                "positive depth is how many unread frames a room holds per member"
        }
    }

    override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room {
        KeyAdmission.requireSignable(roomId = roomId)
        val device = RoomDevice.of(deviceKeyStore)
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
            device = device,
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            roomId = resolvedRoomId,
            // Host's own room identity — the value a joiner's Hello.targetRoom must
            // match (or leave null) to be admitted. Null (the Pattern default) means
            // this host declared no room and admits permissively.
            roomKey = pattern.roomKey,
            memberInboxCapacity = memberInboxCapacity,
            reconnectControllerFactory = reconnectControllerFactory,
        ).also { room -> room.start() }
    }

    override suspend fun join(tag: Tag, memberName: String?, deviceId: String?): Room {
        KeyAdmission.requireSignable(deviceId = deviceId)
        val device = RoomDevice.of(deviceKeyStore)
        val seam = loom.join(tag)
        return SeamRoom(
            seam = seam,
            role = SessionRole.Joiner,
            // This peer's own roster name. Null → peer-id-derived (see SeamRoom.resolvedMemberName).
            // Deliberately NOT tag.sessionName — the discovered session name names the session being
            // joined, not this joiner (the #1177 latent-bug fix).
            memberName = memberName,
            deviceId = deviceId,
            scope = scope,
            clock = clock,
            device = device,
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            roomId = null,
            // Joiner's declared target room — travels in Hello.targetRoom so the host
            // can reject cross-room admission on a flat fabric. Null (the common case)
            // means the transport already bound the room; the host admits permissively.
            roomKey = tag.roomKey,
            memberInboxCapacity = memberInboxCapacity,
            // Re-weave the same tag on tear. For a resumable [Loom] (e.g. [MuxClientLoom])
            // this heals the same [seam] handle onto a fresh base; for a non-resumable Loom
            // it is a no-op with respect to auto-resume (see the `reweave` KDoc contract).
            reweave = { loom.join(tag) },
        ).also { room -> room.start() }
    }

    /**
     * Adopt an **already-woven** [seam] into a [Room] with an explicit [role] — no re-weave.
     *
     * Unlike [host]/[join] (which each weave through the loom), [adopt] takes ownership of a seam the
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
     * so a transient blip runs the [JoinerResumeMachine] reconnect (wait for `Woven`, say Hello
     * again) instead of going straight to terminal. See the [SeamRoom] `reweave` KDoc for the
     * same-instance-heal contract this must satisfy.
     *
     * [roomId] is this room's identity ([Room.roomId]) and applies to a **host** adopt only — a
     * joiner learns the room's id from the host's `Welcome`. Null — the default — mints a fresh one
     * per adopt, which is what stops two rooms one device adopts in a row from colliding (#1594).
     * Supply it when the identity is decided outside kuilt (a lobby code, an invite link, a
     * matchmaker-assigned game id) and the room must adopt it. It does **not** make a host restart
     * resumable — see [Room.roomId]: a restarted host holds no seats.
     *
     * [deviceId] travels in this peer's `Hello` when it joins, and the host records it as the
     * member's [MemberIdentity.deviceId]. Null, the default, sends none. A host sends no `Hello`, so
     * a host adopt ignores it.
     *
     * @throws IllegalArgumentException if [roomId] is non-null and [role] is [SessionRole.Joiner].
     *   A joiner cannot choose the room's identity, and silently overriding the caller's value with
     *   a different one would fork its durable records with no signal. Also when [roomId] or
     *   [deviceId] cannot be signed into the key-proving exchange: it holds an unpaired surrogate, or
     *   its UTF-8 is longer than 65 535 bytes. That is checked whatever the [role], before the seam
     *   is touched.
     */
    public suspend fun adopt(
        seam: Seam,
        role: SessionRole,
        memberName: String? = null,
        roomKey: String? = null,
        reweave: (suspend () -> Seam)? = null,
        roomId: RoomId? = null,
        deviceId: String? = null,
    ): Room {
        // Rejected, not ignored. A joiner learns the room's identity from the host's Welcome, so a
        // supplied id here is a claim this peer is not entitled to make — and silently dropping it
        // is the worse failure: a caller following the supply-your-own-id pattern would land on the
        // joiner branch, get a DIFFERENT identity than it asked for, and fork its durable records
        // with no signal at all. Fail fast instead. (The hypothetical decide-role-at-runtime caller
        // does not exist today: SeamElectionLobby.adoptRoom passes no roomId. If one appears, that
        // is the moment to relax this — with a test.)
        require(roomId == null || role == SessionRole.Host) {
            "roomId applies to a host adopt; a joiner learns it from the host's Welcome"
        }
        KeyAdmission.requireSignable(roomId = roomId, deviceId = deviceId)
        val device = RoomDevice.of(deviceKeyStore)
        val resolvedRoomId = if (role == SessionRole.Host) roomId ?: mintRoomId(seam) else null
        return SeamRoom(
            seam = seam,
            role = role,
            memberName = memberName,
            deviceId = deviceId,
            scope = scope,
            clock = clock,
            device = device,
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            roomId = resolvedRoomId,
            roomKey = roomKey,
            memberInboxCapacity = memberInboxCapacity,
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
     *   virtual test clock, and so the sequence needs no durable storage: it restarts at `0` after a
     *   process restart, but the timestamp has moved, so a fresh run cannot re-mint an id an earlier
     *   run used. (That is a statement about *collisions*, not about resume: see [Room.roomId].)
     *   Deliberately *not* a [kotlin.random.Random] — a required parameter would be a ~96-call-site
     *   mechanical diff, and a defaulted `Random.Default` would be un-injected randomness, banned here.
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
            deviceKeyStore: DeviceKeyStore,
            heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
            admitTimeout: Duration = DEFAULT_ADMIT_TIMEOUT,
            memberInboxCapacity: Int = DEFAULT_MEMBER_INBOX_CAPACITY,
            reconnectControllerFactory: JoinerReconnectControllerFactory? = null,
        ): SeamRoomFactory = SeamRoomFactory(
            loom = loom,
            scope = scope,
            clock = { Clock.System.now() },
            deviceKeyStore = deviceKeyStore,
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            memberInboxCapacity = memberInboxCapacity,
            reconnectControllerFactory = reconnectControllerFactory,
        )

        /**
         * Default joiner admit deadline (#1178). Generous enough to cover a relay round-trip and
         * host-side admit work on a slow link, short enough that a dropped/refused `Hello` fails
         * loudly in seconds rather than hanging. Override per deployment via the [admitTimeout]
         * constructor parameter.
         */
        internal val DEFAULT_ADMIT_TIMEOUT: Duration = 30.seconds

        /**
         * Default per-member inbox depth (#2802) — how many unread frames a room this factory builds
         * holds for each member's [Room.incomingFrom]. Override per deployment via the
         * [memberInboxCapacity] constructor parameter, which `0` turns off entirely.
         *
         * Deliberately not a public constant: the depth a room was built with is a property of that
         * room, not a number a consumer should read off the factory type and reason about.
         */
        internal val DEFAULT_MEMBER_INBOX_CAPACITY: Int = 64
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
 * Width, in bytes, of the random membership id a host mints per admission (#3215): 128 bits, so two
 * admissions never share one by chance.
 */
private const val MEMBERSHIP_ID_BYTES = 16

/**
 * Buffer depth of [Room.incoming] — how far behind a subscriber of that one shared stream may fall
 * before [SeamRoom] drops what it cannot hand over.
 *
 * Its own number, deliberately: the per-member hold has its own depth
 * ([SeamRoomFactory.memberInboxCapacity]) that a consumer sets, and for a while both read off one
 * public constant — which made turning the per-member hold off look as though it would shrink this too.
 */
private const val INCOMING_BUFFER = 64

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
 *   mesh peer that detects locally *and* receives the fan-out emits once. A pause ended by a
 *   resume fans out the same [AdmitMessage.Unpaused], so those members see `Recovered` (#2908).
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
 * `hostPeerId`, `incomingCollectJob`, `admissionFailed`, `admitDeadlineJob`, `detectors`,
 * `channelViews`, `admitLanes`, `relayLanes`, `episodeDetectedAtMs`) is guarded by an atomicfu
 * [reentrantLock]. The joiner-side reconnect state (`pendingResume`, `reconnecting`,
 * `reconnectJob`) lives in [JoinerResumeMachine], which **shares the same lock instance**
 * (see [resumeMachine]).
 * Critical sections perform only synchronous map/field operations; all suspend calls (sends,
 * broadcasts, re-weave, awaiting `Woven`, resume) are made outside the lock.
 *
 * [start] must be called by [SeamRoomFactory] after construction to launch these loops.
 */
// Shared constant roster for a room whose seam carries no attestation concept — never mutated.
private val EMPTY_ATTESTED_ROSTER: StateFlow<Map<PeerId, Principal>> =
    MutableStateFlow<Map<PeerId, Principal>>(emptyMap())

/**
 * Which partition episode a refined reconnect deadline is *about* — the discriminator
 * `SeamRoom.refineWindow` needs to reject an announcement that belongs to an episode the member
 * already recovered from and re-entered (#1781).
 *
 * A sealed pair rather than a nullable instant, because the two sources genuinely differ and the
 * difference is worth reading at each call site: a `null` would say only "no check here" and would
 * hide which of the two lanes is unprotected and why.
 */
private sealed interface WindowEpisode {
    /**
     * The announcement names the episode detected at [atMs] — the value the controller was handed
     * in [JoinerReconnectController.onPeerUnresponsive] and echoed back on
     * [JoinerReconnectEvent.WindowOpened].
     */
    data class Detected(val atMs: Long) : WindowEpisode

    /**
     * The announcement carries no episode identity: an inbound [AdmitMessage.Paused], whose wire
     * format has no such field. That lane is protected instead by the per-recipient admit lane
     * (#1800/#2048), which makes the host's own enqueue order the wire order, so a refinement cannot
     * be overtaken by the estimate that preceded it. What remains uncovered there is a *lost*
     * refinement — the fan-out is best-effort and there is no anti-entropy behind it — which the
     * drop log exists for and which episode identity on the wire would be the fix for.
     */
    data object Unidentified : WindowEpisode
}

/**
 * What `SeamRoom.refineWindow` decided about one incoming reconnect deadline.
 *
 * It exists so the decision can be reached under the lock and *acted on* outside it — specifically
 * so [StaleEpisode], the only outcome that discards a value a consumer supplied, can be logged
 * rather than vanishing into a bare `return` from the critical section (#1781).
 */
private sealed interface RefineVerdict {
    /** The deadline moved; announce it (and, on a host, re-fan it). */
    data object Applied : RefineVerdict

    /** The member is not [Liveness.Partitioned] — recovered, or gone. The documented no-op. */
    data object NotPartitioned : RefineVerdict

    /** The announced deadline is the one already held. Nothing to say. */
    data object Unchanged : RefineVerdict

    /**
     * The announcement named partition episode [announced] while this room holds [held] (null when
     * it holds none) — so the deadline belongs to an episode that is over, and applying it would
     * move the level backwards.
     *
     * Both instants are carried rather than a flag because they are what tells the two causes apart:
     * a real late announcement leaves [held] at some *other* genuine detection instant, whereas a
     * hold policy that fails to echo the `at` it was handed produces an [announced] that matches no
     * [held] this room will ever record.
     */
    data class StaleEpisode(val held: Long?, val announced: Long) : RefineVerdict
}

/**
 * A membership `SeamRoom.detachMember` removed, whose inbox is closed once the caller has released the
 * room lock: closing can resume a collector inline, which may call back into the room. A re-admission
 * owns a different inbox. Closed after the departure is published.
 */
private class DetachedMember(private val inbox: MemberInbox?) {
    fun close() {
        inbox?.close()
    }
}

/**
 * One admitted peer's liveness [detector] and the [job] that owns its coroutines, held as one entry
 * so that neither can be registered, or removed, without the other. The job identifies the
 * membership a `PeerLost` is about; the detector is read for its
 * [HeartbeatPartitionDetector.lastHeard], the key → seat table's last contact (#3213), so the room
 * stamps no time of its own under its lock on every inbound frame.
 */
private class DetectorHandle(val job: Job, val detector: HeartbeatPartitionDetector)

/**
 * What `SeamRoom.handleWindowExpired` decided about one [JoinerReconnectEvent.WindowExpired].
 *
 * The sibling of [RefineVerdict], and it exists for a stronger reason than that one does: the two
 * things an expiry drives — an authoritative `Farewell` to every other member, and the local
 * eviction — used to be two statements with one guard between them, so a recovery landing in the
 * gap evicted a healthy member everywhere but the host (#2556). Reaching a single verdict under a
 * single acquisition of the lock is what makes them one decision. The section that decides also
 * evicts and enqueues the `Farewell` on every other member's lane, neither of which suspends; only
 * the inbox close and the log line follow it.
 */
private sealed interface ExpiryVerdict {
    /**
     * The window really did run out for the episode this room holds, and the seat was [evicted] in
     * the section that decided so. Fan out.
     */
    class Expired(val evicted: DetachedMember) : ExpiryVerdict

    /**
     * The member is gone already — normally because the detector's [PartitionEvent.PeerLost] won
     * the race to the same instant. Nothing to evict, but the `Farewell` still goes out: see
     * `SeamRoom.handleWindowExpired` for why gating it on membership would make #1557 a coin toss.
     */
    data object AlreadyGone : ExpiryVerdict

    /**
     * The member is [Liveness.Connected], so the expiry is stale and neither the fan-out nor the
     * eviction may run. Either the membership the window was opened for came back inside it (#2556),
     * or, when [readmitted], that membership is over and a newer one holds the id: a Proof released
     * the seat and seated its joiner afresh before the expiry was handled (#3212).
     */
    data class Recovered(val readmitted: Boolean) : ExpiryVerdict

    /**
     * The expiry named partition episode [announced] while this room holds [held] (null when it
     * holds none), so it belongs to an episode that is over and the member's *current* window has
     * not run out.
     *
     * Both instants are carried, not a flag, for [RefineVerdict.StaleEpisode]'s reason: they are
     * what tells a genuine late expiry apart from a hold policy that fails to echo the `at` it was
     * handed.
     */
    data class StaleEpisode(val held: Long?, val announced: Long) : ExpiryVerdict
}

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
    /**
     * **Joiner only.** The hardware-stable id this peer sends in its [AdmitMessage.Hello], which the
     * host records as the member's [MemberIdentity.deviceId]. Null sends none. A host sends no
     * `Hello`, so a host room ignores it.
     */
    private val deviceId: String? = null,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    /**
     * This device: its key store, and the key already read from it (see [RoomDevice] for why the room
     * never reads the store for its key). Required, so a test that builds a room directly chooses its
     * device just as a factory's caller does. A joiner names [RoomDevice.key] in every
     * [AdmitMessage.Hello], and a host names it in the host-intro [AdmitMessage.Welcome] (#3210).
     */
    private val device: RoomDevice,
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
     * Required for a host — construction throws without one — because a host signs it into every
     * challenge it sends (#3211). Defaults to null for a joiner. [SeamRoomFactory] always passes the
     * host's minted-or-supplied id.
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
     * How many unread frames this room holds per member for [incomingFrom] (#2802), and `0` to hold
     * nothing — see [SeamRoomFactory.memberInboxCapacity], which is where a consumer sets it.
     *
     * Defaulted so tests that construct [SeamRoom] directly still compile; [SeamRoomFactory] always
     * passes its configured value.
     */
    private val memberInboxCapacity: Int = SeamRoomFactory.DEFAULT_MEMBER_INBOX_CAPACITY,
    /**
     * **Joiner only.** Re-weaves the underlying fabric after a transport tear, so the joiner
     * can attempt an in-window resume instead of going straight to terminal
     * [MembershipEvent.HostLost] (#1037).
     *
     * **Required Loom contract — same-instance heal.** Invoking this lambda must *heal the same
     * [seam] instance*: the [Loom] must return a stable, resumable handle whose [Seam.selfId] is
     * frozen and whose underlying channel is re-pointed onto a freshly-woven base. [MuxClientLoom]'s
     * `ResumableChannel` satisfies this: `loom.join(tag)` on a torn base re-weaves the base once and
     * returns the same handle, so this room's [seam] transitions back out of [SeamState.Torn]. A
     * fabric whose seam re-forms by itself without ever reading `Torn` satisfies it by answering a
     * repeat of the rendezvous with that live seam, which is what `NwLoom` does.
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
     * the host's `Welcome` carries the id.
     */
    private val _roomId = MutableStateFlow(roomId)
    override val roomId: StateFlow<RoomId?> = _roomId.asStateFlow()

    /**
     * The id this room was **constructed** with — the host's minted-or-supplied [RoomId], or null
     * for a joiner. Distinct from [roomId], which a joiner moves on admission; this one never
     * moves, so it is what the host-only [reconnectController] is built from.
     */
    private val constructedRoomId: RoomId? = _roomId.value.also { id ->
        require(id != null || role != SessionRole.Host) { "a host room needs a roomId: it signs it into every challenge" }
    }

    /**
     * Guards every mutation of the plain membership state:
     * `admittedById`, `_roster`, `_rosterPeers`, `closed`, `hostLost`, `hostPeerId`, `incomingCollectJob`,
     * `detectors`, `channelViews`, `admitLanes`, `relayLanes`, `episodeDetectedAtMs` — and, shared with
     * [JoinerResumeMachine] (which is handed this same instance), the joiner-side resume state.
     *
     * Multiple coroutines (`runMainLoop`, `runTornWatcher`, the resume machine's reconnect
     * attempt, `runReconnectEventLoop`, per-peer detector collectors, `scope.launch { handleProof }`)
     * may run under a multithreaded dispatcher and all
     * read-modify-write that state. This reentrant lock serialises them.
     *
     * Critical sections are pure synchronous map/field operations (µs); all suspend calls
     * (`seam.sendTo`, `seam.broadcast`) run outside the lock — the lock is never held
     * across a suspension point.
     */
    private val lock = reentrantLock()

    // Admitted members (excluding self), keyed by PeerId for O(1) lookup.
    private val admittedById = mutableMapOf<PeerId, Member>()

    /**
     * The detection instant most recently handed to
     * [JoinerReconnectController.onPeerUnresponsive] for each peer (#1781) — the identity of the
     * partition episode a [JoinerReconnectEvent.WindowOpened] must name to be applied. Guarded by
     * [lock] and written in the same critical section as the level itself; reaped with the member in
     * [detachMember].
     *
     * **Not "the episode of every partitioned member":** it is written only by [markPartitioned], so
     * on a joiner a member paused by an inbound [AdmitMessage.Paused] ([handlePaused]) is
     * [Liveness.Partitioned] with no entry here at all. Harmless, because only
     * [WindowEpisode.Unidentified] reaches [refineWindow] on that path and it never consults this
     * map — but the map and the level do genuinely disagree there, and a reader should not be told
     * otherwise.
     *
     * A [JoinerReconnectEvent.WindowOpened] echoes that instant back as
     * [JoinerReconnectEvent.WindowOpened.detectedAt], and [refineWindow] applies the announcement
     * only when the two agree. Without it the only question available is *"is this peer partitioned
     * now?"*, which is true both for an announcement about the current episode and for one about an
     * episode the peer already recovered from and re-entered — and the latter moves the deadline
     * **backwards**, so a seat counts down to an instant the host is no longer holding it to.
     *
     * **Not [Liveness.Partitioned.since].** Today the two agree on every reachable host path: host
     * re-detection *within* one episode is unreachable — see
     * `WindowLevelTest.reDetectionReEmitsWindowOpenedWithoutRegressingTheDeadline`'s KDoc for why —
     * so `existing` is always null at [markPartitioned] on a host and `since` is always the same
     * `at`. But `since` is a **public contract field defined never to drift** on a re-detection,
     * while [markPartitioned] re-arms the host window from the *new* `at`. Keying this gate on it
     * would couple the gate to a promise made for an unrelated purpose, and would break *silently* —
     * dropping an injected hold policy's refinements (#1614) — the day re-detection becomes
     * reachable. It also stays out of [Liveness.Partitioned] itself: this is bookkeeping about who
     * was told what, not a fact about the member that belongs on the public roster surface.
     *
     * An entry survives recovery unread — [refineWindow] rejects any announcement for a member that
     * is not partitioned before it consults this map — and the next episode overwrites it.
     */
    private val episodeDetectedAtMs = mutableMapOf<PeerId, Long>()

    /**
     * **Host only.** For each partitioned member, the partition episode whose
     * [JoinerReconnectEvent.WindowOpened] this room has seen from its controller: the episode the
     * controller is known to be holding a window for. Written by [refineWindow] when an announcement
     * names the current episode, reaped with the member, guarded by [lock].
     *
     * A Proof's restore reads it ([settleRestore]). Until the controller has announced the current
     * episode's window it may still hold the previous one, spent or expired — the default controller
     * opens a window on a launched coroutine after the partition — and a refusal read from that state
     * is about an episode that is over. The controller's own state cannot be fixed instead: forgetting
     * a spent or expired window would make it answer `WindowNotYetOpen` where a resume must hear
     * `WindowClosed` (#1572).
     */
    private val windowOpenedEpisodeMs = mutableMapOf<PeerId, Long>()

    /**
     * **Host only.** The ids whose seat a new id of the same key took over, so their Hello is answered
     * with `Farewell(supersededBy)` rather than a challenge (#3159, Task 1.7): see [SupersededIds].
     * Guarded by [lock]; written by [supersedeSeat] and [admitMember], read by the Hello gate.
     */
    private val supersededIds = SupersededIds()

    /**
     * **Host only.** For each seat a Proof exchange is asking the window controller about, that
     * exchange's claim, held from the pass that decided to ask until the pass that acts on the
     * answer (#3213). Guarded by [lock]; written only by [claimOrRelease] and [releaseWindowClaim].
     *
     * One asker per seat is what keeps two answers about one seat from existing at once. A new id
     * asks about the seat it would take over, not about itself, so two tabs of one device coming back
     * under two new ids ask about the same seat. Without the claim the second ask is refused, since
     * the first spent the window, and that refusal releases the seat the first exchange was just
     * given. A second exchange that finds the seat claimed does
     * not ask: it waits for the claim to be released ([SeatOutcome.AwaitWindow]) and decides again,
     * against the seat as the first exchange left it.
     *
     * An exchange holds at most one claim, and none while it waits, so two exchanges cannot wait on
     * each other.
     */
    private val windowClaims = mutableMapOf<PeerId, WindowClaim>()

    /**
     * **Host only.** Each current member's membership id: an opaque value minted when the membership
     * was admitted, sent on its direct `Welcome` as [AdmitMessage.Welcome.membership] (#3215). Guarded
     * by [lock].
     *
     * The invariant: `membershipIds.keys` equals `admittedById.keys` whenever [lock] is free.
     * [admitMember] mints an entry in the critical section that admits the member (and none on a
     * closed room, which admits nobody), [detachMember] drops it in the one that removes it, [leave]
     * clears it with the roster, and nothing else writes it. So a restore ([restoreSeat]) sends the id
     * the seat already had, and every new admission, a takeover under a new id included, sends a new
     * one. A joiner that compares the two learns whether its own seat came back.
     *
     * Bounded by the live roster, so a fabric that mints a fresh id per connection does not grow it.
     */
    private val membershipIds = mutableMapOf<PeerId, String>()
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
     * Updated with [_roster] under [lock] by [addToRoster], [detachMember] and [leave].
     * Used by [RoomChannelSeam.peers] to provide the admit-gated peer set to consumers such as
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

    // Deliberately its own number, not [memberInboxCapacity]: this buffers one shared stream for
    // whatever subscribers `incoming` has, while that one bounds a per-member hold. They were briefly
    // sized by a single public constant, which read as if turning the per-member hold off would also
    // shrink this — it does not, and `incoming` keeps its depth at `memberInboxCapacity = 0`.
    private val _incoming = MutableSharedFlow<RoomFrame>(extraBufferCapacity = INCOMING_BUFFER)
    override val incoming: Flow<RoomFrame> = _incoming.asSharedFlow()

    /**
     * Each admitted member's held frames, behind [incomingFrom] (#2802).
     *
     * Empty throughout on a room built with `memberInboxCapacity = 0`, which holds nothing and whose
     * [incomingFrom] refuses rather than hands back an empty stream.
     *
     * An entry is created in [addToRoster] and removed in [detachMember], each in the critical section
     * that changes [admittedById]. Routing is gated on [isAdmittedPeer], so no frame from a member can be
     * routed before its inbox exists; that pairing is the whole "held from admission" guarantee. A removed
     * inbox is [MemberInbox.close]d last: after [lock] is released, because closing can resume its
     * collector in place, and after the member has left the published [roster] and `Left` was emitted, so
     * a reader that sees its flow complete never still sees the member. [leave] closes and clears every entry
     * once the room is terminal, after releasing [lock] — but it emits no `Left` and leaves [roster] as it
     * was, so a reader completed by [leave] can still see its member on the roster. Guarded by [lock].
     */
    private val memberInboxes = HashMap<PeerId, MemberInbox>()

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

    /**
     * Each admitted peer's liveness detector and the job that runs it, keyed by [PeerId]. Guarded by
     * [lock]; written only by [startDetector], [stopDetector] and [leave].
     */
    private val detectors = mutableMapOf<PeerId, DetectorHandle>()

    private var closed = false
    private var hostLost = false

    /** **Joiner only.** The member that took this room's seat over, once the host said so. Guarded by [lock]. */
    private var supersededBy: PeerId? = null

    /**
     * **Joiner only.** The child job running the current [seam]-`incoming` collect.
     *
     * After a re-weave heals [seam] onto a fresh generation, the old collect is still bound to
     * the dead generation (`ResumableChannel.incoming` binds `current()` once at collection start),
     * so it must be cancelled and relaunched or the host's answer to a reconnect is never delivered. Tracked
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

    // ── Key-proving exchange (#3211) ───────────────────────────────────────────

    /**
     * **Host only.** The challenges this host has sent and not yet seen answered, one per fabric
     * sender, each open for [admitTimeout]. Thread-safe on its own lock; [seatProvenJoiner] retires an entry
     * under [lock] as well, in the same critical section that admits, so a `Goodbye` (which drops the
     * entry under [lock]) lands either before the admission — and stops it — or after it.
     */
    private val pendingChallenges = PendingChallenges(clock)

    /**
     * **Joiner only.** The nonce of this joiner's latest `Hello`, which a challenge must echo to be
     * answered, and which a direct `Welcome` or a `Reject` must name ([AdmitMessage.Welcome.answers],
     * [AdmitMessage.Reject.answers]) for this joiner to act on it (#3215). The rest of an admission
     * burst names it too, and a burst frame naming another Hello is dropped (#3615). Null before the first
     * `Hello`, and from a reconnect episode's claim until its first Hello is minted
     * ([JoinerResumeHost.forgetLatestHello]), so an answer to the Hello before the episode cannot
     * close or end it. Guarded by [lock].
     */
    private var latestHelloNonce: ByteArray? = null

    /**
     * **Joiner only.** The membership id the host named on the direct `Welcome` that last admitted
     * this room ([AdmitMessage.Welcome.membership], #3215), or null before the first one. A `Welcome`
     * that answers a reconnect and names this id is the seat coming back; any other id is a fresh
     * admission, and replaces it. Guarded by [lock].
     */
    private var membership: String? = null

    /**
     * **Joiner only.** Every member a host `Welcome` has named since [latestHelloNonce] was minted
     * (#3615): the members the host vouches for in the admission that answers that Hello. A
     * reconnect's admission burst names every member the host holds, and a member admitted after the
     * host built the burst is named by its own roster `Welcome`, which can reach this room on its
     * fan-out lane before the burst does. Counting from the Hello rather than from the answer keeps
     * that member. A member leaves the count when it leaves the roster ([detachMember]), so the count
     * never holds more than the roster does between Hellos. Guarded by [lock].
     */
    private val namedSinceHello = mutableSetOf<PeerId>()

    /**
     * **Joiner only.** A reconnect closed and its admission burst is still arriving (#3615). The
     * host's own `Welcome` comes last in the burst; when the one naming the latest Hello arrives,
     * [rebuildRoster] drops every member the host has not named since that Hello. Cleared by that, or
     * by the next Hello. Guarded by [lock].
     */
    private var rosterRebuildPending = false

    /**
     * **Joiner only.** Each fabric sender whose challenge this joiner verified, and the key it signed
     * that challenge with. A `Welcome` is accepted only from a sender here, and a host intro only when
     * it names this key, so the host's entry on a joiner's roster carries a key the host proved.
     * Guarded by [lock].
     *
     * Several senders can be here on a flat fabric: a co-joiner's stray challenge is answered too, since
     * it costs nothing, and proves only that the co-joiner holds some key. [hostPeerId] is still the
     * first sender here whose `Welcome` arrives.
     */
    private val verifiedHostKeys = mutableMapOf<PeerId, DidKey>()

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
     * **Joiner only.** The reconnect state machine (#1122): owns the single-flight slot (#1280),
     * the reconnect guard, and
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

                    override fun isTerminal(): Boolean = lock.withLock { closed || hostLost || supersededBy != null }

                    override fun isClosed(): Boolean = lock.withLock { closed || supersededBy != null }

                    override fun prepareReHello(): ByteArray? = lock.withLock {
                        // Inherited obligation 4 (#3159, Task 1.7): the gate and the nonce in one
                        // critical section, so a room told it was superseded mints no Hello after.
                        if (closed || hostLost || supersededBy != null) return null
                        AdmitMessage.encode(newHello())
                    }

                    override fun forgetLatestHello() {
                        lock.withLock { latestHelloNonce = null }
                    }

                    override fun roomId(): String? = _roomId.value?.value

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

                    override fun claimHostLost(): Boolean = this@SeamRoom.claimHostLost()

                    override suspend fun onReconnectFailed(at: Instant, reason: FailureReason) =
                        announceHostLost(at, reason)
                },
            )
        } else {
            null
        }

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
     * - [JoinerReconnectEvent.Resumed] → logged and nothing more: every restore the controller is
     *   asked about comes from a Proof, which has already restored the seat and announced it in order
     *   with its Welcome burst (#3215).
     * - [JoinerReconnectEvent.WindowExpired] → [handleWindowExpired], which decides *once* whether
     *   this expiry is one to act on and, in the same section, back-stops the local eviction
     *   (#1618 Track C) and enqueues the authoritative [AdmitMessage.Farewell] (#1557). No extra
     *   *local* event is emitted here: the [MembershipEvent.Left] comes out of [detachMember],
     *   whether this arm or the [HeartbeatPartitionDetector]'s [PartitionEvent.PeerLost] gets there
     *   first.
     */
    private suspend fun runReconnectEventLoop(ctrl: JoinerReconnectController) {
        ctrl.events.collect { event ->
            when (event) {
                is JoinerReconnectEvent.WindowOpened ->
                    refineWindow(
                        event.peerId,
                        Instant.fromEpochMilliseconds(event.expiresAt),
                        WindowEpisode.Detected(event.detectedAt),
                    )
                // A Proof restored the seat and announced it in order with its Welcome burst
                // ([seatProvenJoiner]); this is the controller's echo of that restore (#3215).
                is JoinerReconnectEvent.Resumed ->
                    logger.debug { "room.controller.resumed peer=${event.peerId.value}: restored by its proof already" }
                is JoinerReconnectEvent.WindowExpired ->
                    handleWindowExpired(event)
            }
        }
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
     * generation, so the host's answer to a re-Hello — buffered in the new channel's spool — is delivered.
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
                    if (heartbeatSendersSeen.add(sender)) detectors.containsKey(sender) else null
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
                    val keyed = msg.keyed()
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
                                    answers = msg.joinerNonce,
                                ),
                            )
                            runCatchingCancellable { seam.sendTo(sender, rejectBytes) }
                        }
                    } else if (keyed == null) {
                        // The v3 half of the same gate (#3210). The key and nonce are optional on
                        // the wire only so that a v2 Hello still decodes and reaches the version
                        // check above; a Hello that declares v3 carries both, or it does not speak
                        // the version it declares.
                        logger.debug { "Rejecting Hello from $sender: protocol-mismatch (v3 hello without a key or nonce)" }
                        scope.launch {
                            val rejectBytes = AdmitMessage.encode(
                                AdmitMessage.Reject(
                                    "protocol-mismatch: a v${msg.protocolVersion} hello names a device key and a joiner nonce",
                                    RejectCode.ProtocolMismatch,
                                    answers = msg.joinerNonce,
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
                                    answers = keyed.joinerNonce,
                                ),
                            )
                            runCatchingCancellable { seam.sendTo(sender, rejectBytes) }
                        }
                    } else {
                        val successor = lock.withLock { seatedSuccessorFor(sender, keyed.key) }
                        if (successor != null) {
                            // A superseded process saying Hello again, usually its reconnect after a
                            // link that was dead when Decided 19's direct Farewell went (#3159, Task
                            // 1.7). Tell it again rather than challenge it: admitted, it would take an
                            // additional seat of its key and the superseded membership would be back.
                            logger.info { "room.hello.superseded peer=${sender.value} by=${successor.value}" }
                            scope.launch {
                                val farewell = AdmitMessage.Farewell(sender.value, supersededBy = successor.value)
                                runCatchingCancellable { seam.sendTo(sender, AdmitMessage.encode(farewell)) }
                            }
                        } else {
                            challengeJoiner(sender, keyed)
                        }
                    }
                }
            }
            is AdmitMessage.Challenge -> {
                if (_role.value == SessionRole.Joiner) {
                    scope.launch { answerChallenge(sender, msg) }
                }
            }
            is AdmitMessage.Proof -> {
                if (_role.value == SessionRole.Host) {
                    // Taken here, in frame order, and only checked on the launched coroutine: a proof
                    // always answers the challenge that was current when it arrived. Taken inside the
                    // launch, a proof delayed past its sender's Goodbye and next Hello would take
                    // that newer challenge and, failing its nonce, refuse the joiner's honest answer.
                    //
                    // A proof for an older challenge (#3159, Task 1.7) — the sender said Hello again, or a
                    // stale Hello on another path replaced the challenge it answers — leaves the current
                    // one untaken and is dropped here, never refused: see [PendingChallenges.take]. Its
                    // nonce is read without the signature, which is checked only against a match.
                    val answering = AdmissionProof.decode(msg.statement)?.hostNonce
                    val pending = pendingChallenges.take(sender, answering)
                    if (pending == null) {
                        logger.debug { "room.proof.dropped peer=${sender.value}: no open challenge it answers" }
                    } else {
                        scope.launch { handleProof(sender, msg, pending) }
                    }
                }
            }
            is AdmitMessage.Welcome -> {
                if (_role.value == SessionRole.Joiner) {
                    handleWelcome(sender, msg)
                }
            }
            is AdmitMessage.Goodbye -> {
                if (_role.value == SessionRole.Host) {
                    // Under the room lock, so it orders against [settleSeat]'s retire-and-admit: a
                    // Goodbye between a Challenge and its Proof leaves nothing to admit (#3211).
                    lock.withLock {
                        pendingChallenges.drop(sender)
                        stopDetector(sender)
                    }
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
                if (_role.value == SessionRole.Joiner) handleReject(sender, msg)
            }
            null -> { /* malformed frame — ignore */ }
        }
    }

    /**
     * **Host only.** Seat a joiner that has just proved its device key: consult the key → seat table
     * and act on it (#3159, Task 1.5).
     *
     * [keyed] is the Hello the gate let through, whose joiner has since proved [KeyedHello.key]
     * ([handleProof]); [generation] is the pending challenge that proof answered.
     *
     * The table is read under [lock] **at Proof time**, never at Challenge time, because a `Goodbye`
     * or a window expiry can land between the two. What [decideSeat] answers:
     * - [SeatDecision.Restore] on a **connected** seat: the same membership, restored without asking
     *   the window controller, which would answer `WindowNotYetOpen` for a window never opened.
     * - [SeatDecision.Restore] on a **partitioned** seat: the controller is asked, outside the lock,
     *   through a [ProvenResume] — built here, after the key check, which is the only place the Proof
     *   path builds one. `Success` restores the seat. `WindowNotYetOpen` means the window has not
     *   opened yet, not that it closed: the seat is still held, and it is restored as a connected one
     *   would be. Any other verdict means the seat is gone: it is released, and the key is decided
     *   again without it (Decided 23).
     * - [SeatDecision.RestoreUnderNewId]: the key's partitioned seat, under another id, is taken over
     *   once the controller is asked about it ([settleRestoreUnderNewId], #3213). The old member
     *   leaves with [LeaveReason.Superseded], the joiner joins with its identity and key, the other
     *   members are sent `Farewell(old, supersededBy = new)` and then the new member's roster
     *   Welcome, and the old member is sent the same `Farewell` directly (Decided 19), so a room
     *   still running there ends instead of saying Hello again. A refusal releases the seat as above.
     * - [SeatDecision.KeyConflict]: a terminal `Reject`.
     * - [SeatDecision.ReleaseAndAdmit]: the partitioned seat is released, then the joiner is admitted
     *   as the re-decision says (Decided 18, 30).
     * - every other verdict admits a new member ([admitNewMember]): another tab of a device whose
     *   seats are all connected gets a seat of its own (Decided 30).
     *
     * A restore sends the joiner the Welcome burst with its direct Welcome marked
     * [ReturnKind.Restored] and naming the membership id the seat already had. It emits
     * [MembershipEvent.Resumed] only when it ends a partition: once per partition, and never for a
     * connected seat (#3215). It tells the other members nothing new, except the `Unpaused` that ends
     * a partitioned seat's hold. A release is a [LeaveReason.PartitionExpired]
     * `Left` and an expired `Farewell` to the others, ahead of the new member's `Joined` and roster
     * Welcome on the same lanes.
     *
     * **Every decision is acted on in the critical section that took it** ([settleSeat]); only what
     * cannot run under [lock] — asking the controller, closing an inbox, sending — runs between
     * passes, and the next pass decides again from the seats as they are then. A release removes the
     * seat in the same section that chose to, so a heartbeat that heals the seat a moment later finds
     * nothing to heal, and one that healed it a moment earlier is seen by the decision (Decided 18,
     * 30). A challenge no longer current (a `Goodbye`, a newer `Hello` from the same sender, its
     * deadline) seats nobody.
     *
     * **A window `Success` is acted on in the next pass, or its seat is ended there**
     * ([settleSpentWindow]). The answer spends the window and stops its timer, so nothing else will
     * ever end that seat: no expiry fans its `Farewell` out, and a later ask is refused. If the pass
     * neither takes the seat over nor restores it — the challenge went stale, or the seat was lost or
     * healed meanwhile — a seat still held in that episode is released, and one already gone has its
     * expired `Farewell` sent to the other members, so no star bystander is left holding it (#1557).
     *
     * **One exchange asks about a seat at a time** ([windowClaims]); another waits until that one has
     * acted on its answer, then decides again.
     *
     * The loop ends: a release leaves the claimed id holding no seat, so it releases at most once, the
     * controller is asked again only about a partition episode it was not asked about before, and a
     * wait ends when another exchange's pass does.
     */
    private suspend fun seatProvenJoiner(joinerPeerId: PeerId, keyed: KeyedHello, generation: Long) {
        val exchange = SeatExchange()
        try {
            seatProvenJoiner(joinerPeerId, keyed, generation, exchange)
        } finally {
            // Every pass that does not ask releases the claim already; this covers a throw, a
            // cancellation, and the controller's own failure.
            lock.withLock { releaseWindowClaim(exchange) }
        }
    }

    /**
     * **Test-visibility.** Run in [seatProvenJoiner] once [settleSeat]'s critical section has decided
     * a pass and before the exchange acts on it, given the outcome's class name. It is the gap a
     * thread can stall in between a decision and the controller call that reports it (#3675), and a
     * test suspends here to stand in for that stall. Null in production.
     */
    @Volatile
    internal var seatSettledForTest: (suspend (outcome: String) -> Unit)? = null

    private suspend fun seatProvenJoiner(joinerPeerId: PeerId, keyed: KeyedHello, generation: Long, exchange: SeatExchange) {
        var asked: AskedWindow? = null
        var released: PeerId? = null
        while (true) {
            val seating = settleSeat(joinerPeerId, keyed, generation, asked, released, exchange)
            seatSettledForTest?.invoke(seating::class.simpleName.orEmpty())
            when (seating) {
                SeatOutcome.Stale -> return logStaleAdmission(joinerPeerId)
                is SeatOutcome.AwaitWindow -> {
                    logger.debug { "room.admit.await-window peer=${joinerPeerId.value} seat=${seating.seat.value}" }
                    // This exchange has moved on from the seat its answer was about, and released its
                    // claim on that seat, so another exchange may end the seat while this one waits.
                    // An answer about it must never be acted on after that.
                    if (asked?.peerId != seating.seat) asked = null
                    seating.released.await()
                }
                is SeatOutcome.SpentWindowSettled -> {
                    seating.detached?.close()
                    if (seating.detached != null) released = seating.seat
                    asked = null
                }
                is SeatOutcome.AskWindow -> {
                    // As for a wait: an answer about a seat this exchange has moved on from is dropped.
                    if (asked?.peerId != seating.peerId) asked = null
                    val answer = askWindow(seating.peerId, keyed.key)
                    if (answer == WindowAnswer.Failed) {
                        if (pendingChallenges.retire(joinerPeerId, generation)) {
                            val reject = AdmitMessage.Reject("host-failed: the host could not consult its reconnect window", RejectCode.HostFailed, keyed.joinerNonce)
                            runCatchingCancellable { seam.sendTo(joinerPeerId, AdmitMessage.encode(reject)) }
                        }
                        return
                    }
                    asked = AskedWindow(seating.peerId, seating.episode, answer)
                }
                is SeatOutcome.Released -> {
                    seating.detached.close()
                    // Its window is disarmed, since its expiry has nothing left to evict.
                    reconnectController?.onPeerRecovered(seating.peerId, clock().toEpochMilliseconds())
                    released = seating.peerId
                    asked = null
                }
                is SeatOutcome.Refused -> {
                    runCatchingCancellable { seam.sendTo(joinerPeerId, AdmitMessage.encode(seating.reject)) }
                    return
                }
                is SeatOutcome.ToldSuperseded -> {
                    val farewell = AdmitMessage.Farewell(joinerPeerId.value, supersededBy = seating.successor.value)
                    runCatchingCancellable { seam.sendTo(joinerPeerId, AdmitMessage.encode(farewell)) }
                    return
                }
                is SeatOutcome.Restored -> {
                    // Its `Unpaused` was enqueued in the section that restored the seat ([restoreSeat]).
                    if (seating.endedPause) {
                        // The window this partition opened, or is about to open, is not needed any
                        // more. A window `Success` spent needs nothing.
                        if (!asked.spent(joinerPeerId)) {
                            reconnectController?.onPeerRecovered(joinerPeerId, clock().toEpochMilliseconds())
                        }
                    }
                    seating.burst?.let { sendAdmissionBurst(it, keyed.joinerNonce, ReturnKind.Restored) }
                    return
                }
                is SeatOutcome.Admitted -> {
                    sendAdmissionBurst(seating.burst, keyed.joinerNonce, returnKind = null)
                    return
                }
                is SeatOutcome.Superseded -> {
                    seating.detached.close()
                    // A window `Success` is spent; any other answer left it to open, or open already,
                    // over a seat that is gone, and its expiry would only re-announce the departure.
                    if (!asked.spent(seating.previous)) {
                        reconnectController?.onPeerRecovered(seating.previous, clock().toEpochMilliseconds())
                    }
                    sendAdmissionBurst(seating.burst, keyed.joinerNonce, ReturnKind.Restored)
                    // Decided 19: told directly, since the other members' fan-out excludes its subject.
                    // Last, and best-effort: its link is the one that went quiet, and a send into it
                    // must not hold the new member's Welcomes back.
                    val farewell = AdmitMessage.Farewell(seating.previous.value, supersededBy = joinerPeerId.value)
                    runCatchingCancellable { seam.sendTo(seating.previous, AdmitMessage.encode(farewell)) }
                        .onFailure { logger.debug { "room.supersede.farewell-unsent peer=${seating.previous.value}: $it" } }
                    return
                }
            }
        }
    }

    private fun logStaleAdmission(joinerPeerId: PeerId) {
        logger.debug { "room.admit.stale peer=${joinerPeerId.value}: its challenge is no longer current" }
    }

    /** What the window controller said about a partitioned seat a proof asked to restore. */
    private enum class WindowAnswer {
        /** The window was open, and is spent on this restore. */
        Restored,

        /** No window has opened yet: the seat is held, and the controller is merely behind. */
        NotYetOpen,

        /** The window refused: the seat is gone, if the refusal is about this partition episode. */
        Refused,

        /** The controller threw. */
        Failed,
    }

    /** The controller's [answer] about [peerId]'s seat, in its partition episode [episode]. */
    private class AskedWindow(val peerId: PeerId, val episode: Long, val answer: WindowAnswer)

    /** Whether this answer spent [peerId]'s window: a `Success` about that seat. */
    private fun AskedWindow?.spent(peerId: PeerId): Boolean = this?.peerId == peerId && answer == WindowAnswer.Restored

    /**
     * Ask the window controller to restore [peerId]'s partitioned seat for a joiner that proved [key].
     * The controller may be the consumer's own hold policy (#1614), so it is asked outside [lock], and
     * a throw is caught here: on Kotlin/Native an escape from this launched coroutine aborts the
     * process. The [ProvenResume] names this room's own id, so the controller's own room check can
     * never refuse it; the proof already named this room ([KeyAdmission.checkProof]).
     */
    private suspend fun askWindow(peerId: PeerId, key: DidKey): WindowAnswer {
        // Only a host holds seats, and a host always has both.
        val ctrl = checkNotNull(reconnectController) { "only a host restores a seat, and a host has a reconnect controller" }
        val roomId = checkNotNull(constructedRoomId) { "only a host restores a seat" }
        val verdict = try {
            ctrl.tryResume(ProvenResume(peerId, roomId, key), at = clock().toEpochMilliseconds())
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            logger.warn(failure) { "room.restore.window-failed peer=${peerId.value}" }
            null
        }
        val answer = when (verdict) {
            ResumeResult.Success -> WindowAnswer.Restored
            ResumeResult.WindowNotYetOpen -> WindowAnswer.NotYetOpen
            ResumeResult.WindowClosed -> WindowAnswer.Refused
            null -> WindowAnswer.Failed
        }
        return answer
    }

    /** What [settleSeat] decided, for [seatProvenJoiner] to act on outside [lock]. */
    private sealed interface SeatOutcome {
        /** The challenge is no longer current: seat nobody. */
        data object Stale : SeatOutcome

        /**
         * Ask the window controller about [peerId]'s partitioned seat in its partition [episode], then
         * decide again. [peerId] is the claimed id for a same-id restore, and the seat being taken
         * over for a restore under a new id.
         */
        class AskWindow(val peerId: PeerId, val episode: Long) : SeatOutcome

        /**
         * [peerId]'s held seat was released, and the other members' `Farewell` enqueued, in the deciding
         * critical section: close its inbox, then decide again.
         */
        class Released(val peerId: PeerId, val detached: DetachedMember) : SeatOutcome

        class Refused(val reject: AdmitMessage.Reject) : SeatOutcome

        /** The joiner's id was superseded by [successor] under the key it proved: tell it, seat nobody. */
        class ToldSuperseded(val successor: PeerId) : SeatOutcome

        /**
         * The held seat came back. [endedPause]: the seat was partitioned until now. [burst] is null
         * when the challenge stopped being current, so a newer exchange owes the joiner its Welcomes.
         */
        class Restored(val burst: AdmissionBurst?, val endedPause: Boolean) : SeatOutcome

        /** A new member was added. */
        class Admitted(val burst: AdmissionBurst) : SeatOutcome

        /**
         * [previous]'s partitioned seat was taken over by the joiner under its new id, in the deciding
         * critical section: [previous] left, its `Farewell` was enqueued to the other members, and the
         * joiner was added as [burst]'s member. Close [detached], send the burst, and tell [previous].
         */
        class Superseded(val previous: PeerId, val detached: DetachedMember, val burst: AdmissionBurst) : SeatOutcome

        /**
         * Another exchange is asking the controller about [seat] ([windowClaims]): wait for [released],
         * then decide again.
         */
        class AwaitWindow(val seat: PeerId, val released: CompletableDeferred<Unit>) : SeatOutcome

        /**
         * A window `Success` spent on [seat] that this pass did not act on was settled in the deciding
         * critical section ([settleSpentWindow]): the seat was released ([detached] non-null) or, gone
         * already, its expired `Farewell` was enqueued to the other members. Close [detached], forget
         * the answer, and decide again.
         */
        class SpentWindowSettled(val seat: PeerId, val detached: DetachedMember?) : SeatOutcome
    }

    /** One run of [seatProvenJoiner], the identity its [WindowClaim] is held under. */
    private class SeatExchange {
        /** The seat this exchange holds the [windowClaims] claim on, if any. Guarded by [lock]. */
        var claimed: PeerId? = null
    }

    /** [exchange]'s claim on asking about one seat; [released] completes once it has acted on the answer. */
    private class WindowClaim(val exchange: SeatExchange) {
        val released = CompletableDeferred<Unit>()
    }

    /**
     * What a joiner is sent once it is seated: its [member] entry, the members it did not know
     * ([existing]), and the id of the membership it holds ([membershipIds], #3215).
     */
    private class AdmissionBurst(val member: Member, val existing: List<Member>, val membership: String)

    /**
     * **Host only; the critical section of [seatProvenJoiner].** Decide against the seats as they are
     * now and act on the decision, all under one acquisition of [lock]: retire the challenge and
     * change the roster to match, release a seat, or say what must happen outside the lock first.
     *
     * [asked] is the controller's answer, when it was asked, and the partition episode it was asked
     * about. [released] is the seat an earlier pass released, if any.
     */
    private fun settleSeat(
        joinerPeerId: PeerId,
        keyed: KeyedHello,
        generation: Long,
        asked: AskedWindow?,
        released: PeerId?,
        exchange: SeatExchange,
    ): SeatOutcome = lock.withLock {
        val current = pendingChallenges.isCurrent(joinerPeerId, generation)
        // 2.6: host-side grant verification feeds this (plan amendment bf12e0ffc); revocation is checked from 2.4.
        val decision = decideSeat(joinerPeerId, keyed.key, heldSeats(), holdsValidGrant = false)
        val outcome = settleSpentWindow(joinerPeerId, current, decision, asked) ?: toldSuperseded(joinerPeerId, keyed, generation, asked) ?: when (decision) {
            is SeatDecision.Restore -> settleRestore(joinerPeerId, generation, current, asked)
            is SeatDecision.ReleaseAndAdmit ->
                if (current) SeatOutcome.Released(decision.released, releaseHeldSeat(decision.released)) else SeatOutcome.Stale
            SeatDecision.KeyConflict -> if (retire(joinerPeerId, generation)) {
                SeatOutcome.Refused(
                    AdmitMessage.Reject("key-conflict: ${joinerPeerId.value} is held by another device", RejectCode.KeyConflict, keyed.joinerNonce),
                )
            } else {
                SeatOutcome.Stale
            }
            is SeatDecision.RestoreUnderNewId -> settleRestoreUnderNewId(joinerPeerId, generation, current, decision.previous, asked)
            SeatDecision.AdditionalSeat, SeatDecision.Returning, SeatDecision.Ordinary ->
                if (retire(joinerPeerId, generation)) admitNewMember(joinerPeerId, keyed) else SeatOutcome.Stale
        }
        claimOrRelease(exchange, outcome).also { settled ->
            if (released != null && (settled is SeatOutcome.Admitted || settled is SeatOutcome.Superseded)) {
                logger.info { "room.seat.released peer=${released.value} readmitted=${joinerPeerId.value}" }
            }
        }
    }

    /**
     * **Under [lock].** The id that took [peerId]'s seat over under [key], while it still holds that
     * seat; else null. A successor that has left holds no membership for [peerId]'s Hello to take
     * back, so [peerId] is then challenged as any Hello is (review round 1 on #3614). The record is
     * kept, so [peerId] is told again should its successor be seated again.
     */
    private fun seatedSuccessorFor(peerId: PeerId, key: DidKey): PeerId? =
        supersededIds.successorFor(peerId, key)?.takeIf { it in admittedById }

    /**
     * **Under [lock].** A superseded process whose Hello was challenged before its seat was taken over
     * (#3159, Task 1.7): the Hello gate answers every later Hello from that id with the `Farewell`,
     * and this is the same answer for the one that slipped in first. Read under the lock that recorded
     * the supersession ([supersedeSeat]), so whichever comes first, the old process is not seated.
     *
     * Null when [asked] holds a spent window `Success`: that answer must be acted on, and an exchange
     * whose key proved itself and won a window keeps it. [settleSpentWindow] runs first and clears
     * any such answer this pass does not act on, so the next pass reaches here.
     */
    private fun toldSuperseded(joinerPeerId: PeerId, keyed: KeyedHello, generation: Long, asked: AskedWindow?): SeatOutcome? {
        if (asked?.answer == WindowAnswer.Restored) return null
        val successor = seatedSuccessorFor(joinerPeerId, keyed.key) ?: return null
        return if (retire(joinerPeerId, generation)) SeatOutcome.ToldSuperseded(successor) else SeatOutcome.Stale
    }

    /**
     * **Under [lock].** A window `Success` ([asked]) spent the window on its seat and stopped the
     * seat's timer, so that seat can end only here. Null when [decision] acts on the answer itself — a
     * same-id restore of the seat, or, for a current challenge, its takeover under the new id — or
     * there is no spent answer to settle. Otherwise:
     * - the seat is still held, partitioned, in the episode the answer was about: it is released
     *   ([releaseHeldSeat]), with its expired `Farewell` enqueued to the other members in this
     *   section. Nobody can restore it now: the next ask, same id or new, is refused.
     * - the seat is gone: its expired `Farewell` is enqueued here, as [ExpiryVerdict.AlreadyGone]
     *   does, so a star bystander does not keep it. The departure this is written for is its
     *   detector's `PeerLost`, which tells no one because an expiry normally does, and this window will
     *   never expire. Others have announced themselves already — a [SeatDecision.ReleaseAndAdmit] by a
     *   claimant under another key, or a newer episode's expiry — and then the `Farewell` is a repeat,
     *   which a bystander drops. A takeover by another exchange can only follow a newer episode's ask,
     *   and an exchange that moves on to ask about or wait for another seat forgets its answer first
     *   ([seatProvenJoiner]), so a `Superseded` departure is never followed by an expired one.
     * - the seat healed, or was partitioned again in a newer episode: null. A healed seat is a member
     *   who is here, and a newer episode has a window of its own whose expiry fans out.
     *
     * Enqueued in the deciding section, the `Farewell` precedes on every lane whatever a later pass
     * enqueues, the new member's roster `Welcome` included (#1781).
     */
    private fun settleSpentWindow(joinerPeerId: PeerId, current: Boolean, decision: SeatDecision, asked: AskedWindow?): SeatOutcome? {
        if (asked?.answer != WindowAnswer.Restored) return null
        val seat = asked.peerId
        val actsOnIt = when (decision) {
            is SeatDecision.Restore -> decision.peerId == seat
            is SeatDecision.RestoreUnderNewId -> current && decision.previous == seat
            else -> false
        }
        if (actsOnIt) return null
        val held = admittedById[seat]
        return when {
            held == null -> {
                propagateFarewell(seat, expired = true)
                logger.info { "room.window.spent-seat-gone peer=${seat.value} episode=${asked.episode} joiner=${joinerPeerId.value}" }
                SeatOutcome.SpentWindowSettled(seat, detached = null)
            }
            held.liveness is Liveness.Partitioned && episodeDetectedAtMs[seat] == asked.episode -> {
                logger.info { "room.window.spent-seat-released peer=${seat.value} episode=${asked.episode} joiner=${joinerPeerId.value}" }
                SeatOutcome.SpentWindowSettled(seat, releaseHeldSeat(seat))
            }
            else -> null
        }
    }

    /**
     * **Under [lock].** Hold [exchange]'s claim on the seat an [SeatOutcome.AskWindow] asks about
     * ([windowClaims]), or, when another exchange holds it, turn the ask into
     * [SeatOutcome.AwaitWindow]. Every other outcome acts on the last answer, so it releases the
     * exchange's claim in the section that acted.
     */
    private fun claimOrRelease(exchange: SeatExchange, outcome: SeatOutcome): SeatOutcome {
        if (outcome !is SeatOutcome.AskWindow) {
            releaseWindowClaim(exchange)
            return outcome
        }
        val holder = windowClaims[outcome.peerId]
        return when {
            holder == null -> {
                releaseWindowClaim(exchange)
                windowClaims[outcome.peerId] = WindowClaim(exchange)
                exchange.claimed = outcome.peerId
                outcome
            }
            holder.exchange === exchange -> outcome
            else -> {
                releaseWindowClaim(exchange)
                SeatOutcome.AwaitWindow(outcome.peerId, holder.released)
            }
        }
    }

    /** **Under [lock].** Release [exchange]'s claim, if it holds one, and wake whoever waits on it. */
    private fun releaseWindowClaim(exchange: SeatExchange) {
        val seat = exchange.claimed ?: return
        exchange.claimed = null
        val claim = windowClaims[seat]
        if (claim?.exchange === exchange) {
            windowClaims.remove(seat)
            claim.released.complete(Unit)
        }
    }

    /**
     * **Under [lock].** [SeatDecision.Restore] for [peerId], against its seat as it is now.
     *
     * - A `Success` restores the seat even when the challenge has stopped being current: the window is
     *   spent, and a newer exchange from the same joiner must find the seat connected rather than ask
     *   for it again. Only a current challenge is sent its burst.
     * - A **connected** seat — never partitioned, or healed by a heartbeat since the controller was
     *   asked — is restored without the controller: there is no window to consult, and a refusal about
     *   a partition that is over does not release a member who is here (Decided 18, 30).
     * - A **partitioned** seat needs the controller's answer about its current partition episode, and
     *   is asked again if the answer it has was about an earlier one.
     * - `WindowNotYetOpen` restores: the seat is held, and the controller is behind.
     * - A refusal releases the seat, but only once this room has seen the controller's `WindowOpened`
     *   for the episode ([windowOpenedEpisodeMs]). Before that the controller may still hold the
     *   previous episode's window, spent or expired, and its refusal is about that one: the default
     *   controller opens a window on a launched coroutine after the partition, so a Proof can land in
     *   between. Read as "not open yet", it restores.
     */
    private fun settleRestore(peerId: PeerId, generation: Long, current: Boolean, askedAny: AskedWindow?): SeatOutcome {
        val held = checkNotNull(admittedById[peerId]) { "a Restore names a held seat" }
        // An answer about another seat — one an earlier pass meant to take over under this id — says
        // nothing about this one.
        val asked = askedAny?.takeIf { it.peerId == peerId }
        if (asked?.answer == WindowAnswer.Restored) return restoreSeat(held, owesBurst = retire(peerId, generation))
        if (!current) return SeatOutcome.Stale
        if (held.liveness !is Liveness.Partitioned) return restoreSeat(held, owesBurst = retire(peerId, generation))
        val episode = checkNotNull(episodeDetectedAtMs[peerId]) { "a host records the episode of every seat it partitions" }
        if (asked == null || asked.episode != episode) return SeatOutcome.AskWindow(peerId, episode)
        return when (asked.answer) {
            WindowAnswer.Refused -> if (windowOpenedEpisodeMs[peerId] == episode) {
                SeatOutcome.Released(peerId, releaseHeldSeat(peerId))
            } else {
                logger.debug { "room.restore.refusal-before-window peer=${peerId.value} episode=$episode: read as not open yet" }
                restoreSeat(held, owesBurst = retire(peerId, generation))
            }
            WindowAnswer.NotYetOpen -> restoreSeat(held, owesBurst = retire(peerId, generation))
            // Handled before either can reach here: a Success above, a throw by [seatProvenJoiner].
            WindowAnswer.Restored, WindowAnswer.Failed -> error("window answer ${asked.answer} is settled before the seat is")
        }
    }

    /**
     * **Under [lock].** [SeatDecision.RestoreUnderNewId]: the joiner's key holds the partitioned seat
     * [previous], and the joiner claimed an id with no seat (#3213; Decided 19, 30).
     *
     * The controller is asked about [previous]'s current partition episode, as a same-id restore asks
     * about its own (see [settleRestore], whose rules about a refusal and `WindowNotYetOpen` this
     * follows). A `Success`, a `WindowNotYetOpen`, or a refusal before this room has seen the
     * episode's `WindowOpened` takes the seat over ([supersedeSeat]); a refusal after it releases the
     * seat, and the next pass decides again without it (Decided 23).
     *
     * Unlike a same-id restore, a `Success` for a challenge that is **no longer current** seats
     * nobody. A same-id restore can bring its seat back and leave the newer exchange to find it
     * connected, but here there is no seat under the claimed id to bring back, and the exchange may
     * have ended in a `Goodbye`: taking the seat over then would admit a peer that has left. The
     * window is spent all the same, so nobody else can restore the seat either, and
     * [settleSpentWindow] releases it in that pass, before this is reached: the other members hear
     * its expired `Farewell` then, and a later Hello from the sender is an ordinary admission.
     */
    private fun settleRestoreUnderNewId(
        joinerPeerId: PeerId,
        generation: Long,
        current: Boolean,
        previous: PeerId,
        askedAny: AskedWindow?,
    ): SeatOutcome {
        if (!current) return SeatOutcome.Stale
        val held = checkNotNull(admittedById[previous]) { "a RestoreUnderNewId names a held seat" }
        check(held.liveness is Liveness.Partitioned) { "a RestoreUnderNewId names a partitioned seat" }
        val episode = checkNotNull(episodeDetectedAtMs[previous]) { "a host records the episode of every seat it partitions" }
        val asked = askedAny?.takeIf { it.peerId == previous && it.episode == episode } ?: return SeatOutcome.AskWindow(previous, episode)
        return when (asked.answer) {
            WindowAnswer.Restored, WindowAnswer.NotYetOpen -> supersedeSeat(joinerPeerId, generation, held)
            WindowAnswer.Refused -> if (windowOpenedEpisodeMs[previous] == episode) {
                SeatOutcome.Released(previous, releaseHeldSeat(previous))
            } else {
                logger.debug { "room.supersede.refusal-before-window peer=${previous.value} episode=$episode: read as not open yet" }
                supersedeSeat(joinerPeerId, generation, held)
            }
            // A throw is settled by [seatProvenJoiner] before the seat is.
            WindowAnswer.Failed -> error("window answer ${asked.answer} is settled before the seat is")
        }
    }

    /**
     * **Under [lock].** Take [held]'s partitioned seat over for [joinerPeerId], all in this critical
     * section: [held] leaves with [LeaveReason.Superseded], the other members' `Farewell` naming the
     * successor is enqueued, and the joiner is added with [held]'s identity and key. The joiner
     * inherits the membership, so the identity is the one the seat was admitted with, not this
     * Hello's, as [restoreSeat] keeps it for a same-id restore.
     *
     * The order is the one #1781 needs on every lane: the `Farewell` is enqueued here, before the
     * roster `Welcome` [admitMember] enqueues for the new member later in this section, so no
     * member hears `Welcome(new)` and then `Farewell(old)` for a membership that has moved, and every
     * member present for it reads `Left(old, Superseded(new))`, then `Joined(new)`. A member whose
     * link was down hears neither: its admission burst names `new`, which it hears as `Joined(new)`,
     * and its roster rebuild drops `old` later with [LeaveReason.Unobserved] (#3615).
     */
    private fun supersedeSeat(joinerPeerId: PeerId, generation: Long, held: Member): SeatOutcome {
        if (!retire(joinerPeerId, generation)) return SeatOutcome.Stale
        val previous = held.id
        val detached = checkNotNull(detachMember(previous, LeaveReason.Superseded(by = joinerPeerId))) {
            "the superseded seat was read under this lock"
        }
        fanOutToOtherMembers(previous, AdmitMessage.Farewell(previous.value, supersededBy = joinerPeerId.value))
        // Before the joiner is added, which forgets its own id should it have been superseded once.
        supersededIds.record(previous, held.key, successor = joinerPeerId)
        logger.info { "room.seat.superseded peer=${previous.value} by=${joinerPeerId.value}" }
        val admitted = admitMember(joinerPeerId, held.identity, held.key)
        return SeatOutcome.Superseded(previous, detached, admitted.burst)
    }

    /** [PendingChallenges.retire], read as "this exchange still owes its joiner an answer". */
    private fun retire(joinerPeerId: PeerId, generation: Long): Boolean = pendingChallenges.retire(joinerPeerId, generation)

    /**
     * **Under [lock].** The seats the host holds for its joiners, as [decideSeat] reads them. The host's
     * own seat is never among them: [admittedById] holds other peers only.
     *
     * [Seat.lastContact] is the member's detector's [HeartbeatPartitionDetector.lastHeard], which
     * breaks the tie between several partitioned seats of one key (Decided 30). Only partitioned
     * seats are compared, and on a host a seat is partitioned only by its own detector, so the
     * fallbacks are reached only where the detector has already stopped and the seat is about to go:
     * between a `Goodbye`'s [stopDetector] and its removal, and during [leave]'s tear-down. There a
     * partitioned seat falls back to when its partition was noticed, and a connected one, which is
     * never compared, to the distant past.
     */
    private fun heldSeats(): Map<PeerId, Seat> = admittedById.mapValues { (id, member) ->
        val partitioned = member.liveness as? Liveness.Partitioned
        Seat(
            key = member.key,
            liveness = if (partitioned != null) SeatLiveness.Partitioned else SeatLiveness.Connected,
            lastContact = detectors[id]?.detector?.lastHeard ?: partitioned?.since ?: Instant.DISTANT_PAST,
        )
    }

    /**
     * **Under [lock].** Bring [held] — the seat the decision just read, under this same acquisition —
     * back as [Liveness.Connected], with the member it was admitted as: its identity, key and
     * principal belong to the membership, not to this Hello. So a restore keeps the held member's
     * identity, and a rebuilt process's new `displayName` and `deviceId` are dropped. It keeps its
     * membership id too ([membershipIds]), which is how the joiner tells this from a fresh admission.
     * [owesBurst]: this exchange still owes the joiner its Welcome burst.
     */
    private fun restoreSeat(held: Member, owesBurst: Boolean): SeatOutcome {
        val member = checkNotNull(updateMemberLiveness(held.id, Liveness.Connected)) { "the seat was read under this lock" }
        val endedPause = held.liveness is Liveness.Partitioned
        // Enqueued in the section that ended the pause, for [markPartitioned]'s reason (#3625): after
        // it, a re-detection deciding in between enqueues its `Paused` first, and this `Unpaused` then
        // releases, on every other member, a seat the host holds `Partitioned`. Only a host restores
        // a seat, so there is no role to check. Before the `Resumed` below, which
        // `PausedFanOutOrderConcurrencyTest` parks on to hold this thread inside the section.
        if (endedPause) propagateUnpaused(held.id)
        // In the section that restored the seat, like [markRecovered]'s `Recovered`, so a heal the
        // decision saw is announced before this restore and never after it. Once per partition
        // (#3215): only a restore that ends a pause is a recovery. A connected seat restored — a
        // retry whose earlier attempt the host already answered, or a restart faster than the host's
        // detector — was never seen to go, so there is nothing to announce.
        if (endedPause) emitEvent(MembershipEvent.Resumed(held.id))
        val burst = if (owesBurst) AdmissionBurst(member, admittedById.values.filter { it.id != held.id }, membershipOf(held.id)) else null
        return SeatOutcome.Restored(burst, endedPause = endedPause)
    }

    /**
     * **Under [lock].** Release a held seat the key → seat table gave up on, in the critical section
     * that decided to, so the joiner's new membership is a `Joined` rather than a silent re-admission
     * of the same id: its `Left` carries [LeaveReason.PartitionExpired], since the hold ended without
     * its member coming back. The expired `Farewell` to every other member is enqueued in this section
     * too, so it precedes on each lane whatever a later section enqueues about the id, the new
     * member's roster `Welcome` included (#1781). Enqueued after the lock, a second Proof's admission
     * could slip its `Welcome` in first. The caller closes the inbox.
     */
    private fun releaseHeldSeat(peerId: PeerId): DetachedMember {
        val detached = checkNotNull(detachMember(peerId, LeaveReason.PartitionExpired)) { "the released seat was read under this lock" }
        propagateFarewell(peerId, expired = true)
        return detached
    }

    /**
     * **Under [lock].** Add a new member for [joinerPeerId], from [keyed]: the Hello its joiner sent,
     * and the key it proved, which becomes [Member.key] and rides every Welcome that names the joiner.
     */
    private fun admitNewMember(joinerPeerId: PeerId, keyed: KeyedHello): SeatOutcome.Admitted {
        val hello = keyed.hello
        val identity = MemberIdentity(displayName = hello.displayName, sessionId = hello.sessionId, deviceId = hello.deviceId)
        return admitMember(joinerPeerId, identity, keyed.key)
    }

    /**
     * **Under [lock].** Add [joinerPeerId] as a connected member with [identity] and [key], and enqueue
     * its roster `Welcome` on every other member's fan-out lane (#3630).
     *
     * The enqueue is in this section, the one that admits, and so before the suspending direct
     * `Welcome` to the joiner ([sendAdmissionBurst]). Whatever a later section decides about the new
     * member is then enqueued behind it on every lane: a `Paused` from its detector, or the `Farewell`
     * of its `Goodbye`. After the send, either could reach a bystander first. A `Paused` for a member
     * the bystander did not hold yet was dropped, and the `Welcome` then added the member as
     * connected. A `Farewell` was dropped the same way, and the `Welcome` then added a member that
     * had left: on a star the bystander has no heartbeat to it, so that ghost stays.
     *
     * The bystanders may now hear of the new member before the joiner hears its own `Welcome`. A
     * relayed frame from a bystander can then reach the joiner before the joiner holds that
     * bystander, and the joiner drops it (`origin-not-admitted`). That window was already open
     * before: the joiner learns the other members only from the burst's member `Welcome`s, which
     * followed the roster copy. It is now one send wider.
     */
    private fun admitMember(joinerPeerId: PeerId, identity: MemberIdentity, key: DidKey): SeatOutcome.Admitted {
        val member = Member(
            id = joinerPeerId,
            identity = identity,
            key = key,
            liveness = Liveness.Connected,
            // Prefer the map-keyed roster (hub path: RoomHubSeam / Mesh keys a principal per peer),
            // falling back to the single-value marker (2-peer relay path: Seam.withPrincipal).
            principal = (seam as? PrincipalRoster)?.attestedPrincipals?.value?.get(joinerPeerId)
                ?: (seam as? PrincipalAttested)?.principal,
        )
        val existing = admittedById.values.toList()
        addToRoster(member)
        supersededIds.forget(joinerPeerId)
        // Minted in the SAME critical section that admits the member, keeping the invariant on
        // [membershipIds]. `closed`: addToRoster admitted nobody, so there is no member to name.
        val id = newMembershipId()
        if (!closed) membershipIds[joinerPeerId] = id
        // No-op once closed: [fanOutToOtherMembers] checks [closed] too, and addToRoster admitted nobody.
        fanOutToOtherMembers(joinerPeerId, rosterWelcome(member))
        return SeatOutcome.Admitted(AdmissionBurst(member, existing, id))
    }

    /**
     * The `Welcome` that names [member] to the others: its roster copy, which names neither a Hello
     * nor a membership, since both are the joiner's business alone. The direct `Welcome` to the
     * joiner is a copy of it that adds them.
     */
    private fun rosterWelcome(member: Member): AdmitMessage.Welcome = AdmitMessage.Welcome(
        assignedPeerId = member.id.value,
        displayName = member.identity.displayName,
        sessionId = member.identity.sessionId,
        key = member.key,
        deviceId = member.identity.deviceId,
        roomId = _roomId.value?.value,
    )

    /** **Under [lock].** [peerId]'s membership id, which a held seat always has ([membershipIds]). */
    private fun membershipOf(peerId: PeerId): String =
        checkNotNull(membershipIds[peerId]) { "every admitted member has a membership id" }

    /**
     * A fresh membership id: [MEMBERSHIP_ID_BYTES] bytes from the platform CSPRNG, in hex. Opaque to
     * the joiner, which only compares it, so it need be unique within a host's life and nothing more;
     * random rather than a counter so that a restarted host can never repeat one an old process gave.
     */
    private fun newMembershipId(): String =
        CryptoRand.Default.nextBytes(ByteArray(MEMBERSHIP_ID_BYTES)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * Send a seated joiner what it needs, outside [lock]: its direct Welcome (marked [returnKind],
     * naming the Hello it [answers] and the membership), then a Welcome for each member it did not
     * know, then the host's own intro, which names the same Hello.
     *
     * The direct Welcome is the ONE frame that names the joiner's membership, and the one that
     * answers its Hello (#3215). Every other frame of the burst names the Hello too, but only to say
     * which burst it belongs to (#3615): a joiner drops a member Welcome or an intro from a burst
     * answering an older Hello, and takes the intro of the latest one as the end of its burst, never
     * as the answer.
     * These sends stay INLINE: they address the joiner, not the bystanders, so they share no
     * recipient with any queued fan-out and have no order to keep against one.
     *
     * The roster copy to the other members is not sent here. [admitMember] enqueues it on their
     * fan-out lanes in the section that admits, before this first suspends (#3630). A restored seat
     * sends no roster copy at all, since every other member already holds it.
     */
    private suspend fun sendAdmissionBurst(burst: AdmissionBurst, answers: ByteArray, returnKind: ReturnKind?) {
        val member = burst.member
        val welcome = rosterWelcome(member)
        val direct = AdmitMessage.encode(welcome.copy(returnKind = returnKind, answers = answers, membership = burst.membership))
        runCatchingCancellable { seam.sendTo(member.id, direct) }

        for (existing in burst.existing) {
            // Names the Hello the burst answers, as the intro does, so a joiner can drop it when it
            // arrives after the joiner has said a newer Hello (#3615): an earlier burst's view of who
            // is a member may be stale by then.
            val existingWelcome = AdmitMessage.encode(
                AdmitMessage.Welcome(
                    assignedPeerId = existing.id.value,
                    displayName = existing.identity.displayName,
                    sessionId = existing.identity.sessionId,
                    key = existing.key,
                    deviceId = existing.identity.deviceId,
                    answers = answers,
                ),
            )
            runCatchingCancellable { seam.sendTo(member.id, existingWelcome) }
        }

        // The intro closes this burst, and names the Hello the burst answers, so a joiner can tell it
        // from a late intro of an earlier burst to the same joiner: bursts run outside the lock, so
        // two of them can interleave on its link (#3615). It carries no membership, so it is never
        // the answer to that Hello: only the direct Welcome, which names the joiner, is.
        val hostIntro = AdmitMessage.encode(
            AdmitMessage.Welcome(
                assignedPeerId = selfId.value,
                displayName = resolvedMemberName,
                sessionId = selfId.value,
                key = device.key.id,
                answers = answers,
            ),
        )
        runCatchingCancellable { seam.sendTo(member.id, hostIntro) }
    }

    /** The first admission's Hello, broadcast: this joiner does not know its host yet. */
    private suspend fun sendHello() {
        val hello = lock.withLock { newHello() }
        runCatchingCancellable { seam.broadcast(AdmitMessage.encode(hello)) }
    }

    /**
     * **Joiner only; under [lock].** A Hello with a fresh joiner nonce, which becomes
     * [latestHelloNonce] here — before the Hello goes, so the challenge answering it, and the
     * `Welcome` or `Reject` that answers the Hello, always find it (#3211, #3215).
     */
    private fun newHello(): AdmitMessage.Hello {
        val hello = AdmitMessage.Hello(
            displayName = resolvedMemberName,
            sessionId = selfId.value,
            deviceId = deviceId,
            targetRoom = roomKey,
            protocolVersion = ProtocolVersion.CURRENT,
            key = device.key.id,
            // Fresh on every Hello, from the platform CSPRNG: the challenge built over it is
            // single-use only if the nonce is never reused (#3210).
            joinerNonce = CryptoRand.Default.nextBytes(ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE)),
        )
        latestHelloNonce = hello.joinerNonce
        // A new Hello starts a new count of who the host names, and a burst that never closed is
        // not closed by a later one's intro.
        namedSinceHello.clear()
        rosterRebuildPending = false
        return hello
    }

    /**
     * **Host only.** Open a challenge for [sender]'s Hello, then sign it and send it (#3211).
     *
     * The entry is opened here, synchronously in frame order, and only the signing runs on a launched
     * coroutine. Opening inside the launch would let two Hellos from one sender open out of order, so
     * the table could hold the older nonce while the joiner answers the newer challenge, and its honest
     * proof would be refused.
     */
    private fun challengeJoiner(sender: PeerId, keyed: KeyedHello) {
        val hostNonce = CryptoRand.Default.nextBytes(ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE))
        val generation = pendingChallenges.open(sender, keyed, hostNonce, clock() + admitTimeout)
        scope.launch { sendChallenge(sender, keyed, hostNonce, generation) }
    }

    /**
     * Sign and send the challenge [challengeJoiner] opened. A host that cannot sign — a platform key
     * whose provider fails, a room id a layout cannot carry — refuses the joiner with a retryable
     * `Reject` and retires the entry. The failure is never let out of this launched coroutine: on
     * Kotlin/Native an escaped throw aborts the process, and the joiner would hear nothing.
     */
    private suspend fun sendChallenge(sender: PeerId, keyed: KeyedHello, hostNonce: ByteArray, generation: Long) {
        val statement = try {
            KeyAdmission.signChallenge(device.key, signedRoomId(), selfId, sender, keyed.joinerNonce, hostNonce)
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            logger.warn(failure) { "room.challenge.sign-failed peer=${sender.value}" }
            if (pendingChallenges.retire(sender, generation)) {
                val reject = AdmitMessage.Reject("host-failed: the host could not sign its challenge", RejectCode.HostFailed, keyed.joinerNonce)
                runCatchingCancellable { seam.sendTo(sender, AdmitMessage.encode(reject)) }
            }
            return
        }
        runCatchingCancellable { seam.sendTo(sender, AdmitMessage.encode(AdmitMessage.Challenge(statement))) }
            .onFailure { logger.debug { "room.challenge.send-failed peer=${sender.value}: $it" } }
    }

    /**
     * The room id a host's challenge names and its proofs must sign: the id it was constructed with,
     * which construction refuses to leave null for a host. **Host only.**
     */
    private fun signedRoomId(): String = checkNotNull(constructedRoomId) { "only a host signs a room id" }.value

    /**
     * **Host only.** Check [sender]'s proof against [pending], the challenge it answers, and admit on
     * a valid one (#3211).
     *
     * - **Nothing pending** — never opened, expired, dropped, or already taken by a proof whose check
     *   is in flight — never reaches here: the frame dispatch drops it silently. A refusal would be
     *   terminal for a joiner whose first proof is about to admit it.
     * - **A proof that fails any check** is refused with [RejectCode.BadProof], unless a newer Hello
     *   from the same sender has replaced the challenge meanwhile, which a refusal of the old one must
     *   not kill.
     * - **A check that cannot run** (the platform's provider fails) is refused retryably.
     *
     * The proof is checked under the key the Hello named, in this room's own scheme, so a room on
     * P-256 checks with ECDSA whatever a joiner sends.
     */
    private suspend fun handleProof(sender: PeerId, msg: AdmitMessage.Proof, pending: PendingChallenges.Pending) {
        val fault = try {
            KeyAdmission.checkProof(
                statement = msg.statement,
                pending = pending,
                roomId = signedRoomId(),
                hostKey = device.key.id,
                hostPeerId = selfId,
                sender = sender,
                scheme = device.scheme,
            )
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            logger.warn(failure) { "room.proof.check-failed peer=${sender.value}" }
            if (pendingChallenges.retire(sender, pending.generation)) {
                val reject = AdmitMessage.Reject("host-failed: the host could not check the proof", RejectCode.HostFailed, pending.keyed.joinerNonce)
                runCatchingCancellable { seam.sendTo(sender, AdmitMessage.encode(reject)) }
            }
            return
        }
        if (fault != null) {
            logger.info { "room.proof.refused peer=${sender.value} fault=$fault" }
            if (pendingChallenges.retire(sender, pending.generation)) {
                val reject = AdmitMessage.Reject("bad-proof: $fault", RejectCode.BadProof, pending.keyed.joinerNonce)
                runCatchingCancellable { seam.sendTo(sender, AdmitMessage.encode(reject)) }
            }
            return
        }
        seatProvenJoiner(sender, pending.keyed, pending.generation)
    }

    /**
     * **Joiner only.** Answer [sender]'s challenge with a proof, if it is one to answer: it echoes this
     * joiner's latest Hello nonce, names this joiner, and is signed by the key it names (#3211).
     *
     * Every challenge is answered on its own, signing [sender] — the fabric sender — as the host's peer
     * id. A failure to sign or check is logged and the challenge goes unanswered; the admit deadline
     * then ends the join, which is the honest outcome for a device that cannot use its own key.
     */
    private suspend fun answerChallenge(sender: PeerId, msg: AdmitMessage.Challenge) {
        val nonce = lock.withLock { latestHelloNonce?.copyOf() }
        val answer = try {
            KeyAdmission.answerChallenge(msg.statement, nonce, selfId, sender, device.scheme, device.key, deviceId)
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            logger.warn(failure) { "room.challenge.answer-failed from=${sender.value}" }
            return
        }
        if (answer == null) {
            logger.debug { "room.challenge.ignored from=${sender.value}: not an answerable challenge" }
            return
        }
        lock.withLock { verifiedHostKeys[sender] = answer.hostKey }
        runCatchingCancellable { seam.sendTo(sender, AdmitMessage.encode(AdmitMessage.Proof(answer.proof.wire))) }
            .onFailure { logger.debug { "room.proof.send-failed to=${sender.value}: $it" } }
    }

    /**
     * **Joiner only.** A host's `Reject` (#3215). The joiner acts on it only when it names this
     * joiner's latest Hello ([AdmitMessage.Reject.answers]); a refusal of any other exchange — an
     * impostor's, sent to the id it claimed, or a bystander's forgery — is ignored. One that does
     * name it means one of two things:
     * - a reconnect under way → the machine takes it ([JoinerResumeMachine.rejectFlight]): a terminal
     *   code ends the episode, a retryable one schedules another Hello (#1572, #2364);
     * - the first admission → the host refused it; fail loudly (#1178) instead of leaving `join()`'s
     *   consumer hanging.
     *
     * One exception, before the first admission only: a [RejectCode.ProtocolMismatch] that names no
     * Hello. A host older than v7 cannot name one, and this is what it says to a v7 Hello; ignoring
     * it would turn its answer into an admit deadline.
     *
     * Whether the `Reject` names the latest Hello, and the flight that Hello's attempt owns, are read
     * in one critical section, so a new Hello cannot be minted between the two.
     */
    private fun handleReject(sender: PeerId, reject: AdmitMessage.Reject) {
        val failsAdmission = lock.withLock {
            val answersLatest = answersLatestHello(reject.answers)
            rejectCheckedForTest?.invoke(answersLatest)
            when {
                answersLatest -> resumeMachine?.rejectFlight(reject.reason, reject.code) != true
                reject.answers == null && reject.code == RejectCode.ProtocolMismatch && !admitted.isCompleted -> true
                else -> {
                    logger.info { "room.reject.ignored from=${sender.value} code=${reject.code.id}: it answers no Hello this joiner is waiting on" }
                    false
                }
            }
        }
        if (failsAdmission) {
            scope.launch { failAdmission(AdmissionFailure.Rejected(reject.reason, reject.code), clock()) }
        }
    }

    /**
     * **Test-visibility.** Run on the frame thread inside [handleReject]'s critical section, between
     * the check of the Hello a `Reject` names ([answersLatestHello], whose answer it is given) and the
     * flight take: the one point a concurrency test can park to force a caller's attempt to land
     * between the two. Null in production. A test that sets it, sets it before the room handles the
     * `Reject` it means to park, and may clear it once the room handles no more.
     */
    @Volatile
    internal var rejectCheckedForTest: ((answersLatest: Boolean) -> Unit)? = null

    /**
     * Joiner-side: handle a [AdmitMessage.Welcome].
     *
     * The host sends Welcome both for the joiner themselves (confirming their own admission)
     * and for each existing member (bootstrapping the joiner's roster view).
     * Either way, add the described peer to our roster if not already there.
     *
     * The **first** Welcome this member accepts identifies its host: every Welcome is minted by
     * the host, so that first sender is the host, and it is recorded for [HostLost] detection
     * (#1994). Only a sender whose challenge this joiner verified can send one it accepts, and the
     * host intro must name the key that challenge proved (#3211). Waiting for the self-introduction shape instead left the host unidentified across
     * the whole admit burst — see the comment on the assignment below, which also states what
     * that narrowing costs on a flat loom.
     *
     * The self-admission welcome (`assignedPeerId == selfId`) counts only when it names this
     * joiner's latest Hello ([AdmitMessage.Welcome.answers], #3215): then it admits the room, and may
     * close a reconnect ([closeReconnect]). It does not add self to the roster. Whether it closes the
     * reconnect as a restore is read from its membership id, against the one this room already
     * holds ([membership]), never from its `returnKind`.
     */
    private fun handleWelcome(sender: PeerId, welcome: AdmitMessage.Welcome) {
        // Members a closing burst dropped (#3615): their inboxes are closed once the lock is released.
        val dropped = mutableListOf<DetachedMember>()
        // One critical section: whether the Welcome answers the latest Hello, and the flight that
        // Hello's attempt owns, are read together, as a Reject's are, so a new Hello cannot be minted
        // between the two (review round 2 on #3614).
        val closed = lock.withLock {
            acceptWelcome(sender, welcome, dropped)?.let { restored -> closeReconnect(sender, restored) }
        }
        dropped.forEach { it.close() }
        val (flight, verdict) = closed ?: return
        flight?.complete(verdict)
    }

    /**
     * **Joiner only; under [lock].** Whether [answers] names this joiner's latest Hello (#3215): the
     * only answer it acts on. False when there is no latest Hello, so between a reconnect's claim and
     * its first Hello nothing is an answer.
     */
    private fun answersLatestHello(answers: ByteArray?): Boolean {
        val latest = latestHelloNonce
        return answers != null && latest != null && answers.contentEquals(latest)
    }

    /**
     * **Joiner only; under [lock].** [handleWelcome]'s critical section. Returns null unless this is
     * the host's `Welcome` to this member answering its latest Hello — the only one that may close a
     * reconnect — and otherwise whether it restored this room's own membership: `true` when it names
     * the id this room already held, `false` for a fresh admission, whose id this room holds from now
     * on (#3215).
     *
     * Every other `Welcome` it accepts names a member the host vouches for, counted in
     * [namedSinceHello]. The host's own, which closes an admission burst and names the Hello that
     * burst answers, rebuilds the roster when a reconnect closed on the burst answering the latest
     * Hello ([rebuildRoster], #3615); a member it drops goes into [dropped], whose inboxes the caller
     * closes once it has released [lock].
     */
    private fun acceptWelcome(sender: PeerId, welcome: AdmitMessage.Welcome, dropped: MutableList<DetachedMember>): Boolean? {
        run {
            val assignedId = PeerId(welcome.assignedPeerId)

            // Joiner-side cross-admit hardening (#1180): once we've identified our host, ignore
            // Welcomes from anyone else — a foreign host on a flat loom must not pollute our roster
            // or hijack hostPeerId. The host sends *every* legitimate Welcome (self-admission,
            // bootstrap, host-intro), so they all share the host's sender; a differing sender is
            // foreign. (Cannot gate on Welcome.roomId — bootstrap/host-intro Welcomes carry null.)
            // Complements the #1172 Change A host-side gate that stops foreign Welcomes at the source.
            val establishedHost = hostPeerId
            if (establishedHost != null && sender != establishedHost) return null

            // Key-proving gate (#3211): every Welcome comes from the host, and a host has signed a
            // challenge before it sends one. So a Welcome from a sender whose challenge this joiner
            // has not verified is dropped, whatever its shape, and the host's intro (it names its
            // sender) must carry the very key that challenge was signed with. This is what makes the
            // host's own entry on this roster a proven key; every other entry is host-asserted.
            val provenKey = verifiedHostKeys[sender] ?: return null
            if (assignedId == sender && welcome.key != provenKey) return null

            // Identify the host from the FIRST Welcome we accept, whatever its shape (#1994).
            //
            // Any Welcome is by definition minted by the host, so the sender of the first one IS
            // the host. Keying on the self-introduction instead (`assignedId == sender`) left
            // `hostPeerId` null across `sendAdmissionBurst`'s whole K+1-send burst — this joiner's
            // own Welcome, one bootstrap Welcome per pre-existing member, and only THEN the host
            // intro — while each bootstrap Welcome had already added its member to this roster.
            // Across that window a joiner holds co-members with no identified host, which is both:
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

            // Learn the room's identity (#1594). Placed above the self-admission branch to cover
            // every Welcome shape a joiner accepts.
            //
            // TWO shapes carry a roomId, not one: `rosterWelcome` builds one `welcome` with
            // `roomId = _roomId.value?.value`. `admitMember` hands it to `fanOutToOtherMembers`,
            // which re-encodes it to every other admitted member (the roster-sync Welcome), and
            // `sendAdmissionBurst` sends a copy of it naming the joiner's Hello and its membership to
            // the joiner itself (the self-admission Welcome). Both therefore name the room. The
            // bootstrap and host-intro Welcomes send null. Which shape lands first does not matter: they agree on the value,
            // and the write-once guard in [adoptRoomIdIfAbsent] makes every one after the first a
            // no-op.
            adoptRoomIdIfAbsent(welcome.roomId)

            // Self-admission welcome: it counts only as the answer to this joiner's latest Hello
            // (#3215). An answer to an older one — the Hello before a reconnect, or an attempt this
            // one replaced — admits nothing and closes nothing.
            if (assignedId == selfId) {
                if (!answersLatestHello(welcome.answers)) {
                    logger.debug { "room.welcome.stale from=${sender.value}: it answers no Hello this joiner is waiting on" }
                    return null
                }
                // The host explicitly admitted us — disarm the admit deadline (#1178).
                admitted.complete(Unit)
                val restored = welcome.membership != null && welcome.membership == membership
                welcome.membership?.let { membership = it }
                return restored
            }

            // A burst's member Welcomes and its intro name the Hello the burst answers (#3615). One
            // naming an older Hello comes from an earlier burst, whose view of the roster may be
            // stale by now: a member it names may have left since, so it neither counts nor adds
            // anyone. A roster fan-out copy names no Hello and is always taken.
            if (welcome.answers != null && !answersLatestHello(welcome.answers)) {
                logger.debug { "room.welcome.stale-burst from=${sender.value} names=${assignedId.value}: an earlier burst" }
                return null
            }

            namedSinceHello += assignedId
            // The host's own Welcome is the last of its admission burst (sendAdmissionBurst), and it
            // names the Hello that burst answers. Only the one closing the burst that answered the
            // latest Hello rebuilds: two bursts to this joiner can interleave on the host, and an
            // earlier one's intro landing after the new answer would rebuild on a count that has not
            // yet heard the new burst's members (#3615).
            if (assignedId == sender && rosterRebuildPending && answersLatestHello(welcome.answers)) dropped += rebuildRoster()

            if (admittedById.containsKey(assignedId)) return null // already known
            val identity = MemberIdentity(
                displayName = welcome.displayName,
                sessionId = welcome.sessionId,
                deviceId = welcome.deviceId,
            )
            val member = Member(id = assignedId, identity = identity, key = welcome.key, liveness = Liveness.Connected)
            addToRoster(member)
        }
        return null
    }

    /**
     * **Joiner only; under [lock].** The admission burst a reconnect closed on has ended with the
     * host's own `Welcome` (#3615): drop every member the host has not named since the Hello it
     * answered ([namedSinceHello]). That burst names every member the host holds, so a member this
     * room still lists and the host did not name left while this room was away, and its `Farewell`
     * went down the link that was down. Without this it stays a ghost until something else notices,
     * and on a star nothing does: this room has no heartbeat edge to it.
     *
     * A dropped member leaves with [LeaveReason.Unobserved]: this room did not see it go and cannot
     * say why. [LeaveReason.Normal] would claim the member chose to leave, and
     * [LeaveReason.PartitionExpired] that its link died. Decided and removed in one section, so no
     * `Welcome` naming it lands in between. The host is never dropped: its own `Welcome`, which calls
     * this, has just been counted as named.
     */
    private fun rebuildRoster(): List<DetachedMember> {
        rosterRebuildPending = false
        val gone = admittedById.keys.filter { it !in namedSinceHello }
        if (gone.isNotEmpty()) logger.info { "room.roster.rebuilt self=${selfId.value} dropped=${gone.map { it.value }}" }
        return gone.mapNotNull { detachMember(it, LeaveReason.Unobserved) }
    }

    /**
     * **Joiner only.** Record the host's [RoomId] the first time a `Welcome` carries one.
     *
     * Write-once: the id is the room's identity for its whole life, so a later frame must not be able to move it. A host never reaches here at
     * all — the frame dispatch routes `Welcome` to [handleWelcome] only while [role] is
     * [SessionRole.Joiner] — and the `== null` guard would refuse it anyway, since a host's level is
     * non-null from construction.
     *
     * Callers hold [lock]; the write itself is to a [MutableStateFlow] and needs no further guard.
     */
    private fun adoptRoomIdIfAbsent(value: String?) {
        if (value != null && _roomId.value == null) _roomId.value = RoomId(value)
    }

    /**
     * **Joiner only; under [lock].** The host's `Welcome` to this member answered its latest Hello
     * (#3159, Task 1.7): close the reconnect it was sent for, if one is under way, and take the
     * flight. Returns the flight (null between attempts) and the verdict [handleWelcome] completes it
     * with once it releases the lock, or null when there is neither a flight nor an episode to close.
     * Called in the section that found the Welcome names the latest Hello, so no flight installed
     * after that Hello was forgotten can be taken here.
     *
     * [restored]: the Welcome names the membership this room already held (#3215), so the seat came
     * back, and the room emits [MembershipEvent.Resumed] for itself. Any other membership means the
     * seat was gone and this was a fresh admission, which the room keeps (Amendment C): it emits
     * [MembershipEvent.Readmitted] for itself (#3615), never [MembershipEvent.Recovered], whose
     * meaning is a link that healed before its window expired. The flight then settles
     * [ResumeResult.WindowClosed], which is the truth about the seat; the episode is a success all
     * the same ([JoinerResumeMachine.takeFlightForWelcome]).
     *
     * Either way it arms the roster rebuild ([rosterRebuildPending]): the rest of the burst this
     * Welcome heads names every member the host holds, and the host's own Welcome, last in it and
     * naming the same Hello, drops the members this room holds and the host did not name
     * ([rebuildRoster]).
     *
     * **A terminal room closes nothing (#3094).** A reconnect window that expires leaves its flight
     * installed until [leave] takes it. The `Welcome` and the window's verdict are ordered by [lock],
     * and whichever takes it first wins: a `Welcome` handled before the verdict closes the episode,
     * while a verdict that the window was lost marks the room [hostLost] before it releases the
     * lock. A `Welcome` handled after that finds the room terminal, settles the flight as
     * [ResumeResult.WindowClosed], and emits nothing. The take, the check and the closing edge share
     * one critical section, so no `Resumed` can follow `HostLost`.
     *
     * The host is the only sender a joiner takes a `Welcome` from once it has one ([acceptWelcome]),
     * and the joiner's own `Welcome` closes a reconnect only when it names the latest Hello, which
     * went to the host alone, so neither a foreign host nor a late answer to an older Hello closes
     * one.
     *
     * **Only the reconnect loop's own Hello can close an episode early (#3614 round 3, #3215).** An
     * episode's claim forgets the latest Hello ([JoinerResumeMachine.attemptReconnect]), and the next
     * one is minted only by an attempt of that episode, which [JoinerResumeMachine.runReconnect]
     * makes after it has announced the partition. So between the claim and that announcement no
     * `Welcome` names the latest Hello, and none can close the episode before it has opened.
     */
    private fun closeReconnect(
        sender: PeerId,
        restored: Boolean,
    ): Pair<CompletableDeferred<JoinerOutcome>?, JoinerOutcome>? {
        val (flight, closes) = resumeMachine?.takeFlightForWelcome() ?: return null
        if (closes) {
            updateMemberLiveness(sender, Liveness.Connected)
            // The arc the reconnect opened closes on this room's own membership, either way (#3615).
            emitEvent(if (restored) MembershipEvent.Resumed(selfId) else MembershipEvent.Readmitted(selfId))
            // The rest of this Welcome's burst is on its way; the host's own Welcome closes it.
            rosterRebuildPending = true
        }
        return flight to if (closes && restored) ResumeResult.Success else ResumeResult.WindowClosed
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
     * [admitMember]: the host is the membership authority for joins *and* leaves. Best-effort —
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
     *
     * A Farewell with [AdmitMessage.Farewell.supersededBy] says the same device took the departed
     * member's seat over under a new id (#3213): the departed member leaves with
     * [LeaveReason.Superseded], and the successor's roster Welcome follows on the same lane. One
     * naming **this** room is the host telling it directly that it was superseded (Decided 19), and
     * ends it ([endSuperseded]).
     */
    private fun handleFarewell(sender: PeerId, farewell: AdmitMessage.Farewell) {
        val departed = PeerId(farewell.peerId)
        val successor = farewell.supersededBy?.let(::PeerId)
        lock.withLock {
            val host = hostPeerId
            if (host == null || sender != host || departed == host) return
            if (departed != selfId) stopDetector(departed)
        }
        if (departed == selfId) {
            // Detached, as [failAdmission] is: [leave] cancels the inbound collector this runs on.
            if (successor != null) scope.launch { endSuperseded(successor) }
            return
        }
        removeFromRoster(
            departed,
            when {
                successor != null -> LeaveReason.Superseded(by = successor)
                farewell.expired -> LeaveReason.PartitionExpired
                else -> LeaveReason.Normal
            },
        )
    }

    /**
     * **Joiner only.** The host gave this room's seat to [by], the same device under a new peer id,
     * while this room's link to it was down (#3213; Decided 19). Emit `Left(self, Superseded(by))`
     * and end the room, without a `Goodbye` and without ever saying Hello again: another process of
     * this device holds the membership now, and a Hello from here would take it back and start the
     * two of them taking it in turns (the plan's Review Focus 1). Once only; a room that is already
     * ending does neither.
     *
     * **One ending.** A room already host-lost ends as that, so this and [claimHostLost] exclude each
     * other: whichever takes [lock] first ends the room, and the other emits nothing (review round 1
     * on #3614).
     */
    private suspend fun endSuperseded(by: PeerId) {
        lock.withLock {
            if (closed || hostLost || supersededBy != null) return
            supersededBy = by
        }
        logger.info { "room.superseded self=${selfId.value} by=${by.value}" }
        emitEvent(MembershipEvent.Left(selfId, LeaveReason.Superseded(by)))
        leave(LeaveReason.Superseded(by))
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
        var previous = emptySet<PeerId>()
        seam.peers.collect { emitted ->
            // The emitted list was read before anything here and may already be stale: a sender that
            // left and came back with a fresh Hello since would be "gone" in it. So the list that
            // decides is read again, after marking the challenges: every challenge at or below the
            // mark was opened before that read (#3610).
            val (mark, reachable) = pendingChallenges.markBefore { seam.peers.value }
            lock.withLock {
                // A sender still waiting on its challenge is not a member, so no detector reports it
                // lost. Its leaving the seam is the loss: forget its challenge (#3211). Only a sender
                // that was reachable and no longer is — a Hello may arrive before a fabric lists its
                // sender, and dropping on mere absence would forget that challenge at once. And only a
                // challenge opened before the list was read: the challenges are not under this lock,
                // so a sender back with a fresh Hello while this waits for it opens a newer one, which
                // belongs to its new link (#3610).
                //
                // "Was reachable" counts the emitted list as well as the last read. `collect` decides
                // what to emit next against what it emitted, not against what was read here, so a
                // sender listed in [emitted] and gone by the read would otherwise never be seen leaving
                // (review of #3664).
                for (gone in (previous + emitted) - reachable) pendingChallenges.dropOpenedBy(gone, mark)
                previous = reachable
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
     * by one child [Job], held with the detector in [detectors]. Cancelling that job via [stopDetector]
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
     * `:kuilt-multipeer`) a joiner has no route to a co-joiner, so its pings
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
        if (member.id in detectors) return // steady-state re-evaluation — already monitored, silent
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
        // Registered BEFORE anything is launched (#3609). [handlePartitionEvent] acts on an event
        // only from the detector registered for its peer, and on an unconfined dispatcher the
        // collector launched below can take an event and reach that check on this thread, under this
        // reentrant lock, before this function returns: registered after, its first event would be
        // dropped as a stale detector's.
        detectors[member.id] = DetectorHandle(detectorJob, detector)
        detector.start(detectorScope)
        detectorScope.launch {
            logger.debug { "detector-collector.start peer=${member.id.value} role=${_role.value}" }
            detector.events.collect { event ->
                if (event is PartitionEvent.PeerLost) {
                    logger.info {
                        "detector-collector.received PeerLost peer=${member.id.value} role=${_role.value} at=${event.at}"
                    }
                }
                handlePartitionEvent(event, detectorJob)
            }
        }
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
        detectors.remove(peerId)?.job?.cancel()
    }

    /**
     * Test-visibility: is a live per-peer liveness detector currently registered for [peerId]?
     *
     * Exposed for [us.tractat.kuilt.session] tests that assert the host-liveness detector is
     * **silenced for the duration of a reconnect** (so it can't race an in-flight resume) and
     * **restarted on success** ([JoinerResumeMachine], #1037). No production caller reads this.
     */
    internal fun hasDetector(peerId: PeerId): Boolean = lock.withLock { detectors.containsKey(peerId) }

    /**
     * **Test-visibility.** The job of the detector registered for [peerId], or null: the identity
     * [handlePartitionEvent] is given for each event that detector reports (#3609). No production
     * caller reads this.
     */
    internal fun detectorJobForTest(peerId: PeerId): Job? = lock.withLock { detectors[peerId]?.job }

    /**
     * **Test-visibility.** Handle [event] as the collector of the detector whose job is [detector]
     * would. A test passes the job of a detector that has since been replaced to stand in for the one
     * interleaving no single-threaded test can reach (#3609): a collector that took an event off its
     * detector, then lost its thread before its first critical section while the detector was
     * stopped and another started for the same peer. The collector runs nothing that suspends between
     * the two, so a stalled thread is the only way there, and it hands over exactly this pair.
     */
    internal suspend fun handlePartitionEventForTest(event: PartitionEvent, detector: Job) =
        handlePartitionEvent(event, detector)

    /**
     * Test-visibility: is a reconnect attempt currently in flight (its reply slot installed)?
     *
     * Exposed for [us.tractat.kuilt.session] tests that interleave [sayHelloAgain] with the
     * internal auto-reconnect's own attempt (#1280). No production caller reads this.
     */
    internal fun hasPendingResume(): Boolean = resumeMachine?.hasPendingFlight() ?: false

    /**
     * Test-visibility: is a reconnect episode still undecided? Its job is recorded while it runs and
     * cleared in the critical section that decides how the episode ended (#3094).
     */
    internal fun hasReconnectInFlight(): Boolean = resumeMachine?.inFlightReconnectJob() != null

    /**
     * Test-visibility: is a reconnect loop still running? Unlike [hasReconnectInFlight], `false` for an
     * episode whose job [leave] cancelled before it could decide — a superseded room's, say (#3159).
     */
    internal fun isReconnectRunning(): Boolean = resumeMachine?.inFlightReconnectJob()?.isActive == true

    /** Test-visibility: how many resume flights this joiner has started (#3165). */
    internal fun resumeFlightsStarted(): Int = resumeMachine?.flightsStarted() ?: 0

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

    /**
     * [detector] is the job of the detector that reported [event], and every arm acts only while
     * that detector is still the one registered for the event's peer (#3609). A member's detector is
     * stopped when the member leaves, and a joiner's host detector while a reconnect runs, so an
     * event from one no longer registered is about a membership, or a link, that is over. Cancelling
     * the detector does not stop its collector from handing over an event it has already taken: the
     * collector suspends nowhere between taking an event and the room's lock, so on a multi-threaded
     * dispatcher a thread that stalls there can deliver it after another detector is watching the
     * same peer. Each arm checks in the critical section that acts, as [handlePeerLost] did first.
     */
    private suspend fun handlePartitionEvent(event: PartitionEvent, detector: Job) {
        when (event) {
            is PartitionEvent.PeerUnresponsive -> handleUnresponsive(event, detector)
            is PartitionEvent.PeerRecovered -> markRecovered(event.peerId, event.at, detector)
            is PartitionEvent.PeerLost -> handlePeerLost(event.peerId, event.at, detector)
        }
    }

    /** Whether [detector] is the detector registered for [peerId]. Callers must hold [lock]. */
    private fun isCurrentDetector(peerId: PeerId, detector: Job): Boolean = detectors[peerId]?.job === detector

    /** What [handleUnresponsive] decided under [lock]. */
    private enum class UnresponsiveFork { StaleDetector, Resume, Partition }

    /**
     * Maps a [PartitionEvent.PeerUnresponsive] to a membership event by role + reason.
     *
     * A joiner whose **host** link is lost to a definitive transport close attempts an in-window
     * return via [JoinerResumeMachine.attemptReconnect] (#1037) — re-weaving the base and saying
     * Hello again rather than immediately going terminal. This path races the
     * transport-`Torn` watcher ([runJoinerTornWatcher]); the machine's reconnect guard funnels
     * both into one attempt. Every other case (host watching a joiner; a joiner's non-host peer;
     * a silent [PartitionEvent.Reason.Timeout] partition that may still recover) opens the
     * reconnect window via [markPartitioned].
     *
     * Each fork checks [detector] in the section that acts on it (#3609), and only there. The resume
     * is claimed in the section that checks it: checked in one section and claimed in another, a
     * resume that ended in between, restarting the host detector, would leave this event from the
     * one it replaced to start a second episode on the healed link.
     * [JoinerResumeMachine.attemptReconnect] shares this room's reentrant [lock] and only launches,
     * so nothing suspends under it. A partition is checked by [markPartitioned], in the section that
     * writes the level; a check here as well would be the one that decides on one thread, leaving
     * that one unpinned.
     */
    private suspend fun handleUnresponsive(event: PartitionEvent.PeerUnresponsive, detector: Job) {
        val (fork, isHost) = lock.withLock {
            val isHost = _role.value == SessionRole.Joiner && event.peerId == hostPeerId
            val fork = when {
                !isHost || event.reason != PartitionEvent.Reason.TransportClosed -> UnresponsiveFork.Partition
                !isCurrentDetector(event.peerId, detector) -> UnresponsiveFork.StaleDetector
                else -> {
                    resumeMachine?.attemptReconnect(event.at)
                    UnresponsiveFork.Resume
                }
            }
            fork to isHost
        }
        if (fork == UnresponsiveFork.StaleDetector) {
            logger.debug {
                "membership.unresponsive.stale-detector peer=${event.peerId.value} reason=${event.reason}: " +
                    "a replaced host detector reported it, so it starts no resume"
            }
            return
        }
        val hostTransportClose = fork == UnresponsiveFork.Resume
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
        // The resume was claimed under the lock above.
        if (!hostTransportClose) markPartitioned(event.peerId, event.at, event.reason.toReconnectReason(), detector)
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
     *
     * A no-op unless [detector], the job of the detector that reported the drop, is still the one
     * registered for [peerId], checked in the section that writes the level (#3609). From a detector
     * since replaced, the drop is about a membership or a link that is over: acted on, it would hold
     * a newer, connected seat partitioned and open a window that seat's own detector never closes,
     * since it never saw the peer go quiet.
     */
    private fun markPartitioned(peerId: PeerId, at: Instant, reason: ReconnectReason, detector: Job) {
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
            if (!isCurrentDetector(peerId, detector)) {
                logger.debug { "membership.partitioned.stale-detector peer=${peerId.value}: a replaced detector reported it" }
                return
            }
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
            // Written under the SAME critical section as the level, and BEFORE the
            // `onPeerUnresponsive` call below hands the same instant to the controller — so no
            // announcement the controller derives from it can be judged against a stale entry.
            //
            // Deliberately NOT `level.since`, even though on a host the two are always equal today
            // (re-detection within one episode is unreachable, so `existing` is null here): `since`
            // is a public contract field defined never to drift on a re-detection, while this line
            // re-arms from the new `at`. Keying the gate on `since` would rest on those two
            // happening to coincide rather than on either one's promise. See [episodeDetectedAtMs].
            episodeDetectedAtMs[peerId] = at.toEpochMilliseconds()
            // Null only for a peer that is not admitted, already excluded above.
            updateMemberLiveness(peerId, level) ?: return
            // Enqueued INSIDE the critical section that decided it (#3625), for the reason at the
            // propagatePaused call below.
            if (existing == null && isHost) {
                propagatePaused(peerId, expiresAtMs = level.windowExpiresAt.toEpochMilliseconds())
            }
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
        // Nothing below depends on the old order: `propagatePaused` reads only `level` and the
        // roster, `emitEvent` is a non-suspending `tryEmit`, and refineWindow's own gate reads the
        // level this function already wrote.
        //
        // The enqueue sits INSIDE the critical section above, not here (#3625). Here it ran after
        // the lock was released and after the two `emitEvent`s, and anything on that gap could
        // finish a later change to the same member first: a `Goodbye` and a fresh `Hello` put
        // `Farewell` and `Welcome` on each bystander's lane, the late `Paused` followed them, and
        // the bystander held a seated member `Partitioned` with no `Unpaused` to come. A
        // `Resume` in the gap put `Unpaused` ahead of `Paused` the same way (#3026's second
        // concern). With the enqueue there, whatever a LATER section decides about this member is
        // enqueued after this `Paused` on every lane, wherever that section enqueues. An EARLIER
        // section's frame precedes it only if that section also enqueued under its own lock:
        // every `Paused`/`Unpaused` sender now does, and so does an admission's roster `Welcome`
        // ([admitMember], #3630), but the `Goodbye` handler's `Farewell` (#3628) is still enqueued
        // after its deciding section. `PausedFanOutOrderConcurrencyTest` parks this thread on the gap.
        // `fanOutToOtherMembers` re-enters the reentrant lock, and its `trySend` into an
        // UNLIMITED lane cannot suspend, so nothing suspend-capable runs under the lock.
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
     * after the member recovered or was evicted must not resurrect a stale deadline — a no-op when
     * [episode] names a partition episode that is not the current one, and a no-op when the deadline
     * did not move.
     *
     * **The partition guard alone is narrower than it reads** (#1781). It rejects a deadline for a
     * member that is not partitioned *now*, which covers "recovered, and still recovered" but not
     * "recovered, then partitioned again" — and a controller's
     * [JoinerReconnectEvent.WindowOpened] is emitted from its own `scope.launch`, so an
     * announcement for episode *N* can land after episode *N+1* opened, pass that guard, and move
     * the level **backwards** to *N*'s deadline. The reordering happens *before* this function is
     * reached rather than on the wire after it, so the per-recipient admit lanes cannot see it.
     * Theoretical — the recovery→re-detection gap is at least one [HeartbeatConfig.timeout], orders
     * of magnitude above launch latency, and no failure was ever observed.
     *
     * [episode] closes it by **identity**. A cheap `expiresAt < since` test was considered and
     * rejected as *misleadingly* incomplete: with a window longer than the recovery→re-detection gap
     * — the default controller's 60 s comfortably is — the stale episode's deadline still lands
     * after the new episode's [Liveness.Partitioned.since], so the guard passes and the level still
     * moves backwards, while a future reader believes the case is handled.
     * `WindowEpisodeIdentityTest` is written so that guard leaves it red.
     */
    private fun refineWindow(peerId: PeerId, expiresAt: Instant, episode: WindowEpisode) {
        // The decision is made under the lock and, apart from the fan-out it orders (#3625), ACTED
        // ON outside it, so the one outcome that discards a consumer-supplied value can be logged. Returning straight out of the critical
        // section — as this did — made the gate the only place in this file that drops such a value
        // silently, the absence [fanOutToOtherMembers] and [recordOversizeDrop] already decided has
        // to be diagnosable off-device (#1781).
        val verdict = lock.withLock {
            val current = admittedById[peerId]?.liveness as? Liveness.Partitioned
                ?: return@withLock RefineVerdict.NotPartitioned
            val held = episodeDetectedAtMs[peerId]
            val stale = when (episode) {
                // A null `held` is stale too: an announcement naming an episode this room never
                // recorded a detection for is as unusable as one naming a superseded episode.
                is WindowEpisode.Detected ->
                    if (held == episode.atMs) null else RefineVerdict.StaleEpisode(held, episode.atMs)
                WindowEpisode.Unidentified -> null
            }
            if (stale != null) return@withLock stale
            // The controller now holds this episode's window, whatever its deadline: a refusal it
            // gives from here on is about this partition (see [windowOpenedEpisodeMs]).
            if (episode is WindowEpisode.Detected) windowOpenedEpisodeMs[peerId] = episode.atMs
            if (current.windowExpiresAt == expiresAt) return@withLock RefineVerdict.Unchanged
            updateMemberLiveness(peerId, current.copy(windowExpiresAt = expiresAt))
                ?: return@withLock RefineVerdict.NotPartitioned
            // Enqueued INSIDE the decision's critical section (#3625). Enqueued after the lock was
            // released, a window expiry or a `Goodbye` plus a readmission could put `Farewell` and
            // `Welcome` on a bystander's lane first, and this `Paused` then pinned a seated member
            // `Partitioned` there. See the matching comment in [markPartitioned].
            if (_role.value == SessionRole.Host) {
                propagatePaused(peerId, expiresAtMs = expiresAt.toEpochMilliseconds())
            }
            RefineVerdict.Applied
        }
        when (verdict) {
            RefineVerdict.Applied -> emitEvent(MembershipEvent.WindowOpened(peerId, expiresAt))
            is RefineVerdict.StaleEpisode ->
                // `debug`, not `warn`: a genuine stale race is expected and rare, and the gate
                // dropping it is the fix working. It is logged at all for the OTHER cause — an
                // injected hold policy (#1614) that does not echo the `at` it was handed, whose
                // every refinement is then dropped forever with the deadline stuck at the room's
                // [HeartbeatConfig] estimate. That obligation is stated in
                // [JoinerReconnectController.onPeerUnresponsive]'s KDoc and cannot be
                // compiler-checked, so this line is the only way to see it being broken.
                //
                // IDENTITIES, not a count: `announced` against `held` is what separates "a late
                // announcement for a superseded episode" (held is some other real instant) from
                // "this controller echoes the wrong value" (announced matches no held, ever), and a
                // counter cannot tell those apart.
                logger.debug {
                    "room.window.stale-episode self=${selfId.value} peer=${peerId.value} " +
                        "announced=${verdict.announced} held=${verdict.held} " +
                        "discardedExpiresAt=${expiresAt.toEpochMilliseconds()}"
                }
            // Neither discards a value: NotPartitioned is the documented no-op for a member that
            // recovered or left, and Unchanged means this names the deadline already held.
            RefineVerdict.NotPartitioned, RefineVerdict.Unchanged -> Unit
        }
    }

    /**
     * Restore [Liveness.Connected] for [peerId] and announce it. Idempotent counterpart of
     * [markPartitioned]: a member that was not paused emits nothing and announces nothing.
     *
     * **It also closes the reconnect window it opened** ([JoinerReconnectController.onPeerRecovered],
     * #2556). This is the exact mirror of [markPartitioned]'s `onPeerUnresponsive` call, and its
     * absence was the root of #2556: a blip is restored by the detector alone, so the peer never
     * proves its seat and [JoinerReconnectController.tryResume] — the only other thing that
     * closes a window — is never reached. The window stayed armed behind a member that was already
     * back, and its [JoinerReconnectEvent.WindowExpired] was fanned out as an authoritative
     * `Farewell`, evicting a healthy member from every roster but this one's.
     *
     * Called **outside** the lock, like `onPeerUnresponsive`: an injected hold policy (#1614) is
     * consumer code and must never run under this room's lock.
     *
     * The room does not *rely* on the controller honouring it — [handleWindowExpired] gates every
     * expiry on its own state regardless, which is what makes an injected policy that ignores this
     * merely noisy rather than destructive. Removing the cause and refusing to act on it are
     * separate obligations; this is the first.
     *
     * A no-op unless [detector] is still the one registered for [peerId], for [markPartitioned]'s
     * reason (#3609): a recovery a replaced detector saw would release a seat the current detector
     * holds partitioned, and close its window, while the link it watches is still silent.
     */
    private fun markRecovered(peerId: PeerId, at: Instant, detector: Job) {
        val wasPartitioned = lock.withLock {
            if (!isCurrentDetector(peerId, detector)) {
                logger.debug { "membership.recovered.stale-detector peer=${peerId.value}: a replaced detector reported it" }
                return
            }
            val current = admittedById[peerId] ?: return
            val wasPartitioned = current.liveness is Liveness.Partitioned
            updateMemberLiveness(peerId, Liveness.Connected) ?: return
            // Emitted in the section that healed the seat: a Proof restoring it next decides in a
            // later section and emits its `Resumed` there, so the two are seen in the order they
            // happened. Emitted after the lock, `Recovered` could follow that `Resumed`.
            if (wasPartitioned) emitEvent(MembershipEvent.Recovered(peerId, at))
            // In the deciding critical section, for [markPartitioned]'s reason (#3625): enqueued
            // after it, a re-detection's `Paused` could reach a lane first and this `Unpaused`
            // would then release a member the host holds `Partitioned`.
            if (wasPartitioned && _role.value == SessionRole.Host) propagateUnpaused(peerId)
            wasPartitioned
        }
        if (!wasPartitioned) return
        reconnectController?.onPeerRecovered(peerId, at.toEpochMilliseconds())
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
     * order (#1781). The enqueues themselves are ordered by [lock]: [markPartitioned],
     * [refineWindow], [markRecovered] and a Proof's [restoreSeat] each enqueue inside the critical
     * section that decided the transition (#3625), so a restore can no longer land between
     * `markPartitioned`'s state commit and its `Paused` and put `Unpaused` first (#3026's second
     * concern).
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
     *
     * [finished] is the highest [RelayForward.seq] the writer is done with, and the lane's half of
     * [flush]. A marker in the queue would not work here: [BufferOverflow.DROP_OLDEST] could drop the
     * marker. Comparing stamps is sound where it would not be on an admit lane because this lane has
     * one enqueuer, so a stamp's order is the queue's order, and a forward overflow discards takes
     * its turn from the next stamp up rather than leaving a gap to wait on forever.
     */
    private class RelayLane(
        val queue: Channel<RelayForward>,
        val finished: MutableStateFlow<Long>,
        val writer: Job,
    ) {
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
     * for a peer [detachMember] has already reaped. Nothing would ever collect it: the eviction
     * that would have called [discardLanes] has been and gone, and a later duplicate eviction returns
     * early when it removes nothing. That is a leaked writer plus a 64-slot channel per departed peer
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
            val finished = MutableStateFlow(0L)
            val writer = scope.launch { runRelayForwardWriter(recipient, queue, finished) }
            // However the writer ends, nothing more leaves this lane, so a flush has nothing to wait for.
            // On the job rather than in a `finally`: a writer cancelled before its first dispatch never
            // runs its body. That needs a flush to snapshot the lane between its creation and its discard,
            // with no suspension between those two on a single-threaded dispatcher, so no test pins that
            // half; `RoomFlushTest` pins the started one.
            writer.invokeOnCompletion { finished.value = Long.MAX_VALUE }
            RelayLane(queue, finished, writer)
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
    private suspend fun runRelayForwardWriter(
        recipient: PeerId,
        queue: Channel<RelayForward>,
        finished: MutableStateFlow<Long>,
    ) {
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
            sendRelayForward(recipient, forward)
            finished.value = forward.seq
        }
    }

    /** One forward, best-effort: a failure is this recipient's and is logged. Only our own cancel escapes. */
    private suspend fun sendRelayForward(recipient: PeerId, forward: RelayForward) {
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
            return
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

    /** What an admit lane queues: a frame to send, or a [flush]'s marker. */
    private sealed interface AdmitLaneItem

    /** One queued admit fan-out for one recipient: a frame encoded once for the whole fan-out. */
    private class AdmitFanOut(
        val bytes: ByteArray,
        /** [AdmitMessage] subclass name, carried purely so a dropped frame is nameable in the log. */
        val label: String,
    ) : AdmitLaneItem

    /**
     * Marks a place in an admit lane for [flush]. The lane is [Channel.UNLIMITED] and never drops, and it
     * has several enqueuers, so its queue order is the only order there is: stamps like a relay lane's
     * could be taken in one order and enqueued in another.
     */
    private class AdmitBarrier(val passed: CompletableDeferred<Unit>) : AdmitLaneItem

    /** One recipient's admit fan-out lane: the frames waiting for that peer, and its writer. */
    private class AdmitLane(val queue: Channel<AdmitLaneItem>, val writer: Job)

    /**
     * Queued admit fan-outs, **one lane per recipient** (#2048), each drained **in enqueue order**
     * by its own [runAdmitFanOutWriter] coroutine (#1781).
     *
     * Every host-authoritative membership announcement *to bystanders* — [AdmitMessage.Paused],
     * [AdmitMessage.Unpaused], [AdmitMessage.Farewell], and the roster-sync [AdmitMessage.Welcome] —
     * is enqueued here instead of being sent from a fresh `scope.launch` per call or an inline loop,
     * so **enqueue order is wire order**. (Joiner-*directed* frames — the welcome, the roster
     * bootstrap, the host's self-introduction — stay inline: they address the joiner,
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
     *   evicts the ghost. This one is why the welcome fan-out is enqueued here too, and why it is
     *   enqueued in the section that admits rather than after the joiner's own `Welcome` (#3630) —
     *   see [admitMember].
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
    private fun admitLaneFor(recipient: PeerId): Channel<AdmitLaneItem>? {
        if (closed || recipient !in admittedById) return null
        return admitLanes.getOrPut(recipient) {
            val queue = Channel<AdmitLaneItem>(Channel.UNLIMITED)
            AdmitLane(queue, scope.launch { runAdmitFanOutWriter(recipient, queue) })
        }.queue
    }

    /**
     * Encode [message] once and **enqueue** it on the lane of every admitted member except [subject].
     * The fan-out is *ordered* here and *sent* by each recipient's [runAdmitFanOutWriter] — see
     * [admitLanes] for why that separation is the fix and not an indirection.
     *
     * The roster snapshot and the lane resolution are taken under [lock] and the enqueues happen
     * after this function's own acquisition is released. A caller already holding [lock] keeps them
     * inside its section — [handleWindowExpired] and [releaseHeldSeat] do, so their `Farewell` is
     * ordered against every later section's enqueues, and [markPartitioned], [refineWindow],
     * [markRecovered] and [restoreSeat] do for their `Paused`/`Unpaused`
     * (#3625). The
     * lock is reentrant, and a [Channel.trySend] into an unbounded channel cannot suspend, so
     * nothing suspend-capable sits inside a critical section either way.
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
    private suspend fun runAdmitFanOutWriter(recipient: PeerId, queue: Channel<AdmitLaneItem>) {
        for (item in queue) {
            val fanOut = when (item) {
                is AdmitBarrier -> {
                    item.passed.complete(Unit)
                    continue
                }
                is AdmitFanOut -> item
            }
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
     * roster. Callers must hold [lock]; the sole caller is [detachMember], which is the sole
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
            refineWindow(subject, hostDeadline, WindowEpisode.Unidentified)
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

    /**
     * [detector], the job of the detector that reported the loss, names the membership the loss is
     * about. A member's detector is stopped when the member is removed ([detachMember]), so a loss
     * from a detector no longer registered for [peerId] is about a membership that is over — and
     * evicting by peer id would remove a newer member admitted under the same id. The check and the
     * eviction share one critical section.
     *
     * The joiner's host arm is checked first, the same way (#3609). Its detector is stopped while a
     * reconnect runs and another started when one succeeds, so a loss from one no longer registered
     * would end a healed room as host-lost, and stop the detector watching it.
     */
    private suspend fun handlePeerLost(peerId: PeerId, at: Instant, detector: Job) {
        logger.info { "handlePeerLost peer=${peerId.value} role=${_role.value} at=$at" }
        val loss = lock.withLock {
            when {
                !isCurrentDetector(peerId, detector) -> PeerLoss.StaleDetector
                _role.value == SessionRole.Joiner && peerId == hostPeerId -> {
                    stopDetector(peerId)
                    PeerLoss.HostLost
                }
                else -> {
                    stopDetector(peerId)
                    PeerLoss.Evicted(detachMember(peerId, LeaveReason.PartitionExpired))
                }
            }
        }
        when (loss) {
            PeerLoss.HostLost -> {
                logger.info { "handlePeerLost.markHostLost peer=${peerId.value}" }
                markHostLost(at, FailureReason.WindowExpired)
            }
            PeerLoss.StaleDetector ->
                logger.debug { "handlePeerLost.stale-detector peer=${peerId.value}: the membership or link it watched has ended" }
            is PeerLoss.Evicted -> {
                logger.info { "handlePeerLost.evict peer=${peerId.value} reason=PartitionExpired" }
                loss.detached?.close()
            }
        }
    }

    /** What [handlePeerLost] decided under [lock]. */
    private sealed interface PeerLoss {
        data object HostLost : PeerLoss

        data object StaleDetector : PeerLoss

        /** [detached] is null when the member was gone already and only its detector was left. */
        class Evicted(val detached: DetachedMember?) : PeerLoss
    }

    /**
     * A reconnect window expired. Decide **once**, under one acquisition of [lock], whether this
     * expiry is one to act on, and in that same section evict (#1618 Track C) and enqueue the
     * authoritative [AdmitMessage.Farewell] (#1557) on every other member's lane.
     *
     * **Why the eviction is not a later step (#3212).** It was: the decision, then the `Farewell`,
     * then [removeFromRoster] by peer id, in sections of their own. A Proof that releases the seat
     * and admits a new member under the same id can land between the decision and the removal — with
     * the default controller it lines up exactly, since the Proof's ask is refused once the window
     * has closed — and the removal then evicted the *new* member, after its Welcome, leaking its
     * detector. Evicted where it was decided, the expiry removes the membership it read, and a Proof
     * landing after finds no seat and admits afresh. Only an eviction fans out, or an absence.
     *
     * **Why the `Farewell` is enqueued in the section too (#3212).** A lane keeps enqueue order, and
     * nothing orders enqueues made from two coroutines after each has released the lock. Enqueued
     * after this section, the `Farewell` could follow the roster `Welcome` a Proof on another thread
     * enqueues for the member it admits next, and a bystander told `Welcome`, then `Farewell`, drops
     * the new member for good (the #2556 split). The same holds for [ExpiryVerdict.AlreadyGone] when
     * this section falls between a Proof's release and its admission. Enqueued here, the `Farewell`
     * precedes every frame a later section enqueues. [fanOutToOtherMembers] takes [lock] again, which
     * is reentrant, and only calls `trySend` on unlimited channels, so nothing suspends under it.
     * `ProvenRestoreConcurrencyTest` pins the order on real threads.
     *
     * **Why one decision and not two (#2556).** These were two statements, and only the second was
     * guarded — on *"is this peer partitioned right now?"*. The fan-out ran first and unguarded, so
     * a window left armed behind a member that had already recovered evicted that member from every
     * *other* roster while this one, correctly, declined: a permanent split, spreading, because
     * [handleFarewell] stops the departed peer's detector and there is no re-admit path behind an
     * admit fan-out. Two guards would have been the same bug one level down — the fan-out reading
     * the level, then the eviction re-reading it, with the recovery free to land between them. The
     * decision has to be atomic with respect to the state it is about, so it is taken here, and
     * everything it drives but the inbox close is done in the same section.
     *
     * **Why liveness alone is not the guard.** [ExpiryVerdict.StaleEpisode] is the other half. The
     * partition guard rejects an expiry for a member that is not partitioned *now*, which covers
     * "recovered, and still recovered" but not "recovered, then partitioned again" — and an expiry
     * naming the *first* episode, arriving while the second is open, would pass it and take a seat
     * whose window has not run out. That is exactly the insufficiency #1781 established for
     * [refineWindow], and it matters more here, not less: moving a deadline is reversible, evicting
     * a seat is not. [JoinerReconnectEvent.WindowExpired.detectedAt] against [episodeDetectedAtMs]
     * is the same identity test [refineWindow] applies, on the same map.
     *
     * **[ExpiryVerdict.AlreadyGone] still fans out, deliberately.** The member being absent from
     * [admittedById] is the *ordinary* path, not an anomaly: the detector's
     * [PartitionEvent.PeerLost] normally fires at the same virtual instant as this event, from the
     * same [heartbeatConfig]-derived window, and which of the two arrives first is scheduling luck.
     * [handlePeerLost] fans nothing out, so gating the `Farewell` on membership would make #1557's
     * whole guarantee a race — a star topology's bystander would evict, or not, depending on
     * coroutine order. There is nothing left to evict, so only the fan-out runs.
     *
     * Idempotent throughout: [detachMember] guards a duplicate [MembershipEvent.Left], so a late
     * `PeerLost` after this arm emits nothing. Host-only, since [reconnectController] is null
     * on a joiner — it never pre-empts a joiner's terminal `HostLost` path.
     */
    private fun handleWindowExpired(event: JoinerReconnectEvent.WindowExpired) {
        val peerId = event.peerId
        val verdict = lock.withLock {
            val current = admittedById[peerId]
            if (current == null) {
                // In this section, like the eviction's: see the KDoc.
                propagateFarewell(peerId, expired = true)
                return@withLock ExpiryVerdict.AlreadyGone
            }
            if (current.liveness !is Liveness.Partitioned) {
                // Absent from [episodeDetectedAtMs]: [detachMember] reaped this id's entry, so the
                // member here is a newer admission, connected since it was seated.
                return@withLock ExpiryVerdict.Recovered(readmitted = peerId !in episodeDetectedAtMs)
            }
            val held = episodeDetectedAtMs[peerId]
            // A null `held` is stale too, for [refineWindow]'s reason: an expiry naming an episode
            // this room recorded no detection for is as unusable as one naming a superseded
            // episode. Unreachable on a host today — `markPartitioned` is the only writer of
            // `Liveness.Partitioned` here and it writes the map in the same critical section, and
            // `detachMember` reaps the two together — so the log line below is how a reader
            // would find out that stopped being true.
            if (held != event.detectedAt) return@withLock ExpiryVerdict.StaleEpisode(held, event.detectedAt)
            // Evicted in the section that decided to, so the eviction is of the membership just read.
            // Acted on in a later section, a Proof could release that seat and admit a new member
            // under the same id in between, and this would then evict the new one.
            val evicted = checkNotNull(detachMember(peerId, LeaveReason.PartitionExpired)) { "the seat was read under this lock" }
            propagateFarewell(peerId, expired = true)
            ExpiryVerdict.Expired(evicted)
        }
        when (verdict) {
            is ExpiryVerdict.Expired -> {
                logger.info {
                    "windowExpired.evict peer=${peerId.value} reason=PartitionExpired episode=${event.detectedAt}"
                }
                verdict.evicted.close()
            }
            ExpiryVerdict.AlreadyGone ->
                logger.info { "windowExpired.farewellOnly peer=${peerId.value} episode=${event.detectedAt}" }
            is ExpiryVerdict.Recovered ->
                if (verdict.readmitted) {
                    // `debug`: the expected end of a race the default controller runs. A Proof whose
                    // ask was refused because this window had just closed released the seat and
                    // admitted its joiner afresh before this expiry was handled, so the expiry is
                    // about a membership that is over.
                    logger.debug {
                        "windowExpired.suppressed peer=${peerId.value} reason=readmitted " +
                            "episode=${event.detectedAt} at=${event.at}"
                    }
                } else {
                    // This suppressed an **irreversible** action against a healthy member whose
                    // membership is the one the window was opened for. `debug`, because the shipped
                    // [DefaultJoinerReconnectController] reaches it on purpose, ordering its calls by
                    // their instant (#3673), in two ways: a stale open that lands on a finished newer
                    // window opens a window of its own, and a recovery that is not strictly later than
                    // the window (a clock that stepped back or did not move) is ignored, leaving the
                    // window armed. The third cause is a hold policy (#1614) that does not honour
                    // [JoinerReconnectController.onPeerRecovered], a contract breach nothing can check
                    // at compile time.
                    logger.debug {
                        "windowExpired.suppressed peer=${peerId.value} reason=recovered " +
                            "episode=${event.detectedAt} at=${event.at}"
                    }
                }
            is ExpiryVerdict.StaleEpisode ->
                // `debug`, matching `room.window.stale-episode`: a genuine late expiry racing a
                // re-detection is expected and rare, and dropping it is the gate working.
                // IDENTITIES, not a count — `announced` against `held` separates "a late expiry for
                // a superseded episode" (held is some other real instant) from "this controller
                // echoes the wrong value" (announced matches no held, ever).
                logger.debug {
                    "windowExpired.stale-episode self=${selfId.value} peer=${peerId.value} " +
                        "announced=${verdict.announced} held=${verdict.held} at=${event.at}"
                }
        }
    }

    private suspend fun markHostLost(at: Instant, reason: FailureReason) {
        if (claimHostLost()) announceHostLost(at, reason)
    }

    /**
     * Sets [hostLost] and returns whether this call set it: the one place the flag is raised. A
     * superseded room is never host-lost: it was told why it ended, and its own `leave()` follows.
     */
    private fun claimHostLost(): Boolean = lock.withLock {
        if (supersededBy != null) return false
        val was = hostLost
        hostLost = true
        !was
    }

    /** The second half of [markHostLost], for a caller whose [claimHostLost] returned `true`. */
    private suspend fun announceHostLost(at: Instant, reason: FailureReason) {
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
        // Both callers ([admitNewMember], [handleWelcome]) hold [lock] across this, and [leave] flips
        // `closed` under the same lock — so a `closed == true` here is authoritative. Without this,
        // an in-flight admit (host: `scope.launch { handleProof }`, which [leave] does NOT cancel —
        // it is not the inbound-collect job) can re-register a peer AFTER the room went terminal,
        // resurrecting `_roster`/`_rosterPeers` and leaking a fresh detector (#1368; the
        // RoomHubSeam.deliver resurrection, #1364, one module over).
        if (closed) return
        val isReadmit = admittedById.containsKey(member.id)
        admittedById[member.id] = member
        // Same critical section as the admission itself, so the inbox exists before any frame from
        // this member can pass the isAdmittedPeer gate — incomingFrom holds from admission (#2802).
        // A re-admit keeps the inbox it already has.
        // No inbox at all when this room holds nothing: a consumer that never reads incomingFrom — a
        // joiner admitting its host, say — pays for none of it.
        if (memberInboxCapacity > 0) {
            memberInboxes.getOrPut(member.id) { MemberInbox(member.id, memberInboxCapacity) }
        }
        _roster.update { current -> current.filterNot { it.id == member.id }.toSet() + member }
        _rosterPeers.update { current -> current + member.id }
        if (!isReadmit) {
            emitEvent(MembershipEvent.Joined(member))
            startDetector(member)
        }
    }

    /**
     * Remove [peerId]'s membership, if it has one. For a caller whose decision to remove was taken
     * in an earlier critical section, and so may remove a newer membership under the same id: a
     * caller deciding on one membership in particular uses [detachMember] under its deciding lock.
     */
    private fun removeFromRoster(peerId: PeerId, reason: LeaveReason) {
        lock.withLock { detachMember(peerId, reason) }?.close()
    }

    /**
     * **Under [lock].** Remove [peerId]'s membership and stop its detector, in the caller's critical
     * section, so the removal acts on the membership the caller's decision read and not on one
     * admitted after it under the same id. Null when [peerId] holds none: no duplicate `Left`.
     * The caller closes the returned inbox once it has released [lock].
     */
    private fun detachMember(peerId: PeerId, reason: LeaveReason): DetachedMember? {
        // The fan-out lanes are torn down in the SAME critical section that removes the member. That
        // reaps every lane minted while the peer WAS a member; what stops a new one appearing after
        // is the membership re-check in [admitLaneFor]/[relayLaneFor], not this line — the relay path
        // resolves its recipients under a SEPARATE lock acquisition, so an eviction can land between
        // the two and a check keyed only on `closed` would mint a lane for a peer already gone. The
        // two together give the invariant `lanes.keys ⊆ admittedById.keys` at every point the lock is
        // not held. If no admission is removed, no lane is discarded. See [discardLanes].
        if (admittedById.remove(peerId) == null) return null
        // A member's detector goes with it, so none outlives the membership it watches.
        stopDetector(peerId)
        discardLanes(peerId)
        // Reaped with the member so a long-lived room does not accumulate an entry per peer
        // that ever left. refineWindow's Partitioned gate rejects an announcement for a gone member.
        episodeDetectedAtMs.remove(peerId)
        windowOpenedEpisodeMs.remove(peerId)
        // Pruned with the member, keeping `membershipIds.keys == admittedById.keys` (#3215).
        membershipIds.remove(peerId)
        // A joiner's count of who the host named is cleared only by a new Hello, and a room that never
        // reconnects never says one, so it is pruned here too (#3615). It changes no rebuild: on a
        // joiner only a Welcome adds a member, and that Welcome names it again.
        namedSinceHello.remove(peerId)
        // A later admission gets a fresh inbox; close this admission's inbox outside the lock.
        val inbox = memberInboxes.remove(peerId)
        // Keep removal in the same critical section as admission and liveness updates. Otherwise
        // a Hello can re-admit this peer after the map removal, and these late publications erase
        // the new admission from both roster views while the frame gate still accepts it.
        _roster.update { current -> current.filterNot { it.id == peerId }.toSet() }
        _rosterPeers.update { current -> current - peerId }
        emitEvent(MembershipEvent.Left(peerId, reason))
        return DetachedMember(inbox)
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

    /** Test-visibility: the members a host `Welcome` has named since this joiner's latest Hello (#3615). */
    internal fun namedSinceHelloForTest(): Set<PeerId> = lock.withLock { namedSinceHello.toSet() }

    /** Test-visibility: how many membership ids this host holds — one per current member (#3215). */
    internal fun heldMembershipCount(): Int = lock.withLock { membershipIds.size }

    // ── Application frame routing ─────────────────────────────────────────────

    private fun routeApplicationFrame(sender: PeerId, bytes: ByteArray) {
        val frame = RoomFrame(sender = sender, payload = bytes)
        _incoming.tryEmit(frame)
        holdForMember(frame)
    }

    /**
     * Offer [frame] to its sender's inbox ([incomingFrom]). Taken off the map under [lock], offered
     * outside it: the inbox resolves a full buffer itself without suspending, and the main loop routes
     * one frame at a time, so a sender's frames reach its inbox in arrival order.
     */
    private fun holdForMember(frame: RoomFrame) {
        lock.withLock { memberInboxes[frame.sender] }?.offer(frame)
    }

    override fun incomingFrom(member: PeerId): Flow<RoomFrame> {
        // A configuration error, not a race: this room was built to hold nothing, so there is no stream
        // to hand back. Completion would be the wrong answer — it reads as "that admission ended", which
        // a consumer acts on, and it would say so again on every call.
        check(memberInboxCapacity > 0) {
            "incomingFrom(${member.value}) on a room built with memberInboxCapacity = 0, which holds no " +
                "per-member frames; read `incoming`, or build the room with a capacity"
        }
        // No inbox means no current admission — never admitted, already evicted, or `leave` closed them.
        // That is indistinguishable from an admission that just ended, so it reads the same: completion.
        return lock.withLock { memberInboxes[member] }?.claim() ?: emptyFlow()
    }

    // ── Room interface ────────────────────────────────────────────────────────

    /**
     * Broadcast [bytes] to all admitted members.
     *
     * On a star fabric a spoke's frame reaches only the host, so once this member's roster diverges
     * from what the transport can address, the frame is wrapped and **relayed via the host**
     * (#1994). [RoomChannelSeam] uses the same routing through [broadcastChannel], after adding
     * its channel header. Raw application bytes are checked before reaching that shared route.
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
     * Contrast [sendTo], which is addressed and therefore **does** report an unreachable hop.
     *
     * @throws ReservedRoomPrefix if a non-terminal room would classify [bytes] as protocol traffic.
     */
    override suspend fun broadcast(bytes: ByteArray) {
        if (lock.withLock { hostLost || closed }) return
        refuseReservedPrefix(bytes)
        broadcastFrame(bytes)
    }

    internal suspend fun broadcastChannel(subId: Short, payload: ByteArray) {
        broadcastFrame(RoomChannel.frame(subId, payload))
    }

    private suspend fun broadcastFrame(bytes: ByteArray) {
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
     * **Reports an undeliverable hop, unlike [broadcast].** An addressed send that cannot be
     * delivered is reported: this is [Seam.sendTo]'s documented contract, and swallowing it would re-create #1994's own symptom
     * — silent non-delivery — at the send side. The two methods differ deliberately because their
     * contracts differ; a caller that wants best-effort semantics for an addressed send wraps this
     * in `runCatchingCancellable`.
     *
     * The frame is still lossy *after* the first hop: once the host has accepted the envelope, an
     * unresolvable destination or a wedged recipient is dropped with a debug log and nothing is
     * reported back. Only the hop this member performs itself can throw.
     *
     * @throws ReservedRoomPrefix if a non-terminal room would classify [bytes] as protocol traffic.
     * @throws PayloadTooLarge if the encoded frame will not fit the fabric — measured on the frame,
     *   so a direct send is charged no envelope and a relayed one is charged exactly what its own
     *   envelope cost. See [oversizeOrNull].
     * @throws PeerNotConnected if the hop this member must perform cannot be made. On a relayed
     *   send the peer it names is the **host** — the hop that actually failed — not [peer], which
     *   this member has no direct route to by construction.
     */
    override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
        if (lock.withLock { hostLost || closed }) return
        refuseReservedPrefix(bytes)
        sendFrameTo(peer, bytes)
    }

    internal suspend fun sendChannelTo(peer: PeerId, subId: Short, payload: ByteArray) {
        sendFrameTo(peer, RoomChannel.frame(subId, payload))
    }

    private fun refuseReservedPrefix(bytes: ByteArray) {
        val prefix = RoomFramePrefix.entries.firstOrNull { it.classifies(bytes) } ?: return
        throw ReservedRoomPrefix(prefix)
    }

    private suspend fun sendFrameTo(peer: PeerId, bytes: ByteArray) {
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
     * **Test-visibility.** One attempt of the reconnect's Hello, as [JoinerResumeMachine.runReconnect]
     * makes it: a fresh Hello to the host alone, its challenge answered, and the host's answer to
     * that Hello — see [JoinerResumeMachine.resume]. `Room.resume` was the public way in until #3215
     * retired it; a reconnect is the room's own business now, and this is how a test drives one
     * attempt without first tearing the link. [ResumeResult.WindowClosed] on a host.
     */
    internal suspend fun sayHelloAgain(): JoinerOutcome =
        resumeMachine?.resume() ?: ResumeResult.WindowClosed

    /**
     * Drain the lanes as they stand on entry, then ask the seam. See [Room.flush].
     *
     * The order matters: a lane's writer hands a frame to [Seam.sendTo], so the seam's own barrier
     * covers that frame only if it is taken after the lane has passed it.
     */
    override suspend fun flush(timeout: Duration): FlushOutcome = flushWithin(timeout) {
        val laneWait = lock.withLock { if (hostLost || closed) null else laneWaitOnEntry() }
        when {
            laneWait == null -> FlushOutcome.Failed(IllegalStateException("room for ${selfId.value} has ended"))
            !lanesPassedBeforeTheSeamTore(laneWait) ->
                FlushOutcome.Failed(IllegalStateException("the fabric under room ${selfId.value} tore"))
            // A lane's wait also ends when its writer does. A member leaving is a good reason, but this
            // scope ending kills the writers with frames still queued, and the seam knows nothing of those.
            !scope.isActive -> FlushOutcome.Failed(IllegalStateException("room for ${selfId.value} has ended"))
            else -> seam.flush(Duration.INFINITE)
        }
    }

    /**
     * Mark every lane now, and return the wait for those marks. Callers must hold [lock]: what is
     * marked here is what this flush covers, so a lane's position has to be read at one moment.
     *
     * The relay target is read rather than marked, which needs no [lock] of its own: a forward stamped a
     * moment before this is enqueued a moment after, and waiting for it costs only a little longer.
     *
     * An admit lane's wait also watches its writer: [discardLanes] cancels a lane's queue, marker
     * included, when its member leaves the roster. A relay lane's writer reports the same by moving
     * [RelayLane.finished] to the end.
     */
    private fun laneWaitOnEntry(): suspend () -> Unit {
        val admitMarks = admitLanes.values.mapNotNull { lane ->
            val barrier = AdmitBarrier(CompletableDeferred())
            // The lane is unlimited, so this fails only on a lane that has already closed.
            if (lane.queue.trySend(barrier).isSuccess) barrier to lane.writer else null
        }
        val relayMarks = relayLanes.values.map { lane -> lane.finished to lane.nextSeq.value }
        return {
            admitMarks.forEach { (barrier, writer) ->
                select {
                    barrier.passed.onAwait {}
                    writer.onJoin {}
                }
            }
            relayMarks.forEach { (finished, target) -> finished.first { it >= target } }
        }
    }

    /**
     * `true` once the lanes pass their marks; `false` if the seam tears first.
     *
     * A lane's send can be parked in a seam that has torn, and the lane holds on until that send's own
     * budget runs out, up to `reconnectWindow + timeout`. That is no reason to keep a caller waiting
     * for an answer the tear has already settled.
     */
    private suspend fun lanesPassedBeforeTheSeamTore(laneWait: suspend () -> Unit): Boolean = coroutineScope {
        val tore = async { seam.state.first { it is SeamState.Torn } }
        val passed = async { laneWait() }
        select<Boolean> {
            tore.onAwait { false }
            passed.onAwait { true }
        }.also { currentCoroutineContext().cancelChildren() }
    }

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
                detectors.values.map { it.job }.also { detectors.clear() },
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
        // A terminal room has ended every admission, so it lists nobody, and it says so BEFORE it
        // completes the incomingFrom readers: "reader completed, peer still listed" means a newer
        // admission and nothing else, exactly as in [detachMember], and a reader resumed by the
        // close below reads the roster at that instant (#2822). `closed` is already set, so
        // addToRoster cannot open another inbox or relist anyone behind this. No Left: nobody left.
        lock.withLock {
            admittedById.clear()
            episodeDetectedAtMs.clear()
            windowOpenedEpisodeMs.clear()
            // With the members it belongs to, keeping `membershipIds.keys == admittedById.keys`.
            membershipIds.clear()
            supersededIds.clear()
            _roster.value = emptySet()
            _rosterPeers.value = setOf(selfId)
            memberInboxes.values.toList().also { memberInboxes.clear() }
        }.forEach { it.close() }
        jobsToCancel.forEach { it.cancel() }
        detectorJobsToCancel.forEach { it.cancel() }
        seam.close(
            when (reason) {
                is LeaveReason.Normal -> CloseReason.Normal
                is LeaveReason.Error -> CloseReason.Error(RuntimeException(reason.message))
                is LeaveReason.PartitionExpired, is LeaveReason.Superseded, is LeaveReason.Unobserved -> CloseReason.Normal
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
