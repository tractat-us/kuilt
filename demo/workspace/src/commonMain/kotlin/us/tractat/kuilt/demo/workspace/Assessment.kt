package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Rga

/** What one replica can say about a late proposal, judged against what that replica holds. */
public sealed interface Verdict {
    /** Nothing this replica holds, beyond the basis, would change the pick under the relevance policy. */
    public data object Applicable : Verdict

    /** This replica holds inputs the agent never received, and the relevance policy says they matter. */
    public data class NeedsReview(val relevantMissing: Set<Dot>) : Verdict

    /**
     * The agent received inputs this replica has not: it cannot judge a basis it does not hold.
     * Missing evidence yields unknown, never proof of absence (HYPOTHESES.md § Trust boundary).
     */
    public data class Unknown(val undelivered: Set<Dot>) : Verdict
}

public object Assessment {
    /**
     * Judges [p] against [known], one replica's log, in this order:
     * 1. A basis dot [known] has not delivered makes the verdict [Verdict.Unknown]. Delivery is
     *    `d in causalDots() || causalFloor().contains(d)` and nothing else, so an input that was
     *    removed, or removed and compacted away, still counts as delivered, and no basis dot is
     *    ever looked up as a live entry.
     * 2. Otherwise the missing inputs are [known]'s live entries whose dots are not in the basis.
     *    Another agent's proposal is an output, not an input, so it is never missing. An `Accept`
     *    is left to the relevance policy, which never counts one.
     * 3. If any missing input is relevant to [p]'s recommendation, [Verdict.NeedsReview] names them;
     *    otherwise [Verdict.Applicable].
     */
    public fun assess(p: Proposal, known: Rga<WorkspaceEntry>): Verdict {
        val delivered = known.causalDots()
        val floor = known.causalFloor()
        val basis = p.basis.allDots
        val undelivered = basis.filterNotTo(mutableSetOf()) { it in delivered || floor.contains(it) }
        if (undelivered.isNotEmpty()) return Verdict.Unknown(undelivered)
        val relevant = known.entries()
            .filter { (id, entry) -> id.dot !in basis && entry !is WorkspaceEntry.AgentProposal }
            .filter { (_, entry) -> Relevance.isRelevant(entry, p.recommendation) }
            .mapTo(mutableSetOf()) { it.first.dot }
        return if (relevant.isEmpty()) Verdict.Applicable else Verdict.NeedsReview(relevant)
    }
}
