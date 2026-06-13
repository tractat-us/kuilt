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
    implementation("us.tractat.kuilt:kuilt-session:0.1.+")    // if you want the membership/room layer
    implementation("us.tractat.kuilt:kuilt-websocket:0.1.+")  // if you want the WebSocket fabric
    implementation("us.tractat.kuilt:kuilt-mdns:0.1.+")       // if you want LAN discovery
}
```

The whole surface is `Loom` (make a session), `Seam` (use it), `Swatch` (the
frames). Everything below is those three types over different wires.

## The shape of every interaction

```kotlin
// 1. Get a Seam — either by hosting a session or joining one.
val seam: Seam = loom.host(Pattern(displayName = "alice", maxPeers = 4))

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
val host = loom.host(Pattern("host"))
val joiner = loom.join(InMemoryTag("joiner"))

val received = async { joiner.incoming.take(1).toList() }
host.broadcast(byteArrayOf(1, 2, 3))
assertEquals(host.selfId, received.await().first().sender)
```

## The membership layer (`kuilt-session`)

`Seam` is pure transport: `peers` is whoever the wire says is connected. Most
applications want more — *who has identified themselves*, *who is the host*,
*has someone dropped and might reconnect*. That's `kuilt-session`. It wraps any
`Loom` and adds an **admit/identify handshake** on top: a connected peer becomes
an admitted `Member` only after the handshake completes, and only admitted
members appear in the `roster` or deliver frames to `incoming`.

`SeamRoomFactory` wraps a `Loom` and produces `Room`s. Because it takes any
`Loom`, the same code runs over `InMemoryLoom` in tests and over the WebSocket
or mDNS fabrics in production:

```kotlin
val factory: RoomFactory = SeamRoomFactory(loom, scope) // loom = any Loom; scope owns the room's coroutines

// Host a room (this peer becomes the Host) or join one (becomes a Joiner).
val room: Room = factory.host(Pattern(displayName = "alice", maxPeers = 4))
// val room = factory.join(someTag)   // on the joining peer

// The roster holds admitted members only — never raw, unidentified peers.
scope.launch { room.roster.collect { members -> render(members) } }

// Membership transitions: Joined / Left / Partitioned / Recovered / WindowOpened / Resumed / HostLost.
scope.launch { room.events.collect { event -> handle(event) } }

// Frames from admitted members, tagged with the sender. Single-collection, like Seam.incoming.
scope.launch { room.incoming.collect { frame -> consume(frame.sender, frame.payload) } }

room.broadcast("hello room".encodeToByteArray())
room.sendTo(somePeerId, "just for you".encodeToByteArray())

room.leave()  // idempotent
```

**Host loss is terminal — there is no auto-election.** When a joiner's link to
the host drops permanently, the room emits `MembershipEvent.HostLost` and
`broadcast`/`sendTo` become silent no-ops. The room does not promote a new host;
the consumer decides what to do (tear down, start a new session, etc.).

**Reconnect/resume.** A joiner's `room.resumeToken` (non-null once admitted)
is a credential it can save and present to `room.resume(token)` after a
transport drop, to re-enter the same room within the host's reconnect window.
The token carries the `RoomId`, not the host's identity, so it survives a host
change. Resume drives `MembershipEvent.WindowOpened` → `Resumed` (or `Left` with
`LeaveReason.PartitionExpired` if the window closes first).

`SeamRoomFactory` takes an injectable `clock: () -> Instant` and a
`HeartbeatConfig`; tests pass virtual time and tight intervals, production uses
real defaults. Conformance-test your own `RoomFactory` by subclassing
`RoomConformanceSuite` (the `Room` analogue of `SeamConformanceSuite`).

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

**Client (any target)** — `join` a `WebSocketAdvertisement`. `KtorClientLoom.host`
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

// Host: host() registers the mDNS service and waits for the first joiner.
val host = MDNSPeerLinkFactory(application, jmdns, port = 8080, httpClientFactory = { HttpClient { /* … */ } })
val hostSeam = host.host(Pattern("alice's game"))

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
// Return the same Loom instance twice for in-process radio fabrics (shared mesh),
// or distinct host/joiner Looms for role-split fabrics.
class MyFabricConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> {
        val loom = MyFabricLoom()
        return loom to loom  // adjust for role-split fabrics: hostLoom to joinerLoom
    }
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

## Live-converging state (`kuilt-crdt`)

`kuilt-crdt` provides the delta-state CRDT zoo and `SeamReplicator`, which
propagates any `Quilted<S>` over a `Seam` with no application-level merge
calls. For collaborative JSON documents, use `JsonCrdt`:

```kotlin
val doc = JsonCrdt.empty(ReplicaId("my-peer"))
    .set("title", JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, JsonValue.Str("Hello"))))

// After deserialization, restore the replica id before mutating:
val received: JsonCrdt = cbor.decodeFromByteArray(JsonCrdt.serializer(), bytes)
    .withReplica(ReplicaId("my-peer"))
```

See the `JsonCrdt` and `JsonNode` KDoc for conflict-resolution semantics and
the cross-type precedence rule (`Object > Array > Leaf`).

## Consensus layer (`kuilt-raft`)

`kuilt-raft` adds a Raft consensus layer on top of any kuilt `Seam`. Use it
when you need strongly-consistent, replicated state across multiple nodes —
for example, a shared game state machine or a distributed lock.

```kotlin
// 1. Describe the cluster.
val cluster = ClusterConfig.ofVoters(listOf(NodeId("a"), NodeId("b"), NodeId("c")))

// 2. Wrap a Seam as the transport (one per node).
val seam: Seam = loom.host(Pattern("raft-cluster"))
val transport = SeamRaftTransport(seam)

// 3. Provide storage (use a persistent implementation in production).
val storage = InMemoryRaftStorage()

// 4. Start the node — its lifetime is tied to the scope.
val node: RaftNode = scope.raftNode(cluster, transport, storage)

// 5. Apply committed entries on every node.
scope.launch {
    node.committed.collect { entry -> applyToStateMachine(entry.command) }
}

// 6. Propose on the leader.
scope.launch {
    node.awaitLeadership()
    try {
        val committed = node.propose("set x=1".encodeToByteArray())
        println("committed at index ${committed.index}")
    } catch (e: NotLeaderException) {
        // redirect to node.leader
    } catch (e: LeadershipLostException) {
        // retry with idempotent key
    }
}
```

See the KDoc on `RaftNode` and `ClusterConfig` for the full API, and
`docs/superpowers/specs/2026-06-05-raft-design.md` for the design rationale.
