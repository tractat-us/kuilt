package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [policyOverlay], the generalization of [starOverlay] over any [TopologyPolicy].
 *
 * The overlay pins zero recompute jitter, so a node's broadcast floods to exactly its policy's
 * active view with no jittered settle window — a [runCurrent] after the roster is known suffices.
 * Flood targets are read off the [FakeSeam]'s directed gossip sends, so the assertions are on
 * observed dissemination behaviour, not internal view state.
 *
 * Virtual time + seeded RNG throughout; time driven with [runCurrent], never `advanceUntilIdle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PolicyOverlayTest {

    private fun TestScope.clock(): () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

    /** Recipients of gossip broadcast frames the overlay flooded on [this] base. */
    private fun FakeSeam.floodTargets(): Set<PeerId> =
        directed.mapNotNull { (peer, bytes) -> GossipFrame.tryDecode(Swatch(bytes))?.let { peer } }.toSet()

    /**
     * `policyOverlay(base, FullFanout, …)` is behaviour-identical to `starOverlay(base, …)`: a
     * node's broadcast floods to every other peer. Two overlays built over matched fakes with the
     * same seed flood the same target set — the full roster minus self.
     */
    @Test
    fun policyOverlayWithFullFanoutMatchesStarOverlay() = runTest {
        val self = PeerId("self")
        val roster = (1..5).map { PeerId("peer-$it") }.toSet() + self

        val starBase = FakeSeam(selfId = self, initialPeers = roster)
        val star = backgroundScope.starOverlay(starBase, Random(7), clock())
        runCurrent()

        val policyBase = FakeSeam(selfId = self, initialPeers = roster)
        val policy = backgroundScope.policyOverlay(policyBase, FullFanout, Random(7), clock())
        runCurrent()

        star.broadcast(byteArrayOf(1))
        policy.broadcast(byteArrayOf(1))
        runCurrent()

        assertAll(
            { assertEquals(roster - self, starBase.floodTargets(), "star floods every other peer") },
            {
                assertEquals(
                    starBase.floodTargets(),
                    policyBase.floodTargets(),
                    "FullFanout policyOverlay floods identically to starOverlay",
                )
            },
        )
    }

    /**
     * A [TwoTier] policy is accepted and drives the flood shape: a **server** floods the other
     * servers plus its own local clients — never another server's clients. `self` is a server, so
     * its broadcast reaches `{server2, clientA}` and not `clientB`.
     */
    @Test
    fun policyOverlayWithTwoTierServerFloodsCorePlusLocalClients() = runTest {
        val self = PeerId("server-1")
        val server2 = PeerId("server-2")
        val clientA = PeerId("client-a") // attaches to self
        val clientB = PeerId("client-b") // attaches to server2
        val core = setOf(self, server2)
        val attachment = mapOf(clientA to self, clientB to server2)
        val roster = core + clientA + clientB

        val base = FakeSeam(selfId = self, initialPeers = roster)
        val overlay = backgroundScope.policyOverlay(base, TwoTier(core) { attachment[it] }, Random(0), clock())
        runCurrent()

        overlay.broadcast(byteArrayOf(1))
        runCurrent()

        val targets = base.floodTargets()
        assertAll(
            { assertTrue(server2 in targets, "the server floods the core") },
            { assertTrue(clientA in targets, "the server floods its own local client") },
            { assertTrue(clientB !in targets, "a server never floods another server's clients") },
            { assertEquals(setOf(server2, clientA), targets, "server view is the core plus the local periphery") },
        )
    }

    /**
     * The client side of [TwoTier]: a **client** floods **only the one server it attaches to** —
     * its sole relay into the rest of the graph. `self` is a client attaching to `server-1`, so
     * its broadcast reaches only `server-1`.
     */
    @Test
    fun policyOverlayWithTwoTierClientFloodsItsServerOnly() = runTest {
        val self = PeerId("client-a") // attaches to server-1
        val server1 = PeerId("server-1")
        val server2 = PeerId("server-2")
        val clientB = PeerId("client-b")
        val core = setOf(server1, server2)
        val attachment = mapOf(self to server1, clientB to server2)
        val roster = core + self + clientB

        val base = FakeSeam(selfId = self, initialPeers = roster)
        val overlay = backgroundScope.policyOverlay(base, TwoTier(core) { attachment[it] }, Random(0), clock())
        runCurrent()

        overlay.broadcast(byteArrayOf(1))
        runCurrent()

        assertEquals(setOf(server1), base.floodTargets(), "a client floods only its own server relay")
    }
}
