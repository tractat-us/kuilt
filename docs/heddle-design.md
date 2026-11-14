# `:kuilt-heddle` — weighted fair-share scheduling over kuilt

> **Status: design spec, built (pre-1.0, Playground).** All six phases of §15 have
> shipped; where the code settled a question differently from this text, the section
> says so (§11 maps the sketch onto what shipped). A kuilt-native design for a
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
:kuilt-heddle-core   the policy, the ledger and the local allocator — no network
    depends on :kuilt-crdt, kotlinx-serialization
:kuilt-heddle        the node and the Raft-governed control plane
    depends on :kuilt-heddle-core, :kuilt-core, :kuilt-crdt, :kuilt-raft,
               :kuilt-quilter, :kuilt-liveness
               (and :kuilt-store, which arrives through :kuilt-raft)
:kuilt-warp-heddle   (satellite, alongside :kuilt-warp-planning/-ml/-compiler)
    depends on :kuilt-warp + :kuilt-heddle   (lane-tagged scheduling, §14)
```

**The split is at the network.** `:kuilt-heddle-core` holds everything that is
arithmetic over a ledger: `HeddlePolicy`, `EntitlementLedger`, and
`FairShareAllocator`, the one copy of the pick-and-grant loop with its seat bump
and wake clamps. A consumer that only needs to decide shares in one process takes
that module alone. `HeddleNode` is the allocator plus replication, a demand board
and reservations. `verifyHeddleCoreDependencies` reds the build if core's main
classpaths ever resolve `:kuilt-core`, `:kuilt-raft`, `:kuilt-quilter`,
`:kuilt-liveness` or the coroutines runtime.

The control plane still reads ledger state no consumer should — the finals a
replica declares on a fenced edge and the projections behind them. Those
declarations sit in core behind the `@HeddleInternalApi` opt-in, because
`internal` cannot cross a module boundary.

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
independently consumable. Both modules are published and constrained by
`:kuilt-bom`, like every other `kuilt.kmp-library` module.

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
and reparented at runtime, any depth, no fixed level names (today a child the app
creates while running is always a leaf; §11.1 says where that stands):

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
recorded. Unknown-cost work reserves in renewable quanta. (The single-writer
`LocalHeddle` differs: its `complete` charges an overrun inline, and §7.4 decides
what a wrong estimate may cost.)

Because generations are immutable, a completion that arrives after its edge
closed still charges the *old* generation — historical service follows the
authority under which the work was admitted, never the topology visible when
the receipt merges. A later reshape mints a new `AttachmentId`, so there is no
ambiguity and no rewrite of history.

**The one exception: a quiesced edge (#1693).** When a generation was retired
with entitlement still riding on it (the advisory-retire race, #1665), the
recovery moves that entitlement onto the live generation — and to make the move
safe every peer must first *promise* it will never write the dead edge again.
That promise is the `Quiesce` barrier of
[`heddle-ledger-relocation-design.md`](heddle-ledger-relocation-design.md) §6.2:
once a peer has applied it, a completion whose captured path names the quiesced
edge charges the child's **live inbound generation** at that peer's applied log
index instead. It is not a rewrite of history — it is the *same* conserving move
the recovery performs, applied at charge time rather than afterwards, and it is
sound precisely because a quiesced edge is drained by construction. If no live
inbound exists yet (between a `Close` and the next `Activate`), the charge is
buffered locally and flushed when one activates: **never dropped, never charged
to the dead edge.**

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

- **Create** a group under a parent: new record, new PREPARED edge, ACTIVATE.
  The edge's virtual-time seat is *not* part of the record — the scheduler's seat
  bump writes it into the replicated gauge at the parent's current virtual time on
  its next round (§7.2). No ancestor above the parent needs to know. Creating a
  child mints nothing.
- **Close** an edge: CLOSING → let reservations finish, entitlement return →
  `outstanding == 0` → RETIRED. History stays queryable forever, edge by edge
  on a replicated ledger. A single-process engine will instead fold a finished
  leaf's edge into a per-parent total (§5.5).
- **Reparent** `C` from `A` to `B`: prepare `B→C`, close-drain-retire `A→C`,
  then activate `B→C` neutrally at `B`'s current virtual time. Old history
  stays under `A`; `C` starts prospectively neutral under `B`.
- **Change weight** = new generation, same drain discipline.
- **Insert an intermediate group** = compose the above.

The reparent above keeps the child's identity, and is a ledger-level operation.
A `Topology` (§11.1) names a child by its path, so a move at the topology level is
a delete plus an add: the old child is closed, drained and retired with its history,
and a new child starts under the new parent with none.

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

### 5.5 Forgetting retired edges

Picture a group with one child per user: a child is attached when a user
arrives and detached when the user goes idle. Every departure leaves something
behind. Detaching retires the child's edge, and a retired edge stays in the
ledger for good, because the work done under it still counts in the books. So
an engine that runs for months grows with every arrival and departure, in
memory and on disk, without bound. This section records how a single-process
engine will forget those edges while keeping the books exact, and why peers
cannot yet do the same.

#### As built: kept for good, summed once

`holdings(parent)` subtracts every child's net inflow, `effIssued − returned`,
retired children included: a retired child that served has a net inflow equal
to what it spent. Task L0 (#3189) pre-sums the retired children per parent and
replica in an index, `retiredOutflow`, which is derived from the ledger's own
slots and never encoded. That made reads fast at 50,000 retired edges. Nothing
bounds the size.

Three other readers need a retired edge:

- **Conservation.** `leafSpentTotal()` sums each edge's effective leaf spend over
  every edge, retired included.
- **The generation floor.** It is one global maximum over every edge, retired
  included, so that no id is ever reused.
- **`Topology.diff`'s `hasChildEdges` test.** It asks whether a group has any
  child edge in any lifecycle, to decide whether a reload needs a `Reconcile`.

#### The design: fold, then forget

Decided in #3192, and not yet built.

**A new encoded component, `forgotten`.** It is the ledger's seventeenth, written
through `CanonicalMapSerializer` at every level, with its own arm in
`:kuilt-heddle`'s `EntitlementLedgerCanonicalEncodingTest`. It holds:

- per parent, a map from replica to the folded `effIssued − returned` of its
  forgotten children: the term `holdings` subtracts;
- per replica, the folded effective leaf spend of the forgotten edges, so that
  conservation still balances;
- `forgottenGenerationFloor: Long`, the highest generation any forgotten edge
  carried, since the floor is a single global maximum.

`holdings`, the conservation check in `validate()`, the generation floor and
`hasChildEdges` each read the fold as well as the live edges.

**When an edge may be forgotten.** All of these hold:

- it is RETIRED with outstanding 0, and no open lease's captured path crosses it;
- its child has no child edges, so leaves go first;
- `holdings(child)` is 0;
- no transfer rows sit at its path;
- `strandRelocation` would move nothing.

A PREPARED edge that will never activate carries no history, so it is forgotten
with only the floor updated.

**When it happens.** Automatically, in the same swap that retires the edge
(`detachRuntimeChild`, `apply`). There is no new API. The same swap prunes the
edge from the allocator's wake memory, `AllocatorMemory`, since
`forgetDepartedChildren` finds an edge's parent through its record and could not
once the record is gone.

**Forgetting is not a join.** Every other mutator only adds to the lattice; this
one removes slots. So it is outside §10 invariant 2, scoped to a single writer,
as invariant 4's quiesced-edge exception is. Three guards hold the scope:

- `forget` is internal to `:kuilt-heddle-core`, and `LocalHeddle` is its only
  caller;
- `piece` refuses a delta that carries a non-empty `forgotten`, or that names an
  unknown edge at or below the forgotten floor;
- `:kuilt-heddle` requires `forgotten` to be empty.

**What `validate()` checks.** An edge's `ClosureViolation` and `PerEdgeSafety`
are checked when it is forgotten, since nothing is left to check afterwards. One
new invariant: the fold is never negative.

**The format.** `forgotten` is encoded under snapshot format 2, which reads
formats 1 and 2. v0.7.4 wrote format 1. Every encoded change for the next
release goes under that one bump.

**Until it is built,** keep a runtime child attached rather than detaching it
when its user goes idle.

#### Settled — don't reopen

- An explicit compaction call: forgetting happens in the retire's own swap, so
  there is no new API (#3192).
- Leaving the allocator's wake memory to prune itself later: its pruning needs
  the record that forgetting removes (#3192).
- A snapshot format bump per encoded change: one bump, format 2, per release
  (#3192).

#### Open

- **Forgetting between peers.** A replicated ledger would need causal stability
  per edge plus a tombstone, and a stored aggregate risks the drift that rejected
  candidate B ([`heddle-ledger-design.md`](heddle-ledger-design.md), *How this
  was chosen*). Tracked by #3332.
- **The coupling to the position ceiling.** If #3329 settles on generation
  turnover, every turnover retires a parent's children, and this forgetting
  becomes its prerequisite. Tracked by #3329.

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
the folded live `Demand`, immutable attachment policy (weight), the replicated
virtual-time seat (the edge's `Gauge` paired with its base issuance),
scheduler-local wake state, and locally available holdings. It inspects nothing else — no global queues, no descendants. Purity
is what makes it testable at virtual time and safe to run divergently on
partitioned peers: a bad local decision misplaces entitlement; it cannot
create any.

### 7.1 Virtual service

For active edge `e` with weight `w(e)`, the baseline `b(e)` is read off the edge's
replicated `Gauge` rather than off its record — a floor paired with the base issuance
the writer had observed when it asserted that floor:

```text
gross(e) = floor(e) + (baseIssued(e) − folded(e)) / w(e)     (no gauge ⇒ baseIssued(e) / w(e))
v_raw(e) = gross(e) − returned(e) / w(e)
```

Which is design §7.1's `b(e) + committedService(e) / w(e)` with `b` supplied by the
gauge — and with the **base** issuance as the fold axis, deliberately not the effective
one, so relocation credit restores a re-homed child's spendable holdings without also
advancing its virtual clock.

Because the numerator is *committed* service, a grant advances the child's
virtual position the moment it is issued — hoarding is charged (the
over-allocation loop the source warns about is closed), and `release` walks
the child back and restores eligibility.

**An edge's virtual time is kept as a position on its own grid** (#3313,
#3231): a `Long` count of ticks of the edge's own service, `G = 16` ticks per
unit, standing for the virtual time `p / (G · w)`. Issuance, returns and sleeper
credit are whole units, so they move a position by whole ticks, exactly. Across
edges nothing is divided: a weighted mean stays an unreduced sum of positions
over a sum of weights, and two edges compare by integer cross-multiplication in
128 bits. Only the two joins of §7.2 round, and both round up.

**A position has a ceiling, and at the ceiling it saturates (#3313).** The
ceiling is `(Long.MAX_VALUE − 2⁴⁸) / w.denominator` ticks. Dividing by the
denominator keeps a floor's wire form, `p · w.den / (G · w.num)`, in `Long`. The
`2⁴⁸` is room for one largest-reservation deadline `p + q · G`. A position
that would pass the ceiling is held there: the gauge read, a seat, a checkpoint
and a wake clamp all saturate. Saturating is a pure function of the view, and it
commutes with the gauge's componentwise `max`, so replicas still agree. What a
saturated position loses is exactness, so the rule splits by call:

- A call that **chooses** refuses. A pick at a parent with a child at its
  ceiling, or one whose own delegation would take a child there, returns
  `PositionCeiling(parent)` from `LocalHeddle.acquire` and `extend`. The call
  changes nothing.
- A call that **charges** lands. `admit` and `complete` keep the books exact,
  and the position stays at the ceiling.
- **No credit leaks through.** A front over a saturated child reads the ceiling.
  A floor at the ceiling stays there whatever the fold says. A wake clamp to a
  front past the waker's ceiling stores the ceiling itself. So a seat or clamp
  taken against an understated front lands at the penalty end, and nothing kept
  can outlast the saturated child as credit (§10.5).

The binding sum is lifetime service under one parent, about `2⁵⁷` units at the
largest reservation and a weight of `3/7`, so a busy parent does reach it.
Until positions can be brought back down (#3329 for `LocalHeddle`), a parent at
its ceiling refuses picks for good. The replicated `HeddleNode` stops granting at
that parent; its rebase and refusal surface are tracked by #3353. `apply` keeps the weight products in range
the same way: an activation whose weights would put its parent's `L`, `S`,
`G · S` or `S · d` out of `Long` is refused with a `StepRefusal`.

Why a grid at all, against the exact `Rational` this section used to prescribe:
a joiner stored the front, a weighted mean whose denominator carries `Σw` of the
set it was taken over, and the next front averaged that value in again. Every
join could multiply a stored denominator by another `Σw`, so a pick overflowed
`Long` within tens of picks once the waiting set changed between them, and the
overflowed state persisted. A position's denominator is fixed by its edge's
weight, so it cannot grow. The grid is `2⁻⁴` because each bit of it comes out of
the `Long` ceiling on a position: at `2⁻⁴` the churn fairness error measured
0.0170 against 0.0167 exact, and a finer grid buys nothing a test can see.

### 7.2 Neutral creation and no idle credit

- **Neutral creation:** a new generation is seated at the parent's current
  virtual time — its **front**, defined below — so a newborn starts level with
  its siblings, with no credit for the parent's whole past.

  The seat is **published, not carried** (issue #1752). It is not in the record;
  it is a `Gauge` in the replicated ledger, written by the scheduler's seat bump
  on every peer that still sees the edge unseated, and the gauge's componentwise
  join resolves those readings by `max` on the floor. That matters because `V` is
  a *local* reading — demand ages out by local receive time and wake clamps are not
  replicated — so a proposer that froze its own reading into the record made one
  peer's guess everyone's permanent fact, and a proposer reading a partial view
  froze a wrong one irrecoverably (issue #1713). A published reading is corrected
  by a better-informed one instead.

  **The seat rounds up, to the newborn's own grid: `⌈V · w · G⌉` ticks.**
  Flooring would seat the newborn *behind* the front, the lifetime credit §10.5
  forbids, so up is the side that can only give a turn away, at a cost under one
  tick: `1/16` of a unit at weight 1, against the whole unit the record-era
  `⌈V⌉` cost. §7.3 step 3 reads `V` on the same grid, so that sliver never keeps
  a newborn out of the round it joins. `EntitlementLedger.seat` is the single
  implementation; the floor it writes is the position as a `Rational`, so the
  wire form is unchanged and every later read of it is exact.

  This reverses #1752's 2026-07-30 ruling, which kept the floor an exact
  `Rational` and warned that a fixed-point floor would have to round **down**,
  because a `ceil` at every checkpoint would compound into real inflation. That
  premise does not hold here, because a checkpoint adds whole ticks. It writes
  the floor plus the issuance since the fold, which is whole units; reading an
  on-grid floor back is exact; and only the seat and the wake clamp round, each
  once per join. A floor written off the grid, by a peer older than #3313, is
  rounded up once, by the first checkpoint that reads it, and every checkpoint
  after that is exact. Peers that merge in any order still read the same
  position, because the read is a function of the joined gauge and the join is
  unchanged.

  Two consequences of mixed versions, accepted rather than fenced. An old and a
  new peer can read the same state up to one tick apart, because the new one rounds
  an off-grid floor up and the old one does not. And an un-upgraded peer keeps
  writing exact fronts into floors, so the denominators in its own state keep
  growing toward the overflow this change fixes. A new peer that merges those
  floors reads them on its grid, reducing each fraction before it multiplies, so
  it saturates only where the position itself passes its ceiling (§7.1).
- **No unlimited idle credit:** when a child goes from not-demanding to
  demanding, a local wake offset clamps it forward:

  ```text
  virtualOffset = max(0, parentVirtualTime − v_raw(e) − sleeperCredit / w(e))
  effectiveVirtualService(e) = v_raw(e) + virtualOffset
  ```

  On the waker's grid that is `max(0, ⌈V · w · G⌉ − p − sleeperCredit · G)`
  ticks, the same up-rounding the seat takes. Sleeper credit is service units,
  so it scales onto the grid by `G` like any other service. The stored offset is
  joined with `max` on the grid and kept there, so it never carries a front's
  denominator into the next one.

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

  **An empty surviving set has no front at all, and the front read reports that
  as `null` rather than quietly as the origin.** Two different situations wear
  that one face: the legitimate **first** generation under a parent, whose origin
  seat is genuinely correct, and a peer whose view has simply not applied the
  siblings yet, for which the origin seat is wrong.

  While the seat was frozen into the committed record that ambiguity was
  load-bearing and unfixable from inside — one peer's guess became everyone's
  permanent fact — so creation had to fence on leader authority (§9 #3
  `readIndex()`) *and* on this peer's applied prefix having caught up, and fail
  closed otherwise. That fence closed the stale-*records* case only; a view that
  had merged some siblings but not all computed a plausible front over those and
  froze it, with nothing anomalous to see (#1713).

  **Publishing the seat dissolves the ambiguity rather than fencing it** (#1752).
  The seat bump writes `max(front, baseIssued / w)` into the edge's `Gauge`, so a
  peer with no front seats at the origin — where the edge already reads, pinning
  it without moving it — and any better-informed peer's higher floor wins the
  join's `max`. A low reading is absorbed instead of frozen, so the fence and both
  of its residuals are gone, and a node with no control plane no longer needs to
  tell the two situations apart.

### 7.3 Selection

Per allocation round at one parent, on one peer:

1. **Candidates:** active edges with
   `additionalNeed = max(0, targetOutstanding − outstanding(e)) > 0`, quantum
   `q = min(configuredQuantum, additionalNeed, maximumUsefulGrant,
   localHoldings, perChildOutstandingCap)`, dropping `q == 0`.
2. **Parent virtual time:** the weighted mean
   `V = Σ w(e)·effectiveVirtualService(e) / Σ w(e)` over the fixed candidate
   set — the *trimmed* one from step 1. On positions that is
   `Σ p(e) / (G · Σ w(e))`, kept unreduced (§7.1). This is the same arithmetic
   as §7.2's seating front over a deliberately different set: the front drops
   the quantum trims, this step keeps them, so the two values coincide only
   when no trim binds.
3. **Eligibility:** `p(e) ≤ ⌈V · w(e) · G⌉` — `effectiveVirtualService(e) ≤ V`
   read on the edge's own grid, with `V` rounded the way a seat rounds it
   (#3313). Exact eligibility would keep a joiner seated a sliver above the
   front out of the very round it joined; this keeps it in, and admits nothing
   more than one tick past `V`. The set is never empty, and that is a
   **theorem, not an expectation** (#1737): `V` is the weighted mean of the
   *same* candidate set over strictly positive weights, so the candidate with
   the least virtual time has `p ≤ V · w · G`, and the ceiling only widens that.
   An empty set could therefore only mean step 2's mean was taken over a
   different set than this filter, or a non-positive weight was admitted;
   either leaves the round's whole ordering untrustworthy, so the
   implementation **asserts and fails loudly** rather than silently
   substituting the minimum and scheduling on regardless.
4. **Deadline:** among eligible candidates pick minimum
   `(effectiveVirtualService(e) + q/w(e), attachmentId)` — on the grid, the
   position `p + q · G` at weight `w`, compared by 128-bit cross-multiplication.
   The stable id is the deterministic tie-break.
5. **Delegate `q`**, apply the patch locally *before* the next selection;
   repeat until holdings, demand, caps, or a per-cycle limit stop the loop.

Expected behavior (and the acceptance tests behind it): continuously
demanding siblings converge on the weight ratio in committed service; a
hoarder is throttled; a returner recovers.

Production extensions (latency classes, minimum grants, bounded sleeper
credit, entitlement recall) are policy-layer only and must not touch
conservation or path accounting.

### 7.4 A wrong estimate: what it may cost, and whom

A lane asks for a slot and says how long its job will take. If it says too
little, its job looks cheap while it runs, and the lane wins slots it should
have waited for. If it says too much, it waits longer than it needed to. This
section asks two things: can a wrong estimate move shares, and if it can, whose?
It also records what to do about it. The aim is to be *seen* to be fair, with
the simplest rule that gets there. A rule that tries to close every corner
pushes gaming into places that are harder to explain.

In short:

- **Today's estimates are trusted.** A deployment whose operator sets them
  needs nothing more than the #3238 fix, which is shipping.
- **A deployment where work vouches for itself can turn on a strict mode.** In
  strict mode the engine charges what a lane has been seen to cost, and the
  caller's number never moves a charge. The job's *shape* still can: a lane
  whose bursts hide one long job among short ones is charged the mean for it.
- Renewing leases as they run, capping at the caller's value, and flooring the
  charge at the observed cost were all measured and are not adopted.

#3229, a draft, proposed a §7.4 about how long a request is for *ordering*. If it
lands, it becomes §7.5. This section is about what a request is *charged*.

#### As built

- **A pick charges the slot's reservation, and `complete` corrects it.**
  `acquire` funds the reservation first (`fundedBase`, `LocalHeddle.kt:429`).
  It then delegates the reservation at every level of the descent (`descend`,
  `:404-421`, with the check at `:411`). `extend` delegates more down the same
  path (`:473-482`). `complete` extends an overrun or releases the remainder,
  and `cancel` releases everything (`:496-532`).
- **The wake clamp counts an open lease at its reservation.** A waker's offset
  is `max(carried, front − v_raw − sleeperCredit/w)` (`clampWakers`,
  `FairShareAllocator.kt:417-430`; `wakeOffset`, `HeddlePolicy.kt:283`). Its
  front is taken over the siblings that are not joining (`settleJoiners`,
  `FairShareAllocator.kt:312-331`). `v_raw` is committed service,
  `issued − returned`, so it holds every open lease at what that lease reserved
  (`HeddlePolicy.kt:193`). This holds for the waker and for every sibling the
  front is read from.
- **A leaf with running leases and an empty queue is idle.** `waiting` means
  queued work (`LocalHeddle.kt:222-226`). This is right for a pooled
  scheduler: such a lane is not asking for the slot being handed out.
- **Orphans are settled through `complete`.** `reconcileLeases` reports them and
  changes nothing (`:690-710`). The caller then completes each one, by default
  at `defaultSettlement = min(elapsed, granted)` (`:164`). `complete` cannot
  tell that settlement from a finished job.
- **#3238's fix (PR #3248, shipping in v0.7.4)** records, at each clamp, the
  clamp's own slack and the lease watermark. A later release of a lease that
  was open at that clamp then hands back nothing the clamp denied. The fix
  needs one `WakeExposure` per edge in `AllocatorMemory`.

#### Measured

**The model.** The numbers come from a Python model of `pickChild`,
`settleJoiners` and the clamp, with #3248's fix added. Its source and tables
are in the comment on #3240.

- **Calibrated.** It matches all 76 of #3248's TODAY and EXPOSURE engine rows
  to within 0.00016. Two cells outside those rows were later run on the engine
  for #3248, and match too: the staggered two-level row (7,5,2,2) and the flat
  honest-noise cell with costs `[6,10,7,9,8]`. JVM and macosArm64 gave
  byte-identical rows.
- **Not calibrated.** No engine row checks the model's other staggered starts,
  thinks of 12 and 20, or two-level bursts of 2. Nor does one check the
  five-cost cycle elsewhere, unequal weights, sleeper credit, or any part of
  the observed-cost path. Every strict-mode number below is the model's alone.

**The rigs.** Each number is a slot-time share, measured against the same
configuration's honest arm. The worst cell is named.

- `flat t/b` is `root → {a, b}` with 8 slots. `b` is always backlogged at
  cost 1. `a` runs in bursts of `b` jobs, and the next burst arrives `t` after
  the last one starts. The rigs are `t ∈ {4, 7, 12, 20}` and `b ∈ {3, 4, 8}`.
- `2L (t1, t2, s2, b2)` is `root → {g, b}` with `g → {a1, a2}`. `a1` runs
  bursts of 4 jobs of cost 8. `a2` is honest, starts at `s2`, and runs bursts
  of `b2`. The first two rigs are #3248's own.
- Every lane has weight 1. Both the model and #3248's rig acquire at
  reservation 1 and apply the estimate with `extend`.

| arm | today | with #3248 | strict mode |
|---|---|---|---|
| flat, under-reserve every job ×¼ | +0.111 (12/8) | +0.111 (12/8) | 0.000 |
| flat, over-reserve every job ×4 | −0.111 (4/4) | −0.186 (4/4) | 0.000 |
| flat, over-reserve the job that empties the queue | +0.136 (7/4) | −0.054 (4/3) | 0.000 |
| flat, cancel that job | +0.056 (4/4) | 0.000 | 0.000 |
| 2L, `a1` under ×¼: `a1`, and its honest sibling `a2` | +0.081, −0.046 | +0.081, −0.046 | 0.000, 0.000 |
| 2L, `a1` over ×4: `a1`, `a2` | −0.075, −0.053 | −0.130, +0.055 | 0.000, 0.000 |
| 2L, `a1` over-reserves the job that empties its queue: `a1`, `a2` | +0.111, −0.100 (7,4,0,4) | +0.164, −0.135 (7,5,2,2) | 0.000, 0.000 |
| 2L, `a1` cancels that job: `a1`, `a2` | +0.128, −0.053 (7,5,2,2) | +0.007, +0.005 (4,7,0,4) | +0.007, +0.005 |
| honest arms against today, all lanes steady | 0 | 0.000 | at most +0.005 (12,9,5,2) |
| 2L, honest steady `a2` beside a truthful `a1` with varying costs, against exact estimates | 0 | 0 | −0.029 (7,7,3,2), +0.060 (7,5,2,2) |
| honest noise against an exact oracle, flat | −0.067 (7/4), +0.044 (20/8) | the same | −0.067 (7/4), +0.017 (12/8) |
| honest noise against an exact oracle, 2L | −0.065, +0.058 (4,7,0,4) | the same | −0.044 (12,9,5,2), +0.026 (7,5,2,2) |

In the honest-noise arm, each estimate is the lane's last observed cost, with
costs cycling through `[4,4,4,20]`, `[2,14]` and `[6,10,7,9,8]`. The oracle is
the same engine with exact estimates. In those cycles the longest job is at most
2.5 times the mean. The steady-sibling row cycles `a1` through `[10,10,10,2]`,
`[2,10,10,10]`, `[4,4,4,20]` and `[20,4,4,4]`.

**Strict mode's 0.000 holds by construction.** Its charge never reads the
estimate, so every estimate arm equals the honest arm. That is what the design
says, not evidence that it is safe. And the model cannot see the other half of
the estimate, the ordering: with every weight at 1, all candidates in one pick
share the slot's request length, so it orders nobody. The Acceptance arms below
cover that half.

Four things in the table matter beyond the arms themselves:

- **A lease still open, and over-reserved, when its sibling wakes inflates the
  front that the sibling is clamped to.** The bottom row of the next table is
  this case. It belongs to today's clamp, and #3248 neither causes it nor cures
  it. Over 192 staggered two-level rigs, the sibling's mean deviation is −0.052
  today and −0.052 with #3248. #3248 compensates once in the whole run, by 3.0
  on `root>g`, and that moves the run onto a different periodic schedule.
  Adding that one 3.0 to today's engine reproduces #3248's row exactly.
  - Which rigs land on a bad phase changes. With #3248, 37 are worse by more
    than 0.01, 24 are better, and 131 are the same.
  - The worst cell moves. Today's worst is (7,4,0,4) at −0.100, which #3248
    improves to −0.017. #3248's worst is (7,5,2,2) at −0.135, where today shows
    −0.069.
  - The engine confirms (7,5,2,2) on #3248: `main` +0.098 and −0.069, #3248
    +0.164 and −0.135.
- **The honest-noise bar is already broken today**, by up to 0.067. The cause
  is cost variance, not anyone's estimate. A job that costs more than its
  reservation, and is still open at a wake, pays its overrun after the clamp.
  No candidate here brings that under 0.03 in every cell. So acceptance is two
  sided against today's envelope (below), not against 0.03.
- **#3248 also changes one honest-noise cell**: −0.028 at flat 4/4 with costs
  `[6,10,7,9,8]`, where today shows 0.000. The engine confirms it on #3248.
- **The shape of a job is a lever in strict mode.** A lane charged its mean is
  under-charged for a job far above that mean, and the overrun lands after the
  clamp. These cases are flat, against exact estimates under `DECLARED`:
  - Costs `[1,1,1,57,1,1,1,1]` in bursts of 8 gain +0.098 (think 7) to +0.109
    (think 12).
  - An honest lane whose bursts end in the long job, `[1,1,1,1,1,1,1,57]`, loses
    up to −0.089 (think 7).
  - Under `DECLARED` the same shapes move further when the lane estimates by its
    last observed cost, +0.171 (think 12). A lane that estimates its mean gets
    +0.096, close to strict mode. Strict mode gives the lever to every lane;
    under `DECLARED`, a lane with exact estimates never had it.

#### Where a correction lands, and why each candidate fails or holds

A lease's error, `R − C`, is corrected when the lease settles. What the
correction does depends on where the lane is at that moment.

| the correction lands | over-reserved (a release) | under-reserved (an overrun) |
|---|---|---|
| while the lane competes | exact | exact; a lead, repaid |
| while the lane sleeps | the next clamp may forfeit it: a penalty | **the next clamp may erase it: a gain** |
| after a clamp that counted the lease | closed by #3248 | a penalty the clamp did not foresee |
| at any time, at a parent with a waking sibling | **the sibling is clamped to a front the open lease inflates** (today's; #3248 moves the phase) | the sibling is clamped to a front the open lease deflates |

The bold cells are what is left after #3248. Each candidate either removes
the error at its source or does not:

- **Strict mode removes it.** The charge never depends on the caller's
  number, so in the estimate arms `R − C` is the same whatever the caller says.
  No lease is over-reserved on the caller's word, so the sibling spill goes as
  well. What remains is cost variance, which today has too, and job shape.
- **Renewing in arrears**, extending a lease to its reported elapsed time as it
  runs, does not reach the bold cells. A burst's picks happen at one instant,
  before there is any progress to report. And a running lease's renewals land
  while its lane sleeps, so the next clamp erases them. Measured with #3248:
  - under-reserving gains **+0.136** (flat 7/4) instead of +0.111;
  - the honest sibling of an under-reserver loses **−0.100** (2L 7,4,0,4);
  - honest noise reaches +0.093 (flat 12/8).

  With strict mode, renewals make honest noise worse, −0.086 (flat 4/4)
  instead of −0.067.
- **Renewing in arrears with the caller's value as a cap**,
  `min(caller, observed)`, keeps the under side open. Measured together with
  renewing in arrears, never alone. Under-reserving gains +0.136 (flat 7/4), the
  honest sibling loses −0.069 (2L 7,5,2,2), and honest noise reaches +0.107
  (flat 12/8).
- **A pick-time floor at the observed cost**, `max(caller, observed)`, closes
  the under side. The flat gain falls to +0.000, and the two-level gain to
  +0.028 with the sibling at −0.017. It keeps the over side exactly as #3248
  leaves it: −0.186 flat, and +0.164 and −0.135 at two levels. So it is not a
  strict mode.
- **Honouring `extend` under strict mode** is that floor. An extension adds the
  caller's excess on top of the observed charge, so the over arms become the
  floor's rows. (Both rigs apply every estimate through `extend`.)
- **Not clamping a lane with work in flight** (#3248's rule B) is rejected by
  the engine. One long lease open lets a lane bank idle credit for every slot it
  never asked for: every slot for 1,000 ticks on #3248's long-lease row. The
  model agrees: +0.070 of share, with mean wait cut from 12.9 to 1.0. The prior
  art behind it assumes per-processor queues charged as they run. Heddle has
  one pool, charged at the pick.

#### Decision

**Recommended; not built.** Default mode is today's, plus #3248. Strict mode is
`LocalPolicy.charge = ChargeRule.OBSERVED`, off by default. Nothing in strict
mode changes the wake clamp, so it holds whatever #3248 ships: rule A as
measured, or A with a fix to the front.

- **Default mode, `ChargeRule.DECLARED`.** A lease is charged the slot's
  reservation plus its extensions, as today. An estimate's effect on shares is
  bounded per level by the table's numbers:
  - under-reserving gains up to +0.111 at one level;
  - over-reserving costs up to −0.186;
  - an over-reserved lease open across a sibling's wake moves the sibling by up
    to −0.135, and moves itself by up to +0.164. Today's worst is −0.100, and
    the mean over 192 rigs is −0.052 with or without #3248.

  These are the costs of trusting the caller. An operator who sets the
  estimates bears them on its own lanes. They are documented, not fixed.
- **Strict mode, `ChargeRule.OBSERVED`.** A lease is charged its lane's
  observed cost. The caller's `reservation` still orders the pick, as the
  slot's request length (Amendment 9). It moves no charge, and neither does
  `extend` (API, below). Every estimate arm is 0.000 at both depths, by
  construction. The one exception is cancel-last at two levels: +0.007 and
  +0.005, as with #3248 alone.
  - *Honest lanes, all steady,* change by at most +0.005 against today.
  - *An honest steady lane beside a truthful one with varying costs* moves by
    −0.029 to +0.060 against exact estimates.
  - *Honest lanes with varying costs* stay inside today's envelope, for cost
    cycles whose longest job is at most 2.5 times the mean: −0.067 to +0.017
    flat, and −0.044 to +0.026 at two levels. The mean absolute deviation is
    0.014, against today's 0.014, flat. At two levels it is 0.022 against
    0.028. More skewed shapes are outside that bound, from +0.109 to −0.089
    (above).
  - *Its real cost:* an operator whose estimates are exact gives that up. On
    the measured cycles the worst cell is flat 4/4 with `[4,4,4,20]`: −0.056
    against today's 0.000. On skewed shapes it is up to −0.089. That is why
    strict mode is off by default.
- **Not adopted:** renewing in arrears, alone or with a cap; a pick-time floor;
  honouring `extend` under strict mode; and not clamping work in flight. The
  measurements are above, and the reasons are under Settled.

**The observed cost, precisely.** The engine keeps a mean per leaf `GroupId`, in
fixed point with 16 fractional bits:

```text
complete(lease, actual):   m(ℓ) := m(ℓ) + ((min(actual, MAX_RESERVATION) << 16) − m(ℓ)) >> s
                           (the first completion sets m(ℓ) := min(actual, MAX_RESERVATION) << 16)
