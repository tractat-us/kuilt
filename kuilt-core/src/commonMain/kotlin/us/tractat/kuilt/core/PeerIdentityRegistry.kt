package us.tractat.kuilt.core

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * The one place a fabric decides whether a peer-supplied identity may join its roster, and who is
 * allowed to take it away again.
 *
 * A fabric learns a remote peer's [PeerId] from bytes the remote itself supplied — a display name,
 * a handshake payload, a native callback's string. Those bytes are not trustworthy and not
 * necessarily well-formed, and every fabric that decided for itself what to do about that decided
 * slightly differently. This type is that decision, once: [bind] answers "may this identity join,
 * and who holds it", [unbind] answers "may this device take it away", and [peers] is the roster
 * that follows from the two.
 *
 * Membership is keyed by the underlying **device identity** [T] rather than by the bare [PeerId],
 * which is what makes the answers possible:
 *  - [bind] REFUSES to overwrite an id already held by a different device
 *    ([BindResult.COLLISION]) rather than merging them;
 *  - [unbind] removes an id only when the *same* device that holds it drops, so a colliding
 *    newcomer's drop leaves the incumbent connected;
 *  - a blank id ([BindResult.REFUSED_BLANK]) and this peer's own [selfId]
 *    ([BindResult.REFUSED_SELF]) are refused outright — neither names a remote peer.
 *
 * ## The incidents this closes
 *
 * Historically each fabric kept peers in a bare `Set<PeerId>` fed from those bytes:
 *  - **#1494 / the #1466 class** — two devices colliding on one id collapsed to a single set entry,
 *    and a disconnect for *either* removed that entry, evicting BOTH. `MCSessionLink` was the first
 *    path fixed.
 *  - **#1466 self-dial** — a peer handed its own advertisement registers *itself* as a remote, and
 *    the eventual drop of that self-link evicts the peer from its own roster.
 *  - **#1821** — the same shape, still open in two sibling paths, plus the blank-id variant: an
 *    unaddressable `PeerId("")` that a set-equality teardown test (`remaining == setOf(selfId)`)
 *    can never clear, so a seam holding one stays Woven after its last real peer has gone.
 *
 * ## What it is and is not
 *
 * This is **defence-in-depth against a malformed or hostile announcement**, not a substitute for a
 * fabric minting distinct identities in the first place (a per-device nonce, [freshPeerId]). Its
 * guarantee is that the failure mode is "a peer is refused and logged", never "the wrong peer is
 * evicted" or "the seam never tears".
 *
 * It also cannot un-merge what the layer beneath it already merged. A fabric whose transport hands
 * up only the id string — with no device handle to key by — must pass the id as its own [T], and
 * [BindResult.COLLISION] is then structurally unreachable for it: two devices really have become
 * one identity before this type ever sees them. Such a caller still gets the blank/self refusals
 * and the identity-scoped [unbind]; closing the collision needs a change one layer down.
 *
 * ## Threading
 *
 * Callers fire from framework queues with **no cross-peer serialization guarantee** (an `MCSession`
 * delegate, a JNA callback thread), so [bound] is guarded by an explicit [reentrantLock] — matching
 * the `Quilter`/`SeamRoom` exemplars. Correctness is a local property of this type, never an
 * assumption about caller threading. There are no suspend calls, so the whole body of each
 * operation runs under the lock.
 */
public class PeerIdentityRegistry<T : Any>(
    /** This peer's own identity, refused as a remote by [bind]. */
    private val selfId: PeerId,
) {
    private val lock = reentrantLock()
    private val bound: MutableMap<PeerId, T> = mutableMapOf()

    /** Snapshot of the ids currently held by a live device. Never contains [selfId]. */
    public val peers: Set<PeerId> get() = lock.withLock { bound.keys.toSet() }

    /** The device currently holding [id], or `null` if nothing holds it. */
    public fun holderOf(id: PeerId): T? = lock.withLock { bound[id] }

    /** The id [token] currently holds, or `null` if it holds none. */
    public fun idHeldBy(token: T): PeerId? = lock.withLock { bound.entries.firstOrNull { it.value == token }?.key }

    public enum class BindResult {
        /** The id was free and well-formed; [bind]'s device now owns it. */
        BOUND,

        /** The SAME device re-announced the id — idempotent (a duplicate connect callback). */
        ALREADY_BOUND,

        /** A DIFFERENT device already owns the id; the newcomer is refused, not merged. */
        COLLISION,

        /** The announced id is this peer's own [selfId] — a self-dial, never a remote. */
        REFUSED_SELF,

        /** The announced id is blank — unaddressable, and two blank remotes would collapse onto one. */
        REFUSED_BLANK,
    }

    /**
     * Binds [id] to [token], unless the id is blank, is this peer's own [selfId], or is already held
     * by a different device. The incumbent always wins a [COLLISION][BindResult.COLLISION] — the id
     * is never reassigned out from under a live peer.
     *
     * The order of the checks is not arbitrary: blankness and self are properties of the *announced
     * id alone*, so they are decided before anything is consulted about who holds what. That makes
     * the refusals independent of arrival order — a blank id is refused whether it is the first
     * announcement or the tenth.
     */
    public fun bind(
        id: PeerId,
        token: T,
    ): BindResult =
        lock.withLock {
            when {
                id.value.isBlank() -> BindResult.REFUSED_BLANK
                id == selfId -> BindResult.REFUSED_SELF
                else ->
                    when (bound[id]) {
                        null -> {
                            bound[id] = token
                            BindResult.BOUND
                        }
                        token -> BindResult.ALREADY_BOUND
                        else -> BindResult.COLLISION
                    }
            }
        }

    /**
     * Drops every binding, so [peers] is empty until something binds again.
     *
     * Unlike [unbind] this is deliberately **not** identity-scoped: it is for the terminal teardown
     * of the whole session, where no device holds anything any more. A caller that recomputes a
     * roster from [peers] needs this at tear time — otherwise a post-teardown disconnect callback
     * recomputes from stale bindings and republishes peers that are gone (#1851).
     */
    public fun clear() {
        lock.withLock { bound.clear() }
    }

    /**
     * Removes [id] only if [token] is the device currently holding it. Returns `true` when a binding
     * was actually removed. A drop from a device that does not hold [id] — a collision-refused
     * newcomer, a stale callback after [clear], a peer that never bound at all — is a no-op, so the
     * incumbent survives.
     */
    public fun unbind(
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

/**
 * A fabric refused an identity a remote announced for itself.
 *
 * Raised where peer-supplied bytes are turned into a [PeerId] and the result is not admissible —
 * a blank id, this peer's own [PeerId], an id a different device already holds, or bytes that are
 * not valid UTF-8 at all. The connection carrying it is not a peer and the handshake fails with
 * this rather than resolving onto a fabricated identity (#1821).
 *
 * The message names the [reason], never the announced bytes: they are attacker-controlled and may
 * be arbitrary, and the [endpoint] the fabric already knows is the useful half for correlating a
 * refusal with a log line.
 */
public class PeerIdentityRejectedException(
    /** The fabric's own handle for the connection whose announcement was refused. */
    public val endpoint: String,
    /** Why the announcement was refused, in the fabric's own words. */
    public val reason: String,
) : Exception("peer identity announced on $endpoint refused: $reason")
