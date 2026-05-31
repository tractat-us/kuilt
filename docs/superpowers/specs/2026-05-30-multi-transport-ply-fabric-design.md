# Many-transport ("Ply") composite fabric — design

**Status:** design, pre-implementation. Epic #49.
**Date:** 2026-05-30
**Depends on:** `2026-05-30-seam-lifecycle-design.md` (axis-1 `SeamState`) landing
first — this design reuses its rollup semantics verbatim and must not begin
implementation until axis-1 has shipped and at least the websocket + one radio
fabric drive `state`.
**Scope:** `kuilt-core` contract additions (`PlyId`, `Seam.plies`) + a new
`CompositeLoom` (also in `kuilt-core` — it depends only on the `Loom`/`Seam`
contract, never on a fabric) + composite conformance coverage. No fabric module
changes beyond what axis-1 already requires.

## Goal

Today a `Seam` rides exactly one transport. This design lets one logical session
be woven from **several transports at once** — a *composite fabric* — and exposes
the health of each constituent link (a **ply**) without changing the meaning of
the aggregate lifecycle. The consumer keeps seeing one ordinary `Seam`: one peer
set, one single-collection `incoming`, one `broadcast`. The plumbing is hidden.

## Why (the driving scenario)

Cross-platform games — a mix of Android and iPhone — must let everyone "just show
up." The constraint that shapes the whole design: **the zero-config peer-to-peer
radios are platform-siloed and do not interoperate.** Apple AWDL (under
Multipeer) bridges Apple↔Apple only; Android WiFi-Aware/Direct + BLE (under
Nearby) bridges Android↔Android only. No device runs both, so no amount of
software topology bridges a pure-Apple clique to a pure-Android clique.

What *is* cross-platform is any transport that rides a **shared IP network**:
the internet relay (WebSocket), or an offline LAN/hotspot carrying
mDNS-discovered WebSocket / WebRTC-LAN. mDNS itself is cross-platform but is only
*discovery* — it finds peers on a network that already connects them; it does not
create the network. (In the woods with no access point, the cross-platform link
is an Android-hosted WiFi hotspot — Android SoftAP needs no cell plan — over
which mDNS + WebSocket light up for everyone. That hotspot step is a UX nicety,
not a contract concern.)

This gives the model its spine: **the union of a session's plies must cover its
peer set.** The common shape is one *universal* ply every peer shares (the relay,
or an offline LAN/hotspot) plus optional low-latency *overlays* (platform radios,
WebRTC-LAN) reaching a subset — the universal ply is the connectivity floor that
makes "everyone shows up" true and overlays are pure latency optimization. But a
single universal ply is not required: as long as the union covers everyone, the
plies can be several relays a device multi-homes across (below).

### Other driving scenarios

- **Cross-region / multi-server.** Players connect to a server *local* to them for
  latency but want to reach peers on other servers. A device can **multi-home**
  across several relay plies (join server A *and* server B); `peers` is the union,
  and if the B link drops the device sees `plies = { server-A: Woven, server-B:
  Torn }` — "the remote region went away," cleanly distinguished from "a peer
  left." (True server-to-server *federation* — A↔B bridging for a peer pinned to
  one server — lives **below** kuilt: a federated relay mesh is, from the `Seam`'s
  view, one ply. Federation alone hides the regional-down signal that multi-homing
  surfaces.)
- **Gateway / piggyback.** A player with internet shares it so data-less local
  players reach the upstream server *through* them. Served at the **network layer**
  (hotspot/NAT) with **zero kuilt changes** — the data-less players get real IP
  internet through the gateway and appear directly on the relay ply; the gateway
  is an OS routing detail below the `Seam`. Application-layer single-hop forwarding
  (for data-less players on an internet-less *radio* link) is a deliberate future
  capability the wire format reserves for (see Dedup, below); it is not built here.

## The model

