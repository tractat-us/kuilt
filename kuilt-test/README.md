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

## InMemoryRoomFabric — room-isolating in-memory double

For tests that stand up **more than one room**, reach for this instead of the flat
`InMemoryLoom`. `InMemoryLoom` is a single broadcast domain: a joiner of one room is silently
cross-admitted into every other room hosted on the same loom. `InMemoryRoomFabric`'s `serverLoom`
is a `MuxServerLoom`, so rooms hosted over it are structurally isolated by name (the Seam-layer
fanout isolation pinned by `RoomFanoutIsolationConformanceSuite`).

```kotlin
val fabric = InMemoryRoomFabric(scope = backgroundScope, dispatcher = dispatcher)
val hostFactory = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock = zeroClock)
val room1 = hostFactory.host(Pattern("room-1"))
val room2 = hostFactory.host(Pattern("room-2"))

val joinerFactory = SeamRoomFactory(fabric.clientLoom(PeerId("joiner"), Random(1L)), backgroundScope, clock = zeroClock)
val joiner = joinerFactory.join(InMemoryTag("room-1"))   // reaches room-1 only; room-2 stays empty
```

`clientSeam(peerId, random)` returns the raw multi-channel seam (wrap in a `NamedMux`);
`clientLoom(peerId, random)` wraps it in a `MuxClientLoom` so it plugs straight into
`SeamRoomFactory`. Hosts and joiners of one room must agree on the rendezvous display name.

## Why this module

When `Seam` evolves, only this module updates. Consumers pin to a version and get stable doubles.
