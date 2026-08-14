@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.deal.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.deal.DealSession
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

// These tests exercise the deal SESSION / CRDT reveal logic, not the crypto itself.
// They drive it with the fast [XorKeystreamScheme] test double instead of the real
// 2048-bit SRA scheme: XOR is commutative and self-inverse, so it satisfies the same
// commutative-encryption laws (verified by XorKeystreamSchemeConformanceTest) while
// running in microseconds on every platform. Real-crypto byte-parity coverage lives in
// SraSchemeConformanceTest. Using the real SraScheme here used to run the pure-Kotlin
// ionspin big-integer path on Apple/K/N and wasmJs (~40× slower than the JVM intrinsic),
// where the partial-quorum scenario's chained modPows intermittently blew runTest's
// timeout on contended CI (issue #1332 / #1324) — the double removes that cost entirely,
// so the default runTest timeout suffices.

class DealSessionTest {

    @Test
    fun twoPlayerPokerDeal_aliceSeesHerCard_bobCannotRead() = runTest {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val (aliceSession, bobSession) =
            fakeDealSessionPair(alice, bob, seededXorSchemes(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

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
        val (aliceSession, bobSession) =
            fakeDealSessionPair(alice, bob, seededXorSchemes(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

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
        val (aliceSession, bobSession) =
            fakeDealSessionPair(alice, bob, seededXorSchemes(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

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
    fun assignQuorums_rejectsUnknownPlayers() = runTest {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val scheme = XorKeystreamScheme()
        val session = DealSession(
            seam = FakeSeam(selfId = alice),
            scheme = scheme,
            myKey = scheme.generateKey(),
            allPlayers = setOf(alice, bob),
            myId = alice,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        assertFailsWith<IllegalArgumentException> {
            session.assignQuorums(mapOf(0 to setOf(alice, PeerId("mallory"))))
        }
    }

    @Test
    fun partialQuorum_membersDecrypt_nonMemberCannot() = runTest {
        // A card visible to exactly 2 of 3 players (issue #1281): after carol (the
        // non-member) strips, the quorum members cooperatively strip per-member
        // reveal tracks so each member ends up holding a copy carrying only their
        // own layer — without the plaintext ever becoming public.
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val carol = PeerId("carol")
        val sessions = fakeDealSessionGroup(
            playerIds = listOf(alice, bob, carol),
            newScheme = seededXorSchemes(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        val (aliceSession, bobSession, carolSession) = sessions

        val originalCard = "ACE_OF_SPADES".encodeToByteArray()
        val deck = listOf(originalCard)
        sessions.forEach { it.shuffle(deck) }

        val partialQuorum = mapOf(0 to setOf(alice, bob))
        sessions.forEach { it.assignQuorums(partialQuorum) }

        // Non-member strips first (main chain), then each member strips the
        // other member's reveal track.
        carolSession.strip()
        aliceSession.strip()
        bobSession.strip()

        val carolAttempt = runCatchingCancellable { carolSession.decrypt(0) }.getOrNull()
        assertAll(
            { assertEquals(originalCard.toList(), aliceSession.decrypt(0).toList()) },
            { assertEquals(originalCard.toList(), bobSession.decrypt(0).toList()) },
            { assertNotEquals(originalCard.toList(), carolAttempt?.toList()) },
        )
    }

    /**
     * A factory handing each session its **own** [XorKeystreamScheme], as every player runs their
     * own in production (#2311) — and its own seed, drawn from one seeded source so the whole run
     * stays deterministic. Two peers seeded identically would hold the same XOR key, and XOR
     * layers with equal keys cancel, so the deal would degenerate rather than exercise anything.
     */
    private fun seededXorSchemes(seed: Int = 0x0DEA1): () -> XorKeystreamScheme {
        val seeder = Random(seed)
        return { XorKeystreamScheme(Random(seeder.nextInt())) }
    }
}
