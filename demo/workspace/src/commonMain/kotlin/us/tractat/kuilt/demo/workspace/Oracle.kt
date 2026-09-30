package us.tractat.kuilt.demo.workspace

/**
 * The metrics of `HYPOTHESES.md`, as counts over scripted actors in one run of one scenario. Never
 * user-study results. `missedInvalidations` has no field: in M0 it equals [staleTreatedAsCurrent]
 * for both backends and is not scored separately.
 */
public data class Metrics(
    val editsMade: Int,
    val editsPreserved: Int,
    val agentRuns: Int,
    val unnecessaryReruns: Int,
    val staleTreatedAsCurrent: Int,
    val falseInvalidations: Int,
    val humanPrompts: Int,
    val outageActions: Int,
    val outageActionsServed: Int,
)

/** The cheap relevance POLICY (HYPOTHESES.md §4). The Oracle never uses it; it scores ground truth. */
public object Relevance {
    public fun isRelevant(missing: WorkspaceEntry, rec: Recommendation): Boolean {
        val chosen = rec.venue?.let(Fixtures::venue)
        return when (missing) {
            is WorkspaceEntry.Report.Reopened -> true
            is WorkspaceEntry.Report -> missing.venue == chosen?.id
            is WorkspaceEntry.PreferenceSet ->
                chosen == null ||
                    missing.budget < chosen.pricePerHead ||
                    Fixtures.venues.any { it.walkMinutes < chosen.walkMinutes && it.pricePerHead <= missing.budget }
            // Exhaustive on purpose: a new WorkspaceEntry (AgentProposal, Task 6) must decide its own relevance.
            is WorkspaceEntry.Note -> false
        }
    }
}

public object Oracle {
    /**
     * Scores [result] against ground truth: the scripted agent rerun on basis ∪ known, in scenario order.
     *
     * `editsPreserved` compares entries by value, so two identical `Note`s would be indistinguishable.
     * No scenario creates two identical entries.
     */
    public fun score(scenario: Scenario, result: RunResult): Metrics {
        val missingViews = scenario.actors.toSet() - result.finalViews.keys
        require(missingViews.isEmpty()) { "${scenario.name}: no final view reported for $missingViews" }
        val entries: Map<InputKey, WorkspaceEntry> = scenario.inputs
        // Ground truth: rerun the deterministic agent on basis ∪ what the presenter knew, in scenario order.
        val order: List<InputKey> = entries.keys.toList()
        fun truth(p: Presentation): Recommendation {
            val unknown = (p.basis + p.known) - entries.keys
            require(unknown.isEmpty()) { "${scenario.name}: presentation names inputs the scenario never creates: $unknown" }
            return ScriptedAgent.recommend(order.filter { it in p.basis || it in p.known }.map { entries.getValue(it) })
        }
        val stale = result.presentations.count { p -> p.shownAsApplicable && truth(p) != p.recommendation }
        val falseInvalidations = result.presentations.count { p -> !p.shownAsApplicable && truth(p) == p.recommendation }
        val edits = scenario.steps.filterIsInstance<Step.Edit>()
        val preserved = edits.count { e -> scenario.actors.all { actor -> e.entry in result.finalViews.getValue(actor) } }
        return Metrics(
            editsMade = edits.size, editsPreserved = preserved,
            agentRuns = result.agentRuns,
            unnecessaryReruns = result.reruns.count { (before, after) -> before == after },
            staleTreatedAsCurrent = stale, falseInvalidations = falseInvalidations, humanPrompts = result.humanPrompts,
            outageActions = result.outageActions, outageActionsServed = result.outageActionsServed,
        )
    }
}
