# Testing

kuilt moves messages between peers — phones, laptops, browsers, servers — that come
and go, drop off the network, and reconnect. Testing a library like that sounds like
it should need real devices and real Wi-Fi. It doesn't. Every test in kuilt runs
**in one process, in a fraction of a second**, with the passage of time under the
test's control — so "wait five seconds for an election" costs no real seconds at all.

The catch with tests that stand up a little network of peers is that when one *does*
fail, "it failed" is not much to go on. Which peer? Doing what? Waiting on whom? So
the second half of this page is about **turning on the instruments** — asking each
peer to record what it's doing as it runs, so a red test hands you the story instead
of a shrug.

You'll build up in three steps:

1. **[One fabric](#one-fabric)** — the smallest possible test: two peers, one message.
2. **[A whole cluster](#cluster)** — several peers electing a leader, still in one test.
3. **[With the instruments on](#instruments)** — metrics, traces, and logs, so a failure
   explains itself.

## Start with one fabric {id="one-fabric"}

A *fabric* is a way for peers to reach each other — a WebSocket, a LAN, a
peer-to-peer radio. kuilt has one built-in fabric that lives entirely in memory, the
`InMemoryLoom`, and it's all you need to test the message contract itself.

kuilt ships a ready-made checklist of everything a fabric must do — the
**conformance suite**. You don't write those tests; you *inherit* them. Point the
suite at your fabric by handing it a pair of peers, and every rule in the contract is
checked for you:

<!-- verbatim from kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/InMemoryLoomConformanceTest.kt -->

```kotlin
class InMemoryLoomConformanceTest : SeamConformanceSuite() {
    private val loom = InMemoryLoom()
    override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
}
```

That's the whole test class. `newLoomPair()` returns the two peers to wire together —
for the in-memory mesh the same instance plays both roles, so it's `loom to loom`. A
new fabric (a WebSocket, a TCP socket) returns two distinct peers wired to reach each
other, and passes the *same* suite. One checklist, every fabric.

## Time you control {id="virtual-time"}

kuilt's tests run on a **virtual clock**. Election timers, heartbeats, retry
back-offs — all the waiting a networked system does — happen on a clock the test
advances itself, so a test that exercises a 5-second timeout finishes in
milliseconds and gives the exact same answer on every machine.

Getting this right has a few rules (use a fixed, single-threaded scheduler; never ask
the clock to "run until everything's quiet" when the system has timers that never stop
re-arming). They're spelled out, with the reasoning, in
**[Coroutine test determinism](https://github.com/tractat-us/kuilt/blob/main/docs/testing-coroutine-determinism.md)**.
The harnesses below already encode those rules, so most of the time you just use them.

## A whole cluster in one test {id="cluster"}

Testing agreement between peers — who's the leader, is everyone's copy of the log the
same — means running several peers at once. kuilt gives you a harness that stands up a
cluster, wires an in-process network between the peers, and hands it to your test.
`raftSimTest` is the front door:

<!-- verbatim from kuilt-raft-test/src/commonTest/kotlin/us/tractat/kuilt/raft/test/MultiNodeRaftSimTest.kt#threeNodeCluster_electsStableLeader -->

```kotlin
    @Test
    fun threeNodeCluster_electsStableLeader() = raftSimTest(n = 3) { sim ->
        val leader = sim.awaitLeader()
        assertNotNull(leader)
        sim.checkInvariants()
    }
```

Three peers, elect a leader, check the cluster's invariants still hold — in a few
milliseconds of real time. `awaitLeader()` is one of a family of bounded **`await*`**
helpers: they nudge the virtual clock forward in small steps until the thing you're
waiting for happens, and — this is the point — if it *never* happens, they give up
quickly and print a full state dump instead of hanging.

A few rules keep cluster tests fast and honest, all handled for you by `raftSimTest`:

- **A tight timeout, not the default.** The harness caps the wait at 5 seconds, so a
  cluster that never agrees fails in seconds with a diagnostic, not after a minute of
  silence.
- **Never "run until idle."** A consensus engine's timers re-arm forever, so it's
  never idle — asking it to run until quiet would spin forever. The `await*` helpers
  advance time in bounded steps instead.
- **Each peer gets its own seeded randomness** so their election timers differ and one
  actually wins the race — otherwise every peer times out in lockstep and no leader
  ever emerges.

Reach for `raftSimTest` (from `:kuilt-raft-test`) any time a test needs more than one
consensus peer — never hand-roll a cluster. The full reasoning is in the
[technical companion](https://github.com/tractat-us/kuilt/blob/main/docs/testing.md).

## Turn on the instruments {id="instruments"}

Everything above tells you *whether* a test passed. When one fails, you want to know
*why* — and for that, every peer can narrate what it's doing through three channels:

- **Metrics** — a running count of the milestones that matter ("proposal committed").
- **Traces** — a blow-by-blow of every internal state change, in order.
- **Logs** — plain lines of text the engine writes as it runs.

### Metrics: the milestones {id="metrics"}

Hand a peer a callback and it will hand you back each milestone as it happens. Collect
them into a list and assert on the sequence:

<!-- verbatim from kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/MetricInstrumentationTest.kt#proposeEmitsAcceptedThenCommittedThenApplied -->

```kotlin
    @Test
    fun proposeEmitsAcceptedThenCommittedThenApplied() = raftRunTest {
        val metrics = mutableListOf<RaftMetric>()
        val config = FAST_RAFT_CONFIG.copy(expectVirtualTime = true)

        val self = NodeId("solo")
        val cluster = ClusterConfig(voters = setOf(self))
        val network = InMemoryRaftNetwork()
        val node = backgroundScope.raftNode(
            clusterConfig = cluster,
            transport = network.transport(self),
            storage = InMemoryRaftStorage(),
            raftConfig = config,
            onMetric = { metrics += it },
        )

        node.awaitLeadership()
        node.propose("hello".encodeToByteArray())

        val proposeMetrics = metrics.filterIsInstance<RaftMetric.ProposeAccepted>() +
            metrics.filterIsInstance<RaftMetric.ProposeCommitted>() +
            metrics.filterIsInstance<RaftMetric.ProposeApplied>()

        // Accepted must come before Committed which comes before Applied.
        val accepted = metrics.indexOfFirst { it is RaftMetric.ProposeAccepted && it.logIndex == 2L }
        val committed = metrics.indexOfFirst { it is RaftMetric.ProposeCommitted && it.logIndex == 2L }
        val applied = metrics.indexOfFirst { it is RaftMetric.ProposeApplied && it.logIndex == 2L }

        assertTrue(accepted >= 0, "ProposeAccepted not emitted; metrics=$metrics")
        assertTrue(committed >= 0, "ProposeCommitted not emitted; metrics=$metrics")
        assertTrue(applied >= 0, "ProposeApplied not emitted; metrics=$metrics")
        assertTrue(accepted < committed, "Accepted must precede Committed; metrics=$metrics")
        assertTrue(committed <= applied, "Committed must not follow Applied; metrics=$metrics")
    }
```

The `onMetric` callback fires synchronously, so it never misses an event — and because
each failure message ends with `metrics=$metrics`, a red assertion shows you the whole
sequence that led there.

### Traces: the blow-by-blow {id="traces"}

Where a metric is a milestone, a **trace** is every step in between — each vote, each
heartbeat, each commit — in order. A peer exposes them as a stream you collect:

<!-- verbatim from kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/TraceTest.kt#proposal_emits_ClientRequest_then_AdvanceCommitIndex -->

```kotlin
    @Test fun proposal_emits_ClientRequest_then_AdvanceCommitIndex() = raftRunTest {
        val sim = raftSim(this, backgroundScope)
        val leader = awaitLeader(sim)
        val events = mutableListOf<RaftTraceEvent>()
        val job = launch { leader.trace.collect { events.add(it) } }
        leader.propose(byteArrayOf(42))
        delay(20)
        job.cancel()

        val clientReqs = events.filterIsInstance<RaftTraceEvent.ClientRequest>()
        val advances = events.filterIsInstance<RaftTraceEvent.AdvanceCommitIndex>()
        assertTrue(clientReqs.isNotEmpty(), "Expected ClientRequest event")
        assertTrue(advances.isNotEmpty(), "Expected AdvanceCommitIndex event")
        // ClientRequest must precede AdvanceCommitIndex
        val firstReq = clientReqs.minBy { it.clock }
        val firstAdvance = advances.minBy { it.clock }
        assertTrue(
            firstReq.clock < firstAdvance.clock,
            "ClientRequest(clock=${firstReq.clock}) must precede AdvanceCommitIndex(clock=${firstAdvance.clock})",
        )
    }
```

Each event carries a monotonic `clock`, so you can assert not just that things happened
but that they happened *in the right order*. The cluster harness keeps a rolling buffer
of these events per peer and prints them in its failure dump — which is why a stuck
cluster tells you *which* peer was thrashing and *what* it was doing.

### Logs: the running commentary {id="logs"}

The engine also writes plain log lines as it runs (`[raft:solo] election started for
term 2`, and so on). On JVM and Android these print through the standard logging
backend the moment they happen — which makes them the one channel that stays visible
even if the virtual clock stops advancing. If a test ever fails with a bare
"didn't finish in time" and *no* state dump, that's the tell: some internal loop
stopped letting the clock move, and the logs are how you see it.

To capture those log lines *inside* a test and assert on them — or to pull them off a
device that's already running — kuilt has a dedicated facility. See
**[Capturing logs](log-capture.md)**.

## A test that explains itself {id="worked"}

Here's all three channels on at once, on a single peer, for one proposal:

<!-- verbatim from kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ObservabilityWalkthroughTest.kt#proposeIsFullyObservable -->

```kotlin
    @Test
    fun proposeIsFullyObservable() = raftRunTest {
        val metrics = mutableListOf<RaftMetric>()
        val traces = mutableListOf<RaftTraceEvent>()

        val self = NodeId("solo")
        val cluster = ClusterConfig(voters = setOf(self))
        val network = InMemoryRaftNetwork()
        val node = backgroundScope.raftNode(
            clusterConfig = cluster,
            transport = network.transport(self),
            storage = InMemoryRaftStorage(),
            raftConfig = FAST_RAFT_CONFIG.copy(expectVirtualTime = true),
            onMetric = { metrics += it }, // channel 1: metrics — synchronous, never misses an event
        )
        // channel 2: traces — launched before awaitLeadership so the collector subscribes as
        // virtual time advances and captures the propose's transitions.
        backgroundScope.launch { node.trace.collect { traces += it } }
        // channel 3: logging — the engine's [raft:solo] debug lines print through the logback
        // backend on JVM/Android; nothing to wire here, it is always on.

        node.awaitLeadership()
        val entry = node.propose("ping".encodeToByteArray())

        // Metrics prove the propose lifecycle completed; the failure message carries the sequence.
        assertTrue(
            metrics.any { it is RaftMetric.ProposeCommitted && it.logIndex == entry.index },
            "no ProposeCommitted for index ${entry.index}; metrics=$metrics traces=$traces",
        )
        // Traces prove the state-machine transition that produced it.
        assertTrue(
            traces.any { it is RaftTraceEvent.ClientRequest && it.index == entry.index },
            "no ClientRequest trace for index ${entry.index}; metrics=$metrics traces=$traces",
        )
    }
```

**What a failure looks like.** Say a change breaks commit, and `ProposeCommitted`
never fires. The assertion fails with:

```
no ProposeCommitted for index 2; metrics=[ProposeAccepted(logIndex=2, term=1)] traces=[..., ClientRequest(clock=7, ...)]
```

Read left to right: the metrics show the proposal was *accepted* but never *committed*;
the traces show a `ClientRequest` went in but no commit came out. You already know the
failure is between accept and commit — before you've re-run anything. And scrolling up,
the engine's log lines show what each internal step was doing when it stalled. Three
channels, one read, no re-run.

## Reaching a device you can't attach to {id="off-device"}

Sometimes the peer you need to see isn't in your test process at all — it's an app on a
phone, or in a CI simulator, that you can't easily reach. kuilt can **join that running
peer as another peer and read its captured logs out over a fabric**, the same way any
two peers reconcile data. That's the `:kuilt-otel-tap` module; it's covered on
**[Capturing logs](log-capture.md)**.

## Testing several rooms at once {id="rooms"}

For tests that need multiple isolated *rooms* (peer groups that must not see each
other's traffic), a dedicated room-isolating in-memory double is being designed under
issue [#1172](https://github.com/tractat-us/kuilt/issues/1172). Until it lands, use one
`InMemoryLoom` per room, or the multiplexing transport to separate channels.

## Going deeper

- **[The technical companion](https://github.com/tractat-us/kuilt/blob/main/docs/testing.md)**
  — the harness types in full, the dump-on-failure mechanism, multi-node trace
  assertions, and off-device capture.
- **[Coroutine test determinism](https://github.com/tractat-us/kuilt/blob/main/docs/testing-coroutine-determinism.md)**
  — why virtual time works the way it does, and the rules that keep it deterministic.
