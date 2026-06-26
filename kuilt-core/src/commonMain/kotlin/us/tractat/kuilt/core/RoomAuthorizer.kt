package us.tractat.kuilt.core

/**
 * Server-side authorization policy for room membership.
 *
 * Invoked once per connection per room tag when a connection first emits a frame
 * on that channel (lazy-on-first-frame registration). A returning `false` means
 * the connection is **never** added to the room's fanout or [Seam.peers] — structural
 * rejection, not a runtime filter. The rejected connection is structurally absent from
 * the room: a cross-room leak stays unrepresentable.
 *
 * ## Required — non-nullable, no default
 *
 * Per the "optional ≠ tuning" rule, this parameter is required wherever it is wired:
 * absent or nullable would silently disable the authorization gate. Supply
 * [AllowAll] in tests and benchmarks; supply a real policy in production.
 *
 * ## Example
 *
 * ```kotlin
 * val authorizer = RoomAuthorizer { peer, tag ->
 *     sessionStore.isAdmitted(peer, tag)
 * }
 * val serverLoom = MuxServerLoom(source, scope, PeerId("server"), authorizer = authorizer)
 * ```
 */
public fun interface RoomAuthorizer {

    /**
     * Return `true` to admit [peerId] into the room identified by [channelName],
     * `false` to reject (structural exclusion — the connection is never added to the fanout).
     *
     * This function is called outside the room's internal lock; it MAY suspend (e.g. to
     * consult a session store). It must NOT hold any external lock while suspended.
     */
    public suspend fun authorize(peerId: PeerId, channelName: String): Boolean

    public companion object {
        /** Authorizer that admits every connection to every room. For tests and open-access servers. */
        public val AllowAll: RoomAuthorizer = RoomAuthorizer { _, _ -> true }
    }
}
