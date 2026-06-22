package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multi-node tests over the [GossipSimulation] virtual-time harness (Phase 3, #658):
 * a broadcast reaches the whole overlay, dedup bounds the message count to ~O(N·k)
 * rather than the full-mesh O(N²), and the overlay re-forms and still disseminates
 * after a peer leaves.
 *
 * Determinism: per-peer seeded RNG, bounded `awaitTrue`/`settle`, fail-fast dumps —
 * never `advanceUntilIdle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipSimulationTest {
    @Test
    fun broadcastReachesEveryPeer() =
        runTest {
            val sim = GossipSimulation(n = 20, scope = this, nodeScope = backgroundScope, scheduler = testScheduler, seedBase = 1)
            sim.settle()

            val origin = sim.nodeIds.first()
            val payload = byteArrayOf(1, 1, 1)
            sim.broadcastFrom(origin, payload)
            sim.awaitTrue("broadcast reaches every peer") { sim.allReceived(payload, origin) }

            assertAll(
                {
                    assertTrue(
                        sim.received(origin).none { it.contentEquals(payload) },
                        "the origin does not deliver its own broadcast back to itself",
                    )
                },
                {
                    assertTrue(
                        (sim.nodeIds - origin).all { sim.received(it).count { p -> p.contentEquals(payload) } == 1 },
                        "every other peer delivers the payload exactly once",
                    )
                },
            )
        }

    @Test
    fun dedupBoundsMessageCountBelowFullMesh() =
        runTest {
            val n = 20
            val sim = GossipSimulation(n = n, scope = this, nodeScope = backgroundScope, scheduler = testScheduler, seedBase = 1)
            sim.settle()

            val origin = sim.nodeIds.first()
            val payload = byteArrayOf(2, 2, 2)
            sim.broadcastFrom(origin, payload)
            sim.awaitTrue("broadcast reaches every peer") { sim.allReceived(payload, origin) }

            val k = sim.activeViewSize
            val fullMesh = n * (n - 1)
            assertAll(
                {
                    assertTrue(
                        sim.relaySendCount <= n * k,
                        "relay sends (${sim.relaySendCount}) stay within the O(N·k) bound (${n * k})",
                    )
                },
                {
                    assertTrue(
                        sim.relaySendCount < fullMesh,
                        "relay sends (${sim.relaySendCount}) beat the full-mesh O(N²) cost ($fullMesh)",
                    )
                },
            )
        }

    @Test
    fun overlayReformsAndDisseminatesAfterChurn() =
        runTest {
            val sim = GossipSimulation(n = 20, scope = this, nodeScope = backgroundScope, scheduler = testScheduler, seedBase = 1)
            sim.settle()

            val origin = sim.nodeIds.first()
            sim.broadcastFrom(origin, byteArrayOf(3))
            sim.awaitTrue("initial broadcast reaches every peer") { sim.allReceived(byteArrayOf(3), origin) }

            // A peer leaves; the overlay re-forms (active slots heal from spares/fresh draws).
            val gone = sim.nodeIds[1]
            sim.disconnect(gone)
            sim.settle()
            sim.clearSinks()

            val payload = byteArrayOf(4, 4)
            sim.broadcastFrom(origin, payload)
            sim.awaitTrue("post-churn broadcast reaches every remaining peer") {
                sim.allReceived(payload, origin)
            }

            assertEquals(
                emptyList(),
                sim.received(gone).filter { it.contentEquals(payload) },
                "the departed peer receives nothing after leaving",
            )
        }
}
