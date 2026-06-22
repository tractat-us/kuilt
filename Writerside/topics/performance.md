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
