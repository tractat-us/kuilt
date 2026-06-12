@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import us.tractat.kuilt.conformance.CloseableLifecycleConformanceSuite
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.test.fakeSeamPair

class DealSessionCloseTest : CloseableLifecycleConformanceSuite() {

    override fun create(scope: CoroutineScope): ScopedCloseable {
        val peerId = PeerId("alice")
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

    override fun backgroundJobsOf(instance: ScopedCloseable): List<Job> =
        listOf((instance as DealSession).incomingJobForTest)
}
