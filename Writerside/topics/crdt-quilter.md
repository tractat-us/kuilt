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
- `FullState(state)` — the complete current state, sent to new peers, as a retry on gap
  detection, and in reply to a `FullStateRequest`.
- `Ack(seq)` — acknowledgement, used to clear the pending-delta buffer. Also what a peer
  sends back when a `RootDigest` matches its own state.
- `Resend(fromSeq)` — request to re-send deltas from `fromSeq` when a gap is detected.
- `RootDigest(root, upThrough)` — the background reconcile tick: a 64-bit hash of the
  state rather than the state itself, so a round between peers that already agree costs
  two small frames — this one out, and an `Ack` back. Also sent on first contact, as a way
  of saying "I can read these"; a peer that has never said it back is still sent the whole
  state each round, since it may be running an older version that cannot read a hash.
- `FullStateRequest` — the reply a peer sends when its own hash differs, asking for the
  state it is missing.
- `Delivered(vector)` — a peer's own summary of what it has applied from everyone, gossiped
  so the group can agree on which history is safely behind everybody and can be discarded.
