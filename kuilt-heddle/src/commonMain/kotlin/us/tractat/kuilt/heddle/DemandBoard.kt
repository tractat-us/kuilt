package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable

/**
 * One peer's advertised appetite across the edges it serves — the value carried in a
 * peer's slot of the demand board (design §6).
 *
 * A peer publishes, into its own slot of an
 * [`EphemeralMap`][us.tractat.kuilt.crdt.EphemeralMap], a `DemandBoard` naming how
 * much more service each child edge could usefully take right now. The parent folds
 * the **live** (non-expired) slots when it ranks children: a crashed peer's slot ages
 * out by local receive time, so its stale advertisement stops steering (design §6,
 * "stale-demand safety").
 *
 * `DemandBoard` is **advisory, never authority** — exactly like the [Demand] values it
 * carries. It can be stale, duplicated, or lost, and the worst outcome is entitlement
 * briefly parked in the wrong pocket; it can never authorize a spend, because spends
 * check the ledger's holdings, which are blind to demand. That separation is a type
 * distinction (this rides an `EphemeralMap`, entitlement rides the ledger) that cannot
 * be quietly violated.
 *
 * @property perEdge the demand this peer advertises per child edge; an edge absent from
 *   the map advertises [Demand.NONE].
 */
@Serializable
public data class DemandBoard(
    public val perEdge: Map<AttachmentId, Demand> = emptyMap(),
) {
    public companion object {
        /** A peer advertising nothing. */
        public val EMPTY: DemandBoard = DemandBoard()
    }
}
