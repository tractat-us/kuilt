# BoundedCounter active rebalancing (Rung 5b)

## The problem

`BoundedCounter.trySpend` enforces local quota without coordination: a replica can
only spend what it has been allocated. If a replica exhausts its quota, all further
`trySpend` calls return `null` — even if other replicas have surplus sitting idle.

Rung 5b adds the **active rebalancing** protocol: when a replica runs low, it asks
peers to transfer some of their surplus. No coordination is required for the spend
itself (the local quota check is always the gatekeeper); the protocol is purely
about moving quota between replicas before it's needed.

## How it works

```
Replica B (quota: 1)          Replica A (quota: 15)
      |                               |
      |  TransferRequest(amount=5)    |
      |---broadcast------------------>|
      |                               |  evaluates surplus
      |                               |  transfer(A→B, 5) → patch
      |                               |  replicator.apply(patch)
      |                               |  delta broadcast
      |<---delta (SeamReplicator)-----|
      |  merges delta                 |
      |  quota(B) is now 6           |
      |                               |
      trySpend(B) → succeeds
```

The transfer delta rides the existing `SeamReplicator` path — no separate
response message is needed. Multiple donors can respond to the same request
concurrently: each writes to its own row of the transfer matrix, so they compose
without collision.

## Routing: how two logical channels share one Seam

`Seam.incoming` is single-collection per the kuilt contract. `SeamReplicator`
already owns that collection. The coordinator needs its own incoming channel.

Solution: **`MuxSeam`** (`kuilt-core`) wraps the underlying seam and owns the
single collect via `shareIn`. It prefixes every frame with a 1-byte tag and
provides an N-way channel split:

| Tag  | Channel |
|------|---------|
| `0x00` | `SeamReplicator` (delta / ack / fullState / resend) |
| `0x01` | `BoundedCounterTransferCoordinator` (transfer requests) |

Each consumer calls `mux.channel(tag)` to get a typed `Seam` view that strips
the tag on reads and prepends it on writes. Neither consumer needs to know about
the other. The same `MuxSeam` mechanism powers `Room.channel(id)` in
`kuilt-session`, which extends this pattern to session metadata convergence (see
`docs/architecture.md`).

## Safety invariant

**The request protocol is advisory. It cannot cause an overdraw.**

`BoundedCounter.trySpend` is the only way to commit a spend, and it always checks
`quota(replica) >= amount` against the current merged state. A transfer that hasn't
arrived yet does not increase quota. If no peer responds, the requester continues
to deny `trySpend` locally — it does not block, it degrades gracefully.

## Liveness

The coordinator retries up to `maxRetries` times with exponential backoff.
If all retries fail (peers unreachable or all have zero surplus), the requester
falls back to "deny locally" until a future transfer arrives through normal
delta propagation.

## Conservation

At any quiescent state across fully-converged replicas:

```
totalSpent + totalBudget == Σ initial[r]
```

Transfers move quota between replicas but do not change `totalBudget` (they go
through the `transfers` matrix, not `spent`). Only `trySpend` increases `totalSpent`.
