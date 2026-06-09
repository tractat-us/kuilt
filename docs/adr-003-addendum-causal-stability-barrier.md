# ADR-003 Addendum — Causal-stability (stable-VV) GC barrier for RGA

**Status:** Accepted (corrects ADR-003 Decisions 2 & 3; supersedes the
per-author-floor draft)
**Date:** 2026-06-09
**Resolves:** #262 (corrects #247 / #258)

> This is the **second** addendum design. The first (per-author delivered-vector
> floor) was falsified by an adversarial executable model — it carried forward the
> same *author-independence* bug as the original `universalAckFlow` watermark. This
> version is re-derived from the established **causal-stability / stable-version-vector**
> construction for op-based CRDT garbage collection, and is sound *by construction*
> against that counterexample. The counterexample trace is in §5.

## 1. The bug, stated once, precisely

ADR-003 Decision 2 purges a tombstoned `Insert(I, _, after)` when
`I.lamport ≤ watermark` **and** the *local* op-log has no surviving
`Insert(_, _, after = I)`. Decision 3 derives `watermark` from
`SeamReplicator.universalAckFlow`.

Both halves share one root error: **they gate the purge of `I` on `I`'s own
author, then trust a successor check against the compactor's local op-log.**
But the dangerous operation is a concurrent `Insert(J, after = I)` minted by a
**different** author `c`. The compactor may hold a watermark/floor high enough to
authorise purging `I` while `J` is still **in flight and invisible** to it — its
local successor check sees nothing, and it purges. When `Compact({I})` and `J`
then propagate, `J`'s structural predecessor is gone everywhere, `computeSequence`
can no longer reach `J` from `HEAD`, and **a committed insert is silently and
permanently lost on every replica.** Convergence holds; correctness does not.

The per-author floor (first addendum) fails identically: it gates on `floor[I.author]`,
never on `floor[J.author]`. `J`'s author is independent of `I`'s; a per-author
quantity keyed on `I` cannot witness `J`.

**The compactor cannot distinguish "peer `c` has nothing new" from "peer `c` has
minted a `J` I have not yet delivered" from any per-author scalar or vector keyed
on `I`.** That is the whole problem, and it dictates the fix.

## 2. The construction: causal stability over a stable version vector

This is the standard result for garbage-collecting op-based / delta CRDTs. An
operation `x` is **causally stable** at a replica once `x` has been delivered at
**every** replica. At that point no operation concurrent with `x` can ever arrive
anywhere again — every future delivery is causally *after* `x`, never concurrent.

Causal stability is decided with a **matrix clock**:

- Each replica maintains its own **delivered version vector**
  `delivered[author] = highest contiguous author-sequence it has applied`
  (contiguous = gap-excluding; an op with an unfilled causal gap before it is *not*
  counted). This is exactly the `vv` of a `DotContext` (`docs`/`DotContext.kt`).
- Each replica **gossips** its `delivered` VV. A replica thus holds a *matrix* of
  frontiers: `frontiers[peer] = ` that peer's last-gossiped `delivered` VV
  (including its own).
- The **stable cut** is the elementwise minimum over the matrix:
  `S[author] = min over peers p of frontiers[p][author]`.

`S` is the largest VV such that **every** replica has applied every op at-or-below
it. An op `x` with dot `(author, seq)` is causally stable iff `seq ≤ S[author]`.

The matrix — full VVs, not a single shared min — is load-bearing. The gossiped
**frontier** of peer `c` tells the compactor that a dot `(c, seq(J))` *exists*,
even before the compactor has delivered `J`'s payload. That is precisely the
knowledge a per-author floor throws away.

## 3. The barrier (sound predicate)

Purge a tombstoned `I` (dot `(r, sᵢ)`) iff **all** hold:

1. **Tombstoned** — `Remove(I)` is in the log (unchanged).
2. **Causally stable** — `sᵢ ≤ S[r]`, where `S` is the stable cut above.
3. **Frontier-complete** — for every author `x`, the compactor has delivered every
   op at-or-below the *maximum* frontier any peer reports:
   `delivered[x] ≥ max over peers p of frontiers[p][x]`.
   Equivalently: the compactor knows of **no** dot that exists-yet-is-undelivered.
4. **No surviving local successor** — no `Insert(_, _, after = I)` in the local
   op-log (condition 2 of ADR-003, retained).

