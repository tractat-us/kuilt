@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fakeSeamPair
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DealSessionCloseTest {

    private fun makeDealSession(peerId: PeerId, scope: kotlinx.coroutines.CoroutineScope): DealSession {
        val allPlayers = setOf(peerId, PeerId("other"))
        val (seam, _) = fakeSeamPair(peerId, PeerId("other"))
        val scheme = SraScheme()
        return DealSession(
            seam = seam,
            scheme = scheme,
            myKey = scheme.generateKey(),
            allPlayers = allPlayers,
            myId = peerId,
            scope = scope,
        )
    }

    @Test
    fun incomingJobActiveBeforeClose() = runTest(UnconfinedTestDispatcher()) {
        val session = makeDealSession(PeerId("alice"), backgroundScope)
        assertTrue(session.incomingJobForTest.isActive)
        // Cancel before runTest cleanup to prevent the infinite collector from blocking drain.
        session.close()
    }

    @Test
    fun closeStopsIncomingJob() = runTest(UnconfinedTestDispatcher()) {
        val session = makeDealSession(PeerId("alice"), backgroundScope)
        session.close()
        assertFalse(session.incomingJobForTest.isActive)
    }

    @Test
    fun closeIsIdempotent() = runTest(UnconfinedTestDispatcher()) {
        val session = makeDealSession(PeerId("alice"), backgroundScope)
        session.close()
        session.close() // must not throw
        assertFalse(session.incomingJobForTest.isActive)
    }
}
