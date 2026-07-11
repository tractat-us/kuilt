# kuilt-nw Network.framework Transport — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `kuilt-nw`, a Network.framework peer-to-peer fabric that implements kuilt's `Loom`/`Seam` contract, to route around the iOS 26 MultipeerConnectivity AWDL-teardown regression, and retire `kuilt-multipeer`.

**Architecture:** Follow the `kuilt-nearby` shape — a thin `NwApi` interface in `commonMain`, all Loom/Seam/handshake/framing logic in `commonMain`, one real cinterop binding in `appleMain`, a macOS-dylib/JNA binding in `jvmMain`, and a role-split `FakeNwApi` that runs the whole capability TCK on the JVM. **Full-mesh** topology (every peer advertises + browses + dials every other; MC-parity semantics — `Swatch.sender` = the arriving connection, no relay, no SPOF; connection-dedup tie-break the one new mechanism). Length-prefix framing reuses `:kuilt-stream`.

**Tech Stack:** Kotlin Multiplatform (iosArm64, iosSimulatorArm64, macosArm64, jvm), Kotlin/Native cinterop against `platform.Network`, kotlinx-coroutines, kotlinx-atomicfu, JNA (jvm bridge), Xcode/SwiftUI (Phase 0 harness only).

## Global Constraints

- **`explicitApi()` is enforced.** Every public declaration needs an explicit visibility modifier. New public types get `public`.
- **New module applies `id("kuilt.kmp-library")`** and almost nothing else; Android namespace is `us.tractat.kuilt.nw`. Package root: `us.tractat.kuilt.nw`.
- **Dependency direction:** `kuilt-nw → kuilt-core` (+ `kuilt-session`, `kuilt-stream`). Never depend back into a fabric; never import fabric-specifics into `kuilt-core`.
- **Coroutine determinism:** a scope-owning type takes an **injected** dispatcher (production default) or inherits `currentCoroutineContext()`; tests inject a test dispatcher. No `Dispatchers.{Unconfined,Default,IO,Main}`/`GlobalScope` in test sources. No real-dispatcher defaults on scope-owning helpers (required injection).
- **Thread-safety by explicit primitives.** Guard shared mutable state with atomicfu `reentrantLock`/atomics or genuinely thread-safe structures. **Never** `Dispatchers.X.limitedParallelism(1)` confinement as a mutex substitute. Every scope-owning type must be correct under a multi-threaded dispatcher.
- **Exception discipline.** Use `runCatchingCancellable { … }` (from `:kuilt-core`) in suspend/coroutine contexts, never bare `runCatching`. Any `catch (Exception|Throwable)` rethrows `CancellationException` before swallowing.
- **Test style:** no `test` prefix (`@Test` suffices); multi-assert tests use `assertAll()`; `@ParameterizedTest` when materially shorter.
- **Verify before merge:** `./gradlew :kuilt-nw:build detektAll --rerun-tasks` (full module build, not `jvmTest` — Android/Native variants differ). For anything touching consensus/cluster behavior run full `./gradlew build`. Use `detektAll`, never bare `detekt`.
- **Multi-node/timer tests** (none expected here, but if added) go through the canonical simulation harness with tight timeouts; never hand-roll.
- **Build env:** `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` first in non-interactive shells.
- **PR posture:** small PRs, auto-merge once `ci-required` green, open PRs *ready* (not draft) to avoid the stale-draft `ci-required` FAILURE.

---

## File Structure (locked)

