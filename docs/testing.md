# Testing kuilt — harnesses and observability

This is the technical companion to the guide's **[Testing](https://tractat-us.github.io/kuilt/guide/testing.html)**
page. It assumes you've met the ideas there — virtual time, the conformance suites, the
cluster harness, and the three observability channels — and want the type names,
signatures, and the reasoning underneath.

It deliberately does **not** re-explain coroutine determinism: that has its own
document, **[Coroutine test determinism](testing-coroutine-determinism.md)**, and the
rules there (injectable dispatchers, `StandardTestDispatcher`, never `advanceUntilIdle()`)
are the foundation everything below is built on.

## The layers

| Layer | What it verifies | Entry point |
|-------|------------------|-------------|
| Fabric contract | any `Loom`/`Seam` obeys the message contract | `SeamConformanceSuite`, `RoomConformanceSuite` (`:kuilt-conformance`) |
| Single consensus node | one `RaftNode`'s lifecycle | `raftRunTest` + `singleVoterNode` (`:kuilt-raft` commonTest) |
| Consensus cluster | agreement across many nodes | `raftSimTest` + `MultiNodeRaftSim` (`:kuilt-raft-test`, published) |

## Fabric conformance

`SeamConformanceSuite` is an `abstract class`; every `@Test` on it encodes one required
invariant of the seam contract. You bind a fabric by implementing one method:

```kotlin
public abstract fun newLoomPair(): Pair<Loom, Loom>
```

`.first` hosts (`Loom.host`, i.e. `weave(Rendezvous.New(pattern))`); `.second` joins
(`Loom.join(joinTag())`). In-process radio fabrics return the *same* instance twice;
role-split fabrics (WebSocket, mDNS, WebRTC, Multipeer) return distinct host/joiner
looms wired to reach each other. The reference binding is the whole point of the design
— a fabric adapter is conformant when this subclass is green:

<!-- verbatim from kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/InMemoryLoomConformanceTest.kt#InMemoryLoomConformanceTest -->

```kotlin
class InMemoryLoomConformanceTest : SeamConformanceSuite() {
    private val loom = InMemoryLoom()
    override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
    // …
}
```

`RoomConformanceSuite` follows the same shape for membership-aware `Room`
implementations, binding a `RoomFactory` through an abstract `newHarness` instead of
`newLoomPair`.

## The cluster harness

`MultiNodeRaftSim` (published in `:kuilt-raft-test`) is the harness to reach for from
any module that needs a real `RaftNode` cluster — `:kuilt-cluster`, `:kuilt-game`,
`examples/`. The canonical entry point wires the dispatcher, timeout, and a ready
simulation into the test body:

```kotlin
public fun raftSimTest(
    n: Int = 3,
    baseConfig: RaftConfig = MULTI_NODE_SIM_BASE_CONFIG,
    baseSeed: Long = MULTI_NODE_SIM_SEED,
    timeout: Duration = RAFT_SIM_WEDGE_BACKSTOP,
    body: suspend TestScope.(MultiNodeRaftSim) -> Unit,
): TestResult
```

Inside `:kuilt-raft`'s own commonTest the equivalent internal pair is `raftRunTest`
(`runTest(StandardTestDispatcher(), timeout = 5.seconds)`) plus `raftSim(...)`, which
builds a `RaftSimulation`. Both harnesses expose the same surface:

- **Cluster mutation** — `crash`, `restart`, `partition`, `partitionOff`, `heal`,
  `dropLink`.
- **Bounded awaits** — `awaitLeader`, `awaitCommit`, `awaitRole`, `awaitTrue`,
  `proposeOnLeader`, plus `settle()` to drain pending work at the current instant.
- **Inspection** — `leader()`, `followers()`, `appliedState(id)`, `checkInvariants()`,
  `dumpState(reason)`.

### Why the discipline is not optional

The harness encodes four rules because each one, dropped, produces a test that *hangs*
rather than fails:

1. **Bounded `await*`, never `advanceUntilIdle()`.** A never-quiescing system has no idle
   state to advance to; the awaits step the clock forward in bounded 1 ms increments and
   fail fast on non-convergence — with a `dumpState()`. This, plus the election-churn
   bound, is where fast failure actually comes from: both bounds are **virtual**-time, so
   they are immune to machine load.
