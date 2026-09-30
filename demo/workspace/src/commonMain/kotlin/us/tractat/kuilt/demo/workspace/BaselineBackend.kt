package us.tractat.kuilt.demo.workspace

/**
 * The honest comparator: one server owns the model, guards it with a version per key scope, and
 * holds an offline client's edits in a queue until it reconnects. Per-scope versions are the
 * strongest simple design, so wherever they solve a problem this backend gets the credit.
 *
 * **The model.** The server holds `entries: List<Pair<InputKey, WorkspaceEntry>>` and a version per
 * scope: `venue:<id>` for reports and notes about a venue, `budget` for preferences, and `plan` for
 * accepted proposals. Entries are only ever appended, so a venue or budget scope's version is the
 * number of entries in it; `plan` counts accepts.
 *
 * **Edits and accepts.** A connected client's `Edit` or `Accept` applies at once and bumps its
 * scope. A partitioned client's goes into its offline queue with the scope version it last saw and
 * counts as one `outageAction`, an `Accept` exactly like an `Edit`. With `optimisticLocal = false`
 * it is **not served**: the server owns the model, so nothing shows even locally until reconnect.
 * With `optimisticLocal = true` the client shows it locally at once, and it is served.
 * The version a queued write "last saw" includes the actor's own earlier queued writes to the same
 * scope, so two offline edits by one person never conflict with each other.
 *
 * **Reconnect.** The queue replays in order. A write whose scope version is unchanged applies;
 * otherwise it is a conflict, `humanPrompts += 1`, and it applies after the prompt, since the
 * scripted human accepts. The actor then syncs to the server's set.
 *
 * **Agents.** `StartAgent` records the inputs the host can see minus `selectExcluding`, and
 * `basedOn`, the version of `budget` and of every `venue:v` *as the agent received them*: an input
 * the selection left out is not counted, so the check below catches it. A `ToolResult` joins the
 * agent's basis only; it is not a workspace edit. The agent reads its inputs in scenario order, as
 * the oracle does.
 *
 * **The version check.** A result depends on `{budget, venue:<recommended>}` (every scope, if it
 * recommends nothing). If any of those moved since `basedOn`, the server rejects the result and
 * reruns the agent on the current server set plus its tool results, with no exclusion
 * (`agentRuns += 1`, one `reruns` entry). The rerun's result is the proposal of record and carries
 * the current versions. The check runs at release, and again whenever a deferred presentation is
 * delivered (below). It never prompts: a rerun is the baseline's way of not showing a stale answer.
 *
 * **Presentation timing.** A released proposal is presented, as applicable, to every scenario actor
 * connected at release, `remote` included. A partitioned actor gets it when it reconnects, after its
 * queue replays: the check runs again against the server as it is then, and a rerun it triggers is
 * pushed to every connected actor, since the proposal they saw has been replaced. `known` for an
 * actor is what it has seen (the server's set if connected, its last sync if not) **plus every input
 * it created itself, served or not**.
 *
 * **Final views.** A connected actor's view, and always `remote`'s, is the server's entries. A still
 * partitioned actor's is its last sync, plus its queued edits under `optimisticLocal`.
 *
 * [run] is `suspend` only to match [WorkspaceBackend]: it is a plain sequential interpreter.
 */
public class BaselineBackend(private val optimisticLocal: Boolean = false) : WorkspaceBackend {
    override suspend fun run(scenario: Scenario): RunResult = Interpreter(scenario, optimisticLocal).execute()
}

private sealed interface Scope {
    data object Budget : Scope
    data object Plan : Scope
    data class Venue(val id: VenueId) : Scope
}

private val agentScopes: List<Scope> = listOf(Scope.Budget) + Fixtures.venues.map { Scope.Venue(it.id) }

private fun scopeOf(entry: WorkspaceEntry): Scope = when (entry) {
    is WorkspaceEntry.Report -> Scope.Venue(entry.venue)
    is WorkspaceEntry.Note -> Scope.Venue(entry.venue)
    is WorkspaceEntry.PreferenceSet -> Scope.Budget
}

