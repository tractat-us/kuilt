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
    /**
     * End-of-run scoring (HYPOTHESES.md § Metrics, amended 2026-10-01): the actors whose **standing
     * answer** is shown as applicable yet stale against everything that actor knows when the run
     * ends. See [Oracle.standingAnswers] for which presentation stands.
     */
    val staleAtEnd: Int,
    /**
     * Presentations flagged **needs review** where the rerun agrees. An unknown verdict is not a
     * relevance call and is not counted here (amended 2026-10-01); it is in [unknownPresentations].
     */
    val falseInvalidations: Int,
    val humanPrompts: Int,
    val outageActions: Int,
    val outageActionsServed: Int,
    /**
     * How many presentations carried an unknown verdict (the presenter had not received the whole
     * basis). Reported beside H1b as its own row: since the 2026-10-01 amendment an unknown is not
     * counted in [falseInvalidations], and it is never stale, since it is never shown as applicable.
     */
    val unknownPresentations: Int = 0,
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
            // Exhaustive on purpose: a new WorkspaceEntry must decide its own relevance.
            is WorkspaceEntry.Note -> false
            // A proposal is an agent's output, not an input it could have missed, so it is never a
            // relevance input.
            is WorkspaceEntry.AgentProposal -> false
            // An accept is a person's choice about a proposal, not a fact about a venue.
            is WorkspaceEntry.Accept -> false
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
        // Every presentation is checked against the scenario's inputs, whichever count it lands in.
        result.presentations.forEach { truth(scenario, it.basis, it.known) }
        val stale = result.presentations.count { p -> p.shownAsApplicable && truth(scenario, p.basis, p.known) != p.recommendation }
        val falseInvalidations = result.presentations.count { p ->
            !p.shownAsApplicable && !p.unknown && truth(scenario, p.basis, p.known) == p.recommendation
        }
        val staleAtEnd = standingAnswers(scenario, result).count { (actor, p) ->
            p.shownAsApplicable && truth(scenario, p.basis, knownAtEnd(scenario, result, actor)) != p.recommendation
        }
        val edits = scenario.steps.filterIsInstance<Step.Edit>()
        val preserved = edits.count { e -> scenario.actors.all { actor -> e.entry in result.finalViews.getValue(actor) } }
        return Metrics(
            editsMade = edits.size, editsPreserved = preserved,
            agentRuns = result.agentRuns,
            unnecessaryReruns = result.reruns.count { (before, after) -> before == after },
            staleTreatedAsCurrent = stale, staleAtEnd = staleAtEnd,
            falseInvalidations = falseInvalidations, humanPrompts = result.humanPrompts,
            outageActions = result.outageActions, outageActionsServed = result.outageActionsServed,
            unknownPresentations = result.presentations.count { it.unknown },
        )
    }

    /**
     * Each actor's **standing answer**: the latest proposal it has been shown. "Latest" is by the
     * scenario's `ReleaseAgent` order, so once `r2` is shown, `r2` stands over `r1` whenever each
     * reached the actor. Among presentations of the one request (a baseline rerun replaces its
     * proposal under the same request), the last one shown stands. An actor shown nothing has no
     * standing answer and no entry here.
     */
    public fun standingAnswers(scenario: Scenario, result: RunResult): Map<ActorId, Presentation> {
        val released = mutableMapOf<String, Int>()
        scenario.steps.forEachIndexed { i, step -> if (step is Step.ReleaseAgent) released.getOrPut(step.request) { i } }
        return result.presentations.withIndex()
            .groupBy { it.value.actor }
            .mapValues { (actor, shown) ->
                shown.maxWith(
                    compareBy<IndexedValue<Presentation>> { (_, p) ->
                        requireNotNull(released[p.request]) {
                            "${scenario.name}: ${actor.value} was shown ${p.request}, which no ReleaseAgent step returns"
                        }
                    }.thenBy { it.index },
                ).value
            }
    }

    /**
     * What [actor] knows when the run ends: every scenario input in its final view, plus every input
     * it created itself, served or not, as for `known` (HYPOTHESES.md § Metrics). Entries match by
     * value, with the same caveat as `editsPreserved`.
     */
    private fun knownAtEnd(scenario: Scenario, result: RunResult, actor: ActorId): Set<InputKey> {
        val view = result.finalViews.getValue(actor)
        val own = scenario.steps.filterIsInstance<Step.Edit>().filter { it.actor == actor }.map { it.key }
        return scenario.inputs.filterValues { it in view }.keys + own
    }

    /** Ground truth: the scripted agent rerun on [basis] ∪ [known], in scenario order. */
    private fun truth(scenario: Scenario, basis: Set<InputKey>, known: Set<InputKey>): Recommendation {
        val entries = scenario.inputs
        val unknown = (basis + known) - entries.keys
        require(unknown.isEmpty()) { "${scenario.name}: presentation names inputs the scenario never creates: $unknown" }
        return ScriptedAgent.recommend(entries.keys.filter { it in basis || it in known }.map { entries.getValue(it) })
    }
}
