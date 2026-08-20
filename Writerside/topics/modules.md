# All modules

The [overview](overview.md) highlights the handful you meet first; this page goes
wider. Each module is published independently, so you depend only on the ones you
use.

There are more in the build than are listed here — a test-support module for
several of the libraries below, the `kuilt-bom` version-alignment platform, and
the pieces that only run at build time or on a benchmark. The
[API reference](https://tractat-us.github.io/kuilt/api/) covers every published
module.

| Module | What it gives you |
|--------|-------------------|
| `kuilt-core` | The contract (`Loom`/`Seam`/`Swatch`), `InMemoryLoom` reference impl, `MuxSeam` + `NamedMux` channel splitters |
| `kuilt-crdt` | Replication data structures (`GCounter`, `ORSet`, `LWWMap`, `JsonCrdt`, …) |
| `kuilt-quilter` | Live replication over a `Seam`: `Quilter` propagates deltas and merges inbound changes |
| `kuilt-bolt` | A history archive kept beside a live replica (`Bolt`, `BoltDecorator`): a server keeps a year of edits while the phone that fed it keeps an hour |
| `kuilt-store` | Put some bytes somewhere they will still be after a restart (`DurableStore`): the write comes back only once the data is safe, with a crash-safe implementation for each platform |
| `kuilt-gossip` | Partial-mesh overlay (`GossipSeam`): gossip with ~k neighbours so large sessions scale O(k), not O(N) |
| `kuilt-deal` | Cryptographically fair card dealing (`DealSession`) + dealer-less fair-random (`FairRandom`) |
| `kuilt-game` | Turn-based game facade: `gameHost`/`gameJoin`/`gameNode` → `GameSession`, `TurnSequencer`, `SpeculativeSequencer` |
| `kuilt-raft` | Raft consensus — leader election, log replication, snapshots, dynamic membership, linearizable reads, leadership transfer |
| `kuilt-cluster` | Server-cluster overlay: `ServerCluster` (voter mesh + relay accept loop) + `ClusterClient` (propose + observe) |
| `kuilt-liveness` | Peer-liveness detection: `HeartbeatPartitionDetector` emits `PartitionEvent` (Unresponsive/Recovered/Lost) |
| `kuilt-session` | Membership-aware `Room`: admit/identify handshake, roster, reconnect tokens |
| `kuilt-heddle` | Fair shares of one pooled resource, with nobody in charge: each group gets the slice it was promised, an idle group lends its share to a busy one, and it keeps working through a network split — see [Fair Share](heddle.md) |
| `kuilt-websocket` | Ktor WebSocket fabric (`KtorClientLoom` + `KtorServerLoom`) |
| `kuilt-tcp` | Plain TCP sockets as a fabric (`tcpLoomHost`/`tcpLoomJoin`, JVM/Android) — the worked example in the [fabric kit](fabric-kit.md) |
| `kuilt-mdns` | Bonjour/mDNS local-network discovery feeding a WebSocket connection |
| `kuilt-multipeer` | Apple Multipeer Connectivity fabric (iOS/macOS) |
| `kuilt-nearby` | Google Nearby Connections fabric (Android) |
| `kuilt-webrtc` | WebRTC data-channel fabric (wasmJs) |
| `kuilt-stream` | Turns any ordered pipe of bytes — a socket, your own in-house protocol — into a fabric: `framed()` puts the message boundaries back and rejects an oversized frame before allocating for it |
| `kuilt-conformance` | `SeamConformanceSuite` + `RoomConformanceSuite` — prove any fabric or room implementation correct |
| `kuilt-otel` | Offline-first telemetry: record logs, metrics, and traces on any device; they sync up when the network returns, with no duplicates. Companion modules pick up what your app and its libraries already log and carry it to your dashboard — see [Device to dashboard](observability.md) |
| `kuilt-warp` | Spreads a pile of work across whoever is connected — no central boss, and no peer doing the same job twice (`WarpNode`). It and the modules around it are a preview, not a foundation — see [Warp](warp.md) |