Condition 3 is the new, decisive one. It is what makes condition 4 *complete*:

> Once the compactor has delivered every op below every reported frontier, any
> `Insert(J, after = I)` that exists anywhere has been delivered locally — so the
> local successor check (4) sees it and refuses. A `J` the compactor has *not*
> delivered cannot satisfy condition 3 (some frontier sits above `delivered`), so
> the purge is refused before condition 4 is even consulted.

Conditions 2 and 3 together are the operational statement of "`I` is causally
stable **and** its neighbourhood is settled." Neither alone suffices — 2 alone is
the falsified design; 3 alone would over-purge nothing but is the completeness
guarantee that retires the author-independence bug for good.

> **On condition 2 vs 3.** Condition 3 implies `delivered ≥ S` is saturated for
> the compactor, so in steady state 2 follows from a saturated 3. They are kept
> separate because (a) it states the theory directly — *stability* (2) is the
> classical predicate, *frontier-completeness* (3) is why the local check is sound
> — and (b) an implementation may advance `S` lazily; keeping 2 explicit means the
> predicate is correct regardless of when `S` is recomputed.

## 4. Why the per-author floor could never work, and this does

The per-author floor's proof claimed: *"for the floor to advance past `Remove(I)`,
the compacting replica must have delivered `J`, because the floor is a min over
peers including the compactor."* This is false. The floor over **`I`'s author**
advancing says nothing about **`J`'s author**; the compactor can deliver its own
`Remove(I)` (advancing `floor[r]`) while `delivered[c] = 0`. The floor `{r: sᵢ, c: 0}`
is a legitimate min-over-peers value, and it authorises the purge. The proof
checked `floor[r]`; the hazard lives in author `c`.

The stable-cut barrier closes this because condition 3 keys off **every** author's
*frontier*, not `I`'s author's *delivery*. The compactor holds C's gossiped
frontier `{…, c: seq(J)}`; it sees `max frontier[c] = seq(J) > delivered[c] = 0`;
condition 3 fails; the purge is refused — **without ever needing to reason about
which tombstone `J` happens to reference.** The author-independence bug cannot
recur because the predicate no longer keys on the tombstone's author at all for
the completeness check.

## 5. By-construction trace of the #272 counterexample

