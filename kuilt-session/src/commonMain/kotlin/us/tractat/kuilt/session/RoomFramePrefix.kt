package us.tractat.kuilt.session

/**
 * The single source of truth for the **room frame-prefix byte space** (#2007).
 *
 * Every frame `SeamRoom.dispatchIncoming` classifies is discriminated by its first byte. Before
 * this enum those bytes were five loose `public const val`s in four packages, and nothing made a
 * collision a compile error. One member per frame family means a family cannot be added without
 * claiming a byte, and [RoomFramePrefixTest] pins that no two claim the same one.
 *
 * ## The real collision band is `0x60..0x7f`, not `0xe0..0xff`
 *
 * Inherited prose (originally `RoomChannel`'s KDoc) claimed these bytes are safe because they sit
 * "outside the CBOR major-type-7 range (`0xe0`–`0xff`) used by serialization". **That is false**,
 * and it is false in the direction that matters. CBOR text-string headers are `0x60 or len`, so a
 * bare top-level CBOR string collides with *every* byte claimed here:
 *
 * | payload | first byte | collides with |
 * |---|---|---|
 * | 1-char string | `0x61` | [Admit] |
 * | 3-char string | `0x63` | [Channel] |
 * | 5-char string | `0x65` | [Lobby] |
 * | 11-char string | `0x6b` | [Heartbeat] |
 * | 18-char string | `0x72` | [Relay] |
 *
 * The codebase lives with this because room payloads are **framed**, not bare — an application
 * payload is wrapped by the channel header before it reaches the wire. This registry's job is
 * single-source-of-truth for the byte space and **distinctness**; it is deliberately *not* a
 * safety proof, because no registry can make one.
 *
 * @property byte the first byte of every frame in this family.
 */
public enum class RoomFramePrefix(public val byte: Byte) {
    /** The admit handshake — `AdmitMessage`. `0x61`, ASCII 'a'. */
    Admit(0x61),

    /** A `Room.channel(id)` view's frames — `RoomChannel`. `0x63`, ASCII 'c'. */
    Channel(0x63),

    /** Host election — `LobbyMessage`. `0x65`, ASCII 'e'. */
    Lobby(0x65),

    /**
     * Liveness ping/pong — `HeartbeatPartitionDetector`.
     *
     * The odd one out: heartbeat declares a *String* prefix (`"kuilt.heartbeat.ping"`) whose first
     * byte happens to be `0x6b`, and `:kuilt-liveness` cannot depend on `:kuilt-session` to derive
     * from here. The reservation is one-directional and pinned by test, not by construction.
     */
    Heartbeat(0x6b),

    /**
     * A host-forwarded frame between two spokes of a star — `RelayEnvelope` (#1994). `0x72`, ASCII 'r'.
     *
     * **Release note:** an application payload sent via `Room.broadcast` whose first byte is `0x72`
     * was previously legal and is now swallowed as a relay frame.
     */
    Relay(0x72),
    ;

    /** Whether [bytes] is a frame of this family — i.e. its first byte is [byte]. Empty is never. */
    public fun matches(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == byte
}
