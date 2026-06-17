# Using kuilt

How to depend on kuilt, open and join sessions over each fabric, and write +
conformance-test a fabric of your own. For the *why* behind the contract, read
[architecture.md](architecture.md).

## Add the dependency

kuilt publishes to Maven Central under `us.tractat.kuilt:*`. Add the repository
and depend on the modules you need — the BOM is the recommended way to keep every
module on one aligned version:

```kotlin
// settings.gradle.kts or build.gradle.kts
repositories {
    mavenCentral()
}

// build.gradle.kts
dependencies {
    // Import the BOM once; then declare modules without version numbers.
    implementation(platform("us.tractat.kuilt:kuilt-bom:VERSION"))

    implementation("us.tractat.kuilt:kuilt-session")    // membership/room layer
    implementation("us.tractat.kuilt:kuilt-websocket")  // WebSocket fabric
    implementation("us.tractat.kuilt:kuilt-mdns")       // LAN discovery
}
```

Each module re-exports the `kuilt-core` contract (`Loom`/`Seam`/`Swatch`), so you
don't list `kuilt-core` separately unless it's the *only* thing you depend on.
Without the BOM, pin each module explicitly (`us.tractat.kuilt:kuilt-core:VERSION`).
Replace `VERSION` with the [latest release](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core).

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

## Bonding multiple transports (composite fabric)

When one peer should ride several transports at once — say a relay WebSocket
*and* a direct LAN link, for redundancy — wrap the per-transport `Loom`s in a
`CompositeLoom`. It weaves each as a *ply* and hands back a single `Seam` over the
union, so the rest of your code is unchanged:

```kotlin
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.composite.CompositeLoom

val loom = CompositeLoom(
    listOf(
        PlyId("ws")  to wsLoom,   // e.g. KtorClientLoom to the relay
        PlyId("lan") to lanLoom,  // e.g. a direct LAN/TCP fabric
    ),
)
val seam = loom.join(tag)         // one Seam, bonded over both plies
```

The composite keeps a single stable `selfId` across plies coming and going,
collapses a remote multi-homed peer to one entry in `peers`, sends over every live
ply, and drops the duplicate copy that arrives over the second path. A path
failing over is **not** a membership change — tear one ply and the survivor keeps
the peer present. Because the bonding sits below the `Seam`, you can feed the
composite straight into the layers above:

```kotlin
// Same wiring as any other Seam — Raft/CRDT never know there are two transports.
val replicator = Quilter(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,
    initial = GCounter.ZERO,
    messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
    scope = coroutineScope,
)
```

