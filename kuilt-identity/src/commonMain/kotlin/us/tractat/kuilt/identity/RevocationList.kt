package us.tractat.kuilt.identity

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Revoking voids passes; it is not a ban. A revoked device that knocks again is a newcomer, and the
 * host's admission policy decides it like any other. A cutoff cannot be taken back, so an app that
 * means to keep a device out keeps its own blocked set, which it can undo, and refuses on that.
 *
 * Revoking stops a return on an old pass; it does not remove a device that is in the room now. That is
 * the app's separate call.
 *
 * One entry per subject key: its cutoff, `notBefore`. A grant with `issuedAt < notBefore` is revoked,
 * and one issued exactly at the cutoff is not. The host keeps no grants, only these cutoffs, so
 * choosing the cutoff is the caller's job: see [revoke].
 *
 * A cutoff only ever moves later: [revoke] keeps the larger of the old and new cutoffs, so a replayed
 * or reordered call cannot move one earlier. That is all it guarantees. It does not make a cutoff
 * read from a clock that has since stepped backwards cover the grants issued before the step.
 *
 * Two ways to hold one. [inMemory] forgets every revocation with the process, so a revoked device
 * comes back on its old pass after the host restarts. [open] keeps the list in a [DurableStore], so
 * it survives the restart. The two ways to lose state go in opposite directions: losing the store
 * forgets every revocation, which is dangerous; losing the host's key makes every grant
 * [GrantVerdict.UntrustedIssuer], which is safe.
 *
 * Safe under a multi-threaded dispatcher: one lock guards the cutoffs, and nothing suspends while it
 * is held. A stored list also serialises its writes, below.
 *
 * @sample us.tractat.kuilt.identity.sampleGrantTrust
 * @sample us.tractat.kuilt.identity.sampleDurableRevocations
 */
public class RevocationList private constructor(
    private val keeping: Keeping,
    initial: Map<DidKey, Long>,
) {
    private val lock = reentrantLock()
    private val cutoffs = HashMap(initial)

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
     * A list from [open] returns only once the whole list, this cutoff included, is written to its
     * store. The cutoff holds in this process before its write starts, so a write that fails or never
     * returns does not let the device in here. If the write throws, so does `revoke`, and what the
     * store holds is unknown; calling `revoke` again writes the whole list again, so a retry makes it
     * good.
     *
     * Concurrent calls write one at a time, in the order they merged, and each waits for its write to
     * return before the next starts, even when it is cancelled. That keeps an older list from landing
     * on top of a newer one, with one exception the store's contract leaves open: a write that
     * *throws* may still commit later, and if it does, it can land on top of a list written after it.
     * Only a later `revoke` whose write commits after that repairs it.
     *
     * The cost of waiting through cancellation: on a list from [open], `revoke` is not cancellable
     * while its write runs. A store whose write never returns makes `revoke` never return, cancelled
     * or not, and holds up every later `revoke` on the list. A host shutting down gets its last
     * revocation written rather than abandoned.
     */
    public suspend fun revoke(subject: DidKey, notBefore: Long) {
        when (keeping) {
            Keeping.InMemory -> merge(subject, notBefore)
            // The writes lock is held across the merge AND the write. Merged under `lock` alone, two
            // calls could each take a snapshot and the older could reach the store last, dropping the
            // newer revocation from it: the dangerous direction. `lock` cannot be the one held, since
            // the write suspends and nothing may suspend under it.
            is Keeping.InStore -> keeping.writes.withLock {
                val snapshot = merge(subject, notBefore)
                // Shielded, so cancelling this call cannot release `writes` while the write is still in
                // flight: a store may commit after its caller stops waiting, and the next revoke's newer
                // list would then be overwritten by this older one.
                withContext(NonCancellable) {
                    keeping.store.write(keeping.key, RevocationListLayout.encode(snapshot))
                }
            }
        }
    }

    /** Records the cutoff, keeping the later of old and new, and returns the whole list as it now is. */
    private fun merge(subject: DidKey, notBefore: Long): Map<DidKey, Long> = lock.withLock {
        val held = cutoffs[subject]
        if (held == null || notBefore > held) cutoffs[subject] = notBefore
        cutoffs.toMap()
    }

    /** Where a list's cutoffs are kept beyond its own memory. */
    private sealed interface Keeping {
        data object InMemory : Keeping

        class InStore(val store: DurableStore, val key: StoreKey) : Keeping {
            /** Held across a merge and its write, so writes reach the store in merge order. */
            val writes = Mutex()
        }
    }

    public companion object {
        /** The key [open] keeps the list under unless told otherwise. */
        public val DEFAULT_STORE_KEY: StoreKey = StoreKey("kuilt.identity.revocations")

        /** An empty list held in memory, which forgets every revocation when the process ends. */
        public fun inMemory(): RevocationList = RevocationList(Keeping.InMemory, emptyMap())

        /**
         * The list kept in [store] under [key], with every revocation it held when the host last
         * wrote it. Each [revoke] on it writes the whole list back before returning.
         *
         * Open one list per store key per process. Two lists over one key do not see each other's
         * revocations, and each write replaces the other's.
         *
         * Nothing under [key] opens an empty list. That is also what a lost or wiped store looks
         * like, and it forgets every revocation: a revoked device comes back on its old pass. Keep
         * the store at least as well as the grants it guards.
         *
         * @throws IllegalStateException naming [key] when the bytes there are not a revocation list.
         *   Opening a damaged list as empty would forget every revocation without a word, so it does
         *   not; the bytes are left as they are.
         * @throws Throwable whatever [DurableStore.read] throws, unchanged. A store that cannot be
         *   read is not an empty one.
         */
        public suspend fun open(store: DurableStore, key: StoreKey = DEFAULT_STORE_KEY): RevocationList {
            val bytes = store.read(key)
            val cutoffs = if (bytes == null) {
                emptyMap()
            } else {
                checkNotNull(RevocationListLayout.decode(bytes)) {
                    "the ${bytes.size} bytes under store key \"${key.name}\" are not a revocation list; " +
                        "refusing to open it as empty, which would let every revoked device back in on its old pass"
                }
            }
            return RevocationList(Keeping.InStore(store, key), cutoffs)
        }
    }
}
