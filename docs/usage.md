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

The fabric and session modules re-export the `kuilt-core` contract
(`Loom`/`Seam`/`Swatch`), so you don't list `kuilt-core` separately alongside
them. `kuilt-crdt`, `kuilt-bolt` and `kuilt-store` don't — they're plain value
types and a storage interface, with no dependency on the networking contract — so
add `kuilt-core` explicitly if you want both.

Without the BOM, pin each module explicitly (`us.tractat.kuilt:kuilt-core:VERSION`).
Replace `VERSION` with the [latest release](https://central.sonatype.com/artifact/us.tractat.kuilt/kuilt-core).

The whole surface is `Loom` (make a session), `Seam` (use it), `Swatch` (the
frames). Everything below is those three types over different wires.

## The shape of every interaction

```kotlin
// 1. Get a Seam — either by hosting a session or joining one.
val seam: Seam = loom.host(Pattern(sessionName = "alice", maxPeers = 4))

// 2. Collect incoming frames EXACTLY ONCE. Fan out with shareIn if you need to.
scope.launch {
    seam.incoming.collect { swatch ->
        println("from ${swatch.sender}: ${swatch.decodeToString()}")
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
val room: Room = factory.host(Pattern(sessionName = "alice", maxPeers = 4))
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
        sessionName = "alice",
    ),
)
```

The client and server arrive at the same membership view with no in-band
handshake because the advertisement carries the server's `PeerId`.

### Per-dial credentials

Suppose your server wants a fresh, single-use auth ticket on every connection.
You could bake one into the `url` above — but kuilt reconnects transparently
behind your back when a link drops, and it re-dials the *same* advertisement, so
that one-shot ticket is already spent by the first reconnect. What you want is a
way to mint a *new* ticket for each dial. That's a **weft**: a small function
kuilt calls right before every connection attempt, including every reconnect.

Pass it as the `weft` argument. On each dial, kuilt runs your function and folds
the result into the outgoing WebSocket request:

```kotlin
val client = KtorClientLoom(
    httpClient,
    weft = { WebSocketDialContext(queryParams = mapOf("ticket" to mintTicket())) },
)
```

`WebSocketDialContext` carries two maps: `queryParams`, appended to the dial URL
(percent-encoded), and `headers`, set on the upgrade request. Nothing is cached
— `mintTicket()` runs afresh for every connection, so a single-use credential
survives kuilt's reconnects. The value is generic: the same hook can carry a
trace id or a client-version header just as well as a ticket.

The WebRTC signaling channel (`kuilt-webrtc`) takes the same idea, but its weft
yields a plain `Map<String, String>` of query params only —
`WebSocketSignalingChannel(baseUrl, weft = { mapOf("ticket" to mintTicket()) })`.
Query params, not headers, because the browser's `WebSocket` constructor can't
set custom request headers at all — the URL is the only channel available, so
ticket-in-query is the honest ceiling there.

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

## Splitting one Seam into logical channels

`incoming` is single-collection per the kuilt contract — a second concurrent
collector races and is unsupported. When several independent consumers (e.g. a
`Quilter` and a Raft transport) must share one underlying `Seam`, use a channel
splitter to fan the flow out safely.

**`MuxSeam` — byte-tagged, up to 256 channels.** Each channel is a `Seam` view
prefixed with a 1-byte tag. `channel()` is idempotent and thread-safe.

```kotlin
import us.tractat.kuilt.core.MuxSeam

val mux = MuxSeam(seam, scope)
val raftSeam: Seam = mux.channel(0x00.toByte())
val crdtSeam: Seam = mux.channel(0x01.toByte())
```

**`NamedMux` — string-keyed, unbounded namespace.** Frames carry a UTF-8 name
prefix (1–255 bytes). Use it when the 256-slot ceiling of a byte tag is too
small — for example, for open-ended application channel names. `NamedMux` and
`MuxSeam` compose by nesting: assign one `MuxSeam` byte-tag to the `NamedMux`
subtree so only that subtree pays the wider header.

```kotlin
import us.tractat.kuilt.core.NamedMux

// Nest NamedMux under MuxSeam byte-tag 0x03.
val mux = MuxSeam(seam, scope)
val named = NamedMux(mux.channel(0x03.toByte()), scope)

val chatSeam: Seam = named.channel("chat")
val cursorSeam: Seam = named.channel("cursors")
```

Both splitters use `replay = 0` — frames emitted before a channel view starts
collecting are not replayed. They are suitable for `Quilter`-grade consumers
(which heal gaps via FullState + resend) but require application-level
reliability for raw at-least-once consumers.

## Writing your own fabric

Implement `Loom` (and a private `Seam`), then **prove it conforms** by
subclassing `SeamConformanceSuite`:

```kotlin
class MyFabricLoom : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam = when (rendezvous) {
        is Rendezvous.New -> TODO("host")
        is Rendezvous.Existing -> TODO("join")
    }
    // Override capability(), never availability() — the latter is derived from it.
    // The default is a roleless Unknown, so a fabric that has checked nothing says
    // so rather than claiming Available on the surface an app turns into guidance.
    override fun capability(): TransportCapability = TransportCapability(
        roles = setOf(TransportRole.Data),
        availability =
            if (myRadioPresent()) FabricAvailability.Available
            else FabricAvailability.Unavailable("my radio is off"),
    )
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
own `Tag` carrying whatever its `join` needs (`sessionName` + a stable `peerKey`
are the only required fields).

## Live-converging state (`kuilt-crdt`)

`kuilt-crdt` provides the delta-state CRDT zoo and `Quilter`, which
propagates any `Quilted<S>` over a `Seam` with no application-level merge
calls. For collaborative JSON documents, use `JsonCrdt`:

```kotlin
// set/remove return a Patch carrying the one key they touched — that is what goes
// on the wire, whatever else the document holds.
val title = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(replica, JsonValue.Str("Hello")))
quilter.mutate { it.set("title", title) }

