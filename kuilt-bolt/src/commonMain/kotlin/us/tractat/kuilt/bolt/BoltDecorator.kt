package us.tractat.kuilt.bolt

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.LogOp

/**
 * Feeds a [Bolt] the operations a CRDT owner applied, and suppresses the ones it has already
 * archived.
 *
 * This is the wiring, and it is deliberately the *only* wiring. The owner of the replica — a log
 * exporter, a document, anything holding an `Rga` or a `Fugue` — publishes the operations it
 * applied and knows nothing about archiving; this class knows nothing about what the operations
 * mean. So one decorator serves every op-log owner, and neither side has to learn about the other.
 *
 * ### Two paths reach here, and the second one is the whole point
 *
 * A replica's *local* edits already come with their operations in hand, so teeing those is
 * straightforward. **A merge does not.** Absorbing a peer's replica is a state join: it produces no
 * operation stream at all. And gossip is exactly how a phone's records reach a server — so an
 * archive fed only by the local path holds a server's own history and **none** of the history that
 * arrived from the devices, which is the one capability the module exists to open. The owner must
 * therefore publish the remote replica's operations on the merge path too, by enumerating them
 * through `OpLogCrdt.operations()`.
 *
 * ### Which is why deduplication is not optional
 *
 * Anti-entropy re-offers the same remote log every round. Without suppression, each round writes
 * one full copy of the peer's log, so an archive that is supposed to grow with *history* would grow
 * with *time spent gossiping*. An append-only log gives no dedup for free.
 *
 * ### Inserts and removes are recognised by different things, and cost different amounts
 *
 * An `Insert` mints exactly one causal [Dot]. A `Remove` mints none — it reuses its target insert's
 * id — so the two need different handles, and the difference is the whole memory story:
 *
 * - **Inserts are suppressed from a [DotFrontier]**, which holds contiguous runs of archived `seq`
 *   per author. One peer's entire live log is *one entry*, however many operations it holds. There
 *   is no working set to exceed and no cliff to fall off: an insert this decorator has archived is
 *   suppressed for the life of the process, at O(1) memory. Inserts are the bulk of any log.
 * - **Removes are suppressed from a bounded LRU set of [LogOp] identities**, because there is no
 *   dot to key them on. This is the residual, and it is bounded by [removalWindow].
 *
 * **The residual, stated with its bound.** Suppression of *removes* is total only while the
 * aggregate offered working set of removes — every peer's live tombstones, plus this replica's own
 * export stream, since one window serves them all — fits in [removalWindow]. Past that the window
 * thrashes and the archive grows by roughly `Σ(offered removes) − removalWindow` operations per
 * round. Removes are the minority of any log, and are themselves collected by the source's own
 * compaction, so this is a materially smaller residual than one sized by every operation — but it
 * is a residual, and raising [removalWindow] moves that cliff rather than removing it.
 *
 * The frontier has a bound too, in [frontierWindow], but it counts *runs* rather than operations:
 * one per author, plus one for each hole that has not filled. Past it the shortest run is evicted
 * and the inserts it covered are archived a second time.
 *
 * All of it is sound because a suppression miss costs *bytes*, never correctness: a duplicate
 * operation in the archive replays as a duplicate operation, and folding an op-log CRDT's operation
 * twice is idempotent. The frontier is careful to keep the error on that side — see [DotFrontier].
 *
 * ### Nothing is rebuilt on open, and the frontier does not survive a restart either
 *
 * The design sketch this class grew from proposed an unbounded set of archived identities, rebuilt
 * from the archive's tail whenever a process opens one. Both halves are unaffordable for the same
 * reason — **a bolt's archive is unbounded by construction, so nothing sized against it may be held
 * in memory or read at startup**.
 *
 * So a new process starts with an empty frontier and an empty removal window, and that is a
 * deliberate choice rather than an omission. It costs at most one extra copy of each peer's live
 * log per process start — bounded, one-off, and cheaper than reading an unbounded archive to avoid
 * it. Persisting the frontier is possible (it is small and serialisable) and is *not* done here: it
 * would put a second durable thing beside the archive that has to stay consistent with it across
 * crashes, to save a cost that is already bounded. If that trade ever changes, it changes as its own
 * issue.
 *
 * A restart is also why the frontier holds runs rather than a high-water mark: the second process
 * meets each peer part-way through that peer's sequence, so there is a hole below first contact that
 * will never fill. [DotFrontier] leaves it open at no cost.
 *
 * ### Ordering: this runs BEFORE the owner's durable write, on purpose
 *
 * The owner publishes as soon as it has applied the operations, which is earlier than its own
 * durable write returns. So a failed write leaves the archive holding a record the owner's store
 * does not. That asymmetry is the right way round — an archive that is a **superset** of the live
 * replica is the product; one that is a subset is a silent hole — but it is a property, not an
 * accident, and it is pinned by a test rather than left to be rediscovered.
 *
 * ### Never throws (except cancellation), and never fails its caller
 *
 * [publish] reports through its return value and [health]; a full archive disk must not take down
 * the application whose telemetry it is archiving. A caller that wants to act on a refusal reads
 * the returned [AppendResult] — which carries the lost frame's identities, never a tally.
 *
 * That holds even against a **misbehaving backend**. [Bolt.append] promises not to throw *for an
 * I/O failure*, which is narrower than not throwing at all, and [Bolt] is a public, pluggable
 * interface — a backend wrapping a network or a database can throw anything. [publish] converts
 * such a throw into [AppendResult.Failed] carrying the operations' dots, so identities that would
 * otherwise vanish reach [health] like any other refusal.
 *
 * `CancellationException` is the one thing that still propagates, and it must: swallowing it would
 * turn a structured-concurrency cancel into a silent no-op. The claim is released first, so a
 * cancelled publish leaves nothing reserved behind.
 *
 * @param bolt the archive to feed.
 * @param format how operations are classified and encoded — the same value the [bolt] was built
 *   with. Classification is what separates content from records of forgetting, supplies the causal
 *   dot an insert is recognised by, and supplies the identity a repeated removal is recognised by.
 * @param removalWindow how many **removal** identities to remember, evicted least-recently-offered
 *   first. Inserts do not consume it — they are suppressed from the frontier — so size it to the
 *   aggregate live *tombstones* you expect to be offered rather than to whole logs. `0` disables
 *   removal suppression, which is only ever right for an owner that never merges.
 * @param frontierWindow how many contiguous runs of archived insert dots to remember: one per
 *   author while that author's dots arrive densely, one more for each hole that has not filled. It
 *   is **not** sized by operations — a peer's whole log is one run — so the default covers a fleet
 *   far larger than any [removalWindow] could.
 */
