# `:kuilt-heddle` — weighted fair-share scheduling over kuilt

> **Status: design spec, draft (unbuilt, pre-1.0).** A kuilt-native design for a
> distributed weighted fair-share scheduler — a hierarchical EEVDF-inspired
> entitlement scheduler expressed entirely in terms of kuilt's real contract and
> CRDT zoo. Every concept is grounded in a primitive kuilt already ships; where
> no honest correspondence exists, that is said plainly. The final sections place
> this scheduler inside the shipped `:kuilt-warp` compute grid — its first
> customer, not its home. Tracked by the `:kuilt-heddle` epic; implementation
> phasing is §15.

## What this is for

Imagine a room full of machines — phones, laptops, a couple of servers —
running work for several customers at once. Customer A has paid for three
times the throughput of customer B. Inside customer A's slice, interactive
requests should jump ahead of overnight batch jobs. Nobody is in charge; the
machines just need to *agree, over time*, on who got how much. Now cut the
network in half. Both halves should keep working, each spending only what it
was already given — and when the room reconnects, the books must balance to
the penny.

That is a **weighted fair-share scheduler**. It answers one question,
recursively: *given a budget of capacity and several claimants with different
weights, whose turn is it, and how much?* The claimants form a tree —
customers at the top, teams under them, individual workloads at the leaves —
and the same rule runs at every level. A parent hands a portion of its budget
to a child; the child, itself a scheduler, hands a portion to *its* children;
a leaf finally spends its portion on real work.

The whole job in one line: **share capacity fairly among nested groups, keep
scheduling through a partition, and never let the accounting lie.**

## The one idea

Split *"who has done work"* from *"who is allowed to do work next"*, and make
both replicated state that always converges:

- **History only grows.** Work already completed is recorded in monotonic
  counters. Merging two peers' views can only make the history more complete.
- **Permission is conserved.** The right to do *future* work — call it
  **entitlement** — behaves like money in escrow. It is minted once at the
  root, delegated down a tree of groups, spent by leaves, and returned when
  unused. No merge, no partition, no crash can create more of it.

A peer cut off from the room keeps scheduling with exactly the entitlement
already in its pocket, and not one unit more. That single rule is where every
guarantee in this spec comes from: the fairness error a partition can cause is
*bounded by the entitlement that was out in pockets when the partition began*.

One refinement makes the fairness honest: decisions charge a child for
**committed service** — history *plus* outstanding entitlement — so a child
that hoards an unspent grant is charged for it and cannot ask for the same
deficit twice.

## Recognition: most of this is already on the shelf

kuilt already ships almost every mechanism this scheduler needs. As with warp,
the job is less "build a scheduler" than "notice the pieces and give them a
policy." This table is the spine of the document; the sections after it expand
each row.

| The scheduler needs… | …kuilt already ships | Why it fits |
|---|---|---|
| a conserved budget with a coordination-free local spend check | `BoundedCounter` (`:kuilt-crdt`) | per-replica quota; `trySpend` returns a `Patch` or `null`, checked locally; `totalBudget = Σinitial − Σspent` conserved by construction; `transfer` moves quota on each donor's exclusively-owned row |
| monotonic per-origin accounting that merges by max | `GCounter` (and the matrix-of-`GCounter` idiom inside `BoundedCounter`) | grow-only, per-origin, merge = per-slot max; `committedService` is a pure lattice read |
| replication, anti-entropy, causal GC over any fabric | `Quilter` over a `Seam` (`:kuilt-quilter`); `Causal` / `DotContext` | delta-exchange + anti-entropy is the module's whole job; the lattice laws are stated against exactly the faults fabrics have |
| soft, expiring, *non*-conserved signals (demand) | `EphemeralMap` + `EphemeralMapTracker` (`:kuilt-crdt`) | per-replica slot, per-slot monotonic clock, TTL expiry by local receive time, explicitly not durable — a demand epoch in a type |
| knowing who is reachable right now | `:kuilt-liveness` (`HeartbeatPartitionDetector`, `PartitionEvent`) | partition ≠ crash is a first-class signal, already shared by session and warp |
| a replicated tree that survives concurrent moves | `MovableTree` (`:kuilt-crdt`) — the Kleppmann et al. move-op algorithm, already shipped | rejected for v1, kept as the v2 horizon (§5.4) |
| agreement where agreement is genuinely unavoidable | `:kuilt-raft`, with `readIndex()` as a quorum fence | the same embroidery warp's coordinated path already threads |
| a deterministic multi-peer simulator | `InMemoryLoom`; `RaftSimulation`/`InMemoryRaftNetwork`/`raftRunTest`; `MultiNodeRaftSim` (`:kuilt-raft-test`) | hand-rolling a cluster harness is banned here; the canonical harnesses model drop/duplicate/reorder/partition already |

Three things are genuinely new, and they are the real deliverable:

1. **The entitlement ledger** — a path-indexed generalization of
   `BoundedCounter` (§4). `BoundedCounter` is its depth-zero special case.
2. **The attachment lifecycle lattice** — a small monotonic CRDT for topology
   edges where *closure dominates activation under merge* (§5).
3. **The EEVDF-inspired policy** — a pure ranking function over per-edge
   summaries (§7). No kuilt precedent; entirely new code.

**The name.** On a loom, the *heddles* decide which warp threads rise to meet
each throw of the shuttle — literally *whose thread is served this pick*. A
scheduler over warp's parallel lanes is a heddle.

## Where it sits

**A standalone module, not a warp satellite.** Fair-share entitlement over a
`Seam` is useful to any consumer with contended capacity — a `:kuilt-cluster`
relay apportioning propose bandwidth across rooms is the obvious second
customer — so the dependency arrow points *from* warp *to* the heddle:

```text
:kuilt-heddle
    depends on :kuilt-core, :kuilt-crdt, :kuilt-quilter, :kuilt-liveness
    control-plane adapter depends on :kuilt-raft
:kuilt-warp-heddle   (satellite, alongside :kuilt-warp-planning/-ml/-compiler)
    depends on :kuilt-warp + :kuilt-heddle   (lane-tagged scheduling, §14)
```

Warp core carries only an *opaque* lane tag (a warp-local envelope field,
defaulting to a root lane), so its serialized wire format never references this
experimental module; the entitlement enforcement lives in the satellite. Gradle
KMP has no compile-time-optional dependency, so the arrow is a real satellite
depending on both, matching warp's existing Phase-2 layout.

And warp is not a sketch to bolt onto: `:kuilt-warp` is shipped code with a
measured Phase-1 foundation (the consistent-hash ring, `WorkQueue`, `Results`,
the coordinated path) and a Phase-2 program of satellite modules already
underway (`:kuilt-warp-planning`, `:kuilt-warp-ml`, `:kuilt-warp-compiler`,
`:kuilt-warp-runtime`; see `docs/warp-roadmap.md`). What warp's whole program
lacks — at any phase — is a notion of *fairness*. `:kuilt-heddle` slots into
that real program as the missing apportionment layer (§14), while remaining
independently consumable. Same experimental posture as the warp satellites:
out of `:kuilt-bom` and `kuilt.publish` until it earns its way in.

## Vocabulary — source concept → kuilt concept

| Source spec | This spec | Notes |
|---|---|---|
| replica / physical authority shard | a **peer**: `ReplicaId` (CRDT layer) / `PeerId` (Seam layer) | The source's shard-vs-replica split collapses: in kuilt every peer on the `Seam` is both a replica of the state and an authority holder. One id. |
| event-set CRDT, map-union merge | delta-state `Quilted` lattices replicated by `Quilter` | The source itself permits this substitution; kuilt's native representation *is* the substitution. §12 says what it changes. |
| `issued(e)` / `returned(e)` / `spent(e)` | three `GCounter`s per attachment generation | per-origin, monotonic, merge = per-slot max |
| entitlement lot / escrow | per-`(path, replica)` **holdings** derived from the ledger | no lot objects; §4 |
| owner / fencing epoch | Raft term via `:kuilt-raft` (control plane only) | §9 |
| demand advertisement, demand epoch | `EphemeralMap` slot value, `EphemeralEntry.clock` | soft state by construction; §6 |
| deterministic simulator | the canonical kuilt test harnesses | §13; never hand-rolled |
| service receipt | the completion's `spent` increments along the captured path | §4.4 — idempotence handled differently, honestly noted |

