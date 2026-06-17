# Quilter

`Quilter<S>` keeps a CRDT replica live over a `Seam`. It collects the `Seam`'s `incoming` flow, merges inbound deltas, and broadcasts outbound deltas as you apply mutations. `state` is a `StateFlow<S>` — always the current converged value.

## Basic setup

```kotlin
val seam: Seam = loom.host(Pattern("my-session"))
val replicator = Quilter(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,
    initial = GCounter.ZERO,
    messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
    scope = coroutineScope,
)

// Apply mutations — delta is broadcast to all peers automatically.
replicator.apply(replicator.state.value.inc(replicator.replica, 1L))

// Read the live converged state.
replicator.state.collect { counter -> println(counter.value) }
```

## Two-peer GCounter convergence

```kotlin
```
{ src="../../kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterTest.kt" include-symbol="twoPeerGCounterConverges" }

## Late-joiner full-state sync

When a peer joins after others have accumulated state, `Quilter` sends a `FullState` message rather than replaying the delta history. The late joiner converges in one round-trip:

```kotlin
```
{ src="../../kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterTest.kt" include-symbol="lateJoinerReceivesFullState" }

## Multiplexing multiple replicators over one Seam

`Seam.incoming` is single-collection per the kuilt contract. If two replicators tried to collect the same `Seam` independently, one would starve. `MuxSeam` (`kuilt-core`) solves this: it wraps the underlying seam, owns the single collection via `shareIn`, and prefixes frames with a 1-byte channel tag:

```kotlin
val mux = MuxSeam(seam, scope)
val replicatorSeam: Seam = mux.channel(0x00.toByte())
val coordinatorSeam: Seam = mux.channel(0x01.toByte())
```

Each consumer gets a typed `Seam` view that strips the tag on reads and prepends it on writes. This is how `BoundedCounterTransferCoordinator` and `Quilter` share one transport (see [BoundedCounter](crdt-bounded-counter.md)). It is also how `kuilt-session`'s `Room.channel(id)` provides scoped sub-channels.

## Session metadata convergence

`Quilter` + `LWWMap` is the standard pattern for live-converging session metadata (display names, preferences):

```kotlin
val rep = Quilter<LWWMap<PeerId, String>>(
    replica = ReplicaId(room.selfId.value),
    seam = room.channel("member-metadata"),
    initial = LWWMap.empty(),
    messageSerializer = QuiltMessage.serializer(
        LWWMap.serializer(PeerId.serializer(), String.serializer())
    ),
    scope = scope,
)
// rep.state is the live-converging display-name map.
```

## `AutoCloseable` lifecycle

`Quilter` implements `AutoCloseable`. Call `close()` to cancel the background collection and release resources. In a `use {}` block or a scope that is cancelled, the replicator shuts down cleanly.

## Wire protocol

`Quilter` serialises messages with CBOR by default (via `Cbor` from `kotlinx-serialization`). Messages are `QuiltMessage<S>`:

- `Delta(seq, patch)` — an incremental update.
- `FullState(state)` — the complete current state, sent to new peers and as a retry on gap detection.
- `Ack(seq)` — acknowledgement, used to clear the pending-delta buffer.
- `Resend(fromSeq)` — request to re-send deltas from `fromSeq` when a gap is detected.
