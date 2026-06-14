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
| Pump needed? | **No** — already hot | **Yes** — cold flow; must be pumped first |
| Reference | *(hypothetical in-process fabric)* | `:kuilt-tcp` — cite every line |

## The SPI: `Conn`

`Conn` is the only interface a transport must implement. It models a
point-to-point, message-framed, full-duplex link between exactly two peers.

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Conn.kt:16-25
public interface Conn {
    /** Send one whole message. Suspends until the transport accepts it. */
    public suspend fun send(frame: ByteArray)

    /** Whole messages received from the peer, in order. Single-collection. */
    public val incoming: Flow<ByteArray>

    /** Close the link. Idempotent. Completes [incoming]. */
    public suspend fun close()
}
```

Stream transports (TCP, pipes, gRPC bidi stream) do not implement `Conn`
directly — they go through `framed()` first.

## Track A — message RPC fabric

Use this track when your transport already delivers whole messages: WebSocket,
gRPC unary-over-bidi, Multipeer, Nearby, an in-process channel, …

### Step 1 — implement `Conn`

Wrap your transport's send/receive in three methods:

```kotlin
class MyRpcConn(private val rpc: YourRpcSession) : Conn {
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
    conn: Conn,
    selfId: PeerId,
    remoteId: PeerId,
    dispatcher: CoroutineContext,
): Seam = LinkSeam(conn, selfId, remoteId, dispatcher)
```

**In-band identity** — if peers must negotiate IDs over the wire, use
`handshaking()`. It sends a `Hello` preamble as the first frame and reads
the peer's preamble:

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Handshaking.kt:30-38
public suspend fun handshaking(
    conn: Conn,
    selfId: PeerId,
    dispatcher: CoroutineContext,
): Seam {
    conn.send(Hello.encode(selfId))
    val remoteId = Hello.decode(conn.firstFrame())
    return identified(PreambleStrippedConn(conn), selfId, remoteId, dispatcher)
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
        // Conn is already hot — go straight to handshaking.
        return handshaking(MyRpcConn(session), selfId, dispatcher)
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
): Conn = FramedConn(source, sink, maxFrameSize)
```

`Source` and `Sink` are from `kotlinx-io`. For a JVM socket they come from
the Ktor IO adapters or the plain `InputStream`/`OutputStream` adapters.

### Step 2 — `pumped()`: the hot single-reader pump (CRITICAL)

**This is the most important step.** `framed()` returns a *cold* `Conn`:
its `incoming` flow drives blocking reads from the underlying source and
**must be collected exactly once**. But `handshaking()` collects `incoming`
*twice* in sequence:

1. `conn.firstFrame()` reads the identity preamble (one collection).
2. `identified()`'s internal read loop installs a second collection.

Feeding a raw `framed()` directly into `handshaking()` hangs on the second
collection. The fix is `pumped()` — a background coroutine that collects the
cold flow once on an IO dispatcher and re-publishes frames through an
unbounded `Channel` whose `receiveAsFlow()` is re-collectable:

```kotlin
// kuilt-tcp/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/tcp/PumpedConn.kt:29-58
internal fun Conn.pumped(ioDispatcher: CoroutineDispatcher): Conn = PumpedConn(this, ioDispatcher)

private class PumpedConn(
    private val delegate: Conn,
    ioDispatcher: CoroutineDispatcher,
) : Conn {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        scope.launch {
            runCatchingCancellable { delegate.incoming.collect { inbox.send(it) } }
            inbox.close()
        }
    }

    override suspend fun send(frame: ByteArray) = delegate.send(frame)
    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    override suspend fun close() {
        scope.cancel()
        runCatchingCancellable { delegate.close() }
    }
}
```

The rule: **`handshaking()` requires a hot, re-collectable `Conn`; cold flows
must be pumped first.** Every stream fabric needs this step. Message-based
fabrics (Track A) already deliver frames through a hot channel — their `Conn`
satisfies the contract without a pump.

This is the same pattern `WebSocketSeam` uses internally. The pump also keeps
blocking reads off the seam's confinement dispatcher: the IO-blocking work
stays on `ioDispatcher`, so the seam's state loop is never stalled by a slow
read.

### Step 3 — `TcpConn`: the full adapter in ~11 lines

`:kuilt-tcp`'s `tcpConn` shows the complete pipeline for a Ktor TCP socket:

```kotlin
// kuilt-tcp/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/tcp/TcpConn.kt:24-34
internal fun tcpConn(socket: Socket, ioDispatcher: CoroutineDispatcher): Conn {
    val source = socket.openReadChannel().toInputStream().asSource().buffered()
    val sink = socket.openWriteChannel(autoFlush = true).toOutputStream().asSink().buffered()
    val pumped = framed(source, sink).pumped(ioDispatcher)
    return object : Conn by pumped {
        override suspend fun close() {
            pumped.close()
            socket.close()
        }
    }
}
```

Three lines of pipeline: bridge Ktor channels to `Source`/`Sink`, add
length-prefix framing, pump to make it hot. The `close()` override adds
socket teardown on top of pump teardown.

