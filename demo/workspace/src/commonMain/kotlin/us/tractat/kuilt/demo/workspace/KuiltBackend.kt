package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.quilter.RgaGcCoordinator
import kotlin.random.Random
import kotlin.time.Duration

/**
 * A read-only window onto a [KuiltBackend] run, for tests that need to see what the backend did
 * rather than only what it reported. Both hooks default to doing nothing.
 */
public interface KuiltRunObserver {
    /** Called before [step] runs, with every actor's log as it stands after the previous step settled. */
    public fun beforeStep(step: Step, replicas: Map<ActorId, Rga<WorkspaceEntry>>) {}

    /** Called once for each proposal the backend accepts from an agent, before it is appended. */
    public fun released(proposal: Proposal) {}

    public companion object {
        public val None: KuiltRunObserver = object : KuiltRunObserver {}
    }
}

/** Switches one actor's network link off and on. What "off" drops, and what "on" restores, is the implementation's. */
public interface ActorLinks {
    /** [actor] drops off the network: nothing reaches it and nothing it sends gets out. */
    public fun cut(actor: ActorId)

    /** [actor] comes back, on whatever terms its link had before [cut]. */
    public fun restore(actor: ActorId)
}

/**
 * One run's network: a seam per actor, all in one session, plus the switch that cuts and restores
 * each actor's link. The seam and its switch come from one object because only the fabric knows
 * which link belongs to which actor.
 */
public interface WorkspaceNetwork : ActorLinks {
    /** Joins [actor] to the session and returns its seam. Called once per actor, in scenario actor order. */
    public suspend fun weave(actor: ActorId): Seam
}

/**
 * The kuilt design: no server. Every actor keeps its own replica of one shared log, an
 * `Rga<WorkspaceEntry>` replicated by a [Quilter] (with an [RgaGcCoordinator] for compaction) over
 * the seams a [WorkspaceNetwork] provides. Every input, every agent proposal and every accept is an
 * element of that log, so what a replica has received is exactly its delivered dots.
 *
 * **Edits and accepts.** An `Edit` or `Accept` is appended to its actor's own replica at once,
 * connected or not. While the actor is partitioned it counts as one `outageAction`, and as served
 * **by construction**: apply is local-first, so the entry is in the actor's own replica the moment
 * it is made, and that is what "served" means here. An accept is a real [WorkspaceEntry.Accept]
 * entry, so an offline choice shows on its chooser's phone the way an offline edit does.
 *
 * **Partitions.** `Partition(actor)` calls [ActorLinks.cut] and `Reconnect(actor)` calls
 * [ActorLinks.restore] on the run's network.
 *
 * **Convergence is checked, not assumed.** After every step's [advance], before anything is
 * presented, and again at the end of the run, every connected (non-partitioned) replica must have
 * delivered the same dots; otherwise [run] throws [IllegalStateException]. A shortfall in [advance]
 * would otherwise show up as fewer presentations, fewer conflicts and fewer prompts, scoring low
 * instead of failing. Checking only at a reconnect was not enough: a proposal presented to a
 * connected replica that still lagged would be judged against that lag, and the oracle would score
 * the same lagging `known`, while the end-of-run check still passed.
 *
 * **Agents.** `StartAgent` captures the host's replica through [InputCapture.capture], minus the
 * inputs `selectExcluding` names (matched by value, as the oracle matches entries) and minus
 * proposals and accepts, which are outputs and choices rather than inputs an agent receives. A
 * `ToolResult` is appended to the host's log and recorded with [Proposal.Pending.withToolResult], so
 * it has a dot like every other input. **Capture timing is this backend's obligation**: the
 * [Proposal] type fixes a basis once captured, but cannot stop a backend capturing late, at release
 * or through a tool result carrying later host updates. `KuiltBackendTest.capturedBasisIsTheHostStateAtStart`
 * pins it. The scripted agent reads its basis in scenario order, as the oracle does.
 *
 * `ReleaseAgent` completes the run and appends the proposal's wire form to the host's log. A release
 * for a request with no open run on the host (never started, or already released) is refused:
 * nothing is appended, and it counts in `rejectedReplies`.
 *
 * **Presentation timing** is the [Presenter]'s, run once after every settled step. A released
 * proposal is presented to each actor when that actor's replica first holds it, and is assessed with
 * [Assessment.assess] against that replica's state at that moment. The host holds it at once; a
 * partitioned actor holds it after it reconnects. It is shown as applicable only for
 * [Verdict.Applicable]. [Verdict.NeedsReview] and [Verdict.Unknown] are both flagged for review.
 * Each actor's **standing answer**, the latest-released proposal it has been shown, is then
 * re-judged against its current log on every settled step, and re-shown whenever the verdict on
 * screen changes (`RECHECK.md`, #2880). Entering a flagged state counts one `humanPrompt`, but only
 * for a person: an actor that hosts an agent in the scenario (`remote`) is shown the flag and never
 * counted as asked.
 *
 * **This backend never presents an Unknown.** Links are cut whole-actor, Quilter orders deltas per
 * sender, and the convergence check runs before every presentation, so a replica that holds a
 * proposal has delivered every dot the host held when it released it. A zero
 * `unknownPresentations` from this backend is therefore vacuous by construction, not evidence that
 * the verdict works; the end-to-end Unknown is pinned on a test mesh instead
 * (`AdversarialTraceTest.heldFramesLeaveTheProposalUnknown`).
 *
 * `known` is every scenario input the presenter's replica has delivered, plus every input the actor
 * created itself (HYPOTHESES.md § Metrics). Proposals and accepts are never scenario inputs, so they
 * never appear in `known` or `basis`.
 *
 * **Accept conflicts.** When a person's replica holds accepts by different people for different
 * requests, those two accepts conflict. Each conflicting pair costs one `humanPrompt`, counted once
 * across the run however many replicas see it. That coincides with the baseline's count on S4 but
 * is a different rule: the baseline prompts once per replayed accept whose plan version moved, so
 * the two can differ at three accepts. The prompt goes to no specific person; who settles the
 * conflict is application policy.
 *
 * **Final views** are each replica's entries minus proposals and accepts: the oracle compares Edit
 * entries, and this keeps the views comparable with the baseline's.
 *
 * [network] builds a fresh [WorkspaceNetwork] for each run on the run's scope. [advance] moves the
 * replicas forward after each step; a test passes something that drives virtual time, which keeps
 * this class free of test-scheduler types. The replicas run on [scope].
 */
