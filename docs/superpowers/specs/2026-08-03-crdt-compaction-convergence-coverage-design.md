# Teaching the convergence generator to clean up after itself

**Issue:** [#2019](https://github.com/tractat-us/kuilt/issues/2019) · **Date:** 2026-08-03 ·
**Status:** design, not yet implemented. Every number below was measured on this branch and the
measurement scripts are described in "How the figures were produced".

## What this changes, in plain language

When several people edit the same thing on different devices, kuilt keeps a little history of who
did what so the devices can agree later. That history would grow forever, so each data type has a
tidy-up step that throws away the parts everybody has already seen and nobody can still refer to.

kuilt has a randomised test that plays out edits on three make-believe devices, then hands them to
each other in every possible order and checks they all end up identical — right down to the exact
bytes. It is the net that catches "these two devices agree, but they'd write themselves to disk
differently", which is the kind of disagreement that is invisible until it isn't.

That net has a hole: **the test never runs the tidy-up step.** So anything the tidy-up produces
falls straight through. Twice now a real defect has been caught only because somebody wrote a
one-off test aimed directly at it. This design closes the hole, and — the harder half — proves the
hole is actually closed rather than merely appearing to be.

The rest of this page gets technical.

## The premise, verified

#2019 claims no convergence generator reaches `compact()`. Confirmed two ways:

- `compact` appears nowhere in `:kuilt-conformance`'s sources except three KDoc mentions in
  `RaftStorageConformanceSuite`, which are about Raft log compaction and unrelated.
- Reverting each of the three canonicalisation mechanisms in turn on current `main` leaves
  `RgaConvergenceTest`, `FugueConvergenceTest` and `MovableTreeConvergenceTest` **green**. The
  generator is structurally blind, not merely lucky.

**Scope that claim precisely: the gap is the *generator's* coverage, not total absence.** Each of
the three mechanisms is pinned somewhere — by the dedicated tests #1957 and #2013 wrote in
`:kuilt-crdt`'s `commonTest` precisely because the generator could not see the defect. That is the
whole complaint: every future compactable type starts with zero generator coverage and needs its own
hand-written test, which is the cost this design removes.

Two of #2019's incidental figures are off, in the direction that makes this cheaper:

- **16 bound suites, not ~19** — all `internal` classes in
  `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/convergence/`. A repo-wide
  search for `CrdtConvergenceSuite` / `CrdtConvergenceHarness` outside `commonMain` finds nothing
  else; no module outside `:kuilt-conformance` subclasses either.
- **`JsonCrdt` has no `compact()`.** It overrides `causalDots()` and its KDoc says nested arrays
  "do not participate in the `Rga.compact` … GC path". The compactable set is exactly `Rga`,
  `Fugue`, `MovableTree`.

## The crux: one hook is not enough

#2019 proposes a **post-merge** hook — merge everything, then compact — on the reasoning that a
sound cut only exists once every replica's history is present. That hook works, and it is necessary.
It is not sufficient, and the way it fails is exactly the failure mode the issue itself warns about:
a generator that *reaches* `compact()` while its assertions would pass with the mechanism stubbed out.

Three separate mechanisms make a compaction record encode canonically. Reverting each in turn and
re-running both candidate hooks gives a clean split (byte mismatches out of the 192 permutation
comparisons per type — 32 seeds × 6 folds). **These verdicts were taken on the JVM.** The
*unmutated* baseline — reach counts, convergence, byte agreement — was re-run on `macosArm64` and is
identical to the byte, so the mechanism is target-stable; but the mutation matrix itself must still
be re-taken on `wasmJsBrowserTest` and `macosArm64Test` before a binding merges, and the plan
requires it:

| mechanism (and the issue that shipped it) | Hook A — post-merge | Hook B — pre-merge |
|---|---|---|
| `Rga`/`Fugue` `Compact.positions` **map** order — `CanonicalMapSerializer` inside `RgaOpSerializer` / `FugueOpSerializer` (#1978) | **RED** — Rga 101, Fugue 23 | green — 0 |
| `MovableTree.compactedDots` **set** order — `CanonicalSetSerializer` (#1957) | green — 0 | **RED** — 100 |
| order **between** several `Compact` ops in one log — `compareCompactPositions` (#713) | green — 0 | **RED** — Rga 101, Fugue 23 |

**Hook A alone pins one mechanism of three.** Ship only what #2019 describes and
`MovableTree.compactedDots` stays exactly as unpinned as it is today, while the coverage metric
happily reports that compaction was reached on 30 of 32 seeds. That is the entire hazard of this
issue, reproduced inside its own proposed fix.

The two hooks are complementary because they vary different things:

- **Hook A** merges first, so the *tombstone set* the compaction predicate walks was built by
  `Set.plus` in fold order. `gcIds` inherits that order, `positions` inherits it from `gcIds`, and
  one `Compact` op's map order therefore depends on the merge order. That is the #1978 axis.
- **Hook B** compacts each replica *before* any merge, so each replica mints its own `Compact` op
  from its own single-author history — an order fixed at mint time and identical under every later
  fold. What varies instead is the **merge of already-compacted states**: `compactedDots +
  other.compactedDots` and the position of each `Compact` op in the unioned op set. Those are the
  #1957 and #713 axes.

Hook A cannot reach the merge-of-compacted-states path because after it runs there is nothing left
to merge. Hook B cannot reach the fold-dependent-tombstone-set path because each replica compacts a
history only it authored. Neither is redundant.

### Why `MovableTree` is invisible to Hook A specifically

Worth naming, because the general shape recurs. `MovableTree.compact` selects its droppable ops by
filtering `log`, and `log` is a `List` kept sorted by `(ts, replicaId)` — canonical regardless of
fold order. So `droppedDots`, and therefore the freshly-compacted state's `compactedDots`, is
already in canonical order and `CanonicalSetSerializer` has nothing to do. Measured: **0 of 32
seeds** show any variation in that set's iteration order across the six permutations, at both 8 and
16 ops per replica. `Rga` and `Fugue` derive theirs from a `Set`, and vary on 25/32 and 7/32.

The general rule this instance teaches: **a field is only pinned by a byte assertion if the value's
iteration order can actually differ between the states being compared.** Reaching the code that
writes the field is not the same as reaching the disagreement.

Corroboration, found after the measurement rather than before it: the dedicated test #1957 had to
write for this field builds `compactedTreeFor(alice)` and `compactedTreeFor(bob)` and merges them —
two independently-compacted replicas, which is **exactly Hook B's construction**. The author of that
test reached for the only shape that works. This design generalises it instead of hand-writing it
once per type.

## The two hooks, precisely

The harness gains one per-type adapter and runs it in two phases. The existing phase is untouched.

```
buildReplicas(seed)  ──►  r0, r1, r2                       (unchanged)
                            │
     ┌──────────────────────┼──────────────────────────────┐
     │                      │                              │
 PHASE 0 (today)        PHASE A (new)                  PHASE B (new)
 fold every             fold every permutation,        compact each replica alone,
 permutation,           then compact to stable         then fold every permutation
 assert equal + bytes   assert equal + bytes           assert equal + bytes
```

**Phase 0 is preserved byte-for-byte.** This is not politeness: a new gate placed ahead of an older
one is how an older guard's coverage silently drops to zero (#1872, and #2002 on this very track).
The compaction phases *add* assertions over *additional* states; they never replace Phase 0's, and
the audit in "Testing" re-proves Phase 0 still fails when a non-compaction canonical serializer is
reverted.

**Both phases compact to stable** — loop until `compact()` returns `null` — mirroring
`RgaGcCoordinator.compactUntilStable`, which exists because removing one tombstone can unblock its
structural predecessor. Looping is what makes a chain reachable rather than only its tail.

**Phase B asserts one property Phase 0 and Phase A cannot: that compaction is merge-safe.** Three
independently-compacted replicas must still converge, and to the same bytes. Measured 0 failures
today across 32 seeds × 6 permutations × 3 types on both JVM and `macosArm64` — so it starts green,
which is the point: it is a standing guard, not a bug report.

### What the hook is allowed to observe

The adapter receives **only** `(state, stableCut, frontierMax, delivered)`. It never sees the seed,
the permutation, the replica index, or the other replicas. Three consequences, all deliberate:

1. It **cannot tailor a cut per permutation** and manufacture agreement. The cut is derived by the
   harness from the state alone.
2. It is a **pure function of state**, so two `equal` states compact to `equal` states — which is
   what makes the equality assertion hold while leaving the byte assertion free to discriminate.
3. It **cannot mutate Phase 0's inputs**, because it is handed values, and every CRDT here is
   immutable.

The adapter's whole body, per type, is one call to that type's own `compact(...)` plus unwrapping
the returned pair. There is nowhere for a per-type fudge to live.

## The cut, and why it is reachable

A cut nothing reaches pins nothing. The harness derives the cut, not the test, and derives it as
the quantity `Quilter` actually publishes:

```
delivered = contiguousFrontier(state.causalDots())
stableCut = delivered
frontierMax = delivered
```

`causalDots()` is on `Quilted`, so this is generic over every type. `contiguousFrontier` is the
per-author highest gap-free `seq`, and `Quilter` derives its own `deliveredLocal` from exactly that
call:

<!-- verbatim from kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt#contiguousFrontier -->
```kotlin
internal fun contiguousFrontier(dots: Set<Dot>): VersionVector {
    val seqsByAuthor: Map<ReplicaId, Set<Long>> = dots
        .groupBy(keySelector = { it.replica }, valueTransform = { it.seq })
        .mapValues { (_, seqs) -> seqs.toSet() }
    val highWaters = seqsByAuthor.mapValues { (_, seqs) -> contiguousHighWater(seqs) }
    return VersionVector.of(highWaters)
}
```

Setting `S = F = delivered` is not a convenient fiction. It is literally what `recomputeCut`
computes in two reachable topologies, because `rows` always contains `self`:

<!-- verbatim from kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt#recomputeCut -->
```kotlin
val self = _deliveredLocal.value
val rows = knownPeers.map { frontiers[it] ?: VersionVector.EMPTY } + self
val fLive = rows.fold(VersionVector.EMPTY) { acc, vv -> acc.ceilWith(vv) }
val sMin = rows.reduce { acc, vv -> acc.floorWith(vv) } // rows always non-empty (self)
```

- **Phase A's cut** is the cut of a **fully converged room**: every peer has gossiped a `Delivered`
  equal to every other's, so `min == max == self`. This is the modal steady state of any quiet
  `Quilter` mesh, not an edge case.
- **Phase B's cut** is the cut of a **solo peer**: `knownPeers` is empty, `rows == [self]`, so
  `sMin == fLive == self` and the GC coordinator fires. A device editing offline before anyone else
  joins is the ordinary offline-first case, and the harness's independent per-replica histories are
  already a model of exactly that.

Two soundness notes:

- **The derivation errs toward under-compaction, never over.** `contiguousFrontier` stops at the
  first gap in an author's `seq` run, so a generator quirk that leaves a hole yields a *lower* cut
  and *less* compaction. It cannot manufacture a cut that authorises dropping something a real
  execution would keep. A quirk therefore costs coverage — which the vacuity gate catches — rather
  than soundness.
- **Phase B is sound because the histories are disjoint.** #2019 warns that compacting with a
  replica's own delivered VV "violates barrier condition 2". That is right in general and does not
  bite here: replica `Rᵢ`'s history contains only `Rᵢ`'s ops, so no other replica can hold a
  concurrent op referencing one of them — `Rga`'s condition 4 (no surviving successor) and
  `Fugue`'s (no surviving tree anchor) are evaluated over a set no peer can add to behind the
  compactor's back. Empirically: 0 convergence failures across 32 seeds × 6 permutations × 3 types,
  on JVM and `macosArm64`. Should a future generator make replicas share an author id, that premise
  breaks — so the harness KDoc states it as a precondition and the plan pins it with an assertion.

## Vacuity accounting

The measurable question is not "did `compact()` run" but "could this assertion have failed". Four
distinct ways the answer is no, and a distinct guard for each.

**V1 — the hook never fires.** `compact()` returns `null` on every seed; the phase is a no-op.
Guard: an asserted floor on the number of seeds that produced a state-changing compaction.

**V2 — it fires, but too small to discriminate.** A `Compact` carrying **one** id has exactly one
key order; a one-element `compactedDots` has one element order. A canonical-ordering defect is
undetectable at size 1. Guard: an asserted floor of **≥2** on the largest single compaction step.
This is the metric that would have caught nothing had it been stated as "compaction happened".

**V3 — the mechanism is inert on this path.** The `MovableTree` case above: everything fires,
everything is big enough, and the serializer under test has no order to fix. **No in-suite
assertion can detect this** — the suite is green either way, which is the definition of the problem.
Guard: a **recorded mutation matrix**, produced by reverting each mechanism in turn and confirming
the phase that claims to pin it goes red. The matrix in "The crux" above *is* that artefact for the
current three mechanisms; the plan requires it be re-run on `wasmJsBrowserTest` and
`macosArm64Test`, per type, before the binding slice merges.

**V4 — the new phase un-pins the old one.** Phase 0's byte assertion is #1957's coverage for every
non-compaction field in the zoo. Guard: keep Phase 0 unchanged, and re-run one Phase-0 mutation
(revert a canonical serializer that has nothing to do with compaction) to confirm it still fails.

### The technique, stated as a requirement

**Reproduce the vacuity before asserting it is gone.** Neuter the mechanism first, on unmodified
`main`, and confirm the existing tests stay green. Only then write the assertion and confirm the
same neutering turns it red. Asserting first and mutating afterwards tempts you to accept a green
suite as evidence; it is evidence of nothing until you have seen it be red for the right reason.
This is what caught #2002, and it is what caught the `MovableTree` hole in this design's own first
draft — the post-merge-only hook was written, looked complete, and was killed by the measurement.

### The thresholds are floors on reach, not proof of teeth

Measured on this branch, 32 seeds, `replicaCount = 3`:

| type | ops/replica | Phase A: seeds compacting | Phase A: max dropped in one step | Phase B: seeds with ≥2 replicas compacting |
|---|---|---|---|---|
| `Rga` | 8 (today) | 32/32 | 8 | 25/32 |
| `Fugue` | 8 (today) | 22/32 | 3 | 7/32 |
| `Fugue` | 16 (proposed) | 26/32 | 4 | 19/32 |
| `MovableTree` | 8 (today) | 30/32 | 10 | 28/32 |

`Rga` and `MovableTree` need **no generator change**. `Fugue` at its current 8 ops reaches Phase B
on only 7 seeds; raising it to 16 lifts that to 19 and its Phase-A reach from 22 to 26. That is a
strict strengthening — more ops can only add coverage — but it changes an existing load-bearing
test's shape, so the plan requires re-running `Fugue`'s #1978 mutation at the new size (measured:
23 → 67 byte failures, i.e. still red and more so).

Pin each floor at roughly three-quarters of the measured value and record the measured value beside
it. An exact pin turns every future generator tweak into a mechanical number-bump, which is how a
coverage floor rots; a floor with stated headroom lets a reviewer see that 17/32 against a floor of
16 and a measurement of 25 is a regression even though it passes.

**Dropped from an earlier draft:** a `maxStepsInOneRun ≥ 2` metric, intended to cover the
several-`Compact`-ops-in-one-log axis. Measurement showed that axis is pinned by Phase B, not by
Phase A's multi-step chains — reverting `compareCompactPositions` leaves Phase A green. A metric
that maps to no mechanism is itself vacuous, so it is gone rather than kept for symmetry.

**Kept, with its justification stated rather than assumed:** `MovableTree` asserts Phase A floors
even though Phase A pins none of its *current* mechanisms. That is not the same mistake. Phase A
still asserts something with content for `MovableTree` — that compacting the merged state converges
and encodes identically under every fold — and it is a standing net for a mechanism this type does
not have yet. The dropped metric measured an axis proven inert for **every** type; this one is
load-bearing for two of three and forward-looking for the third.

## The `commonMain` API change

All of it is additive and defaulted, in `:kuilt-conformance`'s `commonMain`.

```kotlin
/** One compaction step: the resulting [state] and how many ids it dropped. */
public class CompactionStep<S>(public val state: S, public val droppedCount: Int)

/**
 * Invokes one compaction of [state] at a harness-derived cut, or returns `null` when nothing
 * qualifies. Receives the state and the cut and nothing else.
 */
public fun interface CrdtCompactor<S> {
    public fun compactOnce(
        state: S,
        stableCut: VersionVector,
        frontierMax: VersionVector,
        delivered: VersionVector,
    ): CompactionStep<S>?
}

/** What the compaction phases actually reached, accumulated across one harness's runs. */
public data class CompactionCoverage(
    public val runs: Int = 0,
    public val postMergeRunsWithCompaction: Int = 0,
    public val postMergeMaxDroppedInOneStep: Int = 0,
    public val preMergeRunsWithTwoOrMoreCompacting: Int = 0,
)
```

`CrdtConvergenceHarness` gains a trailing `compactor: CrdtCompactor<S>? = null` constructor
parameter and a `public val compactionCoverage: CompactionCoverage` accumulator. `run(seed)` keeps
its current return — the **pre-compaction** converged state — so no existing caller's meaning moves.

`CompactableCrdtConvergenceSuite<S> : CrdtConvergenceSuite<S>()` adds the coverage test and three
`abstract`/`open` floors. A compactable type binds by extending it; the three floors are per-type
because the distributions differ by type.

### Blast radius: 16 suites, 3 files changed

All 16 are `internal` classes in one directory of `:kuilt-conformance`'s `commonTest`:

`BoundedCounterConvergenceTest` · `EphemeralMapConvergenceTest` · **`FugueConvergenceTest`** ·
`GCounterConvergenceTest` · `GSetConvergenceTest` · `IntMaxConvergenceTest` ·
`JsonCrdtConvergenceTest` · `LWWMapConvergenceTest` · `LWWRegisterConvergenceTest` ·
**`MovableTreeConvergenceTest`** · `MVRegisterConvergenceTest` · `ORMapConvergenceTest` ·
`ORSetConvergenceTest` · `PNCounterConvergenceTest` · **`RgaConvergenceTest`** ·
`TwoPhaseSetConvergenceTest`

The three in bold change. **The other 13 compile and behave identically** — the new parameter is
trailing and defaulted, and the coverage test lives on a subclass they do not extend. That the
13 are untouched is asserted, not assumed: the harness slice lands with no bindings at all and must
leave all 16 green before any type binds.

### Can the API change be avoided or narrowed?

- **Avoided: no.** `compact()` is not on `Quilted`, its signature differs per type, and the harness
  lives in `commonMain` where the per-type test lambdas cannot reach it.
- **Rejected narrowing — put `compact()` on `Quilted`.** It would push GC vocabulary
  (`stableCut`/`frontierMax`/`delivered`) into the interface that ~20 zoo types implement, for the
  benefit of 3. `Quilted` deliberately carries exactly one optional capability (`causalDots()`) and
  its KDoc explains why the default keeps it non-breaking; a second, far larger one is a worse trade.
- **Rejected narrowing — let each test supply its own cut** (`compactor: (S) -> S?`). Simpler
  signature, but it hands every test the power to pass a cut no execution reaches, which is the
  exact failure #2019 names. The harness owning the cut is the design, not an implementation detail.
- **Accepted narrowing — trailing defaulted parameter plus a subclass.** Zero edits at 13 of 16
  binding sites and no conditional skip anywhere: a type either extends the compactable suite and
  gets the coverage assertions, or it does not and nothing pretends otherwise.

### One production change, and why it belongs here

`contiguousFrontier` is `internal` to `:kuilt-quilter`, and `:kuilt-conformance` does not depend on
that module. Rather than duplicate it — two copies of the quantity whose *sameness* is the entire
"reachable, not synthetic" argument — move it to `:kuilt-crdt` beside `VersionVector`, as
`VersionVector.Companion.contiguous(dots: Set<Dot>)`, and have `:kuilt-quilter` delegate. It is pure
`Dot`/`VersionVector` logic with no coroutine or `Seam` content; it belongs there on its own merits.

This is the one judgement call worth a second opinion — see "Open question" below.

## What is deliberately unchanged

- **Phase 0**, exactly as it stands.
- **`run(seed)`'s return value** — the pre-compaction converged state.
- **`Rga`'s and `MovableTree`'s generators.** Only `Fugue`'s `opsPerReplica` moves, 8 → 16.
- **The dedicated tests from #1957 and #2013 stay.** A generator is a net over a *class*; a named
  test is a legible failure message for one *instance*. Deleting `MovableTree.compactedDots`' or
  `Rga`/`Fugue` `Compact.positions`' dedicated tests would trade a one-line diagnosis for a byte
  diff. Keep both; the generator's job is future types, not retiring past tests.
- **`JsonCrdt`** — no `compact()`, nothing to bind.

## Risks

| Risk | Handling |
|---|---|
| Phase B's disjoint-history premise breaks if a future generator gives two replicas the same author id | Stated as a precondition in the harness KDoc and pinned by an assertion that the replicas' `causalDots()` author sets are pairwise disjoint |
| A compactable type is added and nobody binds it | It must extend `CompactableCrdtConvergenceSuite` deliberately; the plan adds the reminder to `docs/op-log-crdt-compaction.md` beside the existing per-type notes |
| Coverage floors get bumped downward to make a red go green | Each floor's KDoc carries the measured value and date, so a bump is visibly a regression rather than a tuning |
| Runtime cost | Two extra phases at 6 permutations × 32 seeds × 3 types; the whole probe suite ran in a few seconds on `jvmTest` and about a minute on `macosArm64Test` including native compilation |
| JVM-only verification | `jvmTest` is meaningful here (these fields are `LinkedHashSet`/`LinkedHashMap`-backed, insertion-ordered on the JVM too) but is **not** sufficient — the mutation matrix must be re-run on `wasmJsBrowserTest`, and `macosArm64Test` locally since Apple targets are nightly-only |

## Testing

1. **Reproduce the vacuity first** (V3), on unmodified `main`, per mechanism, and record it.
2. Land the harness with no bindings; all 16 suites green, no test file edited.
3. Per type: bind, measure the coverage distribution, pin floors at ~75 % of measured, run that
   type's mutation matrix on JVM + wasmJs + macosArm64, paste the reds into the commit.
4. Re-run one Phase-0 mutation (V4) to confirm the older pin still fails.
5. Full `./gradlew build detektAll --rerun-tasks` before the last PR leaves draft.

## Success criteria

1. Reverting **any** of the three canonicalisation mechanisms turns a *convergence* suite red, on
   `wasmJsBrowserTest` and `macosArm64Test`. Today all three leave all three convergence suites
   green — they are caught only by hand-written tests in `:kuilt-crdt`.
2. The coverage assertions fail if compaction stops being reached, or stops being large enough to
   discriminate — proven by mutating the floors, not by reading them.
3. Phase 0's assertions still fail under a non-compaction canonical-serializer mutation.
4. Three independently-compacted replicas converge, byte-identically, under every fold order.
5. Thirteen of sixteen bound suites are textually unchanged.

## Open question for Iain

**Should `contiguousFrontier` become public API on `:kuilt-crdt`?** It is currently `internal` to
`:kuilt-quilter`, and the harness needs the same quantity. Moving it makes the harness's cut
*provably* the production cut rather than a look-alike, and the function is pure `VersionVector`
logic that arguably belongs beside `VersionVector` regardless. The cost is a slightly wider public
surface on the module whose selling point is having almost none.

**Recommendation: move it.** The alternative is two implementations of the exact quantity whose
sameness this design's central argument rests on, which is a worse trade than one small, well-named
public function. If the answer is no, the fallback is to duplicate it in `:kuilt-conformance` and
add a cross-module golden test pinning both to the same answers on a shared fixture.

## How the figures were produced

Three throwaway probes in `:kuilt-conformance`'s `commonTest`, run on `jvmTest` and
`macosArm64Test`, deleted before commit:

- **reach** — merge, compact to stable, histogram the steps and dropped counts across 32 seeds at
  8/12/16/24 ops per replica.
- **order variation** — for each seed, compare the raw iteration order of `Compact.positions.keys`
  (`Rga`, `Fugue`) and `MoveTreeCompact.droppedDots` (`MovableTree`) across all six permutations.
  This is what showed `MovableTree` at 0/32 and killed the post-merge-only design.
- **both hooks, byte-compared** — run Phase A and Phase B end to end with the real wire serializers,
  counting equality and byte failures, with each mechanism reverted in turn.

The plan restates these as required implementation steps rather than as history, because a
measurement nobody can re-run is a claim, not a receipt.
