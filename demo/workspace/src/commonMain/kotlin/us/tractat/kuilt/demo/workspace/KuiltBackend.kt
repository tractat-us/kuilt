package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.quilter.RgaGcCoordinator
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.FaultyLoom
import us.tractat.kuilt.test.FaultySeam
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

/**
 * The kuilt design: no server. Every actor keeps its own replica of one shared log, an
 * `Rga<WorkspaceEntry>` replicated by a [Quilter] (with an [RgaGcCoordinator] for compaction) over
 * `FaultyLoom(InMemoryLoom())`. Every input, every agent proposal and every accept is an element of
 * that log, so what a replica has received is exactly its delivered dots.
 *
 * **Edits and accepts.** An `Edit` or `Accept` is appended to its actor's own replica at once,
 * connected or not. While the actor is partitioned it counts as one `outageAction`, and as served
 * when the entry is then visible in that actor's own replica, which is checked rather than assumed.
 * An accept is a real [WorkspaceEntry.Accept] entry, so an offline choice shows on its chooser's
 * phone the way an offline edit does.
 *
 * **Partitions.** `Partition(actor)` drops every frame in and out of that actor's seam;
 * `Reconnect(actor)` restores the actor's own fault profile from [faults] (`Healthy` unless a test
 * says otherwise), rather than forcing `Healthy`, so a lossy link stays lossy after an outage.
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
 * **Presentation timing.** A released proposal is presented to each actor when that actor's replica
 * first holds it, and is assessed with [Assessment.assess] against that replica's state at that
 * moment. The host holds it at once; a partitioned actor holds it after it reconnects. It is shown
 * as applicable only for [Verdict.Applicable]. [Verdict.NeedsReview] and [Verdict.Unknown] are both
 * flagged for review, and each counts one `humanPrompt`, but only when shown to a person: an actor
 * that hosts an agent in the scenario (`remote`) is shown the flag and never counted as asked.
 * A proposal is presented at most once per actor, however many times its entry is delivered.
 *
 * `known` is every scenario input the presenter's replica has delivered, plus every input the actor
 * created itself (HYPOTHESES.md § Metrics). Proposals and accepts are never scenario inputs, so they
 * never appear in `known` or `basis`.
 *
 * **Accept conflicts.** When a person's replica holds accepts by different people for different
 * requests, those two accepts conflict. Each conflicting pair costs one `humanPrompt`, counted once
 * across the run however many replicas see it, the same accounting as the baseline's single prompt.
 *
 * **Final views** are each replica's entries minus proposals and accepts, so they compare with the
 * baseline's entry-only views.
 *
 * [advance] moves the replicas forward after each step. A test passes something that drives virtual
 * time, which keeps this class free of test-scheduler types; the replicas run on [scope].
 */
public class KuiltBackend(
    private val scope: CoroutineScope,
    private val advance: () -> Unit,
    private val faults: (ActorId) -> FaultProfile = { FaultProfile.Healthy },
    private val observer: KuiltRunObserver = KuiltRunObserver.None,
) : WorkspaceBackend {
    override suspend fun run(scenario: Scenario): RunResult {
        // One child job per run, so a run's replicas stop when it returns instead of outliving it.
        val job = SupervisorJob(scope.coroutineContext[Job])
        try {
            return KuiltRun(scenario, scope + job, advance, faults, observer).execute()
        } finally {
            job.cancel()
        }
    }

    public companion object {
        /** The replicas' anti-entropy cadence, so a test can drive enough rounds for a reconnect to converge. */
        public val antiEntropyInterval: Duration = QuilterConfig().antiEntropyInterval
    }
}

private val config = QuilterConfig(expectVirtualTime = true)
private val messageSerializer = QuiltMessage.serializer(Rga.wireSerializer(WorkspaceEntry.serializer()))

/** True for an input an agent can receive; false for an agent's output or a person's accept. */
private fun isInput(entry: WorkspaceEntry): Boolean = when (entry) {
    is WorkspaceEntry.Report, is WorkspaceEntry.PreferenceSet, is WorkspaceEntry.Note -> true
    is WorkspaceEntry.AgentProposal, is WorkspaceEntry.Accept -> false
}

private class Replica(val id: ReplicaId, val seam: FaultySeam, val quilter: Quilter<Rga<WorkspaceEntry>>) {
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

    fun holds(id: RgaId): Boolean = log.entries().any { it.first == id }
}

private class OpenRun(val host: ActorId, val pending: Proposal.Pending)

