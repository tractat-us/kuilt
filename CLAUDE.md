# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**kuilt** is a peer-symmetric, multiplatform networking library. It moves
opaque byte frames between peers over interchangeable *fabrics* (WebSocket,
mDNS-discovered LAN, Apple Multipeer, WebRTC, Android Nearby) behind one
contract. It knows nothing about the application semantics that ride on top —
that's the consumer's job.

Published to Maven Central under `us.tractat.kuilt:*` (tagged releases), with
continuous snapshots on Tigris. Kotlin Multiplatform
(JVM, Android, iOS, macOS, wasmJs). See `docs/architecture.md` for the design
and `docs/usage.md` for how to consume it.

> **Status: in active development (pre-1.0).** The API and module layout are
> still moving as the extraction lands. Bias toward **aggressive, low-ceremony
> merging** — small PRs, auto-merge once green, fix-forward over long review
> cycles. The only hard gate is the `ci-required` check (below); everything else
> (up-to-date branches, reviews, signed-off discussions) is intentionally relaxed
> while the foundation settles.

## Module structure & dependency direction

"all" = the `kuilt.kmp-library` default target set: JVM, Android, iOS
(`iosArm64`/`iosSimulatorArm64`), macOS (`macosArm64`), wasmJs.

**Contract & core**

| Module | Targets | Role |
|--------|---------|------|
| `:kuilt-core` | all | The contract (`Loom`/`Seam`/`Swatch`/…), the `InMemoryLoom` reference impl, `MuxSeam` (multiplexes several logical channels over one fabric), and `CompositeLoom` (bonds several transports/"plies" into one multipath `Seam` — see `docs/ply-roadmap.md`). Depends on nothing but coroutines + serialization. |

**Libraries layered on the contract**

| Module | Targets | Role |
|--------|---------|------|
| `:kuilt-crdt` | all | The delta-state CRDT zoo (`GCounter`/`PNCounter`/`GSet`/`ORSet`/`TwoPhaseSet`/`LWWRegister`/`MVRegister`/`LWWMap`/`ORMap`/`BoundedCounter`/`Rga`/`Causal`/`JsonCrdt`/`EphemeralMap`), plus `SeamReplicator` (live replication over a `Seam`). |
| `:kuilt-deal` | all | Cryptographically fair card dealing over a `Seam`: `DealSession` (op-based shuffle/strip/quorum-reveal via the SRA commutative-encryption scheme, `SraScheme`) plus `FairRandom` (two-phase commit-reveal seed agreement, no trusted dealer). Depends on `:kuilt-core` + `:kuilt-crdt`. |
| `:kuilt-game` | all | Turn-based game facade over `:kuilt-raft`: `TurnSequencer` (propose/commit typed actions) + `IndexedAction` (committed action carrier) + `SpeculativeSequencer` (optimistic apply with deterministic rollback/replay). |
| `:kuilt-raft` | all | Raft consensus over a `Seam` — leader election + PreVote, log replication, log compaction + chunked `InstallSnapshot`, dynamic membership, linearizable reads (`readIndex()`, §3.6/§3.7) and graceful leadership transfer (`transferLeadership()` via TimeoutNow, §3.10). |
| `:kuilt-session` | all | Membership-aware `Room` over a `Loom` (`SeamRoom`): admit/identify handshake, roster, reconnect tokens, partition detection. |
| `:kuilt-stream` | all | Byte-stream → message-link adapter: `framed()` wraps a kotlinx-io `Source`/`Sink` as a `Conn` with 4-byte length-prefix framing + oversize protection (`FrameTooLargeException`). The bridge a *stream* transport crosses to become a fabric; consumed by `:kuilt-tcp`. |

**Fabrics & discovery**

