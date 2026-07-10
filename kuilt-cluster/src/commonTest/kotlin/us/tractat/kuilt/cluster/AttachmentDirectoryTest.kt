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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The attachment directory replicates over a plain [us.tractat.kuilt.core.Seam]
 * (slice-4's inter-server mesh in production; a two-peer in-memory seam here),
 * driven by a real [us.tractat.kuilt.quilter.Quilter] under
 * `UnconfinedTestDispatcher` — the established replicator-test harness (see
 * `:kuilt-quilter`'s `QuilterTest`). No Raft cluster is involved, so no
 * `MultiNodeRaftSim` is needed; the timeout keeps a non-converging run fast to fail.
 */
class AttachmentDirectoryTest {

    private val s1 = PeerId("S1")
    private val s2 = PeerId("S2")
    private val bob = PeerId("Bob")
    private val carol = PeerId("Carol")

    /** Config that suppresses the Quilter TestDispatcher guard under virtual time. */
    private val cfg = QuilterConfig(expectVirtualTime = true)

    @Test
    fun attachmentReplicatesToOtherServersAndFeedsTwoTier() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        val loom = InMemoryLoom()
        val s1Seam = loom.host(Pattern("core"))
        val s2Seam = loom.join(InMemoryTag("s2"))

        // A shared, strictly-increasing controlled clock stands in for well-synced wall clocks.
        var t = 0L
        val clock: () -> Long = { ++t }

        val dir1 = attachmentDirectory(self = s1, interServerSeam = s1Seam, scope = backgroundScope, clock = clock, config = cfg)
        val dir2 = attachmentDirectory(self = s2, interServerSeam = s2Seam, scope = backgroundScope, clock = clock, config = cfg)

        // Unknown clients read null before anyone writes them.
        assertNull(dir1.lookup(bob))
        assertNull(dir2.lookup(bob))

        // S2 records that Bob's packets now flow through it.
        dir2.attach(bob)
        testScheduler.advanceUntilIdle()

        // It replicates over the inter-server seam: S1 now routes Bob to S2 too.
        assertEquals(s2, dir1.lookup(bob), "attachment must replicate S2 -> S1")
        assertEquals(s2, dir2.lookup(bob))

        // A client nobody has attached is still unknown everywhere.
        assertNull(dir1.lookup(carol))
        assertNull(dir2.lookup(carol))

        // Wired into TwoTier: the directory is the policy's live `attachment` lookup.
        val core = setOf(s1, s2)
        val roster = setOf(s1, s2, bob)
        // Client Bob floods only through the server he is attached to (S2) — read live from dir1.
        assertEquals(setOf(s2), dir1.twoTier(core).activeView(bob, roster))
        // S2 sees Bob as one of its local clients in its own active view.
        assertTrue(bob in dir2.twoTier(core).activeView(s2, roster))
    }

    @Test
    fun detachTombstoneRemovesTheEntryEverywhere() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        val loom = InMemoryLoom()
        val s1Seam = loom.host(Pattern("core"))
        val s2Seam = loom.join(InMemoryTag("s2"))

        var t = 0L
        val clock: () -> Long = { ++t }

        val dir1 = attachmentDirectory(self = s1, interServerSeam = s1Seam, scope = backgroundScope, clock = clock, config = cfg)
        val dir2 = attachmentDirectory(self = s2, interServerSeam = s2Seam, scope = backgroundScope, clock = clock, config = cfg)

        dir2.attach(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(s2, dir1.lookup(bob))

        // Bob disconnects from S2 — the detach tombstone must win (later tag) and clear him everywhere.
        dir2.detach(bob)
        testScheduler.advanceUntilIdle()

        assertNull(dir2.lookup(bob), "detach must clear locally")
        assertNull(dir1.lookup(bob), "detach tombstone must replicate S2 -> S1")
    }
}
