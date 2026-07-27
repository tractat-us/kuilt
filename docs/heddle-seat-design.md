# Where a newborn's seat lives — settling #1713 and #1696

> **Status: design, under review. Not implemented.** This settles a question two issues
> ask in two different voices: when a new child joins the fair-share tree, *what decides
> where it starts*, and *where does that decision live*? #1713 recommends removing the
> stored answer; #1696 recommends storing a better one. They cannot both be right.
> Companion to [`heddle-design.md`](heddle-design.md) §7.2/§10.5/§10.6 and
> [`heddle-ledger-relocation-design.md`](heddle-ledger-relocation-design.md), whose
> structure this document follows.
>
> **Verdict: #1696's direction, not #1713's** — refined in three places, and for a
> reason neither issue states. The decisive finding is in §4.

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

---

## 5. Candidate B — a replicated per-edge seat register

#1696's sketch: CFS's `min_vruntime`, a monotone front held in the replicated ledger,
with the newborn seated at *activate* rather than propose. The direction is right. Three
refinements make it precise and, in the process, buy back everything A was reaching for.

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

### 5.2 Who writes it, and when

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

### 5.3 What it costs — the ratchet bias, quantified

B's one new bias: a **max** over peers' locally-computed weighted **means** can sit above
the converged mean, penalising the newborn. #1696 flags it; nobody has bounded it. Bound:

> A weighted mean never exceeds its largest element, so `storedFront ≤ max_c ev(c)`.
> EEVDF's own eligibility rule serves only children with `ev ≤ V`, and a grant advances
> the winner by `q / w`, so no child ever sits more than `q / w` above the mean.
> Therefore **`0 ≤ storedFront − V ≤ q / w_min`**.

That is the **same order as #1687's `0 ≤ ⌈V⌉ − V < 1`** — a bias the design has already
examined and accepted — and it is in the §10.5-safe direction: it can only ever make the
newborn *give up* a share, never claim one. Compare with the term it replaces: #1696's
lag deficit is *unbounded* and points the *forbidden* way.

Two honest provisos, both asserted in the PoC:

- The bound assumes a fixed demanding set and no `release`. With a changing demanding
  set it degrades to the ev-spread of the ACTIVE set — the same quantity §10.6's clamp
  already bounds, and the same "arbitrary penalty" case `HeddlePolicy.front`'s KDoc
  already names for a satisfied-and-ahead sibling.
- **Max-join corrects a stale-*low* reading, not a stale-*high* one.**
  `candidateBDoesNotCorrectAStaleHighSeatAndThatIsTheSafeDirection` demonstrates it. A
  view missing the siblings that are *behind* reads high, and the join keeps it. This is
  a residual, not a hole: it is bounded by the ev-spread and points the safe way — the
  same one-directional trade `ceil` and `front`'s max fallback already make.

### 5.4 What it deletes

B is not purely additive. It retires machinery that exists only to defend the frozen seat:

- **`GovernedHeddleNode.prepareNeutral`'s `readIndex()` fence and applied-prefix gate**
  (`HeddleGoverned.kt:255-300`, landed in #1700) become dead. `Prepare` no longer carries
  a seat, so a stale proposer's `Prepare` is inert and there is nothing to fence.
  `prepareNeutral` collapses to `prepare`.
- **Both of that method's documented residuals** go with it — the unfenced partial-view
  case *and* the false-refusal-after-an-election case.
- **The two-proposer `RecordDivergence` starvation hazard** dissolves: records become
  byte-identical, so there is nothing to diverge on. (A's claimed bonus, kept.)

Net replicated state: **one `Map<AttachmentId, Long>` added, one `Long` field made inert.**

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
*agreed*, provided it is agreed in a lattice that resolves rather than preserves.

**No architectural fork is surfaced.** B needs no control→node seam, no new
`ControlCommand`, and no control-plane change of any kind — the seat is pure data plane,
and no control gate reads it. One policy decision does need @keddie:

> **Fork (policy, not architecture): is a breaking `EntitlementLedger` wire change
> acceptable?** #1713 says the field must be retained. But
> `heddle-ledger-relocation-design.md` §8 already set the opposite precedent for this
> exact module — "a **breaking wire-format change**. Pre-1.0 explicitly permits this …
> No migration shim needed — bump the ledger and let snapshots/E2E re-derive." Both
> cannot be standing policy. If the relocation design's posture holds, slice 3 is a
> two-line field deletion; if #1713's holds, it is the ~40-line legacy serializer of §7.
> Cheap either way — but it should be decided once, in one place, rather than per PR.

---

## 10. Blast radius, sizing, slicing

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
so `:kuilt-heddle:build detektAll --rerun-tasks` is the gate; the full-`build` rule for
consensus-*behaviour* changes does not bind. S3 touches the wire format, so it does need
the full `./gradlew build` for the Android/Native variants and `:examples`.

---

## 11. Verification plan

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
not as a mutable variable. Delete before the real implementation lands.

---

## 13. Adversarial review record

*Pending — a Fable adversarial pass is scheduled against this design before implementation.*

The most productive angles to attack, named in advance:

1. **§5.3's bound.** It assumes a fixed demanding set and no `release`. Construct a
   demand-churn interleaving that makes the ratchet exceed `q / w_min` materially, and
   check whether the degraded ACTIVE-set-spread bound really holds.
2. **The ratchet window.** Between `activate` and the first grant, every peer writes. Can
   a partitioned peer with a *fresh front* but a *stale `effIssued`* keep ratcheting a
   seat long after the edge has been served elsewhere, and how far?
3. **§4's claim of unrepeatability.** Is there *any* replicated quantity from which the
   seat can be reconstructed after the fact? If one exists, A returns.
4. **Interaction with the relocation work.** A re-homed generation (#1665) moves counters
   between edges. What happens to the *seat* of an edge that receives a re-homed
   generation — does `effIssued(t) > 0` via `issuedRelocIn` freeze `t`'s seat correctly,
   or does it freeze a seat that was never computed?

Angle 4 is the one this design is least confident about and the one a reviewer should
start with.

---

*Deliverable for #1713 and #1696. If the reviewer rejects B, the strictly-safe fallback
is **not** A — it is to change nothing and keep #1700's fence, which closes the
stale-**records** case and leaves the partial-view and propose→activate-lag cases
standing, as it already documents.*
