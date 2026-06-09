# ADR-003 — RGA tombstone GC + history windowing

**Status:** Accepted
**Date:** 2026-06-08
**Resolves:** #247

## Context

`Rga` is op-based: the op-log (`Set<RgaOp>`) grows without bound —
every `Insert` and every tombstoned `Remove` accumulates forever.
`Rga.kt` is explicit that tombstone GC is out of scope; there is also
no convergent equivalent of `MutableSharedFlow(replay = N, DROP_OLDEST)`.

`SeamReplicator` already tracks exactly the causal-stability signal
needed for GC: `ackedThrough[peer]` records the highest delta seq
each known peer has acknowledged, and `gcPendingDeltas` computes
`universalAck = knownPeers.minOf { ackedThrough[peer] ?: 0L }` —
the highest seq every current peer has absorbed. Peer eviction
(`evictStalePeers`) removes absent peers after a configurable TTL so
they cannot pin the watermark forever.

## Decisions

### 1. Layering: new `RgaGcCoordinator` alongside the replicator

GC and windowing logic lives in a new coordinator type,
`RgaGcCoordinator`, following the `BoundedCounterTransferCoordinator`
precedent. `Rga` gains a single new primitive (`compact(watermark)`).
`SeamReplicator` is unchanged except for one new `StateFlow<Long>`.

**Rationale.** Three alternatives considered:

- *In `Rga` (self-contained GC):* `Rga` has no view of peer ack state;
  it would need to accept an externally-derived watermark anyway,
  making the primitive context-aware. `compact(watermark)` is the
  right primitive; the decision of *when* to call it belongs outside.
- *In `SeamReplicator` (generic GC hook):* `SeamReplicator` is typed
  over `Quilted<S>`; teaching it to compact `Rga` specifically breaks
  the generic boundary. A GC hook parameterized by watermark would
  work but couples replication policy to structural knowledge of one
  CRDT type. The coordinator pattern avoids this.
- *`RgaGcCoordinator` (chosen):* mirrors `BoundedCounterTransferCoordinator`
  in structure — it observes replicator state, acts on a condition
  (`universalAck` advancing), calls a mutation on the CRDT, and
  broadcasts the resulting delta. Clean separation; independently
  testable.

### 2. Tombstone GC via causal stability

**Primitive added to `Rga`:**

```kotlin
// Returns a compacted Rga with causally-stable tombstoned ops removed,
// plus a Patch<Rga<V>> delta to broadcast. Returns null patch if nothing
// was compacted.
fun compact(watermark: Long): Pair<Rga<V>, Patch<Rga<V>>?>
```

**Safety condition.** A tombstoned `RgaOp.Insert(id, _, after)` may be
removed from the op-log only when both conditions hold simultaneously:

1. `id.lamport ≤ watermark` — the op is causally stable (every current
   peer has seen the delta that carried it).
2. No surviving op has `after == id` — no op in the remaining log
   references this id as its predecessor.

Condition 2 is the structural invariant. An `Insert(B, _, after=A)`
with `A` tombstoned keeps `A` anchored until `B` is also tombstoned and
compacted. In practice, for a chat log where deleted messages are
rarely used as insert predecessors, most tombstones compact quickly.

**`Compact` op typing.** `Compact` is encoded as a variant of the
single `RgaOp<V>` sealed hierarchy using `RgaOp<Nothing>` — one
hierarchy, one serializer, no parallel `RgaControl` type:

```kotlin
@Serializable
data class Compact(val ids: Set<RgaId>) : RgaOp<Nothing>
```

The coordinator broadcasts the `Compact` op as a delta. Any peer that
receives it removes those ids from its own op-log (no-op if already
absent). `piece` for `Compact` is union of the `ids` sets.

**Late joiner and eviction safety.** A returning peer (previously
evicted) receives a `FullState` from `SeamReplicator`. That state
already reflects compaction — GC'd ops are absent. The returning peer
cannot re-introduce them. `Compact` ops themselves are retained in the
log as lightweight "GC tombstones" to make `FullState` merges
idempotent; their total size is bounded by the number of compaction
events, not the number of elements ever inserted.

**Watermark derivation.** `RgaGcCoordinator` consumes
`SeamReplicator.universalAckFlow` (see Decision 3). When it advances,
the coordinator calls `compact(watermark)`, passes the patch to the
replicator's `apply`, and the delta propagates normally.

### 3. `SeamReplicator.universalAckFlow` — permanent public API

`SeamReplicator` exposes a new deliberate, permanent public property:

```kotlin
public val universalAckFlow: StateFlow<Long>
```