---

## 1. Two hierarchies, kept separate

**The logical scheduling tree** is data, not schema — groups created, closed,
and reparented at runtime, any depth, no fixed level names:

```text
root
├── tenant-a
│   ├── interactive
│   └── batch
└── tenant-b
```

Each internal group apportions entitlement among its **immediate children
only**. A parent never reaches through a child to a grandchild, and never
sees a descendant's queues, membership, or internal topology — only the
per-edge aggregate summary (§4.5).

**The physical topology** is simply the `Seam`: the set of peers in the
session, over whatever fabric (or `CompositeLoom` bond of fabrics) carries it.
A peer may hold entitlement for many unrelated logical groups; one logical
group's entitlement may sit in many peers' pockets. The two hierarchies never
determine each other: nothing in the logical tree may encode peer identity,
and nothing in the ledger may assume the tree's shape mirrors the roster.

Kuilt disciplines that apply unchanged: `Seam.incoming` is single-collection —
whatever type owns the seam (a `HeddleNode`, or the enclosing `WarpNode`)
collects it once and fans out; scope, clock, and random are injected, never
defaulted.

## 2. Fundamental types

Opaque, value-equal, totally ordered, stably serialized identifiers — kuilt's
usual `@JvmInline value class` idiom:

```kotlin
public value class GroupId(public val value: String)
public value class AttachmentId(public val value: String)   // one per generation
public value class ReservationId(public val value: String)
```

Service units are `Long`, non-negative, **overflow-checked** (fail fast, never
wrap). Weights are positive integer ratios; comparisons use
cross-multiplication with overflow detection. **No floating point anywhere a
replica could disagree** — the scheduler's ordering must be bit-identical on
JVM, Native, and wasmJs.

An **entitlement path** is the list of attachment generations from the root to
a group:

```kotlin
public value class EntitlementPath(public val edges: List<AttachmentId>)
```

The empty path is root authority. Delegation appends exactly one active edge;
release removes exactly the final edge; a completion is charged to the path
*captured at reservation time*, whatever the tree looks like later. Paths with
different edge lists are different accounting lineages and are never pooled.

An honest note, up front: the path is **structural, not a payload**. kuilt has
no CRDT that carries per-unit lineage, and it should not — the path is *which
counters you are reading*, encoded in the ledger's keys, not a field inside a
counter. That framing is what makes §4 work.

## 3. Attachments: fairness state lives on the edge

Fairness belongs to the parent–child *relationship*, not to the child. That
relationship is an **immutable attachment generation**:

```kotlin
public class AttachmentRecord(
    public val id: AttachmentId,
    public val parent: GroupId,
    public val child: GroupId,
    public val weight: Long,               // > 0
    public val initialVirtualTime: VirtualTime,
)
```

Changing anything fairness-significant — weight, parent, a fairness reset —
**creates a new generation**; nothing is mutated in place. Old generations
keep their history forever (immutable, queryable, never re-parented).

This is the same instinct kuilt already applies everywhere: state is a value
in a lattice, and change is a new element, not a mutation.

## 4. The entitlement ledger — `BoundedCounter`, grown a path dimension

This is the heart of the module, and the strongest recognition in the whole
mapping. Read `BoundedCounter` first: a budget seeded per replica; a
`transfers` matrix where **slot `(s, r)` is written exclusively by `s`**; a
`spent` counter per replica; `quota(r)` derived; `trySpend` checks locally and
returns a `Patch` or `null` — conservation *by construction*, coordination
only for rebalancing.

The entitlement ledger is exactly that design with one added dimension: the
counters are indexed by **attachment generation**, and quota lives at a
**(path, replica)** pair instead of a bare replica.

Grasp what that single move buys before reading the mechanics. The source
spec spends whole sections detecting double-spend at runtime — a lot consumed
twice, quarantine propagating down the tree, every replica converging on the
same explicit invalid state. In this representation the problem largely
evaporates, because every subtraction from a peer's holdings reads a slot
**only that peer writes**: *the invariant the lot model must detect at runtime
is the invariant `BoundedCounter` holds by construction.* What survives of
double-spend detection is a narrow integrity check (§4.6) plus one fencing
question that was never a CRDT problem to begin with (§9).

### 4.1 Per-edge accounting

For each attachment generation `e`, three monotonic per-origin counters:

```text
issued(e)     cumulative entitlement delegated across e       (GCounter)
returned(e)   cumulative unused entitlement returned across e (GCounter)
spent(e)      cumulative completed service charged through e  (GCounter)
```

Derived, per edge:

```text
outstanding(e)      = issued(e) − returned(e) − spent(e)
committedService(e) = spent(e) + outstanding(e) = issued(e) − returned(e)
```

The core per-edge invariant:

```text
0 ≤ spent(e) + returned(e) ≤ issued(e)
```

The replicated payloads stay monotone (each counter only grows; merge is
per-slot max) even though the derived `outstanding` may fall. This is why
`returned` is its **own** `GCounter` and not a `PNCounter` decrement:
monotonicity of every replicated component is a safety property here, not a
style choice.

### 4.2 Holdings: who may spend what, where

A peer's spendable authority at path `P` is derived, never stored:

```text
holdings(P, r) =
      credited-into-P-for-r      (delegations across P's final edge to r,
                                   plus peer-to-peer transfers at P into r)
    − delegated-out-by-r          (r's grants across P's child edges)
    − returned-by-r-at-P
    − spent-by-r-at-P
```

Every subtraction reads a slot **only `r` writes** — the `BoundedCounter`
exclusive-slot discipline, per path. So the local safety check is exactly
`trySpend`'s shape: mutators return `Patch<EntitlementLedger>?`, `null` when
`holdings(P, r)` is insufficient, and the caller's state is untouched either
way. No coordination is ever needed to *spend*; coordination is only ever
needed to *move* authority toward where demand is — and even that is the
`BoundedCounterTransferCoordinator` request/grant pattern from
`:kuilt-quilter`, reused.

Note what the single-lattice design dissolves: there is no "cross-counter
move" between a parent's counter object and a child's. Delegation and release
are balanced increment pairs *within one lattice* (§4.3), applied as one
`Patch` — atomic at the writer by construction, with no two-object update to
keep consistent and no seam for conservation to leak through.

### 4.3 The ledger's mutators

All conserve by construction — each is a balanced pair of increments:

- **mint** — root-path credit. Only the control plane may mint (§9).
- **delegate(e, amount)** — debit parent-path holdings, credit
  `issued(e)`-into-child-path. Requires `e` **ACTIVE**.
- **release(e, amount)** — debit child-path holdings, credit `returned(e)`,
  restoring parent-path holdings. Allowed while `e` is ACTIVE or CLOSING.
  (Named `release`, not `return` — Kotlin keyword.)
- **transfer(P, from, to, amount)** — move holdings at the *same* path between
  peers; the lineage never changes, only the pocket. This is verbatim
  `BoundedCounter.transfer`: append to `from`'s own matrix row.
- **reserve / complete / cancel** — leaf-only; §4.4.

### 4.4 Reservations, completions, and where idempotence really comes from

Work at a leaf must reserve before it runs:

```text
reserve maximumCost   →  local earmark against holdings(leafPath, self)
execute
complete(actualCost)  →  spent += actualCost on EVERY edge of the captured
                          path; earmark released; remainder back in holdings
cancel                →  complete with actualCost = 0
```

`actualCost > maximumCost` is rejected as an invariant violation, never
recorded. Unknown-cost work reserves in renewable quanta.

Because generations are immutable, a completion that arrives after its edge
closed still charges the *old* generation — historical service follows the
authority under which the work was admitted, never the topology visible when
the receipt merges. A later reshape mints a new `AttachmentId`, so there is no
ambiguity and no rewrite of history.

Two honest notes where this differs from the source's event-set model:

- **Reservations are local state, not replicated state.** Only the owning
  peer may complete or cancel its reservation, so the earmark needs no
  replication for safety — reserved-but-unspent entitlement is simply still
  `outstanding` on the leaf edge, which the accounting already charges. A
  crashed peer strands its earmarks exactly as the source specifies: nobody
  else may reclaim them without the explicit recovery path (§8.1, §9).
