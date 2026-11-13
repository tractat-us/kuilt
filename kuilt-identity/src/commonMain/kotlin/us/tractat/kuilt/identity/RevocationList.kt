package us.tractat.kuilt.identity

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey

/**
 * A host's record of the devices it no longer lets back in on an old pass.
 *
 * A host gave a friend's phone a [Grant], and the phone is lost. Revoking the phone's key here, with a
 * cutoff, makes every grant the host issued it *before* the cutoff worthless, so the phone cannot come
 * back on one; [GrantTrust] answers [GrantVerdict.Revoked] for it. A grant issued at or after the
 * cutoff still works, so a device that is welcome again simply gets a fresh pass.
 *
 * Revoking blocks the next return; it does not remove a device that is in the room now. That is the
 * app's separate call.
 *
 * One entry per subject key: its cutoff, `notBefore`. A grant with `issuedAt < notBefore` is revoked,
 * and one issued exactly at the cutoff is not. The host keeps no grants, only these cutoffs, so
 * choosing the cutoff is the caller's job: see [revoke].
 *
 * A cutoff only ever moves later: [revoke] keeps the larger of the old and new cutoffs, so a replayed
 * or reordered call cannot move one earlier. That is all it guarantees. It does not make a cutoff
 * read from a clock that has since stepped backwards cover the grants issued before the step.
 *
 * [inMemory] forgets every revocation with the process, so a revoked device comes back on its old
 * pass after the host restarts. That is the dangerous direction, so a host that issues long-lived
 * grants should keep its revocations somewhere durable.
 *
 * Safe under a multi-threaded dispatcher: one lock guards the cutoffs, and nothing suspends while it
 * is held.
 *
 * @sample us.tractat.kuilt.identity.sampleGrantTrust
 */
public class RevocationList private constructor() {
    private val lock = reentrantLock()
    private val cutoffs = HashMap<DidKey, Long>()

    /**
     * The cutoff for [subject]: grants issued to it before this epoch millisecond are revoked. `null`
     * when [subject] was never revoked.
     */
    public fun notBefore(subject: DidKey): Long? = lock.withLock { cutoffs[subject] }

    /**
     * Revokes every grant issued to [subject] before [notBefore], in epoch milliseconds.
     *
     * [notBefore] must be *later* than every grant you mean to stop, because a grant issued exactly at
     * the cutoff stays good. The host's clock now is not enough: a grant issued in this same
     * millisecond has `issuedAt == now` and survives, which under virtual time is the common case.
     * Use `now + 1`. When you know the latest `issuedAt` you gave [subject], use the later of that
     * plus one and `now + 1`: that also catches a grant issued while the clock ran ahead and was then
     * set back. With only the clock, that case is not handled: a cutoff read after the clock stepped
     * backwards can fall before a grant issued ahead of it, and that grant stays good.
     *
     * A grant issued at or after the cutoff works, so a pass handed out from the cutoff on lets the
     * device back.
     *
     * Monotone: a cutoff earlier than the one already held changes nothing.
     *
     * It is `suspend` so that a list kept in storage can return only once the cutoff is written; this
     * in-memory one never suspends.
     */
    public suspend fun revoke(subject: DidKey, notBefore: Long) {
        lock.withLock {
            val held = cutoffs[subject]
            if (held == null || notBefore > held) cutoffs[subject] = notBefore
        }
    }

    public companion object {
        /** The key [open] keeps the list under unless told otherwise. */
        public val DEFAULT_STORE_KEY: StoreKey = StoreKey("kuilt.identity.revocations")

        /** An empty list held in memory, which forgets every revocation when the process ends. */
        public fun inMemory(): RevocationList = RevocationList()

        /** Not yet persistent: the failing-tests commit. */
        public suspend fun open(store: DurableStore, key: StoreKey = DEFAULT_STORE_KEY): RevocationList = RevocationList()
    }
}
