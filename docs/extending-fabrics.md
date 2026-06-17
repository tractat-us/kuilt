# Implementing a custom RPC fabric

This tutorial walks you through wiring any existing RPC transport —
message-based or stream-based — into a kuilt `Seam` in roughly 30 lines of
transport-specific code. Everything above the transport layer (Raft, CRDTs,
session, dealing) just works on the result.

Two tracks are presented side by side:

| | Track A — message RPC | Track B — stream RPC (TCP headline) |
|---|---|---|
| Transport surface | already delivers whole messages | exposes a byte stream (`Source`/`Sink`) |
| Framing | none required | `framed()` — 4-byte length prefix |
| Pump needed? | **No** | **No** — `handshaking()`/`meshSeam()` wrap the cold connection internally |
| Reference | *(hypothetical in-process fabric)* | `:kuilt-tcp` — cite every line |

## The SPI: `Connection`

`Connection` is the only interface a transport must implement. It models a
point-to-point, message-framed, full-duplex link between exactly two peers.

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Connection.kt:16-25
public interface Connection {
    /** Send one whole message. Suspends until the transport accepts it. */
    public suspend fun send(frame: ByteArray)

    /** Whole messages received from the peer, in order. Single-collection. */
    public val incoming: Flow<ByteArray>

    /** Close the link. Idempotent. Completes [incoming]. */
    public suspend fun close()
}
```

Stream transports (TCP, pipes, gRPC bidi stream) do not implement `Connection`
directly — they go through `framed()` first.

## Track A — message RPC fabric

Use this track when your transport already delivers whole messages: WebSocket,
gRPC unary-over-bidi, Multipeer, Nearby, an in-process channel, …

### Step 1 — implement `Connection`

Wrap your transport's send/receive in three methods:

```kotlin
class MyRpcConnection(private val rpc: YourRpcSession) : Connection {
    override suspend fun send(frame: ByteArray) = rpc.send(frame)
    override val incoming: Flow<ByteArray> = rpc.frames()   // already hot
    override suspend fun close() = rpc.close()
}
```

The `incoming` flow from a message-based RPC is already hot (the transport
delivers frames in the background; you just consume them). No pump needed.

### Step 2 — choose an identity strategy

**Out-of-band identity** — if you know both peer IDs before the connection
(e.g. from an authentication layer), call `identified()` directly:

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/LinkSeam.kt:46-51
public fun identified(
    connection: Connection,
    selfId: PeerId,
    remoteId: PeerId,
    dispatcher: CoroutineContext,
): Seam = LinkSeam(connection, selfId, remoteId, dispatcher)
```

**In-band identity** — if peers must negotiate IDs over the wire, use
`handshaking()`. It sends a `Hello` preamble as the first frame and reads
the peer's preamble:

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Handshaking.kt:30-38
public suspend fun handshaking(
    connection: Connection,
    selfId: PeerId,
    dispatcher: CoroutineContext,
): Seam {
    connection.send(Hello.encode(selfId))
    val remoteId = Hello.decode(connection.firstFrame())
    return identified(PreambleStrippedConnection(connection), selfId, remoteId, dispatcher)
}
```

`Hello` encodes a `PeerId` as its UTF-8 bytes — one frame, one round-trip:

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Hello.kt:6-9
public object Hello {
    public fun encode(selfId: PeerId): ByteArray = selfId.value.encodeToByteArray()
    public fun decode(frame: ByteArray): PeerId = PeerId(frame.decodeToString())
}
```

### Step 3 — write a `Loom`

A `Loom` has one abstract method: `weave(Rendezvous): Seam`. Implement it to
dial or accept depending on the rendezvous variant:

```kotlin
class MyRpcLoom(private val selfId: PeerId, private val dispatcher: CoroutineContext) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val session = when (rendezvous) {
            is Rendezvous.New      -> MyRpcServer.accept()       // host role
            is Rendezvous.Existing -> MyRpcClient.connect(rendezvous.tag as MyRpcAddress)
        }
        // Connection is already hot — go straight to handshaking.
        return handshaking(MyRpcConnection(session), selfId, dispatcher)
    }
}
```

