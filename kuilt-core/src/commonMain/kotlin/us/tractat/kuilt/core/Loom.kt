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
     * capability primitive — override this, not [availability].
     *
     * ## The default is a floor, not a verdict
     *
     * Default: a roleless [FabricAvailability.Unknown]. This is the **pre-connect** surface a
     * consuming app turns into actionable guidance — "Bluetooth is off", "can't reach the server"
     * (#1530) — so a loom that has probed nothing must say *"I cannot tell"* rather than assert a
     * confident [FabricAvailability.Available] the caller will then act on. It mirrors the floor
     * `Seam.capability` took in #1712: an authoritative false negative is strictly worse than
     * silence, and the two halves of one contract must not default in opposite directions (#1746).
     *
     * **Override it whenever you can answer.** The question is "is this fabric *attemptable on this
     * runtime*" — compiled in, permission granted, radio present — not "is the remote reachable",
     * which is a live question a woven `Seam`'s own `capability` answers. So three shapes of
     * override are all correct, and the default is none of them:
     *
     *  - [FabricAvailability.Available] where it is established **by construction** — an in-memory
     *    loom, or an accept-side loom that acquires no OS resource and reaches no remote. Say in a
     *    comment *why* it is a fact, so the next reader can tell it from a guess.
     *  - [FabricAvailability.Unavailable] where the answer is known and negative — a stub whose
     *    [weave] always throws on this target. (`FabricAvailability`'s "simply absent" case is a
     *    fabric with no `Loom` at all on the target; a constructible stub is not that.)
     *  - [FabricAvailability.Unknown] with a **specific** reason naming what was not established,
     *    where a real probe exists but has not been run.
     */
    public fun capability(): TransportCapability =
        TransportCapability(
            roles = emptySet(),
            availability = FabricAvailability.Unknown(
                "this loom does not check whether the fabric is attemptable on this runtime",
            ),
        )

    /**
     * Whether this fabric can be attempted now — the availability half of
     * [capability]. Derived; do not override.
     */
    public fun availability(): FabricAvailability = capability().availability
}