It tracks `min(ackedThrough over knownPeers)` and advances
monotonically. This is a first-class API commitment, not an internal
accessor promoted for convenience. The coordinator observes it
directly; no re-derivation from observed deltas inside the coordinator.

### 4. History windowing / stable-prefix truncation

A convergent window drops elements from the visible prefix using the
same `Compact` op. The coordinator accepts a `WindowPolicy`:

```kotlin
fun interface WindowPolicy {
    /** Return the set of element ids to drop from the visible prefix. */
    fun idsToTruncate(sequence: List<RgaId>, tombstones: Set<RgaId>): Set<RgaId>
}
```

Built-in policies:
- `WindowPolicy.byCount(n)` — keep the last `n` visible elements;
  compact the leading prefix of visible and tombstoned ops whose
  structural predecessors are entirely outside the retained window.
- `WindowPolicy.never()` — GC-only, no windowing (the default).

**Per-peer policy divergence is allowed.** Two replicas may run
different `WindowPolicy` configurations; no room-uniform enforcement.
After set-union merge, the more-aggressive window dominates — each
replica ends up compacting the union of what either dropped. This
"most-aggressive-window-wins" behaviour is expected and convergent.

**Convergence.** The window is expressed as a `Compact` op and
broadcast identically to tombstone GC. Asymmetric races (one replica
has windowed, the other has not yet) resolve via set-union: after
merging, both have compacted the union of what either dropped.

**Late-joiner semantics.** A new peer receives a `FullState` already
reflecting the window. It converges to the current window without
replaying dropped history. This is the convergent replacement for
`DROP_OLDEST`.

**API shape:**

```kotlin
public class RgaGcCoordinator<V>(
    private val replicaId: ReplicaId,
    private val replicatorState: StateFlow<Rga<V>>,
    private val universalAck: StateFlow<Long>,
    private val applyCompaction: (Patch<Rga<V>>) -> Unit,
    private val windowPolicy: WindowPolicy = WindowPolicy.never(),
    private val scope: CoroutineScope,
)
```

### 5. Forward-looking: edit (LWW-per-element)

An update-in-place primitive would associate each `RgaId` with a
`LWWRegister<V>` rather than a fixed value. GC interacts cleanly:
once an id is tombstoned, its register state is irrelevant; the
`Compact` op removes both the insert and its register. The window
design does not foreclose this path.

## Resolved decisions

| # | Question | Resolution |
|---|----------|------------|
| Op typing | `Compact` as `RgaOp<Nothing>` or a parallel `RgaControl` sealed type? | `RgaOp<Nothing>` — single hierarchy, one serializer. |
| Watermark API | Internal accessor or public `StateFlow<Long>`? | `public val universalAckFlow: StateFlow<Long>` on `SeamReplicator` — deliberate, permanent. |
| Window-policy coordination | Room-uniform enforcement or per-peer divergence? | Per-peer divergence allowed; "most-aggressive-window-wins" set-union is expected convergent behaviour. |

## Sub-issue decomposition

Each sub-issue is independently mergeable, testable with the virtual-
clock fake harness, and must be green on wasmJs + iOS.

| # | Title | Acceptance |
|---|-------|------------|
| A | `Rga.compact(watermark)` — tombstone GC primitive | `compact` removes causally stable tombstones satisfying the predecessor-safety check; `Compact: RgaOp<Nothing>` op merges idempotently; existing `RgaTest` battery stays green |
| B | `SeamReplicator.universalAckFlow` — expose causal-stability watermark | New `public val universalAckFlow: StateFlow<Long>` advances monotonically; deterministic test with fake seam + ≥3 peers confirms it tracks the minimum ack |
| C | `RgaGcCoordinator` — drives compaction from replicator ack state | Coordinator triggers `compact` when watermark advances; delta propagates normally; 3-peer integration test converges to bounded op-log under continuous insert + remove |
| D | History windowing — `WindowPolicy.byCount(n)` | Late joiner over a 3-peer room with `byCount(10)` converges to the windowed state via `FullState`, not full-history replay; per-peer policy divergence resolves by set-union |

## Consequences

- `Rga` gains `compact(watermark)` and a `Compact: RgaOp<Nothing>` op variant.
- `SeamReplicator` gains one new public `StateFlow<Long>`
  (`universalAckFlow`); no other changes.
- New `RgaGcCoordinator` class (similar scope to
  `BoundedCounterTransferCoordinator`) in `kuilt-crdt/replicator/`.
- No changes to `MuxSeam`, `Room.channel`, or any fabric module.
- The coordinator pattern is confirmed as the right model for
  CRDT-specific replication side-channels.
