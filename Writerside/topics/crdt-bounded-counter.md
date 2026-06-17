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

When a replica runs low on quota, it can request a transfer from peers via `BoundedCounterTransferCoordinator`. The coordinator sends a `TransferRequest` over a `MuxSeam` channel; donors evaluate their surplus and respond with a transfer delta over the existing `Quilter` path. The request protocol is advisory — it cannot cause an overdraw, because `trySpend` always checks local quota before committing.

See `docs/crdt/bounded-counter-rebalancing.md` in the repository for the full rebalancing design.
