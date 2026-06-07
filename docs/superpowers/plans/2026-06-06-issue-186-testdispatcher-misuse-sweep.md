# Issue #186 — TestDispatcher Misuse Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the production-CRDT-under-TestDispatcher footgun (issue #172) impossible to fall into by accident across `:kuilt-raft` and `:kuilt-crdt`, AND eliminate the warning noise from in-house tests that legitimately use the pattern.

**Architecture:** The existing guard (#184 / #189) warns on any construction of `RaftNode` or `SeamReplicator` under any `TestDispatcher`. That's correct for consumer code but produces ~50 informational warnings per CI run from in-house tests that intentionally use `UnconfinedTestDispatcher` (where real-clock `delay()` actually fires). Fix in two phases. **Phase A** is mechanical: banner the test fixtures so the next contributor sees the contract immediately, then add an `expectVirtualTime: Boolean = false` opt-out to the guard so tests that have validated the pattern can suppress the warning. **Phase B** is judgment-per-test: each of the 14 raft tests + 4 crdt SeamReplicator tests gets individually re-evaluated — migrate to `StandardTestDispatcher + advanceTimeBy` where the test doesn't actually depend on real time, document the intent where it does. Phase B is open-ended and peelable; Phase A is one PR per module.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines.test (`UnconfinedTestDispatcher`, `StandardTestDispatcher`, `TestScope.advanceTimeBy`, `backgroundScope`), `:kuilt-raft-test`'s `FakeRaftNode` substitute.

**Source material:** Issue #186 (audit's groupings + recommendations), PR #184 (`RaftNode` guard), PR #189 (`SeamReplicator` guard), PR #187 (`checkNotUnderTestDispatcher` helper hoisted to `:kuilt-raft`'s `internal/TestDispatcherGuard.kt`).

---

## File Structure

| File | Phase | Responsibility |
|------|-------|----------------|
| `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/TestDispatcherGuard.kt` (modify) | A | Add `expectVirtualTime` parameter to the helper |
| `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftConfig.kt` (modify) | A | Add `expectVirtualTime: Boolean = false` field |
| `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt` (modify) | A | Plumb the new flag from config into the guard call |
| `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftTestFixtures.kt` (modify) | A | Banner comment + set `expectVirtualTime = true` in `FAST_RAFT_CONFIG` |
| `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftSimulation.kt` (modify) | A | Banner comment |
| `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/TestDispatcherGuardTest.kt` (modify) | A | Add a `expectVirtualTime = true` suppression test |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicator.kt` (modify) | A | Mirror Phase-A change: add `expectVirtualTime` parameter to the internal guard helper |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorConfig.kt` (modify) | A | Add `expectVirtualTime: Boolean = false` field (file may be inline in SeamReplicator.kt — check) |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorTest.kt` (modify) | A | Set the flag in fixture config |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorGapTest.kt` (modify) | A | Same |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorEvictionTest.kt` (modify) | A | Same |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorChaosTest.kt` (modify) | A | Same |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorTestDispatcherGuardTest.kt` (modify) | A | Add a suppression test |
| `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/<various>.kt` (Phase B follow-ups) | B | Per-test individual judgment — see Phase B section |

---

## Preamble (run once)

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
```

Phase A is two parallel PRs (one per module). Phase B is one PR per migrated test.

---

# Phase A — Foundation

Phase A is mechanical and safe. Both PRs are interruption-resilient: stop after any task and the codebase is in a coherent state.

## Task 1: Add `expectVirtualTime` to `:kuilt-raft`'s guard helper

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/TestDispatcherGuard.kt`

- [ ] **Step 1: Read the current helper**

The existing signature (per PR #187) is roughly:
```kotlin
internal fun checkNotUnderTestDispatcher(
    scope: CoroutineScope,
    strict: Boolean,
    typeName: String,
    substitute: String,
) { /* … */ }
```

- [ ] **Step 2: Add the `expectVirtualTime` parameter**

Update the helper to take a third optional parameter that bypasses the warning entirely when `true`:

```kotlin
internal fun checkNotUnderTestDispatcher(
    scope: CoroutineScope,
    strict: Boolean,
    expectVirtualTime: Boolean,
    typeName: String,
    substitute: String,
) {
    if (expectVirtualTime) return  // caller has validated the pattern intentionally
    // … existing class-name check + println/error logic unchanged
}
```

- [ ] **Step 3: Run the existing guard tests**

Run: `./gradlew :kuilt-raft:jvmTest --tests '*TestDispatcherGuardTest*'`
Expected: PASS — the existing tests pass `expectVirtualTime = false` implicitly (default), behavior unchanged.

You may need to add the parameter to the existing call sites in the same commit; that's fine.

- [ ] **Step 4: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/TestDispatcherGuard.kt
# include any updated call sites
git commit -m "feat(kuilt-raft): TestDispatcherGuard — add expectVirtualTime opt-out parameter"
```

## Task 2: Plumb `expectVirtualTime` through `RaftConfig` and `RaftNode`

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftConfig.kt`
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt`

- [ ] **Step 1: Add field to `RaftConfig`**

Add to the `RaftConfig` data class, alongside the existing `strictTestGuard`:

```kotlin
/**
 * Suppresses the TestDispatcher warning (see [strictTestGuard]) for tests that
 * intentionally run a real `RaftNode` under `UnconfinedTestDispatcher` — where
 * real-clock `delay()` actually fires, so the engine's election/heartbeat loops
 * tick normally. Has no effect in production (production code is never under a
 * `TestDispatcher`). Default `false`: warn as usual.
 *
 * Set `true` only when you have explicitly validated that the test's use of
 * virtual time is correct. NEVER set in production code.
 */
public val expectVirtualTime: Boolean = false,
```

- [ ] **Step 2: Pass the flag into the guard call site in `RaftNode`**

Locate the `checkNotUnderTestDispatcher` call in `RaftNode.kt`. It currently passes `strict = config.strictTestGuard`. Add `expectVirtualTime = config.expectVirtualTime`:

```kotlin
checkNotUnderTestDispatcher(
    scope = scope,
    strict = config.strictTestGuard,
    expectVirtualTime = config.expectVirtualTime,
    typeName = "RaftNode",
    substitute = "FakeRaftNode (from :kuilt-raft-test)",
)
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :kuilt-raft:jvmTest`
Expected: PASS — all existing tests still green. The field has a default of `false`, so behavior is unchanged.

- [ ] **Step 4: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftConfig.kt \
        kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt
git commit -m "feat(kuilt-raft): RaftConfig.expectVirtualTime — plumbed into RaftNode guard"
```

## Task 3: Add a suppression test for `:kuilt-raft`

**Files:**
- Modify: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/TestDispatcherGuardTest.kt`

- [ ] **Step 1: Add a test that validates `expectVirtualTime = true` suppresses the warning AND strict mode**

Append to the existing test class:

```kotlin
@Test
fun realRaftNodeUnderTestDispatcher_doesNotWarnOrThrow_whenExpectVirtualTimeIsTrue() = runTest(UnconfinedTestDispatcher()) {
    val config = RaftConfig(
        electionTimeoutMin = 5.milliseconds,
        electionTimeoutMax = 10.milliseconds,
        heartbeatInterval = 2.milliseconds,
        strictTestGuard = true,           // would normally throw
        expectVirtualTime = true,         // opt-out — must take precedence
    )
    // Construct a real RaftNode here under runTest; if expectVirtualTime did NOT
    // take precedence, strictTestGuard = true would have thrown.
    val node = backgroundScope.raftNode(
        clusterConfig = ClusterConfig(voters = setOf(NodeId("v1"))),
        transport = InMemoryRaftNetwork(listOf(NodeId("v1"))).transportFor(NodeId("v1")),
        storage = InMemoryRaftStorage(),
        config = config,
    )
    // If we got here, the guard was suppressed correctly. No assertion needed beyond non-throw.
    assertNotNull(node)
}
```

(Adjust imports / construction shape to match what the existing `TestDispatcherGuardTest` already imports — read it for the exact fixture pattern. If a simpler "construct directly without InMemoryRaftNetwork" path exists in the existing tests, use that.)

- [ ] **Step 2: Run the test**

Run: `./gradlew :kuilt-raft:jvmTest --tests '*TestDispatcherGuardTest*'`
Expected: PASS — three tests total now.

- [ ] **Step 3: Commit**

```bash
git add kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/TestDispatcherGuardTest.kt
git commit -m "test(kuilt-raft): TestDispatcherGuard — verify expectVirtualTime suppresses warning"
```

## Task 4: Banner `RaftTestFixtures.kt` + bake `expectVirtualTime` into `FAST_RAFT_CONFIG`

**Files:**
- Modify: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftTestFixtures.kt`

- [ ] **Step 1: Prepend a banner comment**

Add this comment block ABOVE the `@file:OptIn(...)` line (or just below if it must remain first):

```kotlin
/**
 * # TestDispatcher contract for raft tests
 *
 * Every raft test in this suite that constructs a real [RaftEngine] (via [raftSim]
 * or by calling `nodeScope.raftNode(...)` directly) runs under
 * `UnconfinedTestDispatcher()`. This is **load-bearing**: the engine uses real-clock
 * `delay()` for elections and heartbeats; `UnconfinedTestDispatcher` runs
 * continuations eagerly but does NOT install virtual time, so those `delay()` calls
 * elapse normally on the wall clock. With [FAST_RAFT_CONFIG]'s single-digit-ms
 * timings, the cluster converges in milliseconds.
 *
 * **DO NOT** switch to `StandardTestDispatcher()` or add `testScheduler.advanceTimeBy(...)`
 * to "speed up" a slow test. Under `StandardTestDispatcher` (without explicit
 * `advanceTimeBy`), `delay()` virtual-time-waits forever and the engine deadlocks
 * silently — issue #172. The lone exception is `SchedulerElectionTest`, which uses
 * `StandardTestDispatcher` + `advanceTimeBy` *intentionally* to drive the real-clock
 * election path deterministically.
 *
 * [FAST_RAFT_CONFIG] sets `expectVirtualTime = true` to suppress the TestDispatcher
 * warning across the whole suite. If you introduce a new test fixture, mirror this
 * config — or migrate away from real `RaftEngine` per issue #186.
 */
```

- [ ] **Step 2: Set `expectVirtualTime = true` on `FAST_RAFT_CONFIG`**

Update the existing config:

```kotlin
internal val FAST_RAFT_CONFIG = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,  // see banner above — raft tests use Unconfined intentionally
)
```

- [ ] **Step 3: Run the full raft test suite**

Run: `./gradlew :kuilt-raft:jvmTest`
Expected: PASS — no warnings emitted from any of the 14 tests that used to fire them. (Confirm by grepping the output for "TestDispatcher" or "WARNING".)

- [ ] **Step 4: Commit**

```bash
git add kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftTestFixtures.kt
git commit -m "test(kuilt-raft): banner TestDispatcher contract + set expectVirtualTime on FAST_RAFT_CONFIG"
```

## Task 5: Banner `RaftSimulation.kt`

**Files:**
- Modify: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftSimulation.kt`