- **Idempotence moves from event identity to lattice + single-writer.** In
  the source, duplicate receipt delivery is absorbed by set-union of an
  `EventId`. In delta-state form: a `GCounter` delta carries the *resulting*
  slot value, so **duplicate or reordered delivery of the same patch is
  idempotent by the lattice laws** (`piece` is idempotent) — kuilt's whole
  reason for using join-semilattices under lossy fabrics. Duplicate
  *generation* (charging one completion twice) is prevented one layer up: the
  spend slots for peer `r` are written only by `r`, and `r` mints exactly one
  spend per `ReservationId` from its local reservation table. The property to
  test is therefore "deliver the completion patch N times, history rises
  once" — same observable guarantee, different mechanism, and the mechanism
  is the one kuilt already trusts everywhere else.

### 4.5 The parent-facing summary, and why the homomorphism is free

A parent scheduling its children consumes **only** this, per immediate edge:

```kotlin
public class EdgeSummary(
    public val attachment: AttachmentId,
    public val issued: Long,
    public val returned: Long,
    public val spent: Long,
) {
    public val outstanding: Long get() = issued - returned - spent
    public val committedService: Long get() = issued - returned
}
```

No descendant queues, identities, ordering, placement, or leaf receipts —
information hiding is a hard interface boundary, not a convention. It falls
out structurally: the parent holds its child edges' keys and reads their
summaries; the subtree below each child is simply not in the parent's view.

The source demands the projection be a merge homomorphism
(`project(merge(A,B)) = merge(project(A), project(B))`) and proven as a
property. In the kuilt representation this is nearly definitional: the ledger
merges per-edge, componentwise (the `LatticeProduct` idiom), and projection is
restriction to one edge's components — restriction of a componentwise join
commutes with the join. It is still pinned by a property test (§13); it is
just no longer a design risk.

### 4.6 Conflicts are surfaced, never resolved by timestamp

With no Byzantine peers (out of scope, as in the source), overspend cannot
arise from honest concurrency — the exclusive-slot discipline removes the
race. What merge *can* reveal is an integrity violation: an edge whose
aggregate `leafSpent + rollupSpent + returned > issued`, or a lineage whose
holdings derive **persistently** negative on a causally-complete state (the
real overspend signal). The required behavior is the source's, restated:

- every replica derives the **same** conflict report from the same state,
- the affected lineage contributes **no** spendable holdings (quarantine is
  transitive down the path),
- nothing is ever silently resolved by timestamp or arrival order.

This is a `validate(): List<LedgerConflict>` derivation over the merged state
— structured data — in the spirit of the diagnostics discipline the repo already
enforces. **Honest scope note:** because `piece` is a `GCounter` max-join, an
*equivocated* one-writer slot (a non-Byzantine peer forking its own history into
two values from one prefix) converges silently to the larger value — the "two
different values for one slot" fault is **not** recoverable from merged state
alone without per-slot version dots, which this design deliberately does not pay
for under the non-Byzantine model. Feasibility-*violating* equivocation is still
caught (it drives a persistently-negative holdings derivation); pure feasible
equivocation is out of scope. See [`heddle-ledger-design.md`](heddle-ledger-design.md)
§`validate` for the concrete checks and the self-justifying-patch mechanism that
keeps them from false-firing on honest partial delivery.

## 5. Topology as a lattice: lifecycle, strict reparenting, and `MovableTree`

### 5.1 The lifecycle lattice

Attachment records are immutable and live in a grow-only set. Each
attachment's lifecycle is a four-point chain:

```text
PREPARED < ACTIVE < CLOSING < RETIRED
```

with **join = max**. That one line delivers the source's key merge rule for
free: *closure dominates activation* — a replica that has observed CLOSING can
merge with any laggard and never regress to issuing. The lifecycle register is
a tiny new `Quilted` (an ordinal max-register); kuilt has no off-the-shelf
type for it, and it is ~30 lines.

Semantics per state, unchanged from the source: PREPARED — edge exists, no
entitlement may cross; ACTIVE — delegation allowed; CLOSING — no new
delegation, spend/release still allowed; RETIRED — reached only when
`outstanding(e) == 0`, nothing crosses ever again.

### 5.2 Validity is derived, not enforced by merge

The active tree must satisfy the usual sanity set (one active inbound edge
per group, no cycles, leaves have no active outbound edges, the root has no
inbound edge). Because concurrent topology writes can violate these, validity
is a **derived classification with explicit conflict reports** — e.g. a child
with two ACTIVE inbound generations is a reported topology conflict, and *no
new entitlement may be delegated across either* until the control plane
resolves it. Never last-writer-wins on a parent pointer. (kuilt's `LWWMap`
would be the tempting dishonest mapping here; it is explicitly rejected, in
agreement with the source.)

### 5.3 Reconfiguration: strict generation-and-drain

All topology changes are low-frequency control-plane operations (§9) built
from one primitive — *close, drain, retire, re-generate*:

- **Create** a group under a parent: new record, new PREPARED edge,
  `initialVirtualTime` = parent's current virtual time (§7.2), ACTIVATE. No
  ancestor above the parent needs to know. Creating a child mints nothing.
- **Close** an edge: CLOSING → let reservations finish, entitlement return →
  `outstanding == 0` → RETIRED. History stays queryable forever.
- **Reparent** `C` from `A` to `B`: prepare `B→C`, close-drain-retire `A→C`,
  then activate `B→C` neutrally at `B`'s current virtual time. Old history
  stays under `A`; `C` starts prospectively neutral under `B`.
- **Change weight** = new generation, same drain discipline.
- **Insert an intermediate group** = compose the above.

### 5.4 `MovableTree`: shipped, and deliberately not used in v1

The source cites the replicated-tree move algorithm as *future* work. kuilt
already ships it — `MovableTree` in `:kuilt-crdt` implements exactly that
op-log replay with cycle-skip. It is still the wrong tool for **v1** of this
module, for the same reason the source gives: `MovableTree` *silently
resolves* conflicting concurrent moves by `(timestamp, replica)` priority (the
lower-priority op is skipped), which is perfect for a document tree and wrong
for edges that carry conserved entitlement lineage — here a conflicting move
must be *surfaced and drained*, not auto-resolved, because a silent structural
move would strand entitlement on a path that no longer exists. The honest
position: v1 uses strict generations; a later version may layer entitlement
drain *on top of* `MovableTree`'s convergence, and kuilt is unusually well
placed to try because the hard CRDT is already in the zoo, GC'd by `Quilter`'s
causal-stability machinery.

## 6. Demand is an `EphemeralMap`, and that is not an accident

A child advertises how much *more* outstanding entitlement it could usefully
hold:

```kotlin
public class Demand(
    public val targetOutstanding: Long,     // total unspent it would find useful
    public val maximumUsefulGrant: Long,
)
```

Each peer publishes its per-edge demand view into **its own slot** of an
`EphemeralMap<Map<AttachmentId, Demand>>`, tracked by `EphemeralMapTracker`:
per-slot monotonic clocks give the source's "demand epochs"; TTL eviction by
*local receive time* gives expiry without trusting any cross-peer clock; a
departed peer's demand vanishes with its slot. The parent folds the live
slots when ranking.

The type does the doctrine's work for it: `EphemeralMap` is kuilt's one
explicitly *non-durable* CRDT — presence, not ledger. Demand may be stale,
duplicated, or lost, and the worst outcome is entitlement temporarily parked
in the wrong pocket: a stale advertisement can misplace capacity, but it can
never authorize a spend, because spends check `holdings`, which is blind to
demand. **Demand is advisory; entitlement is authority; they never merge** —
here that is not a rule to remember but a type distinction (`EphemeralMap` vs
the ledger) that cannot be quietly violated.

## 7. The policy: EEVDF over edge summaries

The reference policy apportions entitlement quanta among immediate children.
It is EEVDF-*inspired* (Stoica & Abdel-Wahab's lag/eligibility/virtual-
deadline distinction; the insight that a group is both schedulable and a
scheduler) — not a reproduction of any kernel's internals.

It is a **pure function**. Inputs: the immediate children's `EdgeSummary`s,
the folded live `Demand`, immutable attachment policy (weight,
`initialVirtualTime`), scheduler-local wake state, and locally available
holdings. It inspects nothing else — no global queues, no descendants. Purity
is what makes it testable at virtual time and safe to run divergently on
partitioned peers: a bad local decision misplaces entitlement; it cannot
create any.

### 7.1 Virtual service

