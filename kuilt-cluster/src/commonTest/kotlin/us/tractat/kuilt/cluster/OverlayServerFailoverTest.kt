@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Client failover for the two-tier overlay (slice 5D): when a client's entry server
 * dies it reconnects to any survivor and re-announces itself, and the overlay
 * consequence — a single [OverlayServer.admit] on the new server — re-homes the
 * whole core's routing to it under last-writer-wins. The headline is the
 * **stale-window guard**: a unicast sent *during* the failover is safely dropped
 * (never fanned) while the directory is stale, and the sender's **resend on
 * convergence** lands on the client at its new server.
 *
 * Like [AttachmentDirectoryTest] / [RoutedUnicastRouterTest] this drives real
 * [Seam]s ([InMemoryLoom]) under `UnconfinedTestDispatcher` — the established
 * replicator-test harness. No Raft cluster is in the loop (durable membership is
 * *assumed* Raft-held; this slice is only the overlay consequence), so no
 * `MultiNodeRaftSim`; the tight timeout keeps a non-converging run fast to fail.
 */
class OverlayServerFailoverTest {

    private val bob = PeerId("bob")

    @Test
    fun admitPublishesAttachmentAndRegistersLocalDelivery() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            // admit does BOTH halves: the directory names this server AND a unicast for
            // the client is delivered down its local link. (register-without-attach or
            // attach-without-register would each be a silent drop.)
            val coreLoom = InMemoryLoom()
            val s1Core = coreLoom.host(Pattern("core"))
            val s2Core = coreLoom.join(InMemoryTag("core"))
            val s2 = s2Core.selfId

            val dirLoom = InMemoryLoom()
            val s1Dir = dirLoom.host(Pattern("dir"))
            val s2Dir = dirLoom.join(InMemoryTag("dir"))

            val bLoom = InMemoryLoom()
            val s2ToBob = bLoom.host(Pattern("s2-bob"))
            val bobSeam = bLoom.join(InMemoryTag("s2-bob"))

            val clock = increasingClock()
            val cfg = QuilterConfig(expectVirtualTime = true)
            val overlay1 = overlayServer(self = s1Core.selfId, coreSeam = s1Core, directorySeam = s1Dir, scope = backgroundScope, clock = clock, directoryConfig = cfg)
            val overlay2 = overlayServer(self = s2, coreSeam = s2Core, directorySeam = s2Dir, scope = backgroundScope, clock = clock, directoryConfig = cfg)

            overlay2.admit(bob, s2ToBob)
            testScheduler.advanceUntilIdle()
            assertEquals(s2, overlay1.lookup(bob), "admit publishes the attachment to the whole core")

