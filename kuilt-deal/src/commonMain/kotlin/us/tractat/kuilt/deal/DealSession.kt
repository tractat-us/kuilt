@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GSet

/**
 * Drives a cryptographically fair card deal over a [Seam], as an op-based CRDT.
 *
 * Each card is a [CardState]; players sequentially encrypt the deck (SRA layers
 * compose because the cipher is commutative), then non-quorum players strip their
 * layer so only the visibility quorum can read each card. State converges via the
 * [CardOp] stream broadcast over the seam.
 */
public class DealSession(
    private val seam: Seam,
    private val scheme: CommutativeScheme,
    private val myKey: SchemeKeyPair,
    private val allPlayers: Set<PlayerId>,
    private val myId: PlayerId,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DeckState(emptyList()))
    public val state: StateFlow<DeckState> = _state.asStateFlow()

    init {
        seam.incoming
            .onEach { swatch ->
                val frame = Cbor.decodeFromByteArray<IndexedCardOp>(swatch.payload)
                _state.update { deck -> deck.applyRemote(frame.cardIndex, frame.op) }
            }
            .launchIn(scope)
    }

    /**
     * Apply a remote op, growing the deck with placeholder cards if the op references
     * an index beyond the current deck size. Ops can arrive before local shuffle
     * initialises the deck (the seam cross-wires delivery synchronously in tests).
     */
    private fun DeckState.applyRemote(cardIndex: Int, op: CardOp): DeckState {
        val cards = cards.toMutableList()
        while (cards.size <= cardIndex) {
            cards.add(emptyCard())
        }
        val next = cards[cardIndex].applyOp(op) ?: return this
        cards[cardIndex] = next
        return copy(cards = cards)
    }

    private fun emptyCard(): CardState = CardState(
        ciphertext = ByteArray(0),
        encryptedBy = GSet.empty(),
        strippedBy = GSet.empty(),
        visibilityQuorum = emptySet(),
        allPlayers = allPlayers,
    )

    /**
     * Encrypt every card with my key. Seeds the deck from [deck] on first call
     * (encoding each plaintext once via [encodePlaintext]). If remote ops have
     * already grown the deck with placeholder cards (the synchronous test harness
     * delivers the first shuffler's ops before the second shuffler calls this),
     * the deck is used as-is so the second shuffler's key composes on top.
     */
    public suspend fun shuffle(deck: List<ByteArray>) {
        if (_state.value.cards.isEmpty()) {
            _state.value = DeckState(
                deck.map { plaintext ->
                    CardState(
                        ciphertext = encodePlaintext(plaintext),
                        encryptedBy = GSet.empty(),
                        strippedBy = GSet.empty(),
                        visibilityQuorum = emptySet(),
                        allPlayers = allPlayers,
                    )
                },
            )
        }

        for (index in _state.value.cards.indices) {
            encryptCard(index)
        }
    }

    private suspend fun encryptCard(index: Int) {
        val card = _state.value.cards[index]
        if (myId in card.encryptedBy.elements) return
        val (newCiphertext, proof) = scheme.encrypt(card.ciphertext, myKey.encryptKey)
        val op = CardOp.Encrypt(myId, newCiphertext, proof)
        _state.update { deck -> deck.applyLocalOp(index, op) }
        seam.broadcast(Cbor.encodeToByteArray(IndexedCardOp(index, op)))
    }

    /** Set visibility quorums for each card (local state — no broadcast). */
    public fun assignQuorums(assignments: Map<Int, Set<PlayerId>>) {
        _state.update { deck ->
            val cards = deck.cards.toMutableList()
            assignments.forEach { (index, quorum) ->
                if (index < cards.size) cards[index] = cards[index].copy(visibilityQuorum = quorum)
            }
            deck.copy(cards = cards)
        }
    }

    /** Strip my encryption layer from all cards where I am not in the visibility quorum. */
    public suspend fun strip() {
        for (index in _state.value.cards.indices) {
            stripCard(index)
        }
    }

    private suspend fun stripCard(index: Int) {
        val card = _state.value.cards[index]
        if (myId in card.visibilityQuorum) return
        if (myId !in card.encryptedBy.elements) return
        if (myId in card.strippedBy.elements) return
        val (newCiphertext, proof) = scheme.strip(card.ciphertext, myKey.stripKey)
        val op = CardOp.Strip(myId, newCiphertext, proof)
        _state.update { deck -> deck.applyLocalOp(index, op) }
        seam.broadcast(Cbor.encodeToByteArray(IndexedCardOp(index, op)))
    }

    /**
     * Remove my own encryption layer from the card at [cardIndex] and decode the
     * original plaintext. Call once the card is REVEALED (all non-quorum players
     * have stripped). Local — does not broadcast.
     */
    public fun decrypt(cardIndex: Int): ByteArray {
        val card = _state.value.cards[cardIndex]
        val (stripped, _) = scheme.strip(card.ciphertext, myKey.stripKey)
        return decodePlaintext(stripped)
    }

    /** Optionally escrow my key to a trusted server for liveness recovery. */
    public suspend fun depositKey(escrow: KeyEscrow) {
        escrow.deposit(myId, EncryptedKey(myKey.encryptKey.raw))
    }

    private fun DeckState.applyLocalOp(index: Int, op: CardOp): DeckState {
        val updated = cards[index].applyOp(op) ?: return this
        return copy(cards = cards.toMutableList().also { it[index] = updated })
    }
}

/** Wire type for Seam transport: card index + the op to apply. */
@Serializable
internal data class IndexedCardOp(val cardIndex: Int, val op: CardOp)