private class KuiltRun(
    private val scenario: Scenario,
    private val scope: CoroutineScope,
    private val advance: () -> Unit,
    private val faults: (ActorId) -> FaultProfile,
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
    private val presented = mutableSetOf<Pair<ActorId, RequestId>>()
    private val conflicts = mutableSetOf<Set<WorkspaceEntry.Accept>>()

    private val presentations = mutableListOf<Presentation>()
    private var agentRuns = 0
    private var humanPrompts = 0
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
            presentNew()
            countConflicts()
        }
        return RunResult(
            presentations = presentations.toList(),
            finalViews = scenario.actors.associateWith { actor -> replica(actor).log.entries().map { it.second }.filter(::isInput) },
            agentRuns = agentRuns,
            reruns = emptyList(),
            humanPrompts = humanPrompts,
            outageActions = outageActions,
            outageActionsServed = outageActionsServed,
            rejectedReplies = rejectedReplies,
        )
    }

    /** One replicator and compaction coordinator per actor, as `RgaGcCoordinator3PeerIntegrationTest` wires them. */
    private suspend fun wire() {
        val loom = FaultyLoom(InMemoryLoom(), scope)
        scenario.actors.forEachIndexed { index, actor ->
            val seam = if (index == 0) loom.host(Pattern("workspace-${scenario.name}")) else loom.join(InMemoryTag(actor.value))
            seam.setFaultProfile(faults(actor))
            val id = ReplicaId(seam.selfId.value)
            val quilter = Quilter(
                replica = id,
                seam = seam,
                initial = Rga.empty(),
                messageSerializer = messageSerializer,
                scope = scope,
                config = config,
                // Seeded per actor so anti-entropy's peer choice is the same on every run.
                random = Random(index),
            )
            RgaGcCoordinator(
                state = quilter.state,
                cutFrontier = quilter.cutFrontier,
                delivered = quilter.deliveredLocal,
                applyCompaction = { patch -> quilter.apply(patch) },
                scope = scope,
            )
            replicas[actor] = Replica(id, seam, quilter)
        }
    }

    private fun replica(actor: ActorId): Replica = replicas.getValue(actor)

    private fun step(step: Step) {
        when (step) {
            is Step.Edit -> {
                val id = replica(step.actor).append(step.entry)
                inputKeys[id.dot] = step.key
                created.getOrPut(step.actor) { mutableSetOf() } += step.key
                outageAction(step.actor, id)
            }
            is Step.Accept -> {
                val chooser = replica(step.actor)
                require(chooser.log.entries().any { (_, e) -> e is WorkspaceEntry.AgentProposal && e.request.value == step.request }) {
                    "${step.actor} accepts ${step.request}, which its replica does not hold"
                }
                outageAction(step.actor, chooser.append(WorkspaceEntry.Accept(step.actor, RequestId(step.request))))
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
                replica(step.actor).seam.partition()
            }
            is Step.Reconnect -> {
                require(partitioned.remove(step.actor)) { "${step.actor} reconnects without having been partitioned" }
                replica(step.actor).seam.setFaultProfile(faults(step.actor))
            }
        }
    }

    private fun outageAction(actor: ActorId, id: RgaId) {
        if (actor !in partitioned) return
        outageActions += 1
        if (replica(actor).holds(id)) outageActionsServed += 1
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
        // Complete on an empty recommendation first, only to read the finished basis back.
        val basisKeys = keysOf(run.pending.complete(Recommendation(null)).basis.allDots)
        val recommendation = ScriptedAgent.recommend(order.filter { it in basisKeys }.map { scenario.inputs.getValue(it) })
        val proposal = run.pending.complete(recommendation)
        observer.released(proposal)
        replica(run.host).append(proposal.toEntry(run.host))
    }

    private fun keysOf(dots: Set<Dot>): Set<InputKey> = dots.mapTo(mutableSetOf()) { dot ->
        requireNotNull(inputKeys[dot]) { "basis names $dot, which no scenario input created" }
    }

    /** Presents each proposal to each actor the first time that actor's replica holds it. */
    private fun presentNew() {
        for ((actor, replica) in replicas) {
            val log = replica.log
            for ((_, entry) in log.entries()) {
                if (entry !is WorkspaceEntry.AgentProposal || !presented.add(actor to entry.request)) continue
                val proposal = entry.toProposal()
                val verdict = Assessment.assess(proposal, log)
                presentations += Presentation(
                    actor = actor,
                    request = entry.request.value,
                    recommendation = proposal.recommendation,
                    basis = keysOf(proposal.basis.allDots),
                    known = deliveredInputs(log) + created[actor].orEmpty(),
                    shownAsApplicable = verdict == Verdict.Applicable,
                    unknown = verdict is Verdict.Unknown,
                )
                if (verdict != Verdict.Applicable && actor !in agentHosts) humanPrompts += 1
            }
        }
    }

    /** Every scenario input [log] has delivered, by the one delivery rule: in its dots or under its floor. */
    private fun deliveredInputs(log: Rga<WorkspaceEntry>): Set<InputKey> {
        val dots = log.causalDots()
        val floor = log.causalFloor()
        return inputKeys.filterKeys { it in dots || floor.contains(it) }.values.toSet()
    }

    /** One prompt per pair of accepts, by different people for different requests, that a person's replica holds. */
    private fun countConflicts() {
        for ((actor, replica) in replicas) {
            if (actor in agentHosts) continue
            val accepts = replica.log.entries().map { it.second }.filterIsInstance<WorkspaceEntry.Accept>()
            for (a in accepts) for (b in accepts) {
                if (a.by != b.by && a.request != b.request && conflicts.add(setOf(a, b))) humanPrompts += 1
            }
        }
    }
}