For active edge `e` with weight `w(e)` and baseline `b(e) = initialVirtualTime`:

```text
v_raw(e) = b(e) + committedService(e) / w(e)
         = b(e) + (issued(e) − returned(e)) / w(e)
```

Because the numerator is *committed* service, a grant advances the child's
virtual position the moment it is issued — hoarding is charged (the
over-allocation loop the source warns about is closed), and `release` walks
the child back and restores eligibility. Comparisons are exact: integer
cross-multiplication with overflow checks. Never per-child independent
rounding.

### 7.2 Neutral creation and no idle credit

- **Neutral creation:** a new generation's `initialVirtualTime` is the
  parent's current virtual time — its **front**, defined below — recorded
  immutably in the record. A newborn starts level with its siblings — no
  credit for the parent's whole past.

  The parent's virtual time `V = Σ w·ev / Σ w` is a rational and almost never
  integral, while `initialVirtualTime` is a `Long`, so creation must round.
  **The rule is the exact ceiling — `initialVirtualTime = ⌈V⌉`** — and the
  direction is normative, not a matter of taste. Flooring would seat the
  newborn *behind* the front, and lower virtual service reads as "has had less
  than its share", so the newborn would be eligible ahead of every sibling and
  take the next grants outright: a sliver of lifetime credit, which §10.5
  forbids, accrued systematically by any subtree that churns generations. The
  ceiling can only ever give up a fraction of a service unit, never claim one,
  and the deviation is bounded by `0 ≤ ⌈V⌉ − V < 1` virtual unit. It is exact
  and deterministic, so every replica re-deriving a record from the same `V`
  lands on the same `Long`. `AttachmentRecord.neutral` / `neutralInitialVirtualTime`
  are the single implementation of the rule.
- **No unlimited idle credit:** when a child goes from not-demanding to
  demanding, a local wake offset clamps it forward:

  ```text
  virtualOffset = max(0, parentVirtualTime − v_raw(e) − sleeperCredit / w(e))
  effectiveVirtualService(e) = v_raw(e) + virtualOffset
  ```

  Default `sleeperCredit = 0`. `parentVirtualTime` here is the front defined
  just below, taken with the waker itself — and every sibling waking in the
  same round — excluded. The clamp fires only on a transition this peer has
  actually *observed*: an edge seen for the first time is recorded as it
  stands, never clamped on arrival, because a first sighting is not evidence
  that anyone slept. The offset is scheduler-local policy state — deliberately
  *not* replicated, so divergent offsets can reorder locally but can never
  touch conservation.