The interleaving (`RgaCompactFloorBarrierProbeTest`, PR #272):

- A: `Insert(I)` at `HEAD` (dot `(a,1)`), then `Remove(I)` (dot `(a,2)`).
- C: delivers `Insert(I)` and `Remove(I)`, then mints `Insert(J, after = I)`
  (dot `(c,1)`) — **concurrent** with `Remove(I)`, **not** delivered to A.
- A is the compactor.

A's matrix clock:

```
delivered_A   = { a: 2, c: 0 }     // A applied its own I, Remove(I); nothing of C
frontier_C    = { a: 2, c: 1 }     // C applied I, Remove(I), and its own J
frontiers     = { A: delivered_A, C: frontier_C }
S (stable cut)= { a: 2, c: 0 }     // elementwise min
```

- Condition 2 (`I` stable): `I.dot = (a,1)`, `S[a] = 2 ≥ 1` ✓ — under the *old*
  design this alone authorised the purge.
- Condition 3 (frontier-complete): `max frontier[c] = 1`, `delivered_A[c] = 0`,
  `0 ≥ 1` is **false** ✗. A knows dot `(c,1)` exists and has not delivered it.

→ **Purge refused.** `I` is retained. When `J` finally arrives, `I`'s predecessor
is intact and `J` lands at every replica. No loss.

Once `J` is globally delivered, `delivered_A` catches up to `{a:2, c:1}`, condition
3 passes, but now `Insert(J, after = I)` is in A's local log, so condition 4 refuses
the purge **for the correct reason** (a live successor exists). `I` is retained as
long as `J` references it; the chain stays connected forever.

This trace is encoded and **passing** in
`RgaCompactStableCutBarrierTest` (model level — the cut is a VV map, no replicator):
`refusesGc_whileConcurrentSuccessor_J_known_but_undelivered`,
`onceJ_globallyDelivered_localSuccessorCheckRefuses_forTheRightReason`,
`collectsTombstone_whenStable_caughtUp_andNoSuccessor`.

## 6. Metadata each op needs — the `RgaId` decision

`compact` must test an individual element against the cut: `S.dominates(I.dot)`.
The cut is keyed by `(author, author-seq)` (a `Dot`). `RgaId` today carries
`(lamport, replicaId)` — a Lamport timestamp, **not** a dense per-author sequence.

The first addendum proposed two options:
- **(a)** add a dense per-author `seq` to `RgaId` → `RgaId(lamport, replicaId, seq)`;
- **(b)** key the cut by `replicaId → highest globally-delivered RGA *lamport*`,
  exploiting that one author's lamports are locally monotonic in mint order.

**Recommendation: (a), unambiguously.** Option (b) is unsound under two conditions
the design otherwise allows:

- **Lamport gaps.** A single author's lamports are monotonic but **not dense** —
  the Lamport clock jumps to `max(seen) + 1` on every op, so an author's lamports
  reflect *other* authors' activity. "Highest globally-delivered lamport `L` for
  author `r`" cannot certify *contiguous* delivery of `r`'s ops, which is exactly
  what a *contiguous* delivered VV (and hence a genuine stable cut) requires. A
  gap between two of `r`'s lamports cannot be distinguished from an undelivered op.
- **`Compact` deltas** carry no single author lamport, perturbing any lamport-based
  per-author monotonicity assumption on the wire.

A dense per-author `seq` is the `Dot` the rest of `:kuilt-crdt` already uses
(`ORSet`/`MVRegister`/`ORMap` are all dot-keyed). Adding it to `RgaId` makes the
RGA causal model uniform with the rest of the module and lets `compact` reuse
`DotContext`'s contiguous-VV machinery directly. **Cost:** a wire-format change to
every serialized RGA op (the published `0.3.x` RGA format). **This is cheap now**:
RGA GC is experimental and *unconsumed* (`RgaGcCoordinator` is marked experimental,
published-on-merge, no downstream reads it), and pre-1.0 the format is explicitly
unstable. Breaking it before a consumer locks in is a one-time, zero-migration cost;
breaking it after is not. Do it in this epic.

`RgaId` keeps `lamport` for the existing total-order tiebreak in `computeSequence`
(concurrent inserts after the same predecessor still break ties by `lamport` then
`replicaId`). The new `seq` is *only* for causal-stability accounting. They are
orthogonal: `lamport` orders, `seq` tracks delivery.

## 7. What `SeamReplicator` must track and gossip

| Surface | What |
|---|---|
| **State — `deliveredLocal: Map<ReplicaId, Long>`** | `author → highest contiguous applied author-seq` on this replica, expressed in **RGA-author `seq`** (read from each delivered op's `RgaId.seq`), plus this replica's own applied count for its own author. Contiguous by construction (advances only when the causal gap is filled — the same property `expectedReceiveSeq` already enforces per sender). |
| **State — `frontiers: Map<PeerId, Map<ReplicaId, Long>>`** | Each peer's last-gossiped `delivered` VV (the matrix clock). Includes self. |
| **Wire — one new message** | `ReplicatorMessage.Delivered(sender, vector)` — the whole-room delivered VV as `Map<String, Long>` (`author.value → seq`). Gossiped on local apply and on the anti-entropy tick. **Not** piggybacked on `Ack`: `Ack` is per-`(sender, seq)` on the inbound-delta path; the delivered VV is a whole-room snapshot on its own cadence, so it converges even in asymmetric-traffic rooms and leaves `Ack`'s delta-buffer-GC semantics untouched. |
| **Derived — `stableCut: StateFlow<VersionVector>`** | `S[author] = min over (knownPeers ∪ self) of frontiers[peer][author]`, absent entries default `0`. Recomputed on: local apply, inbound `Delivered`, peer join, **and peer eviction** (eviction can legitimately *raise* the cut). Advances monotonically per author (elementwise max with previous); never decreases. |
| **Derived — `frontierMax: StateFlow<VersionVector>`** | `F[author] = max over peers of frontiers[peer][author]` — the "known-to-exist" frontier that condition 3 checks `deliveredLocal` against. |

`VersionVector` is a thin `Map<ReplicaId, Long>` wrapper with `dominates(other)`,
`floorWith(other)` (elementwise min) and `ceilWith(other)` (elementwise max).
`Dot`/`DotContext` already model `(replica, seq)` and the contiguous VV; reuse the
concept (and, for `deliveredLocal`, the contiguous-compaction logic), not
necessarily the `DotContext` type itself (its removal `cloud` is unneeded here).

**This is distributed GC** — the thing `Rga.kt` originally declared out of scope —
and the matrix-clock variant is *more* than the single-vector gossip the first
addendum proposed: every peer's full VV is held, not just the running min. The
added cost is bounded: one gossiped VV per peer per cadence (O(authors) per
message, O(peers·authors) memory). For chat-sized rooms (authors ≈ participants)
this is small. **The only failure mode is the existing eviction-pin:** a silently
departed peer holds its last frontier, pinning the cut until the eviction TTL
releases it — identical to today's `universalAckFlow` pin, no new liveness hazard.

## 8. Coordinator change

`RgaGcCoordinator`:

- Consumes `stableCut: StateFlow<VersionVector>` and `frontierMax: StateFlow<VersionVector>`
  **instead of** `universalAck: StateFlow<Long>` + `localSeq: StateFlow<Long>`.
- Deletes the entire `seqToLamport` bridge (`observeLocalSeq`, `lamportWatermarkFor`)
  — the cut is already in author-`seq` terms; no seq→lamport guessing.
- `compactUntilStable` passes both VVs to a new
  `Rga.compact(stableCut, frontierMax, delivered)` overload (signature in §9).

## 9. `Rga` changes

**Signature** — the scalar overload is **removed** (it is unsound; no caller keeps it):

```kotlin
public fun compact(
    stableCut: VersionVector,    // S — causal-stability cut
    frontierMax: VersionVector,  // F — known-to-exist frontier
    delivered: VersionVector,    // this replica's contiguous delivered VV
): Pair<Rga<V>, RgaOp.Compact>?
```

Eligibility for a tombstoned `id` (dot `(r, sᵢ)`):

```
frontierComplete = forall author x: delivered[x] >= frontierMax[x]   // condition 3
stable           = sᵢ <= stableCut[r]                                // condition 2
noSuccessor      = id !in { op.after : op in localInserts }          // condition 4
gc(id)           = frontierComplete && stable && noSuccessor
```

`frontierComplete` is a whole-`compact`-call gate (all-or-nothing for this pass);
`stable` + `noSuccessor` are per-tombstone. If `frontierComplete` is false, the
call returns `null` — no GC this round.

### Secondary bug — `apply` re-inflation (#272 probe `reintroductionAfterGc_viaRawApply_probe`)

`Rga.apply(Insert(I))` unions the op in **without consulting `compactedIds`**, so a
late raw `Insert(I)` for an already-compacted `I` resurrects it. Masked today
because `SeamReplicator` routes deltas through `piece` (which purges), but a latent
sharp edge for any caller using bare `apply`.

**Fix:** `apply` consults `compactedIds` and drops any `Insert`/`Remove` whose id is
already compacted — the same guard `piece` applies on its union. One-line addition
to the `Insert`/`Remove` arms:

```kotlin
is RgaOp.Insert -> if (op.id in compactedIds) this else Rga(ops + op, maxOf(lamport, op.id.lamport))
is RgaOp.Remove -> if (op.id in compactedIds) this else Rga(ops + op, lamport)
```

This makes `apply` and `piece` agree: once compacted, always compacted. Pinned by
promoting the probe to an assertion that `I` stays purged.

### Secondary bug — `Compact`-delta accounting

`compact` returns `Rga(newOps, lamport)` — the lamport is unchanged, and the
`Compact` op carries no author `seq`. Under the new dense-`seq` model this is fine:
`Compact` is **not** a causal op (it references ids, mints no new element), so it
needs no `Dot` and does not advance any author's `delivered` seq. The replicator
must therefore **exclude `Compact` from `deliveredLocal` accounting** — it reads
`seq` only from `Insert`/`Remove` ops, never from `Compact`. State this explicitly
in §7's `deliveredLocal` derivation so option-(b)'s lamport-perturbation concern
(the original §"single biggest risk") simply does not arise under option (a).

## 10. Revised sub-issue decomposition

The old sub-issues **#267–270 encode the falsified per-author-floor predicate** and
must be re-specified. The change in each:

| Old | Was (per-author floor — **wrong**) | Now (stable-cut — corrected) |
|---|---|---|
| **#267** | `VersionVector` + `Rga.compact(floor)`; GC iff `id.lamport ≤ floor[id.replicaId]` + local successor. Keying via option (b) lamports. | **Add dense per-author `seq` to `RgaId` (option a)** + `VersionVector` (`dominates`/`floorWith`/`ceilWith`) + `Rga.compact(stableCut, frontierMax, delivered)` with the **3-condition** predicate (§9). Includes the `apply` re-inflation fix. Extends `RgaCompactConcurrentInsertSoundnessTest` / promotes `RgaCompactStableCutBarrierTest` to green. Removes scalar `compact(Long)`. **Wire-format change to RGA ops lands here.** |
| **#268** | Gossip a single delivered vector; replicator tracks `deliveredLocal` + `deliveredByPeer` (the running min). | Gossip **full** delivered VVs (matrix clock): `ReplicatorMessage.Delivered`; replicator tracks `deliveredLocal` (RGA-author `seq`, contiguous, **excludes `Compact`**) + `frontiers[peer]`. 3-peer determinism test. |
| **#269** | `deliveredVectorFloor: StateFlow<VersionVector>` = elementwise min. | **Two** flows: `stableCut` (min) **and** `frontierMax` (max). Both recompute on apply / inbound `Delivered` / join / eviction; `stableCut` monotonic. Re-document `universalAckFlow` as delta-buffer-GC only. |
| **#270** | Coordinator consumes `deliveredVectorFloor`, calls `compact(floor)`. | Coordinator consumes **both** `stableCut` + `frontierMax`, calls the 3-arg `compact`. Drops `seqToLamport`/`nextSeqFlow` consumption. Integration test: converges, bounded op-log, **no lost inserts**. |

Order unchanged: **#267 → (#268, #269 pipeline) → #270.** #267 is independently
mergeable and lands the fix at the `Rga` level immediately; #268–270 wire it through
the live path. The end-to-end coordinator hazard test stays gated on the
controllable-delivery fake seam (#263) — `InMemoryLoom`'s atomic fan-out cannot
express the interleaving. The unit-level `RgaCompactStableCutBarrierTest` pins the
soundness meanwhile.

> #267–270 are **not** refiled here — their bodies need updating to the corrected
> predicate above. (Owner reconciles.)

## 11. Migration

| Surface | Disposition |
|---|---|
| `SeamReplicator.universalAckFlow` (#252) | **Keep** — still correctly gates *delta-buffer* GC (`gcPendingDeltas`), which is per-author by design and sound. **No longer** the RGA GC watermark. Re-document: "delta-buffer GC only; NOT a causal-stability barrier — use `stableCut`/`frontierMax`." |
| `SeamReplicator.nextSeqFlow` (#253) | **Remove** — sole consumer was the coordinator's `seqToLamport` bridge, now deleted. |
| `Rga.compact(watermark: Long)` | **Remove** — unsound; replaced by the 3-arg VV form. |
| `RgaId` wire format | **Break** — add dense `seq` (option a). Cheap pre-1.0 / pre-consumption. |
| `Rga.apply` | **Fix** — consult `compactedIds` (re-inflation guard). |
| `RgaOp.Compact`, `purge`, `piece` GC | **Keep** — purge mechanics and idempotent merge are correct; only the *gating decision* was wrong. `Compact` excluded from `deliveredLocal`. |
| `WindowPolicy`, serializers, coordinator structure | **Keep** — windowing rides the same `Compact` op. |
| `RgaGcCoordinator3PeerIntegrationTest` | Keep; cannot exercise the hazard (atomic fan-out). Real coverage via #263. |

`RgaGcCoordinator` stays **experimental / unconsumed** until this lands, so the
breaking `RgaId` and `compact` changes cost no downstream migration.

## 12. Honest assessment

This design is a genuine **distributed-GC** protocol (matrix clock + stable cut),
which is heavier than the first addendum's single-vector gossip and heavier than a
scalar watermark. It is, however, the *established* construction for the problem,
bounded in cost (O(peers·authors)), and **sound by construction** against the
author-independence counterexample that broke both prior attempts — the predicate
no longer keys completeness on the tombstone's author at all. It does **not** require
multi-round agreement or consensus: gossip + elementwise min/max, monotonic, with
the existing eviction TTL as the sole liveness backstop. The cost is one extra wire
message, two `StateFlow`s, a per-op `seq`, and an `apply` guard — within the shape
the epic already anticipated, not a balloon into a new subsystem.
