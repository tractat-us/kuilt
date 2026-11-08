package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.CausalMap
import us.tractat.kuilt.crdt.DotFun
import us.tractat.kuilt.crdt.DotMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

private typealias Doc = CausalMap<String, DotMap<String, DotFun<Int>>>

/**
 * `CausalMap` over MV registers two levels deep, every sample built through `apply` and `remove`
 * so each is a state the type can reach.
 *
 * The retirement shape is the one #3126 is about: `poll` written by A, removed, and recreated by
 * A. The recreated write mints a fresh dot from the shared context rather than reusing the one the
 * remove retired.
 *
 * The suite's guidance is to re-assert under a *different* author, so the retired value cannot
 * dominate the new one. Re-asserting under the same author is the point here, and it is not
 * dominated: the shared context makes the recreated write mint `(A,2)`, not `(A,1)` again.
 */
internal class CausalMapConformanceTest : QuiltedConformanceSuite<Doc>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    private fun Doc.write(replica: ReplicaId, outer: String, field: String, value: Int): Doc =
        piece { doc ->
            doc.apply(replica, outer, DotMap()) { slot ->
                val register = slot.narrow(slot.store.entries[field] ?: DotFun())
                CausalMap.Change(
                    DotMap(mapOf(field to DotFun(mapOf(register.mint() to value)))),
                    retired = register.store.dots,
                )
            }
        }

    private val empty: Doc = CausalMap.empty()
    private val asserted = empty.write(a, "poll", "votes", 3)
    private val retired = asserted.piece { it.remove("poll") }
    private val reAsserted = retired.write(a, "poll", "votes", 5)

    /** B's concurrent vote on the poll A then removes: survives the remove, alone. */
    private val votedByB = asserted.write(b, "poll", "votes", 4)

    override fun samples(): List<Doc> = listOf(
        empty,
        asserted,
        retired,
        reAsserted,
        votedByB,
        empty.write(b, "note", "question", 1),
        votedByB.write(b, "poll", "question", 2),
    )

    override val retirementIsMeaningful: Boolean get() = true

    override fun retirementReAssertion(): RetirementReAssertion<Doc> =
        RetirementReAssertion(
            subject = "key \"poll\"",
            asserted = asserted,
            retired = retired,
            reAsserted = reAsserted,
            shows = { "poll" in it.keys },
        )
}
