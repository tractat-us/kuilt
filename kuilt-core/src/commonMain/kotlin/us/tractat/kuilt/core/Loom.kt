package us.tractat.kuilt.core

/**
 * Establishes a [Seam] in the role of either an existing-session
 * joiner or a new-session opener. The factory hides discovery (mDNS,
 * MultipeerConnectivity advertising, WebSocket URL).
 *
 * The single abstract method is [weave]; [host] and [join] are default
 * wrappers. ADR-002.
 *
 * ## Per-dial data
 *
 * A fabric implementation that needs a value recomputed on every dial — most commonly a
 * credential that must be refreshed on reconnect — accepts a [Weft] on its own constructor and
 * invokes it inside [weave]. See [Weft]'s KDoc for the full idiom; see
 * `KtorClientLoom`/`WebSocketSignalingChannel` in `:kuilt-websocket`/`:kuilt-webrtc` for the
 * first concrete uses.
 *
 * ## Usage
 *
 * Host a session, let a second peer join, and exchange a frame:
 *
 * @sample us.tractat.kuilt.core.sampleHostAndJoin
 */
public interface Loom {
    /**
     * Establish a [Seam] according to [rendezvous] — either host a new session or join an existing one.
     *
     * ## A fabric failure must NOT be reported as a cancellation
     *
     * Throw an ordinary exception when the fabric cannot be reached. An implementation must **not** let a
     * `CancellationException` out of this method unless it is signalling the *caller's* own cancellation,
     * because the caller cannot tell the two apart: the idiomatic guard (`runCatchingCancellable`)
     * rethrows any `CancellationException`, and a rethrown one **cancels** the calling coroutine rather
     * than failing it — no failure handler runs, and there is not even a stack trace to find it by.
     *
     * The trap is `withTimeout(dialTimeout) { dial() }`. `withTimeout` throws
     * `TimeoutCancellationException` — which *is* a `CancellationException` — **to its caller**, without
     * cancelling that caller's job. Convert it before it escapes: `withTimeoutOrNull` plus an explicit
     * throw, or catch it and rethrow as a plain `Exception`. `NwLoom` in `:kuilt-nw` is the in-tree
     * pattern — it converts its own dial timeout into a plain `NwUnreachableException` for exactly this
     * reason. A ply whose `Loom.weave` breaks this rule used to kill a `CompositeLoom`'s reconciliation
     * for the life of the seam, silently (#1784).
     */
    public suspend fun weave(rendezvous: Rendezvous): Seam

    /** Host / start a new session. */
    public suspend fun host(pattern: Pattern): Seam = weave(Rendezvous.New(pattern))

    /**
     * Join an existing session. The advertisement carries enough info
     * to reach the existing peer set.
     */
    public suspend fun join(tag: Tag): Seam = weave(Rendezvous.Existing(tag))

    /**
     * This fabric's role(s) and whether it can be attempted now. The single
     * capability primitive — override this, not [availability]. Default: a
     * roleless [FabricAvailability.Available].
     */
    public fun capability(): TransportCapability =
        TransportCapability(roles = emptySet(), availability = FabricAvailability.Available)

    /**
     * Whether this fabric can be attempted now — the availability half of
     * [capability]. Derived; do not override.
     */
    public fun availability(): FabricAvailability = capability().availability
}
