# Relocating stranded budget — a representation change for the entitlement ledger

> **Status: design, under review. Not implemented.** This proposes the
> representation change that lets the fair-share ledger *move* already-spent budget
> from a retired branch of the tree onto the branch that replaced it — the one thing
> the current representation cannot do, and the reason issue #1665's through-service
> case is carved out of PR #1669. A Fable adversarial review runs against this design
> before any code is written. Companion to [`heddle-design.md`](heddle-design.md) §4/§5/§9/§10
> and [`heddle-ledger-design.md`](heddle-ledger-design.md).

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

The net-inflow half of the move stays as PR #1669 has it — ordinary `returned(s)`/
`issued(t)` bumps — so `holdings`'s hot path keeps using base `issued`/`returned` with
no indirection. Only the two **spent** reads become effective. (One could relocate
`issued`/`returned` too, for uniformity; rejected — it puts an effective-counter
indirection on `holdings`, the per-pick hot path, for no correctness gain.)

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
| blast radius | 2 new maps; 4 effective reads; hot `holdings` path untouched | rewrite the entire per-edge accounting substrate |
| re-proof difficulty | extend the existing per-component proofs | re-prove conservation over a foreign convergence model |
| expresses the move? | yes — signed adjustment, net-decrease via a 2nd monotone counter | no — node-move, not quantity-move |

Iain's prior (A is the sweet spot) is **confirmed**, and the proof of it is that A
changes *how much* state there is, not *how it converges*.

---

## 4. The move under representation (A) — precise operation

`reconcileStranded(child)` becomes a **generation-move** instead of a
conserving-or-refuse. For the child's single live inbound edge `t = liveEdge` and each
retired inbound edge `s` with `outstanding(s) > 0`, per replica `r`, let

```
iss = issued(s)[r]      ret = returned(s)[r]
lsp = effLeafSpent(s)[r]  rsp = effRollupSpent(s)[r]     n = iss − ret   sp = lsp + rsp
```

The patch (all targets shipped at **absolute** values — max-safe, idempotent):

```
returned(s)[r]      → iss             # release the full net inflow up s   (n = iss − ret)
issued(t)[r]        += n              # re-delegate it down the live edge
leafReloc.out(s)[r] += lsp   leafReloc.in(t)[r]   += lsp   # relocate leaf-spend s → t
rollupReloc.out(s)[r] += rsp rollupReloc.in(t)[r] += rsp   # relocate rollup-spend s → t
```

**Preconditions (per replica), and how they map to the existing carve-outs:**

- `n ≥ sp` ⟺ `outstanding(s)[r] ≥ 0`. Holds for a healthy strand. A replica left
  **net-negative** on `s` (the transfer-tangle case, PR #1669 `break4`) has `n < 0` and
  is **still refused** — relocating it faithfully would require moving transfer rows too
  (out of scope; a separate follow-up).
- `t`'s post-move per-edge safety must hold: `effLeaf(t)+effRollup(t)+ret(t) ≤ iss(t)`.
  On a fresh reparent edge (`t` base 0) this reduces to `sp ≤ n` — the same condition.

The through-service case that PR #1669 **refuses**, this design **clears**. The
non-through case (`sp = 0`) reduces exactly to PR #1669's shipped
release-up-then-redelegate — so **(A) is a strict superset** of the current behaviour,
and the two shipped positive tests
(`reconcileStrandedClearsConflictsAndRestoresConservation`,
`reconcileClearsRacedRetireStrandAcrossAllPeers`) keep passing unchanged.

---

## 5. Re-proof sketch (enough for a reviewer to check soundness)

Let `E = base + reloc.in − reloc.out` denote any effective spent value; base and both
reloc halves are grow-only `GCounter`s.

### 5.1 Join laws (idempotent / commutative / associative)

`piece` gains two componentwise `GCounter`-map joins (`leafReloc`, `rollupReloc`), each
already a semilattice. A finite product of semilattices is a semilattice, so `piece`
remains idempotent/commutative/associative — the **identical `LatticeProduct` argument**
that covers the existing eight components, now n = 10. Every stored component still only
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
`out(s) += lsp` where `lsp = effLeafSpent(s)[r] ≥ 0`). ∎

### 5.4 Idempotence of the move itself

Absolute targets ⇒ re-applying the Reconcile witness is a max no-op. After a move,
`effOutstanding(s) = 0`, so a second `reconcileStranded` finds nothing and returns
`null` — matching PR #1669's `reconciliationIsConservingAndIdempotent`. ∎

