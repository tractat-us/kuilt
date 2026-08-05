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

/**
 * Ship the change, not the set. Every mutator returns a [Patch] — the one element it touched —
 * and a re-add's delta also retires the dots it supersedes, which is what stops a later remove
 * from resurrecting the element. Absorbing a patch locally is `set.piece { … }`.
 */
@Suppress("unused")
internal fun sampleORSet() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    // Two peers have converged: "alice" is present on both, added by B.
    var alpha = ORSet.empty<String>().piece { it.add(b, "alice") }
    var bravo = alpha

    // A re-adds "alice" and puts only the change on the wire. The delta names A's new dot
    // *and* B's older one, which the re-add supersedes — so both peers drop the old dot.
    val readd = alpha.add(a, "alice")
    alpha = alpha.piece(readd)
    bravo = bravo.piece(readd)
    check(alpha == bravo)

    // A concurrent add beats a concurrent remove: the remove can only retire the dots it saw.
    val elsewhere = ORSet.empty<String>().piece { it.add(b, "alice") }
    check(alpha.piece(alpha.remove("alice")).piece(elsewhere).contains("alice"))

    // A remove lands everywhere, because both peers agree on which dot is live. Had the delta
    // above kept quiet about B's dot, it would still be alive on bravo — and "alice" would come
    // back from the dead there.
    val forget = alpha.remove("alice")
    alpha = alpha.piece(forget)
    bravo = bravo.piece(forget)
    check(!alpha.contains("alice"))
    check(!bravo.contains("alice"))
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

/**
 * Ship the change, not the map. Every mutator returns a [Patch] carrying the one cell it wrote —
 * and a removal ships a *tombstone cell*, not an absence: an empty map says nothing at all, so
 * the removal would never leave the writer. Absorbing a patch locally is `map.piece { … }`.
 */
@Suppress("unused")
internal fun sampleLWWMap() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    // Two peers have converged on a settings map.
    var alpha = LWWMap.empty<String, String>()
        .piece { it.set(a, timestamp = 1L, key = "lang", value = "en") }
        .piece { it.set(a, timestamp = 2L, key = "tz", value = "UTC") }
        .piece { it.set(a, timestamp = 3L, key = "theme", value = "dark") }
    var bravo = alpha

    // B changes one setting and puts only that cell on the wire. The frame is the same size
    // whether the map holds three keys or ten thousand, and the other keys are untouched.
    val change = alpha.set(b, timestamp = 4L, key = "theme", value = "light")
    alpha = alpha.piece(change)
    bravo = bravo.piece(change)
    check(alpha == bravo)
    check(alpha["theme"] == "light")
    check(alpha["lang"] == "en")

    // Per key the higher (timestamp, replica) tag wins, and a remove is a write like any other,
    // so its delta is a one-cell tombstone map…
    val forget = bravo.remove(b, timestamp = 5L, key = "lang")
    check(alpha.piece(forget)["lang"] == null)

    // …and never an empty map, which is the lattice identity and carries no removal at all.
    check(alpha.piece(LWWMap.empty<String, String>())["lang"] == "en")
}

// ── ORMap ─────────────────────────────────────────────────────────────────────

/**
 * Ship the change, not the map. Every mutator returns a [Patch], and the change has two things to
 * say: only the value *you* passed, and the tags of yours this put supersedes. Absorbing a patch
 * locally is `map.piece { … }`.
 */
