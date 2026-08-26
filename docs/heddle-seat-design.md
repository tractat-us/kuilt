# Where a newborn's seat lives — settling #1713 and #1696

> **Status: SUPERSEDED and shipped — the revision this document asked for exists, and it
> landed.** The `Gauge` composition recorded on
> [#1752](https://github.com/tractat-us/kuilt/issues/1752) replaced §5.2's refuted write
> gate and §5.1's additive read path; it shipped in three slices, the last of which
> deleted `AttachmentRecord.initialVirtualTime` outright. Read this document for *why the
> seat had to move* (§1–§4 are unaffected and were strengthened by review) and for the
> attacks in §5.5 and §11 that killed the first composition. Do **not** read §5.1/§5.2 as
> the design, §7 as work to be done, or §9's fork as open — see the three notes below.
>
> - **§7's escape hatch is moot.** It designed a non-`data` `AttachmentRecord` plus a
>   ~40-line legacy `KSerializer` that would keep writing a `0` seat element on the wire,
>   because #1713 said the field had to be *retained*. The field was deleted instead
>   (@keddie, 2026-08-12: *"just break it… no one is using it at the moment"*), so there
>   is nothing left to make inexpressible and no legacy element to carry. §7.1's analysis
>   of what deleting it costs — undecodable historical `Prepare` entries are **silently
>   skipped** on replay — is still accurate, and is the price that was knowingly paid.
> - **§9's fork is decided, the same way.** "Is a breaking `EntitlementLedger` wire change
>   acceptable?" — yes; the relocation design's precedent held.
> - **§5.4's deletions all happened**, including `prepareNeutral`'s `readIndex()` fence and
>   applied-prefix gate, and both of that method's documented residuals.
>
> ---
>
> **Original status header, kept for the record: PARKED after adversarial review.**
> This settles a question two issues ask in two different voices: when a new child joins
> the fair-share tree, *what decides where it starts*, and *where does that decision
> live*? #1713 recommends removing the stored answer; #1696 recommends storing a better
> one. Companion to [`heddle-design.md`](heddle-design.md) §7.2/§10.5/§10.6 and
> [`heddle-ledger-relocation-design.md`](heddle-ledger-relocation-design.md), whose
> structure this document follows.
>
> **Revision 2 (2026-07-26), after the Fable adversarial pass recorded in §11.** The
> verdict splits in two, and the split is the point:
>
> - **The *location* argument survives, and was strengthened.** The seat must live in a
>   **join-convergent, replicated** position. Candidate A — stop storing it — is refuted
>   (§4), and the obvious rescue of A collapses back into this same class (§4.2). That
>   part is settled.
> - **The *composition* specified here does not survive.** §5.2's `effIssued == 0` write
>   gate, combined with §5.1's additive read path `ev = seat + committed/w`, is
>   **refuted by two executed attacks** (§5.5): the gate is stale-readable and
>   double-counts service, and a relocation-receiving edge can never be seated at all.
>   §5.3's bound is falsified in general.
>
> So this document is the **durable record of what was learned**, not a green light.
> Nothing here should be implemented until §5.2 is redesigned; §10's slicing is
> retained because it stays useful once that revision exists.

## In one paragraph, for anyone

Imagine a queue where everyone gets a fair turn. To keep it fair, the system remembers
roughly how much service each person has already had, and always serves whoever is
furthest behind. Now someone new walks up. Where do they go? Not at the front — they'd
get everything until they "caught up" to a lifetime of service they never received. Not
at the back either. They should join **level with everyone else**, as if they had been
waiting exactly as long as the people already there. So the system writes down a
starting position for the newcomer, once, permanently. The bug is that whoever writes it
down is guessing from an out-of-date picture of the queue — and once written, that guess
is treated as fact by everyone, forever. This document works out where that starting
position should actually live so a wrong guess can be corrected instead of frozen. The
answer turns out to hinge on a question nobody asked: **can a machine that reboots still
work out where everyone was standing?**

It half-worked. Deciding *where* the starting position has to be kept is settled, and the
reasoning held up under attack. Deciding *when* to write it down did not: a second review
found two ways the rule proposed here writes the wrong number, and both are recorded rather
than papered over. So this is a document about what was learned, and the work is paused
until the second half is redesigned. That is the honest state, and writing it down is worth
more than a plan that looks finished.

The rest of this document is the technical design.

---

## 1. One question, two faces

`AttachmentRecord.initialVirtualTime` — the newborn's *seat* — is a number the proposing
peer computes from its own local view and freezes into a replicated record. Two open
issues attack it from opposite ends:

- **#1696 (the sample is stale by construction).** `V` is read at *propose*; the newborn
  only competes after *activate* commits. Every grant the parent makes in between moves
  the real front forward while the frozen seat does not. The newborn enters *behind* the
  front — the lifetime credit §10.5 forbids — by exactly the service rendered during the
  round trip. #1696 measured it.
- **#1713 (the sample is unvalidatable).** `initialVirtualTime` is the only **stored
  derived** quantity in the ledger, and it sits in `records`, whose per-id join is set
  union — the one component that *preserves* conflicts rather than resolving them
  (§5.2 deliberately forbids last-writer-wins on a parent pointer). No apply-time check
  can recompute it, because the log-pure projection has no counters by design. A
  proposer whose view is unconverged seats the newborn wrongly — identically,
  permanently, on every peer.

They are the same defect. #1696 is the case where the *sample was right and went stale*;
#1713 is the case where the *sample was wrong to begin with*. Both end in one frozen
`Long` that nothing can ever correct.

#1713 states the safety rule that governs all of this, and it is correct:

> A carried proposer-local reading is safe when it (**a**) **republishes** slots the data
> plane itself authors into a join-convergent position — so the authoritative value
> arrives independently and max-join dominates any stale carry — and/or (**b**) is
> **validated at apply time against log-ordered state**, so a wrong carry is refused.
> A carry that **fabricates** the sole authoritative write is monotone but never
> self-corrects.

`Prepare`'s seat satisfies neither. §8 verifies #1713's carriage table against `main`.

---

## 2. The measurement, re-derived

#1696 reports: at consensus lag 25 rounds the newborn's deficit is **12.5 virtual units**
and it takes **18 of the next 30 grants** instead of a fair 10. Its stated source is an
`Inv1688Test` harness — which **exists on no ref in this checkout**, `main` included
(`git log --all -S Inv1688` is empty). So the number is unpinned by anything that ships.
It is also correct: `NewbornSeatPocTest` re-derives it from first principles.

The scenario the numbers imply is a parent with **two** incumbent children at unit
weight and quantum 1. During `lag` rounds the parent renders `lag` units split between
them, so the front advances by `lag / 2`:

| lag (rounds) | deficit at activate | newborn's share of the next 30 grants |
|---|---|---|
| 0 | 0 | 10 / 30 — fair |
| 25 | **12.5** | **18 / 30** |

The 18 is not a rounding artefact; it is EEVDF working correctly on a wrong input. The
newborn is the only eligible child (`ev ≤ V`) for the first **13** consecutive grants,
and only then does the round-robin resume. #1687's `ceil` correction is worth `< 1` unit
against a term worth 12.5 — which is #1696's central point, and it holds.

**And the deficit is unbounded**: it is `(grants committed at that parent during
propose→activate) / (siblings × w)`. A busy parent and a slow round trip make it
arbitrarily large. This is live, not theoretical.

The PoC also pins the target every candidate is measured against: seating at the front
**of the set the newborn actually joins, at the moment it joins it** is fair at lag 0, 5,
25 and 200. That is the specification.

---

## 3. Candidate A — don't freeze a seat; make the newborn a waker

#1713's recommendation. `initialVirtualTime` becomes a constant; the effective seat
materialises *locally on first demand* as a recomputed wake offset, which is what
`HeddleNode.wakeOffsets` already is. Its appeal is real and worth stating plainly:

- It collapses **two** mechanisms into one. §7.2 already has a rule for "a child joins
  the competing set" — the §10.6 wake clamp — and a newborn is definitionally a child
  crossing not-demanding→demanding. Two rules become one.
- It **removes** replicated state rather than adding it.
- It dissolves the two-proposer hazard: with no seat to disagree on, two proposers
  building the same generation produce **byte-identical** records, so the
  `RecordDivergence` starvation `HeddleNode.prepare`'s KDoc warns about cannot arise.
- Verified in support: the **only** production read of `initialVirtualTime` is
  `HeddlePolicy.virtualService` (`HeddlePolicy.kt:146`). `BoundMetrics`, `validate()`,
  `LedgerConflict` and the conservation identity never touch it — it participates only
  via whole-record equality in divergence detection. That claim in #1713 is **confirmed**
  by exhaustive grep.

Every one of those is true. A still fails, for a reason none of them touches.

---

## 4. Critical soundness finding — A's seat is not recomputable

**`HeddleNode.wakeOffsets` is a per-boot, in-memory `HashMap`** (`HeddleNode.kt:178`).
It is never replicated (deliberately — §7.2), never persisted, and never rebuilt from
anything. Today that is harmless: losing a wake clamp on restart costs one child a
bounded idle-credit clamp, and §7.2's justification is exactly that — "divergent offsets
can reorder locally but can never touch conservation."

Under A, that same map carries **the entire seat**. So:

> **A peer that restarts, or joins the mesh after a generation was seated, has no seat
> for any edge and reads `ev = committed / w` for all of them.**

Work it. A parent with two children, both correctly seated:

| child | seat | committed | true `ev` |
|---|---:|---:|---:|
| `old` | 0 (first generation) | 1000 | 1000 |
| `recent` | 1000 (seated at the front, correctly) | 5 | 1005 |

They are level; `recent` is 5 units *ahead*. A peer that witnessed the seating splits the
next 30 grants **18 / 12** in `old`'s favour — correct. A peer restarted under A reads
`ev(old) = 1000` and `ev(recent) = 5`, concludes `recent` is 995 units behind, and gives
it **30 of 30**. That is the §10.5 lifetime-credit violation the entire seating rule
exists to prevent, reintroduced *maximally* and *certainly* — on every restart, and on
every late join — in exchange for removing a *rare* error on an unconverged proposer.

Both numbers are asserted in `NewbornSeatPocTest`
(`candidateALosesTheSeatOnRestartAndHandsTheNewbornLifetimeCredit`).

This is not a wiring gap. The seat is **derived once, from an observation that can never
be repeated**: the state of the competing set at the instant this child joined it. Once
that instant passes, no amount of replicated state reconstructs it — `committed` alone
cannot distinguish "seated at 0, ran 1000" from "seated at 1000, ran 5". The seat is not
derived state that ought not be frozen; it is the **memo of an unrepeatable observation**,
and history has to be stored because history cannot be recomputed.

**This reframes #1713's own survey.** The survey is right that `initialVirtualTime` is
the only *derived* value in `records` — and right that its position is wrong. It draws
the wrong conclusion from it. The fix is not to stop storing the value; it is to **store
it somewhere its join resolves conflicts instead of preserving them.**

`heddleStatic` makes this worse, not better: it has no log and no persistence at all
(`heddleStatic` rebuilds `buildInitialLedger` from the bootstrap arguments and merges the
rest over the seam), so a restarted static node under A would seat *every* edge at the
origin.

### 4.1 The newborn discriminator — dissolved, not solved

#1713 names the discriminator as A's open sub-problem, and it is genuinely hard:
`refreshWakeClamps` deliberately declines to clamp on first sight (`HeddleNode.kt:170-176`
— an id that has never been observed is never treated as a wake, because forfeiting a
deficit accrued under another peer's scheduling is "a penalty this peer has no standing
to impose"). Distinguishing a newborn from a first-sighted pre-existing edge needs a
log anchor that `heddleStatic` cannot have, and the data-plane `lifecycle` read is
explicitly **non-monotone** — a peer that merges a `delegate` counter before the edge's
`prepare` reads the ACTIVE default and then *regresses* to PREPARED
(`EntitlementLedger.kt`, `lifecycle`'s KDoc; verified). "First seen as PREPARED" is
therefore unreliable, exactly as #1713 says.

**It is not solved here, and it does not need to be.** The discriminator is a symptom of
§4: A needs to recognise the seating *moment* only because it refuses to record the
seating *result*. Record the result and the moment stops mattering — a peer that missed
it reads the answer instead of re-deriving it. §5 does that, and its seating predicate
(`effIssued(e) == 0`) is monotone and replicated, so it needs no non-monotone
`lifecycle` read at all.

The honest form of the answer: **a log-anchored discriminator would require a control→node
seam that does not exist, and this design declines to invent one — because the
recommended candidate removes the need for it.** No architectural fork is surfaced.

### 4.2 The rescue of A collapses into B's design class

§4 rejects A on a *fact about the current code* — `wakeOffsets` is in-memory and per-boot.
The obvious objection is that this is a fixable wiring gap: **persist the offsets, or
replicate them.** Run it out, because where it lands is the strongest form of the whole
argument.

- **Persist them locally.** The module has no durable store on either node type
  (`heddleStatic` rebuilds from its bootstrap arguments; a governed node replays the
  control log, which carries no offsets), so this is new infrastructure. Worse, it
  cannot work even in principle: local persistence preserves observations this peer
  *made*. It is silent about the **late-join** case, where the peer was not present at
  the seating and no local store can hold a record of something it never saw. Persistence
  fixes restart and leaves half the defect standing.
- **Replicate them.** Now the offsets are shared state written by many peers with
  legitimately different readings, so they need a **convergent, multi-writer join** — and
  the join must resolve conflicts rather than preserve them, or the divergence just moves.
  A per-edge, monotone, max-joined register in the replicated ledger is what that
  requirement *is*.

So the rescue of A **is** B's design class, arrived at from the other side. That sharpens
§4's claim into its final form:

> The objection to A is not really "history cannot be recomputed" — it is narrower and
> harder to escape: **any storable form of the seat must be join-convergent and
> replicated.** A is not a different design point from B; it is B with the storage
> omitted, and every repair to it re-derives B's requirements.

This is why §5's composition being refuted (§5.5) does *not* reopen A. The location
argument and the composition argument are independent, and only the second one failed.

---

## 5. Candidate B — a replicated per-edge seat register

#1696's sketch: CFS's `min_vruntime`, a monotone front held in the replicated ledger,
with the newborn seated at *activate* rather than propose. The direction is right, and
§5.1's *representation* survived review. The three refinements below were meant to make it
precise; **the second one (§5.2) is refuted** — see §5.5 before reading §5.2 as a
specification.

### 5.1 The representation

Add one component to `EntitlementLedger`:

```text
seats : Map<AttachmentId, Long>          join: componentwise maxOf
```

Structurally **identical to the existing `lifecycle` register** — a product of
max-registers, so it slots into the ledger's `piece` with no new merge machinery, and
`Lifecycle`'s "closure dominates activation" argument transfers verbatim. It stores a
`Long`, not a `Rational`: `Rational` is deliberately **not** `@Serializable`
(`Rational.kt:22-32`, so an unreduced value cannot arrive over the wire), and the `Long`
is what `virtualService` needs anyway. **#1687's `⌈V⌉` rule is therefore retained, not
retired** — it is precisely the conversion from the exact front to the stored `Long`.
(#1713 claims A "makes #1687 unnecessary"; under B it stays load-bearing. See §8.)

`AttachmentRecord.initialVirtualTime` is **retained on the wire and dropped from the read
path**: `HeddlePolicy.virtualService` reads a seat supplied on `PolicyEdge`, not the
record's field. §7 makes the field unconstructible.

### 5.2 Who writes it, and when — **REFUTED, see §5.5**

> ⚠ **This subsection is the part of the design that did not survive review.** It is kept
> as written so §5.5 and §11 have something concrete to point at. Do not implement it.

> **Every peer, every scheduling round, for every child edge that is ACTIVE, demanding,
> and has `effIssued(e) == 0`:**
> `seats[e] ← max(seats[e], ⌈front(parent, excluding = the unseated edges)⌉)`

Four things fall out of that one line.

**It satisfies rule (a) by construction.** The slot is authored by the *data plane* —
the same plane that authors the counters `front` is computed from — into a
**join-convergent max-register**, by *many independent writers*. A stale proposer's low
reading is not authoritative; it is one bump, dominated by any better-informed peer's.
The PoC's `candidateBLetsABetterInformedPeerDominateAStaleProposersSeat` runs #1713's
worst case — a view that has merged three of five siblings and computes "a plausible
front over three, with no null, no error, nothing anomalous" — and shows the converged
peer's higher reading wins, in either merge order.

**The seating predicate is monotone and replicated.** `effIssued(e) > 0` is a `GCounter`
read: once any peer has delegated down the edge, the fact converges and never regresses.
That is the discriminator §4.1 could not build locally, and it is available *without* a
log, so `heddleStatic` gets it too. A prepared edge cannot receive delegation at all
(`EntitlementLedger.kt:646` — `delegate` returns `null` unless the edge is ACTIVE), so
`effIssued > 0` implies ACTIVE was reached.
The non-monotone `lifecycle` read is not load-bearing: a transient ACTIVE↔PREPARED flip
only makes a peer skip a bump for one round.

**The ratchet terminates.** Once the edge is served, every peer stops bumping. This is
why #1696's per-*parent* `min_vruntime` register is not sufficient on its own: a shared
parent-level front never freezes, so a newborn reading it live would be pinned at the
front forever and could never legitimately fall behind. CFS copies `min_vruntime` **into
the entity** at wakeup for exactly this reason. The seat must be per-edge; a per-parent
register would be redundant state.

**It unifies newborn-seating with the §10.6 wake clamp — A's headline benefit, kept.**
An edge that is activated and never served keeps being seated at the current front,
which is precisely what §10.6 does to an idler. Gating the bump on *demanding* (not
merely ACTIVE) is what keeps it quiet: an idle edge produces no delta, and the ratchet
window is "demanding but never yet served", which is normally one round. A peer with no
holdings still bumps — `HeddlePolicy.isDemanding`'s KDoc already argues that a peer with
nothing to delegate must still be able to answer who is competing, "it may be the one
creating a generation", which is this.

### 5.3 What it costs — the ratchet bias, and where the bound actually holds

B's one new bias: a **max** over peers' locally-computed weighted **means** can sit above
the converged mean, penalising the newborn. #1696 flags it; nobody had bounded it. The
derivation:

> A weighted mean never exceeds its largest element, so `storedFront ≤ max_c ev(c)`.
> EEVDF's own eligibility rule serves only children with `ev ≤ V`, and a grant advances
> the winner by `q / w`, so no child ever sits more than `q / w` above the mean.
> Therefore **`0 ≤ storedFront − V ≤ q / w_min`**.

**Revision 2: this bound is falsified as a bound on the *seat*, and the derivation is
sound only for what it literally computes.** The distinction matters and it is where the
design went wrong:

- **What the derivation bounds — and this survived review, with a positive-control test
  (§11).** The gap between one peer's locally-computed front and the true front, *at one
  instant*, over a fixed demanding set with no `release`, **when the writer's
  `effIssued(e) == 0` reading is true rather than merely unmerged.**
- **What it does not bound — the seat overshoot.** The stored seat's error is not
  `front − V`; it is `front_at_write − seat_correct`. Once the write gate misfires on an
  edge that has *already been served* (§5.5, F1), that quantity grows with the service the
  edge accumulated, because the read path adds the seat to a `committed` counter the
  writer never saw. **Overshoot is linear in the staleness window, unbounded in service.**
  The adversarial PoC measures 20× the claimed bound in a single write, scaling ~4× with a
  4× window.

The register maxes over **two** axes — across peers *and* across time — and only the peer
axis was bounded. That is the specific analytic error, and it is worth naming precisely
because the corrected `q / w_min` claim is still true and still useful; it simply is not
a statement about the stored seat.

Two residuals that do survive, both asserted in this document's own PoC:

- With a changing demanding set the front bound degrades to the ev-spread of the ACTIVE
  set — the same quantity §10.6's clamp already bounds, and the same "arbitrary penalty"
  case `HeddlePolicy.front`'s KDoc already names for a satisfied-and-ahead sibling.
- **Max-join corrects a stale-*low* reading, not a stale-*high* one.**
  `candidateBDoesNotCorrectAStaleHighSeatAndThatIsTheSafeDirection` demonstrates it. A
  view missing the siblings that are *behind* reads high, and the join keeps it. Bounded
  by the ev-spread and pointing the safe way — the same one-directional trade `ceil` and
  `front`'s max fallback already make.

### 5.4 What it deletes

B is not purely additive. It retires machinery that exists only to defend the frozen seat.
The review confirmed each deletion is real *as specified* — but note that all three are
downstream of "no seat travels through the log", so a §5.2 revision that reintroduces a
control-plane-written seat (§9's warning) would take some of this back:

- **`GovernedHeddleNode.prepareNeutral`'s `readIndex()` fence and applied-prefix gate**
  (`HeddleGoverned.kt:255-300`, landed in #1700) become dead. `Prepare` no longer carries
  a seat, so a stale proposer's `Prepare` is inert and there is nothing to fence.
  `prepareNeutral` collapses to `prepare`.
- **Both of that method's documented residuals** go with it — the unfenced partial-view
  case *and* the false-refusal-after-an-election case.
- **The *unavoidable* half of the two-proposer `RecordDivergence` hazard** dissolves: two
  honest proposers always had different fronts, and with no seat to differ on that source
  of divergence is gone. **Not the whole hazard** — two ungoverned proposers supplying
  different *intent* (a different `weight`) still union to a divergent set and starve the
  child exactly as today. Revision 1 over-claimed this; the qualifier is §11 finding 4.

Net replicated state: **one `Map<AttachmentId, Long>` added, one `Long` field made inert.**

### 5.5 What does not survive review — §5.2 is refuted

Two attacks were executed against §5.2 as specified, both with the real joins, and both
land. They are §13's own angles 2 and 4 — the two this document ranked most dangerous —
and the answer to each is worse than the question anticipated. Full record in §11;
independently re-verified against `main` before being written up here.

**F1 — the `effIssued == 0` gate is replicated but *stale-readable*, and the additive read
path double-counts.** The gate's virtue was supposed to be monotone convergence. It is
monotone; it is not *decidable* from a partial view. A peer missing only edge `e`'s own
`issued` slots — ordinary per-slot delta loss, which the order-free join is designed to
tolerate, or a one-way partition — still reads `effIssued(e) == 0` and bumps an **already
served** edge to the *current* front. The read path is `ev = seat + committed/w`, so when
that bump max-joins into a view that *does* have `committed(e)`, both terms are present
and the service is counted twice. The overshoot is `committed(e)` at heal.

The root cause is a real distinction I collapsed. **CFS takes its max on the *sum*** —
`vruntime = max(vruntime, min_vruntime)`, once, atomically, on a value that already
includes the entity's service. **§5.2 takes its max on the seat *addend*,** which is then
recombined with a counter the writer never observed. A max-register is only safe over a
quantity that is *complete* at the moment of writing, and `seat` is not: it is one term of
a sum whose other term the writer may not have.

Neither §5.3 proviso applies — the failure reproduces with zero ev-spread, a fixed
demanding set and no `release`. Direction is §10.5-safe (it starves the newborn rather
than crediting it), which is the small mercy, but it starves it by exactly its own served
history — the mirror image of the #1696 term B exists to remove — and max-join makes the
wrong value **permanent**.

**F2 — a relocation-receiving edge can never be seated.** Verified in the code directly:
`reconcileStranded` re-homes a strand by writing `issuedRelocIn(liveEdge)`
(`EntitlementLedger.kt:601`), and `effIssuedTotal(e) = counterValue(issued, e) +
counterValue(issuedRelocIn, e)` (`:178`). So the moment #1665's reconcile lands on a fresh
edge `t`, `effIssued(t) > 0` even though `issued(t) == 0` — and because both components
are `GCounter`s, the gate is false **forever, on every peer, in every merge order**.
`seats[t]` is never written, and both readings of an absent seat fail:

- *drop as candidate* ⇒ the child that #1665's relocation machinery exists to make whole
  becomes permanently unschedulable;
- *default to 0* ⇒ the entire relocated strand reads as committed service at the origin,
  which is §10.5 lifetime credit sized by the relocation (the PoC's 300-unit strand takes
  30/30 grants).

Whether an edge is ever seated therefore depends on **delta arrival order** — the reconcile
delta versus the child's demand, which rides a separate seam. That is not a bug in the
gate's threshold; it is the gate having no coherent meaning on a re-homed edge. **It also
couples this design to #1665**, which revision 1 did not account for at all.

**What this does and does not invalidate.** §4's location argument is untouched — it is an
argument about *where* the seat must live, and F1/F2 are both failures of *when to write
it* and *how to combine it on read*. Both look addressable inside B's class (bumping
`⌈front − committed_local/w⌉` would bound the double-count by the writer's own counter
staleness; the relocation case needs an explicit seating story for re-homed edges), but
that is a redesign of §5.2, not a wiring detail, and it must be reviewed on its own before
anything is built. **Parked here deliberately rather than patched in place** — a repair
drafted in the same pass that was just refuted has not earned confidence.

---

## 6. Candidate C — derive at apply time

#1713 calls it doubly dead. Both halves verified on `main`:

1. The control plane decides against the **log-pure projection**, whose counters are
   empty by design — `ControlPlane.kt:503-505` says so in as many words ("the projection
   has no data-plane counters, so its `outstanding` is 0"), and the retire gates depend
   on it staying that way. It cannot recompute even the all-ACTIVE mean.
2. Deriving from each peer's *data plane* at apply is non-deterministic per peer, so
   `decideAndApply`'s "decides identically on every peer" contract breaks and `records`
   forks. `ControlPlane.kt:522-529` already refuses to read the data plane at apply for
   `Reconcile`, "per BLOCKER-1".

**C stays dead — and B makes the question moot rather than answering it.** Under B
nothing seat-shaped travels through the log at all, so there is nothing to validate at
apply time.

---

## 7. Closing the escape hatch

Both `HeddleNode.prepare(record)` (`HeddleNode.kt:232`) and
`GovernedHeddleNode.prepare(record)` (`HeddleGoverned.kt:194`) accept a hand-built
`AttachmentRecord`, so an arbitrary seat stays constructible whichever fix lands. Under B
a hand-built seat is already **inert** — nothing reads the field. That is
impossible-by-convention. To make it impossible-by-construction:

Turn `AttachmentRecord` into a non-`data` `public class` with a **four-argument** public
constructor (`id`, `parent`, `child`, `weight`) and a custom `KSerializer` that declares
the legacy `initialVirtualTime` element, writes `0`, and ignores it on read. A `data
class` with the field demoted to `internal` is **not** sufficient — `copy()` and
`componentN()` re-expose it. There is precedent in the module: `WeightSerializer` is
already a normalizing custom serializer routed through `of()`, adopted for exactly this
class of reason (#1647). `AttachmentRecord.neutral` / `neutralInitialVirtualTime` lose
their `parentVirtualTime` parameter (the rounding rule moves to the register write).

**The non-`data` class MUST hand-implement `equals`/`hashCode` over all four fields.**
This is an implementer trap, not a detail: `records` is a `Map<AttachmentId,
Set<AttachmentRecord>>` deduplicated by set union, and `validate` raises
`RecordDivergence` on `recs.size > 1` (`EntitlementLedger.kt:866`). With the identity
equality a non-`data` class gets by default, one duplicate delivery after a serializer
round-trip makes **every** edge read as divergent — and a divergent record is dropped by
`EntitlementLedger.record`, so the child stops competing permanently. Dropping `data` to
close one hole would silently open a worse one.

After that change there is **no public API through which a seat can be expressed at all**.

### 7.1 The wire-compatibility constraint, stated precisely

#1713 says the field must be retained, not removed. The mechanism is confirmed:
`AttachmentRecord` is `@Serializable` and CBOR-encoded on two surfaces — the Quilter's
`EntitlementLedger` deltas, and `ControlEnvelope` inside Raft log entries — and heddle
uses the **default** `Cbor` instance with no `ignoreUnknownKeys` (unlike `:kuilt-raft`,
which sets it: `RaftEngine.kt:67`). A governed node replays from index 1 on boot, and
`applyEntry` **silently skips** an undecodable envelope
(`runCatchingCancellable { … }.getOrNull()` then `if (envelope == null) return`,
`ControlPlane.kt:395-409`). So deleting the field would make historical `Prepare` entries
vanish from the projection on replay: **silent topology loss.**

"Retained as a constant" therefore means precisely: *the serialized form keeps an
element under the same name and type, whose value is written as `0` and discarded on
read.* It does **not** mean the Kotlin property must survive — a custom serializer
satisfies the constraint while removing the property, which is what §7 recommends.

That silent skip deserves its own issue independent of this design: an undecodable
control entry is a data-loss event being swallowed.

---

## 8. #1713's carriage table, verified against `main`

Checked line by line. **The table is sound in substance** — every claim about `Retire`,
`Reconcile`, `Prepare`, and the intent-carrying commands reproduces on `main` (line
numbers have drifted a few lines since it was written):

| claim | verdict |
|---|---|
| `Retire`'s witness joins via `patch.delta.piece(witness)`, safe by (a) alone | ✅ `ControlPlane.kt:516` |
| `Reconcile` is shape-gated (`carriesOnlyRehomeSlots`) but magnitude-unfenced | ✅ `ControlPlane.kt:533-537`; the residual is documented in `HeddleGoverned.kt` and `EntitlementLedger.reconcileStranded` |
| `Depart` is self-service-gated | ✅ `ControlPlane.kt:566-580` |
| `Reconcile.liveEdge` is apply-validated (`live.single() == liveEdge`) | ✅ `ControlPlane.kt:534` |
| `Prepare`'s seat lands in `records`, per-id set union, unvalidatable at apply | ✅ `EntitlementLedger.kt:143`, `prepare()` at `:395` |
| the only production read of `initialVirtualTime` is `HeddlePolicy.virtualService` | ✅ exhaustive grep — everything else is KDoc or construction |
| the derived `lifecycle` read is non-monotone (ACTIVE→PREPARED) | ✅ documented in its own KDoc |

**Three corrections**, all because `main` moved under the issue (#1700 merged at
`af7599b1`, 2026-07-26 20:53 ET, hours after #1713 was filed):

1. **"No `parentVirtualTime` / `front` / `isDemanding` / `wakeOffsets` /
   `refreshWakeClamps` exists on `main`" — now false.** All five ship. §10.6 is wired:
   `policyEdges` passes `wakeOffsets[…]` into `PolicyEdge.virtualOffset`
   (`HeddleNode.kt:502`), not the default `ZERO`.
2. **"#1700 ships no test for `prepareNeutral` at all" — now false.** Both branches are
   pinned (`prepareNeutralRefusesAnOriginSeatOnAStaleEmptyView`,
   `prepareNeutralSeatsAGenuineFirstGenerationAtTheOrigin`). The recommendation to land
   #1700 with a fenced fallback was taken, and correctly.
3. **"A makes #1687 unnecessary" — not under the recommended fix.** B keeps `⌈V⌉` as the
   Rational→`Long` conversion at the register write, and keeps it for the same fairness
   reason. See §5.1.

One correction to **#1696**: its per-parent `min_vruntime` register is not sufficient
alone; the seat must be frozen per-edge (§5.2), as CFS also does.

---

## 9. The tension, resolved

#1713 states it: **the fairness-optimal seating front is not derivable from replicated
state, and what is derivable is not fairness-optimal.**

Both halves are true, and B does not refute either. It changes the question. The
fairness-optimal front is a *local reading* — it always was, and `HeddlePolicy.front`'s
KDoc already says so ("not derivable from replicated state, by design"). What B changes
is not *how the front is computed* but **what happens to the reading afterwards**:

- Today the reading is **carried and frozen** into a conflict-preserving join. One peer's
  guess becomes everyone's permanent fact.
- Under B the reading is **published into a conflict-resolving join**, by every peer that
  can still see the edge unserved. Many local readings, one converged answer, and the
  convergence rule is `max` — which is the direction the design has already decided is
  safe every other time it faced this choice.

The tension is real; it is not a barrier. A value that cannot be *derived* can still be
*agreed*, provided it is agreed in a lattice that resolves rather than preserves. **§5.5
does not touch this conclusion** — F1 and F2 are failures of *when to write* the agreed
value, not of the claim that it must be agreed in a resolving lattice.

**No architectural fork is surfaced by the design as written.** B needs no control→node
seam, no new `ControlCommand`, and no control-plane change of any kind — the seat is pure
data plane, and no control gate reads it.

> ⚠ **The §5.2 revision may surface one.** F2's cleanest-looking repair — have the control
> plane write the seat for a re-homed edge, where the reconcile is already serialized — is
> a **carried proposer-local magnitude**, which is the precise class #1713 exists to ban
> and the class `Reconcile`'s own unshipped residual sits in. Re-gating the predicate
> instead reopens F1. So the revision has a real chance of forcing an architectural
> decision that this document does not. That is a reason to give it its own pass, not to
> guess at it here.

One policy decision does need @keddie regardless:

> **Fork (policy, not architecture): is a breaking `EntitlementLedger` wire change
> acceptable?** #1713 says the field must be retained. But
> `heddle-ledger-relocation-design.md` §8 already set the opposite precedent for this
> exact module — "a **breaking wire-format change**. Pre-1.0 explicitly permits this …
> No migration shim needed — bump the ledger and let snapshots/E2E re-derive." Both
> cannot be standing policy. If the relocation design's posture holds, slice 3 is a
> two-line field deletion; if #1713's holds, it is the ~40-line legacy serializer of §7.
> Cheap either way — but it should be decided once, in one place, rather than per PR.

---

## 10. Blast radius, sizing, slicing — **conditional on a §5.2 revision**

> ⚠ **Not a work plan yet.** §5.5 refutes the write gate S2 would implement, so S2 cannot
> be sized honestly until §5.2 is redesigned, and S1's register is only worth building if
> the revision keeps it (it probably does — the *location* argument is what survived). The
> slicing is retained because the **shape** of the work is unlikely to change: a lattice
> component, then a read path, then the API surface. Treat the numbers as a floor.

**Three sub-issues, in order. Each is independently reviewable and independently safe.**

| slice | scope | prod LOC | test LOC | risk |
|---|---|---:|---:|---|
| **S1 — the register** | `seats: Map<AttachmentId, Long>` on `EntitlementLedger`: field, `piece` max-join, `of`/`ZERO`/`equals`/`hashCode`/`toString`, a `seat(id)` read and a `seat(id, value)` patch mutator. Nothing reads it yet. | ~80 | ~80 | low — a new lattice component, laws inherited |
| **S2 — the read path** | `PolicyEdge.seat: Long` beside `virtualOffset`; `HeddlePolicy.virtualService` reads it instead of `record.initialVirtualTime`; `HeddleNode` seats at the top of `schedule` (extend `refreshWakeClamps`); unseated edges excluded from `front` and dropped as candidates. | ~80 | ~140 | medium — scheduling behaviour |
| **S3 — close the hatch** | non-`data` `AttachmentRecord` + legacy `KSerializer` (or the field deletion, per §9's fork); retire `prepareNeutral`'s fence and `neutral`'s `parentVirtualTime` parameter; docs / `agent-cookbook.md` / Writerside / `@sample` sync. | ~60 | ~60 | low — API surface, no new behaviour |

**~220 LOC prod total.** For comparison, the machinery S3 *deletes* is roughly 50 lines
of fence plus two documented residuals.

**Reads that change:** `HeddlePolicy.virtualService` (signature — a public API break,
which pre-1.0 permits) and everything downstream of it, which is `pick`, `front`,
`wakeOffset` and `HeddleNode.policyEdges`. `BoundMetrics`, `validate()`, conservation and
every gate are untouched — they never read the seat.

**Not consensus behaviour.** B changes no `ControlCommand` and no `decideAndApply` branch,
so `:kuilt-heddle:build --rerun-tasks` is the gate; the full-`build` rule for
consensus-*behaviour* changes does not bind. S3 touches the wire format, so it does need
the full `./gradlew build` for the Android/Native variants and `:examples`.

---

## 11. Verification plan — **conditional, like §10**

> The §11 findings become **regression vectors of the revised design**, and are the first
> two entries below. A revision that cannot turn F1 and F2 green is not a revision.
>
> - **F1** — a writer with a stale `effIssued(e)` and fresh sibling slots must not be able
>   to move `e`'s effective virtual service past its siblings by more than the stated
>   bound, at any staleness window. The adversarial PoC's 30- and 120-round windows are the
>   vectors; both must stop scaling.
> - **F2** — after `reconcileStranded` re-homes onto a fresh edge `t`, `t` must be
>   schedulable *and* seated at the front, with the outcome independent of whether the
>   reconcile delta or the demand update arrives first. Both arrival orders must be tested.


- **The §2 measurement becomes a regression assertion**: the lag-25 scenario, which today
  yields 18/30, must yield 10/30 under B at every lag in `{0, 5, 25, 200}`.
- **The §4 restart case becomes a test**: seat a generation, tear the node down, rebuild
  from the merged ledger, and assert the schedule is identical. This is the assertion that
  fails under A and is the reason A is rejected — it must exist so nobody re-proposes A.
- **Multi-writer convergence**: three peers with different partial views bump the same
  seat; assert the merged register equals the best-informed reading, in every merge order
  (idempotent / commutative / associative on the new component, via the existing
  join-law suite, which already parameterises over components).
- **The ratchet bound**: property test asserting `0 ≤ storedFront − V ≤ q / w_min` over
  randomized weights and demand patterns with a fixed demanding set.
- **`heddleStatic` parity**: the same seating behaviour with no log present — the case
  A could not serve at all.
- **Chattiness**: assert an ACTIVE-but-idle edge produces **no** seat delta (the bump is
  gated on demanding), and that a served edge stops producing them.
- **Wire**: an `AttachmentRecord` encoded before S3 still decodes after it, through the
  real `Cbor` instance — not a hand-rolled one.

---

## 12. PoC

`kuilt-heddle/src/commonTest/.../NewbornSeatPocTest.kt` (clearly marked **POC —
throwaway**, self-contained, does not touch `EntitlementLedger`, `HeddleNode` or
`HeddlePolicy`) re-implements §7.3's four selection steps over the module's real
`Rational`, and asserts: #1696's measurement (§2); A's restart and late-join failures
(§4); B's max-join self-correction, restart-safety, monotone ratchet and the one
direction it does *not* correct (§5); and the `q / w_min` bound (§5.3). Ten tests, all
green under `:kuilt-heddle:jvmTest`.

It validates the **arithmetic** of §4–§5, not the eventual Kotlin wiring. Following the
lesson recorded in the relocation design's §10 — whose PoC modelled counters as mutable
`Long`s and thereby hid the join, which the adversarial review then exploited — the seat
register here is modelled with its **real `maxOf` join and an explicit merge-order test**,
not as a mutable variable.

**And it was still not enough** (§11). It modelled the *register* faithfully and the
*counters* abstractly — `committed` is a plain field — so no test in it can express "a
writer whose view of this edge's counter is stale", which is precisely the gap F1 comes
through. The adversarial PoC on `review/1713-seat-design-adversarial` models **both**
joins, and both findings fell out at once. The rule worth carrying forward: *the join you
model loosely is the join the attack comes through.*

Which assertions still stand: §2's measurement, §4's restart and late-join failures, and
the max-join/monotonicity properties are unaffected by §5.5 — they are statements about
*location* and about the front, not about the write gate. The
`candidateBsRatchetBiasIsBoundedByOneQuantumAtTheSmallestWeight` test is **correct but
misnamed**: it verifies the front-gap bound in its clean regime, which is not a bound on
the seat. Rename it if the file survives a revision.

Both PoCs are throwaway; delete before any implementation lands.

---

## 13. Adversarial review record (2026-07-26)

A Fable adversarial pass re-modelled §5.1–§5.2 under the **real joins** — per-slot max
counters mirroring `EntitlementLedger.mergeEdgeCounters`, and a componentwise-max seat
register — rather than this document's own PoC abstractions, and executed the angles
revision 1 had named but not answered. Artifacts: branch
`review/1713-seat-design-adversarial` (`SeatDesignAdversarialPocTest.kt`, four tests green
under `:kuilt-heddle:jvmTest`) and the review comment on PR #1740. Every finding below was
**re-verified against `main` by this author** before being recorded; the line citations are
the ones that check out on the rebased branch, not the ones in the review comment (which
predate #1730/#1731/#1735).

**Verdict on the location argument: it SURVIVED, and was strengthened.** The pass ran the
one counter this document had not — *persist or replicate `wakeOffsets`* — and found it
collapses into B's own design class (§4.2 now records this). The sharper form of §4's claim
is the reviewer's, not this author's: any storable form of the seat must be
join-convergent. **Verdict on the composition: REFUTED.** §5.2's write gate fails on both
of the two angles this document itself ranked most dangerous.

| # | finding (severity / confidence) | what broke | executed? | answered by |
|---|---|---|---|---|
| 1 | **Stale-`effIssued` ratchet double-counts committed service** (high / high) — §13 angle 2, previously unanswered | §5.2's predicate is replicated but **stale-readable**. A writer missing only edge `e`'s own `issued` slots still reads `effIssued(e) == 0` and bumps a *served* edge to the moved front; the additive read path `ev = seat + committed/w` then recombines that seat with a counter the writer never saw. Merged overshoot `= committed(e)` at heal — **linear in the staleness window, not `q/w_min`** — with zero ev-spread, fixed demanding set and no `release`, so neither §5.3 proviso applies. Root cause: **CFS maxes on the *sum*** (`vruntime = max(vruntime, min_vruntime)`, atomically); **§5.2 maxes on the seat *addend***. A max-register is safe only over a quantity complete at write time. | ✅ 2 tests + positive control | **NOT ANSWERED.** §5.2 marked refuted; §5.3's bound corrected to its true regime. A repair (bump `⌈front − committed_local/w⌉`) is *sketched only* in §5.5 and deliberately not designed here. |
| 2 | **A relocation-receiving edge can never be seated** (high within #1665 scope / high on the mechanism) — §13 angle 4 confirmed as a hole | `reconcileStranded` writes `issuedRelocIn(liveEdge)` (`EntitlementLedger.kt:601`) and `effIssuedTotal = counterValue(issued, e) + counterValue(issuedRelocIn, e)` (`:178`), both `GCounter`s — so after a re-home the gate is false **forever, everywhere, in every merge order**, and `seats[t]` is never written. Both readings of an absent seat fail: *drop as candidate* ⇒ the child #1665 exists to make whole is permanently unschedulable; *default 0* ⇒ §10.5 credit sized by the strand (300-unit strand ⇒ 30/30 grants). Seating becomes an artifact of delta arrival order. | ✅ | **NOT ANSWERED.** Recorded in §5.5. Couples this design to #1665, which revision 1 did not account for. |
| 3 | S3's non-`data` `AttachmentRecord` must hand-implement `equals`/`hashCode` (low / high) | §7 specified a non-`data` class but not equality. `records` dedupes by set union and `validate` fires `RecordDivergence` on `recs.size > 1` (`EntitlementLedger.kt:866`); with identity equality, one duplicate delivery after a serializer round-trip makes every edge divergent-and-dropped. | argued | ✅ §7, as an explicit MUST with the failure spelled out. |
| 4 | "Dissolves the two-proposer `RecordDivergence` hazard" over-claimed (low / high) | Byte-identical records need identical **intent**. Two ungoverned proposers with different `weight`s still union to a divergent set and starve the child. B removes the *unavoidable* divergence (two honest proposers always had different fronts); intent divergence remains. | argued | ✅ §5.4, qualified. |

**Verified, no finding** (recorded so a later pass need not redo it): the conservation and
determinism story — `initialVirtualTime`'s only production read really is
`HeddlePolicy.virtualService`, and `BoundMetrics` / `validate()` / `LedgerConflict` / the
conservation identity have zero references, so nothing in a control gate would read
`seats`. The deletion claims of §5.4 — `fenceReadIndex` has exactly two call sites
(`HeddleGoverned.kt:269`, `:389`), and under B the `prepareNeutral` one and both its
documented residuals are genuinely dead while the `reconcile` fence is untouched.
`effIssued` monotonicity, and the `heddleStatic` / log-free claims. §5.3's derivation is
correct *in the regime it states*, confirmed by a positive-control test — which is exactly
what makes finding 1 a scoping error rather than an arithmetic one.

**The methodological lesson, which is the transferable part.** Revision 1's PoC modelled
the seat register with its real `maxOf` join and an explicit merge-order test — a
deliberate improvement on the relocation design's mutable-`Long` PoC, and it caught real
things. It still missed F1 and F2, because it modelled the *register* faithfully and the
*counters* abstractly: `committed` was a plain field, so no test could express "a writer
whose view of this edge's counter is stale". **The join you model loosely is the join the
attack comes through.** The adversarial PoC modelled per-slot counters *and* the register,
and both findings fell out immediately.

---

*Deliverable for #1713 and #1696, revision 2 — parked after adversarial review. The
location argument (§4, §4.2) is settled and should be treated as durable; §5.2 is refuted
and needs its own design pass before any implementation. The strictly-safe present state is
to change nothing: #1700's fence closes the stale-**records** case and leaves the
partial-view and propose→activate-lag cases standing, as it already documents.*
