package us.tractat.kuilt.session.partition

import us.tractat.kuilt.core.PeerId

/**
 * Opaque token a joiner presents when reconnecting after a transport drop.
 *
 * The leader validates two claims:
 * 1. The [roomId] matches the current session — prevents cross-session replay.
 * 2. The reconnect window for [peerId] is still open — prevents stale reconnects.
 *
 * The leader's own identity is **not** encoded here. Only [roomId] is. This
 * preserves D-010 forward-compatibility: when auto-election (v2 goal) replaces
 * the leader mid-session, [roomId] survives the transition and joiners can
 * resume against the new host without token renegotiation.
 *
 * [issuedAt] is epoch-millis from the injected clock (never [System.currentTimeMillis]
 * directly — callers must inject via `clock: () -> Long`).
 */
public data class ResumeToken(
    val peerId: PeerId,
    val roomId: RoomId,
    val issuedAt: Long,
)

/**
 * Stable handle for a P2P Room session. Survives leader changes (D-010):
 * the [RoomId] is assigned at Room creation and does not rotate when
 * auto-election promotes a new leader in v2.
 *
 * Scoped to `:transport-core` so the resume-token path stays in the library
 * layer. `:live-runtime`'s `SessionId` is a separate application-layer type
 * (game session identity) and must not be conflated with this transport-layer
 * room identity.
 */
@kotlin.jvm.JvmInline
public value class RoomId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RoomId cannot be blank" }
    }

    override fun toString(): String = value
}
