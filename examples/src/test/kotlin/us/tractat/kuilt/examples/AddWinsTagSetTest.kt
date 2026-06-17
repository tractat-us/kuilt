@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Example: an add-wins label set replicated across two peers using [ORSet]
 * + [SeamReplicator].
 *
 * Two collaborators manage presence tags on a shared document. Tags can be added
 * and removed, and a concurrent add always wins over a concurrent remove — if one
 * peer removes a tag at the same moment another re-adds it, the add survives the
 * merge. Tags that were removed without a concurrent add disappear cleanly.
 *
 * ## Why ORSet fits
 *
 * - Add-wins semantics match "optimistic collaboration": a re-add should survive
 *   unless the remove definitively happened after it. The causal dot machinery
 *   encodes exactly which prior adds each remove has witnessed.
 * - Unlike [us.tractat.kuilt.crdt.TwoPhaseSet], a removed element can be re-added
 *   freely — there are no permanent tombstones blocking re-entry.
 * - Each peer needs its own [us.tractat.kuilt.crdt.ReplicaId] only for adds;
 *   removes do not mint new identities.
 *
 * ## Serializer note
 *
 * [ORSet] is a parameterised type. Its serializer requires an element serializer:
 * `ORSet.serializer(String.serializer())`. The convenience [SeamReplicator] factory
 * accepts this directly as `valueSerializer`.
 *
 * [ORSet.add] and [ORSet.remove] return a new full [ORSet] rather than a
 * [Patch], so mutations are expressed as
 * `replicator.mutate { s -> Patch(s.add(replicator.replica, element)) }`.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [SeamReplicator] convenience factory with a parameterised element serializer
 * - [SeamReplicator.mutate] wrapping [ORSet.add] / [ORSet.remove] in a [Patch]
 * - [SeamReplicator.state] (`StateFlow<ORSet<String>>`) to read converged elements
 */
class AddWinsTagSetTest {

    private val replicatorCfg = SeamReplicatorConfig(expectVirtualTime = true)

    @Test
    fun `tags added by two peers converge to the union and removed tags disappear`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("or-tags"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTags = SeamReplicator(
                seam = seamAlice,
                initial = ORSet.empty<String>(),
                valueSerializer = ORSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )
            val bobTags = SeamReplicator(
                seam = seamBob,
                initial = ORSet.empty<String>(),
                valueSerializer = ORSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            aliceTags.mutate { s -> Patch(s.add(aliceTags.replica, "kotlin")) }
            aliceTags.mutate { s -> Patch(s.add(aliceTags.replica, "networking")) }
            bobTags.mutate { s -> Patch(s.add(bobTags.replica, "multiplatform")) }

            delay(10) // advance virtual time so all delta broadcasts deliver

            // All adds survive; both peers converge to the union.
            assertEquals(
                setOf("kotlin", "networking", "multiplatform"),
                aliceTags.state.value.elements,
            )
            assertEquals(aliceTags.state.value.elements, bobTags.state.value.elements)

            // Bob removes a tag he no longer needs; Alice has no conflicting add.
            bobTags.mutate { s -> Patch(s.remove("networking")) }

            delay(10) // allow the remove delta to propagate

            assertFalse(aliceTags.state.value.contains("networking"))
            assertFalse(bobTags.state.value.contains("networking"))
        }

    @Test
    fun `concurrent add beats a concurrent remove (add-wins)`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("or-tags-add-wins"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTags = SeamReplicator(
                seam = seamAlice,
                initial = ORSet.empty<String>(),
                valueSerializer = ORSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )
            val bobTags = SeamReplicator(
                seam = seamBob,
                initial = ORSet.empty<String>(),
                valueSerializer = ORSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Both peers add "featured" so both have it locally before any sync.
            aliceTags.mutate { s -> Patch(s.add(aliceTags.replica, "featured")) }
            bobTags.mutate { s -> Patch(s.add(bobTags.replica, "featured")) }

            delay(10) // let the adds propagate — both peers now agree it is present

            assertTrue(aliceTags.state.value.contains("featured"))
            assertTrue(bobTags.state.value.contains("featured"))

            // Alice removes "featured" while Bob concurrently re-adds it.
            // Because Bob's re-add mints a new dot that Alice's remove never witnessed,
            // the add wins and "featured" survives the merge.
            aliceTags.mutate { s -> Patch(s.remove("featured")) }
            bobTags.mutate { s -> Patch(s.add(bobTags.replica, "featured")) }

            delay(10) // let the concurrent operations propagate and merge

            // Add-wins: the tag must still be present on both replicas.
            assertTrue(aliceTags.state.value.contains("featured"))
            assertTrue(bobTags.state.value.contains("featured"))
        }
}
