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
  P2P does *not* materially beat MC on real hardware Wi-Fi-off — measured for both
  *connect rate* **and** *mid-session survival* — we stop and re-plan (the answer
  might then be an infrastructure-Wi-Fi/relay fallback, not a new radio transport).
  See [Phase 0](#phase-0--connectivity-spike-gates-everything).

## What we are building (and not)

**In scope**

- A new module `kuilt-nw`: a Network.framework fabric passing the same seam
  conformance as every other fabric, plus the lobby-facing discovery surface the
  app needs to actually switch off MC.
- A **capability TCK** refactor: transports *declare* what they support; the
  conformance suite asserts each supported capability and surfaces each gap in a
  rendered, issue-linked **capability matrix** instead of hiding gaps in silent
  `@Ignore` overrides.
- Immediate **deprecation** of `kuilt-multipeer`, and its **deletion** the moment
  the new transport has downstream validation (aggressive, pre-1.0 posture — not
  gated on a release cycle).

**Out of scope (deferred)**

- **`CompositeSeam`** racing MC + the new transport, first-to-connect-wins. The
  issue marks it optional/future; once MC is deleted there is nothing to race it
  with anyway.

## Architecture — follow `kuilt-nearby`, not `kuilt-multipeer`

Two existing Apple/radio fabrics offer opposite templates:

- `kuilt-multipeer` entangles apple-specific seam logic (`MCSessionLink`) with a
  JNA/dylib bridge, and can only run conformance against a hand-written fake native
  lib. Hard to test, hard to reason about.
- `kuilt-nearby` keeps **all** Loom/Seam/handshake logic in `commonMain` behind a
  thin `NearbyApi` interface, puts the one real platform binding in `androidMain`,
  and runs the **entire `SeamConformanceSuite` on the JVM** against a `FakeNearbyApi`.
  No device needed for the logic layer. (One bug **not** to inherit: its
  `freshPeerId()` is a per-loom counter that collides across devices — see
  [Identity](#identity--peerid).)

`kuilt-nw` follows the Nearby shape:

```
commonMain
  NwApi            (interface) — the thin Network.framework surface:
                     advertise + browse + listen (every peer does all three),
                     connection lifecycle, byte send + receive; events as Flows.
  NwLoom : Loom    — weave() → NwSeam; owns advertise+browse; mints identity.
  NwSeam : Seam    — the MESH seam: one direct connection per peer, a peer
                     registry, direct broadcast fan-out + direct sendTo, teardown.
  NwConnectMachine — per-connection handshake: identity exchange + the dedup
                     tie-break that collapses a double-dial to one connection.
  (framing)        — length-prefix framing reusing :kuilt-stream's frame format.

appleMain
  RealNwApi        — hand-rolled cinterop against platform.Network.
                     NWListener / NWBrowser / NWConnection, includePeerToPeer,
                     Bonjour, TLS-PSK. This is the load-bearing native code.

jvmMain (+ macOS dylib)
  BridgeNwApi      — JNA over a macosArm64 dylib, adapting kuilt-multipeer's
                     existing Bridge* scaffolding, so a macOS-desktop JVM can join.
                     Non-macOS JVM → availability() == Unavailable.

commonTest
  FakeNwApi        — in-memory NwApi routing between N DISTINCT NwLoom instances
                     (role-split, never same-loom-twice — see Testing).
appleTest
  loopback-TCP conformance — the REAL RealNwApi over 127.0.0.1 (see Testing).
```

Dependency direction stays legal: `kuilt-nw → kuilt-core` (+ `kuilt-session`,
`kuilt-stream`). The arrow never points back into `kuilt-core`.

## Topology — full mesh (MC-parity, no relay, no SPOF)

`NWConnection` is point-to-point; `NWListener` accepts many. MC gave a **mesh** for
free (every peer interconnected, so `Swatch.sender` = the peer a frame came from,
the roster = the connected set, and directed send is direct). Network.framework
does not give that for free — so `kuilt-nw` **builds** the mesh: **every peer
advertises (`NWListener`) *and* browses (`NWBrowser`) *and* dials (`NWConnection`)
every other peer it discovers on the session's Bonjour service.**

This restores MC-parity semantics natively:

- **`Swatch.sender`** = the `PeerId` of the connection a frame arrives on. No relay,
  so no origin-stamping envelope is needed (a relayed star would have required one —
  every consumer stamps sender from the arriving connection: `MeshSeam`, `NearbySeam`,
  `MCSessionLink`; `SeamRaftTransport` maps `sender → NodeId`, `GossipSeam` does
  `flood(except = sender)`).
- **Roster** = `self` + every directly-connected peer, converging as connections
  establish. No host membership-announcement protocol needed.
- **`sendTo(peer)`** = send on that peer's direct connection. `PeerNotConnected` only
  when the peer genuinely isn't connected.
- **No single point of failure.** Any peer can drop and the rest stay connected —
  exactly MC's resilience. (A star with a host-hub would have been a *mid-session
  regression* vs. MC: the host leaving kills the whole session, fatal for a Raft
  group mid-election.)

### Why mesh, not star (decision record)

The first draft proposed a star (host = the one `NWListener`, joiners each hold one
connection to it, host relays joiner↔joiner). An adversarial design review showed
the star's simplicity was **illusory**: preserving the `Seam` contract over a star
requires a whole control plane — an origin-stamped relay envelope, host-emitted
membership announcements to build spoke rosters, and host-routed directed sends —
*plus* it keeps the host as a SPOF, *plus* the relay path couples cross-peer traffic
to the host's own inbound backpressure. Mesh needs none of that: the only mesh-
specific work is **connection dedup** (below), which is far less than the star's
missing routing protocol. So mesh is the *simpler correct* option.

### Connection dedup — the one mesh-specific mechanism

Because every peer dials every other, peers A and B can dial each other
simultaneously, yielding two `NWConnection`s for one logical link. Resolve with a
deterministic tie-break during the identity handshake: once both sides know both
`PeerId`s, **keep the connection whose dialer has the lower `PeerId`** (lexicographic)
and cancel the other. Both sides compute the same winner, so exactly one survives.
The registry keys on `PeerId`, so the loser's teardown must not evict the winner.

**Connection count** is `N(N-1)/2` — ≤15 for a 2–6-player lobby. Negligible.
`meshDelivery = true` in the capability matrix — earned, not claimed.

## Stream framing — reuse `:kuilt-stream`, like `:kuilt-tcp`

Unlike MC (message-oriented `sendData`) and Nearby (message-oriented payloads),
`NWConnection` is a **byte stream**. So `kuilt-nw` is architecturally closer to
`:kuilt-tcp` than to Multipeer: it needs length-prefix framing + oversize protection
over each connection's stream. `:kuilt-stream` already provides this (`framed()`,
4-byte length prefix, `FrameTooLargeException`).

`nw_connection_receive` is completion-handler based (min/max length hints), which is
awkward to bridge to `framed()`'s pull-based blocking `Source`. So we reuse the
**frame *format* and `FrameTooLargeException`**, implemented as an explicit
chunk-accumulating receive-loop parser over the async handlers — not `framed()`
itself. Each connection has one such parser; its re-arm and backpressure policy for
the hot receive path is specified alongside the seam (a bounded staging channel +
single drain to the `Spool`, mirroring `MCSessionLink`'s bridge — a slow local
consumer must never wedge the receive loop).

## Identity — `PeerId`

`PeerId` is a **collision-resistant random** (UUID-grade) minted once per `NwLoom`
instance — **not** a per-loom counter (Nearby's `nearby-peer-${counter}` collides:
two devices both mint `nearby-peer-1`; masked only because Nearby's conformance runs
one loom in-process). Each side sends its `PeerId` as the first framed message after
a connection opens; the handshake resolves once both identities are known (and drives
the dedup tie-break). A caller may inject a stable `PeerId` for reconnect continuity;
the reconnect-stability contract (does a fresh `weave()` reuse identity?) is stated
explicitly in the KDoc, since `kuilt-session`'s reconnect/roster keys on `PeerId`.

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
- **TLS-PSK, not PKI.** `sec_protocol_options_add_pre_shared_key` on both listener and
  connection TLS options, per Apple DTS's MC→Network.framework migration guidance.
  **Threat model, stated honestly (see review finding):** if the PSK derives from the
  session pattern/tag and that tag is advertised in the Bonjour TXT/service name, then
  the key is broadcast in cleartext to everyone in radio range — confidentiality only
  against off-channel *passive* observers, **no authentication** (roughly MC's
  unauthenticated-DH parity). To get real access control, derive the PSK from a
  **user-visible join code carried in `Rendezvous`** (never advertised over Bonjour).
  Which of these `securesTransport` means is **decided in Phase 2 before the flag is
  declared**, and the capability matrix reflects the honest answer.
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
- **No `nw_*` call under the lock.** Handlers fire on the GCD queue and can re-enter;
  compute the teardown set under the lock, then `cancel` outside it (mirrors the
  repo's suspend-outside-lock rule, and avoids a self-deadlock if `close()` runs on
  the handler queue).
- **Distinguish local cancel from remote drop.** A local `nw_connection_cancel` fires
  the state handler with `cancelled`; the seam must not misreport that as a remote
  drop. Use a `closing` flag set before cancel (the precedent is `MCSessionLink`'s
  `closing`), so `CloseReason` is `Normal` on our own teardown, not `RemoteRequested`.
- Deterministic release on **every** exit path — peer-drop, `close(reason)`, seam-tear,
  and the dedup-loser cancel — each: `cancel` the connection, then drop the registry
  entry (our last ref). Cancel-first-then-drop so teardown is intentional, not GC-timed.
- **Cancellation-safe throughout** (`runCatchingCancellable`, rethrow
  `CancellationException`; a torn connection during broadcast logs at debug, not throws).
- **`Torn` is terminal and stays terminal** — the registry writer must not clobber
  `Torn` with a stale non-terminal value (the `stateStaysTornAfterClose` class).

The capability TCK's teardown obligations plus a real-threaded leak probe (below) are
the executable checks that this is right.

## Capability TCK — make support explicit (foundation, lands first)

Today `SeamConformanceSuite` lives in `:kuilt-conformance` and every fabric
subclasses it, but capabilities are **implicit**: a fabric that cannot honour an
invariant overrides one of the suite's `open fun` escape hatches with `@Ignore` + a
tracking issue. There are **three** such hatches today — `incomingCompletesWhenSeamCloses`
(WebRTC, #335), `stateStaysTornAfterClose`, and `sendOnTornSeamThrows` (Multipeer JVM
bridge, Gossip; the last added by #1390). That hides real capability differences in
scattered overrides.

Refactor: a transport **declares** its capabilities; the suite asserts each supported
one, and each *unsupported* one is surfaced in a rendered matrix — **not** via a fake
"skip".

```kotlin
// illustrative — exact flags finalised in the PR
public data class SeamCapabilities(
    val ordersDelivery: Boolean,            // FIFO to a single collector
    val reportsPeerLoss: Boolean,           // peer-drop reflected in peers/state
    val terminatesIncomingOnClose: Boolean, // incoming completes on Torn (hatch 1)
    val staysTornAfterClose: Boolean,       // Torn terminal under churn (hatch 2)
    val throwsOnSendToTorn: Boolean,        // send on Torn throws (hatch 3, #1390)
    val supportsSendTo: Boolean,            // directed send delivers; absent → PeerNotConnected
    val securesTransport: Boolean,          // encrypted on the wire (honest — see TLS-PSK)
    val meshDelivery: Boolean,              // peer↔peer with no relay hop
)

public abstract class SeamConformanceSuite {
    public abstract fun capabilities(): SeamCapabilities   // no default — every fabric declares
}
```

Design points forced by the review:

- **The visibility artifact is the matrix, not a skip.** common `kotlin-test` has *no*
  assumption/skip API — a gated early-return would report **PASS** (worse than today's
  visible-in-JVM `@Ignore`). So: an obligation gated on a `false` capability **must not
  silently pass**. Instead, every `false` cell is required to carry an issue link in the
  fabric's matrix entry, and `renderMatrix(...)` (buildable in `commonMain`) is the
  artifact that makes the gap visible. A `false` with no issue link fails the build.
- **Enumerate the full flag ↔ obligation map before migrating** (all three hatches
  included), so the fabric migrations don't invent flags mid-flight.
- **Pin the ungated core.** Obligations 1–9b (host yields usable seam, broadcast
  delivers, order preserved, peers ≥2, close idempotent, availability, Woven states)
  are **ungated** — no capability may skip them. In particular
  `broadcastFromHostDeliversToJoinedPeer` is core; nothing gates it.
- **Add the missing positive obligations** (the suite today has only the *negative*
  `sendTo` case): a `sendToDeliversToNamedPeer` obligation, and a **3-peer** obligation
  (below) — both capability-gated only where a fabric legitimately can't (a 2-peer
  role-split fabric declares its 3-peer obligation a documented gap).

Output a generated **capability matrix** — the artifact the "implementing a new
transport" skill points at as its checklist. `kuilt-nw` declares `meshDelivery = true`
(earned via the mesh), `supportsSendTo = true`, and the honest `securesTransport` from
the TLS-PSK decision. This touches every transport, so per "harden the foundation
before stacking more on top" it lands **before** the new transport (Phase 1).

## Discovery / lobby surface

To let the app actually switch off MC, `kuilt-nw` mirrors Multipeer's lobby contract:

- A `visiblePeers: StateFlow<Set<…>>` on the loom, updated reactively from
  `NWBrowser` results (peer appears / disappears), so a lobby can `collectAsState`.
- An `NwRoomHost` equivalent to `MultipeerRoomHost` for session bootstrap.

The consuming app swaps `MultipeerPeerLinkFactory` → `NwLoom` and
`MultipeerRoomHost` → `NwRoomHost` with minimal churn. Exact surface tracks the
Multipeer types so the migration is mechanical.

## Testing strategy — three tiers below hardware

1. **`commonTest` (JVM, CI):** `FakeNwApi` → the full capability TCK, including the
   3-peer obligation. **The fake wires N *distinct* `NwLoom` instances** (role-split,
   the fake radio routing between them) — **never** the same-loom-twice pattern, which
   would let the in-process `sharedPeers` crutch mask cross-device roster/identity bugs.
   This is where mesh correctness (sender attribution, roster convergence, dedup,
   spoke-free directed send) is actually proven.
2. **`appleTest` (CI macOS runner):** the **real** `RealNwApi` over **loopback TCP**
   (`NWListener`/`NWConnection` bound to `127.0.0.1` via `requiredLocalEndpoint`).
   Loopback is exempt from Local Network Privacy and needs no `includePeerToPeer`.
   **TLS-PSK is enabled over loopback** so the `sec_protocol_options` C-API path is
   CI-covered. **Honest coverage:** this exercises the real send/receive/framing/cancel
   plumbing, the connection registry + teardown, and the K/N cinterop of the connection
   surface. It **cannot** cover `NWBrowser`/Bonjour discovery (an entire subsystem with
   **zero** automated coverage below physical hardware — called out, not hidden),
   `includePeerToPeer`, AWDL, TLS over a real P2P path, the Local-Network-Privacy denial
   path, or IPv6-required behavior.
3. **`-Pnw.realnet.tests` (physical, two-device, opt-in):** real AWDL/Bonjour/TLS-PSK
   over the P2P radio, on the measured iOS 26 ↔ iOS 18 pairing. The hardware acceptance
   gate. Reuses the cross-process-probe pattern (`MultipeerCrossProcessProbe`) and the
   two-iPhone harness from the MC debugging.

Plus a **real-threaded leak/stress probe** (gated like `-Pconcurrency.stress.tests`)
that opens/closes many connections on a multi-threaded dispatcher and asserts the
registry drains to empty — the stress-grade check for reference management. And a
**weaving-window harness** (a `DelayedWovenLoom` equivalent): kuilt-nw has multi-second
real `Weaving` windows, and the suite explicitly instructs async radio fabrics to prove
frames sent during `Weaving` are not dropped.

**Open verification:** whether AWDL/true-P2P works in the iOS Simulator is
**unconfirmed** by research. Verify empirically in Phase 0; if the simulator cannot do
P2P, tier 3 is physical-only and the plan already assumes that.

## Phase 0 — connectivity spike (gates everything)

A minimal **Kotlin/Native standalone** app, deployable to two iPhones, that proves
the product question *and* the real cinterop path before we invest in the full module:

- A tiny SwiftUI harness: **Host** / **Join** buttons, a connection-state label, a
  **ping round-trip** (send a frame, echo it back, show RTT).
- Backed by a **Kotlin/Native binding calling `platform.Network` via cinterop** —
  `NWListener` + Bonjour + `includePeerToPeer` (advertise), `NWBrowser` + `NWConnection`
  (browse + dial), TLS-PSK, one framed round-trip. This is the *actual* production code
  path, not a Swift stand-in, so it de-risks the C-API TLS-PSK + block-callback bridge
  for real. The binding it produces **seeds `RealNwApi`** — throwaway UI, keeper transport.
- **What it proves:** (a) `platform.Network` cinterop compiles/links from K/N and the
  block-callback→coroutine bridge works; (b) two phones connect **Wi-Fi-off** where MC
  fails; (c) a frame round-trips; (d) simulator P2P support (empirical).

### The gate — predeclared, and it measures the *right* failure class

The MC failure is a **mid-session** AWDL teardown, not just a connect failure — so
connect rate alone is insufficient. **Predeclared bar** (set now, judged after, no
post-hoc goalposts):

- **Connect:** ≥ 8/12 successful connects Wi-Fi-off on the iOS 26 ↔ iOS 18 pairing,
  median time-to-connect under a stated bound. (Against a 1/12 baseline, 6/12 isn't even
  statistically separable — the bar must clear noise.)
- **Survive:** on ≥ 3 successful connections, a **~10-minute session-survival soak**
  with periodic pings, Wi-Fi off — a mid-session teardown is a gate **failure**.
- **Cellular probe:** repeat with a cellular-capable phone off Wi-Fi to specifically
  exercise the 808917 resolver regression.

Meets the bar ⇒ proceed to Phase 1. Does not ⇒ **stop and re-plan** (the 808917 risk may
dominate; a relay/infra-Wi-Fi fallback may be the real answer). Do not start Phase 3+
before the gate passes.

## Plan of record — PR stack

```
Phase 0  Connectivity spike       K/N standalone two-iPhone app; prove NW P2P
                                   Wi-Fi-off connect AND mid-session survival +
                                   cinterop. GATES the rest.
Phase 1  Capability-TCK refactor   All fabrics declare SeamCapabilities; matrix as
                                   the visibility artifact; +positive/3-peer obligations.
Phase 2  Transport core           commonMain NwApi + NwLoom/NwSeam (mesh) +
                                   NwConnectMachine (identity + dedup) + framing +
                                   role-split FakeNwApi capability TCK.
Phase 3  appleMain RealNwApi       cinterop against platform.Network; connection
                                   registry + teardown; loopback-TCP appleTest (+TLS).
Phase 4  macOS dylib + JVM bridge  BridgeNwApi/JNA adapting Multipeer's Bridge*.
Phase 5  Lobby discovery surface   visiblePeers + NwRoomHost; app-migration shape.
Phase 6  Hardware validation       -Pnw.realnet.tests two-device connect + soak;
                                   deprecate kuilt-multipeer (@Deprecated) here.
Phase 7  Retire kuilt-multipeer    Delete the module + its dylib bridge once downstream
                                   validation lands. Dylib surface nets to one.
```

Phases 1 and 2 are logic-only and do not need the real radio, but Phase 2's **merge is
sequenced after the Phase 0 gate** (not run in parallel with a merge-hold — that prose
gate is culturally fragile against auto-merge and invites duplicate-fix collisions).
Phase 1 (the TCK foundation) survives any Phase-0 outcome and may land independently.
Deprecation (Phase 6) is immediate; deletion (Phase 7) is gated only on downstream
validation, not a release cycle. Deleting `kuilt-multipeer` is a breaking change for any
consumer using it — file the consumer-migration notice at deprecation time.

## Side deliverable — the "implementing a new transport" skill

Authored **at the end**, from the real pain points captured while building (a running
log — `spike/PAINPOINTS.md` — kept through Phases 0–6). Its spine is the **capability
TCK**: the checklist a new fabric author works through is "declare your
`SeamCapabilities`, make each supported obligation green, register each gap with an
issue link." The skill records the cinterop / memory-model / three-place-`includePeerToPeer`
/ reference-leak / connection-dedup sharp edges this project actually hit, so the next
fabric author doesn't rediscover them.

## Risks

| Risk | Mitigation |
|---|---|
| NW P2P doesn't beat MC on real hardware (808917 cellular-off-Wi-Fi resolver regression) | Phase 0 spike gates everything; acceptance relative; predeclared connect **and** soak bars; stop-and-re-plan if not met. |
| Mid-session AWDL teardown (the actual MC failure class) slips past a connect-only gate | Phase 0 gate includes a ~10-min Wi-Fi-off session-survival soak. |
| Connection reference leak / silent cancel | Dedicated registry + cancel-first-then-drop + no-`nw_*`-under-lock + `closing` flag + real-threaded leak probe + TCK teardown obligations. |
| Double-dial → duplicate connections | Deterministic dedup tie-break (lower-`PeerId` dialer wins) in the handshake; registry keyed by `PeerId`. |
| `PeerId` collision across devices (Nearby's counter bug) | UUID-grade random / caller-injected `PeerId`; cross-loom uniqueness test in the role-split harness. |
| TLS-PSK is a broadcast key (no real auth) | Honest `securesTransport` threat model; optional join-code-derived PSK carried in `Rendezvous`, not Bonjour. Decided in Phase 2. |
| `includePeerToPeer` set in too few places | Checklist item; loopback tier can't catch it (no P2P) — Phase 0 + tier 3 do. |
| Simulator can't do P2P | Assume physical-only for tier 3; verify empirically in Phase 0. |
| Bonjour discovery has no coverage below hardware | Acknowledged explicitly; Phase 0 + tier 3 are its only proof; not claimed otherwise. |
| K/N cinterop surprise vs. ergonomic Swift | Phase 0 spike is K/N, not Swift — proves the real path incl. C-API TLS-PSK. |
| Short range / no SLA | Out of transport's control; infra-Wi-Fi fallback remains available via the WebSocket relay fabric. |

## References

- Apple DevForums 803339 — the iOS 26 MC AWDL-teardown regression.
- Apple DevForums 808917 — the *separate*, still-open NW-P2P cellular-off-Wi-Fi
  resolver regression (the honest seam).
- Apple DTS — "Moving from Multipeer Connectivity to Network Framework" (TLS-PSK for P2P).
- Apple TN3179 — Understanding Local Network Privacy.
- In-tree templates: `kuilt-nearby` (architecture; do not inherit its `PeerId` counter),
  `:kuilt-stream`/`:kuilt-tcp` (framing), `SeamConformanceSuite` (TCK),
  `MCSessionLink` (`closing`-flag + receive-bridge precedents), `kuilt-multipeer`
  `Bridge*` (JVM bridge).
