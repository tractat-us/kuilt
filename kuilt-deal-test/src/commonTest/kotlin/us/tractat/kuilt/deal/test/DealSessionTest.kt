package us.tractat.kuilt.deal.test

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.deal.SraScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DealSessionTest {

    @Test
    fun twoPlayerPokerDeal_aliceSeesHerCard_bobCannotRead() = runTest {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val scheme = SraScheme()
        val (aliceSession, bobSession) = fakeDealSessionPair(alice, bob, scheme)

        val originalCard = "ACE_OF_SPADES".encodeToByteArray()
        val deck = listOf(originalCard)

        // Shuffle: both players encrypt the deck (alice first, then bob builds on it)
        aliceSession.shuffle(deck)
        bobSession.shuffle(deck)

        // Deal: alice's hand — only alice can see card 0
        val quorumAlice = mapOf(0 to setOf(alice))
        aliceSession.assignQuorums(quorumAlice)
        bobSession.assignQuorums(quorumAlice)

        // Reveal: non-quorum players (bob) strip their layers
        bobSession.strip()

        // Alice decrypts her own layer
        val revealed = aliceSession.decrypt(0)
        assertEquals(originalCard.toList(), revealed.toList())

        // Secrecy: bob is not in the quorum — he cannot recover the plaintext.
        val bobAttempt = runCatching { bobSession.decrypt(0) }.getOrNull()
        assertNotEquals(originalCard.toList(), bobAttempt?.toList())
    }

    @Test
    fun twoPlayerDeal_holderCannotSeeOwnCard() = runTest {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val scheme = SraScheme()
        val (aliceSession, bobSession) = fakeDealSessionPair(alice, bob, scheme)

        val originalCard = "KING_OF_HEARTS".encodeToByteArray()
        val deck = listOf(originalCard)

        aliceSession.shuffle(deck)
        bobSession.shuffle(deck)

        // holder cannot see their own card — quorum is {bob} (everyone except alice)
        val quorumBob = mapOf(0 to setOf(bob))
        aliceSession.assignQuorums(quorumBob)
        bobSession.assignQuorums(quorumBob)

        // alice strips (she is not in the quorum)
        aliceSession.strip()

        // bob decrypts his own layer
        val revealed = bobSession.decrypt(0)
        assertEquals(originalCard.toList(), revealed.toList())

        // Secrecy: alice is not in the quorum — she cannot recover the plaintext.
        val aliceAttempt = runCatching { aliceSession.decrypt(0) }.getOrNull()
        assertNotEquals(originalCard.toList(), aliceAttempt?.toList())
    }
}
