# `kuilt-nw` — a Network.framework peer-to-peer transport

> **In one sentence.** Two iPhones near each other should be able to play together
> with no Wi-Fi router in the room. Today that path is broken; this replaces the
> broken piece with Apple's current, supported one.

## Why

kuilt already speaks a fabric-agnostic contract (`Loom`/`Seam`/`Swatch`). One of
its Apple fabrics, `kuilt-multipeer`, is built on Apple's **MultipeerConnectivity**
(MC). On iOS 26, MC is broken for the exact case it exists to serve: two phones
with no infrastructure Wi-Fi. Measured on hardware (iPhone 17 Pro / iOS 26.5.1 ↔
iPhone XS / iOS 18.7.9):

| Condition | MC connect rate |
|---|---|
| Wi-Fi ON (same network) | 8/8, ~0.6 s |
| Wi-Fi OFF (AWDL) | ~1/12 (`encryptionPreference=Required`) |

Every failure is the same **data-path stall** (`Connecting → ~10 s → NotConnected`),
never discovery — and **AirDrop works between the same phones in the same state**,
so the AWDL data plane is fine. This is the Apple-confirmed iOS 26 MC regression:
the system tears down `awdl0` mid-handshake before `MCSession` finishes connecting
(Apple DevForums thread 803339). MC is also **deprecated as of Xcode 27**.

Apple's own guidance is to move to **Network.framework** for peer-to-peer, and a
tester who ran Apple's Network.framework sample against the same failing scenario
**did not reproduce the MC bug**. So: build a Network.framework fabric that
implements the same `Loom`/`Seam` contract — a drop-in behind kuilt's existing
abstraction — and retire MC.

### The honest seam (read before believing this fixes everything)

The new transport rides the **same AWDL radio**. It routes around the *MC-layer*
regression, but it is **not** immune to a genuine OS-level AWDL teardown, and
research surfaced a **separate, still-open iOS 26 regression** (DevForums 808917)
in which **cellular-capable iPhones off Wi-Fi fail to resolve peers**
(`nw_resolver_start_query_timer_block_invoke` timeout). Range is also short and
unspecified by Apple (~30 m line-of-sight, no SLA). Therefore:

- Acceptance is **relative** ("materially higher connect rate than MC"), never
  absolute. We are removing one known, fixable failure mode, not promising a
  bulletproof radio.
