package us.tractat.kuilt.deal

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.GSet

@Serializable
public sealed class CardOp {

    @Serializable
    public data class Encrypt(
        val player: PlayerId,
        val newCiphertext: ByteArray,
        val proof: EncryptProof,
    ) : CardOp() {
        override fun equals(other: Any?): Boolean =
            other is Encrypt && player == other.player && newCiphertext.contentEquals(other.newCiphertext)
        override fun hashCode(): Int = 31 * player.hashCode() + newCiphertext.contentHashCode()
    }

    @Serializable
    public data class Strip(
        val player: PlayerId,
        val newCiphertext: ByteArray,
        val proof: StripProof,
    ) : CardOp() {
        override fun equals(other: Any?): Boolean =
            other is Strip && player == other.player && newCiphertext.contentEquals(other.newCiphertext)
        override fun hashCode(): Int = 31 * player.hashCode() + newCiphertext.contentHashCode()
    }

    @Serializable
    public data class DepositKey(
        val player: PlayerId,
        val escrowedKey: EncryptedKey,
    ) : CardOp()
}

/** Returns true iff [op] is valid to apply to this [CardState]. */
public fun CardState.canApply(op: CardOp): Boolean = when (op) {
    is CardOp.Encrypt -> op.player !in encryptedBy.elements
    is CardOp.Strip -> op.player in encryptedBy.elements &&
        op.player !in strippedBy.elements &&
        op.player !in visibilityQuorum
    is CardOp.DepositKey -> true
}

/** Returns the next [CardState] after applying [op], or null if [op] is invalid. */
public fun CardState.applyOp(op: CardOp): CardState? {
    if (!canApply(op)) return null
    return when (op) {
        is CardOp.Encrypt -> copy(
            ciphertext = op.newCiphertext,
            encryptedBy = encryptedBy.piece(GSet.of(op.player)),
        )
        is CardOp.Strip -> copy(
            ciphertext = op.newCiphertext,
            strippedBy = strippedBy.piece(GSet.of(op.player)),
        )
        is CardOp.DepositKey -> this
    }
}
