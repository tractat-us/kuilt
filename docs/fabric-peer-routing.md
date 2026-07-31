# Can two guests talk to each other directly?

When several people share a session, one of them is usually the *host* — the
person everybody else connected to. Whether two **guests** can send a message
straight to each other, without the host passing it along, depends entirely on
how they are connected. Some connection types wire everybody to everybody, so
any two people can talk directly. Others wire everybody only to the host, like
spokes on a wheel: a guest can reach the host, but has no way at all to reach
another guest.

That distinction matters because kuilt currently watches every other member of a
session by sending them a small "are you still there?" message. Where two guests
have no way to reach each other, that check never arrives, the sender concludes
the other person has vanished, and a perfectly healthy member gets dropped from
the session. The table below records, for each connection type kuilt ships,
which of the two worlds it lives in — so the watching can be switched off
exactly where there is nobody listening.

This is the survey called for by
[#1576](https://github.com/tractat-us/kuilt/issues/1576). It is a finding, not a
fix: no behaviour changed to produce it.

## The question, precisely

For each shipped fabric: does `Seam.sendTo(otherJoiner, …)` from one non-host
member actually reach that other non-host member?

Three answers are possible:

- **Full mesh** — every member holds a direct link to every other. Route exists.
- **Star with relay** — the host forwards peer-addressed frames between spokes.
  Route exists.
- **Star without relay** — a spoke can only address the host. **No route**; this
  is the #1576 hazard.

## The matrix

| Fabric | Topology | Spoke→spoke route? | Deciding code | Notes |
|---|---|---|---|---|
| `InMemoryLoom` (`:kuilt-core`) | Full mesh | **Yes** | [`InMemoryLoom.kt:174`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/InMemoryLoom.kt#L174) | Resolves against the factory-wide `peers`; every member of the session is addressable. The reference impl. |
| `MeshSeam` / `peerMesh` (`:kuilt-core/fabric`) | Full mesh *by construction* | **Yes**, for every peer in `peers` | [`MeshSeam.kt:495`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt#L495) | `links[peer]?.conn ?: throw PeerNotConnected`. A peer is in `peers` **iff** a direct link exists, so `sendTo` never silently misroutes. |
| `MeshSeam` via `hubMesh` — spoke side | Star without relay | **No** | same `sendTo`, wired with one link | A spoke's mesh holds a single link (to the host), so its `peers` is `{self, host}` and a co-joiner is not addressable at all. |
| `LinkSeam` / `identified` / `handshaking` (`:kuilt-core/fabric`) | 2-peer link | **N/A** | [`LinkSeam.kt:113`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/LinkSeam.kt#L113) | Only two members ever exist; there is no third peer to be unroutable. |
| `RoomHubSeam` / `MuxServerLoom` (`:kuilt-core`) | Star without relay | **No** | [`RoomHubSeam.kt:179`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RoomHubSeam.kt#L179) | The hub's own view. Spoke frames are delivered into the **host's** `incoming`; nothing forwards them onward. Pinned by `StarTopologyPeerRoutingTest` (#1588). |
| `MuxSeam` / `NamedMux` (`:kuilt-core`) | Inherits | Inherits | [`MuxBase.kt:100`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxBase.kt#L100) | Pure delegation after frame-wrapping — changes nothing about routing. |
| `MuxClientLoom` (`:kuilt-core`) | Inherits (spoke of a hub ⇒ **no**) | Inherits | [`MuxClientLoom.kt:189`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxClientLoom.kt#L189) | Delegates to the currently-active underlying seam. |
| `TieredSeam` (`:kuilt-core`) | Inherits, per tier | Inherits — routable iff one tier owns the id | [`TieredSeam.kt:220`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/TieredSeam.kt#L220) | Routes to whichever tier owns the id; an id owned by **neither** tier now throws `PeerNotConnected` (#1935). It used to be discarded silently — the worst variant for #1576, since even a caller that handled the exception saw nothing. |
| `CompositeSeam` / `CompositeLoom` (`:kuilt-core/composite`) | Inherits, per ply | Inherits — routable iff some ply reaches the peer | [`CompositeSeam.kt:328`](../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt#L328) | `resolveSendTargets` only considers *direct* per-ply targets; no ply relays for another. `peers` is recomputed as exactly the reachable set, so roster and route agree. |
| `:kuilt-websocket` — `KtorServerLoom` / `KtorRoomHost` | Star of independent 2-peer rooms | **N/A** | [`KtorRoomHost.kt`](../kuilt-websocket/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/websocket/KtorRoomHost.kt), [`WebSocketSeam.kt:33`](../kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/WebSocketSeam.kt#L33) | Every accepted connection becomes its **own** two-peer `Room` (`identified` = `LinkSeam`). There is no multi-joiner roster, so no unroutable peer can be admitted. |
| `:kuilt-websocket` — `KtorClientLoom` | 2-peer link | **N/A** | `WebSocketSeam` → `LinkSeam` | Point-to-point. `KtorMeshClientLoom` is the hub-spoke variant and inherits the `hubMesh` answer (**no**). |
| `:kuilt-mdns` | Discovery only | Inherits | — | Discovery is orthogonal to topology; feeds a WebSocket connection. |
| `:kuilt-multipeer` | Star without relay | **No** | [`MCSessionLink.kt:153`](../kuilt-multipeer/src/appleMain/kotlin/us/tractat/kuilt/multipeer/internal/MCSessionLink.kt#L153), [`MultipeerPeerLinkFactory.apple.kt` `joinSession`](../kuilt-multipeer/src/appleMain/kotlin/us/tractat/kuilt/multipeer/MultipeerPeerLinkFactory.apple.kt) | Decisive: each joiner creates its **own** `MCSession` and invites only the host. Two joiners are in different sessions, so a co-joiner never appears in `session.connectedPeers`. Not hardware-verified. |
| `:kuilt-nearby` | Star without relay | **No** | [`NearbySeam.kt:165`](../kuilt-nearby/src/commonMain/kotlin/us/tractat/kuilt/nearby/NearbySeam.kt#L165), [`GmsNearbyApi.kt:44`](../kuilt-nearby/src/androidMain/kotlin/us/tractat/kuilt/nearby/GmsNearbyApi.kt#L44) | Strategy is `P2P_STAR`, and `endpointIdFor` scans only directly-connected endpoints. A discoverer holds one endpoint — the advertiser. Not hardware-verified. |
| `:kuilt-webrtc` | 2-peer data channel | **N/A** | [`WebRTCPeerLink.kt:116`](../kuilt-webrtc/src/wasmJsMain/kotlin/us/tractat/kuilt/webrtc/internal/WebRTCPeerLink.kt#L116) | `resolvedRoster()` is literally `setOf(selfId, remote)`. Point-to-point. |
| `:kuilt-tcp` | 2-peer link | **N/A** | [`TcpLoom.kt` `weave`](../kuilt-tcp/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/tcp/TcpLoom.kt) | `handshaking(...)` yields a `LinkSeam`. Multi-peer TCP is assembled by the *caller* out of `hubMesh`/`peerMesh`, which then decides the answer. |
| `:kuilt-nw` | Full mesh | **Yes** | [`NwSeam.kt:865`](../kuilt-nw/src/commonMain/kotlin/us/tractat/kuilt/nw/NwSeam.kt#L865), [`NwLoom.kt:50`](../kuilt-nw/src/commonMain/kotlin/us/tractat/kuilt/nw/NwLoom.kt#L50) | Every peer advertises, browses **and** auto-dials every other, so each pair forms its own connection; `registry[peer]` always resolves for a peer in the roster. |
| `:kuilt-gossip` — `GossipSeam` | Decorator | **Unchanged** | [`GossipSeam.kt:374`](../kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipSeam.kt#L374) | `sendTo` is a bare delegation to the base seam — gossip flooding applies to `broadcast` only. It neither creates nor removes a peer-addressed route; it also presumes a full-membership base seam. |

## What follows

Two facts combine into the bug:

1. `SeamRoom` starts a `HeartbeatPartitionDetector` for **every** admitted
   member — `SeamRoom.kt:1417` — using the *room* roster, which on a star
   contains peers the local `Seam` cannot address.
2. `HeartbeatPartitionDetector.runHeartbeatLoop`'s **timeout** branch is not
   gated on the peer being in `link.peers` (only the `TransportClosed` branch
   is), so silence from an unroutable peer still matures into
   `PeerUnresponsive(Timeout)` → `PeerLost` → `Left(PartitionExpired)`.

So on every fabric marked **No** above, each joiner independently evicts every
other joiner. The obvious shape of the fix is to start a detector only where the
peer is present in the seam's own `peers` — a *liveness edge* — and let the
host's authoritative presence fan-out (#1557) cover the rest.
