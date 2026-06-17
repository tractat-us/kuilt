@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.PNCounter
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Samples for the quilter (replicator) API used by `@sample` KDoc tags.
 *
 * Every function here is compiled as part of commonTest so a typo or API
 * change will break the build, not silently produce stale documentation.
 */

// ── Quilter convenience API ───────────────────────────────────────────────────

/**
 * Convenience [SeamReplicator] factory + [SeamReplicator.mutate]:
 * pass the value serializer directly; the message serializer is derived internally.
 * Replica id defaults to `ReplicaId(seam.selfId.value)`.
 */
@Suppress("unused")
internal fun sampleSeamReplicatorConvenience() = runTest(
    StandardTestDispatcher(),
    timeout = 5.seconds,
) {
    val loom = InMemoryLoom()
    val seamAlice = loom.host(Pattern("vote-tally"))
    val seamBob = loom.join(InMemoryTag("bob"))

    // No manual QuiltMessage.serializer(...) wrapping needed.
    val cfg = SeamReplicatorConfig(expectVirtualTime = true)
    val aliceTally = SeamReplicator(seamAlice, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = cfg)
    val bobTally = SeamReplicator(seamBob, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = cfg)

    kotlinx.coroutines.delay(1)

    // mutate removes the state.value repetition at every call site.
    aliceTally.mutate { it.increment(aliceTally.replica, 3L) }
    bobTally.mutate { it.decrement(bobTally.replica, 1L) }

    kotlinx.coroutines.delay(10)

    assertEquals(2L, aliceTally.state.value.value)
    assertEquals(aliceTally.state.value.value, bobTally.state.value.value)
}
