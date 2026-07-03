package us.tractat.kuilt.core

/**
 * Configuration for opening a new peer session.
 *
 * Forward-compatible: new fields should be added with defaults so existing
 * callers are unaffected.
 *
 * [roomKey] is this host's stable room identity — the value a joiner must target
 * to be admitted. It defaults to [displayName] so every host has a room identity
 * without extra ceremony; set it explicitly when several hosts share a display
 * name but must remain distinct rooms (e.g. two `host()` sessions on one flat
 * in-memory mesh). A joiner declares which room it wants via
 * [Tag.roomKey]; the host admits only when the targets agree (or the joiner
 * names none — see [Tag.roomKey]).
 */
public data class Pattern(
    val displayName: String,
    val maxPeers: Int = 6,
    val roomKey: String = displayName,
)
