# kuilt-nw Phase 3 — `RealNwApi` (appleMain cinterop) — task breakdown

_Re-plan of the parent plan's Phase 3 (`docs/superpowers/plans/2026-07-11-kuilt-nw-transport.md`),
expanded after the Phase-0 spike using its exact cinterop signatures (`spike/src/appleMain/kotlin/spike/nw/SpikeNw.kt`)
and `spike/PAINPOINTS.md`. Written 2026-07-11 ET. Drives `superpowers:subagent-driven-development`._

## Goal

Implement `RealNwApi` in `kuilt-nw/src/appleMain` — the real Network.framework binding behind the
`NwApi` interface (`kuilt-nw/src/commonMain/.../NwApi.kt`) that `FakeNwApi` fakes on JVM. Every peer
advertises (`NWListener` + Bonjour + `includePeerToPeer`), discovers (`NWBrowser`), and dials
(`NWConnection`) — a symmetric full mesh — with **TLS-PSK** on the wire. Prove it on CI with a
loopback conformance run that flips `securesTransport = true` and closes #1412.

## What already exists (do not rebuild)

- `NwApi` surface + all event DTOs (`NwEvents.kt`), `NwSeam`, `NwLoom`, `NwFraming`, `NwHello`,
  `NwPeerId` — all in `commonMain`, all done, all tested on JVM via `FakeNwApi`/`FakeNwRadio`.
- `appleMain`/`macosMain`/`appleTest` source sets + the `macosArm64` `sharedLib` + `packageMacosNatives`
  task are **already hand-wired** in `kuilt-nw/build.gradle.kts` (Dokka appleMain gotcha already
  dodged — appleMain is a real source set). Phase 3 adds files under `src/appleMain`, no build wiring.
- The full cinterop path is **proven to compile and round-trip on-device** in the spike:
  listener/browser/connection + `dispatch_queue_create` + TLS-PSK (`sec_protocol_options_add_pre_shared_key`)
  + `nw_content_context_create` (the `NW_CONNECTION_*_CONTEXT` constants mis-bridge under K/N — create
  the context explicitly). Lift `SpikeNw.kt`, do not re-derive.

## Blocking decision (resolve BEFORE Task 3.3) — TLS-PSK threat model

The spike used a hardcoded PSK. Real derivation sets whether `securesTransport = true` is HONEST.
Two options; the choice also determines whether the #1414 session-scoping fix folds in here:

- **(A) advertised-tag-derived** — PSK from the advertised serviceName/tag. A co-located device that
  can browse Bonjour can derive it ⇒ `securesTransport = true` would be dishonest against the most
  relevant adversary.
- **(B) join-code / Tag-as-bearer-secret** — PSK from a secret NOT advertised (kuilt's `Tag` treated
  as a bearer credential; serviceType stays public for discovery). Honest `securesTransport = true`,
  and the same secret can scope membership (folds in #1414).

**DECISION (Iain + Fable, 2026-07-11): (B), implemented as `roomKey`-as-bearer-secret.** No kuilt-core
contract change — the slot already exists: `Pattern.roomKey` (host) / `Tag.roomKey` (joiner) are already
an out-of-band admission capability that kuilt never transmits between devices. Deriving the PSK from
`roomKey` upgrades the existing honor-system "targets must agree" admission into a cryptographic check,
and closes #1414 at the TLS layer for free (different `roomKey` ⇒ different PSK ⇒ cross-session
double-dial fails the handshake ⇒ meshes cannot merge). Concrete mechanics folded into Task 3.3 below.

## Tasks (bite-sized; one general-purpose worker each, `isolation: "worktree"`, accumulating on `nw-phase3`)

### 3.1 — `NwConnectionBridge` + strong-ref registry (appleMain) — LOAD-BEARING
The connection-lifecycle unit, isolated from discovery so the memory management is reviewable alone.
- A registry keyed by `NwConnectionId` holding a **strong ref** to each live `nw_connection_t`
  (Network.framework cancels a connection whose last ref drops — `SpikeNw.connections`).
