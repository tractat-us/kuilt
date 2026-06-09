package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp

/**
 * **⚠ EXPERIMENTAL — UNSOUND.**
 *
 * This coordinator can silently drop concurrent inserts: a `Insert(J, after=I)` from a peer is
 * lost if another peer GCs tombstoned `I` before `J` is delivered, because the GC watermark is
 * derived from per-author delta-ack rather than true causal stability. Do not use in production
 * until [#262](https://github.com/tractat-us/kuilt/issues/262) lands.
 *
 * Coordinator that drives tombstone GC (and optional history windowing) for an [Rga] CRDT
 * replicated via [SeamReplicator].
 *
 * ## Role
 *
 * The [Rga] op-log grows without bound unless tombstones are periodically garbage-collected.
 * [SeamReplicator.universalAckFlow] emits the highest sequence number that every current peer
 * has acknowledged — the causal-stability watermark. Any op whose Lamport timestamp is at or
 * below the corresponding Rga lamport at that seq is causally stable and safe to GC (subject
 * to the structural predecessor invariant in [Rga.compact]).
 *
 * ## Seq → Lamport mapping
 *
 * `universalAckFlow` speaks in delta sequence numbers (1, 2, …) while `Rga.compact(watermark)`
 * expects a Lamport timestamp. The coordinator bridges this gap by observing
 * [SeamReplicator.nextSeqFlow]: each time the local seq advances (a local [SeamReplicator.apply]
 * was called), it records `seqToLamport[seq] = state.value.lamport`. When `universalAck`
 * advances to N, the Lamport watermark is `seqToLamport[N]` (or 0 if not yet recorded).
 *
 * [RgaGcCoordinator] observes [universalAck] and, on each advance, calls [Rga.compact] in a
 * loop until no further compaction is possible (loop-until-stable). This handles the two-pass
 * chain case: a single watermark advance may unlock further tombstones once their structural
 * predecessors have been removed.
 *
 * Each resulting [RgaOp.Compact] is bridged into a [Patch]`<`[Rga]`<V>>` via
 * `Rga.empty<V>().apply(compactOp)` — a minimal single-op delta that any peer merges via
 * [Rga.piece], triggering the same purge on the remote op-log.
 *
 * ## Window policy
 *
 * [windowPolicy] may return additional ids to truncate from the visible prefix (history
 * windowing). The default [WindowPolicy.never] does nothing beyond causal-stability GC.
 * `WindowPolicy.byCount(n)` (sub-issue #254) is explicitly out of scope here.
 *
 * ## Structural shape
 *
 * Mirrors [BoundedCounterTransferCoordinator]: an `init` block observes a [StateFlow] via
 * `.launchIn(scope)` and feeds a [Patch] back through an `apply` callback.
 *
 * @param replicaId this replica's [ReplicaId] (reserved for future use / diagnostics).
 * @param state live [Rga] state (updated by [SeamReplicator] on every incoming delta).
 * @param universalAck causal-stability watermark from [SeamReplicator.universalAckFlow].
 * @param localSeq current local delta sequence from [SeamReplicator.nextSeqFlow]. Used to
 *   maintain the seq → Rga-lamport mapping needed to bridge [universalAck] seq numbers to the
 *   Lamport timestamps expected by [Rga.compact].
 * @param applyCompaction called with each compaction [Patch]; the caller wires this to
 *   [SeamReplicator.apply] so the delta propagates to all peers.
 * @param windowPolicy optional history-windowing policy (default [WindowPolicy.never]).
 * @param scope the [CoroutineScope] for background coroutines.
 *
 * @see Rga.compact
 * @see SeamReplicator.universalAckFlow
 * @see SeamReplicator.nextSeqFlow
 * @see WindowPolicy
 */