            val bobReceived = collectInto(bobSeam)
            overlay1.route(bob, "hello-bob".encodeToByteArray())
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("hello-bob"), bobReceived.map { it.decodeToString() }, "admit registered local delivery")
        }

    @Test
    fun evictRetractsAttachmentAndDropsLocalDelivery() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val coreLoom = InMemoryLoom()
            val s1Core = coreLoom.host(Pattern("core"))
            val s2Core = coreLoom.join(InMemoryTag("core"))
            val s2 = s2Core.selfId

            val dirLoom = InMemoryLoom()
            val s1Dir = dirLoom.host(Pattern("dir"))
            val s2Dir = dirLoom.join(InMemoryTag("dir"))

            val bLoom = InMemoryLoom()
            val s2ToBob = bLoom.host(Pattern("s2-bob"))
            val bobSeam = bLoom.join(InMemoryTag("s2-bob"))

            val clock = increasingClock()
            val cfg = QuilterConfig(expectVirtualTime = true)
            val overlay1 = overlayServer(self = s1Core.selfId, coreSeam = s1Core, directorySeam = s1Dir, scope = backgroundScope, clock = clock, directoryConfig = cfg)
            val overlay2 = overlayServer(self = s2, coreSeam = s2Core, directorySeam = s2Dir, scope = backgroundScope, clock = clock, directoryConfig = cfg)

            overlay2.admit(bob, s2ToBob)
            testScheduler.advanceUntilIdle()
            assertEquals(s2, overlay1.lookup(bob))

            overlay2.evict(bob)
            testScheduler.advanceUntilIdle()
            assertNull(overlay1.lookup(bob), "evict retracts the attachment across the core")

            // A unicast for the evicted client is now dropped at the origin (directory null).
            val bobReceived = collectInto(bobSeam)
            overlay1.route(bob, "post-evict".encodeToByteArray())
            testScheduler.advanceUntilIdle()
            assertTrue(bobReceived.isEmpty(), "no delivery to an evicted client")
        }

    /**
     * THE HEADLINE: the stale-window guard. A frame sent during a failover reconnect
     * is retried and lands — never dropped for good, never fanned.
     */
    @Test
    fun frameSentDuringFailoverIsRetriedAndLands_staleWindowGuard() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            // Three-server core. S1 is the sender; S2 is Bob's entry server (it will
            // die); S3 is the survivor Bob fails over to.
            val coreLoom = InMemoryLoom()
            val s1Core = coreLoom.host(Pattern("core"))
            val s2Core = coreLoom.join(InMemoryTag("core"))
            val s3Core = coreLoom.join(InMemoryTag("core"))
            val s1 = s1Core.selfId
            val s2 = s2Core.selfId
            val s3 = s3Core.selfId

            // The directory replicates over its OWN channel (single-collection).
            val dirLoom = InMemoryLoom()
            val s1Dir = dirLoom.host(Pattern("dir"))
            val s2Dir = dirLoom.join(InMemoryTag("dir"))
            val s3Dir = dirLoom.join(InMemoryTag("dir"))

            // Bob's link into his entry server S2, and (after failover) into S3.
            val bLoom2 = InMemoryLoom()
            val s2ToBob = bLoom2.host(Pattern("s2-bob"))
            val bobAtS2 = bLoom2.join(InMemoryTag("s2-bob"))

            val bLoom3 = InMemoryLoom()
            val s3ToBob = bLoom3.host(Pattern("s3-bob"))
            val bobAtS3 = bLoom3.join(InMemoryTag("s3-bob"))

            val clock = increasingClock()
            val cfg = QuilterConfig(expectVirtualTime = true)

            // Record S1's core sends so we can HARD-assert the origin never fans a unicast.
            val s1Recording = RecordingSeam(s1Core)
            val overlay1 = overlayServer(self = s1, coreSeam = s1Recording, directorySeam = s1Dir, scope = backgroundScope, clock = clock, directoryConfig = cfg)
            val overlay2 = overlayServer(self = s2, coreSeam = s2Core, directorySeam = s2Dir, scope = backgroundScope, clock = clock, directoryConfig = cfg)
            val overlay3 = overlayServer(self = s3, coreSeam = s3Core, directorySeam = s3Dir, scope = backgroundScope, clock = clock, directoryConfig = cfg)

            // 1. Bob is admitted to his entry server S2; the attachment converges to S1.
            overlay2.admit(bob, s2ToBob)
            testScheduler.advanceUntilIdle()
            assertEquals(s2, overlay1.lookup(bob), "precondition: Bob attached to S2, converged to S1")

            val bobAtS2Received = collectInto(bobAtS2)
            val bobAtS3Received = collectInto(bobAtS3)

            // 2. Bob's entry server S2 dies. The directory still names S2 (STALE) — nobody
            //    detached Bob; retry-any-server only re-homes the directory as a consequence
            //    of a *re-admission*, which hasn't happened yet.
            s2Core.close(CloseReason.Normal)
            s2ToBob.close(CloseReason.Normal)
            testScheduler.advanceUntilIdle()
            assertEquals(s2, overlay1.lookup(bob), "directory is stale during the failover window — still names the dead S2")

            // 3. S1 sends Bob a unicast DURING the stale window. 5C misroutes it to exactly
            //    the one (now-dead) server, which drops it — it is never fanned, never lands.
            overlay1.route(bob, "for-bob".encodeToByteArray())
            testScheduler.advanceUntilIdle()
            assertTrue(bobAtS2Received.isEmpty(), "the stale-window frame is not delivered (entry server is dead)")
            assertTrue(bobAtS3Received.isEmpty(), "and it is never fanned to the failover server either")
            assertEquals(listOf(s2), s1Recording.sentTo, "stale route addressed exactly one (dead) server — never fanned")
            assertEquals(0, s1Recording.broadcastCount, "a routed unicast is never a broadcast to the core")

            // 4. Bob fails over: he reconnects to survivor S3 and is re-admitted. admit
            //    re-homes the directory (Bob -> S3 supersedes Bob -> S2 under LWW) and
            //    registers his new local link.
            overlay3.admit(bob, s3ToBob)
            testScheduler.advanceUntilIdle()
            assertEquals(s3, overlay1.lookup(bob), "directory converged to Bob's new server S3")

            // 5. The sender RESENDS on convergence — and now the frame LANDS on Bob at S3.
            overlay1.route(bob, "for-bob".encodeToByteArray())
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("for-bob"), bobAtS3Received.map { it.decodeToString() }, "the resend lands on Bob at his new server")
            assertEquals(s3ToBob.selfId, bobAtS3Received.single().sender, "delivered via S3 — the resend crossed the core to Bob's new home")
            assertTrue(bobAtS2Received.isEmpty(), "the old (dead) link never received anything, ever")
            // Still no fan on the resend: S1 addressed exactly S3 this time, never broadcast.
            assertEquals(listOf(s2, s3), s1Recording.sentTo, "resend addressed exactly the new server — one addressee per send")
            assertEquals(0, s1Recording.broadcastCount, "no broadcast on the resend either")
        }

    /** A shared, strictly-increasing controlled clock standing in for well-synced wall clocks. */
    private fun increasingClock(): () -> Long {
        var t = 0L
        return { ++t }
    }

    /** Collect a seam's incoming into a growing list (single collector, on the test's background scope). */
    private fun TestScope.collectInto(seam: Seam): List<Swatch> {
        val received = mutableListOf<Swatch>()
        backgroundScope.launch { seam.incoming.collect { received += it } }
        return received
    }
}
