# kuilt

[![Maven Central](https://img.shields.io/maven-central/v/us.tractat.kuilt/kuilt-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core)
[![CI](https://github.com/tractat-us/kuilt/actions/workflows/ci.yml/badge.svg)](https://github.com/tractat-us/kuilt/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A peer-symmetric, multiplatform networking library for moving opaque byte frames
between peers over interchangeable *fabrics* — WebSocket, mDNS-discovered LAN,
Multipeer, WebRTC — behind a single contract.

kuilt knows nothing about your application. It carries bytes; what those bytes
mean is the consumer's business.

Kotlin Multiplatform: JVM, Android, iOS, macOS, wasmJs.

## Setup

```kotlin
// settings.gradle.kts
repositories { mavenCentral() }
```

```kotlin
// build.gradle.kts — pick the modules you need
dependencies {
    implementation("us.tractat.kuilt:kuilt-core:0.4.0")
    implementation("us.tractat.kuilt:kuilt-websocket:0.4.0")  // WebSocket fabric
    implementation("us.tractat.kuilt:kuilt-raft:0.4.0")       // Raft consensus
    implementation("us.tractat.kuilt:kuilt-crdt:0.4.0")       // CRDT zoo
    implementation("us.tractat.kuilt:kuilt-session:0.4.0")    // membership / room
}
```

Replace `VERSION` with the [latest release](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core).

## Modules

**Contract & core**

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-core` | all | The contract (`Loom`/`Seam`/`Swatch`), `InMemoryLoom` reference impl. |

**Libraries**

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-crdt` | all | Delta-state CRDT zoo (`GCounter`, `ORSet`, `LWWMap`, `Rga`, …) + `SeamReplicator` live replication. |
| `kuilt-raft` | all | Raft consensus — leader election, log replication, snapshots, dynamic membership. |
| `kuilt-session` | all | Membership-aware `Room` (`SeamRoom`): handshake, roster, reconnect tokens, partition detection. |

**Fabrics**

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-websocket` | all | Ktor WebSocket fabric. `KtorClientLoom` everywhere; `KtorServerLoom` on JVM/Android. |
| `kuilt-mdns` | JVM, Android, iOS | Bonjour/mDNS discovery feeding a WebSocket connection. |
| `kuilt-multipeer` | iOS, macOS | Apple Multipeer Connectivity fabric. |
| `kuilt-nearby` | Android | Google Nearby Connections fabric. |
| `kuilt-webrtc` | wasmJs | WebRTC data-channel fabric. |

**Test support**

| Module | What it gives you |
|--------|-------------------|
| `kuilt-conformance` | `SeamConformanceSuite` — verifies any fabric impl with one subclass. |
| `kuilt-test` | Shared fakes and test utilities built on `kuilt-core`. |

Every module depends only on `kuilt-core` (plus any fabric it wraps).

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

## Quick start

```kotlin
// In a test, or any layer above the wire, the in-memory fabric needs no network:
val loom: Loom = InMemoryLoom()
val host = loom.host(Pattern(displayName = "alice"))

launch { host.incoming.collect { swatch -> handle(swatch.payload) } } // collect once
host.broadcast("hello".encodeToByteArray())
```

See **[docs/usage.md](docs/usage.md)** to open/join a WebSocket session, discover
peers over mDNS, and write + conformance-test your own fabric. See
**[docs/architecture.md](docs/architecture.md)** for the design and the rules
the contract enforces.

## Building

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem  # JDK 21, matches CI
./gradlew build       # build + test everything
./gradlew jvmTest     # fast inner loop
```
