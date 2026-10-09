package us.tractat.kuilt.raft.test.samples

import kotlinx.coroutines.test.TestResult
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.test.raftSimTest

public fun traceAssertions(): TestResult = raftSimTest { sim ->
    val leader = sim.awaitLeader()
    val id = sim.nodes.entries.first { it.value === leader }.key
    sim.settle()
    val elected = sim.assertTraced<RaftTraceEvent.BecomeLeader>(id)
    sim.assertNotTraced<RaftTraceEvent.BecomeLeader>(id) { it.term > elected.term }
    sim.assertTracedInOrder(id,
        { it is RaftTraceEvent.Timeout },
        { it is RaftTraceEvent.BecomeLeader && it.term == elected.term },
    )
    val snapshot = sim.traceOf(id)
    check(snapshot.isNotEmpty())
}
