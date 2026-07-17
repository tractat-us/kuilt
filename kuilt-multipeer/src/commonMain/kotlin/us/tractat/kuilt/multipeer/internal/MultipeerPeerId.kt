package us.tractat.kuilt.multipeer.internal

import us.tractat.kuilt.core.PeerId

/**
 * Collision-resistant mapping between a human display name and the wire
 * identity used as a [PeerId] on the MultipeerConnectivity fabric.
 *
 * Apple's `MCPeerID.displayName` is the only cross-process identity handle MC
 * exposes, but it is whatever the *remote* device chose — two default-named
 * "iPhone" devices produce the SAME string and therefore, historically, the
 * SAME [PeerId]. A disconnect of either then evicted BOTH from the peer set
 * (the wrong-peer-eviction class, kuilt#1466 / #1494).
 *
 * The fix embeds a per-device nonce INTO the advertised display name
 * ([decorate]) *before* the `MCPeerID` is constructed, so the collision-resistant
 * identity travels WITH the advertisement. This is the only way both ends stay
 * consistent: the observer cannot append a nonce to a *received* display name
 * (it does not know the remote's nonce), so the nonce must be baked into the
 * advertised name the local device chose. Every observer — the advertiser and
 * every browser/joiner — then derives the [PeerId] from the same decorated
 * string ([peerId]), so both ends of a link always agree on the id.
 * [humanName] recovers the original display name for UI.
 */
internal object MultipeerPeerId {
    /**
     * Separates the human display name from the per-device nonce. The nonce is
     * appended as `<name><delimiter><nonce>`; [humanName] splits on the LAST
     * delimiter so a human name that itself contains the delimiter round-trips.
     */
    internal const val NONCE_DELIMITER: Char = '#'

    /**
     * Apple caps `MCPeerID.displayName` at 63 UTF-8 bytes (a construction with a
     * longer name raises). [decorate] trims the human prefix to stay within it.
     */
    internal const val MAX_DISPLAY_NAME_BYTES: Int = 63

    /** Fallback when the human name trims away to nothing under the byte budget. */
    private const val EMPTY_NAME_FALLBACK: String = "peer"

    /**
     * Produces the wire display name embedding [nonce], trimming [name] so the
     * result fits [MAX_DISPLAY_NAME_BYTES]. The nonce is kept whole — it is the
     * collision-resistant part — and only the human prefix is trimmed.
     *
     * @throws IllegalArgumentException if [nonce] is empty, contains the
     *   [NONCE_DELIMITER], or is itself so long it leaves no room for a name.
     */
    internal fun decorate(
        name: String,
        nonce: String,
    ): String {
        require(nonce.isNotEmpty()) { "nonce must be non-empty" }
        require(NONCE_DELIMITER !in nonce) { "nonce must not contain the delimiter '$NONCE_DELIMITER'" }
        val suffix = "$NONCE_DELIMITER$nonce"
        val budget = MAX_DISPLAY_NAME_BYTES - suffix.encodeToByteArray().size
        require(budget >= 1) {
            "nonce '$nonce' leaves no room for a display name within $MAX_DISPLAY_NAME_BYTES bytes"
        }
        val prefix =
            name
                .truncateToUtf8Bytes(budget)
                .ifEmpty { EMPTY_NAME_FALLBACK.truncateToUtf8Bytes(budget) }
        return "$prefix$suffix"
    }

    /**
     * The wire [PeerId] for a (possibly decorated) display name — the FULL
     * string. Deriving from the whole decorated name is what makes the id
     * collision-resistant, and what keeps both ends in agreement (they observe
     * the same `MCPeerID.displayName`).
     */
    internal fun peerId(displayName: String): PeerId = PeerId(displayName)

    /**
     * Recovers the human display name from a decorated wire name — everything
     * before the LAST [NONCE_DELIMITER]. An undecorated name (no delimiter) is
     * returned unchanged, so legacy/undecorated peers still display sensibly.
     */
    internal fun humanName(displayName: String): String =
        if (NONCE_DELIMITER in displayName) {
            displayName.substringBeforeLast(NONCE_DELIMITER)
        } else {
            displayName
        }

    /**
     * Trims this string so its UTF-8 encoding is at most [maxBytes] bytes,
     * never splitting a surrogate pair (so the result is always valid text).
     */
    private fun String.truncateToUtf8Bytes(maxBytes: Int): String {
        if (encodeToByteArray().size <= maxBytes) return this
        var end = length - 1
        while (end > 0) {
            if (this[end].isLowSurrogate()) {
                end--
                continue
            }
            val candidate = substring(0, end)
            if (candidate.encodeToByteArray().size <= maxBytes) return candidate
            end--
        }
        return ""
    }
}
