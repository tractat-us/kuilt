package us.tractat.kuilt.gossip

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GossipSeamTerminalViewTest {
    private val self = PeerId("self")
    private val remote = PeerId("remote")
    private val spare = PeerId("spare")

    // Select one active peer, leaving the other available as a spare. Sorting
    // makes the rig independent of each target's set iteration order.
    private fun topology(onSelection: () -> Unit = {}): TopologyPolicy =
        object : TopologyPolicy by FullFanout {
            override fun activeView(self: PeerId, roster: Set<PeerId>): Set<PeerId> {
                onSelection()
                return (roster - self).sortedBy { it.value }.take(1).toSet()
            }
        }

    private fun gossip(base: FakeSeam, topology: TopologyPolicy = topology()): GossipSeam =
        GossipSeam(
            base = base,
            random = Random(0),
            clock = { Instant.fromEpochMilliseconds(0) },
            jitter = Duration.ZERO..Duration.ZERO,
            topology = topology,
        )

    @Test
    fun closeClearsActiveAndSpareViews() = runTest {
        val seam = gossip(FakeSeam(self, setOf(self, remote, spare)))
        seam.start(backgroundScope)
        runCurrent()
        assertAll(
            { assertEquals(setOf(remote), seam.activePeers.value, "active-view rig must be populated") },
            { assertEquals(listOf(spare), seam.spares.value, "spare-view rig must be populated") },
        )

        seam.close()
        runCurrent()

        assertAll(
            { assertEquals(setOf(self), seam.peers.value) },
            { assertEquals(emptySet(), seam.activePeers.value, "close left active neighbours") },
            { assertEquals(emptyList(), seam.spares.value, "close left spare neighbours") },
        )
    }

    @Test
    fun baseTearClearsActiveAndSpareViews() = runTest {
        val base = FakeSeam(self, setOf(self, remote, spare))
        val seam = gossip(base)
        seam.start(backgroundScope)
        runCurrent()
        assertAll(
            { assertEquals(setOf(remote), seam.activePeers.value) },
            { assertEquals(listOf(spare), seam.spares.value) },
        )

        base.tear()
        runCurrent()

        assertAll(
            { assertEquals(emptySet(), seam.activePeers.value, "base tear left active neighbours") },
            { assertEquals(emptyList(), seam.spares.value, "base tear left spare neighbours") },
        )
    }

    @Test
    fun inFlightReconciliationCannotRestoreViewsAfterClose() = runTest {
        val base = FakeSeam(self, setOf(self, remote, spare))
        var closeDuringSelection: () -> Unit = {}
        val seam = gossip(base, topology { closeDuringSelection() })
        seam.start(backgroundScope)
        runCurrent()
        assertAll(
            { assertEquals(setOf(remote), seam.activePeers.value) },
            { assertEquals(listOf(spare), seam.spares.value) },
        )

        var closeReturned = false
        closeDuringSelection = {
            closeDuringSelection = {}
            // Re-enter close while the command coroutine is computing its next
            // view. It can still publish that view after close cancels its job.
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                seam.close()
                closeReturned = true
            }
        }
        base.addPeer(PeerId("z-new"))
        runCurrent()

        assertAll(
            { assertTrue(closeReturned, "close must return during the in-flight selection") },
            { assertEquals(emptySet(), seam.activePeers.value, "reconciliation restored active neighbours") },
            { assertEquals(emptyList(), seam.spares.value, "reconciliation restored spare neighbours") },
        )
    }
}
