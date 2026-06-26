# F4 — end-to-end federated-learning demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run F2's training kernel end-to-end on the substrate — through the real `WarpNode` free-path and the replicated `results` board on a multi-node `MultiNodeRaftSim` cluster — proving every node converges to one shared model (incl. leader-failover-mid-round), plus a runnable `:examples` demo that prints the convergence trajectory.

**Architecture:** A training round is N free-path `WarpNode` tasks, one per data-owner. Each task carries `(globalModel, ownerBatch, lr)` as args (F2's `FedAvgKernelCodec.encodeInput`) and `op` = the kernel's `OpId`; the ring owner resolves/lazy-fetches the kernel and runs it; the `OpResult` (encoded `TrainingUpdate`) lands in the `results` board, Quilter-replicated to all nodes. Each node folds the converged board through `FedAvg` (read-side) to read the averaged model. `FedAvg`'s `epoch` axis carries the round. **The FedAvg `ReplicaId` is the data-owner encoded in the `TaskId`, not the executing node — so convergence is independent of ring assignment.**

**Tech Stack:** Kotlin Multiplatform (`:kuilt-warp` jvmTest) + plain-JVM `:examples`; `MultiNodeRaftSim`/`raftSimTest` (`:kuilt-raft-test`); `ChicoryWasmRuntime` (`:kuilt-warp` jvmMain, Chicory); Ktor WebSocket (`:kuilt-websocket`); kotlin.test.

## Global Constraints

- **JVM-only.** The kernel runs on `ChicoryWasmRuntime` (`jvmMain`). The proof is `:kuilt-warp` **jvmTest**; the example is the plain-JVM `:examples` module. No commonTest, no other targets.
- **Reuse the F2 kernel verbatim** — `fedavg_train.wasm`, `FedAvgKernelCodec`, `ReferenceTrainer`, `TrainingUpdate`, `FedAvg`. No new model, no new public API. F4 is integration + example only.
- **Multi-node test discipline** (CLAUDE.md): drive multi-node tests through `raftSimTest(n=…) { sim -> }` (gives `StandardTestDispatcher` + 5 s timeout) + `MultiNodeRaftSim`. Per-node seeded RNG is inside the harness. Use bounded `sim.awaitTrue(...)`/`sim.settle()` — **never `advanceUntilIdle()`**. WarpNodes run on `backgroundScope`. A hang ⇒ stop-and-`jstack`, never widen-and-retry.
- **`explicitApi()` is enforced** in `:kuilt-warp` — any new public decl needs explicit visibility. (F4 adds no public decls; test/example code is not API.)
- Test methods: **no `test` prefix**; multi-assert tests use **`assertAll()`** (`us.tractat.kuilt.test`).
- **Convergence tuning is the F2-proven result:** reuse `y = 2x + 1`, `lr = 0.01`, ~500 epochs for tolerance `0.05`. This is a tuning constant (feature-scaling + small `lr` ≡ full-batch GD), not an algorithmic knob — do not "fix" slow convergence with an algorithm change.
- Before any auto-merge (cache-disabled): `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` then `./gradlew :kuilt-warp:build :examples:test detektAll --rerun-tasks` — confirm tasks `EXECUTED`, not `FROM-CACHE`.
- Fast inner loops: `./gradlew :kuilt-warp:jvmTest --tests "*FedAvgWarpSimTest*"` · `./gradlew :examples:test --tests "*FederatedLearningExampleTest*"`.
- Spec: `docs/superpowers/specs/2026-06-26-warp-f4-end-to-end-demo-design.md`. Issue: the F4 sub-issue of #856 (file at dispatch). Part of epic #856.

## Key existing APIs (verbatim — confirmed on `main`)

```kotlin
// :kuilt-warp commonMain
public class WarpNode(
    selfId: PeerId, seam: Seam, rosterFlow: Flow<Set<PeerId>>, scope: CoroutineScope,
    quilterConfig: QuilterConfig = QuilterConfig(), clock: () -> Instant,
    heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    strategy: ClaimStrategy = ClaimStrategy.RingWithIntent(), registry: OpRegistry,
    coordinatedExecutor: suspend (TaskId) -> String = { error(...) },
    raftNode: RaftNode? = null, lazyFetch: WarpLazyFetch? = null,
)
public fun WarpNode.enqueue(taskId: TaskId, descriptor: TaskDescriptor)   // free path
public val WarpNode.results: Results<TaskId, OpResult>                    // converged board snapshot
public fun WarpNode.close()

public class WarpLazyFetch(creel: Creel, runtime: WasmRuntime, opToBobbin: (OpId) -> BobbinHash?)
@Serializable public class TaskDescriptor(op: OpId, args: ByteArray = ByteArray(0), traceparent: String? = null)
public class OpResult(bytes: ByteArray) { val bytes: ByteArray; val isError: Boolean; val error: String? }
public class OpRegistry { fun register(id: OpId, op: Op); fun resolve(id: OpId): Op? }
public value class TaskId(val value: String)   // string-keyed (see reference test usage TaskId("p1-1"))
public value class OpId(val value: String)

// Results board read surface
public val Results<TaskId, OpResult>.taskIds: Set<TaskId>
public operator fun Results<TaskId, OpResult>.get(taskId: TaskId): OpResult?

// :kuilt-warp — F2 (verbatim, merged in #941)
FedAvgKernelCodec.encodeInput(weights: List<Double>, examples: List<Pair<Double,Double>>, learnRate: Double): ByteArray
FedAvgKernelCodec.decodeOutput(bytes: ByteArray): TrainingUpdate           // throws on bad shape
TrainingUpdate(sampleCount: Long, weights: List<Double>)
TrainingUpdate.toContribution(peer: ReplicaId, epoch: Long = 1L): FedAvg
FedAvg.ZERO; FedAvg.piece(other): FedAvg; val FedAvg.weights: List<Double>

// :kuilt-warp jvmMain
public class ChicoryWasmRuntime(config: WasmSandboxConfig = WasmSandboxConfig()) : WasmRuntime, AutoCloseable

// C4/C5 commonMain
Creel().put(bytes: ByteArray): BobbinHash; Creel().get(hash: BobbinHash): ByteArray?

// :kuilt-raft-test commonMain — the harness (from WarpNodeCoordinatedRaftSimTest)
raftSimTest(n: Int) { sim: MultiNodeRaftSim -> ... }        // StandardTestDispatcher + 5s timeout
sim.nodeIds: List<NodeId>; sim.nodes[nodeId]!!: RaftNode; sim.nodes[id]!!.role.value is RaftRole.Leader
sim.awaitLeader(); sim.awaitLeader(among: Set<NodeId>); sim.awaitTrue(msg, within: Duration) { cond }
sim.awaitRole(id, RaftRole.Follower); sim.partitionOff(id); sim.heal(); sim.settle(); sim.checkInvariants()
```

## File Structure

- `kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgWarpSimTest.kt` — **new.** The proof: two `@Test`s (convergence; leader-failover) + private fold/setup helpers.
- `kuilt-warp/src/jvmMain/resources/us/tractat/kuilt/warp/fedavg_train.wasm` — **moved** from `jvmTest/resources` (Task 3) so `:examples` can load it off the `:kuilt-warp` JVM artifact. (jvmTest references update to the same resource path — it resolves transitively.)
- `examples/build.gradle.kts` — **modified.** Add `testImplementation(project(":kuilt-warp"))` + `-Pwarp.fl.ws` → system property.
- `examples/src/test/kotlin/us/tractat/kuilt/examples/warp/FederatedLearningExampleTest.kt` — **new.** Two tiers (in-process default; `-P`-gated WebSocket).
- `examples/README.md` — **modified.** One row for the new example.

## Design notes the implementer must hold

1. **TaskId encoding carries (epoch, owner):** `TaskId("e$epoch-$owner")` where `owner ∈ {alice,bob,carol}`. The read-side fold parses `owner` back out for the `FedAvg` `ReplicaId` and filters by `epoch`. The executing ring node is irrelevant to correctness.
2. **Per-epoch barrier:** after enqueuing epoch `e`'s N tasks (on `node0`), `sim.awaitTrue` until **every** node's `results` holds all N `TaskId`s for epoch `e`; only then read `node0`'s folded model as the next epoch's `globalModel`. The monotone `FedAvg` epoch join makes a partial read safe (never regresses), but gating on "all N present" keeps the trajectory clean.
3. **Two implementer decisions (resolve with review between tasks):**
   - **Kernel for the nodes' lazy-fetch.** Simplest first cut: give every node a `Creel` **preloaded** with the kernel bytes (`creel.put(kernel)`) + a `WarpLazyFetch` whose `opToBobbin` maps the kernel `OpId` → that hash. This exercises resolve→load→run without depending on in-sim `BobbinExchange` peer-fetch timing (cross-peer fetch is already proven by F2/C5b). If you prefer to exercise `BobbinExchange` (only the publisher's creel has it), do so only if it converges within the bounded sim — otherwise keep preloaded and note it.
   - **`:examples` kernel resource (Task 3).** Moving `fedavg_train.wasm` to `:kuilt-warp` `jvmMain/resources` makes it resolve on the `:examples` test classpath via the project dependency. It is production-shippable kernel content, not test-only, so the move is defensible. Verify the `:kuilt-warp` jvmTest still finds it at the same `/us/tractat/kuilt/warp/fedavg_train.wasm` path (it will — jvmMain resources are on the jvmTest classpath).

