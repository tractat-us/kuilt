# Module kuilt-heddle-core

Decides who gets the next slice of something shared, fairly, and keeps the books
on it — with no network involved.

Imagine several teams drawing from one budget of computing time. You want each
team to get the slice it was promised (say, three parts to one), and you want a
team that isn't using its share to lend it to a busier one. This module is the
arithmetic of that: a tally of who was granted what, who passed their grant down
to a sub-team, and who actually spent it, plus the rule that picks who is served
next. Two copies of the tally can always be merged, in any order, and agree.

`:kuilt-heddle` puts exactly this onto a live connection between peers. Take this
module alone when you only need the decision and the books in one process: it has
no `Seam`, no Raft, no replication and no coroutines, and its build fails if it
ever gains one (`verifyHeddleCoreDependencies`).

## Start here: a scheduler in one program

Most programs want the whole loop rather than its parts: when a worker frees up,
ask whose work runs next, run it, and charge what it really cost. That is
`LocalHeddle`. Describe the groups and their weights as a `Topology` (in code
with `draft`, or from an admin's file with `Topology.from`), start an engine with
`LocalHeddle.fromTopology`, tell it how many slots you run with
`reconcileCapacity`, and then lease one slot at a time:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleLocalLease -->
```kotlin
    // Both groups have work queued, and this slot can run either.
    val queued = setOf(heavy, light)
    val first = heddle.acquire(waiting = queued, eligible = queued, startedAt = 100L)
    check(first is AcquireOutcome.Acquired)

    // The job overran its 3-unit estimate: all 5 units are charged, 2 of them as overrun.
    check(heddle.complete(first.lease.id, actualCost = 5L) == LeaseOutcome.Done(charged = 5L, overrun = 2L))
```

The rest of the surface, by what you would ask it:

- **Who goes next, and why not me?** `previewPicks` lists the next picks without
  making them; `explain` returns a `WaitExplanation`; `entitlement` is a group's
  fair share as an exact `Rational`.
- **Other endings.** `extend` reserves more for a job still running, `cancel`
  charges nothing, and `admit` charges work that started without a pick.
- **The tree changes.** `apply` takes a whole new `Topology` and returns an
  `ApplyResult`, whose `StepRefusal`s name what is still draining.
  `attachRuntimeChild` and `detachRuntimeChild` add and remove children the
  program names as it runs.
- **Something crashed.** `reconcileLeases` compares the open leases with the work
  still running and reports a `LeaseReconciliation`.
- **The program restarts.** `persist` returns a `LocalHeddleSnapshot` to encode as
  JSON or CBOR; `LocalHeddle.decode` reads it back as a `RestoreResult`, and
  `LocalHeddle.restore` carries on from it under a later epoch, a boot counter
  you persist and raise on every start. Each release
  reads its own `FORMAT_VERSION` and the one before it
  (`SUPPORTED_FORMAT_VERSIONS`).

Every refusal is a typed outcome (`AcquireOutcome`, `AdmitOutcome`, `LeaseOutcome`)
that changes nothing, and the engine is not thread-safe: one writer, under your own
scheduling lock. Two fairness gaps are known. An estimate's size leans share
toward groups that under-reserve, and a lease still running when its group
returns from idle can hand idle credit back. Both are explained, with what to
do about them, in the guide's
[Fair share in one program](https://tractat-us.github.io/kuilt/guide/heddle-local.html#how-fair-is-it),
which walks a whole build farm through every call above.

The sections below are the parts `LocalHeddle` is built from, for a caller that
needs to drive them directly.

## What the ledger keeps

The ledger's data layer — the shared tally and its order-independent merge rule —
plus the **economics** that ride on top: who may spend what (derived *holdings*),
the conserving operations that move entitlement around (granting, returning,
transferring, spending), and an integrity report that flags tampering.

Safety lives in one place: before it moves anything, an operation checks feasibility
against the peer's own complete copy and either returns a small patch to merge in or
refuses — so no honest peer ever spends beyond its share, with no coordination. The
integrity report is a **diagnostic, not a gate**: on a fully-shared copy it reads the
same everywhere, but while a hand-off is still propagating it can momentarily flag a
multi-hop transfer that has not yet caught up; that clears itself as copies reconcile.
Don't block work on the report being empty — block on the operation refusing.

Every edge also carries a **lifecycle** — `prepared → active → closing → retired` —
kept as a max-register that only ever climbs. Preparing an edge creates it without
letting entitlement cross; activating it opens delegation; closing it stops *new*
delegation while still letting entitlement drain back out; retiring it is allowed only
once the edge has fully drained, after which nothing crosses it again and its history
stays queryable forever. Because merging takes the higher state, closure always wins
over a lagging peer still trying to activate — a closed edge can never be resurrected.
Changing a child's weight or parent is not a mutation: it mints a **new** generation
(a new edge id) and drains the old one, so old history is never overwritten. Two peers
that concurrently activate different inbound edges for one child surface a reported
`DualActiveInbound` conflict — not a silently-picked winner — and delegation across
either is refused until the control plane resolves it.

## How the tally is kept

The library is built entirely from the conflict-free replicated data types in
`:kuilt-crdt` — grow-only counters (`GCounter`) and grow-only maps. Everything is
a value in a *join-semilattice*: merging two copies (`piece`) takes the
least-upper-bound, so merging is idempotent, commutative, and associative, and any
two peers that have seen the same set of updates — in any order, with any repeats —
compute exactly the same ledger. Nothing is ever resolved by a wall clock or by
who-merged-first.

Fairness state lives on the **edge** between a parent group and a child group — an
immutable *attachment generation* (`AttachmentRecord`) — not on the child itself.
For each edge the ledger keeps a small set of monotone counters:

| Counter | Meaning |
|---------|---------|
| `issued` | entitlement delegated down across this edge |
| `returned` | entitlement handed back up across this edge |
| `leafSpent` | service charged where this edge is the final edge of the spending path |
| `rollupSpent` | service charged where this edge is a strict prefix of the spending path |

A peer's spendable authority is always **derived** from these counters (subtracting
only slots that peer itself wrote), never stored — which is what makes the local
"can I spend?" check safe with zero coordination. Root supply enters through
`minted`, keyed by a unique `MintId` so two independently-recorded mints union
rather than collide. Peer-to-peer hand-offs at one path live in `transfers`.

`piece` is just the componentwise join of all of these: grow-only union for the
immutable topology and mints, per-edge counter max for the accounting, and a nested
per-row max for transfers. A finite product of semilattices is a semilattice, so the
whole ledger inherits the three merge laws for free.

## Types at a glance

- `GroupId` / `AttachmentId` / `MintId` / `PathKey` — opaque, stably-serialized
  identifiers; a `PathKey` names an entitlement path by its final edge, with a
  `ROOT` sentinel for the root path.
- `Weight` — a positive integer ratio; sibling shares are compared by exact
  cross-multiplication, never floating point, so every peer orders them identically.
- `ServiceUnits` — a non-negative quantity of service, with overflow-checked
  arithmetic.
- `AttachmentRecord` / `MintRecord` — the immutable facts the ledger unions. A
  `MintRecord` names the root it was minted at, so supply is creditable at that
  group and nowhere else: merging two separately bootstrapped ledgers cannot
  count either one's mint twice.
- `Lifecycle` — an edge's `PREPARED → ACTIVE → CLOSING → RETIRED` state, merged by
  taking the higher one so closure always dominates activation.
- `LedgerConflict` — the surfaced integrity/topology faults, including
  `DualActiveInbound` (a child with two active inbound generations),
  `ClosureViolation` (entitlement crossing a retired generation), `LineageCycle`
  (inbound edges that loop instead of reaching a root), `OrphanedTransferPath`
  (a peer-to-peer hand-off the topology moved out from under, which conservation
  is structurally blind to), `MultipleRoots` (supply minted at more than one root —
  two bootstraps merged into one ledger), `ConservationViolation` (the global
  backstop: more service charged than supply ever minted) and
  `FrozenCarriedHandoff` (a hand-off frozen on a replaced generation, naming the
  peer whose fence ack the blocked move is waiting on — the one report here that
  names a peer as the *cause* rather than as a party to the fault).
- `Gauge` — an edge's replicated virtual-time seat: where a new child starts competing, written by
  the scheduler and merged by taking the larger reading, so no single peer's guess is frozen in.
- `EdgeSummary` — the parent-facing projection of one edge (`issued`/`returned`/
  `spent` and the derived `outstanding`).
- `EntitlementLedger` — the replicated `Quilted` state itself.
- `HeddlePolicy` — the pure EEVDF scheduling policy. `HeddlePolicy.pick(edges, config,
  localHoldings)` decides which child to delegate the next quantum to: among the
  children that both want service (`Demand`) and are not running ahead of their fair
  share, it serves the one whose next grant finishes soonest in virtual time, breaking
  ties by a stable identity so every replica picks the same winner. No wall clock, no
  randomness, no floating point — so partitioned peers stay consistent.
- `FairShareAllocator` / `LedgerCell` — the loop that drives the policy against a ledger,
  and the one-method seam it reads and writes through (below).
- `Demand` — how much more service a child could usefully take right now. Advisory
  only: it can be stale or lost and the worst outcome is entitlement briefly parked in
  the wrong pocket; it can never authorize a spend.
- `Rational` — an exact `numerator/denominator` value over `Long`, the type the policy
  reasons in. Virtual times are compared by exact cross-multiplication (never `Double`),
  which is what makes a scheduling decision bit-identical on every platform.
- `HeddleInternalApi` — an opt-in marker on the few declarations `:kuilt-heddle`'s control
  plane reads and no consumer should: the finals a replica declares on a fenced edge
  (`SlotFinals`), the move derived from them (`Relocation`), and the projections behind both.
  They carry no stability promise and may change in any release.

Arithmetic that would exceed `Long` throws rather than wrapping: a fair-share
tally that silently overflowed would be worse than one that stopped. The one
exception is a child's position, which is held at a ceiling instead. A pick that
would have to order a position there is refused with a typed `PositionCeiling`
outcome, and a charge still lands.

## Deciding who runs next

Fairness state is kept per edge, but the *decision* — which child to hand the next
quantum to — is a separate, **pure** step: `HeddlePolicy.pick`. It reads only the
parent's immediate children (their `EdgeSummary`, their advertised `Demand`, the weight from the
`AttachmentRecord`, and the edge's virtual-time seat from its `Gauge`) and returns a single
`Grant`, or `null` when nobody is both eligible and demanding.

The rule is *earliest eligible virtual deadline first* (EEVDF): each child has a
virtual time that advances as it is granted service (so a hoarder is charged the moment
it is issued entitlement, not only when it spends); a child is *eligible* when it is not
ahead of the weighted-mean of its siblings; among the eligible, the one whose next grant
would finish soonest wins. A freshly created child starts level with its siblings — no
credit for the parent's whole past — and an idle child that wakes is clamped forward to
the current front, so neither can bank an unfair head start (design §7, §10.5–6). Each
child's progress is a whole number of sixteenths of a unit, and every comparison is integer
cross-multiplication, so the same inputs produce the same pick on JVM, Android, iOS, macOS,
and wasmJs.

## Scheduling without a network

`FairShareAllocator.schedule(parent, ledger, earmarked, demand)` runs the policy to
exhaustion: build each child's input from the ledger, pick, apply the grant, repeat
until nothing more can move. Before the first pick it seats any child that has no seat
yet and clamps any child that just woke, both against one front, so neither joiner
drags the front down to meet itself. It remembers, between calls, which children it
has seen idle and what clamp each carries; that memory is local to the allocator and
is never part of the ledger.

It reads and writes through a `LedgerCell`: the current ledger, and one step that
decides a write against the state it lands on. For a single process that is a `var`;
`:kuilt-heddle`'s `HeddleNode` hands it the replicator instead, and is otherwise this
allocator plus a demand board and reservations. An allocator is not thread-safe: one
per scheduling peer, called by one thread at a time.

See `docs/heddle-design.md` and `docs/heddle-ledger-design.md` for the full model.
