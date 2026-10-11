package us.tractat.kuilt.session

/**
 * A raw [Room.broadcast] or [Room.sendTo] payload would be interpreted as room protocol traffic.
 *
 * The payload is refused before sending. Use [Room.channel] to carry arbitrary application bytes
 * inside channel framing, or choose a raw payload that no [RoomFramePrefix.classifies] predicate
 * reserves.
 *
 * @property prefix the frame family whose classifier reserved the payload.
 */
public class ReservedRoomPrefix(public val prefix: RoomFramePrefix) :
    IllegalArgumentException("Raw Room payload is reserved by $prefix; use Room.channel for arbitrary bytes")
