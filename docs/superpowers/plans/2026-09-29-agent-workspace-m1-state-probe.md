# Agent workspace — M1 shared-state semantics probe: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decide how the dinner workspace's shared state is shaped. Run the same five merge questions against two dot-carrying models, a typed model built from `Rga` logs and a `JsonCrdt` document, and record the defined result of each operation in a semantic decision table.

**Architecture:** A plain-KMP, JVM-only `:demo-workspace-state` module under `demo/workspace-state/`. It is deliberately separate from `:demo-workspace` (the causality plan) so the two tracks run in parallel without sharing files. The models are pure values, merged with `piece`; there is no network. Replication is `Quilter`'s job and is already covered by its own suites. Each probe is a **characterization test**: it states the question, pins the *observed* result, and explains it in KDoc. The table in `SEMANTICS.md` is the product.

**Tech Stack:** Kotlin Multiplatform (JVM only), `:kuilt-crdt` (`Rga`, `LatticeProduct`, `JsonCrdt`, `JsonNode`, `MVRegister`), `:kuilt-test` (`assertAll`).

**Spec:** Epic tractat-us/kuilt#2869, M1 section (the paragraph starting "In a separate state probe"). Ruling on #2869: **identities come from dot-carrying types only.** This probe is where that ruling meets the model. Every input must be an `Rga` element, so the question is what the *rest* of the state looks like around that constraint.

## Global Constraints

- Module `:demo-workspace-state` at `demo/workspace-state/`, package `us.tractat.kuilt.demo.workspace.state`. Plain `kotlinMultiplatform` + `kotlinSerialization`, `jvm()` only. Add it to `settings.gradle.kts`, and add it to `deliberatelyUnpublished` in `kuilt-bom/build.gradle.kts` in the same PR.
- An input-bearing value must have a `Dot` reachable through `Quilted.causalDots()`. `JsonNode.Leaf`, `ORMap`, `MVRegister` and `LWWRegister` contribute none (`Quilted.causalDots()` defaults to empty; `JsonNode.Object`/`JsonCrdt` union only their `Array` descendants). The probe must **show** this per model with a test, not assume it.
- **Neither a Kotlin data class nor a JSON document is a merge strategy.** Every "result" in the table comes from a `piece` the test actually ran.
- Probe both merge orders, `a.piece(b)` and `b.piece(a)`, for every concurrent case, and assert that they are equal.
- No `!!`. No `test` prefix on test names. Use `assertAll` for multiple asserts. No external links in committed files.
- JVM-only evidence. Make no cross-target claim.
- Verify before auto-merge: `./gradlew clean && ./gradlew :demo-workspace-state:build :kuilt-bom:build --rerun-tasks`.

## Review Focus