charge for ℓ:              ⌈m(ℓ) / 2^16⌉, clamped to 1..MAX_RESERVATION,
                           or LocalPolicy.defaultReservation before ℓ's first completion
```

- **The shift is a floor shift.** A truncating division never comes down to `C`
  once it has overshot, and sticks one unit high.
- **`s = 3` is the default,** about the last 8 completions. The measurement
  rules out `s ≤ 1` and nothing more: `s = 3` and `s = 5` cannot be told apart
  on these rigs, and 3 is the shorter memory. The honest-noise ranges with
  strict mode were:

  | `s` | flat | two levels |
  |---|---|---|
  | 0 | −0.067 to +0.088 | −0.050 to +0.026 |
  | 1 | −0.067 to +0.036 | −0.057 to +0.040 |
  | 3 | −0.067 to +0.017 | −0.044 to +0.026 |
  | 5 | −0.067 to +0.021 | −0.044 to +0.026 |

- **What teaches it.**
  - It learns at `complete`, from the cost the caller reports. Heddle reads no
    clock, so the mean moves with completions, not with time.
  - `cancel` teaches nothing; otherwise cancelling would lower a lane's charge.
  - **An orphan may raise `m`, but never lower it** (provisionally, per the
    owner's answer on #3240). The documented settlement is
    `min(elapsed, granted)`, and in strict mode `granted` is the charge. So a
    default settlement can only pull `m` down, and a crashed job would teach
    the engine that the lane's jobs are short. Because `complete` cannot tell an
    orphan's settlement from a finished job, the engine marks the lease instead.
    In strict mode, `reconcileLeases` records each orphan it reports in
    `CostMemory`. A later `complete` of a marked lease updates `m` only when
    the update would raise it. A `complete` or `cancel` drops the mark. A lease reported orphaned
    whose work was in fact alive is therefore learned only upward. That is the
    conservative error.
- **Keyed by leaf `GroupId`, not by edge.** It survives a reweight (R1). L3
  drops a removed leaf's entry. A rename or a move starts over (Ruling 12).
- **What can still be gamed, and whether it is closed.**
  - A caller that misreports `actualCost` breaks every guarantee in this
    document, as it does today. Cancelling a job that ran is the same lie:
    +0.007 at two levels.
  - **Job shape.** A lane that hides one long job among short ones gains up to
    +0.109 (above). This is the same overrun-after-the-clamp that sets today's
    honest-noise envelope, and it is not closed (Open).
  - **Cold start.** A runtime child created fresh is charged
    `defaultReservation` until its first completion. Every pick in its first
    burst lands before any of those jobs completes, so a whole burst goes at
    that charge. Detaching the leaf and attaching it again drops its entry (L3),
    so the trick repeats. It pays only with a low `defaultReservation`, and it
    is not closed.

#### API

```text
public enum class ChargeRule { DECLARED, OBSERVED }              // new

