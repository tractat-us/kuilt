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
import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.quilter.SeamReplicator
import us.tractat.kuilt.quilter.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Example: a live up/down vote tally replicated across two peers using [PNCounter]
 * + [SeamReplicator].
 *
 * Each peer owns its own [us.tractat.kuilt.crdt.ReplicaId] slot in the counter — increments
 * record upvotes, decrements record downvotes. Peers apply mutations locally and [SeamReplicator]
 * broadcasts deltas over the [us.tractat.kuilt.core.Seam] automatically. Both replicas
 * converge to the same net tally regardless of which peer applied which vote.
 *
 * ## Why PNCounter fits
 *
 * - Votes from different users arrive concurrently and must all be counted — no ordering
 *   is needed, just an accurate sum.
 * - Downvotes (decrements) are first-class: the counter can go negative if downvotes
 *   exceed upvotes, which is the desired behaviour for unrestricted vote tallies.
 * - Each replica's slot is independent: two users voting on different replicas never
 *   interfere with each other's bookkeeping.
 *
 * ## API-surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [SeamReplicator] convenience factory (value serializer, default replica)
 * - [SeamReplicator.mutate] to broadcast a mutation without repeating `state.value`
 * - [SeamReplicator.state] (`StateFlow<PNCounter>`) to read the current converged tally
 */
class VoteTallyTest {

    private val replicatorCfg = SeamReplicatorConfig(expectVirtualTime = true)

    @Test
    fun `upvotes and downvotes from two peers converge to the correct net tally`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("vote-tally"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTally = SeamReplicator(seamAlice, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = replicatorCfg)
            val bobTally = SeamReplicator(seamBob, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice casts 3 upvotes for the post.
            aliceTally.mutate { it.increment(aliceTally.replica, 3L) }

            // Bob casts 1 upvote and then 1 downvote (changed his mind).
            bobTally.mutate { it.increment(bobTally.replica, 1L) }
            bobTally.mutate { it.decrement(bobTally.replica, 1L) }

            // Alice adds another upvote concurrently.
            aliceTally.mutate { it.increment(aliceTally.replica, 2L) }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Both replicas must converge to the same net tally.
            // alice: +3 +2 = 5 increments; bob: +1 -1 = 0 net → total = 5
            assertEquals(5L, aliceTally.state.value.value)
            assertEquals(aliceTally.state.value.value, bobTally.state.value.value)
        }

    @Test
    fun `a downvote-heavy result can go negative and still converges`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("controversial-post"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTally = SeamReplicator(seamAlice, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = replicatorCfg)
            val bobTally = SeamReplicator(seamBob, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = replicatorCfg)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Bob downvotes heavily; Alice upvotes once.
            bobTally.mutate { it.decrement(bobTally.replica, 5L) }
            aliceTally.mutate { it.increment(aliceTally.replica, 1L) }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Net = +1 - 5 = -4; both peers must agree.
            assertEquals(-4L, aliceTally.state.value.value)
            assertEquals(aliceTally.state.value.value, bobTally.state.value.value)
        }
}
