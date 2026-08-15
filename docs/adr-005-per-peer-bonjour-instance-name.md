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

## Decision

**Advertise a per-peer instance name under `Rendezvous.New`: `"<sessionName>-<selfId>"`, truncated to
Bonjour's 63-byte instance-name limit by trimming the *session-name prefix*, never the id suffix.**

```kotlin
val serviceName = when (rendezvous) {
    is Rendezvous.New -> advertisedInstanceName(rendezvous.pattern.sessionName, selfId)
    is Rendezvous.Existing -> selfId.value
}
```

Uniqueness is load-bearing and the label is cosmetic, so the truncation budget is spent on the id
first. Names become unique, so a name resolves to exactly one device and the collision window — with
it the `… (2)` rename and the whole race — ceases to exist.

### Why not "dial an address, not a name" (#2416 option 1)

Rejected. Capturing a resolved address at browse time pins the dial to an interface, and AWDL
interface churn is exactly why the named endpoint was kept in the first place. Option 2 reaches the
same guarantee without touching the dial path at all, so the churn question never arises.

### Why not "verify-after-connect and re-key" (#2416 option 3)

That is #2417, already landed. It stops the race from becoming *permanent*; it does not remove the
race, and it leaves the `peerEndpoint` sibling above untouched. Recovery, not removal.

## Consequences

**`NwEndpoint.serviceName` changes meaning under `New`** — from "the shared session name" to "a
per-peer name carrying the session name as a prefix". `NwLoom.visiblePeers` exposes it for a lobby
view, so this is a pre-1.0 public-surface change and the reason this was a design decision rather
than a drive-by. It is also what makes a session prefix filter *possible* for the first time: today
every peer of the service type is armed regardless of session, and only the PSK handshake sorts them
out. Adding that filter is deliberately **not** part of this decision — it is a separate optimisation
against a cost that predates this ADR.

**The pre-TXT window stops being dangerous.** When TXT has not resolved, `RealNwApi` falls back to
`id = serviceName`. That fallback id is now per-device, so an unresolved sighting already names the
right machine — it merely does not yet name its `PeerId`.

**The JVM-bridge self-dial under `New` gets caught pre-dial.** Today neither self-filter clause fires
there (`id` = dylib-selfId, `serviceName` = the shared name). With a per-peer name the `#1709`
deferral recognises our own advertisement by name and holds it until TXT resolves.

**Machinery this makes redundant, retired separately.** With unique names,
`serviceName == advertisedServiceName` becomes a precise self-test, which likely collapses the #1709
deferral into the self-filter. That is a deletion, not a behaviour change, and lands as its own PR
after this one — one behaviour move per PR, so a revert-check can isolate either half.

**Not a wire-compatibility break in the protocol sense** — no frame, TXT key, or PSK input changes.
A peer running the old code and one running the new still interoperate: each browses by service type,
reads the other's TXT `PeerId`, and dials. The old peer simply advertises a name that can still
collide with another old peer.
