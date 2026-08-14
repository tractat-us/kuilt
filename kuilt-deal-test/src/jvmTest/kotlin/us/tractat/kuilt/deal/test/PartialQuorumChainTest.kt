@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.deal.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.deal.CardPhase
import us.tractat.kuilt.deal.SraScheme
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A 3-of-4 visibility quorum needs *chained* reveal-track strips: each member's
 * private copy requires the other two members to strip in canonical order, so a
 * single [us.tractat.kuilt.deal.DealSession.strip] pass per player is not enough —
 * players re-invoke `strip()` as ops arrive until the card is
 * [CardPhase.REVEALED]. JVM-only: four SRA-2048 key generations plus the
 * quadratic track work is too heavy for the wasmJs test budget.
 */
class PartialQuorumChainTest {

    @Test
    fun fourPlayerDeal_threeMemberQuorum_chainedTrackStripsRevealToAllMembers() = runTest {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val carol = PeerId("carol")
        val dave = PeerId("dave")
        val sessions = fakeDealSessionGroup(
            playerIds = listOf(alice, bob, carol, dave),
            newScheme = { SraScheme() },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        val (aliceSession, bobSession, carolSession, daveSession) = sessions
        val members = listOf(aliceSession, bobSession, carolSession)

        val originalCard = "QUEEN_OF_DIAMONDS".encodeToByteArray()
        sessions.forEach { it.shuffle(listOf(originalCard)) }

        val quorum = mapOf(0 to setOf(alice, bob, carol))
        sessions.forEach { it.assignQuorums(quorum) }

        // Non-member strips the main chain; members then drive the reveal tracks
        // to completion, re-invoking strip() until every track is done (each pass
        // completes at least one turn per track, so |quorum| - 1 passes suffice).
        daveSession.strip()
        repeat(2) { members.forEach { it.strip() } }

        val daveAttempt = runCatchingCancellable { daveSession.decrypt(0) }.getOrNull()
        assertAll(
            { assertEquals(CardPhase.REVEALED, aliceSession.state.value.phase(0)) },
            { assertEquals(originalCard.toList(), aliceSession.decrypt(0).toList()) },
            { assertEquals(originalCard.toList(), bobSession.decrypt(0).toList()) },
            { assertEquals(originalCard.toList(), carolSession.decrypt(0).toList()) },
            { assertNotEquals(originalCard.toList(), daveAttempt?.toList()) },
        )
    }
}
