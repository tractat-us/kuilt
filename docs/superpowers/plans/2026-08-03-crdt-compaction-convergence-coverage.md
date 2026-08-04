# CRDT Compaction Convergence Coverage — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `CrdtConvergenceHarness` reach `compact()` for `Rga`, `Fugue` and `MovableTree`, in
the two ways that between them pin all three compaction-canonicalisation mechanisms — and pin the
coverage so it cannot silently rot back to zero.

**Architecture:** Two new phases run after the existing convergence assertions, which are preserved
unchanged. **Phase A** merges every permutation and *then* compacts to stable. **Phase B** compacts
each replica *before* any merge, then folds. Both assert equality and byte-identity against a
canonical reference. The harness derives the cut itself, from `causalDots()`, as `S = F = delivered`
— literally what `Quilter.recomputeCut` produces for a converged room (Phase A) and for a solo peer
(Phase B). Per-type adapters supply only the `compact()` call. Coverage floors, asserted on a new
`CompactableCrdtConvergenceSuite`, guard against the phases becoming no-ops.

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization (CBOR), kotlin-test. Gradle 9.4.1, JDK 21.

**Spec:** [`docs/superpowers/specs/2026-08-03-crdt-compaction-convergence-coverage-design.md`](../specs/2026-08-03-crdt-compaction-convergence-coverage-design.md)
· **Issue:** [#2019](https://github.com/tractat-us/kuilt/issues/2019)

> **Two figures in #2019 are wrong — do not plan against them.** There are **16** bound convergence
> suites, not "~19", and **13 of them need no edit**. And **`JsonCrdt` has no `compact()`** — the
> compactable set is exactly `Rga`, `Fugue`, `MovableTree`. The issue body has been corrected;
> the design doc carries the detail.

## Global Constraints

- **JDK/toolchain:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` in every
  non-interactive shell. In an `isolation: "worktree"` agent that `source` is refused — use
  `export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem` instead.
- **`explicitApi()` is enforced.** Every new public declaration needs an explicit `public`.
- **`detektAll`, never bare `detekt`** — bare `detekt` is `NO-SOURCE` here and is a false green.
- **Test naming:** no `test` prefix. Multi-assert tests use `assertAll()` from `us.tractat.kuilt.test`.
- **`jvmTest` is meaningful here but not sufficient.** The fields under test are
  `LinkedHashSet`/`LinkedHashMap`-backed, so insertion order shows through on the JVM too — a JVM
  red is real. A JVM green is not evidence: every mutation verdict in this plan must also be taken
  on `:kuilt-conformance:wasmJsBrowserTest` and, locally, `:kuilt-conformance:macosArm64Test`
  (Apple targets are nightly-only in CI). `wasmJsTest` is a lifecycle task that rejects `--tests`.
- **A mutation that fails to compile is never a red test.** Check the build exit code before reading
  any verdict; an XML-parsing harness will otherwise report the *previous* mutation's result.
- **`runCompactionPhases` is long enough to trip detekt's `LongMethod`.** If it does, split it into
  `runPostMergePhase` and `runPreMergePhase` returning their per-run counters — do **not** raise the
  threshold, and do not merge the two phases to shorten it.
- **Never use the word "chore".**
- **Commit messages end with:** `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
- **Backticks do NOT disarm closing keywords in commit messages** — a squash turns the PR body into
  one. Use **"part of #2019"** on every commit and PR here. Do not write a closing keyword at all.

---

## File Structure

| File | Responsibility |
|---|---|
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/VersionVector.kt` | **Modify.** Gain `VersionVector.contiguous(dots)`, moved from `:kuilt-quilter`. |
| `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt` | **Modify.** `contiguousFrontier` becomes a one-line delegate. |
| `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/convergence/CrdtCompactor.kt` | **Create.** `CrdtCompactor`, `CompactionStep`, `CompactionCoverage`. |
| `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/convergence/CrdtConvergenceHarness.kt` | **Modify.** The two phases, the cut derivation, the accumulator. |
| `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/convergence/CompactableCrdtConvergenceSuite.kt` | **Create.** The coverage test and its per-type floors. |
| `kuilt-conformance/src/commonTest/.../convergence/RgaConvergenceTest.kt` | **Modify.** Bind. |
| `kuilt-conformance/src/commonTest/.../convergence/FugueConvergenceTest.kt` | **Modify.** Bind; `opsPerReplica` 8 → 16. |
| `kuilt-conformance/src/commonTest/.../convergence/MovableTreeConvergenceTest.kt` | **Modify.** Bind. |
| `docs/op-log-crdt-compaction.md` | **Modify.** Record that the generator now covers this class. |

`:kuilt-conformance` has no `module.md` — do not go looking for one.

**Dependency order:** Task 0 → Task 1 → Task 2 → {Task 3a ∥ Task 3b ∥ Task 3c} → Task 4.
**Tasks 3a, 3b and 3c are fully parallelizable** — one test file each, no shared file, no shared
symbol. Dispatch them together. Everything else is serial.

---

### Task 0: Reproduce the vacuity — MANDATORY AND BLOCKING

> **Do not start Task 1 until Task 0's four verdicts are recorded.** No code lands in this task, and
> that is exactly why it is skippable-looking and must not be skipped: it is the step that makes
> every later "now it goes red" mean something. Without it, a green suite after the change is
> indistinguishable from a green suite that was always going to be green. Asserting first and
> mutating afterwards tempts you to read success into the wrong evidence — the failure mode this
> whole issue is about.

This produces the baseline: proof that the coverage being added does not already exist.

**Files:** none (every edit is reverted).

- [ ] **Step 1: Confirm the premise — all three suites green on unmodified `main`**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem
./gradlew :kuilt-conformance:jvmTest --tests "*RgaConvergenceTest*" \
  --tests "*FugueConvergenceTest*" --tests "*MovableTreeConvergenceTest*"
```

- [ ] **Step 2: Revert each mechanism in turn and confirm the suites stay green**

Copy each file to a scratch path first and restore from the copy — **never `git stash`**, which is
repo-global across every linked worktree.

| # | Mechanism | Edit |
|---|---|---|
| M1 | `Rga` `Compact.positions` map order | `RgaOpSerializer.positionsSerializer`: `CanonicalMapSerializer(rgaIdSerializer, rgaIdSerializer)` → `MapSerializer(…)` |
| M2 | `Fugue` `Compact.positions` map order | `FugueOpSerializer.positionsSerializer`: `CanonicalMapSerializer(idSerializer, idSerializer)` → `MapSerializer(…)` |
| M3 | `MovableTree.compactedDots` set order | delete the `@Serializable(with = CanonicalSetSerializer::class)` line above `compactedDots` |
| M4 | inter-`Compact`-op order | `compareCompactPositions`: insert `if (a.size >= 0) return 0` as the first statement |

> ## ⚠ STOP — mutate `RgaOpSerializer`, NOT the annotation
>
> **For M1 and M2, mutate `RgaOpSerializer.positionsSerializer` / `FugueOpSerializer.positionsSerializer`
> — NOT the `@Serializable(with = CanonicalMapSerializer::class)` annotation on
> `RgaOp.Compact.positions` / `FugueOp.Compact.positions`.**
>
> Both exist, and the annotation is the one you will reach for first. It is the wrong one:
> `RgaConvergenceTest` encodes through `Rga.wireSerializer` → `RgaSerializer` → `RgaOpSerializer`,
> and a **hand-written enclosing `KSerializer` ignores the annotation entirely.** Mutating only the
> annotation leaves the suite green — and a green mutation reads as *"the assertion has no teeth"*
> when the truth is *"you mutated a path the test never takes"*. That inversion already cost a round
> on #1978, where an `@Serializable(with = …)` annotation was proposed as the fix and would have
> fixed nothing on the wire.
>
> `MovableTreeConvergenceTest` is the **opposite** case: it uses the compiler-generated
> `MovableTree.serializer(…)`, so for M3 the annotation *is* the wire path and deleting it is
> correct.
>
> Rule of thumb: before trusting any mutation verdict here, confirm the symbol you edited is on the
> path from the test's `serializer` argument to the bytes.

Expected for all four, on JVM **and** `wasmJsBrowserTest` **and** `macosArm64Test`: **GREEN**.
Record the four green verdicts. Restore every file and confirm `git status --short` is clean.

- [ ] **Step 3: Paste the four verdicts into the first PR body**

The claim "no convergence generator reaches `compact()`" is now a receipt rather than an assertion.

---

### Task 1: Move `contiguousFrontier` to `:kuilt-crdt` — a PUBLIC-API addition

Behaviour-preserving, and independently reviewable as such. It exists because the harness must
derive its cut with the *same* function `Quilter` uses — two copies of the quantity whose sameness
is the design's central argument would defeat the argument.

> **This is a public-API addition to `:kuilt-crdt`, not an internal move, and carries a different
> review bar than the rest of this plan.** `explicitApi()` is enforced, so it needs an explicit
> `public` and real consumer-facing KDoc — not the terse internal comment it has today. Per repo
> convention a documented public entry point also gets a `@sample`, and samples compile as part of
> `commonTest`, so a broken one breaks the build. Budget for all three; do not land it as a rename.

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/VersionVector.kt`
- Modify: `kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt`
- Modify: `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt`

**Interfaces:**
- Produces: `public fun VersionVector.Companion.contiguous(dots: Set<Dot>): VersionVector`.
- Consumes: nothing.

- [ ] **Step 1: Confirm the existing tests are green — they are the oracle**

```bash
./gradlew :kuilt-quilter:jvmTest --tests "*ContiguousFrontierTest*" \
  --tests "*DeliveredFrontierRegressionTest*" --tests "*RgaDeliveryTrackingAuditTest*"
```

- [ ] **Step 2: Add `contiguous` to `VersionVector`'s companion**

Move the body verbatim, including `contiguousHighWater`, into `VersionVector.kt`:

```kotlin
        /**
         * The contiguous (gap-free) frontier of [dots]: for each author, the highest `seq` such
         * that every seq in `1..seq` is present. A gap stops the frontier at the gap — dots
         * `{1, 2, 4}` for one author yield high-water `2`. Authors with no dot at `seq == 1`
         * contribute nothing (omitted, reading as `0`).
         *
         * This is the **delivered** quantity of the causal-stability barrier (ADR-003 addendum v3):
         * `Quilter` folds it into `deliveredLocal`, and `:kuilt-conformance`'s convergence harness
         * derives its compaction cut from it. Both must be the same function, or the harness's cut
         * is a look-alike rather than the production one — which is the whole basis of its
         * reachability claim (#2019).
         */
        public fun contiguous(dots: Set<Dot>): VersionVector {
            val seqsByAuthor: Map<ReplicaId, Set<Long>> = dots
                .groupBy(keySelector = { it.replica }, valueTransform = { it.seq })
                .mapValues { (_, seqs) -> seqs.toSet() }
            return of(seqsByAuthor.mapValues { (_, seqs) -> contiguousHighWater(seqs) })
        }

        /** The highest `n` such that `1..n` are all in [seqs]; `0` if `1` is absent. */
        private fun contiguousHighWater(seqs: Set<Long>): Long {
            var n = 0L
            while ((n + 1L) in seqs) n++
            return n
        }
```

The KDoc above is written for a consumer, not for the one existing caller — that is the point of
promoting it. Add the `@sample` tag to it:

```kotlin
         * @sample us.tractat.kuilt.crdt.sampleVersionVectorContiguous
```

- [ ] **Step 2b: Write the sample**

Append to `kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt`, matching the
file's existing shape (`internal fun`, `@Suppress("unused")`, `check(...)` assertions, a one-line
KDoc that reads plainly). The sample must show the property that makes the function worth having —
**a gap stops the frontier**:

```kotlin
// ── VersionVector.contiguous ─────────────────────────────────────────────────

/** A gap stops the frontier: an author's high-water is the last seq with no hole below it. */
@Suppress("unused")
internal fun sampleVersionVectorContiguous() {
    val phone = ReplicaId("phone")
    val watch = ReplicaId("watch")

    // The phone's ops 1, 2 and 4 arrived — 3 is still missing.
    val delivered = VersionVector.contiguous(
        setOf(Dot(phone, 1), Dot(phone, 2), Dot(phone, 4), Dot(watch, 1)),
    )

    // The phone counts as delivered only up to 2: everything past the hole is held back.
    check(delivered[phone] == 2L)
    check(delivered[watch] == 1L)
}
```

- [ ] **Step 3: Make `:kuilt-quilter`'s `contiguousFrontier` a delegate**

Replace the body in `Quilter.kt` with `VersionVector.contiguous(dots)` and delete the now-unused
private `contiguousHighWater`. **Keep the `internal fun contiguousFrontier` name** — five test files
reference it, and keeping it means zero test churn in this task.

- [ ] **Step 4: Verify no behaviour changed**

```bash
./gradlew :kuilt-crdt:build :kuilt-quilter:build detektAll --rerun-tasks
```

Expected: PASS with **no test edited**. If a test needed changing, the move was not
behaviour-preserving — stop and re-read. Confirm the tasks show `EXECUTED`, not `FROM-CACHE`.

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/VersionVector.kt \
        kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt
git commit -m "refactor(crdt): move the contiguous-frontier fold next to VersionVector (part of #2019)

It is pure Dot/VersionVector logic with no coroutine or Seam content, and the convergence
harness in :kuilt-conformance needs the same quantity to derive a compaction cut. Sharing one
implementation is what makes that cut the production cut rather than a look-alike.
Quilter keeps contiguousFrontier as a delegate, so no test moves. No behaviour change.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: The harness phases, with no type bound to them

Adds the whole mechanism and binds nothing, so **all 16 convergence suites must stay green with no
test file edited**. That is what proves the API change is additive.

**Files:**
- Create: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/convergence/CrdtCompactor.kt`
- Create: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/convergence/CompactableCrdtConvergenceSuite.kt`
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/convergence/CrdtConvergenceHarness.kt`

**Interfaces:**
- Consumes: `VersionVector.contiguous` (Task 1).
- Produces: `CrdtCompactor<S>`, `CompactionStep<S>`, `CompactionCoverage`,
  `CrdtConvergenceHarness(…, compactor)`, `CrdtConvergenceHarness.compactionCoverage`,
  `CompactableCrdtConvergenceSuite<S>`.

- [ ] **Step 1: Create the adapter types**

`CrdtCompactor.kt`:

```kotlin
package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.VersionVector

/** One compaction step: the resulting [state] and how many ids it dropped. */
public class CompactionStep<S>(public val state: S, public val droppedCount: Int)

/**
 * Invokes one compaction of [state] at a harness-derived cut, or returns `null` when nothing
 * qualifies.
 *
 * The adapter receives the state and the cut and **nothing else** — no seed, no permutation index,
 * no sibling replicas. That is deliberate: a compactor that could see the permutation could tailor
 * a cut per fold order and manufacture the agreement the harness is trying to test. The cut is
 * derived by [CrdtConvergenceHarness] from `causalDots()`, so a binding cannot supply a synthetic
 * one (#2019).
 *
 * A binding's whole body is one call to its type's own `compact(stableCut, frontierMax, delivered)`
 * plus unwrapping the returned pair.
 */
public fun interface CrdtCompactor<S> {
    public fun compactOnce(
        state: S,
        stableCut: VersionVector,
        frontierMax: VersionVector,
        delivered: VersionVector,
    ): CompactionStep<S>?
}

/**
 * What the compaction phases actually reached, accumulated across one harness instance's runs.
 *
 * Reaching `compact()` is not the same as being able to *observe* a defect in what it produces — a
 * step that drops one id has exactly one key order, so a canonical-ordering mutation is invisible
 * at that size. These counters exist so a suite can assert reach, and so a future change that
 * quietly stops reaching fails loudly rather than passing vacuously.
 *
 * A harness is constructed per test method, so the accumulator is per-test.
 */
public data class CompactionCoverage(
    /** Runs (seeds) in which the compaction phases executed. */
    public val runs: Int = 0,
    /** Phase A: runs where compacting the merged state changed it. */
    public val postMergeRunsWithCompaction: Int = 0,
    /** Phase A: the largest single step seen. Below 2 there is no key order to disagree about. */
    public val postMergeMaxDroppedInOneStep: Int = 0,
    /**
     * Phase B: runs where **two or more** replicas each compacted before the merge. That is what
     * makes the merged compaction record's iteration order depend on the fold order — with one
     * compacting replica the merge has nothing to interleave.
     */
    public val preMergeRunsWithTwoOrMoreCompacting: Int = 0,
)
```

- [ ] **Step 2: Add the phases to the harness**

In `CrdtConvergenceHarness`, add the trailing constructor parameter and the accumulator:

```kotlin
    public val compactor: CrdtCompactor<S>? = null,
) {
    private var coverage: CompactionCoverage = CompactionCoverage()

    /** What the compaction phases reached across this instance's runs. Empty when no compactor. */
    public val compactionCoverage: CompactionCoverage get() = coverage
```

Change `assertAllPermutationsConverge` to **return** its permutation results (it is private; the
assertions inside it must not change by one character), and extend `run`:

```kotlin
    public fun run(seed: Long): S {
        val random = Random(seed)
        val replicas = buildReplicas(random)
        val canonical = mergeAll(replicas)
        val results = assertAllPermutationsConverge(replicas, canonical)
        compactor?.let { runCompactionPhases(it, replicas, results) }
        return canonical
    }
```

`run` still returns the **pre-compaction** state, so no existing caller's meaning moves.

Add the phases:

```kotlin
    /** The cut a fully-converged room (phase A) or a solo peer (phase B) publishes: `S = F = D`. */
    private fun cutOf(state: S): VersionVector = VersionVector.contiguous(state.causalDots())

    private fun compactToStable(c: CrdtCompactor<S>, start: S): Triple<S, Int, Int> {
        var state = start
        var steps = 0
        var maxDropped = 0
        while (true) {
            val cut = cutOf(state)
            val step = c.compactOnce(state, stableCut = cut, frontierMax = cut, delivered = cut) ?: break
            state = step.state
            steps++
            maxDropped = maxOf(maxDropped, step.droppedCount)
        }
        return Triple(state, steps, maxDropped)
    }

    private fun runCompactionPhases(c: CrdtCompactor<S>, replicas: List<S>, results: List<Pair<List<Int>, S>>) {
        requireDisjointAuthors(replicas)

        // Phase A — merge, THEN compact. Varies the tombstone set the predicate walks, so it pins
        // the order WITHIN one Compact op's positions map (#1978).
        val (canonicalA, stepsA, maxDroppedA) = compactToStable(c, results.first().second)
        val canonicalABytes = encoded(canonicalA)
        for ((permutation, result) in results) {
            val (compacted, _, _) = compactToStable(c, result)
            checkConverged(compacted, canonicalA, canonicalABytes, "phase A (compact after merge)", permutation)
        }

        // Phase B — compact each replica ALONE, then merge. Each op's own map order is fixed at
        // mint time, so what varies instead is the merge of already-compacted states: the union of
        // several Compact ops (#713) and MovableTree's compactedDots set (#1957). Phase A reaches
        // neither, which is why both phases exist.
        var replicasCompacting = 0
        val compactedReplicas = replicas.map { replica ->
            val (state, _, dropped) = compactToStable(c, replica)
            if (dropped > 0) replicasCompacting++
            state
        }
        val canonicalB = mergeAll(compactedReplicas)
        val canonicalBBytes = encoded(canonicalB)
        for (permutation in permutationsOf(compactedReplicas.indices.toList())) {
            val folded = permutation.fold(initial) { acc, idx -> acc.piece(compactedReplicas[idx]) }
            checkConverged(folded, canonicalB, canonicalBBytes, "phase B (compact before merge)", permutation)
        }

        coverage = CompactionCoverage(
            runs = coverage.runs + 1,
            postMergeRunsWithCompaction = coverage.postMergeRunsWithCompaction + if (stepsA > 0) 1 else 0,
            postMergeMaxDroppedInOneStep = maxOf(coverage.postMergeMaxDroppedInOneStep, maxDroppedA),
            preMergeRunsWithTwoOrMoreCompacting =
                coverage.preMergeRunsWithTwoOrMoreCompacting + if (replicasCompacting >= 2) 1 else 0,
        )
    }

    private fun checkConverged(result: S, expected: S, expectedBytes: ByteArray, phase: String, permutation: List<Int>) {
        check(result == expected) { "Convergence failure in $phase under permutation $permutation:\n  expected $expected\n  got      $result" }
        val bytes = encoded(result)
        check(bytes.contentEquals(expectedBytes)) {
            "Canonical-encoding failure in $phase under permutation $permutation:\n" +
                "  expected bytes ${expectedBytes.toHexString()}\n" +
                "  got      bytes ${bytes.toHexString()}\n" +
                "  state    $expected"
        }
    }

    /**
     * Phase B compacts each replica at a cut derived from its own history, which is sound only
     * because the replicas' histories are **disjoint** — no peer can hold a concurrent op
     * referencing another's, so the no-surviving-successor / no-surviving-anchor conditions are
     * evaluated over a set nobody can add to behind the compactor's back. A generator that gave two
     * replicas the same author id would break that premise silently, so it is asserted (#2019).
     */
    private fun requireDisjointAuthors(replicas: List<S>) {
        val authors = replicas.map { r -> r.causalDots().mapTo(mutableSetOf()) { it.replica } }
        for (i in authors.indices) {
            for (j in i + 1 until authors.size) {
                val shared = authors[i].intersect(authors[j])
                check(shared.isEmpty()) {
                    "Compaction phases require per-replica author disjointness; replicas $i and $j share $shared"
                }
            }
        }
    }
```

> **Phase A's reference is `results.first().second`, not `canonical`.** They are equal by the
> assertion that just ran, so either works — but reading it from the permutation list keeps the
> reference on the same footing as the values it is compared against, rather than quietly making the
> natural-order fold privileged.

Extend the class KDoc with a paragraph naming the two phases, and state the disjoint-author
precondition there as well as in the check.

- [ ] **Step 3: Create the compactable suite**

`CompactableCrdtConvergenceSuite.kt`:

```kotlin
package us.tractat.kuilt.conformance.convergence

import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [CrdtConvergenceSuite] for a type that can garbage-collect its own history. Extending this is how
 * a compactable type opts in — deliberately, rather than by a flag that silently no-ops.
 *
 * The floors below are **reach** floors: they fail when the generator stops driving compaction, or
 * stops driving it big enough for a key-order defect to be observable. They are not proof that the
 * assertions have teeth — that is a mutation matrix, recorded per type when the binding lands.
 * Each floor's KDoc carries the value measured when it was set, so a later number that passes the
 * floor but sits far below the measurement still reads as a regression.
 */
public abstract class CompactableCrdtConvergenceSuite<S : Quilted<S>> : CrdtConvergenceSuite<S>() {

    /** Minimum seeds (of 32) whose merged state must compact. */
    public abstract val minPostMergeRunsWithCompaction: Int

    /** Minimum seeds (of 32) where two or more replicas must each compact before the merge. */
    public abstract val minPreMergeRunsWithTwoOrMoreCompacting: Int

    /** Minimum ids one step must drop on some seed. Below 2 there is no order to disagree about. */
    public open val minPostMergeMaxDroppedInOneStep: Int = 2

    @Test
    public fun compactionCoverageIsNonVacuous() {
        val harness = newHarness()
        harness.runSeeds(0L..31L)
        val c = harness.compactionCoverage
        assertAll(
            { assertTrue(c.runs == 32, "compaction phases must run on every seed; ran on ${c.runs}") },
            {
                assertTrue(
                    c.postMergeRunsWithCompaction >= minPostMergeRunsWithCompaction,
                    "phase A compacted on only ${c.postMergeRunsWithCompaction}/32 seeds " +
                        "(floor $minPostMergeRunsWithCompaction) — the phase is drifting toward a no-op",
                )
            },
            {
                assertTrue(
                    c.postMergeMaxDroppedInOneStep >= minPostMergeMaxDroppedInOneStep,
                    "largest phase-A step dropped ${c.postMergeMaxDroppedInOneStep} id(s) " +
                        "(floor $minPostMergeMaxDroppedInOneStep) — a one-id step has a single key " +
                        "order, so the byte assertion over it pins nothing",
                )
            },
            {
                assertTrue(
                    c.preMergeRunsWithTwoOrMoreCompacting >= minPreMergeRunsWithTwoOrMoreCompacting,
                    "only ${c.preMergeRunsWithTwoOrMoreCompacting}/32 seeds had two or more replicas " +
                        "compact before the merge (floor $minPreMergeRunsWithTwoOrMoreCompacting) — " +
                        "with fewer, the merge has no compaction records to interleave",
                )
            },
        )
    }
}
```

- [ ] **Step 4: Prove the change is additive**

```bash
./gradlew :kuilt-conformance:build detektAll --rerun-tasks
```

Expected: PASS, **with no file under `commonTest` edited**. `git status --short` must show only the
three commonMain files. If any of the 16 suites needed a change, the parameter was not added as a
trailing default — fix that rather than the suite.

- [ ] **Step 5: Commit**

```bash
git add kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/convergence/
git commit -m "feat(conformance): add post-merge and pre-merge compaction phases to the convergence harness (part of #2019)

Two phases, because one is not enough: compacting AFTER the merge varies the tombstone set the
predicate walks, so it pins the order within one Compact op's positions map; compacting each
replica BEFORE the merge fixes that order at mint time and instead varies the merge of
already-compacted states, which is the only way to reach MovableTree.compactedDots and the
order between several Compact ops. Measured: reverting each mechanism turns exactly one of the
two phases red, never both.

No type binds a compactor yet, so all 16 convergence suites are green with no test file edited.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3a / 3b / 3c: bind `Rga`, `Fugue`, `MovableTree`

**These three run in parallel.** One test file each, no shared file or symbol. Each is a complete
slice: bind, measure, pin, mutate, record.

Common shape — do all five steps for your type:

- [ ] **Step 1: Bind**

Change the class to extend `CompactableCrdtConvergenceSuite`, add the `compactor` argument, and
declare the floors. The compactor body is one call:

| Task | Type | compactor body | `opsPerReplica` |
|---|---|---|---|
| 3a | `Rga<String>` | `state.compact(stableCut, frontierMax, delivered)?.let { CompactionStep(it.first, it.second.positions.size) }` | 8, unchanged |
| 3b | `Fugue<String>` | `state.compact(stableCut, frontierMax, delivered)?.let { CompactionStep(it.first, it.second.positions.size) }` | **8 → 16** |
| 3c | `MovableTree<String>` | `state.compact(stableCut, frontierMax, delivered)?.let { CompactionStep(it.first, it.second.droppedDots.size) }` | 8, unchanged |

Task 3b's `opsPerReplica` bump is the only generator change in this plan. **Approved on 2026-08-03
as a measured choice, not a round number** — record these figures in the test's KDoc so a later
reader can tell:

| `Fugue` at `opsPerReplica` | seeds reaching phase B | seeds reaching phase A | M2 byte failures (of 192) |
|---|---|---|---|
| 8 (today) | 7/32 | 22/32 | 23 |
| **16 (adopted)** | **19/32** | **26/32** | **67** |

More ops can only add coverage, but it changes an existing load-bearing test's shape, so Step 4
re-runs `Fugue`'s M2 mutation at the new size and confirms it is still red — and it is redder.

- [ ] **Step 2: Measure, then pin**

Temporarily print `harness.compactionCoverage` after `runSeeds`, run `jvmTest`, record the four
numbers, remove the print. Set each floor to ~75 % of the measured value, rounded down, and put the
measured value and today's date in its KDoc. Starting points from the design's measurements:

| type | phase-A runs (measured → floor) | max dropped (measured → floor) | phase-B runs (measured → floor) |
|---|---|---|---|
| `Rga` | 32 → 24 | 8 → 2 | 25 → 18 |
| `Fugue` @16 | 26 → 19 | 4 → 2 | 19 → 14 |
| `MovableTree` | 30 → 22 | 10 → 2 | 28 → 21 |

If your measurement disagrees with the table, **your measurement wins** — record both and say so.

- [ ] **Step 3: Confirm the floors are load-bearing**

Raise one floor above its measured value, confirm `compactionCoverageIsNonVacuous` fails with the
message you wrote, restore. A coverage assertion nobody has watched fail is decoration.

- [ ] **Step 4: Run your type's mutation matrix — the actual proof**

Per Task 0's table, on **JVM, `wasmJsBrowserTest`, and `macosArm64Test`**, checking the build exit
code before reading any verdict:

| Task | mutation | must go RED | must stay green |
|---|---|---|---|
| 3a | M1 (`RgaOpSerializer.positionsSerializer`) | phase A | phase B |
| 3a | M4 (`compareCompactPositions`) | phase B | phase A |
| 3b | M2 (`FugueOpSerializer.positionsSerializer`) | phase A | phase B |
| 3b | M4 (`compareCompactPositions`) | phase B | phase A |
| 3c | M3 (`MovableTree.compactedDots` annotation) | phase B | phase A |

Note the *must stay green* column: it is not decoration. It is what proves the two phases are
non-redundant, and it is the finding that killed the post-merge-only design. **M4 is shared by 3a
and 3b** — it lives in `OpLogEngine.kt`, so if 3a and 3b run concurrently in separate worktrees they
each mutate their own copy; if they somehow share one, serialise this step.

**Task 3c's phase A is expected to pin none of `MovableTree`'s current mechanisms** — that is the
measured result, not a defect in your binding. Phase A still asserts that a compacted merged state
converges and encodes identically under every fold, and is a standing net for a mechanism this type
does not have yet. Do not "fix" a green phase A for `MovableTree`.

- [ ] **Step 5: Also re-run one phase-0 mutation**

Revert a canonical serializer with nothing to do with compaction — `GSetConvergenceTest`'s element
ordering is the cheapest — and confirm the **pre-compaction** byte assertion still fails. The new
phases must not have un-pinned the old one (#1872, #2002).

- [ ] **Step 6: Gate and commit**

```bash
./gradlew :kuilt-conformance:build detektAll --rerun-tasks
./gradlew :kuilt-conformance:wasmJsBrowserTest
./gradlew :kuilt-conformance:macosArm64Test
```

Commit with the mutation verdicts pasted in, e.g.:

```bash
git commit -m "test(conformance): drive Rga through both compaction phases (part of #2019)

Reverting RgaOpSerializer's CanonicalMapSerializer now reddens phase A (101 byte mismatches of
192, JVM; same on wasmJs and macosArm64) and reverting compareCompactPositions reddens phase B
(101). Before this, both left RgaConvergenceTest green — which is why #1978 needed a dedicated
test the generator could not have found.