// Outside a replicator, absorb the patch to hold the resulting document:
val doc = JsonCrdt.empty(ReplicaId("my-peer")).piece { it.set("title", title) }

// After deserialization, restore the replica id before mutating:
val received: JsonCrdt = cbor.decodeFromByteArray(JsonCrdt.serializer(), bytes)
    .withReplica(ReplicaId("my-peer"))
```

See the `JsonCrdt` and `JsonNode` KDoc for conflict-resolution semantics and
the cross-type precedence rule (`Object > Array > Leaf`). A write nested inside an
existing object still rebuilds that object, so its frame is the size of the
subtree — [#2469](https://github.com/tractat-us/kuilt/issues/2469) tracks a
path-addressed edit.

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

For **turn-based games**, prefer the `kuilt-game` facade
(`gameNode` / `gameHost` / `gameJoin`): it returns a `GameSession` that hides the
cluster / transport / storage wiring shown below and layers a typed
`TurnSequencer` on top. Reach for the raw `kuilt-raft` API directly for non-game
coordination — distributed locks, durable workflow steps — or when you need to
drive `RaftNode` internals yourself.

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
`NodeId(serverPeerId.value)`. The server carries the true voter as the
`RaftRelay.origin` on every relayed frame (never re-stamped with the relaying
server's fabric sender); the client's player relay transport maps that `origin`
back to a `NodeId` for Raft routing and credit. Mismatched IDs cause silently
dropped AppendEntries.

### Client side

The `CoroutineScope.clusterClient()` extension is the whole client: it joins the
server relay room, wires the **player relay transport** (the client speaks the
same `RaftRelay` dialect as the server both ways, so a plain `SeamRaftTransport`
no longer interoperates — every Raft send is wrapped as a `RaftRelay(dest =
leader)` addressed to the single relay server, and a down-frame is accepted only
when its `RaftRelay.origin` is a current voter, read live per frame), runs an
automatic reconnect loop over a swappable `ManagedSeam`, and returns a
`ClusterClient`. The one alignment rule: construct the client's loom with its
`selfPeerId` pinned to the client `NodeId`, so the wire identity the server
admits equals the `NodeId` Raft routes and credits on.

```kotlin
val clientScope = CoroutineScope(coroutineContext + Job())
val clientNodeId = NodeId("my-client")

// Pin the loom's selfPeerId to the client NodeId: wire identity == Raft identity.
val clientLoom = KtorClientLoom(
    httpClient = createClient { install(WebSockets) },
    selfPeerId = PeerId(clientNodeId.value),
)

