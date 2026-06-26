# F2 — model-as-wasm-kernel (the local training step, shipped as a kernel)

**Issue:** F2 sub-issue of [#856](https://github.com/tractat-us/kuilt/issues/856) (Epic F — federated ML) · **Epic:** [#856](https://github.com/tractat-us/kuilt/issues/856) · **Design:** `docs/warp-ml.md`, `docs/warp-roadmap.md` (§ Epic F)
**Date:** 2026-06-26 · **Status:** draft design, pre-implementation — **authored unattended; pending Iain's review.**
**Hard dependency:** [C5b #929](https://github.com/tractat-us/kuilt/issues/929) — production `WasmRuntime` + the `warp_alloc`/`warp_run` ABI. F2's end-to-end sim cannot run until C5b merges.

## The one idea

Everyone's phone helps train a shared model — *without anyone's data ever leaving their phone.*
Each peer runs the same tiny training program on its own examples, and sends back only a small
summary (an updated set of model weights). Those summaries are averaged together, weighted by how
much data each peer had, and everyone ends up with the same improved model. F1 already proved the
*averaging* converges as a CRDT. **F2 ships the training program itself — as a portable wasm kernel
that travels across the fabric and runs on every peer.**

## Problem

F1 ([#903](https://github.com/tractat-us/kuilt/issues/903), merged) gave us `FedAvg`: a
coordination-free CRDT where each peer contributes `(sampleCount, localWeights)` and the merged read
is the count-weighted mean. But F1 *assumes the weights already exist* — it never says where the
local training happens. In F1's tests the "training" is a hand-supplied vector.

F2 closes that: the local training step becomes **a real wasm kernel**, content-addressed in the
`Creel`, fetched on demand via `BobbinExchange` (C5a #927 ✅), and executed through the production
`WasmRuntime` (C5b #929). A peer that has never seen the kernel fetches it, runs it on its own
private batch, and contributes the result to `FedAvg`. The code travels; the data never does.

This is the payoff wiring for the whole substrate — code mobility (Epic C) feeding a CRDT merge
(Epic F) to do something genuinely useful (federated learning).

## Scope

**In:**
1. A **training kernel** — a committed `.wat`/`.wasm` that conforms to the C5b warp ABI and computes
   one gradient-descent step of linear regression over a local batch.
2. A **codec** (`commonMain`) that marshals `(weights, batch)` → kernel input bytes and kernel output
   bytes → `(sampleCount, updatedWeights)`, bridging to `FedAvg.contribution(...)`.
3. A **pure-Kotlin reference trainer** — the same GD step in Kotlin — as the kernel's correctness
   oracle and as the way to test the codec + FedAvg wiring *without* a runtime.
4. An **end-to-end convergence sim** (jvmTest): N peers, each fetches the kernel bobbin, runs it on a
   different local batch, contributes to `FedAvg`, and the merged weights converge toward the true
   line over rounds. **(Gated on C5b.)**

**Out (YAGNI / other slices):**
- The full multi-device *demo* presentation — that's **F4**.
- Secure aggregation — that's **F3** (optional).
- Browser/native execution of the kernel — rides C5b's deferred `WasmRuntime` impls.
- Any neural-net / non-linear model — overshoots a substrate demo (see Approaches).
- A wasm build toolchain — the kernel is hand-authored `.wat` (see §2).

## Approaches considered

**Learning task.**
- **(A, chosen) One GD step of linear regression.** Fit `y ≈ w·x + b`; weights are a small fixed
  vector. The kernel output *is* a weight vector, so the `FedAvg.contribution` wiring is honest with
  zero contortion. Convergence to the true line across peers is a legible, visual payoff.
  Hand-authorable in `.wat`.
- (B) Federated mean/statistic estimation. Simplest, but it's "just averaging" — a weak ML payoff.
- (C) Tiny neural net / MNIST. Most impressive, but needs a real wasm toolchain, a large kernel, and
  fights the sandbox's memory/time caps. Overshoots a demo.

**Kernel authoring.**
- **(chosen) Hand-written `.wat` → `.wasm`, committed with provenance** (mirrors C5b's
  `square.wat`/`reverse.wat`). Zero build-toolchain cost; the kernel is small and bounded. A loop
  over N f64 examples computing the MSE gradient is ~40–60 lines of `.wat`.
- (alt) Compile from Rust/AssemblyScript. More ergonomic for bigger kernels, but adds a toolchain to
  the build and a non-reproducible artifact step. Documented as the fallback if `.wat` proves too
  painful; **default is hand `.wat`.**

## Design

### 1. The model and the training step

Multivariate linear regression with a fixed dimension `D` (e.g. `D = 2`: one feature + bias →
weights `[w, b]`; the design generalises to any `D`). Each peer holds a local batch of `N` examples
`(xᵢ, yᵢ)` where `xᵢ ∈ ℝ^(D−1)` and `yᵢ ∈ ℝ`.

One GD step over the batch (mean-squared-error loss, learning rate `η`):

```
prediction_i = w · x_i + b              // dot product over D−1 features, plus bias
error_i      = prediction_i − y_i
grad_w[j]    = (2/N) · Σ_i error_i · x_i[j]
grad_b       = (2/N) · Σ_i error_i
w'[j] = w[j] − η · grad_w[j]            // updated feature weights
b'    = b    − η · grad_b              // updated bias
```

The peer contributes `(N, w')` to `FedAvg`; the count-weighted mean across peers is the round's
global model. Multiple rounds increment `FedAvg`'s `epoch`.

### 2. The kernel — ABI conformance (C5b)

The kernel is a wasm module exporting the C5b warp ABI exactly:

| Export | Signature | Role |
|--------|-----------|------|
| `memory` | linear memory | shared host/guest buffer |
| `warp_alloc` | `(len: i32) -> i32` | guest returns a pointer to `len` writable bytes |
| `warp_run` | `(ptr: i32, len: i32) -> i64` | run over arg bytes `[ptr, ptr+len)`; return packed `(resPtr << 32) | (resLen & 0xFFFFFFFF)` |

`warp_run` decodes the input layout (§3), runs the GD step in f64, encodes the output layout, and
returns the packed pointer/length. No imports (C5b sandbox rejects any import). Pure compute, bounded
loops — well inside the sandbox's memory cap and execution-time bound. Committed as
`fedavg_train.wat` + `fedavg_train.wasm` with a provenance comment, mirroring C5b's resources.

The learning rate `η` and dimension `D` are encoded in the input header (below), so the *same kernel
bytes* serve any `(D, η)` — the kernel is hyperparameter-agnostic; the task descriptor's `args`
carry the round's configuration.

### 3. Wire layout (the codec — `commonMain`)

Bit-deterministic, little-endian (wasm linear memory is LE; matches `FedAvg`'s bit-for-bit
requirement — identical bytes → identical f64 on every platform). All multi-byte integers LE; all
reals IEEE-754 f64 LE.

**Input** (`warp_run` args) — `FedAvgKernelCodec.encodeInput`:

```
magic:    u32   = 0x46415631 ("FAV1")   // versioned, fail-loud on mismatch
dim:      u32   = D                       // weight-vector length (features + bias)
learnRate:f64                            // η
count:    u32   = N                       // number of local examples
weights:  f64 × D                         // current global weights [w_0..w_{D-2}, b]
examples: f64 × (N × D)                   // per example: D−1 features then the label y
```

**Output** (`warp_run` result bytes) — `FedAvgKernelCodec.decodeOutput`:

```
magic:    u32   = 0x46415631
dim:      u32   = D
count:    u64   = N                       // echoed sampleCount (Long for FedAvg)
weights:  f64 × D                         // updated weights w'
```

`decodeOutput` returns a small `data class TrainingUpdate(sampleCount: Long, weights: List<Double>)`;
the caller feeds it straight to `FedAvg.contribution(peer, update.sampleCount, update.weights, epoch)`.
Malformed/short/`magic`-mismatched output **throws** (`IllegalArgumentException`) — fail loud, never
a silent default (the C5b runtime already converts a kernel trap to a terminal error; the codec
guards the bytes-shape contract on top).

### 4. The reference trainer (`commonMain`) — correctness oracle

A pure-Kotlin `ReferenceTrainer.step(weights, batch, learnRate): List<Double>` implementing the exact
§1 arithmetic. Two jobs:
1. **Pin the kernel.** A jvmTest asserts the `.wasm` kernel's output equals the reference output for
   the same input — bit-for-bit on the f64 weights (the kernel is *proven equivalent* to a readable
   Kotlin oracle, not trusted blind).
2. **Unblock the FedAvg wiring.** The codec round-trip and the `update → FedAvg.contribution`
   wiring are tested through the reference trainer in **commonTest**, needing **no runtime** — so
   this slice of F2 lands before C5b.

### 5. The convergence sim (jvmTest) — gated on C5b

Via the canonical harness (`MultiNodeRaftSim` from `:kuilt-raft-test`; `StandardTestDispatcher`,
tight timeout, bounded advance — never `advanceUntilIdle`):

- N peers; one peer seeds the kernel bobbin into its `Creel`; the others lack it.
- Each peer is assigned a `train` task (`TaskDescriptor` whose `op` = the kernel's `BobbinHash`, whose
  `args` = `encodeInput(currentGlobalWeights, peerLocalBatch, η)`).
- A peer lacking the kernel **fetches** it via `BobbinExchange`, **loads** it via C5b's
  `ChicoryWasmRuntime`, **runs** it, decodes the output, and merges
  `FedAvg.contribution(...)` onto its board.
- Assert: after R rounds, every peer's merged `FedAvg.weights` converge toward the known true line
  (within ε), and all peers agree (CRDT convergence).

### 6. Module placement & collision surface

All in `:kuilt-warp` (where `FedAvg`, `Op`, `Creel`, `BobbinExchange`, `WarpNode` live):

- **`commonMain`:** `FedAvgKernelCodec.kt`, `ReferenceTrainer.kt`, `TrainingUpdate.kt` — **new files,
  no edits to C5b-owned files** (`WarpNode.kt`, `OpResult.kt`). Low collision risk with the c-bobbin
  session.
- **`commonTest`:** codec round-trip + reference-trainer + FedAvg-wiring tests (no runtime).
- **`jvmTest`:** `fedavg_train.wat`/`.wasm` resources, kernel-equals-reference test,
  `FedAvgKernelSimTest.kt` (the §5 sim — depends on C5b's `ChicoryWasmRuntime`).

**Sequencing:** the runtime-independent slice (codec + reference + commonTest) can land immediately;
the wasm-kernel + sim slice **stacks on C5b #929** so it targets the *merged* ABI (eliminating the
ABI-race Iain flagged when approving "start F2 now").

## Testing

1. **Codec round-trip** (commonTest): `decodeOutput(encodeOutput(x)) == x`; input encode produces the
   documented byte layout (golden bytes).
2. **Reference trainer** (commonTest): one GD step on a known batch matches hand-computed expected
   weights; loss decreases.
3. **FedAvg wiring** (commonTest): two reference updates with different sample counts produce the
   correct count-weighted mean via `FedAvg`.
4. **Kernel ≡ reference** (jvmTest, gated on C5b): the `.wasm` output equals `ReferenceTrainer` output
   bit-for-bit.
5. **Malformed output** (commonTest): truncated / bad-magic bytes throw, never default silently.
6. **Convergence sim** (jvmTest, gated on C5b): §5 — fetch→load→run→merge→converge across N peers.

`./gradlew :kuilt-warp:build detektAll --rerun-tasks` before any auto-merge (Android + Native
variants compile `commonMain`/`commonTest`).

## Files

- **New (`commonMain`):** `FedAvgKernelCodec.kt`, `ReferenceTrainer.kt`, `TrainingUpdate.kt`.
- **New (`commonTest`):** `FedAvgKernelCodecTest.kt`, `ReferenceTrainerTest.kt`, `FedAvgWiringTest.kt`.
- **New (`jvmTest`):** `fedavg_train.wat` + `fedavg_train.wasm` (provenance comment),
  `FedAvgKernelEquivalenceTest.kt`, `FedAvgKernelSimTest.kt`.
- **Docs:** `kuilt-warp/module.md` + a Writerside topic note once landed; tick F2 on epic #856.
- **`@sample`:** a `sampleFedAvgKernel` in `kuilt-warp/src/commonSamples/` showing
  encode → (run) → decode → contribute.

## Dependency & risk

- **C5b #929 is a hard gate for the wasm slice** (steps 4–6 above). Until it merges there is no
  production `bytes → Op` loader and no final ABI. The runtime-independent slice (steps 1–3, 5-codec)
  is unblocked.
- **ABI-race** (the risk Iain accepted in choosing "start F2 now"): the kernel + codec target C5b's
  *spec'd* `warp_alloc`/`warp_run`. Mitigation: land the wasm slice **after** C5b merges so it targets
  the real ABI; the spec is cheap to adjust if the ABI shifts before then.

## Deferred (follow-up)

- **F3** secure aggregation (optional) — rides the same monotone accumulation.
- **F4** end-to-end demo / visualisation.
- **Browser/native kernel execution** — once C5b's `WasmRuntime` impls for wasmJs/native land.
- **Larger models** (logistic regression, tiny NN) — if the demo wants more than a line.
