@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.crdt.GSet

/**
 * Drives a cryptographically fair card deal over a [Seam], as an op-based CRDT.
 *
 * Each card is a [CardState]; players sequentially encrypt the deck (SRA layers
 * compose because the cipher is commutative), then non-quorum players strip their
 * layer so only the visibility quorum can read each card. State converges via the
 * [CardOp] stream broadcast over the seam: each op carries the base sets it was
 * computed against and is folded in via [CardState.merge], so convergence holds
 * under per-sender FIFO delivery even when ops from different senders interleave
 * differently at different peers (cross-sender transport reorder).
 *
 * **Single-local-writer assumption.** The [shuffle] and [strip] helpers read the
 * base ciphertext from [state] outside the `_state.update` block, run crypto, then
 * commit. If the incoming-op collector races to apply a remote op to the *same
 * card* in that window, the local op was computed against a stale base and its
 * merged ciphertext can lose the remote layer while the metadata claims it. This
 * is safe under the current model where writes to a card are serialized by the
 * protocol (one shuffler/stripper at a time); concurrent local shuffle/strip while
 * remote ops mutate the same card is a production hazard that needs an "await my
 * turn" gate (deferred).
 */
public class DealSession(
    private val seam: Seam,
    private val scheme: CommutativeScheme,
    private val myKey: SchemeKeyPair,
    private val allPlayers: Set<PlayerId>,
    private val myId: PlayerId,
    scope: CoroutineScope,
) : ScopedCloseable(scope) {
    private val _state = MutableStateFlow(DeckState(emptyList()))
    public val state: StateFlow<DeckState> = _state.asStateFlow()

    private val incomingJob: Job = seam.incoming
        .onEach { swatch ->
            val frame = try {
                swatch.decode(Cbor, IndexedCardOp.serializer())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Drop a malformed/foreign frame rather than letting it cancel the
                // collector (which would silently halt all further op delivery).
                return@onEach
            }
            _state.update { deck -> deck.applyRemote(frame.cardIndex, frame.op) }
        }
        .launchIn(this.scope)

    /** Exposed internally so tests can verify [close] cancels the background job. */
    internal val incomingJobForTest: Job get() = incomingJob

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
        val op = CardOp.Encrypt(
            player = myId,
            newCiphertext = newCiphertext,
            proof = proof,
            baseEncryptedBy = card.encryptedBy.elements,
            baseStrippedBy = card.strippedBy.elements,
        )
        _state.update { deck -> deck.applyLocalOp(index, op) }
        seam.broadcast(Cbor.encodeToByteArray(IndexedCardOp(index, op)))
    }

    /**
     * Set visibility quorums for each card (local state — no broadcast).
     *
     * Supported quorums: a single reader (`|quorum| == 1`), a public/community
     * card (`quorum == allPlayers`, or an empty quorum), and nothing in between —
     * a partial multi-member quorum's members could never decrypt (the card would
     * still carry the *other* members' layers, which [CardOp.Strip] structurally
     * refuses to remove) so it is rejected here rather than discovered as a
     * permanently unreadable card at reveal time.
     *
     * @throws IllegalArgumentException if a quorum contains an unknown player or
     *   has `1 < |quorum| < |allPlayers|`.
     */
    public fun assignQuorums(assignments: Map<Int, Set<PlayerId>>) {
        assignments.forEach { (index, quorum) ->
            require(allPlayers.containsAll(quorum)) {
                "Quorum for card $index contains players not in this deal: ${quorum - allPlayers}"
            }
            require(quorum.size <= 1 || quorum == allPlayers) {
                "Unsupported visibility quorum for card $index (${quorum.size} of ${allPlayers.size} players): " +
                    "only a single reader, a community card (quorum == allPlayers), or an empty quorum is " +
                    "supported — a partial multi-member quorum can never be decrypted by its members " +
                    "without private re-encryption"
            }
        }
        _state.update { deck ->
            val cards = deck.cards.toMutableList()
            assignments.forEach { (index, quorum) ->
                if (index < cards.size) cards[index] = cards[index].copy(visibilityQuorum = quorum)
            }
            deck.copy(cards = cards)
        }
    }

    /**
     * Strip my encryption layer from all cards that don't need it for secrecy:
     * cards where I am not in the visibility quorum, plus community cards
     * (quorum == allPlayers), which every player must strip.
     */
    public suspend fun strip() {
        for (index in _state.value.cards.indices) {
            stripCard(index)
        }
    }

    private suspend fun stripCard(index: Int) {
        val card = _state.value.cards[index]
        val keepsQuorumSecrecy = myId in card.visibilityQuorum && card.visibilityQuorum != allPlayers
        if (keepsQuorumSecrecy) return
        if (myId !in card.encryptedBy.elements) return
        if (myId in card.strippedBy.elements) return
        val (newCiphertext, proof) = scheme.strip(card.ciphertext, myKey.stripKey)
        val op = CardOp.Strip(
            player = myId,
            newCiphertext = newCiphertext,
            proof = proof,
            baseEncryptedBy = card.encryptedBy.elements,
            baseStrippedBy = card.strippedBy.elements,
        )
        _state.update { deck -> deck.applyLocalOp(index, op) }
        seam.broadcast(Cbor.encodeToByteArray(IndexedCardOp(index, op)))
    }

    /**
     * Decode the plaintext of the card at [cardIndex]. Call once the card is
     * [CardPhase.REVEALED]. If my own layer is the one remaining (single-reader
     * quorum), it is stripped locally first; a community card (everyone stripped)
     * decodes directly. Local — does not broadcast.
     *
     * @throws IllegalArgumentException if the card is not yet [CardPhase.REVEALED],
     *   or is revealed to a quorum I am not part of.
     */
    public fun decrypt(cardIndex: Int): ByteArray {
        val card = _state.value.cards[cardIndex]
        require(card.phase() == CardPhase.REVEALED) {
            "Card $cardIndex is not REVEALED (phase=${card.phase()}); cannot decrypt until all non-quorum players have stripped"
        }
        val remainingLayers = card.encryptedBy.elements - card.strippedBy.elements
        return when {
            remainingLayers.isEmpty() -> decodePlaintext(card.ciphertext)
            remainingLayers == setOf(myId) -> {
                val (stripped, _) = scheme.strip(card.ciphertext, myKey.stripKey)
                decodePlaintext(stripped)
            }
            else -> throw IllegalArgumentException(
                "Card $cardIndex is REVEALED but still carries encryption layers $remainingLayers " +
                    "that are not mine ($myId); only the visibility quorum can decrypt it",
            )
        }
    }

    /** Optionally escrow my key to a trusted server for liveness recovery. */
    public suspend fun depositKey(escrow: KeyEscrow) {
        escrow.deposit(myId, EncryptedKey(myKey.encryptKey.raw))
    }

    private fun DeckState.applyLocalOp(index: Int, op: CardOp): DeckState {
        val updated = cards[index].applyOp(op) ?: return this
        return copy(cards = cards.mapIndexed { i, c -> if (i == index) updated else c })
    }
}

/** Wire type for Seam transport: card index + the op to apply. */
@Serializable
internal data class IndexedCardOp(val cardIndex: Int, val op: CardOp)