@Deprecated(
    message = "Experimental and unsound — silently drops concurrent inserts; see #262. Do not use until fixed.",
    level = DeprecationLevel.WARNING,
)
public class RgaGcCoordinator<V>(
    private val replicaId: ReplicaId,
    private val state: StateFlow<Rga<V>>,
    private val universalAck: StateFlow<Long>,
    private val localSeq: StateFlow<Long>,
    private val applyCompaction: (Patch<Rga<V>>) -> Unit,
    private val windowPolicy: WindowPolicy = WindowPolicy.never(),
    private val scope: CoroutineScope,
) {
    /**
     * Maps delta sequence number → the Rga lamport at the time that delta was applied locally.
     *
     * This is the bridge between [universalAck] (seq-based) and [Rga.compact] (lamport-based).
     * When `universalAck = N`, the GC watermark is `seqToLamport[N]` — the maximum Lamport
     * timestamp of any op included in local deltas 1..N.
     */
    private val seqToLamport: MutableMap<Long, Long> = mutableMapOf()

    init {
        observeLocalSeq()
        observeWatermark()
    }

    private fun observeLocalSeq() {
        localSeq
            .onEach { seq -> if (seq > 0L) seqToLamport[seq] = state.value.lamport }
            .launchIn(scope)
    }

    private fun observeWatermark() {
        universalAck
            .onEach { ack -> if (ack > 0L) compactUntilStable(lamportWatermarkFor(ack)) }
            .launchIn(scope)
    }

    /**
     * Returns the Rga Lamport watermark corresponding to the given [universalAck] seq.
     *
     * Falls back to the seq value itself when the mapping isn't recorded yet — this is safe
     * because the seq and lamport are both monotonically increasing and seq <= lamport in
     * typical single-op-per-delta usage. The mapping is populated by [observeLocalSeq].
     */
    private fun lamportWatermarkFor(ack: Long): Long =
        seqToLamport[ack] ?: seqToLamport.entries.filter { it.key <= ack }.maxOfOrNull { it.value } ?: ack

    /**
     * Runs [Rga.compact] + optional [windowPolicy] in a loop until no further compaction
     * is possible for this watermark. Handles the two-pass chain-GC case: removing a
     * tombstone may unblock a structural predecessor, making it eligible on the next pass.
     *
     * [SeamReplicator.apply] updates [StateFlow] synchronously via `StateFlow.update`, so
     * `state.value` reflects each compaction before the next loop iteration. This makes the
     * loop-until-null safe: each iteration reads fresh state and terminates when nothing
     * further can be GC'd.
     */
    private fun compactUntilStable(watermark: Long) {
        while (true) {
            val current = state.value
            val windowIds = windowPolicy.idsToTruncate(current.sequence, current.tombstones)
            val (_, compactOp) = compactWithWindow(current, watermark, windowIds) ?: break
            applyCompaction(Patch(Rga.empty<V>().apply(compactOp)))
        }
    }

    /**
     * Merges GC candidates from [Rga.compact] and [windowIds], returning a unified
     * [RgaOp.Compact] that covers both, or `null` if there is nothing to compact.
     */
    @Suppress("DEPRECATION") // Unsound scalar watermark; replaced by the VV barrier in #270.
    private fun compactWithWindow(
        rga: Rga<V>,
        watermark: Long,
        windowIds: Set<RgaId>,
    ): Pair<Rga<V>, RgaOp.Compact>? {
        val gcResult = rga.compact(watermark)
        val windowTombstones = windowIds.intersect(rga.tombstones)
        return when {
            gcResult != null && windowTombstones.isNotEmpty() -> {
                val (gcRga, gcOp) = gcResult
                val mergedIds = gcOp.ids + windowTombstones
                gcRga to RgaOp.Compact(mergedIds)
            }
            gcResult != null -> gcResult
            windowTombstones.isNotEmpty() -> {
                val compactOp = RgaOp.Compact(windowTombstones)
                rga.apply(compactOp) to compactOp
            }
            else -> null
        }
    }
}
