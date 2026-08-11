package us.tractat.kuilt.bolt

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * with *time spent gossiping*. An append-only log gives no dedup for free, and neither does the
 * frame's dot field: a `Remove` mints no dot of its own — it reuses its target `Insert`'s id — so
 * dots cannot tell a repeated removal from a new one. Identity here is the [LogOp] classification
 * itself, which carries exactly the insert-versus-remove discriminator on the same id that the
 * dots cannot.
 *
 * ### The suppression window is BOUNDED, and the issue's proposal is not what shipped
 *
 * The design sketch proposed an unbounded set of archived identities, rebuilt from the archive's
 * tail whenever a process opens one. Both halves turned out wrong, and the reason is the same in
 * each case — **a bolt's archive is unbounded by construction, so nothing sized against it may be
 * held in memory or read at startup**:
 *
 * - *Unbounded set.* One entry per archived operation, in memory, for the life of the process. A
 *   server keeping a year of history would hold a year of identities beside it — a leak sized by
 *   the very thing the module promises to let grow.
 * - *Rebuild from the tail.* Reading "the tail" means replaying to find it, and a replay of an
 *   unbounded archive is an unbounded startup cost. Rebuilding from a *bounded* tail is affordable
 *   and does not do the job it was proposed for: it cannot suppress anything older than that tail,
 *   which is precisely the case it was meant to cover.
 *
 * So [dedupWindow] bounds the set explicitly and **nothing is rebuilt on open**. Both are sound
 * because a suppression miss costs *bytes*, never correctness: a duplicate operation in the archive
 * replays as a duplicate operation, and folding an op-log CRDT's operation twice is idempotent.
 * The bound is also matched to where duplicates actually come from — a peer re-offers the log it
 * *currently* holds, which its own retention already bounds, so the identities worth remembering
 * are the recent ones. And skipping the rebuild costs at most one extra copy of each peer's live
 * log per process start: bounded, one-off, and cheaper than reading the archive to avoid it.
 *
 * ### Ordering: this runs BEFORE the owner's durable write, on purpose
 *
 * The owner publishes as soon as it has applied the operations, which is earlier than its own
 * durable write returns. So a failed write leaves the archive holding a record the owner's store
 * does not. That asymmetry is the right way round — an archive that is a **superset** of the live
 * replica is the product; one that is a subset is a silent hole — but it is a property, not an
 * accident, and it is pinned by a test rather than left to be rediscovered.
 *
 * ### Never throws, and never fails its caller
 *
 * [publish] reports through its return value and [health]; a full archive disk must not take down
 * the application whose telemetry it is archiving. A caller that wants to act on a refusal reads
 * the returned [AppendResult] — which carries the lost frame's identities, never a tally.
 *
 * @param bolt the archive to feed.
 * @param format how operations are classified and encoded — the same value the [bolt] was built
 *   with. Classification is what separates content from records of forgetting, and what supplies
 *   the identity a duplicate is recognised by.
 * @param dedupWindow how many operation identities to remember. `0` disables suppression, which is
 *   only ever right for an owner that never merges.
 */
public class BoltDecorator<Id : Any, V, Op : Any>(
    private val bolt: Bolt<Op>,
    private val format: BoltArchiveFormat<Id, V, Op>,
    private val dedupWindow: Int = DEFAULT_DEDUP_WINDOW,
) {

    init {
        require(dedupWindow >= 0) { "dedupWindow must not be negative, was $dedupWindow" }
    }

    /**
     * Guards [archived].
     *
     * An explicit lock rather than dispatcher confinement: an owner may publish from any thread,
     * and correctness here must not depend on where coroutines happen to run. Nothing inside a
     * locked section suspends — in particular [Bolt.append] is called with the lock **released**,
     * which is what the reservation protocol in [publish] is written around.
     */
    private val lock = reentrantLock()

    /**
     * The identities already archived, oldest first — an insertion-ordered set so the bound in
     * [trimToWindow] can drop the oldest.
     *
     * A [LogOp] *is* the identity. `LogOp.Insert(id)` and `LogOp.Remove(id)` are distinct values
     * for the same `id`, which is exactly the discriminator a frame's insert-only dots cannot
     * supply. `LogOp.Compact` never reaches here: it names a *set* of ids rather than one, so it
     * has no single identity — and the archive discards it anyway.
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
     * failure is self-healing rather than permanent. There is one visible consequence, stated
     * rather than hidden: a *second* publish that arrived during a *first* one's failed append
     * skipped the operation as claimed, so that round archived nothing either. The identities it
     * lost are on the returned [AppendResult.Failed] and on [health], and the operation is claimed
     * again on the next round.
     */
    public suspend fun publish(ops: List<Op>): AppendResult {
        // Classification is pure and non-trivial, so it happens with no lock held.
        val identified = ops.mapNotNull { op ->
            val classified = format.classifyOp(op)
            if (classified is LogOp.Compact) null else op to classified
        }
        if (identified.isEmpty()) return AppendResult.Skipped

        val reserved = lock.withLock { identified.filter { (_, identity) -> archived.add(identity) } }
        val suppressed = identified.size - reserved.size
        if (reserved.isEmpty()) {
            record(AppendResult.Skipped, suppressed)
            return AppendResult.Skipped
        }

        val result = bolt.append(reserved.map { (op, _) -> op })

        lock.withLock {
            if (result is AppendResult.Written) {
                trimToWindow()
            } else {
                reserved.forEach { (_, identity) -> archived.remove(identity) }
            }
        }
        record(result, suppressed)
        return result
    }

    /** Drop the oldest identities until the set is back inside [dedupWindow]. Called under [lock]. */
    private fun trimToWindow() {
        if (archived.size <= dedupWindow) return
        val iterator = archived.iterator()
        while (archived.size > dedupWindow && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun record(result: AppendResult, suppressed: Int) {
        healthState.update { current ->
            val withSuppression = current.copy(opsDeduplicated = current.opsDeduplicated + suppressed)
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
         * How many operation identities are remembered by default.
         *
         * Sized as "a few peers' worth of a full log buffer", not as a fraction of the archive —
         * the archive is unbounded and this must not be. A miss costs one duplicated operation in
         * the archive, so the number is a space/bytes trade with no correctness edge to fall off.
         */
        public const val DEFAULT_DEDUP_WINDOW: Int = 65_536

        /**
         * How many [AppendResult.Failed] values [ArchiveHealth.recentFailures] retains.
         *
         * Enough to diagnose a burst; small enough that a permanently failing archive cannot grow
         * a list of its own failures without bound.
         */
        public const val RETAINED_FAILURES: Int = 32
    }
}
