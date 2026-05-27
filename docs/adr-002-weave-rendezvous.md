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

**No in-repo extra implementor.** An earlier draft flagged `transport-multipeer/.../MultipeerPeerLinkFactory` as a sixth `Loom` implementor in the consumer — that module was **deleted in #1613** (kuilt Phase 1e; transport now lives only in `:kuilt-multipeer`, game glue in `:live-multipeer`). So `kuilt-multipeer` is the only multipeer implementor. (The original recon ran on a 7-commit-stale checkout; re-verified against current `origin/main`, which also renamed `PeerLink*`→`Seam*` and `Multipeer*`→`MC*` in #1630 — the names below are current.)

## Consumer blast radius (fireworks-compose, re-verified against current `origin/main`)

**One chokepoint, and it's tiny.** `SeamLiveTransport` (`live-runtime`; renamed from `PeerLinkLiveTransport` in #1630) is the only consumer code that calls a `Loom` directly, via its `host`/`join`/`auto` factory methods:

```kotlin
host(factory, config)        →  factory.open(config)              // → factory.host(config)   [1-line rename]
join(factory, advertisement) →  factory.join(advertisement)       // unchanged (wrapper survives)
auto(opener, config)         →  opener.openWithServerRole(config) // unchanged (dynamic role unaffected)
```

With the `host`/`join` wrappers, the **~22** downstream sites that call `SeamLiveTransport.host/join/auto` change **zero**: composeApp `LANLobbyScreen.{jvm,android,ios,wasmJs}`, `QuickPlayScreen.{jvm,ios}`, `LiveDemoModule.{jvm,wasmJs}`, `IosMCSessionFactory`, `RemoteSpectateRuntimeFactory`, + tests (`SeamLiveTransportTest`, `PairedLanRealNetworkTest`, `PairedRuntimeHarness`).

**Direct `Loom` rendezvous callers** — the only sites that rename `open(`→`host(` (every `join` caller is unchanged, the wrapper survives):

| Call site | `open`→`host` | `join` (unchanged) |
|---|---|---|
| `live-runtime/SeamLiveTransport.kt` (chokepoint, prod) | `:57` | `:72` |
| `live-multipeer/MCLeaderListener.kt` (prod) | `:62` | — |
| `data-remote/HttpRemoteGameRepository.kt` | — | `:64` |
| `session-protocol` tests (`MembershipTest`, `TestHelpers`) | 3 | 3 |
| `live-runtime` tests (`SeamLiveTransportTest`, `WebRtcHostJoinerIntegrationTest`) | 7 | — |
| `live-multipeer` test (`MCLeaderListenerTest`) | 5 | — |

Net consumer cost with wrappers: **2 production** `open`→`host` renames (`SeamLiveTransport.kt:57`, `MCLeaderListener.kt:62`) + ~15 test-line renames; **zero** behavioural change; the `includeBuild` build stays green the moment the wrappers ship. Dynamic role (`LiveDemoModule.wasmJs.kt:97` `openWithServerRoleResult`) is untouched. Without wrappers (hard break): every site above rewrites to `weave(New(...))`/`weave(Existing(...))` in one coordinated commit — larger, and forces kuilt+consumer to move together.

**`includeBuild` lockstep:** fireworks-compose consumes kuilt via a presence-gated `includeBuild ../kuilt` override. A breaking kuilt API change breaks the fireworks build the instant both are checked out locally — so the abstract-method change (`open`/`join` → `weave`) must land in kuilt **and** the consumer's `PeerLinkLiveTransport` one-line edit must land together, *unless* the wrappers absorb it. Wrappers are what make this not a single atomic cross-repo commit.

## Migration sequence (PR stack)

Strictly serial unless noted. Foundation-first: `weave` lands before ADR-001's harness reshape.

