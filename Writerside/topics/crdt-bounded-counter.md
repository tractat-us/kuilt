# BoundedCounter

A shared budget counter where no replica can spend more than it has been allocated. Unlike `PNCounter`, the total spent can never exceed the total budget.

**Converges to:** a state where `totalSpent + totalBudget == sum of initial allocations` at all times, with each replica constrained to spend only its own quota.

## Why not PNCounter?

With a `PNCounter`, two replicas each starting with a budget of 5 could independently attempt to spend 7 — and on convergence the counter would show -4. With `BoundedCounter`, each spend is checked against the local quota before a delta is produced: a spend over quota returns `null` rather than a delta.

## Quota model

Each replica owns a row in a `received` GCounter (transfers from others) and a `spent` GCounter (its own spends). Local quota is:

```
quota(r) = initial(r) + received(r) - spent(r)
```

Transfers move quota between replicas via a 2D matrix (one row per donor, one column per recipient), so two concurrent donors can both transfer to the same recipient without colliding.

## Code examples

**Initialise — per-replica quotas:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt#initSetsPerReplicaQuotas -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt
// Test: initSetsPerReplicaQuotas
val bc = BoundedCounter.init(mapOf(a to 5L, b to 5L))
assertEquals(10L, bc.totalBudget)
assertEquals(0L, bc.totalSpent)
assertEquals(5L, bc.quota(a))
assertEquals(5L, bc.quota(b))
assertEquals(0L, bc.quota(ReplicaId("nobody")))
```

**Spend within quota:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt#trySpendWithinQuotaProducesADeltaThatDebitsTheReplica -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt
// Test: trySpendWithinQuotaProducesADeltaThatDebitsTheReplica
val bc = BoundedCounter.init(mapOf(a to 5L))
val delta = bc.trySpend(a, 3L)
assertNotNull(delta)
val next = bc.piece(delta)
assertEquals(2L, next.quota(a))
assertEquals(3L, next.totalSpent)
assertEquals(2L, next.totalBudget) // 5 received - 3 spent
```

**Spend over quota is denied:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt#trySpendOverQuotaIsDenied -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt
// Test: trySpendOverQuotaIsDenied
val bc = BoundedCounter.init(mapOf(a to 5L))
assertNull(bc.trySpend(a, 6L))
assertEquals(5L, bc.quota(a))
assertEquals(0L, bc.totalSpent)
```

**Transfer quota between replicas:**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt#transferMovesQuotaFromSenderToReceiver -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt
// Test: transferMovesQuotaFromSenderToReceiver
val bc = BoundedCounter.init(mapOf(a to 5L, b to 5L))
val delta = bc.transfer(from = a, to = b, amount = 3L)!!
val next = bc.piece(delta)
assertEquals(2L, next.quota(a))   // 5 - 3
assertEquals(8L, next.quota(b))   // 5 + 3
assertEquals(10L, next.totalBudget)  // unchanged — transfers redistribute
```

**Two concurrent donors both succeed (the 2D matrix preserves both):**

<!-- verbatim from kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt#concurrentMultiDonorTransfersConverge -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt
// Test: concurrentMultiDonorTransfersConverge
val a = ReplicaId("A"); val c = ReplicaId("C"); val b = ReplicaId("B")
val start = BoundedCounter.init(mapOf(a to 5L, c to 5L))

val aliceBranch = start.piece(start.transfer(from = a, to = b, amount = 3L)!!)
val charlesBranch = start.piece(start.transfer(from = c, to = b, amount = 3L)!!)
val merged = aliceBranch.piece(charlesBranch)

assertEquals(6L, merged.quota(b))  // both 3-transfers survived
assertEquals(2L, merged.quota(a))
assertEquals(2L, merged.quota(c))
```

## Active rebalancing

When a replica runs low on quota, it can request a transfer from peers via `BoundedCounterTransferCoordinator`. The coordinator sends a `TransferRequest` over a `MuxSeam` channel; donors evaluate their surplus and respond with a transfer delta over the existing `SeamReplicator` path. The request protocol is advisory — it cannot cause an overdraw, because `trySpend` always checks local quota before committing.

See `docs/crdt/bounded-counter-rebalancing.md` in the repository for the full rebalancing design.
