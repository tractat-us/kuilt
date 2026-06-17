# Getting started

Most network features start the same way: connect peers, share data, then add
ordering only where strict agreement matters. This page walks that path in four
steps, each adding one kuilt module without changing your application shape.

## Step 1: Two peers over WebSocket

Start with the smallest useful milestone: two peers exchanging frames over one
`Seam`.

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

Both peers now hold an identical `Seam`. This is your base transport surface;
the rest of this page layers data and ordering on top.

```kotlin
// Same API on every peer:
scope.launch { seam.incoming.collect { println(it.payload.decodeToString()) } }
seam.broadcast("hello".encodeToByteArray())
seam.close()
```

---

## Step 2: Add a chat (replicated data)

Now make the shared state itself converge. For chat, that means everyone sees
the same message list in the same order, even with concurrent sends. Add
`kuilt-crdt` and use `Rga` (RGA — Replicated Growable Array):

```kotlin
// build.gradle.kts
implementation("us.tractat.kuilt:kuilt-crdt")
```

```kotlin
val replica = ReplicaId("alice")
val msgSerializer = QuiltMessage.serializer(Rga.wireSerializer(String.serializer()))
val replicator = Quilter(replica, seam, Rga.empty<String>(), msgSerializer, scope)

// Send a message — appended to the shared list, propagated to all peers
val current = replicator.state.value
val (_, op) = current.insertAt(replica, current.size, "hello from alice")
replicator.apply(Patch(Rga.empty<String>().apply(op)))

// Render the live chat log
replicator.state.collect { messages -> chatView.items = messages.toList() }
```

→ [Replicated data structures](crdt-overview.md)

---

## Step 3: Add tic-tac-toe (consensus and leadership)

Some state needs stronger guarantees than mergeable data. Tic-tac-toe moves are
an ordered log: both players must agree on exactly who moved where, in order,
with no disputes. Add `kuilt-raft` and `kuilt-game`:

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

→ [Contract](contract.md)

---

## Step 4: Run on more platforms

Your chat/game logic above only depends on `seam.broadcast`, `seam.incoming`,
and `seam.peers`. Swap the `Loom` that produced the `Seam` and keep the rest of
the code unchanged:

```kotlin
val loom: Loom = when {
    isApple   -> MultipeerPeerLinkFactory(displayName = "alice", serviceType = "com.example.app")
    isAndroid -> NearbyLoom(api = GmsNearbyApi(context), serviceId = "com.example.app")
    isBrowser -> WebRTCPeerLinkFactory(signaling = WebSocketSignalingChannel(wsUrl), room = "myroom")
    else      -> KtorClientLoom(httpClient)
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
