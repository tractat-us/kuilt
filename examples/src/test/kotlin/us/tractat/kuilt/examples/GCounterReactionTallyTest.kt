@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Example: a grow-only reaction tally replicated across two peers using [GCounter]
 * + [SeamReplicator].
 *
 * Each peer owns its own [us.tractat.kuilt.crdt.ReplicaId] slot in the counter — increments
 * record reactions (e.g. 👍 clicks). Peers apply increments locally and [SeamReplicator]
 * broadcasts deltas over the [us.tractat.kuilt.core.Seam] automatically. Both replicas
 * converge to the same total regardless of which peer received which clicks.
 *
 * ## Why GCounter fits
 *
 * - Reaction counts can only go up — users click 👍, never un-click (or un-click is
 *   modelled as a separate counter, which is [us.tractat.kuilt.crdt.PNCounter]).
 * - Each replica's slot is independent: concurrent increments on different peers
 *   never interfere — merge is elementwise max, total is their sum.
 * - The grow-only invariant makes GCounter simpler and cheaper than PNCounter:
 *   no negative tracking, no overflow concern, just a map of non-decreasing counts.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [SeamReplicator] convenience factory (value serializer, default replica)
 * - [SeamReplicator.mutate] to increment a slot and broadcast the delta
 * - [SeamReplicator.state] (`StateFlow<GCounter>`) to read the converged total
 * - [GCounter.value] for the sum across all replicas
 */
class GCounterReactionTallyTest {

    private val replicatorCfg = SeamReplicatorConfig(expectVirtualTime = true)

    @Test
    fun `reaction clicks from two peers converge to the correct total`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("reactions"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTally = SeamReplicator(seamAlice, GCounter.ZERO, GCounter.serializer(), backgroundScope, config = replicatorCfg)
            val bobTally = SeamReplicator(seamBob, GCounter.ZERO, GCounter.serializer(), backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice records 3 reactions from her viewers.
            aliceTally.mutate { it.inc(aliceTally.replica, 3L) }

            // Bob concurrently records 5 reactions from his viewers.
            bobTally.mutate { it.inc(bobTally.replica, 5L) }

            // Alice receives another burst of 2 reactions.
            aliceTally.mutate { it.inc(aliceTally.replica, 2L) }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas must converge to alice(3+2) + bob(5) = 10 total reactions.
            assertEquals(10L, aliceTally.state.value.value)
            assertEquals(aliceTally.state.value.value, bobTally.state.value.value)
        }

    @Test
    fun `each replica's own slot is tracked independently`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("reactions-slots"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTally = SeamReplicator(seamAlice, GCounter.ZERO, GCounter.serializer(), backgroundScope, config = replicatorCfg)
            val bobTally = SeamReplicator(seamBob, GCounter.ZERO, GCounter.serializer(), backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            aliceTally.mutate { it.inc(aliceTally.replica, 4L) }
            bobTally.mutate { it.inc(bobTally.replica, 7L) }

            delay(10) // advance virtual time so all delta broadcasts deliver

            val converged = aliceTally.state.value
            assertEquals(4L, converged.count(aliceTally.replica), "Alice's slot is exactly her increments")
            assertEquals(7L, converged.count(bobTally.replica), "Bob's slot is exactly his increments")
            assertEquals(11L, converged.value, "Total is the sum of both slots")
            assertEquals(converged.value, bobTally.state.value.value)
        }
}
