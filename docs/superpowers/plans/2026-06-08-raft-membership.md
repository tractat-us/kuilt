# kuilt-raft dynamic membership (§6 joint consensus) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A running Raft cluster can change its membership — add/remove learners, grow/shrink/replace voters, promote a learner to voter — without losing the log, via §6 joint consensus. Membership lives in the replicated log, is adopted on append (recomputed as a pure function of log+snapshot), and voter-set changes transition through a dual-majority joint configuration.

**Spec:** `docs/superpowers/specs/2026-06-08-raft-membership-design.md` (read it first — it carries the safety argument and the adversarial-review corrections this plan assumes).

**Architecture in one paragraph:** Replace the engine's immutable `clusterConfig` read sites with a `MembershipState` (`Simple(config)` | `Joint(old, new)`) recomputed from the log on every append/truncate/install/restart. A new nullable `LogEntry.config: ConfigPayload?` marks internal config entries (withheld from `committed`, like `isNoOp`). `changeMembership(target)` appends a `Simple` entry for learner-set-only changes (no quorum impact) or a `Joint` then (on its commit) a `Simple(new)` for voter-set changes; commit and election require dual majorities during the joint phase, with the leader credited toward a voter set only when it is a member of it. Snapshots carry the effective config as of `lastIncludedIndex`.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines (actor over `Channel`, `Job` timers), kotlinx.serialization (CBOR), kotlin.test + `UnconfinedTestDispatcher`.

**Environment:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`. Inner loop: `./gradlew :kuilt-raft:jvmTest`. **Before pushing each PR, the full build:** `ANDROID_HOME=~/Library/Android/sdk ./gradlew build` (jvmTest hides the Android-variant + `verifyRaftHarnessDiscipline` checks — see the run-full-build-locally lesson). Assert through `trace` (config/term are private); follow `PreVoteTest.kt` / `CheckQuorumTest.kt` / `LearnerTest.kt` for the in-memory sim harness (`InMemoryRaftNetwork`, `raftSim`, `partitionOff`, virtual-time advance) and `docs/testing-coroutine-determinism.md`.

---

## PR stack

Three stacked PRs, each independently green and reviewable. Rebase each onto the prior. **Phased-migration rule: one behavior move per PR** — PR A introduces the config-in-log machinery with the *only* observable new behavior being learner add/remove; PR B adds voter-set joint consensus; PR C is coverage. Only the **last** PR carries `Closes #194`.

