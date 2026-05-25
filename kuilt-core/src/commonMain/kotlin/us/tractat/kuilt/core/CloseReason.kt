package us.tractat.kuilt.core

/**
 * Reason a [PeerLink] is being closed. Designed as a forward-compatible
 * sealed hierarchy — new variants can be added without breaking existing
 * `when` exhaustiveness because the common cases are covered.
 */
public sealed interface CloseReason {
    /** Clean, intentional disconnect. */
    public data object Normal : CloseReason

    /** Closed due to an error on the local side. */
    public data class Error(
        val throwable: Throwable,
    ) : CloseReason

    /** The remote peer requested the close. */
    public data object RemoteRequested : CloseReason
}
