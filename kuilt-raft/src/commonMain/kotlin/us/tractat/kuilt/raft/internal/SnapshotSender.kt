package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.SnapshotMeta

/**
 * Leader-side chunked-InstallSnapshot state machine (§7). Owns the per-peer transfer offsets and
 * the load/slice/advance arithmetic; it is a **synchronous, decision-returning** machine — it never
 * sends, traces, or mutates engine/[RaftState] fields. The engine keeps every `send(...)`,
 * `emitTrace(...)`, `debug { }`, and `state`-mutation side-effect at the call site.
 *
 * Invariants (unchanged from the pre-extraction engine): leader-only; at most one transfer per peer;
 * exactly one chunk in flight per peer (await-ack-then-next); a peer's `nextOffset` never exceeds the
 * snapshot's byte length.
 *
 * **Concurrency:** actor-confined exactly like the fields it holds — every method is called only from
 * inside the engine's single dispatch loop (or the init-restore coroutine that strictly precedes it).
 * Confinement is provided by that single dedicated actor coroutine draining `cmd`, which the repo
 * thread-safety rule sanctions as a real primitive. It holds no locks, launches no coroutines, and
 * must never be handed to a coroutine that isn't an actor message handler.
 *
 * @property storage source of the snapshot bytes — the machine's only side-effect (a [nextChunk]
 *   read from [RaftStorage.loadSnapshot]); this is why the machine takes [storage].
 * @property chunkBytes supplies the per-chunk byte budget (transport payload cap ∩ configured
 *   ceiling, header budget already subtracted); the engine owns that computation, so the machine
 *   depends on neither the transport nor [us.tractat.kuilt.raft.RaftConfig].
 */
internal class SnapshotSender(
    private val storage: RaftStorage,
    private val chunkBytes: () -> Int,
) {
    /** One in-flight transfer to a peer: the stored snapshot's [meta]/[state] bytes and the next byte offset to send. */
    private class SnapshotXfer(val meta: SnapshotMeta, val state: ByteArray, var nextOffset: Long)

    private val snapshotXfer = mutableMapOf<NodeId, SnapshotXfer>()

    /**
     * The next chunk for [peer]'s in-flight transfer, loading the stored snapshot fresh (from offset 0)
     * iff there is none in flight; otherwise it resumes from the peer's acked offset. A restart is never
     * initiated here — the follower drives any rewind via its `ReAdvertise(0)` ack. Returns null when no
     * snapshot is stored yet (nothing to send).
     */
    suspend fun nextChunk(peer: NodeId): Chunk? {
        val xfer = snapshotXfer[peer] ?: run {
            val stored = storage.loadSnapshot() ?: return null   // nothing to send yet
            SnapshotXfer(stored.meta, stored.state, 0L).also { snapshotXfer[peer] = it }
        }
        // Lossless by construction: nextOffset is only ever 0 (fresh load) or a value [onAck] clamped
        // into 0..state.size, and state.size is an Int. Keep that clamp if you touch [onAck] (#1818).
        val start = xfer.nextOffset.toInt()
        val end = minOf(start + chunkBytes(), xfer.state.size)
        val done = end >= xfer.state.size
        return Chunk(
            meta = xfer.meta,
            offset = xfer.nextOffset,
            data = xfer.state.copyOfRange(start, end),
            done = done,
            totalBytes = xfer.state.size,
        )
    }

    /**
     * Advance the transfer to [peer] on the follower's ack (its next expected offset, [nextOffset]).
     * Returns [AckOutcome.NoTransfer] if no transfer is in flight, [AckOutcome.Complete] once the whole
     * snapshot has been received (the transfer is removed), else [AckOutcome.SendNext].
     *
     * [nextOffset] is an unvalidated wire field, so it is clamped into `0..state.size` (#1817's sibling,
     * #1818) — enforcing the class invariant above on the field rather than merely documenting it.
     *
     * The **lower** bound is the corrective half. A negative ack left `nextOffset >= state.size` false,
     * so the machine returned [AckOutcome.SendNext] and the engine called [nextChunk], which sliced
     * `state.copyOfRange(-1, …)`. That throws inside the engine's actor loop — a `try`/`finally` with no
     * `catch` — so the throw unwinds the loop and its `finally` runs the full teardown: one malformed
     * frame from one follower permanently killed the leader. Clamping to 0 rewinds the transfer instead,
     * which is also the only in-range reading of the ack and matches the follower-driven `ReAdvertise(0)`
     * rewind already supported. `require` would be the wrong shape for exactly the reason the crash was
     * fatal: it throws in that same uncaught loop.
     *
     * The **upper** bound is defensive, not corrective: any `nextOffset > Int.MAX_VALUE` is necessarily
     * `>= state.size` (a `ByteArray`'s size is an `Int`), so it already exited via [AckOutcome.Complete]
     * and never reached [nextChunk]'s `.toInt()`. Clamping makes that narrowing lossless by construction
     * instead of by that two-step argument.
     *
     * Deliberately **not** addressed, because no clamp can: a follower that stored nothing but acks
     * `nextOffset = state.size` gets [AckOutcome.Complete], and the engine credits it
     * `matchIndex = lastIncludedIndex`. That value is in range, so it is indistinguishable from an honest
     * completion — a Byzantine lie, outside Raft's crash-fault model, and unprovable without an
     * end-to-end digest of the transferred bytes.
     */
    fun onAck(peer: NodeId, nextOffset: Long): AckOutcome {
        val xfer = snapshotXfer[peer] ?: return AckOutcome.NoTransfer
        xfer.nextOffset = nextOffset.coerceIn(0L, xfer.state.size.toLong())
        return if (xfer.nextOffset >= xfer.state.size) {          // fully received
            snapshotXfer.remove(peer)
            AckOutcome.Complete(xfer.meta.lastIncludedIndex)
        } else {
            AckOutcome.SendNext
        }
    }

    /** Abandon every in-flight transfer — call on leadership relinquish (leader-only state). */
    fun abandonAll(): Unit = snapshotXfer.clear()

    /** A chunk ready to be framed into a `RaftMessage.InstallSnapshot` by the engine. */
    class Chunk(
        val meta: SnapshotMeta,
        val offset: Long,
        val data: ByteArray,
        val done: Boolean,
        /** Total snapshot byte length — for the engine's send-side debug log only. */
        val totalBytes: Int,
    )

    /** The engine's next action after a follower's InstallSnapshot ack. */
    sealed interface AckOutcome {
        /** No transfer in flight for this peer — ignore the ack. */
        data object NoTransfer : AckOutcome

        /** More chunks remain — send the next one. */
        data object SendNext : AckOutcome

        /** The follower has the whole snapshot through [lastIncludedIndex] — resume normal replication. */
        data class Complete(val lastIncludedIndex: Long) : AckOutcome
    }
}
