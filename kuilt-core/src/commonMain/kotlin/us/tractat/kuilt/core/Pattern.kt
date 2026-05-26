package us.tractat.kuilt.core

/**
 * Configuration for opening a new peer session.
 *
 * Forward-compatible: new fields should be added with defaults so existing
 * callers are unaffected.
 */
public data class Pattern(
    val displayName: String,
    val maxPeers: Int = 6,
)
