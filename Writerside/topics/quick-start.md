# Quick start

The fastest way to get two peers exchanging frames is `InMemoryLoom`. It needs no network, no configuration, and no real fabric — every `Seam` it produces shares one in-memory mesh. It is the right tool for tests and for any layer of your application that lives above the wire.

## Host a session and broadcast a frame

<!-- verbatim from kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomTest.kt#broadcast from A causes B to receive the frame -->

```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomTest.kt
// Test: `broadcast from A causes B to receive the frame`
runTest {
    val factory = InMemoryLoom()
    val a = factory.host(Pattern("Alice"))
    val b = factory.join(InMemoryTag("Bob"))

    val receivedByB = async { b.incoming.first() }

    a.broadcast(byteArrayOf(1, 2, 3))

    val frame = receivedByB.await()
    assertEquals(Swatch(byteArrayOf(1, 2, 3), sender = a.selfId, sequence = 1L), frame)
}
```

Three things to notice:

1. **`InMemoryLoom` is the loom; `host` and `join` use the same instance.** Both seams share one in-memory mesh. Radio fabrics (Nearby, Multipeer) work the same way — one loom plays both roles.
2. **`incoming` is collected once.** All frames for a peer arrive on a single `Flow<Swatch>`. Collect it in one coroutine; if several parts of your app need the frames, wrap it with `shareIn`.
3. **`sender` and `sequence` are stamped on receipt.** The sending peer leaves `sender` null and `sequence` zero; the receiving `Seam` fills them in.

## Membership: peers join and leave

```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomTest.kt
// Test: `join after open causes both peers to appear in each other's peer set`
runTest {
    val factory = InMemoryLoom()
    val host = factory.host(Pattern("Alice"))
    val joiner = factory.join(InMemoryTag("Bob"))

    assertEquals(setOf(host.selfId, joiner.selfId), host.peers.value)
    assertEquals(setOf(host.selfId, joiner.selfId), joiner.peers.value)
}
```

`peers` is a `StateFlow<Set<PeerId>>`. Both host and joiner see the same set — peer symmetry in action. When a peer closes, it disappears from every other peer's `peers` atomically.

## The interaction pattern (every fabric)

```kotlin
// 1. Get a Seam — host or join, depending on your role.
val seam: Seam = loom.host(Pattern(displayName = "alice", maxPeers = 4))

// 2. Collect incoming frames EXACTLY ONCE. Fan out with shareIn if needed.
scope.launch {
    seam.incoming.collect { swatch ->
        println("from ${swatch.sender}: ${swatch.payload.decodeToString()}")
    }
}

// 3. Send. broadcast() reaches all peers; sendTo() targets one.
seam.broadcast("hello everyone".encodeToByteArray())
seam.sendTo(somePeerId, "just for you".encodeToByteArray())

// 4. Watch membership.
scope.launch { seam.peers.collect { current -> render(current) } }

// 5. Close when done. Idempotent.
seam.close()
```

This pattern is the same for every fabric — swap out the `Loom` and your application code is unchanged.

## Next steps

- [The contract](contract.md) — deep dive into `Loom`, `Seam`, `Swatch`, and the rules.
- [Fabrics](fabrics.md) — using WebSocket, mDNS, and writing your own.
- [CRDT zoo](crdt-overview.md) — live-converging replicated data structures over a `Seam`.
