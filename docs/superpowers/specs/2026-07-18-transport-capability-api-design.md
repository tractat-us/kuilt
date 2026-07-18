# Transport-capability API — unified availability + role reporting

**Issue:** [#1530](https://github.com/tractat-us/kuilt/issues/1530)
**Date:** 2026-07-18
**Status:** design approved, ready for planning

## Problem

Every `Loom`/`Seam` should report, at runtime, **(a)** whether its fabric is
usable on this device right now and **(b)** what *role(s)* it plays
(discovery / data / wifi-infra / bluetooth / webrtc / server-relay). The
consuming app (fireworks-compose #3485) wants to turn a failed/impossible
connect into specific, actionable guidance ("Bluetooth is off", "Wi-Fi radio is
off", "can't reach the server") instead of a silent stall — and this same report
is the natural input a future `CompositeSeam` uses to pick/compose transports.

Today only a thin slice exists: `Loom.availability(): FabricAvailability`, a
one-shot `Available | Unavailable(reason)` check on the factory. It has no
`Unknown` state (iOS often can't give ground truth), no role concept, and is not
live (can't observe a radio being toggled off mid-session).

## Approved shape

Two axes, decided in brainstorming:

1. **Placement:** *both* a pre-connect one-shot on `Loom` **and** a live
   `StateFlow` on `Seam`.
2. **Role:** a `Set<TransportRole>` (a transport may hold several roles at once —
   Nearby is Bluetooth + Wi-Fi; NW is Bonjour discovery + data), modeled as a
   **sealed hierarchy** so a novel fabric can add a case without editing a closed
   enum.
3. **Unification:** *fully unified* — `capability()` becomes the single primary
   method on `Loom`; the existing `availability()` becomes a derived default. The
   ~6 existing `override fun availability()` sites migrate to
   `override fun capability()`. We do not carry two overlapping methods.

## Core types (`:kuilt-core`)

```kotlin
sealed interface FabricAvailability {
    data object Available : FabricAvailability
    data class Unavailable(val reason: String) : FabricAvailability
    data class Unknown(val reason: String) : FabricAvailability   // NEW
}

sealed interface TransportRole {
    data object Discovery   : TransportRole
    data object Data        : TransportRole
    data object WifiLan     : TransportRole   // access-point / same-network (mDNS, WebSocket-LAN)
    data object WifiDirect  : TransportRole   // peer-to-peer Wi-Fi, no AP (AWDL/Multipeer, Nearby)
    data object Bluetooth   : TransportRole
    data object WebRtc      : TransportRole
    data object ServerRelay : TransportRole
}

data class TransportCapability(
    val roles: Set<TransportRole>,
    val availability: FabricAvailability,
)
```

`FabricAvailability` stays the atomic "can I use it" lattice, now three-valued;
`TransportCapability` adds the "what is it" dimension. Reusing
`FabricAvailability` (not a parallel new `Availability`) *is* the unification —
one availability concept everywhere.

**`Unknown` semantics:** best-effort platforms that cannot determine ground truth
(iOS: no SSID query; Local-Network permission not yet probed) report
`Unknown(reason)` rather than guessing `Available`/`Unavailable`. `Unknown` is
distinct from a target-scoped-out fabric (which is simply *absent*, per the
existing `FabricAvailability` KDoc).

## `Loom` — pre-connect snapshot

`capability()` is the single primary method; `availability()` derives from it and
is no longer overridden anywhere:

```kotlin
interface Loom {
    /** What this fabric is and whether it can be attempted now. */
    fun capability(): TransportCapability =
        TransportCapability(roles = emptySet(), availability = FabricAvailability.Available)

    /** Derived convenience — the availability half of [capability]. Do not override. */
    fun availability(): FabricAvailability = capability().availability
}
```

Concrete fabrics move their existing availability logic into `capability()` and
declare static roles:

| Fabric | roles | availability logic (unchanged, relocated) |
|---|---|---|
| WebSocket (`KtorClientLoom`/`KtorServerLoom`) | `{ServerRelay, Data}` | `Available` |
| NW (`NwLoom`) | `{Discovery, Data}` | delegate `api.availability()` (macOS-arm64 + dylib) |
| Multipeer | `{Discovery, Data, WifiDirect, Bluetooth}` | native-lib gate |
| Nearby | `{Bluetooth, WifiDirect, Data}` | Play-Services gate |
| mDNS | `{Discovery, WifiLan}` | (whatever it reports today) |
| WebRTC | `{WebRtc, Data}` | `Available` where present |
| InMemory / test looms | `emptySet()` | `Available` |
| CompositeLoom | union of live plies' roles | `Available` if any ply is (existing logic) |

## `Seam` — live StateFlow

```kotlin
interface Seam {
    /**
     * Live capability of the fabric carrying this session. Updates as radios,
     * permissions, and network paths change. Default: a single static value
     * (the woven fabric's roles, Available) — fabrics with real OS observers
     * override to make it reactive.
     */
    val capability: StateFlow<TransportCapability>
        get() = /* single static value derived from the fabric */
}
```

**Scope boundary (decided):** the initial landing wires the *plumbing* + static
reporting everywhere. **No fabric is forced to implement OS observers.** Real
reactive observers (`NWPathMonitor`, `CBCentralManager` delegate, GMS listeners)
land as **follow-up issues, one per fabric** — filed at plan time. This keeps the
first PR small and matches the repo's incremental, aggressive-merge posture.

The interface default returns a shared `Available`/`emptySet()` floor (exposed via
`asStateFlow()` so it can't be downcast-and-mutated). Roles are *not* seeded into
every concrete fabric `Seam` in this PR — that would be per-fabric work. Instead:

- **`CompositeSeam`** unions roles from the constituent **`Loom`s** (which it holds
  in `desired`), for currently-`Woven` plies — this is where the aggregated report
  matters, and roles live statically on the `Loom`.
- A **direct** (non-composite) fabric `Seam` reports the floor (roleless
  `Available`) until its per-fabric live-observer follow-up seeds it. Acceptable
  because the pre-connect role answer already comes from `Loom.capability()`; the
  Seam-level report becomes rich when the follow-ups add real OS observers.

## Composition — `CompositeSeam` / `CompositeLoom`

- `CompositeLoom.capability()` already aggregates availability across plies —
  extend to **union `roles`** across plies and keep the existing "Available if any
  ply is" availability rollup.
- `CompositeSeam.capability` rolls up the live plies' `StateFlow`s: union of roles
  across currently-`Woven` plies, availability = `Available` if any ply is. This
  is the "input a future `CompositeSeam` uses to pick/compose transports" the
  issue names — the aggregation lands now, selection logic is out of scope.

## Conformance & tests

- **`SeamConformanceSuite`** obligation (6) at ~line 408 currently asserts
  `availability()` returns `Available || Unavailable`. **Widen** to also accept
  `Unknown`. Add an ungated obligation: `capability().roles` is non-empty for a
  real fabric (test/in-memory looms exempt via a capability flag), and
  `capability.value.availability == Available` while `Woven`.
- **`FabricAvailabilityTest`** — add an `Unknown` round-trip case.
- **Test `Seam`/`Loom` fakes** (~4 in composite tests, `FakeNwApi`,
  `FakeNearbyRadio`, `ControllableLoom`, `DelayedWovenLoom`, gossip conformance):
  migrate `override fun availability()` → `override fun capability()`; the
  `Seam.capability` default `get()` covers most without edits.

## Naming-collision hazard (call out in the PR)

`:kuilt-conformance`'s `SeamConformanceSuite` already uses **"capability"**
heavily for `SeamCapabilities` — the *test-harness feature flags* that gate
optional conformance obligations. That is a **different concept** from this
transport `TransportCapability`. Keep the type name `TransportCapability` (not a
bare `Capability`) to avoid confusion, and do not overload the conformance
suite's `capabilityGaps()` / `SeamCapabilities` vocabulary.

## Out of scope (follow-up issues, filed at plan time)

- Per-fabric **live OS observers** (`NWPathMonitor`, `CBCentralManager`, GMS
  listeners, WebRTC ICE state) — one issue per fabric.
- `CompositeSeam` **transport-selection** logic that consumes the aggregated
  capability (this spec only lands the aggregated *report*).
- Any fireworks-compose #3485 consumer-side UX.

## Migration checklist (mechanical)

1. Add `Unknown` to `FabricAvailability`; add `TransportRole`, `TransportCapability`.
2. Add `Loom.capability()` (primary) + derived `availability()`; **remove** every
   `override fun availability()`, replace with `override fun capability()`.
3. Add `Seam.capability` StateFlow default; seed concrete `Seam` impls.
4. Extend `CompositeLoom`/`CompositeSeam` role-union + rollup.
5. Widen conformance obligation (6) for `Unknown`; add roles/live obligations.
6. Update `FabricAvailabilityTest`, sample (`LoomSamples.kt`), and KDoc/`@sample`.
7. File per-fabric live-observer follow-ups + the CompositeSeam-selection follow-up.
```