```
kuilt-nw/
  build.gradle.kts                         # id("kuilt.kmp-library") + macosArm64 sharedLib "kuilt" + manual appleMain wiring
  module.md                                # Dokka module doc (+ appleMain wiring gotcha)
  src/commonMain/kotlin/us/tractat/kuilt/nw/
    NwApi.kt                               # interface: advertise/browse/listen, connection lifecycle, send/recv as Flows; event types
    NwLoom.kt                              # Loom impl; weave() → NwSeam; advertise+browse; mints UUID PeerId; visiblePeers
    NwSeam.kt                              # Seam impl; full mesh; per-peer connection registry; direct broadcast/sendTo; dedup; teardown
    NwConnectMachine.kt                    # per-connection handshake (subscribe-before-trigger, identity exchange, dedup tie-break)
    NwFraming.kt                           # length-prefix framing over the byte stream, reusing :kuilt-stream frame format
    NwRoomHost.kt                          # lobby host surface (Phase 5)
  src/commonMain/kotlin/.../internal/      # internal helpers
  src/appleMain/kotlin/us/tractat/kuilt/nw/
    RealNwApi.kt                           # cinterop against platform.Network (Phase 3)
    internal/NwConnectionBridge.kt         # nw_connection callback→Flow bridge; strong-ref registry
  src/jvmMain/kotlin/us/tractat/kuilt/nw/
    BridgeNwApi.kt                         # JNA over macosArm64 dylib (Phase 4; adapts kuilt-multipeer Bridge*)
    NwNativeLib.kt
  src/macosMain/kotlin/us/tractat/kuilt/nw/bridge/   # dylib entry points (Phase 4)
  src/commonTest/kotlin/us/tractat/kuilt/nw/
    FakeNwApi.kt                           # in-memory NwApi; role-split — N DISTINCT looms on one FakeNwRadio (#1404)
    FakeNwRadio.kt
    NwConformanceTest.kt                   # subclasses SeamConformanceSuite; declares SeamCapabilities
    NwConnectMachineTest.kt
    NwFramingTest.kt
  src/appleTest/kotlin/us/tractat/kuilt/nw/
    NwLoopbackConformanceTest.kt           # REAL RealNwApi over 127.0.0.1 (Phase 3)
    NwConnectionLeakTest.kt                # real-threaded registry-drains-to-empty probe

kuilt-conformance/ (Phase 1 — capability TCK refactor)
  src/commonMain/kotlin/us/tractat/kuilt/conformance/
    SeamCapabilities.kt                    # NEW: the capability declaration
    SeamConformanceSuite.kt                # MODIFY: capabilities() gate on every obligation
    CapabilityMatrix.kt                    # NEW: renders the cross-fabric matrix

spike/ (Phase 0 — throwaway UI, keeper binding; exact home decided in Task 0.1)
  … K/N binding + minimal SwiftUI harness + Xcode project

settings.gradle.kts                        # MODIFY: include(":kuilt-nw")
```

---

## Phase 0 — Connectivity spike (GATES EVERYTHING)

> **Nature:** this phase is a **build-to-learn spike**, not strict red-green TDD. Its "test" is a two-iPhone hardware measurement, and its purpose is to *discover* the exact `platform.Network` cinterop incantations and prove NW P2P beats MC Wi-Fi-off. Keep a running **pain-points log** (`spike/PAINPOINTS.md`) from the first task — it seeds the "implementing a new transport" skill. The K/N binding produced here is **keeper code** that seeds `RealNwApi`; the SwiftUI harness is throwaway.

