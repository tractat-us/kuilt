@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The directory-replication contract behind [ServerCluster]'s **federated** overload
 * (`CoroutineScope.serverCluster(host, voterIds, raftConfig, overlay)`): two servers'
 * [OverlayServer]s, each replicating over a shared inter-server seam with a real
 * [us.tractat.kuilt.quilter.Quilter], converge on who a client is attached to — and a
 * re-admit on the second server **supersedes** the first under last-writer-wins.
 *
 * When each cluster is built with its own federated overlay, that is exactly the
 * cross-server visibility a far player's routing depends on: a client admitted on S1
 * becomes lookup-visible on S2, and a failover re-admit re-homes the whole core.
 *
 * Drives real [us.tractat.kuilt.core.Seam]s ([InMemoryLoom]) under
 * `UnconfinedTestDispatcher` — the established replicator-test harness (mirrors
 * [OverlayServerFailoverTest]); no Raft cluster is in the loop, so a tight timeout
 * keeps a non-converging run fast to fail.
 */
class ServerClusterDirectoryConvergenceTest {

    private val client = PeerId("client-alice")

    @Test
    fun admitOnOneServerConvergesToTheOther_andReAdmitSupersedes() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val coreLoom = InMemoryLoom()
            val s1Core = coreLoom.host(Pattern("core"))
            val s2Core = coreLoom.join(InMemoryTag("core"))
            val s1 = s1Core.selfId
            val s2 = s2Core.selfId

            val dirLoom = InMemoryLoom()
            val s1Dir = dirLoom.host(Pattern("dir"))
            val s2Dir = dirLoom.join(InMemoryTag("dir"))

            val clock = increasingClock()
            val cfg = QuilterConfig(expectVirtualTime = true)
            val overlay1 = overlayServer(self = s1, coreSeam = s1Core, directorySeam = s1Dir, scope = backgroundScope, clock = clock, directoryConfig = cfg)
            val overlay2 = overlayServer(self = s2, coreSeam = s2Core, directorySeam = s2Dir, scope = backgroundScope, clock = clock, directoryConfig = cfg)

            // Admitted on S2 (its two-peer client link is irrelevant to the directory here).
            val bLoom = InMemoryLoom()
            val s2ToClient = bLoom.host(Pattern("s2-client"))
            overlay2.admit(client, s2ToClient)
            testScheduler.advanceUntilIdle()
            assertEquals(s2, overlay1.lookup(client), "admit on S2 converges to S1's lookup")
            assertEquals(s2, overlay2.lookup(client), "S2 sees its own attachment")

            // Re-admit on S1 (a failover) supersedes the S2 entry under last-writer-wins.
            val bLoom1 = InMemoryLoom()
            val s1ToClient = bLoom1.host(Pattern("s1-client"))
            overlay1.admit(client, s1ToClient)
            testScheduler.advanceUntilIdle()
            assertEquals(s1, overlay1.lookup(client), "re-admit on S1 supersedes locally")
            assertEquals(s1, overlay2.lookup(client), "and converges: S2 now routes the client to S1")
        }

    /** A shared, strictly-increasing controlled clock standing in for well-synced wall clocks. */
    private fun increasingClock(): () -> Long {
        var t = 0L
        return { ++t }
    }
}
