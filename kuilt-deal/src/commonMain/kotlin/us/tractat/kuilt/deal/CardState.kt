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
) {
    public fun phase(): CardPhase = when {
        encryptedBy.elements.isEmpty() -> CardPhase.UNENCRYPTED
        encryptedBy.elements != allPlayers -> CardPhase.SHUFFLING
        strippedBy.elements.isEmpty() -> CardPhase.FULLY_ENCRYPTED
        strippedBy.elements != (allPlayers - visibilityQuorum) -> CardPhase.REVEALING
        else -> CardPhase.REVEALED
    }

    public fun merge(other: CardState): CardState = copy(
        encryptedBy = encryptedBy.piece(other.encryptedBy),
        strippedBy = strippedBy.piece(other.strippedBy),
        // ciphertext converges to same value by commutativity — take either
        ciphertext = if (encryptedBy.elements.size >= other.encryptedBy.elements.size)
            ciphertext else other.ciphertext,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CardState) return false
        return ciphertext.contentEquals(other.ciphertext) &&
            encryptedBy == other.encryptedBy &&
            strippedBy == other.strippedBy &&
            visibilityQuorum == other.visibilityQuorum &&
            allPlayers == other.allPlayers
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + encryptedBy.hashCode()
        result = 31 * result + strippedBy.hashCode()
        result = 31 * result + visibilityQuorum.hashCode()
        result = 31 * result + allPlayers.hashCode()
        return result
    }
}
