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
import us.tractat.kuilt.crdt.TwoPhaseSet
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Example: a tombstoned label set replicated across two peers using [TwoPhaseSet]
 * + [SeamReplicator].
 *
 * Two moderators manage content-classification labels on a post. A label can be
 * added, but once removed it is permanently tombstoned — a later add has no effect.
 * This is the right model when "once withdrawn, always withdrawn" is a product
 * invariant (e.g. a label that triggers a legal hold, or a "verified" badge revoked
 * for policy violation).
 *
 * ## Why TwoPhaseSet fits
 *
 * - Tombstoning is intentional: the domain must prevent re-adding after withdrawal.
 * - Two grow-only sets (added + removed) are simpler than the causal-dot machinery
 *   of [us.tractat.kuilt.crdt.ORSet]. Pays that simplicity with the permanent tombstone.
 * - Contrast with [us.tractat.kuilt.crdt.ORSet]: there, a concurrent re-add survives a
 *   remove. Here, the tombstone always wins — even a concurrent add is masked.
 *
 * ## Serializer note
 *
 * [TwoPhaseSet] is a parameterised type. Its serializer requires an element serializer:
 * `TwoPhaseSet.serializer(String.serializer())`. The convenience [SeamReplicator] factory
 * accepts this directly as `valueSerializer`.
 *
 * [TwoPhaseSet.add] and [TwoPhaseSet.remove] return [us.tractat.kuilt.crdt.Patch] directly,
 * so mutations follow the same pattern as [us.tractat.kuilt.crdt.GSet]:
 * `replicator.mutate { it.add(element) }`.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [SeamReplicator] convenience factory with a parameterised element serializer
 * - [SeamReplicator.mutate] with [TwoPhaseSet.add] / [TwoPhaseSet.remove] (both return [us.tractat.kuilt.crdt.Patch])
 * - [SeamReplicator.state] (`StateFlow<TwoPhaseSet<String>>`) to read converged elements
 */
class TombstonedTagSetTest {

    private val replicatorCfg = SeamReplicatorConfig(expectVirtualTime = true)

    @Test
    fun `labels added by two peers converge to the union and a removed label stays gone`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("2p-tags"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTags = SeamReplicator(
                seam = seamAlice,
                initial = TwoPhaseSet.empty<String>(),
                valueSerializer = TwoPhaseSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )
            val bobTags = SeamReplicator(
                seam = seamBob,
                initial = TwoPhaseSet.empty<String>(),
                valueSerializer = TwoPhaseSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            aliceTags.mutate { it.add("verified") }
            aliceTags.mutate { it.add("featured") }
            bobTags.mutate { it.add("premium") }

            delay(10) // advance virtual time so all delta broadcasts deliver

            assertEquals(
                setOf("verified", "featured", "premium"),
                aliceTags.state.value.elements,
            )
            assertEquals(aliceTags.state.value.elements, bobTags.state.value.elements)

            // Bob revokes the "featured" label — tombstone propagates to Alice.
            bobTags.mutate { it.remove("featured") }

            delay(10) // allow the tombstone delta to propagate

            assertFalse(aliceTags.state.value.contains("featured"))
            assertFalse(bobTags.state.value.contains("featured"))
        }

    @Test
    fun `a tombstoned label cannot be re-added (remove-wins permanently)`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("2p-tags-tombstone"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTags = SeamReplicator(
                seam = seamAlice,
                initial = TwoPhaseSet.empty<String>(),
                valueSerializer = TwoPhaseSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )
            val bobTags = SeamReplicator(
                seam = seamBob,
                initial = TwoPhaseSet.empty<String>(),
                valueSerializer = TwoPhaseSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Add then remove "verified" — establishing the tombstone.
            aliceTags.mutate { it.add("verified") }
            aliceTags.mutate { it.remove("verified") }

            delay(10) // propagate tombstone to Bob

            assertFalse(aliceTags.state.value.contains("verified"))
            assertFalse(bobTags.state.value.contains("verified"))

            // Bob tries to re-add "verified" — the tombstone wins and blocks it.
            bobTags.mutate { it.add("verified") }

            delay(10) // propagate the attempted re-add

            // Still absent on both replicas — tombstone is permanent.
            assertFalse(aliceTags.state.value.contains("verified"))
            assertFalse(bobTags.state.value.contains("verified"))

            // A fresh label with no tombstone still works normally.
            bobTags.mutate { it.add("premium") }
            delay(10)
            assertTrue(aliceTags.state.value.contains("premium"))
            assertTrue(bobTags.state.value.contains("premium"))
        }
}
