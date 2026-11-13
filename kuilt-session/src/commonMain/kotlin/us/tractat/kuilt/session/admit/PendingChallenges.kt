package us.tractat.kuilt.session.admit

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.admit.AdmitMessage.Hello
import kotlin.time.Instant

/**
 * The host's table of challenges it has sent and not yet seen answered: one entry per fabric sender.
 *
 * When a joiner says Hello, the host challenges it and must remember what it asked — the Hello and the
 * nonce it chose — until the joiner's Proof comes back. This table is that memory, and it is kept small
 * on purpose:
 *
 * - **One entry per fabric sender.** A newer Hello from the same sender overwrites the older entry.
 *   The key is the sender the fabric reports, never an id a frame claims, so a bystander can only ever
 *   overwrite its own entry and cannot lock a member out by flooding Hellos in that member's name.
 * - **Each entry expires at its deadline** (the room sets it from `admitTimeout`). Expiry is lazy: an
 *   expired entry is removed when a [take] or [isCurrent] finds it, and every [open] sweeps all expired
 *   entries. No timer runs and the table owns no scope, so between opens an expired entry may still be
 *   held — invisible to [take] and [isCurrent], counted only by [retained].
 * - **Every [open] mints a fresh generation**, unique across the whole table rather than counted per
 *   sender. A host that suspends between taking an entry and acting on it (a policy decision) checks
 *   [isCurrent] afterwards and discards its result if a later open, a [drop] or the deadline overtook
 *   it. Because generations are never reused, a sender dropped and then opened again cannot hand a
 *   stale decision the number it was waiting on.
 *
 * A [take] answers a challenge once: it hands out the entry and marks it taken, so a second Proof for
 * the same challenge finds nothing. The taken entry stays in the table — still current — so that the
 * decision in flight can tell whether it was superseded, until a later open replaces it, [drop] removes
 * it or its deadline passes.
 *
 * Thread-safe: every operation runs under one lock and none suspends, so a room on a multi-threaded
 * dispatcher may call it from anywhere.
 *
 * @param clock the room's injected clock; deadlines are compared against it and nothing else.
 */
internal class PendingChallenges(private val clock: () -> Instant) {

    /**
     * A challenge handed out by [take]. [generation] identifies this open of [sender]'s entry for
     * [isCurrent]; [hello] and [hostNonce] are what the Proof is checked against.
     *
     * Not a data class: it carries a [ByteArray], whose `equals` is identity.
     */
    internal class Pending(
        val generation: Long,
        val hello: Hello,
        val hostNonce: ByteArray,
        val deadline: Instant,
    )

    private class Entry(val pending: Pending) {
        var taken: Boolean = false
    }

    private val lock = reentrantLock()

    // All guarded by [lock].
    private val entries = HashMap<PeerId, Entry>()
    private var nextGeneration = 0L

    /**
     * How many entries the table holds, **including expired ones no sweep or lookup has removed yet**.
     * For tests and diagnostics: it is the one reading that does not expire lazily.
     */
    val retained: Int get() = lock.withLock { entries.size }

    /**
     * Records a challenge sent to [sender] in answer to [hello], replacing any entry [sender] already
     * had, and first sweeps every expired entry of every sender. The entry stops being takeable or
     * current at [deadline]. [hostNonce] is copied.
     */
    fun open(sender: PeerId, hello: Hello, hostNonce: ByteArray, deadline: Instant) {
        val now = clock()
        lock.withLock {
            entries.values.removeAll { it.pending.expiredAt(now) }
            val generation = ++nextGeneration
            entries[sender] = Entry(Pending(generation, hello, hostNonce.copyOf(), deadline))
        }
    }

    /**
     * The challenge pending for [sender], or `null` when there is none, it has expired, or it was
     * already taken. A non-null result marks the entry taken; it stays current (see [isCurrent]).
     */
    fun take(sender: PeerId): Pending? {
        val now = clock()
        return lock.withLock {
            val entry = liveEntry(sender, now) ?: return@withLock null
            if (entry.taken) return@withLock null
            entry.taken = true
            entry.pending
        }
    }

    /**
     * Whether [generation] is still [sender]'s live entry: `false` once a later [open] by [sender]
     * replaced it, [drop] removed it, or its deadline passed.
     */
    fun isCurrent(sender: PeerId, generation: Long): Boolean {
        val now = clock()
        return lock.withLock { liveEntry(sender, now)?.pending?.generation == generation }
    }

    /** Removes [sender]'s entry, whatever its generation — for a sender that left or said Goodbye. */
    fun drop(sender: PeerId) {
        lock.withLock { entries.remove(sender) }
    }

    /** [sender]'s entry if it has not expired; an expired one is removed. Call under [lock]. */
    private fun liveEntry(sender: PeerId, now: Instant): Entry? {
        val entry = entries[sender] ?: return null
        if (entry.pending.expiredAt(now)) {
            entries.remove(sender)
            return null
        }
        return entry
    }

    private fun Pending.expiredAt(now: Instant): Boolean = now >= deadline
}
