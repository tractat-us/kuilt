@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.deal.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.deal.DealSession
import us.tractat.kuilt.deal.SraScheme
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DealSessionTest {

    @Test
    fun twoPlayerPokerDeal_aliceSeesHerCard_bobCannotRead() = runTest {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val scheme = SraScheme()
        val (aliceSession, bobSession) =
            fakeDealSessionPair(alice, bob, scheme, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

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
        val bobAttempt = runCatchingCancellable { bobSession.decrypt(0) }.getOrNull()
        assertNotEquals(originalCard.toList(), bobAttempt?.toList())
    }

    @Test
    fun twoPlayerDeal_holderCannotSeeOwnCard() = runTest {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val scheme = SraScheme()
        val (aliceSession, bobSession) =
            fakeDealSessionPair(alice, bob, scheme, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

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
        val aliceAttempt = runCatchingCancellable { aliceSession.decrypt(0) }.getOrNull()
        assertNotEquals(originalCard.toList(), aliceAttempt?.toList())
    }

    @Test
    fun communityCard_quorumOfAllPlayers_everyPlayerCanDecrypt() = runTest {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val scheme = SraScheme()
        val (aliceSession, bobSession) =
            fakeDealSessionPair(alice, bob, scheme, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        val originalCard = "QUEEN_OF_DIAMONDS".encodeToByteArray()
        val deck = listOf(originalCard)

        aliceSession.shuffle(deck)
        bobSession.shuffle(deck)

        // Community card: everyone is in the visibility quorum (issue #1274).
        val community = mapOf(0 to setOf(alice, bob))
        aliceSession.assignQuorums(community)
        bobSession.assignQuorums(community)

        // Everyone strips — a community card has no secrecy to preserve.
        aliceSession.strip()
        bobSession.strip()

        assertAll(
            { assertEquals(originalCard.toList(), aliceSession.decrypt(0).toList()) },
            { assertEquals(originalCard.toList(), bobSession.decrypt(0).toList()) },
        )
    }

    @Test
    fun assignQuorums_rejectsPartialMultiMemberQuorum() = runTest {
        // A quorum with 1 < |quorum| < |allPlayers| can never be decrypted by its
        // members without private re-encryption (issue #1274) — reject it up front.
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val carol = PeerId("carol")
        val scheme = SraScheme()
        val session = DealSession(
            seam = FakeSeam(selfId = alice),
            scheme = scheme,
            myKey = scheme.generateKey(),
            allPlayers = setOf(alice, bob, carol),
            myId = alice,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        assertFailsWith<IllegalArgumentException> {
            session.assignQuorums(mapOf(0 to setOf(alice, bob)))
        }
    }
}
