package us.tractat.kuilt.bolt

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
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
 * ### The suppression window is BOUNDED and LRU, and neither is what the issue proposed
 *
 * The design sketch proposed an unbounded set of archived identities, rebuilt from the archive's
 * tail whenever a process opens one. Both halves are unaffordable for the same reason — **a bolt's
 * archive is unbounded by construction, so nothing sized against it may be held in memory or read
 * at startup**. An unbounded set is one entry per archived operation for the life of the process, a
 * leak sized by the very thing the module promises to let grow; and reading "the tail" means
 * replaying to find it, while a *bounded* tail cannot suppress anything older than itself, which is
 * exactly the case it was proposed for.
 *
 * So [dedupWindow] bounds the set explicitly and **nothing is rebuilt on open**. Skipping the
 * rebuild costs at most one extra copy of each peer's live log per process start — bounded,
 * one-off, and cheaper than reading an unbounded archive to avoid it.
 *
 * **The window is LRU, not FIFO, and the difference is the whole property.** A re-offered identity
 * is *refreshed* to the young end (see [claim]) rather than left where it was first archived.
 * Insertion order alone would evict a peer's identities on a schedule set by how much *other*
 * traffic arrived since — while that peer re-offered every one of them on every round in between —
 * so the archive would grow by a full copy of each peer's log every `dedupWindow / rate` rounds:
 * growth proportional to time spent gossiping, which is the exact failure this exists to prevent.
 * Under LRU, an offered working set that fits in [dedupWindow] produces **zero** duplicates however
 * long gossip runs.
 *
 * **The residual, stated rather than implied.** Suppression is total only while the *aggregate*
 * offered working set — every peer's live log, plus this replica's own export stream, since one
 * window serves them all — fits in [dedupWindow]. Past that the window thrashes and the archive
 * grows by roughly `Σ(offered) − dedupWindow` operations per round. Raising [dedupWindow] moves
 * that cliff and does not remove it.
 *
 * All of it is sound because a suppression miss costs *bytes*, never correctness: a duplicate
 * operation in the archive replays as a duplicate operation, and folding an op-log CRDT's operation
 * twice is idempotent.
 *
 * **A third option exists and is not built here.** "Unbounded set" and "bounded window" are not
 * exhaustive. An `Insert` mints exactly one causal `Dot`, so inserts — the bulk of any log — can be
 * suppressed *completely* in O(authors) from a frontier this class accumulates itself: a
 * high-water `VersionVector` of archived dots plus a small gap set for out-of-order arrivals. A
 * `Remove` mints no dot, so removes would still need a bounded set, but they are the minority.
 * That is #2254, deliberately deferred: it is its own design (gap draining, holes that never fill,
 * whether the frontier survives a restart), and this window is correct without it.
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
 *   with. Classification is what separates content from records of forgetting, and what supplies
 *   the identity a duplicate is recognised by.
 * @param dedupWindow how many operation identities to remember, evicted least-recently-offered
 *   first. **Size it to the aggregate working set you expect to be offered** — roughly the sum over
 *   peers of each peer's live log, plus this replica's own export stream, since one window serves
 *   them all. Below that the archive grows per round; above it, not at all. `0` disables
 *   suppression, which is only ever right for an owner that never merges.
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
     * The identities already archived, **least recently offered first** — an insertion-ordered set
     * whose order [claim] refreshes on every hit, so [trimToWindow] evicts by recency of *offer*
     * rather than by age of first archive.
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
     * failure is self-healing rather than permanent — for a refusal *and* for a backend that threw,
     * which is converted to one. There is one visible consequence, stated rather than hidden: a
     * *second* publish that arrived during a *first* one's failed append skipped the operation as
     * claimed, so that round archived nothing either. The identities it lost are on the returned
     * [AppendResult.Failed] and on [health], and the operation is claimed again on the next round.
     */
    public suspend fun publish(ops: List<Op>): AppendResult {
        // Classification is pure and non-trivial, so it happens with no lock held.
        val identified = ops.mapNotNull { op ->
            val classified = format.classifyOp(op)
            if (classified is LogOp.Compact) null else op to classified
        }
        if (identified.isEmpty()) return AppendResult.Skipped

        val reserved = lock.withLock { identified.filter { (_, identity) -> claim(identity) } }
        val suppressed = identified.size - reserved.size
        if (reserved.isEmpty()) {
            record(AppendResult.Skipped, suppressed)
            return AppendResult.Skipped
        }

        val result = appendOrConvert(reserved)

        lock.withLock {
            if (result is AppendResult.Written) trimToWindow() else release(reserved)
        }
        record(result, suppressed)
        return result
    }

    /**
     * Claim [identity] for an append about to happen — or, if it is already claimed, **refresh** it
     * to the young end of the window and answer `false`. Called under [lock].
     *
     * The refresh is what makes [dedupWindow] an LRU bound rather than a FIFO one, and it is the
     * difference between suppressing a gossiping peer forever and suppressing it for
     * `dedupWindow / rate` rounds. `LinkedHashSet.add` on a present element returns `false` and
     * leaves its position **unchanged**, so without the explicit remove-then-add a peer's
     * identities march toward the head on a clock set by everyone else's traffic and are evicted
     * while that peer is still re-offering every one of them. Deleting these two lines restores
     * exactly that; [BoltDecoratorTest] pins it at a window boundary, because at any other size the
     * two orders are indistinguishable.
     */
    private fun claim(identity: LogOp<Id>): Boolean {
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
    private suspend fun appendOrConvert(reserved: List<Pair<Op, LogOp<Id>>>): AppendResult {
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

    /** Give back the claims an append did not honour. Called under [lock]. */
    private fun release(reserved: List<Pair<Op, LogOp<Id>>>) {
        reserved.forEach { (_, identity) -> archived.remove(identity) }
    }

    /** Drop the least recently offered identities until the set is back inside [dedupWindow]. Called under [lock]. */
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
         *
         * **It is a default, not a bound that scales.** A phone archiving only its own exports sits
         * well inside it; a server gossiping with enough peers does not, because the window serves
         * every peer at once. Raise it deliberately against the sizing rule on [dedupWindow] rather
         * than assuming it covers a fleet — and see #2254 for the change that would remove the
         * question for inserts entirely.
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
