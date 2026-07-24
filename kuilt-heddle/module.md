# Module kuilt-heddle

Shares a pool of work fairly across a group of peers — even when some of them
can't talk to each other for a while.

Imagine several teams drawing from one shared budget of computing time. You want
each team to get the slice it was promised (say, three parts to one), you want a
team that isn't using its share right now to lend it to a busier team, and you
want all of this to keep working while the network is flaky — no central referee
handing out permission slips. That is what heddle does. It keeps a running,
tamper-evident tally of who was granted what, who passed their grant down to a
sub-team, and who actually spent it, and every peer can merge its own copy of
that tally with anyone else's and always agree on the result.

The name is a weaver's word: a *heddle* is the part of a loom that lifts one set
of threads so another can cross. Here it lifts one group's claim so another's can
pass through — the same fair-share idea, mechanical instead of computational.

## What this phase ships

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
- `AttachmentRecord` / `MintRecord` — the immutable facts the ledger unions.
- `Lifecycle` — an edge's `PREPARED → ACTIVE → CLOSING → RETIRED` state, merged by
  taking the higher one so closure always dominates activation.
- `LedgerConflict` — the surfaced integrity/topology faults, including
  `DualActiveInbound` (a child with two active inbound generations) and
  `ClosureViolation` (entitlement crossing a retired generation).
- `EdgeSummary` — the parent-facing projection of one edge (`issued`/`returned`/
  `spent` and the derived `outstanding`).
- `EntitlementLedger` — the replicated `Quilted` state itself.

Arithmetic that would exceed `Long` throws rather than wrapping: a fair-share
tally that silently overflowed would be worse than one that stopped.

See `docs/heddle-design.md` and `docs/heddle-ledger-design.md` for the full model.
