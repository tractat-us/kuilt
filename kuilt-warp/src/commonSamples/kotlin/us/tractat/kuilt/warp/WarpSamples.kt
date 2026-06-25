package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.LatticeProduct
import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Samples for the warp B3 monotone combinators used by `@sample` KDoc tags.
 *
 * Every function here is compiled as part of commonTest so a typo or API
 * change will break the build, not silently produce stale documentation.
 */

// ── zip ──────────────────────────────────────────────────────────────────────

/**
 * Pair a grow-only counter with a net counter into one atomic coordination-free snapshot.
 * Both components converge independently under componentwise join.
 */
@Suppress("unused")
internal fun sampleZip() {
    val r1 = ReplicaId("r1")
    val r2 = ReplicaId("r2")

    // Each peer carries its own (events seen, net score) snapshot.
    val peerA: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
        CoordinationFree(GCounter.of(r1 to 3L))
            .zip(CoordinationFree(PNCounter.ZERO.piece(PNCounter.ZERO.increment(r1, 100L).delta)))

    val peerB: CoordinationFree<LatticeProduct<GCounter, PNCounter>> =
        CoordinationFree(GCounter.of(r2 to 7L))
            .zip(CoordinationFree(PNCounter.ZERO.piece(PNCounter.ZERO.increment(r2, 200L).delta)))

    // After merging: GCounter sums, PNCounter sums.
    val merged = peerA.embroider(peerB)
    check(merged.state.first.value == 10L)   // 3 + 7
    check(merged.state.second.value == 300L) // 100 + 200

    // Idempotent: absorbing the same peer again changes nothing.
    check(merged.embroider(peerA).state == merged.state)
}

// ── joinAllOrNull ─────────────────────────────────────────────────────────────

/**
 * Merge a dynamically-built list of contributions where the list may be empty.
 * Returns null when the list is empty rather than throwing [NoSuchElementException].
 */
@Suppress("unused")
internal fun sampleJoinAllOrNull() {
    val r1 = ReplicaId("r1")
    val r2 = ReplicaId("r2")

    // Empty list — returns null instead of throwing.
    val empty = joinAllOrNull(emptyList<CoordinationFree<GCounter>>())
    check(empty == null)

    // Non-empty list — same result as joinAll.
    val contributions = listOf(
        CoordinationFree(GCounter.of(r1 to 10L)),
        CoordinationFree(GCounter.of(r2 to 20L)),
    )
    val merged = joinAllOrNull(contributions)
    check(merged?.state?.value == 30L)
}
