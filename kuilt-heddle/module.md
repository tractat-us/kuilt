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
- `HeddlePolicy` — the pure EEVDF scheduling policy. `HeddlePolicy.pick(edges, config,
  localHoldings)` decides which child to delegate the next quantum to: among the
  children that both want service (`Demand`) and are not running ahead of their fair
  share, it serves the one whose next grant finishes soonest in virtual time, breaking
  ties by a stable identity so every replica picks the same winner. No wall clock, no
  randomness, no floating point — so partitioned peers stay consistent.
- `Demand` — how much more service a child could usefully take right now. Advisory
  only: it can be stale or lost and the worst outcome is entitlement briefly parked in
  the wrong pocket; it can never authorize a spend.
- `Rational` — an exact `numerator/denominator` value over `Long`, the type the policy
  reasons in. Virtual times are compared by exact cross-multiplication (never `Double`),
  which is what makes a scheduling decision bit-identical on every platform.
- `HeddleNode` / `heddleStatic` — the node that runs the tally, demand board, reservations,
  and liveness over a `Seam`, and its fixed-roster bootstrap (see below).
- `GovernedHeddleNode` / `heddleGoverned` — the same node with runtime supply creation and
  reshapes serialized through `kuilt-raft`; its control calls return a `ControlOutcome`
  (`Applied` / `Conflict`) so an overlapping reshape's loser surfaces, never silently drops.
- `HeddleConfig` — the node's policy caps, the §8.2 bound cap, the demand-staleness window,
  and the injected replication/liveness/RNG knobs.
- `DemandBoard` — one peer's advertised appetite across the edges it serves; the value
  carried in that peer's slot of the presence-style demand board.
- `ReservationId` — the handle a leaf earmark is completed against.
- `BoundMetrics` — the three consistent pieces of the temporary fairness-error bound at a
  parent (§8.2): the configured worst case, the current state-dependent bound, and the
  fairness gap actually observed.

Arithmetic that would exceed `Long` throws rather than wrapping: a fair-share
tally that silently overflowed would be worse than one that stopped.

## Deciding who runs next

Fairness state is kept per edge, but the *decision* — which child to hand the next
quantum to — is a separate, **pure** step: `HeddlePolicy.pick`. It reads only the
parent's immediate children (their `EdgeSummary`, their advertised `Demand`, and the
immutable weight/virtual-time origin from the `AttachmentRecord`) and returns a single
`Grant`, or `null` when nobody is both eligible and demanding.

The rule is *earliest eligible virtual deadline first* (EEVDF): each child has a
virtual time that advances as it is granted service (so a hoarder is charged the moment
it is issued entitlement, not only when it spends); a child is *eligible* when it is not
ahead of the weighted-mean of its siblings; among the eligible, the one whose next grant
would finish soonest wins. A freshly created child starts level with its siblings — no
credit for the parent's whole past — and an idle child that wakes is clamped forward to
the current front, so neither can bank an unfair head start (design §7, §10.5–6). Every
comparison is exact integer arithmetic on `Rational`, so the same inputs produce the
same pick on JVM, Android, iOS, macOS, and wasmJs.

## Running it over a network

`HeddleNode` is the piece that puts the tally, the policy, and the schedule onto a live
connection between peers. Start one with `heddleStatic` — you hand it the connection, this
peer's identity, the root group, the starting supply (who begins with how much), and the
starting shape of the tree; every peer that starts with the same inputs begins from an
identical tally, and from then on their copies stay in step over the wire on their own.

What a node does for you:

- **Keeps every peer's tally in step.** Grants, returns, and charges are exchanged as they
  happen and reconciled in the background, so a peer that was briefly cut off catches up on
  reconnect — no referee, no lost history.
- **Collects "who wants work right now."** Each peer advertises appetite per child with
  `advertise`; a peer that stops refreshing (say, it crashed) has its appetite quietly
  expire, so stale wants can't keep pulling work its way. Appetite is only advice — it can
  never authorize spending.
- **Hands out and charges for work.** `schedule(parent)` runs allocation rounds, delegating
  this peer's share down toward the children that want it. Leaf work `reserve`s a slice,
  runs, then `complete`s it — and completing the same reservation twice charges exactly once.
- **Notices when a peer goes quiet.** It distinguishes a temporary split from a real crash
  and reports it (`partitionEvents`). It deliberately does **not** claw back a crashed peer's
  share: a wrong reclaim would let two peers spend the same units, so a crashed peer's share
  simply sits unused until an operator recovers it.
- **Publishes how far off fair it could be.** `boundMetrics(parent)` reports the worst-case,
  current, and actually-observed fairness gap, so a consumer never claims tighter fairness
  than it can prove while peers are still reconciling.

## Creating supply and reshaping at runtime

`heddleStatic` is the fixed-roster front door: the supply is decided once, at start-up, and
every peer is handed the same starting tree. Its sibling **`heddleGoverned`** is for when supply
and shape have to change *while the system is running* — a new tenant arrives, a lane is added,
entitlement is topped up. Those few decisions are the only ones that genuinely need everyone to
agree on an order, so `heddleGoverned` runs them through a small agreement step (`kuilt-raft`)
and nothing else does:

- **Creating supply can't be double-counted.** Minting new entitlement (`mint`) goes through the
  agreement step, so if the network splits, the smaller half simply can't create supply — two
  halves of a split can never both mint against the same pool.
- **Two conflicting reshapes don't corrupt the tree.** If two peers try to re-attach the same
  child to different parents at once, the agreement step picks an order: the first wins, and the
  second comes back as a **structured conflict** (`ControlConflict.DualInbound`) for the operator
  to resolve — never a silent overwrite, never a coin-flip on a timestamp. Reshapes that don't
  touch the same child don't wait on each other at all.
- **The everyday path is untouched.** Handing out and charging for work — `schedule`, `reserve`,
  `complete` — never goes through the agreement step, at any rate. That is the whole point:
  agreement appears only at the two rare moments that need it.

`heddleGoverned` returns a `GovernedHeddleNode`: the same data-plane calls as `HeddleNode` plus
`mint`/`prepare`/`activate`/`close`/`retire`, each of which returns a `ControlOutcome` — `Applied`
when it took effect, or `Conflict` carrying the structured reason when it lost a race. Reclaiming a
crashed peer's stranded share is a fenced control-plane act whose seam is **defined but not yet
shipped** (`revocation`): a wrong reclaim would let two peers spend the same units, so v1 leaves the
share safely stranded.

See `docs/heddle-design.md` and `docs/heddle-ledger-design.md` for the full model.
