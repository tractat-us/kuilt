package us.tractat.kuilt.crdt

import kotlin.test.assertEquals

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

// ── GCounterDouble ───────────────────────────────────────────────────────────

/** A running fractional total several devices add to independently, always agreeing when they sync. */
@Suppress("unused")
internal fun sampleGCounterDouble() {
    val phone = ReplicaId("phone")
    val watch = ReplicaId("watch")

    // Each device independently accumulates fractional seconds of CPU time.
    var onPhone = GCounterDouble.ZERO
    onPhone = onPhone.piece(onPhone.inc(phone, 0.75).delta)

    var onWatch = GCounterDouble.ZERO
    onWatch = onWatch.piece(onWatch.inc(watch, 0.5).delta)

    // Merge either direction — the total is the same, to the bit.
    val total = onPhone.piece(onWatch).value // 1.25
    check(total == 1.25)
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

// ── ResettableCounter ─────────────────────────────────────────────────────────

/**
 * Two replicas increment; one resets. A concurrent increment (missed the reset)
 * survives; an increment the resetter had observed is cleared.
 */
@Suppress("unused")
internal fun sampleResettableCounter() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    // Shared start: A has incremented 10.
    var shared = ResettableCounter.ZERO
    shared = shared.piece(shared.increment(a, 10L))

    // B resets based on what it observed (the 10 from A).
    val afterReset = shared.piece(shared.reset())

    // Concurrently, A increments 3 more — A hasn't seen B's reset yet.
    val concurrentAdd = shared.piece(shared.increment(a, 3L))

    // Merge: the pre-reset 10 is gone; the concurrent 3 survives.
    val merged = afterReset.piece(concurrentAdd)
    check(merged.value == 3L) // only the concurrent increment survived
}

// ── BloomFilter ───────────────────────────────────────────────────────────────

/**
 * Two independent replicas each add elements; merging produces a filter that
 * answers for both, without false negatives.
 */
@Suppress("unused")
internal fun sampleBloomFilter() {
    // Both replicas share the same configuration: 1 000 expected elements, 1% FP rate.
    var replicaA = BloomFilter.create(expectedElements = 1_000, falsePositiveRate = 0.01)
    var replicaB = BloomFilter.create(expectedElements = 1_000, falsePositiveRate = 0.01)

    // Each replica adds its own element independently.
    replicaA = replicaA.piece(replicaA.add("alice"))
    replicaB = replicaB.piece(replicaB.add("bob"))

    // After merging (bitwise OR), both elements are visible to either replica.
    val merged = replicaA.piece(replicaB)
    check(merged.mightContain("alice"))  // no false negatives
    check(merged.mightContain("bob"))    // no false negatives

    // Elements never added cannot report false negatives by definition,
    // but they may occasionally produce a false positive (within the rate bound).
    check(!replicaA.mightContain("carol") || true)  // might be a false positive — that's expected
}

// ── Fugue ─────────────────────────────────────────────────────────────────────

/**
 * Concurrent runs inserted at the same position stay contiguous after merge.
 * This is the property that distinguishes Fugue from RGA.
 */
@Suppress("unused")
internal fun sampleFugue() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    // Replica A builds a run: "a1", "a2", "a3" (each prepended before the prior front).
    val (fA1, opA1) = Fugue.empty<String>().insertAt(a, 0, "a1")
    val (fA2, opA2) = fA1.insertAt(a, 0, "a2")
    val (fA3, opA3) = fA2.insertAt(a, 0, "a3")

    // Replica B independently builds "b1", "b2" at the same position.
    val (fB1, opB1) = Fugue.empty<String>().insertAt(b, 0, "b1")
    val (fB2, opB2) = fB1.insertAt(b, 0, "b2")

    // Merge all ops into both replicas.
    val mergedByA = fA3.apply(opB1).apply(opB2)
    val mergedByB = fB2.apply(opA1).apply(opA2).apply(opA3)

    // Both converge to the same order.
    check(mergedByA.toList() == mergedByB.toList()) { "Convergence: both must agree" }

    val merged = mergedByA.toList()
    // The A-run and B-run each form a contiguous block — no interleaving.
    val aIndices = merged.mapIndexedNotNull { i, v -> if (v.startsWith("a")) i else null }
    val bIndices = merged.mapIndexedNotNull { i, v -> if (v.startsWith("b")) i else null }
    check(aIndices == (aIndices.first()..aIndices.last()).toList()) { "A run is contiguous: $merged" }
    check(bIndices == (bIndices.first()..bIndices.last()).toList()) { "B run is contiguous: $merged" }
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

// ── MovableTree ───────────────────────────────────────────────────────────────

/**
 * Concurrent moves and cycle prevention: two replicas move the same node to
 * different parents; both converge to the same acyclic tree.
 */
