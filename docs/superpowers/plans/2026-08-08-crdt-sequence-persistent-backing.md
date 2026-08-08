# Θ(N)-per-append in `Rga`/`Fugue` (#2193) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This plan does not begin with code.** Its first phase is a measurement, and its second is a decision that is not Claude's to make. Do not skip to Phase 2 — see "Why this is shaped as a gate" below.

**Goal:** Decide, on evidence, whether the sequence CRDTs still need a persistent (structurally-shared) backing collection once #2194's batched write turn has landed — and if they do, ship it without putting a collections dependency on every consumer of `:kuilt-crdt`.

**Architecture:** Phase 1 re-runs the on-device probe against post-#2194 `main` and **decomposes** what remains, because at least four Θ(N) terms sit on the measured path and a persistent collection addresses only one of them. Phase 2 is a decision gate with two prerequisites, one of which is a consumer-visible breaking change. Phase 3A/3B are the two conditional implementation tracks; exactly one runs, and possibly neither.

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization. Candidate new dependency: `org.jetbrains.kotlinx:kotlinx-collections-immutable` (CHAMP-backed `PersistentSet`/`PersistentMap`), not currently in `gradle/libs.versions.toml`.

## Global Constraints

- **#2194 lands first.** Its bulk `Rga.insertAllAfter` / `removeFirst` pay one copy per *turn* rather than per record, which is the cheapest available lever and changes what is left here. See `2026-08-08-otel-batched-write-turn.md`.
- **`:kuilt-crdt` is deliberately dependency-free** — `api(libs.kotlinx.serialization.core)` and nothing else. No coroutines, no kuilt-core. That is the module's headline property and the whole reason this issue is `needs-design`.
- **Byte-parity is already safe.** `Rga`'s encoding is canonical via the #1957 track (#1962, #2034, #2046 — the last pins `Rga`/`Fugue` cross-target), so the encoder never observes the backing collection's iteration order. Swapping `LinkedHashSet`/`LinkedHashMap` for a hash-ordered CHAMP structure does **not** change the wire bytes. Re-verify with `./gradlew :kuilt-crdt:build` + the golden-vector suites; do not re-litigate it.
- **`explicitApi()`**, **`detektAll` never bare `detekt`**, **full `./gradlew build` before auto-merge**, **`runTest(timeout = TEST_WEDGE_BACKSTOP)`**, **no production dispatchers in tests** — as in every plan in this repo.
- **Measurement hygiene.** Any absolute timing reported here must be preceded by an `uptime` sample quoted alongside it, and comparisons must be **relative within one process** (arm A vs arm B under identical conditions). A saturated box distorts wall-clock by orders of magnitude and the distortion is invisible in the number.

---

## Why this is shaped as a gate

Two things stand between this issue and an implementation, and neither is code.

**1. The residue may not be worth a dependency.** #2194 divides the fixed per-turn cost by the batch size *and* collapses `2k` CRDT mutations into `2`. What is left is one Θ(N) copy per turn, at a turn size of ~128 records. If that is 0.1 ms/record, the module-identity argument is being had over nothing. Phase 1 answers this and is cheap.

**2. The decomposition in #2193's body is incomplete, and the missing terms change the answer.** The issue names `ops + op` (`Rga.kt:351`) as the defect. On the exporter's measured path — a full buffer, so every record evicts one — the *pre-#2194* `evictOldest` pays **four** Θ(N) passes per record, and a persistent set/map fixes exactly one:

