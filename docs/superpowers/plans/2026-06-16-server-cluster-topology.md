# Plan — server-cluster topology (epic #485)

_2026-06-16. Companion to `specs/2026-06-16-server-cluster-topology-design.md`.
Part of #485._

Slices land in order; within a slice, tasks are mostly sequential (each builds the
test surface the next leans on). Dispatch one `coding-partner` (`isolation: "worktree"`)
per task; review between tasks.

## Slice 1 — relay-room (Far). Lands first.

### S1a — round-robin endpoint-reconnect helper (`:kuilt-session`)
- **Add** a `RoundRobinReconnect` helper (name TBD) that takes a static list of
  server endpoints + an injectable selector (O3: default deterministic rotation),
  connects to one, and on transport tear advances to the next, presenting the
  existing `ResumeToken` (keyed on `RoomId` — O4).
- Owns a scope → **dispatcher is a required ctor param** (repo policy: no real-dispatcher
  defaults). Guard mutable selector state with an explicit primitive (atomicfu lock /
  atomic) — must be correct under a multi-threaded dispatcher; **no `limitedParallelism(1)`
  confinement**.
- Tests: `StandardTestDispatcher`, fake fabrics; assert rotation order, failover on
  tear, token re-presentation, and resume-vs-fresh-join on a *different* endpoint (O4).
- TDD: failing test first, then impl (two commits, don't squash).

### S1b — relay-room assembly (`examples/`)
- New example: `KtorRoomHost` server hosting a `RaftNode` (voter) over the room Seam;
  a learner-client joins via `changeMembership` add-learner (D3) and `propose()`s
  through forwarding; observe committed entries replicate back.
- Keep it an integration test under `examples/src/test/` (no new `main` module — D2).

### S1c — slice-1 done-when integration test (`examples/`)
- N clients, M servers (M ∈ {1,3}). Drive a leader change mid-flight
  (`transferLeadership`) and kill a client's entry-server mid-`propose`.
- Client round-robins (S1a) to another server, retries with its `DedupKey`.
- **Assert no double-apply**: the consumer's `ClientSessionTable.shouldApply` skips
  the retried serial; committed state contains the action exactly once.
- Deterministic: `StandardTestDispatcher` + bounded time-advance; seeded RNG for any
  randomized selector.

**Slice-1 exit:** `./gradlew build detektAll` green; S1c proves the done-when over
the relay model. Open the `:kuilt-cluster` extraction (S3) only after this.

## Slice 2 — point-to-point star (bigger).

### S2a — sub-spec: per-server raft-message relay
- Design doc: how a server relays a client's 2-peer-Seam raft messages into the
  cluster Seam (multi-hop fabric). Resolve framing, addressing, back-pressure,
  and how a client's `ResumeToken` maps across the star. Own design sub-issue.

### S2b — star implementation + integration test
- Implement the relay per S2a; mirror S1c's done-when assertions over the star
  topology (client ↔ one server 2-peer Seam, servers meshed).

## Facade — after the shape is proven.

### S3 — extract `:kuilt-cluster`
- New module (`id("kuilt.kmp-library")`, `all` targets): `ServerCluster` (small
  voter set + learner admission) and `ClusterClient` (round-robin connect + propose +
  observe) facades, lifting the proven examples glue. `explicitApi()` — `public` on
  every declaration. Wire `module.md` + `@sample` + Writerside topic per repo doc rules.

## Cross-cutting guardrails (every task)

- Time/randomness are injected dependencies; production wires real, tests inject
  virtual/seeded (`docs/testing-coroutine-determinism.md`).
- `runCatchingCancellable` (not bare `runCatching`); rethrow `CancellationException`.
- No references to other `tractat-us/*` repos in code/docs (references policy).
- Run `./gradlew build` (not just `jvmTest`) before pushing — catches Android-variant
  and cross-module failures.
- Each PR moves one behavior; auto-merge once `ci-required` is green.

## Follow-ups (file, don't block)

- **Learner churn / GC (O1)** — lease-based learner GC; pair with dedup GC v2 (#495).
- **Dynamic endpoint discovery (O2)** — mDNS / gossiped roster feeding S1a.
