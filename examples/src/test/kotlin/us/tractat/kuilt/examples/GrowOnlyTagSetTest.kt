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
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Example: a grow-only label set replicated across two peers using [GSet]
 * + [Quilter].
 *
 * Two collaborators tag a shared document. Each can only add tags — once a tag
 * exists it is permanent. Both peers' tag sets converge to the union of all
 * tags ever added by anyone.
 *
 * ## Why GSet fits
 *
 * - Tags are additive: the domain never needs to withdraw them (for that, see
 *   [TwoPhaseSet] or [us.tractat.kuilt.crdt.ORSet]).
 * - The join is set union, the simplest possible CRDT — no tombstones, no dots,
 *   no replica identity required to add an element.
 * - Concurrent adds on different peers always converge: union is commutative,
 *   associative, and idempotent.
 *
 * ## API surface exercised
 *
 * - [InMemoryLoom] + `host`/`join` for in-process transport
 * - [Quilter] convenience factory with a parameterised element serializer
 * - [Quilter.mutate] with [GSet.add] (returns a [us.tractat.kuilt.crdt.Patch])
 * - [Quilter.state] (`StateFlow<GSet<String>>`) to read converged elements
 */
class GrowOnlyTagSetTest {

    private val replicatorCfg = QuilterConfig(expectVirtualTime = true)

    @Test
    fun `tags added by two peers converge to the union`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("doc-tags"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTags = Quilter(
                seam = seamAlice,
                initial = GSet.empty<String>(),
                valueSerializer = GSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )
            val bobTags = Quilter(
                seam = seamBob,
                initial = GSet.empty<String>(),
                valueSerializer = GSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Alice tags the document.
            aliceTags.mutate { it.add("kotlin") }
            aliceTags.mutate { it.add("multiplatform") }

            // Bob tags concurrently.
            bobTags.mutate { it.add("networking") }

            delay(10) // advance virtual time so all delta broadcasts deliver

            val expected = setOf("kotlin", "multiplatform", "networking")
            assertEquals(expected, aliceTags.state.value.elements)
            assertEquals(expected, bobTags.state.value.elements)
        }

    @Test
    fun `adding the same tag from both sides is idempotent`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("doc-tags-idempotent"))
            val seamBob = loom.join(InMemoryTag("bob"))

            val aliceTags = Quilter(
                seam = seamAlice,
                initial = GSet.empty<String>(),
                valueSerializer = GSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )
            val bobTags = Quilter(
                seam = seamBob,
                initial = GSet.empty<String>(),
                valueSerializer = GSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = replicatorCfg,
            )

            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Both peers independently add the same tag — union keeps only one copy.
            aliceTags.mutate { it.add("kotlin") }
            bobTags.mutate { it.add("kotlin") }

            delay(10) // advance virtual time so all delta broadcasts deliver

            assertEquals(setOf("kotlin"), aliceTags.state.value.elements)
            assertEquals(setOf("kotlin"), bobTags.state.value.elements)
            assertTrue(aliceTags.state.value.contains("kotlin"))
        }
}
