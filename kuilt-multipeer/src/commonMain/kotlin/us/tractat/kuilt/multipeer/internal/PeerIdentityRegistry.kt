package us.tractat.kuilt.multipeer.internal

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
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
 * Thread-safe: `MCSession` fires `didChangeState` from the framework's private
 * queue with **no cross-peer serialization guarantee**, so the [bound] map is
 * guarded by an explicit [reentrantLock] (matching the `Quilter`/`SeamRoom`
 * exemplars). Correctness is a local property of this type, not an assumption
 * about caller threading. There are no suspend calls, so the whole body of each
 * operation runs under the lock.
 */
internal class PeerIdentityRegistry<T : Any> {
    private val lock = reentrantLock()
    private val bound: MutableMap<PeerId, T> = mutableMapOf()

    /** Snapshot of the ids currently held by a live device. */
    internal val peers: Set<PeerId> get() = lock.withLock { bound.keys.toSet() }

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
    ): BindResult =
        lock.withLock {
            when (bound[id]) {
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
        lock.withLock {
            if (bound[id] == token) {
                bound.remove(id)
                true
            } else {
                false
            }
        }
}
