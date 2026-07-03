package us.tractat.kuilt.core

/**
 * Configuration for opening a new peer session.
 *
 * Forward-compatible: new fields should be added with defaults so existing
 * callers are unaffected.
 *
 * [roomKey] is this host's stable room identity — the value a joiner must target
 * to be admitted. It defaults to `null`, meaning **this host declares no room**
 * and admits any joiner (the permissive default); set it explicitly to bind
 * admission to a room, e.g. when several hosts share a session name but must
 * remain distinct rooms (e.g. two `host()` sessions on one flat in-memory mesh).
 * A joiner declares which room it wants via [Tag.roomKey]; the host admits only
 * when the targets agree (or either side names none — see [Tag.roomKey]).
 *
 * Keeping this nullable — rather than defaulting it to [sessionName] — keeps
 * room identity an explicit, opt-in concept, orthogonal to the (already
 * overloaded) session name.
 */
public data class Pattern(
    val sessionName: String,
    val maxPeers: Int = 6,
    val roomKey: String? = null,
)
