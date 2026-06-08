package us.tractat.kuilt.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
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
     * Starts as [SessionRole.Host] or [SessionRole.Joiner] based on which
     * [RoomFactory] method was called. May change in 1C (host-election).
     */
    public val role: StateFlow<SessionRole>

    /**
     * The live set of admitted members. Does NOT include this peer itself.
     *
     * A peer is absent until the admit/identify handshake completes.
     * A peer is removed on clean [leave] or transport disconnect.
     */
    public val roster: StateFlow<Set<Member>>

    /**
     * Stream of [MembershipEvent]s describing roster and liveness changes.
     *
     * Hot; backed by a shared flow. Late collectors miss historical events.
     */
    public val events: Flow<MembershipEvent>

    /**
     * Stream of [RoomFrame]s received from admitted members.
     *
     * Frames from unadmitted peers are silently dropped.
     * Hot; backed by a shared flow. Late collectors miss historical frames.
     */
    public val incoming: Flow<RoomFrame>

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
     *   handshake are **never** included. This is the central correctness guarantee: a
     *   replicator running over this [Seam] will only ever send state to admitted members.
     * - **`incoming`** — frames from admitted members tagged with this channel [id], with
     *   channel framing stripped. Frames from unadmitted peers are silently dropped.
     * - **`broadcast` / `sendTo`** — send channel-framed payloads over the Room's
     *   underlying transport. No-ops when the room is terminal (HostLost / closed).
     * - **`state`** — forwards the underlying [us.tractat.kuilt.core.SeamState].
     * - **`close`** — no-op. The Room owns lifecycle; closing the channel view does not
     *   tear down the Room.
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
     * collected are dropped. Safe for [us.tractat.kuilt.crdt.replicator.SeamReplicator]
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
    /** Host a new room. The caller's peer becomes the [SessionRole.Host]. */
    public suspend fun host(pattern: Pattern): Room

    /** Join an existing room. The caller's peer becomes a [SessionRole.Joiner]. */
    public suspend fun join(tag: Tag): Room
}