| Module | Targets | Role |
|--------|---------|------|
| `:kuilt-websocket` | all | Ktor WebSocket fabric — the "Far"/relay topology. `KtorClientLoom` everywhere; `KtorServerLoom` + `KtorRoomHost` on JVM/Android only. |
| `:kuilt-tcp` | JVM, Android | Raw TCP fabric (the pluggable-fabric-kit headline). `TcpLoom.host`/`join` adapt a Ktor socket via `:kuilt-stream`'s `framed()` + `handshaking()` into a 2-peer `Seam`; real-IO only (guards against virtual-time construction). |
| `:kuilt-multipeer` | iOS, macOS | Apple Multipeer Connectivity fabric — the "Near"/peer-to-peer topology. Provides `MultipeerRoomHost`. |
| `:kuilt-nearby` | all (Android impl) | Google Nearby Connections fabric — Android implementation behind `play-services-nearby`. |
| `:kuilt-webrtc` | all (browser/wasmJs) | WebRTC data-channel fabric. |
| `:kuilt-mdns` | JVM, Android, iOS | Bonjour/mDNS discovery. On JVM it depends on `:kuilt-websocket` (discovery feeds a WebSocket connection — discovery is orthogonal to topology). |

**Conformance & test support**

| Module | Targets | Role |
|--------|---------|------|
| `:kuilt-conformance` | all | The TCKs — `SeamConformanceSuite` and `RoomConformanceSuite`. Every fabric/room impl is verified by subclassing these. |
| `:kuilt-test` | all | Shared test utilities/fakes built on `:kuilt-core`. |
| `:kuilt-session-test` | all | Session test support (`FakeRoomFactory`, …). |
| `:kuilt-raft-test` | all | Raft test harness (`FakeRaftNode`, …). |
| `:kuilt-deal-test` | all | Commutative-scheme TCK — `CommutativeSchemeConformanceSuite` verifies any `CommutativeScheme` impl (round-trip recovery, commutativity, strip-order independence, key distinctness). Shipped in `commonMain` for subclassing. |

**Packaging**

| Artifact | Role |
|----------|------|
| `:kuilt-bom` | A Gradle/Maven platform (`java-platform`) that constrains every kuilt module to one aligned version. Consumers import it once (`implementation(platform("us.tractat.kuilt:kuilt-bom:<v>"))`) and then declare modules without versions. Not a KMP code module. |

Fabric and feature modules depend on `:kuilt-core` (some also on sibling
libraries — e.g. `:kuilt-mdns` → `:kuilt-websocket`, `:kuilt-deal` →
`:kuilt-crdt`). The dependency arrow never points back into `:kuilt-core` — it
must stay free of fabric-specific imports.

## The contract (one-paragraph orientation)

`Loom` is a factory: `weave(Rendezvous): Seam` is the single abstract method —
pass `Rendezvous.New(pattern)` to host a session or `Rendezvous.Existing(tag)` to
join one. Convenience wrappers `host(Pattern)` and `join(Tag)` delegate to `weave`.
`availability(): FabricAvailability` reports whether the fabric is usable on this
runtime. `Seam` is one peer's *symmetric* view of a multi-peer session — there is
no client/server split at this layer; a 2-peer WebSocket connection is just the
degenerate `peers.size == 2` case. `Swatch` is the opaque, binary-only frame.
`incoming: Flow<Swatch>` is **single-collection** — collect it once per `Seam`;
fan out with `shareIn`, never collect twice. This is the cohered contract of
ADR-034 / ADR-002; the full rationale is in `docs/architecture.md`.

## Build & test commands

