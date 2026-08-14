# kuilt

[![Maven Central](https://img.shields.io/maven-central/v/us.tractat.kuilt/kuilt-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core)
[![CI](https://github.com/tractat-us/kuilt/actions/workflows/ci.yml/badge.svg)](https://github.com/tractat-us/kuilt/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A Kotlin Multiplatform networking library — one codebase, every domain.

Kotlin Multiplatform lets you share application logic across servers, browsers,
and phones. kuilt extends that to the parts of a connected app that are usually
different on every platform:

### One network fabric — server, web, and phone

WebSocket on a server, WebRTC in a browser, Network.framework on iPhone,
Nearby on Android: four different APIs for the same idea. kuilt replaces them
with a single interface — host a session, join a session, send a frame, watch
who's connected. Swap the underlying transport without changing your application
code.

### Replicated data structures

When users on different devices edit shared state at the same time, something
has to decide what wins. kuilt's [replicated data structures][crdt] converge
automatically to the same result on every device — no custom merge logic, no
"last save wins" bugs.

The CRDT types (`LWWMap`, `ORSet`, `Rga`, `JsonCrdt`, …) are plain serializable
value objects. You can use them with any transport you already have — or without
any network at all. Live propagation via `Quilter` is optional.

[crdt]: https://tractat-us.github.io/kuilt/guide/crdt-overview.html

### Consensus and leadership

Collaborative and multiplayer apps need a reliable answer to "who goes next?"
and "did that just happen?". kuilt's shared log — powered by
[Raft][raft-guide] — gives every participant the same committed history, in
the same order, even when connections drop and rejoin.

`RaftTransport` is an interface — you can run the Raft implementation over
your own messaging layer (gRPC, a queue, anything) without using any kuilt
fabric module.

[raft-guide]: https://tractat-us.github.io/kuilt/guide/

### Logs and metrics that outlive being offline

When something goes wrong on a phone with no signal, the evidence is usually
gone by the time anyone looks. kuilt records traces, metrics and logs on the
device and reconciles them across devices once the network returns — nothing
lost, nothing counted twice.

It's [OpenTelemetry][otel], so collectors and dashboards you already run read it
unchanged. Tests and CI can also pull the logs off a live device by joining it
as a peer, without the device ever talking to a server.

[otel]: https://tractat-us.github.io/kuilt/guide/observability.html

---

## Quick start

```kotlin
val loom = InMemoryLoom()                            // swap for any real fabric
val host  = loom.host(Pattern(sessionName = "alice"))
val guest = loom.join(InMemoryTag("bob"))

// Collect `incoming` exactly once per Seam; fan out with shareIn if you need to.
scope.launch { host.incoming.collect { println(it.payload.decodeToString()) } }
guest.broadcast("hello".encodeToByteArray())
```

→ **[Getting started](https://tractat-us.github.io/kuilt/guide/getting-started.html)** — WebSocket hello world, then chat (replicated data) and tic-tac-toe (consensus and leadership) built on top.

---

## Setup

```kotlin
// settings.gradle.kts
repositories { mavenCentral() }
```

### Using the BOM (recommended for multi-module consumers)

Import the BOM once to align all module versions, then add individual modules without version numbers:

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("us.tractat.kuilt:kuilt-bom:0.7.3"))

    implementation("us.tractat.kuilt:kuilt-websocket")  // WebSocket fabric
    implementation("us.tractat.kuilt:kuilt-raft")       // Raft consensus
    implementation("us.tractat.kuilt:kuilt-crdt")       // CRDT zoo
    implementation("us.tractat.kuilt:kuilt-session")    // membership / room
}
```

Fabric and session modules re-export the `kuilt-core` contract, so you rarely
list `kuilt-core` directly. The data modules are the exception: `kuilt-crdt` and
`kuilt-bolt` are plain serializable value types that don't depend on the
networking contract at all, so add `kuilt-core` yourself if you want `Loom` and
`Seam` alongside them.

### Without the BOM

```kotlin
// build.gradle.kts — pick the modules you need, specifying versions explicitly
dependencies {
    implementation("us.tractat.kuilt:kuilt-core:0.7.3")
    implementation("us.tractat.kuilt:kuilt-websocket:0.7.3")
}
```

Replace `0.7.3` with the [latest release](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core).

## Modules

**Contract & core**

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-core` | all | The contract (`Loom`/`Seam`/`Swatch`), `InMemoryLoom` reference impl. |

**Libraries**

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-crdt` | all | Delta-state CRDT zoo (`GCounter`, `ORSet`, `LWWMap`, `Rga`, `JsonCrdt`, `EphemeralMap`, …). Plain value types — no network needed. Live replication over a `Seam` is `kuilt-quilter`'s `Quilter`. |
| `kuilt-bolt` | all | Write-only history archive kept beside a live replica (`Bolt`, `BoltDecorator`): a server can keep a year of edits while the phone that fed it keeps an hour. `InMemoryBolt` everywhere; memory-mapped files on JVM/Android (`MappedBolt`) and Apple (`PosixMappedBolt`). |
| `kuilt-gossip` | all | Partial-mesh overlay (`GossipSeam`): each peer gossips with ~k neighbours so broadcast and GC scale O(k), not O(N), for large sessions. |
| `kuilt-deal` | all | Cryptographically fair card dealing (`DealSession`, `SraScheme`) + dealer-less fair-random seed agreement (`FairRandom`). |
| `kuilt-game` | all | Turn-based game facade over `kuilt-raft`: `TurnSequencer` + `IndexedAction` + `SpeculativeSequencer` (optimistic apply + rollback). |
| `kuilt-raft` | all | Raft consensus — leader election, log replication, snapshots, dynamic membership, linearizable reads, leadership transfer. |
| `kuilt-session` | all | Membership-aware `Room` (`SeamRoom`): handshake, roster, reconnect tokens, partition detection. |
| `kuilt-heddle` | all | Fair-share scheduling of a pooled resource across peers, with no central referee: each group gets the slice it was promised, an idle group lends its share to a busy one, and it survives a partition. |

**Fabrics**

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-websocket` | all | Ktor WebSocket fabric. `KtorClientLoom` everywhere; `KtorServerLoom` on JVM/Android. |
| `kuilt-mdns` | JVM, Android, iOS | Bonjour/mDNS discovery feeding a WebSocket connection. |
| `kuilt-multipeer` | iOS, macOS | Apple Multipeer Connectivity fabric. |
| `kuilt-nw` | iOS, macOS | Apple Network.framework full-mesh peer-to-peer fabric — nearby devices find each other and connect directly, no server and no shared Wi-Fi. The successor to `kuilt-multipeer`. |
| `kuilt-nearby` | Android | Google Nearby Connections fabric. |
| `kuilt-webrtc` | wasmJs | WebRTC data-channel fabric. |

**Test support**

| Module | What it gives you |
|--------|-------------------|
| `kuilt-conformance` | `SeamConformanceSuite` — verifies any fabric impl with one subclass. |
| `kuilt-test` | Shared fakes and test utilities built on `kuilt-core`. |

**Observability**

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-otel` | all | Offline-first OpenTelemetry exporter: record traces, metrics, and logs on any device and have them reconcile when connectivity returns — with no duplicates and no data loss (`WarpTelemetry`, `WarpOtlpBridge`). See the [Observability guide](https://tractat-us.github.io/kuilt/guide/observability.html). |

**Playground** — pre-1.0, outside the stability surface; API may change, not for production.

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-warp` | all | Coordination-free distributed task scheduler over a connected mesh: spread work across whoever is connected, no central boss, no peer doing the same job twice (`WarpNode`). See the [Warp guide](https://tractat-us.github.io/kuilt/guide/warp.html). |

Most modules sit directly on `kuilt-core`, but the higher-level ones build on
each other — `kuilt-heddle` layers over `kuilt-raft` and `kuilt-quilter`, and
`kuilt-game` over `kuilt-raft` and `kuilt-session`. Gradle pulls in whatever a
module needs; the BOM keeps every version aligned. The one rule that never bends
is the direction: nothing points back into `kuilt-core`, which is what keeps the
contract free of any one fabric's assumptions.

## The vocabulary

The contract is quilt-themed. Eight types carry the whole surface:

| Type | Role |
|------|------|
| `Loom` | Factory — `host(Pattern): Seam`, `join(Tag): Seam`, `weave(Rendezvous): Seam`, `availability(): FabricAvailability` |
| `Seam` | One peer's symmetric view of a live session — `selfId`, `peers: StateFlow<Set<PeerId>>`, `incoming: Flow<Swatch>`, `broadcast`, `sendTo`, `close` |
| `Swatch` | Opaque, binary-only frame — `payload: ByteArray`, plus `sender`/`sequence` stamped on receipt |
| `Pattern` | Config for opening a session (display name, max peers) |
| `Tag` | Discovery handle for joining one (mDNS record / MC peer / WS URL) |
| `PeerId` | Stable peer identifier within a session |
| `FabricAvailability` | `Available` / `Unavailable(reason)` — is this fabric usable on this runtime? |
| `CloseReason` | `Normal` / `Error` / `RemoteRequested` |

There is **no client/server split**. Every peer holds an identical `Seam`; a
2-peer WebSocket connection is the degenerate `peers.size == 2` case of the same
symmetric model.

## Documentation

The full documentation suite is published to GitHub Pages on every push to `main`:

- **[Guide](https://tractat-us.github.io/kuilt/guide/)** — concepts, usage, fabrics,
  and the [CRDT zoo](https://tractat-us.github.io/kuilt/guide/crdt-overview.html),
  with every code example drawn from real, compiled test code.
- **[API reference](https://tractat-us.github.io/kuilt/api/)** — Dokka multi-module
  reference for every public symbol, with runnable `@sample` snippets.
- **[Observability](https://tractat-us.github.io/kuilt/guide/observability.html)**
  — the offline-first OpenTelemetry exporter (`kuilt-otel`): record on any device,
  survive being offline, and deliver to your dashboard with no duplicates.
- **[Playground → Warp](https://tractat-us.github.io/kuilt/guide/warp.html)**
  — the coordination-free distributed scheduler (`kuilt-warp`) and the research behind it.

The in-repo sources are **[docs/usage.md](docs/usage.md)** (open/join a WebSocket
session, discover peers over mDNS, write + conformance-test your own fabric) and
**[docs/architecture.md](docs/architecture.md)** (the design and the rules the
contract enforces).

## Building

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem  # JDK 21, matches CI
./gradlew build       # build + test everything
./gradlew jvmTest     # fast inner loop
```
