# `:kuilt-crdt` Rung 0 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `:kuilt-crdt` module with the delta-state foundation — the `Quilted` join-semilattice interface, the `Patch` delta type, and a reusable, multiplatform `QuiltedConformanceSuite` that checks the three CRDT laws.

**Architecture:** A new all-targets module `:kuilt-crdt` holds the `Quilted<S>` interface (one method, `piece` = join) and a `Patch<S>` value class (a delta is a state fragment). The reusable law-conformance suite lives in `:kuilt-conformance`'s `commonMain` (matching `SeamConformanceSuite` / `RaftStorageConformanceSuite`), so every future CRDT type validates itself by subclassing it. A throwaway `IntMax` toy lattice in the conformance module's `commonTest` proves the suite works before any real type exists.

**Tech Stack:** Kotlin Multiplatform, `kuilt.kmp-library` convention plugin, `explicitApi()` enforced, `kotlin-test`. (Serialization and jqwik property-based generators arrive with the first concrete type in a later rung — not needed here.)

**Design reference:** `docs/superpowers/specs/2026-06-06-crdt-module-design.md` (Rung 0).

---

## File Structure

| File | Responsibility |
|------|----------------|
| `settings.gradle.kts` (modify) | Register the new `:kuilt-crdt` module |
| `kuilt-crdt/build.gradle.kts` (create) | Module build — applies `kuilt.kmp-library`, nothing else |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Quilted.kt` (create) | The `Quilted` interface, `Patch` value class, `S.piece(Patch)` extension |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/QuiltedLawsTest.kt` (create) | Direct unit test proving the interface + `Patch` plumbing on a local toy lattice |
| `kuilt-conformance/build.gradle.kts` (modify) | Add `api(project(":kuilt-crdt"))` |
| `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/QuiltedConformanceSuite.kt` (create) | Reusable CRDT-law contract suite — subclass per type |
| `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/IntMaxConformanceTest.kt` (create) | Toy `IntMax` lattice + subclass that validates the suite itself |

---

## Preamble (run once at the start of the session)

Non-interactive shells don't load `~/.zshrc`. Source SDKMAN and select JDK 21 (matches CI) before any gradle command:

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
```

---

## Task 1: Scaffold the `:kuilt-crdt` module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `kuilt-crdt/build.gradle.kts`
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/.gitkeep` (temporary; deleted in Task 2)

- [ ] **Step 1: Register the module in settings**

Add this line to `settings.gradle.kts`, immediately after the existing `include(":kuilt-raft-test")` line (keep the existing ordering — append at the end of the `include(...)` block):

```kotlin
include(":kuilt-crdt")
```

- [ ] **Step 2: Create the module build file**

Create `kuilt-crdt/build.gradle.kts`:

```kotlin
plugins {
    id("kuilt.kmp-library")
}
```

(No dependencies yet — `Quilted`/`Patch` need only the Kotlin stdlib. Serialization and coroutines arrive with the first concrete type in a later rung; adding them now would be unused.)

- [ ] **Step 3: Create the package directory so the module configures**

```bash
mkdir -p kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt
touch kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/.gitkeep
```

- [ ] **Step 4: Verify the module is recognized and configures**

Run: `./gradlew :kuilt-crdt:tasks --group=build -q`
Expected: prints build tasks (e.g. `assemble`, `build`) with no configuration error. This confirms `settings.gradle.kts` and the build file are valid.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts kuilt-crdt/build.gradle.kts kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/.gitkeep
git commit -m "feat(kuilt-crdt): scaffold module"
```

---

## Task 2: The `Quilted` interface + `Patch` delta type

The interface is the whole contract: `piece` is the join (least-upper-bound) and must be idempotent, commutative, associative. `Patch<S>` wraps a delta (which is itself a lattice element) — the quilting metaphor on the interface, per the design's "metaphor on the interface only" decision.

**Files:**
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Quilted.kt`
- Create: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/QuiltedLawsTest.kt`
- Delete: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/.gitkeep`

- [ ] **Step 1: Write the failing test**

Create `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/QuiltedLawsTest.kt`:

```kotlin
package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smallest possible lattice: max-wins integer. Proves the interface plumbing. */
private data class IntMax(val value: Int) : Quilted<IntMax> {
    override fun piece(other: IntMax): IntMax = IntMax(maxOf(value, other.value))
}

class QuiltedLawsTest {

    @Test
    fun pieceIsIdempotent() {
        val a = IntMax(3)
        assertEquals(a, a.piece(a))
    }

    @Test
    fun pieceIsCommutative() {
        assertEquals(IntMax(3).piece(IntMax(7)), IntMax(7).piece(IntMax(3)))
    }

    @Test
    fun pieceIsAssociative() {
        val a = IntMax(1); val b = IntMax(5); val c = IntMax(2)
        assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
    }

    @Test
    fun patchAppliesViaPiece() {
        val state = IntMax(3)
        val patch = Patch(IntMax(5))
        assertEquals(IntMax(5), state.piece(patch))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kuilt-crdt:jvmTest`
