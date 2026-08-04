# Dot-Based Delta Mutators — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `ORSet`, `ORMap` and `LWWMap` a minimal delta for every mutator, so one write ships
O(change) bytes instead of O(state) — and pin the delta-mutator law hard enough that the two shapes
#2044 proposes can never come back.

**Architecture:** No new type, no new wire format, no `Quilter` change. Each mutator gains a
`Patch<S>`-returning form whose delta is *the same CRDT type*, carrying the touched entry plus a
causal context holding **exactly the dots the operation asserts or retires**. The property every
test is built around is the delta-mutator law, asserted on **encoded bytes**:

```
X.piece(mᵟ(X))  ==  m(X)        byte-for-byte, for every state X
```

`LWWMap` needs no causal context at all and is fully independent of the other two.

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization (CBOR), kotlin-test, jqwik (JVM only).
Gradle 9.4.1, JDK 21.

**Spec:** [`docs/superpowers/specs/2026-08-04-crdt-dot-delta-mutators-design.md`](../specs/2026-08-04-crdt-dot-delta-mutators-design.md)
· **Issue:** [#2044](https://github.com/tractat-us/kuilt/issues/2044)

> **Three claims in #2044 are wrong — do not plan against them.**
> 1. Its **add** delta (`DotContext.of(dot)`) omits the superseded dots → permanent divergence and a
>    resurrected element.
> 2. Its **remove** delta ("empty store, context unchanged") wipes the receiver's entire set.
> 3. Its **`1,585×`** compares a flooded numerator to an un-flooded denominator. The measured
>    both-sides figure is **138×** at 400 entries, growing linearly with entry count.
>
> Also: **`MVRegister` is not in this class** (its state is O(replicas), 11.3× at R=64) and
> **`LWWMap` is**, though #2044 only lists it as "worth auditing". Update the issue body when the
> first PR lands.

## Global Constraints

- **JDK/toolchain:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` in every
  non-interactive shell. In an `isolation: "worktree"` agent that `source` is refused — use
  `export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem` instead.
- **`explicitApi()` is enforced.** Every new public declaration needs an explicit `public`, real
  consumer-facing KDoc, and — for a documented entry point — a `@sample` in
  `kuilt-crdt/src/commonSamples/kotlin/`. Samples compile as part of `commonTest`; a broken one
  breaks the build.
- **`detektAll`, never bare `detekt`** — bare `detekt` is `NO-SOURCE` here and is a false green.
- **`jvmTest` is not sufficient.** `commonTest` sources must also compile for Android and Kotlin/
  Native. Before enabling auto-merge, run the **module** build with `--rerun-tasks` and confirm the
  tasks are `EXECUTED`, not `FROM-CACHE`. Task 6 touches consensus-adjacent runtime behaviour and
  needs the **full** `./gradlew build`, not a module build.
- **Assert on bytes, not only on `equals`.** Two states can be equal and encode two ways; that is
  precisely the failure #1955's gate is sensitive to, and the reason this whole change is safe.
- **Test naming:** no `test` prefix. Multi-assert tests use `assertAll()` from `us.tractat.kuilt.test`.
- **Never use `git stash`** — `refs/stash` is repo-global across every linked worktree. Copy files to
  a scratch path and restore from the copy.
- **Never use the word "chore".**
- **Commit messages end with:** `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
- **Backticks do NOT disarm closing keywords in commit messages** — a squash turns the PR body into
  one. Use **"part of #2044"** everywhere. Do not write a closing keyword at all; #2044 stays open
  until Task 7 is decided.

---

## File Structure

| File | Responsibility |
|---|---|
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ORSet.kt` | **Modify.** Delta forms of `add`/`remove`. |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ORMap.kt` | **Modify.** Delta forms of `put`/`remove`. |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/LWWMap.kt` | **Modify.** Delta forms of `set`/`remove`. |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Quilted.kt` | **Modify (Task 7).** Correct the KDoc paragraph that put maps on the "whole state is minimal" side. |
| `kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt` | **Modify.** One `@sample` per type. |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DeltaMutatorLawTest.kt` | **Create.** The law, on bytes, plus the two #2044 regression tests. |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/CanonicalGoldenVectorTest.kt` | **Modify (Task 4).** `ORSET_DELTA`, `ORMAP_DELTA`. |
| `kuilt-scale/src/test/kotlin/us/tractat/kuilt/scale/DotWireEncodingCostModelTest.kt` | **Modify (Task 5).** Invert part (I)'s standing assertion; add the delta row. |
| `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt` | **Modify (Task 6).** Four `Quilter`s onto `mutate {}` + deltas. |
| `docs/agent-cookbook.md`, `Writerside/topics/crdt-orset.md`, `crdt-ormap.md` | **Modify (Task 7).** |

**Dependency order:** Task 0 → {Task 1 ∥ Task 2 ∥ Task 3} → {Task 4 ∥ Task 5 ∥ Task 6} → Task 7.

**Tasks 1, 2 and 3 are fully parallelizable** — one production file and one test class each, no
shared symbol. Tasks 4, 5 and 6 are likewise parallel once 1–3 have landed. Task 7 is gated on a
decision, not on code.

---

### Task 0: Reproduce both #2044 defects — MANDATORY AND BLOCKING

> **Do not start Task 1 until Task 0's verdicts are recorded.** No code lands here, which is exactly
> why it looks skippable and must not be skipped. Every later "and now it is correct" is meaningless
> without a recorded red. This is also the step that stops a reviewer re-litigating the design from
> the issue body, which still describes the broken shapes.

**Files:** none (every edit is reverted).

- [ ] **Step 1: Add the two #2044 shapes to `ORSet` temporarily**

Copy `ORSet.kt` to a scratch path first (`/tmp/.../d2044-ORSet.kt.pristine`) and restore from the
copy — **never `git stash`**.

```kotlin
    /** #2044's stated add delta — context carries only the minted dot. */
    public fun addDeltaIssueShape(replica: ReplicaId, element: E): Patch<ORSet<E>> {
        val dot = causal.context.nextDot(replica)
        return Patch(ORSet(Causal(DotMap(mapOf(element to DotSet(setOf(dot)))), DotContext.of(dot))))
    }

    /** #2044's stated remove delta — "empty store, context unchanged". */
    public fun removeDeltaIssueShape(): Patch<ORSet<E>> =
        Patch(ORSet(Causal(DotMap(), causal.context)))
```

- [ ] **Step 2: Record the two failures**

| # | Construction | Expected, and what it proves |
|---|---|---|
| D1 | 5-element set; remove one; ship `removeDeltaIssueShape()` to a converged peer | Peer holds **0** of 5. The shape is the full state of an emptied set, not a delta. |
| D2 | Converged on `{e ↦ (a,1)}`; `a` re-adds `e` and ships `addDeltaIssueShape`; then `a` removes `e` and ships a **correct** remove delta | `alpha=[]`, `bravo=[e]`. The stale dot survives the remove — a resurrection. |

Record both. Restore `ORSet.kt` and confirm `git status --short` is clean.

- [ ] **Step 3: Paste both verdicts into the first PR body**

---

### Task 1: `ORSet` — the delta mutators and the law

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ORSet.kt`
- Modify: `kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt`
- Create: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/DeltaMutatorLawTest.kt`

**Interfaces:**
- Produces: `ORSet.add(replica, element): Patch<ORSet<E>>`, `ORSet.remove(element): Patch<ORSet<E>>`
  — **or** `addDelta`/`removeDelta` siblings if Task 7's decision is "no break". Write the bodies as
  private helpers (`addPatch`/`removePatch`) so the public name is one line and the Task 7 decision
  is a rename, not a rewrite.

- [ ] **Step 1: Write the failing law test first**

```kotlin
    @Test
    fun addDeltaSatisfiesTheMutatorLawOnBytes() { /* 400 randomised states */ }

    @Test
    fun removeDeltaSatisfiesTheMutatorLawOnBytes() { /* 400 randomised states */ }
```

The generator must produce states whose `DotSet`s have **more than one dot** — fold in a second,
independently-built branch — or the whole superseded-dots term is unreachable and the test is
vacuous against exactly the defect it exists to catch. Assert `viaFull == viaDelta` **and**
`encodeToByteArray(...).contentEquals(...)`.

Seed the RNG explicitly (`Random(11)`), never `Random.Default`.

- [ ] **Step 2: Implement**

```kotlin
    private fun addPatch(replica: ReplicaId, element: E): Patch<ORSet<E>> {
        val dot = causal.context.nextDot(replica)
        val superseded = causal.store.entries[element]?.dots ?: emptySet()
        return Patch(
            ORSet(
                Causal(
                    DotMap(mapOf(element to DotSet(setOf(dot)))),
                    (superseded + dot).fold(DotContext.EMPTY) { context, d -> context.add(d) },
                ),
            ),
        )
    }

    private fun removePatch(element: E): Patch<ORSet<E>> {
        val live = causal.store.entries[element]?.dots ?: emptySet()
        return Patch(ORSet(Causal(DotMap(), live.fold(DotContext.EMPTY) { context, d -> context.add(d) })))
    }
```

> ## ⚠ The superseded dots are the whole point
>
> Drop `superseded` from `addPatch` and you have reimplemented #2044's shape. The law test will go
> red; the KDoc must say **why**, in one sentence, so a later reader tempted to "simplify" it stops.

- [ ] **Step 3: The two #2044 regression tests**

Named so a failure reads as a regression, not a puzzle:

```kotlin
    @Test fun anAddDeltaThatOmitsSupersededDotsResurrectsARemovedElement() { … }
    @Test fun aRemoveDeltaCarryingTheWholeContextWouldWipeTheReceiver() { … }
```

Each reconstructs its Task 0 shape **inline in the test** (not as a production method) and asserts
the correct behaviour. Reinstating either shape in production must turn these red — verify that by
doing it once, watching them fail, and restoring.

- [ ] **Step 4: The order/duplication property**

Three replicas, random op streams, deltas delivered shuffled **and duplicated**, byte-compared
against the straight fold. 200 trials, seeded. Plus: a remove delta applied **before** the add it
retires, all orders byte-identical. These are what license "no causal delivery required" — the claim
the design makes and `Quilter` now depends on.

- [ ] **Step 5: KDoc and `@sample`**

The KDoc says what ships and why the context is what it is. The sample shows the superseding
property, not merely that `add` returns something:

```kotlin
/** A re-add supersedes the dots it observed — that is what the delta's context carries. */
@Suppress("unused")
internal fun sampleORSetDelta() { … }
```

- [ ] **Step 6: Gate and commit**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem
./gradlew :kuilt-crdt:build detektAll --rerun-tasks
./gradlew verifyDocCitations
```

Confirm `EXECUTED`, not `FROM-CACHE`.

---

### Task 2: `ORMap` — same shape, one extra subtlety

**Files:** `ORMap.kt`, `CrdtSamples.kt`, `DeltaMutatorLawTest.kt` (its own test class if Task 1 is
still in flight — do not contend on one file).

- [ ] **Step 1: Law tests first**, over randomised maps with a real value lattice (`GSet<String>` is
      enough) and multi-dot tag sets.

- [ ] **Step 2: Implement**

```kotlin
    private fun putPatch(replica: ReplicaId, key: K, value: S): Patch<ORMap<K, S>> {
        val dot = causal.context.nextDot(replica)
        val superseded = causal.store.entries[key]?.tags?.dots ?: emptySet()
        return Patch(
            ORMap(
                Causal(
                    DotMap(mapOf(key to ORMapEntry(DotSet(setOf(dot)), value))),
                    (superseded + dot).fold(DotContext.EMPTY) { context, d -> context.add(d) },
                ),
            ),
        )
    }
```

> ## ⚠ Ship the SUPPLIED value, not the locally merged one
>
> `put` merges locally (`existing?.value?.piece(value) ?: value`) because a put is additive over the
> value lattice. The delta must carry the **caller's** `value`: `ORMapEntry.join` re-does the merge
> at the receiver, against *the receiver's* value, which is the correct one. Shipping the sender's
> merged value still converges but is O(value) and re-transmits history the receiver already has —
> and on a nested `ORMap<K, ORSet<X>>` that is most of the saving gone.

- [ ] **Step 3: `removePatch(key)`** — empty store, context = the key's tags. A no-op on an absent
      key returns `Causal(DotMap(), DotContext.EMPTY)`; assert that it is the lattice identity.

- [ ] **Step 4: Nested-value case.** One test with `ORMap<String, ORSet<String>>` — the value has its
      own dot space and its own context, and the two must not interfere. This is the shape
      `JsonCrdt` builds on (`JsonNode.Object` wraps `ORMap<String, JsonNode>`), so a mistake here
      reaches further than `ORMap`.

- [ ] **Step 5: `@sample`, gate, commit.** Same commands as Task 1.

---

### Task 3: `LWWMap` — the easiest slice, and the biggest measured ratio

Independent of Tasks 1 and 2: `LWWMap` has **no causal context**. `piece` is a per-key max-tag
merge, so a single-cell map is already a sound delta — measured 0 law failures over 300 randomised
states, and 578,620 b → 63 b at 10,000 keys (**9,184×**).

**Files:** `LWWMap.kt`, `CrdtSamples.kt`, its own law test class.

- [ ] **Step 1: Law test first**, over randomised maps. `LWWMap.empty<K, V>().set(replica, ts, k, v)`
      is the delta.
- [ ] **Step 2: Implement** `setPatch` / `removePatch` returning `Patch(LWWMap(mapOf(key to cell)))`.
      `remove` is a tombstone write, so its delta is a one-cell map too — **not** an empty one.
- [ ] **Step 3:** Assert the tag-uniqueness precondition is unchanged; the delta form does not
      weaken it.
- [ ] **Step 4: `@sample`, gate, commit.**

---

### Task 4: Golden vectors for delta-shaped frames

The one genuinely new **shape** on the wire: `DotContext.cloud` becomes non-empty on `ORSet`/`ORMap`
frames for the first time. `CanonicalGoldenVectorTest` records (from #2038) that `cloud` is empty in
the `ORSET`/`ORMAP` vectors and that the cloud sort is pinned only by the standalone `DOT_CONTEXT`
vector. Implemented and pinned — but never on an `ORSet` frame.

**Files:** `CanonicalGoldenVectorTest.kt`.

- [ ] **Step 1:** Add `ORSET_DELTA` and `ORMAP_DELTA` constructions whose context has a **non-empty
      cloud** and **more than one** cloud dot (a single dot has one order and pins nothing — the V2
      vacuity trap from #2019's design). Build them via the real delta mutators.
- [ ] **Step 2:** Record the vectors on JVM, then confirm identical on `wasmJsBrowserTest` and
      locally on `macosArm64Test`. **A vector that differs per target is the defect this file exists
      to catch — investigate, never re-record per target.**
- [ ] **Step 3:** Extend the file's mechanism/vector table so the new rows say which sort they pin.
- [ ] **Step 4:** Prove they have teeth: revert `DotContextSerializer`'s `cloud.sorted()` to
      `value.cloud.toList()`, confirm the new vectors go red, restore. Record the verdict.

---

### Task 5: Re-meter, and invert the standing assertion

`DotWireEncodingCostModelTest`'s part (I) currently asserts one write costs **more** than admitting a
new peer, with the message *"if this ever inverts, `ORSet` grew a delta mutator and part (H)'s budget
is stale"*. It is about to invert. **Invert it deliberately — do not delete it.**

**Files:** `DotWireEncodingCostModelTest.kt`.

- [ ] **Step 1:** Add a delta column to part (I): meter one add and one remove on both paths, at
      100 / 400 / 1,600 entries. Reference numbers from the design (JVM, this branch):

  | entries | add today | add delta'd | remove today | remove delta'd |
  |---|---|---|---|---|
  | 100 | 56,325 b | 1,605 b | 56,179 b | 1,612 b |
  | 400 | 221,025 b | 1,605 b | 220,879 b | 1,612 b |
  | 1,600 | 890,481 b | 1,605 b | 890,335 b | 1,612 b |

  If your measurement disagrees, **your measurement wins** — record both and say so.

- [ ] **Step 2:** Replace the inversion assertion with the property that now holds and that a future
      regression would break: **the metered cost of one write is flat in state size.** Assert the
      1,600-entry write costs no more than ~1.2× the 100-entry write. That is a real invariant;
      "smaller than before" would pass a change that reintroduced O(n) with a better constant.
- [ ] **Step 3:** Assert converged anti-entropy is **unchanged** at ~94 b/node/round on the delta
      path. This is the #1955 gate; a delta path that desynchronised the root hash would show up
      here as full states.
- [ ] **Step 4:** Update part (H)'s budget table and its prose — it currently says "`ORSet` has no
      delta mutator" in a `println`.

---

### Task 6: Migrate `kuilt-warp` — where the win actually ships

`WarpNode` runs four `Quilter`s — three `ORMap`s and one `ORSet` — and every mutation site is
`apply(Patch(state.value.mutator(…)))`. This is the in-tree consumer that pays the O(n) cost on
every task enqueue, claim and completion.

**Files:** `WarpNode.kt`.

- [ ] **Step 1:** Move every site to `quilter.mutate { it.<mutator>(…) }`. `mutate` reads the state
      inside `Quilter`'s own lock, which is the idiom the design pushes.
- [ ] **Step 2:** **`enqueue(taskId, CoordinationKind.Coordinated)` reads
      `coordQueueQuilter.state.value` outside `WarpNode`'s lock**, while every other mutation site in
      the file holds it. Two concurrent coordinated enqueues mint the same dot and **both writes are
      lost** (each join reads the other's dot as a deliberate remove). That is a pre-existing bug on
      both the full-state and delta paths — **file it as its own issue, do not fix it inside this
      task.** Moving the site to `mutate {}` happens to close it; say so in the commit rather than
      claiming the fix here.
- [ ] **Step 3: Full build, not a module build.**

```bash
./gradlew build detektAll --rerun-tasks
```

Per repo convention a `:<module>:build`-scoped run is a false green for runtime-behaviour changes —
it skips the `:examples` / `:kuilt-cluster` E2E tests that exercise the whole stack. `WarpSimulation`
and `WarpSpikeDChurnSim` are the ones that matter here.

- [ ] **Step 4:** Meter one warp task's end-to-end cluster egress before and after, and put both
      numbers in the commit. A migration with no number is a refactor.

---

### Task 7: The naming decision, the KDoc correction, and the docs — GATED ON IAIN

Do not start until the question in the design's "Decisions Iain owns" is answered.

**If the answer is "yes, change the return types":**

- [ ] **Step 1: Change them, and count the compile errors.** That count **is** the blast radius —
      report it, do not estimate it beforehand. Every failure is a real call site; there is no path
      where old code compiles and behaves differently (`Patch(it.add(…))` becomes `Patch(Patch(…))`).
- [ ] **Step 2: Migrate every site**, including `commonSamples`, `:kuilt-conformance`,
      `:kuilt-scale`, `examples/`, `:kuilt-otel` and the Writerside snippets.
- [ ] **Step 3: Correct `Quilted`'s KDoc.** It currently states the `Patch` wrapper "is reserved for
      CRDTs whose delta is a strict, non-obvious subset of their state" and offers *registers and
      maps* as the family where the whole state is already minimal. True for `LWWRegister` and
      `Gauge`; false for `ORSet`, `ORMap` and `LWWMap` — and that sentence is a large part of why
      nobody looked for six months.

**Either way:**

- [ ] **Step 4: `docs/agent-cookbook.md`** — the symptom→primitive entry for observed-remove sets and
      maps must quote the delta form. Note it already cites `CrdtSamples.kt#sampleORSet`
      **verbatim**, so editing that sample in Task 1 moves this file too — `verifyDocCitations` will
      say so. A primitive whose cookbook entry shows the expensive spelling is the exact failure that
      surface exists to prevent.
- [ ] **Step 5: `Writerside/topics/crdt-orset.md`, `crdt-ormap.md` and `crdt-lwwmap.md`** — update
      the inlined snippets and their `<!-- verbatim from … -->` citations. Re-read each page
      top-to-bottom afterwards and confirm it still opens in plain language with the technical depth
      below; a mechanical snippet swap is exactly how that gets lost.
- [ ] **Step 6:** `./gradlew verifyDocCitations` (about a second) and the full build.

---

## Wrap-up (after Task 7)

- [ ] Rebase onto current `origin/main`: `git fetch origin main && git rebase origin/main`
- [ ] Full cache-disabled gate: `./gradlew build detektAll --rerun-tasks` — confirm `EXECUTED`
- [ ] Update #2044's body: strike the two wrong delta shapes and the `1,585×`, move `MVRegister` to a
      follow-up, add `LWWMap`. A body contradicted by its own comments is worse than a thin one.
- [ ] File the two follow-ups: **(a)** `MVRegister` + `EphemeralMap` minimal deltas (O(replicas), 11×
      and 194×); **(b)** the stale-snapshot annihilation at `WarpNode.enqueue(…, Coordinated)`.
- [ ] Open each PR **ready, not draft** — a drafted-then-readied PR leaves a stale `ci-required`
      FAILURE. Use **"part of #2044"**; whether the last PR may close it is Iain's call.
- [ ] `~/.claude/bin/gh-pr-wait <PR> --arm-auto`

## Self-Review

**Spec coverage.** Delta shapes and the lattice → Tasks 1–3. The remove problem and its convergence
argument → Task 1 Steps 2–4 plus Task 0's D1 receipt. Causal-context transmission → Task 1 Step 4
(order/duplication/out-of-order properties), which is what licenses the design's "no buffering"
claim. API shape → Tasks 1–3 (private helpers, so the public spelling is one line) and Task 7 (the
decision). The anti-entropy gate → Task 5 Step 3, plus the byte-level law in Tasks 1–3. Canonical
encoding → Task 4. Sequencing and parallelism → the dependency line above. The audit of other zoo
types → recorded in the design; `MVRegister`/`EphemeralMap` deliberately deferred to a follow-up
rather than bundled.

**Type consistency.** `Patch<ORSet<E>>` / `Patch<ORMap<K,S>>` / `Patch<LWWMap<K,V>>` — Tasks 1–3
produce, Tasks 5–6 consume. No new type crosses a task boundary, which is why 1–3 parallelise
cleanly.

**One correction from my own review.** An earlier draft had Task 6 (`kuilt-warp`) fix the unguarded
`enqueue` read as part of the migration. That would have buried a real, separately-reproducible bug
inside a performance PR and made the E2E gate ambiguous about which change caused what. It is a
separate issue; the migration closing it incidentally is stated in the commit, not claimed as the
fix.

**A second one.** Task 4 originally said "add a delta-shaped golden vector". A delta's context is
typically **one** dot, and a one-element cloud has exactly one order — the vector would have been
green either way, which is #2019's V2 vacuity trap reproduced. The step now requires **two or more**
cloud dots and a recorded mutation showing the vector goes red without `cloud.sorted()`.

**A third.** Task 5's first draft asserted the delta path is "cheaper than before". That would pass
a future change that reintroduced O(n) with a smaller constant. The assertion is now that the
metered write cost is **flat in state size**, which is the property actually being bought.