- [ ] **Step 1: Prepend a shorter banner**

Add this comment above the class (or above the `package` if it's terser):

```kotlin
/**
 * Drives a multi-node real-[RaftEngine] cluster for testing.
 *
 * Requires `UnconfinedTestDispatcher` — see the banner in `RaftTestFixtures.kt`
 * for the full TestDispatcher contract. Pass the test's `TestScope` as `scope`
 * and `backgroundScope` as `nodeScope` so the infinite election/heartbeat loops
 * are cancelled when the test body completes.
 */
```

- [ ] **Step 2: Run the suite**

Run: `./gradlew :kuilt-raft:jvmTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftSimulation.kt
git commit -m "test(kuilt-raft): banner RaftSimulation TestDispatcher contract"
```

## Task 6: Open the kuilt-raft Phase A PR

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/kuilt-raft-expect-virtual-time
```

- [ ] **Step 2: Open the PR**

```bash
gh pr create --title "feat(kuilt-raft): TestDispatcher guard expectVirtualTime opt-out + fixture banners" --body "$(cat <<'EOF'
> 🤖 This comment was generated by Claude on behalf of @keddie.

Phase A of issue #186: silence the TestDispatcher warning for tests that
intentionally use the pattern, and banner the fixtures so the next contributor
sees the contract.

- Add `expectVirtualTime: Boolean = false` to `RaftConfig` and the shared
  `checkNotUnderTestDispatcher` helper. When `true`, the guard short-circuits.
- Set `expectVirtualTime = true` in `FAST_RAFT_CONFIG` so the 14 existing
  raft tests stop emitting the warning.
- Banner `RaftTestFixtures.kt` and `RaftSimulation.kt` with the TestDispatcher
  contract — what works (`UnconfinedTestDispatcher`), what deadlocks
  (`StandardTestDispatcher` without `advanceTimeBy`), and the lone exception.

Phase B (per-test migration to `advanceTimeBy` or `FakeRaftNode` where
appropriate) follows in separate PRs per issue #186.
EOF
)"
```

- [ ] **Step 3: Auto-merge**

```bash
gh pr merge --auto --squash
```

---

## Task 7: Mirror Phase A for `:kuilt-crdt`'s SeamReplicator

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicator.kt`

