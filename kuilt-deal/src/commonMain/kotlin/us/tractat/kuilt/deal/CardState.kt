package us.tractat.kuilt.deal

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.GSet

public enum class CardPhase {
    UNENCRYPTED,
    SHUFFLING,
    FULLY_ENCRYPTED,
    REVEALING,
    REVEALED,
}

@Serializable
public data class CardState(
    val ciphertext: ByteArray,
    val encryptedBy: GSet<PlayerId>,
    val strippedBy: GSet<PlayerId>,
    val visibilityQuorum: Set<PlayerId>,
    val allPlayers: Set<PlayerId>,
    /**
     * Per-member reveal tracks for a partial multi-member quorum
     * (`1 < |visibilityQuorum| < |allPlayers|`), keyed by the member the track
     * reveals to. Empty for single-reader and community cards. See [QuorumTrack].
     */
    val quorumTracks: Map<PlayerId, QuorumTrack> = emptyMap(),
) {
    public fun phase(): CardPhase = when {
        encryptedBy.elements.isEmpty() -> CardPhase.UNENCRYPTED
        encryptedBy.elements != allPlayers -> CardPhase.SHUFFLING
        strippedBy.elements.isEmpty() && quorumTracks.isEmpty() -> CardPhase.FULLY_ENCRYPTED
        strippedBy.elements != requiredStrippers() -> CardPhase.REVEALING
        !quorumTracksComplete() -> CardPhase.REVEALING
        else -> CardPhase.REVEALED
    }

    /** True iff this card is visible to a strict multi-member subset of players. */
    public fun isPartialQuorum(): Boolean =
        visibilityQuorum.size > 1 && visibilityQuorum != allPlayers

    /**
     * The players whose layers must come off before the card is [CardPhase.REVEALED].
     *
     * - `|visibilityQuorum| == 1`: everyone except the single reader — their own
     *   layer is what keeps the card private to them.
     * - `visibilityQuorum == allPlayers` (community card): *everyone* — the card
     *   is public, so every layer must come off.
     * - An empty quorum behaves like a community card (no reader to protect).
     * - Partial multi-member quorums (`1 < |quorum| < |allPlayers|`): the
     *   non-members — and additionally every member's [QuorumTrack] must complete
     *   (see [quorumTracksComplete]) before the card is [CardPhase.REVEALED].
     */
    private fun requiredStrippers(): Set<PlayerId> =
        if (visibilityQuorum == allPlayers) allPlayers else allPlayers - visibilityQuorum

    /**
     * For a partial multi-member quorum: every member's reveal track carries all
     * *other* members' strips, so each member privately holds a copy encrypted
     * under only their own layer. Trivially true for non-partial quorums.
     */
    private fun quorumTracksComplete(): Boolean =
        !isPartialQuorum() || visibilityQuorum.all { member ->
            quorumTracks[member]?.strippedBy?.elements == visibilityQuorum - member
        }

    public fun merge(other: CardState): CardState {
        // Ciphertext convergence: the side further along the op chain wins — each
        // Encrypt/Strip adds exactly one element to exactly one GSet, so
        // |encryptedBy| + |strippedBy| totally orders the states of an honest,
        // sequential op chain (comparing |encryptedBy| alone would mis-merge
        // reordered Strip states, whose encryptor counts are all equal).
        // On a tie (states not on one chain — a protocol-violating fork), break
        // deterministically by ciphertext byte order so merge stays commutative.
        // Once both sides converge the ciphertexts are byte-identical, so the
        // tie-break is invisible in steady state.
        val winningCiphertext = when {
            progress() > other.progress() -> ciphertext
            progress() < other.progress() -> other.ciphertext
            else -> if (compareCiphertext(ciphertext, other.ciphertext) <= 0) ciphertext else other.ciphertext
        }
        return copy(
            encryptedBy = encryptedBy.piece(other.encryptedBy),
            strippedBy = strippedBy.piece(other.strippedBy),
            ciphertext = winningCiphertext,
            quorumTracks = mergeTracks(quorumTracks, other.quorumTracks),
        )
    }

    /** How many ops (encrypts + strips) this state reflects — its position on the op chain. */
    private fun progress(): Int = encryptedBy.elements.size + strippedBy.elements.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CardState) return false
        return ciphertext.contentEquals(other.ciphertext) &&
            encryptedBy == other.encryptedBy &&
            strippedBy == other.strippedBy &&
            visibilityQuorum == other.visibilityQuorum &&
            allPlayers == other.allPlayers &&
            quorumTracks == other.quorumTracks
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + encryptedBy.hashCode()
        result = 31 * result + strippedBy.hashCode()
        result = 31 * result + visibilityQuorum.hashCode()
        result = 31 * result + allPlayers.hashCode()
        result = 31 * result + quorumTracks.hashCode()
        return result
    }
}

/**
 * One quorum member's private-copy reveal chain on a partial multi-member
 * quorum card ([CardState.isPartialQuorum]).
 *
 * The track for member `r` starts from the card's quorum-revealed ciphertext
 * (all non-members stripped — the card still carries every *member's* layer)
 * and accumulates strips by the **other** quorum members, in the canonical
 * order defined by [DealSession]. When [strippedBy] reaches
 * `visibilityQuorum - r`, [ciphertext] carries only `r`'s own layer — exactly
 * as protected as a single-reader card's revealed state — and `r` strips it
 * locally in [DealSession.decrypt]. `r`'s own layer never comes off publicly.
 */
@Serializable
public data class QuorumTrack(
    val ciphertext: ByteArray,
    val strippedBy: GSet<PlayerId>,
) {
    /**
     * Join two states of the same track: [strippedBy] is a GSet union, and the
     * ciphertext of the side further along the strip chain wins (each honest
     * strip removes exactly one layer, so |strippedBy| totally orders one
     * track's honest chain). Ties (a protocol-violating fork) break
     * deterministically by ciphertext byte order — mirroring [CardState.merge].
     */
    public fun merge(other: QuorumTrack): QuorumTrack {
        val winningCiphertext = when {
            strippedBy.elements.size > other.strippedBy.elements.size -> ciphertext
            strippedBy.elements.size < other.strippedBy.elements.size -> other.ciphertext
            else -> if (compareCiphertext(ciphertext, other.ciphertext) <= 0) ciphertext else other.ciphertext
        }
        return QuorumTrack(
            ciphertext = winningCiphertext,
            strippedBy = strippedBy.piece(other.strippedBy),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuorumTrack) return false
        return ciphertext.contentEquals(other.ciphertext) && strippedBy == other.strippedBy
    }

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + strippedBy.hashCode()
}

/** Key-wise join of two track maps: union of members, per-member [QuorumTrack.merge]. */
private fun mergeTracks(
    a: Map<PlayerId, QuorumTrack>,
    b: Map<PlayerId, QuorumTrack>,
): Map<PlayerId, QuorumTrack> {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    return (a.keys + b.keys).associateWith { member ->
        val left = a[member]
        val right = b[member]
        when {
            left != null && right != null -> left.merge(right)
            // member came from a.keys ∪ b.keys and track values are non-null, so at
            // least one side is present; error is unreachable but keeps this !!-free.
            else -> left ?: right ?: error("member $member present in neither track map")
        }
    }
}

private fun compareCiphertext(a: ByteArray, b: ByteArray): Int {
    val min = minOf(a.size, b.size)
    for (i in 0 until min) {
        val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
        if (cmp != 0) return cmp
    }
    return a.size.compareTo(b.size)
}
