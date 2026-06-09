# ADR-003 Addendum — Causal-stability barrier for RGA GC

**Status:** Accepted (corrects ADR-003 Decision 2 & 3)
**Date:** 2026-06-09
**Resolves:** #262 (corrects #247 / #258)

## Correction: the GC barrier in ADR-003 is unsound

ADR-003 Decision 2 GCs a tombstoned `Insert(id, _, after)` when
`id.lamport ≤ watermark` **and** the *local* op-log has no surviving
`Insert(_, _, after = id)`. Decision 3 derives `watermark` from
`SeamReplicator.universalAckFlow`.

Both halves are wrong for the same underlying reason: **per-author
delta-ack is not global causal stability.**

- `universalAckFlow = min over peers of ackedThrough[peer]`, and
  `ackedThrough[peer]` only records acks where `msg.sender == replica`
  (`SeamReplicator.onAck`). It proves *"every peer has absorbed **my**
  delta stream up to seq N"*. It proves nothing about whether those
  peers' own concurrent ops have reached **me**.
- `compact`'s condition 2 ("no surviving op has `after == id`") is
  evaluated against the **local** op-log only. A concurrent
  `Insert(J, after = I)` authored elsewhere and not yet delivered to the
  compacting replica is invisible, so a tombstoned `I` looks like a safe
  leaf.

