@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The worked example for the Testing guide (`docs/testing.md`, `Writerside/topics/testing.md`):
 * one `propose` round-trip on a single-voter node with **all three observability channels wired
 * on at once**, so a failure explains itself without a re-run.
 *
 *  - **Metrics** — the `onMetric` hook captures the [RaftMetric] lifecycle
 *    (`ProposeAccepted` → `ProposeCommitted` → `ProposeApplied`).
 *  - **Traces** — [RaftNode.trace] is collected into a list, recording every [RaftTraceEvent]
 *    state transition (`ClientRequest`, `AdvanceCommitIndex`, …).
 *  - **Logging** — the engine's kotlin-logging `debug` sink prints its `[raft:solo] …` lines
 *    through the JVM/Android logback backend. Unlike the trace flow, the log sink stays visible
 *    even if virtual time stalls — the failure mode that once hid a hot re-dispatch loop.
 *
 * A single voter is used deliberately: it self-elects immediately, so the example converges with
 * no split-vote ceremony and stays a clean illustration of *wiring*, not of consensus. When an
 * assertion fails, the captured `metrics` and `traces` are embedded in the message, turning a red
 * test into a self-describing report.
 */
class ObservabilityWalkthroughTest {

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
}
