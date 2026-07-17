package us.tractat.kuilt.multipeer.internal

import us.tractat.kuilt.core.PeerId

/**
 * Tracks which underlying device identity [T] currently owns each [PeerId],
 * so two DISTINCT devices can never be silently merged onto one id and — the
 * bug this closes — a disconnect of one can never evict the other.
 *
 * Historically the MC session path kept peers in a bare `Set<PeerId>`: two
 * devices colliding on one id collapsed to a single entry, and a
 * `.notConnected` for either removed that entry, evicting BOTH (kuilt#1494 /
 * the #1466 class). Keying membership by the device identity [T] (the
 * `MCPeerID`) instead means:
 *  - [bind] REFUSES to overwrite an id already held by a different device
 *    ([BindResult.COLLISION]) rather than merging them;
 *  - [unbind] removes an id only when the *same* device that holds it drops
 *    ([unbind] is identity-scoped), so a colliding newcomer's drop leaves the
 *    incumbent connected.
 *
 * This is defence-in-depth: the primary fix is the per-device nonce in
 * [MultipeerPeerId.decorate], which makes real collisions astronomically
 * unlikely. The registry guarantees the failure mode is "a peer is refused and
 * logged", never "the wrong peer is evicted".
 *
 * Not thread-safe: callers (the MC delegate) already serialize state-change
 * callbacks on the framework's private queue.
 */
internal class PeerIdentityRegistry<T : Any> {
    private val bound: MutableMap<PeerId, T> = mutableMapOf()

    /** Snapshot of the ids currently held by a live device. */
    internal val peers: Set<PeerId> get() = bound.keys.toSet()

    internal enum class BindResult {
        /** [id] was free; [bind]'s device now owns it. */
        BOUND,

        /** The SAME device re-announced [id] — idempotent (duplicate connect callback). */
        ALREADY_BOUND,

        /** A DIFFERENT device already owns [id]; the newcomer is refused, not merged. */
        COLLISION,
    }

    /**
     * Binds [id] to [token], unless a different device already holds it.
     * The incumbent always wins a [COLLISION][BindResult.COLLISION] — the id is
     * never reassigned out from under a live peer.
     */
    internal fun bind(
        id: PeerId,
        token: T,
    ): BindResult {
        val existing = bound[id]
        return when (existing) {
            null -> {
                bound[id] = token
                BindResult.BOUND
            }
            token -> BindResult.ALREADY_BOUND
            else -> BindResult.COLLISION
        }
    }

    /**
     * Removes [id] only if [token] is the device currently holding it. Returns
     * `true` when a binding was actually removed. A drop from a device that
     * does not hold [id] (e.g. a collision-refused newcomer) is a no-op, so the
     * incumbent survives.
     */
    internal fun unbind(
        id: PeerId,
        token: T,
    ): Boolean =
        if (bound[id] == token) {
            bound.remove(id)
            true
        } else {
            false
        }
}
