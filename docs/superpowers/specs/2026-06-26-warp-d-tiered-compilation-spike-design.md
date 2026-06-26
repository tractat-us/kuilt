# Warp Epic D — distributed tiered compilation (mechanism spike) — design

**Date:** 2026-06-26
**Epic:** [#855](https://github.com/tractat-us/kuilt/issues/855) · **Umbrella:** #857 · **Roadmap:** `docs/warp-roadmap.md` (Epic D) · **Full design:** `docs/warp-execution.md#compiler-nodes--distributed-tiered-compilation`
**Status:** approved design — implementation plan to follow.

## What this is, in one line

A peer that starts by *interpreting* a wasm kernel automatically *tiers up* to a
**compiled** variant once a stronger peer builds it and gossips it across the mesh —
a JIT smeared across the network. This spec scopes only the **mechanism proof**: prove
the variant can be produced, gossiped, discovered, fetched, and swapped in, measured by
a durable metric — with a *fake* compiler. Real toolchains (GraalWasm / Kotlin-Wasm /
`wasm-opt`) are explicitly **out of scope** here (Epic D4, a separate brainstorm).

## Why a spike, not the whole epic

Epic D carries an explicit go/no-go: *"a peer demonstrably tiers up interpreted →
compiled on a non-iOS target via a gossiped bobbin variant."* That question is about the
**distribution + swap mechanism**, not about whether the compiled code is actually
faster. Answering it with a fake compiler costs almost nothing and de-risks the heavy
toolchain integration that follows. This matches the roadmap's "go/no-go before
committing to toolchains."

## The foundation D builds on (already on `main`)

D adds almost no new surface; it wires existing seams.

- **`Creel`** — content-addressed byte store, invariant `key == hash(bytes)`;
  `put`/`get`/`putVerified` (tamper-checked insert). **Untouched by this spec.**
- **`BobbinExchange`** — gossips a manifest of known bobbin hashes via a `Quilter`
  (`GSet`), and fetches bytes on demand. **D changes the manifest element type** (below).
- **`WasmRuntime`** (C5b, #942) — `load(bytes): Op`, injectable, capability-sandboxed.
  Tests inject a fake that returns a known `Op` without real wasm.
- **`WarpLazyFetch`** (C5b, #942) — `(creel, runtime, opToBobbin: (OpId) -> BobbinHash?)`.
  The bundle `WarpNode` uses to resolve an unknown op at execution time:
  `opId → opToBobbin → BobbinHash → creel bytes → runtime.load → run`. **This is the
  exact seam tiering hooks into.**
- **`WarpNode`** — already carries GCounters (`executions` / `failovers` / `duplicates`)
  exported through `:kuilt-warp-otel` → `WarpMetricExporter`. **Tier counters extend this.**
- **`Op`** — `fun interface Op { suspend fun invoke(args: ByteArray): ByteArray }`.
  **Unchanged** — the tier is decided at *resolution*, not inside the op.

## Decisions (the three forks, settled)

1. **Scope** — mechanism spike (D1–D3) with a fake compiler; real toolchains deferred (D4).
2. **Variant model** — *variant metadata in the manifest* (additive): the manifest element
   becomes `BobbinMeta(hash, variantOf: VariantKey?)`, where `variantOf == null` means
   "raw/source bobbin" (today's meaning). A strict superset of `GSet<BobbinHash>`. No new
   CRDT, no new gossip channel. `Creel` stays pure content-addressing.
3. **Tiering proof** — *per-tier execution counters* on `WarpNode`
   (`executionsInterpreted` / `executionsCompiled` GCounters), mirroring the existing
   metrics and flowing through the same OTel bridge. The same counters measure *real*
   tiering once D4 lands a real compiler — the metric is durable, not test scaffolding.

## Architecture — four small pieces

| Piece | What it is | Reuses |
|---|---|---|
| **`compile` op** | An ordinary warp op `compile(sourceHash, target, optLevel) → variantHash`. A "compiler node" is a peer that registered it. Dispatched through the normal task ring. (The roadmap's 2-arg `compile(wasmHash, target)` elides `optLevel`.) | `Op`, `OpRegistry`, `TaskDescriptor`, `WarpNode` dispatch |
| **`BobbinMeta` / `VariantKey`** | Manifest element carries optional variant provenance. | `BobbinExchange` manifest, `Quilter` |
| **Variant-aware resolution** | `opToBobbin` prefers a compiled variant of the source for the peer's `(target, optLevel)` when the manifest holds one. | `WarpLazyFetch.opToBobbin`, `BobbinExchange.manifest` |
| **Tier counters** | `WarpNode` GCounters incremented at the resolution site by which hash was chosen. | existing GCounters + OTel bridge |

What is **not** here: no new CRDT, no new gossip channel, no change to `Op`, no change to
`Creel`, no runtime abstraction (C5b already shipped it).

## D1 — the `compile` op

`compile` is registered only on compiler-node peers (those carrying the toolchain). Its
body, for the spike, is the **fake compiler** (below). It runs like any op: a
`compile(sourceHash, target, optLevel)` descriptor is enqueued, the compiler node claims it,
produces the variant bytes, `put`s them into its `Creel` / `BobbinExchange` (gossiping a
`BobbinMeta` with `variantOf` set), and returns the variant hash. **Zero new dispatch
code** — it rides the existing task ring.

### The fake compiler

The fake must yield **distinct, still-runnable** wasm with no toolchain. Trick: **append
a wasm custom section** —
`fakeCompile(wasm, target) = wasm + customSection("compiled-for:" + target)`.

- A custom section is valid wasm that runtimes ignore, so `WasmRuntime.load` accepts it
  (no import added ⇒ no capability violation) and Chicory / wasm3 run it *identically*
  (`square(5) == 25`).
- The bytes — and therefore the content hash — differ per target, so the variant is a
  genuinely distinct, fetchable bobbin.
- Deterministic and pure: same input ⇒ same output ⇒ same hash.

## D2 — bobbin variants (additive manifest)

```kotlin
public enum class Target { Jvm, Browser, MacosArm64, IosArm64, /* … */ }
public enum class OptLevel { O0, O2 }

public data class VariantKey(
    val sourceHash: BobbinHash,
    val target: Target,
    val optLevel: OptLevel,
)

public data class BobbinMeta(
    val hash: BobbinHash,
    val variantOf: VariantKey?,   // null ⇒ raw/source bobbin
)
```

`BobbinExchange`'s manifest changes `GSet<BobbinHash>` → `GSet<BobbinMeta>`. Every raw
bobbin maps to `BobbinMeta(hash, null)`, so the change is a strict superset of today's
behaviour; existing producers/consumers migrate to the `variantOf == null` form.
`Creel` is **untouched** — bytes stay keyed by `hash(bytes)`, `putVerified` still works.
Kernel bytes never enter a CRDT (the existing raw-fetch channel still carries them).

## D3 — tiering (variant-aware resolution + counters)

The peer's resolution becomes variant-aware. Given an `opId` whose source bobbin is `S`,
a small resolver consults `BobbinExchange.manifest`:

- If it holds a `BobbinMeta(C, variantOf = VariantKey(S, myTarget, opt))` for the peer's
  target (choosing the highest available `opt` when several exist), resolve to `C`.
- Otherwise resolve to `S` (interpret the raw bobbin).

**The registry-cache wrinkle (important).** `WarpNode.executeViaRegistry` (#942) calls
`registry.resolve(descriptor.op)` *first*, and once a lazily-fetched op is loaded it
registers the `Op` under its `OpId` and reuses it. A naive "swap" therefore never tiers
up: the second execution short-circuits on the cached registry entry and re-runs the raw
bobbin forever. Tiering must **re-resolve per execution** for bobbin-backed ops.

Fix (localized to the lazy-fetch branch; the symbolic-registry path is untouched): for an
op whose `opToBobbin` returns non-null, resolve the **best bobbin per execution** via a
variant resolver, and cache loaded `Op`s keyed by **`BobbinHash`**
(`bobbinToOp: Map<BobbinHash, Op>`) rather than registering under `OpId`. Bytes are
already Creel-cached, so each distinct bobbin loads at most once, while the chosen hash is
free to change when a variant gossips in. The resolver:

- `bestBobbin(op)`: let `S = opToBobbin(op)` (the raw source hash). Scan the
  `BobbinExchange.manifest` for `BobbinMeta(C, variantOf = VariantKey(S, myTarget, opt))`
  for the peer's target; return the highest-`opt` `C` if any, else `S`.

At the resolution site `WarpNode` increments `executionsCompiled` when the chosen hash was
a variant, else `executionsInterpreted`. The **swap is one-way** for the spike (no de-opt):
once a variant exists for the target it always wins. The swap *is* the manifest gaining the
`BobbinMeta(C, …)` entry — the next execution resolves to `C`, loads compiled bytes, ticks
the compiled counter.

## Go/no-go test (the GO/NO-GO PR)

Canonical sim harness, 2–3 peers, in `commonTest` (all targets) with a **fake
`WasmRuntime`** injected (returns the `square` `Op` without real wasm — the real-runtime
path is already proven by C3):

1. Peers run `square`; weak peers start by interpreting the raw bobbin `S`.
2. Enqueue `compile(S, Jvm, O2)`; the compiler-node peer produces the variant `C` and
   gossips `BobbinMeta(C, variantOf=VariantKey(S, Jvm, O2))`.
3. Assert: a weak peer on target `Jvm` resolves to `C` (the highest available `optLevel`
   for its target), fetches it, and its `executionsCompiled` goes `0 → ≥1`, while results
   stay correct (`square(5) == 25`).

Coroutine discipline: `UnconfinedTestDispatcher` (or `StandardTestDispatcher`) with
bounded `advanceTimeBy` + `runCurrent`; **never `advanceUntilIdle`** (anti-entropy timers
re-arm forever). Seed every `Quilter.random`. Mirrors `ChicoryRuntimeDispatchTest`'s
`settle()` shape.

## Honest asterisks (must stay loud in docs)

- **The spike proves *distribution + swap*, not *speedup*.** The custom-section transform
  is a no-op optimization. "Is the compiled tier actually faster?" is **D4's** question,
  with real `wasm-opt` / GraalWasm. Do not oversell.
- **iOS ceiling stays *interpret*.** A compiler node can ship iOS an *optimized wasm*
  (wasm→wasm) but never native — Apple forbids executing externally-delivered machine
  code at all (JIT *and* downloaded dylibs). Policy, not capability — the one thing the
  mesh cannot optimize away.

## Slices (one behaviour per PR)

1. **D-spike-1** — `VariantKey` / `BobbinMeta` + additive manifest migration in
   `BobbinExchange` (raw bobbins become `variantOf = null`). No behaviour change to fetch.
2. **D-spike-2** — `compile` op + fake custom-section compiler + `Target` / `OptLevel`.
   A compiler node produces a gossiped variant; no tiering yet.
3. **D-spike-3** — variant-aware resolution + `executionsInterpreted` /
   `executionsCompiled` GCounters + the **go/no-go sim** (the GO/NO-GO PR). Wire the new
   counters into the `:kuilt-warp-otel` bridge.
4. **D-polish** — docs (`warp-execution.md` honesty notes, `module.md`, guide), cleanup,
   `@sample` coverage.

## Conventions

`explicitApi` (every public decl gets `public`); `detektAll`; canonical sim harness;
`:kuilt-warp` stays out of the BOM. TDD per slice (failing test first). One branch per
sub-issue off `origin/main`; PR `closes #<sub-issue>`. Comment on the sub-issue at start
and per-PR; tick #855's checklist; close on merge.

## Out of scope (Epic D4 — separate brainstorm)

Real compiler toolchains: `D4·kwasm` (Kotlin/Wasm authoring of kernels) and `D4·graal`
(GraalWasm compiler node producing genuinely optimized native/wasm variants), plus real
`wasm-opt` (Binaryen) for the iOS-relevant wasm→wasm tier. These answer "is it faster?"
and replace the fake compiler behind the same `compile` op and the same tier counters.