public class KuiltBackend(
    private val scope: CoroutineScope,
    private val advance: () -> Unit,
    private val network: (CoroutineScope) -> WorkspaceNetwork,
    private val observer: KuiltRunObserver = KuiltRunObserver.None,
) : WorkspaceBackend {
    override suspend fun run(scenario: Scenario): RunResult {
        // One child job per run, so a run's replicas stop when it returns instead of outliving it.
        val job = SupervisorJob(scope.coroutineContext[Job])
        val runScope = scope + job
        try {
            return KuiltRun(scenario, runScope, network(runScope), advance, observer).execute()
        } finally {
            job.cancel()
        }
    }

    public companion object {
        internal val config = QuilterConfig(expectVirtualTime = true)

        /** The replicas' own anti-entropy cadence, so a test can drive enough rounds for a reconnect to converge. */
        public val antiEntropyInterval: Duration = config.antiEntropyInterval
    }
}

internal val messageSerializer = QuiltMessage.serializer(Rga.wireSerializer(WorkspaceEntry.serializer()))

/**
 * One actor's replica on [seam]: a [Quilter] over an empty `Rga<WorkspaceEntry>`, with an
 * [RgaGcCoordinator] for compaction, wired as `RgaGcCoordinator3PeerIntegrationTest` wires them.
 * The replicator uses [KuiltBackend.config] and a `Random([index])` seed, so anti-entropy's peer
 * choice is the same on every run. The one wiring every [KuiltBackend] run and every test built on
 * the same replicas share.
 */
internal fun wireReplica(seam: Seam, index: Int, scope: CoroutineScope): Quilter<Rga<WorkspaceEntry>> {
    val quilter = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = Rga.empty(),
        messageSerializer = messageSerializer,
        scope = scope,
        config = KuiltBackend.config,
        random = Random(index),
    )
    RgaGcCoordinator(
        state = quilter.state,
        cutFrontier = quilter.cutFrontier,
        delivered = quilter.deliveredLocal,
        applyCompaction = { patch -> quilter.apply(patch) },
        scope = scope,
    )
    return quilter
}

/** True for an input an agent can receive; false for an agent's output or a person's accept. */
private fun isInput(entry: WorkspaceEntry): Boolean = when (entry) {
    is WorkspaceEntry.Report, is WorkspaceEntry.PreferenceSet, is WorkspaceEntry.Note -> true
    is WorkspaceEntry.AgentProposal, is WorkspaceEntry.Accept -> false
}

/** The one delivery rule: a dot is delivered when it is in the log's dots or under its floor. */
internal fun Rga<WorkspaceEntry>.delivers(dot: Dot): Boolean = dot in causalDots() || causalFloor().contains(dot)

