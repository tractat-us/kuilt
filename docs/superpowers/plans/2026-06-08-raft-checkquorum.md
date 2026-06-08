# kuilt-raft CheckQuorum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A leader steps down to follower — at the same term, no term bump — when it has not heard from a voter-quorum within an election-timeout window. Tidies the stale-minority-leader residual that PreVote (#193) left.

**Architecture:** The leader records which voters reach it (any `AppendEntriesResponse`/`InstallSnapshotResponse` at `currentTerm`) into a windowed set. A periodic `quorumCheckJob` (`delay(randomElectionTimeout)` Job, mirroring `heartbeatJob`) ticks `EngineCommand.QuorumCheck`; each tick counts voter contacts (+1 self), resets the window, and if `< quorumSize` relinquishes leadership via a new same-term `stepDownToFollower`. No wire types, no config, no persistence.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines (actor over `Channel`, `Job`-based timers), kotlin.test + `UnconfinedTestDispatcher`.

**Spec:** `docs/superpowers/specs/2026-06-08-raft-checkquorum-design.md`

**Sequencing:** Land **after #228** (pre-vote nonce — both edit `RaftEngine.kt`). Rebase onto the post-#228 `origin/main` before starting so the engine merges cleanly.

---

## File structure

| File | Change | Task |
|------|--------|------|
| `internal/EngineCommand.kt` | + `QuorumCheck` data object | 1 |
| `RaftTraceEvent.kt` | + `StepDownReason.LostQuorum` | 1 |
| `internal/RaftEngine.kt` | extract `relinquishToFollower`; `stepDownToFollower`; `recentVoterContacts` + `quorumCheckJob`; record contact in the two response handlers; `becomeLeader` starts the job; `onQuorumCheck` (T1) | 1 |
| `kuilt-raft/src/commonTest/.../raft/CheckQuorumTest.kt` (new) | canonical step-down, no-false-positive, single-voter, success=false-counts, heal-after | 1 |
| existing PBT/chaos suite | term-stability + Election-Safety invariant with CheckQuorum on | 2 |

`currentTerm` is private — **all term assertions go through `trace`** (`BecomeFollower(reason=LostQuorum)`, term carried on trace events). Collect a node's `trace` on `backgroundScope` before the scenario, then assert on the captured list. Follow the patterns in the existing `PreVoteTest.kt` / `ElectionTest.kt` for the in-memory sim harness (`raftSim` / `InMemoryRaftNetwork`, `partitionOff`, virtual-time advance).

