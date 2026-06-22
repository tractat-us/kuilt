# BoundedCounter

A counter with a hard budget — no device can spend more than it has been allocated. Unlike `PNCounter`, total spend across all devices can never exceed the total budget, even with concurrent spends.

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

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt" include-symbol="initSetsPerReplicaQuotas" }

**Spend within quota:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt" include-symbol="trySpendWithinQuotaProducesADeltaThatDebitsTheReplica" }

**Spend over quota is denied:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt" include-symbol="trySpendOverQuotaIsDenied" }

**Transfer quota between replicas:**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt" include-symbol="transferMovesQuotaFromSenderToReceiver" }

**Two concurrent donors both succeed (the 2D matrix preserves both):**

```kotlin
```
{ src="../../kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/BoundedCounterTest.kt" include-symbol="concurrentMultiDonorTransfersConverge" }

## Active rebalancing

Devices lend each other spending room automatically. When one replica's quota falls low, it asks the peer with the most spare quota to top it up. The peer checks its own balance, transfers what it can spare, and the delta propagates via the existing replication path — no broadcast to the whole group, no global coordination.

This is handled by `BoundedCounterTransferCoordinator`, wired alongside a `Quilter`. When quota for the local replica drops to or below `lowWaterThreshold`, the coordinator:

1. Looks at the currently connected peers (`Seam.peers`) and reads their surplus from the local `BoundedCounter` state — no network round-trip needed.
2. Picks the top-N peers by surplus (up to two as a small fan-out fallback), excluding those with no surplus above the configured floor.
3. Sends a `TransferRequest` directly to those peers via `Seam.sendTo`.

A donor that receives the request checks its own surplus and, if positive, calls `BoundedCounter.transfer` and passes the resulting patch to `Quilter.apply`. The state delta then propagates to all peers via the normal delta-replication path. There is no explicit response message.

**Partition and partial-mesh safety.** The coordinator only contacts peers present in `Seam.peers` at the moment of the request. Unreachable peers are simply skipped; the coordinator retries with exponential backoff, and if quota is still low after all retries, `trySpend` continues denying locally until state updates arrive.

**No overdraw is possible.** The `TransferRequest` is advisory. `trySpend` always enforces local quota from the merged CRDT state — a transfer that hasn't propagated yet cannot unlock a spend.

**Concurrent donors compose cleanly.** Two peers responding to the same request each write their own row of the 2D transfer matrix. The requester simply ends up with more quota than it asked for, which is safe.

**End-to-end example** — a low replica obtains quota from a targeted peer and can spend again:

<!-- verbatim from kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/BoundedCounterTransferCoordinatorTest.kt#spendSucceedsAfterTransferArrivesFromPeer -->
```kotlin
// A has plenty; B starts with only 1 quota
val initial = BoundedCounter.init(mapOf(replicaA to 20L, replicaB to 1L))

val coordConfig = BoundedCounterTransferConfig(
    lowWaterThreshold = 1L, // triggers when quota <= 1
    requestedAmount = 5L,
    surplusFloor = 5L,      // A keeps at least 5 for itself
    maxRetries = 2,
    initialRetryDelay = 10.milliseconds,
)

// B uses its 1 unit of quota
val firstSpend = repB.state.value.trySpend(replicaB)
repB.apply(firstSpend!!)
testScheduler.advanceUntilIdle()

// B is now at 0. Coordinator fires, contacts A (highest surplus), A donates, delta propagates.
val secondSpend = repB.state.value.trySpend(replicaB)
assertNotNull(secondSpend, "B should have received quota transfer and be able to spend")
```

> **Planned:** a proactive background equalizer that redistributes surplus evenly without waiting for a low-water event is tracked in issue [#644](https://github.com/tractat-us/kuilt/issues/644).
