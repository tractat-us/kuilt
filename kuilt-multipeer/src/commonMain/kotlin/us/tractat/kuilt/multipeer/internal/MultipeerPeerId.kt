package us.tractat.kuilt.multipeer.internal

import us.tractat.kuilt.core.PeerId

/**
 * Mapping between a human display name and the wire identity used as a
 * [PeerId] on the MultipeerConnectivity fabric.
 *
 * Apple's `MCPeerID.displayName` is the only cross-process identity handle MC
 * exposes, but it is whatever the *remote* device chose — two default-named
 * "iPhone" devices produce the SAME string and therefore, historically, the
 * SAME [PeerId]. A disconnect of either then evicted BOTH from the peer set
 * (the wrong-peer-eviction class, kuilt#1466 / #1494).
 *
 * The fix embeds a collision-resistant identity INTO the advertised display
 * name ([decorate]) *before* the `MCPeerID` is constructed, so the identity
 * travels WITH the advertisement. This is the only way both ends stay
 * consistent: the observer cannot append anything to a *received* display name
 * (it does not know the remote's identity), so it must be baked into the
 * advertised name the local device chose. Every observer — the advertiser and
 * every browser/joiner — then derives the [PeerId] from the same decorated
 * string ([peerId]), so both ends of a link always agree on the id.
 * [humanName] recovers the original display name for UI.
 *
 * **The identity is the caller's `selfId`, not an internal nonce (#1430).** The
 * suffix used to be an 8-hex-character nonce minted inside the factory, which
 * made the wire `PeerId` an *output* of construction that no caller could
 * predict. It is now the `PeerId` the caller supplied, so
 * `peerId(decorate(name, selfId.value)) == selfId`. A `freshPeerId()` UUID
 * already carries all the collision resistance the nonce provided — that was
 * the nonce's only job — so #1466 is unaffected: two same-named devices still
 * advertise different suffixes and derive different ids. The consequence for
 * [peerId] is that it takes the part after the LAST delimiter rather than the
 * whole string; that is what makes the round-trip an identity.
 */
internal object MultipeerPeerId {
    /**
     * Separates the human display name from the identity. The identity is
     * appended as `<name><delimiter><selfId>`; [humanName] splits on the LAST
     * delimiter and [peerId] takes what follows it, so a human name that itself
     * contains the delimiter round-trips and can never be mistaken for the
     * identity ([decorate] forbids the delimiter in the identity, so the last
     * one is always the separator).
     */
    internal const val ID_DELIMITER: Char = '#'

    /**
     * Apple caps `MCPeerID.displayName` at 63 UTF-8 bytes (a construction with a
     * longer name raises). [decorate] trims the human prefix to stay within it.
     */
    internal const val MAX_DISPLAY_NAME_BYTES: Int = 63

    /** Fallback when the human name trims away to nothing under the byte budget. */
    private const val EMPTY_NAME_FALLBACK: String = "peer"

    /**
     * Produces the wire display name embedding [selfId], trimming [name] so the
     * result fits [MAX_DISPLAY_NAME_BYTES]. The identity is kept whole — it is
     * what the far end derives the [PeerId] from — and only the human prefix is
     * trimmed. A `freshPeerId()` UUID costs 37 of the 63 bytes, leaving 26 for
     * the human name; the display name is cosmetic, the identity is not.
     *
     * @param selfId the `PeerId.value` of the local peer.
     * @throws IllegalArgumentException if [selfId] is empty, contains the
     *   [ID_DELIMITER], or is itself so long it leaves no room for a name.
     */
    internal fun decorate(
        name: String,
        selfId: String,
    ): String {
        require(selfId.isNotEmpty()) { "selfId must be non-empty" }
        require(ID_DELIMITER !in selfId) { "selfId must not contain the delimiter '$ID_DELIMITER': '$selfId'" }
        val suffix = "$ID_DELIMITER$selfId"
        val budget = MAX_DISPLAY_NAME_BYTES - suffix.encodeToByteArray().size
        require(budget >= 1) {
            "selfId '$selfId' leaves no room for a display name within $MAX_DISPLAY_NAME_BYTES bytes " +
                "(MCPeerID.displayName is capped there); use a shorter PeerId such as freshPeerId()"
        }
        val prefix =
            name
                .truncateToUtf8Bytes(budget)
                .ifEmpty { EMPTY_NAME_FALLBACK.truncateToUtf8Bytes(budget) }
        return "$prefix$suffix"
    }

    /**
     * The wire [PeerId] for a (possibly decorated) display name — everything
     * after the LAST [ID_DELIMITER], which is exactly the `selfId` the advertiser
     * baked in. Both ends of a link observe the same `MCPeerID.displayName` and
     * so derive the same id.
     *
     * An **undecorated** name (no delimiter) yields the whole string, so a legacy
     * or non-kuilt peer still gets a sensible, non-blank id — `substringAfterLast`
     * returns its receiver when the delimiter is absent. The same fallback covers
     * a name that merely *ends* with the delimiter: [decorate] can never produce
     * one (an empty identity is refused), so it is a foreign name, and deriving
     * `PeerId("")` from it would only be refused later by
     * `PeerIdentityRegistry`'s blank-id guard.
     */
    internal fun peerId(displayName: String): PeerId {
        val identity = displayName.substringAfterLast(ID_DELIMITER)
        return PeerId(identity.ifEmpty { displayName })
    }

    /**
     * Recovers the human display name from a decorated wire name — everything
     * before the LAST [ID_DELIMITER]. An undecorated name (no delimiter) is
     * returned unchanged, so legacy/undecorated peers still display sensibly.
     */
    internal fun humanName(displayName: String): String =
        if (ID_DELIMITER in displayName) {
            displayName.substringBeforeLast(ID_DELIMITER)
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
