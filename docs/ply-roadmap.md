# Ply roadmap

The multi-transport ("Ply") composite fabric shipped its MVP in epic #49
(`CompositeLoom`, the `Announce`/`Data` envelope, `PlyInboundGate` dedup +
per-origin reorder, broadcast-over-all-plies send, identity reconciliation).
That MVP's design — `docs/superpowers/specs/2026-05-30-multi-transport-ply-fabric-design.md`
— deliberately deferred a handful of capabilities under "Explicitly out of
scope." This file is the living record of what remains, in what order, and the
concrete signal that flips each from "later" to "build now."

Guiding posture: **build when a consumer needs it.** None of the items below has
a consumer need today. The roadmap's job is to record *why each waits* and *what
triggers it*, not to pull work forward speculatively.

## Not on the roadmap (settled by the MVP design)

Two items sometimes lumped in with the deferred work are, by design, **not kuilt
features**:

- **Server-to-server federation.** A federated relay mesh is, from the `Seam`'s
  view, a single ply. Bridging server A↔B for a peer pinned to one server lives
  *below* kuilt. (A device that wants the regional-down signal multi-homes across
  relay plies instead — that *is* supported, and is just the ordinary composite.)
- **Network-layer gateway / piggyback.** A peer sharing its internet so data-less
  local peers reach the relay is served at the network layer (hotspot/NAT) with
  zero kuilt changes — the data-less peers get real IP and appear directly on the
  relay ply. Only the *application-layer* single-hop variant (item 2 below) is a
  kuilt concern, and only for an internet-less *radio* link.

## The roadmap (ordered)

| # | Capability | Trigger that unblocks it | Depends on | Wire impact |
|---|------------|--------------------------|------------|-------------|
| 1 | **Dynamic ply attach/detach** | A consumer with overlays that come and go (radio peers entering/leaving proximity; multi-homing a relay mid-session) | — | none (control-plane only) |
| 2 | **App-layer single-hop gateway forwarding** | Data-less peers on an internet-less *radio* link needing the relay through one bridge peer | benefits from #1 (the bridge is a ply that attaches) | none — `originId` already reserved in every `Data` frame |
| 3 | **Primary-ply-per-peer send** | Measured bandwidth/latency pain from broadcast-over-all-plies redundancy | benefits from #1 (per-ply bookkeeping is simpler once attach/detach is modeled) | none |

Every item is **additive** and **wire-compatible** — the MVP's explicit
`(originId, originSeq)` envelope and the `plies` map were chosen precisely so
these can land without a break.

### 1. Dynamic ply attach/detach

Today the ply set is frozen at `weave()`. This item lets plies join and leave a
live session, so an overlay (a platform radio, WebRTC-LAN) can light up when
peers come into proximity and drop when they leave — the scenario the MVP's "Why"
describes but does not yet realize. Most foundational of the three: it reshapes
`CompositeSeam` into a reconcile-based core, and items 2 and 3 are cleaner built
on a composite whose ply set is already mutable.

**Designed:** `docs/superpowers/specs/2026-06-04-dynamic-ply-attach-detach-design.md`.

### 2. App-layer single-hop gateway forwarding

One peer bridges data-less, radio-only peers to the relay. The MVP reserved the
wire for this by carrying `originId` explicitly in every `Data` frame (so a
forwarded frame's origin survives even though the per-link `sender` becomes the
gateway). Adding it means: cross-bridge membership propagation, and a forwarder
loop guard (the dedup gate already doubles as one for single hop). Narrower
real-world trigger than item 1, because the common piggyback case is already met
at the network layer (see above).

### 3. Primary-ply-per-peer send

Send each peer's frames over one chosen ply instead of broadcasting to every
ply, suppressing the redundant copy. Removes redundant bandwidth and makes
per-origin reordering structurally rarer, at the cost of per-peer ply bookkeeping
and a transient dedup during ply-switchover. The inbound dedup gate makes this a
safe later swap. Pure optimization — lowest urgency; gate it on measured pain,
not anticipation.
