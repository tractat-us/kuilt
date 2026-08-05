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
