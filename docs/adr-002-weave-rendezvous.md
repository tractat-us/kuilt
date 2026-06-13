# ADR-002 — `weave(Rendezvous)`: one rendezvous call surface

**Status:** Proposed
**Date:** 2026-05-27
**Amends:** the prior `Loom`/`Seam`/`Swatch` contract — kuilt now owns that contract, so the amendment lives here.
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

### `weave` signature — wrappers only (no `open` alias)

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

`weave` is the one abstract method each fabric implements. `host`/`join` are **default interface methods** delegating to it. The deprecated `open(Pattern)` alias was removed after consumers migrated off it (kuilt v0.2.0/0.2.1). This is a breaking change to `Loom`'s API surface — consumers must rename `open(...)` → `host(...)`.

kuilt is pre-1.0 with an explicit "aggressive, fix-forward, breaking changes expected" posture, so a hard break to `weave`-only was on the table. **Rejected** because the wrappers cost ~4 lines, keep the common call sites readable, and — load-bearing — **decouple the kuilt version bump from the consumer migration** under the `includeBuild` lockstep (below). Keeping `open` as a deprecated alias takes this all the way: the consumer migration drops to **zero files** at landing time and becomes Phase 4's cleanup. A hard break would instead force both repos to move in one atomic commit.

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

**No in-repo extra implementor.** `:kuilt-multipeer` is the only multipeer implementor.

## Migration sequence (PR stack)

Foundation-first: `weave` lands before ADR-001's harness reshape.

1. **ADR-002 + ADR-001** (this PR #10, docs-only) → land.
2. **kuilt contract PR — one PR across `:kuilt-core` + the 4 fabric modules.** Add `Rendezvous`; add `weave` (abstract) + `host`/`join` + deprecated `open` (defaults); migrate all 5 implementors (`InMemoryLoom` + websocket/mdns/webrtc/multipeer) to `override fun weave` with a `when (rendezvous)`. Making `weave` abstract breaks every fabric until it overrides, so this is **one PR**, not core-then-fabrics (small repo; 5 trivial rewraps; multipeer heaviest — `expect` + 4 actuals). Per-fabric tests stay green.
3. **kuilt version bump.** `open` stays deprecated during the migration window. Consumers rename `open`→`host` at their pace.
4. **Consumer migration complete; `open` alias removed.** Alias deleted from kuilt in this PR.
5. **ADR-001 conformance reshape** (`newLoom()` → `newLoomPair()`), rebuilt to drive `weave`/`host`/`join`. *(after the contract is stable — building the harness on the final contract avoids re-touching it.)*
6. **Four fabric conformance tests** — parallelize (ADR-001 §Sequencing: webrtc → multipeer → websocket → mdns).

**Serial:** 1 → 2 → 3 → 4 → 5 → 6. **Parallel within a step:** step 6's four conformance tests.

## Consequences

- `Loom`'s surface: `open`/`join` (abstract) → `weave` (abstract) + `host`/`join` (defaults). `explicitApi()` requires explicit `public` on all of them. The deprecated `open` alias has been removed.
- `Rendezvous` is new public API in `:kuilt-core`.
- Every fabric implementor rewrites its dispatch into a single `when (rendezvous)`. Multipeer touches 5 source sets (`expect` + 4 actuals in `:kuilt-multipeer`).
- Dynamic role (`openWithServerRole*`) is explicitly **out of `weave`** and documented as the relay-assigned escape hatch.
- Docs to update when implementing: `CLAUDE.md` "one-paragraph orientation" (`open`/`join` → `weave`), `docs/architecture.md`, `docs/usage.md`.
- **ADR-001 stays valid.** `weave` does not remove the two-endpoint need — the listen/connect asymmetry is in the transport, so `newLoomPair()` is still required (a host Loom + a joiner Loom). ADR-001's suite sketches now drive `weave(New(...))` / `weave(Existing(...))` (or the wrappers); the `newLoomPair()` decision is unchanged.
- **The `host`/`join` wrappers decouple kuilt from any consumer migration** — existing `open(...)` call sites continue to compile (deprecated warnings only) while consumers migrate at their own pace.

## Alternatives considered

1. **Add a third `Auto`/`RoleAssigned` variant so `openWithServerRole` folds into `weave`** — *rejected.* Role assignment must return the assigned role; `weave(): Seam` can't. Folding it in forces a `Pair`/wrapper return on all fabrics for a one-fabric concern.
2. **Hard break to `weave`-only, no wrappers** — *defensible* (pre-1.0 posture) but *rejected* for now: it forces kuilt + consumer to migrate in one atomic cross-repo commit (the `includeBuild` lockstep), for the sake of removing ~4 lines of wrapper. Reconsider if the wrappers start hiding real role-confusion bugs at call sites.
3. **Keep `open`/`join` abstract, add `weave` as a default that dispatches to them** — *rejected.* Inverts the intended direction (`weave` should be the primitive); leaves the two-method surface as the thing every fabric still implements, defeating the "one call surface" goal.
4. **`Rendezvous` as an `enum` + separate `Pattern`/`Tag` args** — *rejected.* The whole point is carrying the role *with* its payload as data; an enum splits them back apart.
