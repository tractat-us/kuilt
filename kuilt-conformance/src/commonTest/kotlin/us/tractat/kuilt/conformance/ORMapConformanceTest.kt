package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/** ORMap is the add-wins map lattice — it obeys every law. */
internal class ORMapConformanceTest : QuiltedConformanceSuite<ORMap<String, GCounter>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<ORMap<String, GCounter>> {
        val base = ORMap.empty<String, GCounter>()
        val withVotes = base.piece { it.put(a, "votes", GCounter.of(a to 1L)) }
        val withVotesAndPoll = withVotes.piece { it.put(b, "poll", GCounter.of(b to 1L)) }
        return listOf(
            base,
            withVotes,
            withVotes.piece { it.remove("votes") },
            withVotesAndPoll,
            withVotesAndPoll.piece { it.remove("poll") },
        )
    }
}
