# ADR-003 Addendum — Causal-stability GC barrier for RGA (v3, eviction-safe)

**Status:** Accepted (corrects ADR-003 Decisions 2 & 3; supersedes the
per-author-floor draft **and** the v2 stable-cut draft for membership change)
**Date:** 2026-06-09
**Resolves:** #262 (corrects #247 / #258 / #266 / #274)

> This is the **third** addendum design. The lineage:
> - **v1** (per-author delivered-vector floor, #266) — falsified by #272: it
>   carried the *author-independence* bug of the original `universalAckFlow`
>   watermark.
> - **v2** (matrix-clock stable cut + frontier-complete, #274) — sound for
>   **fixed membership** (#272 refused by construction) but falsified by #275:
>   **unsound under eviction.** Eviction drops a peer's row from the matrix, so
>   the `F = max over peers` frontier can *fall* below a dot that witnesses a
>   concurrent successor, and condition 3 passes blind.
> - **v3** (this document) — retains evicted peers' frontier knowledge as a
>   **monotonic floor on F**, closing the eviction hole. Re-verified by
>   construction against **both** prior probes (#272 author-independence, #275
>   eviction) plus two new membership-change probes. The §7 liveness tradeoff is
>   stated honestly and is a **decision-needed for Iain**, not papered over.

The barrier itself (the matrix-clock stable cut, the 3-condition predicate, the
`RgaId` dense-`seq` decision, the `apply` re-inflation fix) is unchanged from v2
and is restated here for completeness; the **new** material is §4 (retained
frontier), §6 (eviction-safety argument), and §7 (liveness tradeoff). Read those
three first if you reviewed v2.

## 1. The bug, stated once, precisely

ADR-003 Decision 2 purges a tombstoned `Insert(I, _, after)` when `I` is causally
old and the *local* op-log has no surviving `Insert(_, _, after = I)`. The
dangerous operation is a concurrent `Insert(J, after = I)` minted by a **different**
author `c`. If the compactor purges `I` while `J` is in flight and invisible to it,
then when `Compact({I})` and `J` both propagate, `J`'s structural predecessor is
gone everywhere, `computeSequence` can no longer reach `J` from `HEAD`, and **a
committed insert is silently and permanently lost on every replica.** Convergence
holds; correctness does not.

**The compactor cannot distinguish "peer `c` has nothing new" from "peer `c` has
minted a `J` I have not yet delivered" from any per-author scalar or vector keyed
on `I`.** That dictated the matrix-clock fix (v2). Eviction then reintroduced the
*same loss* through a different door (v3).

## 2. The construction: causal stability over a stable version vector

An operation `x` is **causally stable** at a replica once `x` has been delivered at
**every** replica. At that point no operation concurrent with `x` can ever arrive
anywhere again. Causal stability is decided with a **matrix clock**:

- Each replica maintains its **delivered version vector**
  `delivered[author] = highest contiguous author-sequence it has applied`
  (contiguous = gap-excluding).
- Each replica **gossips** its `delivered` VV. A replica thus holds a *matrix*:
  `frontiers[peer] = ` that peer's last-gossiped `delivered` VV (including its own).
- The **stable cut** is the elementwise minimum over the matrix:
  `S[author] = min over peers p of frontiers[p][author]`.

`S` is the largest VV such that **every** replica has applied every op at-or-below
it. The gossiped **frontier** of peer `c` tells the compactor that a dot
`(c, seq(J))` *exists*, even before the compactor has delivered `J`'s payload —
precisely the knowledge a per-author floor throws away.

## 3. The barrier (v2 predicate — sound for fixed membership)

Purge a tombstoned `I` (dot `(r, sᵢ)`) iff **all** hold:

1. **Tombstoned** — `Remove(I)` is in the log.
2. **Causally stable** — `sᵢ ≤ S[r]`, `S` = stable cut (min over peers).
3. **Frontier-complete** — for every author `x`, `delivered_self[x] ≥ F[x]` where
   `F[x] = max over peers p of frontiers[p][x]` — the compactor knows of **no**
   dot that exists-yet-is-undelivered.
4. **No surviving local successor** — no `Insert(_, _, after = I)` in the local
   op-log.

Condition 3 makes condition 4 *complete*: once the compactor has delivered every
op below every reported frontier, any `Insert(J, after = I)` that exists anywhere
has been delivered locally — so 4 sees it and refuses. **For fixed membership this
is sound by construction** (the #272 trace, §6.1).

## 4. The eviction hole, and the fix (NEW in v3)

### 4.1 Why v2 is unsound under eviction

`F[x] = max over peers p of frontiers[p][x]` is computed over the **currently-live**
matrix rows. Under fixed membership `F` is monotonic non-decreasing: peers gossip
monotonically-increasing VVs, and `max` of monotone inputs is monotone.

**Eviction is the only operation that removes a row.** When peer `c` is evicted
(silent past the TTL, `evictStalePeers` drops `frontiers[c]`), `F[c]` is recomputed
over the *surviving* rows. If `c`'s row was the **sole witness** of `(c, seq(J))` —
i.e. no live peer had yet gossiped a frontier reaching `seq(J)` — then `F[c]` falls
from `seq(J)` to `0`. Condition 3's `delivered_self[c] ≥ F[c]` becomes
`0 ≥ 0` ✓ — it passes **blind to J**. Meanwhile `S = min over peers` can only
*rise* when a row is removed, so `I` stays stable. `I` is purged; when `J` later
resurfaces (reconnect FullState, or a peer that held `J` forwards it), it orphans.
This is #275, an ordinary partition — no contrived timing. The design originally
misfiled it as a liveness "eviction-pin"; **it is a safety hole.**

### 4.2 The fix — a monotonic retained frontier

Eviction must release the **pin on the stable cut and the delta buffer** (a dead
peer must not stall `S` or pin buffered deltas forever) but must **not erase
known-to-exist dots from the frontier `F`.** We decouple the two:

- **`F_live[x] = max over live peers p of frontiers[p][x]`** — as today.
- **`retainedFrontier: Map<ReplicaId, Long>`** — a monotonic floor accumulating the
  last-gossiped delivered VV of every **evicted** peer, merged by elementwise max.
- Condition 3 checks against **`F[x] = max(F_live[x], retainedFrontier[x])`.**

`S` is unchanged — still `min over *live* peers` — so eviction still lets `S` rise
and still releases buffered deltas. Only the *known-to-exist* frontier gains an
eviction-proof floor.

### 4.3 Retain rule — what enters `retainedFrontier`

On evicting peer `c` with last-gossiped frontier `frontiers[c]`:

```
retainedFrontier := retainedFrontier.ceilWith(frontiers[c])     // elementwise max
```

This captures *every* dot `c` ever told us about, including `(c, seq(J))` and any
third-author dots `c` had relayed. The merge is elementwise-max, so retaining is
idempotent and order-independent across multiple evictions.

### 4.4 Release rule — how a dot ever LEAVES `retainedFrontier`

This is where a v4 bug would hide, so it is stated exactly. A retained entry
`retainedFrontier[x] = s` is the obligation "I must not believe author `x` is
caught-up below `s` until I can prove it." That obligation is **discharged**, and
the entry may be lowered/cleared, in exactly two cases — both of which preserve
the invariant that condition 3 is *never* checked against a frontier lower than
the true set of known-to-exist dots:

- **(R1) Self delivered it.** When `delivered_self[x] ≥ s`, the compactor has itself
  delivered every dot the retained entry witnessed. Condition 3's
  `delivered_self[x] ≥ F[x]` is then satisfied through `delivered_self[x]` directly,
  independent of the retained floor — so `retainedFrontier[x]` may be lowered to
  `delivered_self[x]` (or cleared if it now reaches `s`) with no loss of safety.
- **(R2) A live peer dominates it.** When `F_live[x] ≥ s`, some live peer's frontier
  already carries the same (or higher) knowledge of author `x`. The retained entry
  is **redundant** with a live row and may be dropped — `F = max(F_live, retained)`
  is unchanged by removing a retained entry a live row already dominates.

Formally, after any matrix update we may normalise: keep `retainedFrontier[x]`
**only if** `retainedFrontier[x] > max(delivered_self[x], F_live[x])`; otherwise
drop it. Equivalently, `retainedFrontier` need only carry, per author, the
**excess** of known-to-exist dots over what self has delivered and over what any
live peer witnesses. Everything `(R1)`/`(R2)` covers is provably redundant; nothing
else is removed. **A retained entry that is neither self-delivered nor live-witnessed
stays — that is the pin, and §7 owns its cost.**

### 4.5 Rejoin interaction (reconnect → FullState)

When an evicted peer `c` reconnects, `onPeersChanged` re-adds `c` to `knownPeers`
and `sendFullStateTo(c)` ships current state; `c` replies with its own gossip
(`Delivered`) carrying a *fresh* delivered VV `frontiers_new[c]`. Because `c`'s VV
is **monotonic** across its own lifetime (delivered VVs only grow per author), the
fresh VV **dominates** whatever was retained for `c` — `frontiers_new[c][x] ≥`
the value retained at eviction for every `x` `c` authored or had delivered. So:

- The fresh live row re-enters `F_live` and, by **(R2)**, subsumes `c`'s retained
  contribution: every dot retained from `c`'s pre-eviction frontier is now carried
  by a live row, so those retained entries are dropped as redundant.
- Crucially, `J = (c, seq(J))` was in `c`'s delivered VV at eviction (it minted it),
  so it is in `frontiers_new[c]` too. Rejoin therefore **does not** lose the `J`
  obligation; it merely migrates it from `retainedFrontier` back to a live row. If
  self has by then delivered `J`, condition 4 (local successor) now guards `I`
  directly. Either way `I` is never purged while `J` exists unconfirmed.

The one subtlety: a peer that is **brand new** (never saw `J`) and whose VV has
`[c] = 0` does **not** clear a retained entry for author `c` — (R2) only clears
author `x` when *that author's* live frontier dominates. A new joiner with no
knowledge of `c` cannot discharge `c`'s retained obligation. Correct: only
self-delivery (R1) or a peer that *actually witnesses* the dot (R2) discharges it.

## 5. Metadata each op needs — the `RgaId` decision (unchanged from v2)

`compact` tests an individual element against the cut: `S.dominates(I.dot)`. The
cut is keyed by `(author, author-seq)` — a `Dot`. `RgaId` today carries
`(lamport, replicaId)` — a Lamport timestamp, **not** a dense per-author sequence.
A single author's lamports are monotonic but **not dense** (the clock jumps to
`max(seen)+1`), so they cannot certify *contiguous* delivery. **Add a dense
per-author `seq` to `RgaId`** → `RgaId(lamport, replicaId, seq)`. `lamport` keeps
its total-order tiebreak role in `computeSequence`; `seq` is *only* for
causal-stability accounting. They are orthogonal: `lamport` orders, `seq` tracks
delivery. This is a wire-format change to every serialized RGA op — **cheap now**
(RGA GC is experimental and unconsumed, pre-1.0 format explicitly unstable), and
not cheap after a consumer locks in. Do it in this epic.

## 6. By-construction safety — both probes

### 6.1 #272 author-independence (`RgaCompactFloorBarrierProbeTest`) — REFUSED

- A: `Insert(I)` `(a,1)`, `Remove(I)`. C: `Insert(J, after=I)` `(c,1)`, concurrent,
  **not** delivered to A. A is compactor, **all peers live**.
- `delivered_A = {a:1, c:0}`; `frontier_C = {a:1, c:1}`; `frontiers = {A, C}`.
- `S = {a:1, c:0}`; `F_live = {a:1, c:1}`; `retainedFrontier = {}`;
  `F = max(F_live, retained) = {a:1, c:1}`.
- Condition 2: `I.dot=(a,1)`, `S[a]=1 ≥ 1` ✓.
- Condition 3: `delivered_A[c]=0 ≥ F[c]=1`? **No** ✗ → **purge refused.** `I`
  retained; `J` lands when delivered. No loss. (Identical to v2 — the retained
  frontier is empty here, so v3 reduces to v2 under fixed membership.)

### 6.2 #275 eviction (`RgaCompactStableCutEvictionProbeTest`) — REFUSED (the fix)

Same setup, then **C is evicted** before A delivers `J`, and C's frontier was the
sole witness of `(c, seq(J))`.

- **v2:** evict drops `frontiers[C]`; `F_live[c]` falls `1 → 0`; condition 3
  `0 ≥ 0` ✓ → **purged → J orphaned.** Unsound.
- **v3:** evicting C runs the retain rule (§4.3):
  `retainedFrontier := ceilWith({a:1, c:1}) = {a:1, c:1}`. Now
  `F_live = {a:1}` (only A live), but `F = max(F_live, retained) = {a:1, c:1}`.
  Condition 3: `delivered_A[c]=0 ≥ F[c]=1`? **No** ✗ → **purge refused.** `I`
  retained; when `J` resurfaces (reconnect/anti-entropy) it lands. **No loss.**

The release rule does **not** fire here: self has not delivered `J` (R1 false), and
no live peer witnesses `(c,1)` (R2 false). The obligation correctly persists.

### 6.3 New probe — eviction-then-rejoin — SAFE, and unpins on delivery

C evicted (retained `{c:1}`), then C reconnects with fresh VV `{a:1, c:1}`:
- (R2) fires: live row `C` now witnesses `(c,1)`, retained `c`-entry dropped as
  redundant; `F = {a:1, c:1}` unchanged. Still refused while A hasn't delivered `J`.
- A then delivers `J` (via C's FullState / forward): `delivered_A = {a:1, c:1}`,
  and `Insert(J, after=I)` now in A's local log → condition 4 refuses for the
  **right** reason. `I` retained as long as `J` references it. No loss, ever.

### 6.4 New probe — eviction where J reached a LIVE peer — SAFE

C mints `J`, **B delivers `J`** (B's frontier `{…, c:1}`), then C is evicted.
- `F_live[c]` from B = 1; even with `retainedFrontier` empty (suppose C's row never
  carried it to A), `F[c]=1`. Condition 3 `delivered_A[c]=0 ≥ 1`? No → refused. When
  A delivers `J` (B forwards), condition 4 guards. **No loss.** This is the case
  where the retained frontier is *not even needed* — a live witness suffices — but
  it must still be safe, and it is.

## 7. Honest assessment of the liveness tradeoff (DECISION-NEEDED)

The retained frontier converts the v2 **safety hole** into a **GC-liveness pin**.
Two cases must be distinguished, and they have genuinely different verdicts:

### (a) `J` reached some surviving peer, but not self
The retained (or live) frontier correctly blocks GC of `I` until self delivers `J`.
This is **bounded**: anti-entropy / reconnect FullState will deliver `J` to self in
finite time under the standard eventual-delivery assumption the whole replicator
already relies on. Once self has `J`, (R1) discharges the retained entry and
condition 4 takes over. **Verdict: bounded, correct, no decision needed.** This is
the case the fix exists for.

### (b) `J` left with the peer — NO surviving peer ever received it
Peer `c` minted `J` fire-and-forget and was evicted before *any* other replica
delivered it. Then `J` is **genuinely lost-to-the-system** — it departed with `c`
and (barring `c` ever returning with it) no replica will ever deliver it. The
retained frontier still carries `(c, seq(J))`, so it **pins GC of `I` forever** —
an unbounded op-log stall for that one element (and any tombstone `I` it guards).

This is the honest tradeoff and it is a **decision for Iain**:

- The pin is **not harmful to safety** — keeping `I` retained is conservative; the
  list stays correct, just un-GC'd for that element.
- The pin is **unbounded** only when `J` is provably lost-to-the-system — but
  **"provably lost" is not locally decidable.** From A's vantage, "`c` minted `J`,
  was evicted, and no one got `J`" is **indistinguishable** from "`c` minted `J`,
  someone got it, and self just hasn't been told yet (case a)." There is no local
  predicate that separates (a) from (b). So the retained entry cannot be safely
  auto-expired without reopening the #275 safety hole.

**Bounding (b) requires a NEW mechanism, and every candidate has a real cost:**
1. **A liveness/membership assumption** — e.g. "an evicted peer's un-acked mints are
   considered lost after a *second*, longer TTL, and the retained entry expires
   then." This re-admits the safety hole during the window where (a) and (b) are
   still ambiguous; it is only sound if the room can *guarantee* no un-evicted peer
   still holds `J` — which needs membership-level knowledge the replicator lacks.
2. **An explicit departure protocol** — an evicted peer that *gracefully* leaves
   flushes its un-acked mints (or a tombstone-of-intent) to a surviving peer before
   going, so (b) cannot arise for graceful departures. Ungraceful crashes (the case
   that matters) are not covered.
3. **Raft-style committed membership** — bound (b) by making "the room agrees `c`
   and its in-flight ops are gone" a *committed* fact. This is heavyweight and pulls
   consensus into a gossip-GC path the design deliberately avoided.

**Verdict:** the v3 barrier is **safe under both probes** and the liveness pin is
**bounded in the only case that matters in practice (a)**. Case (b) — a peer minting
an insert that *no one* ever receives and then crashing — is a real but narrow
unbounded-pin window, and **it cannot be bounded locally without a new mechanism
that itself trades away safety or pulls in membership/consensus.** I am **not**
inventing one. This is the decision to make:

> **Ship v3 with the (b) pin accepted** (op-log may retain one element's tombstone
> indefinitely if its concurrent successor was lost-with-a-crashed-peer — a rare,
> non-harmful stall), **or** gate GC behind a membership-committed departure signal
> (option 3) if the unbounded stall is unacceptable. v3 does not decide this; it
> makes the tradeoff explicit and safe-by-default.

## 8. `Rga` and replicator changes (unchanged from v2, plus retained frontier)

`Rga.compact` signature (scalar overload removed — unsound):

```kotlin
public fun compact(
    stableCut: VersionVector,    // S — min over LIVE peers
    frontierMax: VersionVector,  // F = max(F_live, retainedFrontier)
    delivered: VersionVector,    // this replica's contiguous delivered VV
): Pair<Rga<V>, RgaOp.Compact>?
```

Eligibility for tombstoned `id` (dot `(r, sᵢ)`):
```
frontierComplete = forall x: delivered[x] >= frontierMax[x]   // condition 3, vs F (incl. retained)
stable           = sᵢ <= stableCut[r]                          // condition 2
noSuccessor      = id !in { op.after : op in localInserts }    // condition 4
gc(id)           = frontierComplete && stable && noSuccessor
```

`SeamReplicator` additions over v2:
- `retainedFrontier: Map<ReplicaId, Long>` — updated in `evictStalePeers` (retain
  rule §4.3) and normalised on every matrix update (release rule §4.4).
- `frontierMax` StateFlow now emits `max(F_live, retainedFrontier)`.
- `stableCut` StateFlow unchanged (`min over live peers`).

### `apply` re-inflation fix (#272 probe `reintroductionAfterGc_viaRawApply`)
`Rga.apply(Insert(I))` unions without consulting `compactedIds`, resurrecting a
compacted `I` on a late raw apply. Fix — both arms drop already-compacted ids:
```kotlin
is RgaOp.Insert -> if (op.id in compactedIds) this else Rga(ops + op, maxOf(lamport, op.id.lamport))
is RgaOp.Remove -> if (op.id in compactedIds) this else Rga(ops + op, lamport)
```
Makes `apply` and `piece` agree: once compacted, always compacted.

## 9. Migration

| Surface | Disposition |
|---|---|
| `SeamReplicator.universalAckFlow` | **Keep** — delta-buffer GC only; **not** the RGA GC watermark. |
| `SeamReplicator.nextSeqFlow` | **Remove** — sole consumer (`seqToLamport`) deleted. |
| `Rga.compact(watermark: Long)` | **Remove** — unsound; replaced by 3-arg VV form. |
| `RgaId` wire format | **Break** — add dense `seq`. Cheap pre-1.0 / pre-consumption. |
| `Rga.apply` | **Fix** — consult `compactedIds`. |
| `retainedFrontier` | **New** — monotonic floor on `F` across evictions (§4). |
| `RgaGcCoordinator` | stays **experimental / unconsumed** until this lands. |

## 10. Honest summary

v3 is the v2 matrix-clock stable cut **plus an eviction-proof retained frontier**.
It is **sound by construction** against both known probes (§6): #272
author-independence (refused for fixed membership) and #275 eviction (refused via
the retained floor). The cost over v2 is one `Map<ReplicaId, Long>` and a
retain/release rule with a precisely-stated discharge condition (§4.4). The
**residual** is a GC-liveness pin (§7): **bounded** when the lost successor reached
*any* surviving peer (case a — the case that matters), **unbounded and not locally
boundable** when the successor was minted-then-lost by a crashing peer (case b).
Case (b) cannot be bounded without a new membership/consensus mechanism that
itself trades away safety — so v3 **does not invent one**; it accepts the narrow
pin by default and surfaces the alternative (committed departure) as Iain's call.