- [ ] **Step 1: Read the existing config and guard call**

Find the `SeamReplicatorConfig` class — it may be a nested class inside `SeamReplicator.kt` or its own file. Find the `checkNotUnderTestDispatcher` (or whatever the kuilt-crdt-local equivalent is — PR #189 inlined it; refactoring to share with kuilt-raft's helper is out of scope here).

- [ ] **Step 2: Add `expectVirtualTime: Boolean = false` to `SeamReplicatorConfig`**

Append to the class with KDoc:
```kotlin
/**
 * Suppresses the TestDispatcher warning for tests that intentionally run a real
 * [SeamReplicator] under `UnconfinedTestDispatcher`. Has no effect in production.
 * Default `false`: warn as usual. See [strictTestGuard].
 */
public val expectVirtualTime: Boolean = false,
```

- [ ] **Step 3: Plumb into the guard**

In the `SeamReplicator` constructor / init, locate where the guard is called and add the opt-out path. The local helper (introduced in PR #189) needs the same `expectVirtualTime: Boolean` parameter:

```kotlin
private fun checkNotUnderTestDispatcher(
    scope: CoroutineScope,
    strict: Boolean,
    expectVirtualTime: Boolean,
) {
    if (expectVirtualTime) return
    // ... existing class-name + println/error logic unchanged
}
```

Call site:
```kotlin
checkNotUnderTestDispatcher(
    scope = scope,
    strict = config.strictTestGuard,
    expectVirtualTime = config.expectVirtualTime,
)
```

- [ ] **Step 4: Run the SeamReplicator tests**

Run: `./gradlew :kuilt-crdt:jvmTest --tests '*SeamReplicator*'`
Expected: PASS — all existing tests still green. Default-false behavior is unchanged.

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicator.kt
git commit -m "feat(kuilt-crdt): SeamReplicatorConfig.expectVirtualTime — opt-out from TestDispatcher warning"
```

## Task 8: Apply `expectVirtualTime = true` to the 4 SeamReplicator tests

**Files:**
- Modify: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorTest.kt`
- Modify: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorGapTest.kt`
- Modify: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorEvictionTest.kt`
- Modify: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorChaosTest.kt`

- [ ] **Step 1: Find each test's `SeamReplicatorConfig` construction**

Each file constructs a `SeamReplicatorConfig` somewhere (possibly in a shared helper). Locate every site.

- [ ] **Step 2: Add `expectVirtualTime = true` to each construction**

Mechanical edit per site:
```kotlin
SeamReplicatorConfig(
    antiEntropyInterval = 100.milliseconds,
    evictionAfter = 1.seconds,
    expectVirtualTime = true,  // see kuilt-raft FAST_RAFT_CONFIG banner for the contract; this suite uses UnconfinedTestDispatcher intentionally
)
```

If there's a shared helper that builds the config, update it once and inherit across tests.

- [ ] **Step 3: Run the four tests**

Run: `./gradlew :kuilt-crdt:jvmTest --tests '*SeamReplicator*'`
Expected: PASS, and **no TestDispatcher warnings in the output.** Confirm by grepping for "WARNING".

- [ ] **Step 4: Add a short banner to one of the test files (or a shared helper)**

Pick the topmost shared file in the replicator test package (or create a `ReplicatorTestContract.kt` companion) and add:

```kotlin
/**
 * Replicator tests run a real [SeamReplicator] under `UnconfinedTestDispatcher`.
 * The contract mirrors `:kuilt-raft`'s `RaftTestFixtures.kt`: see issue #186.
 *
 * Tests inject [SeamReplicatorConfig] with `expectVirtualTime = true` so the
 * TestDispatcher guard does not warn. Future replicator tests should follow
 * the same pattern, or use a fake replicator (planned in #186 Phase B).
 */
```

- [ ] **Step 5: Add a suppression test to the guard test**

In `SeamReplicatorTestDispatcherGuardTest.kt` add a test that asserts `expectVirtualTime = true` suppresses even `strictTestGuard = true`:

```kotlin
@Test
fun seamReplicatorUnderTestDispatcher_doesNotWarnOrThrow_whenExpectVirtualTimeIsTrue() = runTest(UnconfinedTestDispatcher()) {
    val config = SeamReplicatorConfig(
        antiEntropyInterval = 100.milliseconds,
        evictionAfter = 1.seconds,
        strictTestGuard = true,
        expectVirtualTime = true,
    )
    // Construct a real SeamReplicator under runTest. With strictTestGuard = true,
    // this would normally throw; expectVirtualTime = true must take precedence.
    val replicator = SeamReplicator(
        replica = ReplicaId("A"),
        seam = /* a no-op InMemoryLoom-derived Seam — copy from SeamReplicatorTest's fixture */,
        initial = GCounter.ZERO,
        messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
        parentScope = backgroundScope,
        config = config,
    )
    assertNotNull(replicator)
}
```

Run it: `./gradlew :kuilt-crdt:jvmTest --tests '*SeamReplicatorTestDispatcherGuardTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/
git commit -m "test(kuilt-crdt): apply expectVirtualTime to SeamReplicator test suite + banner"
```

## Task 9: Open the kuilt-crdt Phase A PR

- [ ] **Step 1: Push**

```bash
git push -u origin feat/kuilt-crdt-expect-virtual-time
```

- [ ] **Step 2: Open the PR**

```bash
gh pr create --title "feat(kuilt-crdt): SeamReplicatorConfig.expectVirtualTime opt-out + test banners" --body "$(cat <<'EOF'
> 🤖 This comment was generated by Claude on behalf of @keddie.

Phase A of issue #186, applied to `:kuilt-crdt`. Adds `expectVirtualTime: Boolean = false`
to `SeamReplicatorConfig`, plumbs it through the existing TestDispatcher guard
(PR #189), and sets it `true` for the 4 SeamReplicator test files so the warning
no longer fires for intentional `UnconfinedTestDispatcher` use. Banner added
referencing the kuilt-raft equivalent (`RaftTestFixtures.kt`).

Phase B (per-test migration to `advanceTimeBy` / fake replicator) is the
follow-on per issue #186.
EOF
)"
gh pr merge --auto --squash
```

---

# Phase B — Per-test re-evaluation

Phase B is **not mechanical**. Each test on the list below gets individually re-evaluated. Two possible outcomes per test:

1. **Migrate to `StandardTestDispatcher + advanceTimeBy`** — drive the real engine under virtual time deterministically. Pattern: `SchedulerElectionTest` in `:kuilt-raft`. Removes the need for `expectVirtualTime = true`.
2. **Keep on `UnconfinedTestDispatcher` with `expectVirtualTime = true` (Phase A already set)** + add a per-test comment documenting *why* (typically "this test relies on real-clock election timing the way SchedulerElectionTest's pattern would be heavier to express").

`FakeRaftNode` is appropriate **only for tests of consumers** of `RaftNode` — tests that mock a single-node cluster and assert on the consumer's behavior. The audit's "Group 1" tests are testing the engine itself, so `FakeRaftNode` is the wrong substitute; `advanceTimeBy` is the right one.

## Task 10: Per-test re-evaluation worksheet

For EACH of the following tests, create a separate PR titled `test(kuilt-raft): migrate <TestName> to virtual time / document real-time contract`:

**Group 1 candidates (assert log/commit invariants):**
- `ReplicationTest` — assess: probably migrate to `advanceTimeBy` (timing-insensitive)
- `EngineCorrectnessTest` — assess: probably migrate
- `LearnerTest` — assess: probably migrate
- `NoOpEntryTest` — assess: keep + document (relies on election→no-op timing)
- `CommittedReplayTest` — assess: probably migrate
- `EdgeCaseTest` — assess: per-test, mixed
- `AwaitLeadershipTest` — assess: keep + document (it's literally about leadership timing)

**Group 2 (intentional real-clock):**
- `ElectionTest` — keep + document
- `ChaosTest` — keep + document
- `CancellationTest` — keep + document
- `InstallSnapshotTest` — keep + document (assess)
- `TraceTest` — keep + document (asserts on engine-emitted trace events)
- `CompactionTest` — keep + document (drives single-voter election)
- `DocumentedUsageTest` — keep + document (DOCUMENTS the API)
- `StorageAtomicityTest` (third test only) — keep + document

**`:kuilt-crdt` SeamReplicator tests** (per Task 8, already silenced):
- `SeamReplicatorTest` — assess: probably migrate parts
- `SeamReplicatorGapTest` — keep (the chaos worker noted Resend retry needs a real ticker, see #180)
- `SeamReplicatorEvictionTest` — keep (already uses `advanceTimeBy` for time-source — verify)
- `SeamReplicatorChaosTest` — keep + document

For each migration:

- [ ] **Step 1: Read the test and identify which assertions depend on real-clock timing**

If the test asserts things like "after N rounds, leader X commits Y" — likely timing-independent → migrate. If the test asserts election timing or heartbeat cadence — keep.

- [ ] **Step 2: Migrate (when chosen)**

Pattern: replace `runTest(UnconfinedTestDispatcher())` with `runTest(StandardTestDispatcher())`. Where the test would have waited on real time (`delay(50.milliseconds)`, or implicit waits), replace with `testScheduler.advanceTimeBy(50.milliseconds)` followed by `testScheduler.runCurrent()`. The `SchedulerElectionTest` is the canonical reference.

If the test injects a `RaftConfig` (currently `FAST_RAFT_CONFIG`), it inherits `expectVirtualTime = true`. **After migration, that override is no longer needed** — explicitly override `expectVirtualTime = false` on the test's local config (so the suite-wide flag doesn't accidentally hide a future regression).

- [ ] **Step 3: Document (when not migrating)**

Add a `/* test-rationale */` comment at the top of the `@Test` method explaining:
```kotlin
/**
 * Real-clock test: this exercises actual election timing under the engine's
 * heartbeat cadence. `advanceTimeBy` would require manually advancing through
 * every election timeout (~5-10ms × N attempts) which is heavier and less
 * informative than the real-clock baseline.
 *
 * Real-clock under `UnconfinedTestDispatcher` is suppressed via
 * `FAST_RAFT_CONFIG`'s `expectVirtualTime = true` — see issue #186.
 */
```

- [ ] **Step 4: Run + commit + PR each test individually**

Run the specific test class. Commit. Push. Open one small PR per test (or per logical group — e.g. all migrations together). Auto-merge each as it goes green.

---

# Self-Review notes

- **Spec coverage:** Phase A covers issue #186's Group 3 (banners) immediately and silences the warnings from Group 1 + 2 + the SeamReplicator suite by setting `expectVirtualTime = true` in the shared config. Phase B covers Group 1 / Group 2's actual migration per test. Per-test judgment is required, so Phase B is structured as a per-test worksheet rather than a single mechanical edit.
- **Placeholder scan:** No "TBD" / "implement later" / unspecified types. The `expectVirtualTime` parameter is fully defined with default false, signature shown, KDoc shown. Each Phase B path has a concrete pattern (the `SchedulerElectionTest` reference).
- **Type consistency:** `expectVirtualTime` is the exact field name used in all 5+ files it touches; `strictTestGuard` is unchanged from PR #184/#189.
- **Coupling to in-flight work:** PR #175 (kover) is still open at the time of writing; it does not interact with this plan's edits. PR #185 (the earlier `ElectionTest` migration issue) is subsumed by this plan's Phase B Task 10 — close #185 with reference to this plan.
- **Out of scope:** Refactoring `:kuilt-crdt`'s inline guard to share `:kuilt-raft`'s helper. The two modules don't depend on each other directly, and `:kuilt-crdt` doesn't depend on `:kuilt-raft`. Sharing would require a new dependency or a third helper module — not justified for two ~30-line guard functions. Mark TODO for a future refactor if a third type ever needs the guard.