- Open → retain + `nw_connection_set_queue` + state-changed handler + `nw_connection_start`.
- Close → **cancel-first-then-drop**: `nw_connection_cancel` THEN remove the strong ref.
- **No `nw_*` call under the registry lock** — handlers re-enter on the GCD queue ⇒ self-deadlock.
  Guard the map with an atomicfu `reentrantLock`; take the ref out under the lock, call `nw_*` outside.
- `send` over an open connection (`nw_connection_send` + explicit `nw_content_context_create`, NOT the
  `NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT` constant — it mis-bridges); receive loop re-arming only on
  no-error (`SpikeNw.receiveLoop`). `dispatch_data` ↔ `ByteArray` helpers lifted from the spike.
- `closing` flag per connection so a LOCAL cancel maps to `CloseReason.Normal`, not `RemoteRequested`
  (precedent: `MCSessionLink`). `runCatchingCancellable` throughout.
- Emits onto the four `NwApi` flows — **single-collection**: one callback → one `MutableSharedFlow`;
  N collectors would duplicate delivery. Mirror `FakeNwApi`'s no-replay + `extraBufferCapacity` shape.

### 3.2 — `RealNwApi` host / browse / dial (appleMain)
- `startListening(serviceName, serviceType)` — `nw_listener_create(secureParams())` + Bonjour advertise
  descriptor + `includePeerToPeer` + new-connection handler → `bridge.accept(...)` → `connectionOpened`.
- `startBrowsing(serviceType)` — `nw_browser_create` + browse-results handler → `endpointFound`
  (`nw_browse_result_copy_endpoint`; MUST browse to bring up AWDL — do not synthesize a name).
- `connect(endpoint)` — `nw_connection_create(ep, secureParams())` → `bridge.dial(...)`.
- `stopListening`/`stopBrowsing`/`disconnect`/`send` → delegate to the listener/browser/bridge.
- `availability()` — `FabricAvailability.Available` on Apple targets.
- Everything on one serial `dispatch_queue_create`; callback→Flow via the bridge's flows.

### 3.3 — TLS-PSK from `roomKey` + `NwTag` + #1414 fix (appleMain + commonMain) — DECIDED (B)
- `secureParams(psk, pskId)` = `nw_parameters_create_secure_tcp` with the `configure_tls` block installing
  the PSK via `nw_tls_copy_sec_protocol_options` + `sec_protocol_options_add_pre_shared_key` (lifted; PSK
  path proven in the spike). `nw_parameters_set_include_peer_to_peer(params, true)`.
- **Derive, never use raw bytes.** `PSK = HKDF-SHA256(ikm = roomKey, salt = "kuilt-nw|" + serviceType,
  info = "tls-psk|v1")`, 32 bytes; derive the **PSK identity separately** (`info = "psk-id|v1"`) — the PSK
  identity travels in cleartext in the ClientHello, so it must NOT be `roomKey`. Do NOT bind `sessionName`
  into the KDF (host/joiner advertise different service names). The HKDF itself is `commonMain` (pure,
  testable on JVM); only the `sec_protocol_options` wiring is appleMain. Reuse an existing kuilt HKDF/hash
  if one exists (check `:kuilt-deal`/`:kuilt-crdt`); else a small SHA-256 HKDF in the module.
- **Wire `roomKey` through `NwLoom`.** `weave` reads `pattern.roomKey` (`Rendezvous.New`) / `tag.roomKey`
  (`Rendezvous.Existing`) and derives the PSK before `startListening`/`startBrowsing`. **Require it —
  fail fast:** `NwLoom` throws if `roomKey == null` on the real (securing) path rather than silently
  running unencrypted-but-flagged-secure ("Optional ≠ tuning"). Add `NwTag(sessionName, peerKey, roomKey)`
  mirroring the existing fabric Tag exemplars (`MultipeerAdvertisement`), plus an `NwTag.forSecret(...)`
  convenience for the host to build the invite Tag.
- **#1414 folds in here:** different `roomKey` ⇒ different PSK ⇒ two sessions on one serviceType can no
  longer merge (handshake fails). Upgrade `roomKey`'s "not a secret" KDoc *on the kuilt-nw surface*:
  on this fabric `roomKey` is a bearer secret — anyone holding it can join and decrypt. (Optional polish,
  not required: advertise a one-way `H(secret)` session-id in the service name so browse skips doomed dials.)
