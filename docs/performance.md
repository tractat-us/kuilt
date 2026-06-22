# Performance and scaling

How well does kuilt scale as you add more devices to a session?

The short answer: **the core data path scales well**. Adding a peer adds a bounded amount
of extra work, not an exponential amount. The messaging protocols used for coordination are
designed to scale linearly, and the numbers below back that claim with actual measurements.

## What was measured and why

A dedicated module, `:kuilt-scale`, provides a reusable harness for measuring how much
network traffic kuilt generates as the number of peers grows. Every measurement runs
deterministically in-memory, with no real network and no wall-clock timing, so the results
are stable enough to use as **regression guards in CI**. A separate, opt-in layer runs the
same scenarios over real TCP sockets on localhost to confirm the in-memory predictions hold.

The harness was added to give the library an evidence base: not because the code was slow,
but because without measurement it was impossible to know *when* a change made things worse.

## Consensus: how many messages does an agreement cost?

When N peers need to agree on something in order — a game action, a configuration change —
kuilt uses Raft consensus. The leader sends every follower a message with the new entry, and
each follower replies to confirm it. That is the whole round.

The cost is exactly **2(N−1) messages per committed entry**:

| Peers (N) | Messages per entry |
|-----------|-------------------|
| 3         | 4                 |
| 5         | 8                 |
| 7         | 12                |
| 9         | 16                |

This is the theoretical minimum for this protocol, and the implementation hits it exactly.
The number is verified as an automated regression test in `RaftScalingTest`
(`kuilt-scale/src/test/kotlin/us/tractat/kuilt/scale/RaftScalingTest.kt`).

Why is 2(N−1) the right answer? The leader sends one AppendEntries message to each of the
N−1 followers, and each follower sends one acceptance back. Two messages per follower, N−1
followers: 2(N−1). Heartbeats (empty AppendEntries with no entries) are not counted.

## Shared data: how does replication scale?

Shared data structures — counters, sets, maps, sequences — replicate using delta-state
synchronisation via `Quilter`. A delta is just the change, not the whole state, so each
update is small. The message complexity across the zoo is summarised below.

| Data structure | Workload (per peer) | Message growth |
|----------------|---------------------|----------------|
| GCounter / PNCounter | 1–2 mutations | Linear in N |
| ORSet | 1 add | Linear in N |
| LWWMap | 3 writes | Linear in N |
| ORMap | 2 puts (nested GCounter) | Linear in N |
| RGA (ordered sequence) | 5 inserts + 2 removes | Linear in N |

"Linear in N" means that doubling the number of peers roughly doubles the number of
messages. This is what you want from a broadcast protocol: every peer needs to hear about
every other peer's change once.

All five workloads converge in **one round** (one pass of delta delivery to all peers),
except RGA, which requires two rounds because removes run after inserts.

Tests: `CrdtZooScalingTest` covers GCounter, PNCounter, ORSet, LWWMap, ORMap, and RGA
across N = 3, 5, 7, and 10 peers.

### One exception: BoundedCounter transfer

`BoundedCounter` has a transfer protocol that today costs O(N²) messages per transfer
event. When one peer's quota runs out and requests more from the others, each surplus peer
independently broadcasts a donation delta, and every peer acks every delta. On a 10-peer
mesh that can mean ~81 extra messages for a single transfer event.

This is a known design limitation, recorded as a regression baseline in
`BoundedCounterScalingTest`. A future redesign will replace the broadcast-to-all
protocol with a targeted borrow from a single surplus peer, bringing the cost down to
linear. Until that lands, BoundedCounter transfer is O(N²).

## Methodology

### Layer A — deterministic, in-memory (always runs in CI)

The in-memory layer builds a fully-connected mesh of N peers using channel-backed
connections. All coroutines run under a virtual-time scheduler (`StandardTestDispatcher`)
with no real clock, so results are bit-for-bit reproducible. Each peer's `Seam` is wrapped
in a `MeteredSeam` that counts broadcasts, directed sends, and inbound frames using
atomicfu atomics. Totals are summed across all peers into a `ClusterMetrics` snapshot.

For consensus tests, the harness reuses the canonical `MultiNodeRaftSim` / `raftSimTest`
infrastructure to avoid hand-rolled clusters (which spin the virtual scheduler and hang).
For CRDT tests, `Quilter` instances run over the metered seams with virtual time driven
by `advanceUntilIdle`.

Topologies available: complete graph (default, every peer connected to every other),
ring (each peer connected only to neighbours), and star (hub-and-spoke relay). The scaling
tests all use the complete graph because it represents the highest-connectivity case.

### Layer B — real TCP (opt-in, never in CI by default)

The TCP layer assembles N*(N−1)/2 `TcpLoom` 2-peer links into per-node `meshSeam`s over
real localhost sockets. It sweeps N over {3, 5, 7, 10}, reports wall-clock convergence
time and socket counts, and validates that the in-memory predictions hold over real IO.

## How to run

**In-memory tests (runs as part of the normal build):**

```bash
./gradlew :kuilt-scale:test
```

These tests are always part of `./gradlew build`. No extra flags needed.

**Real-TCP tests (opt-in):**

```bash
./gradlew :kuilt-scale:test -Pscale.tcp.tests=true
```

The `-P` flag forwards to the test process as a system property. Tests guarded by
`ScaleTcpTests.assumeEnabled()` are silently skipped unless the flag is present.
This matches the pattern used by the mDNS multicast suite (`-Pmdns.multicast.tests=true`).

The TCP layer requires a real local network stack and spawns actual file descriptors —
keep the node count modest (up to ~20) for laptop use.

## Internal optimizations

Two internal optimizations landed alongside the harness. They are not user-visible but
explain why the measured numbers are what they are.

**Raft log access** was changed from linear search (`firstOrNull { it.index == … }`)
to direct index arithmetic on the contiguous log. Per-heartbeat filtering was replaced
with a `subList` slice. These changes eliminate a per-entry scan that was invisible at
small N but would have shown up as super-linear Raft cost at larger cluster sizes.

**RGA sequence operations** now thread internal caches (`insertsById`, `nextSeq`,
Lamport state) forward across inserts and removes instead of discarding them after each
operation. The `size` property reads the cached length directly. This removes an O(ops)
cliff on sequences with many insertions.

Neither change affects the protocol-level message counts above. They reduce CPU work
inside a single node, not the number of messages exchanged between nodes.
