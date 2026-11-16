# `:kuilt-heddle` — weighted fair-share scheduling over kuilt

> **Status: design spec, built (pre-1.0, Beta).** All six phases of §15 have
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
attaches while running is always a leaf; §11.1 says where that stands):

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
recorded. Unknown-cost work reserves in renewable quanta.

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
memory and on disk, without bound. This section records how a ledger forgets
those edges while keeping the books exact, when the engine has it forget them,
and why peers cannot yet do the same. Both halves are built.

#### Kept for good, summed once

`holdings(parent)` subtracts every child's net inflow, `effIssued − returned`,
retired children included: a retired child that served has a net inflow equal
to what it spent. Task L0 (#3189) pre-sums the retired children per parent and
replica in an index, `retiredOutflow`, which is derived from the ledger's own
slots and never encoded. That made reads fast at 50,000 retired edges. It
bounds the cost of a read, not the size.

Three other readers need a retired edge:

- **Conservation.** `leafSpentTotal()` sums each edge's effective leaf spend over
  every edge, retired included.
- **The generation floor.** It is one global maximum over every edge, retired
  included, so that no id is ever reused.
- **`Topology.diff`'s `hasChildEdges` test.** It asks whether a group has any
  child edge in any lifecycle, to decide whether a reload needs a `Reconcile`.

#### As built: the ledger forgets

Decided in #3192. A ledger can forget an edge, and a replicated ledger refuses
one that has (#3357). When the engine forgets is the next part, *As built: the
engine forgets*.

**The seventeenth component, `forgotten`.** It is an internal `ForgottenEdges`,
written through `CanonicalMapSerializer` at every level, with its own arm in
`:kuilt-heddle`'s `EntitlementLedgerCanonicalEncodingTest`. It holds:

- `outflow`: per parent, a map from replica to the folded `effIssued − returned`
  of its forgotten children, the term `holdings` subtracts;
- `leafSpend`: per replica, the folded effective leaf spend of the forgotten
  edges, so that conservation still balances;
- `generationFloor`: the highest generation any forgotten edge carried, `-1` for
  none, since the floor is a single global maximum.

`holdings`, `leafSpentTotal`, the generation floor and `hasChildEdges` each read
the fold beside the live edges. A parent gets a row in `outflow` once any of its
children is forgotten, even a row that nets to nothing, so it still counts as
having child edges. A ledger with nothing forgotten holds `ForgottenEdges.NONE`,
which a default `Json` or `Cbor` does not encode, so its bytes are what they
were before the component existed.

**When an edge may be forgotten.** When all of these hold. `forgetRefusal`
names the first one an edge fails, as a `ForgetRefusal`, checking in the order
`ForgetRefusal` declares:

- the ledger knows the edge, its id is in the topology grammar (the floor can
  only carry an id that has a generation), and one record names it;
- it is RETIRED, or PREPARED;
- its child has no child edges in any lifecycle, forgotten ones included, so
  leaves go first;
- no transfer row, base or relocation, sits at its path;
- a PREPARED edge has no counter and no gauge;
- a RETIRED edge's books closed: charged plus returned within issued, no
  negative effective spend in total, no negative net inflow or effective leaf
  spend in any one replica's share, nothing outstanding, nothing stranded on it
  for any replica, and its child holding nothing.

Whether an open lease's captured path crosses the edge is the engine's
question, not the ledger's. With nothing outstanding, no lease is charging it.

`forget(edges)` is all or nothing: it returns `null`, and forgets nothing, when
any edge is refused. A RETIRED edge folds its net inflow, its leaf spend and its
generation. A PREPARED edge carries no history, so it folds only its generation,
plus the parent's row. That a forgotten PREPARED edge will never activate is the
caller's precondition. If it is activated anyway, `activate` finds no record and
refuses, and a delta built elsewhere names an id at or below the floor, which
`piece` refuses. A `prepare` of any id at or below the floor returns `null`, so
the refusal comes where the edge is asked for, not as a throw from `piece`. The
floor cannot tell a forgotten id from one the ledger never held, so it refuses
both. That costs nothing, since a diff and an attach number every edge above it.

**Forgetting is not a join.** Every other mutator only adds to the lattice; this
one removes slots. So it is outside §10 invariant 2, scoped to a single writer,
as invariant 4's quiesced-edge exception is. Three guards hold the scope:

- `forget` is internal to `:kuilt-heddle-core`, and `LocalHeddle` is its only
  caller.
- `piece` throws rather than take a fold that is not the receiver's own: a
  ledger with no fold cannot take one, and two different folds do not join. It
  also throws when one side names an edge the other does not hold, at or below
  the other's forgotten floor: an edge the forgetting side may have forgotten,
  coming back. So `piece` is partial, in exactly the states a single writer
  never produces.
- `:kuilt-heddle` never lets a fold in. `ReplicatedLedgerSerializer` refuses to
  decode a ledger that carries one, so the replicator drops such a frame as
  unreadable instead of throwing from `piece` inside its pump. A `Retire`
  command's `witness` decodes through the same serializer, and `HeddleNode`
  refuses to start from a ledger with a fold.

**What `validate()` checks.** Nothing new. An edge's `ClosureViolation`,
`PerEdgeSafety` and `NegativeEffectiveSpend` are checked when it is forgotten,
since nothing is left to check afterwards. The fold is never negative, and that
is the type's rule rather than a `validate()` arm: `ForgottenEdges` refuses a
negative entry when it is built, so a hand-built or corrupt snapshot that
carries one fails to decode.

**The format.** `forgotten` is encoded under snapshot format 1, the one 0.7.5
ships and the first frozen format (§7.1, "Snapshot"). A snapshot v0.7.4 wrote
also says format 1, in a layout 0.7.5 does not read, so a consumer coming from
0.7.4 starts from its topology again. Every encoded change for 0.7.5 lands under
format 1 before it ships.

#### As built: the engine forgets

`LocalHeddle` forgets automatically, with no new API, in the swap that lands
each `apply`, `attach` and `detach`. That swap forgets:

- every RETIRED edge `forgetRefusal` passes and no open lease's path crosses.
  Every one, not only the edges this swap retired, so an edge an open lease
  held at an earlier swap goes at the first swap after that lease settles;
- every PREPARED edge the topology in force does not want: a diff of that
  topology would leave it prepared and name it in no step. It moves the floor and
  gives its parent a row, nothing else. An attached child's prepared edge is kept
  unless the child has another edge the diff adopts, since a prepared edge may be
  what says the child is attached.

The same swap prunes each forgotten edge from the allocator's wake memory,
`AllocatorMemory`, since `forgetDepartedChildren` finds an edge's parent through
its record and could not once the record is gone. The sweep reads every edge's
lifecycle once. It walks the counters for their replicas twice, once to choose
the candidates and once to forget them, rather than once per candidate.

Measured on the JVM: 2,000 users each attached, served once and detached leave
the snapshot within 64 bytes of where it stood after the first 20. Before, each
one added about 410 bytes. Where the edges forgotten are retired ones, an engine
that forgets and one that keeps everything give the same picks, holdings and
conservation on the same calls. A forgotten prepared edge does change a later
diff: one that wants that edge back prepares a new edge above the floor instead
of adopting the old one.

#### Settled — don't reopen

- An explicit compaction call: forgetting happens in the retire's own swap, so
  there is no new API (#3192).
- Leaving the allocator's wake memory to prune itself later: its pruning needs
  the record that forgetting removes (#3192).
- A snapshot format bump per encoded change: one number per release, and 0.7.5's
  is format 1 (#3192, #3489).
- Any coupling to the position ceiling. No pick reads it, and nothing keeps
  positions under it (§7.1, #3472), so this forgetting is not its
  prerequisite. Generation turnover, which would have made it one, was not
  chosen.
- A `validate()` arm for a negative fold: the type refuses one, so there is
  nothing left for the diagnostic to report (#3357).

#### Open

- **Forgetting between peers.** A replicated ledger would need causal stability
  per edge plus a tombstone, and a stored aggregate risks the drift that rejected
  candidate B ([`heddle-ledger-design.md`](heddle-ledger-design.md), *How this
  was chosen*). Tracked by #3332.
- **A reweighted or reparented child's old edge stays.** A reweight or a move
  retires the child's old edge and gives it a new one, so the child lives on and
  usually holds something, and `CHILD_HOLDS` refuses the old edge while it does.
  Only a child that is gone for good compacts reliably.
- **A group whose children were forgotten is never forgotten itself.** Its row
  in the fold keeps it counting as having child edges, and leaves go first. An
  attached child is always a leaf, so this bites a declared group removed after
  its attached children came and went.

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
replicated `Gauge` rather than off its record — a position paired with a fold: the base
issuance the writer had observed when it asserted that position. Nothing moves a fold, and
nothing rebases a parent (below). The position is
`P(e) = ticks(e)`, a 128-bit count on the edge's own grid of `G` ticks to a unit
(below), which every seat and checkpoint writes exactly and never caps (#3353). v0.7.4
kept the position in a `floor`, a fraction of virtual time; a gauge carrying a non-zero
one is refused at decode on every path a gauge arrives by, never read as if it had none,
which would put the edge lower than it was written:

```text
gross(e) = P(e) + (baseIssued(e) − folded(e)) · G     (no gauge ⇒ baseIssued(e) · G)
raw(e)   = gross(e) − returned(e) · G
v_raw(e) = raw(e) / (G · w(e))
```

A position is how much service an edge has had, counted from its start, and it only
goes up. Until step B the ledger also kept a per-parent **origin**, a replicated
max-register every child read its position less of, which the rebase raised; it is
gone from the ledger and the wire (#3472, below). Snapshot format 1 reads a 0.7.5
ledger through a frozen surrogate that still holds the origins, and translates each
into its children's positions, rounded up.

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

**A pick orders the full 128-bit position, so no position refuses it.** The
gauge stores its position absolute, wide, exact and uncapped. The raw position,
the front, a wake clamp `front − raw`, the stored clamp, the effective position,
raw plus clamp, and the deadline `p + q · G` are all computed and compared in 128
bits. Nothing is ever capped. Until #3313 and #3353 a pick narrowed the effective
position to a `Long`, refused with `PositionCeiling(parent)` at the ceiling, and
a busy parent beside a starved child met it after about 65,000 leases of the
largest size.

**A position still has a ceiling, for the public reads (#3313).** The ceiling is
`(Long.MAX_VALUE − 2⁴⁸) / w.denominator` ticks. Dividing by the denominator keeps
a position's virtual time, `p · w.den / (G · w.num)`, in `Long`, for
`virtualService` and its kin, which report the ceiling past it, the penalty
side. No pick depends on it, and nothing keeps a position below it: a busy parent
passes it in time, and only those reads saturate (#3472).

**What bounds a parent's life is the `Long` counters.** An edge's effective
issuance and the root's minted supply are `Long`s, about 9.2 × 10¹⁸ units. No
call throws at them: a pick refuses a little before either runs out, and work
already running uses the room it keeps. "The lifetime limit", below, is the
rule.

- **No credit leaks through.** Nothing is narrowed before the effective
  position, so no front, seat or clamp is taken against an understated value. A
  seat is stored at the exact front, past the ceiling if that is where it is. A
  clamp carries a waker the whole way up to the front, however far behind it
  idled. The pick over either orders it exactly (§10.5).

**Nothing rebases (#3472).** Gross positions grow with lifetime service under
the parent, and a lane that idles between picks has a wake offset, the front less
its raw position at its last wake, that grows by every stretch it sits out. So a
busy parent's positions climb past the ceiling and stay there, and that is the
whole consequence: the pick reads the **effective** position, raw plus offset, in
128 bits, and orders it exactly. A newborn is seated at the front, a clamped waker
at the front less its sleeper credit, and a returner where its parent's net-service
clock puts it (§7.2), each exactly and on its own grid, wherever that is. No seat,
clamp or return is held at a floor, since nothing is ever put below zero but a
child its own `cancel` walked back, which the public reads hold at its floor.

The engine used to raise each busy parent's origin before a child passed a
sixteenth of its ceiling, aimed at its demanding children's mean and held inside
a window `[−C, C)` per child (the window rule), so the public reads stayed exact.
That rebase moved every child alike and so changed no pick: a twin that never
rebased made the same picks on the seven-lane `LocalHeddle` run of #3329 and on a
governed cluster, past its ceiling, before slice 3 deleted it. What it kept exact
were the reads alone, which step B makes wide instead (slice 6).

On a governed cluster of three peers under `MultiNodeRaftSim`, `HeddleAcceptanceTest`
runs every shape the rebase was written for past the old ceiling. One scheduling
peer that gets exactly one grant per call picks as `LocalHeddle` does on every
call. A 60/30/10/1 parent whose weight-1 child runs alone in bursts keeps granting.
So does a weight-60 newborn joining a busy weight-1 runner, seated level with it,
past its own ceiling. And so does a restarted scheduler whose weight-60 child idled
through the whole run before the crash: that child comes back level with the
runner (§7.2, the restart rule). Two schedulers across a partition cannot match a
single engine; there the run checks that the peers converge and every minted unit
is accounted for. `LocalHeddleLongRunTest` and `LocalHeddleWindowRuleTest` pin the
`LocalHeddle` side: every step leases, and a closing stretch with every lane
waiting hands each its weight's share.

**Returns no longer reach it** (#3360). The ceiling is measured on the raw
position, net of returns, and the gross position
under it is 128-bit (#3353). Before that, the gross position was what saturated,
and it grew by every returned unit as well as every charged one: at the largest
reservation charged 1 unit, a weight of `3/7` refused after 4,681 leases. It now
makes 100,000 with no refusal.

**A weight set too fine for one full lease is refused when it is applied
(#3353, P1′).** Keep every weight's denominator at or below 30,719; a finer
weight set is refused when it is applied. The limit holds at any lease size,
because it is sized for the largest lease: `H` is the constant
`MAX_RESERVATION · G`, and `HeddlePolicy.pick` caps every grant at
`MAX_RESERVATION`, whatever `PolicyConfig.quantum` says, so the refusal is a
guarantee on both engines. It is necessary, not sufficient, as the step term
below shows.
The rebase fired only once a child passed its `R`, so a child that an
under-charged `complete` parked between `C − H` and `R` (`H = MAX_RESERVATION · G`)
was never rebased, yet its next full lease passed `C`, where the reads saturate.
With no rebase that reasoning has nothing left to protect, and W1 replaces it
(slice 5).
Before picks were wide, `acquire` refused there for good. The review measured `{1/30,721, 1/30,722}`:
one lease charged `1.172 · 10¹²` and every later acquire refused, while
`1.1715 · 10¹²` ran. So the budget also asks, for every child `i` of the set,

```
C_i − R_i > H + gridStep(S) · G · n_i / d_i
```

in 128 bits. The step term is the whole set's, so activating one child can
refuse on behalf of another: `1/3` beside a weight of `1.85 · 10¹⁷` triples the
heavy child's step past its window. The same term refuses `{1, k}` for `k` above
about `0.94 · 2⁵⁹`, and a set of several fine weights whose denominators share no
factor, since the step grows with their lcm. A spread like `2⁴²/1023` beside
`1/1021`, which once wedged a parent by quantisation alone, is refused this way.
At the edge, `{1/30,718, 1/30,719}` runs at full leases and `{1/30,719, 1/30,720}`
is refused.

**Upgrading.** Durable state that holds a weight set this now refuses, a
governed log with such a `Prepare` or a `LocalHeddle` snapshot, needs those
edges closed before the upgrade, or a fresh log, because a replay refuses them.
`LocalHeddle.restore` does not check the budget, so a restored parent over it
keeps running, but a new sibling there is refused.

`HeddleNode.schedule` meets no ceiling either: a grant past it is made, as
`LocalHeddle` makes one: no position refuses it.
`apply` keeps the weight products in range the
same way: an activation whose weights would put its parent's `L`, `S`, `G · S` or
`S · d` out of `Long`, or fail P1′, is refused with a `StepRefusal`. Where a topology act enters a
replicated ledger the same rule is checked. The control log refuses a `Prepare`
whose own weight fails it and an `Activate` that fails it beside the parent's
active children, on its log-pure projection (§9). `heddleStatic` checks its
starting tree, and `HeddleNode`'s ungoverned `prepare` and `activate` check this
peer's view only. That does not keep the merged ledger in range. Two ungoverned
peers activating siblings at once can merge past the budget, and a governed
peer's data plane can briefly hold an over-budget active set when gossip brings
it a later `Activate` before an earlier `Close`. So `HeddleNode.schedule` checks
the parent's active children again before it seats anyone, on every round, and
answers `ScheduleOutcome.WeightBudget(parent)` with nothing written. As at the
ceiling, a refusal met after grants answers `Delegated` and keeps the rounds
already landed, and the next call answers the refusal. The check is the whole
parent's: it refuses even a pick whose own candidates would fit. The node logs
one warning, naming the active edges, each time a parent enters that state.
Either refusal changes nothing, as `LocalHeddle`'s does: the seats, the wake
clamps and the first pick are worked out on a copy of the allocator and land as
one patch. A first pick that finds nothing to grant writes nothing either, and
keeps only what it observed: the wake clamps and who was waiting. So an idle
`schedule` writes nothing to the replicated ledger, and a newborn is seated by
the call that first grants at its parent.

Why a grid at all, against the exact `Rational` this section used to prescribe:
a joiner stored the front, a weighted mean whose denominator carries `Σw` of the
set it was taken over, and the next front averaged that value in again. Every
join could multiply a stored denominator by another `Σw`, so a pick overflowed
`Long` within tens of picks once the waiting set changed between them, and the
overflowed state persisted. A position's denominator is fixed by its edge's
weight, so it cannot grow. The grid is `2⁻⁴` because each bit of it comes out of
the `Long` ceiling on a position: at `2⁻⁴` the churn fairness error measured
0.0170 against 0.0167 exact, and a finer grid buys nothing a test can see.

#### Future: positions with no origin (step B)

Decided 2026-11-15. Step A lands first: every pick compares the full 128-bit
position, so nothing narrows a position to a `Long` any more. This entry is what
follows it. Each slice below moves its part into the text above when its code
merges, and the entry is deleted with the last one.

**Two releases (@keddie, 2026-11-15, #3501).** 0.7.5 ships slices 1, 2 and 7 and
the snapshot restart, and may break the API, the wire and the stored formats.
Slices 3 to 6 ship in 0.7.6, which **breaks no public signature**: every public
declaration 0.7.5 publishes is still there, with the same types. The committed
API dumps enforce it (`checkKotlinAbi`, #2684): across slices 3 to 6 a dump
gains lines and loses none. The wire between peers may still change, since
peers of different releases never share a cluster. Snapshot format 1 may not:
0.7.5 freezes it with real origins in it, and 0.7.6 reads it (slice 4).

**In plain terms.** The origin, the rebase, the window and the floor all exist for
one reason: a pick used to order positions in 64 bits, so a busy parent's positions
had to be pulled back down before they ran out of room. Once a pick orders them in
128 bits there is nothing to pull back. A position is then simply where an edge
was seated plus the service it has had since, and no rebase moves it. What is
left to guard is the seat itself: a late joiner is seated level with siblings
that may have run a long way (the position limit, below).

**What goes.**

| Piece | What it protects today | After step A | Step B |
|---|---|---|---|
| The parent origin (`ParentRecord`, `raiseOrigin`, `originTicks`) | Keeps the effective position a pick narrows inside `Long` | Nothing narrows, so it protects nothing | Deleted, from the ledger and from the wire. The two public reads of it stay, deprecated: `EntitlementLedger.origin` answers zero, and `PolicyEdge.origin` is read as before by the pure policy and never set by an engine |
| The rebase (`raiseOriginBeneath`, `raiseBeforePick`, `preSeatRaise`, `chargeRebase`, `originStep`, `wholeSteps`, the three "raise changed no pick" checks, `Rebase`, `RebasingPick`) | Raises the origin before a child reaches its ceiling | A translation of every child alike, which changes no pick | Deleted. `rebaseEnabled` and `rebases`, behind `@HeddleInternalApi`, stay as deprecated members that do nothing and read zero. `gridStepOf` stays, for the budget and the format 1 reader |
| The window rule and the floor (`positionFloor`, `windowBound`, the floor arms of `landing`, `shiftOf` and `clampWakers`) | Keeps a child a rebase left below zero inside `[−C, C)` | Only a rebase puts a child below zero | Deleted. A seat lands at the front, a clamp at the front less the sleeper credit, a returner where its clock puts it |
| The ceiling (`positionCeiling`, `rebaseThreshold`, `POSITION_HEADROOM`, `inWindow`, `readVirtual`, `peakPositionFraction`) | The largest position a `Long` pick orders | Unreachable | No engine reads it. It stays for two readers: the weight budget, whose constants it is, and the deprecated `Rational` reads, which report it past it |
| P1′'s lease clause of the weight budget (denominators at most 30,719) | One full lease from just under `R` stays below `C` | No `R` and no `C` | Kept exactly, as the set of weights an apply accepts. What keeps a pick inside 128 bits is the position limit below, not a clause on the weights |
| The multi-scheduler margin (#3458, denominator × schedulers under about 30,000) | `k` peers granting one child at once stay under `C − R` | No `C` | Gone. `k` concurrent grants only add to the edge's issuance |
| The below-zero arm of the first-sighting rule (§7.2) | A child a fresh allocator never watched, sitting below the origin, is clamped | Only a rebase puts a child below zero | Done in slice 2: the restart rule (§7.2) replaced it |

**What stays, and why.**

- **The grid** of 16 ticks to a unit, and the two roundings, both up (§7.2).
- **The seat rules.** A newborn is seated at the front of the demanding children
  it joins, or level with the furthest-ahead child when none is demanding. A
  child seen idle and never seen to stop is clamped to that front. Neither rule
  read the origin: it cancelled out of both.
- **The net-service clock for a returner (#3376, #3416).** It reads issuance and
  returns, never a position, and a shift is a distance, so an origin never
  entered it. Only its floor arm goes. `BurstLagTest` pins it unchanged.
- **The weight budget, whole:** the `Long` products `L`, `S`, `G · S` and
  `S · d`, and P1′'s lease clause. Weights are still pairs of `Long`s, a front
  still forms those products, and the set of weights an apply accepts does not
  move (below).
- **`MAX_RESERVATION`,** the cap on a grant and on a sleeper credit. The cap on a
  grant now keeps `q · G` in `Long`. The cap on a sleeper credit is policy: the
  credit a waker may keep is bounded (§10.6), and one lease is the bound.
- **The gauge's pairing of a position with a fold, and its componentwise join**
  (#1752). Nothing about it depended on an origin.

**What bounds a position now.** Two things, and the first is reached long before
the second.

1. **The `Long` lifetime counters.** An edge's issuance and an epoch's mint are
   `Long`s, about 9.2 · 10¹⁸ units. Before slice 7 they threw. Measured on `main`
   at `3083d775f`, JVM only: two weight-1 lanes at the largest lease, charged in
   full, run 524,287 leases, and the next `acquire` throws
   `ArithmeticException` from the mint. Charged one unit each they run
   1,048,574, and the edge's issuance throws. Step B does not create this
   limit. It makes it the one a caller meets, so it gets the typed arm the
   ceiling had, on the calls that pick, and no call throws there any more. "The
   lifetime limit" below is the whole rule, built in slice 7: the same run now
   ends in `LifetimeExhausted` after 507,903 leases, `(Long.MAX_VALUE − 2⁵⁸) / 2⁴⁴`
   (`LocalHeddleLifetimeRunTest`).
2. **128 bits, which the position limit keeps out of reach.** A check on the
   positions themselves, where they are formed. It replaces W1, the clause on
   the weights this entry first proposed (#3501).

**The position limit.**

*In plain terms.* A child that joins a parent is put level with the children
already there. Level is a virtual time, and the same virtual time is a bigger
number on a heavier child: sixteen ticks a unit, times its weight. So a heavy
child that joins late inherits, in one step, everything its lighter siblings
ran up before it came. Nothing about the weights in force says how high that
is. So the engine checks the number itself, at the only moments it can jump:
when a child is put level with the others. A landing that would not fit is not
made, and the caller gets the answer it already gets when a lifetime counter
runs out.

*Why no rule on the weights can do it (#3501).* W1 bounded a parent's virtual
time by `2⁶⁴ · Σ⌈1/wᵢ⌉` over its current children. Two facts, both measured on
`main` at `e423499d0`, JVM only, through `LocalHeddle`'s public calls:

- **The bridge.** A lane of weight `1/2¹⁴` takes 160 leases of the largest
  size. A lane of weight 4 joins and is leased once. The fine lane is removed.
  A lane of weight `2⁵⁸` joins. Every `apply` lands with no refusal and every
  set passes the budget. Then `acquire`, `explain`, `previewPicks` and
  `EntitlementLedger.virtualService` all throw `ArithmeticException: Int128
  overflow`. At 64 leases they all answer. The rebase does not help: it moves
  the front into the origin, and a seat is written absolute, the origin's share
  plus the front. So the heavy joiner's seat is the same number with or without
  a rebase.
- **The front climbs on reservations, not on service kept.** Two lanes of
  weight `1/2¹⁴` lease in turn, the largest size, and each lease is cancelled
  once the other lane holds one. After 1,024 rounds each edge has issued
  exactly what it has had returned, nothing was charged, and the parent's
  origin stands at `2⁶⁸`. The same bridge then overflows.

So a parent's virtual time is bounded by what its edges have ever reserved, not
by what was used. An edge's reservations are a `Long`. But a reweight makes a
new edge with a new counter, and a cancelled lease returns its units to be
reserved again. No function of the weights ever applied bounds the front.

*The rule.* `POSITION_LIMIT` is `2¹²⁵` ticks. A parent's **load** is the sum of
its active children's effective positions as this allocator reads them, each on
its own grid and each counted by its size. It is formed with a checked sum, and
a load that leaves 128 bits is past the limit.

- **A landing is kept only if it fits.** The seat-and-wake step is where a
  child lands: a newborn's seat, a waker's clamp, a returner's shift, and the
  run of the parent's clock they read. It is already worked out on a copy. It
  is kept only if every value it formed stayed in 128 bits, the load after it
  is at most the limit, and the clock is within the limit of zero either way.
  Otherwise it writes nothing at that parent, the wake memory included.
- **A pick that cannot take its step is refused, typed.** `LocalHeddle.acquire`
  answers `AcquireOutcome.LifetimeExhausted(parent)`, and
  `FairShareAllocator.schedule` and `HeddleNode.schedule` answer theirs, on
  this peer's view, with nothing written. As with `WeightBudget`, the refusal
  is the whole parent's, and a refusal after grants answers `Delegated` and
  keeps them. `explain` answers `WaitExplanation.LifetimeExhausted(parent)`
  for a leaf beneath it, and `previewPicks` stops there. A pick also refuses
  when the load it reads cannot be formed, which only a merged or restored
  state can cause.
- **A charging call is never refused.** Where the step is refused at a parent
  on its path, `admit`, `extend` and `complete` observe nothing there. The
  charge crosses every seated edge as it would have. An edge that could not be
  seated has no room, and the whole-or-nothing rule of the lifetime limit
  applies to it: the charge is recorded at the deepest group it reaches.
- **`LocalHeddle.apply` asks early.** It refuses to activate a child whose seat
  beside the furthest-ahead child would not fit, with a `StepRefusal.Refused`
  that says so. The siblings are untouched and go on being leased. This is the
  same test run sooner, on the one engine that has a single front. It is not
  the guarantee: a child that is already active and wakes at a high front
  never passes through `apply`.

*What a caller does about it.* Three cases. A joiner that cannot be seated:
remove it, and the parent picks again. A waker that cannot be clamped: stop
listing it as waiting. A clock at the limit: the parent is spent, as at a
lifetime counter, and its state is re-seeded.

*Why it holds.* For a state `LocalHeddle` built, with the `Long` products of
the budget and every edge's issuance in `Long`:

1. No position is below zero once nothing rebases (the twin proof's premise).
2. Take the last step kept at a parent, or its birth, where the load is zero.
   The load there is at most `2¹²⁵`. Since then no child was seated and no wake
   offset written, because both happen only in a step. So the load moved only
   by issuance, 16 ticks a unit, by returns, which lower it, and by a child
   closing, which takes a term away. An edge issues under `2⁶³` units in its
   life, `2⁶⁷` ticks. A parent has fewer than `2⁵⁹` active children, since
   `G · S` fits a `Long` and each child adds at least one to `S`. So the load
   stays below `2¹²⁵ + 2¹²⁶`.
3. Every wide value a pick forms is at most the load, or one lease above one
   position. A front's numerator is a sum of some of the positions. The front
   read on a candidate's own grid is at most that numerator, since a candidate
   is one of the children averaged. A deadline adds at most `2⁴⁸`. All are
   below `2¹²⁷`.
4. The one value not bounded by the load is the front read on the grid of a
   child outside the set averaged: a joiner, whether newborn, waker or
   returner. That is the landing, and it is checked.
5. The clock moves only inside a step, so it is always within the limit.

So no call throws or wraps on a position, at any history. The limit could sit
just under `2¹²⁶`. It is `2¹²⁵` to leave the proof a spare bit.

*What it costs.* Nothing at `apply` on a fresh parent: the weight budget does
not change, so every weight set 0.7.5 accepts is accepted, `{1, k}` up to about
`0.94 · 2⁵⁹` included. What changes is the history that used to overflow. Where
0.7.5 forms a value of `2¹²⁷` or more it throws. 0.7.6 refuses from `2¹²⁵`, two
bits sooner, and says why. For `{1, k}` that needs the light lane's virtual
time past `2¹²¹ / k` before the heavy lane lands. Below `k = 2⁵⁸` one edge
cannot reserve that much in its life. At `0.94 · 2⁵⁹` it takes 53% of the light
edge's lifetime, reserved while the heavy lane was away. A heavy lane that
keeps asking is served, holds the front down, and never meets it.

*Replication.* The limit is never a decision of the control log. A front is one
allocator's reading: wake offsets are not replicated, and two peers' gauges
differ until they merge. A log entry must be decided from the log alone, so an
`Activate` cannot be refused on a front, and a rule checked at `apply` would
let two peers disagree about one entry. On a node the limit is met where
`WeightBudget` already is: at `schedule`, on this peer's view, on every round.
Nothing is written clamped, and nothing new goes on the wire. Peers that land
different children high before their writes merge can merge to a load above
the limit. Each position is still one a peer checked, so each fits. The next
pick forms the load with its checked sum and refuses, typed.

*How it is enforced.* By type, not by a scan. The step's result is sealed:
kept, or past the limit at a parent. `VirtualTime.ticksForWide` returns the
same two cases and no longer throws, and there is no throwing overload. A call
site cannot form a landing without handling the refusal.

**The weight budget does not change.** P1′ stays as it is, constants and all:
the denominators at most 30,719, the step term, the `Long` products. Its
ceiling and threshold stop being positions any engine reads. They stay as the
numbers that define which weights an apply accepts. Two reasons. A weight set
0.7.5 accepts must not be refused by 0.7.6. And a control log holds the
`Prepare` and `Activate` entries a budget refused: replayed under a wider
budget they would land, and the replica would not be the one that wrote the
log. The cap on denominators also keeps the clock's reach modest: a lone child
of weight `1/d` runs it `d · 2²⁴` ticks a unit.

**The lifetime limit.** Built in slice 7 (`LocalHeddleLifetimeTest`,
`HeddleNodeLifetimeTest`). Ruled by @keddie, 2026-11-15 (#3475): a new lease is
refused a little before a counter runs out, and work that is already running
is never refused for it and never throws.

*In plain terms.* A scheduler that has handed out nearly all the service its
books can count stops opening new leases, and says so with a typed answer the
caller can act on: leave the work queued. The leases already open go on
extending and settling in the room that was kept back for them. A refusal at
`complete` would be useless, since the time is already spent, so there is none.

*Which counters.* Read from the source, not from the list above. A charge
advances two `Long`s that nothing else bounds:

- **An edge's effective issuance,** `issued + issuedRelocIn`, summed over
  replicas. Every reservation, extension and overrun adds to it on every edge
  of its path. It counts what was reserved, not what was used: a returned
  remainder is counted again when it is next handed down.
- **The root's minted supply,** the sum of every epoch's mint record. It grows
  by what is charged, plus the float a top-up keeps. An epoch's own total is
  not the limit: `holdings` at the root adds all of them, so a restore under
  a new epoch carries the old total with it.

Everything else a charge moves is bounded by one of those two, so it needs no
check of its own: an edge's `returned`, `leafSpent` and `rollupSpent` never pass
its issuance (§10.3), a gauge's fold is its issuance, a lease's `reserved` is
part of its leaf edge's issuance, and the allocator's net-service clock reads
issuance less returns. A gauge's position is 128-bit, and the position limit
bounds it. The lease sequence
grows by one a lease.

*The rule for a pick.* `LIFETIME_HEADROOM` is `2⁵⁸` units, and the watermark is
`Long.MAX_VALUE` less that. A pick may not move a lifetime counter past the
watermark:

- `LocalHeddle.acquire` answers `AcquireOutcome.LifetimeExhausted(parent)` when
  the reservation would carry an edge of the path it picked past the
  watermark. `parent` is the group above that edge. It answers
  `LifetimeExhausted(root)` when the root cannot fund the reservation without
  minting past it; a top-up is trimmed to the watermark first, so the float is
  never what refuses. Either way nothing changes but the wake memory, as for
  `NoLiveLineage`.
- The refusal belongs to the path, not the parent. A sibling whose own edge has
  room is still picked when the full lane is not eligible. While the full lane
  is eligible and next in line, each `acquire` there answers the same refusal:
  a refusal changes nothing, so the pick does not move on. `explain` names the
  group for each leaf behind it, as `WaitExplanation.LifetimeExhausted`.
- `FairShareAllocator.schedule`, and so `HeddleNode.schedule`, answers
  `LifetimeExhausted(parent)` the same way, on this peer's view, in place of
  the throw. As with `WeightBudget`, a first round that refuses writes
  nothing, and a refusal after grants answers `Delegated` and keeps them.

*The rule for a charge.* `admit`, `extend` and `complete` have no lifetime arm
to return: `LeaseOutcome.PositionCeiling` is deleted, not renamed, and
`AdmitOutcome` never had one. They use the headroom, up to `Long.MAX_VALUE`
itself. A charge that still does not fit is **recorded whole or not at all**,
level by level, and the call lands:

- An overrun at `complete` larger than `LocalHeddle.MAX_RECORDED_OVERRUN`,
  `LIFETIME_HEADROOM / 2` (2⁵⁷), is not recorded. It is reported whole in
  `unrecorded`, the reservation is charged at the leaf, and nothing else
  changes, the wake memory included. `admit` and `extend` amounts are already at
  most `MAX_RESERVATION`, so the cap bites only at `complete`.
- An overrun within the cap goes down the path as far as it fits in whole and is
  charged at the deepest group it reaches. That is the rule a draining path
  already follows, with "has no room" beside "is not ACTIVE". The groups above
  a full edge still pay the true cost.
- What the root cannot fund in whole is not recorded. `LeaseOutcome.Done` gains
  a required `unrecorded: Long`, and `charged` is then the cost less that. The
  lease closes either way. At `extend`, `unrecorded` is a reservation that was
  not made, not a cost: the work's cost arrives at its `complete`, so a caller
  summing `unrecorded` over both would count it twice. The same field reports an overrun dropped because
  the root's own child edge is draining, which `Done` reported as charged before (#3493).
- An admission that does not fit reserves nothing. `admit` opens the lease
  with `reserved` zero, so the cost arrives as an overrun when it completes.
  Nothing else changes, the wake memory included: an admission that charges
  nothing observes nothing, and the overrun observes the waiting set when it lands.
- An extension that does not fit reserves nothing and changes nothing, the
  wake memory included. `extend` answers `Done` with `unrecorded` the amount.
  The work carries on, and `complete` charges the cost as an overrun. A
  caller that wants the reason asks `explain`, which answers
  `WaitExplanation.LifetimeExhausted` for a leaf behind a full counter.
- A reweight moves an open lease's reservation down its new path, which counts
  on every edge of it again. A move the new path has no room for within `Long`
  is not made: that child's change is refused, as `StepRefusal.Refused`, until
  the lease settles. A lease admitted with nothing reserved moves with nothing
  to delegate. Slice 7 added this; the entry above did not name `apply`.

No counter is ever filled to its limit with part of a charge, and none
saturates. A saturated counter would break conservation, since the edges of
one path would no longer agree on what crossed them. Trimming the whole charge
to the tightest edge would keep conservation and hand every group above that
edge credit it could spend on its other children. And filling the root's
supply to the brim lets one wrong argument end the engine:
`complete(lease, Long.MAX_VALUE)` on a healthy scheduler would mint to the
limit, and every later `acquire` would be refused. Whole-or-nothing records the
reservation, reports the rest, and the scheduler carries on (§10.1, §10.12).

Whole-or-nothing alone does not stop one wrong cost on a young engine. Where the
lease is the engine's only service, the edge and the minted supply each have
exactly `Long.MAX_VALUE − R` left, so `complete(lease, Long.MAX_VALUE)` fits
whole and leaves the supply past the watermark. So a single overrun is capped:
one above `MAX_RECORDED_OVERRUN` is not recorded at all. Within the cap, an
overrun leaves a counter past the watermark only when that counter was already
within the cap of it, and the headroom holds two of them
(`completeAtLongMaxOnAFreshEngineIsUnrecordedAndTheNextAcquireLeases`,
`anOverrunAtTheCapIsRecordedAndOneUnitMoreIsNot`; ruled by @keddie, 2026-11-15).

Where a charge is not recorded, the lane it ran on has had service its
position does not show. That is credit, the favour side, and it cannot be
spent on a pick: the only way to spend it is an `acquire` across the full
edge, which is refused. It reaches one place, the parent's net-service clock,
which runs short by the overrun, so a sibling returning there comes back that
much less moved. Measured in slice 7 on two twins that differ only in whether
a lane's overrun `O` is recorded, with a sibling away while that lane and one
more ask: the sibling comes back exactly `O / 2` behind, the clock's shortfall
over the two asking, and the lane reads `O` lower
(`anUnrecordedOverrunRunsTheClockShortByItsShare`). The same read on a draining
path is reasoned, not measured: the closing edge has left its parent's active
set, so no clock there counts it.

*What the headroom promises.* Unconditionally: no call throws or wraps at a
lifetime counter, an `acquire` never leaves one past the watermark, and
conservation holds, and no single overrun leaves a counter past the watermark
unless that counter was within `MAX_RECORDED_OVERRUN` of it. Conditionally:
every charge is recorded in full while no overrun is above the cap and the
extensions, admissions and overruns that land on a counter after its last
`acquire` add up to no more than the headroom. No fixed margin can promise
more, because those calls are not refused there. Past it, an `extend` or a
`complete` reports `unrecorded`. A caller that only ever admits is never refused, and has only
the report.

*Why `2⁵⁸`.* The counters count reservations, so a margin is paid for in
leases, and a caller with large reservations feels it:

| Headroom | Leases an edge takes at `MAX_RESERVATION` | At one hour in nanoseconds | Charges of `MAX_RESERVATION` it absorbs |
|---|---|---|---|
| none (today, then a throw) | 524,287 | 2.56 million | none |
| `2⁵⁸` | about 507,900 | 2.48 million | 16,384 |
| `2⁶¹` | about 393,200 | 1.92 million | 131,072 |
| `2⁶²` | about 262,100 | 1.28 million | 262,144 |

`2⁵⁸` costs one lease in 32 and absorbs 16,384 charges of the largest size,
or about 80,000 hour-long ones in nanoseconds. `2⁶²`, the first figure
discussed, costs half the run. A margin tied to what is outstanding under the
edge would move with every completion, would refuse and then accept the same
call, and would still promise nothing about an overrun. One constant is a
rule two peers evaluate alike.

*Replication.* Nothing new goes on the wire and no slot is ever written
clamped. `HeddleNode` needs no charge rule: its `complete` charges at most what
was reserved, so it only moves counters that issuance already bounds. An
edge's issuance is a sum over replicas, so peers that grant one edge before
their writes merge each check their own view, and the merged sum can pass the
watermark. The headroom is what absorbs that: unmerged grants on one edge
totalling at most `LIFETIME_HEADROOM`, 16,384 of the largest reservation. A
`HeddleNode` grant is bounded by the quantum and the holdings it is drawn from,
not by `MAX_RESERVATION`, so the bound is the total. Past `Long` the sum still throws when read,
never wraps, as any state this module did not build does. A mint the control
log applies is an act, not a charge, and this entry does not change it.

**The public reads gain a wide form beside them.** `virtualService`,
`grossVirtualService`, `HeddlePolicy.front`, `FairShareAllocator.virtualTime`,
`HeddleNode.parentVirtualTime` and the three fields of
`WaitExplanation.BehindAtLevel` are `Rational`s over `Long`, read relative to
the origin and held inside the window. With no origin they read absolute
values, and a `Long` rational runs out after about `2⁵⁹ / d` units: one largest
lease at a denominator near 30,000. So `VirtualTime` becomes a public type for
a virtual time: comparable, exact, with a `toDouble()` for display. Each read
gains a sibling that answers it, and no existing signature changes:

| Stays, `@Deprecated`, a `Rational` | Added, a `VirtualTime` |
|---|---|
| `EntitlementLedger.virtualService`, `grossVirtualService` | `virtualServiceWide`, `grossVirtualServiceWide` |
| `HeddlePolicy.front`, `HeddlePolicy.virtualService` | `frontWide`, `virtualServiceWide` |
| `FairShareAllocator.virtualTime` | `virtualTimeWide` |
| `HeddleNode.parentVirtualTime` | `parentVirtualTimeWide` |
| `BehindAtLevel.leafSideVirtualService`, `front`, `eligibleFront` | `leafSideVirtualServiceWide`, `frontWide`, `eligibleFrontWide` |
| `EntitlementLedger.seat(edge, Rational)`, `HeddlePolicy.wakeOffset(Rational, …)` | overloads taking a `VirtualTime` |

The wide reads never saturate and never throw on how much service an edge has
had: a position is below `2¹²⁷` (the position limit). A wide front over a
merged load that leaves 128 bits answers `null`, as it does for a parent with
no children. The deprecated reads keep their types and report the ceiling past
it, the penalty side, as they do today. So a deprecated read of a busy parent
sticks at the ceiling, and its message names the sibling to use.
`BehindAtLevel` gains its three values without changing its constructor, its
`copy` or its `componentN`: if constructor parameters cannot do that, they are
properties in its body, set by an internal constructor.

**The restart rule** is built (slice 2) and now lives in §7.2. The origin did
one job nobody gave it: it told an allocator with no memory that a child had
been idle, since a first sighting was clamped only below the origin. The rule
that replaced it does not read the origin, so slice 3 can remove the rebase
without leaving a restarted scheduler's idle children their whole gap as credit.

**Replication.** The ledger is a product of join-semilattices, one per field,
and step B removes one factor, the per-parent origin. What is left is still a
product of semilattices, so the join is still idempotent, commutative and
associative. A gauge is `(ticks, folded)`: two chains, the ticks compared as
one 128-bit number, joined componentwise (#3470 removes the legacy `floor`).
A seat is written at `max(⌈V · w · G⌉, observed · G)` with no origin term. So
the one case where two peers' seats could differ by the gap between their
origins is gone, and so is the sub-tick residue a child kept when a peer
raised an origin it had not seen that child under. Two peers still seat one
newborn at their own fronts and the join keeps the higher, as since #1752.
Positions differ between peers only by their wake offsets, which were never
replicated. The control log carries no origin and its bytes do not change. The
weight budget it applies does not change either, so a log 0.7.5 wrote replays
to the same state: every entry the old budget refused is still refused.

**Snapshot.** Built (#3489's restart). `LocalHeddleSnapshot.FORMAT_VERSION`
restarts at 1, and 1 is the only format read. It is the first frozen format: no later release changes it
without shipping a reader for it. Format 2, written only by development builds
of 0.7.5, is refused by name, as `RestoreResult.UnsupportedVersion(found, [1])`,
before anything else is read. A snapshot v0.7.4 wrote also says format 1, and
this release does not tell it apart: it fails to decode or misreads, so state
written by v0.7.4 is discarded and re-seeded from its topology, not restored.
That reuse is deliberate. 0.7.5 is the first release with a heddle consumer,
and that consumer re-seeds once at the upgrade, so keeping the number
unambiguous would guard state that nobody holds (@keddie, 2026-11-15).

**0.7.6 reads format 1 by translating its positions, not by dropping its
origins (@keddie, 2026-11-15, #3501).** 0.7.5 ships format 1 with the origin of
every parent a rebase raised, and a consumer will hold such snapshots. A
gauge's stored position is already absolute. What 0.7.5 picks on is the
relative position, the stored one less the origin's share on the child's own
grid, rounded down: `stored − ⌊O · w · G⌋`. Read with the origin simply
dropped, a child whose grid the origin is not whole on comes back a fraction of
a tick lower against its siblings than 0.7.5 had it. That is credit (§10.5).
So the reader puts the origin back, rounded up:

```text
absolute(e) = stored(e) − ⌊O(parent) · w(e) · G⌋ + ⌈O(parent) · w(e) · G⌉
```

A child whose grid the origin is whole on keeps its stored position. Any other
moves up one tick, once: under a tick of penalty, never credit, the side a
seat already rounds to. Wake offsets, the clock, departures and shift
remainders are distances, and an edge with no gauge has no position, so none
of them moves. The origins are then discarded. 0.7.6 writes format 1 with no
origins, which is a layout 0.7.5 already reads, so the format number and
`SUPPORTED_FORMAT_VERSIONS` do not change.

**Proof the slices owe.**

- **The twin (slice 1, before anything is deleted).** After step A an engine
  with `rebaseEnabled` off never raises an origin, so every position is at or
  above zero and no floor arm or below-zero arm can fire: it is the step B
  engine. It and the rebasing engine make the same picks, leaf for leaf, over
  the #3353 sweep's shapes on `WindowRuleRig`, every row of `BurstLagTest`,
  the drivers of `LocalHeddleRebasePropertyTest`, and the governed cluster of
  `HeddleAcceptanceTest`. Each row asserts its rig fired: the rebasing side's
  `rebases` is above zero, and the twin's positions pass `2⁶³` ticks where the
  row is long enough to. On JVM, `macosArm64` and `wasmJs`, read as a union.
- **Where the two may differ, and nowhere else:** a row where the rebasing
  side landed a joiner at its floor (`floorLandings` above zero), or clamped a
  never-observed child below zero, which slice 2 makes common to both. Each such row is named, with the count, and
  the difference shown to be turns the rebasing side took away.
- **Mutations that must red.** In slice 1, an origin step that is not a whole
  multiple of the grid step, and an `originTicks` that rounds up: each breaks
  the translation, and the twin must diverge. In the final engine, a pick
  compare narrowed back to `Long`, a `clampTicks` with its `max(0, …)`
  dropped, and a returner's shift removed.
- **The pick goldens do not move.** `HeddlePolicyGoldenVectorTest` and
  `HeddlePolicyOracleTest` pass unchanged through every slice.
- **The position limit (slice 5).** Every row asserts its rig before it acts:
  each `apply` it makes returns no refusal, each weight set passes
  `weightsFitTheBudget`, and a control one step short of the limit lands. On
  JVM, `macosArm64` and `wasmJs`, read as a union.
  - The bridge, by public calls only: `1/2¹⁴` leased 160 times at the largest
    size, 4 joins and is leased, the fine lane leaves, `2⁵⁸` is applied.
    `LocalHeddle.apply` refuses the heavy lane alone, the lane of weight 4 is
    still leased, and no call throws. At 16 leases the heavy lane lands and is
    picked: its seat is about `2¹²⁴` ticks. (At 64 its seat is about `2¹²⁶`,
    which the limit refuses; 64 was where the picks stopped throwing before
    the limit.) #3500's `W1BridgeProbeTest` is this row, retargeted from "no
    throw" to the typed refusal.
  - The same bridge on `FairShareAllocator` and on `HeddleNode`, where no
    `apply` asks early: `schedule` answers `LifetimeExhausted(parent)`, the
    ledger and the wake memory are equal before and after, and once the heavy
    edge is closed the next `schedule` grants.
  - The waker: the heavy lane is active and idle from the start, the fine lane
    runs the front up, and the heavy lane then asks. `acquire` is refused and
    changes nothing. With the heavy lane left out of `waiting`, it leases.
  - No service kept: 1,024 rounds of two lanes leasing in turn and cancelling.
    The row asserts each edge's issuance equals its returns, then the same
    refusal. It is what shows the bound is not on service kept.
  - Both sides of the limit, on stored extremes: a landing that leaves the load
    at exactly `2¹²⁵` is kept, and one tick more is refused. A landing whose
    own value leaves 128 bits is refused, not thrown.
  - Many children: each lands under the limit on its own, and the one that
    takes the load past it is refused. This is the row that tells a check on
    the load from a check on the joiner's position.
  - A returner whose shift would pass the limit, and a clock planted one step
    under it: the pick is refused, and a planted clock at the limit refuses
    every later pick at that parent.
  - A charging call at each of those rigs: `admit`, `extend` and an overrunning
    `complete` land, the charge is in the ledger, the wake memory at the
    refused parent is equal before and after, and nothing throws. Across an
    edge that could not be seated, `admit` opens at zero and `complete` charges
    the group above it.
  - `explain` answers `LifetimeExhausted(parent)` at each rig, `previewPicks`
    stops, every wide read answers, and every deprecated read answers.
  - Two peers that each land a different child high, then merge: `schedule`
    refuses, typed, and every read answers.
  - A seeded driver over weights at the budget's edges, with joins, leaves,
    reweights, cancels and overruns: no call throws, `validate()` is empty
    after each, and the load is below `2¹²⁷` after each. It asserts the refusal
    fired at least once, and sorts what it walks (#2592).
  - The budget did not move: `HeddleWeightBudgetTest` passes unchanged, with
    `{1/30,718, 1/30,719}` accepted, `{1/30,719, 1/30,720}` refused and
    `{1, k}` accepted up to the edge it has today.
  - Mutations that must red: the check dropped at a seat, at a clamp, at a
    returner's shift, and on the clock, each on its own; the load checked
    before the landing and not after; the joiner's position checked in place
    of the load; a landing's overflow thrown and not refused; a refusal that
    writes the wake memory; a charging call that refuses; `apply` refusing the
    siblings with the joiner.
- **The format 1 reader (slice 4).** A golden snapshot written by the 0.7.5
  tag, committed as bytes, with a parent whose origin is above zero, a child
  the origin is whole on, a child it is not whole on, a child below the origin,
  an unseated edge and a wake offset. The row asserts the origin it read is
  above zero. Restored, the whole-grid children read their stored positions,
  the other reads one tick more, and offsets and clock are equal. Over the
  picks that follow, the restored engine and a 0.7.5 twin with no origin, fed
  the same history and the same calls, pick the same leaves. A 0.7.5 twin
  with the origin does not, and is not meant to: it seated the not-whole child
  with its share rounded down, a fraction of a tick of credit the translation
  takes back. A snapshot 0.7.6 writes holds no origin. Mutations that must red:
  the origin dropped with no translation, on a rig where that tick decides a
  pick; the share added a second time; the share rounded down.
  Built (#3498): `LocalHeddlePersistenceTest.aFormat1SnapshotWithAnOriginIsReadByTranslatingIt`.
- **Nothing public breaks (slices 3 to 6).** The API dumps of
  `:kuilt-heddle-core` and `:kuilt-heddle` gain lines and lose none in each
  slice's diff, and each PR says so. A test compiled against the deprecated
  reads still compiles and passes.
- **The lifetime limit (slice 7).** Every row rigs a counter and asserts the
  value it rigged before it acts, so a rig that missed fails. On JVM,
  `macosArm64` and `wasmJs`, read as a union.
  - Both sides of the watermark, for an edge and for the root's supply: one
    unit under it `acquire` leases, at it `acquire` answers
    `LifetimeExhausted`, and the ledger and the open leases are equal before
    and after. The supply row holds its total in two epochs' records.
  - A leaf edge rigged full under a parent with room: the refusal names that
    parent, and its sibling is leased when it alone is eligible.
  - In the headroom, `admit`, `extend` and an overrunning `complete` land in
    full: `unrecorded` is zero and the counter is past the watermark after.
  - A charge that does not fit. An overrun above a full edge is charged at
    the group above it, that edge's issuance does not move, and `validate()`
    is empty. `complete(lease, Long.MAX_VALUE)` on a fresh engine closes the
    lease, reports the overrun as `unrecorded`, and the next `acquire` leases.
    An overrun of exactly `MAX_RECORDED_OVERRUN` is recorded in full, and one
    unit more is not, leaving the engine as a completion at the reservation
    would. An `extend` the root must mint for lands with the supply past the
    watermark, and answers `Done(unrecorded = amount)` a unit past room within
    `Long`.
    An `extend` at a full counter answers `Done` with `unrecorded` equal to
    the amount, and the lease, the ledger and the wake memory are equal
    before and after. One unit smaller, it lands with `unrecorded` zero. An
    `admit` opens at zero and completes.
  - A seeded driver near both limits, with overruns up to `Long.MAX_VALUE`:
    no call throws, `validate()` is empty after each, and what was recorded
    plus what was reported adds up to what was charged. It sorts what it
    walks (#2592).
  - `HeddleNode.schedule` at the watermark answers `LifetimeExhausted` and
    publishes nothing, and after grants answers `Delegated` and then the
    refusal. Two peers that each grant under their own view merge past the
    watermark, and every read still answers.
  - The run measured above, two lanes at the largest lease, ends in the typed
    refusal at the count the watermark gives, and never throws.
  - Mutations that must red: the check comparing the counter and not the
    counter plus the reservation; reading base issuance, not effective, on a
    relocated edge; reading this epoch's mint and not the sum; the check
    applied to a charging call; a charge tested against the watermark and not
    `Long.MAX_VALUE`; a charge recorded in part; the room test dropped for the
    edges above the leaf; `unrecorded` reported as zero; an `extend` that does
    not fit recording part of its amount; that `extend` throwing.

**Slices,** in order, one behaviour move each. Each carries its own docs.

1. **The twin proof.** Tests only. §13.
2. **The restart rule.** Built (#3490). A never-observed seated demanding
   child is a waker, which takes in the below-zero arm while the rebase still
   runs. Its pin, once "does not wedge", is two "comes back level" rows of
   `HeddleAcceptanceTest`: one with the rebase off, where only the rule can
   level the idle child, and one with it on, which slice 3 retires. It inverted
   the two first-observation tests, which pinned the old rule. §7.2, §10.6.
3. **Nothing rebases.** Built (#3495). The raise sites, the floor arms and the
   window are gone from `FairShareAllocator` and `LocalHeddle`; the origin
   field stays, read as zero. `rebaseEnabled` and `rebases` stay, deprecated
   and inert. It retired `FairShareAllocatorOriginTest`,
   `FairShareAllocatorWindowRuleTest` but its sleeper-credit row,
   `LocalHeddleRebaseTest` and the twin rows of `LocalHeddleRebasePropertyTest`,
   which is now `LocalHeddleLongRunTest`. `LocalHeddleWindowRuleTest` and the
   long-run rows are retargeted to "never refused, and fair". Docs: this
   section, §7.2, §10.5, §10.9, both `module.md`s, the cookbook.
4. **The origin leaves the ledger and the wire, and format 1 is read by
   translating.** Built (#3498). `ParentRecord` and the canonical-encoding arm
   are gone; format 1 reads its ledger through the frozen `LedgerFormat1`,
   which translates. `EntitlementLedger.origin`, `EntitlementLedger.of`'s
   `origins` and `PolicyEdge.origin` stay, deprecated. The format number does
   not change. Docs: §7.1, §10.5, both `module.md`s,
   `Writerside/topics/heddle-local.md`.
5. **The position limit.** In progress (#3500): built so far are the limit, the
   sealed results, the refusal on `acquire` and `schedule`, the charging-call
   path rule and the early check in `apply`, with the bridge, waker and
   two-sides rows. `POSITION_LIMIT`, the sealed result of the
   seat-and-wake step and of `VirtualTime.ticksForWide`, the refusal on
   `acquire` and `schedule`, the path rule for a charging call, and the early
   check in `LocalHeddle.apply`. `weightsFitTheBudget` does not change.
   Retargets #3500's probe. Docs: §7.1, §9, §10, `docs/agent-cookbook/heddle.md`
   (Refusals and limits), both `module.md`s.
6. **`VirtualTime` is a public read, beside the `Rational` ones.** The wide
   siblings in the table above, and `@Deprecated` on the reads they replace.
   Nothing is deleted or retyped. Docs: §11,
   `docs/agent-cookbook/heddle.md`, `Writerside/topics/heddle-local-turns.md`.
7. **The lifetime limit (#3475).** `LIFETIME_HEADROOM` and the watermark
   check on `acquire` and `schedule`; the whole-or-nothing rule on `admit`,
   `extend` and `complete`; `LeaseOutcome.Done.unrecorded`. Three
   `PositionCeiling` arms are renamed `LifetimeExhausted` (`AcquireOutcome`,
   `WaitExplanation`, and `AllocationOutcome` with `ScheduleOutcome`), and
   `LeaseOutcome`'s is deleted. The types hold the ruling: with no lifetime arm
   on `LeaseOutcome` or `AdmitOutcome`, a charging call cannot refuse for it,
   and `unrecorded` has no default. Built, ahead of slice 3, so
   `LocalHeddlePositionCeilingTest` stays until slice 3 or 6 retires the
   rebase and the saturating reads its rows still pin; its lifetime twin is
   `LocalHeddleLifetimeTest`. Docs: §7.1, §10.12, the cookbook, both `module.md`s,
   `Writerside/topics/heddle-local-turns.md`. #3458 is then a use of the
   headroom, not a limit of its own.

Slice 2 goes first so that slice 3 leaves the restarted scheduler's row green.
Slices 1, 2 and 7 ship in 0.7.5. Slices 4, 5 and 6 follow 3 and do not depend
on each other, and 0.7.6 is not cut without slice 5. Slice 3 does not open the
gap slice 5 closes: the bridge overflows on `main` with the rebase in place,
at the same absolute value.

#### Settled — don't reopen

- Keeping the origin as an optimisation that keeps numbers small: it buys
  nothing a pick reads, and it costs a replicated register, a rebase rule and a
  window (#3472).
- Deleting P1′'s lease clause: its denominator cap keeps the net-service
  clock's reach modest, and it is the set of weights 0.7.5 accepts (#3472,
  #3501).
- W1, and any clause on the weights in place of the position limit. #3472
  settled the other way, against a typed refusal at 128 bits, because a limit
  the budget can rule out when a weight is applied should not be met at a
  pick. The budget cannot rule it out: a joiner inherits a departed sibling's
  front, and the front climbs on cancelled reservations, across edges with
  fresh counters. Both are measured (#3501).
- A global clause over every weight ever active at a parent, `Σ⌈1/w⌉ ≤ 2ᵃ` and
  `N · max⌈w⌉ ≤ 2ᵇ` with `a + b ≤ 56`. It is unsound: its bound on virtual
  time, `2⁶³ · max⌈1/w⌉`, counts service kept, and the front climbs on
  reservations. It is also dear. Keeping today's denominators leaves `{1, k}`
  at about `2⁴⁰`, no split keeps `{1, k}` to `2⁵⁹`, and it needs a replicated
  record of every weight a parent has held (#3501).
- A rule at `apply` alone, refusing a weight whose seat at today's front
  leaves less than a lifetime of headroom. It misses a child that is already
  active and wakes at a high front. The headroom that matters is its
  siblings' lifetimes, not its own. And it does not replicate: a front is one
  allocator's reading, and an `Activate` is decided from the log. It is kept
  as `LocalHeddle.apply`'s early answer, not as the guarantee (#3501).
- A count of a parent's total reservations, to make a clause on the weights
  sound: a second replicated register per parent, and the clause still costs
  the spread above (#3501).
- Seating a joiner at the limit when the front is past it: a seat below the
  front is credit (§10.5, #3501).
- Refusing only the joiner that cannot land and picking its siblings: the
  joiner would starve with no answer anywhere, as a full lane would (#3475).
  The parent's picks stall and say so. `LocalHeddle.apply` refuses the joiner
  alone because there the caller is told (#3501).
- Positions wider than 128 bits: format 1 is frozen with 128-bit ticks, and a
  wider count moves the limit without removing it (#3501).
- Widening the weight budget in 0.7.6, now that no window needs the lease
  clause: a control log holds the entries a budget refused, and they would
  land on replay (#3501).
- Deleting `PositionCeiling` and letting the lifetime counters throw from
  `acquire`: measured at 524,287 largest leases, which a consumer can reach
  (#3472).
- Keeping the `Rational` reads as the only reads: with no origin they stick at
  the bound for good, and `explain` reports equal numbers (#3472). They stay,
  deprecated, beside the wide ones (#3501).
- Retyping the `Rational` reads, or `BehindAtLevel`'s fields, in place: 0.7.6
  breaks no public signature (@keddie, 2026-11-15, #3501).
- Deleting `EntitlementLedger.origin`, `PolicyEdge.origin`, `rebaseEnabled` or
  `rebases` with the origin: each is in 0.7.5's API dump. They stay,
  deprecated, and read zero (#3501).
- Reading the public virtual times relative to a front taken at read time: the
  ledger's own reads hold no demand, so they have no front to take (#3472).
- Keeping the below-zero first-sighting arm: nothing is below zero (#3472).
- Reading snapshot format 2 by dropping its origins: no pick would change, but
  one format number would then mean two layouts (#3472).
- Numbering the step B format 3 so that it cannot collide with v0.7.4's format
  1: no consumer holds v0.7.4 state, and the one consumer re-seeds at 0.7.5.
  The format restarts at 1 and is frozen there (@keddie, 2026-11-15).
- Persisting `HeddleNode`'s wake memory in place of the restart rule: it is
  scheduler-local by design, and a second store for it is a larger change than
  the gap it closes (#3472).
- Dropping the restart rule and keeping unbounded idle credit after a restart:
  a child that idled before a crash would come back owed its whole idle time
  and could take every slot it is eligible for. Losing a child's lag once per
  restart, and once per scheduler that first sees it, is bounded and
  predictable; unbounded credit is the starvation this entry exists to remove.
  Ruled by @keddie, 2026-11-15 (#3472).
- `LifetimeExhausted` in place of `PositionCeiling`, and `VirtualTime` as the
  type of a wide read: each costs less than the alternative it replaces. Ruled
  by @keddie, 2026-11-15 (#3472). That ruling also took W1 in place of P1′'s
  lease clause, which #3501 found unsound, and made `VirtualTime` the only
  read type, which 0.7.6 may not do.
- Reading format 1 with its origins dropped: a child whose grid the origin is
  not whole on comes back a fraction of a tick lower against its siblings,
  which is credit (@keddie, 2026-11-15, #3501).
- Translating format 1 by a whole grid step, so that every sibling moves by
  exactly the same virtual time: the step can be near `2⁶³` units, which lifts
  a heavy child by up to `2¹²⁶` ticks. Each share is rounded up on its own
  grid instead, under a tick (#3501).
- A new format number for the layout with no origin: it is format 1 with the
  origins empty, which 0.7.5 reads (#3501).
- A lifetime arm on `extend`, and a charging call that throws at the limit:
  a refusal is for work not yet started, where it means "leave it queued". At
  `complete` the time is spent, and a throw leaves the lease open and the
  caller's settlement broken. `acquire` and `schedule` refuse with headroom,
  and `admit`, `extend` and `complete` use it (@keddie, 2026-11-15, #3475).
- A charge clamped at the limit, in any of its three readings. A counter that
  saturates breaks conservation, since the edges of one path stop agreeing on
  what crossed them, and a replicated slot written that way is joined as if
  it were true. A whole charge trimmed to the tightest edge undercharges the
  groups above it, and they can spend that. The root's supply filled to the
  brim lets `complete(lease, Long.MAX_VALUE)` end a healthy engine. A charge
  that does not fit is recorded whole or not at all, and reported in
  `unrecorded` (@keddie, 2026-11-15, #3475).
- `SupplyExhausted` for an `extend` at a full counter: widening its meaning
  breaks "reconcile capacity" as its remedy, a caller that retries until it
  clears would never stop, and a caller that has reconciled capacity may
  treat that arm on `extend` as unreachable. It answers
  `Done(unrecorded = amount)` and changes nothing
  (@keddie, 2026-11-15, #3475).
- A headroom of `2⁶²`: it halves the leases a caller with large reservations
  gets, for room no caller needs. It is `2⁵⁸` (@keddie, 2026-11-15, #3475).
- A headroom sized from the edge's outstanding: it moves with every
  completion, and it bounds no overrun (@keddie, 2026-11-15, #3475).
- Recording any overrun that fits whole: on an engine whose only service is
  one lease, `complete(lease, Long.MAX_VALUE)` fits exactly and leaves the
  minted supply past the watermark, so one wrong cost ends the engine. An
  overrun above `MAX_RECORDED_OVERRUN`, `LIFETIME_HEADROOM / 2`, is not recorded
  and is reported whole in `unrecorded` (@keddie, 2026-11-15, #3475).
- Skipping a full lane and picking its sibling in its place: the lane would
  starve with no answer anywhere saying why. A full lane that is eligible and
  next in line stalls the picks under its parent, and says so
  (@keddie, 2026-11-15, #3475).

#### Open

- **Whether the position limit should also go onto the rebasing engine, in
  0.7.5.** The bridge is reachable on `main` today, and the fix changes no
  signature. There the check has one more value to cover: the origin's share
  at each child's weight (#3501).
- **Whether the weight budget should widen later.** With the limit in place a
  finer denominator is safe. It needs an answer for a log that holds a refused
  entry, and a bound on the clock's reach (#3501).
- **Whether a parent stalled at the limit should be told apart from one at a
  lifetime counter.** Both answer `LifetimeExhausted(parent)`, and the remedy
  differs: a joiner can be removed, a counter cannot (#3501).
- **Whether a pick should also refuse on the leases open under an edge.** The
  headroom covers 16,384 charges of the largest size after the last `acquire`.
  A caller with more open leases than that, each overrunning in full, passes
  it. None is known (#3475).
- **Whether boot attribution should narrow.** Today `LocalHeddle.Boot` (#3462)
  leaves out the whole subtree of a parent whose children's weights do not fit
  together, even when one child fails on its own. The budget is not changing,
  so single-weight failures stay as common as they are.

### 7.2 Neutral creation and no idle credit

- **Neutral creation:** a new generation is seated at the parent's current
  virtual time — its **front**, defined below — so a newborn starts level with
  its siblings, with no credit for the parent's whole past.

  The seat is **published, not carried** (issue #1752). It is not in the record;
  it is a `Gauge` in the replicated ledger, written by the scheduler's seat bump
  on every peer that grants at the parent while it still sees the edge unseated
  (a call with nothing to grant writes no seat, #3353), and the gauge's componentwise
  join resolves those readings by `max` on the position. That matters because `V` is
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
  implementation. It stores the position itself, in the gauge's ticks, so every
  later read of it is exact (#3353).

  This reverses #1752's 2026-07-30 ruling, which kept the floor an exact
  `Rational` and warned that a fixed-point floor would have to round **down**,
  because a `ceil` at every checkpoint would compound into real inflation. That
  premise does not hold here, because a checkpoint adds whole ticks. It writes
  the position plus the issuance since the fold, which is whole units; reading a
  position back is exact; and only the seat and the wake clamp round, each once
  per join. Peers that merge in any order still read the same position, because
  the read is a function of the joined gauge and the join is unchanged.

  Mixed versions do not converge, and nothing fences them. A v0.7.4 peer cannot
  decode a frame whose gauge carries ticks, and this release refuses a frame whose
  gauge carries a non-zero `floor`. Each drops the other's frames, stays connected
  and silently stops converging. So the two must not share a cluster, and every
  peer is upgraded together.
- **No unlimited idle credit:** when a child goes from not-demanding to
  demanding and this peer never saw it stop, a local wake offset clamps it
  forward. A child this peer *did* see stop comes back by its parent's clock
  instead (below, #3376):

  ```text
  virtualOffset = max(0, parentVirtualTime − v_raw(e) − sleeperCredit / w(e))
  effectiveVirtualService(e) = v_raw(e) + virtualOffset
  ```

  On the waker's grid that is `max(0, ⌈V · w · G⌉ − p − sleeperCredit · G)`
  ticks, the same up-rounding the seat takes. Sleeper credit is service units,
  so it scales onto the grid by `G` like any other service. The stored offset is
  kept on the grid, so it never carries a front's denominator into the next
  one. The clamp and the stored offset are 128-bit
  (#3353): a waker idle for long sits far below its siblings, and the clamp
  carries it the whole way back to the front.

  Default `sleeperCredit = 0`. `parentVirtualTime` here is the front defined
  just below, with the waker itself — and every sibling waking in the same
  round — excluded from its mean. The offset is scheduler-local policy state —
  deliberately *not* replicated, so divergent offsets can reorder locally but
  can never touch conservation. A zero offset is not stored: an edge reads a
  missing one as zero.
- **The restart rule: a seated child this peer has never observed, and finds
  demanding, is a waker** (#3472). It is clamped as above, to the front of the
  children that are not joining, or level with the furthest-ahead child when
  all of them are. A first sighting says nothing about how long the child
  idled, and keeping its raw position would let a child that idled under
  another peer's scheduling, or before a restart, keep the whole gap as credit
  and take every slot it is eligible for until it caught up. Clamping costs it
  its lag instead, once per allocator that first sees it: the penalty side
  (§10.5, §10.6). An unseated child is a newborn, which the seat puts at the
  front, so the rule is about seated ones.

  Who meets it. A restarted `HeddleNode` keeps no wake memory, so every seated
  child it finds asking at its first call is clamped, and all of them are
  joining, so all come back level with the furthest-ahead one
  (`HeddleAcceptanceTest`'s two restart rows). A peer meeting a child another
  peer seated and ran is the same case (`aFirstObservationOfASeatedDemandingEdgeIsAWake`).
  `LocalHeddle` persists its observations, and every pick and every charging
  call observes its waiting set (#3471), so it meets the rule in two places
  only: a generation a reload seated to carry an open lease it moved, which
  no call has observed yet, and a restore from a snapshot whose memory was
  dropped. The second is a restart, and costs what a restart costs: every
  seated child the engine finds demanding is clamped, and since all of them
  are joining, each comes back level with the furthest-ahead one. A clamped
  waker lands where the memory had it, but a child that was owed service loses
  it, and a returner is clamped rather than moved by its parent's clock
  (`LocalHeddlePersistenceTest`). So the snapshot still keeps the memory.

  The rule replaced an older one, under which a first sighting was recorded as
  it stood and clamped only when it sat below its parent's origin, where only
  a rebase could put it. In any run too short to rebase, a child that idled
  before a restart kept all of that as credit, and with no rebase (step B,
  §7.1) that arm would never have fired at all.
- **Which children define the front.** The bullets above turn on one phrase,
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

  **When no child outside the joiners is demanding, the mean has nothing to
  average, and the front is a maximum instead.** The rule, ruled in #3353:

  When no non-joiner is demanding, the front is the furthest-ahead child at the parent, counting idle children, the joiners themselves, and each newborn at its own origin. There is no competing set to come level with, so everyone joining starts level with whoever has had the most. This can cost a joiner a turn; it can never give one.

  §10.5 is one-directional — credit is forbidden, a sliver of penalty is merely
  undesirable — and the maximum is the bound that can only ever give up. The
  consequence is worth stating plainly rather than discovering: an idle child
  sitting *ahead* of everyone pins the front at its own position for every
  joiner while nothing else competes. A cohort of fresh newborns lands at zero,
  the furthest-ahead origin among them.

  It replaces three older rules. The maximum used to be taken over the idle
  survivors alone, which let a waker come back level with an idle bystander far
  behind its sibling: with `a` at 100 and `b` at 0 both waking beside `c` idle
  at 10, `b` was clamped to 10 and took 95 of the next 100 grants, where this
  rule splits them 50/50. With only joiners left there was no front at all, and
  each newborn was seated at its own origin. And whenever newborns joined beside
  wakers, the front was raised to the newborns' origins (#3329), a case this
  rule now covers by counting them.

  **The old rule's empty set had no front, and two situations wore that face**:
  the legitimate **first** generation under a parent, whose origin seat is
  genuinely correct, and a peer whose view has simply not applied the siblings
  yet, for which the origin seat is wrong.

  While the seat was frozen into the committed record that ambiguity was
  load-bearing and unfixable from inside — one peer's guess became everyone's
  permanent fact — so creation had to fence on leader authority (§9 #3
  `readIndex()`) *and* on this peer's applied prefix having caught up, and fail
  closed otherwise. That fence closed the stale-*records* case only; a view that
  had merged some siblings but not all computed a plausible front over those and
  froze it, with nothing anomalous to see (#1713).

  **Publishing the seat dissolves the ambiguity rather than fencing it** (#1752).
  The seat bump writes `max(front, baseIssued / w)` into the edge's `Gauge`, so a
  peer that sees only the newborns seats them at the furthest-ahead origin, where
  a fresh edge already reads, pinning it without moving it, and any
  better-informed peer's higher position wins the join's `max`. A low reading is
  absorbed instead of frozen, so the fence and both of its residuals are gone,
  and a node with no control plane no longer needs to tell the two situations
  apart.

  **The newborns count because a seat cannot go below the edge's own start.** A
  front taken without the newborns could sit below them, at idle children a
  rebase once carried below zero, and a waker clamped there would keep credit
  over a newborn, which took every pick until the waker caught up (#3329
  review). When some non-joiner *is* demanding, the front is that set's mean,
  and the newborn is seated level with that set wherever it stands, on its own
  grid, exactly.

#### A returning child keeps its place, by the parent's clock (#3376)

**Pausing neither costs a child its place nor buys it a better one.** A child
that stops asking and asks again later comes back as far ahead or behind the
others as it was when it stopped, give or take a sibling's refund while it was
away (P2, below). The time it spent away counts for nothing.

The clamp above, applied to every waker, did not do that. It moved each one up
to the front, so it forgave the lag a short burst leaves behind, and a parent
that drains between bursts forgave it every time. Over 300 bursts the weights
stopped holding (`BurstLagTest`):

| Rig | Fair | Clamp alone | Clock |
|---|---|---|---|
| S1c: weights 2:1, 2 units a burst, both asking | 400 / 200 | 300 / 300 | fair, exactly |
| S7: 2:1, the heavy child asks alone for 1, then both for 1 | 500 / 100 | 600 / 0 | fair, exactly |
| S7b: the same, light child first | 200 / 400 | 300 / 300 | fair, exactly |
| S8: S7 at 1:1, with an idle round between the two units | 450 / 150 | 600 / 0 | fair, exactly |
| S9: a ladder, 1:2:3 joining one at a time | 550 / 500 / 450 | 600 / 600 / 300 | fair, exactly |

S7's first unit is uncontested, so it is the heavy child's. Counting it as
contested would pay the light child for a stretch it was not asking (§10.6).

**The rule, in one sentence.** Each parent keeps a clock that runs by the
service its asking children *kept*, issued less refunded, per unit of their
weight, and a child that comes back moves forward by how far the clock ran
while it was away.

- **The clock.** Each time it runs at a parent, the allocator adds up how far
  each child it last saw asking moved, `issued − returned` since it last
  looked, and divides by those children's total weight. A child that was not
  asking adds nothing, its own refunds included: they already lower its own
  position, and counting them in the clock too would credit it twice. The clock
  can run backward, on a round in which refunds outweigh grants.
- **Leaving.** When this peer sees a child stop asking, it records where the
  clock stood. In `schedule` that is at the grant that filled the child, not
  at the next call: a child that reaches its target partway through a call has
  left there, and its siblings' later grants in that call run the clock at
  their weight alone.
- **Returning.** When the child asks again, its wake offset grows by how far the
  clock ran since then, read on the child's own grid, and never by less than
  zero. The record is cleared. A returner counts in the front that a newborn
  or a clamped waker beside it is seated at or clamped to, at where it lands
  (#3446). It competes from there, as far ahead or behind its siblings as it
  left, so that position is not stale the way a waker's is. Leaving it out
  seated the others against a set without a child they were about to compete
  with: a waker joining beside a returner at 200 and a starved sibling at 0
  was clamped to 0, not to their mean of 100, and kept the difference.
- **A child this peer saw idle but never saw stop asking** is clamped to the
  front, as above: one first seen idle, and seated. An unseated one is a
  newborn, and its seat is what puts it at the front, so it is not clamped as
  well (#3454 review). A clamped child holds no wake offset yet. An offset is
  only written on a seated child, a seated child keeps its gauge, and the call
  that writes the offset sees it asking. So its next stop is recorded as a
  leaving and its next wake is a return. The clamp is therefore written, not
  joined with a stored offset (#3416 review).
- **A seated child first seen already asking** has no departure, so it is not
  moved by the clock. It is clamped to the front, by the restart rule above.
  That covers every child of a restarted `HeddleNode`, which keeps no clock.

`FairShareAllocator` runs the clock in its seat-and-wake step, `settleJoiners`,
which every pick, every `LocalHeddle.admit` with a waiting set and the first
round of every `schedule` call pass before they grant. `schedule` runs it again
before each later pick of a call, to see a child that the last grant filled
(`markDepartures`). A child that stops and asks again with no grant between
moves by exactly nothing.

**Why it earns nothing for idleness.** The clock runs only on service someone
else kept while the child was away, so the shift is how far the others moved,
not how long the child slept. It does not read the child's own position, so a
charge or refund that lands on the child during or after the gap counts as it
would with no gap. An over-reserved lease that straddles the wake is the child's
own debt, and its refund cancels the debt (#3238).

It was chosen over the kept-lag rule #3383 proposed, which stored each child's
distance from the front and needed a correction for the mean the returner joins.
A child leaving a set of one kept lag 0 there, so pausing bought it a place (S8:
2:1, light first, 150 / 450 against a fair 200 / 400), and a debtor that paused
while a sibling left lost one (P1: 30 against 35). The clock has no lag to store
and no correction. It is exact on S1, S7, S8, S9 and P1, and #3238's slot rig
(`WakeCreditSlotTest`, 4,000 ticks) reads an over- or under-reserved lease that
straddles a wake as honest, where the clamp alone gave +0.136 and −0.030 at t7.

The returning child's lag now outlives its absence, so some pictures of "level
on return" moved. A deploy lane at its ceiling sat out the next pick while builds
took it, and it comes back as far ahead of builds as it was, not level with
them. A team that went quiet owing a sibling's overrun is still owed it when it
returns. `LocalHeddleFairnessTest`'s churn arm, against waiting-set GPS, went
from 0.0157 to 0.0113 overall and from 0.089 to 0.073 over its worst window.

**What it does not close.**

- **P2, a sibling's refund during the gap** (#3239). b's over-reservation reaches
  the clock at the shared weight before a leaves, and its refund comes off at
  b's weight alone while a is away, so the clock runs short and a returns that
  far behind: 19 / 10 against a fair 15 / 14. Closing it needs to know which
  lease a refund came from, which the ledger does not record. **This is new
  with the clock.** The clamp it replaces put a level with b on return and
  split P2 exactly, 15 / 14. The ruling on #3376 accepted the regression for
  the bursts the clock fixes.
- **The two-level spill** is the same channel one level up, and is smaller, not
  gone: +0.045 / −0.029 at t7, against the clamp's +0.111 / −0.100.
- **A cancelled job that held its slot** is charged nothing, with or without a
  gap (+0.030 at t7). #3238 stays open until a control with no idle gap
  separates that from wake credit.
- **A child that asks but cannot be served**, trimmed or ineligible for every
  slot, still counts in the clock's weight and slows it. Not measured.

**Where it lives.** Beside the wake offsets in `AllocatorMemory`:
scheduler-local, never replicated. The clock is only ever read as a difference
against this peer's own record of a departure, and that record is one peer's
sighting of a transition, with no lattice to join under. Two peers that saw
different departures compute different offsets, which reorders their grants and
never creates entitlement, as wake offsets already do. `LocalHeddle` persists it
in its snapshot, format 1, as `AllocatorMemory`'s `clocks`, `served`,
`departures` and `shiftRemainders`; `HeddleNode` keeps it in memory, so a restart forgets it and
every seated child it sees asking afterwards is a first sighting, clamped by the restart rule.

The clock is kept wide, on a grid of `16 · 720,720` ticks to a unit of virtual
time. `720,720` is the least common multiple of 1 to 16, so a parent whose
asking children's total weight, over their common denominator, divides the grid
advances exactly; every total up to 16 does. A total with a prime factor above
13, or a prime power past the grid's own (27, say), rounds each step up,
by under one clock tick.

A shift is a whole number of the returner's own ticks, and the clock's run
since it left is in general a fraction of one, so each shift rounds. The
rounding is carried (`shiftOf`): the child keeps what its last shift ran past
the clock, under one tick, and its next shift takes that off before rounding
up. So its shifts add up to the clock's runs plus that last remainder: never
credit, and nothing builds up. `LocalHeddle` persists the remainders in its
snapshot beside the clock; `HeddleNode` keeps them in memory.

Two simpler rules drift. The difference of the run's two ends, each read on
the grid rounded up, errs by where those ends fall between ticks, and that does
not telescope: a repeating pattern of bursts locks the ends into one phase, and
the same error comes back every return. On `BurstLagTest`'s off-grid rigs, where
the clock runs `16/13` and `32/10` of the returner's ticks while it is back, it
held the returner 4 to 5 units over fair from 300 bursts to 1,200, credit that
§10.5 forbids, and on a second pattern drifted it from −16 to −66. One
rounded-up run would never fall short, but it rounds every return the same way,
and on the 3:1 rigs that cost the light child 9 of its fair 75 over 300 bursts.

No position enters a shift, since a shift is a distance, so a returner lands
wherever its clock puts it, past its ceiling if that is where it is, and the pick
orders it there (#3472). The front its siblings join is read with each returner
at its landing (#3446). A returner that left owed lands behind its siblings by
what it is owed, and is served first.

**Bounds.** A returning child is moved by what the others kept, so it takes at
most what it would hold had it kept asking, except across P2 and the two-level
spill above (#3239), and the gap adds nothing to §8.2's bound. §10's invariant
6 says so.

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
   wait on this log** at any frequency. The same apply decides the weight
   budget (§7.1, #3353): a `Prepare` whose own weight fails it, putting a
   pick's products out of `Long` or too fine for one full lease, or an
   `Activate` that fails it beside the parent's active children, is refused
   with `ControlConflict.WeightBudget`.
   It reads the log-pure projection, so every peer refuses the same act.
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
   ledger peers exchange.** A single-writer engine's forgetting of retired edges,
   and of prepared edges its topology does not want (§5.5), removes slots and is not a join. It is confined, like invariant 4's
   exception, to a ledger no other writer touches: `forget` is internal to
   `:kuilt-heddle-core`, `piece` refuses a fold that is not the receiver's own,
   and `:kuilt-heddle` refuses to decode a ledger that carries one.
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
   not over all its active ones, or the furthest-ahead child when no child but
   the joiners is demanding (§7.2) — never with lifetime credit. The
   seat is published on the edge's `Gauge`, not frozen into the record, and is
   the front rounded **up** to the edge's own grid, under one tick (§7.2). It is
   stored exactly and uncapped. The front it is taken from is wide too, so no seat is taken against
   an understated front (#3353, §7.1). No floor holds it: nothing seats a
   child below zero any more (#3472).
6. **No unlimited idle credit.** A child that stops competing comes back moved
   by how far its parent's net-service clock ran while it was away, and gains
   nothing for the time it was idle. A child seen idle but never seen to stop
   is clamped to the front, and so is a seated child first seen asking, the
   restart rule: an allocator with no memory of a child, a restarted one say,
   gives it no credit for a gap it did not watch, at the cost of its lag, once
   per allocator. Default sleeper credit is zero, and applies only to a
   clamped waker (§7.2, #3376, #3472).
7. **Partition safety.** No peer ever spends beyond `holdings(P, self)`;
   post-heal convergence restores a single agreed ledger.
8. **Projection homomorphism.**
   `project(merge(A,B), e) = merge(project(A,e), project(B,e))`.
9. **Bounded fairness error.** `error(p) ≤ Σ B(p, s) + discretization`,
   measured in simulation against the configured caps. Discretization is the
   quantum plus the position grid: step 3 admits an edge up to one tick past
   `V`, and a seat or wake clamp lands under one tick above it, each `1/16` of a
   unit of the edge's own service (§7.1). Nothing rebases (#3472), so no origin
   adds to it and no joiner is held at a floor, and no position refuses a pick.
10. **Closure dominance.** A replica that observed CLOSING never again
    delegates across that generation, regardless of merge order.
11. **Conflicts surface.** Integrity violations converge to the same explicit
    report everywhere and quarantine their lineage; nothing is resolved by
    timestamp.
12. **Overflow rejection.** Arithmetic that would exceed `Long` fails
    deterministically, never wraps. The one exception is the effective position
    narrowed for a pick, which saturates at its ceiling; a pick that would order it
    refuses with a typed outcome instead (§7.1). The public reads of a position
    saturate too, at its ceiling and at its floor, so none of them throws on how
    far a child has climbed; a front over many children can still throw
    on the least common multiple of their denominators. Everything before it is 128-bit:
    a gauge's stored position, the raw position, the front and
    a wake clamp, and arithmetic that would leave 128 bits throws (#3353).
    The `Long` lifetime counters, an edge's effective issuance and the root's
    minted supply, are the other exception: no call throws at them. A pick
    refuses with headroom, as `LifetimeExhausted`, and a charge that does not
    fit is recorded whole or not at all and reported in `LeaseOutcome.Done.unrecorded`.
    Nothing wraps or saturates under either (§7.1, "The lifetime limit",
    built in slice 7). A merged sum of peers' writes past `Long` still throws
    when read.
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
        // A ceiling: never more than two batch jobs at once, however idle the pool is.
        lane("batch", maxConcurrent = 2)
    }
    group("tenants") { template() }
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

Children the consumer attaches while running hang under a group with a
`template`, in their own id namespace (`pool/tenants/!c-7f3a`), so an attached
name never collides with a declared one. `Topology.attach(group, name,
ledger, generation)` and `detach(group, name, ledger)` list the steps for
one child, at the template's weight, numbered by the same generation floor as the diff.
Which attached children exist is the ledger's to say, and the diff owns them as it owns
declared edges: under a template the new tree keeps, an attached child is kept, history
and all; a new template weight regenerates it on a new edge; and when the template goes, or
its group does, the child is closed. Each detach retires the child's edge, and the
engine forgets it in the same swap once its books close (§5.5), so an attached child
can be detached whenever it goes idle.

An attached child is a leaf, one level below its group: it cannot carry a template
itself, so nothing discovered while running has children of its own. §1's "any depth"
is met today by declared groups, which nest freely and reshape on a reload, and not
yet by attached ones. A tree found while running two levels deep (say, a repository and
then its authors) has to declare each repository as a group with a template and reload as
each one appears. Letting an attached child have attached children is tracked by #3314.

### 11.2 A ceiling: "run no more than N of these"

An operator sometimes wants to say "never run more than three of these at once"
about one part of the tree: three builds of one repository, say, however idle
the rest of the machines are. That is a ceiling. It gives up keeping every slot
busy, on purpose, and only where someone set it. Without one in heddle, the
caller has to enforce it outside, beside the fairness it interacts with. This
section records how a ceiling is declared, enforced and reported in the
single-process engine, `LocalHeddle` (`kuilt-heddle-core/module.md`).

#### Before it: the caller owned concurrency

`LocalHeddle` opens one lease per slot it picks, and before the ceiling the
caller owned concurrency: the engine kept only total capacity, to size its
supply. The nearest knob, `PolicyConfig.perChildOutstandingCap`, is a different
concept. It is one figure for the whole policy, counted in service units, and it
trims a grant (§7.3 step 1) rather than skipping a candidate. `LocalHeddle` pins
it at `Long.MAX_VALUE`.

#### As built: filter the candidates

Decided in #3324, and built in three parts: declaring it (#3358), enforcing it
(#3364) and reporting it (#3367).

**Declaring it.** `maxConcurrent: Long? = null` on `Topology.Group`,
`Topology.Lane` and `Topology.ChildTemplate`, mirrored in `TopologyDocument`.
`null` means no ceiling, and nothing changes.

- On a group's `template` (`ChildTemplate`) it caps **each** attached child, not
  all of them together; the group's own `maxConcurrent` caps them all together.
  A document writes the per-child ceiling inside the template, so it cannot be
  written on a group that has none (#3368):

  ```yaml
  - type: group
    name: repos
    maxConcurrent: 8                # all repos together
    template: { maxConcurrent: 2 }  # each attached repo
  ```

- **0 means pause:** picks stop under that node. It is not a problem.
- A negative value is the one new `TopologyProblem.Kind`. A negative
  `template.maxConcurrent` is reported at the group's address.
- In a `draft`, `group`, `lane` and `template` each take it as
  `maxConcurrent: Long?`, so a value read from config passes straight through.
- The ceiling lives in the engine, read from the topology last applied
  (`lastApplied`), not in the ledger. `Topology.diff` keeps an existing edge by its
  weight, so it emits no step for a change of ceiling: no new edge and no
  history reset.

**Counting.** In leases: the open leases whose captured path passes through the
node, draining ones included. That is derived from the engine's open leases, so
nothing new is persisted. The throwaway engine `explain` and `previewPicks` run on
starts from the count of the leases open when it was built and adds each lease it
opens, so a preview counts its own picks. A view of a snapshot counts the
snapshot's leases: it carries the count, never the leases.

**Enforcement.** Before a pick builds its demand views, every leaf under a node
at its ceiling is dropped from `waiting` and from `eligible`.

- `admit`, `extend`, `complete`, and an `apply` that moves leases are never
  refused.
- Lowering a ceiling preempts nothing. Running leases finish, and picks resume
  once the count is under the ceiling.

**Fairness.** A node at its ceiling, or paused, counts as idle at its parent.
When it can take work again it comes back by its parent's clock
(`settleJoiners`), as far ahead or behind as it was when it reached the ceiling,
with no banked credit (§10 invariant 6).

**Reporting.**

- `explain` answers `WaitExplanation.AtCeiling(group, limit)`, of kind
  `AT_CEILING`, for a leaf beneath a node at its ceiling. When several nodes on
  the leaf's path are at their ceilings, `group` is the one nearest the root,
  since the whole subtree waits on it.
- Its place in the order is after `NO_LIVE_LINEAGE` and before
  `SUPPLY_EXHAUSTED`, so a capped leaf is told about its ceiling even when the
  root is empty. The lifetime limit's `LIFETIME_EXHAUSTED` (#3475), in the place
  the overflow fix's `POSITION_CEILING` (#3313) held, comes after
  `SUPPLY_EXHAUSTED`, so the order is `NOT_WAITING`, `NOT_ELIGIBLE`,
  `NO_LIVE_LINEAGE`, `AT_CEILING`, `SUPPLY_EXHAUSTED`, `LIFETIME_EXHAUSTED`,
  `BEHIND_AT_LEVEL`, `WOULD_BE_PICKED_NEXT`. Nothing persists the kind, so
  inserting it moved no stored value.
- A name no pick can end at, such as a group named as if it were a leaf, is still
  `NO_LIVE_LINEAGE` from `explain` and `acquire` alike, capped or not. The filter
  keeps such a name, and the demand lift drops it as it does with no ceiling.
- `entitlement` treats a leaf beneath a node at its ceiling as not competing: it
  is owed nothing, and its siblings share its slots.
- When every eligible leaf is capped, `acquire` returns the existing
  `NothingEligible`, whose KDoc says so. There is no new `AcquireOutcome` arm.
- Pinned: across random histories under random ceilings, `explain` answers
  `AtCeiling` exactly for a capped eligible leaf, naming the node a count of the
  open leases' paths names; the acquire that follows never picks it; and
  `entitlement` shares every slot among the uncapped leaves.

**Compatibility.** `Lane` keeps its old constructors; the group and template
shapes broke deliberately in #3368 (no 0.7.4 caller).

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
- `AT_CEILING` before `SUPPLY_EXHAUSTED`, and `LIFETIME_EXHAUSTED` after it
  (#3324, #3313, #3475).
- A per-child ceiling written beside the template rather than inside it: a
  document once had both a marker and a separate ceiling field, and dropped the
  ceiling without a word when the marker was missing. Nesting it in `template`
  makes that document impossible to write, so no second problem kind is needed
  (#3368).

### 11.3 Previewing the next picks

An admin's screen wants to show who goes next: of the twelve jobs waiting, which
three run when the next three slots free up. The engine answers without moving
anything, by playing the picks forward on a throwaway copy of itself. Two things
limited that answer, and both are now fixed. The caller had to fix each pick's
reservation up front, though in practice it depends on which leaf wins, since
each leaf's next job has its own estimate: each leaf now brings its own queue.
And the answer needed a live engine, so a reader holding only a saved snapshot
had to restore a whole engine to ask: that reader now asks a view of the
snapshot.

#### As built: each leaf's own head

`previewPicks(waiting: Map<GroupId, Int>, eligible, reservations: List<Long>)`
takes each leaf's queue as a count, and pick `i` reserves `reservations[i]`. It
runs real `acquire`s on a scratch engine that carries a copy of the wake memory,
so it changes nothing, and it stops at the first call that would not acquire.

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
  refused, that pick is left out and the list ends there, even when the root
  could still fund the next leaf: the caller's lease would hold less than its job
  needs, and its loop stops at that lease.
- Every queued reservation, not only the heads a pick reaches, is checked
  against `1..MAX_RESERVATION` before anything runs.
- Pinned: across random histories, mixed weights, with and without a reconciled
  capacity, the preview equals the leaves the real loop picks on the same state,
  and changes nothing.
- The count-based `previewPicks` stays.

#### As built: the same answers, on a snapshot

**A view of a snapshot.** `snapshot.view(policy): LocalHeddleView`, built in
#3321, carries `previewPicks` in both forms, `explain` and `entitlement`. The engine's own
methods are that view over its live state, so the two cannot disagree. Before
it, a reader holding a snapshot restored a throwaway engine from it, under
`epoch + 1`, on every question.

- The policy is a required parameter, because a snapshot does not keep one. A
  policy that cannot size the snapshot's `totalSlots` throws, as `restore` does.
- The view is built from the value `persist()` returns, `LocalHeddleSnapshot`.
  `snapshot()`'s `LocalHeddleState` will not do: it lacks the reconciled capacity
  and the topology last applied, and a pick reads both.
- It reads the root, the ledger, the wake memory, the capacity and the topology
  last applied, and of the open leases only how many lie beneath each node with a
  ceiling (§11.2): no pick or explanation reads a lease's id, time or reservation.
- Each question plays forward on a throwaway engine with its own fork of the wake
  memory, so a view is a value: asking twice gives the same answer twice, and
  readers may share one. It is additive ABI, with no format change.
- Pinned: across random histories, with and without a reconciled capacity and a
  reload, a view answers as the engine and as an `epoch + 1` restore, and moves
  neither the engine nor the snapshot. Two control arms, one forgetting the wake
  memory and one the capacity, each answer differently, so the match rests on
  the view reading both.

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
