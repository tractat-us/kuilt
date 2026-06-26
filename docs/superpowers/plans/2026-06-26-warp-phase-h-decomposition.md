# Warp Phase H — `:kuilt-warp` Decomposition — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the monolithic `:kuilt-warp` into a lean core plus opt-in satellites (`:kuilt-warp-runtime`, `:kuilt-warp-planning`, `:kuilt-warp-ml`) so a symbolic-dispatch peer no longer drags in Chicory, the wasm3 native binaries, the CALM planner, or the ML demo — with **zero behaviour change**.

**Architecture:** Pure move-and-rewire. `WarpNode` references the `WasmRuntime` *interface* (injected via `WarpLazyFetch`), never a concrete runtime, so the heavy impls move out without engine surgery. The lightweight mobility *contract* (`WasmRuntime`, `Creel`, `BobbinExchange`, `Variant`, `WarpLazyFetch`) stays in core. One satellite extraction per PR; each builds green and changes no behaviour.

**Tech Stack:** Kotlin Multiplatform + Gradle; the `kuilt.kmp-library` convention plugin; `git mv` for rename-detecting moves; `./gradlew build` / `allTests` as the verification gate (this is a refactor — the "test" is the existing suite staying green in the new layout).

**Spec:** `docs/superpowers/specs/2026-06-26-warp-phase-h-decomposition-design.md` · **Epic:** [#969](https://github.com/tractat-us/kuilt/issues/969) · Part of #665.

## Global Constraints

- **DO NOT START until the repo has quiesced** (Task 0 is a hard gate). A whole-module move conflicts violently with any open PR touching `:kuilt-warp`. The plan is authored against a moving target; **the file lists below are a snapshot dated 2026-06-26 and WILL be stale** — Task 0 re-derives the real lists.
- **Zero behaviour change.** No file's *logic* is edited. Permitted edits are limited to: `package`/`import` lines, visibility promotions forced by a new module boundary (`internal` → `public`, justified per occurrence), `build.gradle.kts`/`settings.gradle.kts` wiring, and `@sample`/KDoc FQN fixes. **If a move tempts a logic fix, file a separate issue — do not fold it in.**
- **Red flag:** if moving a test requires editing anything beyond its `package`/`import`s, STOP — that signals a hidden coupling (usually `internal` access across the new boundary). Resolve it deliberately (move the dependency too, or promote visibility), don't rewrite the test.
- **One concern per PR.** Each task = its own branch off the *current* `origin/main`, its own PR, `Part of #665` + epic #969 (non-closing).
- Build with JDK 21: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` first in every non-interactive shell.
- `explicitApi()` enforced; `./gradlew detektAll` must pass (not bare `detekt`). New modules apply `id("kuilt.kmp-library")` and almost nothing else.
- Satellites stay **out of the BOM** (experimental), like `:kuilt-warp`.
- `git mv` (not delete+create) so rename detection keeps history and keeps diffs reviewable.

## Concern → module classification rule (the durable spec; lists are derived from it)

Classify every `kuilt-warp/src/**` file by what it *is*, not by today's filename:

- **`:kuilt-warp-runtime`** — anything that *implements or exercises a concrete wasm runtime*: `ChicoryWasmRuntime`, `TimedGuestRunner` (jvm); the wasm3 cinterop + `nativeInterop/wasm3/**` (apple); the browser/wasmJs runtime; and the **real-runtime dispatch tests** (those that load real wasm bytes through Chicory/wasm3/browser) + their `.wasm`/`.wat` fixtures (e.g. `square.*`, `loop.*`, `trap.*`, `imports.*`, `bigmem.*`, etc.). Excludes fake-runtime tests (see core).
- **`:kuilt-warp-planning`** — the CALM planner: `Draft*`, `CoordinationCost`, `WarpPlanner`, their tests + samples. Excludes `CoordinationKind`/`Coordinated`/`CoordinationFree`/`ConvergentExecution` (these are the *execution-path* coordination types `WarpNode` uses — they **stay in core**).
- **`:kuilt-warp-ml`** — federated learning: `FedAvg*`, `ReferenceTrainer`, `TrainingUpdate`, their tests + samples + the `fedavg_*` wasm fixtures.
- **`:kuilt-warp` (core, stays)** — the engine (`WarpNode`, `Op*`, `Task*`, `Results`, `RosterSource`, `ClaimStrategy`, coordination-path types, `IncrementalResult`, `WarpStats`, `Warp`); the mobility **contract** (`WasmRuntime`, `WasmSandboxConfig`, `Creel`, `BobbinHash`, `BobbinExchange`, `WarpLazyFetch`, `Variant`, `Target`); and the **fake-runtime** test support + tiering tests (`FakeWasmRuntime`, `FakeCompiler`, `WarpNodeTieringTest`, `TieredCompilationGoNoGoTest`, `VariantManifestTest`) — they exercise the engine with no real runtime.

**Anything that doesn't cleanly classify is a STOP-and-ask** (a sibling may have added a new concern). Don't guess.

---

### Task 0: Quiesce gate + execution-time re-survey (HARD GATE — no code)

**Deliverable:** written confirmation the repo is safe to restructure, plus the *current* move manifest. Do not proceed to Task H-1 until both pass.

- [ ] **Step 1: Fetch and confirm quiescence**

```bash
git fetch origin main --prune
# Any open PR touching kuilt-warp* is a blocker — a big move will collide.
gh pr list --state open --json number,title,headRefName --limit 100 \
  | jq -r '.[] | "\(.number) \(.headRefName) \(.title)"'
for pr in $(gh pr list --state open --json number --jq '.[].number'); do
  files=$(gh pr view "$pr" --json files --jq '.files[].path' | grep -c '^kuilt-warp' || true)
  [ "$files" -gt 0 ] && echo "PR #$pr touches kuilt-warp ($files files) — BLOCKER"
done
git worktree list   # any locked agent-* worktree on a warp branch = in-flight work
```
Expected: **no** open PR touches `kuilt-warp*`, no locked warp worktrees. **If any exist, STOP** and report "Phase H blocked on in-flight warp work: <list>" — wait for quiescence.

- [ ] **Step 2: Establish a green baseline**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-warp:build :kuilt-warp-otel:build`
Expected: BUILD SUCCESSFUL. (If red on a clean `origin/main`, STOP — fix/triage before moving anything.)

- [ ] **Step 3: Re-derive the move manifest from the live tree**

```bash
git ls-files 'kuilt-warp/src/**' '*/warp/*.wat' '*/warp/*.wasm'
```
Classify every result with the rule above into runtime / planning / ml / core-stays. Write the result to `.superpowers/sdd/phase-h-manifest.md` (four lists). **Flag any file that doesn't cleanly classify** and resolve with the human before proceeding. This manifest — not the snapshot below — is what H-1…H-3 move.

- [ ] **Step 4: Map cross-module fallout** (who imports the about-to-move types)

```bash
# Dependents that will need build.gradle / import updates after each extraction:
grep -rl 'us.tractat.kuilt.warp' --include=*.kt kuilt-warp-otel examples \
  | xargs grep -lE 'Draft|coordinationCost|FedAvg|ChicoryWasmRuntime|Wasm3|BrowserWasm' 2>/dev/null
```
Known as of authoring (CONFIRM live): **`:kuilt-warp-otel`'s `WarpMetricBridge.recordPlan` imports `Draft` + `coordinationCost`** → after H-2 it needs `api(project(":kuilt-warp-planning"))`. `examples/` references warp sim/types → update its deps per extraction. Record the live dependent list in the manifest file.

- [ ] **Step 5: Internal-boundary audit** (the main hazard)

For each concern to be moved, list `internal` symbols its *tests* touch that live in core (or vice versa):
```bash
grep -rn '\binternal\b' kuilt-warp/src/commonMain | grep -iE 'wasm|chicory|fedavg|draft|planner' || true
```
For each, decide in the manifest: (a) the `internal` moves *with* the concern (preferred), or (b) it must be promoted to `public` in core (justify). No promotions that aren't forced by a real cross-boundary use.

- [ ] **Step 6: Record the gate result**

Append to `.superpowers/sdd/phase-h-manifest.md`: "Quiesce gate PASSED at <commit>" + the four move-lists + the dependent list + the internal-boundary decisions. This file is the source of truth for H-1…H-3.

**Snapshot (2026-06-26 — illustrative, NOT authoritative; Task 0 supersedes):**
runtime ← `ChicoryWasmRuntime`,`TimedGuestRunner`(jvm); `Wasm3RuntimeDispatchTest`,`Wasm3SquareOp`(appleTest); `ChicoryRuntimeDispatchTest`,`ChicorySquareOp`,`ChicoryWasmRuntimeTimingTest`,`WasmSandboxConfigTest`(jvmTest); `BrowserWasmRuntimeDispatchTest`,`BrowserWasmSquareOp`(wasmJsTest); `nativeInterop/wasm3/**`; jvmTest `.wasm`/`.wat` fixtures except `fedavg_*`. · planning ← `Draft`,`DraftCoordinator`,`DraftRewrite`,`DraftStage`,`CoordinationCost`,`WarpPlanner` + `Draft*Test`,`CoordinationCost*Test`,`ConsolidateEmbroideriesTest`,`CombinatorPropertyTest`,`PlannerGoNoGoTest` + planning samples. · ml ← `FedAvg`,`FedAvgKernelCodec`,`ReferenceTrainer`,`TrainingUpdate` + `FedAvg*Test`,`ReferenceTrainerTest` + `FedAvgKernelSample` + `fedavg_*` fixtures. · core-stays ← everything else, incl. `FakeWasmRuntime`,`FakeCompiler`,`WarpNodeTieringTest`,`TieredCompilationGoNoGoTest`,`VariantManifestTest`.

---

### Task H-1: Extract `:kuilt-warp-runtime`

**Files:**
- Create: `kuilt-warp-runtime/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `include(":kuilt-warp-runtime")`)
- Move (per Task-0 manifest): the runtime impls + real-runtime tests + `nativeInterop/wasm3/**` + real-runtime wasm fixtures, from `kuilt-warp/` to `kuilt-warp-runtime/` (same source-set layout).
- Modify: `kuilt-warp/build.gradle.kts` (drop the Chicory dep + the wasm3 cinterop block once they've moved), `examples/build.gradle.kts` (add `:kuilt-warp-runtime` if examples run real wasm).

**Interfaces:**
- Consumes: core's `WasmRuntime`, `WasmSandboxConfig`, `Op` (the contract the runtimes implement) — unchanged, stay in core.
- Produces: `:kuilt-warp-runtime` providing `ChicoryWasmRuntime`, `TimedGuestRunner`, the wasm3 native runtime, the browser runtime — at the same FQNs (package preserved), now in a separate module.

- [ ] **Step 1: Scaffold the module**

Create `kuilt-warp-runtime/build.gradle.kts` mirroring `:kuilt-warp`'s structure but runtime-scoped. Move the **wasm3 cinterop config block** and the Chicory dependency out of `kuilt-warp/build.gradle.kts` into here:

```kotlin
plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":kuilt-warp")) } // the WasmRuntime contract
        jvmMain.dependencies { implementation(libs.chicory.runtime) }
    }
    // Move the wasm3 cinterop (def + include + prebuilt) block here verbatim from kuilt-warp.
}
```
Add `include(":kuilt-warp-runtime")` to `settings.gradle.kts`.

- [ ] **Step 2: `git mv` the runtime files per the Task-0 manifest**

```bash
mkdir -p kuilt-warp-runtime/src/jvmMain/kotlin/us/tractat/kuilt/warp \
         kuilt-warp-runtime/src/nativeInterop
git mv kuilt-warp/src/jvmMain/kotlin/us/tractat/kuilt/warp/ChicoryWasmRuntime.kt kuilt-warp-runtime/src/jvmMain/kotlin/us/tractat/kuilt/warp/
git mv kuilt-warp/src/jvmMain/kotlin/us/tractat/kuilt/warp/TimedGuestRunner.kt   kuilt-warp-runtime/src/jvmMain/kotlin/us/tractat/kuilt/warp/
git mv kuilt-warp/src/nativeInterop/wasm3 kuilt-warp-runtime/src/nativeInterop/wasm3
git mv kuilt-warp/src/nativeInterop/cinterop kuilt-warp-runtime/src/nativeInterop/cinterop
# real-runtime tests + fixtures (apple/jvm/wasmJs) per the manifest — example:
git mv kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/ChicoryRuntimeDispatchTest.kt kuilt-warp-runtime/src/jvmTest/kotlin/us/tractat/kuilt/warp/
# …repeat for every file Task 0 classified as runtime…
```
Keep packages identical so only module wiring changes.

- [ ] **Step 3: Apply the Task-0 internal-boundary decisions**

For each `internal` the moved tests/impls used across the boundary, apply the recorded fix (move-with, or promote-to-`public` with the justifying KDoc). Adjust `package`/`import` lines only.

- [ ] **Step 4: Build the new module + the trimmed core**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-warp:build :kuilt-warp-runtime:build`
Expected: BUILD SUCCESSFUL. If core fails to compile because it still references a moved symbol, that symbol was mis-classified — reconcile against the manifest (it likely belongs in core).

- [ ] **Step 5: Prove the isolation (the point of the task)**

Run: `./gradlew :kuilt-warp:dependencies --configuration jvmRuntimeClasspath | grep -i chicory; echo "exit=$?"`
Expected: **no match** (grep exit 1) — core no longer resolves Chicory. Also confirm `kuilt-warp/src/nativeInterop` is gone.

- [ ] **Step 6: Full build + tests across the repo**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — every moved test passes in its new module; core + otel + examples still green. Zero test-logic edits (only imports).

- [ ] **Step 7: detekt + commit**

```bash
./gradlew :kuilt-warp:detektAll :kuilt-warp-runtime:detektAll
git add -A && git commit -m "refactor(warp): extract :kuilt-warp-runtime (Chicory/wasm3/browser) — H-1

Move-only; removes the Chicory dep and the wasm3 native binaries from core.
Zero behaviour change. Part of #665, #969."
```

---

### Task H-2: Extract `:kuilt-warp-planning` (and fix the otel dependency)

**Files:**
- Create: `kuilt-warp-planning/build.gradle.kts`; Modify: `settings.gradle.kts`.
- Move (per manifest): `Draft*`, `CoordinationCost`, `WarpPlanner` + their tests + planning samples.
- Modify: `kuilt-warp-otel/build.gradle.kts` — add `api(project(":kuilt-warp-planning"))` (its `recordPlan` uses `Draft`/`coordinationCost`); `examples/build.gradle.kts` if examples use the planner.

**Interfaces:**
- Consumes: core types the planner builds on (unchanged).
- Produces: `:kuilt-warp-planning` providing `Draft`, `DraftRewrite`, `CoordinationCost`, `WarpPlanner`, `coordinationCost(...)` at their existing FQNs.

- [ ] **Step 1: Scaffold + include**

`kuilt-warp-planning/build.gradle.kts`:
```kotlin
plugins { id("kuilt.kmp-library") }
kotlin { sourceSets { commonMain.dependencies { api(project(":kuilt-warp")) } } }
```
Add `include(":kuilt-warp-planning")` to `settings.gradle.kts`.

- [ ] **Step 2: `git mv` planning files per the manifest** (Draft*, CoordinationCost, WarpPlanner, their tests + samples). Packages preserved.

- [ ] **Step 3: Fix the otel dependency**

In `kuilt-warp-otel/build.gradle.kts` add `api(project(":kuilt-warp-planning"))`. `WarpMetricBridge.kt`'s imports of `Draft`/`coordinationCost` now resolve cross-module — no code change, just the new dep.

- [ ] **Step 4: Apply internal-boundary decisions** (per Task 0), imports only.

- [ ] **Step 5: Build the trio**

Run: `./gradlew :kuilt-warp:build :kuilt-warp-planning:build :kuilt-warp-otel:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Full build + tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — planner tests pass in `:kuilt-warp-planning`; the otel `recordPlan` test passes via the new dep.

- [ ] **Step 7: detekt + commit**

```bash
./gradlew :kuilt-warp-planning:detektAll :kuilt-warp-otel:detektAll
git add -A && git commit -m "refactor(warp): extract :kuilt-warp-planning; wire otel→planning — H-2

Move-only. :kuilt-warp-otel now depends on :kuilt-warp-planning for recordPlan.
Zero behaviour change. Part of #665, #969."
```

---

### Task H-3: Extract `:kuilt-warp-ml`

**Files:**
- Create: `kuilt-warp-ml/build.gradle.kts`; Modify: `settings.gradle.kts`.
- Move (per manifest): `FedAvg`, `FedAvgKernelCodec`, `ReferenceTrainer`, `TrainingUpdate` + their tests + `FedAvgKernelSample` + the `fedavg_*` wasm fixtures.
- Modify: `examples/build.gradle.kts` if examples use FedAvg.

**Interfaces:**
- Consumes: core (`Op`, descriptors, `Creel`/`BobbinExchange`) + `:kuilt-warp-runtime` in tests that execute the fedavg kernel.
- Produces: `:kuilt-warp-ml` providing the FedAvg API at existing FQNs.

- [ ] **Step 1: Scaffold + include**

`kuilt-warp-ml/build.gradle.kts`:
```kotlin
plugins { id("kuilt.kmp-library") }
kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":kuilt-warp")) }
        commonTest.dependencies { implementation(project(":kuilt-warp-runtime")) } // fedavg kernel exec
    }
}
```
Add `include(":kuilt-warp-ml")`.

- [ ] **Step 2: `git mv` ml files + the `fedavg_*` fixtures per the manifest.** Packages preserved.

- [ ] **Step 3: Apply internal-boundary decisions** (imports only).

- [ ] **Step 4: Build**

Run: `./gradlew :kuilt-warp:build :kuilt-warp-ml:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Full build + tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — FedAvg tests pass in `:kuilt-warp-ml` (incl. the real-runtime fedavg execution via the test dep on `:kuilt-warp-runtime`).

- [ ] **Step 6: detekt + commit**

```bash
./gradlew :kuilt-warp-ml:detektAll
git add -A && git commit -m "refactor(warp): extract :kuilt-warp-ml (FedAvg/F2 demo) — H-3

Move-only. Zero behaviour change. Part of #665, #969."
```

---

### Task H-4: Verify the done-statement + document the module map

**Files:**
- Modify: `kuilt-warp/module.md` (add a short "module map" paragraph), `docs/warp-roadmap.md` (Phase-H entry + new layout), the Writerside guide if it lists modules. `CLAUDE.md`'s module table is **out of scope** (split into its own PR per repo convention).

- [ ] **Step 1: Prove the core is lean (the epic's done-statement)**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-warp:dependencies --configuration jvmRuntimeClasspath > /tmp/warp-deps.txt
grep -iE 'chicory|wasm3|binaryen|fedavg|draft' /tmp/warp-deps.txt && echo "LEAK — investigate" || echo "core is lean ✓"
```
Expected: "core is lean ✓" — no Chicory/wasm3/ML/planning on the core classpath.

- [ ] **Step 2: Add the module map to `kuilt-warp/module.md`** (plain-language first, per the docs style rule):

```markdown
### Module map

`:kuilt-warp` is the execution engine and the lightweight code-mobility contract. The heavy and
optional pieces live in opt-in satellites you add only if you need them:
`:kuilt-warp-runtime` (the wasm runtimes — Chicory, wasm3, browser), `:kuilt-warp-planning`
(the coordination-cost query planner), `:kuilt-warp-ml` (the federated-learning demo), and
`:kuilt-warp-otel` (metrics). A peer that only dispatches named work depends on `:kuilt-warp` alone.
```

- [ ] **Step 3: Add the Phase-H entry to `docs/warp-roadmap.md`** (a short section mirroring the other phases: goal, the new module layout, done-statement, "move-only / zero behaviour change").

- [ ] **Step 4: Build the docs + commit**

```bash
./gradlew :kuilt-warp:dokkaGenerate
git add kuilt-warp/module.md docs/warp-roadmap.md Writerside/ 2>/dev/null
git commit -m "docs(warp): Phase-H module map + roadmap entry — H-4

Part of #665, #969."
```

---

## Self-Review

**1. Spec coverage:** Phase-H spec's four modules → Tasks H-1/H-2/H-3 (runtime/planning/ml) + core-stays (no task — it's the residue). Done-statement (core resolves nothing heavy) → H-1 Step 5 + H-4 Step 1. Boundary calls (BobbinExchange/Creel/WasmRuntime-iface stay in core; otel→planning dep) → the classification rule + H-2 Step 3. Quiesce gate + re-survey (user requirement) → Task 0. ✔

**2. Placeholder scan:** The per-task `git mv` lists end with "repeat for every file Task 0 classified…" — this is **intentional**, not a placeholder: the authoritative list is the Task-0 manifest because the tree is volatile; the snapshot is explicitly labelled non-authoritative. The classification *rule* is fully specified (no TBD). Every command + expected output is concrete.

**3. Type consistency:** No new types are introduced — every moved symbol keeps its FQN (packages preserved), so consumers compile unchanged except for added module deps. The only signature-adjacent change is forced `internal`→`public` promotions, each gated on a recorded Task-0 decision.

**Known cross-module fallout (call out to the final review):** `:kuilt-warp-otel` gains a `:kuilt-warp-planning` dep (H-2); `examples/` and any in-repo consumer gain deps per extraction (Task 0 Step 4 derives the live list). Watch for `internal`-boundary breaks — the single most likely source of a non-trivial diff; if one needs more than a visibility bump, STOP and re-plan that move.