Non-interactive shells don't load `~/.zshrc`, so source SDKMAN and select JDK 21
first (matches CI):

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
```

| Task | Command |
|------|---------|
| Full build + all tests | `./gradlew build` |
| JVM tests only (fast inner loop) | `./gradlew jvmTest` |
| One module's JVM tests | `./gradlew :kuilt-core:jvmTest` |
| A single test class | `./gradlew :kuilt-core:jvmTest --tests "*SwatchTest"` |
| All platforms' tests | `./gradlew allTests` |
| wasmJs / iOS sim / macOS | `./gradlew wasmJsTest` · `iosSimulatorArm64Test` · `macosArm64Test` |
| mDNS multicast integration (off by default — needs a real network) | `./gradlew :kuilt-mdns:jvmTest -Pmdns.multicast.tests=true` |
| Lint / static analysis | `./gradlew detektAll` |

**Use `detektAll`, not bare `detekt`.** Plain `detekt` is `NO-SOURCE` in this KMP setup (the per-target tasks have no aggregated source) and reports BUILD SUCCESSFUL without analyzing anything. `detektAll` is the real check — and the one CI runs. "Detekt passed locally" via bare `detekt` is a false green.

The mDNS multicast suite is opt-in because it sends real multicast packets; the
`-P` flag is forwarded to JVM tests as a system property and to K/N simulator
tests as the `MDNS_MULTICAST_TESTS` env var (see `kuilt-mdns/build.gradle.kts`).

## Conventions specific to this repo

- **`explicitApi()` is enforced** (set in the `kuilt.kmp-library` convention
  plugin). Every public declaration needs an explicit visibility modifier or the
  build fails. New public types get `public`.
- **Build logic is centralized** in `build-logic/`: `kuilt.kmp-library` defines
  the standard target set + Android namespace (`us.tractat.kuilt.<module>`);
  `kuilt.publish` wires the in-tree `TigrisStaging` (file://) Maven repo that
  `publish.yml` stages publications into. New modules apply
  `id("kuilt.kmp-library")` and almost nothing else.
- **KMP source-set hierarchy is wired by hand in `:kuilt-websocket`** — a manual
  `jvmAndAndroidMain` intermediate (Ktor server is JVM/Android-only) disables the
  plugin's default auto-wiring, so `iosMain`/`macosMain` intermediates are also
  declared explicitly. Edit those `build.gradle.kts` source-set blocks carefully.
- **Test a new fabric by subclassing `SeamConformanceSuite`** and implementing
  `newLoomPair()`. Every fabric must pass the same suite (see
  `InMemoryLoomConformanceTest`). In-process radio fabrics return the same instance
  twice; role-split fabrics return distinct host/joiner Looms wired to each other.
  Real-radio/real-network tests stay separate and `-P`-gated; the conformance suite
  runs against an in-memory or loopback harness.
- Test methods: no `test` prefix (the `@Test` annotation suffices); multi-assert
  tests use `assertAll()`.
- **Coroutine test determinism:** types that own a `CoroutineScope` take an
  injectable dispatcher (production default), or inherit `currentCoroutineContext()`;
  tests inject a test dispatcher rather than letting a real production dispatcher run
  under `runTest` (the cause of a past Kotlin/Native flake). Use
  `StandardTestDispatcher(testScheduler)` (FIFO at each virtual instant) for any
  system with concurrent timers + messages — e.g. `RaftNode`'s election/heartbeat
  loops, which switched to it in #383; `UnconfinedTestDispatcher(testScheduler)` is
  fine only where eager-inline ordering doesn't matter. See
  `docs/testing-coroutine-determinism.md`.
  - **No real-dispatcher defaults.** A factory/helper that owns a scope makes the
    `scope`/dispatcher a **required** parameter — never `= CoroutineScope(Dispatchers.Unconfined)`
    or similar. A default real dispatcher silently decouples the work from `runTest`'s
    virtual clock; a `backgroundScope` (lazy `StandardTestDispatcher`) silently breaks
    tests that assume eager delivery. Both bit us; required injection makes the caller choose.
  - **Production dispatchers are banned in test sources** (`Dispatchers.{Unconfined,Default,IO,Main}`,
    `GlobalScope`). The rare deliberate real-threading harness (a true-parallelism stress test,
    a callback-thread regression test, a `runBlocking` benchmark) carries an inline
    `@Suppress` with a one-line reason.
  - **Thread-safety of scope-owning types — use real primitives, never single-thread confinement.**
    kuilt is a genuinely multi-threaded library: a scope-owning type (`Seam`/`Loom`/`Room` impl) MUST be
    correct under a **multi-threaded** dispatcher. Guard shared mutable state (`var`s, mutable collections)
    with **explicit, local primitives** — atomicfu `reentrantLock` (suspend calls kept *outside* the locked
    section) or `kotlinx.atomicfu` atomics, or genuinely thread-safe structures (`Channel`, `MutableStateFlow`).
    **Do NOT use `Dispatchers.X.limitedParallelism(1)` + `withContext` as a substitute for mutual exclusion.**
    Relying on single-threaded dispatch to serialize access to otherwise-unguarded state is a banned *retreat*:
    it conflates scheduling with locking, masks races under test dispatchers (everything is serial under
    `runTest`), and breaks the instant the type runs on a real multi-threaded scope. Correctness must be a
    local, explicit property of each field — never an emergent property of where coroutines happen to run.
    The review question: *"is this still correct if the dispatcher is multi-threaded?"* If no, it needs a
    lock/atomic. **Still legitimate** (proper primitives, not confinement crutches): a single dedicated writer
    coroutine draining a `Channel` for FIFO ordering; the single-collection `incoming` contract (ADR-034, one
    event loop per session); running coroutines on an injected dispatcher purely for *scheduling*. The line: a
    dispatcher may decide *where* work runs, but must never be the *only* thing preventing a data race.
    Exemplars: `SeamReplicator`/`SeamRoom` (lock-guarded). The older `CompositeSeam`/`CompositeLoom`
    `limitedParallelism(1)` confinement is **legacy being migrated to primitives** — do not copy it.

- **Exception discipline — never swallow cancellation.** In any `suspend`/coroutine context,
  use `runCatchingCancellable { … }` (in `:kuilt-core`), **not** bare `runCatching` — the latter
  catches `CancellationException` and converts a structured-concurrency cancel into a normal
  `Result`, a silent bug. A `catch (e: Exception)`/`catch (e: Throwable)` that should tolerate
  failure must `if (e is CancellationException) throw e` (or a leading `catch (CancellationException) { throw e }`)
  before swallowing. Best-effort fabric sends are the common case:
  `runCatchingCancellable { seam.broadcast(frame) }.onFailure { logger.debug { … } }`.

## Documentation

Two published surfaces, deployed to GitHub Pages by `.github/workflows/docs.yml` on every push to `main`:

- **Dokka API reference** (`https://tractat-us.github.io/kuilt/api/`) — generated by `./gradlew dokkaGenerate`. The root `build.gradle.kts` aggregates all modules into one site under `build/dokka/html/`. Per-module KDoc is in `<module>/module.md`; code examples use `@sample` tags pointing at functions in `<module>/src/commonSamples/kotlin/…` (e.g. `kuilt-core/src/commonSamples/` and `kuilt-crdt/src/commonSamples/`).
- **Writerside guide** (`https://tractat-us.github.io/kuilt/guide/`) — source in `Writerside/` (instance id `kuilt`). CRDT zoo topics are one file per type: `Writerside/topics/crdt-<type>.md`. Each code block is a snippet copied verbatim from a specific test function, cited with an HTML comment `<!-- verbatim from <path>#<symbol> -->`.