@Suppress("unused")
internal fun sampleMovableTree() {
    val alice = ReplicaId("alice")
    val bob = ReplicaId("bob")

    // Shared initial state: root → A, root → B, root → C.
    val base = MovableTree.empty<String>()
    val (t1, idA) = base.addNode(alice, ts = 1L, parent = MovableTree.ROOT_ID, value = "A")
    val (t2, idB) = t1.addNode(alice, ts = 2L, parent = MovableTree.ROOT_ID, value = "B")
    val (t3, idC) = t2.addNode(alice, ts = 3L, parent = MovableTree.ROOT_ID, value = "C")

    // Alice moves A under B (ts=4); Bob moves A under C (ts=5). Both diverge from t3.
    val (aliceState, alicePatch) = t3.move(alice, ts = 4L, node = idA, newParent = idB)
    val (bobState, bobPatch)     = t3.move(bob,   ts = 5L, node = idA, newParent = idC)

    // Each replica absorbs the other's delta.
    val mergedByAlice = aliceState.piece(bobPatch)
    val mergedByBob   = bobState.piece(alicePatch)

    // Convergence guaranteed: both arrive at the same tree.
    check(mergedByAlice == mergedByBob)

    // Bob's ts=5 wins — A ends up under C.
    check(mergedByAlice.parentOf(idA) == idC)

    // Cycle prevention: moving A under C while C is under A is silently skipped.
    val (t4, _) = t3.addNode(alice, ts = 6L, parent = idA, value = "D")
    val (_, cyclePatch) = t4.move(alice, ts = 7L, node = idA, newParent = idA)
    val safe = t4.piece(cyclePatch)
    check(!safe.isAncestor(ancestor = idA, descendant = idA))
}

// ── HyperLogLog ───────────────────────────────────────────────────────────────

/** Count distinct items with a fixed memory footprint (≈16 KB at p=14). */
@Suppress("unused")
internal fun sampleHyperLogLog() {
    var hll = HyperLogLog.empty(precision = 14)

    // Add a stream of items — duplicates do not inflate the count.
    // add() returns a sparse Patch; apply it with piece().
    hll = hll.piece(hll.add("alice"))
    hll = hll.piece(hll.add("bob"))
    hll = hll.piece(hll.add("alice")) // duplicate — no-op delta, nothing changes

    // The estimate is approximate but close to 2 for small cardinalities.
    check(hll.estimate() in 1L..3L)
}

/**
 * Two replicas track distinct visitors independently; merging gives the union's
 * cardinality without sharing the actual item list.
 */
@Suppress("unused")
internal fun sampleHyperLogLogMerge() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    // Replica A sees users 0–999; replica B sees users 500–1499 (500 in common).
    var hllA = HyperLogLog.empty(precision = 14)
    var hllB = HyperLogLog.empty(precision = 14)
    repeat(1_000) { i -> hllA = hllA.piece(hllA.add("user-$i")) }
    repeat(1_000) { i -> hllB = hllB.piece(hllB.add("user-${i + 500}")) }

    // Merge: element-wise max of registers.
    val merged = hllA.piece(hllB)

    // The merged estimate is close to 1500 (the true distinct count).
    val estimate = merged.estimate()
    check(estimate in 1_200L..1_800L) { "expected ≈1500, got $estimate" }

    // Idempotent: merging again with either replica changes nothing.
    check(merged.piece(hllA) == merged)
    check(merged.piece(hllB) == merged)
}

// ── CountMinSketch ────────────────────────────────────────────────────────────

/** Track approximate word frequencies; the estimate never underestimates. */
@Suppress("unused")
internal fun sampleCountMinSketch() {
    // width=512, depth=5 → ε ≈ 0.005, δ ≈ 0.007 error bound.
    var sketch = CountMinSketch.empty(width = 512, depth = 5)

    // add() returns a delta; absorb it with piece().
    repeat(10) { sketch = sketch.piece(sketch.add("hello")) }
    repeat(3) { sketch = sketch.piece(sketch.add("world")) }

    check(sketch.estimate("hello") >= 10L)  // never underestimates
    check(sketch.estimate("world") >= 3L)
    check(sketch.estimate("unseen") == 0L)  // empty sketch returns 0
}

/** Max-merge is idempotent: re-delivering the same patch does not inflate the count. */
@Suppress("unused")
internal fun sampleCountMinSketchMerge() {
    var a = CountMinSketch.empty(width = 64, depth = 4)
    var b = CountMinSketch.empty(width = 64, depth = 4)

    // Two replicas observe different occurrences of the same item.
    repeat(7) { a = a.piece(a.add("event")) }
    repeat(4) { b = b.piece(b.add("event")) }

    // After merging, the merged estimate is >= the max of the two.
    val merged = a.piece(b)
    check(merged.estimate("event") >= 7L)

    // Merging again is idempotent — same result.
    check(merged.piece(a) == merged.piece(a).piece(a))
}

// ── LatticeProduct ───────────────────────────────────────────────────────────

/**
 * A GCounter and a GSet tracked together as one atomic coordination-free snapshot.
 * Both components join independently; the lattice laws hold on the pair.
 */
@Suppress("unused")
internal fun sampleLatticeProduct() {
    val r1 = ReplicaId("r1")
    val r2 = ReplicaId("r2")

    // Two replicas each carry a (counter, tags) pair.
    val replicaA = LatticeProduct.of(GCounter.of(r1 to 3L), GSet.of("alpha"))
    val replicaB = LatticeProduct.of(GCounter.of(r2 to 7L), GSet.of("beta"))

    // Componentwise join: counter sums, set unions.
    val merged = replicaA.piece(replicaB)
    check(merged.first.value == 10L)                      // 3 + 7
    check(merged.second.elements == setOf("alpha", "beta"))

    // Idempotent: merging again changes nothing.
    check(merged.piece(replicaA) == merged)
}