Coverage floors pinned at 75% of measured: phase A 24/32 (measured 32), largest step 2
(measured 8), phase B 18/32 (measured 25). Verified load-bearing by raising each above its
measurement and watching it fail.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Record the coverage where the next reader will look

**Files:**
- Modify: `docs/op-log-crdt-compaction.md`

- [ ] **Step 1: Say what is now covered, and what still is not**

State that the convergence generator drives compaction in two phases and which mechanism each
phase pins. **Do not enumerate open gaps or count remaining unpinned fields** — a doc that lists
open work goes stale on the next fix. Enumerate only what is monotonically true.

Add the one sentence a future compactable type needs: *extend
`CompactableCrdtConvergenceSuite`, not `CrdtConvergenceSuite`.*

- [ ] **Step 2: Accessible-first re-read**

Re-read the page top-to-bottom and confirm it still opens in plain language with the technical
depth below. Required by the repo's docs rule and easy to lose in an edit like this one.

- [ ] **Step 3: Verify citations and commit**

```bash
./gradlew verifyDocCitations
git add docs/op-log-crdt-compaction.md
git commit -m "docs(crdt): the convergence generator now drives compaction (part of #2019)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Wrap-up (after Task 4)

- [ ] Rebase onto current `origin/main`: `git fetch origin main && git rebase origin/main`
- [ ] Full cache-disabled gate: `./gradlew build detektAll --rerun-tasks` — confirm `EXECUTED`, not
      `FROM-CACHE`
- [ ] Open the PR **ready, not draft** — a drafted-then-readied PR leaves a stale `ci-required`
      FAILURE. Body: the Task 0 receipts, the mutation matrix, and the coverage floors.
- [ ] Whether the final PR may close #2019 is Iain's call — the issue asks for compaction coverage
      and this delivers it for all three compactable types, but "part of #2019" is the safe default
      and the issue body should be updated either way. Backticks do not disarm the keyword in a
      squashed commit message.
- [ ] `~/.claude/bin/gh-pr-wait <PR> --arm-auto`

## Self-Review

**Spec coverage.** Post-merge hook → Task 2 (phase A). Pre-merge hook → Task 2 (phase B). Cut
derivation and its reachability → Tasks 1 and 2 (`cutOf`). Vacuity V1/V2 → Task 2's
`CompactionCoverage` + Task 3's floors. V3 → Tasks 0 and 3 Step 4. V4 → Task 3 Step 5. API shape and
blast radius → Task 2 Step 4 (the additivity proof). `Fugue` generator tuning → Task 3b. Disjoint-
author precondition → Task 2's `requireDisjointAuthors`. Docs → Task 4. Both of the design's open
questions were decided on 2026-08-03 and are implemented here, not left open: `contiguousFrontier`
moves to `:kuilt-crdt` as public API with KDoc and a `@sample` (Task 1), and `Fugue`'s
`opsPerReplica` goes 8 → 16 with the before/after reachability recorded (Task 3b).

**Type consistency.** `VersionVector.contiguous(Set<Dot>): VersionVector` — Task 1 defines, Task 2
calls. `CompactionStep<S>(state, droppedCount)` and `CrdtCompactor<S>.compactOnce(state, stableCut,
frontierMax, delivered)` — Task 2 defines, Task 3 implements. `CompactionCoverage`'s four fields —
Task 2 defines and accumulates, Task 2's suite asserts, Task 3 sets the floors.

**One correction from my own review.** An earlier draft had `run(seed)` return the *compacted*
state, which would have silently changed the meaning of `runSeeds` for every existing caller and
made `convergesAtSeedZero` assert over a different value than it does today. `run` returns the
pre-compaction state; the compaction results are reachable only through the coverage accumulator and
the phases' own assertions.

**A second one.** The floors were originally a single shared constant on the suite. The measured
distributions differ by roughly 4× between `Fugue` and `Rga`, so one constant would have been set to
the weakest type and been vacuous for the other two. They are per-type and abstract.
