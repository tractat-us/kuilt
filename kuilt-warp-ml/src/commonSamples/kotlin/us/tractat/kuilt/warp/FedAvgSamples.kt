package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId

/**
 * Samples for the warp FedAvg / federated-learning demo used by `@sample` KDoc tags.
 *
 * Every function here is compiled as part of commonTest so a typo or API
 * change will break the build, not silently produce stale documentation.
 */

// ── FedAvg ───────────────────────────────────────────────────────────────────

/**
 * Each peer trains locally and contributes a single sample to the federated average.
 * The merged [FedAvg.weights] is the count-weighted mean across all peers — no central
 * server, no coordination round, converges regardless of delivery order or duplicates.
 */
@Suppress("unused")
internal fun sampleFedAvg() {
    val alice = ReplicaId("alice")
    val bob = ReplicaId("bob")
    val carol = ReplicaId("carol")

    // Each peer trains locally and contributes its results.
    val fromAlice = FedAvg.contribution(alice, sampleCount = 100L, localWeights = listOf(0.5, 0.3))
    val fromBob   = FedAvg.contribution(bob,   sampleCount = 200L, localWeights = listOf(0.7, 0.1))
    val fromCarol = FedAvg.contribution(carol, sampleCount = 300L, localWeights = listOf(0.9, 0.5))

    // Any replica merges contributions in any order — result is the same.
    val merged = FedAvg.ZERO.piece(fromAlice).piece(fromBob).piece(fromCarol)

    // weights[i] = Σ(n_k * w_k[i]) / Σ(n_k)
    val w = merged.weights
    check(w.size == 2)
    // Spot-check: (100*0.5 + 200*0.7 + 300*0.9) / (100+200+300) = 460/600 ≈ 0.7667
    check(w[0] in 0.766..0.768)

    // Idempotent: absorbing the same contribution again changes nothing.
    check(merged.piece(fromAlice) == merged)
    check(merged.piece(fromBob) == merged)

    // Rides the coordination-free path — no Seam or Raft required.
    val free = CoordinationFree(fromAlice).embroider(CoordinationFree(fromBob))
    check(free.state == FedAvg.ZERO.piece(fromAlice).piece(fromBob))
}