1. **A UI action holding a venue handle after a concurrent insert ahead of it.** It must still hit the same venue in both models. A list index is not a handle.
2. **Delete versus a concurrent field edit on the same venue.** One defined outcome, the same in both merge orders. A thrown exception or an orphan that silently vanishes is a finding.
3. **Concurrent budget sets.** The conflict must stay *visible*: both values are recoverable, or the loser's identity is. A silent last-write-wins with no trace of the loser defeats "ask a person".
4. **A scalar edit in `JsonCrdt` (`Leaf`).** Confirm it has no dot, record that as an unsupported input shape, and fail explicitly if the model needs one.
5. **Whole-subtree nested writes (#2469).** Measure the delta size of one field edit in `JsonCrdt` against the typed model. Size matters for the M4 phone.

---

### Task 1: Module and the two models

**Files:**
- Create: `demo/workspace-state/build.gradle.kts` (same shape as `demo/shared/build.gradle.kts`, `jvm()` only, commonMain deps `project(":kuilt-crdt")` + `libs.kotlinx.serialization.core`, commonTest `project(":kuilt-test")` + `libs.kotlin.test`)
- Modify: `settings.gradle.kts`, `kuilt-bom/build.gradle.kts`
- Create: `demo/workspace-state/src/commonMain/kotlin/us/tractat/kuilt/demo/workspace/state/TypedModel.kt`
- Create: `demo/workspace-state/src/commonMain/kotlin/us/tractat/kuilt/demo/workspace/state/JsonModel.kt`
- Test: `demo/workspace-state/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/state/IdentityTest.kt`

**Interfaces:**
- Produces a common probe surface both models implement, so every Task 2 probe is written once and run twice:

```kotlin
package us.tractat.kuilt.demo.workspace.state

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId

/** An opaque, merge-stable handle to one shortlisted venue. Never a list index. */
public data class VenueHandle(val dot: Dot)

public data class VenueView(val handle: VenueHandle, val name: String, val pricePerHead: Int)

/** The probe surface. Each mutator returns the new state; `merge` is the model's `piece`. */
public interface DinnerModel<S> {
    public val name: String
    public fun empty(replica: ReplicaId): S
    public fun addVenue(s: S, replica: ReplicaId, name: String, price: Int): Pair<S, VenueHandle>
    public fun setPrice(s: S, replica: ReplicaId, venue: VenueHandle, price: Int): S
    public fun removeVenue(s: S, replica: ReplicaId, venue: VenueHandle): S
    public fun setBudget(s: S, replica: ReplicaId, budget: Int): S
    public fun merge(a: S, b: S): S
    public fun shortlist(s: S): List<VenueView>
    /** Every budget value still recoverable after merge, in the model's deterministic order. */
    public fun budgetCandidates(s: S): List<Int>
    /** The model's single answer, and how it chose (documented in KDoc). */
    public fun effectiveBudget(s: S): Int?
    public fun causalDots(s: S): Set<Dot>
    /** Encoded delta size of the last mutation, for Review Focus 5. */
    public fun encodedSize(s: S): Int
}
```

**TypedModel.** The state is `LatticeProduct<Rga<VenueAdded>, Rga<Edit>>`, where `VenueAdded(name, price)` and `sealed Edit`:
- `PriceSet(target: Dot, price)`
- `Removed(target: Dot)`
- `BudgetSet(budget)`

A venue's handle is its `VenueAdded` insert dot. `shortlist` folds `Edit`s in the edit log's `entries()` order. The last `PriceSet` per target wins. A `Removed` target is hidden, and so are the `PriceSet`s aimed at it. `effectiveBudget` is the last `BudgetSet` in `entries()` order, which is `RgaId` order `(lamport, replica)`. `budgetCandidates` returns all `BudgetSet`s that are concurrent with the effective one. For the spike, approximate "concurrent" as *every* `BudgetSet` newer than the last one the effective setter's replica had seen; if that needs a vector, record the need in the table. Every mutation is `insertAt(replica, size, value)` on the right log, wrapped as the product's delta. Check the `LatticeProduct` constructor and `piece` in `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/LatticeProduct.kt:36-80` before writing.

**JsonModel.** The state is a `JsonCrdt` document `{"shortlist": Array[Object{name, price}], "budget": Leaf}`. The handle is the venue's `RgaId.dot` inside `(doc["shortlist"] as JsonNode.Array).rga.entries()`. `setPrice` rebuilds the element `Object` with a new price `Leaf` and writes the whole `shortlist` array back through `doc.set(...)` (the #2469 shape). `setBudget` writes a `Leaf`. Build nodes with the helpers in `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/JsonCrdtTest.kt:36-60` (copy them into the model), and use the `piece { it.set(...) }` idiom from lines 120-130.

**If an operation cannot be expressed** in a model (for example, editing a field inside an `Rga` element's `Object` without replacing the element), implement the closest faithful form and mark it with a KDoc line starting `SHAPE:`. That line goes into the table verbatim.

- [ ] **Step 1: Write the failing identity test.**

```kotlin
class IdentityTest {
    private val a = ReplicaId("a")

    private fun <S> dotsCover(m: DinnerModel<S>) {
        val (s1, handle) = m.addVenue(m.empty(a), a, "Uno", 40)
        val s2 = m.setBudget(s1, a, 30)
        assertAll(
            { assertTrue(handle.dot in m.causalDots(s2), "${m.name}: venue has an identity") },
            { assertTrue(m.causalDots(s2).size >= 2, "${m.name}: budget edit has an identity") },
        )
    }

    @Test fun typedModelGivesEveryInputADot() = dotsCover(TypedModel)
    @Test fun jsonModelBudgetLeafHasNoDot() {
        val (s1, _) = JsonModel.addVenue(JsonModel.empty(a), a, "Uno", 40)
        val before = JsonModel.causalDots(s1)
        val after = JsonModel.causalDots(JsonModel.setBudget(s1, a, 30))
        assertEquals(before, after, "Leaf budget contributes no dot (Review Focus 4)")
    }
}
```

`jsonModelBudgetLeafHasNoDot` is the **expected finding**. If it fails, the `JsonCrdt` dot surface differs from what #2869 claims, so stop and report that to the controller before continuing.

- [ ] **Step 2: Run it and watch it fail** (compilation). Implement both models, then run again. Expect both tests to pass, and count them in the XML.
- [ ] **Step 3: Commit and open a Draft PR** ("demo-workspace-state: two dot-carrying dinner models (#2869 M1)").

---

### Task 2: The five merge probes, both models, both merge orders

**Files:**
- Test: `demo/workspace-state/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/state/MergeProbe.kt` (the probe bodies, generic over `DinnerModel<S>`)
- Test: `demo/workspace-state/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/state/TypedModelProbeTest.kt`
- Test: `demo/workspace-state/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/state/JsonModelProbeTest.kt`

The probe bodies are written once in `MergeProbe`. Each `*ProbeTest` has one `@Test` per probe that calls into it with its model and **its own pinned expectation**, because the two models may legitimately differ. A difference is a table row, not a failure.

Every probe starts from a shared base (`base = addVenue ×3` on replica `a`) and forks into `x` (replica `a`) and `y` (replica `b`, same base). Every probe asserts `merge(x', y') == merge(y', x')` in `shortlist`, `effectiveBudget` and `budgetCandidates`.

- [ ] **Step 1: Write the probes.**

```kotlin
object MergeProbe {
    val a = ReplicaId("a"); val b = ReplicaId("b")

    fun <S> base(m: DinnerModel<S>): Pair<S, List<VenueHandle>> {
        var s = m.empty(a); val hs = mutableListOf<VenueHandle>()
        for ((n, p) in listOf("Uno" to 40, "Due" to 25, "Tre" to 30)) { val (s2, h) = m.addVenue(s, a, n, p); s = s2; hs += h }
        return s to hs
    }

    private fun <S> bothOrders(m: DinnerModel<S>, x: S, y: S): S {
        val xy = m.merge(x, y); val yx = m.merge(y, x)
        assertAll(
            { assertEquals(m.shortlist(xy), m.shortlist(yx), "${m.name}: shortlist order-independent") },
            { assertEquals(m.effectiveBudget(xy), m.effectiveBudget(yx), "${m.name}: budget order-independent") },
        )
        return xy
    }

    /** P1 list identity: y inserts ahead of Due; x edits Due by handle. The edit lands on Due. */
    fun <S> listIdentity(m: DinnerModel<S>): VenueView? {
        val (s, hs) = base(m)
        val x = m.setPrice(s, a, hs[1], 22)
        val (y, _) = m.addVenue(s, b, "Zero", 10)
        return m.shortlist(bothOrders(m, x, y)).firstOrNull { it.handle == hs[1] }
    }

    /** P2 path replacement: concurrent price sets on the same venue. */
    fun <S> concurrentFieldEdit(m: DinnerModel<S>): VenueView? {
        val (s, hs) = base(m)
        return m.shortlist(bothOrders(m, m.setPrice(s, a, hs[0], 35), m.setPrice(s, b, hs[0], 45))).firstOrNull { it.handle == hs[0] }
    }

    /** P3 delete vs concurrent edit. */
    fun <S> deleteVersusEdit(m: DinnerModel<S>): List<VenueView> {
        val (s, hs) = base(m)
        return m.shortlist(bothOrders(m, m.removeVenue(s, a, hs[2]), m.setPrice(s, b, hs[2], 28)))
    }

    /** P4 conflicting scalar: concurrent budgets. Returns (effective, candidates). */
    fun <S> concurrentBudget(m: DinnerModel<S>): Pair<Int?, List<Int>> {
        val (s, _) = base(m)
        val merged = bothOrders(m, m.setBudget(s, a, 30), m.setBudget(s, b, 50))
        return m.effectiveBudget(merged) to m.budgetCandidates(merged)
    }

    /** P5 delta cost of one field edit on a 3-venue list. */
    fun <S> fieldEditSize(m: DinnerModel<S>): Int {
        val (s, hs) = base(m)
        return m.encodedSize(m.setPrice(s, a, hs[0], 35))
    }
}
```

`encodedSize` measures the **delta** a `Quilter` would ship. For a model whose mutators return full states, return the encoded size of the diff patch instead (the `Patch.delta` from `JsonCrdt.set`, and the one-insert product delta for Typed), and say which one in KDoc.

- [ ] **Step 2: Write the per-model tests.** Start each expectation from the model's documented semantics:
  - Typed P1: `VenueView(hs[1], "Due", 22)`.
  - Typed P3: `Tre` absent, because the remove hides later edits.
  - Typed P4: the effective budget is the `RgaId`-greater of the two, and the candidates contain both.
  - JSON P2: the price leaf is an `MVRegister`. Record what `shortlist` reports, because concurrent whole-array writes may conflict at the *array* level before the leaf.
  - JSON P3: `JsonCrdtTest.addWinsOverConcurrentRemove` suggests the edit resurrects `Tre`. Pin whichever you observe.
- [ ] **Step 3: Run each test alone** (`--tests "*TypedModelProbeTest*"`, then `--tests "*JsonModelProbeTest*"`). Where the observation differs from the starting expectation, **the observation wins**. Update the pinned value and add a KDoc line `OBSERVED: … because …`, naming the `piece` rule responsible (file:line in `kuilt-crdt`). A result you cannot explain from source is a finding, so report it rather than pin it.
- [ ] **Step 4: Revert-check one pin per model.** Flip one expected value, confirm the test goes red, and restore it. Record this in the PR body.
- [ ] **Step 5: Commit.**

---

### Task 3: Semantic decision table and recommendation (M1-state exit)

**Files:**
- Create: `demo/workspace-state/SEMANTICS.md`

- [ ] **Step 1: Write the doc**, accessible-first. Open with one paragraph on why "who changed the price while I deleted the place" needs a rule at all. Then:
  - **Table:** one row per probe P1–P5 and one row for identity. Columns: `question | typed-Rga result | JsonCrdt result | pinned by (test name) | fits the dot-only ruling?`. Every cell cites the test that pins it. No cell is written from reading source alone.
  - **Unsupported shapes:** every `SHAPE:` line, and the `Leaf`-has-no-dot result, each with the explicit failure a caller would get.
  - **Missing canonical APIs** versus **application policy**, as two separate lists. Examples to confirm or reject: a path-addressed nested mutator (#2469, as related work only, not a prerequisite); "which concurrent writes did the effective value beat" as a reusable primitive over an `Rga` log.
  - **Recommendation:** which model the M2 prototype adopts, in one paragraph, with the deciding rows named. If neither fits the ruling without a new primitive, say so plainly and name the primitive. Narrowing is a valid outcome.
- [ ] **Step 2: Lint the prose.** Run `vale --no-global demo/workspace-state/SEMANTICS.md`, or note in the PR that Vale's scope excludes `demo/`.
- [ ] **Step 3: Commit, ready the PR, and arm auto-merge.** Post the table and the recommendation on #2869 as the M1-state exit comment. **The causality track (`demo/workspace`) adopts the recommended model at M1 exit.** That is the join point between the two plans, and it is the controller's to schedule, not this track's.
