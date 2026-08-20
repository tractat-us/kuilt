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
| `:kuilt-crdt` | all | The dependency-free delta-state CRDT zoo (`GCounter`/`PNCounter`/`GSet`/`ORSet`/`TwoPhaseSet`/`LWWRegister`/`MVRegister`/`LWWMap`/`ORMap`/`BoundedCounter`/`Rga`/`Causal`/`JsonCrdt`/`EphemeralMap`). Depends only on kotlinx-serialization; no coroutines, no kuilt-core. |
| `:kuilt-store` | all | Durable key→bytes storage, and nothing else: `DurableStore` (`read`/`write`/`delete` under a `StoreKey`) promises that `write` returns only once the bytes are committed, so a caller can treat "the write returned" as "the data is safe". `InMemoryDurableStore` everywhere (**not** crash-safe — tests), `FileChannelDurableStore` on JVM/Android, `NSFileManagerDurableStore` on iOS/macOS, `IndexedDbDurableStore` on wasmJs. Depends on nothing but coroutines + atomicfu — deliberately, since a consumer wanting one durable blob should not inherit a CRDT lattice with it; see `kuilt-store/module.md`. |
| `:kuilt-bolt` | all | Write-only operation archive kept *beside* a live replica rather than inside it: a `Bolt` takes the ops a replica applied, drops the `Compact` that records forgetting, and never merges back into the lattice — so how much history a machine keeps stops being a property of the group (a server's archive holds a year while the phone that fed it holds an hour). `BoltDecorator` is the wiring; a `replay` always ends in one `CleanTail`/`Truncated` verdict. `InMemoryBolt` everywhere, `MappedBolt` (memory-mapped segment files) on JVM/Android, `PosixMappedBolt` on iOS/macOS. Depends on `:kuilt-crdt`; see `kuilt-bolt/module.md`. |
| `:kuilt-quilter` | all | Live CRDT replication over a `Seam`: `Quilter` (renamed from `SeamReplicator`) drives delta-exchange, causal garbage-collection, anti-entropy, and `BoundedCounter` transfer coordination. Depends on `:kuilt-core` + `:kuilt-crdt`. |
| `:kuilt-gossip` | all | Partial-mesh overlay over a `Seam`: `GossipSeam` decorates a full-membership seam so `broadcast` floods only to a roster-derived **k-regular** active-neighbour view (`partialView` / `recommendedActiveViewSize`) and disseminates across the room via eager-flood-with-dedup (`GossipDedup`, bounded O(origins)) backstopped by `Quilter` anti-entropy — the O(N)→O(k) scaling decorator (epic #652; see `docs/gossip-mesh-design.md`). Pairs with `:kuilt-quilter` via `deltaTargets = { gossip.activePeers.value }`. Depends on `:kuilt-core` + `:kuilt-liveness`. |
| `:kuilt-deal` | all | Cryptographically fair card dealing over a `Seam`: `DealSession` (op-based shuffle/strip/quorum-reveal via the SRA commutative-encryption scheme, `SraScheme`) plus `FairRandom` (two-phase commit-reveal seed agreement, no trusted dealer). Depends on `:kuilt-core` + `:kuilt-crdt`. |
| `:kuilt-game` | all | Turn-based game facade over `:kuilt-raft`. Bootstrap entry points return a `GameSession`: `gameNode(seam, voterIds)` (roster-given — Raft elects symmetrically) and `gameHost`/`gameJoin` (appoint-the-host — one host admits joiners to quorum, `DuplicateHostException` on lobby-presence conflict). On top: `TurnSequencer` (propose/commit typed actions) + `IndexedAction` (committed action carrier) + `SpeculativeSequencer` (optimistic apply with deterministic rollback/replay), plus app-data channels over `NamedMux` (`GameSession.appChannel`). |
| `:kuilt-raft` | all | Raft consensus over a `Seam` — leader election + PreVote, log replication, log compaction + chunked `InstallSnapshot`, dynamic membership, linearizable reads (`readIndex()`, §3.6/§3.7) and graceful leadership transfer (`transferLeadership()` via TimeoutNow, §3.10). |
| `:kuilt-cluster` | all (server side JVM/Android) | Server-cluster facade over `:kuilt-raft`: the two-tier overlay topology — a complete-graph voter core plus a sparse learner periphery — packaged as `ServerCluster` (server, JVM/Android via `KtorRoomHost`) and `ClusterClient`/`clusterClient` (client, all targets). Learner→leader propose forwarding with cross-crash exactly-once (`requestId` dedup), multi-relay star topology and cross-relay failover. Depends on `:kuilt-core` + `:kuilt-raft` + `:kuilt-session` (+ `:kuilt-websocket` on JVM/Android). |
| `:kuilt-heddle` | all | Fair-share scheduling of one pooled resource across peers with no central referee: a tamper-evident, mergeable ledger of who was granted what, who delegated it onward, and who spent it, so each group gets the slice it was promised and an idle group's share is lent to a busy one — and it keeps working while the network is partitioned. Depends on `:kuilt-core` + `:kuilt-crdt` + `:kuilt-raft`; see `kuilt-heddle/module.md`. |
| `:kuilt-liveness` | all | Peer-liveness detection over a `Seam`: `PartitionDetector` / `HeartbeatPartitionDetector` / `PartitionEvent` / `HeartbeatConfig`. Depends only on `:kuilt-core`; shared by `:kuilt-session` and (via #594) `:kuilt-game`. |
| `:kuilt-session` | all | Membership-aware `Room` over a `Loom` (`SeamRoom`): admit/identify handshake, roster, reconnect tokens, partition detection. Depends on `:kuilt-liveness`. |
| `:kuilt-stream` | all | Byte-stream → message-link adapter: `framed()` wraps a kotlinx-io `Source`/`Sink` as a `Connection` with 4-byte length-prefix framing + oversize protection (`FrameTooLargeException`). The bridge a *stream* transport crosses to become a fabric; consumed by `:kuilt-tcp`. |

**Fabrics & discovery**

| Module | Targets | Role |
|--------|---------|------|
| `:kuilt-websocket` | all | Ktor WebSocket fabric — the "Far"/relay topology. `KtorClientLoom` everywhere; `KtorServerLoom` + `KtorRoomHost` on JVM/Android only. |
| `:kuilt-tcp` | JVM, Android | Raw TCP fabric (the pluggable-fabric-kit headline). `TcpLoom.host`/`join` adapt a Ktor socket via `:kuilt-stream`'s `framed()` + `handshaking()` into a 2-peer `Seam`; real-IO only (guards against virtual-time construction). |
| `:kuilt-multipeer` | iOS, macOS | Apple Multipeer Connectivity fabric — the "Near"/peer-to-peer topology. Provides `MultipeerRoomHost`. |
| `:kuilt-nw` | all (Apple impl) | Apple Network.framework full-mesh peer-to-peer fabric — every peer advertises, browses and dials, and the redundant double-dial is deduplicated into one link; TLS-PSK derived from `Pattern.roomKey`, which is a required bearer secret (an open session is refused). The replacement for `:kuilt-multipeer`, whose MultipeerConnectivity layer regressed on iOS 26 (MC is also deprecated as of Xcode 27). Native on iOS/macOS, plus a **macOS-only** desktop-JVM bridge over `libkuilt.dylib`. See `kuilt-nw/module.md`. |
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
| `:kuilt-gossip-test` | all | Gossip test support — `inMemoryStarOf(n)` returns a `Star`: a started in-memory star of `GossipSeam`s (a hub plus n clients) with an accept handle (`Star.source`) for admitting a fresh client mid-test. |
| `:kuilt-warp-test` | all | Warp's published test infrastructure: the sandboxed-WASM conformance TCK plus `MultiNodeWarpSim` / `warpSimTest` — the deterministic virtual-time multi-node harness (warp's analogue of `:kuilt-raft-test`'s `MultiNodeRaftSim`; use it, don't hand-roll one). |
| `:kuilt-otel-tap-test` | all | Log-tap test/CI support over `:kuilt-otel-tap` — `awaitLog` / `awaitLogBodyContaining` for live-tailing a tapped device from a test or simulator harness. |
| `:kuilt-scale` | JVM | Scaling/bench harness: `MeteredSeam` + `SeamMetrics`/`ClusterMetrics` message-complexity counters, `ConvergenceTracker`, and `buildInMemoryMesh(n, topology)` (complete graph / ring / star). **Not published** — plain `kotlinJvm`, no `kuilt.publish`, excluded from the BOM. Real-TCP layer is opt-in via `-Pscale.tcp.tests=true`. |

**Observability**

| Module | Targets | Role |
|--------|---------|------|
| `:kuilt-otel` | all | Offline-first OpenTelemetry: record traces, metrics and logs on any target and have them reconcile across devices when connectivity returns, with no duplicates and no data loss (`WarpTelemetry`, `WarpOtlpBridge`, `WarpLogRecordExporter`). CRDT-backed, so re-merging a snapshot is idempotent. Depends on `:kuilt-crdt` + `:kuilt-core` + `:kuilt-store`. |
| `:kuilt-otel-tap` | all | Pull the logs off a device by **joining it as a peer** — a test or CI process connects to a running app over a fabric and reads the offline-first buffer it already keeps (`LogTapClient`). |
| `:kuilt-otel-logging` | all | The uniform capture edge: `installLogCapture` routes an app's existing `kotlin-logging` output into the durable telemetry buffer, through one call on every platform. |
| `:kuilt-otel-logback` | JVM, Android | Catches what *other* libraries log through Logback (frameworks, transitive deps) into the same buffer. |
| `:kuilt-otel-log4j2` | JVM, Android | The same for Log4j2. |
| `:kuilt-otel-sdk` | JVM, Android | Bridges an app's *existing* OpenTelemetry SDK setup into the offline-first buffer — trace-context propagation and export, both additive and optional. |
| `:kuilt-otel-otlp` | all | Forwards the buffered spans/logs/metrics to any OTLP/HTTP collector when the network returns, sending only what the endpoint has not already received. |

**Warp** — the coordination-free distributed scheduler. Pre-1.0 playground: outside the stability surface, API may change.

| Module | Targets | Role |
|--------|---------|------|
| `:kuilt-warp` | all | Spread a pile of work across whoever is connected, with no central boss and no peer doing the same job twice (`WarpNode`). See `docs/warp-vision.md`. |
| `:kuilt-warp-runtime` | all | The sandbox that *runs* code another peer sent you: a walled-off WASM engine with no files, no network, no clock, and no way to run forever. |
| `:kuilt-warp-compiler` | JVM, Android | The real optimizer a **compiler node** runs — implements `:kuilt-warp`'s `WasmOptimizer` seam by exec'ing a bundled `wasm-opt` (Binaryen). JVM/Android only: Apple bans externally-delivered machine code and a browser peer cannot fork a process. |
| `:kuilt-warp-ksp` | JVM (build-time) | The `@WarpOp` symbol processor. Deliberately not a KMP module — a KSP processor always runs on the JVM inside the build, and the code it generates is plain `commonMain`. In-repo modules apply the `kuilt.warp-ops` convention plugin instead of wiring it by hand. |
| `:kuilt-warp-planning` | all | The G4 coordination-cost model and planner over a `Draft` pipeline: `coordinationCost` scores what a monotonicity-aware executor would pay, and `Draft.plan()` rewrites the draft to a fixpoint (via `optimize()`) to minimise it. |
| `:kuilt-warp-ml` | all | Learn one shared model from everybody's data without anybody handing over their data — federated aggregation over the mesh instead of collection on a server. |
| `:kuilt-warp-otel` | all | Records `WarpNode` execution metrics (executions / duplicates / failovers) into a `WarpMetricExporter`. Idempotent under retry: the node's counters and the exporter's SUM series are the same `GCounter`. |
| `:kuilt-warp-heddle` | all | Fair share *for* warp: layers `:kuilt-heddle`'s ledger over warp so "the interactive work gets three times the grid the batch work does" is expressible, without changing how warp picks who runs what. |

**Packaging**

| Artifact | Role |
|----------|------|
| `:kuilt-bom` | A Gradle/Maven platform (`java-platform`) that constrains every kuilt module to one aligned version. Consumers import it once (`implementation(platform("us.tractat.kuilt:kuilt-bom:<v>"))`) and then declare modules without versions. Not a KMP code module. |

Fabric and feature modules depend on `:kuilt-core` (some also on sibling
libraries — e.g. `:kuilt-mdns` → `:kuilt-websocket`, `:kuilt-deal` →
`:kuilt-crdt`). The dependency arrow never points back into `:kuilt-core` — it
must stay free of fabric-specific imports.

**These tables are enforced — `verifyModuleTable` (in the root `build.gradle.kts`, wired into
`check`, and run in the `doc-citations` CI job so a docs-only edit is covered too) fails the build
when a `:kuilt-*` module in `settings.gradle.kts` has no row above, or when a row names a module that
is no longer in the build (#2257).** There is no allowlist — adding a module means adding its row in
the same PR. Two things it leans on that are easy to move by accident: the `## Module structure &
dependency direction` heading, which bounds the scan (renaming it fails the build, deliberately), and
the row shape — a line beginning with the module path in backticks, in a real table rather than a
fenced example, since fenced blocks are skipped. It checks **presence and identity only, never a
row's content**: the prose, the targets and every type name in a row are unpinned, and two invented
type names reached review this way. Grep a symbol before you put it in a row.

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
| Real-socket voter-mesh reconnection smoke (off by default — `ci-required` covers reconnection via the deterministic `VoterMeshReconnectionTest`) | `./gradlew :kuilt-cluster:jvmTest -Pcluster.realsocket.reconnection.tests=true` |
| Lint / static analysis | `./gradlew detektAll` |

**Use `detektAll`, not bare `detekt`.** Plain `detekt` is `NO-SOURCE` in this KMP setup (the per-target tasks have no aggregated source) and reports BUILD SUCCESSFUL without analyzing anything. `detektAll` is the real check — and the one CI runs. "Detekt passed locally" via bare `detekt` is a false green.

**But know what `detektAll` reaches — it is not "everything".** With type resolution it covers `commonMain` + `jvmMain` (plus any `jvmAndAndroidMain` intermediate, folded into `detektJvmMain`), **`androidMain` production source** (via `detektAndroidRelease`, wired in #2334), and `jvmTest` + both `androidUnitTest` variants. It reaches **nothing** in `appleMain`/`appleTest` or the native/wasm source sets — those tasks exist but run **parse-only**, and all four enabled rules require type resolution, so none of them can fire (#2039) — nor `commonTest` (no task at all, #1960), nor `spike/` (no convention plugin). So a green `detektAll` on a PR touching only Apple, `commonTest`, or spike source proves nothing about the changed files; name a real gate instead and compensate with review attention on what detekt would have caught. **One rule is covered from outside detekt**: `forbidNotNullAssertionInUnresolvedSource` in the root build bans `!!` in every source set no type-resolved detekt task reaches — apple/native/wasm and `spike/` — on a grandfathered per-file count ratchet (#2039). It is lexical, so it sees neither a typealias nor a helper, and the other three rules remain unenforced in that tier; `commonTest`/`commonSamples`/`jvmAndAndroidTest` are deliberately outside it, as #1960's fix is to give them a real type-resolved task. `androidMain` is wired to the **release** variant only, deliberately: it is one source set compiled identically into both variants, and release is what `publishLibraryVariants("release")` ships. `forbidUnlintedAndroidMain` in the root build asserts **per file, from the resolved task graph**, that no Android production source escapes — so adding a `src/androidDebug` fails the build with the task to wire, rather than going quietly unlinted the way `androidMain` did for months.

**Verify cache-disabled before auto-merge: `./gradlew :<module>:build detektAll --rerun-tasks`.** Two false greens recur here. (1) `jvmTest` (or a scoped `:module:jvmTest`) does **not** compile the Android variant — a `commonTest` source can compile on JVM yet fail `compileDebugUnitTestKotlinAndroid` (and Kotlin/Native test targets) on a type-inference difference the JVM compiler accepts. CI runs the full `./gradlew build`, so it catches this; your local `jvmTest` won't. (2) Gradle's **build cache** can serve a stale `FROM-CACHE` "success" for a test-compile task whose source is actually broken, so a re-run "passes locally" without executing the failing code. Before enabling auto-merge on a code PR, run the **full module build** with `--rerun-tasks` (add `--no-build-cache` if any test-compile task still shows `FROM-CACHE`) and confirm the tasks are genuinely `EXECUTED`. "Built locally" via `jvmTest` or a cached build is not proof the Android/Native variants compile. (3) A **`:<module>:build`-scoped build is a false green for consensus/runtime *behavior* changes** — even the full *module* build (not just `jvmTest`) skips the downstream `:examples`/`:kuilt-cluster` **E2E cluster tests**, which exercise the whole runtime stack. A change to `:kuilt-raft` consensus *behavior* (election / replication / membership / forwarding) that passes every `:kuilt-raft` test can still break a cluster E2E invariant — e.g. a forward-reaping change broke `ClusterClientMultiClientHardeningE2ETest`'s "no double-apply", entirely invisible to `:kuilt-raft:build`. For any consensus-*behavior* change, run the **full `./gradlew build`** (or at minimum add `:examples:test`), not a module-scoped build.

The mDNS multicast suite is opt-in because it sends real multicast packets; the
`-P` flag is forwarded to JVM tests as a system property and to K/N simulator
tests as the `MDNS_MULTICAST_TESTS` env var (see `kuilt-mdns/build.gradle.kts`).

Absent `-Pcluster.realsocket.reconnection.tests=true`, `WebSocketVoterMeshReconnectionTest`
still compiles but self-skips at runtime, so `./gradlew build` doesn't run it.

## Conventions specific to this repo

- **`explicitApi()` is enforced** (set in the `kuilt.kmp-library` convention
  plugin). Every public declaration needs an explicit visibility modifier or the
  build fails. New public types get `public`.
- **Build logic is centralized** in `build-logic/`: `kuilt.kmp-library` defines
  the standard target set + Android namespace (`us.tractat.kuilt.<module>`);
  `kuilt.publish` wires the in-tree `TigrisStaging` (file://) Maven repo that
  `publish.yml` stages publications into. New modules apply
  `id("kuilt.kmp-library")` and almost nothing else. A module that deliberately
  does **not** — a bench harness, a KSP processor, a demo app — still has to be
  linted, and gets that from one line: **`kuilt.detekt-jvm`** for a plain
  Kotlin/JVM module, **`kuilt.detekt-kmp`** for a plain KMP one (declare it last
  in `plugins { }`, so it applies after the Kotlin plugin). `forbidUnlintedModule`
  in the root build fails on a module with Kotlin source and none of the three.
  A published module needs a third edit beyond `settings.gradle.kts` and its
  module-table row (above): a branch in `moduleDescription()` in
  `build-logic/src/main/kotlin/kuilt.publish.gradle.kts`, the POM description
  Maven Central search and klibs.io show a reader. It has no fallback — the
  `else -> error(...)` arm fires at the eager, configuration-time call site
  (`description.set(moduleDescription(project.name))`), so an omitted module
  fails *before* any task runs, including `verifyModuleTable` itself. Hit for
  real extracting `:kuilt-store` (see #2498).
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
- **A conformance property is only as strong as the weakest failure the REFERENCE implementation can
  reach.** When the in-memory reference makes a failure structurally impossible, nobody writes the
  property for it, the suite goes green, and every real backend has to invent the guard alone. In the
  `:kuilt-bolt` epic (#2240) two workers who never saw each other's code shipped the **identical**
  cross-segment defect, because `InMemoryBolt`'s segments are an in-process list that cannot lose an
  element — and the reference **carried that bug too**, so don't stop at "it can't reach the failure",
  ask whether it would even *detect* it. The remedy is copyable from `BoltConformanceSuite`: a
  **non-nullable** abstract fixture hook per unreachable failure, each KDoc'd with *why* non-nullable
  (an "I cannot reach this state" opt-out moves the vacuity one level up, where it is harder to see),
  and a property that asserts its own **precondition**, so a backend handing back a healthy fixture
  fails loudly instead of passing quietly. Where a state is unreachable for a *correct* reason — an
  in-memory bolt promises no durability, so it can never degrade — use a **two-armed sealed fixture**
  rather than a nullable hook, and state in KDoc what the arms cannot detect. Sweeping every suite in
  the repo for this is #2247.
- **A fixture's CONFIGURATION is a prescription too, and it drifts toward the setting where the
  property cannot fail.** Five instances in that one epic, and **twice the vacuous fixture was written
  by the very PR whose subject was fixture vacuity**: `segmentFrameBytes = 1L` (the one budget at which
  a segment has no pre-allocated zero tail, so the defect under test could not occur); 3 ops against a
  65,536-entry window (cannot distinguish "bounded" from "deferred by `N/M` rounds"); an async budget
  at which nothing ever rolls, so the rigged failure was never reached. Three habits catch it:
  enumerate the fixture's numeric knobs and ask what each one switches **off**; make every arm
  **assert its rig fired** — an unreached rig is green by absence, so count the rig's firings rather
  than inferring them from a side effect ("a roll happened" stays true when the bug under test is
  reintroduced); and read a red's **shape**, not just its presence, since #2244's mutation reddened
  one assertion of six and a reader scanning for "did it red" would have ticked it off. Where a suite
  already pairs subclasses over complementary configurations, **thread the budget through a new hook
  the way the existing hooks do** — hardcoding it collapses every subclass onto one configuration.
- **After fixing anything, ask what the fix itself is now unpinned on.** The same defect recurs one
  level up, and on the `:kuilt-bolt` epic it landed *inside* the fix for the previous instance twice.
  Make it the explicit closing step of every fix and every review: name the property the fix now rests
  on, and whether anything reds when that property breaks. Carrying the question is what caught a fix
  whose obvious implementation would have re-vacuated the very property it repaired.
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
  - **No real-dispatcher defaults.** A factory/helper that owns a scope makes the
    `scope`/dispatcher a **required** parameter — never `= CoroutineScope(Dispatchers.Unconfined)`
    or similar. A default real dispatcher silently decouples the work from `runTest`'s
    virtual clock; a `backgroundScope` (lazy `StandardTestDispatcher`) silently breaks
    tests that assume eager delivery. Both bit us; required injection makes the caller choose.
  - **Production dispatchers are banned in test sources** (`Dispatchers.{Unconfined,Default,IO,Main}`,
    `GlobalScope`), enforced by `forbidProductionDispatcherInTests` in the root build (#1934). The
    rare deliberate real-threading harness (a true-parallelism stress test, a callback-thread
    regression test, a `runBlocking` benchmark) declares itself with a line-tight
    `// ALLOW-realDispatcher: <reason>` on its `import kotlinx.coroutines.Dispatchers`, and the
    reason is mandatory. **`@Suppress("ForbiddenMethodCall")` is not a mechanism** — that detekt
    rule is configured nowhere and never fires (#1329); 21 such annotations had accumulated before
    #1934 swept them.
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
    Exemplars: `Quilter`/`SeamRoom` (lock-guarded). The older `CompositeSeam`/`CompositeLoom`
    `limitedParallelism(1)` confinement is **legacy being migrated to primitives** — do not copy it.

- **Exception discipline — never swallow cancellation.** Bare `runCatching` is banned in any
  `suspend`/coroutine context: it catches `CancellationException` and turns a structured-concurrency
  cancel into a normal `Result`, a silent bug. What replaces it turns on one question — **whose
  cancellation would this be, mine or the callee's?** Mine must propagate. But a callee can *mint*
  one and throw it at me while my job stays perfectly alive: `withTimeout` inside a
  consumer-authored `Seam.close`/`sendTo`/`Loom.weave` throws `TimeoutCancellationException` **to
  its caller** without cancelling that caller. Rethrowing *that* one is #1834, and it is maximally
  silent — the escaping throwable *is* a `CancellationException`, so my coroutine is **cancelled
  rather than failed**: no handler runs, no stack trace, and every obligation behind the guard is
  skipped.

  **`currentCoroutineContext().ensureActive()` is the discriminator.** It throws only when *this*
  job really is cancelled and falls through on a callee-minted one; type cannot tell the two apart,
  which is why `runCatchingCancellable { … }` (`:kuilt-core`) rethrows both. So the full form is
  `try { … } catch (_: Throwable) { currentCoroutineContext().ensureActive(); logger.debug { … } }`,
  and **unshielded, with anything the caller still owes following the guarded call, it is the only
  correct guard.** The trigger is *"does work follow"*, **not** *"is it a loop"*: #1834 first scoped
  itself to two multi-item loops and was wrong about three single-item closes.
  `MeshSeam.closeBestEffort` carries the full argument; `CompositeSeam.reconcile` and `SeamRoom`'s
  admit fan-out writer are the others. There are exactly two legitimate elisions of it:

  - **`runCatchingCancellable`, where a wrong rethrow costs nothing** — a best-effort call with no
    obligation behind it (`runCatchingCancellable { seam.broadcast(frame) }.onFailure { logger.debug { … } }`),
    or one on `sendTo`/`broadcast`/`weave`, which the contract forbids to mint at all — there the
    elision rests on the callee's obligation rather than on consequence. This stays the default for
    ordinary best-effort sends.
  - **A plain `catch` inside `withContext(NonCancellable)`** (#1803, #1824) — the degenerate case:
    my job cannot be cancelled there, so `ensureActive` is dead code and every reachable
    `CancellationException` is necessarily callee-minted. One guard **per** cleanup item; that bans
    `runCatchingCancellable` **and** the hand-written `if (e is CancellationException) throw e`,
    either of which aborts the very cleanup the shield exists to guarantee.
    `NwLoom.discardUnreturnedSeam` and `CompositeSeam.discardOrphanedPly` are the patterns.

  ```kotlin
  // Unshielded, work follows: `ensureActive` is LIVE — my own cancel ends the loop, a minted one doesn't.
  losers.forEach { conn ->
      try { conn.close() } catch (_: Throwable) { currentCoroutineContext().ensureActive() }
  }
  // Shielded: `ensureActive` could never fire, so this is the same guard with the dead branch elided.
  withContext(NonCancellable) {
      seams.forEach { seam ->
          try { seam.close() } catch (failure: Throwable) { logger.debug { "close failed: $failure" } }
      }
  }
  ```

  If a cancellable bound (`withTimeout`/`withTimeoutOrNull`) intervenes *inside* the shield, its
  premise is false at that position — hoist the `try`/`catch` outside the bound rather than
  swallowing within it. **On most of this the prose is the enforcement.** Two `check`-wired root
  guards scan production `*Main` sources lexically, and both are backstops rather than proofs.
  `forbidRunCatchingCancellableUnderNonCancellable` looks for the `runCatchingCancellable` **token**
  inside a shield: it cannot see the hand-written rethrow, and sees neither form when reached
  through a helper called from the shield. `forbidCancellationRethrowAroundBound` (#2292) takes the
  one **unshielded** case where the ambiguity is decidable — a rethrow written *directly around a
  `withTimeout`*, where the cancellation reaching it can only be the bound's own — in both its
  `try`/`catch` and its `runCatchingCancellable` spellings, and clears the site if an earlier
  `catch (…: TimeoutCancellationException)` already handles the expiry by type. Everywhere else
  unshielded the two cancellations really are lexically ambiguous, and one helper hop defeats both
  guards. `ensureActive()` remains the only thing that decides it at runtime.

- **Keep the throwable on a log line. Drop it only where the failure is *routine* and the
  exception's type and message are the whole diagnosis.** The qualifying cases are narrow — an
  unreachable telemetry collector on a best-effort drain, an entry that was never meant to decode —
  and there the trace under it is the same framework frames every time
  (`WarpOtlpBridge.drain`, `HeddleControlPlane.applyEntry` are the two in-tree examples, #1596).
  Where the failure is unexpected, or the throwable came out of the **consumer's own** code, the
  trace *is* the diagnostic: keep it. Interpolate (`"… : ${r.exceptionOrNull()}"`) rather than
  attach when you do drop it — type and message survive, and it stays inside the lazy lambda.

  The cost, recorded so nobody re-derives it as an argument for a broad sweep: on Apple targets the
  **first** stack trace a process materializes pays a one-time symbolizer init that collapses under
  load (~65 ms idle, ~6 s saturated; later traces ~2–3 ms either way, and a throwable-free
  `logger.warn { … }` never symbolizes at all). Because it is **one-time**, no single site can be
  "the expensive one" — trimming a handful removes nothing, and only a near-total sweep would, at
  the price of stack traces everywhere. The real lever is not in this codebase: it is the consuming
  app's `sourceInfoType=libbacktrace` (flat under load, `file:line` preserved), documented in
  `docs/usage.md`. Measured on a debug `.kexe` on a dev Mac under synthetic saturation; unverified
  on a real device in a release build.

- **Debugging bugs a local suite can't see** (hardware/network/contention-only) follows the
  process rules in [`docs/debugging-process.md`](docs/debugging-process.md): don't `closes #N` a
  hardware-reproduced bug until validated against the reproducer (a `FakeSeam`-injected test proves
  the consumer's *reaction*, not the transport's *emission*); after one failed fix round, ship
  **evidence capture** (log identities+`state`, not sizes) before a second hypothesis; and a
  contract-impossible value is a **fork** — probe both the measurement bug and the contract-violation
  bug. Distilled from the #1466 post-mortem.

## Documentation

Two published surfaces, deployed to GitHub Pages by `.github/workflows/docs.yml` on every push to `main`:

- **Dokka API reference** (`https://tractat-us.github.io/kuilt/api/`) — generated by `./gradlew dokkaGenerate`. The root `build.gradle.kts` aggregates all modules into one site under `build/dokka/html/`. Per-module KDoc is in `<module>/module.md`; code examples use `@sample` tags pointing at functions in `<module>/src/commonSamples/kotlin/…` (e.g. `kuilt-core/src/commonSamples/`, `kuilt-crdt/src/commonSamples/`, and `kuilt-quilter/src/commonSamples/`).
- **Writerside guide** (`https://tractat-us.github.io/kuilt/guide/`) — source in `Writerside/` (instance id `kuilt`). CRDT zoo topics are one file per type: `Writerside/topics/crdt-<type>.md`. Each code block is a snippet copied verbatim from a specific test function, cited with an HTML comment `<!-- verbatim from <path>#<symbol> -->`.

### Write top-down: accessible first, technical depth only deeper (REQUIRED)

Documentation must be **meaningful to a non-technical reader** — a student, a product manager,
someone who has never heard of CRDTs, Raft, or consensus — at the **top of every surface**:

- The guide landing page (`Writerside/topics/overview.md`), each section's intro topic, the
  README opening, and the **first paragraph of every page** must read in plain language. Lead with
  *what the thing does for a person* and a concrete example. Short sentences, everyday words.
- **Technical depth — algorithms, type names, wire formats, guarantees, edge cases — appears only
  as the reader digs deeper**: lower on the page, and in the deeper topics. A reader should be able
  to stop reading at any point and never have hit jargon they weren't first eased into.
- **Never open a top-level surface with jargon or a bare definition.** A term like *CRDT*,
  *quorum*, or *delta* may appear only after a plain-language framing, and only deeper in. When you
  must use one up top, define it in one short phrase, then use the plain name.
- **Section names are the plain ones: Network Fabric / Replicated Data / Consensus** — never expand
  to "Conflict-free Replicated Data Types" or "Consensus (Raft)" in guide headings or the TOC
  (`Writerside/kuilt.tree`). The expanded/technical forms belong in body prose, deeper down, once.
- This is a stylistic decision that is easy to lose: an AI- or contributor-authored docs rewrite
  tends to re-derive headings from the technical baseline. When you touch any doc, **re-read it
  top-to-bottom and confirm it still flows accessible → technical** before committing.

The test: could a curious non-engineer read the first screen and understand *what kuilt is for*?
If the first screen needs a CS background, the page is wrong — move the depth down.

### The descent: narrative shape for vision & design docs

Accessible-first (above) is the *rule*; this is the *shape* it takes in a longer narrative doc —
a design doc, an ADR's motivation, a vision page. Exemplar: [`docs/warp-vision.md`](docs/warp-vision.md).
When a doc has room to tell a story, walk the reader **down the mountain**, in this order:

1. **One idea, in plain language** — a single concrete "what if…" a non-engineer gets on the first
   screen.
2. **Recognition** — show the thing is mostly already built; point at the pieces that exist.
3. **The reduction** — let a terrifying list of machinery visibly *collapse* into a few lines of
   surface. The feeling to engineer is **relief**.
4. **The honest seam** — the one place the simplicity is *allowed* to leak, and *why* (the
   constraint, not a missing feature).
5. **The fantasy, last** — the most exciting/speculative capability is dessert, never the opener.

Two devices that carry it:

- **Name the role, reveal the primitive under it.** Every time a low-level type appears, lead with
  the domain term and reveal the kuilt primitive as the thin wrapper: "a `TaskScheduler` *is* the
  `BoundedCounter` equalizer," "a work-queue *is* an `ORSet`." The domain layer reads as
  *vocabulary, not machinery* — and the recognition lands on every mention, not once.
- **Diagrams are the visual spine of the walk**, not decoration. Author them as committed,
  dark-themed SVGs under `docs/images/<topic>/` (they render natively on GitHub and stay diff-able
  text); embed one per beat of the descent.

Why this is written down: an AI- or contributor-authored rewrite re-derives a doc from the
*technical* baseline — leading with type names, lattices, and consensus — and the walk is the first
thing lost. **The walk is the product as much as the API is.** When you touch a narrative doc,
re-read it top-to-bottom and confirm the descent still survives.

### Keeping docs in sync with code

**`@sample` functions are compiled as part of `commonTest`** (wired by the `kuilt.kmp-library` convention plugin — any `src/commonSamples/kotlin/` directory is added to `commonTest` source roots). A typo or API change that breaks a sample breaks the build. Treat sample functions as load-bearing.

**The `@sample` *link* is enforced too — `verifySampleLinks` (in the root `build.gradle.kts`, wired into `check`, and run in the `doc-citations` CI job so a docs-only `module.md` edit is covered) fails the build on a tag Dokka cannot resolve (#2259).** A tag resolves only when it **starts its KDoc line** (written mid-line Dokka does not parse it as a tag at all — no sample, no warning), is **fully qualified** (a bare name never resolves, even in the sample's own package), names a **function**, and lives in the **citing module's own** `src/commonSamples/kotlin`. That last one is the quiet trap: the convention plugin gives each module only its own samples root, so a tag naming a real sample in a *sibling* module compiles, reads correctly, and renders nothing.

When you change public API:
- Update the KDoc on the changed declaration.
- Update (or add) the matching `@sample` function in `src/commonSamples/kotlin/`.
- If the type has a `crdt-<type>.md` Writerside topic, update its inlined snippet so it still matches the source, and update the `<!-- verbatim from … -->` citation if the function was renamed.

When you rename or remove a test function that a Writerside snippet cites, update the `<!-- verbatim from … -->` comment and the inlined code block in the corresponding topic file.

**Citations are enforced — `verifyDocCitations` (in the root `build.gradle.kts`, wired into `check`, and run as its own `doc-citations` CI job so docs-only PRs are covered too) fails the build on a citation that has drifted from, or dangles off, the source it names (#1792).** Two markers, two strengths:

- `<!-- verbatim from <path>#<symbol> -->` — the block must still appear in the cited declaration character-for-character, modulo indentation. Accepted forms: the whole declaration (including its leading `@annotations`), its body with braces stripped, or a *contiguous* run of either (which is what lets one long test back several walkthrough blocks). A citation with no `#symbol` is matched against the whole file.
- `<!-- condensed from <path>#<symbol> -->` — the block is deliberately abridged or reworded; only the path and symbol have to resolve.

**A bare `// …` line marks an omission and keeps the block `verbatim` (#1825).** It is for the shape a contiguous run cannot express — most often a class shell with members left out:

```kotlin
class InMemoryLoomConformanceTest : SeamConformanceSuite() {
    private val loom = InMemoryLoom()
    override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
    // …
}
```

The marker *adds* an assertion — "source was omitted here" — it does not relax one. Each part between markers must still be a contiguous, character-for-character run of the source; the parts must appear in **source order**, must not overlap, and every marker must elide **at least one real line** (so a marker between two adjacent lines is rejected, not waved through). A marker at the start or end of a block, or two in a row, is rejected too — it would assert an omission on one side and nothing on the other. Reach for it instead of `condensed from` whenever the only reason a block isn't verbatim is that you left the middle out.

**A block using the marker must name a `#symbol`.** Without one the block is matched against the whole file, and elision over a whole file would let it draw its parts from two unrelated declarations and present them as one flow — every line real, every line in order, and the block still misrepresenting the source. Naming the declaration bounds the match to it (and buys rename protection on that declaration for free).

So when a block stops being a literal quote — you dropped the source's own comments, trimmed an assertion message, reworded a line — **relabel it `condensed from` rather than leaving a `verbatim from` that lies.** A block that claims to be verbatim and isn't is worse than no citation, because a reader stops checking. Run `./gradlew verifyDocCitations` (about a second) after touching either side.

**Relabel only when the block *cannot* be a literal quote — otherwise re-copy it, or mark the gaps.** `condensed from` is the cheapest way past a red gate and it is a **one-way door**: that block is never content-checked again. Reach for it when the snippet is genuinely illustrative or reworded; not when re-copying is a two-line edit, and **not merely because you left the middle out** — a block that splices non-adjacent chunks, or abridges a class down to the members being discussed, stays `verbatim` with a `// …` at each gap. And relabelling never hides a *rename* — a `condensed` citation whose symbol disappears still fails.

- **Agent cookbook + skill stay in sync with the primitives.** When you add,
  rename, or remove a public primitive a downstream consumer would reach for (a
  fabric, a `Room`/reconnect entry point, a CRDT, a liveness detector, a
  consensus/`GameSession` entry point, a dealing/gossip primitive): (1) add or
  update its symptom→primitive entry in `docs/agent-cookbook.md`, quoting a
  compiled snippet verbatim (`<!-- verbatim from … -->`); and (2) confirm
  `.claude/skills/kuilt-primitives/SKILL.md` still routes to it and its
  `description` still matches how a developer would phrase the need. A new
  primitive with no cookbook entry is the exact failure this surface prevents —
  treat a missing entry as a broken build even though nothing enforces it.

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

**Don't hand-roll a merge poll — run `~/.claude/bin/gh-pr-wait <PR> --arm-auto`.**
It exits on a terminal state (`0` merged, `1` gate failed, `2` conflict, `3`
timeout, `4` closed, `5` blocked) and encodes the traps below. This paragraph
used to describe those traps and expect you to apply them by hand; that failed
twice in one session while landing #1760, which is why the fix is now executable.

**The trap, and why the obvious fix is also wrong.** A drafted-then-readied PR
keeps a STALE `ci-required` FAILURE in `statusCheckRollup`: opening as Draft
skips the `build-jvm` / `build-native` jobs, so the aggregator records a
`FAILURE` for that draft run, and marking the PR ready starts a fresh run while
GitHub leaves **both** entries under the `ci-required` name. Scanning the rollup
for the substring `FAILURE` false-alarms on every iteration. **But keying the
verdict to the newest run id — the fix this file used to recommend — is also
wrong:** for ~30 s after `gh pr ready` the post-ready run does not exist yet, so
the newest row *is* the stale draft row. The sound discriminator is structural:
**a run whose non-aggregator jobs are all `SKIPPED` executed nothing, and is
never a verdict.** (Opening the PR ready when you can avoids the stale run
entirely.)

**Before starting work on an issue, check whether someone already is.** Several
Claude sessions run against this repo concurrently. Two independent sessions
implemented #1556 in parallel on 2026-07-19; the second finished after the first
had already merged and closed the issue, so the whole second implementation was
thrown away. Two cheap checks prevent it:

```bash
gh issue view <N> --json closedByPullRequestsReferences \
  --jq '.closedByPullRequestsReferences[]? | select(.state == "OPEN")'
gh pr list --search "<N> in:body" --state open --json number,title,headRefName
```

An open PR referencing the issue means it is claimed — coordinate or pick
something else. Then **claim it yourself the same way**: open a Draft PR with
`closes #N` before the bulk of the work, so a parallel session sees your claim
too. This matters most for a long-running dispatched worker, which can spend an
hour on work that landed elsewhere in minute five.

**A worktree with uncommitted changes is ACTIVE, not abandoned.** The same
incident began with a session deleting a sibling worktree it read as dead. Both
signals it relied on point the other way: uncommitted edits are the signature of
a session *mid-edit*, and "no PR yet" means work *not yet claimed*, not work
never shipped. Before removing any worktree or branch you did not create, check
`git worktree list` for a lock, look for recent commits, and prefer asking over
deleting. If you delete anyway, commit the uncommitted work first so it survives
as a recoverable object — commits outlive a deleted branch, a dirty working tree
does not.

## Versioning & publishing

The `major.minor` version line lives in `kuiltVersionLine` in `gradle.properties`;
the full version is `<line>.<patch>`. Group is `us.tractat.kuilt`. Two axes move
independently:

- **Snapshots → internal, automatic.** Every push to `main` publishes a Tigris
  snapshot at `${kuiltVersionLine}.0-dev.<run_number>` — the patch is `0` and the
  `-dev.<run_number>` suffix (the CI run number) advances on its own with no PR
  and sorts *below* real releases in semver/Maven order. These are the continuous
  internal builds consumers iterate against; nobody hand-edits them.
- **Releases → external, deliberate, PATCH by default.** A Maven Central release
  is a `v<x.y.z>` tag on the target commit (the tag drives the version). **Default
  to a PATCH bump** (`v0.7.0` → `v0.7.1`) — no `kuiltVersionLine` change needed. A
  **minor** bump (`kuiltVersionLine` `0.7` → `0.8`, a one-line PR before the tag) is
  reserved for a deliberate breaking-API release and is a human call, not a default.
  Pre-1.0 low-ceremony: patch is the normal external cadence.

So: don't pin a concrete `0.4.0`-style number in prose or examples (it dates the
moment a snapshot publishes) — describe the line, and link consumers to the
"latest release". `build.gradle.kts` sets the local default to
`${kuiltVersionLine}.0-dev`.

There are **two publish channels**, by trigger:

**Tigris snapshots — every push to `main`** (plus manual `workflow_dispatch`).
Continuous `${kuiltVersionLine}.0-dev.<run_number>` builds; no tag required. Flow:

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

**Maven Central releases — on a `v<x.y.z>` tag** (a patch by default, or a minor
bump on a deliberate breaking-API release; or a manual dispatch with
`release_to_central=true`), **never** on a plain main push. The
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