LocalPolicy(
    defaultReservation: Long,
    sleeperCredit: Long = 0,
    supply: SupplyPolicy = SupplyPolicy(),
    charge: ChargeRule = ChargeRule.DECLARED,                     // new, trailing and defaulted
    costWindow: Int = 3,                                          // new: s, in 0..16; read only under OBSERVED
)
LocalHeddleState(…, costMemory: CostMemory)                       // new trailing component
CostMemory                                                        // new: @Serializable, contents internal, EMPTY
```

- `acquire`, `admit`, `explain`, `previewPicks`, `extend`, `complete`,
  `cancel` and `reconcileLeases` keep their signatures. Under `DECLARED` every
  one of them behaves as today, and `CostMemory` stays `EMPTY`.
- **Under `OBSERVED`:**
  - A lease's `Lease.reserved` is its charge, not the `reservation` passed.
  - **`extend` is accepted and changes nothing.** It returns
    `LeaseOutcome.Done(0, 0)` and delegates nothing, so it never reports
    `PathDraining` or `SupplyExhausted`. A lease that does not exist is still
    reported as missing. `complete` charges an overrun inline and is never
    refused, so a strict-mode lease never needs more reserved to keep running.
  - **`reconcileLeases` marks each orphan it reports** in `CostMemory`, and
    `complete` reads the mark (above). Its KDoc's "changes nothing" becomes
    "changes nothing but that mark".
- Changes to the binary interface: one enum, two `LocalPolicy` components, and
  one `LocalHeddleState` component. `LocalPolicy` loses another JVM constructor
  shape, as L2's `supply` did.

#### `explain`, `fundedBase` and `previewPicks`

- **Funding happens before the descent.** Under `OBSERVED`, `fundedBase` funds
  `max(reservation, max charge over the eligible leaves)`, before any pick.
  - A supply refusal therefore still happens before the descent. The
    wake-memory exception (`LocalHeddle.kt:263-266`) stays the only side effect
    of a refused `acquire`.
  - `explain` calls the same `fundedBase` with the same eligible set, so its
    `SupplyExhausted` arm agrees with `acquire`.
  - **The cost:** with no capacity reconciled, the root funds only from what it
    holds. An `acquire` can then be refused when another eligible leaf's charge
    does not fit, even though the picked leaf's own charge would. An engine
    told its slot count tops up below the low watermark and is never refused
    this way.
- **The descent is unchanged.** It orders and delegates the slot's
  reservation, as now. Once the leaf is known, the same operation extends the
  difference down the whole path from the root, or releases it up that path.
  That release is part of the pick, not a settlement, so #3248 does not
  compensate it. `explain`'s `BehindAtLevel` is read from the descent, so it is
  unchanged.
- **The scratch engine copies `CostMemory`**, as well as the wake memory and
  the capacity. Then `previewPicks` and `explain` charge as the acquires they
  stand for would.

#### Task P and v0.7.4

**v0.7.4 carries #3248 and nothing from this section.** P's format 1 already
nests `AllocatorMemory` as one value, so #3248's `WakeExposure` rides in it.

**`CostMemory` goes in format 2** (decided). It is the one thing strict mode
persists: the means and the orphan marks. Reading a format-1 snapshot gives an
empty `CostMemory`, so a restored engine starts cold, and P's rule, that each
release reads the previous format, already covers this. A cold start loses the
orphan marks, so `reconcileLeases` should run again after a restore. Nothing
else here is persisted: the charge rule is policy, passed in at restore, and
renewals are not adopted.

#### What must still hold

| invariant | after this design |
|---|---|
| `minted == Σ holdings + spent`, and `Σ leaf holdings == Σ open reserved` | holds: the charge correction is a delegation or release through the existing helpers, `Lease.reserved` follows it, and a no-op `extend` moves neither side |
| every operation all-or-nothing | holds: `CostMemory` is updated after the swap, at `complete`, and at `reconcileLeases` for the marks |
| one leaf predicate, with demand lifted by the descent's terminal predicate | unchanged: the charge is set after the descent |
| `previewPicks` equals the acquires it previews | holds if the scratch engine copies `CostMemory` |
| a pick never reads `startedAt` | unchanged |
| `complete` is never refused | unchanged |
| `MAX_RESERVATION` | caps every charge and every learned cost |
| #3231's ceiling | no new `Rational` state: `m` is a `Long`, and a charge is a delegation like any other |

#### Acceptance

All of these run under `allTests`. The JVM, macosArm64 and wasmJs rows are read
together, and the rig draws nothing at random, so a row that differs between
targets is a determinism defect. Every arm is **two sided**, with a ceiling on
the honest penalty as well as on the gain.

- **`DECLARED` is today plus #3248, exactly.** Every honest and honest-noise
  row matches #3248's rows within 0.000. This pins that strict mode, switched
  off, changes nothing.
- **Under `OBSERVED`**, on the probe's rigs (flat 4/4 and 7/4), and on #3248's
  2L rigs plus two staggered ones (`a2` in bursts of 2, starting at 2 or 5):
  - Every estimate arm is within ±0.03 of the honest arm. That covers under,
    over, under-last, over-last and cancel-last, at one level and at two, for
    both `a1` and its sibling `a2`. The model says 0.000, and +0.007 for
    cancel-last at two levels.
  - Every honest arm is within ±0.01 of `DECLARED`'s.
  - Every honest-noise cell is within 0.06 of `DECLARED`'s same cell. The mean
    absolute deviation from the exact oracle is no higher than `DECLARED`'s.
    The model gives 0.014 against 0.014 flat, and 0.022 against 0.028 at two
    levels.
- **New arms under `OBSERVED`, for what the model cannot vouch for:**
  - *Unequal weights.* `a1` at weight 2 and `a2` at weight 1, with every
    estimate passed as `acquire`'s `reservation` rather than through `extend`.
    Only then does the caller's number order anything. Same bar: ±0.03 of the
    honest arm. The model has not measured this, so a miss is a finding against
    the decision, not a bar to loosen.
  - *Job shape.* Flat, think 7 and 12, costs `[1,1,1,57,1,1,1,1]` and
    `[1,1,1,1,1,1,1,57]` in bursts of 8. The rows must match the model within
    0.01: +0.098 and +0.109, then −0.089 and −0.030. They are the documented
    exposure, so they are recorded, not hidden.
  - *A steady sibling beside a varying lane.* 2L, `a1` truthful with the four
    varying orders in the Measured section, and `a2` steady. Within 0.01 of the model's
    −0.029 to +0.060.
- **Mutations, recorded in the implementing PR.**
  - Charge the declared value under `OBSERVED`: the strict estimate arms red.
  - Honour `extend` under `OBSERVED`: the strict over and over-last arms red.
    They become the floor's rows: −0.186 flat, and +0.164 and −0.135 at
    (7,5,2,2).
  - Fund after the descent: `explainAgreesWithAcquireOnSupply` reds.
  - Let an orphan lower `m`, or ignore the mark: `anOrphanNeverLowersTheObservedCost` reds.
  - Use truncating division: `costMemoryGoldenValues` reds.
- **Unit tests.**
  - `coldStartChargesDefaultReservation`
  - `costMemoryLearnsFromCompleteNotCancel`
  - `costMemoryGoldenValues`: a fixed sequence gives the same `m` on every
    target.
  - `anOrphanNeverLowersTheObservedCost`: settled at `defaultSettlement`, and
    at a cost above `m`.
  - `orphanMarkSurvivesASnapshotRoundTrip`
  - `extendChangesNothingUnderObserved`
  - `explainAgreesWithAcquireOnSupply`
  - `previewPicksEqualsAcquiresUnderObserved`
  - `observedChargeSurvivesAReweight`
  - `declaredIsTodayExactly`

#### Settled — don't reopen

- **No wake clamp while work is in flight** (#3248's rule B). A lane holding
  one long lease banks credit for every slot it did not ask for: every slot for
  1,000 ticks on #3248's long-lease row. In the model it gains +0.070 of share
  (#3238, #3239). Per-processor prior art does not transfer to one pool charged
  at the pick.
- **Renewing leases in arrears.** Under-reserving then gains +0.136, an honest
  sibling loses up to −0.100, and with strict mode honest noise worsens to
  −0.086 (#3239).
- **Renewing in arrears with the caller's value as a cap.** The under side stays
  open, at +0.136, with a sibling at −0.069 (#3239).
- **A pick-time floor at the observed cost as the strict mode.** It leaves the
  over side at two levels at +0.164, with its sibling at −0.135 (#3239).
- **Honouring `extend` under strict mode.** It turns strict mode into that floor.
  Refusing `extend` instead would need a new `LeaseOutcome` arm. That would
  break every exhaustive `when` on it, and tell a caller that renews by habit
  that it erred, when under `OBSERVED` there is nothing to reserve (#3240).
- **A separate `settleOrphan` operation.** It leaves `complete` teaching
  downward for every caller who settles an orphan through `complete`, which
  `reconcileLeases`' KDoc tells them to do (#3240).
- **The first draft's `owed` (debt carried to the next wake) and the per-lease
  `atRisk`.** `owed` fails below depth 1, and the pair cost honest estimators
  8–14% in the Fable review (#3240). #3248's exposure replaces `atRisk`.
- **A ledger surcharge, or a penalty added to the offset at `complete`.** The
  first breaks `spent == reported cost`. The second is swallowed by the next
  wake's `max` join (#3239).
- **Keeping the observed cost in `AllocatorMemory`.** The allocator never sees a
  lease or a leaf, `HeddleNode` would carry it unused, and keyed by edge it
  would be lost at every reweight (#3239).
- **`CostMemory` in format 1.** It goes in format 2, and reading format 1
  cold-starts it (#3240).

#### Open

- **The front a sibling is clamped to counts an open lease at its
  reservation.** An over-reserved lease open across a sibling's wake pulls the
  sibling's share down: a mean of −0.052 over 192 rigs, worst −0.100 today and
  −0.135 with #3248. Should that front leave out an open lease's unspent
  reservation? Strict mode avoids it, but this is a question about today's
  clamp, so it belongs to #3248 or a follow-up (#3238).
- **Cost variance under 0.03, and job shape with it.** Today's honest-noise
  envelope is ±0.067, from overruns that land after a clamp. Job shaping in
  strict mode uses the same gap. No candidate here closes it. A two-sided
  correction, which lowers an offset when an overrun lands after its clamp,
  made other cells worse in the model, up to +0.107 (#3239).
- **Whether strict mode should ship at all, and whether it should become the
  default** once it has run in a deployment that does not control its
  estimates (#3239).
- **Whether the model is committed** beside this document, so that its numbers
  can be re-run, or kept in the PR comment (#3240).

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
discretization: the quantum, and the position grid's one tick (`1/16` of a
unit) per edge, from step 3's grid-resolution eligibility and the up-rounded
seat and wake clamp (§7.1–7.3):

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

**The unit is an execution, not a task.** The bound is stated over what the
ledger actually spends (§14.4), and an executor whose claim path is
at-least-once can turn one logical task into more than one execution. That
extra spend is *not* part of `error(p)`: it is capacity the pool genuinely
consumed, charged to the lane that caused it. What it does mean is that a
lane's charge can exceed the work it asked for, by the duplicate-execution
rate — normally zero, rising only during churn. So an executor whose admission
path can run one task twice must expose that rate alongside the three pieces
above, per lane, so the extra cost is observable rather than inferred.

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
   path already uses. v1 defines the seam and ships mint, topology, the
   roster below, and the conserving re-home of budget a raced `retire`
   stranded (`quiesce`/`reconcile`, relocation design §6.2). Reclaiming a
   *crashed peer's* holdings stays an explicit later feature: `revocation`
   answers `RevocationOutcome.NotShipped`.

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

**Enrolling is what lets a peer spend: the write gate** (#1892, #2921). A governed
peer boots unable to author entitlement. Its gate opens when the apply loop applies
*this run's own* `Enroll(self)`, and because Raft applies in index order, a peer that
has applied its own post-boot enroll has replayed every barrier committed before it,
so a restart cannot lose a quiesce mark and then charge a fenced edge. Applying this
run's `Depart(self)` closes it again. While it is closed, `reserve` and `schedule`
answer `GateClosed` with a `Reason` (`AwaitingEnrollment`, `Departed`,
`UnreadableControlEntry`) — never the `NoHoldings` / `NothingToDelegate` an idle peer
gives. The gate moves **in the apply loop**, in the same locked step as the entry,
not in the proposing caller's continuation: a call that timed out or was cancelled
after its entry applied never resumes, so a gate it moved would stay wrong for good.
A run's own acts are recognised by membership in the set of keys this instance
issued, with a per-run nonce on the two gate acts, never by the key's
`self#incarnation#` text — `incarnation` is caller-supplied and nothing detects a
reused one. Relocation design §6.5 (residual 3) and §13 (item 2) carry the argument;
`HeddleControlPlane`'s KDoc carries the mechanism.

