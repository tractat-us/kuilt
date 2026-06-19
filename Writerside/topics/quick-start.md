# Quick start

If you want to learn kuilt quickly, start with `InMemoryLoom`. It gives you two
peers exchanging real `Seam` frames with no sockets, no radios, and almost no
setup. It is ideal for tests and for building app behavior before choosing a
real transport.

## Host a session and broadcast a frame

```kotlin
```
{ src="../../kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/LoomSamples.kt" include-symbol="sampleBroadcastReceived" }

Three things to notice:

1. **`InMemoryLoom` uses one shared instance for both `host` and `join`.** Both seams share one in-memory mesh. Radio-based fabrics (Nearby, Multipeer) work the same way.
2. **Collect `incoming` once.** All frames for a peer arrive on one `Flow<Swatch>`. Collect it in one coroutine; if multiple parts of your app need the frames, wrap it with `shareIn`.
3. **`sender` and `sequence` are set when a frame is received.** The sender leaves `sender` as null and `sequence` as zero; the receiving `Seam` fills them in.

## Membership: peers join and leave

```kotlin
```
{ src="../../kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/LoomSamples.kt" include-symbol="sampleJoinPeerSet" }

`peers` is a `StateFlow<Set<PeerId>>`. Both host and joiner see the same set.
When a peer closes, it is removed from every other peer's `peers` set atomically.

## The interaction pattern (every fabric)

```kotlin
// 1. Get a Seam — host or join, depending on your role.
val seam: Seam = loom.host(Pattern(displayName = "alice", maxPeers = 4))

// 2. Collect incoming frames once. Fan out with shareIn if needed.
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

This pattern is the same for every fabric: swap the `Loom`, keep the app code.

## Next steps

- [The contract](contract.md) — deep dive into `Loom`, `Seam`, `Swatch`, and the rules.
- [Fabrics](fabrics.md) — using WebSocket, mDNS, and writing your own.
- [CRDT overview](crdt-overview.md) — replicated data structures that converge over a `Seam`.
