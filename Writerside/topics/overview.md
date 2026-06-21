# kuilt

kuilt connects peers and keeps their data in sync — across WebSocket, LAN, Bluetooth, and WebRTC — without changing your app code when you swap transports.

It is a Kotlin Multiplatform library (JVM, Android, iOS, macOS, wasmJs).

## Three parts — pick what you need

- **Network Fabric** — one API for sending messages between devices.
  `Loom` opens a session; `Seam` is your send/receive handle on that session.
  Swap WebSocket for LAN discovery or Bluetooth without touching your app logic.

- **Replicated Data** — data structures that automatically merge when two devices edit at the same time.
  `LWWMap`, `ORSet`, `Rga`, `JsonCrdt`, and eleven others from `kuilt-crdt`.
  Add `Quilter` to propagate changes live over a `Seam`.

- **Consensus** — when every peer must agree on the same order of decisions (turns in a game, locks, durable steps), this picks a leader and keeps everyone in sync.
  `kuilt-raft` is a full Raft implementation. `TurnSequencer` (from `kuilt-game`) wraps it for turn-based games.

## Pick by the guarantee you need

- **Connect and send bytes** → add a fabric only.
- **Shared state that survives offline edits and concurrent updates** → add Replicated Data.
- **Strict turn order or globally-agreed decisions** → add Consensus on top.

Start with the weakest guarantee that keeps your product correct. Add stronger guarantees only where needed.

→ [Getting started: two peers, one session](getting-started.md)

## How it fits together

A **loom** creates sessions. A **seam** is one peer's view of a live session. A **swatch** is a frame of bytes. Every fabric (WebSocket, Multipeer, Nearby, WebRTC) implements these three types. Your app code never deals with socket APIs, Bluetooth internals, or peer-connection objects directly.

Every peer in a session uses the same `Seam` interface — there is no client/server split at this layer. The same app code runs with two peers or twenty, and over relay or direct links.

## Modules at a glance

| Module | What it gives you |
|--------|-------------------|
| `kuilt-core` | The contract (`Loom`/`Seam`/`Swatch`), `InMemoryLoom` reference impl, `MuxSeam` + `NamedMux` channel splitters |
| `kuilt-crdt` | Replicated data structures (`GCounter`, `ORSet`, `LWWMap`, `JsonCrdt`, …) |
| `kuilt-quilter` | Live replication over a `Seam`: `Quilter` propagates deltas and merges inbound changes |
| `kuilt-deal` | Cryptographically fair card dealing (`DealSession`) + dealer-less fair-random (`FairRandom`) |
| `kuilt-game` | Turn-based game facade: `gameHost`/`gameJoin`/`gameNode` → `GameSession`, `TurnSequencer`, `SpeculativeSequencer` |
| `kuilt-raft` | Raft consensus — leader election, log replication, snapshots, dynamic membership, linearizable reads, leadership transfer |
| `kuilt-cluster` | Server-cluster overlay: `ServerCluster` (voter mesh + relay accept loop) + `ClusterClient` (propose + observe) |
| `kuilt-liveness` | Peer-liveness detection: `HeartbeatPartitionDetector` emits `PartitionEvent` (Unresponsive/Recovered/Lost) |
| `kuilt-session` | Membership-aware `Room`: admit/identify handshake, roster, reconnect tokens |
| `kuilt-websocket` | Ktor WebSocket fabric (`KtorClientLoom` + `KtorServerLoom`) |
| `kuilt-mdns` | Bonjour/mDNS local-network discovery feeding a WebSocket connection |
| `kuilt-multipeer` | Apple Multipeer Connectivity fabric (iOS/macOS) |
| `kuilt-nearby` | Google Nearby Connections fabric (Android) |
| `kuilt-webrtc` | WebRTC data-channel fabric (wasmJs) |
| `kuilt-conformance` | `SeamConformanceSuite` + `RoomConformanceSuite` — prove any fabric or room implementation correct |

## What kuilt is not

kuilt moves bytes between peers. It does not assign roles, manage membership lifecycles, or interpret your payload bytes. Those responsibilities belong to your app — or to `kuilt-session` when you need a `Room` with admit/leave semantics.
