# Morning report — overnight 2026-06-23 → 06-24

_Running log; newest section appended. TL;DR at top is kept current._
_Last heartbeat 06:36 ET: main green (`f857e72`), only the 2 warp drafts open, quiet. Stood down._

## TL;DR (final — night at clean rest)

- **`main` was RED** at bedtime — a wasmJs Mocha **timeout** (not a flake, not a hash bug) on a heavy HyperLogLog accuracy test was failing every PR's `build-native` and blocking the whole merge train. **Root-caused + fixed in [#768](https://github.com/tractat-us/kuilt/pull/768)** — merged, locally verified, main now **green** at `f857e72`.
- **That unblock cleared the whole jam:** the entire in-flight **#701 Spool-migration epic** (multiple sibling sessions) landed behind it — my cascade #765/#764/#766 plus #780/#783/#784/#785/#786 and more, all green.
- **Warp fantasy dispatched (the `next-plan.md` GO gate was met) and both lanes delivered:** spike #680 → Draft **[#769]** (dup-rate measured, reviewed); OTel exporter #723 → Draft **[#772]** (`:kuilt-otel` A1+A2). Both **review-gated — parked as Drafts for you**, not merged unattended.
- **No Maven Central release cut** (kuilt releases = deliberate minor bump + tag; not unattended-safe). Snapshots published automatically on every main push.
- Took control of the dispatcher checkout (discarded the stale `kuilt-701-linkseam` per your OK; the `op-log` doc edit was already in #766). Own branch cleaned up.
- **Open PRs now: just #769 + #772** (your warp drafts). Filed follow-up **[#771]**.

## The main-red incident (priority-1, root-caused)

Symptom: latest `main` (`1e286ab`, #763) failed `build-native`; so did #765/#764 — all on the **same** test:
`HyperLogLogTest.estimateIsWithinErrorBandFor10kDistinctItems[wasmJs, browser]`.

Diagnosis (read the CI test-report XML, not the truncated console):
- The `<failure>` was **`Timeout of 2000ms exceeded`** — Mocha's per-test budget, **not** an assertion failure.
- Murmur3 golden vectors **pass** on wasm → no cross-platform hash divergence; the estimate is correct.
- The test inserts 10k distinct items, each an O(m) lattice join over 16384 packed 6-bit registers. #762's 6-bit packing raised per-op cost enough that interpreted wasm overruns the default 2s Mocha budget.

Fix ([#768](https://github.com/tractat-us/kuilt/pull/768)): add `kuilt-crdt/karma.config.d/timeouts.js` — the established #306 pattern already used by `:kuilt-deal`/`:kuilt-deal-test`. **Verified locally: `:kuilt-crdt:wasmJsBrowserTest` BUILD SUCCESSFUL.** Not a band-aid: a correct-but-slow test needs an adequate wall-clock budget; this is configuration, not a race papered over.

Follow-up to file: hoist the Mocha-timeout config into the `kuilt.kmp-library` convention plugin so every module gets it structurally (survey-the-category → make the class impossible).

## Warp fantasy (next-plan GO gate met → dispatched)

- **Lane 1 — spike #680** → Draft [#769](https://github.com/tractat-us/kuilt/pull/769). **Measured exclusive-claim duplicate-execution rate**: 9.1% (2 peers) → 16.7% (4) → 28.6% (8); scales with **peer count, not** loss/partition; all tasks complete, dedup correct. **Go** for low-peer embarrassingly-parallel work; an intent register (`LWWMap<TaskId,PeerId>`) recommended for >8 peers. Review-gated — left as Draft for your call (do not auto-merge).
- **Lane 2 — OTel exporter #723** → Draft [#772](https://github.com/tractat-us/kuilt/pull/772). New **`:kuilt-otel`** module (wired into settings + BOM; compiles on all targets), binding = option (a) direct OTLP wire. Landed **A1 `DurableStore`** (+ in-memory impl; platform WALs deferred) + **A2 `WarpSpanExporter`** (`ORSet<SpanRecord>`, CBOR; `export()` returns on durable write; **no double-count under retry** proven; eviction logs what it dropped; `runCatchingCancellable` + atomicfu-lock-guarded state). **17 tests pass** across JVM/iOS/macOS/wasm. Deferred (re-plan): A3 metrics · A4 logs · A5 OTLP bridge · 3 platform WALs — independent, ready for parallel dispatch next session. Review-gated — left Draft.

  ⚠ Note: #772 wires a **new published module into the BOM**. It's review-gated (Draft) — confirm the module name/surface before it leaves draft.

Both warp lanes are now **done and parked as Drafts** (Draft PRs skip the build, so they're unaffected by the #768 red-main fix; correctly review-gated for you).
- Note: both lanes initially mis-landed in the dispatcher checkout (isolation fell back because I first dispatched from a dirty/wrong-branch checkout). Stopped cleanly (no commits lost), re-dispatched from clean `main` into proper isolated worktrees. Probe confirmed `isolation: worktree` works fine from a clean checkout.

## Release posture (important — no release cut)

kuilt is **not** the fireworks Xcode/`release`-branch flow. Every push to `main` already
publishes a **Tigris snapshot** automatically, so the cascade merges below produce
snapshots with no extra step. A **Maven Central release is a deliberate minor-version
bump (`kuiltVersionLine`) + `v<x.y.z>` tag** — an external, human-intended action. I did
**not** cut one overnight (you didn't ask, and it's not an unattended-safe action). If you
want a release, say so and I'll open the minor-bump PR.

## Cascade (post-#768, on green main `aa5eec6`)

#768 merged → main green. Updated the in-flight #701/crdt PRs onto it; all three carry the
karma fix and have **auto-merge ON**, re-running CI:
- **#765** Spool generic · **#764** MovableTree causal-GC · **#766** Fugue causal-GC.
Drafts **#760 / #757** left to their own sessions (intentional drafts).

## In flight at wake-up

- **#769** warp spike — Draft, architect-reviewed. Your call whether warp graduates spike→build epic.
- **#772** warp OTel `:kuilt-otel` A1+A2 — Draft. Confirm module name/surface before it leaves draft; A3/A4/A5 + platform WALs are a clean re-plan ready for parallel dispatch.
- Their 2 isolated worktrees are intentionally kept (open PRs). All other agent worktrees were swept by their own sessions as PRs merged — nothing left for me to clean.
- I did **not** sweep branches/worktrees aggressively: sibling sessions were still actively merging through the night. Worth a `clean up for next iteration` pass once everything's quiet.

## Follow-ups filed

- **[#771]** — hoist the wasmJs Mocha/Karma timeout config into the `kuilt.kmp-library` convention plugin (this was the 3rd module to need the per-module copy; make the class structural).

## Anomalies

- Both warp lanes **initially mis-landed in the dispatcher checkout** (isolation fell back because the first dispatch went out while the checkout was on a dirty, wrong-branch state). Caught it, stopped both cleanly (no commits lost), re-dispatched from clean `main` into proper isolated worktrees. Probe confirmed `isolation: worktree` is healthy from a clean checkout — lesson: **dispatch only from a clean checkout on the intended branch.**
- #785 (a sibling session) reverted a CI daemon-heap pin that was OOMing `QuilterConcurrencyTest` under contention — their issue, handled by them; noted only so you see it in the main log.

## Decisions needed

- **Warp spike #769 — believe the number, with caveats.** Architect review (posted on #769) flags: the **9.1%→28.6% dup-rate is a lower bound** (the sim merges full state in the same round — the tightest-possible convergence window; real multi-hop gossip + Quilter anti-entropy would push it higher); the `BoundedCounter` scheduler leg is **decorative** (quota seeded to taskCount, never gates a claim); and "dedup is correct" is **asserted loosely, not proven** (no final-convergence assertion). Recommendation: treat the spike as directional-GO for low-peer parallel work, and if pursued, the next iteration is the spike's own **intent-register (`LWWMap<TaskId,PeerId>`)** idea — the harness is already shaped to measure its before/after delta. Your call on whether warp graduates from spike to build epic.
- **Warp OTel #772 — new published module.** `:kuilt-otel` is wired into the BOM. Confirm the module name/surface before it leaves Draft.

## Follow-ups filed

(pending)