- **Phase 0 is a hardware spike that can veto the whole plan.** If Network.framework
  P2P does *not* materially beat MC on real hardware Wi-Fi-off, we stop and re-plan
  (the answer might then be an infrastructure-Wi-Fi/relay fallback, not a new radio
  transport). See [Phase 0](#phase-0--connectivity-spike-gates-everything).

## What we are building (and not)

**In scope**

- A new module `kuilt-nw`: a Network.framework fabric passing the same seam
  conformance as every other fabric, plus the lobby-facing discovery surface the
  app needs to actually switch off MC.
- A **capability TCK** refactor: transports *declare* what they support; the
  conformance suite asserts-or-documents each capability instead of hiding gaps in
  silent `@Ignore` overrides. Produces a legible cross-fabric capability matrix.
- Immediate **deprecation** of `kuilt-multipeer`, and its **deletion** the moment
  the new transport has downstream validation (aggressive, pre-1.0 posture — not
  gated on a release cycle).

**Out of scope (deferred)**

- **`CompositeSeam`** racing MC + the new transport, first-to-connect-wins. The
  issue marks it optional/future; once MC is deleted there is nothing to race it
  with anyway.
- **Full mesh topology** (see [Topology](#topology--star-with-host-relay-v1)).

## Architecture — follow `kuilt-nearby`, not `kuilt-multipeer`

Two existing Apple/radio fabrics offer opposite templates:

- `kuilt-multipeer` entangles apple-specific seam logic (`MCSessionLink`) with a
  JNA/dylib bridge, and can only run conformance against a hand-written fake native
  lib. Hard to test, hard to reason about.
- `kuilt-nearby` keeps **all** Loom/Seam/handshake logic in `commonMain` behind a
  thin `NearbyApi` interface, puts the one real platform binding in `androidMain`,
  and runs the **entire `SeamConformanceSuite` on the JVM** against a `FakeNearbyApi`.
  No device needed for the logic layer.

`kuilt-nw` follows the Nearby shape:

```
commonMain
  NwApi            (interface) — the thin Network.framework surface:
                     advertise / browse / listen, connection lifecycle,
                     byte send + receive; events surfaced as Flows.
  NwLoom : Loom    — weave() → NwSeam; owns discovery + the host/joiner roles.
  NwSeam : Seam    — the star hub/spoke seam (below); fans broadcast across
                     connections, merges incoming, owns the connection registry.
  NwConnectMachine — per-connection request→connect→identify handshake,
                     mirroring nearby's ConnectStateMachine (subscribe-before-trigger).
  (framing)        — length-prefix framing reusing :kuilt-stream (below).

appleMain
  RealNwApi        — hand-rolled cinterop against platform.Network.
                     NWListener / NWBrowser / NWConnection, includePeerToPeer,
                     Bonjour, TLS-PSK. This is the load-bearing native code.

jvmMain (+ macOS dylib)
  BridgeNwApi      — JNA over a macosArm64 dylib, adapting kuilt-multipeer's
                     existing Bridge* scaffolding, so a macOS-desktop JVM can join.
                     Non-macOS JVM → availability() == Unavailable.

commonTest
  FakeNwApi        — in-memory NwApi; runs the full capability TCK on the JVM.
appleTest
  loopback-TCP conformance — the REAL RealNwApi over 127.0.0.1 (see Testing).
```

Dependency direction stays legal: `kuilt-nw → kuilt-core` (+ `kuilt-session`,
`kuilt-stream`). The arrow never points back into `kuilt-core`.

## Stream framing — reuse `:kuilt-stream`, like `:kuilt-tcp`

Unlike MC (message-oriented `sendData`) and Nearby (message-oriented payloads),
`NWConnection` is a **byte stream**. So `kuilt-nw` is architecturally closer to
`:kuilt-tcp` than to Multipeer: it needs length-prefix framing + oversize
protection over the stream. `:kuilt-stream` already provides exactly this
(`framed()`, 4-byte length prefix, `FrameTooLargeException`). We reuse that frame
format and oversize guard rather than invent a bespoke chunk codec.

The exact adapter is a Phase-2 implementation detail: either wrap the `NWConnection`
send/receive as a kotlinx-io `Source`/`Sink` and hand it to `framed()`, or run an
explicit receive-loop parser over the async completion handlers using the same
frame format. The async, completion-handler nature of `nw_connection_receive` may
make the explicit parser cleaner; we decide against a spike in Phase 2.

## Topology — star with host-relay (v1)

`NWConnection` is point-to-point; `NWListener` accepts many. MC gave a **mesh** for
free (every peer interconnected). Network.framework does not. v1 uses a **star**:

- **Host** runs one `NWListener` advertising a Bonjour service; accepts N inbound
  `NWConnection`s.
- **Joiners** run `NWBrowser`, find the host, and dial one `NWConnection` to it.
- The `Seam` contract (broadcast reaches *all* peers) is **preserved by host-relay**:
  a joiner's `broadcast` reaches the host, which relays to the other joiners.

**Contrast with `kuilt-nearby`.** Nearby's `FakeNearbyRadio`/`ConnectStateMachine`
model a *single advertiser↔discoverer pair* — a degenerate 2-peer star. `kuilt-nw`
generalizes that to **N spokes on one hub**: one `NwConnectMachine` per accepted
connection on the host side, a shared peer set across them, and a broadcast that
fans out across all live connections. The handshake (subscribe-before-trigger,
stable-identity exchange as the first frame because endpoint identity is not stable
across peers) is the same discipline Nearby already encodes — reuse its shape.

**Accepted caveats, made legible** (declared as capabilities, not hidden):

- One **relay hop** for joiner→joiner traffic.
- The host is a **single point of failure**: if the host drops, the star collapses
  (MC's mesh survived one peer dropping). For a co-located 2–6-player lobby this is
  acceptable for v1; a peer that wants resilience can re-host.
- `meshDelivery = false` in the capability matrix — explicit, not silent.

Full mesh (every peer listens + browses + dials every other; N² connections) is
**deferred** unless a concrete need appears.

## `appleMain` binding — the load-bearing details

From research (Kotlin/Native + Network.framework), the sharp edges to encode:

- **cinterop, no `.def`.** `platform.Network` is an auto-generated K/N platform
  library (same mechanism as Foundation/UIKit). Use the underlying C functions
  (`nw_listener_create`, `nw_connection_*`, `nw_browser_*`); the Swift-only wrapper
  *classes* (`NWConnection`, `NWBrowser`) are not exposed — the C API is.
- **Block callbacks bridge as plain lambdas.** `nw_connection_set_state_changed_handler
  { state, error -> … }` is a normal Kotlin lambda; no `staticCFunction`/`StableRef`
  needed for this callback class. `StableRef` stays only for the rare raw `void*`
  context APIs.
- **New K/N memory model (default).** No `freeze()`; Kotlin may be entered from the
  arbitrary GCD thread a handler fires on. The callback→`Flow` bridge is a
  `callbackFlow { … awaitClose { nw_connection_cancel(...) } }` on a dedicated
  `dispatch_queue_t`. This is a documented, supported pattern.
- **`includePeerToPeer` is a three-place gotcha.** Set it on the **listener**
  params, the **browser** params, **and** the **connection** params used to dial.
  Omitting it on the connection side is a common bug that silently disables P2P.
- **P2P needs Bonjour end-to-end and IPv6 available.** Direct-IP connections do not
  route over AWDL even with the flag; forcing IPv4-only silently kills P2P.
- **TLS-PSK, not PKI.** `sec_protocol_options_add_pre_shared_key` on the connection's
  TLS options, per Apple DTS's MC→Network.framework migration guidance. The PSK is
  derived from the session pattern/tag (the shared secret both sides already hold
  from the rendezvous), so no trusted third party.
- **Local Network Privacy.** Consumers must add `NSLocalNetworkUsageDescription` and
  declare the Bonjour service in `NSBonjourServices`; iOS shows a one-time prompt
  before any Bonjour/AWDL traffic. Documented in the module's `module.md` and usage
  docs. (Loopback `127.0.0.1` is exempt — see Testing.)

## Connection lifecycle & reference management (the leak hazard)

This is the single most error-prone part and gets a dedicated owner.

Network.framework **silently cancels** an `NWConnection` when the last strong
reference to it drops — so live connections **must** be retained. The mirror hazard:
retain them and *fail to release* on teardown and you leak the connection, its
native receive buffers, **and** keep the `awdl0` interface pinned up (battery,
contention). Both directions are real defects.

Design:

- `NwSeam` owns a **connection registry** — a `PeerId → NwConnection` map plus the
  live `NWListener`/`NWBrowser` handles — guarded by an explicit lock
  (atomicfu `reentrantLock` or a `Mutex`), **never** `Dispatchers.X.limitedParallelism(1)`
  confinement (banned per repo policy; the registry must be correct under a
  multi-threaded dispatcher).
- Deterministic release on **every** exit path: per-peer drop
  (`nw_connection` state → `cancelled`/`failed`), local `close(reason)`, and
  seam-tear. Releasing removes the registry entry (dropping our last ref) **after**
  an explicit `nw_connection_cancel` / `nw_listener_cancel` — cancel first, then
  drop, so teardown is intentional rather than GC-timed.
- **Cancellation-safe throughout.** Best-effort sends and teardown use
  `runCatchingCancellable { … }` and rethrow `CancellationException` — never bare
  `runCatching` (repo exception discipline). A torn connection during broadcast is
  logged at debug, not thrown.
- A **`Torn` state is terminal and must stay terminal** — the registry writer must
  not clobber `Torn` with a stale non-terminal value (the lost-terminal-transition
  class the conformance suite's `stateStaysTornAfterClose` guards).

The capability TCK's teardown obligations (`terminatesIncomingOnClose`,
`stateStaysTornAfterClose`, `closeIsIdempotent`) are the executable check that this
is right; a real-threaded leak probe (below) is the stress-grade check.

## Capability TCK — make support explicit (foundation, lands first)

Today `SeamConformanceSuite` lives in `:kuilt-conformance` and every fabric
subclasses it, but capabilities are **implicit**: a fabric that cannot honour an
invariant overrides an `open fun` with `@Ignore` + a tracking issue (WebRTC does
this for `incomingCompletesWhenSeamCloses`, #335). That hides real capability
differences in scattered overrides.

Refactor: a transport **declares** its capabilities; the suite asserts each
supported one and records each unsupported one as a **visible documented gap**.

```kotlin
// illustrative — exact flags finalised in the PR
data class SeamCapabilities(
    val ordersDelivery: Boolean,            // FIFO to a single collector
    val reportsPeerLoss: Boolean,           // peer-drop reflected in peers/state
    val terminatesIncomingOnClose: Boolean, // incoming completes on Torn
    val supportsSendTo: Boolean,            // directed send + PeerNotConnected
    val securesTransport: Boolean,          // encrypted on the wire
    val meshDelivery: Boolean,              // peer↔peer without a relay hop
    // …
)

abstract class SeamConformanceSuite {
    abstract fun capabilities(): SeamCapabilities
    // each obligation gates on capabilities(): assert if supported,
    // else emit a recorded "documented gap" (skipped-with-reason), never silent.
}
```

- Migrate every existing fabric to declare its capability set (replacing the ad-hoc
  `@Ignore` overrides). `kuilt-nw` is the **first** fabric authored against the new
  suite — e.g. `meshDelivery = false`, made legible.
- Output a generated **capability matrix** (a table across all fabrics) — the
  artifact the "implementing a new transport" skill points at as its checklist.
- This touches every transport, so per "harden the foundation before stacking more
  on top" it lands **before** the new transport (Phase 1).

## Discovery / lobby surface

To let the app actually switch off MC, `kuilt-nw` mirrors Multipeer's lobby contract:

- A `visiblePeers: StateFlow<Set<…>>` on the loom, updated reactively from
  `NWBrowser` results (peer appears / disappears), so a lobby can `collectAsState`.
- An `NwRoomHost` equivalent to `MultipeerRoomHost` for the host role.

The consuming app swaps `MultipeerPeerLinkFactory` → `NwLoom` and
`MultipeerRoomHost` → `NwRoomHost` with minimal churn. Exact surface tracks the
Multipeer types so the migration is mechanical.

## Testing strategy — three tiers below hardware

1. **`commonTest` (JVM, CI):** `FakeNwApi` → the full capability TCK. Pure Loom/Seam/
   handshake/framing logic; no radio, no device. This is where correctness lives.
2. **`appleTest` (CI macOS runner):** the **real** `RealNwApi` over **loopback TCP**
   (`NWListener`/`NWConnection` bound to `127.0.0.1` via `requiredLocalEndpoint`).
   Loopback is exempt from Local Network Privacy and needs no `includePeerToPeer`,
   so this exercises the *actual* Network.framework send/receive/framing/cancel code
   — including the connection-registry teardown — with no radio and no second device.
3. **`-Pnw.realnet.tests` (physical, two-device, opt-in):** real AWDL/Bonjour/TLS-PSK
   over the P2P radio. The hardware connect-rate acceptance gate. Reuses the
   cross-process-probe pattern (`MultipeerCrossProcessProbe`) and the two-iPhone
   harness from the MC debugging.

Plus a **real-threaded leak/stress probe** (gated like the existing
`-Pconcurrency.stress.tests`) that opens/closes many connections on a
multi-threaded dispatcher and asserts the registry drains to empty — the
stress-grade check for the reference-management section.

**Open verification:** whether AWDL/true-P2P works in the iOS Simulator is
**unconfirmed** by research. Verify empirically early (Phase 0); if the simulator
cannot do P2P, tier 3 is physical-only and the plan already assumes that.

## Phase 0 — connectivity spike (gates everything)

A minimal **Kotlin/Native standalone** app, deployable to two iPhones, that proves
the product question *and* the real cinterop path before we invest in the full
module:

- A tiny SwiftUI harness: **Host** / **Join** buttons, a connection-state label, a
  **ping round-trip** (send a frame, echo it back, show RTT).
- Backed by a **Kotlin/Native binding calling `platform.Network` via cinterop** —
  `NWListener` + Bonjour + `includePeerToPeer` (host), `NWBrowser` + `NWConnection`
  (join), TLS-PSK, one framed round-trip. This is the *actual* production code path,
  not a Swift stand-in, so it de-risks the C-API TLS-PSK + block-callback bridge for
  real.
- **What it proves:** (a) `platform.Network` cinterop compiles/links from K/N and
  the block-callback→coroutine bridge works; (b) two phones connect **Wi-Fi-off**
  where MC fails; (c) a frame round-trips; (d) simulator P2P support (empirical).
- **The binding it produces seeds `RealNwApi`** — throwaway UI, keeper transport code.
- **Gate:** materially higher Wi-Fi-off connect rate than MC on the two-iPhone
  harness ⇒ proceed to Phase 1. Not materially better ⇒ **stop and re-plan** (the
  808917 cellular-off-Wi-Fi risk may dominate; a relay/infra-Wi-Fi fallback may be
  the real answer).

## Plan of record — PR stack

```
Phase 0  Connectivity spike       K/N standalone two-iPhone app; prove NW P2P
                                   Wi-Fi-off + cinterop. GATES the rest.
Phase 1  Capability-TCK refactor   All fabrics declare SeamCapabilities; matrix.
                                   (Foundation — lands before the new transport.)
Phase 2  Transport core           commonMain NwApi + NwLoom/NwSeam/NwConnectMachine
                                   + :kuilt-stream framing + FakeNwApi capability TCK.
Phase 3  appleMain RealNwApi       cinterop against platform.Network; connection
                                   registry + teardown; loopback-TCP appleTest.
Phase 4  macOS dylib + JVM bridge  BridgeNwApi/JNA adapting Multipeer's Bridge*.
Phase 5  Lobby discovery surface   visiblePeers + NwRoomHost; app-migration shape.
Phase 6  Hardware validation       -Pnw.realnet.tests two-device connect-rate;
                                   deprecate kuilt-multipeer (@Deprecated) in this phase.
Phase 7  Retire kuilt-multipeer    Delete the module + its dylib bridge the moment
                                   downstream validation lands. Dylib surface nets to one.
```

Deprecation (Phase 6) is immediate; deletion (Phase 7) is gated only on downstream
validation, not a release cycle. Deleting `kuilt-multipeer` is a breaking change for
any consumer using it — file the consumer-migration notice at deprecation time.

## Side deliverable — the "implementing a new transport" skill

Authored **at the end**, from the real pain points captured while building (a running
log kept through Phases 0–6). Its spine is the **capability TCK**: the checklist a new
fabric author works through is "declare your `SeamCapabilities`, make each supported
obligation green, document each gap." The skill records the cinterop/memory-model/
`includePeerToPeer`/reference-leak sharp edges this project actually hit, so the next
fabric author does not rediscover them.

## Risks

| Risk | Mitigation |
|---|---|
| NW P2P doesn't beat MC on real hardware (808917 cellular-off-Wi-Fi resolver regression) | Phase 0 spike gates everything; acceptance is relative; stop-and-re-plan if not materially better. |
| Connection reference leak / silent cancel | Dedicated registry + deterministic teardown + real-threaded leak probe + TCK teardown obligations. |
| `includePeerToPeer` set in too few places | Encoded as a checklist item; loopback tier won't catch it (no P2P) — Phase 0 + tier 3 do. |
| Simulator can't do P2P | Assume physical-only for tier 3; verify empirically in Phase 0. |
| K/N cinterop surprise vs. ergonomic Swift | Phase 0 spike is K/N, not Swift — proves the real path incl. C-API TLS-PSK. |
| Short range / no SLA | Out of transport's control; infra-Wi-Fi fallback remains available via the WebSocket relay fabric. |

## References

- Apple DevForums 803339 — the iOS 26 MC AWDL-teardown regression.
- Apple DevForums 808917 — the *separate*, still-open NW-P2P cellular-off-Wi-Fi
  resolver regression (the honest seam).
- Apple DTS — "Moving from Multipeer Connectivity to Network Framework" (TLS-PSK for P2P).
- Apple TN3179 — Understanding Local Network Privacy.
- In-tree templates: `kuilt-nearby` (architecture), `:kuilt-stream`/`:kuilt-tcp`
  (framing), `SeamConformanceSuite` (TCK), `kuilt-multipeer` `Bridge*` (JVM bridge).