1. **ADR-002 + ADR-001 update** (this PR #10, docs-only) → land.
2. **`:kuilt-core`: add `Rendezvous`, add `weave` + `host`/`join` wrappers, migrate `InMemoryLoom`** to `override fun weave`. Existing `InMemoryLoomTest` stays green. *(serial — everything below depends on the contract)*
3. **Each fabric's `weave`** — can **parallelize** (4 independent PRs, each its own module): websocket, mdns, webrtc, multipeer. Multipeer is the heaviest (`expect` + 4 actuals). No consumer-side multipeer impl to migrate (`:transport-multipeer` already deleted, #1613).
4. **Version bump + consumer migration** — `SeamLiveTransport.kt:57` one-line `open`→`host`; `MCLeaderListener.kt:62`; the ~15 test-line renames. *(serial after 2–3; gated by the `includeBuild` break if wrappers are *not* used — with wrappers this is low-risk and can trail.)*
5. **ADR-001 conformance reshape** (`newLoom()` → `newLoomPair()`) rebuilt to drive `weave` / `host` / `join`. *(serial after the contract is stable — building the harness on the final contract avoids re-touching it.)*
6. **Four fabric conformance tests** — parallelize (per ADR-001 §Sequencing: webrtc → multipeer → websocket → mdns by tractability).

**Parallelizable:** step 3's four fabric `weave` migrations; step 6's four conformance tests.
**Strictly serial:** 1 → 2 → (3) → 5 → (6). Step 4 can trail 3 if wrappers ship (otherwise it's pinned to 3).

## Consequences

- `Loom`'s abstract surface changes: `open`/`join` (abstract) → `weave` (abstract) + `host`/`join` (default wrappers). `explicitApi()` requires explicit `public` on all three.
- `Rendezvous` is new public API in `:kuilt-core`.
- Every fabric implementor rewrites its dispatch into a single `when (rendezvous)`. Multipeer touches 5 source sets (`expect` + 4 actuals in `:kuilt-multipeer`; no consumer-side impl — `:transport-multipeer` deleted in #1613).
- Dynamic role (`openWithServerRole*`) is explicitly **out of `weave`** and documented as the relay-assigned escape hatch — no behavioural change to Quick Play.
- Docs to update when implementing: `CLAUDE.md` "one-paragraph orientation" (`open`/`join` → `weave`), `docs/architecture.md`, `docs/usage.md`.
- **ADR-001 stays valid.** `weave` does not remove the two-endpoint need — the listen/connect asymmetry is in the transport, so `newLoomPair()` is still required (a host Loom + a joiner Loom). ADR-001's suite sketches now drive `weave(New(...))` / `weave(Existing(...))` (or the wrappers); the `newLoomPair()` decision is unchanged.
- **Phase-4 `:live-runtime` refactor** ([#1519](https://github.com/tractat-us/fireworks-compose/issues/1519)) is the major consumer of `weave`; the `LiveChannel`→`Seam` rework should target `weave` directly rather than the wrappers, so its host/joiner branches read the role from `Rendezvous`.

## Alternatives considered

1. **Add a third `Auto`/`RoleAssigned` variant so `openWithServerRole` folds into `weave`** — *rejected.* Role assignment must return the assigned role; `weave(): Seam` can't. Folding it in forces a `Pair`/wrapper return on all fabrics for a one-fabric concern.
2. **Hard break to `weave`-only, no wrappers** — *defensible* (pre-1.0 posture) but *rejected* for now: it forces kuilt + consumer to migrate in one atomic cross-repo commit (the `includeBuild` lockstep), for the sake of removing ~4 lines of wrapper. Reconsider if the wrappers start hiding real role-confusion bugs at call sites.
3. **Keep `open`/`join` abstract, add `weave` as a default that dispatches to them** — *rejected.* Inverts the intended direction (`weave` should be the primitive); leaves the two-method surface as the thing every fabric still implements, defeating the "one call surface" goal.
4. **`Rendezvous` as an `enum` + separate `Pattern`/`Tag` args** — *rejected.* The whole point is carrying the role *with* its payload as data; an enum splits them back apart.