val client: ClusterClient = clientScope.clusterClient(
    loom = clientLoom,
    clusterEndpoints = ClusterEndpoints(
        listOf(
            WebSocketAdvertisement(
                url = "ws://your-server-host/ws/cluster",
                serverPeerId = PeerId("server-1"),
                sessionName = "my-client",
            ),
        ),
    ),
    clientNodeId = clientNodeId,
    clusterConfig = ClusterConfig(
        voters = setOf(NodeId("server-1")),
        learners = setOf(clientNodeId),
    ),
    raftConfig = RaftConfig(/* … */),
    clock = { Clock.System.now() },
)

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

## On iPhone and Mac: keeping the first error log cheap

The first time your app logs an error with a stack trace attached, something has
to turn a list of raw memory addresses into function names and line numbers you
can read. On Apple platforms the default way of doing that asks a shared system
service, off in another process. When the machine is quiet the answer comes back
in a few hundredths of a second. When the machine is busy, that service is
competing for the same CPU as everything else, and the answer can take **seconds**.

It happens once per app launch — every later stack trace is fast either way. But
"the machine is busy and the network is misbehaving" is one situation, not two,
so the once is reasonably likely to land at the worst moment.

There is a one-line build setting that removes the variability, and it costs you
nothing: your traces still have function names and `file:line`, they are just
resolved inside your own process instead of by asking a neighbour.

```kotlin
// The APP's build.gradle.kts. This configures a final binary, so it belongs in
// the application module — a library like kuilt ships klibs and cannot set it
// for you.
kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.all {
            binaryOptions["sourceInfoType"] = "libbacktrace"
        }
    }
}
```

### What was measured

`coresymbolication` is the Apple-target default and does the cross-process call;
`libbacktrace` resolves in-process. Cost of the **first** stack trace materialized
in a process — `Throwable.stackTrace`, `stackTraceToString()`, or any
`logger.warn(e) { … }` that gets rendered:

| `sourceInfoType` | first trace, idle box | first trace, saturated box |
|---|---|---|
| `coresymbolication` (default) | ~65 ms (~356 ms cold) | **~6.0–6.5 s** |
| `libbacktrace` | ~7 ms | **~7 ms — flat** |

Every subsequent trace is ~2–3 ms either way, and a `logger.warn { … }` with no
throwable never resolves anything at all (~2.5 µs). The ~100× gap under load is
the whole effect: idle, this is not worth thinking about.

### What has *not* been confirmed

This is a measured effect with a known lever, **not a confirmed shipped defect** —
be sceptical of it in the following ways:

- The numbers are from a **debug `.kexe` on a development Mac** (macosArm64,
  16-core). A release build for a real device may behave differently.
- The "saturated" column used **synthetic CPU saturation** (`yes > /dev/null` ×32).
  Whether a real iOS device under realistic load starves the same system service
  comparably is **unverified**.
- kuilt ships **klibs**, so nothing here is a property of kuilt's artifacts — the
  consuming application picks `sourceInfoType` for the binary it builds, and this
  section is telling you the choice exists.

kuilt sets `libbacktrace` for its **own test binaries** (see
[`build-logic/src/main/kotlin/kuilt.kmp-library.gradle.kts`](../build-logic/src/main/kotlin/kuilt.kmp-library.gradle.kts)),
where the same one-time cost was landing inside whichever test happened to log a
throwable first and reading as a load-dependent flake.

### Why kuilt does not just stop logging traces

The obvious alternative is for kuilt to stop attaching throwables to its own log
lines. It is the wrong trade, because the cost is **one-time per process**: any
one of the ~40 throwable-attached log sites in the library could be the one that
pays it, so removing the exposure would mean converting essentially *all* of them.
That trades away stack traces everywhere, permanently, to avoid a single
tens-of-milliseconds cost — and it only helps a consumer who has not already set
`libbacktrace`, which is flat under load anyway.

So kuilt drops the trace only where the failure is routine and the exception's
type and message say everything the trace would (an unreachable telemetry
collector; a log entry that was never meant to decode). Everywhere the failure is
genuinely unexpected, the trace stays.

## For coding agents

If you develop against kuilt with a coding agent (Claude Code, Cursor, …), install
the `kuilt-primitives` skill so the agent reaches for existing primitives instead of
reinventing them. One step — copy the skill folder into your repo:

    cp -r <kuilt>/.claude/skills/kuilt-primitives .claude/skills/

(When kuilt is checked out side-by-side via `includeBuild("../kuilt")`, `<kuilt>` is
`../kuilt`.) The skill routes to `docs/agent-cookbook.md`, a symptom→primitive lookup
for reconnect, replicated state, liveness, consensus, and dedup.
