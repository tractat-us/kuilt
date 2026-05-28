package us.tractat.kuilt.session.partition

import kotlinx.coroutines.flow.SharedFlow
import us.tractat.kuilt.core.PeerId

/**
 * Manages per-peer reconnect windows on the leader side.
 *
 * When a joiner's transport link drops, the leader calls [onPeerUnresponsive]
 * to open a timed reconnect window (default 60 s per D-005). Within that window
 * the joiner can present a [ResumeToken] via [tryResume] to reattach. Once the
 * window expires the peer is evicted and a late reconnect is treated as a fresh
 * join.
 *
 * State-resync (replaying the action log to the reconnected joiner) is **out
 * of scope** here. The leader's `LiveLeader` subscribes to [events] and drives
 * the snapshot broadcast on [JoinerReconnectEvent.Resumed]. Integration point:
 * observe `events.filterIsInstance<JoinerReconnectEvent.Resumed>()` and push
 * current game state to `event.peerId`.
 *
 * The leader identity is not part of [ResumeToken]; only [RoomId] is. This
 * is intentional: D-010 (auto-election, v2 goal) preserves [RoomId] across
 * leader changes so joiners can resume against a newly elected leader without
 * token renegotiation.
 *
 * Partition events ([PartitionEvent]) are produced by the partition-detector layer.
 * Until that wiring lands, callers bridge manually: on transport close, invoke
 * [onPeerUnresponsive] directly.
 *
 * **Thread safety:** implementations must be safe for concurrent calls.
 */
public interface JoinerReconnectController {
    /**
     * Live stream of window lifecycle events:
     * [JoinerReconnectEvent.WindowOpened], [JoinerReconnectEvent.Resumed],
     * [JoinerReconnectEvent.WindowExpired].
     */
    public val events: SharedFlow<JoinerReconnectEvent>

    /**
     * Opens (or refreshes) the reconnect window for [peerId].
     *
     * Called by the leader when a peer's link drops. Emits
     * [JoinerReconnectEvent.WindowOpened] on [events].
     *
     * When a [PartitionEvent.PeerUnresponsive] feed is available, bridge
     * it here: `partitionEvents.collect { if (it is PeerUnresponsive) onPeerUnresponsive(it.peerId, it.at) }`.
     */
    public fun onPeerUnresponsive(
        peerId: PeerId,
        at: Long,
    )

    /**
     * Attempts to resume the peer identified by [token].
     *
     * Validation order:
     * 1. Session match — [ResumeToken.roomId] must equal the controller's Room.
     * 2. Window open — the reconnect window for [token.peerId] must still be active at [at].
     * 3. Token not yet consumed — a token may only be used once per window.
     *
     * On success, emits [JoinerReconnectEvent.Resumed] and closes the window so
     * a second [tryResume] with the same token returns [ResumeResult.WindowClosed].
     */
    public suspend fun tryResume(
        token: ResumeToken,
        at: Long,
    ): ResumeResult

    /**
     * Force-expires the reconnect window for [peerId] before the timer fires.
     *
     * Useful for explicit kick and in tests that need deterministic expiry
     * without advancing virtual time.
     */
    public fun expire(
        peerId: PeerId,
        at: Long,
    )
}

/** Events emitted by [JoinerReconnectController] over its [SharedFlow]. */
public sealed interface JoinerReconnectEvent {
    /** The reconnect window opened for [peerId]. It expires at epoch-millis [expiresAt]. */
    public data class WindowOpened(
        val peerId: PeerId,
        val expiresAt: Long,
    ) : JoinerReconnectEvent

    /** [peerId] successfully resumed within the window. */
    public data class Resumed(
        val peerId: PeerId,
        val at: Long,
    ) : JoinerReconnectEvent

    /** The reconnect window for [peerId] expired without a valid resume. */
    public data class WindowExpired(
        val peerId: PeerId,
        val at: Long,
    ) : JoinerReconnectEvent
}

/** Result of a [JoinerReconnectController.tryResume] call. */
public sealed interface ResumeResult {
    /** The token was valid and the window was open. Peer is now resumed. */
    public data object Success : ResumeResult

    /** The window for this peer has already closed (expired or never opened). */
    public data object WindowClosed : ResumeResult

    /** The token failed structural validation. [reason] describes the failure. */
    public data class TokenInvalid(
        val reason: String,
    ) : ResumeResult
}