- **Entropy caveat for `module.md`:** a group PSK from a short human-typed code is offline-guessable from
  one captured TLS 1.3 handshake — recommend ≥128-bit random secrets carried via QR/link; a PAKE is the
  real fix and is out of scope for Phase 3.

### 3.4 — flip `securesTransport = true` + close #1412
- Real capability path reports `securesTransport = true`; drop the `#1412` entry from
  `NwConformanceTest.capabilityGaps()` once 3.5 proves it on CI. Update the "securesTransport gap"
  KDoc in `NwConformanceTest` to past tense. `Closes #1412` on the PR that lands 3.3+3.5.

### 3.5 — `NwLoopbackConformanceTest : SeamConformanceSuite()` (appleTest) — the CI proof
- Real `RealNwApi` over `127.0.0.1` (`requiredLocalEndpoint`, no `includePeerToPeer` — exempt from
  Local Network Privacy), **TLS-PSK ENABLED** so the `sec_protocol_options` C-API path is CI-covered.
- Runs the full capability TCK on the macOS runner (`macosArm64Test`). Tight-timeout ceremony.

### 3.6 — `NwConnectionLeakTest` (appleTest, gated `-Pconcurrency.stress.tests`)
- Open/close many connections on a **multi-threaded** dispatcher; assert the bridge registry drains to
  empty. The reference-management stress check — where cinterop leaks live.

### 3.7 — `module.md` honesty + Info.plist docs
- Honest loopback-coverage caveat: loopback covers send/receive/framing/cancel/registry/teardown +
  connection-surface cinterop; it does **NOT** cover `NWBrowser`/Bonjour discovery, `includePeerToPeer`,
  AWDL, TLS over a real P2P path, Local-Network-Privacy denial, or IPv6-required behaviour (only Phase 0
  + Phase 6 hardware prove those).
- Document required Info.plist keys for consumers: `NSLocalNetworkUsageDescription`, `NSBonjourServices`.

## Cinterop checklist (encode in briefs — from research + review + the Phase-2 wedge fix)
- `includePeerToPeer` in ALL THREE places (listener, browser, connection params).
- IPv6 available (no IPv4-only path); strong-ref every `NWConnection`; **cancel-first-then-drop**.
- **No `nw_*` under the registry lock** (GCD re-entrancy self-deadlock).
- `bytesReceived`/`connectionOpened`/`connectionClosed` **single-collection** (one callback → one Flow).
- Avoid `NW_CONNECTION_*_CONTEXT` constants — `nw_content_context_create(...)` explicitly.
- `closing` flag → local cancel = `CloseReason.Normal`; `Torn` terminal; `runCatchingCancellable`.

## Verify (each task, before stacking / auto-merge)
- `./gradlew :kuilt-nw:build detektAll --rerun-tasks` + explicit `:kuilt-nw:detektMetadataCommonMain`
  (#1416 — aggregate detekt skips commonMain).
- `:kuilt-nw:macosArm64Test` for the real cinterop (3.5 / 3.6). Native compile of appleMain is the real
  gate here — `jvmTest` cannot see appleMain at all.
- **3.1 + 3.3 (the load-bearing bridge + PSK) get an independent opus correctness review** —
  memory-management / strong-ref / cancel-order / KDF is exactly where cinterop bugs live. The Phase-2
  per-task opus review caught 3 real NwSeam bugs the build didn't.
- Whole-branch review = opus correctness + Fable design (capability honesty) before PR. Open PR **ready**
  + auto-merge squash.

## Adjacent follow-ups
- **#1414** (session scoping serviceType-only) — **folds into 3.3** (decision B): different `roomKey` ⇒
  different PSK ⇒ meshes can't merge. `Closes #1414` on the 3.3 PR.
- **#1415** (NwSeam receive-path head-of-line blocking) — a `commonMain` `NwSeam` fix, separate track;
  not required for Phase 3. Document-and-accept for now or a small standalone PR.
- **#1416** (detektAll skips commonMain) — repo-wide `build-logic` infra fix, own trivial PR.