- **Which children define the front.** Both bullets above turn on one phrase,
  "the parent's current virtual time", and it means one thing: the front of
  the set the joiner is about to compete in. Concretely, the weighted mean of
  `effectiveVirtualService` over the children that are **demanding right
  now** — `additionalNeed = targetOutstanding − outstanding > 0` — and *not*
  over every ACTIVE child. One rule serves both kinds of joiner: a newborn
  being seated, and a sleeper being clamped forward.

  Taking the mean over every ACTIVE child instead is wrong in **both**
  directions, which is why the set is normative rather than a detail (#1688).
  Measured at equal weights and quantum 1: with one sibling idle at the origin
  and a runner at virtual service 20, the all-ACTIVE mean seats a newborn at
  10, from where it takes 15 of the next 20 grants against a fair share of 10
  — the lifetime credit §10.5 forbids, and precisely the idle credit the clamp
  above denies the idler itself. Mirror the fixture — a satisfied sibling
  parked *ahead* at 40, the runner at 10 — and the same mean seats the newborn
  at 25, where it takes 7 of 30 against a fair 15. Seating at the mean over
  the demanding set lands on the fair share in both.

  The demanding predicate is §7.3 step 1's, with the quantum trims (holdings,
  `maximumUsefulGrant`, the caps) dropped. The trims decide who can be
  *served this round on this peer*, which is a different question from who is
  competing — and a peer with nothing left to delegate must still be able to
  answer the second, because it may be the one creating the generation. So
  §7.3 step 2 and the seating front share one arithmetic over two deliberately
  different sets, and agree only when no trim binds — two sets, not one
  number.

  **The joiner, and any co-joiner, is excluded from the front by name.** A
  newborn is excluded for free — it is not an edge yet — but a waker is
  already ACTIVE and already demanding by the time its clamp is computed, so
  unless it is named it drags the front back toward its own stale position and
  banks the credit anyway. Two siblings waking in the same round would average
  each other's staleness into the front and both keep it, so all of a round's
  wakers are excluded together, not each merely from its own.

  **When nothing in the surviving set is demanding, the front is the
  *maximum* effective virtual service in that set, not their mean.** There is
  no competing set to come level with, so the fallback takes the bound that
  can only ever give up: §10.5 is one-directional — credit is forbidden, a
  sliver of penalty is merely undesirable — and the maximum is never below the
  mean of the same set, so it can penalise a joiner but never credit one. The
  consequence is worth stating plainly rather than discovering: an idle child
  sitting *ahead* of everyone pins the front at its own position for every
  newborn seated while nothing competes.

  **An empty surviving set has no front at all, and the answer is not zero.**
  When no active child survives the exclusion the front is undefined and must
  be reported as such — `null`, never quietly the origin. Two different
  situations wear that one face: the legitimate **first** generation under a
  parent, whose origin seat is genuinely correct, and a peer whose view has
  simply not applied the siblings yet, for which the origin seat is
  permanently wrong — the seat is frozen into the committed record, and every
  peer then applies the same lifetime credit forever. A caller must therefore
  fence before treating an undefined front as the origin: confirm leader
  authority (§9 #3 `readIndex()`), confirm this peer's applied prefix has
  caught up to the fenced index, and fail closed — nothing written, retryable
  — if either check fails. `GovernedHeddleNode.prepareNeutral` is that fenced
  compute-and-record; a node with no control plane cannot tell the two apart
  at all, and must not seat at the origin unless it *knows* the generation is
  the first. The fence closes the stale-*records* case only: a view that has
  merged some siblings but not all computes a plausible front over those and
  freezes it, with nothing anomalous to see (#1713).

### 7.3 Selection

Per allocation round at one parent, on one peer:

1. **Candidates:** active edges with
   `additionalNeed = max(0, targetOutstanding − outstanding(e)) > 0`, quantum
   `q = min(configuredQuantum, additionalNeed, maximumUsefulGrant,
   localHoldings, perChildOutstandingCap)`, dropping `q == 0`.
2. **Parent virtual time:** the weighted mean
   `V = Σ w(e)·effectiveVirtualService(e) / Σ w(e)` over the fixed candidate
   set — the *trimmed* one from step 1. This is the same arithmetic as §7.2's
   seating front over a deliberately different set: the front drops the
   quantum trims, this step keeps them, so the two values coincide only when
   no trim binds.
3. **Eligibility:** `effectiveVirtualService(e) ≤ V`. This set is never
   empty, and that is a **theorem, not an expectation** (#1737): `V` is the
   weighted mean of the *same* candidate set over strictly positive weights,
   so it is never below that set's minimum — and no rounding can eat the
   margin, because the comparison is exact integer cross-multiplication that
   *throws* on overflow rather than returning a wrong order (§7.1). An empty
   set could therefore only mean step 2's mean was taken over a different set
   than this filter, or a non-positive weight was admitted; either leaves the
   round's whole ordering untrustworthy, so the implementation **asserts and
   fails loudly** rather than silently substituting the minimum and
   scheduling on regardless.
4. **Deadline:** among eligible candidates pick minimum
   `(effectiveVirtualService(e) + q/w(e), attachmentId)` — the stable id is
   the deterministic tie-break.
5. **Delegate `q`**, apply the patch locally *before* the next selection;
   repeat until holdings, demand, caps, or a per-cycle limit stop the loop.

Expected behavior (and the acceptance tests behind it): continuously
demanding siblings converge on the weight ratio in committed service; a
hoarder is throttled; a returner recovers.

Production extensions (latency classes, minimum grants, bounded sleeper
credit, entitlement recall) are policy-layer only and must not touch
conservation or path accounting.

## 8. Partitions, crashes, and the fairness-error bound

### 8.1 Partition ≠ crash

`:kuilt-liveness` supplies the signal (`HeartbeatPartitionDetector` per peer,
`PartitionEvent`s), and the semantics are the source's, verbatim in kuilt
terms:

- A **partitioned** peer keeps scheduling within its holdings; nobody may
  reclaim what they merely cannot see. `Quilter` anti-entropy reconciles the
  ledgers on heal — convergence is the lattice laws, already load-bearing
  everywhere in kuilt.
- A **crashed** peer's holdings and earmarks are stranded. v1 ships **no
  automatic reclamation** — reclaiming requires fencing (§9), and a wrong
  reclaim is an overspend, the one unforgivable failure. Stranding is *safe*:
  the quota just sits unusable in the crashed peer's slots. This deliberately
  trades availability for safety when authority is *lost* rather than
  disconnected.

### 8.2 The bound

At any parent `p`, the temporary fairness error is bounded by the entitlement
that unreconciled peers can independently steer among `p`'s children, plus
quantum discretization:

```text
error(p) ≤ Σ over unreconciled peers s of B(p, s) + discretization
```

where `B(p, s)` is capped by configuration: maximum holdings per peer,
per-child outstanding caps, maximum quantum and transfer sizes. With `n`
peers each capped at `E`, the coarse bound is `n·E`; a leaf path's end-to-end
bound is the sum along its edges. The module must expose the pieces of this
bound as derived metrics — a configured worst case, a state-dependent current
value (from live outstanding holdings), and the observed deviation in
simulation — and must not claim tighter than it proves.

This is the same arithmetic the `BoundedCounterEqualizerConfig` fair-share
logic (`bound / liveN`) already does at depth zero. The bound is not a new
idea in kuilt; it is the equalizer's idea with a tree over it.

## 9. The consensus seam — the embroidery

In warp's language: everything above is **cloth** — coordination-free by
construction, woven from monotone state. Exactly three questions are
non-monotone here, and they are the only places `:kuilt-raft` appears. All
three thread the *same needle* warp's coordinated path already threads, so
the heddle introduces **no new category of coordination**:

1. **Root mint.** Creating entitlement is the one non-conserving operation;
   a mint proposal goes through the Raft log so two halves of a split can
   never both mint against the same supply.
2. **Topology serialization.** Overlapping reconfigurations (two peers
   concurrently reparenting the same child) are serialized as log entries;
   the log order is the serialization, and the loser surfaces as a reported
   conflict, never a timestamp resolution. Non-overlapping subtree operations
   don't contend, and — the important half — **scheduling and spending never
   wait on this log** at any frequency.
3. **Fencing / recovery.** The source's abstract "owner epoch" is a Raft
   term; a control-plane operation that revokes a crashed peer's holdings is
   proposed by the leader after a `readIndex()` quorum fence — the same
   deposed-leader-cannot-pass-the-fence mechanism `WarpNode`'s coordinated
   path already uses. v1 defines the seam and ships only mint + topology;
   reclamation stays an explicit later feature.

**The log-known roster** (`Enroll`/`Depart`, `EnrolledRoster`) is the membership
prerequisite of (3), not a fourth non-monotone question. A barrier that waits for
*every participant* to answer quantifies over a set, and the data-plane roster is
**open** — seam-derived (visible peers ∪ self ∪ flagged-unreachable), so it moves
with no log record and two peers can legitimately disagree at one instant.
`Enroll`/`Depart` commit through the same log and fold, in index order, into an
`EnrolledRoster` held *beside* the control-plane projection; `enrolledAt(index)`
answers "who was enrolled as of this commit index", identically on every peer that
applied that prefix. It is deliberately **independent** of both the Raft voter set
(voters are consensus members, `NodeId`s; the quantifier is over ledger *writers*,
`ReplicaId`s — a learner or a non-voting data-plane peer authors slots too) and the
seam roster. Enrolling is open to any proposer because it only ever *enlarges* the
set (a mistake costs a barrier's liveness, never its safety); departing is
**self-service** because it *shrinks* it, and "this replica will never author
another slot" is a promise about the future that only that replica can make.
Departing therefore reclaims nothing — an absent peer's authority is (3)'s problem,
and its share stays stranded per §8.1. See
`docs/heddle-ledger-relocation-design.md` §6.2.

**Rejoin is membership, so enrollment drives it** (#1652). A
`HeartbeatPartitionDetector` that reaches `PeerLost` is terminal — it closes its
event channel and its heartbeat loop returns — so a peer declared lost would stay
in `unreachable` forever and never be watched again. Reappearing on the *seam* is
deliberately not enough to justify a fresh detector: the seam roster is the open
one, and churning a live detector on every wobble would discard the
unresponsive→recovered transition it is about to report. A committed `Enroll` is
the peer declaring itself a participant again, agreed through the log, so that is
what re-attaches its detector and clears it from `unreachable` — and only for a
peer in the terminal Lost state. A **departure** fires nothing: a departed peer's
entitlement is stranded exactly like a crashed peer's, so it must keep counting
toward the §8.2 bound. Re-monitoring reclaims no entitlement; that is still (3).

**Bootstrap: paired entry points, no nullable consensus.** A `RaftNode?`
parameter defaulting to `null` would gate a functional code path on an
optional — the repo's "Optional ≠ tuning" rule forbids it. Following
`:kuilt-game`'s precedent (`gameNode` vs `gameHost`/`gameJoin` — two
deliberate front doors, not one door with a nullable knob), the module offers
two bootstraps:

- **`heddleStatic`** — a fixed roster and a pre-partitioned mint supplied at
  bootstrap. No runtime mint; topology changes are local strict-drain
  operations, and any overlapping reshape simply surfaces as a conflict for
  the operator to drain. The right shape for small fixed rosters and tests.
- **`heddleGoverned`** — a required `RaftNode`; mint, reshape serialization,
  and (later) fencing ride the log.

Each takes exactly the dependencies its path needs, both required.

## 10. Normative invariants

Carried over from the source intact; each is a named property test (§13).

1. **Conservation.** Globally, at every step:
   `minted = Σ holdings + Σ earmarked + Σ spent`. Every mutator except mint
   balances exactly.
2. **Monotonicity.** Every replicated component only grows; merge is a join;
   the lattice laws hold (`piece` idempotent, commutative, associative).
   `outstanding` may fall but is always derived, never stored.
3. **Per-edge safety.** `0 ≤ spent(e) + returned(e) ≤ issued(e)`.
4. **Path-relative accounting.** A completion charges every edge of the path
   captured at reservation; history never moves to a newer generation.
5. **Neutral attachment initialization.** A new generation starts at the
   parent's current virtual time — the front over its *demanding* children,
   not over all its active ones (§7.2) — never with lifetime credit. Where
   that virtual time is fractional, the record takes its **exact ceiling**
   (§7.2): rounding away from credit, never toward it.
6. **No unlimited idle credit.** Default sleeper credit is zero; waking
   clamps forward.
7. **Partition safety.** No peer ever spends beyond `holdings(P, self)`;
   post-heal convergence restores a single agreed ledger.
8. **Projection homomorphism.**
   `project(merge(A,B), e) = merge(project(A,e), project(B,e))`.
9. **Bounded fairness error.** `error(p) ≤ Σ B(p, s) + discretization`,
   measured in simulation against the configured caps.
10. **Closure dominance.** A replica that observed CLOSING never again
    delegates across that generation, regardless of merge order.
11. **Conflicts surface.** Integrity violations converge to the same explicit
    report everywhere and quarantine their lineage; nothing is resolved by
    timestamp.
12. **Overflow rejection.** Arithmetic that would exceed `Long` fails
    deterministically, never wraps.
13. **Consensus only at the embroidery.** Mint, overlapping reshape, and
    fencing use `:kuilt-raft`; nothing else coordinates, at any frequency.

## 11. Public surface sketch

`explicitApi`-clean; the house mutator idiom (`Patch<S>?`, caller unchanged on
`null`); required injection of scope/clock/random. Sketch, not signature-final:

```kotlin
// ── the replicated state ────────────────────────────────────────────────
public class EntitlementLedger private constructor(/* … */) :
    Quilted<EntitlementLedger> {

    // topology (control-plane; normally invoked via the consensus adapter)
    public fun prepare(record: AttachmentRecord): Patch<EntitlementLedger>?
    public fun activate(edge: AttachmentId): Patch<EntitlementLedger>?
    public fun close(edge: AttachmentId): Patch<EntitlementLedger>?
    public fun retire(edge: AttachmentId): Patch<EntitlementLedger>?

    // entitlement flow (data-plane; local check, null = insufficient holdings)
    public fun delegate(
        replica: ReplicaId, edge: AttachmentId, amount: Long,
    ): Patch<EntitlementLedger>?
    public fun release(
        replica: ReplicaId, edge: AttachmentId, amount: Long,
    ): Patch<EntitlementLedger>?
    public fun transfer(
        path: EntitlementPath, from: ReplicaId, to: ReplicaId, amount: Long,
    ): Patch<EntitlementLedger>?
    public fun spend(
        replica: ReplicaId, path: EntitlementPath, amount: Long,
    ): Patch<EntitlementLedger>?

    // derived views (pure)
    public fun holdings(path: EntitlementPath, replica: ReplicaId): Long
    public fun edge(id: AttachmentId): EdgeSummary?
    public fun activeChildren(parent: GroupId): List<EdgeSummary>
    public fun validate(): List<LedgerConflict>

    override fun piece(other: EntitlementLedger): EntitlementLedger

    public companion object {
        public fun bootstrap(
            root: GroupId, mint: Map<ReplicaId, Long>,
        ): EntitlementLedger
    }
}

// ── the pure policy ─────────────────────────────────────────────────────
public class HeddlePolicy(private val config: HeddleConfig) {
    /** One EEVDF selection at one parent. Pure; null = nothing to allocate. */
    public fun pick(
        parent: GroupId,
        children: List<EdgeSummary>,
        demand: Map<AttachmentId, Demand>,
        wake: WakeState,
        localHoldings: Long,
    ): Allocation?
}

// ── the node: ledger + Quilter + demand + liveness, over a Seam ─────────
public class HeddleNode internal constructor(/* … */) {
    public val selfId: ReplicaId
    public val ledger: StateFlow<EntitlementLedger>
    public suspend fun reserve(leaf: GroupId, maximumCost: Long): ReservationId?
    public suspend fun complete(id: ReservationId, actualCost: Long)
    public suspend fun cancel(id: ReservationId)
    public fun advertise(edge: AttachmentId, demand: Demand)
}

// ── paired bootstraps (§9) — no nullable consensus parameter ────────────
public fun CoroutineScope.heddleStatic(
    seam: Seam,
    selfId: ReplicaId,
    mint: Map<ReplicaId, Long>,       // pre-partitioned root supply
    clock: () -> Instant,             // required — time is a dependency
    config: HeddleConfig,
): HeddleNode

public fun CoroutineScope.heddleGoverned(
    seam: Seam,
    selfId: ReplicaId,
    raft: RaftNode,                   // required — the control plane
    clock: () -> Instant,
    config: HeddleConfig,
): HeddleNode
```

`HeddleConfig` carries the quanta and caps of §8.2 plus an injectable
`Random`; internals follow the house thread-safety rules (atomicfu lock, no
`limitedParallelism(1)` confinement) and exception discipline
(`runCatchingCancellable` on best-effort fabric sends).

### 11.1 The fairness draft — a builder over the topology

Declaring the group tree by hand (`prepare`/`activate` per edge) is correct but
noisy. A small type-safe builder — the **draft**, in the loom's own word — is
pure sugar over those mutators: `group { }` is an internal node, `lane()` a leaf,
weights are relationships among siblings. It produces an immutable topology value
(a set of `AttachmentRecord`s plus the mint plan) that `heddleStatic`/
`heddleGoverned` bootstrap from.

```kotlin
val fairness = draft {
    group("acme", weight = 3) {
        lane("interactive", weight = 3)
        lane("batch", weight = 1)
    }
    group("hobby", weight = 1) { lane("default") }
}
```

Because a weight is a sibling relationship, the builder can only *create*
generations — there is no in-place weight mutation to express, so the DSL enforces
"change = new generation" (§3) by construction. It lowers onto a fluent runtime
API (`acme.lane("incident", weight = 5)`) which is what *dynamic* create/reparent/
close (§5.3) uses directly. The builder is the static-policy front door; the
fluent API is the runtime one; both are the same topology underneath. This is an
ergonomics layer, not a semantic addition — it changes nothing below the surface.

---

## 12. What was dropped from the source, and what is genuinely new

**Dropped: the immutable-lot event-set model** (the source's `EntitlementLot`,
`MintEvent`/`TransferEvent`/…, map-union merge, `VALID`/`PENDING`/`INVALID`/
`QUARANTINED` classification). The source itself designates it a clarity-first
reference semantics and explicitly permits a state-based bounded-counter
backend that preserves the observable invariants. kuilt's native idiom *is*
that backend — delta-state lattices over `Quilter` — so this spec makes the
counter form the reference and drops lots entirely. What the substitution
changes, honestly:

- *Duplicate delivery* idempotence comes from the lattice, not event ids
  (§4.4) — equivalent guarantee, different proof obligation.
- *Pending-missing-dependency* classification disappears: state-based joins
  have no dangling references, and `Causal`/`DotContext` handles causal
  delivery where a type needs it.
- *Double-spend detection* narrows to exclusive-slot integrity checking
  (§4.6) — same fault model (non-Byzantine), same convergent-conflict-report
  requirement, far less machinery. The lot model bought exactly one extra
  thing — double-spend detection under hostile ownership — which is out of
  scope here as it is in the source; if a future consumer genuinely needs it,
  the lot model returns as an opt-in backend and nothing above the ledger
  interface changes.
- `canonicalStateHash` for convergence testing is replaced by structural
  equality of merged states — kuilt CRDTs already define value equality and
  their tests already assert cross-order convergence with it.

**Dropped: the bespoke simulator** (the source's `SimulatedTransport`,
`step()`/`partition()`/`heal()` surface). Hand-rolling a cluster harness is
explicitly banned in this repo; §13 says what to use instead.

**Dropped: the generic interface catalog and the bibliography.** The
`TopologyStore`/`ReplicatedState`/`EntitlementStore`/… indirection collapses
into the three types of §11; the citations survive only as the handful of
inline name-drops the codebase already makes in KDoc. Concepts that stay
abstract behind the module boundary: persistence, serialization wire format,
authentication, multi-resource (vector) fairness.

**Genuinely new primitives kuilt would need** (no honest existing mapping —
these are the build):

1. `EntitlementLedger` — the path-indexed escrow lattice (§4). New CRDT,
   assembled from existing components (`GCounter` matrices, the
   `LatticeProduct` idiom, `BoundedCounter`'s slot discipline).
2. The lifecycle max-register (§5.1). Trivial but new.
3. `HeddlePolicy` — the EEVDF selection (§7). Pure new code.
4. Checked fixed-point / cross-multiplication comparison helpers (§2).

**Rejected mappings** (considered and found dishonest):

- `LWWMap`/`LWWRegister` for topology — timestamp resolution of reparenting
  is exactly what both the source and this spec forbid.
- `PNCounter` for accounting — decrements would surrender
  monotonicity-as-safety; `returned` as its own `GCounter` keeps it.
- `MovableTree` for v1 topology — shipped and tempting, but it silently
  resolves the conflicts this system must surface and drain (§5.4). Kept as
  the credible v2 path.
- `BoundedCounter` used *as-is* per attachment — its budget is fixed at
  `init` and it has no path lineage; forcing it would either smuggle in
  pooling-across-lineages (which the source forbids) or require an atomic
  cross-counter move between separate objects (a conservation seam the
  single-lattice design of §4 exists to avoid). Hence the generalization,
  not the reuse.

## 13. Testing

No new harness. The rules of the house apply and suffice:

- **Single-node/property tests** — plain `runTest` +
  `StandardTestDispatcher`, seeded `Random`, virtual time, bounded
  `advanceTimeBy`; `assertAll()` for multi-assert; no `test` prefix. The
  lattice laws for `EntitlementLedger` join the same property suite every
  `:kuilt-crdt` type already passes (idempotent / commutative / associative,
  cross-order convergence by structural equality).
- **Multi-peer, no consensus** — `InMemoryLoom` + one `Quilter` per peer:
  partition by withholding anti-entropy, heal by letting the cadence run.
  Loss/duplication/reordering are what the lattice laws are *for*; the tests
  assert the observable invariants of §10 across adversarial delivery.
- **Multi-peer with the control plane** — the canonical Raft harness only:
  `RaftSimulation`/`InMemoryRaftNetwork`/`raftRunTest` in-module patterns, or
  `MultiNodeRaftSim` from `:kuilt-raft-test` outside it. Tight timeouts,
  per-node seeded election RNG, `backgroundScope` children, bounded awaits —
  never `advanceUntilIdle()`, never a hand-rolled network.

The source's thirty acceptance tests survive as the named-invariant suite;
the distinct clusters: merge laws & convergence (§10.2), conservation &
overspend/overflow rejection (§10.1, §10.3, §10.12), duplicate-delivery
idempotence (§4.4), dynamic creation / arbitrary depth / neutral init / no
idle credit (§10.5–6), weighted fairness & hoarding & release (§7.3),
lifecycle & strict reparent & weight change (§5), partition safety & bound
measurement & stranded-peer (§8), projection homomorphism & information
locality (§4.5), stale demand (§6), topology conflict & quarantine (§4.6,
§5.2). A model-check comparing `HeddlePolicy` against a slow exact-rational
oracle on small sibling sets rounds it out.

The end-to-end scenario is the source's closing exercise, run through
`InMemoryLoom` + `Quilter` exactly as kuilt's existing multi-peer convergence
tests already run: `root → {tenant-a w1, tenant-b w1}`, dynamically add
`tenant-a → {interactive w3, batch w1}`, partition two peers, keep scheduling
on both sides, heal, converge, and report the measured bound against §8.2.

---

## 14. Warp: the first customer

This section is the point of the exercise. `:kuilt-warp` today is a real,
shipped foundation with a Phase-2 satellite program around it — and the whole
program has **no notion of fairness at all.**

### 14.1 What warp does today

- **`TaskRing`** answers *where*: consistent hashing over the roster maps
  each `TaskId` to one owner, deterministically, with ~zero coordination.
- **`WorkQueue`** (an `ORSet`) holds *everything pending*, unconditionally —
  anyone may add any amount of work at any time.
- **`Results`** (an `ORMap`) absorbs duplicate executions.
- **`BoundedCounterTransferCoordinator` + the equalizer** (`:kuilt-quilter`)
  smooth *load*: a flat budget, fair share = `bound / liveN`, surplus flows
  to the lowest.
- **The coordinated path** escalates exactly-once tasks through `RaftNode`
  behind a `readIndex()` quorum fence.

Every peer is equal; every task is equal; every submitter is equal. For one
app on its own grid, that is exactly right. It also means: no tenants, no
weights, no QoS classes, no protection of interactive work from a batch
flood, and nothing stopping one submitter from stuffing `WorkQueue` and
eating the room.

### 14.2 The recognition, one more time

In warp-vision's own device — name the role, reveal the primitive:

> **The equalizer *is* this scheduler at depth zero.** A root group, `n`
> equal-weight children (the peers), fair share `bound / liveN`, surplus
> flowing toward deficit. `:kuilt-heddle` is the load-smoother grown a tree:
> weights instead of equality, nesting instead of flatness, committed-service
> charging instead of instantaneous quota, and a proven partition bound
> instead of a heuristic.

So the augmentation is not a new engine bolted onto warp — it is warp's own
load-smoothing idea completed. Role by role:

| Warp role | Today | With `:kuilt-heddle` |
|---|---|---|
| `TaskRing` | consistent hashing = *placement* | **unchanged.** The ring keeps answering *where*; the heddle answers *how much, whose turn*. Placement and apportionment are orthogonal and compose. |
| equalizer / `BoundedCounterTransferCoordinator` | flat quota rebalancing | **subsumed & enriched** — becomes the depth-zero configuration of the entitlement flow; the transfer-request protocol is reused as the holdings-rebalancing path (§4.3 `transfer`). |
| `WorkQueue` | unconditional `add` | **gains an admission gate** — a task enters carrying a leaf-group tag, and execution draws on that leaf's entitlement. Backpressure and per-tenant bounds fall out. |
| `Results` | dedup backstop | **unchanged.** |
| coordinated path / embroidery | exactly-once via Raft | **unchanged, plus** mint and topology join it as the two new stitches (§9) — still grep-able, still the only consensus spends. |

### 14.3 New capabilities, concretely

1. **Multi-tenant weighted fair share.** Several apps (or users, or
   experiments) share one grid under a policy tree —
   `root → tenant → {interactive, batch}` — with committed-service EEVDF
   holding each subtree to its weight, at any nesting depth, reconfigurable
   at runtime without touching ancestors.
2. **QoS under contention.** Interactive lanes outrank batch *by weight, not
   by starvation*: batch still converges to its share; a quiet lane doesn't
   accumulate unbounded catch-up credit (§7.2).
3. **Coordination-free inside delegated entitlement.** The grid's hot path
   stays exactly as cheap as today: a peer executing within its holdings
   coordinates with nobody. Consensus spend per task remains **zero** on the
   coordination-free path; the heddle adds none.
4. **Bounded fairness under partition.** Both halves of a split keep
   executing within their pockets; the cross-tenant unfairness a partition
   can cause is the §8.2 bound — computable in advance from the caps,
   reportable live from outstanding holdings.
5. **Hoarding is charged.** A tenant that grabs entitlement and parks it (or
   stuffs queues) advances its own virtual position and throttles itself —
   the anti-abuse property warp currently has no answer to.

### 14.4 Integration sketch

Minimal surface change; the envelope already exists to carry it:

```kotlin
// TaskDescriptor gains an OPAQUE lane tag in warp core — it already carries
// op / args / traceparent / pinnedOwner; the lane is one more envelope field,
// a warp-local value type defaulting to a root lane so untagged warp keeps
// today's behavior. Warp core never references a :kuilt-heddle type.
public value class Lane(public val tag: String)

// The tag → leaf-group binding and entitlement enforcement live in the
// :kuilt-warp-heddle satellite, not in warp core.

// Submission: the lane is a DRAFT MODIFIER, not a shuttle argument. shuttle's
// signature stays untouched; .lane() decorates the returned Draft with an
// opaque tag that flows into the descriptor. This composes with warp's
// draft-as-value model and keeps the lane orthogonal to submission.
warp.shuttle(corpus)
    .lane("acme/interactive")          // thread this draft through a lane
    .weave()

// A scoped form is sugar over the same modifier, for a block of submissions
// that share one lane:
warp.inLane("acme/interactive") {
    shuttle(corpus).weave()
}
```

The earlier idea of a `shuttle(corpus, lane = …)` overload is deliberately
rejected: it couples the lane to submission and overloads `shuttle`'s signature.
The `.lane()` modifier is the right shape — it treats the lane as one more
decoration on a `Draft`, consistent with the opaque-tag/satellite decision above.

Execution-side enforcement first (no producer API beyond the tag): the ring
owner of a task must `reserve` from the task's lane before running it —
`HeddleNode.reserve(leaf, maximumCost)` returning `null` defers the task
until entitlement flows in, throttling each lane to its share while the ring
still decides placement. Producer-side admission (reserve at `shuttle` time,
bounding `WorkQueue` growth itself) is the follow-on. Costing starts as
1-unit-per-task and generalizes to caller-supplied `maximumCost` with
renewable quanta for long tasks (§4.4).

Wiring: `WarpNode` remains the seam's sole `incoming` collector; the heddle's
`Quilter` and demand channels ride the existing mux fan-out, and the
roster/liveness signals are shared, not duplicated.

### 14.5 The honest seam, stated once more

Everything in this module that runs per task or per allocation round is cloth
— monotone, mergeable, partition-happy. The three stitches (mint, topology,
fencing) are embroidery, and they are the *same needle* warp already threads.
Nothing here moves the CALM boundary; it just makes the cloth fair.

### 14.6 Location eligibility — “can I execute *here*” (Model A)

So far the scheduler answers *whether* a task may run (entitlement) and *whose
turn* it is (policy), but not *where it is even allowed to run*. Real grids need
that third question: this work needs a GPU, must stay in-region, or should sit
where its data already is. Call it **eligibility** — a predicate over
`(task, peer)`, upstream of placement.

The shape is deliberately the same as two things already in this design:

- **Peer capabilities are soft state, exactly like demand (§6).** Each peer
  advertises its own slot in an `EphemeralMap<PeerId, CapSet>` — attributes it
  can serve (GPU, region, held datasets, runtime, memory class). Stale,
  duplicated, or expiring is fine; capabilities are presence, not a ledger.
- **A task's affinity is a predicate, like the lane tag (§14.4).** It rides the
  same opaque envelope, decorating the `Draft`:

  ```kotlin
  warp.shuttle(work)
      .where { it.has(GPU) && it.region == "us-east" }   // eligibility (Model A)
      .lane("acme/gpu")                                   // entitlement lane
      .weave()
  ```

Placement then consistent-hashes over the **eligible subset** of the roster, not
the whole roster — every peer computes the same eligible set from the same
convergent capability view, so determinism holds for the same reason the ring
does. A stale view can only *misplace* work; warp's `Results` dedup and ring
re-home already absorb the transient inconsistency. **Eligibility introduces no
conserved quantity, so it cannot touch conservation** — it is purely a
placement-side overlay, orthogonal to the ledger, and composes with lanes rather
than living inside them. In practice it is a warp-side feature (the ring over an
eligible subset + capability advertisements) that `:kuilt-warp-heddle` combines
with entitlement.

**The algebra — why this belongs here.** Allocating virtual *time* and virtual
*space* are the same operation. Every selection in the system is an argmin over a
weighted coordinate on a feasible set:

```text
select = argmin over { c ∈ candidates : feasible(c) } of ( weight(c)·coord(c), tieBreakId(c) )
```

- **coord = virtual service time**, feasible = `v ≤ V`, weight = share
  → EEVDF fair-share (§7).
- **coord = distance in a metric**, feasible = capability match, weight = affinity
  → locality placement.

The heddle policy is `argmin (deadline, id)`; weighted-rendezvous placement is
`argmin (distance, id)` — the same functor on a different coordinate (the
weighted-fair-queueing ↔ weighted-rendezvous-hashing correspondence is real, not
loose). Two consequences worth recording:

- **Model A’s “affinity as a score” is a coordinate position**, not a boolean —
  which is how eligibility generalizes to NUMA-style data-locality,
  latency-to-user, and cost-preference placement.
- **Entitlement is a conserved budget along the *time* axis.** Model A adds no
  budget to the *space* axis, which is exactly why it is safe and additive. The
  mirror — a conserved *spatial* budget bound to a location set (a tenant’s
  GPU-budget that only *exists* on GPU nodes) — is **Model B**, the cpuset
  analogue: the heddle ledger with the axis relabelled, with the same partition
  subtleties (a spatial budget on a vanished location is stranded). Model B is a
  deliberate **north star**, not v1.

This also unifies the two “pluggable” axes: because EEVDF and locality placement
are one functor on two coordinates, a future `SchedulingPolicy` could be generic
over a `Coordinate<C>` (`position`/`feasible`/`weight`), with EEVDF and placement
as two instances. v1 keeps them concrete — premature generality is its own trap —
but the coordinate abstraction is the principled reason the policy axis and the
eligibility axis are the same axis. (Pluggable *policy per group* — the sibling
axis — is already a seam here, §7/§11; making it selectable and shipping a small
zoo is a small, separate follow-on.)

## 15. Implementation phasing

Six phases, each independently landable as its own epic sub-issue track;
small PRs, standard repo cadence; the invariant tests of §10 land **with**
(never after) each phase. Ordering is a dependency chain — each phase builds
only on the ones before it — but review and hardening of a landed phase can
overlap the next.

### Phase 1 — Ledger core

**Home:** `:kuilt-heddle` (new module, `kuilt.kmp-library`, all targets),
depending on `:kuilt-crdt` only for this phase.
**Work:** identifiers (§2), checked-arithmetic helpers, `EntitlementLedger`
with the three-counter edge accounting, `holdings` derivation,
`delegate`/`release`/`transfer`/`spend` over a *static* tree (topology fixed
at `bootstrap`), `EdgeSummary`, `validate()` for slot-integrity conflicts.
**Acceptance:**
- `Quilted` lattice laws pass the standard zoo property suite
  (idempotent/commutative/associative, cross-order structural convergence).
- Conservation identity (`minted = Σ holdings + Σ spent`, no earmarks yet)
  holds at every step of randomized mutator sequences.
- `spend`/`delegate` past `holdings(P, r)` returns `null`, state untouched.
- Projection homomorphism property (§4.5) pinned.
- Overflow paths fail deterministically (§10.12).

### Phase 2 — Lifecycle and strict reconfiguration

**Home:** `:kuilt-heddle`.
**Work:** the lifecycle max-register (§5.1), `prepare`/`activate`/`close`/
`retire`, drain discipline, derived tree-validity classification and topology
conflict reports (§5.2–5.3).
**Acceptance:**
- Closure dominance under adversarial merge orders (§10.10).
- `retire` refused until `outstanding(e) == 0`; a drained edge never issues
  again.
- Two ACTIVE inbound generations for one group converge to the same reported
  conflict on every replica, and delegation across both is refused until
  resolved (§10.11).
- Reparent and weight-change recipes exercise create/close/drain/re-generate
  end to end; old-generation history remains queryable and unchanged.

### Phase 3 — Policy

**Home:** `:kuilt-heddle`; pure code, no fabric.
**Work:** `HeddlePolicy` (§7): candidates, weighted-mean parent virtual time,
eligibility, deadline, deterministic tie-break; neutral init and the wake
clamp; cross-multiplication comparators.
**Acceptance:**
- Closed-loop simulation of the pure function: continuously demanding
  siblings converge to weight ratio in committed service; a hoarder is
  throttled; a returner recovers (§7.3).
- Neutral-creation and no-idle-credit properties (§10.5–6).
- Bit-identical decisions across JVM/Native/wasmJs inputs (no FP, stable
  tie-break).
- Model-check agreement with a slow exact-rational oracle on small sibling
  sets.

### Phase 4 — Node

**Home:** `:kuilt-heddle`, adding `:kuilt-quilter` + `:kuilt-liveness`
(+ `:kuilt-core`).
**Work:** `HeddleNode` over a `Seam`: `Quilter` replication of the ledger,
`EphemeralMap`/`EphemeralMapTracker` demand board, reservation table
(`reserve`/`complete`/`cancel`), liveness wiring, the `heddleStatic`
bootstrap, bound metrics (§8.2).
**Acceptance:**
- Multi-peer convergence over `InMemoryLoom` under partition/heal, loss,
  duplication, reordering (§10.7).
- "Deliver the completion patch N times, history rises once" (§4.4).
- Stale/expired demand can misplace but never authorize a spend (§6).
- Measured fairness deviation in simulation ≤ the §8.2 bound computed from
  the configured caps; the three bound metrics exposed and consistent.
- A crashed peer's earmarks stay stranded; no other peer's holdings change.

### Phase 5 — Control plane

**Home:** `:kuilt-heddle` control-plane adapter, adding `:kuilt-raft`.
**Work:** Raft-backed mint and topology serialization, the `heddleGoverned`
bootstrap, the fencing seam defined (`readIndex()`-fenced revocation
specified but not shipped).
**Acceptance:**
- Split-brain mint impossible: in a partitioned simulation, at most one side
  can commit a mint against a given supply.
- Two overlapping reshapes serialize through the log; the loser surfaces as
  a structured conflict, never a timestamp resolution.
- Non-overlapping subtree reshapes commit without contending; data-plane
  spend throughput is unaffected by control-plane traffic.
- All consensus tests run through the canonical harness
  (`RaftSimulation`/`raftRunTest` or `MultiNodeRaftSim`), tight timeouts,
  seeded election RNG — no hand-rolled network.

### Phase 6 — Warp lane integration

**Home:** `:kuilt-warp` (optional dependency on `:kuilt-heddle`), behind
warp's experimental posture.
**Work:** `Lane` field on `TaskDescriptor` (defaulting to a root lane),
execution-side `reserve` in the claim path (§14.4), shared liveness/mux
wiring through `WarpNode`.
**Acceptance:**
- Two lanes with weights 3:1 converge to the ratio in completed tasks on a
  grid simulation; the untagged default lane reproduces today's behavior
  bit-for-bit.
- Lane exhaustion defers tasks (they stay pending) — never drops them; work
  resumes when entitlement flows in.
- Ring placement is unchanged: the owner of every task is identical with and
  without the heddle enabled.
- Consensus spend on the coordination-free path remains zero (asserted by
  the simulation's message accounting).
