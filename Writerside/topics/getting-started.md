# Getting started

Connect peers, share data, then add strict ordering only where needed. This page walks through that in four steps, adding one kuilt module at a time without changing your app code.

## Step 1: Two peers over WebSocket

**Why this step:** validate send/receive first, before layering shared state or ordering.

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

Both peers now use the same `Seam` API. This is your transport base; the rest
of this page layers shared data and ordering on top.

```kotlin
// Same API on every peer:
scope.launch { seam.incoming.collect { println(it.decodeToString()) } }
seam.broadcast("hello".encodeToByteArray())
seam.close()
```

---

## Step 2: Add a chat (replication)

**Why this step:** transport moves bytes; replication keeps shared state consistent across peers.

Now make shared state converge. For chat, that means everyone sees the same message list in the same order, even with concurrent sends. Add `kuilt-crdt` and use `Rga` — a replicated list where concurrent insertions are ordered deterministically:

```kotlin
// build.gradle.kts
implementation("us.tractat.kuilt:kuilt-crdt")
```

```kotlin
```
{ src="../../kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt" include-symbol="sampleRgaChatReplicator" }

→ [Replication](crdt-overview.md)

---

## Step 3: Add tic-tac-toe (consensus and leadership)

**Why this step:** some decisions can't be merged — they need one agreed order.

Tic-tac-toe moves are an ordered log: both players must agree on exactly who moved where, in order. That's not mergeable — it requires consensus. Add `kuilt-raft` and `kuilt-game`:

```kotlin
// build.gradle.kts
implementation("us.tractat.kuilt:kuilt-raft")
implementation("us.tractat.kuilt:kuilt-game")
```

```kotlin
```
{ src="../../kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt" include-symbol="sampleTurnSequencer" }

→ [Consensus and leader election](raft.md)

---

## Step 4: Run on more platforms

**Why this step:** portability is a `Loom` swap — your app logic stays unchanged.

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

→ [Connections](fabrics.md)

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

`InMemoryLoom` is a conforming fabric, so tests written against it can run
unchanged with a real loom.