Expected: FAIL — compilation error, `unresolved reference: Quilted` (and `Patch`). The types don't exist yet.

- [ ] **Step 3: Write the implementation**

Delete the placeholder and create `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Quilted.kt`:

```bash
rm kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/.gitkeep
```

```kotlin
package us.tractat.kuilt.crdt

import kotlin.jvm.JvmInline

/**
 * A delta-state CRDT: a value living in a join-semilattice.
 *
 * [piece] is the join — the least-upper-bound of two states. It MUST satisfy
 * the three lattice laws:
 *
 *  - **idempotent**   `a.piece(a) == a`
 *  - **commutative**  `a.piece(b) == b.piece(a)`
 *  - **associative**  `a.piece(b).piece(c) == a.piece(b.piece(c))`
 *
 * These laws are exactly what make convergence robust to kuilt's frame delivery
 * semantics: a fabric may drop, duplicate, and reorder frames, but any two
 * replicas that have absorbed the same *set* of states — in any order, with any
 * repeats — compute the same value.
 *
 * Operations are modeled as delta-mutators that return a [Patch] (a small
 * fragment of the same lattice), which any replica absorbs with [piece]. The
 * name nods to kuilt's quilting metaphor: a whole pieced from independent
 * patches.
 *
 * @param S the self-type — implementors write `class Foo : Quilted<Foo>`.
 */
public interface Quilted<S : Quilted<S>> {
    /** The join: the least-upper-bound of `this` and [other]. */
    public fun piece(other: S): S
}

/**
 * A delta produced by a mutator: a small element of the same lattice as [S].
 * A delta *is* a state fragment, so it is absorbed by the very same
 * [Quilted.piece] join — see [piece].
 */
@JvmInline
public value class Patch<S : Quilted<S>>(public val delta: S)

/** Absorb a [patch] into this state via [Quilted.piece]. */
public fun <S : Quilted<S>> S.piece(patch: Patch<S>): S = piece(patch.delta)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kuilt-crdt:jvmTest`
Expected: PASS — all four tests green.

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Quilted.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/QuiltedLawsTest.kt
git rm --cached kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/.gitkeep 2>/dev/null || true
git commit -m "feat(kuilt-crdt): Quilted join-semilattice interface + Patch delta type"
```

---

## Task 3: The reusable `QuiltedConformanceSuite`

A subclassable contract suite that checks the three laws plus the least-upper-bound absorption property over a caller-supplied set of representative samples. Deterministic and multiplatform (no property-based framework — jqwik is JVM-only and arrives with concrete types later). Lives in `commonMain` of `:kuilt-conformance` so any future CRDT type validates itself by subclassing.

**Files:**
- Modify: `kuilt-conformance/build.gradle.kts`
- Create: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/QuiltedConformanceSuite.kt`
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/IntMaxConformanceTest.kt`

- [ ] **Step 1: Add the `:kuilt-crdt` dependency**

In `kuilt-conformance/build.gradle.kts`, inside `commonMain.dependencies { ... }`, add after the existing `api(project(":kuilt-raft"))` line:

```kotlin
            api(project(":kuilt-crdt"))
```

- [ ] **Step 2: Write the failing test (the suite's self-validation)**

Create `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/IntMaxConformanceTest.kt`:

```kotlin
package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Quilted

/** Smallest possible lattice — max-wins integer — used to validate the suite. */
private data class IntMax(val value: Int) : Quilted<IntMax> {
    override fun piece(other: IntMax): IntMax = IntMax(maxOf(value, other.value))
}