> One logical session is woven from several **plies** (constituent transport
> links) whose **union covers the peer set**. Commonly one ply is *universal*
> (shared by every peer) with the rest *overlays* reaching a subset at lower
> latency; equally valid is several relays a device multi-homes across. The
> composite presents the union as a single ordinary `Seam`.

### Contract additions (`kuilt-core`)

```kotlin
/** Stable identity of one constituent link within a composite fabric. */
@JvmInline
public value class PlyId(public val value: String)

public interface Seam {
    // ... existing members + axis-1 `state` ...

    /**
     * Per-ply lifecycle breakdown. Single-ply fabrics report a one-entry map.
     * Invariant: `state.value == rollup(plies.value.values)` under the axis-1
     * rule "any ply Woven ⇒ Woven".
     */
    public val plies: StateFlow<Map<PlyId, SeamState>>
}
```

`plies` is **additive** — every existing single-ply fabric implements it as a
constant one-entry map (`{ thisPlyId -> state.value }`), satisfying the rollup
invariant trivially. Adding `plies` is the only `Seam` surface change.

### `CompositeLoom` (`kuilt-core`)

```kotlin
public class CompositeLoom(
    // Ordered by send preference, most-preferred first (e.g. a low-latency local
    // overlay ahead of the relay). Coverage is emergent — the union of these
    // plies must cover the session; no ply is privileged as "universal".
    private val plies: List<Pair<PlyId, Loom>>,
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam { /* composite seam */ }
    override fun availability(): FabricAvailability =
        if (plies.any { it.second.availability() == FabricAvailability.Available })
            FabricAvailability.Available
        else FabricAvailability.Unavailable("no ply available")
}
```

- It is itself a `Loom`, so consumers gain no new type at the call site:
  `CompositeLoom(...).host(pattern)` returns an ordinary `Seam`.
- `weave(rendezvous)` fans the **same logical session identity** (`Pattern`/`Tag`)
  out to each constituent `Loom`. Each `Loom` already encapsulates how to realize
  that identity on its medium (relay → `wss://…/<tag>`; mDNS → a Bonjour service
  named by `<tag>`). The composite carries **no** per-transport addressing — that
  stays inside each `Loom`. The `Tag`/`Pattern` is the session's logical name.
- The list order is a **send-preference hint** only (used by `sendTo` to pick the
  lowest-latency reaching ply); it does not affect coverage or correctness.
- `availability()` is `Available` if **any** constituent is available — a session
  can be attempted as long as one ply might come up.
- `close(reason)` closes every constituent ply; aggregate `state` → `Torn(reason)`.

### Lifecycle — axis-1 rollup, unchanged

```kotlin
val state: StateFlow<SeamState>              // aggregate (axis-1, shipped)
val plies: StateFlow<Map<PlyId, SeamState>>  // per-ply (this epic)
```

- `Weaving` — no ply `Woven` yet.
- `Woven` — at least one ply `Woven` (a frame can be injected *somewhere*). A
  joiner may begin sending as soon as the aggregate is `Woven` — exactly the
  axis-1 `SeamRoom` await, transport-agnostic.
- `Torn(reason)` — all plies gone / closed locally.
- Invariant: `state == rollup(plies.values)`.

