package us.tractat.kuilt.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeToken

/**
 * A membership-aware session room built over a [us.tractat.kuilt.core.Seam].
 *
 * The key abstraction above [us.tractat.kuilt.core.Seam]: peers become [Member]s only
 * after completing the admit/identify handshake. Raw connected-but-unidentified peers
 * are invisible to consumers of [Room] — they do not appear in [roster] and their
 * frames are dropped from [incoming].
 *
 * All flows are coroutine-scope-bound (the [us.tractat.kuilt.core.Loom] backing this
 * room's [us.tractat.kuilt.core.Seam] drives the lifecycle). Call [leave] to clean up.
 */
public interface Room {
    /** This peer's own identifier (mirrors [us.tractat.kuilt.core.Seam.selfId]). */
    public val selfId: PeerId

    /**
     * The role this peer plays in the room.
     *
     * Fixed for the room's lifetime. Set to [SessionRole.Host] or [SessionRole.Joiner] by the
     * [RoomFactory] method that created the room ([RoomFactory.host] / [RoomFactory.join]), or by the
     * role the room was adopted with. When the role must be *resolved from the connected roster* rather
     * than chosen up front, use the host-election lobby
     * ([us.tractat.kuilt.session.election.ElectionLobby]) — it elects the host before any room exists,
     * then adopts with a now-fixed role.
     */
    public val role: StateFlow<SessionRole>

    /**
     * The live set of admitted members. Does NOT include this peer itself.
     *
     * A peer is absent until the admit/identify handshake completes.
     * A peer is removed on clean [leave] or transport disconnect.
     *
     * **This is the authoritative, replay-safe source of current membership.** Being a
     * [StateFlow], a new collector immediately receives the current set. Reach for [roster]
     * (not [events]) to answer "who is in the room?" — it cannot miss a join or leave.
     */
    public val roster: StateFlow<Set<Member>>

    /**
     * Stream of [MembershipEvent]s describing roster and liveness changes.
     *
     * Hot, backed by a shared flow with a **bounded replay cache**: a late collector still
     * receives the most recent membership events (so a [MembershipEvent.Joined] emitted in the
     * brief window before a `host { onRoom }` consumer subscribes is not lost — see #692), but
     * only the recent tail, not the full history. Treat events as **idempotent notifications**;
     * use [roster] as the authoritative current-membership source.
     */
    public val events: Flow<MembershipEvent>

    /**
     * Stream of [RoomFrame]s received from admitted members.
     *
     * Frames from unadmitted peers are silently dropped.
     * Hot; backed by a shared flow. Late collectors miss historical frames.
     */
    public val incoming: Flow<RoomFrame>

    /**
     * Host-verified principals of currently-linked peers, keyed by the [PeerId] each was verified
     * against at admission — the roster analogue of the per-member [Member.principal].
     *
     * [Member.principal] stays the *primary* per-member surface (it co-locates the self-asserted
     * `deviceId` and the verified principal on one object, where a mismatch check is a single field
     * comparison while walking [roster]). This accessor is the uniform cross-facade view — the same
     * map [us.tractat.kuilt.core.PrincipalRoster] exposes and that `GameSession.attestedPrincipals`
     * mirrors — for consumers that want the roster directly.
     *
     * Populated when this room rides a [us.tractat.kuilt.core.PrincipalRoster] seam (a mux-hub
     * `RoomHubSeam` whose connections carry attested principals); a constant empty map on seams with
     * no attestation concept (a 2-peer relay seam — where [Member.principal] is the surface — or an
     * in-memory fabric without attached principals). Roster-first, mirroring `GameSession`.
     */
    public val attestedPrincipals: StateFlow<Map<PeerId, Principal>>

    /**
     * Whether **this peer's own end of the fabric carrying this room** can carry frames now.
     *
     * Session-scoped, never device-scoped: a peer in two rooms over two fabrics has two
     * independent values and neither speaks for the other. A room over a bonded `CompositeSeam`
     * reports [FabricAvailability.Unavailable] only when every woven ply is down.
     *
     * [FabricAvailability.Unknown] means the fabric has no live path observer. Treat it as
     * "kuilt cannot tell", never as either answer — and expect it: it is the value every fabric
     * but a path-observing one reports, so it is the common case rather than an error.
     *
     * **This is the authoritative, replay-safe level.** Being a [StateFlow], a late collector
     * immediately reads the current value and so cannot miss a drop, where
     * [MembershipEvent.LocalFabricLost] / [MembershipEvent.LocalFabricRestored] on [events] are
     * only notifications that it moved.
     *
     * The level is never *staler* than an edge — so it never claims the fabric is up while an
     * emitted [MembershipEvent.LocalFabricLost] says otherwise. It can, however, be **ahead** of the
     * edge you are handling: events are buffered, so under a rapid flap
     * (`Available → Unavailable → Available`) a handler may read `Available` while processing the
     * `LocalFabricLost`, with the matching `LocalFabricRestored` still queued behind it. Treat the
     * edges as idempotent notifications and this level as authoritative; do not assert that the level
     * matches the edge in hand (#1712).
     *
     * Deliberately has **no** interface default. An implementation that silently inherited
     * `Unknown` would be claiming it cannot tell, when a `Room` implementation — a fake above all —
     * is exactly the thing that can.
     */
    public val localFabric: StateFlow<FabricAvailability>