/** If the suite is correct, IntMax (a genuine join-semilattice) passes every law. */
class IntMaxConformanceTest : QuiltedConformanceSuite<IntMax>() {
    override fun samples(): List<IntMax> =
        listOf(IntMax(0), IntMax(3), IntMax(7), IntMax(-2))
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :kuilt-conformance:jvmTest`
Expected: FAIL — compilation error, `unresolved reference: QuiltedConformanceSuite`.

- [ ] **Step 4: Write the suite**

Create `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/QuiltedConformanceSuite.kt`:

```kotlin
package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.Quilted
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reusable contract test suite for [Quilted] (delta-state CRDT) implementations.
 *
 * Subclass and implement [samples] to bind any type under test; every [Test]
 * encodes a law a conforming join-semilattice must satisfy. Lives in
 * `commonMain` of `:kuilt-conformance` (not a module's `commonTest`) so every
 * CRDT type can subclass it from its own test source set.
 *
 * ```kotlin
 * class GCounterConformanceTest : QuiltedConformanceSuite<GCounter>() {
 *     override fun samples(): List<GCounter> = listOf(/* representative values */)
 * }
 * ```
 *
 * [samples] must return at least **three distinct** values for the associativity
 * and absorption checks to be meaningful; more variety is better. The type's
 * `equals` must reflect lattice equality — the laws are checked with `==`.
 */
public abstract class QuiltedConformanceSuite<S : Quilted<S>> {

    /** Representative, distinct sample values (≥ 3). */
    public abstract fun samples(): List<S>

    @Test
    public fun pieceIsIdempotent() {
        for (a in samples()) {
            assertEquals(a, a.piece(a), "piece must be idempotent for $a")
        }
    }

    @Test
    public fun pieceIsCommutative() {
        val s = samples()
        for (a in s) for (b in s) {
            assertEquals(a.piece(b), b.piece(a), "piece must be commutative for $a, $b")
        }
    }

    @Test
    public fun pieceIsAssociative() {
        val s = samples()
        for (a in s) for (b in s) for (c in s) {
            assertEquals(
                a.piece(b).piece(c),
                a.piece(b.piece(c)),
                "piece must be associative for $a, $b, $c",
            )
        }
    }

    @Test
    public fun pieceIsLeastUpperBound() {
        // The join of a and b must absorb both operands: merging either back in
        // changes nothing. This is what makes resends and reordering harmless.
        val s = samples()
        for (a in s) for (b in s) {
            val joined = a.piece(b)
            assertEquals(joined, joined.piece(a), "join must absorb left operand $a")
            assertEquals(joined, joined.piece(b), "join must absorb right operand $b")
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :kuilt-conformance:jvmTest`
Expected: PASS — the four inherited law tests run against `IntMax` and pass.

- [ ] **Step 6: Commit**

```bash
git add kuilt-conformance/build.gradle.kts \
        kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/QuiltedConformanceSuite.kt \
        kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/IntMaxConformanceTest.kt
git commit -m "feat(kuilt-conformance): QuiltedConformanceSuite — reusable CRDT-law suite"
```

---

## Task 4: Full multiplatform verification + PR

- [ ] **Step 1: Confirm explicitApi compliance and all platforms compile**

Run: `./gradlew :kuilt-crdt:build :kuilt-conformance:build`
Expected: BUILD SUCCESSFUL. `explicitApi()` is enforced by `kuilt.kmp-library`; any public declaration missing a visibility modifier fails here. All three new public declarations (`Quilted`, `Patch`, the `piece` extension) and the suite are explicitly `public`.

- [ ] **Step 2: Run the law suite across all platforms**

Run: `./gradlew :kuilt-crdt:allTests :kuilt-conformance:allTests`
Expected: BUILD SUCCESSFUL — laws hold on JVM, wasmJs, iOS sim, macOS (per the project's target set).

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin <branch>
gh pr create --title "feat(kuilt-crdt): Rung 0 — delta-state foundation (Quilted + conformance suite)" --body "<see below>"
```

PR body:

```
> 🤖 This comment was generated by Claude on behalf of @keddie.

Rung 0 of the :kuilt-crdt zoo (design: docs/superpowers/specs/2026-06-06-crdt-module-design.md).

- New :kuilt-crdt module with the `Quilted<S>` join-semilattice interface and `Patch<S>` delta type (quilting metaphor on the interface; `piece()` = join).
- `QuiltedConformanceSuite` in :kuilt-conformance — reusable CRDT-law contract suite (idempotent · commutative · associative · least-upper-bound), validated against a toy `IntMax` lattice.

Foundation only — no concrete types yet. Next rung: Dots + causal context.

Closes <sub-issue #>.
```

- [ ] **Step 4: Enable auto-merge**

Run: `gh pr merge <n> --auto --squash`
Expected: auto-merge enabled; lands when `ci-required` is green.

---

## Self-Review notes

- **Spec coverage:** This plan implements exactly the spec's Rung 0 ("Module scaffold + `Quilted`/`Patch` interface + generic CRDT-law conformance suite in `:kuilt-conformance`"). Rungs 1–13 are out of scope here and get their own plans.
- **Property-based testing:** The spec notes a soft coupling to the in-flight raft property-based-testing work. Rung 0 deliberately uses a deterministic, sample-based suite (multiplatform; jqwik is JVM-only) so it is **not** blocked by that work. Property-based generators layer on at the first concrete type.
- **Type consistency:** `Quilted<S>`, `piece`, `Patch<S>`, and `samples()` are named identically across Tasks 2–3 and the suite KDoc example. The `IntMax` toy lattice is duplicated intentionally (once in `:kuilt-crdt` tests, once in `:kuilt-conformance` tests) because the two modules' test source sets cannot see each other.
