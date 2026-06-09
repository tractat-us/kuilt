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

    public fun merge(other: CardState): CardState {
        // Ciphertext convergence: the side with more encryption layers wins.
        // On a tie (equal encryptor count — possibly divergent members mid-shuffle),
        // break deterministically by ciphertext byte order so merge stays commutative.
        // Once encryptedBy converges the two ciphertexts are byte-identical, so the
        // tie-break is invisible in steady state.
        val winningCiphertext = when {
            encryptedBy.elements.size > other.encryptedBy.elements.size -> ciphertext
            encryptedBy.elements.size < other.encryptedBy.elements.size -> other.ciphertext
            else -> if (compareCiphertext(ciphertext, other.ciphertext) <= 0) ciphertext else other.ciphertext
        }
        return copy(
            encryptedBy = encryptedBy.piece(other.encryptedBy),
            strippedBy = strippedBy.piece(other.strippedBy),
            ciphertext = winningCiphertext,
        )
    }

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

private fun compareCiphertext(a: ByteArray, b: ByteArray): Int {
    val min = minOf(a.size, b.size)
    for (i in 0 until min) {
        val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
        if (cmp != 0) return cmp
    }
    return a.size.compareTo(b.size)
}
