@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Samples for the CRDT zoo used by `@sample` KDoc tags.
 *
 * Every function here is compiled as part of commonTest so a typo or API
 * change will break the build, not silently produce stale documentation.
 */

// ── GCounter ────────────────────────────────────────────────────────────────

/** Two replicas increment independently; the merge sums correctly. */
@Suppress("unused")
internal fun sampleGCounter() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    var replicaA = GCounter.ZERO
    var replicaB = GCounter.ZERO

    // Each replica increments its own slot.
    replicaA = replicaA.piece(replicaA.inc(a, 3))
    replicaB = replicaB.piece(replicaB.inc(b, 5))

    // After merging both deltas, every replica converges to the same value.
    val merged = replicaA.piece(replicaB)
    check(merged.value == 8L) // 3 + 5
}

/** piece is elementwise max — the same slot is not double-counted. */
@Suppress("unused")
internal fun sampleGCounterPiece() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    val left = GCounter.of(a to 2L, b to 1L)
    val right = GCounter.of(a to 1L, b to 3L)
    // merge takes max per slot: a→2, b→3
    check(left.piece(right) == GCounter.of(a to 2L, b to 3L))
}

// ── PNCounter ────────────────────────────────────────────────────────────────

/** Increment and decrement across replicas; the net converges. */
@Suppress("unused")
internal fun samplePNCounter() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    var counter = PNCounter.ZERO
    counter = counter.piece(counter.increment(a, 10))
    counter = counter.piece(counter.decrement(b, 3))

    check(counter.value == 7L)
}

// ── GSet ─────────────────────────────────────────────────────────────────────

/** Elements grow monotonically; no remove is possible. */
@Suppress("unused")
internal fun sampleGSet() {
    var set = GSet.empty<String>()
    set = set.piece(set.add("alice"))
    set = set.piece(set.add("bob"))
    check(set.elements == setOf("alice", "bob"))
}

// ── TwoPhaseSet ──────────────────────────────────────────────────────────────

/** Once removed, an element is permanently tombstoned. */
@Suppress("unused")
internal fun sampleTwoPhaseSet() {
    var s = TwoPhaseSet.empty<String>()
    s = s.piece(s.add("alice"))
    check(s.contains("alice"))

    s = s.piece(s.remove("alice"))
    check(!s.contains("alice"))

    // Even re-adding won't bring it back — the tombstone wins.
    s = s.piece(s.add("alice"))
    check(!s.contains("alice"))
}

// ── ORSet ─────────────────────────────────────────────────────────────────────

/** Concurrent add beats a concurrent remove (add-wins). */
@Suppress("unused")
internal fun sampleORSet() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    // Shared start: "alice" is present on both replicas.
    val start = ORSet.empty<String>().add(a, "alice")

    val alice = start.remove("alice")       // Alice concurrently removes
    val bob = start.add(b, "alice")         // Bob concurrently re-adds

    val merged = alice.piece(bob)
    check(merged.contains("alice"))         // add-wins
}

// ── LWWRegister ───────────────────────────────────────────────────────────────

/** Higher-timestamped write wins on merge. */
@Suppress("unused")
internal fun sampleLWWRegister() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    val left = LWWRegister.empty<String>().set(a, timestamp = 1L, value = "v1")
    val right = LWWRegister.empty<String>().set(b, timestamp = 2L, value = "v2")

    check(left.piece(right).value == "v2")  // ts=2 wins
    check(right.piece(left).value == "v2")  // commutative
}

// ── MVRegister ────────────────────────────────────────────────────────────────

/** Concurrent writes produce multiple values; a later write resolves them. */
@Suppress("unused")
internal fun sampleMVRegister() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    // Two replicas set independently — neither has seen the other.
    val fromA = MVRegister.empty<String>().set(a, "vA")
    val fromB = MVRegister.empty<String>().set(b, "vB")

    val merged = fromA.piece(fromB)
    check(merged.values == setOf("vA", "vB"))  // concurrent writes retained

    // A later write on one replica that observes the merged state resolves it.
    val resolved = merged.set(a, "resolved")
    check(resolved.values == setOf("resolved"))
}

