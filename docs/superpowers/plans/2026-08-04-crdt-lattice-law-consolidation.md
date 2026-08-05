# Lattice-Law Consolidation — Implementation Plan

> **EXECUTED AND CLOSED, 2026-08-05.** All nine tasks shipped (#2132 · #2133 · #2135 · #2136 ·
> #2137 · #2142 · #2146 · #2147 · #2148), closing #2101. This plan is kept as the record of how the
> work was reasoned about, **not** as a description of the tree — for that, read the code.
>
> **Seven of its prescriptions turned out to be wrong, and each is corrected inline below** at the
> step that carried it, marked **CORRECTED (Task 9)**. Every correction was *measured* by the worker
> that hit it, not inferred. In summary:
>
> | # | where | what was wrong |
> |---|---|---|
> | 1 | cost baseline | `148.6 s` is a **box artifact**, not a gate — identical code has read 53–149 s. The gate is a same-box before/after **ratio**; the track landed at **0.891×** for 19 bindings instead of 16. |
> | 2 | Task 0 Step 2 | "use `ORMapConvergenceTest`'s own generator" — #2110 moved it onto delta mutators after this plan was committed; a `348de854^` type will not compile against it. |
> | 3 | Task 1 Step 2 | the prescribed sample is **inert** — 0 violations, not 12. The rule is *must not dominate*, not *must differ*. |
> | 4 | Task 0 Step 3 | the vacuity-control arm is **not uniquely specified**; four spellings, none reproducing the published triple count. The conclusion survives all four. |
> | 5 | Task 4 Step 4 | `LWWMap`'s 20 commutativity violations are **0** — unreachable, because its mutators join. The fix is preventive, not corrective. |
> | 6 | Task 6 Step 3 | the `L = 4` cost table was measured at `\|A\| = 3` and **does not generalise**; cost is `\|A\|ᴸ`. Shipped with a triple budget. |
> | 7 | Task 8 Step 4 | "leave `jqwik` in the catalog, `:kuilt-raft` uses it" — #2112 took `:kuilt-raft` off jqwik **46 minutes** after this plan was committed. |
>
> The pattern worth carrying forward: **five of the seven are a prescribed literal, a prescribed
> absolute, or a prescribed premise about another module** — the three things a plan cannot state
> without running them, and the three a worker is most likely to copy rather than check.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** One randomised lattice-law suite, running on every target, whose redness against a known
defect is **deterministic** rather than seed-luck, and whose generators **fail** when they stop
producing the shape the law needs. Delete the JVM-only jqwik surface without losing anything it
covered.

**Architecture:** `:kuilt-conformance`'s `CrdtConvergenceSuite`/`CrdtConvergenceHarness` (already in
`commonMain`, already carrying a causal-ancestor pool, both bracketings and byte assertions as of
#2099) becomes the one suite. It gains: a named-op alphabet shared by a randomised and an
exhaustive-small pass, constructed critical shapes, measured vacuity floors, the other two laws, and
op-log failure reporting. `QuiltedConformanceSuite` stays as the cheap smoke surface; per-type
`*Test.kt` stays and absorbs surface 1's non-law properties.

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization (CBOR), kotlin-test. Gradle 9.4.1, JDK 21.
**No jqwik after Task 8.**

**Spec:** [`docs/superpowers/specs/2026-08-04-crdt-lattice-law-consolidation-design.md`](../specs/2026-08-04-crdt-lattice-law-consolidation-design.md)
· **Issue:** [#2101](https://github.com/tractat-us/kuilt/issues/2101)

> **Three things #2101's body says do not survive execution — do not plan against them.**
> 1. Its **surface-1 and surface-2 blind spots are stale.** #2099 merged 11 minutes after the issue
>    was filed and closed both for associativity. Do not rebuild causal pools, bracketings or byte
>    assertions; they ship today, on every target.
> 2. **"Assert all three laws" reds `main`.** Commutativity over the causal pool is violated 20× on
>    `LWWMapConvergenceTest` and 52× on `LWWRegisterConvergenceTest`, because those generators
>    violate `LWWRegister.set`'s documented tag-uniqueness precondition. Fix the generators (Task 4)
>    before asserting the law (Task 5).
>    **CORRECTED (Task 9): only the `LWWRegister` half is real** — 52 at seeds `0..15` reproduced
>    exactly, and 226 over the full pool. `LWWMap` is **0**: its mutators join, so a duplicate-tag
>    write is dropped and the losing value never enters the pool. See Task 4 Step 4.
> 3. **No JVM/all-targets split is needed.** wasmJs is JVM-class; Kotlin/Native is 10–15× slower on
>    the cubic loop but still affordable at the current parameters. Do not propose one.
>
> Update the issue body when Task 1 lands.

## Global Constraints

- **JDK/toolchain:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` in every
  non-interactive shell. In an `isolation: "worktree"` agent that `source` is refused — use
  `export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem` instead.
- **`explicitApi()` is enforced.** `:kuilt-conformance`'s suites live in `commonMain`, so every new
  declaration is genuinely public API with consumer-facing KDoc.
- **`detektAll`, never bare `detekt`** — bare `detekt` is `NO-SOURCE` here and is a false green.
- **`jvmTest` is not sufficient, and here it is actively misleading.** The byte assertions have
  near-zero discriminating power on the JVM (`HashMap` bucket order); Kotlin/Native and wasmJs see
  the defect. Every task that touches an assertion runs `macosArm64Test` **and** `wasmJsTest`.
- **Never use `git stash`** — `refs/stash` is repo-global across every linked worktree. Copy files to
  a scratch path and restore from the copy.
- **Sample `uptime` immediately before any timing you intend to quote**, and quote it alongside. The
  box runs concurrent agents; load swung between 2 and 62 while this plan's figures were taken.
- **Never use the word "chore".**
- **Commit messages end with:** `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
- **Backticks do NOT disarm closing keywords in commit messages** — a squash turns the PR body into
  one. Use **"part of #2101"** everywhere. #2101 stays open until Task 9.

---

## File Structure

| File | Responsibility |
|---|---|
| `kuilt-conformance/src/commonMain/.../convergence/CrdtConvergenceHarness.kt` | **Modify (Tasks 1, 2, 5, 6).** Critical shapes, floors, other laws, op log. |
| `kuilt-conformance/src/commonMain/.../convergence/CrdtConvergenceSuite.kt` | **Modify (Tasks 1, 2, 5, 6, 7).** New `@Test`s; eventual rename to `LatticeLawSuite`. |
| `kuilt-conformance/src/commonMain/.../convergence/LatticeOp.kt` | **Create (Task 2).** `LatticeOp`, `OpKind`, `VacuityFloors`. |
| `kuilt-conformance/src/commonTest/.../convergence/*ConvergenceTest.kt` (16) | **Modify (Tasks 2, 3, 4).** Alphabet, critical shapes, retiring ops, precondition. |
| `kuilt-conformance/src/commonTest/.../convergence/{CausalDotSet,CausalDotMap,DotContext}ConvergenceTest.kt` | **Create (Task 3).** The three orphan types. |
| `kuilt-conformance/src/commonMain/.../QuiltedConformanceSuite.kt` | **Modify (Task 1).** `samplesReAssertAfterRetirement` guard. |
| `kuilt-conformance/src/commonTest/.../ORMapConformanceTest.kt` and siblings | **Modify (Task 1).** The missing re-put sample. |
| `kuilt-crdt/src/jvmTest/kotlin/us/tractat/kuilt/crdt/property/**` | **Delete (Task 8).** 15 files. |
| `kuilt-crdt/src/commonTest/.../{RgaTest,PNCounterTest}.kt` | **Modify (Task 8).** Re-home 5 behavioural properties. |
| `kuilt-crdt/build.gradle.kts` | **Modify (Task 8).** Remove jqwik, `useJUnitPlatform()`, capability resolution. |
| `gradle/libs.versions.toml` | **Modify (Task 9, not 8).** Delete `jqwik` — #2112 took `:kuilt-raft` off it. |

**Dependency order:**

```
Task 0  (baseline, blocking)
  ├─→ Task 1  (surface 3 sample + guard)          ── independent, ships first
  ├─→ Task 1b (merge the two associativity passes) ── independent, ships first, BUYS THE BUDGET
  └─→ Task 2  (alphabet + critical shapes)
        ├─→ Task 3  (three orphan bindings)   ─┐
        ├─→ Task 4  (fix the 4 gap generators) ─┤
        └─→ Task 6  (exhaustive-small pass)   ─┤
                                                ├─→ Task 5  (floors + other laws)
                                                └─→ Task 7  (rename + docs)
Task 3 ∧ Task 5  ──────────────────────────────────→ Task 8  (delete jqwik)
Task 8  ───────────────────────────────────────────→ Task 9  (issue body, re-measure, close)
```

**Parallelism:** Tasks 1 and 1b are independent of everything and of each other, and should ship
immediately — Task 1 is a two-line change with a measured 0→12 effect, Task 1b frees 18% of the
Kotlin/Native budget every later task spends. Tasks 3, 4 and 6 are fully parallel once Task 2 lands:
separate files, no shared symbol. **Task 5 must land after Task 4** or `main` goes red. **Task 8 must
land after Task 3** or deleting jqwik loses three types.

**The cost baseline every task is measured against** — `:kuilt-conformance:macosArm64Test`, summed
over 16 bindings, from the results XML:

| test | native total | share | catches the reintroduced #2086? |
|---|---|---|---|
| `associativeJoinsEncodeIdentically` | **120.5 s** | 81% | **no** |
| `pieceIsAssociativeOverReachableStates` | 27.1 s | 18% | yes, 8/16 seeds |
| `convergesAcrossSeeds` | 1.0 s | <1% | no |
| `convergesAtSeedZero` | 0.0 s | — | no |
| **total** | **148.6 s** | | |

> **CORRECTED (Task 9). The gate is a ratio, not `148.6 s`.** That absolute is a box artifact and
> must never be used as a threshold. Seven independent measurements of **identical code** read
> **148.6 / 77.0 / 79.2 / 60.3 / 62.5 / 53.0 / 74.3 s** — a 2.8× spread with no code change between
> them. What *is* portable is the structure, and it reproduced: encode dominates the bill (81%
> predicted, 76.9% re-measured), and Task 1b's predicted ~18% saving landed at 17.2%.
>
> **The gate every task should have been measured against: a same-box, same-day before/after
> ratio.** Task 9 took the pair by extracting the pre-track tree with `git archive 440098f6` into a
> scratch directory and running both — no checkout, no second branch, no stash:
>
> | | native total | bindings | per binding | load before |
> |---|---|---|---|---|
> | pre-track (`440098f6`) | 74.30 s | 16 | 4.64 s | 1.27 |
> | post-track (`126cf269`) | **66.18 s** | **19** | **3.48 s** | 1.54 |
>
> **0.891× the cost, for 19 bindings instead of 16 and four laws instead of two** — 25% cheaper per
> binding. Affordable because Task 1b returned the duplicated join pass; the largest addition (Task 6
> at `L = 4`, under its triple budget) costs 3.03 s across all 19.

---

### Task 0: Record the baseline — MANDATORY AND BLOCKING

> **Do not start Task 2 until Task 0's verdicts are recorded.** No product code lands here, which is
> exactly why it looks skippable. Every "and now it catches it" later is meaningless without a
> recorded red, and the design's headline numbers are unverifiable without this harness existing
> again. It also stops a reviewer re-litigating from #2101's body, which describes the pre-#2099 tree.

**Files:** one throwaway test source, deleted before commit.

- [ ] **Step 1: Rebuild `LegacyORMap`** — the pre-#2099 `ORMapEntry`/`ORMap` verbatim, renamed, in
  `kuilt-conformance/src/commonTest/.../convergence/`. Recover the source with
  `git show 348de854^:kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ORMap.kt`. The entry is a
  `DotSet` of tags beside **one** value, joined as
  `LegacyEntry(tags.join(other.tags, ctx, otherCtx), value.piece(other.value))`.

- [ ] **Step 2: Bind it to the UNMODIFIED harness** with `ORMapConvergenceTest`'s own generator, and
  record which landed tests red, per seed. Expected (measured 2026-08-04):

  > **CORRECTED (Task 9): "use `ORMapConvergenceTest`'s own generator" was stale before the first
  > task started.** #2110 moved that generator onto delta mutators (`put`/`remove` returning deltas)
  > after this plan was committed, so a `LegacyORMap` recovered from `348de854^` — whole-state
  > `put`/`remove` — does **not** compile against it. Recover the generator from the same commit as
  > the type, or hand-write a whole-state one. The verdicts below still reproduce.

| landed test | verdict |
|---|---|
| `runAssociativity`, seeds 0..15 | RED at **2, 4, 5, 7, 9, 11, 13, 14** — 8 of 16 |
| `runAssociativeEncoding`, seeds 0..15 | GREEN (it `continue`s past failed triples) |
| `run` (convergence), seeds 0..31 | **GREEN** |

  **If your run does not reproduce 8/16 with first-red at seed 2, stop and report** — the pool builder
  has changed and every figure downstream needs re-taking.

- [ ] **Step 3: Record the vacuity control.** Same broken type, generator with the `remove` branch
  deleted. Expected: **0 violations / 47,059 triples**, ancestry **28.4%**, non-trivial inner join
  **39.3%**. This is the arm that proves an ancestry floor is not enough; it is the single most
  load-bearing number in the design.

  > **CORRECTED (Task 9): "same generator, removes deleted" does not uniquely specify an arm.** It
  > leaves the branch structure open, and the branch structure decides where `POOL_LIMIT` truncates —
  > so it decides the triple count. Four spellings were measured (drop the branch; fold it into the
  > adjacent one; renumber the `when`; keep the branch but no-op it) and **none** reproduces both
  > 47,059 triples *and* the published percentages. **The conclusion is robust across all four** —
  > every arm finds 0 violations while ancestry and inner-join stay comfortably above any floor a
  > reviewer would set — which is what the design rests on. So for a control arm, **record the
  > spelling alongside the numbers**; a control that cannot be reconstructed exactly is a claim.

- [ ] **Step 4: Record per-target timings**, sampling `uptime` immediately before each. Expected order
  of magnitude: JVM ≈ wasmJs; macosArm64 10–15× slower on `runAssociativity`.

- [ ] **Step 5: Paste all four verdicts into Task 1's PR body.** Delete the probe; confirm
  `git status --short` is clean.

---

### Task 1: Surface 3 — the missing sample, and a guard so it cannot be deleted

Independent of every other task. Ship it first: measured 0 → 12 violations for one added sample.

**Files:** `QuiltedConformanceSuite.kt`, `ORMapConformanceTest.kt`, `JsonCrdtConformanceTest.kt`,
`ORSetConformanceTest.kt`, `CausalDotSetConformanceTest.kt`, `CausalDotMapConformanceTest.kt`,
`CausalDotFunConformanceTest.kt`, `MVRegisterConformanceTest.kt`, `LWWMapConformanceTest.kt`.

- [ ] **Step 1 (RED first):** with `LegacyORMap` temporarily present, confirm
  `ORMapConformanceTest.samples()` as it stands yields **0** associativity violations.
- [ ] **Step 2:** add one sample per causal binding — a state that **re-asserts a key it retired**:
  `withVotes.remove("votes").put(a, "votes", GCounter.of(a to 1L))`. The re-asserted value must
  **differ** from the original, or a lost contribution is indistinguishable from a kept one (#2099's
  finding, and the reason `ORMapLawsPropertyTest`'s generator varies its weight).

  > **CORRECTED (Task 9): the sample prescribed above is INERT — it yields 0 violations, not 12.**
  > The rule is not "the re-asserted value must **differ**"; it is that the retired value must not
  > **dominate** it. A `GCounter` join takes the max *per author*, so re-asserting any count under
  > the **same** author `a` lands both bracketings on the same number and the defect is invisible:
  > `a to 1L` → **0**, `a to 2L` → **0**. Re-assert under a **different** author — `c to 1L` — and
  > the same construction finds **12**. The shipped `retirementReAssertion()` KDoc carries this;
  > a step that prescribes a literal must be run before it is written down.
- [ ] **Step 3:** confirm the same construction now yields **12** violations against `LegacyORMap`.
- [ ] **Step 4:** add to `QuiltedConformanceSuite`:

```kotlin
/** A binding whose type can retire must show a sample re-asserting something an earlier sample retired. */
public open val retirementIsMeaningful: Boolean get() = false

@Test
public fun samplesReAssertAfterRetirement() { /* … */ }
```

  Default `false` so grow-only types (`GSet`, counters, `IntMax`) are unaffected; set `true` on every
  causal binding.
- [ ] **Step 5:** mutation-verify the guard — delete the new sample and confirm
  `samplesReAssertAfterRetirement` reds. A guard that passes when the thing it guards is gone is
  decoration.
- [ ] **Gate:** `:kuilt-conformance:build --rerun-tasks`, plus `macosArm64Test` and `wasmJsTest`.

---

### Task 1b: Merge the two associativity passes — buys the budget for everything after

Independent of Task 1 and of Task 2. Ship early: it returns 18% of the Kotlin/Native test budget, and
every later task's cost should land against the smaller baseline rather than the larger one.

**Files:** `CrdtConvergenceHarness.kt`, `CrdtConvergenceSuite.kt`.

- [ ] **Step 1:** observe the duplication. `runAssociativity(seed)` and `runAssociativeEncoding(seed)`
  each call `causalPool(Random(seed))` and each compute `a.piece(b).piece(c)` and `a.piece(b.piece(c))`
  for every ordered triple. The second then adds two CBOR encodes. The pool build and the joins are
  done twice, ~45,797 triples per binding per pass.
- [ ] **Step 2:** fold into one loop — compare values; **only when they are equal**, compare bytes.
  Keep the two failure messages **distinct and distinctly worded**: an inequality is an associativity
  defect, a byte difference on equal values is a canonicality defect, and conflating them would make
  the next failure harder to read, not easier. Keep two `@Test` entry points so a failure still names
  which law broke.
- [ ] **Step 3:** re-measure `macosArm64Test` per-test totals from the XML. Expected: the 27.1 s of
  `pieceIsAssociativeOverReachableStates` largely disappears into the merged pass; total ≤ ~122 s.
  Quote `uptime` beside the number.
- [ ] **Step 4:** confirm no assertion changed — with `LegacyORMap` bound, the merged pass must still
  red on exactly seeds 2, 4, 5, 7, 9, 11, 13, 14.
- [ ] **Gate:** module build + `macosArm64Test` + `wasmJsTest`.

---

### Task 2: The named-op alphabet and constructed critical shapes

The structural core. Everything after this depends on it.

**Files:** `LatticeOp.kt` (create), `CrdtConvergenceHarness.kt`, `CrdtConvergenceSuite.kt`, all 16
`*ConvergenceTest.kt`.

- [ ] **Step 1:** create `LatticeOp<S>(name, kind, apply)` with `enum class OpKind { ASSERT, RETIRE }`.
  `OpKind`'s KDoc **must** carry the measurement that justifies it, or the next reader deletes it as
  ceremony and re-derives the byte-size proxy:

  > Whether an op retires is not expressible in the `Quilted` algebra — a removal moves *up* the
  > lattice exactly as an addition does. The cheap proxy (did the encoding shrink?) is a false
  > detector both ways: **62.1%** of `LWWRegister` steps shrink and `LWWRegister` has no removal at
  > all; **0.0%** of `TwoPhaseSet`, `Rga` and `MovableTree` steps shrink and all three remove.

- [ ] **Step 2:** widen `CrdtConvergenceHarness` to take `alphabet: List<LatticeOp<S>>` alongside the
  existing `gen`. Keep `OperationGenerator` working — derive it from the alphabet — so the 16 bindings
  migrate one at a time rather than in one commit.
- [ ] **Step 3:** add `criticalShapes: List<List<String>>` — words over the alphabet, applied on
  replica 0 as a **prefix** to `causalPool` before random exploration. Default for any binding with a
  `RETIRE` op: `[assertA, retire, assertB]`, `assertA ≠ assertB`.
- [ ] **Step 4:** assert each critical shape **changed the state at every step**. `ORMap`'s generator
  currently no-ops on **10 of 29** steps (remove of an absent key); a shape that no-ops is decoration.
- [ ] **Step 5 (the measurement this task exists for):** with `LegacyORMap` bound, confirm
  **64/64 seeds red** — and with the fixed `ORMap` bound, **0/64**. Both arms are required: the second
  is the control proving the prefix does not manufacture a red.
- [ ] **Step 6:** migrate all 16 bindings to declare an alphabet. Mechanical; each is ~10 lines.
- [ ] **Gate:** `:kuilt-conformance:build --rerun-tasks` + `macosArm64Test` + `wasmJsTest`. Report the
  `macosArm64Test` wall clock before and after — the prefix lengthens every pool, and the associativity
  loop is **cubic** in pool size (JVM: 14→107 ms, 20→305 ms, 28→875 ms, 40→2.61 s; ×10–15 on native).
  If `POOL_LIMIT` has to rise to fit the prefix, say so explicitly with the new timing.

---

### Task 3: Bind the three types jqwik uniquely reached

**Blocking for Task 8.** Without this, deleting jqwik *is* a coverage regression — the only place in
this design where that is true.

**Files:** `CausalDotSetConvergenceTest.kt`, `CausalDotMapConvergenceTest.kt`,
`DotContextConvergenceTest.kt` (all new).

- [ ] **Step 1:** all three are already `Quilted` and `@Serializable` (`DotContext` via
  `DotContextSerializer`, `Causal<S>` via its generated serializer), so binding is mechanical. Model
  each alphabet on the corresponding `*LawsPropertyTest.trajectoryFor` before deleting it.
- [ ] **Step 2:** `CausalDotSet`'s retiring op is "drop all dots, context unchanged"; `CausalDotMap`'s
  is per-key; `DotContext` is **grow-only** — it has no `RETIRE` op and must declare so rather than
  fake one.
- [ ] **Step 3:** report each binding's measured ancestry and concurrency rates in the PR body. A
  binding whose pool is a **total order** (0% concurrent pairs) is an `IntMax`-shaped free pass and
  must declare `totalOrder = true` deliberately — `IntMax` and `LWWRegister` both read 0.0% today.
- [ ] **Gate:** module build + `macosArm64Test` + `wasmJsTest`.

---

### Task 4: Fix the four generators that would red Task 5's floors

**Blocking for Task 5.** Landing floors before this makes `main` red.

**Files:** `LWWMapConvergenceTest.kt`, `EphemeralMapConvergenceTest.kt`, `ORMapConvergenceTest.kt`,
`LWWRegisterConvergenceTest.kt`.

- [ ] **Step 1 — `LWWMap`: add a retiring op.** Measured **0.0%** retiring steps today although
  `LWWMap.remove` exists. This is the #2100 vacuity shape on a live binding.
- [ ] **Step 2 — `EphemeralMap`: add a retiring op**, same reason.
- [ ] **Step 3 — `ORMap`: stop wasting the budget.** 10 of 29 steps are no-ops. Remove a key the state
  actually holds.
- [ ] **Step 4 — `LWWMap` and `LWWRegister`: honour the tag-uniqueness precondition.** Measured
  commutativity violations over the causal pool: **`LWWMap` 20, `LWWRegister` 52**, both from minting
  the same `(replica, timestamp)` with two different values. Derive the value deterministically from
  `(replica, timestamp, key)`, exactly as `LWWMapLawsPropertyTest` already does.

  > **CORRECTED (Task 9): `LWWMap`'s 20 violations do NOT reproduce — the count is 0**, in 12,950
  > pairs over seeds `0..63`, and *not* because the precondition was honoured (it was not; the
  > shipped `set` drew its value at random). The pool cannot **reach** the violation: `LWWMap`'s
  > mutators *join*, so a second write at an already-used tag is dropped and the losing value never
  > enters a pool state. `LWWRegister` *assigns* instead of joining, does reach it, and measured
  > **226** over the same pool — while the plan's own `LWWRegister = 52` at seeds `0..15`
  > reproduced exactly, so the generator is not in doubt, only the reachability claim.
  >
  > **So the `LWWMap` half of this step is preventive, not corrective.** Worth doing — the green was
  > an accident of the mutators and a future generator drawing timestamps again would lose it — but
  > #2101's body implies `main` is red today on `LWWMap` and it is not. Note the shape: a violation
  > count of 0 has two causes, "the law holds" and "the pool cannot get there", and only reading the
  > mutator tells them apart.

  **Do not add a per-binding commutativity waiver.** A waiver is a permanent green-by-declaration. The
  violation stays pinned where it belongs and already is — `LWWMapTest.oneTagCarryingTwoValuesCosts
  CommutativityNotAssociativity` and `MVRegisterTest.forkingOneReplicaBreaksCommutativityBut
  NotAssociativity`. Confirm both still exist and still pass before closing this task.
- [ ] **Step 5:** re-measure all four bindings' retirement and commutativity numbers; all must be
  ≥ 10% retiring and 0 commutativity violations.
- [ ] **Gate:** module build + `macosArm64Test` + `wasmJsTest`.

---

### Task 5: The vacuity floors and the other two laws

**Depends on Task 4.**

**Files:** `CrdtConvergenceHarness.kt`, `CrdtConvergenceSuite.kt`, `LatticeOp.kt`.

- [ ] **Step 1:** add `VacuityFloors(strictAncestorPairs, concurrentPairs, effectiveRetireSteps,
  maxNoOpSteps)` with defaults `15% / 15% / 10% / 25%`, and a `totalOrder` waiver for the concurrency
  floor only.
- [ ] **Step 2:** assert them, per binding, and **print the measured values on success as well as
  failure** — a floor whose actual value nobody can see is a floor nobody notices drifting.
- [ ] **Step 3 (the mutation that justifies the whole task):** delete the `RETIRE` branch from one
  binding's alphabet and confirm **only the retirement floor** reds. The ancestry and concurrency
  floors must be shown *not* to — that asymmetry is the entire reason `OpKind` exists (measured:
  ancestry 28.4% and inner-join 39.3% on the arm that finds 0 violations in 47,059 triples).
- [ ] **Step 4:** add commutativity, idempotence and LUB over the causal pool. Expected on `main`
  after Task 4: **0 violations everywhere.** Note in the KDoc that they buy nothing against #2086
  (measured `assoc=500, comm=0, idem=0, lub=0` on the broken type) — they are breadth, not depth.
- [ ] **Step 5:** extend the byte assertion to the commutativity pair. Measured today: 0 differences
  in 3,113–3,261 equal-valued pairs per binding, so it is free.
- [ ] **Gate:** module build `--rerun-tasks` + `macosArm64Test` + `wasmJsTest`, with wall clocks and
  `uptime` quoted.

---

### Task 6: The exhaustive-small pass — the shrinking replacement

Parallel with Tasks 3–5.

**Files:** `CrdtConvergenceHarness.kt`, `CrdtConvergenceSuite.kt`.

- [ ] **Step 1:** enumerate every word of length `1..L` over the binding's alphabet on **one** replica,
  keeping all intermediate states as the pool, checking both bracketings; return the **first** failing
  word, which is the shortest because shorter words were tried first.
- [ ] **Step 2:** verify against `LegacyORMap`. Expected exactly:

  > minimal counterexample = `[put(k0,4), remove(k0), put(k0,1)]`, length **3**, after **28 words**

  Anything longer means the enumeration is not breadth-first by length and the "minimal" claim is false.
- [ ] **Step 3:** `L = 4`, as a named constant whose KDoc carries the cost curve for a **green** run
  (the normal case), because raising it is the obvious future mistake:

| L | words | JVM | wasmJs | macosArm64 |
|---|---|---|---|---|
| 3 | 39 | 25 ms | 9.9 ms | — |
| 4 | 120 | 45 ms | 59 ms | **364 ms** |
| 5 | 363 | 165 ms | 313 ms | — |
| 6 | 1,092 | — | — | **15.2 s** |
| 7 | 3,279 | — | — | **65 s** |
| 8 | 9,840 | — | — | **193 s** |

  > **CORRECTED (Task 9): this table was measured at `|A| = 3` and does not generalise.** Word length
  > is not the cost — `|A|ᴸ` is. Live bindings run **1 to 6** ops wide, and at `L = 4` that spans
  > 12,120 ordered triples (`|A| = 3`) to **176,844** (`JsonCrdt`, `|A| = 6`). Uncapped, `JsonCrdt`
  > alone would have cost ~40 s, not the budgeted ~6 s for the whole pass.
  >
  > **Shipped with `EXHAUSTIVE_TRIPLE_BUDGET`, which reduces the bound by whole lengths only.** A
  > partial length would weaken the headline claim from "shortest counterexample" to "shortest we
  > reached", which is exactly the caveat nobody carries forward. Every live alphabet still keeps
  > `L ≥ 3`, the length the assert/retire/re-assert shape needs.
  >
  > **The `364 ms` native cell is not a per-binding constant.** It has read 364 ms, 694–703 ms and
  > 1.01 s on three occasions (box), and it is `ORMap<String, GCounter>`-specific (type):
  > `LWWRegisterConvergenceTest` runs this **exact** `|A| = 3, L = 4, 120-word` configuration for
  > **1 ms**, because its join is a tag comparison rather than a nested map merge. Do not multiply
  > it out per binding — the spec's "~5.8 s across 16" is wrong for that reason. Whole-pass cost
  > re-measured 2026-08-05 at load 1.5: **3.03 s across 19 bindings**, of which `JsonCrdt` is 2.02 s
  > and `ORMap` 1.01 s and the other **17 are ~0**. The JVM and wasmJs rows reproduce exactly.

- [ ] **Step 4:** add the op log to the **randomised** pass so its failure prints the word that built
  each of `a`, `b`, `c`. The existing message already prints both bracketings and their hex; this adds
  provenance, and together with Step 1 it is what replaces jqwik's shrinking.
- [ ] **Gate:** module build + `macosArm64Test` + `wasmJsTest`; quote the added native wall clock.

---

### Task 7: Rename to `LatticeLawSuite`, and the docs

Cosmetic but load-bearing for discoverability. Do it in its own PR so the behavioural diffs above stay
readable.

- [ ] **Step 1:** rename `CrdtConvergenceSuite` → `LatticeLawSuite`, `CrdtConvergenceHarness` →
  `LatticeLawHarness`, package `convergence` → `lattice`. Deprecated `typealias`es are **not** wanted —
  every subclass is in-tree.
- [ ] **Step 2:** update `docs/agent-cookbook.md` and any Writerside topic naming the old types.
- [ ] **Step 3:** `./gradlew verifyDocCitations` — a rename breaks every `verbatim from …#symbol`
  citation pointing at these files. **Re-copy the blocks; do not relabel them `condensed from`** to get
  past the gate. Relabelling is a one-way door: that block is never content-checked again.

---

### Task 8: Delete jqwik from `:kuilt-crdt`

**Depends on Tasks 3 and 5.** The single highest-risk task in this plan, for a reason that is not
obvious: **a broken test-discovery configuration reports BUILD SUCCESSFUL with zero tests run.**

**Files:** 15 files under `kuilt-crdt/src/jvmTest/.../property/`, `RgaTest.kt`, `PNCounterTest.kt`,
`kuilt-crdt/build.gradle.kts`.

- [ ] **Step 1 — record the before count.** `:kuilt-crdt`'s JVM test count from the **results XML**,
  not the console. Write it in the PR body. Without this number Step 5 cannot be checked.
- [ ] **Step 2 — re-home the 5 non-law properties first, in their own commit**: `RgaLawsPropertyTest`'s
  `threeReplicasConverge`, `removedElementsAreExcludedFromToList`, `insertAtPlacesElementAtCorrectIndex`,
  `concurrentInsertsAfterSamePredecessorAreOrdered`, and `PNCounterLawsPropertyTest`'s
  `valueEqualsIncMinusDec`. They become ordinary `@Test`s in `commonTest` — a **gain**, since they move
  from JVM-only to all targets. Confirm they run and pass before anything is deleted.
- [ ] **Step 3 — delete the 15 files** (14 `*LawsPropertyTest.kt` + `LatticeTrajectory.kt`) and
  `JqwikSmokeTest.kt`.
- [ ] **Step 4 — unwire the build.** Three things come out of `kuilt-crdt/build.gradle.kts` **together**:
  `implementation(libs.jqwik)` and its `junit.vintage.engine` / `junit.platform.launcher` runtime deps;
  `tasks.named<Test>("jvmTest") { useJUnitPlatform() }`; and the
  `capabilitiesResolution.withCapability("org.jetbrains.kotlin:kotlin-test-framework-impl")` block,
  which exists **only** to resolve the JUnit4/JUnit5 conflict the Platform switch creates.
  **Leave `jqwik` in `gradle/libs.versions.toml`** — `:kuilt-raft`'s `PureRaftModelTest` still uses it
  and is another session's track. Do not touch `kuilt-raft/build.gradle.kts`.

  > **CORRECTED (Task 9): that premise expired 46 minutes after this plan was committed.** #2112
  > (`c97f5b8b`, 22:14 ET on 2026-08-04) migrated `:kuilt-raft`'s model check off jqwik. Verified on
  > `origin/main` after #2147: no `*.gradle.kts` references jqwik and no `*.kt` imports
  > `net.jqwik`, leaving the catalog entry the sole survivor — and a catalog entry nothing consumes
  > is not inert, it keeps Renovate proposing bumps to a dependency the repo does not use. Task 9
  > deletes it, along with `junit-vintage-engine`, which existed only to resolve the JUnit4/JUnit5
  > conflict the Platform switch created and now has zero consumers. `junit-platform-launcher`
  > stays — `:kuilt-scale` uses it. **Do not touch `kuilt-raft/build.gradle.kts` still holds**, for
  > the different reason that there is now nothing there to touch.
- [ ] **Step 5 — the gate that actually matters.** `:kuilt-crdt:jvmTest --rerun-tasks`, then read the
  **results XML** and assert:

  `after == before − 76 + 5`

  A green build is **not** sufficient evidence: a broken runner runs zero tests and reports success.
  If the arithmetic does not close, stop and report rather than adjusting the expectation.
- [ ] **Step 6:** full `./gradlew build detektAll --rerun-tasks`, confirming tasks are `EXECUTED`, not
  `FROM-CACHE`. Use `--max-workers=6`; a full `--rerun-tasks` build drives box load to 70–96 on its own
  and false-reds its own wall-clock ceilings.

---

### Task 9: Close the loop on #2101

- [ ] **Step 1:** rewrite #2101's body to lead with what is now true. Its four-surface table describes
  the pre-#2099 tree and will otherwise be quoted forever. Per repo convention a body contradicted by
  its own comments is worse than a thin one.
- [ ] **Step 2:** re-run **every** measurement this plan quotes and record the values in the closing
  comment. A measurement nobody can re-run is a claim: Task 0's 8/16-and-first-red-at-2, Task 2's
  64/64-and-0/64, Task 5's floor asymmetry, Task 6's 28-word minimal counterexample, and the per-target
  wall clocks with `uptime` beside them.
- [ ] **Step 3:** state `:kuilt-conformance`'s `macosArm64Test` **per-test totals** before and after
  the whole track, from the results XML. Baseline **148.6 s**; the track must finish at or below it.
  Kotlin/Native is where this work's cost lands, and an unmeasured 10× is how a suite becomes something
  people disable.
- [ ] **Step 4:** file follow-ups for anything parked, in the same turn rather than later.

---

## What this plan deliberately does NOT do

- **It does not build a new suite.** #2099 already shipped the causal pool, both bracketings and byte
  assertions to every target. A fifth layer is the thing #2101 correctly diagnoses as a symptom.
- **It does not delete `QuiltedConformanceSuite`.** It is the only surface that runs for a type with no
  generator, costs microseconds, covers four types the randomised suite does not bind, and — with one
  added sample — catches #2086.
- **It does not delete the per-type `*Test.kt` files.** Pinned precondition boundaries
  (`LWWMapTest`'s equal-tag pin, `MVRegisterTest`'s forked-replica pin) are prose plus an assertion,
  which a generic suite structurally cannot express.
- **It does not split randomised-deep from exhaustive-small by target.** The numbers do not support it.
- **It does not raise `POOL_LIMIT` to buy redness.** The loop is cubic; constructed shapes buy the same
  redness for free, and that is what they are for.
- **It does not touch `:kuilt-raft`'s jqwik usage.** Different module, different track.