| Pass | Where | Fixed by CHAMP? |
|---|---|---|
| `ops + op`, `insertsById + (id to op)` | `Rga.kt:351`/`:353`, `:401` | **Yes** — that is the issue's subject |
| `computeSequence()` | `removeAt` → `visibleSequence()` → `sequence`, on a **cold lazy** — every mutation returns a new `Rga` whose `sequence` lazy is uncomputed, and `computeSequence` is a `groupBy` over every insert plus an N-deep `appendChildren` recursion | **No** |
| `visibleSequence()`'s `filter` | same call | No |
| `log.toList()[0]` — materialises **all** N values to read index 0 | `WarpLogRecordExporter.evictOldest` | No (and #2194 removes it) |

So "15.1 ms per `insertAfter` at 8,000 ops" (the issue's table) is the *isolated* `insertAfter` cost, measured on an `Rga` grown from empty with nothing else in the frame — a workload with **no evictions**, and therefore no `computeSequence` in it at all. The exporter's steady state is a different shape, and the issue's own decomposition of the whole `export()` ("everything else" ≈ 12.7 ms at 7,000 ops) is where the other three terms are hiding, mislabelled as store cost.

**This is a fork, and Phase 1 must probe both branches.** Either the residual is dominated by the copy — in which case CHAMP is the fix and Phase 2's decision matters — or it is dominated by `computeSequence` recomputation, in which case CHAMP buys close to nothing and the real fix is threading the sequence forward in `RgaCache` (a change with no dependency, no module split, and no consumer cost at all). Do not default to the branch the issue names.

---

## Phase 1 — Measure what #2194 left behind

### Task 1: Decompose the residual on device

**Files:**
- Modify: `spike/src/appleMain/kotlin/spike/otel/OtelStallProbe.kt` (add arms D–F)
- No production code changes.

**Interfaces:**
- Consumes: post-#2194 `main`, with `Rga.insertAllAfter` / `removeFirst` present.
- Produces: a comment on [#2193](https://github.com/tractat-us/kuilt/issues/2193) carrying the table below, which is the input to Phase 2.

- [ ] **Step 1: Confirm #2194 is actually in the binary you are about to measure**

```bash
git -C ~/tractatus/kuilt-worktrees/rejoin fetch origin main
git log origin/main --oneline --grep "2194" | head
```

Then, after building the probe app, verify the symbol is present — **a 14 s "BUILD SUCCESSFUL" on a fresh worktree is this repo's Gradle build-cache false green**, and this measurement is worthless against a stale binary:

```bash
nm -gU <built-framework-or-binary> | grep -ci insertAllAfter
```

Expected: non-zero. (The #2127 precedent: pre-change binaries had 0 `windowPass` symbols vs 4 on `main`.)

- [ ] **Step 2: Add three arms to the probe**

`spike/src/appleMain/kotlin/spike/otel/OtelStallProbe.kt` already has arms A (`insertAfter` alone), B (whole `export()`) and C. Add, at each of n ∈ {250, 2000, 4000, 6000, 8000} ops held:

- **Arm D — the copy alone.** Time `Rga.insertAllAfter(replica, tail, values)` for a run of 128, with the result's `sequence` deliberately **not** touched. Isolates the `ops + newOps` / `insertsById + …` term.
- **Arm E — the sequence alone.** Time a first `sequence` access on a freshly-mutated `Rga` (`state.removeFirst(1).first.sequence`). Isolates `computeSequence()`.
- **Arm F — the exporter's real steady state.** A `WarpLogRecordExporter` at `maxRecords = n` already **full**, so every record evicts one, driven with `export(List)` of 128. This is the workload that matters and the one neither the issue nor arms A/B measured.

Report **mean and max** per arm, not mean alone: iOS auto-lock suspends a probe app mid-run — output stops, the process stays alive, no crash, and it reads exactly like a wedge. `isIdleTimerDisabled` is already set (commit `0807f3dc`); the max is what shows if it regressed.

- [ ] **Step 3: Run it on the same hardware as the original measurement**

iPhone XS (`iPhone11,2`, iOS 18.7.9, UDID `00008020-001B44AC2642002E`), Debug K/N, real `NSFileManagerDurableStore`. Retrieval is `devicectl device process launch --console` (plain `print` over USB), **not** `log collect`.

Sample `uptime` on the build host immediately before, and quote it with the numbers.

- [ ] **Step 4: Post the decomposition on #2193**

Fill in and comment (with the `🤖 This comment was generated by Claude on behalf of @keddie.` prefix):

| ops held | D: copy alone | E: `computeSequence` alone | F: per-record, exporter steady state |
|---|---|---|---|
| 250 | | | |
| 2,000 | | | |
| 4,000 | | | |
| 6,000 | | | |
| 8,000 | | | |

State plainly which term dominates F, and say what the per-record figure is now against the pre-#2194 baseline (9.5 ms at 250 ops, 24.9 ms at 7,000).

- [ ] **Step 5: Commit the probe arms**

```bash
git add spike/src/appleMain/kotlin/spike/otel/OtelStallProbe.kt
git commit -m "spike: decompose the post-#2194 residual — copy vs sequence vs steady state

Part of #2193. Arms A/B measured insertAfter on a log grown from empty, which has
no evictions in it and so no computeSequence. The exporter's steady state is a
full buffer where every record evicts one, and that path pays a cold
computeSequence per mutation as well as the op-set copy. Arms D/E/F separate them."
```

---

## Phase 2 — The gate

### Task 2: Decide, on the Phase 1 table

Not an implementation task. It produces a decision recorded on #2193 and picks Phase 3A, 3B, or neither.

- [ ] **Step 1: Apply the exit test**

**Close #2193 as `not planned`** if arm F's per-record cost is at or below ~1 ms on Debug (≈0.1 ms Release, per the issue's own 8× Debug/Release ratio). At that point the cure costs more than the disease: a dependency, or a module split with a consumer-visible coordinate move, to shave a tenth of a millisecond off a diagnostics write path. Record the numbers in the closing comment so a future reader can reopen against evidence rather than re-measuring.

**Go to Phase 3B (no dependency)** if arm E dominates arm D — i.e. the residual is `computeSequence` recomputation, not copying. This is the better outcome: it is fixable inside `:kuilt-crdt` with no dependency, no module split and no consumer cost.

**Go to Phase 3A (the dependency)** only if arm D dominates *and* arm F is genuinely expensive. Then Step 2's prerequisite is live.

- [ ] **Step 2: If and only if Phase 3A — get an explicit yes on the consumer cost**

Iain has already decided the *principle* ([issuecomment-5224062566](https://github.com/tractat-us/kuilt/issues/2193#issuecomment-5224062566)): a persistent-collections dependency is acceptable **for `Rga`/`Fugue`**, no vendored CHAMP, and it should stay optional for the rest of the zoo.

**What is not decided is what "optional" costs.** Within one Gradle module a dependency is not optional at runtime: if `Rga`/`Fugue` import `kotlinx.collections.immutable` while living in `:kuilt-crdt`, every consumer of `:kuilt-crdt` resolves it — including `:kuilt-otel`, which re-exports the module as `api`. So the decision implies a **module boundary**: a `:kuilt-crdt-seq` holding `Rga` + `Fugue`, depending on `:kuilt-crdt` and on the collections library.

That is a coordinate **and package** move for every existing consumer of `Rga`/`Fugue`, plus a new module on `:kuilt-otel`'s dependency list. Pre-1.0 it is a normal breaking change, but it lands on consumers rather than here, so it needs an explicit yes — not an inference from the principle already agreed. Ask it as a single question on #2193, with the Phase 1 numbers attached so the trade is visible:

> Post-#2194 the residual is **X ms/record**. Fixing it needs `:kuilt-crdt-seq` — a new coordinate and a package move for anyone importing `Rga` or `Fugue`. Worth it, or close #2193?

**Do not build Phase 3A before that answer.** Two alternatives exist and are worse, and should be named in the same comment so the choice is informed rather than presented as forced:

- *Abstract the backing store behind an internal interface*, copying impl by default, CHAMP supplied by an optional companion module. Keeps `Rga` where it is, and adds an indirection to the hottest path in the structure — the thing being optimised.
- *Gradle feature variants / capabilities.* Expresses "optional dependency" natively; awkward across KMP targets and pushes the complexity into every consumer's build file.

---

## Phase 3A — The dependency and the module split

*Runs only on an explicit yes at Task 2 Step 2.* Sketched, not fully specified: the Phase 1 numbers will determine which of the eight call sites are worth touching, and a fully-specified plan written before that measurement would be specifying work that may not exist.

### Task 3A.1: `:kuilt-crdt-seq`

- Create the module (`id("kuilt.kmp-library")`, `api(project(":kuilt-crdt"))`, `implementation(libs.kotlinx.collections.immutable)`), add `kotlinx-collections-immutable` to `gradle/libs.versions.toml`, register it in `settings.gradle.kts` and in `:kuilt-bom`.
- Move `Rga.kt`, `Fugue.kt` and their tests. Keep the package `us.tractat.kuilt.crdt` so only the *coordinate* moves, not the import — that halves the consumer cost, and the module boundary is what carries the dependency, not the package name.
- Update `:kuilt-otel`'s and `:kuilt-quilter`'s dependency blocks, `docs/architecture.md`'s module table, the root `CLAUDE.md` module table, and both `module.md` files.
- Verify: `./gradlew build detektAll --rerun-tasks --max-workers=6` — every downstream module must still resolve `Rga`.
- Verify the dependency really did **not** leak: `./gradlew :kuilt-crdt:dependencies --configuration jvmRuntimeClasspath | grep -c collections.immutable` must print `0`. That single assertion is the whole point of the split; without it the module is cost without benefit.

### Task 3A.2: Swap the backings, one structure at a time

- `Rga`: `ops: Set<RgaOp<V>>` → `PersistentSet`, `insertsById`/`maxSeqByReplica`/`compactPositions` → `PersistentMap`, `tombstones`/`compactedIds` → `PersistentSet`. The four sites are `Rga.kt:351`, `:401`, `:588`, `:602` (plus their `RgaCache` construction).
- `Fugue`: the same shape at `Fugue.kt:339`, `:390`, `:617`, `:629`.
- **Leave `piece`/`merge` alone** (`Rga.kt:700`, `Fugue.kt:491`). `ops + other.ops` there is a genuine union of two sets and is O(N) by nature — a different thing from the per-append copy, and the issue says so.
- TDD per structure: a failing complexity test first (append `k` elements to an `N`-op log and assert the wall-clock ratio between `N` and `2N` is sub-linear), then the swap, then revert-and-confirm-red.
- The existing `Rga`/`Fugue` conformance, lattice-law and golden-vector suites are the regression surface and must pass **unchanged** — that is the byte-parity proof.

---

## Phase 3B — Thread the sequence forward (no dependency)

*Runs if arm E dominates arm D.* This is the outcome to hope for: it is strictly cheaper for everyone.

### Task 3B.1: Carry `sequence` in `RgaCache`

- `RgaCache` currently carries `insertsById`, `maxSeqByReplica`, `tombstones`, `compactedIds`, `compactPositions` — but **not** `sequence`, so every mutation returns an `Rga` whose `sequence` lazy is cold and the next reader pays a full `computeSequence()`.
- On the append path the new sequence is derivable in O(k): `insertAllAfter` chains each element after the previous, so the run appends to the tail of the existing order — no `groupBy`, no recursion. `removeFirst` does not change `sequence` at all (a tombstone stays in it). Both can thread the sequence forward exactly.
- The paths that genuinely cannot — `piece`, `applyCompact`, `dropWindow`, `fromOps`, a remote `applyInsert` landing mid-sequence — pass `null` and keep today's lazy recompute. That is the whole design: make the *common, chained-append* case incremental and leave the general case alone.
- TDD: a test that a freshly-appended `Rga`'s `sequence` equals the recomputed one for every mutation path (the correctness property), plus one that the append path does not recompute (assert via a counter on a test-visible hook, or by wall-clock ratio across N).
- Risk to state in the PR: `sequence` threaded forward is *state that must agree with a pure function of `ops`*. Every construction site must either supply a correct one or supply `null`. A wrong one is a silent divergence, so the equality test above must cover **every** mutation entry point, not a sample.

---

## Verification before either Phase 3 PR leaves draft

- [ ] `./gradlew build detektAll --rerun-tasks --max-workers=6` green, tasks `EXECUTED` not `FROM-CACHE`.
- [ ] The `Rga`/`Fugue` golden-vector and canonical-encoding suites pass **unmodified** — the byte-parity receipt.
- [ ] Every new test run alone (`--tests "*<One>*"`).
- [ ] The on-device probe re-run on the same iPhone XS, with the before/after ratio quoted (ratios, not absolutes) and `uptime` sampled alongside.
- [ ] For 3A only: `./gradlew :kuilt-crdt:dependencies --configuration jvmRuntimeClasspath | grep -c collections.immutable` prints `0`.
- [ ] PR body carries the AI-attribution prefix and says **`closes #2193`**.

## What this plan deliberately does not do

- **No vendored CHAMP.** Ruled out by Iain's decision; `:kuilt-crdt` already vendors canonical hashes under golden vectors, but a hash function is a page of code with a fixed spec and a set/map is not.
- **No change to `piece`/`merge`.** O(N) union is inherent there.
- **No `BoundedCounter`/`ORSet`/… changes.** Nothing else in the zoo has the Θ(N)-per-append shape, so nothing else pays for the cure.
- **No pre-emptive module split.** The split is the *consequence* of a dependency, and the dependency is the consequence of a measurement that has not been taken yet.