/** Every dot [this] has delivered is delivered by [other] too, whether held as a dot or under the floor. */
internal fun Rga<WorkspaceEntry>.deliveredWithin(other: Rga<WorkspaceEntry>): Boolean {
    val otherDots = other.causalDots()
    val otherFloor = other.causalFloor()
    fun delivered(dot: Dot) = dot in otherDots || otherFloor.contains(dot)
    return causalDots().all(::delivered) &&
        causalFloor().entries.all { (author, seq) -> (1L..seq).all { delivered(Dot(author, it)) } }
}

private class Replica(val id: ReplicaId, val quilter: Quilter<Rga<WorkspaceEntry>>) {
    val log: Rga<WorkspaceEntry> get() = quilter.state.value

    /** Appends [entry] at the end of this replica's log, under the replicator's lock, and returns its id. */
    fun append(entry: WorkspaceEntry): RgaId {
        var minted: RgaId? = null
        quilter.mutate { state ->
            val (_, op) = state.insertAt(id, state.size, entry)
            minted = op.id
            Patch(Rga.empty<WorkspaceEntry>().apply(op))
        }
        return checkNotNull(minted) { "mutate ran no transform" }
    }
}

private class OpenRun(val host: ActorId, val pending: Proposal.Pending)

private class KuiltRun(
    private val scenario: Scenario,
    private val scope: CoroutineScope,
    private val network: WorkspaceNetwork,
    private val advance: () -> Unit,
    private val observer: KuiltRunObserver,
) {
    private val order: List<InputKey> = scenario.inputs.keys.toList()
    private val agentHosts: Set<ActorId> = scenario.steps.filterIsInstance<Step.StartAgent>().mapTo(mutableSetOf()) { it.host }

    private val replicas = linkedMapOf<ActorId, Replica>()
    /** The scenario input each input dot carries. Only Edit and ToolResult steps create entries here. */
    private val inputKeys = linkedMapOf<Dot, InputKey>()
    private val created = mutableMapOf<ActorId, MutableSet<InputKey>>()
    private val partitioned = mutableSetOf<ActorId>()
    private val started = mutableSetOf<String>()
    private val open = mutableMapOf<String, OpenRun>()
    private val releaseOrder = mutableListOf<RequestId>()
    private val conflicts = mutableSetOf<Set<WorkspaceEntry.Accept>>()

    private val presenter = Presenter(
        isPerson = { it !in agentHosts },
        releaseRank = { request -> releaseOrder.indexOf(request).also { check(it >= 0) { "$request was never released" } } },
        basisKeys = { proposal -> keysOf(proposal.basis.allDots) },
        known = { actor, log -> deliveredInputs(log) + created[actor].orEmpty() },
    )
    private var agentRuns = 0
    private var conflictPrompts = 0
    private var outageActions = 0
    private var outageActionsServed = 0
    private var rejectedReplies = 0

    suspend fun execute(): RunResult {
        wire()
        advance()
        for (step in scenario.steps) {
            observer.beforeStep(step, replicas.mapValues { it.value.log })
            step(step)
            advance()
            // Before presenting: a presentation judged against a lagging replica would score that
            // lag, and the oracle would read the same lagging `known`.
            checkConverged("after ${describe(step)}")
            presenter.settle(replicas.mapValues { it.value.log })
            countConflicts()
        }
        checkConverged("at the end of the run")
        return RunResult(
            presentations = presenter.presentations,
            finalViews = scenario.actors.associateWith { actor -> replica(actor).log.entries().map { it.second }.filter(::isInput) },
            agentRuns = agentRuns,
            reruns = emptyList(),
            humanPrompts = presenter.humanPrompts + conflictPrompts,
            outageActions = outageActions,
            outageActionsServed = outageActionsServed,
            rejectedReplies = rejectedReplies,
        )
    }

    /** One replica per actor, through [wireReplica]. */
    private suspend fun wire() {
        scenario.actors.forEachIndexed { index, actor ->
            val quilter = wireReplica(network.weave(actor), index, scope)
            replicas[actor] = Replica(quilter.replica, quilter)
        }
    }

    private fun replica(actor: ActorId): Replica = replicas.getValue(actor)

    private fun describe(step: Step): String = when (step) {
        is Step.Reconnect -> "Reconnect(${step.actor.value})"
        is Step.Partition -> "Partition(${step.actor.value})"
        is Step.Edit -> "Edit(${step.key.value})"
        is Step.Accept -> "Accept(${step.actor.value}, ${step.request})"
        is Step.StartAgent -> "StartAgent(${step.request})"
        is Step.ToolResult -> "ToolResult(${step.key.value})"
        is Step.ReleaseAgent -> "ReleaseAgent(${step.request})"
    }

    /** Every connected replica has delivered the same dots, or the run fails here rather than scoring low. */
    private fun checkConverged(where: String) {
        val connected = replicas.filterKeys { it !in partitioned }
        val (first, reference) = connected.entries.firstOrNull()?.let { it.key to it.value.log } ?: return
        for ((actor, replica) in connected) {
            val log = replica.log
            check(log.deliveredWithin(reference) && reference.deliveredWithin(log)) {
                "${scenario.name}: connected replicas did not converge $where: ${actor.value} and ${first.value} " +
                    "hold different dots (${log.causalDots()} / ${log.causalFloor()} vs " +
                    "${reference.causalDots()} / ${reference.causalFloor()}). The advance step drove too few rounds."
            }
        }
    }

    private fun step(step: Step) {
        when (step) {
            is Step.Edit -> {
                val id = replica(step.actor).append(step.entry)
                inputKeys[id.dot] = step.key
                created.getOrPut(step.actor) { mutableSetOf() } += step.key
                outageAction(step.actor)
            }
            is Step.Accept -> {
                val chooser = replica(step.actor)
                require(chooser.log.entries().any { (_, e) -> e is WorkspaceEntry.AgentProposal && e.request.value == step.request }) {
                    "${step.actor} accepts ${step.request}, which its replica does not hold"
                }
                chooser.append(WorkspaceEntry.Accept(step.actor, RequestId(step.request)))
                outageAction(step.actor)
            }
            is Step.StartAgent -> start(step)
            is Step.ToolResult -> {
                val run = open[step.request] ?: error("tool result for ${step.request}, which has no open run")
                val host = replica(run.host)
                val id = host.append(step.entry)
                inputKeys[id.dot] = step.key
                open[step.request] = OpenRun(run.host, run.pending.withToolResult(host.log, setOf(id)))
            }
            is Step.ReleaseAgent -> release(step.request)
            is Step.Partition -> {
                require(partitioned.add(step.actor)) { "${step.actor} is already partitioned" }
                network.cut(step.actor)
            }
            is Step.Reconnect -> {
                require(partitioned.remove(step.actor)) { "${step.actor} reconnects without having been partitioned" }
                network.restore(step.actor)
            }
        }
    }

    /** Served by construction: the entry was just applied to the actor's own replica, locally first. */
    private fun outageAction(actor: ActorId) {
        if (actor !in partitioned) return
        outageActions += 1
        outageActionsServed += 1
    }

    private fun start(step: Step.StartAgent) {
        require(started.add(step.request)) { "request ${step.request} started twice" }
        val excluded = step.selectExcluding.map { scenario.inputs.getValue(it) }.toSet()
        val pending = InputCapture.capture(RequestId(step.request), replica(step.host).log) { entry ->
            !isInput(entry) || entry in excluded
        }
        open[step.request] = OpenRun(step.host, pending)
        agentRuns += 1
    }

    private fun release(request: String) {
        val run = open.remove(request)
        if (run == null) {
            rejectedReplies += 1
            return
        }
        val basisKeys = keysOf(run.pending.basis.allDots)
        val recommendation = ScriptedAgent.recommend(order.filter { it in basisKeys }.map { scenario.inputs.getValue(it) })
        val proposal = run.pending.complete(recommendation)
        observer.released(proposal)
        releaseOrder += proposal.request
        replica(run.host).append(proposal.toEntry(run.host))
    }

    private fun keysOf(dots: Set<Dot>): Set<InputKey> = dots.mapTo(mutableSetOf()) { dot ->
        requireNotNull(inputKeys[dot]) { "basis names $dot, which no scenario input created" }
    }

    /** Every scenario input [log] has delivered. */
    private fun deliveredInputs(log: Rga<WorkspaceEntry>): Set<InputKey> =
        inputKeys.filterKeys { log.delivers(it) }.values.toSet()

    /** One prompt per pair of accepts, by different people for different requests, that a person's replica holds. */
    private fun countConflicts() {
        for ((actor, replica) in replicas) {
            if (actor in agentHosts) continue
            val accepts = replica.log.entries().map { it.second }.filterIsInstance<WorkspaceEntry.Accept>()
            for (a in accepts) for (b in accepts) {
                if (a.by != b.by && a.request != b.request && conflicts.add(setOf(a, b))) conflictPrompts += 1
            }
        }
    }
}
