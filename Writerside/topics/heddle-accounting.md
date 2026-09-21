# Inside Heddle

A job reserves ten units, spends seven, and frees three. Follow the books
as devices lose touch and teams change.

## Where the allowance lives

The budget starts at a root group and flows to its children. Each child has
a weight relative to its siblings. Teams can divide their shares among
subgroups. Heddle calls these groups **tenants** and their links **attachments**.

The `EntitlementLedger` records allowance created, delegated, returned,
transferred, and spent. Each peer writes its own counters; peers merge copies
without counting the same grant twice. For valid operations, created budget
equals holdings plus spending. Passing a unit to a child leaves no second
copy for its parent.

## Reserve, then charge

A team advertises **demand**: how much work it could usefully take.
`schedule(parent)` uses demand and weights to pass allowance toward children.
Demand expires and can be stale; it never grants permission to spend.

Once a leaf holds allowance, work reserves a maximum cost:

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleHeddleNode -->

```kotlin
val reservation = node.reserve(leaf, maximumCost = 10L)
if (reservation != null) {
    node.complete(reservation, actualCost = 7L)
    node.complete(reservation, actualCost = 7L) // idempotent no-op
}
```

The reservation earmarks ten units on this node. Other local jobs cannot
use them; other peers can spend only their own holdings. Completing at seven
removes the earmark and leaves three available.

A failed reservation returns `null`. Cost must lie between zero and the
reserved maximum; an invalid cost throws without discarding the reservation.
Repeated completion on the same node charges once. Reservations are not
durable receipts across a restart.

For setup, use `heddleStatic` with the same root, mint distribution, and tree
on every peer. Supply an advancing clock and a persisted epoch that increases
on each boot. The [compiled sample](https://github.com/tractat-us/kuilt/blob/main/kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt)
shows the wiring.

## Who gets the next turn

Service counts when allowance is **issued**. Sitting on unused grants
cannot make a team look underserved. The policy divides issued
service by weight to track each child's **virtual time**.

Among children with demand that are not ahead of the weighted average,
it chooses the earliest virtual finish time for the next grant. This is
*earliest eligible virtual deadline first* (**EEVDF**).

Handed the same picture, every peer picks the same winner—no clock, no
randomness, no floating point. Their pictures can differ while updates travel.

`boundMetrics(parent)` reports the fairness bounds and observed gap. Weights describe service units,
not completion times. An idle child does not compete or bank credit for
all the work done while it slept.

## When the network splits

Peers can spend their existing holdings locally. Shares may be temporarily
uneven; a peer that exhausts its holdings must wait. Silence cannot justify
reclaiming another peer's budget: it might still be spending.
Automatic revocation of a crashed peer's holdings is not implemented.

Use operation results to decide whether work can proceed. The ledger's
`validate()` report is diagnostic: an incomplete view of a multi-hop transfer
can briefly report a problem that clears when updates arrive.

## When teams change

`heddleGoverned` adds [Consensus](raft.md) for creating supply, changing
attachments, and enrolling or departing peers. Grants and charges stay local.

Attachments move through **prepared → active → closing → retired**.
Closing stops new delegation; retirement requires observed allowance to drain.
But a local observation cannot prove that all peers have stopped writing.

For governed recovery, `quiesce` asks enrolled peers to stop writing a retired
edge and acknowledge their final values. `pendingAcks` shows who has not answered.
Once that barrier completes, `reconcile` can move stranded budget to a live
attachment. An unreachable peer can therefore delay recovery.

Before a clean departure, stop new work and finish outstanding reservations.
Departure alone does not reclaim that peer's holdings.

Next: [connect a Warp job to its allowance](warp-jobs.md), or read the
[ledger design](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-ledger-design.md)
for the algebra. These APIs remain part of the Playground and can change.
