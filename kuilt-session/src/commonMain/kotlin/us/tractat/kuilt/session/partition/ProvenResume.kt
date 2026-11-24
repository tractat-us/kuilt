package us.tractat.kuilt.session.partition

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey

/**
 * A request to restore [peerId]'s held seat in [roomId], made only once the room has checked that the
 * joiner asking for it is that seat's member (#3159).
 *
 * The room builds one in one place, after its own check, and nowhere else: on a verified admission
 * **Proof**, once the key → seat table has found that [peerId]'s seat was admitted under the very
 * key the proof is signed by, which is [key].
 *
 * The constructor is `internal`, so a [JoinerReconnectController] — a consumer's own hold policy
 * included — can only ever be asked about a seat whose member was proven. That is the #3165 property
 * held by a type rather than by a comment: no forgery can reach a window and spend it. A test outside
 * this module reaches one through a real room, never a factory.
 *
 * [roomId] is always the asking room's own id. A controller may still compare it, but the room has
 * already refused a proof for another room before it builds one.
 */
public class ProvenResume internal constructor(
    /** The member whose held seat is asked for. */
    public val peerId: PeerId,
    /** The room the seat is held in: always the asking room's own. */
    public val roomId: RoomId,
    /** The device key that seat's member was admitted under, and that the joiner proved. */
    public val key: DidKey,
) {
    override fun toString(): String = "ProvenResume(peerId=${peerId.value}, roomId=${roomId.value}, key=$key)"
}
