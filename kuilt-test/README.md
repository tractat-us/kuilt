# kuilt-test

Test doubles for `kuilt-core` contracts. Add this as a `testImplementation` dependency so your tests stop hand-rolling `Seam` implementations that break every time the interface evolves.

```kotlin
testImplementation("us.tractat.kuilt:kuilt-test:<version>")
```

## FakeSeam — one-liner setup

```kotlin
val seam = FakeSeam()   // selfId=PeerId("self"), Woven, single-peer
seam.deliver(PeerId("alice"), byteArrayOf(1, 2, 3))
val frame = seam.incoming.first()
assertEquals(PeerId("alice"), frame.sender)
```

Lifecycle helpers: `weave()`, `tear(reason)`, `addPeer(id)`, `removePeer(id)`, `close(reason)`.

Outgoing inspection: `seam.broadcasts: List<ByteArray>`, `seam.directed: List<Pair<PeerId, ByteArray>>`.

## fakeSeamPair — wired two-peer scenario

```kotlin
val (host, joiner) = fakeSeamPair(PeerId("host"), PeerId("joiner"))
host.broadcast(byteArrayOf(1, 2, 3))
val frame = joiner.incoming.first()
// frame.sender == PeerId("host"), frame.sequence == 1L
```

Each side's `peers` contains both IDs. `broadcast` on one side delivers a `Swatch` into the other's `incoming` with the correct `sender` and a receiver-local monotonically increasing `sequence`.

## FakeLoom

```kotlin
val loom = FakeLoom()
val seam = loom.host(Pattern("alice"))
// seam.selfId == PeerId("alice")
```

## Why this module

When `Seam` evolves, only this module updates. Consumers pin to a version and get stable doubles.