**Environment:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`. Inner loop: `./gradlew :kuilt-raft:jvmTest`. **Before pushing, the full build:** `ANDROID_HOME=~/Library/Android/sdk ./gradlew build` (jvmTest hides the Android-variant + `verifyRaftHarnessDiscipline` checks — see the run-full-build-locally lesson).

---

## Task 1: CheckQuorum step-down (engine + unit tests)

The whole behaviour in one cohesive change: contact tracking, the periodic check, and the same-term step-down. Pure additive on the contract surface — no public API beyond the `StepDownReason.LostQuorum` enum constant.

**Files:** Modify `internal/EngineCommand.kt`, `RaftTraceEvent.kt`, `internal/RaftEngine.kt`. New `CheckQuorumTest.kt`.

- [ ] **Step 1: Write the failing test (canonical step-down).**
  In a new `CheckQuorumTest.kt`: build a 3-voter sim, elect a leader, capture its `trace` on `backgroundScope`, `partitionOff` the leader, `advanceTimeBy` past one election-timeout window, and assert the leader emitted `BecomeFollower(reason = LostQuorum)` and its `role` is now `Follower`. Confirm it FAILS against current code (no step-down happens — the partitioned leader stays Leader forever).

- [ ] **Step 2: Implement.**
  1. `EngineCommand.kt`: add `data object QuorumCheck : EngineCommand`; wire it in `startActor`'s `when` to `onQuorumCheck()`.
  2. `RaftTraceEvent.kt`: add `LostQuorum` to `StepDownReason`.
  3. `RaftEngine.kt`:
     - Add fields `private val recentVoterContacts = mutableSetOf<NodeId>()` and `private var quorumCheckJob: Job? = null` near the leader-state block.
     - **Refactor step-down:** extract the body of `stepDown` *after* `persistTermAndVote(...)` into `private suspend fun relinquishToFollower(reason: StepDownReason)`, and add `quorumCheckJob?.cancel()` to it (beside `heartbeatJob?.cancel()`). `stepDown(newTerm, reason)` becomes `persistTermAndVote(newTerm, null); relinquishToFollower(reason)`. Add `private suspend fun stepDownToFollower(reason) = relinquishToFollower(reason)`.
     - `becomeLeader`: after the existing leader setup, `recentVoterContacts.clear(); quorumCheckJob?.cancel()` then launch the check loop — `while (true) { delay(randomElectionTimeout); cmd.trySend(EngineCommand.QuorumCheck) }`. Factor the `Random.nextLong(min, max)` draw used in `resetElectionTimeout` into a `private fun randomElectionTimeoutMillis(): Long` and reuse it in both places (no behavioural change to `resetElectionTimeout`).
     - In `onAppendEntriesResponse` and `onInstallSnapshotResponse`, after the `if (role !is Leader || m.term != currentTerm) return` guard, add `recentVoterContacts += from`.
     - Add `onQuorumCheck()` per the spec: leader-only; `reachable = recentVoterContacts.count { it in clusterConfig.voters } + 1`; `recentVoterContacts.clear()`; if `reachable < clusterConfig.quorumSize` → `emitTrace(BecomeFollower(..., LostQuorum))` then `stepDownToFollower(LostQuorum)`. (Mind ordering: emit the trace with the *current* term before the role flips, matching how `stepDown` emits `BecomeFollower` after the role reset — pick the order that keeps the existing `stepDown` trace semantics consistent; `relinquishToFollower` already emits a `BecomeFollower`, so `onQuorumCheck` should NOT emit its own — just call `stepDownToFollower(LostQuorum)` and let the shared body emit the trace with the correct reason.)
  Verify the Step-1 test now PASSES.

- [ ] **Step 3: Round out the unit tests** (all in `CheckQuorumTest.kt`):
  - **No false step-down:** a connected 3-voter leader stays `Leader` across several check windows (`advanceTimeBy` well past multiple periods).
  - **Single-voter:** a 1-voter leader never steps down.
  - **`success=false` counts as contact:** a peer that only ever rejects AppendEntries (forced log conflict) but stays connected keeps the leader in office.
  - **Heal after step-down:** partition → step-down → heal → the node rejoins as follower under the new leader with no term inflation (assert via trace; ties to the PreVote property).
  - Use `assertAll()` for multi-assert tests; no `test` prefix on methods.

- [ ] **Step 4: TDD discipline check.** Revert the `onQuorumCheck`/`becomeLeader` engine change, confirm the canonical test fails, restore.

- [ ] **Step 5: Full build.** `ANDROID_HOME=~/Library/Android/sdk ./gradlew build`. Fix any existing tests that assumed a partitioned leader *stays* leader — that assumption is exactly what CheckQuorum changes; update them to expect the step-down (and note it in the PR body). Green before pushing.

---

## Task 2: Invariant / property coverage

- [ ] Extend the existing property/chaos suite (e.g. `ChaosTest.kt` / the simulation harness) with a partition+heal sequence asserting: (a) Election Safety still holds; (b) no node holds Leader at a term while a quorum it can't reach has moved on; (c) term stability — a healthy majority's leader is never deposed by CheckQuorum. If the existing harness makes this awkward, keep it to a single focused scenario rather than a broad rewrite, and `log()` what is / isn't covered in the PR.

---

## PR

- One PR, `Closes #196`. Include the spec + plan docs (this file and the design doc) in the PR — they land with the feature, as the #193 docs did.
- TDD: failing-test-first commit, then the implementation commit (do not squash the two).
- Prefix the PR body with the AI-attribution line; keep it to one screen; note any existing tests updated for the new step-down behaviour.
- Conventional-commit title `feat(kuilt-raft): CheckQuorum — leader steps down without a voter-quorum (#196)`. Never use "chore".
- `gh pr merge <n> --auto --squash`, then `gh pr view --web`.
