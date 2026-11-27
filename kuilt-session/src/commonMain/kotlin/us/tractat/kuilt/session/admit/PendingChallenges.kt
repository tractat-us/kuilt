package us.tractat.kuilt.session.admit

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
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
 *   [isCurrent] afterwards, or finishes with [retire], and discards its result if a later open, a
 *   [drop] or the deadline overtook it. Because generations are never reused, a sender dropped and then
 *   opened again cannot hand a stale decision the number it was waiting on.
 *
 * A [take] answers a challenge once: it hands out the entry and marks it taken, so a second Proof for
 * the same challenge finds nothing. The taken entry stays in the table — still current — so that the
 * decision in flight can tell whether it was superseded, until a later open replaces it, [retire] or
 * [drop] removes it, or its deadline passes.
 *
 * Thread-safe: every operation runs under one lock and none suspends, so a room on a multi-threaded
 * dispatcher may call it from anywhere.
 *
 * @param clock the room's injected clock; deadlines are compared against it and nothing else.
 */
internal class PendingChallenges(private val clock: () -> Instant) {

    /**
     * A challenge handed out by [take]. [generation] identifies this open of [sender]'s entry for
     * [isCurrent]; [keyed] — the Hello, with the key and nonce the gate found in it — and [hostNonce]
     * are what the Proof is checked against. [principal] is who the fabric attested the sender's link
     * as when it said Hello, or null when nothing did: the link the whole exchange is about, which a
     * host compares with the link the id reaches at every step that acts on the exchange.
     *
     * Not a data class: it carries a [ByteArray], whose `equals` is identity.
     */
    internal class Pending(
        val generation: Long,
        val keyed: KeyedHello,
        val hostNonce: ByteArray,
        val deadline: Instant,
        val principal: Principal?,
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
     * Records a challenge sent to [sender] in answer to [keyed], replacing any entry [sender] already
     * had, and first sweeps every expired entry of every sender. The entry stops being takeable or
     * current at [deadline]. [hostNonce] is copied. [principal] is the sender's link's attested
     * principal as the Hello found it, kept on the entry ([Pending.principal]); required, so the
     * caller says what the link was. Returns the new entry's generation, for a caller that must
     * [retire] it without first taking it.
     */
    fun open(sender: PeerId, keyed: KeyedHello, hostNonce: ByteArray, deadline: Instant, principal: Principal?): Long {
        val now = clock()
        return lock.withLock {
            entries.values.removeAll { it.pending.expiredAt(now) }
            val generation = ++nextGeneration
            entries[sender] = Entry(Pending(generation, keyed, hostNonce.copyOf(), deadline, principal))
            generation
        }
    }

    /**
     * The challenge pending for [sender], or `null` when there is none, it has expired, or it was
     * already taken. A non-null result marks the entry taken; it stays current (see [isCurrent]).
     *
     * [answering] is the host nonce the Proof says it answers, or null when it does not decode far
     * enough to say; the caller passes what it read either way, so no call site can forget it. A
     * Proof naming another nonce answers an **older** challenge — one a newer Hello from the same
     * sender replaced — and leaves this entry untaken, so the answer to the current challenge still
     * finds it (#3159, Task 1.7). A joiner that says Hello again sends such a proof in good faith,
     * and a refusal would end it; only the sender itself can write under its fabric id, so ignoring
     * the proof hides nothing.
     */
    fun take(sender: PeerId, answering: ByteArray?): Pending? {
        val now = clock()
        return lock.withLock {
            val entry = liveEntry(sender, now) ?: return@withLock null
            if (entry.taken) return@withLock null
            if (answering != null && !answering.contentEquals(entry.pending.hostNonce)) return@withLock null
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

    /**
     * Removes [sender]'s entry, whatever its generation — for a sender that said Goodbye or whose peer
     * was lost.
     *
     * Unconditional is correct there because those events arrive in order on the sender's own link: a
     * Goodbye is handled after every Hello the sender sent before it, so there is no newer entry the
     * drop could wrongly remove. It is **not** correct after a decision that suspended — use [retire] —
     * nor for a departure read from the peer list, which is not ordered against the sender's frames —
     * use [dropOpenedBy].
     */
    fun drop(sender: PeerId) {
        lock.withLock { entries.remove(sender) }
    }

    /**
     * The newest generation any [open] has minted so far, or `0` before the first. Every entry opened
     * before this call has a generation at or below it, and every entry opened after it one above, so
     * a caller can name "the entries that existed when I looked" without holding the table's lock.
     */
    fun mark(): Long = lock.withLock { nextGeneration }

    /**
     * [mark], then [read], in that order: every entry at or below the returned mark was opened before
     * [read] ran. The order is the point — marked after the read, an entry opened in between would
     * fall below the mark and be dropped as if the read had seen it. [read] runs outside the table's
     * lock, so it may block or take another lock.
     */
    inline fun <T> markBefore(read: () -> T): Pair<Long, T> {
        val mark = mark()
        return mark to read()
    }

    /**
     * Removes [sender]'s entry only if it was opened at or before [mark] (see [mark]); a newer entry is
     * kept.
     *
     * For a drop decided from something read at [mark] time that may be stale by the time the drop
     * runs: the route watcher reads the peer list, finds a sender gone, and acts on it later. A sender
     * that came back and said Hello in between has a newer entry, which belongs to its new link and
     * must survive (#3610).
     */
    fun dropOpenedBy(sender: PeerId, mark: Long) {
        lock.withLock {
            val entry = entries[sender] ?: return@withLock
            if (entry.pending.generation <= mark) entries.remove(sender)
        }
    }

    /**
     * Removes [sender]'s entry only if it is still [generation] and has not expired, as one step under
     * the table's lock, and returns whether it did.
     *
     * For finishing a decision that suspended between [take] and here, such as a policy call on another
     * coroutine. `isCurrent` followed by [drop] is two separate lock acquisitions: a re-Hello's [open]
     * can land between them, and the drop would then delete the newer generation, so the joiner's next
     * Proof finds nothing and is refused. `false` means the decision is stale — a later open replaced
     * the entry, a drop removed it, or its deadline passed (an expired entry is removed regardless).
     */
    fun retire(sender: PeerId, generation: Long): Boolean {
        val now = clock()
        return lock.withLock {
            val entry = liveEntry(sender, now)
            if (entry?.pending?.generation != generation) return@withLock false
            entries.remove(sender)
            true
        }
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
