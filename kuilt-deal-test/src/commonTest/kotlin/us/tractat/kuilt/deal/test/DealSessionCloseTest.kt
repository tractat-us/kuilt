@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.deal.test

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.deal.SraScheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DealSessionCloseTest {

    @Test
    fun incomingJobActiveBeforeClose() = runTest(UnconfinedTestDispatcher()) {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val (aliceSession, bobSession) = fakeDealSessionPair(alice, bob, SraScheme(), backgroundScope)
        assertTrue(aliceSession.incomingJobForTest.isActive)
        assertTrue(bobSession.incomingJobForTest.isActive)
        // Cancel before runTest cleanup to prevent the infinite collector from blocking drain.
        aliceSession.close()
        bobSession.close()
    }

    @Test
    fun closeStopsIncomingJob() = runTest(UnconfinedTestDispatcher()) {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val (aliceSession, bobSession) = fakeDealSessionPair(alice, bob, SraScheme(), backgroundScope)
        aliceSession.close()
        bobSession.close()
        assertFalse(aliceSession.incomingJobForTest.isActive)
        assertFalse(bobSession.incomingJobForTest.isActive)
    }

    @Test
    fun closeIsIdempotent() = runTest(UnconfinedTestDispatcher()) {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val (aliceSession, _) = fakeDealSessionPair(alice, bob, SraScheme(), backgroundScope)
        aliceSession.close()
        aliceSession.close() // must not throw
        assertFalse(aliceSession.incomingJobForTest.isActive)
    }
}