    /** Broadcast [bytes] to all other admitted members. */
    public suspend fun broadcast(bytes: ByteArray)

    /** Send [bytes] to one specific admitted member. */
    public suspend fun sendTo(peer: PeerId, bytes: ByteArray)

    /**
     * The joiner's reconnect credential, available after the admit handshake completes.
     *
     * Non-null on a [SessionRole.Joiner] room once the host has sent its [RoomId] via
     * the Welcome frame. Null on a [SessionRole.Host] room (the host does not reconnect
     * to itself) and null on a joiner room whose handshake has not yet completed.
     *
     * Callers that need to reconnect after a transport drop should save this token.
     * Present it to [resume] to attempt re-entry within the reconnect window.
     */
    public val resumeToken: ResumeToken?

    /**
     * Attempt to resume this room from a [ResumeToken] after a transport drop.
     *
     * Wired via [us.tractat.kuilt.session.partition.JoinerReconnectController] (1D).
     */
    public suspend fun resume(token: ResumeToken): ResumeResult

    /**
     * Returns a [Seam] view scoped to this channel [id].
     *
     * The returned [Seam] provides:
     * - **`peers`** — the admitted roster plus self, reactive to [MembershipEvent.Joined] /
     *   [MembershipEvent.Left]. Raw transport peers that have not completed the admit
     *   handshake are **never** included.
     * - **`incoming`** — frames from admitted members tagged with this channel [id], with
     *   channel framing stripped. Frames from unadmitted peers are silently dropped.
     * - **`broadcast` / `sendTo`** — send channel-framed payloads over the Room's
     *   underlying transport. No-ops when the room is terminal (HostLost / closed).
     * - **`state`** — forwards the underlying [us.tractat.kuilt.core.SeamState].
     * - **`close`** — no-op. The Room owns lifecycle; closing the channel view does not
     *   tear down the Room.
     *
     * ## Admit-gating guarantee for replicators
     *
     * A [us.tractat.kuilt.quilter.Quilter] running over this [Seam]
     * uses `peers` to maintain its membership book, so:
     *
     * - **FullState** (the convergence base) is sent via `sendTo` only to peers in
     *   `peers` — i.e. admitted members. An unadmitted transport peer never receives
     *   FullState and therefore cannot reconstruct the replicated state.
     * - **Ack and Resend** are also `sendTo` gated on admitted peers.
     * - **Delta** frames are broadcast via `seam.broadcast` and reach all connected
     *   transport peers, including unadmitted ones. Unadmitted peers have no FullState
     *   base to apply deltas to, so the frames are harmless noise. This is the documented
     *   behaviour: channel framing and admit-gated `peers` prevent the replicator from
     *   *targeting* unadmitted peers and from FullState-syncing them; they do **not**
     *   encrypt or withhold broadcast bytes at the wire level.
     *
     * ## Wire framing
     *
     * Channel frames are prefixed with [RoomChannel.CHANNEL_PREFIX] (`0x63`, 'c' for
     * "channel") followed by a 2-byte sub-id derived deterministically from [id] via
     * [RoomChannel.channelSubId]. This keeps frame headers small (3 bytes overhead)
     * and requires no registration handshake — both peers independently compute the
     * same sub-id for the same String. Sub-id collisions across distinct channel names
     * are theoretically possible (1/65536 per pair) but negligible for typical usage
     * (< 100 channels).
     *
     * Applications **must not** emit payloads starting with [RoomChannel.CHANNEL_PREFIX]
     * on the Room's raw [broadcast] / [sendTo] — that byte is reserved for channel framing.
     *
     * ## Idempotency
     *
     * Multiple calls with the same [id] return the same [Seam] instance.
     *
     * ## Late-subscriber semantics
     *
     * The shared upstream uses `replay = 0`. Frames emitted before [incoming] is
     * collected are dropped. Safe for [us.tractat.kuilt.quilter.Quilter]
     * (gaps heal via FullState + resend) but **not** for raw at-least-once consumers.
     */
    public fun channel(id: String): Seam

    /** Leave the room cleanly. Idempotent. */
    public suspend fun leave(reason: LeaveReason = LeaveReason.Normal)
}

/**
 * Creates [Room]s backed by a [us.tractat.kuilt.core.Loom].
 *
 * The [RoomFactory] wraps one [us.tractat.kuilt.core.Loom] instance and creates
 * rooms via [host] (new session) or [join] (existing session).
 */
public interface RoomFactory {
    /**
     * Host a new room. The caller's peer becomes the [SessionRole.Host].
     *
     * [memberName] is this peer's own roster name — the label other members see for it.
     * It defaults to null, in which case the peer's own id ([Room.selfId]) is used. It is
     * deliberately *not* derived from [Pattern.sessionName]: the session name names the
     * session, not this member (#1177).
     */
    public suspend fun host(pattern: Pattern, memberName: String? = null): Room

    /**
     * Join an existing room. The caller's peer becomes a [SessionRole.Joiner].
     *
     * [memberName] is this peer's own roster name — the label other members see for it.
     * It defaults to null, in which case the peer's own id ([Room.selfId]) is used. It is
     * deliberately *not* derived from the discovered [Tag.sessionName]: the session name
     * names the session being joined, not this joiner (#1177).
     */
    public suspend fun join(tag: Tag, memberName: String? = null): Room
}
