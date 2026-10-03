package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.crdt.Rga

/**
 * What each phone shows, and when a person is asked: the presentation rule of `RECHECK.md` (#2880).
 *
 * [settle] is called once whenever the replicas have settled, with every actor's current log. It
 * works in two passes, in this order (rule 5):
 *
 * 1. **Present new proposals.** Each proposal a log holds for the first time is shown to that actor,
 *    judged with [Assessment.assess] against the log as it stands. It becomes the actor's
 *    **standing answer** if it was released later than the one standing ([releaseRank]).
 * 2. **Re-judge each standing answer** against the current log, with the same [Assessment] and so the
 *    same relevance clauses. Only the standing answer is re-judged; a superseded one costs nothing.
 *
 * **The verdict is derived, never stored.** A standing answer is the [Proposal] alone. Its verdict is
 * read fresh from the log on every pass, and compared with what the screen last showed: the latest
 * [Presentation] row for that proposal. When what the screen shows changes (fits, needs review,
 * unknown), a new row is recorded with the current `known`, so the oracle scores the re-judgement
 * like any first showing. A flagged answer that gains a further relevant input shows the same
 * screen, so it adds no row.
 *
 * **A prompt is charged on entering a flagged state** (rule 4): a first showing flagged, or a
 * re-judge from fits to needs review or unknown, costs one prompt, and only when [isPerson]. Needs
 * review to unknown or back, and anything to fits, cost nothing.
 *
 * `Quilter.state` is the trigger: a replica's log only grows, so re-reading the whole current log
 * after every settled step sees everything delivered since the last pass. Nothing here needs kuilt
 * to say *what* arrived.
 */
internal class Presenter(
    private val isPerson: (ActorId) -> Boolean,
    private val releaseRank: (RequestId) -> Int,
    private val basisKeys: (Proposal) -> Set<InputKey>,
    private val known: (ActorId, Rga<WorkspaceEntry>) -> Set<InputKey>,
) {
    private val rows = mutableListOf<Presentation>()
    private val shown = mutableSetOf<Pair<ActorId, RequestId>>()
    private val standing = mutableMapOf<ActorId, Proposal>()

    /** Every row recorded so far, in the order the screens showed them. */
    val presentations: List<Presentation> get() = rows.toList()

    var humanPrompts: Int = 0
        private set

    fun settle(logs: Map<ActorId, Rga<WorkspaceEntry>>) {
        for ((actor, log) in logs) presentNew(actor, log)
        for ((actor, log) in logs) reJudge(actor, log)
    }

    private fun presentNew(actor: ActorId, log: Rga<WorkspaceEntry>) {
        for ((_, entry) in log.entries()) {
            if (entry !is WorkspaceEntry.AgentProposal || !shown.add(actor to entry.request)) continue
            val proposal = entry.toProposal()
            show(actor, proposal, log, Screen.of(Assessment.assess(proposal, log)), previous = null)
            val current = standing[actor]
            if (current == null || releaseRank(proposal.request) > releaseRank(current.request)) standing[actor] = proposal
        }
    }

    private fun reJudge(actor: ActorId, log: Rga<WorkspaceEntry>) {
        val proposal = standing[actor] ?: return
        val last = rows.last { it.actor == actor && it.request == proposal.request.value }
        val now = Screen.of(Assessment.assess(proposal, log))
        if (now != Screen.of(last)) show(actor, proposal, log, now, previous = Screen.of(last))
    }

    private fun show(actor: ActorId, proposal: Proposal, log: Rga<WorkspaceEntry>, screen: Screen, previous: Screen?) {
        rows += Presentation(
            actor = actor,
            request = proposal.request.value,
            recommendation = proposal.recommendation,
            basis = basisKeys(proposal),
            known = known(actor, log),
            shownAsApplicable = screen == Screen.Fits,
            unknown = screen == Screen.Unknown,
        )
        val entersFlagged = screen != Screen.Fits && (previous == null || previous == Screen.Fits)
        if (entersFlagged && isPerson(actor)) humanPrompts += 1
    }

    /** What a phone's screen says about an answer. A [Verdict]'s sets are not on the screen. */
    private enum class Screen {
        Fits, NeedsReview, Unknown;

        companion object {
            fun of(verdict: Verdict): Screen = when (verdict) {
                Verdict.Applicable -> Fits
                is Verdict.NeedsReview -> NeedsReview
                is Verdict.Unknown -> Unknown
            }

            fun of(row: Presentation): Screen = when {
                row.shownAsApplicable -> Fits
                row.unknown -> Unknown
                else -> NeedsReview
            }
        }
    }
}
