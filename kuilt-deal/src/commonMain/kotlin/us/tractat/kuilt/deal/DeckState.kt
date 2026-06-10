package us.tractat.kuilt.deal

import kotlinx.serialization.Serializable

@Serializable
public data class DeckState(
    val cards: List<CardState>,
) {
    public fun phase(index: Int): CardPhase = cards[index].phase()

    public fun isFullyShuffled(): Boolean =
        cards.all { it.phase() == CardPhase.FULLY_ENCRYPTED || it.phase() == CardPhase.REVEALING || it.phase() == CardPhase.REVEALED }

    public fun isRevealed(index: Int): Boolean = cards[index].phase() == CardPhase.REVEALED

    public fun merge(other: DeckState): DeckState {
        require(cards.size == other.cards.size) {
            "Cannot merge decks of different sizes: ${cards.size} vs ${other.cards.size}"
        }
        return DeckState(cards.zip(other.cards) { a, b -> a.merge(b) })
    }
}
