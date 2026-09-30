package us.tractat.kuilt.demo.workspace

/** The id of the step that created an input. Tests and the oracle refer to inputs by it. */
public data class InputKey(val value: String)

/** One scripted step. Backend-neutral: every [WorkspaceBackend] replays the same list. */
public sealed interface Step {
    /** [actor] adds [entry] to the shared workspace; [key] names the input it creates. */
    public data class Edit(val key: InputKey, val actor: ActorId, val entry: WorkspaceEntry) : Step

    /**
     * The agent for [request] starts on [host] and captures what [host] has, minus [selectExcluding]:
     * inputs the host had but the agent's selection left out.
     */
    public data class StartAgent(
        val request: String,
        val host: ActorId,
        val selectExcluding: Set<InputKey> = emptySet(),
    ) : Step

    /** A tool call made by the agent for [request] returns [entry], an input named [key]. */
    public data class ToolResult(val request: String, val key: InputKey, val entry: WorkspaceEntry) : Step

    /** The agent for [request] returns its recommendation. */
    public data class ReleaseAgent(val request: String) : Step

    /** [actor] accepts the proposal from [request]. The scripted person's choice, not an app prompt. */
    public data class Accept(val actor: ActorId, val request: String) : Step

    /** [actor] drops off the network. */
    public data class Partition(val actor: ActorId) : Step

    /** [actor] comes back. */
    public data class Reconnect(val actor: ActorId) : Step
}

public data class Scenario(val name: String, val actors: List<ActorId>, val steps: List<Step>) {
    /** Every input the scenario creates, by the key of the step that created it, in scenario order. */
    public val inputs: Map<InputKey, WorkspaceEntry>

    init {
        val created = steps.mapNotNull {
            when (it) {
                is Step.Edit -> it.key to it.entry
                is Step.ToolResult -> it.key to it.entry
                is Step.StartAgent, is Step.ReleaseAgent, is Step.Accept, is Step.Partition, is Step.Reconnect -> null
            }
        }
        inputs = created.toMap()
        require(inputs.size == created.size) { "$name: an input key is created twice: ${created.map { it.first }}" }
        // A proposal is an agent's output and an accept is a person's choice (an Accept step); neither
        // is a scripted input. This is what keeps BaselineBackend's scopeOf from ever seeing one.
        val notInputs = created.filter { it.second is WorkspaceEntry.AgentProposal || it.second is WorkspaceEntry.Accept }.map { it.first }
        require(notInputs.isEmpty()) { "$name: an Edit or ToolResult carries an AgentProposal or Accept: $notInputs" }
        val stepActors = steps.mapNotNull {
            when (it) {
                is Step.Edit -> it.actor
                is Step.StartAgent -> it.host
                is Step.Accept -> it.actor
                is Step.Partition -> it.actor
                is Step.Reconnect -> it.actor
                is Step.ToolResult, is Step.ReleaseAgent -> null
            }
        }
        require(actors.containsAll(stepActors)) { "$name: a step names an actor outside $actors" }
    }
}

/** S1–S7 exactly as the step lists in `HYPOTHESES.md` give them. */
public object Scenarios {
    private val alex = ActorId("alex")
    private val sam = ActorId("sam")
    private val remote = ActorId("remote")
    private val v1 = VenueId("v1")
    private val v2 = VenueId("v2")
    private val v3 = VenueId("v3")

    /** S1: independent edits. No agent runs. */
    public val s1: Scenario = Scenario(
        "S1", listOf(alex, sam, remote),
        listOf(
            Step.Edit(InputKey("noteV2"), alex, WorkspaceEntry.Note(alex, v2, "Quiet back room")),
            Step.Edit(InputKey("fullV3"), sam, WorkspaceEntry.Report.Full(sam, v3)),
        ),
    )

    /** S2: budget changed during inference, by an offline Alex. */
    public val s2: Scenario = Scenario(
        "S2", listOf(alex, sam, remote),
        listOf(
            Step.Partition(alex),
            Step.StartAgent("r1", host = remote), // sees nothing → v1
            Step.Edit(InputKey("budget30"), alex, WorkspaceEntry.PreferenceSet(alex, budget = 30)), // offline
            Step.ReleaseAgent("r1"), // returns v1 (40)
            Step.Reconnect(alex),
        ),
    )