/** The scopes a recommendation depends on. Recommending nothing depends on everything. */
private fun dependsOn(rec: Recommendation): List<Scope> =
    rec.venue?.let { listOf(Scope.Budget, Scope.Venue(it)) } ?: agentScopes

private fun versionIn(scope: Scope, entries: Collection<WorkspaceEntry>): Int = entries.count { scopeOf(it) == scope }

private sealed interface Queued {
    data class Edit(val key: InputKey, val entry: WorkspaceEntry, val seen: Int) : Queued
    data class Accept(val request: String, val seen: Int) : Queued
}

private data class Snapshot(val entries: List<Pair<InputKey, WorkspaceEntry>>, val planVersion: Int)

private class Capture(val host: ActorId, val seen: List<Pair<InputKey, WorkspaceEntry>>) {
    val toolResults = mutableListOf<Pair<InputKey, WorkspaceEntry>>()
    val basedOn: Map<Scope, Int> = agentScopes.associateWith { s -> versionIn(s, seen.map { it.second }) }
}

private data class Proposal(
    val request: String,
    val recommendation: Recommendation,
    val basis: Set<InputKey>,
    val basedOn: Map<Scope, Int>,
    val toolResults: List<Pair<InputKey, WorkspaceEntry>>,
)

private class Interpreter(private val scenario: Scenario, private val optimisticLocal: Boolean) {
    private val order: List<InputKey> = scenario.inputs.keys.toList()

    private val server = mutableListOf<Pair<InputKey, WorkspaceEntry>>()
    private var planVersion = 0

    /** Partitioned actors, each with what it had when it dropped off. */
    private val partitioned = mutableMapOf<ActorId, Snapshot>()
    private val queues = mutableMapOf<ActorId, MutableList<Queued>>()
    private val created = mutableMapOf<ActorId, MutableSet<InputKey>>()
    private val captures = mutableMapOf<String, Capture>()
    private val released = mutableMapOf<String, Proposal>()
    private val deferred = mutableMapOf<ActorId, MutableList<String>>()

    private val presentations = mutableListOf<Presentation>()
    private var agentRuns = 0
    private val reruns = mutableListOf<Pair<Recommendation, Recommendation>>()
    private var humanPrompts = 0
    private var outageActions = 0
    private var outageActionsServed = 0

    fun execute(): RunResult {
        scenario.steps.forEach(::step)
        return RunResult(
            presentations = presentations.toList(),
            finalViews = scenario.actors.associateWith(::finalView),
            agentRuns = agentRuns,
            reruns = reruns.toList(),
            humanPrompts = humanPrompts,
            outageActions = outageActions,
            outageActionsServed = outageActionsServed,
        )
    }

    private fun step(step: Step) {
        when (step) {
            is Step.Edit -> edit(step)
            is Step.Accept -> accept(step)
            is Step.StartAgent -> {
                require(step.request !in captures && step.request !in released) { "request ${step.request} started twice" }
                val seen = view(step.host).filter { it.first !in step.selectExcluding }
                captures[step.request] = Capture(step.host, seen)
                agentRuns += 1
            }
            is Step.ToolResult -> captures.getValue(step.request).toolResults += step.key to step.entry
            is Step.ReleaseAgent -> release(step.request)
            is Step.Partition -> {
                require(step.actor !in partitioned) { "${step.actor} is already partitioned" }
                partitioned[step.actor] = Snapshot(server.toList(), planVersion)
            }
            is Step.Reconnect -> reconnect(step.actor)
        }
    }

    private fun edit(step: Step.Edit) {
        created.getOrPut(step.actor) { mutableSetOf() } += step.key
        val offline = partitioned[step.actor]
        if (offline == null) {
            server += step.key to step.entry
            return
        }
        val scope = scopeOf(step.entry)
        val queue = queues.getOrPut(step.actor) { mutableListOf() }
        val ownEarlier = queue.count { it is Queued.Edit && scopeOf(it.entry) == scope }
        queue += Queued.Edit(step.key, step.entry, versionIn(scope, offline.entries.map { it.second }) + ownEarlier)
        outageAction()
    }

