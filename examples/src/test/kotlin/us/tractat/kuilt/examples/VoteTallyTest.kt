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
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.replicator.ReplicatorMessage
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Example: a live up/down vote tally replicated across two peers using [PNCounter]
 * + [SeamReplicator].
 *
 * Each peer owns its own [ReplicaId] slot in the counter — increments record upvotes,
 * decrements record downvotes. Peers apply mutations locally and [SeamReplicator]
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
 * - [SeamReplicator] for live delta replication over a [us.tractat.kuilt.core.Seam]
 * - [PNCounter.increment] / [PNCounter.decrement] returning [us.tractat.kuilt.crdt.Patch]
 * - [SeamReplicator.apply] to broadcast a mutation
 * - [SeamReplicator.state] (`StateFlow<PNCounter>`) to read the current converged tally
 */
class VoteTallyTest {

    private val replicatorCfg = SeamReplicatorConfig(expectVirtualTime = true)
    private val msgSer = ReplicatorMessage.serializer(PNCounter.serializer())

    private fun tallyReplicator(seam: us.tractat.kuilt.core.Seam, scope: kotlinx.coroutines.CoroutineScope) =
        SeamReplicator(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = PNCounter.ZERO,
            messageSerializer = msgSer,
            scope = scope,
            config = replicatorCfg,
        )

    @Test
    fun `upvotes and downvotes from two peers converge to the correct net tally`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("vote-tally"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTally = tallyReplicator(seamAlice, backgroundScope)
            val bobTally = tallyReplicator(seamBob, backgroundScope)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice casts 3 upvotes for the post.
            aliceTally.apply(aliceTally.state.value.increment(aliceTally.replica, 3L))

            // Bob casts 1 upvote and then 1 downvote (changed his mind).
            bobTally.apply(bobTally.state.value.increment(bobTally.replica, 1L))
            bobTally.apply(bobTally.state.value.decrement(bobTally.replica, 1L))

            // Alice adds another upvote concurrently.
            aliceTally.apply(aliceTally.state.value.increment(aliceTally.replica, 2L))

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

            val aliceTally = tallyReplicator(seamAlice, backgroundScope)
            val bobTally = tallyReplicator(seamBob, backgroundScope)

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Bob downvotes heavily; Alice upvotes once.
            bobTally.apply(bobTally.state.value.decrement(bobTally.replica, 5L))
            aliceTally.apply(aliceTally.state.value.increment(aliceTally.replica, 1L))

            delay(10) // advance virtual time so all delta broadcasts deliver

            // Net = +1 - 5 = -4; both peers must agree.
            assertEquals(-4L, aliceTally.state.value.value)
            assertEquals(aliceTally.state.value.value, bobTally.state.value.value)
        }
}
