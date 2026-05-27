# ADR-002 — `weave(Rendezvous)`: one rendezvous call surface

**Status:** Proposed
**Date:** 2026-05-27
**Tracks:** epic [tractat-us/fireworks-compose#1515](https://github.com/tractat-us/fireworks-compose/issues/1515), issue [tractat-us/fireworks-compose#1617](https://github.com/tractat-us/fireworks-compose/issues/1617)
**Amends:** [ADR-034 (fireworks-compose)](https://github.com/tractat-us/fireworks-compose/blob/main/docs/adr-034-cohered-transport-contract.md) — the Loom/Seam/Swatch contract. kuilt now owns that contract, so the amendment lives here, cross-referenced from ADR-034.
**Foundation for:** ADR-001 (the conformance harness is built on top of this contract).

## Context

`Loom` exposes two rendezvous methods today:

```kotlin
public suspend fun open(config: Pattern): Seam   // host / start a new session
public suspend fun join(advertisement: Tag): Seam  // join an existing one
```

Most peers don't host. The user's framing: *"A few known locations (servers/game hosts) will use the newHost… and all the rest will simply pass a tag and have things magically connect."* Two methods make the common case (join-by-tag) and the rare case (host) look equally weighted, and they give the symmetric / dynamic-role case (neither peer knows its role; a relay decides) no home — it lives ad-hoc as `WebRTCPeerLinkFactory.openWithServerRole`.

**Decided (foundation-first):** collapse the two into one `weave(rendezvous: Rendezvous)` where `Rendezvous` is a sum type carrying the role as data.

**Crucial: `weave` does not remove the listen/connect asymmetry — it relocates it.** A `KtorServerLoom` still supports only `New`; a wasm WebRTC client still supports only `Existing`. The unsupported variant still throws — *exactly where `open`/`join` throw today* — but behind one call surface. The payoff is (a) one method, the role carried in data, and (b) a natural place for dynamic role assignment.

## Decision

### `Rendezvous` — two variants, dynamic role stays separate

```kotlin
public sealed interface Rendezvous {
    /** Host / start a new session. The small set of explicit hosts. */
    public data class New(val pattern: Pattern) : Rendezvous
    /** Join an existing session by its advertisement. The common default. */
    public data class Existing(val tag: Tag) : Rendezvous
}
```

**No third `Auto`/`RoleAssigned` variant.** The symmetric case can't fold into `weave` cleanly, because role assignment must *return* the assigned role to the caller: `openWithServerRoleResult(): Pair<Boolean, Seam>` (the `isHost` flag selects `LiveLeader` vs client-joiner downstream — see `LiveDemoModule.wasmJs`). `weave(rendezvous): Seam` has no channel for that flag. Three escape hatches were weighed (return `Pair<Role, Seam>` from `weave` for *all* fabrics, stuff role into the `Seam`, or a `RendezvousResult` wrapper) and all three pollute the common single-`Seam` return for a one-fabric concern. So dynamic role keeps its own method (`weave` for the two known-role cases; `openWithServerRole*` for the symmetric case), exactly as the consumer already models it via `PeerLinkLiveTransport.auto(AutoPeerLinkOpener)`. Revisit only if a second fabric needs relay-assigned roles.

### `weave` signature — with thin convenience wrappers (no hard break)

```kotlin
public interface Loom {
    public suspend fun weave(rendezvous: Rendezvous): Seam

    /** Convenience: host a new session. */
    public suspend fun host(pattern: Pattern): Seam = weave(Rendezvous.New(pattern))
    /** Convenience: join an existing session. */
    public suspend fun join(tag: Tag): Seam = weave(Rendezvous.Existing(tag))

    public fun availability(): FabricAvailability = FabricAvailability.Available
}
```

`weave` is the one abstract method each fabric implements. `host`/`join` are **default interface methods** delegating to it — terse common call sites, and an in-tree consumer (`PeerLinkLiveTransport`) that needn't change in lockstep with the kuilt release. The old abstract `open(Pattern)` is **removed** (renamed to the `host` wrapper); the old `join(Tag)` becomes the wrapper. This is a breaking change to the *implementor* surface (every fabric overrides `weave` not `open`/`join`) but a near-non-breaking change to the *caller* surface (`host`/`join` survive as wrappers, `open` is the one rename).

kuilt is pre-1.0 with an explicit "aggressive, fix-forward, breaking changes expected" posture, so a hard break to `weave`-only was on the table. **Rejected** because the wrappers cost ~4 lines, keep the common call sites readable, and — load-bearing — **decouple the kuilt version bump from the consumer migration** under the `includeBuild` lockstep (below). A hard break forces both repos to move in one commit; wrappers let the consumer migrate on its own schedule.

## Per-fabric dispatch (verified against current source)

Each fabric's `weave` switches on `Rendezvous`; the unsupported variant throws `UnsupportedOperationException` at the same boundary it does today.

| Fabric | `New` | `Existing` | Notes (verified) |
|---|---|---|---|
| **InMemoryLoom** (`kuilt-core/.../InMemoryLoom.kt`) | ✅ register into mesh | ✅ register into mesh | Reference impl; canonical both-variants `weave`. |
| **websocket** `KtorServerLoom` (jvmAndAndroid) | ✅ `nextLink()` | ❌ throws | `open(Pattern)` is already `nextLink()`; `join` already throws (`KtorServerLoom.kt:86`). |
| **websocket** `KtorClientLoom` (common) | ❌ throws | ✅ connect to `WebSocketAdvertisement` | `open` already throws (`KtorClientLoom.kt:37`). |
| **mdns** `MDNSPeerLinkFactory` (jvm) | ✅ register + server `weave(New)` | ✅ `WebSocketAdvertisement` client | Supports both; delegates to the websocket looms (`MDNSPeerLinkFactory.kt:69,79`). |
| **multipeer** `MultipeerPeerLinkFactory` (`expect` common + 4 actuals: apple/jvm/wasmJs/android) | ✅ `check(activeSession==null)` then open | ✅ same guard then join | Both supported, single-session. **The `expect` declaration changes → every `actual` changes** (5 source sets). |
| **webrtc** `WebRTCPeerLinkFactory` (wasmJs) | ✅ host handshake | ✅ joiner handshake | Both supported. `openWithServerRole`/`openWithServerRoleResult` stay as-is — *not* folded into `weave` (see above). |

**In-repo extra implementor (fireworks-compose, not yet retired):** `transport-multipeer/.../MultipeerPeerLinkFactory` is a *parallel* implementation of kuilt's `Loom` still living in the consumer repo (`expect` common + apple/jvm/wasmJs/android/macos actuals). It implements the contract, so it migrates to `weave` alongside `kuilt-multipeer`. Confirm whether it's slated for deletion before this lands — if so, the migration is moot; if not, it's a sixth `expect`/`actual` set to touch.

## Consumer blast radius (fireworks-compose, recon read-only)

**The asymmetry is genuine, but the consumer surface is tiny — one chokepoint.** Almost every consumer `open`/`join` flows through `PeerLinkLiveTransport` (`live-runtime`), whose three factory methods are the *only* places that call a `Loom` directly:

```kotlin
host(factory, config)        →  factory.open(config)              // → factory.host(config)
join(factory, advertisement) →  factory.join(advertisement)       // unchanged (wrapper survives)
auto(opener, config)         →  opener.openWithServerRole(config) // unchanged (dynamic role unaffected)
```

If the `host`/`join` wrappers ship, **`PeerLinkLiveTransport` changes one line** (`factory.open(config)` → `factory.host(config)`) and the ~15 downstream sites that call `PeerLinkLiveTransport.host/join/auto` change **zero**:

| Call site (via `PeerLinkLiveTransport`) | Count | Changes? |
|---|---|---|
| `composeApp` UI / DI (`LANLobbyScreen.{jvm,android,ios,wasmJs}`, `QuickPlayScreen.jvm`, `LiveDemoModule.{jvm,wasmJs}`) | ~10 | No |
| `live-runtime` `RemoteSpectateRuntimeFactory`, tests (`PeerLinkLiveTransportTest`, `WebRtcHostJoinerIntegrationTest`) | ~3 files | No |
| `composeApp` tests (`PairedLanRealNetworkTest`, `PairedRuntimeHarness`) | 2 | No |

**Direct `Loom` callers (bypass the chokepoint) — these change only if the wrapper they use is renamed:**

| Call site | Call | Under the wrapper plan |
|---|---|---|
| `live-multipeer/MultipeerLeaderListener.kt:62` | `factory.open(sessionConfig)` | `factory.host(sessionConfig)` (one rename) |
| `data-remote/HttpRemoteGameRepository.kt:64` | `loom.join(advertisement)` | unchanged (`join` wrapper survives) |
| `live-runtime/PeerLinkLiveTransport.kt:57,72` | `factory.open` / `factory.join` | `.host` rename + `.join` unchanged |
| `session-protocol` tests (`TestHelpers`, `MembershipTest`) | `factory.open(...)` / `factory.join(...)` | `.host` rename + `.join` unchanged |
| `live-multipeer` tests (`MultipeerLeaderListenerTest`) | `factory.open(...)` ×6 | `.host` rename |
| `live-runtime` tests (`PeerLinkLiveTransportTest`, `WebRtcHostJoinerIntegrationTest`) | `factory.open(...)` | `.host` rename |

Net consumer cost with wrappers: ~6 files renaming `open(` → `host(`; **zero** behavioural change; the `includeBuild` build stays green the moment the wrappers ship. Without wrappers (hard break): every site above rewrites to `weave(New(...))`/`weave(Existing(...))` in one coordinated commit — larger, and forces kuilt+consumer to move together.

**`includeBuild` lockstep:** fireworks-compose consumes kuilt via a presence-gated `includeBuild ../kuilt` override. A breaking kuilt API change breaks the fireworks build the instant both are checked out locally — so the abstract-method change (`open`/`join` → `weave`) must land in kuilt **and** the consumer's `PeerLinkLiveTransport` one-line edit must land together, *unless* the wrappers absorb it. Wrappers are what make this not a single atomic cross-repo commit.

## Migration sequence (PR stack)

Strictly serial unless noted. Foundation-first: `weave` lands before ADR-001's harness reshape.

1. **ADR-002 + ADR-001 update** (this PR #10, docs-only) → land.
2. **`:kuilt-core`: add `Rendezvous`, add `weave` + `host`/`join` wrappers, migrate `InMemoryLoom`** to `override fun weave`. Existing `InMemoryLoomTest` stays green. *(serial — everything below depends on the contract)*
3. **Each fabric's `weave`** — can **parallelize** (4 independent PRs, each its own module): websocket, mdns, webrtc, multipeer. Multipeer is the heaviest (`expect` + 4 actuals); if `transport-multipeer` in the consumer is also live, its 6 source sets migrate in the same cross-repo step.
4. **Version bump + consumer migration** — `PeerLinkLiveTransport` one-line `open`→`host`; the ~6 direct-caller `open(`→`host(` renames. *(serial after 2–3; gated by the `includeBuild` break if wrappers are *not* used — with wrappers this is low-risk and can trail.)*
5. **ADR-001 conformance reshape** (`newLoom()` → `newLoomPair()`) rebuilt to drive `weave` / `host` / `join`. *(serial after the contract is stable — building the harness on the final contract avoids re-touching it.)*
6. **Four fabric conformance tests** — parallelize (per ADR-001 §Sequencing: webrtc → multipeer → websocket → mdns by tractability).

**Parallelizable:** step 3's four fabric `weave` migrations; step 6's four conformance tests.
**Strictly serial:** 1 → 2 → (3) → 5 → (6). Step 4 can trail 3 if wrappers ship (otherwise it's pinned to 3).

## Consequences

- `Loom`'s abstract surface changes: `open`/`join` (abstract) → `weave` (abstract) + `host`/`join` (default wrappers). `explicitApi()` requires explicit `public` on all three.
- `Rendezvous` is new public API in `:kuilt-core`.
- Every fabric implementor rewrites its dispatch into a single `when (rendezvous)`. Multipeer touches 5 source sets (+6 if `transport-multipeer` is still live).
- Dynamic role (`openWithServerRole*`) is explicitly **out of `weave`** and documented as the relay-assigned escape hatch — no behavioural change to Quick Play.
- Docs to update when implementing: `CLAUDE.md` "one-paragraph orientation" (`open`/`join` → `weave`), `docs/architecture.md`, `docs/usage.md`.
- **ADR-001 stays valid.** `weave` does not remove the two-endpoint need — the listen/connect asymmetry is in the transport, so `newLoomPair()` is still required (a host Loom + a joiner Loom). ADR-001's suite sketches now drive `weave(New(...))` / `weave(Existing(...))` (or the wrappers); the `newLoomPair()` decision is unchanged.
- **Phase-4 `:live-runtime` refactor** ([#1519](https://github.com/tractat-us/fireworks-compose/issues/1519)) is the major consumer of `weave`; the `LiveChannel`→`Seam` rework should target `weave` directly rather than the wrappers, so its host/joiner branches read the role from `Rendezvous`.

## Alternatives considered

1. **Add a third `Auto`/`RoleAssigned` variant so `openWithServerRole` folds into `weave`** — *rejected.* Role assignment must return the assigned role; `weave(): Seam` can't. Folding it in forces a `Pair`/wrapper return on all fabrics for a one-fabric concern.
2. **Hard break to `weave`-only, no wrappers** — *defensible* (pre-1.0 posture) but *rejected* for now: it forces kuilt + consumer to migrate in one atomic cross-repo commit (the `includeBuild` lockstep), for the sake of removing ~4 lines of wrapper. Reconsider if the wrappers start hiding real role-confusion bugs at call sites.
3. **Keep `open`/`join` abstract, add `weave` as a default that dispatches to them** — *rejected.* Inverts the intended direction (`weave` should be the primitive); leaves the two-method surface as the thing every fabric still implements, defeating the "one call surface" goal.
4. **`Rendezvous` as an `enum` + separate `Pattern`/`Tag` args** — *rejected.* The whole point is carrying the role *with* its payload as data; an enum splits them back apart.