Result (pinned by `RgaCompactConcurrentInsertSoundnessTest`, PR #260):
A GCs `I` before C's `Insert(J, after = I)` arrives. Once `Compact({I})`
and `J` fully propagate, `J`'s structural predecessor is gone
everywhere, `computeSequence` can no longer reach `J` from HEAD, and
**a committed insert is silently and permanently lost on every replica.**
Convergence is preserved; correctness is not.

## The barrier

### Invariant (sound GC condition)

> An op `I` may be GC'd only once **every peer has delivered every op
> that is, or could become, a structural predecessor obligation for `I`**
> — i.e. every op causally at-or-before `I`, author-independent, has been
> delivered to every peer.

Concretely, GC is gated on a **delivered version-vector floor**.

Let each replica maintain `delivered[author] = ` highest contiguous
RGA-author sequence this replica has *applied* (not merely received —
applied, so gaps are excluded). Each replica gossips its `delivered`
vector. Define the room floor elementwise:

```
floor[author] = min over peers p of  delivered_p[author]
```

`floor` is the largest version vector such that **every** peer has
applied every op at-or-below it, for every author.

An op `I` authored by `r` with author-sequence `s` is **globally
delivered** iff `s ≤ floor[r]`.

### Why "globally delivered predecessor" is *not* enough on its own

It is tempting to GC `I` the moment `I` itself is globally delivered.
That is insufficient in isolation: a concurrent `Insert(J, after = I)`
authored by some peer `c` can have an author-sequence *above* `floor[c]`
(it is the newest thing `c` did) while `I` sits comfortably below
`floor[r]`. Gating on `I`'s own global delivery alone would orphan a `J`
that is not yet globally delivered. This is exactly the repro.

The fix combines the floor (condition 2 below) with the **retained
local successor check** (condition 3). The floor is what makes the local
check *provably sufficient*, via this load-bearing property:

> **The floor cannot advance past an op `J` unless the compacting
> replica has itself delivered `J`** — because the floor is the
> elementwise *min* over peers, and the compactor is one of those peers.

So whenever the floor authorises GC of `I` (because `Remove(I)` /
`Insert(I)` is below it for `I`'s author), any `Insert(J, after = I)`
that the floor "knows about" is already in the compactor's own op-log,
where condition 3 sees it and refuses the GC. An `Insert(J, after = I)`
the compactor has *not* delivered cannot have pushed the floor high
enough to matter (proof below). Per-author `universalAckFlow` left this
open precisely because it is *not* a min over deliveries — it is a min
over acks of one author's stream.

Proof sketch that the floor + condition 3 prevents the data loss:

- Suppose `Remove(I)` is globally delivered: every peer `p` has applied
  `Remove(I)` (`floor` covers the removal's author-sequence).
- Any `Insert(J, after = I)` is minted by some replica `c`. For `c` to
  name `I` as predecessor, `c` must have delivered `Insert(I)`. There
  are two cases:
  - `c` minted `J` **before** delivering `Remove(I)`: then `J` exists in
    `c`'s op-log and `c` has acked nothing past it; `c`'s
    `delivered[c]` cannot have advanced past `J` until `J` itself is
    one of `c`'s applied ops — which it is, immediately, since `c`
    authored it. So `delivered_c[c] ≥ seq(J)`. But the floor is the
    *min* over peers, and the **compacting replica A** must also have
    `delivered_A[c] ≥ seq(J)` for the floor to advance — i.e. **A has
    delivered `J`.** Once A has `J`, condition 3 of `compact` (the local
    successor check) sees the surviving successor and refuses to GC `I`.
    No loss.
  - `c` minted `J` **after** delivering `Remove(I)`: a tombstone-aware
    author will not do this (it inserts after `I`'s visible successor or
    skips the tombstone). For robustness we retain condition 3 (local
    successor check) regardless — so even a pathological late `J` that A
    has delivered protects `I`. A `J` that A has *not* delivered cannot
    exist under case 1's floor argument.

The load-bearing step is: **the floor cannot advance past a successor
`J` unless the compacting replica has itself delivered `J`** (min over
peers includes the compactor). Combined with the retained local
condition-3 check, an undelivered concurrent successor can never coexist
with a floor high enough to authorise the GC. That is precisely the gap
the per-author `universalAckFlow` left open.

### Net barrier, as implemented

GC `I` (authored `r`, author-seq `s`) iff **all** hold:

1. `I` is tombstoned (unchanged).
2. `s ≤ floor[r]` — `I` is globally delivered (replaces
   `id.lamport ≤ scalar-watermark`).
3. No surviving local `Insert(_, _, after = I)` (condition 2 retained —
   now provably sufficient against undelivered successors by the floor
   argument, and still the cheap first line of defence).

The scalar Lamport watermark is replaced by a **version-vector floor**.
Condition 3 stays; the floor makes it sound.

## What `SeamReplicator` must expose

Today the replicator tracks `ackedThrough[peer]` — *per-author of MY
deltas*. It has no cross-author **delivered** vector and no gossip of
one. We add exactly that.

### State

- `deliveredLocal: Map<ReplicaId, Long>` — `author → highest contiguous
  applied author-sequence` on **this** replica. Derived from the same
  `expectedReceiveSeq` machinery that already tracks per-sender contiguous
  receive progress (it is `expectedReceiveSeq[author] - 1`), plus this
  replica's own applied count for its own author. This is *contiguous*
  (gap-excluding) by construction — `expectedReceiveSeq` only advances when
  the gap is filled, which is exactly the "applied, not merely received"
  requirement.
- `deliveredByPeer: Map<PeerId, Map<ReplicaId, Long>>` — each peer's last
  gossiped `delivered` vector.

### Wire change (minimal)

One new message, gossiped on advance and on the anti-entropy tick:

```kotlin
@Serializable @SerialName("delivered")
public class Delivered<S>(
    public val sender: ReplicaId,
    public val vector: Map<String, Long>,   // author.value → high-water seq
) : ReplicatorMessage<S>()
```

Piggybacking on `Ack` was considered and rejected: `Ack` is per-(sender,
seq) and fires on the inbound delta path; the delivered vector is a
whole-room snapshot and should gossip on its own cadence (on local apply
and on the anti-entropy tick) so it converges even in asymmetric-traffic
rooms. Keeping it a separate message also keeps `Ack`'s GC semantics
untouched.

### Derived flow

```kotlin
public val deliveredVectorFloor: StateFlow<VersionVector>
```

where `floor[author] = min over knownPeers ∪ {self} of
(deliveredByPeer[peer] ?? deliveredLocal)[author]`, defaulting absent
entries to `0`. Recomputed on: local apply, inbound `Delivered`, peer
join, **and peer eviction** (eviction can legitimately *raise* the floor,
same as `universalAckFlow`). Advances monotonically per author
(elementwise max with previous), never decreases.

`VersionVector` is a thin `Map<ReplicaId, Long>` wrapper with an
elementwise `dominates(other)` and `floorWith(other)` (elementwise min).
`DotContext`/`Dot` already model `(replica, seq)`; reuse the concept,
not necessarily the type (`DotContext` carries a cloud we don't need).

### Honest cost

This **is** distributed GC — the thing `Rga.kt` originally declared out
of scope. The added cost is one gossiped vector per peer per cadence
(O(authors) per message, O(peers·authors) memory). It is bounded and
small for chat-sized rooms (authors ≈ participants). The protocol gains
no new failure modes beyond what eviction already handles: a silently
departed peer pins the floor until the existing eviction TTL releases it,
identical to today's `universalAckFlow` pin. No new liveness hazard.

## Coordinator change

`RgaGcCoordinator`:

- Consumes `deliveredVectorFloor: StateFlow<VersionVector>` **instead of**
  `universalAck: StateFlow<Long>` + `localSeq: StateFlow<Long>`.
- Deletes the entire `seqToLamport` bridge (`observeLocalSeq`,
  `lamportWatermarkFor`) — the floor is already in author-sequence terms,
  no seq→lamport guessing.
- `compactUntilStable` passes the floor to a new
  `Rga.compact(floor: VersionVector)` overload.

`Rga.compact` signature change:

```kotlin
public fun compact(floor: VersionVector): Pair<Rga<V>, RgaOp.Compact>?
```

Condition becomes: tombstoned `id` is GC-eligible iff
`floor.dominates(id)` (i.e. `id.authorSeq ≤ floor[id.replicaId]`) **and**
no surviving local successor. **Open modelling question** (see Risk):
`RgaId` carries `lamport`, not a per-author sequence. The floor must be
keyed so that `compact` can test an individual `RgaId`. Resolution:
either (a) add a per-author monotonic `seq` to `RgaId`
(`RgaId(lamport, replicaId, seq)`) — clean but a wire-format change to
every RGA op; or (b) key the floor by `(replicaId → highest RGA lamport
from that author that is globally delivered)` and test
`id.lamport ≤ floor[id.replicaId]`, exploiting that a single author's
lamports are locally monotonic in mint order. (b) avoids the RgaId wire
change and is the recommended path; it requires the replicator's
delivered vector to be expressed in **RGA-author lamports**, not delta
seqs — achievable because each `Delta` carries the op and the op carries
its `RgaId`. This is the single biggest open question; see Risk.

The scalar `compact(watermark: Long)` overload is **removed** (it is
unsound; no caller should keep it). Tests migrate to the VV form.

## Migration

| Surface | Disposition |
|---|---|
| `SeamReplicator.universalAckFlow` (#252) | **Keep** — it still correctly gates *delta-buffer* GC (`gcPendingDeltas`), which is per-author by design and sound. It is **no longer** the RGA GC watermark. Re-document: "delta-buffer GC only; NOT a causal-stability barrier — use `deliveredVectorFloor`." |
| `SeamReplicator.nextSeqFlow` (#253) | **Deprecate / remove** — its sole consumer was the coordinator's `seqToLamport` bridge, which is deleted. Drop unless another consumer exists. |
| `Rga.compact(watermark: Long)` | **Remove** — replaced by `compact(floor: VersionVector)`. |
| `RgaOp.Compact`, `purge`, `piece` GC | **Keep unchanged** — the op-log purge mechanics and idempotent merge are correct; only the *gating decision* was wrong. |
| `WindowPolicy`, serializers, coordinator structure | **Keep** — fully reusable. Windowing rides the same `Compact` op. |
| `RgaGcCoordinator3PeerIntegrationTest` | Keep, but it cannot exercise the hazard (atomic `InMemoryLoom` fan-out). Real coverage needs the controllable-delivery fake seam (#263). |

`RgaGcCoordinator` stays **experimental / unconsumed** until this lands,
so the breaking signature change costs no downstream migration.

## Testability

- **Unit (immediate):** extend `RgaCompactConcurrentInsertSoundnessTest`
  to call `compact(floor)`. With a floor that does **not** yet cover the
  compactor's delivery of `J`, `I` must NOT be GC'd → `J` survives. With
  a floor proving every peer (including A) delivered `J`, condition 3
  catches the surviving successor. Both directions testable purely at the
  `Rga` level, no seam. This is the fix's first commit (TDD: red → green).
- **Replicator unit:** `deliveredVectorFloor` tracks the elementwise min
  across ≥3 gossiped vectors; advances on eviction; monotonic. Deterministic
  with the existing fake-seam + virtual-clock harness.
- **End-to-end coordinator:** requires the controllable-delivery fake seam
  (#263, in progress) to interleave "A compacts before J arrives." Gated
  on #263; the unit-level invariant above pins the soundness meanwhile.

## Sub-issue decomposition

| # | Title | Acceptance |
|---|-------|------------|
| 1 | `VersionVector` value type + `Rga.compact(floor)` overload | `VersionVector` (`Map<ReplicaId,Long>`, `dominates`, `floorWith`, elementwise-max merge). `Rga.compact(floor)` GCs a tombstone iff `id.lamport ≤ floor[id.replicaId]` AND no surviving local successor. Extended `RgaCompactConcurrentInsertSoundnessTest` goes green (J survives under an insufficient floor). Scalar `compact(Long)` removed; `RgaTest` battery green. |
| 2 | `SeamReplicator` delivered-vector gossip | New `ReplicatorMessage.Delivered`; replicator tracks `deliveredLocal` (RGA-author lamports, contiguous) + `deliveredByPeer`; gossips on apply + anti-entropy tick; absorbs inbound `Delivered`. Deterministic 3-peer test: each peer's gossiped vector reflects its applied ops. No regression to delta/ack/resend paths. |
| 3 | `SeamReplicator.deliveredVectorFloor` StateFlow | `public val deliveredVectorFloor: StateFlow<VersionVector>` = elementwise min over `knownPeers ∪ self`; advances monotonically per author; recomputes on apply / inbound `Delivered` / join / eviction. ≥3-peer deterministic test confirms it tracks the min and that eviction raises it. Re-document `universalAckFlow` as delta-buffer-only. |
| 4 | `RgaGcCoordinator` switch to floor | Coordinator consumes `deliveredVectorFloor`, drops `seqToLamport`/`localSeq`/`nextSeqFlow` consumption, calls `compact(floor)`. `RgaGcCoordinator3PeerIntegrationTest` green (converges, bounded op-log, **no lost inserts**). End-to-end hazard test deferred to #263. |

Order: 1 → (2, 3 can pipeline) → 4. Sub-issue 1 is independently
mergeable and lands the fix at the `Rga` level immediately; 2–4 wire it
through the live path.

## The single biggest risk / open question

**Keying the floor to individual `RgaId`s.** The replicator's natural
unit is the **delta sequence** (per-sender, 1-per-apply); the RGA's unit
is the **`RgaId(lamport, replicaId)`**. `compact` must test an individual
`RgaId` against the floor, so the floor must be expressed in terms
`compact` can compare against an `RgaId`. The recommended path (option
(b) above) keys the floor as `replicaId → highest globally-delivered RGA
lamport from that author`, relying on a single author's lamports being
monotonic in mint order. This holds **only if each delta carries exactly
one RGA op and lamports per author are gap-free in delivery** — which the
contiguous `expectedReceiveSeq` machinery gives us, but the mapping from
"delta seq N from author X" to "RGA lamport L" must be extracted from the
delta payload, not assumed. If a single delta ever batches multiple RGA
ops, or if `RgaOp.Compact` deltas (which have no single author lamport)
perturb the per-author lamport monotonicity, option (b) breaks and we
fall back to option (a): add an explicit per-author `seq` to `RgaId` (a
wire-format change to every RGA op, but unambiguous). **Decision needed
in sub-issue 1 before 2–4 can be specified precisely.** This is the one
place the design could balloon — if (b) proves unsound under batching,
(a)'s RgaId wire change touches every serialized op and the published
0.3.x RGA format.