**The control log is read in full or not at all** (#1738). The log is the truth
about supply, topology and the roster, so a peer that cannot read one of its entries
no longer knows that truth, and scheduling from whatever happened to decode is a
conservation break by a second route. Every act is framed — the magic `KHCL`, a
version byte, then CBOR — and an entry that does not decode, framed or not, halts
the apply loop there. Nothing past it applies; the reason latches as
`GateClosed.Reason.UnreadableControlEntry(index, version)`; a call made after the
halt is refused without proposing (`Conflict` at `NOT_COMMITTED`), and one in flight
at the halt answers `ControlOutcome.Indeterminate`, because it may still commit. A
restart replays to the same entry and halts again. Nothing is skipped, and that has
two accepted costs. The same bytes stop every peer on a build, so one peer on a
newer build stops the older ones until they upgrade. And bytes heddle never wrote
stop it too, since a peer cannot tell them from an act it has lost the means to
read — so the control plane needs a Raft node of its own. That node stays on
`ClientIdentity.Auto`: the plane replays from index 1 and restarts its request
serials on every boot, a serial space no `ClientIdentity.Durable` floor can describe
(#3098). A durable log from v0.7.3 stops at its first `Mint` or `Prepare`; start it
fresh, as there is no migrator.

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
   `outstanding` may fall but is always derived, never stored. **Scope: every
   ledger peers exchange.** A single-writer engine's forgetting of retired edges
   (§5.5) removes slots and is not a join. It is confined, like invariant 4's
   exception, to a ledger no other writer touches: `forget` is internal to
   `:kuilt-heddle-core`, `piece` refuses a delta that carries a non-empty
   `forgotten`, and `:kuilt-heddle` requires it to be empty.
3. **Per-edge safety.** `0 ≤ spent(e) + returned(e) ≤ issued(e)`.
4. **Path-relative accounting.** A completion charges every edge of the path
   captured at reservation; history never moves to a newer generation —
   **except across a quiesced edge**, where the charge re-homes to the child's
   live inbound generation (§4.4; relocation design §6.2 step 2). That weakening
   is deliberate and narrow: it applies only to an edge every peer has
   log-recorded a promise never to write again, and the re-home is the same
   conserving move the recovery would perform, taken at charge time. A
   single-writer ledger meets that fence by construction, provided no open
   reservation's captured path crosses an edge of the strand it re-homes.
5. **Neutral attachment initialization.** A new generation starts at the
   parent's current virtual time — the front over its *demanding* children,
   not over all its active ones (§7.2) — never with lifetime credit. The
   seat is published on the edge's `Gauge`, not frozen into the record, and is
   the front rounded **up** to the edge's own grid, under one tick (§7.2), below
   the position ceiling; at it, the seat lands at the ceiling (§7.1).
6. **No unlimited idle credit.** Default sleeper credit is zero; waking
   clamps forward. Once PR #3248 lands, a release after a wake hands back
   nothing the clamp denied (#3238). What a wrong estimate may still move is bounded per level
   in §7.4.
7. **Partition safety.** No peer ever spends beyond `holdings(P, self)`;
   post-heal convergence restores a single agreed ledger.
8. **Projection homomorphism.**
   `project(merge(A,B), e) = merge(project(A,e), project(B,e))`.
9. **Bounded fairness error.** `error(p) ≤ Σ B(p, s) + discretization`,
   measured in simulation against the configured caps. Discretization is the
   quantum plus the position grid: step 3 admits an edge up to one tick past
   `V`, and a seat or wake clamp lands under one tick above it, each `1/16` of a
   unit of the edge's own service (§7.1). Below the position ceiling, that is:
   at it, a seat or clamp lands at the ceiling and every pick there refuses.
10. **Closure dominance.** A replica that observed CLOSING never again
    delegates across that generation, regardless of merge order.
11. **Conflicts surface.** Integrity violations converge to the same explicit
    report everywhere and quarantine their lineage; nothing is resolved by
    timestamp.
12. **Overflow rejection.** Arithmetic that would exceed `Long` fails
    deterministically, never wraps. The one exception is a position, which
    saturates at its ceiling; a pick that would order it refuses with a typed
    outcome instead (§7.1).
13. **Consensus only at the embroidery.** Mint, overlapping reshape, and
    fencing use `:kuilt-raft`; nothing else coordinates, at any frequency.

## 11. Public surface

This section was a signature sketch written before any code, and the code settled
several of its questions differently. Rather than keep a second copy of signatures
to drift, it now says where each sketched piece landed. The declarations, their KDoc
and the compiled samples they cite are the reference; `kuilt-heddle/module.md` walks
them in order.

| Sketched | Shipped | What moved, and why |
|---|---|---|
| `EntitlementLedger` with `Patch<S>?` mutators, `holdings`, `edge`, `activeChildren`, `validate`, `bootstrap(root, mint)` | the same, plus `mint`, `seat`, `spendCaptured`, `lifecycle`, `gauge` | No `EntitlementPath` type: `holdings`/`spend`/`transfer` take the leaf `GroupId`, and transfer rows are keyed by `PathKey`. `bootstrap` takes a `nonce` naming the mint act, so two distinct acts union rather than collide. |
| `class HeddlePolicy(config)` with `pick(parent, children, demand, wake, localHoldings): Allocation?` | `object HeddlePolicy` with `pick(edges, config, localHoldings): Grant?` | Pure and stateless. Each child arrives as one `PolicyEdge` (record, `EdgeSummary`, `Demand`, `Gauge` seat, base issuance, wake offset), so a parent's whole view is one list. |
| `HeddleNode` with `suspend fun reserve(…): ReservationId?` | `HeddleNode`, whose `reserve` is not suspending and returns `ReserveOutcome` | A `null` could not tell an idle lane from a node that may not spend (#1892). `reserve`/`complete`/`cancel`/`earmarked` are the `FairShareExecution` interface both nodes implement. |
| `heddleStatic(seam, selfId, mint, clock, config)` | adds `root`, `topology` and a per-boot `epoch` | Every peer passes the same root, mint and tree, so all start from one ledger; `epoch` orders a restarted peer's demand past its dead incarnation's (#1666). |
| `heddleGoverned(seam, selfId, raft, clock, config): HeddleNode` | returns `GovernedHeddleNode`; adds `root`, `incarnation`, `epoch` | The control verbs suspend and return `ControlOutcome` (`Applied` / `Conflict` / `Indeterminate`), and `schedule` returns `ScheduleOutcome`, so the governed node is its own type rather than a `HeddleNode` with extra methods. No genesis: all supply and topology come from the log. |

`HeddleConfig` carries the policy caps (`PolicyConfig`), the per-peer holdings cap of
§8.2, the demand TTL, the `Quilter` and heartbeat settings, and an injectable
`Random`. Time is a dependency: the scope, the clock and the RNG are required
injections, never defaults. Internals follow the house rules as sketched: an atomicfu lock, no
`limitedParallelism(1)` confinement, and cancellation-safe best-effort sends.

### 11.1 The topology, the draft, and the diff

Declaring the group tree by hand (`prepare`/`activate` per edge) is correct but
noisy, so a tree is a value: a `Topology` (`:kuilt-heddle-core`), a named root with
nested `Group`s and `Lane`s, each weighted against its siblings. Every way of
building one checks it, so a `Topology` that exists is valid. An admin's file
decodes to a `TopologyDocument`, and `Topology.from` returns either the tree or
every problem in it at once. In code, the **draft** builds the same value:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleTopologyDraft -->
```kotlin
val fairness = draft("pool") {
    group("acme", Weight.of(3)) {
        lane("interactive", Weight.of(3, 2))
        lane("batch")
    }
    group("tenants") { runtimeChildren() }
}
```

A `Topology` carries no mint plan: supply is not part of the admin's view, and an
engine tops it up itself. `Topology.lower(generation)` turns the tree into the
`AttachmentRecord`s a ledger is prepared with, under ids that are the same on every
target (`pool/acme/batch@1:1#0` is `batch` in `acme`, weight 1, generation 0).

A reload is `Topology.diff(new, ledger, generation)`. It returns `TopologyStep`s —
`Prepare`, `Activate`, `Close`, `Retire`, `Reconcile` — in the order §5.3 needs, and
it reads which edges exist from the ledger rather than from the previous tree, so a
reload after a restart, or one that arrives while an earlier one is still draining,
needs nothing else. Because a weight is a sibling relationship, a change of weight
is always a new generation (§3), and the group's fairness history restarts at its
parent's front. A rename or a move is a delete plus an add (§5.3). The steps are
applied by an interpreter that holds the ledger's only writer; the contracts it must
keep are on `TopologyStep`.

Children the consumer creates while running hang under a group marked with
`runtimeChildren`, in their own id namespace (`pool/tenants/!c-7f3a`), so a runtime
name never collides with a declared one. `Topology.attachRuntimeChild(group, name,
ledger, generation)` and `detachRuntimeChild(group, name, ledger)` list the steps for
one child, at the marker's weight, numbered by the same generation floor as the diff.
Which runtime children exist is the ledger's to say, and the diff owns them as it owns
declared edges: under a marker the new tree keeps, an attached child is kept, history
and all; a new marker weight regenerates it on a new edge; and when the marker goes, or
its group does, the child is closed. Each detach leaves a retired edge behind, and for
now it stays for good: until the forgetting in §5.5 is built, keep a runtime child
attached rather than detaching it when it goes idle.

A runtime child is a leaf, one level below its marked group: it cannot carry a marker
itself, so nothing discovered while running has children of its own. §1's "any depth"
is met today by declared groups, which nest freely and reshape on a reload, and not
yet by runtime ones. A tree found at runtime two levels deep (say, a repository and
then its authors) has to declare each repository as a marked group and reload as
each one appears. Letting a runtime child have runtime children is tracked by #3314.

### 11.2 A ceiling: "run no more than N of these"

An operator sometimes wants to say "never run more than three of these at once"
about one part of the tree: three builds of one repository, say, however idle
the rest of the machines are. That is a ceiling. It gives up keeping every slot
busy, on purpose, and only where someone set it. Without one in heddle, the
caller has to enforce it outside, beside the fairness it interacts with. This
section records how a ceiling will be declared and enforced in the
single-process engine, `LocalHeddle` (`kuilt-heddle-core/module.md`).

#### As built: the engine owns no concurrency

`LocalHeddle` opens one lease per slot it picks, and the caller owns
concurrency: the engine keeps only total capacity, to size its supply. The
nearest existing knob, `PolicyConfig.perChildOutstandingCap`, is a different
concept. It is one figure for the whole policy, counted in service units, and it
trims a grant (§7.3 step 1) rather than skipping a candidate. `LocalHeddle` pins
it at `Long.MAX_VALUE`.

#### The design: filter the candidates

Decided in #3324, and not yet built.

**Declaring it.** `maxConcurrent: Long? = null` on `Topology.Group`,
`Topology.Lane` and `Topology.RuntimeChildren`, mirrored in `TopologyDocument`.
`null` means no ceiling, and nothing changes.

- On `RuntimeChildren` it caps **each** attached child, not all of them together.
- **0 means pause:** picks stop under that node. It is not a problem.
- A negative value is the one new `TopologyProblem.Kind`.
- The ceiling lives in the engine, read from the topology last applied
  (`lastApplied`), not in the ledger. `Topology.diff` keeps an existing edge by its
  weight, so it emits no step for a change of ceiling: no new edge and no
  history reset.

**Counting.** In leases: the open leases whose captured path passes through the
node, draining ones included. That is derived from the engine's open leases, so
nothing new is persisted. The scratch engine `explain` and `previewPicks` run on
must carry the open leases too; today it starts with none.

**Enforcement.** Before a pick builds its demand views, every leaf under a node
at its ceiling is dropped from `waiting` and from `eligible`.

- `admit`, `extend`, `complete`, and an `apply` that moves leases are never
  refused.
- Lowering a ceiling preempts nothing. Running leases finish, and picks resume
  once the count is under the ceiling.

**Fairness.** A node at its ceiling, or paused, counts as idle at its parent.
When it can take work again it wakes at the parent's front through the existing
wake clamp (`settleJoiners`), with no banked credit (§10 invariant 6).

**Reporting.**

- `explain` gains a `WaitExplanation` arm of kind `AT_CEILING`, carrying the
  capped group and its limit. Its place in the order is after `NO_LIVE_LINEAGE`,
  before `SUPPLY_EXHAUSTED`, relative order with the overflow fix's arm agreed in
  that PR (#3313).
- `entitlement` treats a node at its ceiling as not competing.
- When every waiting leaf is capped, `acquire` returns the existing
  `NothingEligible`, whose KDoc widens to say so. There is no new
  `AcquireOutcome` arm.

**Compatibility.** A new property on a public data class changes its
constructor and `copy` in binary, so the old constructors are kept.

**Why it belongs in heddle.** The preview counts the leases it opens against a
ceiling, and `explain` reports "at ceiling" as its own reason. A gate in the
caller could do neither.

**The plan's ruling Q6 still holds.** Q6 says the engine has no slot gate. A
ceiling is not one: it filters candidates using the engine's own open leases,
and never decides whether the caller runs anything.

#### Settled — don't reopen

- Reusing `perChildOutstandingCap` as the per-node form: it is policy-wide,
  counted in service units, and trims a grant rather than skipping a candidate
  (#3324).
- Counting reserved cost instead of leases (#3324).
- Keeping the ceiling in the ledger: it is engine configuration, so changing it
  emits no topology step (#3324).
- Letting a capped node bank credit while it is skipped: it counts as idle and
  wakes at the front (#3324).
- Refusing `admit`, `extend`, `complete` or a lease-moving `apply` at a ceiling,
  or preempting when one is lowered (#3324).
- A new `AcquireOutcome` arm for "everything is capped": `NothingEligible`
  covers it (#3324).
- Treating a ceiling of 0 as a problem: it means pause (#3324).

#### Open

- Where `AT_CEILING` sits relative to the arm the overflow fix adds to
  `WaitExplanation`. Tracked by #3313.

### 11.3 Previewing the next picks

An admin's screen wants to show who goes next: of the twelve jobs waiting, which
three run when the next three slots free up. The engine answers without moving
anything, by playing the picks forward on a throwaway copy of itself. Two things
limited that answer. The caller had to fix each pick's reservation up front,
though in practice it depends on which leaf wins, since each leaf's next job has
its own estimate. That one is fixed: each leaf can now bring its own queue. And
it needs a live engine, so a reader holding only a saved snapshot has to restore
a whole engine to ask. This section records the fix for both.

#### As built: each leaf's own head, on a live engine

`LocalHeddle.previewPicks(waiting: Map<GroupId, Int>, eligible, reservations:
List<Long>)` takes each leaf's queue as a count, and pick `i` reserves
`reservations[i]`. It runs real `acquire`s on a scratch engine that carries a
copy of the wake memory, so it changes nothing, and it stops at the first call
that would not acquire.

**Preview with each leaf's own head.** `previewPicks(queues: Map<GroupId,
List<Long>>, eligible, reservation = defaultReservation)`, built in #3321. Each
leaf's queue lists its jobs' reservations, head first, and it runs on the same
scratch engine.

- Each pick does what a real caller does: `acquire` with `reservation`, then, when
  the winner's head is larger, `extend` the lease by `head − reservation`. Then it
  pops the head.
- `extend` makes no pick, so the order is the slot-length order, the contract
  ruled in #3200 (plan Amendment 9), and this ships without settling the rest of
  #3200. The head sets only what the lease holds, `max(reservation, head)`. A
  head below the reservation would need the lease to shrink, which is #3200's
  question.
- The list stops at the first refusal, including the position-ceiling refusal
  the overflow fix adds (#3313). When the `acquire` lands and its `extend` is
  refused, that pick is left out: the caller's lease would hold less than its
  job needs.
- Every queued reservation, not only the heads a pick reaches, is checked
  against `1..MAX_RESERVATION` before anything runs.
- Pinned: across random histories, mixed weights, with and without a reconciled
  capacity, the preview equals the leaves the real loop picks on the same state,
  and changes nothing.
- The count-based `previewPicks` stays.

`explain` and `entitlement` read the live engine too. To ask without moving the
real engine's wake memory, a caller holding a snapshot restores a throwaway
engine from it, under `epoch + 1`, on every decision.

#### The design: the same answers, on a snapshot

Decided in #3321, and not yet built.

**A view of a snapshot.** `snapshot.view(policy): LocalHeddleView` carries
`previewPicks`, `explain` and `entitlement`, and the engine's own methods
delegate to it, so the two cannot disagree.

- The policy is a required parameter, because a snapshot does not keep one.
- The view is built from the value `persist()` returns, `LocalHeddleSnapshot`.
  `snapshot()`'s `LocalHeddleState` will not do: it lacks the reconciled capacity
  and the topology last applied, and a pick reads both.

The overflow fix's slices it waited on, which make each call atomic and add the
position-ceiling refusal, landed in #3352.

#### Settled — don't reopen

- Settling per-leaf request length first: the preview models the real
  acquire-then-extend sequence, which changes no order (#3321).
- `previewPicks(queues: Map<GroupId, List<Long?>>, …)`, with `null` for an
  unknown length, as #3229 planned: superseded by the `List<Long>` form. Once
  type arguments are erased the two have the same JVM signature, so only one can
  exist (#3321).
- A snapshot view that takes no policy: the snapshot does not keep one (#3321).
- A view over `LocalHeddleState`: it lacks the capacity and the last applied
  topology (#3321).

#### Open

- A head below the lease's reservation, which would need the lease to shrink.
  Tracked by #3200.

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

> **What shipped.** The opaque `Lane` on `TaskDescriptor` (in `:kuilt-warp`) and
> execution-side enforcement (`HeddleAdmissionControl`, in the
> `:kuilt-warp-heddle` satellite). A producer tags a descriptor with
> `TaskDescriptor.inLane("acme/interactive")`. The `Draft` modifier `.lane()` and
> the scoped `warp.inLane { }` below are not built, and neither is
> producer-side admission.

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
`reserve(leaf, maximumCost)` answering `ReserveOutcome.NoHoldings` (or, on a governed
node, `GateClosed`) defers the task
until entitlement flows in, throttling each lane to its share while the ring
still decides placement. Producer-side admission (reserve at `shuttle` time,
bounding `WorkQueue` growth itself) is the follow-on.

**A lane is charged once per execution, not once per task.** Costing starts at
one unit per execution and generalizes to caller-supplied `maximumCost` with
renewable quanta for long tasks (§4.4). Every run of a task reserves and
settles, so a task that is retried — or that two peers happen to run at the
same time — costs its lane once per run.

That is deliberate, not a rounding error. Entitlement apportions *capacity*.
Warp's free claim path is at-least-once: under ring or roster churn two peers
can independently claim and run the same `TaskId` before the results board
converges, and when they do, two units of capacity really were consumed —
both peers really did the work. Charging once would let a churning lane push
its own waste onto every other lane, which is the opposite of what a
fair-share scheduler is for.

The gap between executions and tasks is the **duplicate-execution rate**: zero
at stable membership, rising only during churn. It is the one place a lane
pays more than the work it asked for, and it is why §8.2's bound is stated
over executions.

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

> **All six phases have shipped.** The text below is the plan as written, kept
> because its acceptance lists name the properties the tests pin. Where the
> outcome differs, a note says so.

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

**Home:** as built, the opaque `Lane` lives in `:kuilt-warp` and the
enforcement in the `:kuilt-warp-heddle` satellite (`HeddleAdmissionControl`),
since Gradle KMP has no optional dependency; warp core never sees a heddle type.
**Work:** `Lane` field on `TaskDescriptor` (defaulting to a root lane),
execution-side `reserve` in the claim path (§14.4), shared liveness/mux
wiring through `WarpNode`.
**Acceptance:**
- Two lanes with weights 3:1 converge to the ratio in executions (§14.4) on a
  grid simulation; the untagged default lane reproduces today's behavior
  bit-for-bit.
- Lane exhaustion defers tasks (they stay pending) — never drops them; work
  resumes when entitlement flows in.
- Ring placement is unchanged: the owner of every task is identical with and
  without the heddle enabled.
- Consensus spend on the coordination-free path remains zero (asserted by
  the simulation's message accounting).