**Gate (predeclared, see Task 0.5):** Wi-Fi-off ≥ 8/12 connects AND a ~10-min mid-session soak with no teardown, on the iOS 26 ↔ iOS 18 pairing ⇒ proceed. Not met ⇒ **STOP and re-plan** (the 808917 cellular-off-Wi-Fi resolver regression may dominate; a relay/infra-Wi-Fi fallback may be the real answer). Do not start Phase 3+ before this gate passes. (Phase 1 is spike-independent and lands independently; Phase 2's *merge* is sequenced after the gate — see Phase 2.)

### Task 0.1: Spike skeleton + deploy path

**Files:** Create a minimal KMP module/app with an iosArm64 K/N framework embedded in a SwiftUI Xcode project deployable via cable to a physical iPhone. Decide the home: a `spike/` dir in this repo (recommended, self-contained) vs. a scratch location. Create `spike/PAINPOINTS.md`.

- [ ] **Step 1:** Scaffold a K/N `iosArm64` (+ `iosSimulatorArm64`) framework target that produces an embeddable framework, and a minimal SwiftUI app project that links it. Confirm an empty `@CName`/exported Kotlin function is callable from Swift and the app launches on a physical device.
- [ ] **Step 2:** Add two buttons (Host / Join), a connection-state `Text`, and a "Ping" button wired to placeholder Kotlin calls (return dummy state). Deploy to one device; confirm the round-trip of UI→Kotlin→UI.
- [ ] **Step 3:** Add `NSLocalNetworkUsageDescription` + a `NSBonjourServices` entry (`_kuiltnwspike._tcp`) to Info.plist. Deploy; confirm the one-time Local Network prompt appears.
- [ ] **Step 4:** **Keep `spike/` out of the root Gradle graph and out of `detect`'s docs-only classification** — an Xcode-linked K/N app must not break `./gradlew build` on a runner without signing. Either exclude it from `settings.gradle.kts` or gate its inclusion behind a property; confirm a clean `./gradlew build` from repo root is unaffected.
- [ ] **Step 5:** Commit. Log every setup friction in `PAINPOINTS.md` (signing, framework embedding, plist, prompt).

**Acceptance:** app deploys to a physical iPhone, buttons call into Kotlin, Local Network prompt shows, root `./gradlew build` unaffected.

### Task 0.2: Host side — `NWListener` + Bonjour + `includePeerToPeer`

**Files:** K/N binding in the spike module (`SpikeHost.kt`).

- [ ] **Step 1:** From Kotlin/Native, create `nw_parameters` (TCP), set `includePeerToPeer = true` on the **listener params**, create an `nw_listener` advertising the Bonjour service `_kuiltnwspike._tcp`, set the state-changed and new-connection handlers as **Kotlin lambdas** on a dedicated `dispatch_queue_t`, and start it. Surface the listener state to the UI.
- [ ] **Step 2:** On a new inbound connection, **retain it in a strong-ref set** (list/map held by the binding — Network.framework cancels it otherwise), set its state handler, start it, and update the UI peer count.
- [ ] **Step 3:** Deploy to the "host" iPhone; confirm the listener reaches `ready` and the Bonjour service is visible (verify with `dns-sd -B _kuiltnwspike._tcp` from a Mac on the same network for the Wi-Fi-on case first).
- [ ] **Step 4:** Commit; log the exact cinterop signatures + any block-callback friction in `PAINPOINTS.md`.

**Acceptance:** host advertises; a Mac `dns-sd` browse sees the service (Wi-Fi-on baseline).

### Task 0.3: Join side — `NWBrowser` + `NWConnection`

**Files:** `SpikeJoin.kt`.

- [ ] **Step 1:** Create an `nw_browser` with `includePeerToPeer = true` on the **browser params**, browsing `_kuiltnwspike._tcp`; surface discovered endpoints to the UI.
- [ ] **Step 2:** On tapping a discovered endpoint, create an `nw_connection` to it with `includePeerToPeer = true` on the **connection params** (the third place), retain it strongly, set the state handler, and start it. Surface connection state.
- [ ] **Step 3:** Deploy join to the second iPhone (Wi-Fi-on first); confirm both sides reach `ready`.
- [ ] **Step 4:** Commit; log the three-places-for-includePeerToPeer confirmation.

**Acceptance:** two devices connect Wi-Fi-**on** and both reach `ready`.

### Task 0.4: Framed ping round-trip + TLS-PSK

**Files:** `SpikePing.kt`.

- [ ] **Step 1:** Add a 4-byte length-prefix frame writer/reader over `nw_connection_send`/`nw_connection_receive` (the receive is completion-handler based — accumulate until a full frame). Send a "ping" frame on connect; echo it back; show RTT in the UI.
- [ ] **Step 2:** Add TLS to the parameters: `sec_protocol_options_add_pre_shared_key` with a hard-coded PSK on both listener and connection params (before start). Confirm the round-trip still works encrypted.
- [ ] **Step 3:** Deploy; confirm an encrypted ping round-trips Wi-Fi-on. Commit; log the `sec_protocol_options` C-API shape in `PAINPOINTS.md`.

**Acceptance:** encrypted framed ping round-trips between two devices Wi-Fi-on.

### Task 0.5: The gate — Wi-Fi-off connect AND mid-session survival

> **Predeclare the bars before running** (no post-hoc goalposts). The MC failure is a *mid-session* AWDL teardown, so connect rate alone is insufficient — a soak is required.

- [ ] **Step 1 (connect):** Wi-Fi **off** on both phones (Bluetooth on), close together, on the iOS 26 ↔ iOS 18 pairing. Run ≥12 connect attempts; record connect rate + time-to-connect. **Bar: ≥ 8/12 connects, median time-to-connect under a stated bound** (against the 1/12 baseline, 6/12 isn't statistically separable).
- [ ] **Step 2 (survive):** On ≥3 successful connections, hold a **~10-minute session** with periodic pings, Wi-Fi off. **Bar: no mid-session teardown.** A `Connecting→NotConnected` mid-session is a gate FAILURE even if connect rate passed.
- [ ] **Step 3 (cellular probe):** Repeat with a cellular-capable phone off Wi-Fi to specifically exercise the 808917 resolver regression.
- [ ] **Step 4:** Verify simulator P2P support empirically (does browse/connect work between two simulators, or is it physical-only?).
- [ ] **Step 5:** Write results into `docs/nw-transport-design.md` (a "Phase 0 results" section) and `spike/PAINPOINTS.md`. **Decide the gate against the predeclared bars.**

**Acceptance / GATE:** connect bar AND soak bar both met on the iOS 26 ↔ iOS 18 pairing. If yes → Phase 1. If no → stop, write up findings, re-plan with Iain (the 808917 risk may dominate; a relay/infra-Wi-Fi fallback may be the real answer).

---

## Phase 1 — Capability-TCK refactor (foundation; spike-independent)

> Makes transport capabilities **explicit**. Touches every fabric, so it lands before `kuilt-nw`. May proceed in parallel with Phase 0. Each fabric migration is its own reviewable task.

### Task 1.1: `SeamCapabilities` type

**Files:**
- Create: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamCapabilities.kt`
- Test: `kuilt-conformance/src/commonTest/.../SeamCapabilitiesTest.kt`

> Implements #1404. Flags cover **all three** current `@Ignore` escape hatches so every fabric can migrate without inventing flags mid-flight.

**Interfaces — Produces:**
```kotlin
public data class SeamCapabilities(
    val ordersDelivery: Boolean,            // FIFO to a single collector
    val reportsPeerLoss: Boolean,           // peer-drop reflected in peers/state
    val terminatesIncomingOnClose: Boolean, // incoming completes when Torn   (hatch 1, WebRTC #335)
    val staysTornAfterClose: Boolean,       // Torn terminal under churn       (hatch 2)
    val throwsOnSendToTorn: Boolean,        // send on a Torn seam throws      (hatch 3, #1390)
    val supportsSendTo: Boolean,            // directed send DELIVERS; absent peer → PeerNotConnected
    val securesTransport: Boolean,          // encrypted on the wire (honest — see TLS-PSK threat model)
    val meshDelivery: Boolean,              // peer↔peer delivery with no relay hop
) {
    public companion object {
        // A fully-featured direct-mesh fabric — most fabrics start here and flip individual flags off.
        public val FULL: SeamCapabilities
    }
}
```

- [ ] **Step 1:** Write a test asserting `SeamCapabilities.FULL` has every flag `true`.
- [ ] **Step 2:** Run it; expect FAIL (unresolved).
- [ ] **Step 3:** Implement the data class + `FULL`.
- [ ] **Step 4:** Run; expect PASS.
- [ ] **Step 5:** Commit: `feat(conformance): SeamCapabilities declaration (#1404)`.

### Task 1.2: Gate the suite on `capabilities()`

**Files:**
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamConformanceSuite.kt`

**Interfaces — Produces:** `public abstract fun capabilities(): SeamCapabilities` on the suite, plus `public abstract fun capabilityGaps(): Map<String, String>` (capability name → issue URL) for every `false` flag.

> **Critical (from review):** common `kotlin-test` has **no** skip/assume API — an obligation that early-returns reports **PASS**, which is *worse* than a JVM-visible `@Ignore`. So gaps are NOT expressed by skipping a test. Instead: (a) each obligation for a supported capability runs and asserts as today; (b) a supported obligation MUST NOT be gated behind an unrelated flag; (c) the visibility artifact is the rendered matrix (Task 1.8) — a `false` capability with **no** issue URL in `capabilityGaps()` **fails a suite meta-test**. The gap is loud, not silent.

- [ ] **Step 1:** Add abstract `capabilities()` + `capabilityGaps()`. Map each capability-specific obligation to its flag: `incomingCompletesWhenSeamCloses`↔`terminatesIncomingOnClose`, `stateStaysTornAfterClose`↔`staysTornAfterClose`, `sendOnTornSeamThrows`↔`throwsOnSendToTorn`. Add ONE new obligation: `sendToDeliversToNamedPeer` (gated on `supportsSendTo`). Positive multi-peer coverage does NOT go in this suite — ADR-001 fixes it at a 2-Loom fixture, and a strictly-2-peer fabric may legitimately declare `meshDelivery = true` (Task 1.3–1.7), so a `meshDelivery`-gated 3-peer obligation here is structurally unsatisfiable. N-peer positive coverage lives in the existing `MeshConformanceSuite` (roster convergence, broadcast reach + sender attribution, directed routing, leave, dial dedup). Instead: KDoc `meshDelivery` with the coupling rule — a `meshDelivery = true` fabric supporting ≥3 peers MUST also subclass `MeshConformanceSuite` — and enforce visibility in Task 1.8: `MatrixEntry` gains `meshEvidence` (mesh-suite name, or an explicit 2-peer vacuity note); `meshDelivery = true` with no `meshEvidence` is a render error.
- [ ] **Step 2:** **Pin the ungated core** — obligations 1–9b (host yields usable seam, `broadcastFromHostDeliversToJoinedPeer`, order-preserved, peers≥2, close-idempotent, availability, Woven states) are ungated; add a suite meta-test asserting no capability flag can skip them.
- [ ] **Step 3:** Make `capabilities()` abstract (no `FULL` default — every fabric declares intentionally). Add a meta-test: every `false` in `capabilities()` has a key in `capabilityGaps()`. Update in-tree `InMemoryLoomConformanceTest` + `DelayedWovenLoomTest` to declare `FULL` (empty gaps).
- [ ] **Step 4:** Run `:kuilt-conformance:build`; expect PASS (in-tree suites declare FULL). Commit: `refactor(conformance): declare capabilities + gaps; add positive/3-peer obligations (#1404)`.

### Task 1.3–1.7: Migrate each existing fabric to declare capabilities

One task per fabric conformance test (`InMemory`, `websocket`, `mdns`, `webrtc`, `multipeer`, `nearby`, `tcp`, gossip/quilter seams as applicable). Each: replace `@Ignore` overrides with a `capabilities()` + `capabilityGaps()` declaration reflecting reality (e.g. WebRTC `terminatesIncomingOnClose = false` #335; multipeer-JVM-bridge / gossip `throwsOnSendToTorn = false` #1390; a relay/hub fabric like websocket with 3+ peers is `meshDelivery = false` — frames traverse the server; a strictly 2-peer fabric may declare `meshDelivery = true` since it has no third peer to relay to). Every `false` cell needs an issue URL. Run that module's `:build`; commit per fabric.

- [ ] Per fabric: **Step 1** declare `capabilities()`, delete the corresponding `@Ignore`/override; **Step 2** run `:<module>:build --rerun-tasks`; **Step 3** commit `refactor(<fabric>): declare SeamCapabilities`.

### Task 1.8: Capability matrix renderer

**Files:** Create `kuilt-conformance/.../CapabilityMatrix.kt` + a test. This is the **visibility artifact** (Task 1.2) — buildable in `commonMain`, so gaps are loud on every platform.

- [ ] **Step 1:** Test: given fabrics with differing flags + gap issue-links, `renderMatrix(...)` produces a stable markdown table (rows = fabrics, columns = capabilities, ✓ for true, an issue-linked `–` for a declared gap). A `false` flag whose fabric entry lacks a gap URL is a render error.
- [ ] **Step 2:** Run; expect FAIL.
- [ ] **Step 3:** Implement `public fun renderMatrix(entries: List<MatrixEntry>): String` where `MatrixEntry(fabric, SeamCapabilities, gaps: Map<String,String>)`.
- [ ] **Step 4:** Run; expect PASS. Commit `feat(conformance): capability matrix renderer (#1404)` — the artifact the new-transport skill points at.

---

## Phase 2 — Transport core (commonMain; spike-independent for logic)

> **Spike-independence & merge sequencing:** the logic above `NwApi` and the `FakeNwApi` need no real Network.framework, so the *code* can be written any time after Phase 1. But its **merge is sequenced after the Phase 0 gate passes** — NOT run in parallel behind a prose "don't-merge" hold, which is culturally fragile against this repo's auto-merge posture and invites the duplicate-fix collision seen before. Simplest: don't open Phase 2 PRs until the gate is green (it's days). Phase 1 (the TCK foundation) is independent and lands regardless of the gate.

### Task 2.1: Module skeleton

**Files:** Create `kuilt-nw/build.gradle.kts` (copy `kuilt-multipeer`'s manual `appleMain` wiring + macosArm64 `sharedLib`), `kuilt-nw/module.md`, add `include(":kuilt-nw")` to `settings.gradle.kts`.

- [ ] **Step 1:** Create the build file with `id("kuilt.kmp-library")`, deps `api(project(":kuilt-core"))` + `implementation(project(":kuilt-session"))` + `implementation(project(":kuilt-stream"))` + coroutines + kotlin-logging; manual `appleMain`/`macosMain` + `appleTest` source-set wiring mirroring `kuilt-multipeer`.
- [ ] **Step 2:** Run `./gradlew :kuilt-nw:build`; expect PASS (empty module compiles on all targets — watch the Dokka `module.md` gotcha for the first appleMain source).
- [ ] **Step 3:** Commit `feat(nw): kuilt-nw module skeleton`.

### Task 2.2: `NwApi` interface + event types

**Files:** Create `NwApi.kt`. Model on `NearbyApi` but stream-oriented + multi-connection.

**Interfaces — Produces:**
```kotlin
public interface NwApi {
    public fun availability(): FabricAvailability
    // host role
    public suspend fun startListening(serviceName: String, serviceType: String)
    public suspend fun stopListening()
    // join role
    public suspend fun startBrowsing(serviceType: String)
    public suspend fun stopBrowsing()
    public suspend fun connect(endpoint: NwEndpoint)            // dial a discovered endpoint
    public suspend fun disconnect(connectionId: NwConnectionId)
    // data (byte stream — framing is above this layer)
    public suspend fun send(connectionId: NwConnectionId, bytes: ByteArray)
    // events
    public val endpointFound: Flow<NwEndpoint>                  // browse
    public val connectionOpened: Flow<NwConnectionOpened>      // accepted (host) or dialled (join)
    public val bytesReceived: Flow<NwBytesReceived>
    public val connectionClosed: Flow<NwConnectionClosed>
}
// value types: NwEndpoint(id, serviceName), NwConnectionId(String),
// NwConnectionOpened(connectionId, endpoint?), NwBytesReceived(connectionId, bytes),
// NwConnectionClosed(connectionId, reason?)
```

- [ ] Steps: write the interface + value types (with `ByteArray` equals/hashCode override on `NwBytesReceived` like Nearby's `PayloadReceived`); `:kuilt-nw:build`; commit `feat(nw): NwApi surface`.

### Task 2.3: `NwFraming` — length-prefix framing

**Files:** Create `NwFraming.kt` + `NwFramingTest.kt`. Reuse `:kuilt-stream` frame format (4-byte big-endian length prefix, `FrameTooLargeException`).

**Interfaces — Produces:** a `NwFramer` that accepts arbitrary byte chunks from `bytesReceived` and emits complete frames (`ByteArray`), plus `encodeFrame(payload): ByteArray`. Bounded max frame size; oversize → `FrameTooLargeException`.

- [ ] **Step 1:** Tests: (a) a payload encoded then fed back byte-by-byte decodes to the identical payload; (b) two concatenated frames split correctly; (c) a length prefix exceeding the cap throws `FrameTooLargeException`; (d) a partial frame yields nothing until complete. Use `assertAll()`.
- [ ] **Step 2:** Run; expect FAIL.
- [ ] **Step 3:** Implement `NwFramer` (mirror `:kuilt-stream`'s `framed()` prefix logic; prefer reusing its constants/exception).
- [ ] **Step 4:** Run; expect PASS. Commit `feat(nw): length-prefix framing over the byte stream`.

### Task 2.4: `NwConnectMachine` — per-connection handshake + dedup

**Files:** Create `NwConnectMachine.kt` + `NwConnectMachineTest.kt`. Mirror `nearby/ConnectStateMachine` discipline: subscribe-before-trigger (UNDISPATCHED collectors), stable-identity exchange as the first framed message (endpoint id is not a stable `PeerId`).

**PeerId (fixes #1405):** `PeerId` is a **collision-resistant random** minted once per `NwLoom` (NOT Nearby's per-loom counter, which collides across devices), or caller-injected for reconnect continuity. The `NwLoom` owns identity; the machine just exchanges it.

**Interfaces — Produces:** `internal class NwConnectMachine(selfId, api, framer, ...)` with `suspend fun run(scope, connectionId, trigger): NwLink` returning `NwLink(connectionId, remotePeerId)`. Resolves only once the connection is open AND the remote's identity frame has arrived. Exposes the resolved `remotePeerId` so `NwSeam` can run the **dedup tie-break** (lower-`PeerId` dialer wins) when two connections resolve to the same remote.

- [ ] **Step 1:** Tests against a tiny local fake: identity exchange completes and yields the remote `PeerId`; a connection-closed before identity fails with a typed exception; subscribe-before-trigger holds (no lost first frame under `StandardTestDispatcher`); two `NwLoom` instances mint **distinct** `PeerId`s (cross-loom uniqueness — the assertion #1405 lacked).
- [ ] **Step 2–4:** implement; run; commit `feat(nw): per-connection identity handshake + UUID PeerId`.

### Task 2.5: `NwSeam` — full-mesh seam, connection registry, teardown

**Files:** Create `NwSeam.kt`. The load-bearing correctness file. **Mesh, not star** — every peer holds one direct connection to every other peer, so there is NO relay and NO origin-stamping envelope: `Swatch.sender` is simply the `PeerId` of the connection a frame arrived on (MC-parity, like `MCSessionLink`).

**Interfaces — Produces:** `internal class NwSeam(selfId, api, scope, policy, ...) : Seam`. Owns:
- `peers: StateFlow<Set<PeerId>>` (always includes `selfId`; = `selfId` + every directly-connected peer, converging as connections open/close), `state: StateFlow<SeamState>` (Weaving→Woven on first peer; Torn on close), `incoming: Flow<Swatch>` (via `Spool`, fed through a bounded staging channel + single drain so a slow local consumer never wedges the receive path).
- A **connection registry**: `PeerId → NwConnectionId`, guarded by an atomicfu `reentrantLock` (suspend calls AND all `api.*` calls kept **outside** the locked section — handlers fire on the GCD queue and can re-enter). `broadcast` fans `send` across ALL registry connections **directly** (no relay). `sendTo(peer)` sends on that peer's **direct** connection; genuinely-absent peer → `PeerNotConnected`.
- **Connection dedup:** when a `connectionOpened` resolves its remote `PeerId` and a registry entry already exists for that `PeerId` (double-dial), keep the connection whose dialer has the **lower `PeerId`** (both sides compute the same winner) and `disconnect` the loser; the loser's teardown must not evict the winner (guard on connectionId identity).
- **Deterministic teardown:** on `connectionClosed`, remove the peer + drop the registry entry; on `close(reason)`, set a `closing` flag, then `Torn` (terminal — never clobbered by a later writer), disconnect all connections, close the spool/scope. A `closing`-driven local close reports `CloseReason.Normal`, not `RemoteRequested`. All best-effort sends/teardown use `runCatchingCancellable`.

- [ ] **Step 1:** Unit tests (with the **role-split** `FakeNwApi`, Task 2.6): in a 3-peer mesh, a broadcast from peer A reaches B and C with `sender == A` (NOT a relay hub); `sendTo(C)` from A delivers directly; `sendTo` a genuinely-absent peer throws `PeerNotConnected`; a simulated double-dial collapses to one connection with the deterministic winner; `peers` on every node converges to all three; close drives `Torn`, `incoming` completes, registry empties; `Torn` stays `Torn` under post-close churn.
- [ ] **Step 2–4:** implement; run; commit `feat(nw): full-mesh NwSeam with connection registry + dedup + teardown`.

### Task 2.6: `FakeNwApi` / `FakeNwRadio`

**Files:** Create `FakeNwApi.kt` + `FakeNwRadio.kt` in `commonTest`. **Role-split (fixes #1404):** the fake radio routes between **N *distinct* `NwLoom`/`NwApi` instances** — one per simulated device — NOT the same-loom-twice `(loom, loom)` pattern. This makes the in-process shared-StateFlow crutch structurally impossible, so cross-device roster/identity/dedup bugs are visible on the JVM. Emit-directly on the caller's coroutine (no private scope) so all work runs under `runTest`'s virtual clock.

- [ ] **Step 1:** Implement the shared `FakeNwRadio` that N `FakeNwApi`s register with; it matches advertisers↔browsers, opens connections on both endpoints, routes bytes between connection endpoints, and routes closes. Each `FakeNwApi` is a distinct device.
- [ ] **Step 2:** A smoke test stands up **3 distinct `NwLoom(FakeNwApi(...))`** on one radio, connects them into a mesh, and asserts every node's `peers` converges to all three. Commit `test(nw): role-split in-memory NwApi fake (N distinct looms)`.

### Task 2.7: `NwLoom` + conformance

**Files:** Create `NwLoom.kt`, `NwConformanceTest.kt`.

**Interfaces — Produces:** `public class NwLoom(api: NwApi, serviceType: String, selfId: PeerId = <fresh UUID-grade>, ...) : Loom` with `weave(Rendezvous)` → `Rendezvous.New` defines the session (service name from the pattern) + advertise+browse; `Rendezvous.Existing` joins the same service + advertise+browse (every peer does both — mesh). Mints the loom's `PeerId`. `visiblePeers: StateFlow<Set<NwEndpoint>>` for the lobby (Phase 5 consumes it).

- [ ] **Step 1:** `NwConformanceTest : SeamConformanceSuite()` with `capabilities()` declaring `meshDelivery = true` (earned — direct connections, no relay), `supportsSendTo = true`, and the honest `securesTransport` from the TLS-PSK decision. `newLoomPair()` returns **two distinct** `NwLoom` instances wired through one role-split `FakeNwRadio` (NOT the same loom twice). The suite runs on JVM.
- [ ] **Step 2:** Run `./gradlew :kuilt-nw:jvmTest`; iterate until every obligation passes, including the new positive `sendToDeliversToNamedPeer` and an `NwMeshConformanceTest : MeshConformanceSuite()` over the role-split `FakeNwRadio` (absorbing Task 2.6 Step 2's 3-loom smoke test).
- [ ] **Step 3:** Add the **weaving-window harness** (a `DelayedWovenLoom` equivalent) proving frames sent during kuilt-nw's multi-second real `Weaving` window are not dropped — the suite's KDoc requires async radio fabrics to do this.
- [ ] **Step 4:** Run full `:kuilt-nw:build --rerun-tasks`. Commit `feat(nw): NwLoom + role-split JVM capability conformance + weaving harness`.

---

## Phase 3 — appleMain `RealNwApi` (cinterop; DETAIL AFTER PHASE 0)

> **Re-plan gate:** expand this phase into bite-sized tasks **after** the Phase 0 spike, using the exact cinterop signatures + `PAINPOINTS.md` it produced. The spike's `SpikeHost`/`SpikeJoin`/`SpikePing` bindings are lifted and refactored to implement `NwApi`.

**Deliverable:** `RealNwApi` in `appleMain` implementing `NwApi` against `platform.Network` — every peer runs `NWListener`+Bonjour+`includePeerToPeer` (advertise) AND `NWBrowser` (discover) AND dials `NWConnection`s (mesh), TLS-PSK, byte send/receive on a `dispatch_queue_t`, callback→`Flow` via `callbackFlow`. `NwConnectionBridge` owns the **strong-ref connection registry** (retain on open, `nw_connection_cancel` then drop on close — cancel-first-then-release).

**Test obligations (must exist before merge):**
- `NwLoopbackConformanceTest : SeamConformanceSuite()` — the **real** `RealNwApi` over `127.0.0.1` (`requiredLocalEndpoint`, no `includePeerToPeer`, exempt from Local Network Privacy), **TLS-PSK enabled** so the `sec_protocol_options` C-API path is CI-covered, running the full capability TCK on the CI macOS runner (`appleTest`/`macosArm64Test`).
- `NwConnectionLeakTest` — a real-threaded probe (gated like `-Pconcurrency.stress.tests`) that opens/closes many connections on a multi-threaded dispatcher and asserts the registry drains to empty (the reference-management stress check).

**Honest loopback coverage (document in `module.md`):** loopback covers send/receive/framing/cancel plumbing, the registry + teardown, and the cinterop of the connection surface. It does **NOT** cover `NWBrowser`/Bonjour discovery (**zero** automated coverage below hardware — only Phase 0 + tier 3 prove it), `includePeerToPeer`, AWDL, TLS over a real P2P path, the Local-Network-Privacy denial path, or IPv6-required behavior.

**Checklist items to encode (from research + review):** `includePeerToPeer` in all three places; IPv6 available (no IPv4-only); **strong-ref every `NWConnection`, cancel-first-then-drop**; **no `nw_*` call under the registry lock** (handlers re-enter on the GCD queue → self-deadlock); a **`closing` flag** so a local cancel reports `CloseReason.Normal` not `RemoteRequested` (precedent: `MCSessionLink`); `Torn` terminal; `runCatchingCancellable` throughout; the TLS-PSK **threat-model decision** (join-code-derived vs. advertised-tag-derived — sets the honest `securesTransport` value) resolved here if not in Phase 2; Info.plist keys documented in `module.md`.

---

## Phase 4 — macOS dylib + JVM bridge (DETAIL AFTER PHASE 3)

> **Re-plan gate:** expand after Phase 3. Adapts `kuilt-multipeer`'s `Bridge*` scaffolding (`Bridge.kt`, `BridgeBrowser/Client/Host/Runtime`, `MultipeerNativeLib` JNA + `packageMacosNatives`).

**Deliverable:** `BridgeNwApi` (`jvmMain`) implementing `NwApi` via JNA over a `macosArm64` dylib that wraps `RealNwApi`, so a macOS-desktop JVM can host/join. `availability()` → `Unavailable` on non-macOS JVM. Reuse the `packageMacosNatives`/`linkReleaseSharedMacosArm64` packaging from `kuilt-multipeer`'s build.

**Test obligations:** the existing JVM conformance path (cross-process probe pattern) exercises the bridge; a `NwCrossProcessProbe` mirroring `MultipeerCrossProcessProbe` for manual macOS↔iPhone bisection.

---

## Phase 5 — Lobby discovery surface (DETAIL AFTER PHASE 2)

> Can be detailed once `NwLoom.visiblePeers` exists (Phase 2). Mirrors `MultipeerPeerLinkFactory.visiblePeers` + `MultipeerRoomHost`.

**Deliverable:** `NwRoomHost` + the reactive `visiblePeers: StateFlow<Set<…>>` contract, shaped to match the Multipeer lobby types so the consuming app swaps `MultipeerPeerLinkFactory` → `NwLoom` and `MultipeerRoomHost` → `NwRoomHost` mechanically. `RoomConformanceSuite` coverage where applicable.

---

## Phase 6 — Hardware validation + deprecate MC (DETAIL AFTER PHASE 3)

**Deliverable:**
- `-Pnw.realnet.tests`-gated two-device test (reuse the Phase 0 harness + `NwCrossProcessProbe`), recording connect rate + time-to-connect **and a mid-session soak** vs. the MC baseline (same connect + survive bars as the Phase 0 gate). This is the acceptance gate from the issue.
- Mark `kuilt-multipeer` public API `@Deprecated("… migrate to kuilt-nw …", ReplaceWith(...))`. File the consumer-migration notice (breaking-change warning) at this point.

---

## Phase 7 — Retire kuilt-multipeer (DETAIL AFTER DOWNSTREAM VALIDATION)

**Deliverable:** delete the `kuilt-multipeer` module (+ its dylib bridge) and its `settings.gradle.kts` include the moment downstream validation confirms `kuilt-nw` works in the consuming app. Update the module table in `CLAUDE.md` and any docs referencing Multipeer. Net dylib surface = one (`kuilt-nw`). **Gate:** downstream validation landed; not a release cycle.

---

## Side deliverable — the "implementing a new transport" skill

Authored **after** Phase 6, from `spike/PAINPOINTS.md` + the real friction logged through Phases 0–6. Spine = the capability TCK: "declare your `SeamCapabilities`, make each supported obligation green, document each gap." Records the cinterop / memory-model / three-place-`includePeerToPeer` / reference-leak sharp edges this project hit. Not a code PR — a skill authored via `superpowers:writing-skills`.

---

## Self-review notes

- **Spec coverage:** every spec section maps to a phase — architecture→P2, framing→2.3, topology/registry→2.5+3, cinterop→P0+P3, capability TCK→P1, lobby→P5, three test tiers→2.7(JVM)/3(loopback)/6(hardware)+leak probe, deprecate/delete→P6/P7, skill→side deliverable, risks→P0 gate.
- **Deferred detail is intentional, not placeholder:** Phases 3/4/6/7 are milestone-level **by design** — their bite-sized steps depend on Phase 0's discovered cinterop signatures and Phase 1's landed TCK. Each carries a locked deliverable, interfaces, and test obligations, and an explicit re-plan gate. Phases 0/1/2 are fully actionable now.
- **Type consistency:** `NwApi`/`NwEndpoint`/`NwConnectionId`/`NwConnectionOpened`/`NwBytesReceived`/`NwConnectionClosed`/`NwLink`/`NwConnectMachine`/`NwSeam`/`NwLoom`/`SeamCapabilities` used consistently across tasks.