    /** S3: closure report during inference. Nobody is partitioned. */
    public val s3: Scenario = Scenario(
        "S3", listOf(alex, sam, remote),
        listOf(
            Step.StartAgent("r1", host = remote), // sees nothing → v1
            Step.Edit(InputKey("closeV1"), sam, WorkspaceEntry.Report.Closed(sam, v1)),
            Step.ReleaseAgent("r1"), // returns v1
        ),
    )

    /** S4: two conflicting human choices, each right for what its chooser knew. */
    public val s4: Scenario = Scenario(
        "S4", listOf(alex, sam, remote),
        listOf(
            Step.StartAgent("r1", host = remote), // sees nothing → v1
            Step.ReleaseAgent("r1"), // v1, seen by alex and sam
            Step.Partition(sam),
            Step.Edit(InputKey("budget30"), alex, WorkspaceEntry.PreferenceSet(alex, budget = 30)), // alex is connected
            Step.StartAgent("r2", host = remote), // sees budget30 → v2
            Step.ReleaseAgent("r2"), // v2, seen by alex only
            Step.Partition(alex),
            Step.Accept(alex, "r2"), // offline
            Step.Accept(sam, "r1"), // offline
            Step.Reconnect(alex),
            Step.Reconnect(sam),
        ),
    )

    /** S5: a report corrected later. The missing input concerns a venue other than the one recommended. */
    public val s5: Scenario = Scenario(
        "S5", listOf(alex, sam, remote),
        listOf(
            Step.Edit(InputKey("closeV1"), sam, WorkspaceEntry.Report.Closed(sam, v1)),
            Step.StartAgent("r1", host = remote), // sees closeV1 → v2
            Step.Edit(InputKey("reopenV1"), sam, WorkspaceEntry.Report.Reopened(sam, v1)),
            Step.ReleaseAgent("r1"), // returns v2; truth is now v1
        ),
    )

    /** S6: S2 with no partition. Alex stays connected throughout, which isolates what S2's outage costs. */
    public val s6: Scenario = Scenario(
        "S6", listOf(alex, sam, remote),
        listOf(
            Step.StartAgent("r1", host = remote), // sees nothing → v1
            Step.Edit(InputKey("budget30"), alex, WorkspaceEntry.PreferenceSet(alex, budget = 30)), // alex is connected
            Step.ReleaseAgent("r1"), // returns v1 (40); truth is now v2
        ),
    )

    /** S7: a harmless note on v1 while the agent is out. Notes never change the pick, so v1 stays right. */
    public val s7: Scenario = Scenario(
        "S7", listOf(alex, sam, remote),
        listOf(
            Step.StartAgent("r1", host = remote), // sees nothing → v1
            Step.Edit(InputKey("noteV1"), sam, WorkspaceEntry.Note(sam, v1, "Book the window table")),
            Step.ReleaseAgent("r1"), // returns v1; truth is still v1
        ),
    )

    public val all: List<Scenario> = listOf(s1, s2, s3, s4, s5, s6, s7)
}

/**
 * One proposal shown to one actor. [basis] is what the agent received; [known] is every input the
 * actor knew when it was shown, including every input the actor created itself, served or not.
 */
public data class Presentation(
    val actor: ActorId,
    val request: String,
    val recommendation: Recommendation,
    val basis: Set<InputKey>,
    val known: Set<InputKey>,
    val shownAsApplicable: Boolean,
    /**
     * The presenter had not received every input in [basis], so it could not judge the proposal
     * and flagged it for review. Implies `!shownAsApplicable`. The baseline never sets it.
     */
    val unknown: Boolean = false,
) {
    init {
        require(!(unknown && shownAsApplicable)) { "an unknown verdict is never shown as applicable" }
    }
}

/**
 * What a backend reports after replaying one [Scenario]. The [Oracle] scores it.
 *
 * [finalViews] must hold a view for **every** actor in [Scenario.actors], `remote` included; the
 * oracle throws on a missing one rather than counting an edit as preserved in a view nobody reported.
 */
public data class RunResult(
    val presentations: List<Presentation>,
    val finalViews: Map<ActorId, List<WorkspaceEntry>>,
    val agentRuns: Int,
    /** Each rerun as (the recommendation it replaced, the one it returned). */
    val reruns: List<Pair<Recommendation, Recommendation>>,
    val humanPrompts: Int,
    val outageActions: Int,
    val outageActionsServed: Int,
    /** Agent replies refused because their request had no run open on the host: never started, or already returned. */
    val rejectedReplies: Int = 0,
)

/** A way of keeping shared state. The baseline and the kuilt backend both implement it. */
public interface WorkspaceBackend {
    public suspend fun run(scenario: Scenario): RunResult
}
