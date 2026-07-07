package us.tractat.kuilt.deal

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.GSet

/**
 * A card mutation broadcast over the seam.
 *
 * [Encrypt] and [Strip] carry the **base sets** ([Encrypt.baseEncryptedBy]/
 * [Encrypt.baseStrippedBy], likewise on [Strip]) of the card state the sender
 * computed the op against. A receiver reconstructs the sender's *resulting*
 * [CardState] from them and folds it in via [CardState.merge], so application
 * is order-independent: `Seam.incoming` guarantees only per-sender FIFO, and
 * ops from different senders may interleave differently at different peers.
 */
@Serializable
public sealed class CardOp {

    @Serializable
    public data class Encrypt(
        val player: PlayerId,
        val newCiphertext: ByteArray,
        val proof: EncryptProof,
        /** [CardState.encryptedBy] of the state this op was computed against. */
        val baseEncryptedBy: Set<PlayerId>,
        /** [CardState.strippedBy] of the state this op was computed against. */
        val baseStrippedBy: Set<PlayerId>,
    ) : CardOp() {
        // proof is excluded from identity: exactly-once is enforced by the GSet
        // membership check in canApply, not by op-equality dedup.
        override fun equals(other: Any?): Boolean {
            if (other !is Encrypt) return false
            return player == other.player &&
                newCiphertext.contentEquals(other.newCiphertext) &&
                baseEncryptedBy == other.baseEncryptedBy &&
                baseStrippedBy == other.baseStrippedBy
        }

        override fun hashCode(): Int {
            var result = player.hashCode()
            result = 31 * result + newCiphertext.contentHashCode()
            result = 31 * result + baseEncryptedBy.hashCode()
            result = 31 * result + baseStrippedBy.hashCode()
            return result
        }
    }

    @Serializable
    public data class Strip(
        val player: PlayerId,
        val newCiphertext: ByteArray,
        val proof: StripProof,
        /** [CardState.encryptedBy] of the state this op was computed against. */
        val baseEncryptedBy: Set<PlayerId>,
        /** [CardState.strippedBy] of the state this op was computed against. */
        val baseStrippedBy: Set<PlayerId>,
    ) : CardOp() {
        // proof is excluded from identity: exactly-once is enforced by the GSet
        // membership check in canApply, not by op-equality dedup.
        override fun equals(other: Any?): Boolean {
            if (other !is Strip) return false
            return player == other.player &&
                newCiphertext.contentEquals(other.newCiphertext) &&
                baseEncryptedBy == other.baseEncryptedBy &&
                baseStrippedBy == other.baseStrippedBy
        }

        override fun hashCode(): Int {
            var result = player.hashCode()
            result = 31 * result + newCiphertext.contentHashCode()
            result = 31 * result + baseEncryptedBy.hashCode()
            result = 31 * result + baseStrippedBy.hashCode()
            return result
        }
    }

    @Serializable
    public data class DepositKey(
        val player: PlayerId,
        val escrowedKey: EncryptedKey,
    ) : CardOp()
}

/**
 * Returns true iff [op] is valid to apply to this [CardState].
 *
 * The membership checks against local state double as dedup and as the primary
 * double-encode defence; they are order-independent under per-sender FIFO
 * delivery because a player appears in the local sets only via ops that
 * *subsume* the rejected one (see [applyOp]).
 */
public fun CardState.canApply(op: CardOp): Boolean = when (op) {
    is CardOp.Encrypt ->
        op.player !in encryptedBy.elements && op.player !in op.baseEncryptedBy
    is CardOp.Strip -> {
        val selfConsistent = op.player in op.baseEncryptedBy && op.player !in op.baseStrippedBy
        val newLocally = op.player in encryptedBy.elements && op.player !in strippedBy.elements
        // A quorum member's layer is what keeps the card private to the quorum —
        // except on a community card (quorum == allPlayers), where everyone must
        // strip for everyone to read.
        val allowedToStrip = op.player !in visibilityQuorum || visibilityQuorum == allPlayers
        selfConsistent && newLocally && allowedToStrip
    }
    // DepositKey is only valid once the card is at least FULLY_ENCRYPTED — escrowing
    // key material earlier would let a holder leverage key knowledge before the deck
    // is committed.
    is CardOp.DepositKey -> phase() != CardPhase.UNENCRYPTED && phase() != CardPhase.SHUFFLING
}

/**
 * Returns the next [CardState] after applying [op], or null if [op] is invalid.
 *
 * Application reconstructs the op's *resulting* card state (its base sets plus
 * the op's own contribution, with [CardOp.Encrypt.newCiphertext] /
 * [CardOp.Strip.newCiphertext]) and joins it via [CardState.merge]. Because
 * merge is a commutative, associative, idempotent join, replicas converge on
 * the layer-complete ciphertext regardless of cross-sender arrival order —
 * an op that raced ahead carries its causal base with it.
 */
public fun CardState.applyOp(op: CardOp): CardState? {
    if (!canApply(op)) return null
    return when (op) {
        is CardOp.Encrypt -> merge(
            copy(
                ciphertext = op.newCiphertext,
                encryptedBy = gSetOf(op.baseEncryptedBy + op.player),
                strippedBy = gSetOf(op.baseStrippedBy),
            ),
        )
        is CardOp.Strip -> merge(
            copy(
                ciphertext = op.newCiphertext,
                encryptedBy = gSetOf(op.baseEncryptedBy),
                strippedBy = gSetOf(op.baseStrippedBy + op.player),
            ),
        )
        // DepositKey has no card-state effect; the escrow side-effect is the session's concern.
        is CardOp.DepositKey -> this
    }
}

private fun gSetOf(elements: Set<PlayerId>): GSet<PlayerId> =
    GSet.of(*elements.toTypedArray())