### Keeping docs in sync with code

**`@sample` functions are compiled as part of `commonTest`** (wired by the `kuilt.kmp-library` convention plugin — any `src/commonSamples/kotlin/` directory is added to `commonTest` source roots). A typo or API change that breaks a sample breaks the build. Treat sample functions as load-bearing.

When you change public API:
- Update the KDoc on the changed declaration.
- Update (or add) the matching `@sample` function in `src/commonSamples/kotlin/`.
- If the type has a `crdt-<type>.md` Writerside topic, update its inlined snippet so it still matches the source, and update the `<!-- verbatim from … -->` citation if the function was renamed.

When you rename or remove a test function that a Writerside snippet cites, update the `<!-- verbatim from … -->` comment and the inlined code block in the corresponding topic file. The guide has no compile-time check — the citation comment is the only link back to the source.

## CI & merging

`.github/workflows/ci.yml` uses an aggregator pattern: a cheap `detect` job
classifies the change, the heavy `build` job (`./gradlew build`) runs only when
the change is **not** docs-only, and `ci-required` aggregates them into the one
required status check. **Docs-only PRs** (every changed file is `*.md` or under
`docs/`) **skip the build** — `ci-required` goes green without it — so the many
documentation PRs don't wait on a KMP build. Touch any non-doc file and the full
build runs.

`main` is branch-protected to require only `ci-required` (no required reviews,
no up-to-date-branch requirement, admins not enforced — matching the aggressive
pre-1.0 posture). Auto-merge is enabled and head branches are deleted on merge,
so the normal flow is: open PR → `gh pr merge <n> --auto --squash` → it lands as
soon as `ci-required` is green.

## Versioning & publishing