For the proprietary-socket case — a plain `java.net.Socket` with no Ktor —
the bridge is even simpler, using the `kotlinx-io` JVM adapters directly:

```kotlin
// kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/ProprietaryRpcExampleTest.kt:48-52
private fun rpcConn(socket: Socket): Conn =
    framed(
        source = socket.getInputStream().asSource().buffered(),
        sink = socket.getOutputStream().asSink().buffered(),
    ).pumped(Dispatchers.IO)
```

`framed()` → `pumped()` — the proprietary and Ktor adapters are structurally
identical. Swap in whatever `InputStream`/`OutputStream` your RPC exposes.

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
// kuilt-tcp/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/tcp/TcpLoom.kt:36-48
override suspend fun weave(rendezvous: Rendezvous): Seam {
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
    return handshaking(tcpConn(socket, ioDispatcher), selfId, seamDispatcher)
}
```

Dial-or-accept, build the `Conn`, hand to `handshaking`. That is the complete
transport-specific code.

---

## Gotchas

### 1. The cold-`Conn` double-collection trap (stream fabrics only)

Already covered in Step 2 above — it's the one non-obvious step that every
stream fabric builder must get right.

**The rule again:** `handshaking()` collects `incoming` twice.
A `framed()` `Conn` is cold and single-collection — it hangs on the second
collection. A message-based (hot-channel) `Conn` is already safe.
The fix for stream fabrics: `framed(...).pumped(ioDispatcher)`.

### 2. Framing — stream fabrics only

`framed()` handles the 4-byte big-endian length prefix and oversize
protection (`FrameTooLargeException`). You do not write framing yourself.
Message-based transports skip `framed()` entirely — their link is already
frame-delimited.

### 3. Identity — in-band vs. out-of-band

- **`handshaking(conn, selfId, dispatcher)`** — negotiate over the wire. Sends
  a `Hello` preamble as the first frame; suspends until the peer's preamble
  arrives. Use when peer IDs are not known before the connection.
- **`identified(conn, selfId, remoteId, dispatcher)`** — skip the handshake.
  Both identities are known (e.g. from TLS certificates, a session token, a
  shared discovery layer). The resulting `LinkSeam` is immediately usable.

### 4. Lifecycle — map your transport to `Woven`/`Torn`

`weave()` returns a `Seam` in state `SeamState.Woven`. Your `Conn.close()`
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
// kuilt-tcp/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/tcp/TcpLoom.kt:60-62
seamDispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
```

- `seamDispatcher` — confines the `LinkSeam`'s mutable state (the outbox
  channel, peer set, teardown gate). It is the *scheduler* for the seam's
  read/write coroutines, not a mutex — `LinkSeam` thread-safety is enforced by
  an atomic teardown flag, not by dispatcher confinement alone.
- `ioDispatcher` — runs the blocking socket reads inside `PumpedConn`. Real
  sockets cannot run on a virtual test clock; blocking reads belong here.

Test callers inject a `TestCoroutineScheduler`-derived dispatcher so the
seam's read/write loops share the same virtual clock as `withTimeout` in the
test body.

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
of Track B — `framed()` + `pumped()` + `handshaking()` — all in:

```kotlin
// kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/ProprietaryRpcExampleTest.kt:55-56
private suspend fun weaveSeam(socket: Socket, selfId: PeerId): Seam =
    handshaking(rpcConn(socket), selfId, Dispatchers.IO)
```

---

## N-peer cluster: `meshSeam()`

For the N-peer case — all peers connected directly to each other — `meshSeam()`
aggregates a list of point-to-point `Conn`s into a single fully-connected
`Seam`. It exchanges a `Hello` preamble on each link concurrently, deduplicates
symmetric simultaneous-dial races (lower `selfId` wins), handles per-link
failures gracefully (the `Seam` stays `Woven` when a single link drops), and
confines all mutable mesh state to the injected `dispatcher`:

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt:59-63
public suspend fun meshSeam(
    selfId: PeerId,
    conns: List<Conn>,
    dispatcher: CoroutineContext,
): Seam
```

Each `Conn` in the list is produced by the same pipeline: `framed()` +
`pumped()` for stream transports, or a raw hot `Conn` for message-based ones.
Pass one `Conn` per peer; `meshSeam()` handles the rest.

A full TCP cluster example — binding/dialing N ports, assembling the `Conn`
list, and calling `meshSeam()` — is tracked as a follow-up to this tutorial.

---

## Summary

The transport-specific code for a stream fabric totals ~30 lines across three
focused files:

| File | Lines | What it does |
|------|-------|--------------|
| `TcpConn.kt` | ~11 | Bridge socket channels → `Source`/`Sink` → `framed()` → `pumped()` |
| `TcpAddress.kt` | ~7 | Discovery handle (`Tag` implementation) |
| `TcpLoom.weave` | ~10 | Dial or accept, build `Conn`, call `handshaking()` |

Everything else is `kuilt-core` and `:kuilt-stream` — plug in your transport's
connect/accept and you inherit a `Seam` that Raft, CRDTs, fair dealing, and
sessions all run on unchanged.

See also: [Architecture](architecture.md) for the full `Conn`↔`Seam` type
table and the contract's load-bearing invariants.