---

### Task 1: The proof — multi-node convergence (`FedAvgWarpSimTest`, test 1)

**Files:**
- Create: `kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgWarpSimTest.kt`

**Interfaces:**
- Consumes: `WarpNode`, `WarpLazyFetch`, `OpRegistry`, `OpId`, `TaskId`, `TaskDescriptor`, `OpResult`, `Results`, `FedAvgKernelCodec`, `TrainingUpdate`, `FedAvg`, `Creel`, `ChicoryWasmRuntime`, `raftSimTest`/`MultiNodeRaftSim` (`:kuilt-raft-test`), `InMemoryLoom`/`InMemoryTag`/`Pattern`/`PeerId`.
- Produces: the test file + private helpers `kernelBytes`, `RAFT_SIM_QUILTER_CONFIG`, `peerBatches`, `foldGlobalModel(node, epoch)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.flow.MutableStateFlow
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * F4 — the federated-learning round end-to-end on the substrate: three [WarpNode]s on a
 * [raftSimTest] cluster fetch the F2 training kernel, train on private batches via the
 * free path, and every node converges to one shared model through the replicated results
 * board. The data never moves; only the model update does.
 */
class FedAvgWarpSimTest {

    private val kernel: ByteArray = checkNotNull(
        FedAvgWarpSimTest::class.java.getResourceAsStream("/us/tractat/kuilt/warp/fedavg_train.wasm"),
    ) { "fedavg_train.wasm not found on classpath" }.readBytes()

    private val quilterConfig = QuilterConfig(
        antiEntropyInterval = 100.milliseconds,
        fullStateRetryInterval = 150.milliseconds,
        expectVirtualTime = true,
    )

    private val kernelOp = OpId("fedavg-train")
    private val truth = { x: Double -> 2.0 * x + 1.0 }
    private val owners = listOf("alice", "bob", "carol")
    private val batches: Map<String, List<Pair<Double, Double>>> = mapOf(
        "alice" to listOf(0.0, 1.0, 2.0).map { it to truth(it) },
        "bob" to listOf(3.0, 4.0, 5.0).map { it to truth(it) },
        "carol" to listOf(6.0, 7.0, 8.0).map { it to truth(it) },
    )
    private val lr = 0.01

    private fun taskId(epoch: Int, owner: String) = TaskId("e$epoch-$owner")

    /** Fold this node's converged board for [epoch] into a count-weighted FedAvg model. */
    private fun foldGlobalModel(node: WarpNode, epoch: Int): List<Double> {
        val results = node.results
        var merged = FedAvg.ZERO
        for (owner in owners) {
            val res = results[taskId(epoch, owner)] ?: continue
            val update = FedAvgKernelCodec.decodeOutput(res.bytes)
            merged = merged.piece(update.toContribution(ReplicaId(owner), epoch = (epoch + 1).toLong()))
        }
        return if (merged == FedAvg.ZERO) listOf(0.0, 0.0) else merged.weights
    }

    @Test
    fun `peers train on private data and every node converges`() = raftSimTest(n = 3) { sim ->
        val loom = InMemoryLoom()
        val seams = listOf(
            loom.host(Pattern("warp-fedavg")),
            loom.join(InMemoryTag("wfa-b")),
            loom.join(InMemoryTag("wfa-c")),
        )
        // Each node: empty registry + a Creel preloaded with the kernel, resolved by OpId via lazyFetch.
        val warpNodes = sim.nodeIds.mapIndexed { i, nodeId ->
            val creel = Creel()
            val hash = creel.put(kernel)
            WarpNode(
                selfId = seams[i].selfId,
                seam = seams[i],
                rosterFlow = seams[i].rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = quilterConfig,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                strategy = ClaimStrategy.Ring,
                registry = OpRegistry(),
                lazyFetch = WarpLazyFetch(
                    creel = creel,
                    runtime = ChicoryWasmRuntime(),
                    opToBobbin = { op -> if (op == kernelOp) hash else null },
                ),
                raftNode = sim.nodes[nodeId]!!,
            )
        }
        sim.settle()
        sim.awaitLeader()

        val epochs = 500
        var globalModel = listOf(0.0, 0.0)
        for (epoch in 0 until epochs) {
            owners.forEach { owner ->
                warpNodes[0].enqueue(
                    taskId(epoch, owner),
                    TaskDescriptor(op = kernelOp, args = FedAvgKernelCodec.encodeInput(globalModel, batches.getValue(owner), lr)),
                )
            }
            sim.awaitTrue("epoch $epoch board converged on all nodes", within = 4.seconds) {
                warpNodes.all { node -> owners.all { node.results[taskId(epoch, it)] != null } }
            }
            globalModel = foldGlobalModel(warpNodes[0], epoch)
        }

        // Every node folds the same converged board to the same model, → the true line.
        val perNode = warpNodes.map { foldGlobalModel(it, epochs - 1) }
        assertAll(
            { assertEquals(2.0, globalModel[0], absoluteTolerance = 0.05) },
            { assertEquals(1.0, globalModel[1], absoluteTolerance = 0.05) },
            { assertTrue(perNode.all { it == perNode[0] }, "all nodes agree bit-for-bit: $perNode") },
        )
        warpNodes.forEach { it.close() }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-warp:jvmTest --tests "*FedAvgWarpSimTest*"`
