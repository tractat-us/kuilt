# Cleanup & simplification epic — design

**Date:** 2026-07-02
**Status:** approved design, pre-plan
**Driver:** survey of all module families by six parallel review agents (full findings inventoried below)

## Goal

Make the codebase simpler, easier to understand, and more maintainable, and the public
API consistent and bullet-proof — by removing agent anti-patterns (useless null checks,
non-idiomatic Kotlin, copy-paste twins), fixing the handful of real defects the survey
found, and **gating each cleaned pattern in detekt so it cannot regress**. The epic ends
with a minor release and a single downstream upgrade epic for the main consumer.

## Non-goals

- No behavior/feature work beyond the listed defect fixes.
- No serialization/wire-format changes anywhere (CRDT golden vectors, SRA byte parity).
- No decomposition of the structural giants (`RaftEngine`, `SeamRoom`, `WarpNode`) —
  each is filed as a design issue instead (see Deferred).
- No edits to `kuilt-warp-runtime` or wasm-adjacent files until the live `warp/*`
  epics land.

## Survey verdict (context)

The codebase is more disciplined than expected — the June thread-safety (#409–#411) and
bounded-Spool (#701) epics eliminated most of the classic agent debt. What remains:

1. **~7 real defects/policy violations** (Phase 0).
2. **Copy-paste duplication as the dominant theme** (~15 findings; Phase 2).
3. **API-surface inconsistencies** (~12 findings; Phase 3).
4. **An enforcement gap:** the nullability rules in `config/detekt/detekt.yml` require
   type resolution, but commonMain is only analyzed by `detektMetadataCommonMain`,
   which runs *without* it — so the rules silently never fire on the bulk of the
   codebase. Same gap class as #1021; same fix pattern (fold sources into
   `detektJvmMain`).

## Structure: four sequenced phases, parallel lanes inside

Sequencing (not parallelism) is the conflict-avoidance mechanism between phases; module
disjointness is the mechanism inside Phase 2. Phases 0 and 1 are small-diff and merge
fast; Phase 2 lanes never share a module; Phase 3 is one stacked branch.

### Phase 0 — correctness first (TDD: failing test → fix → revert-verify)

| # | Module | Defect | Effort/model |
|---|--------|--------|--------------|
| B1 | kuilt-nearby | `NearbyLoom.kt:119` bare `runCatching` swallows `CancellationException`; `peerCounter++` unguarded (doc claims mutex); `var closed` read/written across three sync domains | M / opus |
| B2 | kuilt-otel-log4j2 | `KuiltLog4j2Appender.append()` missing the #1034 edge `resolveTrace()` snapshot its logback/uniform siblings have | S / sonnet |
| B3 | kuilt-core | `FaultySeam`: inbound `Channel(UNLIMITED)` violates the Spool invariant; `framesDropped/Delayed/Delivered` documented "atomic" but plain `var` races | M / opus |
| B4 | kuilt-game | `GamePresence` stringly-typed markers: a `NodeId` containing `,` or `:sc` corrupts the admission-closed round-trip — delimiter-safe encoding | M / opus |
| B5 | kuilt-raft | `RaftEngine.kt:842` `?: 0L` fabricates a term for a can't-happen — replace with `error(...)` (fail-fast policy) | S / sonnet |

### Phase 1 — pattern sweeps, fix + gate in the same PR

| # | Sweep | Gate |
|---|-------|------|
| P1 | Replace remaining bare `runCatching` in coroutine code (`Quilter.kt:602`, `BoundedCounterTransferCoordinator.kt:218`, `WarpNode.kt:1037`) with `runCatchingCancellable`; annotate the ~4 sanctioned non-suspend uses (`TapAdmitMessage.decode`, `WebSocketSignalingChannel`, multipeer native close/load) with `@Suppress` + one-line reason | detekt `ForbiddenMethodCall` on `kotlin.runCatching` |
| P2 | Fold commonMain sources into `detektJvmMain` (the #1021 pattern) so the type-resolution nullability rules actually analyze commonMain; fix everything that then fires — known: `Fugue.kt:619` `!!`→`getValue`, `kuilt-test` fabric harness `!!`×4→`requireNotNull(...) { }` (`kuilt-scale`'s `!!` lives in test sources the fold doesn't reach — owned by W1) | the fold itself is the gate |

### Phase 2 — module-family lanes (parallel; lanes are module-disjoint)

Within a lane, sub-issues touching the same file are chained (`blocked-by`), everything
else parallel.

**Core lane** (kuilt-core, -stream, -liveness, -test)
- C1 (L/opus): extract generic `MuxBase<K>` + shared `ChannelView`; `MuxSeam` and
  `NamedMux` become thin framing configs (~90 % identical today).
- C2 (M/opus): fix the `:kuilt-test` layering inversion — move `StarHarness` to
  `:kuilt-gossip` test support; drop `api(project(":kuilt-gossip"))` from 30 modules'
  transitive test classpaths.
- C3 (M/sonnet): `ControllableSeam` composes/shares `InMemorySeam` instead of
  re-implementing it; collapse byte-identical `snapshotDelivery`/`snapshotDeliveryLocked`;
  delete dead `InMemoryLoom.isActive`.
- C4 (M/sonnet): `HeartbeatPartitionDetector.runHeartbeatLoop` — extract
  `handleUnresponsive(reason)` to collapse the triplicated block.

**CRDT lane** (kuilt-crdt, -quilter, -gossip)
- D1 (M/sonnet): one internal `Map.mergeValues`/`mergeMax` helper applied at the ~9
  cold copy-paste sites (skip `Rga.kt` until #779 lands). Output-identical — no
  wire-format risk; goldens must stay green.
- D2 (S/sonnet): gossip nits — `isHeartbeat` prefix-byte check instead of
  whole-payload `decodeToString()`; add `logger.debug` to the two empty `onFailure {}`
  swallows (Quilter is the exemplar).
- D3 (S/sonnet): `Quilter` convenience-factory FQN cleanup; `MovableTree` `!==`
  micro-opt removal; `Fugue.sortChildren` inline.

**Consensus lane** (kuilt-raft, -cluster, -game, -deal)
- R1 (S/sonnet): `RaftEngine` mechanical pair — O(entries×log) `filter/none` →
  precomputed `HashSet`; side-effecting `removeAll` predicate → explicit loop.
- R2 (M/opus): one `RaftNode.changeMembershipWithRetry(...)` extension in kuilt-raft;
  both duplicates deleted; give-up contract unified to **throw** (cluster's silent
  log-and-leave is a latent membership leak).
- R3 (M/opus): `ServerCluster` hand-rolled anonymous `StateFlow` →
  `combine(...).stateIn(...)`.
- R4 (L/opus): promote the 12-parameter liveness free functions to a
  `VoterLivenessMonitor` class (also shrinks `GameNode.kt`).
- R5 (M/sonnet, blocked-by R4): collapse the four near-identical flow adapters; extract
  `gameHost` spectator/admit helpers; `kotlin.time.Duration` FQN → import.
- R6 (S/sonnet): `FairRandom.deriveSeed` quadratic fold → linear, byte-identical;
  pinned by a fixed-secret regression test (seed-agreement-critical).

**Fabrics lane** (kuilt-websocket, -tcp, -multipeer, -nearby, -webrtc, -mdns, -session, -conformance)
- F1 (M/opus): WebRTC — extract shared `runHandshake(...)`; dedupe the factory's
  repeated host/joiner connect branch.
- F2 (S/sonnet): Multipeer JVM — one private `startSession(open)` for the
  open/join near-twins.
- F3 (M/sonnet): `SeamConformanceSuite` — `connectedPair()` helper replacing the
  9× host/join preamble.
- F4 (S/sonnet): `SeamRoom` — reattach the orphaned class KDoc (a stray const sits
  between the KDoc and the class).

**Otel lane** (kuilt-otel*)
- O1 (S/sonnet): `tail()` = `tailStamped().map { it.record }` (one dedup path);
  rename `LogTapHost`'s shadowed `seam` ctor param to `rawSeam` and delete the
  warning comment.
- O2 (M/opus): dedupe log-tap/metric-tap: shared `settle`/`awaitRemotePeer`, one
  `TapConfig`, one CBOR wire helper.
- O3 (M/sonnet): `WarpMetricExporter` — `metricCount()` delegates to `totalCount()`;
  use the value `removeAt` already returns; make `pickOldest`/`pickNewest` honest
  (rename or a shared insertion-ordered key registry).

**Warp lane** (GATED: starts only after the live `warp/*` branches land; `kuilt-warp-runtime` excluded entirely)
- W1 (S/sonnet): cold nits — rename `WarpMetricBridge.kt` to match its contents
  (it holds only `WarpMetricExporter` extensions); `kuilt-scale` `!!`→`error(...)`
  + FQN import. (`WarpNode.kt:1037`'s bare `runCatching` is owned by sweep P1 —
  the gate can't flip with an instance outstanding.)

### Phase 3 — API lane (one stacked branch, opus; breaking changes batched)

- A1: CRDT zoo consistency — `BoundedCounter.EMPTY`→`ZERO` (deprecated alias),
  hoist the four independent `ReplicaId("")` sentinels to one constant, document the
  Patch-vs-new-instance mutator split in `Quilted` KDoc (+ add cheap `Patch` variants
  for LWW types if trivial).
- A2: `availability()` honesty audit — Multipeer JVM returns `Unavailable` when the
  native lib is absent; audit every fabric whose runtime can compile-but-not-function.
- A3: standardize wrong-`Tag` rejection on `require(tag is X)` (one exception type).
- A4: clock injection coherence — `gameHost` `clock` becomes required (wall-clock
  default violates the inject-time rule `clusterClient` already follows);
  `ClusterClient`'s required-but-inert `clock` is wired or dropped.
- A5: `framed()` send path throws `FrameTooLargeException` (symmetric with reads).
- A6: otel API parity — `traceContextProvider` param on `installLog4j2Capture`/
  `installLogbackCapture`; decide metric-tap admission (port the token gate or
  document loopback-only by design).
- A7: relocate `FaultySeam`/`FaultyLoom`/`FlakyLifecycleSeam`/`FlakyLifecycleLoom`
  from `:kuilt-core` public API to `:kuilt-test`.
- A8: close-out — bump `kuiltVersionLine`, tag `v<x.y.z>`, Central release; file the
  **single fireworks-compose upgrade epic** with one sub-issue per breaking change
  (A1–A7 mapped).

### Deferred — filed as issues, not attempted in this epic

- `Rga`/`Fugue` op-log engine unification (~200 lines of duplication; blocked on
  #779, wire-adjacent).
- `RaftEngine` decomposition (1868 lines; needs a `RaftState`-holder design first;
  hottest file in the repo).
- `SeamRoom` reconnect/resume-machine extraction (1230 lines, cohesive today).
- Chicory `catch (e: Exception)` missing cancellation rethrow (inside
  `kuilt-warp-runtime` — hand to the wasm-runtime epics).
- `FaultProfile` inbound-delay asymmetry (low value; recorded, likely wontfix).

## Worker contract (baked into every sub-issue brief)

1. First commands: `git branch --show-current` && `pwd`; branch from fresh
   `origin/main`; **verify the cited finding still exists on `origin/main`** (survey
   ran a few commits behind) — if gone, close the issue with a note, don't invent work.
2. Claim by draft PR with `closes #N` before the bulk of work; ~10-minute slices.
3. Phase 0 issues follow TDD (failing test first, separate commit).
4. Before auto-merge: `timeout 600 ./gradlew :<module>:build detektAll --rerun-tasks`
   in the foreground — no `jvmTest`-only greens, no cached greens.
5. Model tier: S=sonnet, M/L=opus. Reviews on opus.
6. No serialization-output changes; goldens are the tripwire.

## Success criteria

- All Phase 0 defects fixed with regression tests.
- `runCatching` ban + commonMain type-resolution gates active in `detektAll` (CI job).
- Zero remaining survey findings in buckets A/B except the explicitly deferred set.
- Public-API changes land as one consumer-visible bump: minor release tagged,
  fireworks-compose upgrade epic filed with per-change sub-issues.
- No PR in the epic conflicts with another epic PR (lane discipline held).
