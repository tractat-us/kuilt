package us.tractat.kuilt.gossip

/**
 * Decides how many **active** (eager-flood + GC-target) neighbours a peer keeps for a
 * given roster size. The active view is a subset of full membership; see [GossipView]
 * and `docs/gossip-mesh-design.md`.
 */
public fun interface ActiveViewPolicy {
    /** Target active-view size for a roster of [rosterSize] peers (including self). */
    public fun activeViewSize(rosterSize: Int): Int

    public companion object {
        /** Today's emergent partial mesh: `k = recommendedActiveViewSize(N)`. The default. */
        public val RandomKRegular: ActiveViewPolicy =
            ActiveViewPolicy { rosterSize -> recommendedActiveViewSize(rosterSize) }

        /**
         * Flood **every** other peer: active = roster minus self. The server-hub policy — the
         * hub re-floods each client's broadcast to all the others. With this policy a node
         * keeps no spares (the active view already covers everyone).
         */
        public val FullFanout: ActiveViewPolicy =
            ActiveViewPolicy { rosterSize -> (rosterSize - 1).coerceAtLeast(0) }
    }
}
