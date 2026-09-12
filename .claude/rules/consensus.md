---
paths:
  - "kuilt-raft/**"
  - "kuilt-raft-test/**"
  - "kuilt-game/**"
  - "kuilt-cluster/**"
---
# Consensus

When every device has to agree on one order of events — whose turn it is, which
move came first — one of them is chosen to lead and the others copy its history.
These modules are that machinery, and the two things built on top of it: a game's
turn order, and a cluster of servers that clients propose into.

What a consumer is about to hand-roll, and the primitive that already exists, with
compiled snippets: `docs/agent-cookbook/consensus.md`. This file holds only the
rules for changing the code here.

## Rules

| Task | Command |
|------|---------|
| Real-socket voter-mesh smoke — reconnection + formation timeout (off by default — `ci-required` covers both via the deterministic `VoterMeshReconnectionTest` / `VoterMeshFormationTimeoutTest`) | `./gradlew :kuilt-cluster:jvmTest -Pcluster.realsocket.tests=true` |

Absent `-Pcluster.realsocket.tests=true`, `WebSocketVoterMeshReconnectionTest` and
`WebSocketVoterMeshFormationTimeoutTest` still compile but self-skip at runtime, so `./gradlew build`
doesn't run them. Both assert **downstream of a live loopback WebSocket upgrade**, which a saturated
box can lose — the failure that follows describes the box, not the code (#2226) — so neither gates a
merge; the deterministic virtual-time siblings do.

- **Spec-critical refactors get a spec-conformance review, not just a behavior-preservation
  review.** When you extract or refactor code that implements a formal spec (Raft consensus,
  a wire protocol, a CRDT lattice), a diff/behavior review only proves "same as before" — it is
  structurally blind to a bug that *predates* the refactor. The refactor is precisely the moment
  the mechanism becomes legible, so **also audit the changed unit against the spec itself** (cite
  the section — Ongaro's Raft dissertation, an RFC), treating the pre-existing code as UNPROVEN.
  During the `RaftEngine` decomposition (#1121) this dimension found **~18 real pre-existing bugs**
  the behavior reviews had passed — a snapshot log-wipe, a §5.4.1 election-safety hole, a §5.3
  fast-backup livelock, a joint-consensus wedge, and more (epics #1218 / #1228 / #1244). Findings
  become their own TDD-fix track, separate from the refactor PRs.
- **Multi-node / consensus tests run through the canonical simulation harness — never hand-roll
  one.** A test that stands up more than one `RaftNode` (or any cluster of timer-driven peers)
  under virtual time MUST drive them through the shared harness — `RaftSimulation` +
  `InMemoryRaftNetwork` + `raftRunTest` in `:kuilt-raft`'s commonTest, or the published
  `MultiNodeRaftSim` in `:kuilt-raft-test` for tests outside that module (e.g. `examples/`,
  `:kuilt-cluster`). **Do not write your own cluster network or `while (true) { delay(1) }`
  leader-wait** — that is how a non-converging cluster spins the scheduler CPU-bound and
  **starves its own virtual timeout: the test HANGS, it does not fail** (a hand-rolled done-when
  test once ran ~90 min this way before being killed). The harness encodes the only setup that
  converges and fails fast:
  - **`runTest`'s `timeout` is a GENEROUS wedge backstop, never a tight assertion.** It is
    **wall-clock over a virtual-time trajectory**, so it measures the *host*, not the code — a
    contended box inflates it while the trajectory is unchanged. Tightening it asserts nothing and
    manufactures load-sensitive false reds. What makes a hang *fast* and *legible* is the next
    bullet's bounded `await*`/`settle()` plus the harness's `dumpState()` — not the ceiling.
    **Use `TEST_WEDGE_BACKSTOP`** (`:kuilt-test`, `us.tractat.kuilt.test`) — or a sim harness's own
    equivalent, `RAFT_SIM_WEDGE_BACKSTOP` / `WARP_SIM_WEDGE_BACKSTOP`. Its KDoc carries the value,
    the rationale and the mutation receipt; **this file deliberately names none of them**, because a
    number copied into prose rots the moment the constant moves and nothing fails when it does.
    `forbidTightRunTestTimeout` (root `build.gradle.kts`, wired into `check`) enforces it against a
    grandfathered baseline — sweep a file all-or-none and drop its baseline entry (#1739).
    The property to hold is **"no real-time ceiling is load-bearing for a virtual-time test"** — a
    rule naming `5.seconds` is evaded by `4.seconds`, and a 1-minute ceiling has failed here too.
    Learned on #1382, left uncorrected, and so recurred on #1891 and after.
  - **Bounded `await*` / `settle()` only — NEVER `advanceUntilIdle()`.** Election/heartbeat timers
    re-arm forever, so the idle state is never reached; advance virtual time in bounded steps.
    These are the real fast-failure mechanism: they fail in ~1.4 s either way, independent of host
    load, because they are bounded in *virtual* time.
  - **Per-node seeded election RNG** so timeouts differ and a leader actually wins (symmetry-
    breaking); seed every `RaftConfig.random` — never an unseeded `Random` in a test.
  - Node coroutines live on `TestScope.backgroundScope` child scopes so the infinite election/
    heartbeat loops cancel cleanly at teardown (no `UncompletedCoroutinesError`).
  When running such a test from an agent, **fence the command too**: `timeout 90 ./gradlew
  :<module>:test --tests "<oneTest>"`, one test at a time. The OS-level fence *stays tight* — unlike
  `runTest`'s ceiling it bounds a real shell command, so wall-clock is the right unit there. Keep the
  two straight: **tight fence outside, generous backstop inside.**
  **A hang is a STOP-and-re-plan signal** — `jstack` the test JVM, name the spinning test, fix
  convergence; do NOT widen a bound and retry. But first **read the results XML, not the console
  line**: a K/N timeout renders as `at null:-1` on the console while the XML carries the full stack
  (e.g. `TimeoutCoroutine.run`) *and* `time="…"`. Those two fields distinguish "the trajectory
  wedged" from "the box was slow" — #1891 was diagnosed entirely from them, after the console had
  made it look undiagnosable.
