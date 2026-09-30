package us.tractat.kuilt.demo.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.Dot

@Serializable public data class ActorId(val value: String)
@Serializable public data class VenueId(val value: String)
@Serializable public data class Venue(val id: VenueId, val name: String, val pricePerHead: Int, val walkMinutes: Int)

public object Fixtures {
    // Ordered by walkMinutes so "nearest" is unambiguous.
    public val venues: List<Venue> = listOf(
        Venue(VenueId("v1"), "Trattoria Uno", pricePerHead = 40, walkMinutes = 2),
        Venue(VenueId("v2"), "Due Fratelli", pricePerHead = 25, walkMinutes = 5),
        Venue(VenueId("v3"), "Tre Sorelle", pricePerHead = 30, walkMinutes = 8),
        Venue(VenueId("v4"), "Quattro", pricePerHead = 20, walkMinutes = 15),
    )
    public fun venue(id: VenueId): Venue = venues.first { it.id == id }
}

@Serializable public data class Recommendation(val venue: VenueId?)

/** Every input an agent can receive. Each lives as one Rga element; its identity is that insert's Dot. */
@Serializable
public sealed interface WorkspaceEntry {
    public val by: ActorId

    @Serializable
    public sealed interface Report : WorkspaceEntry {
        public val venue: VenueId
        @Serializable @SerialName("closed") public data class Closed(override val by: ActorId, override val venue: VenueId) : Report
        @Serializable @SerialName("full") public data class Full(override val by: ActorId, override val venue: VenueId) : Report
        @Serializable @SerialName("reopened") public data class Reopened(override val by: ActorId, override val venue: VenueId) : Report
    }

    @Serializable @SerialName("budget") public data class PreferenceSet(override val by: ActorId, val budget: Int) : WorkspaceEntry
    @Serializable @SerialName("note") public data class Note(override val by: ActorId, val venue: VenueId, val text: String) : WorkspaceEntry

    /**
     * The wire form of a [Proposal]: [basis] holds the dots the agent received, one list per step,
     * with step `i` at position `i`. It is an agent's output, never a scripted input, and it is
     * turned back into a trusted [Proposal] only through [toProposal].
     */
    @Serializable @SerialName("proposal") public data class AgentProposal(
        override val by: ActorId,
        val request: RequestId,
        val basis: List<List<Dot>>,
        val recommendation: Recommendation,
    ) : WorkspaceEntry

    /**
     * [by] accepts the proposal from [request]: a person's choice, appended like any other entry so
     * that an offline accept shows on its chooser's own replica at once. Never a scripted input and
     * never something an agent could have missed; `Scenario` rejects one in an `Edit` or `ToolResult`.
     */
    @Serializable @SerialName("accept") public data class Accept(override val by: ActorId, val request: RequestId) : WorkspaceEntry
}

public object ScriptedAgent {
    /** Deterministic "agent": the nearest venue that is open (last report wins, in input order) and within the last budget set. */
    public fun recommend(inputs: List<WorkspaceEntry>): Recommendation {
        val budget = inputs.filterIsInstance<WorkspaceEntry.PreferenceSet>().lastOrNull()?.budget ?: Int.MAX_VALUE
        val unavailable = inputs.filterIsInstance<WorkspaceEntry.Report>()
            .groupBy { it.venue }
            .filterValues { reports -> reports.last() !is WorkspaceEntry.Report.Reopened }
            .keys
        return Recommendation(
            Fixtures.venues.filter { it.id !in unavailable && it.pricePerHead <= budget }.minByOrNull { it.walkMinutes }?.id,
        )
    }
}
