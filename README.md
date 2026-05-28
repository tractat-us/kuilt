# kuilt

A peer-symmetric, multiplatform networking library for moving opaque byte frames
between peers over interchangeable *fabrics* — WebSocket, mDNS-discovered LAN,
Multipeer, WebRTC — behind a single contract.

kuilt knows nothing about your application. It carries bytes; what those bytes
mean is the consumer's business. Published to GitHub Packages under
`us.tractat.kuilt:*`.

Kotlin Multiplatform: JVM, Android, iOS, macOS, wasmJs.

## Modules

| Module | Targets | What it gives you |
|--------|---------|-------------------|
| `kuilt-core` | all | The contract (`Loom`/`Seam`/`Swatch`), the `InMemoryLoom` reference implementation, and `SeamConformanceSuite` for testing your own fabric. |
| `kuilt-websocket` | all | A Ktor WebSocket fabric. `KtorClientLoom` everywhere; `KtorServerLoom` on JVM/Android. |
| `kuilt-mdns` | JVM, Android, iOS | Bonjour/mDNS service discovery feeding a WebSocket connection. |

Every fabric module depends only on `kuilt-core`.

## The vocabulary

The contract is quilt-themed. Eight types carry the whole surface:

| Type | Role |
|------|------|
| `Loom` | Factory — `open(Pattern): Seam`, `join(Tag): Seam`, `availability(): FabricAvailability` |
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
val host = loom.open(Pattern(displayName = "alice"))

launch { host.incoming.collect { swatch -> handle(swatch.payload) } } // collect once
host.broadcast("hello".encodeToByteArray())
```

See **[docs/usage.md](docs/usage.md)** to add a dependency, open/join a real
WebSocket session, discover peers over mDNS, and write + conformance-test your
own fabric. See **[docs/architecture.md](docs/architecture.md)** for the design
and the rules the contract enforces.

## Building

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem  # JDK 21, matches CI
./gradlew build       # build + test everything
./gradlew jvmTest     # fast inner loop
```