The `dispatcher` is **required** (not optional). See [Dispatcher](#dispatcher-is-a-required-parameter).

---

## Track B — stream RPC fabric (TCP headline)

Use this track when your transport exposes a byte stream — TCP, Unix sockets,
a gRPC raw stream, a pipe pair. The whole adapter is ~30 lines of
transport-specific code split across three focused types in `:kuilt-tcp`.

### Step 1 — `framed()`: length-prefix framing

A byte stream has no message boundaries. `framed()` from `:kuilt-stream`
adds a 4-byte big-endian length prefix and reassembles frames at the other
end:

```kotlin
// kuilt-stream/src/commonMain/kotlin/us/tractat/kuilt/stream/Framed.kt:37-41
public fun framed(
    source: Source,
    sink: Sink,
    maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE,
): Connection = FramedConnection(source, sink, maxFrameSize)
```

`Source` and `Sink` are from `kotlinx-io`. For a JVM socket they come from
the Ktor IO adapters or the plain `InputStream`/`OutputStream` adapters.

### Step 2 — the cold-`Connection` single-collection model (read this)

`framed()` returns a *cold* `Connection`: its `incoming` flow drives blocking reads
from the underlying source and **must be collected exactly once**. But
`handshaking()` (and `meshSeam()`) reads `incoming` *twice* in sequence:

1. `connection.firstFrame()` reads the identity preamble (one collection).
2. the inner seam's read loop installs a second collection.

Earlier versions of this kit asked every stream fabric to pump the cold flow
itself before handing it on. That step is now **gone** — `handshaking()` and
`meshSeam()` wrap the connection internally with a private `singleCollection` adapter:
one pump coroutine collects `incoming` exactly once and re-publishes frames
through an unbounded `Channel`, so the preamble read and the read loop draw from
that single re-collectable stream. The transport just hands its raw `framed()`
`Connection` straight in.

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Handshaking.kt:28
public suspend fun handshaking(
    connection: Connection,            // a cold framed() Connection is fine — wrapped internally
    selfId: PeerId,
    dispatcher: CoroutineContext,
): Seam
```

The rule: **a stream fabric hands `framed()` directly to `handshaking()`/
`meshSeam()` — no pump of its own.** Message-based fabrics (Track A) already
deliver frames through a hot channel; they too pass straight in. The internal
wrap also keeps blocking reads off the seam's scheduling dispatcher when the
transport pins `incoming` to an IO dispatcher (see `tcpConnection`'s `flowOn` below),
so the seam's state loop is never stalled by a slow read.

### Step 3 — `TcpConnection`: the full adapter in ~11 lines

`:kuilt-tcp`'s `tcpConnection` shows the complete pipeline for a Ktor TCP socket:

```kotlin
// kuilt-tcp/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/tcp/TcpConnection.kt:28-40
internal fun tcpConnection(socket: Socket, ioDispatcher: CoroutineDispatcher): Connection {
    val source = socket.openReadChannel().toInputStream().asSource().buffered()
    val sink = socket.openWriteChannel(autoFlush = true).toOutputStream().asSink().buffered()
    val framed = framed(source, sink)
    return object : Connection {
        override suspend fun send(frame: ByteArray) = framed.send(frame)
        override val incoming: Flow<ByteArray> = framed.incoming.flowOn(ioDispatcher)
        override suspend fun close() {
            framed.close()
            socket.close()
        }
    }
}
```

Bridge Ktor channels to `Source`/`Sink`, add length-prefix framing, and pin the
blocking pull-reads to `ioDispatcher` with `flowOn`. The cold, single-collection
`Connection` goes straight to `handshaking()` — no pump. The `close()` adds socket
teardown.

For the proprietary-socket case — a plain `java.net.Socket` with no Ktor —
the bridge is even simpler, using the `kotlinx-io` JVM adapters directly:

```kotlin
// kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/ProprietaryRpcExampleTest.kt:49-53
private fun rpcConnection(socket: Socket): Connection =
    framed(
        source = socket.getInputStream().asSource().buffered(),
        sink = socket.getOutputStream().asSink().buffered(),
    )
```

`framed()` straight to `handshaking()` — the proprietary and Ktor adapters are
structurally identical. Swap in whatever `InputStream`/`OutputStream` your RPC
exposes.

### Step 4 — `TcpAddress`: the discovery handle (~7 lines)

The `Tag` a joiner passes to `weave(Rendezvous.Existing(tag))` is just a data
class in `commonMain`:

```kotlin
// kuilt-tcp/src/commonMain/kotlin/us/tractat/kuilt/tcp/TcpAddress.kt:11-17
public data class TcpAddress(
    val host: String,
    val port: Int,
    override val displayName: String = "$host:$port",
) : Tag {
    override val peerKey: String get() = "$host:$port"
}
```

Your own fabric can inline a similar type or reuse an existing address class.

### Step 5 — `TcpLoom.weave`: the dial/accept loop (~10 lines)

```kotlin
// kuilt-tcp/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/tcp/TcpLoom.kt:45
override suspend fun weave(rendezvous: Rendezvous): Seam {
    // Real-IO seam: refuse to build under a virtual TestDispatcher (would deadlock silently).
    checkNotUnderTestDispatcher(
        scope = CoroutineScope(seamDispatcher),
        typeName = "TcpLoom",
        substitute = "an in-memory connectionPair()/identified() seam",
        strict = true,
        expectVirtualTime = false,
    )
    val socket = when (rendezvous) {
        is Rendezvous.New -> requireNotNull(serverSocket) {
            "TcpLoom.host requires a bound ServerSocket"
        }.accept()
        is Rendezvous.Existing -> {
            val address = rendezvous.tag
            require(address is TcpAddress) { "TcpLoom only joins a TcpAddress, got ${address::class}" }
            aSocket(selector).tcp().connect(address.host, address.port)
        }
    }
    return handshaking(tcpConnection(socket, ioDispatcher), selfId, seamDispatcher)
}
```

Guard against virtual time, dial-or-accept, build the `Connection`, hand to
`handshaking`. That is the complete transport-specific code.

---

## Gotchas

### 1. The cold-`Connection` double-collection trap (handled for you)

Covered in Step 2 above. `handshaking()` (and `meshSeam()`) collects `incoming`
twice — preamble read, then the inner read loop. A `framed()` `Connection` is cold and
single-collection, so a *direct* double-collect would hang.

You no longer guard against this yourself: `handshaking()`/`meshSeam()` wrap the
connection with an internal `singleCollection` adapter, so handing your raw `framed()`
`Connection` straight in is correct. A message-based (hot-channel) `Connection` is also safe
to pass directly.

### 2. Framing — stream fabrics only

`framed()` handles the 4-byte big-endian length prefix and oversize
protection (`FrameTooLargeException`). You do not write framing yourself.
Message-based transports skip `framed()` entirely — their link is already
frame-delimited.

### 3. Identity — in-band vs. out-of-band

- **`handshaking(connection, selfId, dispatcher)`** — negotiate over the wire. Sends
  a `Hello` preamble as the first frame; suspends until the peer's preamble
  arrives. Use when peer IDs are not known before the connection.
- **`identified(connection, selfId, remoteId, dispatcher)`** — skip the handshake.
  Both identities are known (e.g. from TLS certificates, a session token, a
  shared discovery layer). The resulting `LinkSeam` is immediately usable.

### 4. Lifecycle — map your transport to `Woven`/`Torn`

`weave()` returns a `Seam` in state `SeamState.Woven`. Your `Connection.close()`
must be idempotent and must complete `incoming` on call. On transport EOF or
error, `LinkSeam`'s internal read loop catches the exception and calls
`tearDown()`, which transitions the seam to `SeamState.Torn` and closes the
`incoming` channel. You do not implement `Torn` yourself — just map transport
close/error to a completed `incoming` flow.

### 5. Dispatcher is a required parameter

`identified()` and `handshaking()` both take a `dispatcher: CoroutineContext`.
This is **required** — never optional, never defaulted by the kit.

For real-network IO (TCP, WebSocket), pass real production dispatchers.
`:kuilt-tcp` uses two:

```kotlin
// kuilt-tcp/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/tcp/TcpLoom.kt:79-80
seamDispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
```

- `seamDispatcher` — the *scheduler* for the seam's read/write coroutines, not a
  mutex. `LinkSeam` thread-safety is enforced by atomics/locks, so the dispatcher
  only orders the seam's own coroutines.
- `ioDispatcher` — runs the blocking socket reads. `tcpConnection` pins `incoming` to it
  with `flowOn` (and the internal `singleCollection` pump inherits it). Real
  sockets cannot run on a virtual test clock; blocking reads belong here.

Test callers inject a `TestCoroutineScheduler`-derived dispatcher so the
seam's read/write loops share the same virtual clock as `withTimeout` in the
test body. (The real-IO `TcpLoom.weave` rejects a virtual `TestDispatcher`
outright — drive virtual-time tests with an in-memory `connectionPair()`-backed seam
instead.)

---

## Prove it: `SeamConformanceSuite`

Every new fabric passes the contract by subclassing `SeamConformanceSuite` and
implementing `newLoomPair()`. The suite encodes all `Seam` invariants as tests
(open, send, receive, peer membership, single-collection ordering, close
idempotency, availability).

`:kuilt-tcp`'s conformance test is the copy-paste template:

```kotlin
// kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/TcpConformanceTest.kt:32-58
class TcpConformanceTest : SeamConformanceSuite() {

    @Suppress("ForbiddenMethodCall")
    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket
    private var port: Int = 0

    @BeforeTest
    fun setUp() = runBlocking {
        port = JvmServerSocket(0).use { it.localPort }
        serverSocket = aSocket(selector).tcp().bind("127.0.0.1", port)
    }

    @AfterTest
    fun tearDown() {
        serverSocket.close()
        selector.close()
    }

    override fun newLoomPair(): Pair<Loom, Loom> {
        val hostLoom = TcpLoom.host(serverSocket, PeerId("tcp-host"), selector)
        val joinerLoom = TcpLoom.join(PeerId("tcp-joiner"), selector)
        return hostLoom to joinerLoom
    }

    override fun joinTag(): Tag = TcpAddress(host = "127.0.0.1", port = port)
}
```

Key points:

- **Real IO, real dispatchers.** A real-network fabric cannot use virtual time —
  `runTest` and `StandardTestDispatcher` are off the table. The suite runs over a
  real loopback socket with `runBlocking`. This matches `:kuilt-websocket`'s
  conformance harness.
- **`@Suppress("ForbiddenImport")` / `@Suppress("ForbiddenMethodCall")`** are
  required at the file and call sites — the repo bans production dispatchers in
  test sources to catch accidental real-clock leaks; a deliberate real-network
  harness suppresses intentionally with a one-line reason comment.
- **`newLoomPair()` returns distinct host and joiner looms** wired to reach each
  other over the shared `serverSocket`. The suite drives `host()` and `join()`
  concurrently; the host's `accept()`-then-handshake suspends until the joiner
  connects, satisfying the suite's concurrent weave contract.

---

## Running the proprietary-socket example end to end

The `ProprietaryRpcExampleTest` in `:kuilt-tcp` is the end-to-end sanity check
for non-Ktor consumers. It uses a plain `java.net.Socket` — no Ktor required —
and runs under `runBlocking`:

```kotlin
// kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/ProprietaryRpcExampleTest.kt:59-84
@Test
fun aProprietarySocketRpcBecomesAKuiltSeam() = runBlocking {
    withContext(Dispatchers.IO) {
        ServerSocket(0).use { server ->
            withTimeout(10.seconds) {
                coroutineScope {
                    val hostDeferred = async { weaveSeam(server.accept(), PeerId("rpc-host")) }
                    val client = weaveSeam(
                        Socket("127.0.0.1", server.localPort),
                        PeerId("rpc-client"),
                    )
                    val host = hostDeferred.await()

                    val clientReceives = async { client.incoming.first() }
                    host.broadcast("hello from the in-house RPC".encodeToByteArray())

                    assertEquals(
                        "hello from the in-house RPC",
                        clientReceives.await().payload.decodeToString(),
                    )
                }
            }
        }
    }
}
```

The `weaveSeam` helper is the complete transport bridge from Step 3 and Step 4
of Track B — `framed()` + `handshaking()` (which wraps the cold connection internally) —
all in:

```kotlin
// kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/ProprietaryRpcExampleTest.kt:55-56
private suspend fun weaveSeam(socket: Socket, selfId: PeerId): Seam =
    handshaking(rpcConnection(socket), selfId, Dispatchers.IO)
```

---

## N-peer cluster: `meshSeam()`

For the N-peer case — peers connected directly to each other — `meshSeam()`
aggregates a list of point-to-point `Connection`s into a single fully-connected
`Mesh` (a `Seam` that also exposes `addLink`). It exchanges a `MeshHello`
preamble on each link concurrently, deduplicates symmetric simultaneous-dial
races (the link with the lexicographically smallest *link nonce* survives, so
both ends agree on the survivor with no coordination), handles per-link failures
gracefully (the `Seam` stays `Woven` when a single link drops), and guards all
mutable mesh state with a lock — the injected `dispatcher` schedules the per-link
read loops, it is not a mutex:

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt:126
public suspend fun meshSeam(
    selfId: PeerId,
    connections: List<Connection>,
    dispatcher: CoroutineContext,
    random: Random = Random.Default,
): Mesh
```

Each `Connection` is wrapped with `singleCollection` before the preamble read, so the
preamble and the read loop share ONE collection of `incoming` — the cold,
single-collection `Connection` a stream fabric's `framed()` produces works directly,
no hot-reader pump needed. Pass one `Connection` per peer; `meshSeam()` handles the
rest. A late joiner that dials in after construction is admitted with
`Mesh.addLink(connection)`
(`kuilt-core/.../MeshSeam.kt:49`), which runs the same preamble exchange and
dedup, then launches the new link's read loop.

### A real TCP cluster, end-to-end

`TcpClusterExampleTest` stands up a three-peer cluster over real loopback
sockets, proving the whole path — mesh handshake, dynamic join, broadcast, and
per-sender attribution:

- It opens one real loopback connection per spoke and adapts each end to a `Connection`
  via `tcpConnection`
  (`kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/TcpClusterExampleTest.kt:97`,
  `tcpConnectionPair`).
- A hub peer A weaves `meshSeam(a, listOf(connectionToB), Dispatchers.IO)`; B weaves its
  own single-link `meshSeam` to A. Both run concurrently so the preambles cross.
- A late joiner C dials in and A admits it with `hub.addLink(connectionToC)`
  (`TcpClusterExampleTest.kt:65`); C weaves its own `meshSeam` to A.
- A `broadcast` from the hub reaches both B and C, each attributing the frame to
  A
  (`TcpClusterExampleTest.kt:44`,
  `threePeerTcpClusterFormsViaMeshSeamAndAddLink`).

Because sockets cannot be driven by a virtual clock, the example is a real-IO
test (`runBlocking`, real dispatchers) with a `withTimeout` guard — the same
shape as `TcpConformanceTest` and `ProprietaryRpcExampleTest`.

---

## Summary

The transport-specific code for a stream fabric totals ~30 lines across three
focused files:

| File | Lines | What it does |
|------|-------|--------------|
| `TcpConnection.kt` | ~11 | Bridge socket channels → `Source`/`Sink` → `framed()` |
| `TcpAddress.kt` | ~7 | Discovery handle (`Tag` implementation) |
| `TcpLoom.weave` | ~10 | Dial or accept, build `Connection`, call `handshaking()` |

Everything else is `kuilt-core` and `:kuilt-stream` — plug in your transport's
connect/accept and you inherit a `Seam` that Raft, CRDTs, fair dealing, and
sessions all run on unchanged.

See also: [Architecture](architecture.md) for the full `Connection`↔`Seam` type
table and the contract's load-bearing invariants.