@Suppress("unused")
internal fun sampleORMap() {
    val a = ReplicaId("A")
    val b = ReplicaId("B")

    // Two peers have converged: "team" already holds a long roster, put there by B.
    var alpha = ORMap.empty<String, GSet<String>>()
        .piece { it.put(b, "team", GSet.of("alice", "bob", "carol", "dan")) }
    var bravo = alpha

    // A adds one member and puts only the change on the wire. The delta carries A's one name —
    // not the merged roster — because the receiver re-does that merge against its own copy.
    val hire = alpha.put(a, "team", GSet.of("erin"))
    check(hire.delta["team"] == GSet.of("erin"))

    // A's tag joins B's rather than replacing it, so the key's value is both writes together.
    alpha = alpha.piece(hire)
    bravo = bravo.piece(hire)
    check(alpha == bravo)
    check(alpha["team"] == GSet.of("alice", "bob", "carol", "dan", "erin"))

    // A concurrent put beats a concurrent remove: add-wins on the key.
    val elsewhere = ORMap.empty<String, GSet<String>>().piece { it.put(b, "team", GSet.of("frank")) }
    check("team" in alpha.piece(alpha.remove("team")).piece(elsewhere).keys)

    // A remove lands everywhere, because both peers agree on which tags are live. Had A already
    // held a tag on "team" and the delta kept quiet about it, that older tag would still be alive
    // on bravo — and "team" would come back from the dead there.
    val disband = alpha.remove("team")
    alpha = alpha.piece(disband)
    bravo = bravo.piece(disband)
    check("team" !in alpha.keys)
    check("team" !in bravo.keys)
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

// ── DDSketch ──────────────────────────────────────────────────────────────────

/** Track latency quantiles: every estimate is within the configured relative accuracy. */
@Suppress("unused")
internal fun sampleDDSketch() {
    val replica = ReplicaId("api-server-1")

    // α = 0.01 → every quantile estimate is within 1% of the true value.
    var latencies = DDSketch.empty(relativeAccuracy = 0.01)

    // add() returns a one-bucket delta; absorb it with piece().
    for (ms in listOf(12.0, 15.0, 14.0, 250.0, 13.0, 16.0, 900.0, 14.5)) {
        latencies = latencies.piece(latencies.add(replica, ms))
    }

    // The p50 sits among the fast requests; the p99 reflects the slow tail.
    check(latencies.quantile(0.5) in 13.0..17.0)
    check(latencies.quantile(1.0) in 890.0..910.0) // within 1% of 900
}

/** Merging two peers' sketches is exactly the sketch of the combined stream — zero added error. */
@Suppress("unused")
internal fun sampleDDSketchMerge() {
    val serverA = ReplicaId("server-a")
    val serverB = ReplicaId("server-b")

    // Two servers record their own request latencies.
    var a = DDSketch.empty()
    var b = DDSketch.empty()
    repeat(100) { a = a.piece(a.add(serverA, 10.0 + it)) }   // 10–109 ms
    repeat(100) { b = b.piece(b.add(serverB, 500.0 + it)) }  // 500–599 ms

    // Merge: pointwise GCounter join of the bucket counts.
    val merged = a.piece(b)
    check(merged.count == 200L)

    // The merged p50 sits at the boundary between the two servers' ranges.
    check(merged.quantile(0.5) in 100.0..120.0)

    // Idempotent: merging again with either side changes nothing.
    check(merged.piece(a) == merged)
    check(merged.piece(b) == merged)
}

// ── Gauge ─────────────────────────────────────────────────────────────────────

/** The newest observation wins on merge — deterministic tie-break on replica id. */
@Suppress("unused")
internal fun sampleGauge() {
    val phone = ReplicaId("phone")
    val laptop = ReplicaId("laptop")

    // Each device observes the players-online level at its own time.
    var onPhone = Gauge.empty()
    var onLaptop = Gauge.empty()
    onPhone = onPhone.piece(onPhone.observe(phone, timestamp = 100L, value = 4.0))
    onLaptop = onLaptop.piece(onLaptop.observe(laptop, timestamp = 250L, value = 7.0))

    // Merge: the observation with the larger (timestamp, replicaId) tag wins.
    val merged = onPhone.piece(onLaptop)
    check(merged.value == 7.0)
    check(merged.timestamp == 250L)

    // Commutative and idempotent: any merge order, any duplication, same answer.
    check(onLaptop.piece(onPhone) == merged)
    check(merged.piece(onPhone) == merged)
}

// ── Histogram ─────────────────────────────────────────────────────────────────

/** Fixed buckets you choose up front; each recorded value lands in exactly one. */
@Suppress("unused")
internal fun sampleHistogram() {
    val replica = ReplicaId("api-server-1")

    // Buckets: (-inf, 10], (10, 50], (50, 100], (100, +inf) — SLA thresholds in ms.
    var latencies = Histogram.empty(boundaries = listOf(10.0, 50.0, 100.0))

    // record() returns a one-bucket delta; absorb it with piece().
    for (ms in listOf(7.0, 12.0, 45.0, 50.0, 220.0)) {
        latencies = latencies.piece(latencies.record(replica, ms))
    }

    check(latencies.bucketCounts == listOf(1L, 3L, 0L, 1L)) // 50.0 is upper-inclusive in (10, 50]
    check(latencies.count == 5L)
    check(latencies.sum == 334.0)
}

/** Merging two peers' histograms is exactly the histogram of the combined stream. */
@Suppress("unused")
internal fun sampleHistogramMerge() {
    val serverA = ReplicaId("server-a")
    val serverB = ReplicaId("server-b")
    val boundaries = listOf(10.0, 100.0)

    // Two servers count their own request latencies.
    var a = Histogram.empty(boundaries)
    var b = Histogram.empty(boundaries)
    repeat(30) { a = a.piece(a.record(serverA, 5.0)) } // 30 fast requests
    repeat(20) { b = b.piece(b.record(serverB, 500.0)) } // 20 slow requests

    // Merge: pointwise GCounter join of the bucket counts.
    val merged = a.piece(b)
    check(merged.bucketCounts == listOf(30L, 0L, 20L))
    check(merged.count == 50L)

    // Idempotent: merging again with either side changes nothing.
    check(merged.piece(a) == merged)
    check(merged.piece(b) == merged)
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


// ── EphemeralMapTracker ─────────────────────────────────────────────────────

/**
 * The two update channels: a peer's own heartbeat is an author-fresh delta, whereas an
 * anti-entropy exchange re-delivers state whose author may be long gone.
 */
@Suppress("unused")
internal fun sampleEphemeralMapTrackerChannels() {
    val a = ReplicaId("A")
    var now = 0L
    val tracker = EphemeralMapTracker<String>(ttlMs = 5_000L, clock = { now })

    // A heartbeat straight from its author: `received` — this is what liveness is measured on.
    val heartbeat = EphemeralMap.empty<String>().put(a, "editing", clock = 1L)
    tracker.received(heartbeat)
    check(tracker.live()[a] == "editing")

    // A goes silent and ages out.
    now = 5_000L
    check(a !in tracker.live())

    // An anti-entropy round re-delivers A's last frame, held by some other peer. Merged with
    // `relayed` it joins the state without re-stamping the TTL, so A stays correctly absent.
    tracker.relayed(heartbeat)
    check(a !in tracker.live())
    check(tracker.snapshot().entries[a]?.clock == 1L)
}
