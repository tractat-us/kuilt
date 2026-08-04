# Can two guests talk to each other directly?

When several people share a session, one of them is usually the *host* — the
person everybody else connected to. Whether two **guests** can send a message
straight to each other, without the host passing it along, depends entirely on
how they are connected. Some connection types wire everybody to everybody, so
any two people can talk directly. Others wire everybody only to the host, like
spokes on a wheel: a guest can reach the host, but has no way at all to reach
another guest.

Where the second shape holds, kuilt asks the host to pass the message along, so
the two guests can still talk — it just takes two steps instead of one.

What the host does **not** pass along is the small "are you still there?" check
each member sends the others. That check stays on the direct connections only,
and deliberately so: relaying every member's checking through the one person in
the middle is exactly the load it was worth avoiding. A guest with no direct
line to another guest learns whether that person is still around from the host
instead.

So the table below matters twice over. It records, for each connection type
kuilt ships, which of the two worlds it lives in — which decides both whether a
message needs the host's help to arrive, and whether the "are you still there?"
check has anybody listening.

It began as the survey called for by
[#1576](https://github.com/tractat-us/kuilt/issues/1576), which was a finding
rather than a fix. Two fixes were then built on it: the check is now started
only where there is a direct line ([#1592](https://github.com/tractat-us/kuilt/issues/1592)),
and the host relays what it can ([#1994](https://github.com/tractat-us/kuilt/issues/1994)).

## The question, precisely

For each shipped fabric: can one non-host member get a frame to another non-host
member — and if so, by what route? The first-order test is whether
`Seam.sendTo(otherJoiner, …)` reaches it directly.

Three answers are possible:

- **Full mesh** — every member holds a direct link to every other. Route exists.
- **Star with relay** — the host forwards peer-addressed frames between spokes.
  Route exists.
- **Star without relay** — a spoke can only address the host. **No route**; this
  is the #1576 hazard.

### The middle category had no members until #1994

It was defined here from the start and stood empty — every fabric fell
elsewhere. Four moved into it together, and the route they gained lives **one
layer up**: `Room.broadcast` / `Room.sendTo` wrap the frame and hand it to the host,
which forwards it. The fabric's own `Seam.sendTo` is unchanged — on all four it
still throws `PeerNotConnected` for a co-spoke, and that is correct, because the
fabric genuinely holds no such link and a `Seam` must not claim one.

Read the **Spoke→spoke route?** column, then, as *"can a spoke reach a co-spoke
by some route kuilt provides"*, and the **Deciding code** column as where the
fabric itself decides.

## The matrix

| Fabric | Topology | Spoke→spoke route? | Deciding code | Notes |
|---|---|---|---|---|
| `InMemoryLoom` (`:kuilt-core`) | Full mesh | **Yes** | [`InMemoryLoom.kt:174`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/InMemoryLoom.kt#L174) | Resolves against the factory-wide `peers`; every member of the session is addressable. The reference impl. |
| `MeshSeam` / `peerMesh` (`:kuilt-core/fabric`) | Full mesh *by construction* | **Yes**, for every peer in `peers` | [`MeshSeam.kt:495`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt#L495) | `links[peer]?.conn ?: throw PeerNotConnected`. A peer is in `peers` **iff** a direct link exists, so `sendTo` never silently misroutes. |
| `MeshSeam` via `hubMesh` — spoke side | **Star with relay** | **Yes, via the host** (Room layer) | same `sendTo`, wired with one link | A spoke's mesh holds a single link (to the host), so its `peers` is `{self, host}` and `Seam.sendTo` to a co-joiner throws. The Room relays (#1994). |
| `LinkSeam` / `identified` / `handshaking` (`:kuilt-core/fabric`) | 2-peer link | **N/A** | [`LinkSeam.kt:113`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/LinkSeam.kt#L113) | Only two members ever exist; there is no third peer to be unroutable. |
| `RoomHubSeam` / `MuxServerLoom` (`:kuilt-core`) | **Star with relay** | **Yes, via the host** (Room layer) | [`RoomHubSeam.kt:179`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RoomHubSeam.kt#L179) | The hub's own view. Spoke frames are delivered into the **host's** `incoming` and the fabric forwards none of them onward — pinned by `StarTopologyPeerRoutingTest` (#1588). The `SeamRoom` running on that host is what forwards (#1994). |
| `MuxSeam` / `NamedMux` (`:kuilt-core`) | Inherits | Inherits | [`MuxBase.kt:100`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxBase.kt#L100) | Pure delegation after frame-wrapping — changes nothing about routing. |
| `MuxClientLoom` (`:kuilt-core`) | Inherits (spoke of a hub ⇒ **star with relay**) | Inherits | [`MuxClientLoom.kt:189`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxClientLoom.kt#L189) | Delegates to the currently-active underlying seam. |
| `TieredSeam` (`:kuilt-core`) | Inherits, per tier | Inherits — routable iff one tier owns the id | [`TieredSeam.kt:220`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/TieredSeam.kt#L220) | Routes to whichever tier owns the id; an id owned by **neither** tier now throws `PeerNotConnected` (#1935). It used to be discarded silently — the worst variant for #1576, since even a caller that handled the exception saw nothing. |
| `CompositeSeam` / `CompositeLoom` (`:kuilt-core/composite`) | Inherits, per ply | Inherits — routable iff some ply reaches the peer | [`CompositeSeam.kt:328`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt#L328) | `resolveSendTargets` only considers *direct* per-ply targets; no ply relays for another. `peers` is recomputed as exactly the reachable set, so roster and route agree. |
| `:kuilt-websocket` — `KtorServerLoom` / `KtorRoomHost` | Star of independent 2-peer rooms | **N/A** | [`KtorRoomHost.kt`](../kuilt-websocket/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/websocket/KtorRoomHost.kt), [`WebSocketSeam.kt:33`](../kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/WebSocketSeam.kt#L33) | Every accepted connection becomes its **own** two-peer `Room` (`identified` = `LinkSeam`). There is no multi-joiner roster, so no unroutable peer can be admitted. |
| `:kuilt-websocket` — `KtorClientLoom` | 2-peer link | **N/A** | `WebSocketSeam` → `LinkSeam` | Point-to-point. `KtorMeshClientLoom` is the hub-spoke variant and inherits the `hubMesh` answer (**star with relay**). |
| `:kuilt-mdns` | Discovery only | Inherits | — | Discovery is orthogonal to topology; feeds a WebSocket connection. |
| `:kuilt-multipeer` | **Star with relay** | **Yes, via the host** (Room layer) | [`MCSessionLink.kt:153`](../kuilt-multipeer/src/appleMain/kotlin/us/tractat/kuilt/multipeer/internal/MCSessionLink.kt#L153), [`MultipeerPeerLinkFactory.apple.kt` `joinSession`](../kuilt-multipeer/src/appleMain/kotlin/us/tractat/kuilt/multipeer/MultipeerPeerLinkFactory.apple.kt) | Decisive: each joiner creates its **own** `MCSession` and invites only the host. Two joiners are in different sessions, so a co-joiner never appears in `session.connectedPeers`. The Room relays (#1994). Not hardware-verified. |
| `:kuilt-nearby` | **Star with relay** | **Yes, via the host** (Room layer) | [`NearbySeam.kt:165`](../kuilt-nearby/src/commonMain/kotlin/us/tractat/kuilt/nearby/NearbySeam.kt#L165), [`GmsNearbyApi.kt:44`](../kuilt-nearby/src/androidMain/kotlin/us/tractat/kuilt/nearby/GmsNearbyApi.kt#L44) | Strategy is `P2P_STAR`, and `endpointIdFor` scans only directly-connected endpoints. A discoverer holds one endpoint — the advertiser. The Room relays (#1994). Not hardware-verified. |
| `:kuilt-webrtc` | 2-peer data channel | **N/A** | [`WebRTCPeerLink.kt:116`](../kuilt-webrtc/src/wasmJsMain/kotlin/us/tractat/kuilt/webrtc/internal/WebRTCPeerLink.kt#L116) | `resolvedRoster()` is literally `setOf(selfId, remote)`. Point-to-point. |
| `:kuilt-tcp` | 2-peer link | **N/A** | [`TcpLoom.kt` `weave`](../kuilt-tcp/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/tcp/TcpLoom.kt) | `handshaking(...)` yields a `LinkSeam`. Multi-peer TCP is assembled by the *caller* out of `hubMesh`/`peerMesh`, which then decides the answer. |
| `:kuilt-nw` | Full mesh | **Yes** | [`NwSeam.kt:865`](../kuilt-nw/src/commonMain/kotlin/us/tractat/kuilt/nw/NwSeam.kt#L865), [`NwLoom.kt:50`](../kuilt-nw/src/commonMain/kotlin/us/tractat/kuilt/nw/NwLoom.kt#L50) | Every peer advertises, browses **and** auto-dials every other, so each pair forms its own connection; `registry[peer]` always resolves for a peer in the roster. |
| `:kuilt-gossip` — `GossipSeam` | Decorator | **Unchanged** | [`GossipSeam.kt:374`](../kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipSeam.kt#L374) | `sendTo` is a bare delegation to the base seam — gossip flooding applies to `broadcast` only. It neither creates nor removes a peer-addressed route; it also presumes a full-membership base seam. |

## Data is relayed; liveness is not

Heartbeat detectors stay on **direct edges only**. #1592's route gate reads the
*underlying* `seam.peers`, which the relay does not alter — and that is
deliberate. A detector's traffic is per-peer and continuous, so it is O(N²)
across a session's roster, which is exactly the load #1576 removed; relaying it
would put all of it through the host. Presence for a member with no direct edge
still comes from the host's authoritative fan-out (#1557).

Once the roster is routable this gate reads as over-conservative and invites a
"fix". It is not one.

## What follows

Two facts combined into the #1576 bug:

1. `SeamRoom` started a `HeartbeatPartitionDetector` for **every** admitted
   member, using the *room* roster, which on a star contains peers the local
   `Seam` cannot address.
2. `HeartbeatPartitionDetector.runHeartbeatLoop`'s **timeout** branch is not
   gated on the peer being in `link.peers` (only the `TransportClosed` branch
   is), so silence from an unroutable peer still matures into
   `PeerUnresponsive(Timeout)` → `PeerLost` → `Left(PartitionExpired)`.

So on every star row above, each joiner independently evicted every other
joiner. The fix (#1592) starts a detector only where the peer is present in the
seam's own `peers` — a *liveness edge* — and lets the host's authoritative
presence fan-out (#1557) cover the rest. `SeamRoom.startDetector` carries the
gate and the argument for keeping it narrow.
