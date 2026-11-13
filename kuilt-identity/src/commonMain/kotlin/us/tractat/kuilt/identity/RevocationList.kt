package us.tractat.kuilt.identity

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * A host's record of the devices it no longer lets back in on an old pass.
 *
 * A host gave a friend's phone a [Grant], and the phone is lost. Revoking the phone's key here makes
 * every grant the host issued it *before* that moment worthless, so the phone cannot come back on
 * one; [GrantTrust] answers [GrantVerdict.Revoked] for it. A grant issued *after* the revocation
 * still works, so a device that is welcome again simply gets a fresh pass.
 *
 * Revoking blocks the next return; it does not remove a device that is in the room now. That is the
 * app's separate call.
 *
 * One entry per subject key: its cutoff, `notBefore`. A grant with `issuedAt < notBefore` is revoked.
 * The host keeps no grants, only these cutoffs. A cutoff only ever moves later: [revoke] keeps the
 * larger of the old and new cutoffs, so a clock that has stepped backwards, or a replayed call,
 * cannot un-revoke anyone.
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
     * Revokes every grant issued to [subject] before [notBefore], in epoch milliseconds. Usually the
     * host's clock now, so every pass handed out so far stops working and the next one works.
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
        /** An empty list held in memory, which forgets every revocation when the process ends. */
        public fun inMemory(): RevocationList = RevocationList()
    }
}
