# Using kuilt

How to depend on kuilt, open and join sessions over each fabric, and write +
conformance-test a fabric of your own. For the *why* behind the contract, read
[architecture.md](architecture.md).

## Add the dependency

kuilt publishes to GitHub Packages under `us.tractat.kuilt:*`. Add the repository
(it needs a GitHub token with `read:packages`) and depend on the modules you need:

```kotlin
// settings.gradle.kts or build.gradle.kts
repositories {
    maven("https://maven.pkg.github.com/tractat-us/kuilt") {
        credentials {
            username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
            password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
        }
    }
}

// build.gradle.kts
dependencies {
    implementation("us.tractat.kuilt:kuilt-core:0.1.+")
    implementation("us.tractat.kuilt:kuilt-websocket:0.1.+")  // if you want the WebSocket fabric
    implementation("us.tractat.kuilt:kuilt-mdns:0.1.+")       // if you want LAN discovery
}
```

The whole surface is `Loom` (make a session), `Seam` (use it), `Swatch` (the
frames). Everything below is those three types over different wires.

## The shape of every interaction

```kotlin
// 1. Get a Seam — either by opening a session or joining one.
val seam: Seam = loom.open(Pattern(displayName = "alice", maxPeers = 4))

// 2. Collect incoming frames EXACTLY ONCE. Fan out with shareIn if you need to.
scope.launch {
    seam.incoming.collect { swatch ->
        println("from ${swatch.sender}: ${swatch.payload.decodeToString()}")
    }
}

// 3. Send. broadcast() goes to all peers; sendTo() targets one.
seam.broadcast("hello everyone".encodeToByteArray())
seam.sendTo(somePeerId, "just for you".encodeToByteArray())

// 4. Watch membership.
scope.launch { seam.peers.collect { current -> render(current) } }

// 5. Close when done. Idempotent.
seam.close()
```

> **Collect `incoming` once.** It is a single-collection flow — all peers' frames
> arrive on it in order, delivered to one collector. A second concurrent collector
> races. If several parts of your app need the frames, do
> `val shared = seam.incoming.shareIn(scope, SharingStarted.Eagerly)` and collect
> `shared`.

## In-memory (tests and the layer above)

`InMemoryLoom` needs no network. Every `Seam` it produces shares one in-memory
mesh, so it's how you test code built on top of kuilt:

```kotlin
val loom = InMemoryLoom()
val host = loom.open(Pattern("host"))
val joiner = loom.join(InMemoryTag("joiner"))

val received = async { joiner.incoming.take(1).toList() }
host.broadcast(byteArrayOf(1, 2, 3))
assertEquals(host.selfId, received.await().first().sender)
```

## WebSocket fabric (`kuilt-websocket`)

Asymmetric *setup*, symmetric *use*: a server accepts connections, a client
joins, and both ends end up holding an ordinary 2-peer `Seam`.

**Server (JVM/Android)** — mount `KtorServerLoom` on a Ktor application and pull
connections off it:

```kotlin
val server = KtorServerLoom(application, path = "/live", selfPeerId = PeerId("server-1"))
scope.launch {
    while (isActive) {
        val seam = server.nextLink()           // suspends until a client connects
        handleConnection(seam)
    }
}
```

**Client (any target)** — `join` a `WebSocketAdvertisement`. `KtorClientLoom.open`
throws (clients don't host); always `join`:

```kotlin
val client = KtorClientLoom(httpClient /* Ktor HttpClient with WebSockets installed */)
val seam = client.join(
    WebSocketAdvertisement(
        url = "ws://192.168.1.10:8080/live",
        serverPeerId = PeerId("server-1"),     // must match the server's selfPeerId
        displayName = "alice",
    ),
)
```

The client and server arrive at the same membership view with no in-band
handshake because the advertisement carries the server's `PeerId`.

## mDNS discovery (`kuilt-mdns`, JVM/Android)

mDNS is *rendezvous over the LAN*; the actual session still runs over WebSocket.
`MDNSPeerLinkFactory` is a `Loom` that registers an mDNS service on `open` (and
runs the embedded WebSocket server underneath), and resolves an `MDNSAdvertisement`
to a WebSocket join on `join`. Discover peers separately with
`MDNSServiceDiscoverer`, which emits an `MDNSAdvertisement` per peer found:

```kotlin
val jmdns = JmDNS.create()

// Host: open() registers the mDNS service and waits for the first joiner.
val host = MDNSPeerLinkFactory(application, jmdns, port = 8080, httpClientFactory = { HttpClient { /* … */ } })
val hostSeam = host.open(Pattern("alice's game"))

// Joiner: discover, then join one of the advertisements.
val discoverer = MDNSServiceDiscoverer(jmdns)
val ad = discoverer.discoveries().first()      // apply your own timeout / take(n)
val joinerSeam = host.join(ad)
```

mDNS service resolution is timing-sensitive — bound your collection with a
timeout or `take(n)` rather than collecting `discoveries()` forever.

## Writing your own fabric

Implement `Loom` (and a private `Seam`), then **prove it conforms** by
subclassing `SeamConformanceSuite`:

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

// In commonTest — this is your contract test. If it's green, you conform.
class MyFabricConformanceTest : SeamConformanceSuite() {
    override fun newLoom(): Loom = MyFabricLoom()
}
```

Things the suite will hold you to (see [architecture.md](architecture.md) for the
full list): `weave(Rendezvous.New(...))` returns a usable `Seam` with a non-empty
`selfId`; `broadcast`/`sendTo` deliver and stamp `sender`; `peers` tracks
membership; `incoming` is single-collection and ordered; `close` is idempotent.
Keep any real-network smoke tests in a separate, `-P`-gated test so the conformance
suite stays fast and deterministic.

## Provide your own `Tag` for discovery

`Tag` is an open interface, not sealed, so each fabric defines its own
(`WebSocketAdvertisement`, `MDNSAdvertisement`, …). A custom fabric supplies its
own `Tag` carrying whatever its `join` needs (`displayName` + a stable `peerKey`
are the only required fields).
