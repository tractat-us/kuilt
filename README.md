# kuilt

[![Maven Central](https://img.shields.io/maven-central/v/us.tractat.kuilt/kuilt-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core)
[![CI](https://github.com/tractat-us/kuilt/actions/workflows/ci.yml/badge.svg)](https://github.com/tractat-us/kuilt/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A Kotlin Multiplatform networking library — one codebase, every domain.

Kotlin Multiplatform lets you share application logic across servers, browsers,
and phones. kuilt extends that to the three parts of a connected app that are
usually different on every platform:

### One network fabric — server, web, and phone

WebSocket on a server, WebRTC in a browser, Multipeer Connectivity on iPhone,
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
any network at all. Live propagation via `SeamReplicator` is optional.

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

---

## Quick start

```kotlin
val loom = InMemoryLoom()                            // swap for any real fabric
val host  = loom.host(Pattern(displayName = "alice"))
val guest = loom.join(InMemoryTag("bob"))

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
    implementation(platform("us.tractat.kuilt:kuilt-bom:0.5.0"))

    implementation("us.tractat.kuilt:kuilt-websocket")  // WebSocket fabric
    implementation("us.tractat.kuilt:kuilt-raft")       // Raft consensus
    implementation("us.tractat.kuilt:kuilt-crdt")       // CRDT zoo
    implementation("us.tractat.kuilt:kuilt-session")    // membership / room
}
```

Every module re-exports the `kuilt-core` contract, so you only list `kuilt-core`
directly if it's the sole thing you depend on.

### Without the BOM

```kotlin
// build.gradle.kts — pick the modules you need, specifying versions explicitly
dependencies {
    implementation("us.tractat.kuilt:kuilt-core:0.5.0")
    implementation("us.tractat.kuilt:kuilt-websocket:0.5.0")
}
```

Replace `0.4.0` with the [latest release](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core).

## Modules

**Contract & core**

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-core` | all | The contract (`Loom`/`Seam`/`Swatch`), `InMemoryLoom` reference impl. |

**Libraries**

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-crdt` | all | Delta-state CRDT zoo (`GCounter`, `ORSet`, `LWWMap`, `Rga`, `JsonCrdt`, `EphemeralMap`, …) + `SeamReplicator` live replication. |
| `kuilt-deal` | all | Cryptographically fair card dealing (`DealSession`, `SraScheme`) + dealer-less fair-random seed agreement (`FairRandom`). |
| `kuilt-game` | all | Turn-based game facade over `kuilt-raft`: `TurnSequencer` + `IndexedAction` + `SpeculativeSequencer` (optimistic apply + rollback). |
| `kuilt-raft` | all | Raft consensus — leader election, log replication, snapshots, dynamic membership, linearizable reads, leadership transfer. |
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

## Documentation

The full documentation suite is published to GitHub Pages on every push to `main`:

- **[Guide](https://tractat-us.github.io/kuilt/guide/)** — concepts, usage, fabrics,
  and the [CRDT zoo](https://tractat-us.github.io/kuilt/guide/crdt-overview.html),
  with every code example drawn from real, compiled test code.
- **[API reference](https://tractat-us.github.io/kuilt/api/)** — Dokka multi-module
  reference for every public symbol, with runnable `@sample` snippets.

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
