# Relocating stranded budget — a representation change for the entitlement ledger

> **Status: design, under review. Not implemented.** This proposes the
> representation change that lets the fair-share ledger *move* already-spent budget
> from a retired branch of the tree onto the branch that replaced it — the one thing
> the current representation cannot do, and the reason issue #1665's through-service
> case is carved out of PR #1669. Companion to [`heddle-design.md`](heddle-design.md) §4/§5/§9/§10
> and [`heddle-ledger-design.md`](heddle-ledger-design.md).
>
> **Revision 2 (2026-07-25).** The Fable adversarial review (recorded in §11) holed the
> first revision's §6 fencing: the causal-stability wait bounds *past* writes, not a peer
> that has not yet applied the barrier, and the reconciliation witness's absolute
> `issued(liveEdge)` target contends with the live edge's ordinary writer under max-join.
> §4, §6 and §7 are redesigned accordingly; §3/§5's representation claims survived the
> review and are unchanged in substance.

## In one paragraph, for anyone

The scheduler hands out a fixed pot of "fair-share" credit down a tree of groups.
Every peer keeps its own running tally, and the tallies always agree without a
central referee because each number only ever counts *up*. That "only ever up" rule
is what keeps everyone in sync — but it has a cost: once you've written down that a
branch of the tree spent some credit, you can never *un-write* it. Normally that is
fine, because branches are permanent. The trouble in #1665 is that a rare timing
race lets a branch be **retired while credit is still riding on it**, and then a
perfectly legal reorganisation moves the group onto a fresh branch. Now the spent
credit is stranded on a dead branch, the group's balance reads as *negative
forever*, and the health monitor screams "overspend!" even though not a single unit
was actually overspent. To fix it we must do the one forbidden thing: **take the
spent tally off the dead branch and put it on the live one.** This document shows how
to do that while keeping the "only ever up" guarantee intact — by never decreasing a
number, only adding a second number that cancels it — and proves the books still
balance afterward.

The rest of this document is the technical design.

---

## 1. What is actually broken (the two walls)

The full problem statement is issue #1665 and PR #1669's description. Restated in the
terms this design needs:

A gossip-lagged peer proposes `retire(s)` while its view still shows
`outstanding(s) == 0` (the documented advisory-retire race at
`GovernedHeddleNode.retire`). The committed retire gates only on the log-order
lifecycle being `CLOSING`, so `s` becomes `RETIRED` with entitlement still live on it.
A legal reparent then activates a fresh inbound edge `t` for the same child (allowed —
`s` is `RETIRED`, not live). On the converged state the child's budget is stranded on
`s`, its `holdings` derive **permanently negative**, and `validate()` reports
`PersistentNegativeHoldings` / `PerEdgeSafety` / `ClosureViolation` that never heal —
with **zero actual overspend**.

PR #1669 clears this conservingly **only when no service was spent through `s`**
(`spent(s) == 0`). Two walls stop the full fix:

