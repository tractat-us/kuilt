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
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.core.PeerId
import kotlinx.serialization.serializer
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
 * Convenience [Quilter] factory + [Quilter.mutate]:
 * pass the value serializer directly; the message serializer is derived internally.
 * Replica id defaults to `ReplicaId(seam.selfId.value)`.
 */
@Suppress("unused")
internal fun sampleQuilterConvenience() = runTest(
    StandardTestDispatcher(),
    timeout = 5.seconds,
) {
    val loom = InMemoryLoom()
    val seamAlice = loom.host(Pattern("vote-tally"))
    val seamBob = loom.join(InMemoryTag("bob"))

    // No manual QuiltMessage.serializer(...) wrapping needed.
    val cfg = QuilterConfig(expectVirtualTime = true)
    val aliceTally = Quilter(seamAlice, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = cfg)
    val bobTally = Quilter(seamBob, PNCounter.ZERO, PNCounter.serializer(), backgroundScope, config = cfg)

    kotlinx.coroutines.delay(1)

    // mutate removes the state.value repetition at every call site.
    aliceTally.mutate { it.increment(aliceTally.replica, 3L) }
    bobTally.mutate { it.decrement(bobTally.replica, 1L) }

    kotlinx.coroutines.delay(10)

    assertEquals(2L, aliceTally.state.value.value)
    assertEquals(aliceTally.state.value.value, bobTally.state.value.value)
}

// ── Quilter basic setup ───────────────────────────────────────────────────────

/**
 * Basic [Quilter] setup over a [us.tractat.kuilt.core.Seam].
 *
 * Host a session, build a replicator, apply a mutation, and read the live
 * converged state via `state`. The mutation is broadcast to all current peers
 * automatically; every peer's `state` flow reflects the merge.
 */
@Suppress("unused")
internal fun sampleQuilterSetup() = runTest(
    StandardTestDispatcher(),
    timeout = 5.seconds,
) {
    val loom = InMemoryLoom()
    val seam = loom.host(Pattern("my-session"))

    val cfg = QuilterConfig(expectVirtualTime = true)
    val replicator = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GCounter.ZERO,
        messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
        scope = backgroundScope,
        config = cfg,
    )

    // Apply a mutation — the delta is broadcast to all current peers automatically.
    replicator.apply(replicator.state.value.inc(replicator.replica, 1L))

    // state is a StateFlow — always the current converged value.
    assertEquals(1L, replicator.state.value.value)
}

// ── Quilter session-metadata ──────────────────────────────────────────────────

/**
 * [Quilter] + [LWWMap] for live-converging session metadata (display names).
 *
 * `LWWMap<PeerId, String>` gives each key per-entry last-writer-wins semantics.
 * Backed by a `room.channel("member-metadata")` in production; shown here over
 * a plain seam for brevity.
 */
@Suppress("unused")
internal fun sampleQuilterSessionMetadata() = runTest(
    StandardTestDispatcher(),
    timeout = 5.seconds,
) {
    val loom = InMemoryLoom()
    val seamAlice = loom.host(Pattern("session"))
    val seamBob = loom.join(InMemoryTag("bob"))

    val cfg = QuilterConfig(expectVirtualTime = true)
    val msgSer = QuiltMessage.serializer(LWWMap.serializer(PeerId.serializer(), serializer<String>()))

    val aliceRep = Quilter(
        replica = ReplicaId(seamAlice.selfId.value),
        seam = seamAlice,
        initial = LWWMap.empty<PeerId, String>(),
        messageSerializer = msgSer,
        scope = backgroundScope,
        config = cfg,
    )
    val bobRep = Quilter(
        replica = ReplicaId(seamBob.selfId.value),
        seam = seamBob,
        initial = LWWMap.empty<PeerId, String>(),
        messageSerializer = msgSer,
        scope = backgroundScope,
        config = cfg,
    )

    kotlinx.coroutines.delay(1)

    // Each peer writes its own display name. LWWMap gives per-key last-writer-wins.
    // LWWMap.set returns a new LWWMap (the merged state), so wrap it in Patch.
    aliceRep.apply(Patch(aliceRep.state.value.set(aliceRep.replica, timestamp = 1L, key = seamAlice.selfId, value = "Alice")))
    bobRep.apply(Patch(bobRep.state.value.set(bobRep.replica, timestamp = 1L, key = seamBob.selfId, value = "Bob")))

    kotlinx.coroutines.delay(10)

    // rep.state is the live-converging display-name map — both peers converge.
    assertEquals("Alice", aliceRep.state.value[seamAlice.selfId])
    assertEquals("Alice", bobRep.state.value[seamAlice.selfId])
}

// ── Rga chat replicator ───────────────────────────────────────────────────────

/**
 * Rga-backed live chat over a [us.tractat.kuilt.core.Seam] via [Quilter].
 *
 * [us.tractat.kuilt.crdt.Rga] gives a total order for concurrent inserts, so
 * every peer sees the same message list. Append a message by inserting after
 * the last element; collect `state` to render the live chat log.
 */
@Suppress("unused")
internal fun sampleRgaChatReplicator() = runTest(
    StandardTestDispatcher(),
    timeout = 5.seconds,
) {
    val loom = InMemoryLoom()
    val seamAlice = loom.host(Pattern("chat"))
    val seamBob = loom.join(InMemoryTag("bob"))

    val cfg = QuilterConfig(expectVirtualTime = true)
    val msgSer = QuiltMessage.serializer(Rga.wireSerializer(serializer<String>()))

    val alice = ReplicaId(seamAlice.selfId.value)
    val bob = ReplicaId(seamBob.selfId.value)

    val aliceChat = Quilter(
        replica = alice,
        seam = seamAlice,
        initial = Rga.empty<String>(),
        messageSerializer = msgSer,
        scope = backgroundScope,
        config = cfg,
    )
    val bobChat = Quilter(
        replica = bob,
        seam = seamBob,
        initial = Rga.empty<String>(),
        messageSerializer = msgSer,
        scope = backgroundScope,
        config = cfg,
    )

    kotlinx.coroutines.delay(1)

    // Send a message — appended to the shared list, propagated to all peers.
    fun sendMessage(rep: Quilter<Rga<String>>, replicaId: ReplicaId, text: String) {
        val current = rep.state.value
        val (_, op) = current.insertAfter(replicaId, RgaId.HEAD, text)
        rep.apply(Patch(Rga.empty<String>().apply(op)))
    }

    sendMessage(aliceChat, alice, "hello from alice")
    sendMessage(bobChat, bob, "hello from bob")

    kotlinx.coroutines.delay(10)

    // Render the live chat log — both peers converge to the same sequence.
    assertEquals(aliceChat.state.value.toList(), bobChat.state.value.toList())
    assertEquals(2, aliceChat.state.value.toList().size)
}

