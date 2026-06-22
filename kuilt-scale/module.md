# Module kuilt-scale

Scaling and performance measurement harness for kuilt. **Not published** — this is a
test/bench surface used to characterise message complexity and catch regressions.

## What it provides

Two instrumentation layers over a shared metrics model.

**Metrics model** — `MeteredSeam` wraps any `Seam` and counts broadcasts, directed
sends (`sendTo`s), and inbound frames using atomicfu atomics. `SeamMetrics` is a
per-peer snapshot; `ClusterMetrics` is the cluster-wide sum. `ConvergenceTracker`
records how many virtual-time rounds it takes for all peers to reach the same state.

**In-memory mesh builder** — `buildInMemoryMesh(n, topology)` assembles N peers
wired according to a `Topology` (complete graph, ring, or star) using
channel-backed in-memory connections from `:kuilt-test`. The result is an
`InMemoryMesh` whose seams are all `MeteredSeam` instances ready for measurement.
All handshakes run concurrently (serial would deadlock). Runs under virtual-time
test dispatchers — no real clock, no real sockets, deterministic output suitable
for CI regression guards.

**Real-TCP layer (opt-in)** — `TcpMeshBuilder` assembles the same logical mesh
over real localhost TCP sockets using `:kuilt-tcp`. Enable with `-Pscale.tcp.tests=true`;
the build forwards the Gradle property as a JVM system property. `ScaleTcpTests.assumeEnabled()`
skips the test gracefully when the flag is absent. Mirrors the pattern used by
`:kuilt-mdns`'s multicast suite.

## What the tests measure

`RaftScalingTest` (consensus) drives clusters of N = 3, 5, 7, 9 voters through the
canonical `MultiNodeRaftSim` harness and asserts exactly 2(N−1) transport messages
per committed entry — the theoretical minimum for the protocol.

`CrdtZooScalingTest` (shared data) runs GCounter, PNCounter, ORSet, LWWMap, ORMap,
RGA, and BoundedCounter across N = 3, 5, 7, 10 fully-connected peers and verifies
that message counts grow monotonically with N. BoundedCounter's current O(N²)
transfer cost is captured as an explicit baseline, with assertions that enforce the
quadratic shape as a regression guard until a future redesign reduces it to O(N).

`TcpMeshScaleTest` (real sockets) sweeps the same N sizes over TCP, reports a
wall-clock + socket-count table, and validates that the in-memory predictions hold.

## Targets and publication

JVM only. Uses the plain `kotlinJvm` plugin, not `kuilt.kmp-library`. Not included
in the `:kuilt-bom` publication set. The module builds as part of `./gradlew build`
but publishes nothing.

See `docs/performance.md` for the full characterisation and methodology.
