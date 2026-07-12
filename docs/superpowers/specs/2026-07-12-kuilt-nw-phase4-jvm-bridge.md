# kuilt-nw Phase 4 — macOS dylib + JVM bridge

_Spec for the re-plan gate of Phase 4 in `docs/superpowers/plans/2026-07-11-kuilt-nw-transport.md`._
_Written 2026-07-12 ET, after Phase 3 (#1422) landed. Gate PASSED: `:kuilt-nw:packageMacosNatives`
already produces `build/native-binaries-jvm/darwin-aarch64/libkuilt.dylib` (1.9 MB) end-to-end._

## Goal

Let a **macOS-desktop JVM** host/join the Network.framework fabric by bridging `NwApi` over JNA to a
`macosArm64` dylib that wraps the existing `RealNwApi`. On non-macOS JVMs the fabric reports
`Unavailable` and never loads the dylib. Mirrors `kuilt-multipeer`'s `Bridge*` scaffolding exactly.

Nothing in `commonMain`/`appleMain` changes — `NwLoom(api: NwApi, serviceType)` already accepts any
`NwApi`. Phase 4 only adds a JVM-side `NwApi` impl and JVM `nwHost`/`nwJoin` factories.

## Architecture (already true; do not re-derive)

- `NwLoom(api: NwApi, serviceType, …)` — commonMain, public, wraps any `NwApi`.
- Apple: `nwHost/nwJoin` build `NwLoom(RealNwApi(NwPsk.derive(secret, serviceType)), serviceType)`.
- **`NwPsk.derive(roomKey, serviceType): NwPskMaterial` is commonMain** (kotlincrypto HKDF-SHA256) —
  runs on JVM. So HKDF stays JVM-side; only the raw `psk`/`identity` byte arrays cross JNA.
- `RealNwApi.connect(endpoint)` uses **only `endpoint.id`** (internal `endpointsById` lookup) — so
  `nw_connect` marshals just the id string; the K/N side rebuilds `NwEndpoint(id, serviceName = "")`.
- `NwApi` surface to bridge: `availability()`; suspend `startListening/stopListening/startBrowsing/`
  `stopBrowsing/connect/disconnect/send`; four hot flows `endpointFound / connectionOpened /`
  `bytesReceived / connectionClosed`.

## The bridge ABI (cdecl, `@CName`, over `libkuilt.dylib`)

Handles are opaque `COpaquePointer` = `StableRef` of a K/N `NwBridgeRuntime`. Strings are UTF-8
NUL-terminated. Suspend ops run `runBlocking` on the runtime's own scope inside each `@CName` and
return an `Int` result code (`0` ok, `<0` error). Byte buffers: `(ByteArray/Pointer, Int len)`.

**Lifecycle**
- `nw_runtime_create(psk, pskLen, identity, identityLen) -> handle?` — build
  `RealNwApi(NwPskMaterial(psk, identity))` inside a `NwBridgeRuntime` (StableRef + a `CoroutineScope`
  on a dedicated dispatch queue) and return its `asCPointer()`. `null` on bad args.
- `nw_runtime_destroy(handle)` — `runBlocking { stopListening(); stopBrowsing(); disconnect(all) }`,
  cancel the scope, `dispose()` the StableRef. Double-destroy is caller-error UAF (documented).
- `kuilt_protocol_version() -> Int` — ABI version (`1`); JVM side fails fast on mismatch.

**Callback registration** (call once, before start; JVM holds strong refs for runtime lifetime)
- `nw_set_endpoint_found_callback(handle, cb)` — `cb(endpointId: String, serviceName: String)`
- `nw_set_connection_opened_callback(handle, cb)` — `cb(connectionId, endpointId: String, serviceName: String)`
  (empty strings when the opened connection has no dialled endpoint — inbound/host role)
- `nw_set_bytes_received_callback(handle, cb)` — `cb(connectionId: String, data: Pointer, len: Int)`
- `nw_set_connection_closed_callback(handle, cb)` — `cb(connectionId: String, reason: String)` (empty ⇒ null)

The K/N runtime launches one collector per RealNwApi flow on its scope (subscribe-before-start:
register callbacks, THEN the JVM calls the start ops) that forwards each event to the registered
callback. `incoming`-style single-collection is preserved — one collector per flow.

**Ops** (all `-> Int`)
- `nw_start_listening(handle, serviceName, serviceType)`
- `nw_stop_listening(handle)`
- `nw_start_browsing(handle, serviceType)`
- `nw_stop_browsing(handle)`
- `nw_connect(handle, endpointId)`
- `nw_disconnect(handle, connectionId)`
- `nw_send(handle, connectionId, data, len)`

## JVM side (`jvmMain`)

- `NwNativeLib : Library` — JNA interface mirroring the ABI + `Callback` fun-interfaces + `load()`
  (returns `null` off macOS via `os.name` check + `runCatchingCancellable`). `EXPECTED_PROTOCOL_VERSION = 1`.
  Copy the doc-contract discipline from `MultipeerNativeLib` verbatim (const char* lifetime; strong
  callback refs or SIGSEGV; copy-out-immediately).
- `BridgeNwApi(nativeLib, handle, dispatcher) : NwApi` — the crown jewel. Mirrors `BridgePeerLink`:
  - Four hot flows via `MutableSharedFlow` (match `RealNwApi`'s `MutableSharedFlow`/`asSharedFlow`),
    fed from the JNA callbacks. Each callback is a **held field** (strong ref). Byte data copied out
    of the `Pointer` immediately (`getByteArray(0, len)`).
  - JNA callbacks fire on JNA threads (non-suspending): deposit into bounded staging `Channel`s
    (`DROP_OLDEST`, sized by policy) drained by single per-flow coroutines to preserve FIFO — same
    pattern as `BridgePeerLink`'s bridge→spool drain. Do NOT `emit` directly from the JNA thread.
  - suspend ops call JNA inside `withContext(dispatcher)` so the JVM coroutine never blocks a
    dispatcher thread on the native `runBlocking`. `send` throws on `<0` (best-effort per `NwApi` doc).
  - `availability()` → `NwNativeLib.load() != null` gated: `Available` on macOS-arm64, else
    `Unavailable("kuilt-nw JVM bridge is macOS-only")`.
- `NwFabric.jvm.kt` — JVM `nwHost(pattern, serviceType)` / `nwJoin(tag, serviceType)` mirroring
  `appleMain/NwFabric.kt`: `requireNotNull(roomKey)`, derive `NwPsk.derive`, create the runtime via
  `NwNativeLib`, wrap `NwLoom(BridgeNwApi(...), serviceType)`. Must fail fast with the macOS-only
  message when `load()` is null.

## K/N side (`macosMain/.../bridge/`)

- `Bridge.kt` — `kuilt_protocol_version` (copy multipeer's).
- `NwBridgeRuntime.kt` — the StableRef-rooted class: owns `RealNwApi`, a scope, the registered
  callback pointers, and the flow-forwarding collectors. `create/destroy` + `set_*_callback` + the ops.
  Strong-ref discipline is inside `RealNwApi` already; the bridge only forwards.
- Split the `@CName` exports into `NwBridgeExports.kt` if it reads cleaner (multipeer splits
  Runtime/Host/Client/Browser — here one runtime owns everything, so one or two files is fine).

## Tests

**CI (Linux-safe, `ci-required`):**
- `jvmTest/FakeNwNativeLib.kt` — an in-JVM fake `NwNativeLib` that loops back host↔join without the
  dylib (deliver-through fake, mirror `DeliveringFakeMultipeerNativeLib`). Drives two `BridgeNwApi`
  instances so the callback→channel→flow wiring, closing/teardown, and availability gating are all
  exercised on the Linux runner.
- `BridgeNwApiTest` — availability off-macOS returns `Unavailable`; callback→flow FIFO; strong-ref
  lifetime; suspend-op result-code mapping (`send <0` throws).
- Wire a JVM conformance path against the fake if it fits `SeamConformanceSuite` cleanly (via
  `NwLoom(BridgeNwApi(fake))`); otherwise a focused wiring test is acceptable — the real conformance
  is the loopback dylib test below.

**macOS-only, gated (not in `ci-required`; Apple/nightly + manual):**
- `-Pnw.realnet.tests`-gated JVM loopback test loading the **real** dylib: two `BridgeNwApi` over
  `127.0.0.1` TLS-PSK, proving the JNA↔dylib↔`RealNwApi` path end-to-end (the JVM analogue of
  `NwLoopbackConformanceTest`). Forward the `-P` flag like `multipeer.realnet.tests` in the build.
- `NwCrossProcessProbe` — mirror `MultipeerCrossProcessProbe`: CLI-style host/joiner probes for
  manual macOS↔iPhone bisection (Phase 6 hardware validation reuses it).

**Docs:** `kuilt-nw/module.md` gains a JVM-bridge honesty section — the bridge covers the same
loopback plumbing `RealNwApi` does and NOT `NWBrowser`/Bonjour/`includePeerToPeer`/AWDL below hardware;
JVM availability is macOS-arm64-only.

## Build wiring

`packageMacosNatives` + `jvmMain` resources srcDir already exist in `kuilt-nw/build.gradle.kts`. Add:
- forward `-Pnw.realnet.tests` to `Test` tasks (copy the multipeer block).
- ensure `jvmProcessResources`/`jvmTest` depend on `packageMacosNatives` so the dylib is on the test
  classpath when the gated real-dylib test runs (multipeer's resources.srcDir(map) wiring already
  creates the task dependency — confirm `:kuilt-nw:jvmTest` sees the dylib).

## Task breakdown (subagent-driven, one accumulating branch `nw-phase4` off `origin/main`)

- **Task 4.1** — the whole bridge, both sides + Linux-safe fake test. `macosMain` exports +
  `NwBridgeRuntime` + `NwNativeLib` (JNA) + `BridgeNwApi` + `NwFabric.jvm.kt` + `FakeNwNativeLib` +
  `BridgeNwApiTest`. Compiles green on `:kuilt-nw:build` (JVM+native) + `detektAll`. **Load-bearing
  cinterop → independent opus review after this task.**
- **Task 4.2** — real-dylib proof + probe + docs: gated JVM loopback test loading the real dylib,
  `NwCrossProcessProbe`, `module.md` honesty section, build `-P` forwarding. Run
  `:kuilt-nw:macosArm64Test` + the gated JVM loopback locally on the Mac.

Then: whole-branch review (opus + Fable capability sign-off — the JVM bridge is a capability-surface
change: `availability()` semantics), open PR **ready** + auto-merge squash, `closes #<phase4 issue>`.
Verify `:kuilt-nw:build detektAll --rerun-tasks` + `:kuilt-nw:detektMetadataCommonMain`.
