package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.Flow

/**
 * A **write-only archive** for an op-log CRDT: operations flow in and never out.
 *
 * A phone can only keep so much. When it runs out of room it forgets its oldest records — and
 * because forgetting is *contagious* through a CRDT merge, everything it syncs with forgets them
 * too. A bolt breaks that: it is fed the **operations** a replica applied, keeps them in an
 * append-only log of its own, and never joins the lattice. So a server's bolt can hold a year of
 * history beside the phone that fed it holding an hour, and neither one changes the other's mind.
 *
 * ### The invariant
 *
 * > **A bolt consumes operations, never states, and never joins the lattice.**
 *
 * Three consequences, each load-bearing:
 *
 * 1. **Fed ops, not deltas.** A `Patch` is a state fragment of the same lattice, so absorbing one
 *    would mean `piece`, which means inheriting the source's suppression. [append] takes a
 *    `List<Op>`. A compaction *floor* is state, not an operation, so an op stream cannot carry one
 *    even by mistake — the firewall is structural, not enforced.
 * 2. **`LogOp.Compact` is discarded.** Of the three op shapes, `Insert` and `Remove` are content
 *    and `Compact` is a record of *forgetting*. A bolt keeps the first two and drops the third,
 *    which is precisely what lets it retain more than its source. This is the only deliberate
 *    divergence from CRDT semantics in the module.
 * 3. **It never merges back.** This interface does not extend `Quilted` and exposes no `piece`.
 *    The absence is enforced at build time by the root build's `forbidBoltRejoiningTheLattice`
 *    guard, because a compile-level absence is worth more than a runtime test.
 *
 * ### A replay may be READ. It must never be AUTHORED FROM.
 *
 * Folding a [replay] back into a fresh replica produces a structurally valid state, so nothing
 * stops you writing it. The damage appears one step later, and it is permanent: a replica seeded
 * from a replay that is missing frames at its tail will **re-mint an already-used `(replica, seq)`
 * dot carrying different content**, because the next sequence number is derived from the ops
 * present. That breaks the dense per-author delivery counter every causal-stability version vector
 * depends on, silently and mesh-wide, and nothing purges it — the dot was never suppressed.
 *
 * Read a replay. Never author from a replica seeded by one.
 *
 * ### Best-effort, by design
 *
 * A node running a live replica and a bolt has the live replica as its source of truth, and a full
 * archive disk must not take down the application. So a failed [append] returns
 * [AppendResult.Failed] rather than throwing — but it reports the **identities** it lost (the
 * insert dots, and the offset range), never a bare tally: the live replica will subsequently window
 * those records away, so a failed append loses them from *both* sides, and a count says only *that*
 * something was lost where the identities say *what*.
 *
 * @param Op the operation type being archived — `RgaOp<V>` or `FugueOp<V>`.
 */
public interface Bolt<Op> {

    /**
     * Archive [ops], discarding any that classify as `LogOp.Compact`.
     *
     * At most **one** frame is written per call, carrying every retained op in the order given.
     * An [ops] list that is empty — or that contains nothing but compaction records — writes no
     * frame at all and returns [AppendResult.Skipped].
     *
     * Never throws for an I/O failure; see [AppendResult.Failed].
     */
    public suspend fun append(ops: List<Op>): AppendResult

    /**
     * A cold [Flow] of the frames in [scope], in append order, terminated by exactly one verdict on
     * how the stream ended — [CleanTail] or [Truncated].
     *
     * The verdict arrives on every replay **collected to completion**. A consumer that cuts the flow
     * short — `take(n)`, `first()`, an early `return` from `collect` — gets no verdict, and that is
     * the honest answer: it stopped reading before the archive said how it ended.
     *
     * Each collection re-reads the archive, so a flow collected after a later [append] sees the
     * later frames too. The flow completes when the archive's tail is reached; it does not wait for
     * future appends.
     *
     * **It never throws for damaged bytes**, because an archive is best-effort and throwing would
     * discard every intact frame ahead of the damage. But it does not stay *silent* about them
     * either: that is what the terminal [ReplayEvent] is for. Use [frames] to opt out of the
     * verdict, explicitly, when you do not need to know.
     */
    public fun replay(scope: ReplayScope): Flow<ReplayEvent<Op>>

    /**
     * Whether this bolt can be written to on this runtime.
     *
     * Mirrors `Loom.availability()`: a consumer learns that (say) a browser has no filesystem by
     * asking, rather than by crashing. A bolt reporting [BoltAvailability.Available] must accept
     * an [append].
     */
    public fun availability(): BoltAvailability
}