To attach or detach plies on a live session (an overlay that lights up when peers
come into proximity), construct `CompositeLoom` from a
`StateFlow<List<Pair<PlyId, Loom>>>` and emit a new list. See
[architecture.md](architecture.md#multipath-one-peer-several-transports) for the
design and [`ply-roadmap.md`](ply-roadmap.md) for what is deliberately deferred.

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

`kuilt-crdt` provides the delta-state CRDT zoo and `Quilter`, which
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

For live presence and awareness (cursors, typing indicators, per-peer ephemeral
state), use `EphemeralMap` with `EphemeralMapTracker`:

```kotlin
val tracker = EphemeralMapTracker<String>(ttlMs = 5_000)

// On each local heartbeat / state change:
val next = tracker.snapshot().put(myReplica, "cursor=42", clock = localClock++)
tracker.received(next)   // stamps local receive time
sendDelta(next)          // broadcast the update over the Seam

// On receiving a remote delta:
tracker.received(decoded)

// Read live peers (departed and TTL-expired entries are hidden):
val live: Map<ReplicaId, String> = tracker.live()
```

TTL eviction is the sole recovery mechanism after a peer restart — see
`EphemeralMap` KDoc for the reconnect / clock-reset contract.

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

// 6. Propose from any node — forwards to the leader automatically (Raft §8).
scope.launch {
    try {
        val committed = node.propose("set x=1".encodeToByteArray())
        println("committed at index ${committed.index}")
    } catch (e: LeadershipLostException) {
        // retry with idempotent key
    }
}
```

See the KDoc on `RaftNode` and `ClusterConfig` for the full API, and
`docs/superpowers/specs/2026-06-05-raft-design.md` for the design rationale.

## Server-cluster topology (`kuilt-cluster`, JVM/Android)

`:kuilt-cluster` packages the server-cluster topology as two high-level types:
`ServerCluster` (the server side — a voter mesh plus a relay accept loop) and
`ClusterClient` (the client side — a learner that proposes through the leader
and observes the committed stream).

Add the dependency:

```kotlin
implementation("us.tractat.kuilt:kuilt-cluster")
```

### Server side

`CoroutineScope.serverCluster()` wires `m` voter `RaftNode`s in-process
(complete-graph `K_m` channel transport) and mounts a `KtorRoomHost` relay
accept loop that admits learner clients as they connect. Voter nodes start
immediately; call `start()` in a `launch` to run the accept loop:

```kotlin
val host = KtorRoomHost(
    application = application,
    path = "/ws/cluster",
    serverPeerId = PeerId("server-1"),
    pattern = Pattern("cluster-room"),
)

val serverScope = CoroutineScope(coroutineContext + Job())
val cluster = serverScope.serverCluster(
    host = host,
    voterIds = listOf(NodeId("server-1")),   // m=1 for single-server; use 3 or 5 for fault-tolerance
    raftConfig = RaftConfig(/* … */),
)

serverScope.launch { cluster.start() }      // admit loop — runs until scope is cancelled

// Wait for a leader before accepting clients, if you need the guarantee.
val leader = cluster.awaitLeader()

// Collect committed entries on the server side (optional).
serverScope.launch {
    cluster.committed.collect { committed ->
        if (committed is Committed.Entry) applyToStateMachine(committed.entry.command)
    }
}
```

**NodeId ↔ PeerId alignment.** Each voter's `NodeId` must equal
`NodeId(serverPeerId.value)`. The relay stamps the server's `PeerId` as the
sender on every frame; the client's `SeamRaftTransport` maps that sender to a
`NodeId` for Raft message routing. Mismatched IDs cause silently dropped
AppendEntries.

### Client side

Clients use `clusterClientWithNode()` with a caller-managed `RaftNode` over a
`SeamRaftTransport`. The convenience extension `CoroutineScope.clusterClient()`
(relay-room with automatic reconnect) is declared but requires a stable client
identity on the loom (see #544) before it is production-ready.

```kotlin
// Join the server relay room.
val clientScope = CoroutineScope(coroutineContext + Job())
val clientRoom = SeamRoomFactory.systemClock(loom = clientLoom, scope = clientScope)
    .join(
        WebSocketAdvertisement(
            url = "ws://your-server-host/ws/cluster",
            serverPeerId = PeerId("server-1"),
            displayName = "my-client",
        ),
    )
val clientSeam = clientRoom.channel("raft")

// Derive the client's NodeId from the Seam selfId assigned at join time.
// The ServerCluster admission loop derives the same NodeId from the room roster.
val clientNodeId = NodeId(clientSeam.selfId.value)
val learnerConfig = ClusterConfig(
    voters = setOf(NodeId("server-1")),
    learners = setOf(clientNodeId),
)

// Wait for the admit handshake before starting the RaftNode.
withTimeout(5.seconds) { clientRoom.roster.first { it.isNotEmpty() } }

val clientNode = clientScope.raftNode(
    clusterConfig = learnerConfig,
    transport = SeamRaftTransport(clientSeam),
    storage = InMemoryRaftStorage(),
    raftConfig = RaftConfig(/* … */),
)
val client: ClusterClient = clusterClientWithNode(clientNode)

// Observe committed entries.
clientScope.launch {
    client.committed.collect { committed ->
        if (committed is Committed.Entry) applyToStateMachine(committed.entry.command)
    }
}

// Propose a command. Forwards to the leader; suspends until committed.
val entry: LogEntry = client.propose("action:move=1".encodeToByteArray())
println("committed at index ${entry.index}")

// For cross-crash exactly-once semantics, persist the requestId and replay it on retry.
val entry2: LogEntry = client.propose("action:move=2".encodeToByteArray(), requestId = 42L)

client.close()
```

### Failover (round-robin endpoints)

On transport tear, `ServerClusterReconnect` advances to the next endpoint from
the ordered `ClusterEndpoints` list and reconnects. Cross-server resume always
degrades to fresh-join (see #532): each server's reconnect-window registry is
in-memory and per-room-instance, so a `ResumeToken` from server-A is unknown to
server-B. `ClusterClient` treats this as a fall-back-to-fresh-join signal, not
an error — reconnect is correct, it costs a re-snapshot on the learner's log.

### Exactly-once proposals

`propose(command)` auto-mints a monotonic `requestId`. `propose(command,
requestId)` is the cross-crash exactly-once overload: persist `requestId` before
calling, replay it after a crash or failover. The server's `ClientSessionTable`
deduplicates retries transparently.

### Current scope

- M=3 voter mesh is proven under simulation; M=1 is proven over real sockets
  (`ServerClusterE2ETest`, S3b-3 of #513). Real-socket M>1 E2E is #545.
- Failover/resume across an entry-server change is unit-tested and
  sim-proven; the production `clusterClient(loom, …)` reconnect path is
  pending a stable client identity on the loom (see #544). Use
  `clusterClientWithNode()` for caller-managed transport in the meantime.

See `docs/architecture.md#server-cluster-topology` for the topology design and
safety rationale.
