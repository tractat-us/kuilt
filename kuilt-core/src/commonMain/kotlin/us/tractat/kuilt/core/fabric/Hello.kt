package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId

/** The one-shot identity preamble: the first frame on a handshaking link. */
public object Hello {
    public fun encode(selfId: PeerId): ByteArray = selfId.value.encodeToByteArray()
    public fun decode(frame: ByteArray): PeerId = PeerId(frame.decodeToString())
}
