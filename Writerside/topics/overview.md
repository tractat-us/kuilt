# kuilt

kuilt has one job: keep your peer-to-peer app code stable while transports,
platforms, and network conditions change.

It is a networking library for Kotlin Multiplatform (JVM, Android, iOS,
macOS, wasmJs). It moves byte frames between peers over interchangeable
*fabrics* (WebSocket, mDNS-discovered LAN, Apple Multipeer Connectivity,
WebRTC, Android Nearby) behind one contract.

The library has three main parts:

- **Fabrics** — reusable transport integrations.
  `Loom` and `Seam` give you one send/receive/membership API across transports.
  Your application code stays the same when you swap transports.

- **Conflict-free Replicated Data Types** — data structures that converge across peers.
  You can use `LWWMap`, `ORSet`, `JsonCrdt`, and others with or without kuilt's
  network layer. Add `Quilter` when you want live delta propagation.

- **Raft** — strong ordering and agreement.
  `kuilt-raft` provides a tested Raft implementation over any kuilt `Seam`.
  `TurnSequencer` (from `kuilt-game`) wraps it for turn-based game logic.

## Choose by the guarantee you need

- Use a **fabric** when you need peers connected and frames moving.
- Add a **CRDT** when state should converge without central coordination.
- Add **Raft** when every peer must apply the same decisions in the same order.

Start with the weakest guarantee that keeps your product correct, then add
stronger guarantees only where needed.

→ [Getting started: two peers, one session](getting-started.md)

## The fabric metaphor

A **loom** creates sessions. A **seam** is one peer's view of a live session.
A **swatch** is a frame of bytes. Every fabric (WebSocket, Multipeer, Nearby,
WebRTC) implements these three types. Your app code does not need to handle
socket APIs, peer-connection objects, or Bluetooth internals directly.

## Peer symmetry

There is no client/server split in kuilt's contract. Every peer in a session
uses the same `Seam` interface. A 2-peer WebSocket connection is just
`peers.size == 2` of the same model. The same app code can run with two peers
or twenty, and over relay or direct links.

## Modules at a glance

| Module | What it gives you |
|--------|-------------------|
| `kuilt-core` | The contract (`Loom`/`Seam`/`Swatch`), `InMemoryLoom` reference impl, `MuxSeam` + `NamedMux` channel splitters |
| `kuilt-crdt` | Delta-state CRDT library (`GCounter`, `ORSet`, `LWWMap`, `JsonCrdt`, …) |
| `kuilt-quilter` | Live CRDT replication over a `Seam`: `Quilter` drives delta-exchange and anti-entropy |
| `kuilt-deal` | Cryptographically fair card dealing (`DealSession`) + dealer-less fair-random (`FairRandom`) |
| `kuilt-game` | Turn-based game facade: `gameHost`/`gameJoin`/`gameNode` → `GameSession`, `TurnSequencer`, `SpeculativeSequencer` |
| `kuilt-raft` | Raft consensus — leader election, log replication, snapshots, dynamic membership, linearizable reads, leadership transfer |
| `kuilt-cluster` | Server-cluster overlay: `ServerCluster` (voter mesh + relay accept loop) + `ClusterClient` (propose + observe) |
| `kuilt-liveness` | Peer-liveness detection: `HeartbeatPartitionDetector` emits `PartitionEvent` (Unresponsive/Recovered/Lost); depends only on `kuilt-core` |
| `kuilt-session` | Membership-aware `Room`: admit/identify handshake, roster, reconnect tokens |
| `kuilt-websocket` | Ktor WebSocket fabric (`KtorClientLoom` + `KtorServerLoom`) |
| `kuilt-mdns` | Bonjour/mDNS discovery feeding a WebSocket connection |
| `kuilt-multipeer` | Apple Multipeer Connectivity fabric (iOS/macOS) |
| `kuilt-nearby` | Google Nearby Connections fabric (Android) |
| `kuilt-webrtc` | WebRTC data-channel fabric (wasmJs) |
| `kuilt-conformance` | `SeamConformanceSuite` + `RoomConformanceSuite` — prove any fabric or room implementation correct |

## Near and Far

kuilt fabrics fall into two topologies:

- **Far** — client → server relay (`kuilt-websocket`, whether the server is on-LAN or remote).
- **Near** — peer ↔ peer (`kuilt-multipeer`, `kuilt-nearby`, `kuilt-webrtc`).

Discovery is separate from transport. mDNS, Multipeer browsing, and WebRTC
signaling help peers find each other and exchange connection info. They feed a
fabric; they are not fabrics themselves. An mDNS-discovered game still connects
over WebSocket (Far), which is why `kuilt-mdns` depends on `kuilt-websocket`.

## What kuilt is not

kuilt deliberately stops at moving bytes between connected peers. It does not provide:

- **Role assignment** (host vs. joiner) — that is `kuilt-session`'s `Room`.
- **Membership and join/leave lifecycle** — also `kuilt-session`.
- **Application message semantics** — `Swatch.payload` bytes mean whatever your application says they mean. kuilt never inspects them.
