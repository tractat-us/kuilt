# Getting started

Connect two peers, then build a chat and a game on top — each one adding a
single kuilt module to the same foundation.

## Step 1: Two peers over WebSocket

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("us.tractat.kuilt:kuilt-bom:VERSION"))
    implementation("us.tractat.kuilt:kuilt-websocket")
}
```

**Server (JVM/Android)**

```kotlin
embeddedServer(Netty, port = 8080) {
    install(WebSockets)
    val server = KtorServerLoom(application, path = "/live", selfPeerId = PeerId("server"))
    scope.launch {
        while (isActive) {
            val seam: Seam = server.nextLink()   // suspends until a client connects
            scope.launch { handle(seam) }
        }
    }
}.start(wait = true)
```

**Client (any platform)**

```kotlin
val seam: Seam = KtorClientLoom(HttpClient { install(WebSockets) }).join(
    WebSocketAdvertisement("ws://localhost:8080/live", PeerId("server"), displayName = "alice")
)
```

Both peers now hold an identical `Seam`. The rest of this page builds on it.

```kotlin
// Same API on every peer:
scope.launch { seam.incoming.collect { println(it.payload.decodeToString()) } }
seam.broadcast("hello".encodeToByteArray())
seam.close()
```

---

## Step 2: Add a chat (replicated data)

Chat messages need to arrive in the same order on every device. Add `kuilt-crdt`
and use `Rga` — a replicated ordered list where inserts from any peer land
in a stable, consistent position:

```kotlin
// build.gradle.kts
implementation("us.tractat.kuilt:kuilt-crdt")
```

```kotlin
val replica = ReplicaId("alice")
val replicator = SeamReplicator(
    replica = replica,
    seam = seam,
    initial = Rga.empty<String>(),
    messageSerializer = ReplicatorMessage.serializer(Rga.serializer(String.serializer())),
    scope = scope,
)

// Send a message — appended to the shared list, propagated to all peers
val current = replicator.state.value
val (_, op) = current.insertAfter(current.tail, replica, "hello from alice")
replicator.apply(Patch(Rga.empty<String>().apply(op)))

// Render the live chat log
replicator.state.collect { messages -> chatView.items = messages.toList() }
```

**Without kuilt networking.** `Rga` is a plain value object — you can use it
without a `Seam` at all. Serialize it, send it over HTTP or a message queue,
and call `piece` to merge on the other side:

```kotlin
var log = Rga.empty<String>()
val (next, op) = log.insertAfter(log.tail, replica, "hello")
log = log.apply(op)

// Received from another peer via any transport:
val remote: Rga<String> = cbor.decodeFromByteArray(Rga.serializer(String.serializer()), bytes)
log = log.piece(remote)   // always safe, always convergent
```

→ [Replicated data structures](crdt-overview.md)

---

## Step 3: Add tic-tac-toe (consensus and leadership)

Tic-tac-toe needs strict turn ordering: both players must agree on who moved
where, in what order, with no disputes. Add `kuilt-raft` and `kuilt-game`:

```kotlin
// build.gradle.kts
implementation("us.tractat.kuilt:kuilt-raft")
implementation("us.tractat.kuilt:kuilt-game")
```

```kotlin
@Serializable data class Move(val row: Int, val col: Int)

val node = scope.raftNode(
    ClusterConfig.ofVoters(listOf(NodeId("alice"), NodeId("bob"))),
    SeamRaftTransport(seam),
    InMemoryRaftStorage(),   // use a persistent impl in production
)
val game = TurnSequencer(node, Move.serializer())

// Both peers apply the same committed move sequence, in order
scope.launch { game.committed.collect { (_, move) -> board.apply(move) } }

// Active player proposes a move — suspends until both peers commit it
game.propose(Move(row = 1, col = 1))
```

**Without kuilt networking.** `RaftTransport` is an interface — you can
implement it over gRPC, a message queue, or any other messaging layer and use
`kuilt-raft` without any kuilt fabric module:

```kotlin
class MyGrpcRaftTransport : RaftTransport { ... }

val node = scope.raftNode(cluster, MyGrpcRaftTransport(), storage)
```

→ [Contract](contract.md)

---

## Step 4: Run on more platforms

The chat and game code above only use `seam.broadcast`, `seam.incoming`, and
`seam.peers`. Swap the `Loom` that produced the `Seam` and nothing else changes:

```kotlin
val loom: Loom = when {
    isApple   -> MultipeerLoom(...)          // iPhone/Mac — no server
    isAndroid -> NearbyLoom(...)             // Bluetooth/Wi-Fi Direct
    isBrowser -> WebRTCPeerLinkFactory(...)  // browser-to-browser
    else      -> KtorClientLoom(httpClient)  // WebSocket relay (default above)
}
val seam: Seam = loom.join(tag)
```

→ [Fabrics](fabrics.md)

---

## Testing

Replace any `Loom` with `InMemoryLoom` for fast, network-free tests:

```kotlin
@Test
fun `chat messages converge`() = runTest {
    val loom = InMemoryLoom()
    val alice = loom.host(Pattern("alice"))
    val bob   = loom.join(InMemoryTag("bob"))

    // wire up replicators, send a message, assert bob sees it...
}
```

`InMemoryLoom` is a fully conforming fabric — tests written against it work
unchanged with any real loom.
