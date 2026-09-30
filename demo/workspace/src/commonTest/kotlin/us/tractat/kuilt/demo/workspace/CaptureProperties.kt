package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga

/**
 * The bug the input-capture promise forbids, kept in the test source set so it can never ship: a
 * stamp that reads the basis off the **live** log at the moment the result is sent, rather than off
 * the snapshot the agent was handed. Every update the host received after capture leaks into it.
 */
object LeakyStamp {
    fun stamp(proposal: Proposal, liveLog: Rga<WorkspaceEntry>): WorkspaceEntry.AgentProposal =
        WorkspaceEntry.AgentProposal(
            by = ActorId("remote"),
            request = proposal.request,
            basis = listOf(liveLog.entries().map { it.first.dot }),
            recommendation = proposal.recommendation,
        )
}

/**
 * True when [stamp] leaves every proposal's basis exactly as captured, however many host updates
 * land between capture and stamping. [stamp] receives the finished proposal and the live log as it
 * stands when the result is sent.
 *
 * A property of the **stamp and its codec** only. Both sides come from one proposal that this
 * function captured itself, at the right moment, so a backend that calls `InputCapture.capture`
 * late (at release, say) or passes later host updates off through `withToolResult` stays green here.
 * Capture timing is the backend's obligation; `KuiltBackendTest.capturedBasisIsTheHostStateAtStart`
 * pins it for [KuiltBackend].
 *
 * Walks a fixed grid: 0–3 inputs before capture, an optional excluded note, an optional tool result,
 * and 1–3 host updates after capture. Every cell has at least one later update, so a stamp that
 * reads the live log is caught in every cell, not just some. A stamp whose wire form `toProposal`
 * rejects counts as a failure.
 */
fun basisUnchangedByLaterUpdates(stamp: (Proposal, Rga<WorkspaceEntry>) -> WorkspaceEntry.AgentProposal): Boolean {
    val host = ReplicaId("host")
    val sam = ActorId("sam")
    fun Rga<WorkspaceEntry>.append(e: WorkspaceEntry): Rga<WorkspaceEntry> = insertAt(host, size, e).first
    val before = listOf(
        WorkspaceEntry.PreferenceSet(sam, 50),
        WorkspaceEntry.Report.Closed(sam, VenueId("v1")),
        WorkspaceEntry.Report.Reopened(sam, VenueId("v1")),
    )
    val later = listOf(
        WorkspaceEntry.Report.Closed(sam, VenueId("v2")),
        WorkspaceEntry.PreferenceSet(sam, 20),
        WorkspaceEntry.Report.Full(sam, VenueId("v3")),
    )
    for (n in 0..before.size) for (excludedNote in listOf(false, true)) for (tool in listOf(false, true)) {
        for (m in 1..later.size) {
            var log = before.take(n).fold(Rga.empty<WorkspaceEntry>()) { acc, e -> acc.append(e) }
            if (excludedNote) log = log.append(WorkspaceEntry.Note(sam, VenueId("v2"), "quiet"))
            var pending = InputCapture.capture(RequestId("r-$n-$excludedNote-$tool-$m"), log) { it is WorkspaceEntry.Note }
            if (tool) {
                val (withTool, op) = log.insertAt(host, log.size, WorkspaceEntry.Report.Full(sam, VenueId("v4")))
                log = withTool
                pending = pending.withToolResult(log, setOf(op.id))
            }
            val proposal = pending.complete(Recommendation(VenueId("v2")))
            val live = later.take(m).fold(log) { acc, e -> acc.append(e) }
            val stamped = stamp(proposal, live)
            val roundTripped = try {
                stamped.toProposal()
            } catch (_: IllegalArgumentException) {
                return false
            }
            if (stamped.request != proposal.request || roundTripped.basis != proposal.basis) return false
        }
    }
    return true
}
