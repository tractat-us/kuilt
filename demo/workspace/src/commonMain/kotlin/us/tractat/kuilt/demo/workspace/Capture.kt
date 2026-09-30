package us.tractat.kuilt.demo.workspace

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId

/** Names one agent request, from capture to the proposal it returns. */
@Serializable public data class RequestId(val value: String)

/** The inputs that reached the agent at step [index], and only those: earlier steps' dots are not repeated. */
public data class StepBasis(val index: Int, val dots: Set<Dot>)

/**
 * Everything an agent received for [request], step by step. Step 0 is the capture; each later step
 * is one tool result. Every input is an `Rga` element, and a dot is that element's identity.
 *
 * The constructor refuses a malformed history: steps must be indexed `0..n-1` in order, and no dot
 * may appear in two steps, since an input reaches an agent once.
 */
public data class InputRecord(val request: RequestId, val steps: List<StepBasis>) {
    init {
        steps.forEachIndexed { i, step ->
            require(step.index == i) { "request ${request.value}: step at position $i is indexed ${step.index}" }
        }
        val all = steps.flatMap { it.dots }
        require(all.size == all.toSet().size) { "request ${request.value}: a dot appears in more than one step" }
    }

    /** Every input the agent received, over all steps. */
    public val allDots: Set<Dot> = steps.flatMapTo(mutableSetOf()) { it.dots }

    /** What the agent had received by the end of [step], inclusive. */
    public fun basisThrough(step: Int): Set<Dot> {
        require(step in steps.indices) { "request ${request.value}: no step $step in ${steps.indices}" }
        return steps.take(step + 1).flatMapTo(mutableSetOf()) { it.dots }
    }
}

/**
 * An agent's result together with exactly the inputs it was computed from.
 *
 * **The constructor is private, so on the [Pending] path a basis cannot be stamped late.** There are
 * two ways to get a `Proposal`:
 * - [Pending.complete], at the end of a run that began with [InputCapture.capture]. The basis was
 *   fixed when the snapshot was read and grows only by the tool results the run recorded. Nothing
 *   that happens to the live log afterwards can reach [basis], so merging a late result never makes
 *   it look as if it saw reports it did not.
 * - [toProposal], from the wire form. It checks shape only and accepts whatever basis it is given,
 *   by design: it is the boundary where a later adapter must bring its own reason to trust the basis.
 *
 * One hole on the [Pending] path too: [Pending.withToolResult] checks only that the ids are in the
 * log, so a caller could pass later host updates off as a tool result.
 *
 * A plain class rather than a `data class` on purpose: a data class's `copy` would be a third
 * constructor path, one that takes a basis from the caller.
 */
public class Proposal private constructor(
    public val request: RequestId,
    public val basis: InputRecord,
    public val recommendation: Recommendation,
    /** Output the agent showed before it finished, each tagged with the step it was computed at. */
    public val intermediate: List<Pair<Int, Recommendation>>,
) {
    /** The wire form. Dots are sorted within each step, so the same proposal always encodes the same way. */
    public fun toEntry(by: ActorId): WorkspaceEntry.AgentProposal = WorkspaceEntry.AgentProposal(
        by = by,
        request = request,
        basis = basis.steps.map { it.dots.sorted() },
        recommendation = recommendation,
    )

    override fun equals(other: Any?): Boolean = other is Proposal &&
        request == other.request && basis == other.basis &&
        recommendation == other.recommendation && intermediate == other.intermediate

    override fun hashCode(): Int = listOf(request, basis, recommendation, intermediate).hashCode()

    override fun toString(): String = "Proposal(request=$request, basis=$basis, recommendation=$recommendation, intermediate=$intermediate)"

    /**
     * A run in progress: the inputs captured so far and any intermediate output. Immutable, so every
     * call returns a new `Pending`. Its constructor is private too; a run starts only through
     * [InputCapture.capture].
     */
    public class Pending private constructor(
        private val request: RequestId,
        private val steps: List<StepBasis>,
        private val shown: List<Pair<Int, Recommendation>>,
    ) {
        /**
         * Records a tool result: a new step holding only [newIds]' dots. Every id must be an element of
         * [log], and none may already be in the basis.
         */
        public fun withToolResult(log: Rga<WorkspaceEntry>, newIds: Set<RgaId>): Pending {
            val present = log.entries().mapTo(mutableSetOf()) { it.first }
            val absent = newIds - present
            require(absent.isEmpty()) { "request ${request.value}: tool result ids not in the log: $absent" }
            val dots = newIds.mapTo(mutableSetOf()) { it.dot }
            val seen = steps.flatMapTo(mutableSetOf()) { it.dots }
            require(dots.none { it in seen }) { "request ${request.value}: tool result repeats a captured input" }
            return Pending(request, steps + StepBasis(steps.size, dots), shown)
        }

        /** Records output shown before the run finished, tagged with the current step. */
        public fun intermediate(rec: Recommendation): Pending = Pending(request, steps, shown + (steps.lastIndex to rec))

        /** Ends the run. The basis is exactly the steps recorded so far. */
        public fun complete(rec: Recommendation): Proposal = Proposal(request, InputRecord(request, steps), rec, shown)

        internal companion object {
            /**
             * Reads [snapshot]`.entries()` exactly once. `Rga` is an immutable value, so that single
             * read is the atomicity argument: the step-0 basis derives from one value, and no later
             * update to any live log can reach it.
             */
            fun start(request: RequestId, snapshot: Rga<WorkspaceEntry>, exclude: (WorkspaceEntry) -> Boolean): Pending {
                val selected = snapshot.entries().filterNot { (_, entry) -> exclude(entry) }
                return Pending(request, listOf(StepBasis(0, selected.mapTo(mutableSetOf()) { it.first.dot })), emptyList())
            }
        }
    }

    internal companion object {
        fun fromWire(entry: WorkspaceEntry.AgentProposal): Proposal {
            entry.basis.forEachIndexed { i, dots ->
                require(dots.size == dots.toSet().size) { "request ${entry.request.value}: step $i repeats a dot" }
            }
            val steps = entry.basis.mapIndexed { i, dots -> StepBasis(i, dots.toSet()) }
            return Proposal(entry.request, InputRecord(entry.request, steps), entry.recommendation, emptyList())
        }
    }
}

/**
 * Rebuilds a [Proposal] from its wire form: the only constructor path besides [Proposal.Pending.complete],
 * and the one an external adapter has to go through. Steps take their index from their position,
 * so they are `0..n-1` in order by construction. A dot repeated within or across steps is
 * rejected with [IllegalArgumentException]. Intermediate output is not on the wire, so it comes
 * back empty.
 */
public fun WorkspaceEntry.AgentProposal.toProposal(): Proposal = Proposal.fromWire(this)

public object InputCapture {
    /**
     * Starts a run for [request] on [snapshot], minus the entries [exclude] selects. Reads
     * `snapshot.entries()` exactly once from the immutable `Rga` value it was handed; that single
     * read is why the basis cannot drift from the inputs the agent was given.
     */
    public fun capture(
        request: RequestId,
        snapshot: Rga<WorkspaceEntry>,
        exclude: (WorkspaceEntry) -> Boolean = { false },
    ): Proposal.Pending = Proposal.Pending.start(request, snapshot, exclude)
}
