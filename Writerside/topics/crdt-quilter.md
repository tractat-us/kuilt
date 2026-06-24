# Quilter

`Quilter<S>` keeps a CRDT replica live over a `Seam`. It collects the `Seam`'s `incoming` flow, merges inbound deltas, and broadcasts outbound deltas as you apply mutations. `state` is a `StateFlow<S>` — always the current converged value.

## Basic setup

{ src="../../kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt" include-symbol="sampleQuilterSetup" }

## Two-peer GCounter convergence

{ src="../../kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterTest.kt" include-symbol="twoPeerGCounterConverges" }

## Late-joiner full-state sync

When a peer joins after others have accumulated state, `Quilter` sends a `FullState` message rather than replaying the delta history. The late joiner converges in one round-trip:

{ src="../../kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterTest.kt" include-symbol="lateJoinerReceivesFullState" }

## Multiplexing multiple replicators over one Seam

`Seam.incoming` is single-collection per the kuilt contract. If two replicators tried to collect the same `Seam` independently, one would starve. `MuxSeam` (`kuilt-core`) solves this: it wraps the underlying seam, owns the single collection via `shareIn`, and prefixes frames with a 1-byte channel tag:

{ src="../../kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/LoomSamples.kt" include-symbol="sampleMuxSeamChannels" }

Each consumer gets a typed `Seam` view that strips the tag on reads and prepends it on writes. This is how `BoundedCounterTransferCoordinator` and `Quilter` share one transport (see [BoundedCounter](crdt-bounded-counter.md)). It is also how `kuilt-session`'s `Room.channel(id)` provides scoped sub-channels.

## Session metadata convergence

`Quilter` + `LWWMap` is the standard pattern for live-converging session metadata (display names, preferences):

{ src="../../kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt" include-symbol="sampleQuilterSessionMetadata" }

## Scaling to many peers

By default Quilter garbage-collects against every peer in the room, which is fine for
a handful of peers. For dozens-to-hundreds, replicate over a
[`GossipSeam`](partial-mesh.md) and point `deltaTargets` at its active-neighbour view:

```kotlin
val quilter = Quilter(
    seam = gossip,                              // a GossipSeam wrapping your base seam
    initial = GCounter.ZERO,
    valueSerializer = GCounter.serializer(),
    scope = scope,
    deltaTargets = { gossip.activePeers.value },
)
```

This keeps the pending-delta buffer and acknowledgement tracking flat as the group
grows, while Quilter's anti-entropy reconcile still converges every peer. See
[Scaling to many peers](partial-mesh.md) for the full picture.

## `AutoCloseable` lifecycle

`Quilter` implements `AutoCloseable`. Call `close()` to cancel the background collection and release resources. In a `use {}` block or a scope that is cancelled, the replicator shuts down cleanly.

## Wire protocol

`Quilter` serialises messages with CBOR by default (via `Cbor` from `kotlinx-serialization`). Messages are `QuiltMessage<S>`:

- `Delta(seq, patch)` — an incremental update.
- `FullState(state)` — the complete current state, sent to new peers and as a retry on gap detection.
- `Ack(seq)` — acknowledgement, used to clear the pending-delta buffer.
- `Resend(fromSeq)` — request to re-send deltas from `fromSeq` when a gap is detected.
