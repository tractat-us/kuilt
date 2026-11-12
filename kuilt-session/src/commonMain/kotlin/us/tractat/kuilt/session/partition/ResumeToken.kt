package us.tractat.kuilt.session.partition

import kotlinx.serialization.Serializable
import us.tractat.kuilt.core.PeerId

/**
 * The credential a joiner presents when reconnecting after a transport drop.
 *
 * The host validates three claims, in this order:
 * 1. The [roomId] matches the current session — prevents cross-session replay.
 * 2. The [secret] matches the one the host minted for [peerId] at admit — proves the resume is
 *    [peerId]'s own (#3165). Checked **before** the window is touched, so a wrong secret leaves the
 *    real member's window open.
 * 3. The reconnect window for [peerId] is still open and unspent — prevents stale reconnects.
 *
 * [peerId], [roomId] and [issuedAt] are public knowledge inside a room — every member learns every
 * id and the room id from the roster — so without [secret] any admitted member could write a token
 * for any other. The secret is the one field a bystander never sees: the host sends it only in the
 * direct `Welcome` to the member it names. A joiner whose host sent no secret (a host that predates
 * it) holds no token at all, and re-joins fresh rather than resuming.
 *
 * **A token is good only against the host that minted it.** That host keeps the secret and the
 * reconnect window in memory, so a resume against a different host — a re-elected leader, or
 * another server in a cluster — finds neither, and degrades to a fresh join.
 *
 * `@Serializable` so a joiner can persist the token across its own restart; [secret] serializes as
 * hex and should be stored as the secret it is. [toString] redacts it.
 *
 * [issuedAt] is epoch-millis from the injected clock (never [System.currentTimeMillis]
 * directly — callers must inject via `clock: () -> Long`).
 */
@Serializable
public data class ResumeToken(
    val peerId: PeerId,
    val roomId: RoomId,
    val issuedAt: Long,
    val secret: ResumeSecret,
)

/**
 * Stable handle for a P2P Room session. Survives leader changes: the [RoomId]
 * is assigned at Room creation and does not rotate if auto-election promotes a
 * new leader.
 *
 * Scoped to the session layer so the resume-token path stays in the library.
 * Consumer application-layer session identifiers are separate concerns and must
 * not be conflated with this transport-layer room identity.
 */
@Serializable
@kotlin.jvm.JvmInline
public value class RoomId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RoomId cannot be blank" }
    }

    override fun toString(): String = value
}