| PR | Behavior added | Closes |
|----|----------------|--------|
| **A** | Config-in-log foundation + snapshot-carries-config + learner-set changes (simple path) | — (claims #194 via draft) |
| **B** | Joint-consensus voter-set changes + removed-leader step-down | — |
| **C** | Membership invariants / chaos / property coverage | `Closes #194` |

---

## PR A — Config-in-log foundation + learner-set changes

The machinery, exercised end-to-end by the *safe* change class (learner add/remove touches no majority). A static cluster that never changes membership must behave **identically to today** (no config entries ever written, `membership == Simple(bootstrapConfig)`).

**Files:**
- `ClusterConfig.kt` — add `@Serializable`.
- `LogEntry.kt` — add `config: ConfigPayload? = null`; extend `equals`/`hashCode` to include it. New `ConfigPayload(old: ClusterConfig?, new: ClusterConfig)` (`@Serializable`).
- new `internal/MembershipState.kt` — `Simple`/`Joint` + helpers (`replicationTargets`, `electionTargets`, `isVoter`, `isLearner`, `currentVoters`, and the quorum helpers — `Joint` helpers may be stubbed to the `Simple` behavior in PR A and fully implemented in PR B, OR implement them fully now and leave them unexercised; prefer the latter so PR B is pure wiring).
- `RaftStorage.kt` — `SnapshotMeta` gains `config: ConfigPayload`.
- `RaftMessage.kt` — `InstallSnapshot` gains `config: ConfigPayload`.
- `RaftTraceEvent.kt` — `RaftTraceEvent.ConfigChange(clock, node, index, old, new)`; `StepDownReason.RemovedFromConfig` (used in PR B but cheap to add now).
- `RaftExceptions.kt` — `MembershipChangeInProgressException`.
- `RaftNode.kt` / `internal/RaftEngine.kt` — public `changeMembership(target): ClusterConfig`; `EngineCommand.ChangeMembership`; `membership` field + `recomputeMembership()`; redirect all `clusterConfig.*` read sites (per the spec's table) to `membership`; `appendConfigEntry`; the `pendingConfigChange` deferred + failure wiring in `relinquishToFollower` and the actor `finally`; `advanceCommit` config-commit hook (after the loop). In PR A `onChangeMembership` **rejects voter-set changes** with a clear error (or routes only the learner-set path), deferring joint to PR B.
- `InMemoryRaftStorage.kt` — persist/restore the new `SnapshotMeta.config`.
- new `MembershipTest.kt`.

- [ ] **A1 — Failing test: add a learner to a running cluster.** 3 voters, leader elected; `changeMembership(currentVoters + a new learner)`; assert (via `trace` `ConfigChange` + the learner's `committed`) the learner now receives committed entries and commit still needs only the original voter majority (it is excluded from quorum). FAILS today (`changeMembership` doesn't exist / no replication to the new node).
- [ ] **A2 — Implement the foundation.** `MembershipState`, `LogEntry.config`, redirect read sites, `recomputeMembership` (called on append, truncate, install, **and engine init/log-load**), `appendConfigEntry`, the deferred + failure paths, the after-loop commit hook. Wire `changeMembership` for the learner-set-only path; reject voter-set changes for now. Make A1 pass.
- [ ] **A3 — Snapshot carries config.** `SnapshotMeta.config` + `InstallSnapshot.config`; `onCompact` records the config **as of `throughIndex`** (not live `membership` — see spec); install adopts it. Test: change the learner set, compact past the change, install on a fresh node, assert it adopts the post-change membership.
- [ ] **A4 — Backward-compat + remove learner.** Test: a cluster that never calls `changeMembership` writes zero config entries and behaves as today (spot-check an existing scenario still green). Test: remove a learner → leader stops replicating to it. One-change-at-a-time guard test (second `changeMembership` while first uncommitted → `MembershipChangeInProgressException`). Leadership-loss test: start a learner change, kill the leader before commit, assert the caller's deferred fails with `LeadershipLostException` (proves the `pendingConfigChange` failure path).
- [ ] **A5 — TDD discipline check.** Revert the `appendConfigEntry`/recompute wiring, confirm A1 fails, restore.
- [ ] **A6 — Full build.** `ANDROID_HOME=~/Library/Android/sdk ./gradlew build`. Green before pushing. Open as **draft PR** to claim #194 (body: "Phase A of #194 — see plan; voter-set joint consensus in the follow-up").

## PR B — Joint-consensus voter-set changes

Lift PR A's voter-set restriction. This is the dual-majority core.

**Files:** mostly `internal/RaftEngine.kt` + `internal/MembershipState.kt` (+ `RaftLogMath.kt` for the revised `majorityIndexOf`), and `MembershipTest.kt`.

- [ ] **B1 — Failing test: grow 3→5.** 3 voters + 2 learners (added & caught up via PR A's path), then `changeMembership` to 5 voters. Assert `trace` shows `Joint` then `Simple(C_new)`; after C_new commits, any 3-of-5 partition still elects/commits and any 2-of-5 cannot. FAILS (voter-set change rejected by PR A).
- [ ] **B2 — Implement joint consensus.**
  - `RaftLogMath`: replace `majorityCommitIndex` with `majorityIndexOf(voters, matchIndex, leaderLastIndex, leaderIsVoter)` — conditional self-credit (the removed-leader correctness fix; spec SHOULD-FIX 4).
  - `MembershipState.Joint`: `committedIndex` = min over old/new; `voterQuorumReached` / `quorumOfContacts` = dual majority, per-set self-credit.
  - `onChangeMembership`: voter-set change appends `Joint(old=current, new=target)`.
  - `onConfigCommitted` (the after-loop hook): on `Joint` commit append `Simple(new)`; on `Simple(new)` commit complete the deferred and `stepDownToFollower(RemovedFromConfig)` if `self ∉ new.voters`.
  - Make B1 pass.
- [ ] **B3 — Round out voter-change tests.** Shrink 5→3 (old 3-of-5 majority no longer commits once C_new in force). Replace a voter. Promote a learner→voter (assert it leaves `Learner` role and becomes election-eligible — the `recomputeMembership` role re-eval). Remove-the-leader (assert `RemovedFromConfig` step-down after C_new commits + survivors elect). Adopt-on-append rollback (follower appends `Joint` from a doomed leader, new leader overwrites suffix, assert membership rolls back). Use `assertAll()`; no `test` prefix.
- [ ] **B4 — Leader-crash-mid-joint.** Kill leader after `Joint` before `Simple(new)`; assert a safe outcome (a new leader either completes or cleanly aborts the transition — no split-brain) and the caller's deferred fails when its leader dies.
- [ ] **B5 — TDD discipline + full build.** Revert the joint `committedIndex`, confirm B1 fails, restore. `ANDROID_HOME=~/Library/Android/sdk ./gradlew build` green. Push; stack on PR A.

## PR C — Invariants / chaos / property coverage

- [ ] **C1 — Extend `ChaosTest`/PBT** with random membership churn interleaved with partition/heal. Assert across the run: Election Safety, Leader Completeness, and "no two leaders at the same term." If the existing harness makes a full integration awkward, keep it to one focused scenario rather than a broad rewrite, and `log()` what is / isn't covered in the PR body.
- [ ] **C2 — Snapshot-mid-joint** representability test (a `SnapshotMeta.config` carrying a `Joint` payload round-trips and an installer resumes the joint phase).
- [ ] **C3 — Full build**, `Closes #194`, stack on PR B.

---

## Per-PR wrap-up

- TDD: failing-test-first commit, then the implementation commit (don't squash the two).
- Each PR includes the spec + plan docs already on the branch (they landed with PR A).
- Conventional-commit titles, e.g. `feat(kuilt-raft): config-in-log + learner membership changes (#194)` / `feat(kuilt-raft): joint-consensus voter membership changes (#194)`. Never "chore".
- Prefix PR bodies with the AI-attribution line; one screen; note any existing tests updated. Stack-nav footer (`Prev/Next`).
- `gh pr merge <n> --auto --squash`; `gh pr view --web`.
- After the change ships, membership-mutating clusters are a **breaking-API surface bump candidate** — consider `kuiltVersionLine` when PR C merges (see `[[kuilt-114-fireworks-upgrade-notice]]` for the cross-repo notice pattern).

## Out of scope (own issues if pursued)

- Single-server (§4.1) changes — deliberately not implemented.
- Leadership transfer (§3.10) — companion to "remove the leader," separate feature.
- Automatic catch-up gating inside `changeMembership` — caller's job (expose matchIndex/committed).
- Configurable joint-phase timeouts — reuses existing election/heartbeat config.
