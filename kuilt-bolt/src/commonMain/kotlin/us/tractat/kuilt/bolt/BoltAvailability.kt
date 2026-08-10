package us.tractat.kuilt.bolt

/**
 * Whether a [Bolt] can be written to on this runtime.
 *
 * Mirrors `FabricAvailability`, the pattern this repo already uses for a facility that is real on
 * some runtimes and absent on others. A backend that is scoped out by target is simply not
 * compiled; [Unavailable] means present-but-not-usable-now.
 */
public sealed interface BoltAvailability {

    /** The archive is writable. A bolt reporting this must accept an `append`. */
    public data object Available : BoltAvailability

    /**
     * The archive cannot be written to, and the backend knows it — no filesystem on this runtime,
     * a directory that could not be created, a read-only volume.
     */
    public data class Unavailable(public val reason: String) : BoltAvailability

    /**
     * The backend cannot report ground truth right now.
     *
     * Not a hypothetical: an iOS file whose Data Protection class makes it unreadable while the
     * device is locked is neither available nor permanently unavailable, and a best-effort consumer
     * should surface [reason] rather than assume either.
     */
    public data class Unknown(public val reason: String) : BoltAvailability
}
