package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ORMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/** ORMap is the add-wins map lattice — it obeys every law. */
internal class ORMapConformanceTest : QuiltedConformanceSuite<ORMap<String, GCounter>>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val c = ReplicaId("C")

    private val base = ORMap.empty<String, GCounter>()
    private val withVotes = base.piece { it.put(a, "votes", GCounter.of(a to 1L)) }
    private val votesRetired = withVotes.piece { it.remove("votes") }

    /**
     * C puts the key back after A retired it — and puts it back with a count **A's write does not
     * dominate**. A larger count under A would make a dropped contribution indistinguishable from a
     * kept one, because the join takes the max either way; a count under a different author makes
     * the difference visible. Against the pre-#2099 `ORMap` this one sample finds 12 associativity
     * violations where the other five find none.
     */
    private val votesReAsserted = votesRetired.piece { it.put(c, "votes", GCounter.of(c to 1L)) }

    private val withVotesAndPoll = withVotes.piece { it.put(b, "poll", GCounter.of(b to 1L)) }

    override fun samples(): List<ORMap<String, GCounter>> = listOf(
        base,
        withVotes,
        votesRetired,
        withVotesAndPoll,
        withVotesAndPoll.piece { it.remove("poll") },
        votesReAsserted,
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<ORMap<String, GCounter>> =
        RetirementReAssertion(
            subject = """key "votes"""",
            asserted = withVotes,
            retired = votesRetired,
            reAsserted = votesReAsserted,
            shows = { "votes" in it.keys },
        )
}
