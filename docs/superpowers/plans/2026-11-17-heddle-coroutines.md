# `:kuilt-heddle-coroutines` Implementation Plan

> **For agentic workers:** execute task by task under `worktree-dispatch`, one fresh worker per task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `FairSharePool`, a suspending front door over `LocalHeddle`: `withSlot(lane) { … }` waits for fair share to grant a slot, runs the block, and charges the time it took, with no slot leaked under cancellation (epic #3554).

**Architecture:** A new published module `:kuilt-heddle-coroutines`, package `us.tractat.kuilt.heddle`, over `:kuilt-heddle-core`. One `reentrantLock` guards the engine, a FIFO queue of `CancellableContinuation`s per leaf, the set of leases the pool holds open, and the slot count. Dispatch runs under the lock after every mutation. It collects grants into a pending list, and the pending list is resumed only after the lock is released.

**Tech Stack:** Kotlin Multiplatform (the `kuilt.kmp-library` target set), kotlinx-coroutines 1.11.0, atomicfu 0.33.0 (`reentrantLock`), kotlinx-coroutines-test, `:kuilt-test` (`assertAll`, `TEST_WEDGE_BACKSTOP`).

**Spec:** `docs/superpowers/specs/2026-11-17-heddle-coroutines-design.md`. Read it before your task. The rulings below settle what it leaves to the plan.

## Rulings

1. **Module `:kuilt-heddle-coroutines`** (Iain). Dependencies: `api(project(":kuilt-heddle-core"))`, `api(libs.kotlinx.coroutines.core)`, `implementation(libs.kotlinx.atomicfu)`.
2. **Distributed awaiting on `HeddleNode` is out of scope.** Do not add a follow-up module or stub for it.
3. **`withSlot` charges the measured cost on every exit**: return, throw and cancellation, as `ceil(elapsed / costUnit)`. `cancel` is used only for a grant whose block never started.
4. **`LocalHeddle` gains `public val defaultReservation: Long`**, a read of `policy.defaultReservation`. This rides in Task 1 with its `:kuilt-heddle-core` ABI dump. The pool does not take a second `LocalPolicy`: two policies could disagree.
5. **Dispatch outcomes.**
   - `Acquired` grants the head waiter of `lease.leaf`.
   - `NothingEligible` stops the round.
   - `SupplyExhausted` throws `IllegalStateException`.
   - `NoLiveLineage` or `LifetimeExhausted` runs a **refusal pass**. The pass asks `engine.explain(leaf, setOf(leaf), setOf(leaf))` for each waiting leaf, **with that leaf alone**, and refuses every waiter of a leaf whose answer is `WaitExplanation.NoLiveLineage` or `WaitExplanation.LifetimeExhausted`. Then the loop goes on.
   - A pass that refuses nothing is a contradiction. It goes down Ruling 11's path as an `IllegalStateException` naming the acquire outcome, so it can neither spin nor throw at a stranger.

   The leaf goes alone because explain over the full waiting set answers `LifetimeExhausted` for a healthy leaf when *another* full lane is next in line (`LocalHeddleView.explain`, the `Descent.Exhausted` arm). The spec's default would refuse innocent waiters there.
6. **Extending a waiter's reservation.** When a waiter's reservation is above the default, dispatch calls `engine.extend(lease.id, reservation − default, waiting)`. `waiting` is the queue keys **after** the pop, with the emptied leaf's entry dropped, which matches `LocalHeddleView.previewPicks` (LocalHeddleView.kt:122-125). It does not match `samplePreviewQueues`, which passes the set from before the pop. On `Done(unrecorded = 0)` the granted `Lease` is `lease.copy(reserved = reservation)`. On any other answer the lease is granted as acquired, at the default, and the overrun is charged at `complete`. `PathDraining` and `Unknown` cannot follow an acquire in the same critical section.
7. **Re-entry and draining.** Every public mutation goes through one private `mutate { … }`, which increments a `depth` counter under the lock. Only the outermost frame (`depth == 1`) dispatches.
   - Only the frame that takes `depth` back to 0 drains the pending list. It swaps the list out to a local **while it still holds the lock**, releases the lock, and then resumes from the local in a `finally`, so a throwing mutation still resumes what was granted.
   - An inner frame never resumes anything. The lock is reentrant, so an inner frame resuming would run continuations under the outer caller's lock.
   - A hand-back or a removal reached from inside a `withEngine` block mutates without dispatching, and the outer frame dispatches for it.
   - KDoc says a pool resumed through a dispatcher that runs inline with no event loop, like a direct executor, nests one resume per inline `complete`. Coroutines' own `Unconfined` queues them on the thread's event loop.
8. **The constructor adopts the engine's open leases.** A restored engine's open leases are valid under their old ids (LocalHeddleSamples.kt:447-460), so the pool counts them in `open` from the start, and they hold slots like any other.
   - The constructor takes `clockStart: Long = 0L`. `public fun now(): Long` reads `clockStart + elapsed` in whole cost units, and every `startedAt` the pool passes is `now()`.
   - KDoc: persist `now()` beside the snapshot and pass it (plus downtime, if known) as `clockStart` after a restart, so `reconcileLeases(live, pool.now())` through `withEngine` measures adopted leases correctly. With the default of 0, an adopted lease's elapsed reads 0.
   - Validation order: check every argument, then call `reconcileCapacity`, then assign. Nothing is assigned if the engine refuses.
9. **Skill routing.** The `kuilt-primitives` description is not edited, because "fair-share scheduling" already routes to the heddle index row. Name `FairSharePool` (Task 1) and `withSlot` (Task 2) in that row's symbol column, and trim the same row to stay under budget. On `ead6ceee1` the description is 1,532 of 1,536 characters and the body is 8,170 of 8,192 bytes.
10. **The stress probe follows the repo's `*ConcurrencyTest` gating convention.** It is excluded at the `AbstractTestTask` level unless `-Pconcurrency.stress.tests=true`, using both filter spellings as `:kuilt-heddle-core`'s `LeaseScaleProbe` gate does. A step in CI's non-blocking `concurrency-probes` job runs it on the JVM. Run `macosArm64Test` and `wasmJsTest` locally, and put each target's XML test count in the PR body.
   - It lives in `commonTest` on `Dispatchers.Default`, not in `jvmTest` on `:kuilt-test`'s JVM-only `runConcurrencyStress`. That is a deliberate departure: the claim is about Kotlin/Native's threads as well as the JVM's. It costs the harness's coroutine dump on a hang, so print the pool's counters in every failure message.
   - On wasmJs it witnesses that the body runs and nothing else. `mutate` never suspends, so nothing interleaves inside it on a single thread. The `ALLOW-realDispatcher` reason and the PR body say so; the lock claim rests on JVM and macosArm64.
11. **A dispatch failure goes to the waiters, never to the caller who triggered dispatch.** The caller might be a stranger's `complete`, `withSlot`'s `finally`, or a cancellation handler, and a throw from a handler reaches the uncaught-exception handler, which aborts a Kotlin/Native process. Sources of failure are the engine throwing (`IllegalStateException` or `ArithmeticException`) and Ruling 5's contradictions.
    - Dispatch catches the failure (a line-tight `// ALLOW-ise: nothing inside the try suspends`). It removes every queued waiter and adds each to the pending list as a failure, which the drain resumes with `resumeWithException`. It then stops the round.
    - The mutation that triggered it returns normally: `complete` still answers its `Done`, and `cancel` and the handlers never throw for someone else's queue.
    - Later waiters get a fresh dispatch, which fails them the same way if the engine is still broken.
12. **An unleasable name is refused at arrival** (moved into Task 1 from Task 3). When an arrival creates its leaf's queue entry and no slot is free (`open.size >= slots`, including a zero-slot pool), the pool asks `explain(leaf, setOf(leaf), setOf(leaf))` once. It refuses at once on `NoLiveLineage` or `LifetimeExhausted`, through a single-leaf answer separate from `refuseUnleasable`, which only refuses waiters already queued. An arrival joining an existing entry is not checked, so a busy pool pays one explain per new leaf, not per waiter. When a slot is free, dispatch reaches the leaf at once and needs no check. So a zero-slot pool refuses an unleasable name and never a healthy one, and `withEngine`'s pass agrees with it.
13. **Two unenforced module listings get a row:** `README.md`'s Scheduling table (~line 167) and `Writerside/topics/modules.md` (~line 29), both in the plain-language voice of their neighbours. Run `vale --no-global README.md Writerside/topics` after editing.
14. **The gap between a grant and its waiter running is uncharged.** `withSlot` marks time when the block starts, while `startedAt` is taken at dispatch. KDoc says so.

## Global Constraints

- Package `us.tractat.kuilt.heddle`, the same package as both heddle modules. `explicitApi()`: every public declaration says `public`.
- `:kuilt-heddle-core` must not resolve kotlinx-coroutines (`verifyHeddleCoreDependencies`). Nothing in this epic adds a dependency there.
- Mutual exclusion comes from `reentrantLock` only, never `limitedParallelism(1)`. No suspend call inside a lock body (`forbidSuspendCallUnderLock`). Resume continuations outside the lock.
- No `!!`, no bare `runCatching`, no catch that swallows `CancellationException` (`forbidCancellationSwallowingCatch`).
- Tests: no `test` prefix; a multi-assert test uses `assertAll`. Virtual-time tests use `runTest` with `StandardTestDispatcher(testScheduler)` unless a step names `UnconfinedTestDispatcher`. Pass `timeSource = testScheduler.timeSource` and `costUnit = 1.seconds`. A `runTest` timeout is `TEST_WEDGE_BACKSTOP`, never a literal.
- Shared fixture, test-only (`commonTest/.../PoolFixtures.kt`, Task 1): `farm()` returns the engine from `LocalHeddle.fromTopology(Topology("farm", listOf(Topology.Lane("web", Weight.of(3)), Topology.Lane("mobile"))), LocalPolicy(defaultReservation = 3L), epoch = 0L).heddle`, with `web` and `mobile` from `topology.groupId(…)`.
- Real dispatchers appear in Task 4 only, with a line-tight `// ALLOW-realDispatcher: <reason>` on the import.
- Build: `export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-tem`. The `source ~/.sdkman/...` form is refused inside a worker worktree. Fence every Gradle call with `timeout 600`.
- Before marking ready: `timeout 600 ./gradlew clean`, then `timeout 600 ./gradlew :kuilt-heddle-coroutines:build --rerun-tasks`, and check the test tasks ran `EXECUTED`. Then the root guards: `timeout 600 ./gradlew verifyModuleTable verifyFamilyRules verifyDocCitations forbidUncitedDocCodeBlock verifySampleLinks verifySamplesAreRun verifySkillDescriptionBudget verifySkillBodyBudget forbidProductionDispatcherInTests forbidCancellationSwallowingCatch forbidSuspendCallUnderLock forbidTightRunTestTimeout forbidRuntimeSelfSkippingProbe forbidNotNullAssertionInUnresolvedSource`, and `python3 .github/scripts/llms-txt.py --check`.
- One task is one PR. Claim it with a Draft PR before the bulk of the work. The PR body names its sub-issue of #3554. Auto-close is off. Never write "chore". End the PR body with the attribution line.

## Review Focus

0. **A dispatch failure never escapes through a stranger's call or a cancellation handler** (Ruling 11). Test: Task 1, `aDispatchFailureFailsTheWaitersAndLeavesCompleteIntact`.
1. **A waiter for a name no lease can end at, while every slot is busy**, is refused at arrival. It does not sit until every healthy lane drains. Test: Task 1, `aWaiterForAnUnleasableNameIsRefusedEvenWhenEverySlotIsBusy`.
2. **A refusal never takes a healthy lane's waiter.** With an unleasable leaf queued beside a healthy one, the healthy waiter is granted and only the bad leaf is refused. Test: Task 1, `aRefusalNeverTakesAHealthyLanesWaiter`.
3. **A double `complete`, a `cancel` after `complete`, or a lease id the pool never issued** neither frees a second slot nor drives the count below zero. Test: Task 1, `aDoubleCompleteFreesOneSlot` and `aLeaseThePoolDidNotIssueLeavesTheCountAlone`.
4. **A `withEngine` block that throws** still resumes the grants its re-dispatch made, and leaves the pool usable. A block that completes a pool lease through the engine directly frees that slot. Test: Task 3, `aWithEngineBlockThatThrowsStillResumesItsGrants` and `aLeaseCompletedThroughTheEngineFreesItsSlot`.
5. **Zero slots and lowering the count.** A pool at 0 slots queues every healthy waiter and refuses only an unleasable name (Ruling 12). `resize` below the open count preempts nothing, and grants resume only once open < slots. Test: Task 3, `resizeFromZeroGrantsInThePreviewOrder` and `loweringTheSlotCountPreemptsNothing`.

---

### Task 1: The module, `FairSharePool.acquire` / `complete` / `cancel`, and the grant/cancel race

**Files:**
- Create: `kuilt-heddle-coroutines/build.gradle.kts`
- Create: `kuilt-heddle-coroutines/module.md`
- Create: `kuilt-heddle-coroutines/src/commonMain/kotlin/us/tractat/kuilt/heddle/FairSharePool.kt`
- Create: `kuilt-heddle-coroutines/src/commonMain/kotlin/us/tractat/kuilt/heddle/SlotOutcome.kt`
- Create: `kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt`
- Create: `kuilt-heddle-coroutines/api/` (generated by `updateKotlinAbi`)
- Test: `kuilt-heddle-coroutines/src/commonTest/kotlin/us/tractat/kuilt/heddle/{PoolFixtures,FairSharePoolTest,FairSharePoolCancellationTest,FairSharePoolFairnessTest,FairSharePoolSamplesRunTest}.kt`
- Modify: `kuilt-heddle-core/src/commonMain/kotlin/us/tractat/kuilt/heddle/LocalHeddle.kt` (a property beside `epoch`, ~line 498), `kuilt-heddle-core/api/**`, `kuilt-heddle-core/src/commonTest/kotlin/us/tractat/kuilt/heddle/LocalHeddleTest.kt`
- Modify: `settings.gradle.kts:104`, `AGENTS.md` (module table), `build-logic/src/main/kotlin/kuilt.publish.gradle.kts:153`, `.claude/rules/heddle.md` (`paths:`), `docs/agent-cookbook/heddle.md`, `docs/agent-cookbook.md:65`, `.claude/skills/kuilt-primitives/SKILL.md:47`, `llms.txt` (generated)

**Interfaces:**
- Consumes: `LocalHeddle.acquire(waiting, eligible, startedAt, reservation)`, `extend(lease, amount, waiting)`, `complete(lease, actualCost, waiting)`, `cancel(lease)`, `explain(leaf, waiting, eligible)`, `reconcileCapacity(totalSlots)`, `snapshot()`, `LocalHeddle.MAX_RESERVATION`.
- Produces:
  - `LocalHeddle.defaultReservation: Long`.
  - `public class FairSharePool(engine: LocalHeddle, slots: Int, timeSource: TimeSource.WithComparableMarks, costUnit: Duration, clockStart: Long = 0L)`, adopting the engine's open leases (Ruling 8).
  - `public fun now(): Long`.
  - `public suspend fun acquire(leaf: GroupId, reservation: Long = defaultReservation): SlotOutcome`.
  - `public fun complete(lease: LeaseId, actualCost: Long): LeaseOutcome`.
  - `public fun cancel(lease: LeaseId): LeaseOutcome`.
  - `public sealed interface SlotOutcome { data class Granted(val lease: Lease); data class Refused(val reason: WaitExplanation) }`.
  - Internal, for later tasks:
    - `private fun <R> mutate(block: () -> R): R` (Ruling 7).
    - `private fun refuseUnleasable(leaves: Collection<GroupId>): Int`, which returns the number of waiters refused (Ruling 5).
    - `private fun nowUnits(): Long`.
    - `internal val openForTest: Int`, `internal val waitersForTest: Int`.
    - `internal var beforeResumeForTest: (SlotOutcome) -> Unit = {}`.

- [ ] **Step 1: Claim.** Open a Draft PR on an empty commit, naming the Task 1 sub-issue of #3554.

- [ ] **Step 2: Scaffold the module.**
  - `build.gradle.kts`: `id("kuilt.kmp-library")`, the three dependencies from Ruling 1, and `commonTest.dependencies { implementation(project(":kuilt-test")); implementation(libs.kotlinx.coroutines.test) }`. No serialization plugin.
  - Add `include(":kuilt-heddle-coroutines")` after `:kuilt-heddle-core` in `settings.gradle.kts`.
  - Add `"kuilt-heddle-coroutines/**"` to `.claude/rules/heddle.md`'s `paths:` (`verifyFamilyRules`).
  - Add this branch after `"kuilt-heddle-core"` in `moduleDescription()`:
    `"Wait your turn for a slot: a coroutine front door over LocalHeddle that suspends until fair share grants a slot, runs the work and charges the time it took."`
  - Add this AGENTS.md row after `:kuilt-heddle-core`'s:
    ``| `:kuilt-heddle-coroutines` | all | Waiting your turn for a slot in one process: `FairSharePool` holds a `LocalHeddle` and a slot count, and suspends a caller until fair share grants its leaf a slot, with no slot leaked when a waiter is cancelled at the moment of its grant. Depends on `:kuilt-heddle-core` + kotlinx-coroutines (`api`) and atomicfu; see `kuilt-heddle-coroutines/module.md`. |``
  - The BOM needs no edit: `kuilt-bom` collects every module that applies `kuilt.publish`.

  Run: `timeout 600 ./gradlew :kuilt-heddle-coroutines:tasks verifyModuleTable verifyFamilyRules`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: `LocalHeddle.defaultReservation`.**
  - Write the test first: `defaultReservationIsThePolicys` in `LocalHeddleTest` asserts `fromTopology(…, LocalPolicy(defaultReservation = 7L), 0L).heddle.defaultReservation == 7L`.
  - Then add `public val defaultReservation: Long get() = policy.defaultReservation`, with KDoc: "the reservation an `acquire` makes when the caller names none".
  - Run `timeout 600 ./gradlew :kuilt-heddle-core:updateKotlinAbi :kuilt-heddle-core:jvmTest --tests "*LocalHeddleTest"`. Expected: PASS, and the diff under `kuilt-heddle-core/api/` adds only `getDefaultReservation`.

- [ ] **Step 4: Write the failing tests in `FairSharePoolTest`.** Each builds `FairSharePool(farm engine, slots, testScheduler.timeSource, 1.seconds)`.
  - `aWaiterOnAnIdlePoolIsGrantedAtOnce`: `acquire(web)` is `Granted` with `lease.leaf == web`, and `openForTest == 1`.
  - `aWaiterBeyondTheSlotCountWaitsUntilALeaseCloses`: slots = 1. A is granted. B is launched, and after `runCurrent()` it is not done and `waitersForTest == 1`. Then `complete(A, 3L) == LeaseOutcome.Done(3L, 0L, 0L)`. After `runCurrent()`, B is `Granted`.
  - `neverMoreThanSlotsOpenUnderVirtualTime`: slots = 2. Launch 12 waiters alternating web and mobile. Each one, on its grant, increments `inFlight`, records the max, `delay((1 + i % 3).seconds)`, decrements, and completes at 1. Assert `maxInFlight == 2` (equal, so the bound was reached), 12 grants, and at the end `openForTest == 0`.
  - `aReservationAboveTheDefaultExtendsTheLease`: `acquire(web, reservation = 10L)` grants a lease with `reserved == 10L`, and `engine.snapshot().leases.single().reserved == 10L`.
  - `aReservationOutOfRangeThrowsBeforeSuspending`: `assertFailsWith<IllegalArgumentException>` for `0L` and for `LocalHeddle.MAX_RESERVATION + 1`, and `waitersForTest == 0`.
  - `aNameNoLeaseCanEndAtIsRefused`: on an idle pool, `acquire(GroupId("nope")) == SlotOutcome.Refused(WaitExplanation.NoLiveLineage(GroupId("nope")))`.
  - No test reaches `LifetimeExhausted` through the public API: it needs `Long` counters near the watermark, and the hooks that set them up are internal to `:kuilt-heddle-core`. That arm shares the refusal pass with `NoLiveLineage`, so the `NoLiveLineage` arms pin it. Say so in the PR body.
  - `aRefusalNeverTakesAHealthyLanesWaiter`: slots = 1, and A holds web. Queue W2 on web, then a waiter on `GroupId("nope")`. `complete(A)`: W2 is `Granted`. `complete(W2)`: nope is `Refused(NoLiveLineage(nope))`. At the end, `openForTest == 0`.
  - `aDoubleCompleteFreesOneSlot`: slots = 1. `complete(A)` is `Done`, and a second `complete(A) == LeaseOutcome.Unknown`. Launch B and C. Only B is granted: `openForTest == 1` and `waitersForTest == 1`.
  - `aLeaseThePoolDidNotIssueLeavesTheCountAlone`: `complete(LeaseId(0L, 99L), 1L)` and `cancel(LeaseId(0L, 99L))` are both `Unknown`, and `openForTest` is unchanged.
  - `cancelReturnsTheSlot`: `cancel(A) == Done(0L, 0L, 0L)`, and a queued B is then granted.
  - `anEngineWithOpenLeasesIsAdopted` (Ruling 8): slots = 1. Open one lease with `engine.acquire(setOf(web), setOf(web), 0L)` before constructing the pool. Then `openForTest == 1`, and a pool waiter waits. `pool.complete(adopted.id, 3L)` is `Done`, and the waiter is granted.
  - `nowStartsAtClockStart`: `clockStart = 40L`. `now() == 40L`, and after `advanceTimeBy(3.seconds)`, `now() == 43L`. A granted lease's `startedAt` equals `now()` at its dispatch.
  - `aWaiterForAnUnleasableNameIsRefusedEvenWhenEverySlotIsBusy` (Ruling 12): slots = 1, with A held. `acquire(GroupId("nope"))` completes after `runCurrent()` as `Refused(NoLiveLineage(nope))`, while `openForTest == 1`. Then the same at slots = 0. A healthy `acquire(web)` at slots = 0 is still queued.
  - `aDispatchFailureFailsTheWaitersAndLeavesCompleteIntact` (Ruling 11). Rig a failure through an internal `dispatchFaultForTest: (() -> Unit)?` hook that dispatch calls before each engine `acquire`, set to throw. With A held and B, C queued, set the hook and call `pool.complete(A, 3L)`. It answers `Done(3L, 0L, 0L)` without throwing. B and C both fail with the hook's exception, and `waitersForTest == 0`. Clear the hook, and a fresh `acquire(web)` is `Granted`. A second arm triggers the fault from a cancellation handler (cancel a queued waiter while the hook is set): nothing reaches `CoroutineExceptionHandler`, which the test installs and asserts is never called.
  - `anAlreadyCancelledCallerIsNeverQueued` (the `isActive` guard): on a busy pool, a job calls `currentCoroutineContext().job.cancel()` and then `acquire(web)`. Assert `waitersForTest == 0` before any completion.
  - `anInnerMutationNeverResumes` (Ruling 7): this arm needs Task 3's `withEngine`, so it lands there. In Task 1, pin draining with the inline-complete arm below.
  - `lifetimeExhaustedRefusesOnlyTheFullLane`: **first try** to reach `LifetimeExhausted` through the public API. `complete` records an overrun up to `MAX_RECORDED_OVERRUN` per call (LocalHeddle.kt:852, :1746) against a watermark of `Long.MAX_VALUE − LIFETIME_HEADROOM`, so about 62 overrunning completes on one lane may reach it. If it works:
    - queue the full lane beside a healthy lane;
    - assert only the full lane's waiters are refused, and the healthy lane is granted;
    - add "explain with the full waiting set" to Step 8, expected red.

    If the root's mint trips first or the route fails, say so in the PR as **measured**, with the number reached, and fall back to the NoLiveLineage-only pinning below.
  - `mixedReservationsGrantInPreviewOrder` (Ruling 6): slots = 1, with queues `web: [3, 12, 3, 3]` and `mobile: [3, 3, 3]`, as in `samplePreviewQueues`. Each waiter completes at its own reservation + 1 on grant, so `complete`'s `waiting` is exercised off the reservation. The grant order equals `previewPicks(queues, eligible)` on a twin `farm()` engine.
  - `constructionValidatesItsArguments`: `slots = -1`, `costUnit = Duration.ZERO`, `-1.seconds` and `Duration.INFINITE` each throw `IllegalArgumentException`.

  Run: `timeout 600 ./gradlew :kuilt-heddle-coroutines:jvmTest --tests "*FairSharePoolTest"`. Expected: FAIL to compile, since `FairSharePool` is undefined.

- [ ] **Step 5: Implement `SlotOutcome.kt` and `FairSharePool.kt`.**
  - The constructor validates its arguments, takes `origin = timeSource.markNow()`, and calls `engine.reconcileCapacity(slots.toLong())`.
  - Waiter queues: `LinkedHashMap<GroupId, ArrayDeque<Waiter>>`, which iterates in insertion order and so the same on every target. Drop a leaf's entry when its queue empties.
  - The `waiting` set passed to the engine is the map's keys.
  - `nowUnits()` is `(timeSource.markNow() - origin)` in whole `costUnit`s, floored.
  - `acquire`:
    1. Validate `reservation in 1..MAX_RESERVATION` before suspending.
    2. In `suspendCancellableCoroutine`, register `invokeOnCancellation { mutate { removeWaiter(w) } }` first.
    3. Then `mutate { if (cont.isActive) enqueue(w) }`.
  - Dispatch loop: `while (open.size < slots && queues.isNotEmpty())`, handling each outcome as in Ruling 5.
  - A grant pops the head waiter, applies Ruling 6, adds the lease id to `open` (a `HashSet<LeaseId>`), and appends `(cont, Granted)` to `pending`.
  - Resume each pending grant with `cont.resume(granted) { _, _, _ -> handBack(lease.id) }`, capturing the lease: the lambda's value parameter is a `SlotOutcome`. Resume a refusal with `cont.resume(refused, null)`. Call `beforeResumeForTest(outcome)` just before each `resume`.
  - `handBack(id)` is `mutate { if (open.remove(id)) engine.cancel(id) }`.
  - Also implement Rulings 6 (the `waiting` passed to `extend`), 7 (drain only at depth 0, swapped out under the lock), 8 (adoption, `clockStart`, `now()`), 11 (dispatch failures go to the waiters) and 12 (the arrival check). Each has a test in Step 4.
  - `complete` and `cancel` call the engine with `waiting = queues.keys` (`complete` only). They remove the id from `open` only when the id was in it and the answer is `Done`.

  Run: the Step 4 command. Expected: PASS.

- [ ] **Step 6: Write the cancellation tests in `FairSharePoolCancellationTest`.** Each ends with `assertAll` on these invariants: `openForTest == 0`, `engine.snapshot().leases.isEmpty()`, `waitersForTest == 0`, and a fresh `acquire(web)` is `Granted`.
  - `aWaiterCancelledWhileQueuedLeavesNoTrace`: A holds the one slot, and B is queued. Cancel B and `runCurrent()`. **Before completing A**, assert `waitersForTest == 0`: this arm reds if `invokeOnCancellation` stops removing. Then complete A.
  - `aWaiterCancelledAfterItsResumeButBeforeItRunsHandsTheLeaseBack`: `complete(A)` resumes B, but B has not run under `StandardTestDispatcher`. The rig: `openForTest == 1` and `!jobB.isCompleted`. Then `jobB.cancel()` and `runCurrent()`.
  - `aWaiterCancelledBeforeItsResumeHandsTheLeaseBack`: arm `beforeResumeForTest` to cancel B's job just before `complete(A)`, and disarm it right after. It also fires on A's grant and on the closing fresh `acquire`, so an armed-throughout hook cannot count to one. The rig: while armed, the hook fired exactly once.
  - `aResumedWaiterThatCompletesInlineGrantsTheNext`: one slot, held by the test. Launch 1,000 waiters on `UnconfinedTestDispatcher(testScheduler)`. Each calls `pool.complete(lease.id, 1L)` as soon as it is granted. Complete the held lease. Assert 1,000 grants in arrival order, then the invariants. This arm cannot show a deadlock, since one thread on a reentrant lock cannot deadlock, or a stack overflow, since `Unconfined` queues nested resumes on the thread's event loop. What it does catch is resume-under-lock: an inline `complete` would run at depth 2, skip dispatch and strand the next waiter. Step 8 carries that mutation.

  Run: `timeout 600 ./gradlew :kuilt-heddle-coroutines:jvmTest --tests "*FairSharePoolCancellationTest"`. Expected: PASS.

- [ ] **Step 7: Write the fairness test in `FairSharePoolFairnessTest`.** `oneSlotGrantsInTheTwinEnginesOrder` uses slots = 1.
  1. The test acquires a web lease and holds it.
  2. Queue 30 web waiters, then 10 mobile waiters, each completing at `3L` on grant. The test then completes its held lease at `3L`. Read the grant order from the leases' `LeaseId` order.
  3. Feed a twin `farm()` engine the same calls:
     - `acquire(setOf(web), setOf(web), 0L)`, then `complete(id, 3L, setOf(web, mobile))`;
     - for each grant, `acquire(w, w, 0L)` with `w` = the lanes with waiters left before the pop, then `complete(id, 3L, w')` with `w'` = the lanes left after it.

  Assert the two leaf sequences are equal. Also assert `grants.take(8).any { it == mobile }`, so the test reds if the pool serves arrival order.

  Run: `timeout 600 ./gradlew :kuilt-heddle-coroutines:jvmTest --tests "*FairSharePoolFairnessTest"`. Expected: PASS.

- [ ] **Step 8: Mutation check, then revert each mutation.** Record each verdict in the PR body.
  - Resume a grant with a `null` onCancellation: both "…HandsTheLeaseBack" arms red.
  - Delete the `invokeOnCancellation` removal: `aWaiterCancelledWhileQueuedLeavesNoTrace` reds.
  - Drop `open.size < slots` from the loop: `neverMoreThanSlotsOpenUnderVirtualTime` reds.
  - Drop the `cont.isActive` check from the enqueue: `anAlreadyCancelledCallerIsNeverQueued` reds.
  - Resume the pending list at every depth rather than only at depth 0: `aResumedWaiterThatCompletesInlineGrantsTheNext` reds.
  - Let the dispatch failure propagate instead of delivering it (Ruling 11): `aDispatchFailureFailsTheWaitersAndLeavesCompleteIntact` reds.
  - Delete the arrival check (Ruling 12): `aWaiterForAnUnleasableNameIsRefusedEvenWhenEverySlotIsBusy` reds.

  A mutation that stays green is a vacuous arm. Fix the arm, not the mutation.

- [ ] **Step 9: Docs and routing.**
  - Sample: `internal suspend fun sampleAcquireSlot()` in `FairSharePoolSamples.kt`. It boots the two-lane farm, builds a two-slot pool on `TimeSource.Monotonic` with `costUnit = 1.minutes`, acquires web, completes it at 5 (`Done(5, 2, 0)` against a default of 3), and acquires mobile. Assert only on outcomes, never on a time measured from `Monotonic`.
  - `FairSharePoolSamplesRunTest.samples()` calls it inside `runTest`.
  - Reference it with `@sample us.tractat.kuilt.heddle.sampleAcquireSlot` from the `FairSharePool` and `acquire` KDoc.
  - Add a cookbook section `## Waiting your turn for a slot` after "A fair-share queue in one process", in the same **Intent / Primitive** shape. Quote the sample under a `<!-- verbatim from kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt#sampleAcquireSlot -->` citation.
  - Add an index row in `docs/agent-cookbook.md` after line 65. Intent column: "waiting your turn for a slot — a coroutine that suspends until fair share grants it, a semaphore per lane, handing back a slot whose waiter gave up". Primitive column: `FairSharePool.acquire` → `SlotOutcome`, `complete`, `cancel` (`:kuilt-heddle-coroutines`).
  - Add `FairSharePool` to the heddle row of `SKILL.md`, trimming that row by at least as many bytes (Ruling 9).
  - `module.md`: `# Module kuilt-heddle-coroutines`. Open in plain language, using the spec's build-farm story. Quote the same sample verbatim; a `module.md` cannot use `@sample`.
  - Run `python3 .github/scripts/llms-txt.py`.
  - Add the two rows from Ruling 13, then `vale --no-global README.md Writerside/topics`.

  Run: `timeout 600 ./gradlew verifyDocCitations forbidUncitedDocCodeBlock verifySampleLinks verifySamplesAreRun verifySkillDescriptionBudget verifySkillBodyBudget` and `python3 .github/scripts/llms-txt.py --check`. Expected: all green.

- [ ] **Step 10: ABI and the full gate.** Run `timeout 600 ./gradlew :kuilt-heddle-coroutines:updateKotlinAbi`, then everything in the Global Constraints' "Before marking ready" line. Expected: green, with the test tasks `EXECUTED`.

- [ ] **Step 11: Commit, push and mark ready.** Commit message: `heddle-coroutines: FairSharePool waits for a fair-share slot, with no leak under cancellation`.

---

### Task 2: `withSlot`, with measured cost

Runs after Task 1 merges.

**Files:**
- Modify: `kuilt-heddle-coroutines/src/commonMain/kotlin/us/tractat/kuilt/heddle/FairSharePool.kt`
- Create: `kuilt-heddle-coroutines/src/commonMain/kotlin/us/tractat/kuilt/heddle/SlotRefusedException.kt`
- Test: `kuilt-heddle-coroutines/src/commonTest/kotlin/us/tractat/kuilt/heddle/FairSharePoolWithSlotTest.kt`
- Modify: `FairSharePoolSamples.kt`, `FairSharePoolSamplesRunTest.kt`, `docs/agent-cookbook/heddle.md` (§ Waiting your turn for a slot), `docs/agent-cookbook.md` (the Task 1 row), `.claude/skills/kuilt-primitives/SKILL.md:47`, `kuilt-heddle-coroutines/module.md`, `kuilt-heddle-coroutines/api/**`

**Interfaces:**
- Consumes: Task 1's `acquire`, `complete`, `openForTest`, `beforeResumeForTest`, and the `PoolFixtures` `farm()`.
- Produces:
  - `public suspend fun <T> withSlot(leaf: GroupId, reservation: Long = defaultReservation, block: suspend (Lease) -> T): T`.
  - `public class SlotRefusedException(public val reason: WaitExplanation) : IllegalStateException("no lease can be granted for this leaf: $reason")`.
  - `internal fun costUnits(elapsed: Duration, costUnit: Duration): Long`, which rounds up.
  - `internal var afterCompleteForTest: (lease: LeaseId, actualCost: Long, outcome: LeaseOutcome) -> Unit = { _, _, _ -> }`, which `complete` calls after the engine answers.

- [ ] **Step 1: Claim** with a Draft PR naming the Task 2 sub-issue.

- [ ] **Step 2: Write the failing tests.** Use `costUnit = 1.seconds` and a default reservation of 3. Capture `actualCost` and the outcome through `afterCompleteForTest`.
  - `costUnitsRoundsUp`: the table `0ns → 0`, `1ns → 1`, `3s → 3`, `3s + 1ns → 4`. For the overflow row, take `d = Long.MAX_VALUE.nanoseconds` and derive the expectation from it: `costUnits(d, 1.nanoseconds) == d.inWholeNanoseconds`. Past `Duration`'s nanosecond range it is stored in whole milliseconds, so it is not `Long.MAX_VALUE`. Add a `costUnits(d, 3.nanoseconds)` row checked against `ceil` computed in `Int128` or by hand, with no overflow.
  - `aBlockThatDelaysFiveUnitsIsChargedFive`: `withSlot(web) { delay(5.seconds); "done" } == "done"`, with `actualCost == 5L` and `outcome == Done(5L, 2L, 0L)`.
  - `aBlockThatThrowsAfterTwoIsChargedTwoAndTheThrowableEscapes`: the block delays 2 seconds and then throws a test-local `Boom`. `assertFailsWith<Boom>` holds, `actualCost == 2L`, and `openForTest == 0`.
  - `aBlockCancelledAfterThreeIsChargedThreeAndTheCancellationPropagates`: launch the job, `advanceTimeBy(3.seconds)`, `runCurrent()`, `job.cancel()`, `runCurrent()`. Then `job.isCancelled`, `actualCost == 3L`, `openForTest == 0`, and `engine.snapshot().leases.isEmpty()`.
  - `aBlockShorterThanOneUnitIsChargedOne` delays 500 ms and is charged 1. `aBlockThatTakesNoTimeIsChargedZero` is charged 0.
  - `withSlotThrowsWhereAcquireAnswersRefused`: `assertFailsWith<SlotRefusedException> { withSlot(GroupId("nope")) { } }`. Its `reason == NoLiveLineage(GroupId("nope"))`, it `is IllegalStateException`, and the block never ran.
  - `aWaiterCancelledBeforeItsBlockRunsIsHandedBackNotCharged`: Task 1's race through `withSlot`, cancelled by the `beforeResumeForTest` hook. The block never ran, `afterCompleteForTest` never fired, and the Task 1 invariants hold.
  - `aCompleteInsideTheBlockIsNotChargedTwice`: the block calls `pool.complete(lease.id, 1L)` itself. The `finally`'s complete answers `Unknown`, `openForTest == 0`, and a second waiter is granted exactly once.

  Run: `timeout 600 ./gradlew :kuilt-heddle-coroutines:jvmTest --tests "*FairSharePoolWithSlotTest"`. Expected: FAIL to compile.

- [ ] **Step 3: Implement.**
  - `costUnits` divides in whole nanoseconds, as `e / u + if (e % u != 0L) 1 else 0`. The `(e + u − 1) / u` form overflows near `Long.MAX_VALUE`.
  - `withSlot` calls `acquire`. On `Refused` it throws `SlotRefusedException`. On `Granted` it marks the time, runs `try { block(lease) } finally { complete(lease.id, costUnits(mark.elapsedNow(), costUnit)) }`. There is no catch and no `NonCancellable`, because `complete` does not suspend.
  - KDoc says a block that fails or is cancelled is still charged, and why: a lane that fails fast must not take free share.

  Run: the Step 2 command. Expected: PASS.

- [ ] **Step 4: Mutation check.** Move the `complete` out of the `finally`, after the block. The throw and cancel arms must red. Revert.

- [ ] **Step 5: Docs.**
  - Sample: `internal suspend fun sampleWithSlot()`. Two lanes share one slot, and two `withSlot` calls run inside `coroutineScope { launch … }`. Assert results, never measured charges. Call it from `FairSharePoolSamplesRunTest.samples()`, and reference it from the `withSlot` KDoc.
  - Lead the cookbook section with `withSlot` (verbatim quote), and keep `acquire` as the low-level form below it.
  - Add `withSlot` and `SlotRefusedException` to the index row.
  - In `SKILL.md`'s heddle row, make the Task 1 symbol `FairSharePool.withSlot`, within the body budget.
  - In `module.md`, lead with `withSlot`.

  Run the Task 1 Step 9 command. Expected: green.

- [ ] **Step 6: ABI, the full gate, then commit** (`heddle-coroutines: withSlot charges the time a block held its slot, on every exit`), push, and mark ready.

---

### Task 3: `withEngine`, `resize`, and refusing waiters whose leaf is unleasable

Runs after Task 2 merges, because both edit `FairSharePool.kt`.

**Files:**
- Modify: `kuilt-heddle-coroutines/src/commonMain/kotlin/us/tractat/kuilt/heddle/FairSharePool.kt`
- Modify: `kuilt-heddle-coroutines/src/commonTest/kotlin/us/tractat/kuilt/heddle/FairSharePoolTest.kt` (`aRefusalNeverTakesAHealthyLanesWaiter`)
- Test: `kuilt-heddle-coroutines/src/commonTest/kotlin/us/tractat/kuilt/heddle/FairSharePoolEngineTest.kt`
- Modify: `FairSharePoolSamples.kt`, `FairSharePoolSamplesRunTest.kt`, `docs/agent-cookbook/heddle.md`, `docs/agent-cookbook.md` (the row), `kuilt-heddle-coroutines/module.md`, `kuilt-heddle-coroutines/api/**`

**Interfaces:**
- Consumes: Task 1's `mutate`, `refuseUnleasable`, `openForTest`, `waitersForTest`; Task 2's `withSlot`; `LocalHeddle.apply(new, generation)`, `previewPicks(waiting: Map<GroupId, Int>, eligible, reservations)`, `snapshot()`.
- Produces: `public fun <R> withEngine(block: (LocalHeddle) -> R): R` and `public fun resize(slots: Int)`.

- [ ] **Step 1: Claim** with a Draft PR naming the Task 3 sub-issue.

- [ ] **Step 2: Write the failing tests in `FairSharePoolEngineTest`.** Ceiling arms use `Topology.Lane("web", Weight.of(3), maxConcurrent = 1L)`. The reload is the same tree without `maxConcurrent`, at `generation = 1L`.
  - `resizeFromZeroGrantsInThePreviewOrder`:
    1. Start at slots = 0. Queue 20 web and 20 mobile waiters. After `runCurrent()`, `waitersForTest == 40` and `openForTest == 0`: nothing is refused at zero.
    2. Compute the expected order on a twin `farm()` engine: `previewPicks(mapOf(web to 20, mobile to 20), setOf(web, mobile), List(6) { 3L })`.
    3. `resize(6)`. The six grants, in `LeaseId` order, equal the preview.
    4. The rig: the preview contains `mobile`.
  - `loweringTheSlotCountPreemptsNothing`: slots = 3, with three granted. `resize(1)` leaves all three open, and a fourth waiter waits. Complete two: the fourth still waits. Complete the third: the fourth is granted.
  - `resizeValidates`: `resize(-1)` throws `IllegalArgumentException`.
  - `withEngineRedispatchesAfterAReloadLiftsACeiling`: slots = 2 on the ceiling tree. One web waiter is granted and the second waits. `withEngine { it.apply(noCeiling, 1L) }` grants the second.
  - `aLaneRemovedByAReloadRefusesItsWaiters`: slots = 1, with web held. Two mobile waiters are queued. `withEngine { it.apply(Topology("farm", listOf(Topology.Lane("web", Weight.of(3)))), 1L) }`, and its `refusals` are empty. Both mobile waiters answer `Refused(WaitExplanation.NoLiveLineage(mobile))`, and the web lease is still open.
  - `anInnerMutationNeverResumes` (Ruling 7): a queued waiter W, and a refusal pending inside one `withEngine` block that also cancels W's job. Nothing resumes until the block returns, and afterwards W's slot is not leaked and the refusal is delivered. The mutation "resume at every depth" reds it.
  - `aWithEngineBlockThatThrowsStillResumesItsGrants`: on the ceiling tree, a second web waiter is queued, and the rig is `waitersForTest == 1`. `assertFailsWith<IllegalStateException> { withEngine { it.apply(noCeiling, 1L); error("boom") } }`. After `runCurrent()`, the waiter is `Granted`.
  - `aLeaseCompletedThroughTheEngineFreesItsSlot`: slots = 1. A is held and B waits. `withEngine { it.complete(A.id, 3L, emptySet()) }` grants B, and then `openForTest == 1`.
  - `resizeAboveTheSupplyLimitChangesNothing`: a `resize` the engine's `reconcileCapacity` refuses throws `IllegalArgumentException`, and the pool keeps its old count.

  Run: `timeout 600 ./gradlew :kuilt-heddle-coroutines:jvmTest --tests "*FairSharePoolEngineTest" --tests "*FairSharePoolTest"`. Expected: FAIL to compile.

- [ ] **Step 3: Implement.**
  - `resize(slots)` validates, then `mutate { engine.reconcileCapacity(slots.toLong()); this.slots = slots }`, reconciling first so a refusal assigns nothing.
  - `withEngine(block)` is `mutate { try { block(engine) } finally { … } }`. The `finally` does two things:
    1. `open.retainAll(engine.snapshot().leases.map { it.id }.toSet())`, so a lease closed around the pool frees its slot.
    2. `refuseUnleasable(queues.keys.toList())`.

    `mutate`'s outer dispatch and resume then run.
  - The arrival check is already in Task 1 (Ruling 12). Do not move or duplicate it.
  - KDoc on `withEngine`: the block runs under the pool's lock, so keep it short. A block that calls `acquire`, `admit`, `complete` or `cancel` on the engine goes around the pool's count. The pool reconciles the leases it holds afterwards, and an `admit` stays outside the count.

  Run: the Step 2 command. Expected: PASS.

- [ ] **Step 4: Mutation check.** Delete the `retainAll`: `aLeaseCompletedThroughTheEngineFreesItsSlot` reds. Resume at every depth: `anInnerMutationNeverResumes` reds. Assign before reconciling in `resize`: `resizeAboveTheSupplyLimitChangesNothing` reds. Revert each.

- [ ] **Step 5: Docs.**
  - Sample: `internal suspend fun sampleReloadWhileWaiting()`, which lifts a ceiling with `withEngine { it.apply(…) }` and shows the waiter granted. Call it from the run test, and reference it from the `withEngine` KDoc.
  - Add a cookbook paragraph on "one writer", covering `withEngine` (persist, explain and reload go through it) and `resize`, with a verbatim quote.
  - Add `withEngine` and `resize` to the index row, and a line to `module.md`.

  Run the Task 1 Step 9 command. Expected: green.

- [ ] **Step 6: ABI, the full gate, then commit** (`heddle-coroutines: withEngine and resize re-dispatch, and an unleasable leaf is refused`), push, and mark ready.

---

### Task 4: The cross-target real-threaded stress probe

Runs after Task 1 merges, and can run beside Tasks 2 and 3. It uses `acquire` / `complete` / `cancel` only, never `withSlot` or `withEngine`, and it adds no public API.

**Files:**
- Test: `kuilt-heddle-coroutines/src/commonTest/kotlin/us/tractat/kuilt/heddle/FairSharePoolConcurrencyTest.kt`
- Modify: `kuilt-heddle-coroutines/build.gradle.kts` (the gate)
- Modify: `.github/workflows/ci.yml` (a `concurrency-probes` step after `:kuilt-heddle`'s at ~line 1047, and its report paths in that job's artifact upload, ~line 1073)

**Interfaces:**
- Consumes: Task 1's `FairSharePool`, `acquire`, `complete`, `cancel`, `openForTest`, `waitersForTest`, and `farm()`.
- Produces: nothing public.

- [ ] **Step 1: Claim** with a Draft PR naming the Task 4 sub-issue.

- [ ] **Step 2: Gate.** Add to `build.gradle.kts`: `val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"`. Then, in `tasks.withType<AbstractTestTask>().configureEach`, when it is off, call `filter.excludeTestsMatching("*ConcurrencyTest")` and `filter.excludeTestsMatching("*ConcurrencyTest.*")`. Use both spellings, for the wasm runner's reason given in `kuilt-heddle-core/build.gradle.kts`. Add a header comment stating why the probe is gated.

- [ ] **Step 3: Write the probe.** Two tests, each `runTest(timeout = TEST_WEDGE_BACKSTOP) { withContext(Dispatchers.Default) { … } }`. Put `import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the pool's count is guarded by a lock, and only real threads (JVM, Native) put a complete, a hand-back and a dispatch in the same window; on wasmJs this only witnesses that the body runs`. Use atomicfu counters. Constants: `SLOTS = 3`, `WORKERS = 32`, `ROUNDS = 200`.
  - Add internal counters to `FairSharePool` (the one production edit this task makes): `removedWhileQueuedForTest` and `handedBackForTest`, each an atomicfu counter bumped where a queued waiter's handler removes it and where a resume's `onCancellation` hands a lease back. If Tasks 2 or 3 are open in `FairSharePool.kt` at the same time, keep this edit to those two counters, so the rebase is trivial. A lock bug this probe finds is a stop-and-report, not a fix to slip in here.
  - `neverMoreThanSlotsOpenOnRealThreads`: each worker loops `ROUNDS` times over lanes web and mobile. Each round does `acquire` → `Granted`, then increments `inFlight`, updates `maxSeen`, calls `yield()`, decrements, increments `iterations`, and completes at 1. Assert:
    - `iterations == WORKERS * ROUNDS`, the witness that the body ran;
    - `maxSeen <= SLOTS`, the property;
    - and separately, as the rig, that `maxSeen` reached `SLOTS` at least once. An equality alone would couple the property to scheduling;
    - after all joins, `openForTest == 0`, `waitersForTest == 0` and `engine.snapshot().leases.isEmpty()`. The engine is read only after quiescence.
  - `cancellationOnRealThreadsLeaksNoSlot`: run rounds of 2,000 jobs. Each job acquires and then holds `delay(1.milliseconds)` inside `try { … } finally { complete(lease.id, 1L) }`. A canceller cancels every third job after a `yield()`. Keep running rounds until **both** `removedWhileQueuedForTest` and `handedBackForTest` reach a floor of 1, under a cap of 50 rounds. Assert:
    - `ran + cancelled == 2_000 × rounds`, the iteration witness;
    - both counters reached their floor, so both races fired. On JVM and macosArm64, a cap reached with a counter at 0 is a red that names the counter;
    - the same three invariants, and a fresh `acquire(web)` is `Granted`.

    On wasmJs the hand-back window may never open, since nothing interleaves inside `mutate`. Record the wasm counter values in the PR rather than asserting the floor there; gate the floor assertion on a `expect val`/platform flag in test code, not on a runtime skip of the test.

- [ ] **Step 4: Run on every target.** Expected: PASS on each, and the `FairSharePoolConcurrencyTest` count in `build/test-results/<task>/` is 2:
  - `timeout 600 ./gradlew :kuilt-heddle-coroutines:jvmTest -Pconcurrency.stress.tests=true --tests "*ConcurrencyTest"`
  - `timeout 600 ./gradlew :kuilt-heddle-coroutines:macosArm64Test -Pconcurrency.stress.tests=true`
  - `timeout 600 ./gradlew :kuilt-heddle-coroutines:wasmJsTest -Pconcurrency.stress.tests=true`

  Then run `timeout 600 ./gradlew :kuilt-heddle-coroutines:jvmTest` without the flag. Expected: the class is absent from the XML, not skipped (`forbidRuntimeSelfSkippingProbe`).

- [ ] **Step 5: Mutation check.** Make `mutate` not take the lock (keep the depth counter), and run the JVM and macosArm64 commands. Expect a red on each: `maxSeen > SLOTS`, a leaked slot, or a `ConcurrentModificationException`. This is the mutation the probe exists for; if it stays green, the probe is vacuous, so report it rather than tuning. Revert. Record the verdict per target and the per-target XML counts in the PR body. (Dropping `open.size < slots` already reds under virtual time in Task 1, so it proves nothing here.)

- [ ] **Step 6: CI step.** Add `Run :kuilt-heddle-coroutines concurrency probes (isolated runner, NON-BLOCKING)` with `if: always()`, running `./gradlew :kuilt-heddle-coroutines:jvmTest -Pconcurrency.stress.tests=true --tests "*ConcurrencyTest"`. Add `kuilt-heddle-coroutines/build/reports/tests/` and `build/test-results/` to the job's upload. State in the step comment that macOS and wasm are local runs, recorded in the PR.

- [ ] **Step 7: The full gate, then commit** (`heddle-coroutines: a real-threaded probe that the pool never exceeds its slots or leaks one`), push, and mark ready.
