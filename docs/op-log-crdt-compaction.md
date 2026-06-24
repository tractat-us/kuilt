# Op-log CRDT compaction design

**Status:** Design note — implementation pending (#714 Fugue, #725 MovableTree)

## The problem, in plain terms

A replicated session that runs for a long time — a shared document that people edit for days, a task hierarchy that a team rearranges over weeks — keeps growing in the background even when it looks idle to users. Every insert, every move, every delete is recorded as an operation and kept in an op-log. The log is never trimmed.

This is invisible at first. But it compounds: every time a new peer joins, they receive the **entire op-log** as their starting state. Every anti-entropy round — the background sync that keeps replicas from drifting apart — sends the full log again. A session with M operations costs O(M) per sync, and M grows without bound.

Three types in `:kuilt-crdt` accumulate ops this way: `Rga`, `Fugue`, and `MovableTree`. They are the **op-log CRDTs** — their state *is* the log. (The other types in the module — `ORSet`, `MVRegister`, `ORMap`, `ResettableCounter` — carry a compact dot-context that self-trims; they are not discussed here.)

`Rga` already has a working compaction path, implemented in #262/#639. `Fugue` and `MovableTree` do not — their op-logs grow forever. This note:

1. Explains what "safe to discard" means for each type.
2. Proposes the `causalDots()` and compaction API each should implement.
3. Notes the open questions before implementation begins.

---

## Background: how `Rga` compaction works today

### Causal stability

A distributed system has no central clock. "Has everyone seen op X?" is answered by the `Quilter` replicator, which gossips each peer's **delivered version vector** — the highest contiguous sequence number each peer has applied per author. From these, two quantities are derived:

- **Stable cut `S`** — the elementwise minimum over all live peers' delivered VVs. An op with dot `(author, seq)` is **causally stable** once `seq ≤ S[author]`: every live peer has applied it. No peer can ever produce a concurrent op that predates it.
- **Frontier max `F`** — the elementwise maximum of known-to-exist dots (including a retained floor for recently-evicted peers, per ADR-003 addendum v3). This tells the compactor "is there any op out there I haven't delivered yet?"

### The `causalDots()` hook

`Quilted.causalDots()` is the bridge. A CRDT overrides it to expose the `(author, seq)` dots it has delivered. `Quilter` folds these into the delivered VV it gossips. Without an override, the default returns `emptySet()` — the CRDT is invisible to the GC machinery and its ops are never considered for compaction.

`Rga` overrides this: it emits one dot per `Insert` op, plus the compacted ids recorded in any `Compact` op (to keep the contiguous frontier intact after GC removes raw inserts from the log).

### The `Rga.compact()` safety conditions

A tombstoned `Insert(id)` is eligible to be dropped when ALL of:

1. **Tombstoned** — a `Remove(id)` is in the log.
2. **Causally stable** — `id.seq ≤ S[id.author]`.
3. **Frontier-complete** — self has delivered every op below every known frontier (`delivered[x] ≥ F[x]` for all x). This rules out the existence of a concurrent `Insert(J, after=id)` anywhere in the system.
4. **No surviving successor** — no live `Insert` has `after == id`. Dropping `id` while something points to it as its predecessor would orphan that successor.

The compacted ids are broadcast as a `RgaOp.Compact` delta so peers can trim their own logs. Compacted ids are re-emitted in `causalDots()` so the contiguous frontier does not develop holes after GC.

---

## The taxonomy: op-log vs. dot-store CRDTs

This distinction determines whether a CRDT author needs to implement `causalDots()` and a compaction path:

| Category | Types | GC mechanism |
|----------|-------|--------------|
| **Op-log** | `Rga`, `Fugue`, `MovableTree` | Must implement `causalDots()` + explicit compaction; the log never shrinks without it |
| **Dot-store** | `ORSet`, `MVRegister`, `ORMap`, `ResettableCounter` | State carries an embedded `DotContext`; operations self-compact via the existing dot-store join — no `causalDots()` needed |
| **Value / counter** | `GCounter`, `PNCounter`, `LWWRegister`, `LWWMap`, `GSet`, `TwoPhaseSet`, `BoundedCounter`, `EphemeralMap` | No per-op log; state is a fixed-size lattice value — already O(peers), not O(ops) |

A new CRDT author must decide which category applies. The test: does the CRDT's state grow proportionally to the number of **operations ever performed**, regardless of current observable value? If yes, it is op-log and needs `causalDots()`.

---

## `Fugue` compaction

### The structure

`Fugue` is a sequence CRDT. Its op-log is a set of `FugueOp` values — either `Insert` (adds a node to the tree, carrying tree-placement metadata) or `Remove` (tombstones a node). The sequence is materialized by a depth-first traversal of the tree built from the insert ops.

`Fugue` currently returns `emptySet()` for `causalDots()`. Its op-log is unbounded.

### What a `FugueId` needs

`FugueId` today carries `(lamport, replicaId)`. Like the pre-ADR-003-addendum `RgaId`, this is not a dense per-author sequence — a replica's lamport timestamps are monotonic but can jump. The causal-stability barrier needs a dense `(author, seq)` dot.

**Required change:** add a `seq: Long` field to `FugueId`, exactly as was done for `RgaId`. This is a wire-format break. It is cheap to make now (pre-1.0, format explicitly unstable) and expensive to retrofit after consumers lock in.

### Which ops are safe to compact?

A `FugueOp.Insert(id, ...)` paired with a `FugueOp.Remove(id)` is a candidate once:

1. **Tombstoned** — `Remove(id)` is in the log.
2. **Causally stable** — `id.seq ≤ S[id.replicaId]`.
3. **Frontier-complete** — `delivered[x] ≥ F[x]` for all authors `x`. (Same as `Rga`: rules out a concurrent `Insert` anywhere in the system that references this node as its tree parent.)
4. **No surviving tree anchor** — no live `Insert` has `parent == id` OR `rightOrigin == id`. Dropping a node while another node still references it as its tree parent would orphan that subtree; dropping a node that is still a `rightOrigin` would change the sibling-ordering comparator result for the right children of that parent, breaking non-interleaving.

The `parent` field plays the same structural role in Fugue that `after` plays in Rga: it is the positional anchor. `rightOrigin` is a secondary anchor — it determines the ordering of concurrent right-children inserted at the same parent. Both must be guarded: `liveAnchors = { op.parent | op ∈ liveInserts } ∪ { op.rightOrigin | op ∈ liveInserts, op.rightOrigin ≠ null }`. When a node is GC'd, any surviving child whose parent is the compacted node needs to be re-rooted to the nearest surviving ancestor. `Rga` handles this with a `compactPositions` map; `Fugue.compact()` uses the same: a `Compact` op carrying `positions: Map<FugueId, FugueId>` (each compacted id mapped to its parent at GC time) so that `buildTree` can chain-walk to the nearest surviving ancestor.

### `causalDots()` shape

```kotlin
override fun causalDots(): Set<Dot> =
    ops.asSequence()
        .flatMap { op ->
            when (op) {
                is FugueOp.Insert -> sequenceOf(op.id.dot)   // dot = (replicaId, seq)
                is FugueOp.Compact -> op.positions.keys.asSequence().map { it.dot }
                is FugueOp.Remove -> emptySequence()          // same reasoning as Rga: Remove mints no dot
            }
        }
        .toSet()
```

The `Remove` exclusion matters for the same reason as in `Rga`: a `Remove(id)` reuses the target insert's id and mints no new dot. Counting it would over-claim when a `Remove` arrives before its corresponding `Insert`.

### Broadcast mechanism

A `FugueOp.Compact` op (analogous to `RgaOp.Compact`) carries the compacted positions map. It is retained in the log after application (GC keeps the Compact, drops the Insert + Remove). Peers that receive it apply it via `piece` or `apply`. A late-joining peer receives a `FullState` that already reflects the compaction; they cannot re-introduce the dropped ops.

---

## `MovableTree` compaction

### The structure

`MovableTree` is a replicated tree that supports concurrent reparent operations (`move()`). Its state is a sorted `List<MoveOp>`. On every merge, the log is replayed in timestamp order; each op is applied unless it would create a cycle. The effective parent map (`effectiveParents`) is derived entirely from this replay.

Unlike Rga and Fugue — where every op is either an insert or a tombstone — a `MoveOp` can be the **winning op**: the one whose effect the replay keeps. This makes compaction subtler. An `Rga` tombstone GC is always "discarding a losing op"; a `MovableTree` compaction must sometimes discard a *winning* op because a later winning op has superseded it.

`MovableTree` currently returns `emptySet()` for `causalDots()`. The KDoc on the class notes the op-log is unbounded; this issue tracks closing that gap.

### What a `MoveOp` needs

`MoveOp` today carries `(ts, replica, node, newParent, value?)`. `ts` is a Lamport timestamp — monotonic per replica but not dense. The causal-stability barrier needs a dense `seq` per `(ts, replica)` pair.

**Required change:** add a `seq: Long` field to `MoveOp`, or derive a dense per-replica sequence separately (e.g. use the insertion position in the sorted log as the seq — this works because `insertSorted` preserves order and deduplicates by `(ts, replica)`). The wire format change is the same as for `FugueId`: cheap now, expensive later.

### Which ops are safe to compact?

A `MoveOp(ts, replica, node, newParent)` may be dropped once it is both causally stable **and** its effect has been superseded by a later stable op on the same node. Specifically, an op `opA` on node `n` is redundant if:

1. **Causally stable** — `opA.seq ≤ S[opA.replica]`.
2. **Superseded** — there exists a later causally stable op `opB` on the same node `n` (i.e. `opB.ts > opA.ts` or `opB` wins the tiebreak) whose effect is what the current replay actually keeps. In other words, `opA` does not determine the current parent of `n`.
3. **Not a creation op** — `opA.value != null` means it is the op that first registered node `n`. This op must be retained if any live op references `n` (either as a node being moved, or as a `newParent`). A creation op carries the node's value; losing it would lose the node's identity.

The replay determines which op wins for each node: the winning op for node `n` is the highest-priority non-cycle-inducing op on `n` in the final replay state. Every other op on `n` is "losing" and is a compaction candidate once causally stable.

**The safety argument.** The replay is deterministic and order-preserving. Removing a causally-stable losing op from the log cannot change the replay outcome: the winning op for each node is unaffected (it is either the creation op or a later higher-priority move, both of which are retained). No surviving op references a losing op (they reference nodes by id, not by op identity). Therefore, the replay output is identical before and after compaction of a losing op.

**Cycle prevention is preserved.** The cycle check in `replayLog` operates on the growing parent map. A losing op is one whose application was either skipped (cycle) or later overwritten by a higher-priority op. In both cases, retaining or dropping it does not affect the effective parent map produced by the replay — the winning ops are unchanged.

**Creation ops.** The `addNode()` op doubles as the creation record. It must be retained as long as any live op — in the remaining log — references `node` as either the moved node or a `newParent`. Once a node is orphaned (no remaining op references it), the creation op is safe to drop.

### `causalDots()` shape

```kotlin
override fun causalDots(): Set<Dot> =
    log.mapTo(mutableSetOf()) { op -> Dot(op.replica, op.seq) }
```

Unlike Rga/Fugue, there is no "Compact" op that carries compacted dots — a simpler approach is to maintain a separate `compactedDots: Set<Dot>` recorded at compaction time, and include those in `causalDots()` so the contiguous frontier survives GC. (This mirrors the `Rga` pattern where `Compact.positions.keys` re-emit the compacted ids' dots.)

### Broadcast mechanism

A `MovableTree.Compact` value carries the set of `(ts, replica)` pairs that were dropped. Peers apply it by removing those ops from their own log. Because `replayLog` is deterministic, if two replicas start from the same full log and one broadcasts a compact, the other can apply it safely — their replay outcome is already identical, and the dropped ops are truly redundant on both sides.

---

## Open questions

### 1. `FugueId` and `MoveOp` seq fields (wire-format break)

Both types need a dense `seq` per author before `causalDots()` can be implemented. This is the first step of each implementation PR. It should happen before any consumer locks in on the wire format.

### 2. `MovableTree` creation-op retention rule

The precise rule for when a creation op is safe to drop is: no op in the remaining log references the node as `node` or `newParent`. Implementing this requires scanning the surviving log on each compaction pass — O(k) where k is the log size at compaction time, not O(M) total. Worth checking that this scan stays fast enough in practice for large trees; if not, a reverse index (`nodeId → ops that reference it`) amortizes it to O(1) per op.

### 3. MovableTree full-log replay-from-scratch on every `piece()` (#728)

`MovableTree.piece()` triggers a full `replayLog` on every merge. `replayLog` is O(n × depth) — O(n²) for a deep tree. This is a correctness-independent performance problem that matters under eager-flood gossip. It is tracked separately at #728 but is worth coordinating: the compaction design should not foreclose the incremental-parent-map fix that #728 proposes.

### 4. `retainedFrontier` applicability

The v3 causal-stability barrier includes a `retainedFrontier` to prevent a compactor from discarding ops that a just-evicted peer had minted but not yet propagated. This machinery exists in the `RgaGcCoordinator`. A `FugueGcCoordinator` and `MovableTreeGcCoordinator` would consume the same `Quilter.stableCut` / `frontierMax` / `deliveredLocal` signals. **No new Quilter changes are needed** — the v3 signals are already there.

### 5. `Compact` op as a sealed variant vs. a side-channel

`Rga` encodes `Compact` as a `RgaOp<Nothing>` variant inside the existing op sealed hierarchy. The alternative is a separate side-channel (a parallel `sealed interface RgaControl`). The chosen approach — single hierarchy — is simpler and produces one serializer. Fugue and MovableTree should follow the same pattern. If the compacted-positions map is large, consider whether a bloom filter or summary is sufficient for the propagation step (not needed for correctness but may matter for wire size).

---

## Suggested implementation order

1. **#714 — `Fugue`:** wire-format break first (add `seq` to `FugueId`), then `causalDots()` override, then `compact(stableCut, frontierMax, delivered)` + `FugueOp.Compact`, then a `FugueGcCoordinator` mirroring `RgaGcCoordinator`.
2. **#725 — `MovableTree`:** wire-format break first (add `seq` to `MoveOp`), then `causalDots()`, then `compact()` with the creation-op retention rule, then a coordinator.
3. **#728 — `MovableTree` replay perf:** independent of compaction but should be reviewed alongside #725 so the two designs stay compatible.

Each of #714 and #725 is a separate PR chain (wire break → causalDots → compact primitive → coordinator). They do not depend on each other and can be dispatched in parallel.

---

## Reference: `Rga` compaction call graph

For implementors using `Rga` as a template:

```
Quilter.stableCutFlow / frontierMaxFlow / deliveredFlow
    └─ RgaGcCoordinator.observeStability()
           └─ rga.compact(stableCut, frontierMax, delivered)
                  ├─ emits Pair<Rga<V>, RgaOp.Compact>?
                  └─ coordinator calls quilter.apply(patch)
                         └─ quilter broadcasts RgaOp.Compact as delta
                                └─ peers: rga.apply(RgaOp.Compact)
                                       └─ purges Insert + Remove, retains Compact
```

`Fugue` and `MovableTree` follow the same shape with their own `Compact` op types and coordinators.