public class BoltDecorator<Id : Any, V, Op : Any>(
    private val bolt: Bolt<Op>,
    private val format: BoltArchiveFormat<Id, V, Op>,
    private val removalWindow: Int = DEFAULT_REMOVAL_WINDOW,
    frontierWindow: Int = DEFAULT_FRONTIER_WINDOW,
) {

    init {
        require(removalWindow >= 0) { "removalWindow must not be negative, was $removalWindow" }
        require(frontierWindow >= 0) { "frontierWindow must not be negative, was $frontierWindow" }
    }

    /**
     * Guards [frontier], [inFlight] and [archived].
     *
     * An explicit lock rather than dispatcher confinement: an owner may publish from any thread,
     * and correctness here must not depend on where coroutines happen to run. Nothing inside a
     * locked section suspends — in particular [Bolt.append] is called with the lock **released**,
     * which is what the reservation protocol in [publish] is written around.
     */
    private val lock = reentrantLock()

    /**
     * Which inserts have been archived, as runs of causal `seq` per author — O(authors) + O(open
     * holes), never O(archived operations). See [DotFrontier] for why runs rather than a high-water
     * mark plus a gap set.
     */
    private val frontier = DotFrontier(frontierWindow)

    /**
     * Insert dots claimed by an append that has not returned yet.
     *
     * The frontier records what was *archived*, so it cannot also carry a reservation — an entry
     * added before the append would have to be un-added on a failure, and un-adding a `seq` that
     * has already merged into a run means splitting the run back apart. Holding the in-flight dots
     * separately makes both outcomes trivial: commit moves them into the frontier, failure drops
     * them. It is bounded by the operations in concurrently-running publishes, and every exit path
     * from [publish] — including cancellation — empties its own.
     */
    private val inFlight = HashSet<Dot>()

    /**
     * The **removal** identities already archived, least recently offered first — an
     * insertion-ordered set whose order [claimIdentity] refreshes on every hit, so [trimToWindow]
     * evicts by recency of *offer* rather than by age of first archive.
     *
     * Only `LogOp.Remove` reaches here. An `Insert` is recognised by the dot it mints and lives in
     * [frontier]; a `LogOp.Compact` names a *set* of ids rather than one, so it has no single
     * identity — and the archive discards it anyway.
     */
    private val archived = LinkedHashSet<LogOp<Id>>()

    private val healthState = MutableStateFlow(ArchiveHealth())

    /**
     * What this decorator has archived, and what it could not — see [ArchiveHealth].
     *
     * A `MutableStateFlow` owns no `CoroutineScope`, so this adds no scope ownership to the type,
     * and `update {}` is an atomic CAS loop rather than dispatcher confinement.
     */
    public val health: StateFlow<ArchiveHealth> = healthState.asStateFlow()

    /**
     * Archive whatever of [ops] has not been archived already.
     *
     * Returns what the underlying [Bolt.append] did — [AppendResult.Skipped] when every operation
     * was a compaction record or a duplicate, so nothing was offered to the archive at all.
     *
     * ### The reservation protocol, and why it is not simply "append, then remember"
     *
     * Identities are claimed **before** the append and released again if it fails. Two concurrent
     * publishes of the same operation therefore cannot both write it, which "append, then
     * remember" would allow — the window between the two steps is a suspending call, and holding a
     * thread-blocking lock across one is banned here.
     *
     * A released identity is re-offered by the next anti-entropy round and archived then, so a
     * failure is self-healing rather than permanent — for a refusal *and* for a backend that threw,
     * which is converted to one. There is one visible consequence, stated rather than hidden: a
     * *second* publish that arrived during a *first* one's failed append skipped the operation as
     * claimed, so that round archived nothing either. The identities it lost are on the returned
     * [AppendResult.Failed] and on [health], and the operation is claimed again on the next round.
     */
    public suspend fun publish(ops: List<Op>): AppendResult {
        // Classification and dot projection are pure and non-trivial, so they happen with no lock
        // held — which is also why the key is computed once here rather than twice under the lock.
        val identified = ops.mapNotNull { op -> archiveKeyOf(op)?.let { op to it } }
        if (identified.isEmpty()) return AppendResult.Skipped

        val reserved = lock.withLock { identified.filter { (_, key) -> claim(key) } }
        val suppressed = identified.size - reserved.size
        if (reserved.isEmpty()) {
            record(AppendResult.Skipped, suppressed)
            return AppendResult.Skipped
        }

        val result = appendOrConvert(reserved)

        lock.withLock {
            if (result is AppendResult.Written) commit(reserved) else release(reserved)
        }
        record(result, suppressed)
        return result
    }

    /** How [op] is recognised as already-archived, or `null` if it is a record of forgetting. */
    private fun archiveKeyOf(op: Op): ArchiveKey<Id>? = when (val classified = format.classifyOp(op)) {
        is LogOp.Insert -> ArchiveKey.MintedDot(format.dotOf(classified.id))
        is LogOp.Remove -> ArchiveKey.Identity(classified)
        is LogOp.Compact -> null
    }

    /**
     * Claim [key] for an append about to happen, answering whether it was free. Called under [lock].
     *
     * An insert is free when the frontier has not archived its dot and no concurrent publish holds
     * it. A remove goes through [claimIdentity], which also refreshes the LRU order.
     */
    private fun claim(key: ArchiveKey<Id>): Boolean = when (key) {
        is ArchiveKey.MintedDot -> !frontier.contains(key.dot) && inFlight.add(key.dot)
        is ArchiveKey.Identity -> claimIdentity(key.identity)
    }

    /**
     * Claim a removal [identity] — or, if it is already claimed, **refresh** it to the young end of
     * the window and answer `false`. Called under [lock].
     *
     * The refresh is what makes [removalWindow] an LRU bound rather than a FIFO one, and it is the
     * difference between suppressing a gossiping peer forever and suppressing it for
     * `removalWindow / rate` rounds. `LinkedHashSet.add` on a present element returns `false` and
     * leaves its position **unchanged**, so without the explicit remove-then-add a peer's
     * identities march toward the head on a clock set by everyone else's traffic and are evicted
     * while that peer is still re-offering every one of them. Deleting these two lines restores
     * exactly that; [BoltDecoratorTest] pins it at a window boundary, because at any other size the
     * two orders are indistinguishable.
     */
    private fun claimIdentity(identity: LogOp<Id>): Boolean {
        if (archived.add(identity)) return true
        archived.remove(identity)
        archived.add(identity)
        return false
    }

    /**
     * [Bolt.append] the claimed operations, converting a **thrown** failure into
     * [AppendResult.Failed] so its identities are reported rather than lost.
     *
     * [Bolt.append]'s contract is "never throws *for an I/O failure*" — narrower than never throws,
     * and [Bolt] is public and pluggable, so a backend over a network or a database can raise
     * anything. Left to propagate, such a throw would exit [publish] with the claims still held and
     * without reaching [record]: the operations would be absent from the archive, never re-offered
     * on the export path (each is published exactly once), and invisible on [health]. That is
     * "lost from both sides, silently" — the one outcome this module's failure surface exists to
     * make impossible.
     *
     * Cancellation is the exception and propagates, because a structured-concurrency cancel is not
     * an archive failure. It releases the claim on its way out: nothing was written, so nothing may
     * stay reserved, and the operations are re-offered normally on the next round.
     */
    private suspend fun appendOrConvert(reserved: List<Pair<Op, ArchiveKey<Id>>>): AppendResult {
        val content = reserved.map { (op, _) -> op }
        return try {
            bolt.append(content)
        } catch (cancellation: CancellationException) {
            lock.withLock { release(reserved) }
            throw cancellation
        } catch (failure: Throwable) {
            AppendResult.Failed(
                reason = "the archive backend threw rather than reporting a refusal: $failure",
                insertDots = format.insertDotsOf(content),
                // The backend threw, so it reported no extent. `AppendResult.Failed` allows a null
                // offset for exactly this: an archive that could not say where the frame would go.
                offset = null,
                cause = failure,
            )
        }
    }

    /**
     * Move the claims an append honoured into the durable memory, then trim both bounds. Called
     * under [lock].
     *
     * Trimming happens once per committed append rather than once per dot: a batch that closes
     * several holes at once is measured after they have merged rather than while it is transiently
     * over the cap, which is the difference between evicting nothing and evicting a run that was
     * about to disappear anyway.
     */
    private fun commit(reserved: List<Pair<Op, ArchiveKey<Id>>>) {
        reserved.forEach { (_, key) ->
            if (key is ArchiveKey.MintedDot) {
                inFlight.remove(key.dot)
                frontier.add(key.dot)
            }
        }
        frontier.trim()
        trimToWindow()
    }

    /** Give back the claims an append did not honour. Called under [lock]. */
    private fun release(reserved: List<Pair<Op, ArchiveKey<Id>>>) {
        reserved.forEach { (_, key) ->
            when (key) {
                is ArchiveKey.MintedDot -> inFlight.remove(key.dot)
                is ArchiveKey.Identity -> archived.remove(key.identity)
            }
        }
    }

    /**
     * Drop the least recently offered removal identities until the set is back inside
     * [removalWindow]. Called under [lock].
     */
    private fun trimToWindow() {
        if (archived.size <= removalWindow) return
        val iterator = archived.iterator()
        while (archived.size > removalWindow && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun record(result: AppendResult, suppressed: Int) {
        // Read ONCE, before the update, and deliberately not inside the lambda: `update` re-runs its
        // block on a losing compare-and-set, and this call takes the backend's own lock. It is also
        // read on every outcome including `Skipped` — a doubt raised by an earlier append does not
        // stop being true because this one carried nothing new.
        val durability = bolt.durability()
        healthState.update { current ->
            val withSuppression = current.copy(
                opsDeduplicated = current.opsDeduplicated + suppressed,
                durability = durability,
            )
            when (result) {
                is AppendResult.Written -> withSuppression.copy(
                    framesWritten = current.framesWritten + 1,
                    opsArchived = current.opsArchived + result.opCount,
                )
                is AppendResult.Failed -> withSuppression.copy(
                    appendsFailed = current.appendsFailed + 1,
                    // Bounded: a permanently full archive fails every append forever, so keeping
                    // them all would leak on exactly the path already going wrong.
                    recentFailures = (current.recentFailures + result).takeLast(RETAINED_FAILURES),
                )
                AppendResult.Skipped -> withSuppression
            }
        }
    }

    public companion object {
        /**
         * How many **removal** identities are remembered by default.
         *
         * Unchanged from the all-operations window it replaces, so no consumer's suppression gets
         * worse — but it now holds only removes, so the same memory covers many times the traffic.
         * A miss costs one duplicated operation in the archive, so the number is a space/bytes
         * trade with no correctness edge to fall off.
         *
         * **It is still a default, not a bound that scales.** Removes are the minority of a log and
         * the source's own compaction collects them, so a fleet has to be large before this is the
         * binding constraint — but it is bounded by operations, and inserts are not. Raise it
         * deliberately against the sizing rule on [removalWindow] rather than assuming it covers a
         * fleet.
         */
        public const val DEFAULT_REMOVAL_WINDOW: Int = 65_536

        /**
         * How many contiguous runs of archived insert dots are remembered by default.
         *
         * Counted in *runs*, not operations: an author whose dots arrive densely costs one entry
         * however long its log is, and one more for each hole that has not filled. So this is
         * really "how many peers, plus how much delivery fragmentation" — and 4,096 is a fleet far
         * larger than any archive built on this module is expected to gossip with, at a few hundred
         * kilobytes.
         *
         * Past it the shortest run is evicted and the inserts it covered are archived a second
         * time, which costs bytes and never correctness.
         */
        public const val DEFAULT_FRONTIER_WINDOW: Int = 4_096

        /**
         * How many [AppendResult.Failed] values [ArchiveHealth.recentFailures] retains.
         *
         * Enough to diagnose a burst; small enough that a permanently failing archive cannot grow
         * a list of its own failures without bound.
         */
        public const val RETAINED_FAILURES: Int = 32
    }
}

/**
 * How one offered operation is recognised as already archived.
 *
 * The split is not a stylistic one — it is the difference between O(1) memory per author and O(1)
 * memory per operation. An `Insert` mints exactly one causal [Dot], so a frontier of archived dots
 * answers for it. A `Remove` mints none (it reuses its target insert's id), so nothing but its
 * whole [LogOp] identity distinguishes a repeated removal from a new one, and identities have to be
 * remembered one at a time.
 */
private sealed interface ArchiveKey<Id> {

    /** An `Insert`, recognised by the dot it minted. */
    class MintedDot<Id>(val dot: Dot) : ArchiveKey<Id>

    /** A `Remove`, recognised by the whole classification — there is no dot of its own to use. */
    class Identity<Id>(val identity: LogOp<Id>) : ArchiveKey<Id>
}
