package us.tractat.kuilt.nw

/**
 * The current lifecycle state of one [NwApi] connection, keyed by [NwConnectionId] in
 * [NwApi.connectionStates]. A minimal, **total** sum type: every tracked connection is in exactly
 * one of these three states, and a state map is the single drop-tolerant signal `NwSeam` reconciles
 * for both the #1478 path-loss grace timer AND the #1522 terminal-teardown backstop (it unifies the
 * former separate `connectionViability` boolean map and `closedConnections` reason map into one — #1539).
 *
 * ## Terminal + monotone + dominant
 * [Closed] is **terminal**: once a connection's state is [Closed] it NEVER reverts to [Viable]/[PathLost].
 * Producers latch it (a later viability change for a closed id is ignored), so a closure can never be
 * lost to `StateFlow` conflation the way a boolean live-set's presence-then-absence would be under the
 * same starvation that drops the fast [NwApi.connectionClosed] event. `NwSeam` acts ONLY on a state's
 * PRESENCE — a connId absent from the map is never inferred closed.
 */
public sealed interface NwConnState {

    /** The connection's path is currently up (`ready`) — the healthy steady state. */
    public data object Viable : NwConnState

    /**
     * The connection's path is unsatisfied (`ready → waiting`, #1478) — the peer is silently unreachable
     * and NO [NwApi.connectionClosed] will fire. `NwSeam` arms a bounded grace timer while a connection is
     * [PathLost] and tears it if the path does not recover to [Viable] in time. This is the state the old
     * `connectionViability == false` value carried.
     */
    public data object PathLost : NwConnState

    /**
     * The connection has terminally torn down. [reason] carries the raw transport close reason with the SAME
     * semantics the old `closedConnections` map's `String?` value had: `null` for a graceful/local close, a
     * non-null failure reason (e.g. `receive:54`, `connection failed`) otherwise. Terminal, monotone and
     * dominant — see the interface KDoc. `NwSeam` tears a still-tracked connection IMMEDIATELY on [Closed],
     * with no grace timer.
     */
    public data class Closed(public val reason: String?) : NwConnState
}