Expected: compiles, FAILS or HANGS-to-timeout (5 s) — most likely the free-path lazy-fetch/board wiring needs tuning. **A hang is a STOP signal:** `jstack` the test JVM, read the spinning frame, fix convergence (don't widen the timeout). Likely tuning points: `ClaimStrategy.Ring` (no settle window), the `sim.awaitTrue` window (4 s), or whether `enqueue` on `node0` alone replicates to all owners (it does — the queue is replicated; the ring owner executes).

- [ ] **Step 3: Make it converge**

If the board never fills: confirm (a) the lazy-fetch `opToBobbin` returns the hash for `kernelOp`; (b) every node's `Creel` is preloaded (`creel.put(kernel)` per node — do NOT share one `Creel` instance across nodes unless you intend BobbinExchange); (c) the ring assigns each task to some live node (it will, with 3 nodes). If epochs are too slow under virtual time, lower `epochs` only as far as still converges within `0.05` (F2 needed ~500; if 500 epochs × board-drain is too slow for the 5 s virtual budget, reduce the per-epoch barrier cost — e.g. enqueue all epochs is NOT an option since each epoch depends on the prior model; instead verify the anti-entropy cadence is fast enough). Keep `assertEquals(..., 0.05)`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*FedAvgWarpSimTest*"`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgWarpSimTest.kt
git commit -m "test(kuilt-warp): F4 proof — peers train via WarpNode free path, all nodes converge"
```

---

### Task 2: The proof — convergence survives leader failover (`FedAvgWarpSimTest`, test 2)

**Files:**
- Modify: `kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgWarpSimTest.kt` (add a second `@Test` + reuse helpers)

**Interfaces:**
- Consumes: everything from Task 1 + `sim.partitionOff`, `sim.heal`, `sim.awaitRole`, `RaftRole`.

- [ ] **Step 1: Add the failing test** (append inside the class; reuses `kernel`, `quilterConfig`, helpers)

```kotlin
    @Test
    fun `convergence survives leader failover mid-round`() = raftSimTest(n = 3) { sim ->
        val loom = InMemoryLoom()
        val seams = listOf(
            loom.host(Pattern("warp-fedavg-failover")),
            loom.join(InMemoryTag("wff-b")),
            loom.join(InMemoryTag("wff-c")),
        )
        val warpNodes = sim.nodeIds.mapIndexed { i, nodeId ->
            val creel = Creel(); val hash = creel.put(kernel)
            WarpNode(
                selfId = seams[i].selfId, seam = seams[i], rosterFlow = seams[i].rosterSnapshot(),
                scope = backgroundScope, quilterConfig = quilterConfig,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                strategy = ClaimStrategy.Ring, registry = OpRegistry(),
                lazyFetch = WarpLazyFetch(creel, ChicoryWasmRuntime(), { op -> if (op == kernelOp) hash else null }),
                raftNode = sim.nodes[nodeId]!!,
            )
        }
        sim.settle()
        sim.awaitLeader()
        val leaderId = sim.nodeIds.first { sim.nodes[it]!!.role.value is RaftRole.Leader }

        // Run a few epochs normally.
        var globalModel = listOf(0.0, 0.0)
        fun runEpoch(epoch: Int) {
            owners.forEach { owner ->
                warpNodes[0].enqueue(
                    taskId(epoch, owner),
                    TaskDescriptor(op = kernelOp, args = FedAvgKernelCodec.encodeInput(globalModel, batches.getValue(owner), lr)),
                )
            }
            sim.awaitTrue("epoch $epoch converged", within = 4.seconds) {
                warpNodes.all { node -> owners.all { node.results[taskId(epoch, it)] != null } }
            }
            globalModel = foldGlobalModel(warpNodes[0], epoch)
        }
        repeat(20) { runEpoch(it) }

        // Fail the leader, re-elect among survivors, heal, let the old leader step down.
        val survivors = sim.nodeIds.filter { it != leaderId }.toSet()
        sim.partitionOff(leaderId)
        sim.awaitLeader(among = survivors)
        sim.heal()
        sim.awaitRole(leaderId, RaftRole.Follower)

        // Keep training; the round completes and survivors still converge.
        for (epoch in 20 until 120) runEpoch(epoch)

        val survivorModels = sim.nodeIds.withIndex().filter { it.value in survivors }
            .map { foldGlobalModel(warpNodes[it.index], 119) }
        assertAll(
            { assertEquals(2.0, globalModel[0], absoluteTolerance = 0.05) },
            { assertEquals(1.0, globalModel[1], absoluteTolerance = 0.05) },
            { assertTrue(survivorModels.all { it == survivorModels[0] }, "survivors agree: $survivorModels") },
        )
        warpNodes.forEach { it.close() }
        sim.checkInvariants()
    }
```

- [ ] **Step 2: Run to verify it fails/then passes**

Run: `./gradlew :kuilt-warp:jvmTest --tests "*FedAvgWarpSimTest*"`
Expected: both tests PASS. If the failover test hangs: `jstack`, confirm `sim.awaitRole(leaderId, RaftRole.Follower)` is reached (the old leader must step down before more `enqueue`s, else a proposal could target a stale leader). The free path doesn't propose to Raft — but the WarpNodes still need a healthy roster; if the partition leaves a node isolated, ensure `heal()` precedes the post-failover epochs. Tune epoch counts (20 pre / 100 post) only to keep within the 5 s virtual budget while still converging.

- [ ] **Step 3: Commit**

```bash
git add kuilt-warp/src/jvmTest/kotlin/us/tractat/kuilt/warp/FedAvgWarpSimTest.kt
git commit -m "test(kuilt-warp): F4 proof — FedAvg convergence survives leader failover mid-round"
```

---

### Task 3: Wire `:examples` to consume `:kuilt-warp` + reach the kernel

**Files:**
- Move: `kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wasm` → `kuilt-warp/src/jvmMain/resources/us/tractat/kuilt/warp/fedavg_train.wasm` (and the `.wat` alongside for provenance, optional)
- Modify: `examples/build.gradle.kts`

**Interfaces:**
- Produces: `:examples` can `testImplementation(project(":kuilt-warp"))` and load `/us/tractat/kuilt/warp/fedavg_train.wasm` off the classpath; `-Pwarp.fl.ws=true` surfaces as `System.getProperty("warp.fl.ws")`.

- [ ] **Step 1: Move the kernel resource to jvmMain**

```bash
git mv kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wasm \
       kuilt-warp/src/jvmMain/resources/us/tractat/kuilt/warp/fedavg_train.wasm
git mv kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wat \
       kuilt-warp/src/jvmMain/resources/us/tractat/kuilt/warp/fedavg_train.wat 2>/dev/null || true
```

- [ ] **Step 2: Verify `:kuilt-warp` jvmTest still finds it** (jvmMain resources are on the jvmTest classpath)

Run: `./gradlew :kuilt-warp:jvmTest --tests "*FedAvgKernelEquivalenceTest*" --tests "*FedAvgFetchAndTrainTest*" --tests "*FedAvgWarpSimTest*"`
Expected: PASS — the existing F2 tests and the new Task 1/2 tests still resolve the resource at the same path.

- [ ] **Step 3: Edit `examples/build.gradle.kts`** — add the dep and forward the flag

```kotlin
dependencies {
    testImplementation(project(":kuilt-warp"))   // ← add (alphabetical-ish near the other kuilt deps)
    // ... existing deps unchanged ...
}

tasks.test {
    useJUnitPlatform()
    systemProperty("warp.fl.ws", (project.findProperty("warp.fl.ws") ?: "false").toString())
}
```

- [ ] **Step 4: Verify the module still builds**

Run: `./gradlew :examples:compileTestKotlin`
Expected: PASS (no example uses `:kuilt-warp` yet — this just proves the dep resolves).

- [ ] **Step 5: Commit**

```bash
git add kuilt-warp/src/jvmMain/resources examples/build.gradle.kts
git rm --cached kuilt-warp/src/jvmTest/resources/us/tractat/kuilt/warp/fedavg_train.wasm 2>/dev/null || true
git commit -m "build(examples): depend on :kuilt-warp; ship kernel in jvmMain resources; forward -Pwarp.fl.ws"
```

---

### Task 4: The runnable example — in-process tier (default, CI-green) + README

**Files:**
- Create: `examples/src/test/kotlin/us/tractat/kuilt/examples/warp/FederatedLearningExampleTest.kt`
- Modify: `examples/README.md`

**Interfaces:**
- Consumes: `:kuilt-warp` (`WarpNode`, `WarpLazyFetch`, `OpRegistry`, `OpId`, `TaskId`, `TaskDescriptor`, `FedAvgKernelCodec`, `FedAvg`, `Creel`, `ChicoryWasmRuntime`), `:kuilt-raft-test` (`raftSimTest`), `:kuilt-core` (`InMemoryLoom`, `Pattern`, `InMemoryTag`).
- Produces: the example file (in-process `@Test`) + a README row.

- [ ] **Step 1: Write the in-process example** (narrative + prints the trajectory; asserts convergence so it can't rot)

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.examples.warp

import kotlinx.coroutines.flow.MutableStateFlow
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.warp.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Federated learning on kuilt — the payoff demo.
 *
 * Three devices each hold private readings of the same hidden trend (`y = 2x + 1`). No device
 * ever shares its readings. Instead the *training step* travels: a tiny WebAssembly kernel,
 * fetched by content address, runs on each device's own data and emits only a model update.
 * Those updates merge through a CRDT (FedAvg) on the replicated results board, and every device
 * ends up with the same learned line.
 *
 * Run it:  `./gradlew :examples:test --tests "*FederatedLearningExampleTest*"`
 * Run it over a real WebSocket network (see the WS tier): add `-Pwarp.fl.ws=true`.
 */
class FederatedLearningExampleTest {

    private val kernel: ByteArray = checkNotNull(
        FederatedLearningExampleTest::class.java.getResourceAsStream("/us/tractat/kuilt/warp/fedavg_train.wasm"),
    ) { "fedavg_train.wasm not found on classpath" }.readBytes()

    private val cfg = QuilterConfig(antiEntropyInterval = 100.milliseconds, fullStateRetryInterval = 150.milliseconds, expectVirtualTime = true)
    private val kernelOp = OpId("fedavg-train")
    private val owners = listOf("alice", "bob", "carol")
    private val batches = mapOf(
        "alice" to listOf(0.0, 1.0, 2.0), "bob" to listOf(3.0, 4.0, 5.0), "carol" to listOf(6.0, 7.0, 8.0),
    ).mapValues { (_, xs) -> xs.map { it to (2.0 * it + 1.0) } }
    private val lr = 0.01

    private fun taskId(e: Int, owner: String) = TaskId("e$e-$owner")
    private fun fold(node: WarpNode, e: Int): List<Double> {
        var m = FedAvg.ZERO
        for (owner in owners) {
            val r = node.results[taskId(e, owner)] ?: continue
            m = m.piece(FedAvgKernelCodec.decodeOutput(r.bytes).toContribution(ReplicaId(owner), epoch = (e + 1).toLong()))
        }
        return if (m == FedAvg.ZERO) listOf(0.0, 0.0) else m.weights
    }

    @Test
    fun `federated learning converges (in-process)`() = raftSimTest(n = 3) { sim ->
        val loom = InMemoryLoom()
        val seams = listOf(loom.host(Pattern("fl-demo")), loom.join(InMemoryTag("fl-b")), loom.join(InMemoryTag("fl-c")))
        val nodes = sim.nodeIds.mapIndexed { i, id ->
            val creel = Creel(); val hash = creel.put(kernel)
            WarpNode(
                selfId = seams[i].selfId, seam = seams[i], rosterFlow = seams[i].rosterSnapshot(),
                scope = backgroundScope, quilterConfig = cfg,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                strategy = ClaimStrategy.Ring, registry = OpRegistry(),
                lazyFetch = WarpLazyFetch(creel, ChicoryWasmRuntime(), { op -> if (op == kernelOp) hash else null }),
                raftNode = sim.nodes[id]!!,
            )
        }
        sim.settle(); sim.awaitLeader()

        println("Federated learning — 3 devices, private data, true line y = 2x + 1")
        var model = listOf(0.0, 0.0)
        for (e in 0 until 500) {
            owners.forEach { owner ->
                nodes[0].enqueue(taskId(e, owner), TaskDescriptor(kernelOp, FedAvgKernelCodec.encodeInput(model, batches.getValue(owner), lr)))
            }
            sim.awaitTrue("epoch $e", within = 4.seconds) { nodes.all { n -> owners.all { n.results[taskId(e, it)] != null } } }
            model = fold(nodes[0], e)
            if (e % 100 == 0 || e == 499) println("round %3d  w=[%.3f, %.3f]".format(e, model[0], model[1]))
        }
        println("converged: w=[%.3f, %.3f]  (true: 2.000, 1.000)".format(model[0], model[1]))

        assertEquals(2.0, model[0], absoluteTolerance = 0.05)
        assertEquals(1.0, model[1], absoluteTolerance = 0.05)
        nodes.forEach { it.close() }
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :examples:test --tests "*FederatedLearningExampleTest*" --info`
Expected: PASS; stdout shows the `round … w=[…]` trajectory ending near `[2.000, 1.000]`. (Reuse the Task 1 convergence tuning; if it hangs, same `jstack` discipline.)

- [ ] **Step 3: Add the README row**

In `examples/README.md`, add a section:

```markdown
## Warp — federated learning

| File | What it teaches |
|------|-----------------|
| `warp/FederatedLearningExampleTest.kt` | End-to-end federated learning on the substrate: three devices fetch a wasm training kernel by content address, train on private data via the `WarpNode` free path, and converge to one shared model through the replicated results board. The default test runs in-process; add `-Pwarp.fl.ws=true` to run the same round over a real WebSocket fabric (see the WS tier in the file). |
```

- [ ] **Step 4: Commit**

```bash
git add examples/src/test/kotlin/us/tractat/kuilt/examples/warp/FederatedLearningExampleTest.kt examples/README.md
git commit -m "docs(examples): runnable federated-learning demo (in-process) + README row"
```

---

### Task 5: The runnable example — WebSocket tier (`-P`-gated, reader-run)

**Files:**
- Modify: `examples/src/test/kotlin/us/tractat/kuilt/examples/warp/FederatedLearningExampleTest.kt` (add a second `@Test`)

**Interfaces:**
- Consumes: `:kuilt-websocket` (`KtorServerLoom`/`KtorClientLoom` — confirm exact host/join entry points from a `ClusterClient*E2ETest` example in `:examples`), plus everything from Task 4.

- [ ] **Step 1: Add the gated WS test** — same federated round over a real loopback WebSocket fabric

```kotlin
    @Test
    fun `federated learning converges over a real WebSocket fabric`() {
        // Reader-run only: skipped in CI. Enable with -Pwarp.fl.ws=true.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getProperty("warp.fl.ws") == "true",
            "WS demo is reader-run; pass -Pwarp.fl.ws=true to run it",
        )
        // Stand up a Ktor WebSocket server Loom + N client Looms (mirror ClusterClient*E2ETest in :examples),
        // build N WarpNodes over those seams (real ChicoryWasmRuntime, kernel fetched by content address),
        // run the same epoch loop as the in-process tier, print the trajectory, assert convergence.
        // NOTE: this tier is NOT bounded by raftSimTest's virtual clock — use real RaftNodes over the WS
        // fabric and real-time awaits with a generous wall-clock timeout. Validate locally during F4.
    }
```

> **Implementer:** this is the one step that needs the real WebSocket wiring fleshed out — model it on the existing `:examples` `ClusterClient*E2ETest` / `ServerClusterE2ETest` (they already stand up `KtorServerLoom`/Netty + client looms and real `RaftNode`s). Keep the FL loop identical to Task 4; only the fabric + scheduling differ. Because it's `assumeTrue`-gated, CI skips it — so a partially-sketched body that compiles is acceptable to land, but **you must validate it locally** (`./gradlew :examples:test --tests "*FederatedLearningExampleTest*" -Pwarp.fl.ws=true`) and confirm it converges before marking the task done. Replace the comment body with the working wiring.

- [ ] **Step 2: Validate locally (NOT in CI)**

Run: `./gradlew :examples:test --tests "*FederatedLearningExampleTest*" -Pwarp.fl.ws=true`
Expected: PASS; trajectory prints, converges near `[2, 1]` over a real WS fabric.
Then confirm CI-path skips it: `./gradlew :examples:test --tests "*FederatedLearningExampleTest*"` → the WS test reports skipped, the in-process test passes.

- [ ] **Step 3: Commit**

```bash
git add examples/src/test/kotlin/us/tractat/kuilt/examples/warp/FederatedLearningExampleTest.kt
git commit -m "docs(examples): -P-gated WebSocket tier — federated learning over a real fabric (reader-run)"
```

---

## Final verification (before auto-merge)

- [ ] `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`
- [ ] `./gradlew :kuilt-warp:build :examples:test detektAll --rerun-tasks` — all tasks `EXECUTED`, green. (Android/Native variants compile any new common code; F4's new code is jvmTest + the JVM `:examples` module. The WS tier is `assumeTrue`-skipped — confirm it shows skipped, not failed.)
- [ ] Locally only: `./gradlew :examples:test --tests "*FederatedLearningExampleTest*" -Pwarp.fl.ws=true` converges.
- [ ] PR body: `Closes #<F4 sub-issue>`, "Part of #856" (non-closing on the epic). Stack on `main`.
- [ ] Tick the F4 box on epic #856 after merge.

## Self-Review (completed by plan author)

- **Spec coverage:** proof/convergence (Task 1) ✓; proof/failover (Task 2) ✓; examples wiring + kernel-resource move (Task 3) ✓; in-process runnable example + README (Task 4) ✓; `-P`-gated WS tier (Task 5) ✓. JVM-only, kernel reused verbatim, no public-API change — all honored.
- **Placeholder scan:** the WS-tier body (Task 5 Step 1) is intentionally a guided sketch, flagged loudly as "the one step to flesh out" against named existing examples — not a silent TODO. Every other step carries complete, runnable code. The two design decisions (preloaded creel vs BobbinExchange; kernel resource location) are called out up front with a recommended resolution, to be settled with review between tasks.
- **Type consistency:** `taskId(epoch, owner)`, `foldGlobalModel`/`fold`, `kernelOp = OpId("fedavg-train")`, `WarpLazyFetch(creel, runtime, opToBobbin)`, `TaskDescriptor(op, args)`, `node.results[taskId]?.bytes` → `FedAvgKernelCodec.decodeOutput` → `toContribution(ReplicaId(owner), epoch)` — identical across all tasks. WarpNode constructor args match the verbatim signature confirmed on `main`.
- **Risk:** the WS tier is the only non-mechanical piece; it's gated out of CI so it can't break the build, and the plan requires a local convergence check before done. The convergence-under-virtual-time budget (500 epochs × per-epoch board drain inside a 5 s virtual timeout) is the main thing to watch in Tasks 1–2 — if tight, the implementer tunes the anti-entropy cadence / epoch count, never the algorithm.
```
