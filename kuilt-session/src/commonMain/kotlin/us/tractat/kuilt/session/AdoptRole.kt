package us.tractat.kuilt.session

import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.partition.RoomId

/**
 * Which side of a room an [SeamRoomFactory.adopt] takes, and what that side needs.
 *
 * A host decides who comes in, so it carries the [HostAdmission] it decides with, and the room's
 * identity when the caller supplies one. A joiner decides nothing and learns the room's identity from
 * the host's `Welcome`, so it carries neither. That is a type rather than a check: a joiner cannot be
 * handed a policy it would never ask, or a room id it is not entitled to choose.
 */
public sealed interface AdoptRole {
    /**
     * Host the room: this peer admits the others.
     *
     * @property admission Decides every newcomer the room admits: see [HostAdmission].
     * @property roomId This room's identity ([Room.roomId]). Null, the default, mints a fresh one per
     *   adopt, which is what stops two rooms one device adopts in a row from colliding (#1594). Supply
     *   it when the identity is decided outside kuilt: a lobby code, an invite link, a game id.
     */
    public data class Host(
        public val admission: HostAdmission,
        public val roomId: RoomId? = null,
    ) : AdoptRole

    /** Join the room: the host admits this peer. */
    public data object Joiner : AdoptRole
}

/** The [SessionRole] a room adopted under this role reports. */
internal val AdoptRole.sessionRole: SessionRole
    get() = when (this) {
        is AdoptRole.Host -> SessionRole.Host
        AdoptRole.Joiner -> SessionRole.Joiner
    }
