# The contract

The point of kuilt's contract is to keep your app code stable while transports
change underneath it. You learn eight types once, then reuse them across every
fabric.

## Why this contract exists

Different fabrics fail in different ways (discovery delays, dropped links,
platform-specific APIs). The contract narrows that variation to one application
surface:

- open or join a session (`Loom`),
- send and receive opaque frames (`Seam` + `Swatch`),
- react to membership changes (`peers`).

That keeps transport-specific complexity out of app logic.

| Type | Role |
|------|------|
| `Loom` | Factory — `weave(Rendezvous): Seam`; convenience wrappers `host(Pattern)` and `join(Tag)` |
| `Seam` | One peer's symmetric view of a live session |
| `Swatch` | Opaque binary frame — `payload: ByteArray`, `sender: PeerId?`, `sequence: Long` |
| `Rendezvous` | Sum type: `New(pattern)` to host, `Existing(tag)` to join |
| `Pattern` | Config for opening a session: display name, max peers |
| `Tag` | Discovery handle for joining a session (`WebSocketAdvertisement`, `MDNSAdvertisement`, …) |
| `PeerId` | Stable identifier for a peer within a session |
| `FabricAvailability` | `Available` or `Unavailable(reason)` |

## Loom

`Loom` is where sessions come from: host a new one or join an existing one.
Formally, its single abstract method is:

```kotlin
suspend fun weave(rendezvous: Rendezvous): Seam
```

Two convenience wrappers delegate to it:

```kotlin
suspend fun host(pattern: Pattern): Seam = weave(Rendezvous.New(pattern))
suspend fun join(tag: Tag): Seam = weave(Rendezvous.Existing(tag))
```

`availability()` reports whether the fabric is usable on this runtime.

- A fabric that is *absent* on a platform (for example, Multipeer on wasmJs)
  simply is not on the classpath.
- `Unavailable(reason)` is for a capability that exists in principle but is
  missing *right now* (for example, Play Services absent on an AOSP build).

A host composing fabrics can pick the first available loom:

```kotlin
val activeLoom = looms.first { it.availability() is FabricAvailability.Available }
```

A `Loom` can also *combine* other `Loom`s rather than pick one: `CompositeLoom`
runs several transports as one bonded session for the same peer. See
[Multipath](multipath.md).

## Seam

`Seam` is the API your app actually uses at runtime. It is one peer's symmetric
view of a multi-peer session. There is no client `Seam` and no server `Seam` —
every peer holds the same interface.

```kotlin
interface Seam {
    val selfId: PeerId
    val peers: StateFlow<Set<PeerId>>       // includes selfId
    val incoming: Flow<Swatch>              // single-collection
    suspend fun broadcast(payload: ByteArray)
    suspend fun sendTo(peer: PeerId, payload: ByteArray)
    suspend fun close(reason: CloseReason = CloseReason.Normal)
}
```

## The rules

These are the load-bearing invariants. Violating them breaks consumers in ways the type system won't catch:

### `incoming` is single-collection

One `Flow<Swatch>` carries all peers' frames, in send order, delivered to **one** collector. Collect it once per `Seam`. A second concurrent collector races and is unsupported.

If several parts of your application need the frames, wrap with `shareIn`:

```kotlin
val shared = seam.incoming.shareIn(scope, SharingStarted.Eagerly)
// now multiple collectors on `shared` are safe
```

### `Swatch` is binary-only

No text-frame variant. The wire layer never interprets the bytes — that is the consumer's job.

### `sender` and `sequence` are stamped on receipt

Sending peers leave `sender` null and `sequence` zero. The receiving `Seam` stamps them:

```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomTest.kt
// Test: `Swatch sender field on received broadcast equals sender PeerId`
runTest {
    val factory = InMemoryLoom()
    val a = factory.host(Pattern("Alice"))
    val b = factory.join(InMemoryTag("Bob"))

    val deferred = async { b.incoming.first() }
    a.broadcast(byteArrayOf(0))
    val frame = deferred.await()

    assertEquals(a.selfId, frame.sender)
}
```

### No client/server split

A 2-peer WebSocket connection is the degenerate `peers.size == 2` case of the symmetric model. This is why the WebSocket fabric and an N-peer Multipeer mesh share one contract.

### `close()` is idempotent

Calling `close()` twice must not throw:

```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomTest.kt
// Test: `close is idempotent — calling twice does not throw`
runTest {
    val factory = InMemoryLoom()
    val link = factory.host(Pattern("Alice"))

    link.close()
    link.close() // must not throw
}
```

When a peer closes, it is removed from every other peer's `peers` set atomically and sending to it becomes an error.

## `peers` tracks membership

`peers: StateFlow<Set<PeerId>>` always includes `selfId`. When peers join and leave, the flow emits the updated set on every `Seam` in the session:

```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomTest.kt
// Test: `close removes the closing peer from every other peer's peers set`
runTest {
    val factory = InMemoryLoom()
    val a = factory.host(Pattern("Alice"))
    val b = factory.join(InMemoryTag("Bob"))
    val c = factory.join(InMemoryTag("Charlie"))

    b.close()

    val expected = setOf(a.selfId, c.selfId)
    assertEquals(expected, a.peers.value)
    assertEquals(expected, c.peers.value)
}
```

## Sequence numbers

The receiving `Seam` assigns a monotonically increasing sequence number per receiver. Sequence numbers are receiver-local — A and B have independent counters:

```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomTest.kt
// Test: `sequence on received frames is monotonically increasing starting from 1`
runTest {
    val factory = InMemoryLoom()
    val a = factory.host(Pattern("Alice"))
    val b = factory.join(InMemoryTag("Bob"))

    val frames = async { b.incoming.take(3).toList() }

    a.broadcast(byteArrayOf(1))
    a.broadcast(byteArrayOf(2))
    a.broadcast(byteArrayOf(3))

    val received = frames.await()
    assertEquals(1L, received[0].sequence)
    assertEquals(2L, received[1].sequence)
    assertEquals(3L, received[2].sequence)
}
```