**Status of the sketch:** 5.1/5.4 are complete and low-risk (they follow mechanically
from the lattice-product structure). 5.2/5.3 are complete *per replica per move* and
shown on the D1-through witness; the remaining obligation for implementation is the
**multi-replica, multi-retired-edge aggregation** (sum the per-`r`, per-`s` moves and
re-run 5.2/5.3 on the aggregate) — mechanical, and the POC in §9 exercises it on the
worked example.

---

## 6. Wall A — fencing the magnitude (the honest seam)

Representation (A) makes the move *expressible*; it does **not** make it *safe to
compute*. The witness magnitude (`n`, `lsp`, `rsp`) is read from the proposer's
gossip-replicated data plane, and the apply-side gate runs on the **log-pure
projection whose counters are empty** — so **the apply gate can validate only the
*shape* of the witness, never its *magnitude***. The authoritative magnitude comes from
the proposer and is republished identically to every peer. **Consensus gives agreement,
not correctness** — so the magnitude must be *correct at propose time*.

Two facts make "correct at propose time" non-trivial (PR #1669 `break2`):

1. `readIndex()` fences **log-order authority**, not data-plane freshness (independent
   transport). A causally-lagged *current* leader still reads stale counters.
2. `s`'s counters are **not stable after RETIRED** — `spendCaptured` completions on
   captured paths crossing `s` keep charging `leafSpent(s)`/`rollupSpent(s)` (§4.4/§10.4,
   "history follows the captured path").

### The design: **freeze → fence → relocate**

1. **Leader-authority fence (shipped in #1669).** `readIndex()` before computing the
   witness; a deposed/partitioned proposer is refused.

2. **Quiesce barrier on `s` (new, log-serialized).** A `ControlCommand.Quiesce(s)`
   committed *after* the retire. Its normative effect: **after `Quiesce(s)` is in the
   log, no completion may charge `s` again.** A straggler `spendCaptured` whose captured
   path names a quiesced edge re-homes its charge to the child's **live lineage** (or is
   surfaced), rather than charging the dead edge. This freezes `s`'s counters monotone
   forever — turning "history follows the captured path *unconditionally*" (§10.4
   invariant 4) into "…*until the path is quiesced*." **This is a change to a normative
   invariant and must be reviewed as such** (see §7).

3. **Causal-completeness fence (new).** After `Quiesce(s)` commits, the proposer waits
   for **Quilter causal stability** on `s`'s slots — the delivered version-vector frontier
   covers every peer's writes to `s`'s counters — using the same causal-stability
   machinery `MovableTreeGcCoordinator` already consumes. Only then is the proposer's
   view of `s` provably final. It computes `n/lsp/rsp` from that frozen, fully-delivered
   view and proposes `Reconcile` with the witness.

The apply gate keeps PR #1669's structural checks (unique live inbound; witness touches
only `t`'s `issued`/spent-reloc and `s`'s `returned`/spent-reloc slots; no `minted`, no
topology) — now provably sufficient **because** steps 2–3 guarantee the magnitude was
computed on a frozen, complete view. The `Reconcile` command should additionally carry
the **quiesce frontier** (the delivered VV it fenced against) as an auditable
certificate, even though peers cannot re-derive the data plane from the log.

**Separability.** Quiesce (Wall A) is *when to propose*; relocation (Wall B) is *what to
propose*. They are separable slices — **but not independently shippable in the unsafe
direction** (§7).

---

## 7. Critical soundness finding — A and the quiesce must land together

**Representation (A) shipped *without* the Wall A quiesce is a net safety
*regression*.** Today the through-service case **fails closed** (refuses, conflicts left
standing, recoverable). Give `reconcileStranded` the power to *decrease* effective spend
but keep computing the magnitude on an unfenced, unstable view, and the through-service
case flips from *safe refusal* to *silently-accepted relocation of a wrong magnitude* —
manufacturing phantom supply / negative effective spend on the converged state (exactly
`break2`, now reachable for the through case it used to refuse). **The representation
change removes the guardrail that the refusal currently provides.**

Therefore the recommendation is explicit:

- **Do not ship the relocation-enabled `reconcileStranded` ungated.** Either land (A) and
  the quiesce (§6) **together**, or land (A) but keep `reconcile` **refusing** the
  through-service case at the control-plane gate until the quiesce exists (i.e. the
  representation is present, the `EntitlementLedger.reconcileStranded` math is exercised
  by unit tests, but `GovernedHeddleNode.reconcile` still returns `Refused` for
  `spent(s) != 0` — a deliberate, documented fail-closed).
