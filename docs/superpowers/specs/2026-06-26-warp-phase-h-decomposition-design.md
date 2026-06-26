# Warp Phase H — `:kuilt-warp` decomposition / restructuring — design

**Date:** 2026-06-26
**Roadmap:** `docs/warp-roadmap.md` (new Phase H) · **Umbrella:** #857 · **Part of:** #665
**Status:** approved design — implementation plan to follow.

## What this is, in one line

`:kuilt-warp` has grown into a monolith — symbolic dispatch, the CALM query planner, the code-mobility /
wasm runtimes (Chicory + the wasm3 native `.a` binaries), and the federated-learning demo all in one
module. A peer that only wants symbolic dispatch drags in all of it. Phase H **splits the module by
concern** so each consumer pulls in only what it uses, and so later work (the D4 compiler, future fabrics)
slots in without re-bloating the core.

## Why now / why its own phase

Each new warp capability (C5 code mobility, F2 ML, D4 compiler) has added weight to one module. The
breakup is **cross-cutting restructuring** that touches the whole warp family — it is not specific to any
one feature, so it gets its own roadmap phase and lands as a foundation the feature epics build on.
"Harden the foundation before stacking more on top."

## Why it's tractable (no `WarpNode` surgery)

The enabling fact: `WarpNode` references the **`WasmRuntime` *interface*** (injected via `WarpLazyFetch`),
never a concrete runtime. So the heavy runtime *impls* (Chicory, wasm3, browser) move out of the module
**without touching the engine**. The lightweight mobility **contract** (`WasmRuntime` iface,
`WasmSandboxConfig`, `Creel`, `BobbinHash`, `BobbinExchange`, `Variant`/`Target`, `WarpLazyFetch`) carries
no native deps and stays in core. This is a move-and-rewire, not a rearchitecture.

Today the module already keeps `ChicoryWasmRuntime` in `jvmMain` and wasm3 in `nativeInterop`, but they
ship in the *same* module, so a consumer of the core artifact still resolves Chicory (and the native
binaries weigh down the source tree). The split makes the heavy deps opt-in at the **module** level.

## Target module structure

| Module | Holds | Heavy dep it isolates |
|---|---|---|
| `:kuilt-warp` (core) | `WarpNode`, `Op`/`OpId`/`OpResult`/`OpRegistry`, `TaskDescriptor`/`TaskId`/`TaskRing`/`WorkQueue`, `Results`, `RosterSource`, `ClaimStrategy`, `CoordinationKind`/`Coordinated`/`CoordinationFree`/`ConvergentExecution`, `IncrementalResult`, `WarpStats`, `Warp`; **mobility contract** — `WasmRuntime`, `WasmSandboxConfig`, `Creel`, `BobbinHash`, `BobbinExchange`, `WarpLazyFetch`, `Variant`/`Target` | none — symbolic-dispatch peers depend on this alone |
| `:kuilt-warp-runtime` | the real runtimes: `ChicoryWasmRuntime` + `TimedGuestRunner` (jvm), wasm3 cinterop (apple native), browser runtime (wasmJs) | **Chicory dep + the wasm3 `.a` binaries** |
| `:kuilt-warp-planning` | `Draft`, `DraftCoordinator`, `DraftRewrite`, `DraftStage`, `CoordinationCost`, `WarpPlanner` | the CALM query planner |
| `:kuilt-warp-ml` | `FedAvg`, `ReferenceTrainer`, `FedAvgKernelCodec`, `TrainingUpdate` | the F2 federated-learning demo |
| `:kuilt-warp-otel` (exists, unchanged) | `WarpMetricBridge` | — |

**Dependency direction:** satellites → `:kuilt-warp` (never back). `:kuilt-warp-runtime` implements core's
`WasmRuntime`. `:kuilt-warp-ml` depends on core (+ `:kuilt-warp-runtime` in its tests, which execute
kernels). `:kuilt-warp-planning` depends on core only. `:kuilt-warp-otel` unchanged (core only).

### Boundary calls to confirm during planning
- **`BobbinExchange`/`Creel` stay in core**, not a mobility module — they're gossip + a byte cache with no
  native deps, and `WarpNode` builds a `BobbinExchange` internally on the lazy-fetch path. Moving them out
  would force a core→satellite dependency. Keeping them in core preserves the clean direction.
- **The `WasmRuntime` interface + `WasmSandboxConfig` stay in core**; only the *impls* move. This is what
  lets `WarpNode`'s lazy-fetch/tiering compile against core alone.
- **Samples / `@sample` functions** move with the code they document (the convention plugin wires
  `commonSamples` into `commonTest`); update any `@sample` FQNs that cross a module boundary.

## Mechanics

- Each new module applies `id("kuilt.kmp-library")` and almost nothing else. `:kuilt-warp-runtime` is
  multiplatform with per-target source sets (jvm = Chicory, apple = wasm3 cinterop, wasmJs = browser);
  `:kuilt-warp-planning` / `:kuilt-warp-ml` are the standard target set.
- Move files by concern (table above), preserving the `us.tractat.kuilt.warp` package where possible so the
  delta is module wiring + imports, not FQN churn.
- Move each concern's tests with its code; pure-engine tests stay in core. Cross-module test support (e.g. a
  test that needs both the engine and a real runtime) depends on both modules.
- Update `settings.gradle.kts` includes and every dependent `build.gradle.kts` (within the repo: `examples`,
  `:kuilt-warp-otel`, and any module that referenced moved types).
- Satellites stay **out of the BOM** for now (experimental), like `:kuilt-warp`.

## Go/no-go / done statement

`./gradlew build` green; a consumer depending only on `:kuilt-warp` resolves **no** Chicory, wasm3,
Binaryen, ML, or planning code (verify via the resolved dependency graph / artifact contents); each
satellite builds + tests on its own targets; **zero behaviour change** — pure move + rewire, every existing
test still green in its new home.

## Slices (one module-move per PR, each green before the next)

Sequenced to minimise churn — extract the most self-contained, heaviest concerns first:
- **H-1** — extract `:kuilt-warp-runtime` (Chicory jvm + wasm3 native + browser wasmJs runtimes + their
  dispatch tests). Biggest weight win; removes the Chicory dep and the native `.a` files from core.
- **H-2** — extract `:kuilt-warp-planning` (Draft/rewrite/cost/planner + tests + samples).
- **H-3** — extract `:kuilt-warp-ml` (FedAvg/trainer/codec/`TrainingUpdate` + tests + the `fedavg_*` wasm
  fixtures). Depends on `:kuilt-warp-runtime` for its execution tests.
- **H-4** — verify + document: confirm the done statement (core pulls nothing heavy), add a one-paragraph
  "module map" to `:kuilt-warp` `module.md` + the guide, update `docs/warp-roadmap.md` with the Phase-H entry
  and the new module layout.

Each PR moves **one** concern, builds green, and changes no behaviour. A reviewer can reject one move
without blocking the others.

## Conventions

`explicitApi`; `detektAll`; new modules apply `id("kuilt.kmp-library")`. No behaviour change — if a move
tempts a "while I'm here" fix, file it separately. PRs reference `Part of #665` and the Phase-H epic
(non-closing on the epic). Keep diffs move-only (git should detect renames); avoid reformatting moved files.