    private fun accept(step: Step.Accept) {
        require(step.request in released) { "${step.actor} accepts ${step.request}, which was never released" }
        val offline = partitioned[step.actor]
        if (offline == null) {
            planVersion += 1
            return
        }
        val queue = queues.getOrPut(step.actor) { mutableListOf() }
        queue += Queued.Accept(step.request, offline.planVersion + queue.count { it is Queued.Accept })
        outageAction()
    }

    private fun outageAction() {
        outageActions += 1
        if (optimisticLocal) outageActionsServed += 1
    }

    private fun release(request: String) {
        val capture = captures.remove(request) ?: error("request $request released without a StartAgent")
        require(capture.host !in partitioned) { "release from a partitioned host is not modelled" }
        val inputs = capture.seen + capture.toolResults
        val first = Proposal(
            request = request,
            recommendation = recommend(inputs),
            basis = inputs.map { it.first }.toSet(),
            basedOn = capture.basedOn,
            toolResults = capture.toolResults.toList(),
        )
        val proposal = versionCheck(first)
        released[request] = proposal
        for (actor in scenario.actors) {
            if (actor in partitioned) deferred.getOrPut(actor) { mutableListOf() } += request else present(actor, proposal)
        }
    }

    private fun reconnect(actor: ActorId) {
        require(partitioned.remove(actor) != null) { "$actor reconnects without having been partitioned" }
        for (queued in queues.remove(actor).orEmpty()) {
            when (queued) {
                is Queued.Edit -> {
                    if (versionIn(scopeOf(queued.entry), server.map { it.second }) != queued.seen) humanPrompts += 1
                    server += queued.key to queued.entry
                }
                is Queued.Accept -> {
                    if (planVersion != queued.seen) humanPrompts += 1
                    planVersion += 1
                }
            }
        }
        for (request in deferred.remove(actor).orEmpty()) {
            val before = released.getValue(request)
            val after = versionCheck(before)
            released[request] = after
            if (after === before) {
                present(actor, after)
            } else {
                scenario.actors.filter { it !in partitioned }.forEach { present(it, after) }
            }
        }
    }

    /** The version check. Returns [proposal] if its scopes are unmoved, else the rerun that replaces it. */
    private fun versionCheck(proposal: Proposal): Proposal {
        val current = server.map { it.second }
        val moved = dependsOn(proposal.recommendation).any { versionIn(it, current) != proposal.basedOn.getValue(it) }
        if (!moved) return proposal
        val inputs = server + proposal.toolResults
        val rerun = recommend(inputs)
        agentRuns += 1
        reruns += proposal.recommendation to rerun
        return proposal.copy(
            recommendation = rerun,
            basis = inputs.map { it.first }.toSet(),
            basedOn = agentScopes.associateWith { versionIn(it, current) },
        )
    }

    private fun present(actor: ActorId, proposal: Proposal) {
        presentations += Presentation(
            actor = actor,
            request = proposal.request,
            recommendation = proposal.recommendation,
            basis = proposal.basis,
            known = view(actor).map { it.first }.toSet() + created[actor].orEmpty(),
            shownAsApplicable = true,
        )
    }

    /** What [actor] has seen: the server's entries if connected, its last sync if not. */
    private fun view(actor: ActorId): List<Pair<InputKey, WorkspaceEntry>> = partitioned[actor]?.entries ?: server.toList()

    private fun finalView(actor: ActorId): List<WorkspaceEntry> {
        val offline = partitioned[actor] ?: return server.map { it.second }
        val local = if (optimisticLocal) queues[actor].orEmpty().filterIsInstance<Queued.Edit>().map { it.entry } else emptyList()
        return offline.entries.map { it.second } + local
    }

    private fun recommend(inputs: List<Pair<InputKey, WorkspaceEntry>>): Recommendation {
        val byKey = inputs.toMap()
        return ScriptedAgent.recommend(order.filter { it in byKey }.map { byKey.getValue(it) })
    }
}
