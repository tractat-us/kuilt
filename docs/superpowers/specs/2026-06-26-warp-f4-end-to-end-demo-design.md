# F4 — end-to-end federated-learning demo (the payoff)

**Issue:** F4 sub-issue of [#856](https://github.com/tractat-us/kuilt/issues/856) (Epic F — federated ML) · **Epic:** [#856](https://github.com/tractat-us/kuilt/issues/856) · **Design:** `docs/warp-ml.md`, `docs/warp-roadmap.md` (§ Epic F)
**Date:** 2026-06-26 · **Status:** draft design, pre-implementation.
**Builds on:** F1 (`FedAvg` CRDT, #903) + F2 (training step as a fetched wasm kernel, #939/#941), both merged. C5b (#929, production `WasmRuntime`/`ChicoryWasmRuntime`) merged.

## The one idea

Everyone's device helps train a shared model, and *no one's data leaves their device*. F1 proved the
averaging converges as a CRDT. F2 shipped the training step as a portable wasm kernel that travels
across the fabric. **F4 runs the whole thing end-to-end on the substrate**: real peers, each holding
private data, coordinate through `WarpNode`/Raft, fetch the kernel, train locally, and watch one shared
model emerge — even when a peer fails mid-round.

## Problem

F2's `FedAvgFetchAndTrainTest` proved fetch→train→merge, but **hand-orchestrated on one thread**: it
publishes the kernel to a `Creel`, loops the peers itself, runs the kernel via `ChicoryWasmRuntime`, and
merges `FedAvg` in-test. It deliberately stopped at the Creel+runtime altitude — *"the full WarpNode/Raft
multi-node board convergence is F4"* (F2 plan, Task 6 note).

F4 closes that gap: the training round runs through the **real `WarpNode`** task queue, contributions land
in the **replicated `results` board** (`ORMap<TaskId, LWWRegister<OpResult>>`, Quilter-driven), and **every
node converges to the same model** — proven on a multi-node `MultiNodeRaftSim` cluster, including a
leader-failover-mid-round scenario. This is the payoff demo for the whole substrate: code mobility (C)
feeding a CRDT merge (F) to do something genuinely useful, surviving real distributed-systems faults.

## How federated learning maps onto `WarpNode`

The load-bearing modeling decision:

**A training round is the free/Ring path, not the coordinated path.** The coordinated (Raft) path executes
a task *exactly once on the leader* — the opposite of "every peer trains on its own private data." So:

- **Within a round:** N free-path training tasks, one per peer. Peer *i* owns task *i* (Ring ownership),
  resolves the kernel `OpId` from its local `OpRegistry`; if absent it lazy-fetches the bobbin by
  `BobbinHash` (C5b wiring) and loads it via `WasmRuntime`. It runs the kernel on its **private batch**, and
  its `OpResult` — an encoded `TrainingUpdate` (sampleCount + updated weights) — is recorded into the
  `results` board.
- **Replication & merge:** Quilter replicates the board to every node. Each node folds the board's per-peer
  updates through **`FedAvg`** (`contribution(peer, n, weights, epoch)`); the count-weighted mean at read is
  the round's global model. Convergence ⇔ every node sees the same N updates ⇒ the same averaged model.
- **Across rounds:** `FedAvg`'s `epoch` axis carries the round. Round *r*+1 trains from round *r*'s averaged
  global model; the monotone `(epoch, sampleCount, weightedSum)` join is the between-round barrier — no extra
  consensus is needed for the *averaging* itself.
- **Where Raft earns its keep:** the **churn/failover** scenario. Kill the leader mid-round; the substrate
  re-elects, in-flight contributions survive (exactly-once into the board), and every surviving node still
  converges to the same model. That is the headline F4 asserts — the substrate's fault-tolerance made
  visible on a real ML task.

FedAvg aggregation is therefore a **read-side fold** over the converged `results` board, not a separately
replicated CRDT instance: each node reads its board, folds the latest per-peer `TrainingUpdate`s into a
`FedAvg`, and reads the averaged weights.

## Scope

**In:**
1. **The proof** (`:kuilt-warp` jvmTest) — a multi-node `MultiNodeRaftSim` cluster running the real
   `WarpNode` free-path training round, asserting board-driven `FedAvg` convergence across all nodes, plus a
   leader-failover-mid-round variant.
2. **The runnable example** (`:examples/warp/`) — a self-contained `@Test` (kuilt's "runnable example"
   idiom: compiled, runnable via `:examples:test`) that runs a federated round and **prints the round-by-round
   convergence trajectory**, telling the "data never leaves the device" story. Default tier in-process (CI-green,
   doubles as documentation); a WebSocket tier `-P`-gated for the reader to run live.
3. **Wiring + docs** — add `:kuilt-warp` to `examples/build.gradle.kts`; one `examples/README.md` row.

**Out (YAGNI / other slices):**
- New training tasks / models — F4 reuses the **F2 kernel verbatim** (`y = 2x + 1`); the point is that the
  *same* kernel travels and runs on the substrate. A different model is a follow-up, not F4.
- Browser/native multi-node execution — the wasm runtime is JVM-only (`ChicoryWasmRuntime` in `jvmMain`), so
  F4 is JVM-only. Other targets ride C5b's deferred `WasmRuntime` impls.
- Secure aggregation — that's F3 (optional).
- A standalone GUI demo app — kuilt is a library; the "runnable example" idiom is the demo surface. A real
  visual demo belongs in a downstream consumer, out of repo scope.
- Heavy narrative docs / SVG descent diagram — deprioritized in favour of the runnable example.

## Design

### 1. The proof — `:kuilt-warp` jvmTest `FedAvgWarpSimTest`

JVM-only (the kernel needs `ChicoryWasmRuntime`). Uses the canonical harness: `MultiNodeRaftSim` from
`:kuilt-raft-test` (a `jvmTest` may consume it — it's published in that module's `commonMain`),
`StandardTestDispatcher`, per-node seeded RNG, tight `runTest` timeout, bounded `awaitTrue` — **never**
`advanceUntilIdle`. Mirrors the existing `WarpNodeCoordinatedRaftSimTest` setup ceremony (3 nodes, one
`InMemoryLoom`, per-node `WarpNode` sharing the seam, `clock` from `testScheduler.currentTime`).

Setup: 3 `WarpNode`s, `strategy = ClaimStrategy.Ring`, each with an `OpRegistry`. The F2 kernel
(`fedavg_train.wasm`) is published once to a `Creel`; nodes lazy-fetch it via `BobbinExchange` so the
"code travels" path is exercised (not pre-registered on every node). Each node loads it through a shared/
per-node `ChicoryWasmRuntime`.

Helper (test-local): `roundGlobalModel(node): List<Double>` — reads `node.results`, decodes each peer's
latest `OpResult` via `FedAvgKernelCodec.decodeOutput`, folds them into a `FedAvg` for the current epoch,
returns `.weights`.

**Test 1 — `peers train on private data and all nodes converge`:**
- Three peers, each a different private batch drawn from `y = 2x + 1` (reuse F2's batches).
- For each epoch `e` in `0 until R`: each peer *i* enqueues `taskId(e, i)` with
  `TaskDescriptor(op = kernelOpId, args = encodeInput(currentGlobalModel, peerBatch_i, lr))`; the Ring owner
  (peer *i*) executes it locally; the `OpResult` lands in the board.
- `awaitTrue("epoch $e converged")` until every node's `results` holds all N updates for epoch `e`;
  advance `currentGlobalModel = roundGlobalModel(node0)`.
- Assert after R epochs: every node's `roundGlobalModel` → `[2.0, 1.0]` within ε, and all three nodes'
  models are **bit-identical** (CRDT convergence).

**Test 2 — `convergence survives leader failover mid-round`:**
- Run a few epochs; mid-round (after some but not all peers have contributed), stop the current leader
  (`sim` leader-failover helper, as in `coordinatedTaskExecutesExactlyOnceAcrossLeaderFailover`).
- Assert: re-election happens (`sim.awaitLeader(among = survivors)`); the round completes; **no peer's
  contribution is lost or double-counted** in the board (exactly-once); the surviving nodes still converge to
  `[2.0, 1.0]` and agree bit-for-bit.

### 2. The runnable example — `:examples/warp/FederatedLearningExampleTest`

kuilt's "runnable example" = a self-contained JVM `@Test` under `examples/src/test/kotlin/` (per
`examples/README.md`). Two tiers in one file:

**Default tier — `federated learning converges (in-process)`** (runs in CI):
- In-process: 3 `WarpNode`s over an `InMemoryLoom` with real `MultiNodeRaftSim` Raft and real
  `ChicoryWasmRuntime`, the F2 kernel fetched by content address.
- Narrative comments frame the story: each device has private readings; only weight updates leave; one model
  emerges.
- **Prints the trajectory** to stdout: `round 0  w=[0.00, 0.00]` … `round 50  w=[2.01, 0.98]  (true: 2, 1)`.
- Light assertion (converges to the true line) so it stays green and guards against rot.

**WebSocket tier — `federated learning converges over a real fabric`** (`-Pwarp.fl.ws=true`-gated):
- Same federated round, but the peers connect over a real loopback **Ktor WebSocket** fabric
  (`KtorServerLoom`/`KtorClientLoom`, mirroring the `ClusterClient*E2ETest` examples).
- Skipped in CI via `assumeTrue(System.getProperty("warp.fl.ws") == "true")` — the established repo pattern
  for environment-dependent suites (cf. mDNS multicast `-Pmdns.multicast.tests`). The `-P` flag is forwarded
  to the test JVM as a system property in `examples/build.gradle.kts`.
- For the reader to run themselves and watch federated learning converge over an actual network; validated
  locally during F4 implementation, never a CI gate (no network flakiness in the required build).

### 3. Module wiring

- `examples/build.gradle.kts`: add `testImplementation(project(":kuilt-warp"))` (Ktor websocket deps already
  present). Forward `-Pwarp.fl.ws` to the test JVM as a system property
  (`tasks.test { systemProperty("warp.fl.ws", project.findProperty("warp.fl.ws") ?: "false") }`).
- `examples/README.md`: one row under a "Warp / federated learning" heading pointing at the new example and
  the `-P` flag to run the live WebSocket variant.

## Testing & determinism

- **The proof** obeys the multi-node discipline (CLAUDE.md): `runTest(StandardTestDispatcher(), timeout =
  5.seconds)` (tight, never the 60 s default), `MultiNodeRaftSim`, per-node seeded election RNG, bounded
  `awaitTrue`/`settle` — never `advanceUntilIdle` (election/heartbeat timers re-arm forever). Node coroutines
  on `backgroundScope`. A hang ⇒ stop-and-`jstack`, not widen-and-retry.
- **Convergence tuning** is the F2-proven result: with equal batches + one local GD step, count-weighted
  FedAvg-averaging ≡ full-batch GD; slowness is feature-scaling + small `lr`. Reuse `y = 2x + 1`, conservative
  `lr`, and enough epochs (F2 used 500 at `lr=0.01`) for tolerance 0.05. Tuning constant, not an algorithmic
  knob.
- **The in-process example** asserts convergence so it can't rot; the WS tier is `assumeTrue`-skipped in CI.
- Pre-merge (cache-disabled): `./gradlew :kuilt-warp:build :examples:test detektAll --rerun-tasks` — confirm
  tasks `EXECUTED`, not `FROM-CACHE` (Android/Native variants compile any new `commonMain`/`commonTest`; the
  new code is jvmTest + the JVM `:examples` module).

## Files

- **New (`:kuilt-warp` jvmTest):** `kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgWarpSimTest.kt`.
- **New (`:examples`):**
  `examples/src/test/kotlin/us/tractat/kuilt/examples/warp/FederatedLearningExampleTest.kt`.
- **Modified:** `examples/build.gradle.kts` (+`:kuilt-warp` dep, `-P` forwarding); `examples/README.md` (+1 row).
- No `commonMain`/public-API changes — F4 is integration + example only; F1/F2 already shipped the API.

## Dependency & risk

- **No new gates** — F1, F2, C5b all merged; every primitive F4 composes (`WarpNode`, `results` board,
  `Creel`/`BobbinExchange`, `ChicoryWasmRuntime`, `FedAvgKernelCodec`, `MultiNodeRaftSim`) is on `main`.
- **Risk — convergence under real coordination timing.** Read-side fold over an *asynchronously* replicated
  board means a node may read mid-replication. Mitigation: gate each epoch's advance on `awaitTrue(all N
  updates present)` before computing the next global model; the monotone `FedAvg` epoch join makes a
  stale/partial read safe (it never regresses). If the failover test proves flaky, that's a real ordering
  defect to fix (per "it's never a flake"), not a timeout to widen.
- **Risk — `:examples` is a plain-JVM module** (not KMP); `:kuilt-warp` resolves there as its JVM artifact, so
  `ChicoryWasmRuntime` and the kernel resource are available. Confirm the kernel `.wasm` resource is reachable
  on the `:examples` test classpath (it lives in `:kuilt-warp` jvmTest resources — may need to publish it to
  `jvmMain` resources or duplicate a copy into `:examples` test resources). **Resolve at implementation:** if
  the kernel resource isn't transitively visible, the cleanest fix is to move `fedavg_train.wasm` to
  `kuilt-warp/src/jvmMain/resources` (it is production-shippable kernel content, not test-only) — a small,
  defensible relocation.

## Deferred (follow-up)

- **F3** secure aggregation (optional) — rides the same monotone accumulation.
- **F-polish** — cleanup / doc / testing pass after the demo.
- **Browser/native demo** — once C5b's `WasmRuntime` impls for wasmJs/native land.
- **Larger models / real datasets** — if a richer demo is wanted later.