- **The §10.4 invariant weakening** (completions may re-home off a quiesced edge) is a
  normative change to `heddle-design.md` §10 invariant 4 and §4.4 — it must be reviewed
  on its own merits, not slipped in as a side effect. It is *sound* (a quiesced edge is
  drained by construction, so re-homing a straggler to the live lineage is the same
  conserving move applied to one completion) but it is a real semantic change.

This is not a reason to abandon the change — it is the condition under which it is
correct. If the reviewer is uncomfortable weakening §10.4, the fallback (A present,
through-service still refused) is strictly safe and still worth landing, because it makes
the **representation** ready and shrinks the open surface of #1665 to the single quiesce
mechanism.

---

## 8. Blast radius, migration, size

**Mutators — unchanged structurally.** `mint`/`delegate`/`release`/`transfer`/`spend`/
`spendCaptured` keep their grow-only bumps. `spendCaptured` gains the §6 quiesce branch
(re-home off a quiesced captured edge) — the only mutator that changes, and only under
Wall A.

**Reads that switch to effective spend** (add `+ reloc.in − reloc.out`):
`edge()`/`EdgeSummary.spent`, `outstanding` (retire gate + `ClosureViolation`),
`validate()`'s `PerEdgeSafety`, `leafSpentTotal()`. **`holdings()`** changes in exactly
one term — `effLeafSpent(f)[r]` (per-replica) — a single extra map lookup on the hot
path; `rollupSpent` is not in `holdings`, so the rollup relocation never touches it.

**`reconcileStranded`** becomes the §4 generation-move (from ~60 LOC refuse-or-clear to
~90 LOC move). **`retire`/`activate`/`close` gate** logic unchanged (they read
`outstanding`, which becomes effective — transparently correct).

**New state:** two `Map<AttachmentId, PNCounter>` (or four `Map<AttachmentId, GCounter>`
in/out) on `EntitlementLedger`; two more componentwise joins in `piece`;
`equals`/`hashCode`/`toString`/`of`/`ZERO` extended. **Recommendation:** keep the signed
counters **heddle-local** (two in/out `GCounter` maps + the existing `slot()` helper) to
avoid a `:kuilt-crdt` public-API change and its cookbook/Writerside sync burden; note the
`PNCounter`-with-per-replica-`net()` alternative if a reviewer prefers reuse.

**Serialization / migration.** Two new `@Serializable` maps ⇒ a **breaking wire-format
change**. Pre-1.0 explicitly permits this (repo `Status: pre-1.0`, `heddle-ledger-design.md`
"freeze the wire format now" is H1a-scoped and the module is unreleased). No migration
shim needed — bump the ledger and let snapshots/E2E re-derive. Empty reloc maps keep
patch deltas small (a non-reconciling ledger carries no reloc slots at all).

**Size estimate.** Representation-only (A, closes Wall B, through-service still gated):
**~120 LOC prod + ~120 LOC test**, low risk. Full close incl. Wall A quiesce (barrier
command, `spendCaptured` re-home, Quilter causal-stability wait, apply-gate certificate):
**~200–300 LOC prod + a `MultiNodeRaftSim` quiesce-race test**, medium risk (touches
consensus *behaviour* → full `./gradlew build` + `:examples:test`, per CLAUDE.md). Suggest
**two sub-issues**: (1) representation (A) + gated `reconcileStranded`; (2) the quiesce +
un-gate.

---

## 9. Verification plan

- **The 4 D1 breaks become regression assertions of the *new* behaviour:**
  `break1and3` (through-service) now **clears** (was: refused) with conservation restored
  and no created `PerEdgeSafety`; `break4` (transfer-tangle) **still refused**; `break2`
  (stale magnitude) is the Wall A test — it must **still fail closed under the quiesce**
  (the stale proposer cannot pass the causal-stability fence), replacing the
  "documents-the-residual" assertion with "the quiesce prevents the phantom supply."
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
wiring. Delete before the real implementation lands.

---

*Deliverable for #1665. Design under review; a Fable adversarial pass runs before any
implementation. If the reviewer rejects the §7 invariant weakening, the strictly-safe
fallback (representation present, through-service still refused) is the recommended
landing.*