The `major.minor` version line lives in `kuiltVersionLine` in `gradle.properties`;
the full version is `<line>.<patch>`. Group is `us.tractat.kuilt`. Two axes move
independently:

- **Patch → internal.** Every push to `main` publishes a Tigris snapshot at
  `${kuiltVersionLine}.<run_number>` — the patch is just the CI run number, so it
  advances on its own with no PR. These are the continuous internal builds
  consumers iterate against; nobody hand-edits the patch.
- **Minor → external.** A Maven Central / external release **bumps the minor**
  (`0.4.x` → `0.5.0`) — a deliberate one-line PR to `kuiltVersionLine` plus a
  `v<x.y.z>` tag. The minor is the externally-meaningful number; the patch is
  internal churn.

So: don't pin a concrete `0.4.0`-style number in prose or examples (it dates the
moment a snapshot publishes) — describe the line, and link consumers to the
"latest release". `build.gradle.kts` sets the local default to
`${kuiltVersionLine}.0-dev`.

There are **two publish channels**, by trigger:

**Tigris snapshots — every push to `main`** (plus manual `workflow_dispatch`).
Continuous `${kuiltVersionLine}.<run_number>` builds; no tag required. Flow:

1. Gradle stages publications into `build/staged-maven-repo/` via the
   `TigrisStaging` Maven repo (file:// URL).
2. `aws s3 sync build/staged-maven-repo/ s3://buildcache/maven/` uploads the
   whole tree to **Tigris** (Fly's S3-compatible storage) in one parallel
   pass.

Wall time is ~5 min total, ~12–20 s for the upload itself. Bypassing Gradle's
native `s3://` transport is deliberate: it silently sets a request header (ACL
or storage-class) Tigris rejects with HTTP 400. The stage-then-`aws s3 sync`
pattern sidesteps it entirely. Consumers reading from Tigris hit the same
Gradle s3:// transport for GETs, but GETs don't set those headers so the read
path works.

**Maven Central releases — on a `v<x.y.z>` tag** (a minor bump; or a manual
dispatch with `release_to_central=true`), **never** on a plain main push. The
`maven-central` job derives the version from the tag (`v<x.y.z>` → `<x.y.z>`),
publishes signed artifacts as a **PENDING** deployment to the Central Portal that
a human then releases by hand at central.sonatype.com, and commits the README
version bump back to `main` (so the README is the one place a concrete version
number is allowed — it's release-managed, not hand-edited). This is the
consumer-facing channel — the README's `mavenCentral()` setup and version badge
resolve here.

GitHub Packages still hosts the historical 0.1.x and 0.3.x artifacts
(read-only — consumers can still resolve them from
`https://maven.pkg.github.com/tractat-us/kuilt` if needed). New versions go to
Tigris (snapshots) and Maven Central (tagged releases).

## Composite-build consumption

Consumers should depend on kuilt via published coordinates
(`us.tractat.kuilt:kuilt-*:<version>`). For zero-latency iteration when a
consumer is developed alongside kuilt, the standard pattern is a
**presence-gated `includeBuild`** in the consumer's `settings.gradle.kts`:

```kotlin
if (file("../kuilt").exists()) includeBuild("../kuilt")
```

Absent the side-by-side checkout (CI, ephemeral worktrees), the published
artifact resolves. The public API and Maven coordinates are the compatibility
surface — keep them stable across patch versions.

## References policy

When documenting kuilt (KDoc, README, design docs, commit messages, PR bodies,
this file), follow two rules:

- **Don't reference external projects / issues / PRs without explicit approval.**
  Citations to third-party trackers date quickly and mislead future readers —
  what's "the bug" today may be "the fix" tomorrow (see #24's history: it cited
  gradle/gradle#8950 as the *cause* of serial publishes when that issue is
  actually where the fix landed in 2019). If a citation is genuinely necessary,
  ask first.
- **Avoid references to other `tractat-us/*` repos where possible.** kuilt
  ships as a standalone library; cross-repo references leak organisational
  context that doesn't belong in this codebase and become dangling if those
  repos move or restructure. Describe kuilt's own behaviour and contracts in
  terms that stand alone. (Wire identifiers shared with consumers — service
  types, dylib names, cdecl symbols — are the unavoidable exception and stay.)
