package us.tractat.kuilt.core

/**
 * A transport's self-report: the [roles] it plays and whether its fabric is
 * usable now ([availability]). Produced pre-connect by [Loom.capability] and
 * live per-session by [Seam.capability].
 */
public data class TransportCapability(
    public val roles: Set<TransportRole>,
    public val availability: FabricAvailability,
)
