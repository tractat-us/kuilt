# ADR-005 — Advertise a per-peer Bonjour instance name under `Rendezvous.New`

**Status:** Accepted
**Date:** 2026-08-15
**Resolves:** the discovery/dial identity split recorded in #2416.
**Supersedes in part:** the shared-session-name assumption that ADR-002's `Rendezvous.New` arm
acquired in `:kuilt-nw` (never a contract requirement — an implementation choice made in `NwLoom`).

## Context

`:kuilt-nw` picks the advertised Bonjour instance name from the rendezvous:

```kotlin
val serviceName = when (rendezvous) {
    is Rendezvous.New -> rendezvous.pattern.sessionName   // SHARED by every peer
    is Rendezvous.Existing -> selfId.value                // already per-peer
}
```

Under `Rendezvous.New` every peer therefore advertises the **same** instance name, and identity and
dial target end up keyed on different things:

- **Identity** comes from the Bonjour TXT record (Option A, #1502) — a real per-peer `PeerId`.
- **The dial** goes to the stored browse *endpoint object*, which is a **named** Bonjour service.
  mDNS **re-resolves that name at connect time**.

So the loom can correctly identify peer `X` from TXT and then dial a name that resolves to a
different device — including itself — because within the pre-rename collision window both devices
hold the same instance name. mDNS conflict resolution renames one to `… (2)` only afterwards.

Observed on hardware 2026-08-15 (two iPhone 17 Pro, iOS 26.6, 30 cm apart, mutually discovered at
−27 dBm) as two phones that could never form a Quick Play session — the rename landed ~6 s after
the fatal dial. Full trace in #2416.

### What the shared name actually buys

The decisive finding, and the reason this ADR reaches a different conclusion than #2416's own
option analysis: **the advertised instance name performs no filtering.**

- `NwApi.startBrowsing(serviceType)` browses by service *type* only — the name is not a parameter.
- `NwLoom.onEndpointFound` filters **self** and nothing else, then arms a redial campaign for every
  endpoint it sees. There is no session-name check anywhere on the discovery path.
- Session scoping is entirely cryptographic: the TLS-PSK derived from `Pattern.roomKey`, a required
  bearer secret (an open session is refused). A peer from another session fails the handshake.

So under `Rendezvous.New` the shared name contributes a human-readable label and nothing functional,
while costing: the #2416 race, the #1709 identity-deferral machinery, the `serviceName` clause of the
#1502 self-filter, #2417's settle guard, and the unfixed sibling below.

### Two facts that make the rename cheap

1. **`Rendezvous.Existing` already advertises a per-peer name** (`selfId.value`) and works. This is
   not a new design — it makes `New` do what `Existing` already does.
2. **`NwPsk` already refuses to bind `sessionName` in** — "Host and joiner advertise different Bonjour
   service names, so `sessionName` is not a value both sides share; binding it in would break
   agreement." The key derivation is therefore untouched by this change *by existing design*.

### The sibling this does not fix by guarding

`NwSeam` records a connected peer's endpoint on the success path:

```kotlin
cs.endpoint?.let { peerEndpoint[remoteId] = it.id }
```

Under the same misresolution, a dial armed for peer `C` that lands on peer `B` records
`peerEndpoint[B] = C`, so `refreshSettledLocked` puts **C** into `settledEndpoints` while C is
un-connected — and `NwLoom.redialLoop` parks on a settled endpoint, starving C. It needs ≥3 peers
(with 2, a misresolve can only land on self, which is #2417's case) and self-heals when B departs,
so it is less severe than #2417's permanent poisoning — but it is the same defect one level over.

A local guard (`record only if the dialled id can belong to the answering peer`) is **not** the fix.
The guard needs the endpoint-id space and the `PeerId` space to coincide. They do on the meaningful
P2P path — `RealNwApi` requires `appleNwLoom` to pass the loom's own `selfId` so the advertised TXT
id and the loom's identity agree — but not on the JVM↔native bridge, where loom and dylib each
default an independent UUID. A guard would there refuse to record a legitimate endpoint and leave the
redialer dialling at the backoff ceiling forever. Removing the ambiguity at the source fixes both
sites without needing the guard at all.

### Why no test caught this, and what that costs

This bug needed two iPhones. It should have needed a `jvmTest`. The reason it did not is one line in
the reference harness:

```kotlin
// FakeNwRadio.registerOwnership
endpointOwners[l.serviceName] = deviceId   // last writer wins
```

A name collision is modelled as *"the second advertiser cleanly replaces the first"*, so a dial to a
shared name always resolves deterministically and correctly. Reality is *"the name now resolves to
either advertiser, and which one is a race"* — which is the entire defect. `FakeNwRadio.connect` then
routes strictly by `endpoint.id`, so the dialled id and the accepting device could never disagree.

This is the blind spot CLAUDE.md already names: *a conformance property is only as strong as the
weakest failure the reference implementation can reach.* The reference could not reach it, so nobody
wrote the property, the suite stayed green, and the failure surfaced on hardware at the worst
possible cost. #2417's `injectDialLandingOnSelf` is a *point* fix — it reproduces the one divergence
someone thought to inject, not the class.

So this ADR carries a second obligation beyond the rename: **make the ambiguity expressible in the
harness** (hold every owner of a name, and let a test choose which one a dial resolves to), and then
**hold every fabric to it by conformance** rather than fixing `:kuilt-nw` alone. Any fabric that
discovers peers by a consumer-chosen name can put two peers on one name; `:kuilt-nw` is simply where
it was found first. Sweeping the other suites for the same shape is tracked as #2247.

## Decision

**Advertise `selfId.value` as the Bonjour instance name under `Rendezvous.New` — exactly what
`Rendezvous.Existing` already advertises.** The two arms collapse into one:

```kotlin
val serviceName = selfId.value
```

A name then resolves to exactly one device, so the collision window — and with it the `… (2)` rename
and the whole race — ceases to exist.

### Why not keep the session label in the name

A composite `"<sessionName>-<selfId>"` was considered first, and rejected. It is equally unique, and
it would have kept a human-readable label on the wire and made a future session prefix-filter
possible. It also introduces a failure mode bare `selfId` cannot have.

When TXT has not yet resolved, `RealNwApi` falls back to `id = serviceName`. So under a composite
name a peer is sighted under **two different ids** across its lifetime — the fallback
`"quickplay-<theirId>"` and the resolved `<theirId>` — and `NwLoom.redialers` is keyed by
`endpoint.id`. That is two redial campaigns for one peer. `NwSeam.peerEndpoint` is single-valued, so
only the most recently dialled id is in `settledEndpoints`: the parked redialer wakes, resets its
backoff to the initial interval, dials, overwrites `peerEndpoint`, which un-settles the other, which
wakes — a sustained connect → hello → dedup-disconnect churn for the seam's whole lifetime, in
precisely the browse-add-before-TXT case #1709 exists for.

Today the #1709 deferral incidentally prevents this by holding the unresolved sighting until the
resolved one arrives. A per-peer name stops that deferral from matching a *remote* peer, so the
composite form would have converted an incidental protection into a default-path defect.

With bare `selfId.value` the fallback id **is** the resolved id, so there is exactly one id per peer
and the bifurcation is unrepresentable rather than guarded. This is the real reason
`Rendezvous.Existing` "already works", and it is worth more than a label on the wire.

**If a lobby UI ever needs the session label, its home is the TXT record** — already read per-peer,
already the identity channel, and under no uniqueness constraint. The Bonjour instance name is a
routing key, not a display string; this ADR stops overloading it as both.

### Why not "dial an address, not a name" (#2416 option 1)

Rejected. Capturing a resolved address at browse time pins the dial to an interface, and AWDL
interface churn is exactly why the named endpoint was kept in the first place. Option 2 reaches the
same guarantee without touching the dial path at all, so the churn question never arises.

### Why not "verify-after-connect and re-key" (#2416 option 3)

That is #2417, already landed. It stops the race from becoming *permanent*; it does not remove the
race, and it leaves the `peerEndpoint` sibling above untouched. Recovery, not removal.

## Consequences

**`NwEndpoint.serviceName` changes meaning under `New`** — from "the shared session name" to "this
peer's `PeerId`", which is what it has always meant under `Existing`. `NwLoom.visiblePeers` exposes it
for a lobby view, so this is a pre-1.0 public-surface change and the reason this was a design decision
rather than a drive-by. A consumer that rendered `serviceName` as a session label must read the label
from elsewhere; nothing in-tree does.

**The pre-TXT window stops being dangerous.** When TXT has not resolved, `RealNwApi` falls back to
`id = serviceName` — which is now the peer's `PeerId`, i.e. *the same id the resolved sighting
carries*. An unresolved sighting therefore already names the right machine under the right key, and
the resolved one does not introduce a second.

**The JVM-bridge self-dial under `New` gets caught pre-dial.** Today neither self-filter clause fires
there (`id` = dylib-selfId, `serviceName` = the shared session name), so the bridge self-dials, the
post-connect guard drops the link, and — since #2417 — refuses to settle it, leaving a permanent
self-redial at the backoff ceiling. With `serviceName = selfId.value` the filter's
`serviceName == selfId.value` clause fires and the dial never happens.

Note this holds *only* for the bare form. The composite form rejected above would **not** fix it: the
#1709 deferral requires `!identityResolved`, and `BridgeNwApi` hardcodes `identityResolved = true`
because the ABI does not marshal it. The bridge does reach `Rendezvous.New` (`NwCrossProcessProbe`
calls `nwHost`), contradicting `BridgeNwApi`'s own comment that it only uses `Existing`. The
underlying ABI gaps are #2419.

**Session filtering is unchanged, and still absent.** Every peer of the service type is armed
regardless of session; only the PSK handshake sorts them out. That predates this ADR and is not
addressed by it. A filter would now need the session label from the TXT record rather than the name.

**Machinery this makes redundant, retired separately.** With unique names,
`serviceName == advertisedServiceName` becomes a precise self-test, which likely collapses the #1709
deferral into the self-filter. That is a deletion, not a behaviour change, and lands as its own PR
after this one — one behaviour move per PR, so a revert-check can isolate either half.

**Not a wire-compatibility break in the protocol sense** — no frame, TXT key, or PSK input changes.
A peer running the old code and one running the new still interoperate: each browses by service type,
reads the other's TXT `PeerId`, and dials. The old peer simply advertises a name that can still
collide with another old peer.