2. **The outer `runTest` timeout is a wedge backstop, not a budget.** `RAFT_SIM_WEDGE_BACKSTOP`
   (30 s) exists only to bound a wedge that escapes the bounded helpers entirely — an
   unbounded `await`, a deadlocked hand-rolled receive. Do **not** tighten it: everything
   it caps is virtual-time and deterministic, so a tight wall-clock cap asserts only that
   the *host* is fast enough, and fires before the legible detectors can speak. That is
   exactly the false-red class of [#1382](https://github.com/tractat-us/kuilt/issues/1382).
   When it does fire, `dumpOnWedge` prints the cluster state alongside the
   `UncompletedCoroutinesError`.
3. **Per-node seeded election RNG.** Scheduling is deterministic under
   `StandardTestDispatcher`, but the *duration* each node waits is still an RNG draw.
   `MultiNodeRaftSim` seeds `Random(baseSeed + nodeIndex)` per node so timeouts differ
   and a leader actually wins the race. Identical seeds ⇒ split-vote storm ⇒ no leader
   ⇒ hang.
4. **Node coroutines on `backgroundScope`.** The infinite loops must be cancelled at
   teardown, or `runTest` reports `UncompletedCoroutinesError`.

A hang in a cluster test is a **stop-and-investigate** signal — read the state dump, name
the spinning test, fix convergence. Never widen a bounded `await*` and retry.

## Observability, in depth

### Metrics — the `onMetric` callback

`RaftMetric` is a sealed interface delivered through an `onMetric: ((RaftMetric) -> Unit)?`
parameter on the node factory — a **callback, not a flow**. It fires synchronously on the
engine coroutine (so it never misses an event, and must not block). The vocabulary is the
propose lifecycle (`ProposeAccepted → ProposeCommitted → ProposeApplied`) and the election
lifecycle (`ElectionStarted → ElectionWon` / `ElectionTimedOut`), plus
`ElectionSuppressedTermCeiling` — a permanent *level* rather than a lifecycle step, emitted on
every election timeout by a node whose term has reached the plausibility ceiling and so can
never be elected again (#1886). Each carries the log
index or term and, where relevant, elapsed wall-time. A test collects into a list and
asserts on the sequence — see `MetricInstrumentationTest#proposeEmitsAcceptedThenCommittedThenApplied`
(shown in the [guide](https://tractat-us.github.io/kuilt/guide/testing.html)).

Metrics live in exactly two production modules: `:kuilt-raft` (consensus-internal,
callback-only, no exporter) and `:kuilt-otel` (the exportable, offline-first application
telemetry surface — `MetricKey`/`MetricKind`/`WarpMetricExporter`, documented under
[Device to dashboard](https://tractat-us.github.io/kuilt/guide/observability.html)). Seam,
Room, and core carry no metric types.

### Traces — the `trace` flow and `dumpState`

`RaftTraceEvent` is a sealed interface exposed on the node as a hot
`Flow<RaftTraceEvent>` (`node.trace`), with the event vocabulary following the etcd TLA+
action names so a captured trace can be replayed through the standard-raft TLA+ spec.
Every event carries a monotonic logical `clock`, which is what lets a test assert
ordering (`ClientRequest` before `AdvanceCommitIndex`).

One variant is deliberately not a state transition, and so has no TLA+ action to
correspond to: `FrameRefused(clock, node, from, messageType, gate)` reports a frame an
inbound guard **refused**, naming the guard through the `RefusalGate` enum.
It exists because a guard refuses by returning, so its only other observable is the
*absence* of a state change — which several guards produce identically, leaving a test
that asserts only state effects unable to say which one fired. Assert attribution
(`gate`) rather than "term unchanged, still a Follower" whenever more than one guard
could refuse the frame under test. Filter it out before replaying a trace through the
TLA+ spec.

The harness turns traces into a **failure diagnostic**: each `await*` helper, on timeout
or on excess election churn, throws `AssertionError(dumpState(...))`. `dumpState` renders
per-node `role/term/commitIndex`, the log index range, and a
`Timeout=/BecomeLeader=/BecomeFollower=` histogram from a bounded per-node ring buffer of
trace events, then the last events for the worst-off node — so a stuck cluster names the
thrashing peer and shows what it was doing. You rarely call `dumpState` yourself; you let
the awaits call it.

A multi-node test that both asserts on captured traces *and* leans on the harness's
dump-on-failure:

<!-- verbatim from kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ChaosTest.kt#termStability_partitionedFollowerNeverDeposesLeader -->

```kotlin
    @Test fun termStability_partitionedFollowerNeverDeposesLeader() = raftRunTest {
        val sim = raftSim(backgroundScope, backgroundScope, n = 3)

        repeat(3) { round ->
            // Re-confirm (or elect) the current leader at the start of every round.
            val leader = awaitLeader(sim)
            val leaderId = sim.nodes.entries.first { it.value === leader }.key
            val isolated = sim.nodeIds.first { it != leaderId }

            val leaderTrace = mutableListOf<RaftTraceEvent>()
            val isolatedTrace = mutableListOf<RaftTraceEvent>()
            val leaderTraceJob   = backgroundScope.launch { sim.nodes.getValue(leaderId).trace.collect { leaderTrace += it } }
            val isolatedTraceJob = backgroundScope.launch { sim.nodes.getValue(isolated).trace.collect { isolatedTrace += it } }

            // Isolate one follower; leader + third node hold quorum and can still commit.
            sim.partitionOff(isolated)
            val proposalIndex = leader.propose(byteArrayOf(round.toByte())).index
            sim.awaitCommit(proposalIndex, on = setOf(leaderId))

            // Let the isolated node fire many election timeouts (electionTimeoutMax = 10 ms).
            // Pre-vote probes will all fail (no quorum), so Timeout must never fire.
            delay(80)

            sim.heal()
            sim.awaitCommit(proposalIndex, on = setOf(isolated))

            leaderTraceJob.cancel()
            isolatedTraceJob.cancel()

            // Invariant 1: healthy leader was never deposed by a partitioned voter.
            assertTrue(
                leaderTrace.none { it is RaftTraceEvent.BecomeFollower },
                "Round $round: healthy leader $leaderId was deposed — term inflation from isolated $isolated. " +
                    "leaderTrace=${leaderTrace.takeLast(8)}"
            )
            // Invariant 2: pre-vote blocked every real election on the isolated node.
            val realElectionAttempts = isolatedTrace.filterIsInstance<RaftTraceEvent.Timeout>()
            assertTrue(
                realElectionAttempts.isEmpty(),
                "Round $round: isolated $isolated bumped its real term ${realElectionAttempts.size} time(s) — " +
                    "pre-vote should have blocked all of them. events=${realElectionAttempts}"
            )
            sim.checkInvariants()
        }
    }
```

### Logging — the real sink that survives a clock freeze

The engine logs through kotlin-logging (`private val logger = KotlinLogging.logger(...)`),
and `:kuilt-raft` declares logback as a JVM/Android runtime dependency, so those
`[raft:<id>] …` lines print to the console during a plain `./gradlew :kuilt-raft:jvmTest`
run. This is deliberately **not** routed through the trace flow: the trace ring buffer and
`dumpState` are virtual-time-gated — invisible while the clock is stalled — whereas the log
sink prints synchronously regardless of the clock.

That distinction is the diagnostic for one specific failure: `UncompletedCoroutinesError`
("the test body did not run to completion") **with no state dump**. The missing dump means
the `withTimeout` guard never fired, which only happens when virtual time stopped
advancing — an ungated hot re-dispatch loop in the engine re-enqueuing work at a single
instant. A loop merely gated by the heartbeat would still advance the clock and produce a
dump. So a no-dump timeout is a real logic bug, not host contention, and the log lines are
how you watch the spin.

To **capture** log output inside a test (assert on it, or write it as a CI artifact), kuilt
ships a first-class facility rather than a `logback-test.xml`:

- `:kuilt-otel-logging` — `installLogCapture(...)` routes kotlin-logging output into a
  durable buffer through a `CapturingAppender`; cross-platform.
- `:kuilt-otel-logback` — `KuiltLogbackAppender`, a real Logback `Appender` that captures
  every SLF4J logger on the JVM.

Both are documented, with the pull-off-a-device path, on
[Capturing logs](https://tractat-us.github.io/kuilt/guide/log-capture.html).

### Off-device capture — `:kuilt-otel-tap`

When the peer you need is an app on a phone or a CI simulator, `:kuilt-otel-tap` joins it
as a peer and reads its captured logs out over a fabric. The device calls
`installLogTap(...)` (opt-in, loopback-bound by default) to offer its on-device log buffer;
a harness uses `LogTapClient` to `pull()` a one-shot ordered snapshot or `tail()` a live
stream. Because the buffer is a CRDT (an ordered `Rga` of log records) replicated by
`:kuilt-quilter`, extraction is idempotent and order-preserving — a reconnecting puller
re-merges with no duplicates or loss. It's fabric-agnostic: loopback WebSocket for a
simulator, LAN or peer-to-peer for a real phone, same code. Full detail on
[Capturing logs](https://tractat-us.github.io/kuilt/guide/log-capture.html).

## Multi-room isolation — not yet

Tests that need several isolated rooms want an in-memory double that keeps each room's
traffic separate. A dedicated room-isolating double is being designed under issue
[#1172](https://github.com/tractat-us/kuilt/issues/1172); until it lands, use one
`InMemoryLoom` per room, or `MuxSeam` to separate logical channels over one fabric.

## See also

- **[Coroutine test determinism](testing-coroutine-determinism.md)** — the dispatcher and
  virtual-time rules underneath every harness here.
- **[Extending fabrics](extending-fabrics.md)** — writing a new fabric and proving it with
  the conformance suite.
