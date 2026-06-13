# kuilt

kuilt is a peer-symmetric, multiplatform networking library. It moves opaque byte frames between peers over interchangeable *fabrics* — WebSocket, mDNS-discovered LAN, Apple Multipeer Connectivity, WebRTC, Android Nearby — behind one contract.

**kuilt knows nothing about your application.** It carries bytes. What those bytes mean is the consumer's business.

Kotlin Multiplatform: JVM, Android, iOS, macOS, wasmJs.

## The fabric metaphor

A **loom** is a factory for sessions. A **seam** is one peer's view of a live session. A **swatch** is a frame of bytes. Every fabric — WebSocket, Multipeer, Nearby, WebRTC — implements these three types and is done. Your application code never sees a socket, a peer-connection, or a Bluetooth peripheral.

## Peer symmetry

There is no client/server split in kuilt's contract. Every peer in a session holds an identical `Seam`. A 2-peer WebSocket connection is just the degenerate `peers.size == 2` case of the symmetric model. This means the same application code runs whether your session has two peers or twenty, and whether the fabric is a relay server or a direct radio link.

## Modules at a glance

| Module | What it gives you |
|--------|-------------------|
| `kuilt-core` | The contract (`Loom`/`Seam`/`Swatch`), `InMemoryLoom` reference impl, conformance suite |
| `kuilt-crdt` | Delta-state CRDT zoo + `SeamReplicator` live replication |
| `kuilt-raft` | Raft consensus — leader election, log replication, snapshots, dynamic membership |
| `kuilt-session` | Membership-aware `Room`: admit/identify handshake, roster, reconnect tokens |
| `kuilt-websocket` | Ktor WebSocket fabric (`KtorClientLoom` + `KtorServerLoom`) |
| `kuilt-mdns` | Bonjour/mDNS discovery feeding a WebSocket connection |
| `kuilt-multipeer` | Apple Multipeer Connectivity fabric (iOS/macOS) |
| `kuilt-nearby` | Google Nearby Connections fabric (Android) |
| `kuilt-webrtc` | WebRTC data-channel fabric (wasmJs) |
| `kuilt-conformance` | `SeamConformanceSuite` — proves any fabric implementation correct |

## Near and Far

kuilt fabrics fall into two topologies:

- **Far** — client → server relay (`kuilt-websocket`, whether the server is on-LAN or remote).
- **Near** — peer ↔ peer (`kuilt-multipeer`, `kuilt-nearby`, `kuilt-webrtc`).

Discovery is orthogonal to this cut. mDNS, Multipeer browsing, and WebRTC signaling are rendezvous mechanisms — they tell you who is out there and how to reach them. They feed a fabric; they are not fabrics themselves. An mDNS-discovered game still connects over WebSocket (Far), which is why `kuilt-mdns` depends on `kuilt-websocket` and not the other way around.

## What kuilt is not

kuilt deliberately stops at moving bytes between connected peers. It does not provide:

- **Role assignment** (host vs. joiner) — that is `kuilt-session`'s `Room`.
- **Membership and join/leave lifecycle** — also `kuilt-session`.
- **Application message semantics** — `Swatch.payload` bytes mean whatever your application says they mean. kuilt never inspects them.
