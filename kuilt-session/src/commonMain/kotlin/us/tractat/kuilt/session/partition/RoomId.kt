package us.tractat.kuilt.session.partition

import kotlinx.serialization.Serializable

/**
 * Stable handle for a P2P Room session. Survives leader changes: the [RoomId]
 * is assigned at Room creation and does not rotate if auto-election promotes a
 * new leader.
 *
 * A host signs it into every admission challenge, so a joiner's proof is good only in the room
 * that challenged it.
 *
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
