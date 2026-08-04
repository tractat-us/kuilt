package us.tractat.kuilt.session

import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.election.LobbyMessage

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
    Admit(0x61) {
        override fun classifies(bytes: ByteArray): Boolean = AdmitMessage.isAdmitFrame(bytes)
    },

    /**
     * A `Room.channel(id)` view's frames — `RoomChannel`. `0x63`, ASCII 'c'.
     *
     * [classifies] is **narrower than [matches]**: a channel frame carries a 3-byte header, so a
     * 1- or 2-byte payload leading with `0x63` claims the byte but is not a channel frame.
     */
    Channel(0x63) {
        override fun classifies(bytes: ByteArray): Boolean = RoomChannel.isChannelFrame(bytes)
    },

    /** Host election — `LobbyMessage`. `0x65`, ASCII 'e'. */
    Lobby(0x65) {
        override fun classifies(bytes: ByteArray): Boolean = LobbyMessage.isLobbyFrame(bytes)
    },

    /**
     * Liveness ping/pong — `HeartbeatPartitionDetector`.
     *
     * The odd one out: heartbeat declares a *String* prefix (`"kuilt.heartbeat.ping"`) whose first
     * byte happens to be `0x6b`, and `:kuilt-liveness` cannot depend on `:kuilt-session` to derive
     * from here. The reservation is one-directional and pinned by test, not by construction.
     *
     * [classifies] is therefore **much** narrower than [matches] — the real test is the whole
     * string, so every ordinary payload beginning with `k` (`"keepalive"`, `"key"`, …) claims this
     * byte while being a perfectly ordinary application frame.
     */
    Heartbeat(0x6b) {
        override fun classifies(bytes: ByteArray): Boolean =
            HeartbeatPartitionDetector.isHeartbeatFrame(bytes)
    },

    /**
     * A host-forwarded frame between two spokes of a star — `RelayEnvelope` (#1994). `0x72`, ASCII 'r'.
     *
     * The one family for which [classifies] and [matches] genuinely coincide:
     * `RelayEnvelope.isRelayFrame` is *defined* as `Relay.matches`, so this entry inherits the
     * default rather than delegating back into a definition that would only point here again.
     *
     * **Release note:** an application payload sent via `Room.broadcast` whose first byte is `0x72`
     * was previously legal and is now swallowed as a relay frame.
     */
    Relay(0x72),
    ;

    /** Whether [bytes] **claims** this family's prefix byte — a single-byte test. Empty is never. */
    public fun matches(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == byte

    /**
     * Whether [bytes] **is** a frame of this family, by the family's own real classifier.
     *
     * This is the predicate `SeamRoom.dispatchIncoming` actually dispatches on, and it is the one
     * to use for any "which family is this?" question. [matches] answers the strictly weaker
     * question "does this claim the byte?", and for two families the two answers differ:
     * [Channel] additionally requires a 3-byte header, and [Heartbeat] requires the whole
     * `"kuilt.heartbeat.ping"`/`"…pong"` string.
     *
     * **Why the registry carries the predicate rather than the byte alone.** A caller that asks
     * "is this payload spoken for?" by folding [matches] over [entries] gets a *different* answer
     * from the dispatcher for exactly those two families — and the disagreement is silent. #1994's
     * relay allow-list was written that way and dropped a spoke's `"keepalive"` broadcast (byte
     * `0x6b`) that the direct path delivers as ordinary application data. Registering each family's
     * real classifier here makes the two planes agree by construction, while keeping the property
     * that a *new* family must claim a byte — and so is excluded from the allow-list by default —
     * rather than having to be remembered at every call site.
     */
    public open fun classifies(bytes: ByteArray): Boolean = matches(bytes)
}
