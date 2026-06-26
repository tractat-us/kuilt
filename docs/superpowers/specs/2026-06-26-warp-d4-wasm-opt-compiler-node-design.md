# Warp Epic D4 — real `wasm-opt` compiler node — design

**Date:** 2026-06-26
**Epic:** [#855](https://github.com/tractat-us/kuilt/issues/855) · **Umbrella:** #857 · **Builds on:** the D-spike mechanism (#959, merged)
**Depends on:** **Phase H** — `:kuilt-warp` decomposition (`docs/superpowers/specs/2026-06-26-warp-phase-h-decomposition-design.md`). The compiler lands as a new satellite module; it slots cleanly into the post-H structure (and avoids re-bloating today's monolith).
**Status:** approved design — implementation plan to follow.
**Parked siblings:** D4·graal (#967), D4·kwasm (#968) — deliberately deferred.

## What this is, in one line

The D-spike proved a peer can *tier up* interpreted→compiled via a gossiped variant, but with a
**fake** (no-op) compiler. This delivers the **real** compiler — a `wasm-opt` (Binaryen) optimizer
behind the same `compile` op + same tier counters — and finally answers the deferred go/no-go:
**is the compiled tier actually faster?**

## Relationship to Phase H

Phase H breaks `:kuilt-warp` into satellites (`:kuilt-warp-runtime`, `:kuilt-warp-planning`,
`:kuilt-warp-ml`, …) so a symbolic-dispatch peer drags in nothing heavy. D4 adds **one more satellite**,
`:kuilt-warp-compiler` (bundled Binaryen), to that structure. D4 should land **after** H so the compiler
doesn't re-bloat the monolith. If H slips, D4 can still ship as a standalone satellite module — it only
*depends on* the core `WasmRuntime`/`WasmOptimizer` contract and `:kuilt-warp-runtime` for its benchmark,
both of which exist pre- or post-H.

## Architecture & packaging

- **New module `:kuilt-warp-compiler` (JVM).** Bundles the Binaryen `wasm-opt` binary and provides the real
  optimizer. **Opt-in:** weak peers depend on `:kuilt-warp` (+ `:kuilt-warp-runtime` to interpret); only a
  peer that wants to *be* a compiler node adds `:kuilt-warp-compiler`. The Binaryen weight falls solely on
  compiler-node operators.
- **`WasmOptimizer` seam in `:kuilt-warp` core** (mirrors the `WasmRuntime` seam):
  `public fun interface WasmOptimizer { public fun optimize(wasm: ByteArray, optLevel: OptLevel): ByteArray }`.
  Returns a distinct, still-runnable, leaner module with the `warp_alloc`/`warp_run` ABI preserved. The
  `compile` op references the interface; the bundled `BinaryenWasmOptimizer` impl lives in
  `:kuilt-warp-compiler`.
- **Binary sourcing.** Gradle resolves the official Binaryen release per host/target OS at build time
  (version-pinned + checksum-verified) into the module's resources. At runtime `BinaryenWasmOptimizer`
  extracts the host binary to a temp file and execs `wasm-opt -O<level> <in> -o <out>`. **Honest cost:**
  kuilt now vendors per-OS `wasm-opt` binaries and the extract-and-exec machinery — accepted for the
  zero-setup payoff; it is the price of "bundle" over "shell out to a PATH binary."
- **Compiler nodes are JVM/server peers.** iOS/browser/native peers are pure consumers of the gossiped
  optimized variant — they never run `wasm-opt`.

## The `compile` op (real)

`compile` is a real warp op registered only on compiler nodes (same site the spike used `publishVariant`,
now backed by a real optimizer):

1. fetch source bytes from the creel for `sourceHash`,
2. `wasmOptimizer.optimize(bytes, optLevel)`,
3. `publishVariant(optimized, VariantKey(sourceHash, target, optLevel))`.

Everything downstream — gossip, discover, fetch, tier-swap, `executionsInterpreted`/`executionsCompiled` —
is **unchanged from the spike**. We swap only the *fake* transform for the real one. (Reifying `compile`
as a *ring-dispatched* op rather than an imperative call remains #961, orthogonal to this slice.)

## Opt levels

`OptLevel` today is `O0`/`O2`. Extend to `O0`/`O2`/`O3`/`Oz` so the benchmark can select the level that
produces the largest, most unambiguous win. Mapping: `O2 → -O2`, `O3 → -O3`, `Oz → -Oz`; `O0` = passthrough
(no variant worth shipping). `wasm-opt` preserves exported functions, so the ABI survives every level.

## Testing posture (the key decision)

- **CI (deterministic, always-on)** — these run in CI because the binary is *bundled* (present on the
  runners), so no `-P` gating:
  - `BinaryenWasmOptimizer` produces a **valid, smaller, ABI-preserving, distinct-hash** module from a
    representative kernel (assert: still loads via `ChicoryWasmRuntime`; `warp_alloc`/`warp_run` present;
    byte size strictly smaller; hash ≠ source).
  - The `compile` op end-to-end on a compiler node: optimize → `publishVariant` → variant gossips with
    provenance (deterministic; virtual-time for the gossip, real wasm for correctness only).
  - Tiering integration: a weak peer swaps to the optimized variant and its result stays correct
    (extends the spike's go/no-go with a *real* optimized variant).
- **Local-only (the speedup proof)** — a real wall-clock micro-benchmark, **not** a CI job (benchmarks on
  shared CI flake; you'd want hardware anyway). Run by hand; the GO evidence is the recorded numbers
  (captured in the PR / this spec), never a CI gate.

## The benchmark (making the win unambiguous)

`wasm-opt` optimizes already-emitted wasm, so a hand-minimal `.wat` shows little. Author a **deliberately
bloated** benchmark kernel — redundant locals, dead code, an un-fused/un-hoisted hot loop doing real
arithmetic over many iterations — so `-O3` yields a dramatically smaller, faster module. Benchmark raw vs
optimized through `ChicoryWasmRuntime` (interpreter-only, so fewer instructions ⇒ directly faster), with
**warmup + median-of-N iterations + a generous margin** (target a clearly-reliable win, e.g. ≥30–50% on the
JVM interpreter — well above timing noise). Record the median raw/optimized times + ratio as the GO. The
deterministic CI test pins the *cause* (smaller, valid module); the local benchmark confirms the *effect*.

## Go/no-go (record on #855)

A `wasm-opt`-optimized variant runs **measurably faster** than interpreting the raw kernel through the real
runtime (recorded local wall-clock numbers), AND the deterministic CI tests prove the optimizer produces a
valid, smaller, ABI-preserving, distinct-hash variant that a weak peer tiers up to with correct results.

## Honest scope

- This proves **real speedup via wasm→wasm optimization** — the all-target lever (it speeds every
  *interpreter* tier: JVM Chicory, native wasm3, browser). The benchmark is recorded on JVM Chicory; the
  same mechanism benefits the iOS wasm3 interpret tier (the only iOS-legal optimization), though a wasm3
  wall-clock number is not part of this slice's GO.
- **iOS ceiling stays interpret** — `wasm-opt` ships iOS a leaner wasm, never native (Apple bans
  externally-delivered machine code).
- Real *native/JVM-exec* compilation (GraalWasm, native-image) is parked (#967); Kotlin/Wasm authoring is
  parked (#968); ring-dispatched `compile` is #961.

## Slices (one behaviour per PR)

- **D4-1** — `WasmOptimizer` seam in core + `OptLevel` extended to `O0/O2/O3/Oz`.
- **D4-2** — `:kuilt-warp-compiler` module: Gradle-resolved bundled Binaryen + `BinaryenWasmOptimizer`
  (extract-and-exec) + the deterministic optimizer test (valid/smaller/ABI/distinct-hash).
- **D4-3** — wire the real `compile` op (optimize → `publishVariant`) + tiering-with-real-variant CI test.
- **D4-4** — the deliberately-bloated benchmark kernel + the local wall-clock benchmark; record GO numbers.
- **D4-polish** — docs (`module.md` for `:kuilt-warp-compiler`, guide), honesty notes, cleanup.

## Conventions

`explicitApi`; `detektAll`; canonical sim harness for any multi-node test; coroutine-determinism rules
(bounded `advanceTimeBy`, no `advanceUntilIdle`, seeded RNG). New module applies `id("kuilt.kmp-library")`.
TDD per slice. PRs reference `Part of #855` (non-closing — never close the epic from a PR). The real-runtime
benchmark is the sanctioned real-threading exception (wall-clock, off the virtual scheduler), like
`ChicoryWasmRuntime`'s existing real-IO path.
