# Module kuilt-quilter

Live CRDT replication over a `Seam`. Depends on `:kuilt-core` (transport contract)
and `:kuilt-crdt` (value types); `:kuilt-crdt` itself has no transport dependency.

## Core types

- **`Quilter<S>`** — runs any `Quilted<S>` CRDT live over a `Seam`. Collects
  `Seam.incoming`, merges inbound deltas, broadcasts outbound deltas on `apply()`.
  `state: StateFlow<S>` is always the current converged value. See `QuilterConfig`
  for eviction, anti-entropy, and retry tuning.

- **`QuiltMessage<S>`** — sealed wire message hierarchy: `Delta`, `Ack`, `FullState`,
  `Resend`, `Delivered`, `RootDigest`, `FullStateRequest`. Serialised with CBOR by
  default. The last two are the anti-entropy tick: it ships a `RootDigest` — a 64-bit
  hash of the state plus the sender's own-delta high-water — and the peer answers with a
  `FullStateRequest` only when its own root differs, so a converged round costs one small
  frame instead of the whole CRDT (#1955). `FullState` is untouched and remains the
  always-correct fallback that every convergence guarantee traces to.

- **`RgaGcCoordinator`** — causal-stability GC coordinator for `Rga`. Consumes
  `Quilter.cutFrontier` + `deliveredLocal` and emits `Compact` patches that evict
  stable tombstones from the op-log.

- **`BoundedCounterTransferCoordinator`** — distributed quota-transfer protocol for
  `BoundedCounter` (Rung 9). Runs alongside a `Quilter<BoundedCounter>` over a
  `MuxSeam`.

## Convenience factory

The top-level `Quilter(seam, initial, valueSerializer, scope, …)` function derives
the message serializer automatically:

```kotlin
val tally = Quilter(seam, PNCounter.ZERO, PNCounter.serializer(), backgroundScope)
tally.mutate { it.increment(tally.replica, 3L) }
```

The full constructor `Quilter(replica, seam, initial, messageSerializer, scope, …)`
is required for CRDTs whose serializer needs a custom `SerializersModule` (e.g. `Rga`
with a generic value type — use `Rga.wireSerializer(serializer<T>())` and pass
`QuiltMessage.serializer(…)` explicitly).