A dropped overlay surfaces as `plies = { relay: Woven, lan: Torn(Unreachable) }`
while `state` stays `Woven`. A consumer that cares ("you've left the local
network, now relaying — expect higher latency") reads `plies`; one that doesn't
just sees `Woven`. That distinguishability is the entire point of this epic.

### Peer set & membership reconciliation

- `peers` = **union** across plies; a dual-reachable peer appears once.
- A peer is present iff reachable on **≥1** ply; it is removed only when absent
  from **all** plies. This makes overlay churn non-flapping by construction: an
  blip on one ply for a peer still reachable on another causes **no** membership
  change. Only a peer's disappearance from every ply removes it.

## Send & receive (the three settled decisions)

### 1. Dedup identity — explicit `(originId, originSeq)` envelope, recommended

The relay-copy and overlay-copy of one frame must collapse to a single delivery.
The composite wraps each outbound payload in a minimal **envelope** prefixed to
the opaque payload, carrying an explicit **`(originId: PeerId, originSeq: Long)`**;
the receiving composite strips it and dedups on that key before handing the bare
payload to `incoming`. Plies treat the whole envelope+payload as opaque bytes,
which is exactly the `Swatch` contract.

`originSeq` is a per-origin monotonic counter assigned by the **sending** composite.
**`originId` is explicit on the wire** rather than relying on `swatch.sender`
(which transports stamp per-link on receive). In the pure no-forwarding case
`sender == origin` and the explicit id is redundant — but carrying it now is the
single forward-compatible choice that keeps **single-hop gateway forwarding** (the
piggyback scenario) addable later with **no wire break**: once a gateway forwards
a frame, the per-link `sender` becomes the gateway, so the origin must travel in
the frame. The cost is a few bytes per (tiny) game frame; the benefit is the
forwarding door stays open.

> **Minor:** an origin that leaves and rejoins under the *same* `PeerId` with a
> reset counter could collide `(originId, originSeq)`. Mitigated by `PeerId` being
> unique per join (the existing convention); if a fabric reuses ids, add a small
> per-join epoch nonce to the envelope. Noted, not built now.

### 2. Ordering — bounded per-origin reorder buffer, recommended

Across independent plies there is no global order, and broadcast-all-then-dedup
can reorder *same-origin* frames (seq 1's overlay copy lost → its relay copy
arrives after seq 2's overlay copy). For a card game, per-origin order matters, so
the composite delivers **per-origin in `originSeq` order** through a bounded
reorder buffer:

- Hold out-of-order frames per origin; release in sequence.
- Bound the wait (default small — e.g. 16 frames or 250 ms); on expiry, skip the
  missing seq and release, favoring liveness over a stalled-ply head-of-line stall.
- **No-op in the common case:** a relay ply is WebSocket (TCP — reliable,
  ordered), so when it is the effective deliverer the buffer never holds anything.
  The cost is paid only under genuine cross-ply races.

The contract promise is therefore: **per-origin ordering, best-effort across
plies with a bounded reorder window.** No cross-origin global order is promised
(there never was one — `incoming` only ever ordered a single collector's stream).

### 3. Send strategy — broadcast over all plies, recommended (MVP)

`broadcast(p)` sends over **every** ply; correctness lives entirely in the inbound
dedup gate. Send-side stays dumb; no per-peer ply bookkeeping. Game frames are
tiny, so the redundant relay+overlay copy is cheap.

`sendTo(peer, p)` picks any ply currently reaching `peer` (overlay preferred for
latency); throws `PeerNotConnected` (axis-1) if no ply reaches it. Per-ply
addressing is **never** exposed on the public surface — ply selection is always
internal.

> **Deferred, additive optimization:** a "primary ply per peer" send strategy
> (send each peer's frames over one chosen ply, suppressing the redundant copy)
> removes the redundant bandwidth and makes per-origin reordering structurally
> rarer, at the cost of per-peer ply bookkeeping and a transient dedup during
> ply-switchover. Not needed for the MVP; the dedup gate makes it a safe later
> swap.

## Conformance

A composite conformance harness bonds **two delayed-`Woven` fakes** (reusing the
axis-1 delayed-`Woven` test fabric) under one `CompositeLoom`, asserting:

- Aggregate `state` reaches `Woven` as soon as **any** ply is `Woven`; `plies`
  reflects each constituent's state and satisfies the rollup invariant throughout.
- A frame sent over a peer reachable on **both** plies is delivered **exactly
  once** (dedup).
- Per-origin order is preserved when plies deliver copies in different orders
  (reorder buffer), and a permanently-missing seq is skipped within the bound
  (liveness).
- One ply going `Torn` while another stays `Woven` does **not** remove a peer
  still reachable on that other ply (no membership flap) and does **not** change
  the aggregate `state`.
- `close()` drives every ply and the aggregate to `Torn(Normal)`.

The existing `SeamConformanceSuite` is unchanged; composite fabrics additionally
pass it (a `CompositeLoom` over conformant plies is itself a conformant `Seam`).

## Explicitly out of scope

- **Arbitrary multi-hop mesh routing.** No general routing tables / transitive
  bridging across an unknown topology. If no ply (or union of plies) covers
  everyone — e.g. disjoint Apple/Android radio cliques with no shared LAN — those
  peers simply don't appear in each other's `peers`. Honest: no device sits on
  both radios, so the hardware can't bridge them regardless.
- **Application-layer single-hop gateway forwarding** (data-less players on an
  internet-less radio link, bridged to the relay by one gateway peer). A
  *deliberate future* capability, not built here — but the explicit `originId` in
  the dedup envelope (Decision 1) reserves the wire so it can be added without a
  break. Adding it later means: origin-tagged frames (done), cross-bridge
  membership propagation, and a forwarder loop guard (the dedup gate already
  doubles as one for single-hop). The piggyback need is met *now* at the network
  layer (hotspot/NAT), below kuilt.
- **Server-to-server federation** (A↔B bridging for a peer pinned to one server).
  Below kuilt: a federated relay mesh is one ply from the `Seam`'s view.
- **Dynamic ply attach/detach mid-session** (e.g. an overlay appearing when peers
  come into proximity). Plies are fixed at `weave()` time in the MVP. The `plies`
  map is already the surface that would reflect dynamic plies, so this is a later
  additive capability, not a contract break.
- **Latency-optimized "primary ply per peer" send** (deferred, above).
- **Application-level quorum/checkpoint** ("all peers caught up"). A session-layer
  concern, per the axis-1 design — not a `Seam` contract.

## Open questions from the brief — resolutions

| Brief question | Resolution |
|---|---|
| Loom composition | `CompositeLoom` wrapping an ordered list of plies (union must cover the session; order = send preference); itself a `Loom`. `weave` fans the logical session identity to each constituent loom. |
| `Woven` when only some plies woven | Aggregate `Woven` as soon as **any** ply is `Woven` (axis-1 rollup). Joiner may send then. |
| Frame de-dup / fan-out | Broadcast over all plies; inbound dedup by explicit `(originId, originSeq)` carried in a minimal envelope (reserves wire for future single-hop forwarding). |
| Per-ply addressing | Internal only; never exposed. `sendTo` picks a ply (overlay preferred). |
| Membership reconciliation | `peers` = union; remove only when absent from **all** plies ⇒ no overlay-churn flap. |
| Failure semantics / surfacing | `plies` map surfaces per-ply `Torn`; aggregate stays `Woven` if any ply lives. |
| Conformance | Two delayed-`Woven` fakes bonded; assertions above. |
| Ordering across plies | Per-origin ordering via bounded reorder buffer; no global cross-origin order promised. |

## Module placement & guardrails

- `PlyId`, `Seam.plies`, the envelope codec, the dedup/reorder gate, and
  `CompositeLoom` all live in `kuilt-core` — every one of them depends only on the
  `Loom`/`Seam` contract, so the dependency-direction rule (`kuilt-core` free of
  fabric-specific imports) holds.
- Do **not** start implementation before axis-1 `SeamState` has shipped and is
  driven by the websocket + one radio fabric. The rollup definition is the
  foundation this builds on.

## Sequencing

Detailed PR breakdown is deferred to the implementation plan (writing-plans). At a
glance: (1) `PlyId` + additive `Seam.plies` on the contract and every existing
single-ply fabric (constant one-entry map); (2) the envelope codec + dedup/reorder
gate as standalone, unit-tested `kuilt-core` units; (3) `CompositeLoom` wiring them
together; (4) the composite conformance harness.
