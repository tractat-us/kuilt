# Fabrics

Every fabric implements `Loom` and a private `Seam`. Your application code only ever sees the contract — swap the `Loom` and the rest is unchanged.

## WebSocket fabric (`kuilt-websocket`)

WebSocket sessions have asymmetric setup (a server accepts, a client connects) but the resulting `Seam` is symmetric for both. The split is:

- `KtorServerLoom` — JVM/Android. Supports only `host()`; `join()` throws.
- `KtorClientLoom` — all targets. Supports only `join()`; `host()` throws.

**Server:**

```kotlin
val server = KtorServerLoom(application, path = "/live", selfPeerId = PeerId("server-1"))
scope.launch {
    while (isActive) {
        val seam = server.nextLink()  // suspends until a client connects
        handleConnection(seam)
    }
}
```

**Client:**

```kotlin
val client = KtorClientLoom(httpClient)
val seam = client.join(
    WebSocketAdvertisement(
        url = "ws://192.168.1.10:8080/live",
        serverPeerId = PeerId("server-1"),
        displayName = "alice",
    ),
)
```

The advertisement carries the server's `PeerId` so both ends arrive at the same membership view without an in-band handshake.

## mDNS discovery (`kuilt-mdns`, JVM/Android)

mDNS is rendezvous over the LAN — the actual session still runs over WebSocket. `MDNSPeerLinkFactory` registers an mDNS service on `host()` and resolves an `MDNSAdvertisement` to a WebSocket join on `join()`. Discover peers separately with `MDNSServiceDiscoverer`:

```kotlin
val jmdns = JmDNS.create()

// Host: registers the mDNS service and starts the WebSocket server.
val host = MDNSPeerLinkFactory(application, jmdns, port = 8080, httpClientFactory = { HttpClient { } })
val hostSeam = host.host(Pattern("alice's game"))

// Joiner: discover then join.
val discoverer = MDNSServiceDiscoverer(jmdns)
val ad = discoverer.discoveries().first()
val joinerSeam = host.join(ad)
```

Bound your collection with a timeout or `take(n)` — `discoveries()` emits indefinitely.

## Near fabrics

`kuilt-multipeer` (iOS/macOS) and `kuilt-nearby` (Android) are Near fabrics — peer-to-peer, no relay server. They both implement `Loom` and return the same instance for host and join (one in-process mesh). Replace `InMemoryLoom` with one of these and your application code is unchanged.

`kuilt-webrtc` (wasmJs) provides a WebRTC data-channel fabric. WebRTC sessions involve a signaling handshake, but that is entirely inside the fabric implementation — callers see only `Loom`/`Seam`.

## Writing your own fabric

Implement `Loom` (and a private `Seam`), then prove it conforms by subclassing `SeamConformanceSuite`:

```kotlin
class MyFabricLoom : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam = when (rendezvous) {
        is Rendezvous.New -> TODO("host")
        is Rendezvous.Existing -> TODO("join")
    }
    override fun availability(): FabricAvailability =
        if (myCapabilityPresent()) FabricAvailability.Available
        else FabricAvailability.Unavailable("my radio is off")
}

// In commonTest — this is your contract test. Green means you conform.
class MyFabricConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> {
        val loom = MyFabricLoom()
        return loom to loom   // same instance for in-process radio fabrics
        // role-split fabrics: return hostLoom to joinerLoom (distinct instances wired together)
    }
}
```

`newLoomPair()` returns `(hostLoom, joinerLoom)`. In-process radio fabrics return the same instance twice (shared mesh). Role-split fabrics (WebSocket, mDNS, WebRTC) return distinct host and joiner instances wired to each other. The suite runs `host()` and `join()` concurrently — this matters for WebSocket-style fabrics where `host()` suspends until a client connects.

The suite tests:
- `weave(Rendezvous.New(...))` returns a `Seam` with a non-empty `selfId`.
- `broadcast` and `sendTo` deliver frames and stamp `sender`.
- `peers` tracks membership.
- `incoming` is single-collection and ordered.
- `close()` is idempotent.
- `availability()` returns sensibly.

Keep real-network smoke tests in a separate test that is opt-in (e.g. `-Pmy.fabric.integration.tests=true`) so the conformance suite stays fast and deterministic.

## `Tag` and custom discovery

`Tag` is an open interface. Each fabric defines its own (`WebSocketAdvertisement`, `MDNSAdvertisement`, …). A custom fabric supplies its own `Tag` carrying whatever its `join()` needs.

## The membership layer (`kuilt-session`)

`Seam` is pure transport — `peers` is whoever the wire says is connected. When you need who has *identified themselves*, who is the host, or whether someone dropped and might reconnect, use `kuilt-session`.

`SeamRoomFactory` wraps any `Loom` and produces `Room`s with an admit/identify handshake, a roster of admitted members, reconnect tokens, and partition detection:

```kotlin
val factory: RoomFactory = SeamRoomFactory(loom, scope)
val room: Room = factory.host(Pattern(displayName = "alice", maxPeers = 4))

scope.launch { room.roster.collect { members -> render(members) } }
scope.launch { room.events.collect { event -> handle(event) } }
scope.launch { room.incoming.collect { frame -> consume(frame.sender, frame.payload) } }

room.broadcast("hello room".encodeToByteArray())
room.leave()
```

Because `SeamRoomFactory` accepts any `Loom`, the same code runs over `InMemoryLoom` in tests and over WebSocket or mDNS in production.