// ── LWWMap ────────────────────────────────────────────────────────────────────

/** Per-key last-writer-wins semantics. */
@Suppress("unused")
internal fun sampleLWWMap() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    val left = LWWMap.empty<String, Int>()
        .set(a, timestamp = 1L, key = "score", value = 10)
    val right = LWWMap.empty<String, Int>()
        .set(b, timestamp = 2L, key = "score", value = 20)

    val merged = left.piece(right)
    check(merged["score"] == 20)  // ts=2 wins for this key
}

// ── ORMap ─────────────────────────────────────────────────────────────────────

/** Observed-remove map: a concurrent put survives a concurrent remove. */
@Suppress("unused")
internal fun sampleORMap() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    val start = ORMap.empty<String, GSet<String>>()
        .put(a, "team", GSet.of("alice"))

    val alice = start.remove("team")                          // Alice removes the key
    val bob = start.put(b, "team", GSet.of("bob"))            // Bob concurrently adds

    val merged = alice.piece(bob)
    check("team" in merged.keys)                               // add-wins on the key
}

// ── BoundedCounter ────────────────────────────────────────────────────────────

/** Each replica spends within its own quota; transfers redistribute budget. */
@Suppress("unused")
internal fun sampleBoundedCounter() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    var counter = BoundedCounter.init(mapOf(a to 5L, b to 3L))

    // A spends 2 from its own quota.
    val spendPatch = counter.trySpend(a, 2L) ?: error("quota sufficient")
    counter = counter.piece(spendPatch)
    check(counter.quota(a) == 3L)

    // B transfers 1 unit to A.
    val transferPatch = counter.transfer(from = b, to = a, amount = 1L) ?: error("quota sufficient")
    counter = counter.piece(transferPatch)
    check(counter.quota(a) == 4L)
    check(counter.quota(b) == 2L)
}

// ── Causal ────────────────────────────────────────────────────────────────────

/**
 * Add-wins over concurrent remove: a dot unknown to the remover survives the merge.
 * Remove-wins when the remover had already witnessed the dot.
 */
@Suppress("unused")
internal fun sampleCausal() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    // Alice removed the only dot she saw; her context still remembers (A,1).
    val alice = Causal(DotSet(emptySet()), DotContext.of(Dot(a, 1L)))
    // Bob concurrently added a fresh dot; he still holds both.
    val bob = Causal(
        DotSet(setOf(Dot(a, 1L), Dot(b, 1L))),
        DotContext.of(Dot(a, 1L), Dot(b, 1L)),
    )
    val merged = alice.piece(bob)
    // (A,1): Alice saw & dropped -> gone. (B,1): Alice never saw -> kept.
    check(merged.store.dots == setOf(Dot(b, 1L)))
    check(!merged.store.isBottom)  // present — add wins
}

// ── Rga ───────────────────────────────────────────────────────────────────────

/** Concurrent inserts converge to a deterministic order. */
@Suppress("unused")
internal fun sampleRga() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    val (rgaA, opA) = Rga.empty<String>().insertAt(a, 0, "Hello")
    val (rgaB, opB) = Rga.empty<String>().insertAt(b, 0, "World")

    // Both replicas absorb both ops.
    val mergedByA = rgaA.apply(opB)
    val mergedByB = rgaB.apply(opA)

    // Convergence: both produce the same list regardless of delivery order.
    check(mergedByA.toList() == mergedByB.toList())
}

// ── SeamReplicator convenience API ───────────────────────────────────────────

/**
 * Convenience `SeamReplicator` factory + [us.tractat.kuilt.crdt.replicator.SeamReplicator.mutate]:
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
    val seamBob = loom.join(us.tractat.kuilt.core.InMemoryTag("bob"))

    // No manual ReplicatorMessage.serializer(...) wrapping needed.
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