- **Wall B — the representation wall.** Making the child whole requires re-homing its
  **full net inflow** `issued(s) − returned(s)` onto `t`. When service was spent
  through `s`, faithfully doing that also requires *decreasing* the grow-only
  `rollupSpent(s)` (and, if `s` was a leaf's final edge, `leafSpent(s)`) so per-edge
  safety survives on `s` and the charge is re-attributed to the live lineage.
  **Decreasing a grow-only `GCounter` is impossible** in today's representation. This
  is the wall this document exists to remove.

- **Wall A — the magnitude wall.** The relocation *amount* is read from the proposer's
  gossip-replicated data plane, which `readIndex()` does **not** fence (independent
  transport, §10.13) and which is **not even stable post-retire** — captured-path
  `spendCaptured` completions keep charging `s` after it is `RETIRED` (§4.4/§10.4). A
  lagged proposer commits a wrong magnitude → phantom supply / real overspend. This
  wall is about *when it is safe to compute the amount*, and is orthogonal to the
  representation — but, as §7 shows, **it becomes more dangerous once Wall B is
  removed**, so the two must be addressed together.

---

## 2. The conserving generation-move (what we need the representation to express)

Work the D1 **through-service** example (the exact `spendThroughStrand()` in PR #1669's
`EntitlementLedgerReconcileTest`):

```
mint 10 → p3 at root
e1: root→g   (ACTIVE)      e2: g→h (ACTIVE)
p3 delegates 10 ↓ e1       issued(e1)[p3] = 10
p3 delegates  6 ↓ e2       issued(e2)[p3] = 6
p3 spends 3 through [e1,e2] at h   leafSpent(e2)[p3]=3, rollupSpent(e1)[p3]=3
close(e1); RACED retire(e1) → RETIRED    (outstanding read as 0 by a lagged peer)
reparent: prepare e3 (root→g), activate e3      issued(e3)=0
```

**Converged counters:**

| edge | issued | returned | leafSpent | rollupSpent | lifecycle |
|------|-------:|---------:|----------:|------------:|-----------|
| e1   | 10     | 0        | 0         | 3           | RETIRED   |
| e2   | 6      | 0        | 3         | 0           | ACTIVE    |
| e3   | 0      | 0        | 0         | 0           | ACTIVE    |

**Derived holdings (p3):** `root = 0`, `g = −6`, `h = 3`. Σ holdings `= −3`;
`leafSpentTotal = 3`; **Σ holdings + leafSpent = 0 ≠ minted 10** → conservation broken
by 10 (the full net inflow of `e1` leaked: `root` subtracts `netInflow(e1)=10` as a
child edge, but nobody credits it because `e1` is no longer `g`'s live inbound).

### The target end-state — a faithful *generation move* e1 → e3

We want `e3` to carry `g`'s inbound generation exactly as if the delegation and the
through-spend had happened on `e3`, and `e1` to become inert-and-drained:

| edge | eff issued | eff returned | eff leafSpent | eff rollupSpent | per-edge safety `L+R+ret ≤ iss` |
|------|-----------:|-------------:|--------------:|----------------:|--------------------------------|
| e1   | 10         | 10           | 0             | **0**           | `0+0+10 ≤ 10` ✓ (drained)      |
| e3   | **10**     | 0            | 0             | **3**           | `0+3+0 ≤ 10` ✓                 |
| e2   | 6          | 0            | 3             | 0               | unchanged ✓                    |

Re-derived holdings: `root = 10 − netInflow(e1)=0 − netInflow(e3)=10 = 0`;
`g = creditIn(e3)=10 − netInflow(e2)=6 = 4`; `h = 6 − leafSpent(e2)=3 = 3`. Σ holdings
`= 7`, `leafSpentTotal = 3`, **Σ + leaf = 10 = minted ✓**. Every conflict clears.

The move decomposes into two parts:

1. **Net-inflow re-home (already grow-only-expressible).** `returned(e1)[p3] += 10`
   (release the full net inflow up `e1`) and `issued(e3)[p3] += 10` (re-delegate it down
   `e3`). Both are ordinary monotone bumps — this is exactly PR #1669's
   release-up-then-redelegate, and it is conserving on its own.

2. **Spent relocation (the impossible part today).** `rollupSpent(e1)[p3]: 3 → 0` and
   `rollupSpent(e3)[p3]: 0 → 3`. The first is a **decrease of a grow-only counter** —
   Wall B. Without it, part 1 alone drives `e1`'s per-edge safety to
   `0 + 3 + 10 = 13 > 10` (a *created* `PerEdgeSafety(e1)`); re-homing only the
   `outstanding = 7` instead silently destroys 3 units. Neither is acceptable.

So the representation must let the **spent** counters (`rollupSpent`, and `leafSpent`
when `s` is a leaf's final edge) be **net-decreased on one edge and net-increased on
another**, while every stored component stays monotone.

---

## 3. Representation choice — (A) relocation counters vs (B) MovableTree op-log

### (A) Relocation counters — a signed adjustment in the grow-only family

Keep every existing counter grow-only and **unchanged**. Add, per edge, a signed
*spent-adjustment* built from two grow-only `GCounter`s (equivalently one `PNCounter`):

```
effRollupSpent(e)[r] = rollupSpent(e)[r] + rollupReloc.in(e)[r] − rollupReloc.out(e)[r]
effLeafSpent(e)[r]   = leafSpent(e)[r]   + leafReloc.in(e)[r]   − leafReloc.out(e)[r]
```

A generation-move of `δ` spent-units for replica `r` from `s` to `t` bumps
`reloc.out(s)[r] += δ` **and** `reloc.in(t)[r] += δ`. Net subtraction on `s` is
achieved by a *second monotone counter*, never a decrement — the PNCounter idiom, which
kuilt already ships. The effective value is **derived, never stored**, exactly like
`holdings` and `outstanding` today (both already fall while every stored slot rises).

The net-inflow half of the move changes shape on **one side only**. On the fenced
retired edge `s`, the drain stays an ordinary base bump (`returned(s)[r] → iss` — legal
because `s` is fenced, §6.3). On the **live** edge `t`, the re-delegation moves to a
third relocation family, `issuedReloc.in(t)[r]`, so `effIssued(t)[r] = issued(t)[r] +
issuedReloc.in(t)[r]`. The first revision rejected this as needless uniformity;
the adversarial review's finding 1 (§11) showed it is a **correctness requirement**:
writing an absolute `issued(t)[r]` target fabricates a value on a slot the live edge's
ordinary `delegate` writer also writes, and per-slot max-join silently erases one of
the two. `holdings` gains exactly one extra map lookup (`effIssued`); `returned` stays
base-only everywhere, because the control plane only ever writes it on fenced edges.
Only the spent reads and `effIssued` become effective; no `issuedReloc.out` exists —
issuance is never net-decreased.

**Why this stays convergent, for free.** `reloc.in`/`reloc.out` are `GCounter`s with
the same exclusive-slot discipline (slot `(e, r)` written only for replica `r`, by the
log-serialized Reconcile apply). Adding them to the product lattice keeps `piece`
componentwise-join, so idempotent/commutative/associative hold **by the same
`LatticeProduct` argument that already covers the ledger** (§`piece` re-proof, §5). No
new conflict-resolution rule, no priority tiebreak, no event ids. This is the decisive
property: **the CRDT gets strictly larger, not structurally different.**

### (B) MovableTree-style op-log replacing the per-edge counters

`MovableTree` (`:kuilt-crdt`) is a replicated tree whose concurrent moves converge by
**replaying an op-log in `(timestamp, replicaId)` order, skipping the lower-priority
op** on a conflict. It is deliberately *shipped-but-unused* by heddle for exactly the
reason it fails here (design §5.4): it **silently resolves conflicting concurrent moves
by priority**, dropping the loser. For a document tree that is correct; for edges
carrying *conserved entitlement*, a silently-dropped move **strands or double-counts a
conserved quantity** — the precise fault §5.2 forbids ("never last-writer-wins on a
lineage").

Beyond the conflict-resolution mismatch, `MovableTree` moves **nodes**, not
**accumulated quantities**. "Relocate 3 units of already-charged spend" is not a
node-move; to express it you would carry quantities in op-log entries with commutative
accumulation — which is *counters again*, but now with an LWW replay bolted on that
**discards the exclusive-slot no-race property** that makes the whole ledger sound
without coordination. The D1 worker's judgment stands: **MovableTree cannot express a
grow-only-counter move, and bending it to try is strictly worse than (A).** B is both
overkill and unsound.

### Recommendation: **(A), decisively.**

| axis | (A) relocation counters | (B) MovableTree op-log |
|------|-------------------------|------------------------|
| convergence risk | none — product of `GCounter`s, laws inherited | LWW replay drops a conserved move; loses exclusive-slot no-race |
| blast radius | 3 new counter families; 5 effective reads; `holdings` gains one lookup | rewrite the entire per-edge accounting substrate |
| re-proof difficulty | extend the existing per-component proofs | re-prove conservation over a foreign convergence model |
| expresses the move? | yes — signed adjustment, net-decrease via a 2nd monotone counter | no — node-move, not quantity-move |

Iain's prior (A is the sweet spot) is **confirmed**, and the proof of it is that A
changes *how much* state there is, not *how it converges*.

---

## 4. The move under representation (A) — precise operation

`reconcileStranded` becomes a **generation-move** instead of a conserving-or-refuse.
The *math* below is unchanged from revision 1; what changed is **who computes it and
from which inputs**: the magnitudes are no longer read from the proposer's gossip view —
they are derived **inside the control-plane apply**, deterministically, from the
per-peer acked finals the §6 fence records in the log. For the child's single live
inbound edge `t = liveEdge` (in log order) and each **fenced** retired inbound edge `s`,
per replica `r`, with `r`'s acked-final slot values on `s`:

```
iss = issued(s)[r]      ret = returned(s)[r]
lsp = effLeafSpent(s)[r]  rsp = effRollupSpent(s)[r]     n = iss − ret   sp = lsp + rsp
```

The patch, with each write annotated by its **writer domain** (§6.3):

```
returned(s)[r]        → iss       # base slot, control-authored — legal ONLY because s is fenced
leafSpent(s)[r]       → base_lsp  # republished at the acked final (observer completeness, §6.4)
rollupSpent(s)[r]     → base_rsp  # ditto
leafReloc.out(s)[r]   += lsp      leafReloc.in(t)[r]   += lsp   # log-pure control counters
rollupReloc.out(s)[r] += rsp      rollupReloc.in(t)[r] += rsp   # log-pure control counters
issuedReloc.in(t)[r]  += n        # log-pure — NEVER base issued(t)[r]  (finding 1)
```

### Correcting revision 1's "absolute values — max-safe" claim

Revision 1 shipped `issued(t)[r]` as an absolute target computed from the proposer's
snapshot and called all targets "max-safe, idempotent". The adversarial review
(§11, finding 1; PoC `concurrentDelegateDownLiveEdgeErasesHalfTheReconcile`) showed
that max-safety is a claim about **duplicate delivery of one write**, not about
**independent concurrent writers to one slot**: `t` is the *live* edge, so replica `r`
writes `issued(t)[r]` concurrently via ordinary `delegate`; two writers on one
max-joined slot means one side is silently erased — with conservation and per-edge
safety both blind to the loss (units teleport to the wrong node; nothing surfaces,
violating §10.11). The corrected discipline is stated as two ownership rules:

1. **Republish-observed is always safe.** A non-owner may re-ship a slot at a value it
   *observed* — necessarily ≤ what the owner already emitted, so max-join absorbs it.
   (This is what data-plane witnesses already do, and why they were never a problem.)
2. **Fabricate-beyond-owner only on a fenced slot.** A value the owner never emitted
   (snapshot + delta) may be written to the owner's slot only when the owner has
   log-recorded that it will never write that slot again (§6). On an unfenced slot the
   addition must go to a counter the control plane exclusively owns — the reloc
   families.

Every line of the patch obeys one of the two rules: `s`'s base slots are fenced, the
reloc slots are control-plane-owned, and no base slot of `t` is ever touched.

**Preconditions (per replica), checked at apply against the acked finals** (log-pure,
so every peer derives the identical accept/refuse — Raft State Machine Safety):

- `n ≥ sp` ⟺ `outstanding(s)[r] ≥ 0`. Holds for a healthy strand. A replica left
  **net-negative** on `s` (the transfer-tangle case, PR #1669 `break4`) has `n < 0` and
  is **still refused** — relocating it faithfully would require moving transfer rows too
  (out of scope; a separate follow-up).
- `t`'s post-move per-edge safety needs no data-plane read: the move adds `sp` to `t`'s
  effective spend side and `n ≥ sp` to its effective issued side, so if `t` satisfied
  per-edge safety before the move it satisfies it after — an increment-safe argument,
  deliberately independent of `t`'s (non-log-pure, concurrently-moving) base counters.

The through-service case that PR #1669 **refuses**, this design **clears**. The
non-through case (`sp = 0`) reduces to release-up-then-redelegate with the
re-delegation expressed via `issuedReloc.in(t)` instead of a base-`issued(t)` write —
behaviourally identical to PR #1669's shipped tests
(`reconcileStrandedClearsConflictsAndRestoresConservation`,
`reconcileClearsRacedRetireStrandAcrossAllPeers`), but the witness slots move; the
shipped path **inherits finding 1 and must migrate** (§7).

---

## 5. Re-proof sketch (enough for a reviewer to check soundness)

Let `E = base + reloc.in − reloc.out` denote any effective spent value; base and both
reloc halves are grow-only `GCounter`s.

### 5.1 Join laws (idempotent / commutative / associative)

`piece` gains three componentwise `GCounter`-map joins (`leafReloc`, `rollupReloc`,
`issuedReloc.in`), each already a semilattice. A finite product of semilattices is a
semilattice, so `piece` remains idempotent/commutative/associative — the **identical
`LatticeProduct` argument** that covers the existing eight components, extended by the
new families. Every stored component still only
grows; `E` is derived and may fall, exactly as `outstanding`/`holdings` already do.
Absolute-valued deltas ⇒ duplicate/reordered delivery absorbed by max. **No new
convergence obligation is introduced.** ∎

### 5.2 Conservation `minted = Σ_g holdings(g) + Σ_e effLeafSpent(e)`

`leafSpentTotal()` is redefined over **effective** leaf-spend. A move sends
`leafReloc.out(s)[r] += lsp` and `leafReloc.in(t)[r] += lsp` (equal), so
`Σ_e effLeafSpent` is **unchanged** by the relocation half. The net-inflow half
telescopes: `root`'s subtraction of `netInflow(s)` drops by `n` while `t`'s `creditIn`
rises by `n` (net 0). `rollupSpent` appears in neither the identity nor `holdings`, so
its relocation is conservation-neutral. Therefore every move preserves the identity;
on D1-through it *restores* it (0 → 10 = minted, §2). ∎

### 5.3 Per-edge safety `0 ≤ effLeaf(e)+effRollup(e)+effReturned(e) ≤ effIssued(e)`

Per replica, post-move:

- **on `s`:** `effLeaf=0, effRollup=0, effReturned=iss, effIssued=iss` →
  `0+0+iss ≤ iss` ✓ (fully returned, spend fully relocated; `outstanding(s)=0`, clearing
  `ClosureViolation`).
- **on `t`:** `effLeaf += lsp, effRollup += rsp, effReturned += 0, effIssued += n` →
  the increment satisfies `lsp+rsp = sp ≤ n` by the precondition. ✓

The lower bound `effLeaf ≥ 0` etc. holds because a move never sends `reloc.out(x)`
beyond `base(x) + reloc.in(x)` for that slot (we only relocate what is present:
`out(s) += lsp` where `lsp = effLeafSpent(s)[r] ≥ 0`). **Proviso — this is a
proposer-view argument, and revision 1 overstated it.** The adversarial review
(§11, finding 3) showed an *observer* that receives the log-published `Reconcile`
before gossiping in the base spend reads `effRollup(s) < 0` and a transient
`PerEdgeSafety` false-fire — log and data plane are independent transports. The bound
above is therefore restored **on every observer** by shipping `s`'s final base spend
slots inside the same published delta (§6.4), so no state in which `reloc.out(s)`
has arrived without its covering base can be observed. With that, the bound holds
at every observer at every interleaving. ∎

### 5.4 Idempotence of the move itself

Three layers, none of which is the (refuted) "absolutes are max-safe against
everything" claim: (i) **re-delivery** of the one published delta is a max no-op
(absolutes, per-slot join); (ii) **re-commit** of the same logical act is absorbed by
the control plane's `requestKey` dedup (`ControlPlane.kt` `applied` table); (iii) a
**distinct second `Reconcile`** for the same child is refused deterministically by the
apply gate — the fence state records `s` as already relocated, and a fenced, drained
edge presents `n = 0, sp = 0`. ∎

**Status of the sketch:** 5.1/5.4 are complete and low-risk (they follow mechanically
from the lattice-product structure). 5.2/5.3 are complete *per replica per move* and
shown on the D1-through witness; the remaining obligation for implementation is the
**multi-replica, multi-retired-edge aggregation** (sum the per-`r`, per-`s` moves and
re-run 5.2/5.3 on the aggregate) — mechanical, and the POC in §10 exercises it on the
worked example.

---

## 6. Wall A — the fence, redesigned (what finality actually requires)

Representation (A) makes the move *expressible*; it does **not** make it *safe to
compute*. Revision 1's fence — quiesce, then wait for Quilter causal stability —
**claimed a barrier it did not deliver**, and the interaction is fatal rather than
cosmetic: the move drains `s` to zero headroom (`returned = issued`), so a single
straggler charge afterwards yields a **permanently unclearable**
`PerEdgeSafety(s)`/`ClosureViolation(s)` — `outstanding(s) < 0` fails both the
clearing iteration (`outstanding > 0` only) and the `n ≥ sp` precondition (§11,
finding 2). Without (A), a straggler is a magnitude nuisance absorbed by `s`'s
headroom; with (A), it is a permanent false violation. **So (A) is unsafe without a
real fence, and the fence below is load-bearing, not a companion.**

### 6.1 Why causal stability cannot be the fence

Quilter's `CutFrontier.stableCut` is an elementwise **min over live peers' delivered
version vectors** (`kuilt-quilter/.../CutFrontier.kt`): it proves every write *that
existed when the frontier was taken* has been delivered everywhere. It says **nothing
about writes that do not exist yet.** Log apply is per-peer asynchronous
(`raft.committedFrom` is an independent flow per peer, `ControlPlane.kt:216`), so at
the moment the stability wait passes, a peer that has **not yet applied `Quiesce(s)`**
can still create a *new* charge against `s` — from an uncompleted local reservation
whose captured path crosses `s` (reservations are **local, unreplicated** state,
`heddle-design.md` §4.4), or from a stale-view `delegate`/`spend` that still believes
`s` live. No frontier over the past bounds that future write.

State the requirement precisely. Every counter slot on `s` is single-writer
(`(edge, counter, r)` written only by `r`). "`s` is final" therefore means: **for every
replica `r` that could ever author a slot on `s` — (i) `r` will never author one again,
and (ii) everything `r` has authored is visible to whoever computes the magnitude.**
Clause (i) is a *promise about the future*; only the promiser can make it. The fence
must therefore collect a per-peer promise — an acknowledgment — not a frontier.

### 6.2 The fence: enroll → quiesce → ack → derive

**Prerequisite — a log-known roster.** "Every peer has promised" is quantified over a
set, and today that set is open: the data-plane roster is seam-derived
(`HeddleNode.kt:456-462` — peers visible on the seam, plus self, plus ledger-known
replicas). The fence needs the quantifier to be a **log-order fact**: an
`Enroll(replica)`/`Depart(replica)` control-command pair (or, for deployments where
data-plane peers coincide with Raft members, the Raft configuration itself). The ack
set for a given `Quiesce(s)` is **the enrolled set at its commit index** — log-pure,
identical on every peer. This is a genuine new prerequisite and is costed in §8;
without it the fence is *undefined*, and through-service relocation stays refused.

**Step 1 — `Quiesce(s)`,** log-serialized, gated (like `Retire`) on the log-order
lifecycle: `s` must be RETIRED. Refused otherwise — deterministic from the projection.

**Step 2 — the peer-local barrier.** Each peer `r`, when its apply loop reaches
`Quiesce(s)`, atomically with respect to its own mutator execution (one lock — a
stated implementation obligation):

  1. **marks `s` locally quiesced** — from this instant, every local mutator refuses to
     author a slot on `s`: `delegate`/`release` across `s` return `null`, and a
     `spendCaptured` whose captured path names `s` **re-homes** that edge's charge to
     the child's live inbound edge *at `r`'s applied log index* (a base write to
     `r`'s own slot on a live edge — uncontended, lattice-legal). This is the §10.4
     invariant weakening of revision 1, unchanged: "history follows the captured path
     *until the path is quiesced*." It remains a normative change to
     `heddle-design.md` §10 invariant 4 / §4.4 and must be reviewed as such (§7). If no
     live inbound exists at `r`'s index (between `Close` and the next `Activate`), the
     charge is buffered locally and flushed when one activates — never dropped, never
     charged to the dead edge.
  2. **reads its own authored slots on `s`** — `issued/returned/leafSpent/rollupSpent
     (s)[r]`. These are final: only `r` writes them, and `r` just swore off.
  3. **proposes `QuiesceAck(s, r, finals)`** with its own requestKey.

**Step 3 — ack recording.** Applying a `QuiesceAck` records the finals in **log-pure
fence state** held *beside* the control-plane projection — never inside the
projection's ledger counters, which must stay empty so the lifecycle gates keep
working (the constraint stated at #1669's `ControlPlane.kt:454`). Re-acks (e.g. after
a restart) are legal and join by per-slot max.

**Step 4 — `Reconcile(child)`, magnitude derived at apply.** The proposer sends **no
witness magnitudes**. The apply gate checks, all against log-pure state: the child has
a unique live inbound `t`; every retired inbound edge of the child being cleared is
quiesced with **acks from the entire enrolled-at-commit set**; per replica, `n ≥ sp`
(else refuse — transfer-tangle unchanged). It then *derives* the §4 patch from the
acked finals and publishes it. Every peer derives the identical patch from the
identical log prefix (State Machine Safety).

This **retracts revision 1's central concession** — "the apply gate can validate only
the shape of the witness, never its magnitude." The magnitude is now itself a
deterministic function of the log, because its inputs (the acked finals) are
consensus-recorded facts. Consensus now confers *correctness*, not merely agreement.
A corollary: the #1669 `readIndex()` proposer fence is no longer load-bearing for this
path — a stale or deposed proposer's `Reconcile` is refused or correctly derived at
apply regardless. Keep `readIndex()` as a cheap pre-propose courtesy check only.

**Why the finding-2 straggler can no longer strike.** Walk the attack: a peer `q`
holds an uncompleted reservation captured across `s`. (a) If `q` has not applied
`Quiesce(s)`, it has not acked, so the fence is incomplete and `Reconcile` **refuses**
— the strand stays standing (safe, recoverable), it is never drained to zero headroom.
(b) If `q` completes *before* its barrier, the charge lands in `q`'s base slots, and
`q`'s subsequent ack finals **include it** (local writes are locally visible; the
barrier and mutators are mutually exclusive under the step-2 lock). (c) If `q`
completes *after* its barrier, the charge **re-homes** to the live lineage and never
touches `s`. (d) A delta emitted by `q` *before* its ack that is still in flight when
the move commits carries per-slot absolutes **≤ `q`'s acked finals** (slots are
monotone in time at their writer), so max-join absorbs it into the already-relocated
values — harmless, by ownership rule 1. There is no fifth case in-incarnation; the
cross-incarnation case is §6.5.

### 6.3 Slot-ownership discipline (finding 1, closed structurally)

The ledger's soundness has always rested on exclusive-writer slots. #1669's witness
quietly broke the discipline: the control plane fabricated `issued(t)[r]` values
(`slot(issued, liveEdge, r) + add`, `EntitlementLedger.kt:488` on that branch) that
owner `r` never emitted, on the **live** edge `r` concurrently writes. The redesign
makes ownership explicit and total:

| slot family | writer |
|---|---|
| base counters on a live edge | the data plane — replica `r` writes its own slots, only ever locally-derived values |
| base counters on a **fenced** edge | the control plane (log apply) — the owners have log-recorded they are done |
| `*Reloc.*` counters, everywhere | the control plane (log apply) exclusively — the data plane never touches them |

With the two §4 rules (republish-observed always; fabricate-beyond-owner only on a
fenced slot), no slot ever has two independent writers, so the max-join erasure of
finding 1 **cannot recur by construction** — there is no interleaving to defeat,
because there is no contended slot. The concurrent-`delegate` replay from the PoC now
converges to `effIssued(t)[r] = delegate's base + relocated n`: both writes survive.

### 6.4 Observer completeness (finding 3)

The published `Reconcile` delta carries **every slot the derivation read on the fenced
edge** — base `issued`, both spend families at their acked values, and the three
`relocIn` families — alongside the reloc slots it writes. This is the same one-delta
drain-witness idiom `Retire` already uses (`ControlPlane.kt:397`: the lifecycle bump is
published merged with the proposer's carried witness, precisely so a laggard cannot
observe the conclusion without its premises).

Republishing the *whole* read set, rather than only the final base spend slots, is
load-bearing in two ways an earlier revision of this section missed. The drain also
writes `returned(s)[r] → effIssued(s)[r]`, so an observer holding the drain without
`issued(s)[r]` reads `returned > issued` and **false-fires `PerEdgeSafety`**; and an
observer holding `leafRelocOut(s)` without `leafRelocIn(s)` reads a **negative**
effective spend. With the full read set carried, no observer can hold the conclusion
without its premises at all: `effRollup(s) ≥ 0` at every observer (§5.3 proviso). #1669's
`carriesOnlyReturnedAndIssued()` witness-shape gate is superseded by the apply-derived
patch — the shape is no longer an input to validate but an output of a deterministic
derivation.

### 6.5 What the fence does not deliver — named residuals

1. **Liveness is hostage to every enrolled peer.** The fence completes only when the
   whole enrolled-at-commit set has acked. This is not an implementation weakness — it
   is the problem statement: an unreachable peer is *exactly* the peer that may hold an
   unreplicated reservation crossing `s`. A crashed peer therefore leaves
   through-service relocation **refused** until it returns (and acks) or is formally
   removed — and removing a dead peer's authority is the unshipped `RevocationSeam`
   problem (`ControlPlane.kt:417-450`), not this design's. No global stop-the-world is
   involved (§6.6), but the *fence's completion* is as available as the slowest
   enrolled peer. Stated plainly: this design trades availability of a rare recovery
   operation for its correctness, and stands by that trade.
2. **The cross-incarnation ack gap.** Peer `r` charges `s`, the delta reaches only
   peer `q`, `r` crashes before acking, restarts with an empty local ledger, and
   re-acks finals that miss its own dead incarnation's write still floating at `q`.
   The relocation then under-counts, and the floating delta later re-creates
   `PerEdgeSafety(s)` — finding 2's disease through a needle's eye. Fully closing this
   requires per-peer **durable** authored-slot storage, which heddle does not have and
   this design does not smuggle in. Instead: (a) the **boot-ordering invariant**
   (below) forces a restarted peer to replay the control log before acking; (b) re-acks
   join by max, so a late recovery *raises* the recorded finals; (c) if the residue
   lands anyway, it is **detectable and attributable** — `base(s)[r] > ackedFinal(s)[r]`
   is machine-checkable from the log — and a **residue sweep** (a second
   quiesce/ack/derive round moving `base − ackedFinal` through the reloc counters, iff
   the live edge's headroom covers it) is *specified as a follow-up and refused in v1*.

   Two corrections to earlier revisions of this clause, both found by implementing it
   (§14 forks 3 and 4):

   - **(a) ships as control-log replay only.** An earlier revision also required the
     restarted peer to "complete one anti-entropy exchange" before acking. There is no
     `Quilter` primitive that signals *"one full exchange has completed"*, so that half
     was never implementable as written and is **not** shipped. The consequence is that
     this residual is exactly as wide as stated above when no anti-entropy has run,
     rather than narrowed — do not assume the narrowing. Adding the Quilter signal is
     tracked as #1782.
   - **A leaf-edge residue IS a conservation break.** An earlier revision claimed a
     post-fence residue was "not a conservation break (`rollupSpent` is outside the
     identity; a leaf-edge residue keeps the identity true because the spend was
     real)". The first clause holds. **The second is false.** `holdings` subtracts
     `effLeafSpent(f)` at the child's *live inbound* edge, so an under-acked leaf spend
     moves the **credit** onto the live edge without moving the **charge** with it, and
     `Σ holdings + Σ effLeafSpent` exceeds `minted` by exactly the under-acked amount.
     It remains surfaced (`PerEdgeSafety` on the edge) and machine-attributable, so it
     is **diagnosed rather than silent** — but it is a conservation break, not a benign
     residue. Pinned by
     `EntitlementLedgerReconcileTest.namedResidual_anUnderAckedLEAFSpendDoesBreakTheIdentityContraDesign652`.
     **This is the case the refused-in-v1 residue sweep exists for**, and its
     justification should be read that way rather than as an optional tidy-up
     (tracked as #1783).

   Both shapes are reachable only through the cross-incarnation gap described above: a
   peer that acks normally marks the edge unwritable and then reads its own slots, so it
   cannot understate. A restart with an empty local ledger can.
3. **The boot-ordering invariant (new normative obligation).** A peer — fresh joiner
   or restart — must not execute data-plane mutators until its control-plane apply has
   caught up to the leader's commit index at boot (one `readIndex()` + wait). Without
   it, a rebooted peer whose quiesce mark (in-memory) was lost could reserve across a
   stale-ACTIVE view of `s` and charge it before replay restores the mark. With it,
   every mark is restored before the first mutator can run. Cheap, and independently
   valuable (it also closes the analogous window for every other log-gated fact).

### 6.6 Cost, honestly

Per fence: `1 + |roster|` control commands (`Quiesce` + one ack each) plus one
`Reconcile` — for a **rare recovery operation** on the "consensus only at the
embroidery" plane (§10.13 explicitly names fencing as embroidery). No peer pauses; no
data-plane traffic stops; the only thing "frozen" is new charges against an edge that
is already RETIRED — a dead generation. This is not a global stop-the-world: it is
`|roster| + 2` log entries and one lock acquisition per peer, and the data plane on
every live edge proceeds untouched throughout.

---

## 7. Sequencing — corrected premise, three slices

Revision 1's sequencing rested on "land (A) and the quiesce together"; the adversarial
review voided that premise — **the quiesce as then specified did not do the job**, so
"together" would have shipped (A) gated behind a fence with a hole exactly where the
fence was claimed to hold. The corrected sequencing:

**Slice 1 — representation (A) + finding-1 migration. Ships first, alone, safely.**
The three reloc families land; `GovernedHeddleNode.reconcile` keeps **refusing**
`spent(s) != 0` (fail-closed, as #1669 does today); and #1669's shipped non-through
witness **migrates off the contested base-`issued(t)` write onto `issuedReloc.in(t)`**.
This last part is not optional polish: finding 1 attacks the net-inflow re-home half
that #1669 already shipped — it is *inherited*, not introduced by (A) — so the shipped
path is exposed to the same silent max-join erasure today. Slice 1 is a strict safety
improvement with no fence dependency.

**Slice 2 — enrollment.** The `Enroll`/`Depart` log-known roster (§6.2 prerequisite).
Independently reviewable; nothing relocates yet.

**Slice 3 — the fence + un-gate.** `Quiesce`/`QuiesceAck`/apply-derived `Reconcile`,
the boot-ordering invariant, and only then the through-service un-gate. Once this
ships, route the **non-through** path through the same fence too, retiring #1669's
documented "not a safety fence — magnitudes read from this possibly stale view"
caveat (`EntitlementLedger.kt:445-452` on that branch) instead of carrying it forever.

Two review flags, unchanged in substance from revision 1:

- **Never ship relocation-enabled `reconcileStranded` ungated.** (A) without the §6
  fence is a net safety *regression*: today through-service fails closed; (A) unfenced
  flips it to silently-accepted relocation of a wrong magnitude — and, per finding 2,
  even a *correct* magnitude followed by one straggler becomes a permanent false
  violation because the drained edge has zero headroom.
- **The §10.4 invariant weakening** (completions re-home off a quiesced edge — §6.2
  step 2) is a normative change to `heddle-design.md` §10 invariant 4 and §4.4 and must
  be reviewed on its own merits. It is sound — a quiesced edge is drained by
  construction, so re-homing one completion to the live lineage is the same conserving
  move applied at charge time — but it is a real semantic change.

**The honest fallback remains available and acceptable:** land slice 1 only, leave
through-service **REFUSED** indefinitely. That outcome fixes finding 1 for the shipped
path, makes the representation ready, and concedes that #1665's through-service case
stays a surfaced, standing conflict until someone funds slices 2–3.

---

## 8. Blast radius, migration, size

**Mutators — unchanged structurally.** `mint`/`delegate`/`release`/`transfer`/`spend`/
`spendCaptured` keep their grow-only bumps. Under slice 3 only: `spendCaptured` gains
the §6.2 re-home/buffer branch for quiesced captured edges, and
`delegate`/`release` gain a locally-quiesced refusal (`null`) — no bump shapes change.

**Reads that switch to effective values:** spent reads add `+ reloc.in − reloc.out`
(`edge()`/`EdgeSummary.spent`, `outstanding` — retire gate + `ClosureViolation` —
`validate()`'s `PerEdgeSafety`, `leafSpentTotal()`); issuance reads add
`+ issuedReloc.in` (`netInflow`, `creditIn`). **`holdings()`** changes in two terms —
`effLeafSpent(f)[r]` and `effIssued` — one extra map lookup each on the hot path;
`rollupSpent` is not in `holdings`, so the rollup relocation never touches it.

**`reconcileStranded`** becomes the §4 generation-move, with the derivation living in
the control-plane apply against fence state (§6.2 step 4) and the `EntitlementLedger`
function reduced to the pure math (acked finals in, patch out).
**`retire`/`activate`/`close` gate** logic unchanged (they read
`outstanding`, which becomes effective — transparently correct).

**New state:** three reloc counter families on `EntitlementLedger` (five
`Map<AttachmentId, GCounter>`: leaf in/out, rollup in/out, issued in); three more
componentwise joins in `piece`; `equals`/`hashCode`/`toString`/`of`/`ZERO` extended.
Control-plane side: fence state beside the projection (quiesced set, per-`(edge,
replica)` acked finals, enrolled roster), commands
`Enroll`/`Depart`/`Quiesce`/`QuiesceAck`/`Reconcile(child)`, and the boot-ordering
gate. **Recommendation:** keep the signed
counters **heddle-local** (in/out `GCounter` maps + the existing `slot()` helper) to
avoid a `:kuilt-crdt` public-API change and its cookbook/Writerside sync burden; note the
`PNCounter`-with-per-replica-`net()` alternative if a reviewer prefers reuse.

**Serialization / migration.** Two new `@Serializable` maps ⇒ a **breaking wire-format
change**. Pre-1.0 explicitly permits this (repo `Status: pre-1.0`, `heddle-ledger-design.md`
"freeze the wire format now" is H1a-scoped and the module is unreleased). No migration
shim needed — bump the ledger and let snapshots/E2E re-derive. Empty reloc maps keep
patch deltas small (a non-reconciling ledger carries no reloc slots at all).

**Size estimate.** Slice 1 (representation (A) + finding-1 migration of #1669's
witness, through-service still gated): **~150 LOC prod + ~150 LOC test**, low risk.
Slice 2 (enrollment): **~100 LOC prod**, low risk. Slice 3 (fence: barrier command +
per-peer ack + apply-derived `Reconcile` + `spendCaptured` re-home/buffer + boot gate):
**~350–450 LOC prod + `MultiNodeRaftSim` fence-race tests**, medium-high risk (touches
consensus *behaviour* → full `./gradlew build` + `:examples:test`, per CLAUDE.md). One
sub-issue per slice (§7).

---

## 9. Verification plan

- **The 4 D1 breaks become regression assertions of the *new* behaviour:**
  `break1and3` (through-service) now **clears** (was: refused) with conservation restored
  and no created `PerEdgeSafety`; `break4` (transfer-tangle) **still refused**; `break2`
  (stale magnitude) is retired *structurally* — the magnitude is no longer proposer-read
  at all, and the test becomes: a `Reconcile` proposed before the ack set is complete
  is **refused at apply**, on every peer identically.
- **The three §11 adversarial PoCs become regression vectors of the fixed design:**
  attack 1 → an observer merging the published `Reconcile` delta alone reads
  `effRollup(s) = 0` and per-edge-safe (never negative); attack 2 → concurrent
  `delegate` down `t` and the relocation both survive
  (`effIssued(t) = base + relocIn`, no erasure, conservation *and placement* correct);
  attack 3 → an un-acked peer **blocks** the fence (refused, strand stands), a
  post-barrier completion re-homes to the live lineage, and a pre-ack in-flight delta
  is absorbed by the acked finals.
- **`MultiNodeRaftSim` fence races:** barrier-vs-completion on one peer (the step-2
  lock), restart mid-fence with re-ack, proposer deposed mid-fence (any leader
  finishes), a joiner enrolling during an open fence (excluded by commit-index
  quantifier, marked via boot gate).
- **Full existing conservation suite passes under the new rep** — the non-through
  positive tests unchanged; the randomized op/merge conservation prototype
  (`heddle-ledger-design.md` §"Prototype validation") re-run with a mid-sequence
  raced-retire + reparent + through-spend + reconcile injected, asserting
  `minted = Σ holdings + Σ effLeafSpent` and per-edge safety after **every** step and
  **every** partial-delivery merge.
- **Cross-platform determinism:** `:kuilt-heddle:build detektAll --rerun-tasks` (native +
  Android variants), and because reconcile is consensus *behaviour*, the full
  `./gradlew build` incl. `:examples`/cluster E2E (CLAUDE.md gate).
- **Join-law suite** extended to the two new components (the zoo lattice-law property
  suite already parameterises over components).

## 10. POC

`kuilt-heddle/src/commonTest/.../LedgerRelocationPocTest.kt` (clearly marked **POC —
throwaway**, self-contained, no dependency on modifying `EntitlementLedger`) models the
effective-counter arithmetic and asserts, on the D1-through example, that the
generation-move (a) restores conservation, (b) preserves per-edge safety on both `s` and
`t`, and (c) is idempotent. It validates the *math* of §4–§5, not the eventual Kotlin
wiring. **Caveat found in review:** it models the counters as mutable `Long`s, which
hides the join — the adversarial PoC (§11) re-modelled the same representation under
the real `GCounter` per-slot max-join and found the §11 findings the `Long` model
could not express. Both are throwaway; delete before the real implementation lands.

## 11. Adversarial review record (2026-07-25)

A Fable adversarial pass re-modelled §3(A) under the **real `GCounter` per-slot
max-join** (mirroring `EntitlementLedger.mergeEdgeCounters`) instead of the shipped
PoC's mutable `Long`s, and replayed the interleavings revision 1 deferred as
"mechanical" or claimed to fence. Artifacts: branch
`review/1665-relocation-adversarial`
(`LedgerRelocationAdversarialPocTest.kt`, all three attacks reproduce green) and the
review comment on PR #1677. Verdict on the representation: §3/§5.1 **survived** — the
reloc counters really are componentwise-join `GCounter`s and inherit the join laws;
option (B) stays rejected. The findings, and where this revision answers each:

| # | finding (severity) | what broke | answered by |
|---|---|---|---|
| 1 | contested absolute target (high) | witness fabricated `issued(t)[r]` beyond the owner's emissions on the live edge; concurrent `delegate` + max-join silently erased one writer; invisible to conservation *and* per-edge safety; **inherited by #1669's shipped path** | §4 ownership rules; §6.3 — live-edge additions go to control-plane-owned `issuedReloc.in`, base slots single-writer by construction; §7 slice 1 migrates #1669 |
| 2 | post-stability straggler (highest — the reason for this revision) | causal stability bounds only writes that exist when the frontier is taken; a peer that had not *applied* `Quiesce(s)` charged the drained edge afterwards → `PerEdgeSafety(s)` with `outstanding = −2`, permanently unclearable (`outstanding > 0` iteration + `n ≥ sp` both fail) | §6.1 (why no frontier can work), §6.2 (per-peer barrier + ack; magnitude derived at apply from acked finals; the four-case walk), §6.5.2–3 (the named residual and boot invariant) |
| 3 | observer-negative effective spend (low) | `reloc.out(s)` delivered via the log before the base spend gossiped in → transient `effRollup < 0` / false `PerEdgeSafety`; §5.3's bound was a proposer-view claim; entrenched by #1669's `carriesOnlyReturnedAndIssued()` gate | §6.4 — the published delta carries `s`'s final base slots (Retire's drain-witness idiom); §5.3 proviso restored observer-side |
| — | (interaction, from finding 2) | revision 1's §7 premise "land (A) and the quiesce together" was void — the quiesce as specified did not deliver its barrier | §7 rewritten: three slices, fence load-bearing, fallback = through-service refused |

**Interleavings attacked during this revision that the redesign defeats:** concurrent
`delegate` on the fenced-move target (no contended slot exists); a peer applying the
barrier concurrently with a completion (step-2 lock); a pre-ack in-flight delta racing
the move (absolutes ≤ acked finals, absorbed); a proposer deposed or restarted
mid-fence (fence state is log-pure; any leader finishes; `readIndex` demoted to
courtesy); a joiner enrolling mid-fence (commit-index quantifier + boot gate); a
double `Reconcile` (apply-gate refuses on fence state); a straggler `release` on the
fenced edge (pre-ack: included in finals; post-ack: refused by mark). **The one
interleaving not fully defeated** is §6.5.2 — charge, delta escapes to exactly one
other peer, crash before ack, restart re-acks lower before anti-entropy recovers the
floating write. It is narrowed by the boot-ordering invariant, made detectable and
attributable (`base > ackedFinal` in log state), and its cleanup is specified as the
refused-in-v1 residue sweep. Closing it outright requires durable per-peer
authored-slot storage — a dependency this design declines to smuggle in.

---

*Deliverable for #1665, revision 2 — the post-adversarial-review redesign of the
fence. If the reviewer rejects the §7 invariant weakening or the slice-2/3 machinery,
the strictly-safe fallback (slice 1 only: representation present, #1669 migrated off
the contested slot, through-service still refused) is the recommended landing.*

---

## 12. Implementation feedback from slice 1 (#1691 / PR #1694)

Slice 1 landed the representation and the finding-1 ownership fix. Five corrections to
this document surfaced while building to it. **Slice 3 must read these** — three of them
change what the fence has to do.

1. **§4's drain target is wrong: `iss = issued(s)[r]` should be `effIssued(s)[r]`.** A
   retired edge may itself have received an earlier relocation, so draining against the
   *base* value under-drains it. No behavioural difference in slice 1 (nothing relocates
   onto an edge that later retires), but it bites in slice 3.

2. **§6.3's ownership table is coarser than reality, and the missing line is load-bearing.**
   The table lumps "base counters on a fenced edge → control plane". In fact the
   `returned(s)` write is safe *without* any fence, for a narrower reason: `release` refuses
   a non-live inbound edge, so `returned` on a RETIRED edge has **no data-plane writer at
   all**. This does **not** extend to `leafSpent`/`rollupSpent`, which a captured-path
   `spendCaptured` can still charge on a retired edge. That asymmetry is precisely why
   *spend* relocation needs the fence and the `returned` drain does not — the table should
   say so rather than implying a uniform rule.

3. **§5.4's idempotence clause (iii) leans on fence state slice 1 does not have.** Absent a
   fence, the guard against double relocation is that `reconcileStranded` returns `null`
   once `outstanding <= 0` — a **proposer-side** check the apply gate cannot re-derive,
   because the projection's counters are empty. It is still safe (a stale witness carries
   absolutes ≤ what already merged, so max-join absorbs it), but there is a real residual:
   **two relocations onto the same live edge must each be computed from a view that merged
   the previous one**, or the second absolute undershoots and is swallowed. This is the
   Wall-A magnitude-freshness residual again, narrowed from two writers to one. Slice 3's
   apply-derived magnitude is what removes it.

4. **§3(A) says five new maps; §8 says "two new `@Serializable` maps".** It is five.

5. **`validate()` has no negative-effective-spend check.** §5.3's observer-negative proviso
   cannot fire in slice 1 (nothing writes spend relocation) and a new `LedgerConflict`
   variant is slice-3 scope — but §6.4's observer-completeness work must add it, or the
   condition §5.3 describes will be unreportable.

## 13. Implementation feedback from slice 2 (#1692 / PR #1698)

Slice 2 landed the `Enroll`/`Depart` log-known roster. Four gaps in §6.2 surfaced. The
first two are safety-relevant and **slice 3 must close the second one**.

1. **§6.2 never says who may enroll or depart.** It names the command pair and stops.
   Slice 2 inferred the rule from §6.1's promise argument and it should be stated here:
   **`Depart` is self-service** — applied iff the committed `ControlEnvelope.proposer` *is*
   the departing replica — because departing *shrinks* the ack set, which asserts "I will
   never author another slot," a promise about the future only the promiser can make.
   **`Enroll` is open to any proposer**, because it only *enlarges* the set: a mistaken
   enroll costs a barrier's liveness, never its safety. That asymmetry is the roster's
   load-bearing safety property and belongs in the normative text.

2. **An unenrolled writer defeats the fence.** §6.2 quantifies the barrier over the enrolled
   set, but nothing makes enrollment a *precondition* for authoring a counter slot. A peer
   that spends without enrolling is a writer no barrier ever waits for — which reintroduces
   finding 2 through a side door. Slice 2 could only document this as an obligation on
   `enroll`; **only slice 3's boot gate can make it structural, and slice 3 must.**

3. **A wrongly-enrolled phantom wedges every future fence, permanently.** Because `Depart`
   is self-service, a replica that is enrolled but never existed (or is gone for good)
   cannot be removed, and every subsequent barrier waits on it forever. Liveness, not
   safety, and the same class as §6.5 residual 1 — but §6.5 does not name it. It is the
   direct cost of the §6.1 asymmetry in item 1, and the exit is the unshipped
   `RevocationSeam`.

4. **`enrolledAt` needs a companion applied-index**, unmentioned in §6.2. Without one a peer
   can silently answer the barrier quantifier from a log prefix it has not applied — a short
   fold that looks like an answer. `EnrolledRoster` carries `appliedIndex` and throws rather
   than answering beyond it.

## 14. Implementation feedback from slice 3 (#1693)

Slice 3 landed the fence, the apply-derived `Reconcile`, the boot gate, and the through-service
un-gate. Seven corrections.

Items 3 and 4 were surfaced by the implementation as **forks rather than invented around** — both
are cases where the design's normative text promised something the code does not do. Both are now
resolved *in the design's favour of honesty*: the text is corrected to describe what ships, and the
missing capability is tracked (#1782, #1783). Item 4 is the consequential one — a residue this
section documented as safe turns out to be a real conservation break in one shape.

1. **`QuiesceAck` must be self-service, and §6.2 never says so.** §6.2 step 2.3 says a peer
   "proposes `QuiesceAck(s, r, finals)` with its own requestKey", which *implies* self-authorship
   but does not gate it. It has to be gated, on exactly §6.1's argument and exactly §13.1's
   `Depart` asymmetry: an ack **shrinks** what the barrier is still waiting for, so it asserts a
   fact about the acking replica's own future writes, and only the promiser can assert that. A
   third-party ack would let the survivors declare a peer done while it still holds an
   unreplicated reservation — finding 2 through a side door, a second time. Shipped as a gate on
   `ControlEnvelope.proposer == replica`, the same shape as `Depart`.

2. **The boot fence does not need `readIndex()`, and is better without it.** §6.5.3 specifies "one
   `readIndex()` + wait". `readIndex()` throws on a non-leader, so a follower could never open its
   own gate, and §11's own note about entries Raft withholds from `committedFrom` means the
   applied prefix can legitimately sit below the fenced index (the trap `prepareNeutral` already
   documents). A strictly better fence with the same guarantee: **wait for this peer's own
   post-boot `Enroll(self)` to apply here.** Raft applies in index order, so a peer that has
   applied its own enroll has applied every entry before it — every barrier committed earlier is
   restored, and every later one arrives in order. It works on a follower, it cannot be wedged by
   a withheld entry, and it makes §13.2's enrollment precondition *the same act*, closing both
   holes with one gate. `GovernedHeddleNode.isWritable`; `reserve`/`schedule` are the two entry
   points that author a slot, and `complete` is transitively gated because a reservation can only
   come from `reserve`.

3. **§6.5.2(a) required "one anti-entropy exchange" before acking, and nothing supports it.**
   §6.5.2 narrowed the cross-incarnation ack gap by having a restarted peer "replay the
   control log *and complete one anti-entropy exchange* before acking". §6.5.3's normative text
   mentions only the control-log half. The anti-entropy half needs a *Quilter* primitive that does
   not exist — there is no "one full exchange has completed" signal to wait on — so shipping it
   means designing that primitive. **Not implemented; the control-log half is.** The consequence
   is that the §6.5.2 residual is exactly as wide as the design says it is when no anti-entropy
   has run, rather than narrowed.

   **Resolved:** §6.5.2(a) now describes what actually ships, so the design no longer promises a
   narrowing the code does not perform. Adding the Quilter signal is tracked as **#1782**.

4. **§6.5.2's "not a conservation break" was wrong for a *leaf*-edge residue.** The text
   says the residue is "not a conservation break (`rollupSpent` is outside the identity; a
   leaf-edge residue keeps the identity true because the spend was real)". The parenthetical's
   first clause holds and is now pinned by a test; **the second does not.** `holdings` subtracts
   `effLeafSpent(f)` at the child's *live inbound* edge, so an under-acked leaf spend moves the
   **credit** onto the live edge without moving the **charge** with it, and
   `Σ holdings + Σ effLeafSpent` exceeds `minted` by exactly the under-acked amount. It is still
   surfaced (`PerEdgeSafety` on the edge) and still machine-attributable (`base > ackedFinal`), so
   it is diagnosed rather than silent — but it is a conservation break, and the refused-in-v1
   residue sweep must therefore treat the leaf case as the one it exists for. Pinned by
   `EntitlementLedgerReconcileTest.namedResidual_anUnderAckedLEAFSpendDoesBreakTheIdentityContraDesign652`.

   **Resolved:** §6.5.2's text is corrected, and the residue sweep's justification is restated as
   *this is the case it exists for* rather than an optional tidy-up. Tracked as **#1783**. Both
   shapes are reachable only through the cross-incarnation gap — a peer that acks normally marks
   the edge unwritable and then reads its own slots, so it cannot understate.

5. **The published move must republish more than §6.4 says.** §6.4 says the delta carries "`s`'s
   **final base spend slots** at their acked values". That is necessary but not sufficient: the
   drain also writes `returned(s)[r] → effIssued(s)[r]`, so an observer holding the drain without
   `issued(s)[r]` reads `returned > issued` and false-fires `PerEdgeSafety` — and an observer
   holding `leafRelocOut(s)` without `leafRelocIn(s)` reads a negative effective spend. The
   shipped delta republishes **every** slot the derivation read on the fenced edge — base `issued`
   and both spend families, plus the three `relocIn` families — so no observer can hold the
   conclusion without its premises at all. §6.4's list should be widened to match.

6. **The refusal shape is "not fenced yet", not "malformed witness".** With the proposer sending
   no magnitudes, `carriesOnlyRehomeSlots()` and the whole witness-shape gate disappear — there is
   nothing to validate, because the patch is an output of the derivation rather than an input to
   it. What replaces them is a fence-completeness refusal that **names the replicas it is waiting
   on**, and the practical consequence for the caller is that `reconcile(child)` is a *two-call*
   operation: it opens the barriers, and the acks are separate committed acts that land afterwards.
   The design does not specify a driver for that wait, and one was deliberately not invented —
   refusing with the pending set (`pendingAcks(edge)`) is fail-closed and observable; a caller that
   blocked would be waiting, unbounded, inside a recovery path whose completion is by design as
   available as the slowest enrolled peer.

7. **The write gate is a breaking behaviour change for consumers, and it fails *silently*.** The
   §13.2 gate (item 2) means a governed node that has not applied `enroll(self)` delegates nothing
   and reserves nothing. Correct — but the closed gate is reported through **in-band values that
   already mean something else**: `reserve` returns `null` (which also means "no holdings") and
   `schedule` returns `0` (which also means "nothing to delegate"). A consumer that forgets to
   enroll therefore gets a scheduler that silently does nothing, indistinguishable from an idle one.
   The full build caught exactly this downstream — `:kuilt-warp-heddle`'s
   `HeddleAdmissionControlTest.governedNodeComposesIntoAdmissionControl` failed with `A=0 B=0` —
   while every `:kuilt-heddle` test passed, so a module-scoped build would have green-lit it. Two
   things to carry: consumers must enroll on **every** boot (now stated on
   `HeddleAdmissionControl`'s KDoc and in the cookbook), and **whether the gate should refuse
   loudly rather than return an ambiguous in-band value is an open question** — this module's own
   precedent (#1737, #1753) is to fail loudly rather than return a silent fallback.
