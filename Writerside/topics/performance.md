# Performance and Scaling

How does kuilt behave as you add more devices to a session?

The core primitives are designed to add work proportional to the number of new peers, not exponential amounts. The numbers below are verified by automated tests that run on every CI build.

## Consensus cost

When peers need to agree on something in order — a game turn, a distributed lock — kuilt uses a leader-based agreement protocol. The leader sends every other peer a message; each peer replies.

The cost is exactly **2(N−1) messages per decision**, where N is the number of peers.

| Peers | Messages per decision |
|-------|-----------------------|
| 3     | 4                     |
| 5     | 8                     |
| 7     | 12                    |
| 9     | 16                    |

This is the minimum possible for this family of protocols, and the implementation hits it exactly. It is verified as a regression test in CI (`RaftScalingTest`).

## Shared data cost

Shared state — counters, sets, maps, collaborative sequences — replicates by sending only the change (a "delta"), not the whole value. The message count grows linearly with the number of peers for all data structure types: GCounter, PNCounter, ORSet, LWWMap, ORMap, and RGA.

**One exception:** `BoundedCounter` transfer today costs O(N²) messages per transfer event. This is a known limitation; a future protocol change will bring it to linear. See [BoundedCounter](crdt-bounded-counter.md) for details.

## Partial-mesh gossip: O(N) → O(k)

The cost above is per peer you actually talk to. On a full mesh every peer talks to
every other one, so "per peer" means *all N−1 of them* — broadcast fan-out, and the
acknowledgements Quilter tracks to garbage-collect, both grow with N. [Partial-mesh
gossip](partial-mesh.md) caps that at a constant handful of neighbours **k** (≈ `ln N`,
so 4–7 peers for tens-to-hundreds), and the costs stop tracking N:

- **Broadcast fan-out stays ≈ k.** A single broadcast disseminates across the whole
  overlay, but each node only floods to its ~k neighbours. Measured on the in-memory
  harness, max per-node fan-out is **5 / 5 / 6 at N = 10 / 20 / 40** (= k) and reaches
  every peer — versus the full-mesh **9 / 19 / 39** (N−1). Total relay sends stay below
  N·k (44 / 95 / 237), sub-quadratic against the full-mesh N·(N−1).
- **GC tracks k acks, not N.** Pointing Quilter's `deltaTargets` at the active-neighbour
  view drops the garbage-collection watermark from `min over N` to `min over k`, so the
  pending-delta buffer no longer grows with membership.

This is verified in CI (`GossipBroadcastScalingTest`, `GossipQuilterScalingTest`). The
relay seen-set used for flood deduplication is itself bounded to O(origins), not O(total
broadcasts), via a per-origin high-water mark (`GossipDedup`).

### Deferred optimizations (measured, not yet needed)

Two gossip optimizations are intentionally **not** built — measured to be unnecessary at
the target scale, with the trigger to revisit recorded:

| Optimization | Measured today | Trigger to build it |
|--------------|----------------|---------------------|
| **Digest-gated reconcile** | Anti-entropy ships full state every round: ~78 B/round for a 1-element CRDT, ~6.5 KB for 200 elements (cost ∝ state size, not change size) | Average CRDT state reaches the multi-KB range |
| **Anti-entropy fanout > 1** | First-contact latency with fanout=1 follows the coupon-collector tail ≈ N·H(N): 29 / 80 / 166 rounds at N = 10 / 20 / 40 — but only on the backstop path; the flood reaches everyone in O(k) immediately | Large membership where backstop latency matters and flood drops are non-trivial |

Numbers are reproduced by `GossipAntiEntropyMeasurementTest`. The full design rationale —
including why kuilt uses simpler variants than HyParView and Plumtree — is in
[`docs/gossip-mesh-design.md`](https://github.com/tractat-us/kuilt/blob/main/docs/gossip-mesh-design.md).

## How to run the scaling tests

**Default (in-memory, always runs in CI):**

```bash
./gradlew :kuilt-scale:test
```

**Real TCP sockets (opt-in, for local validation):**

```bash
./gradlew :kuilt-scale:test -Pscale.tcp.tests=true
```

For a full explanation of the methodology, the measurement harness design, and the complete data table, see [docs/performance.md](https://github.com/tractat-us/kuilt/blob/main/docs/performance.md).
