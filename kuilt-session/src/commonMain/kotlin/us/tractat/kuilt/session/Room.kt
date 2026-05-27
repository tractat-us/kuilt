package us.tractat.kuilt.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
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
     * Attempt to resume this room from a [ResumeToken] after a transport drop.
     *
     * Stub in 1B — wired fully in 1D via [us.tractat.kuilt.session.partition.JoinerReconnectController].
     */
    public suspend fun resume(token: ResumeToken): ResumeResult

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
