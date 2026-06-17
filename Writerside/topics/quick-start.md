# Quick start

If you want to learn kuilt fast, start above the network. `InMemoryLoom` gives
you two peers exchanging real `Seam` frames with no sockets, no radios, and no
setup. It is ideal for tests and for building application behavior before you
care about transport details.

## Host a session and broadcast a frame

```kotlin
```
{ src="../../kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/LoomSamples.kt" include-symbol="sampleBroadcastReceived" }

Three things to notice:

1. **`InMemoryLoom` is the loom; `host` and `join` use the same instance.** Both seams share one in-memory mesh. Radio fabrics (Nearby, Multipeer) work the same way — one loom plays both roles.
2. **`incoming` is collected once.** All frames for a peer arrive on a single `Flow<Swatch>`. Collect it in one coroutine; if several parts of your app need the frames, wrap it with `shareIn`.
3. **`sender` and `sequence` are stamped on receipt.** The sending peer leaves `sender` null and `sequence` zero; the receiving `Seam` fills them in.

## Membership: peers join and leave

```kotlin
```
{ src="../../kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/LoomSamples.kt" include-symbol="sampleJoinPeerSet" }

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
