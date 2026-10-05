package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.ConfigPayload
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
 * @property chunkBytes supplies the per-chunk **raw** byte budget for a transfer to the given peer
 *   of a snapshot whose membership is the given [ConfigPayload] (transport payload cap ∩ configured
 *   ceiling, envelope reserve already subtracted); the engine owns that computation, so the machine
 *   depends on neither the transport nor [us.tractat.kuilt.raft.RaftConfig]. It takes the config
 *   because the reserve is a function of it alone — the snapshot's `config` rides on every chunk
 *   (#2720) — and the peer because a refusal has to name the follower it strands. **`null` is a
 *   refusal**: the transport's budget cannot carry this snapshot's envelope at all, so there is no
 *   slice size that would fit and the engine has already reported it. [nextChunk] then emits nothing
 *   rather than a chunk that could only be dropped.
 */
internal class SnapshotSender(
    private val storage: RaftStorage,
    private val chunkBytes: (NodeId, ConfigPayload?) -> Int?,
) {
    /**
     * One in-flight transfer to a peer: the stored snapshot's [meta]/[state] bytes, the next byte
     * offset to send, and the heartbeat round its first chunk was stamped with — see [onAck].
     */
    private class SnapshotXfer(val meta: SnapshotMeta, val state: ByteArray, var nextOffset: Long, val startRound: Long)

    private val snapshotXfer = mutableMapOf<NodeId, SnapshotXfer>()

    /**
     * The round the latest chunk to each peer was stamped with, kept past the end of its transfer. A
     * fresh transfer starts only in a round strictly above it, which is what lets [onAck] tell an ack
     * for this transfer from a late one for the transfer before it.
     */
    private val lastStampedRound = mutableMapOf<NodeId, Long>()

    /**
     * The next chunk for [peer]'s in-flight transfer, loading the stored snapshot fresh (from offset 0)
     * iff there is none in flight; otherwise it resumes from the peer's acked offset. A rewind of the
     * same snapshot is never initiated here — the follower drives that via its `ReAdvertise(0)` ack; the
     * one restart this makes is onto a *newer* snapshot, below. Returns null when no
     * snapshot is stored yet (nothing to send), and when [chunkBytes] refuses the transfer outright.
     *
     * **A refusal does not start a transfer, and does not end one already running** — unless a newer
     * snapshot is stored, below. A budget too small
     * for this snapshot's envelope is a *level*, not a verdict — `Seam.maxPayloadBytes` is a reading
     * that a peer attaching over a tighter link lowers and leaving raises — so an in-flight transfer
     * keeps its acked offset and resumes when the budget recovers, while a fresh one is not installed
     * at all. The second half matters beyond tidiness: installing it would retain a private copy of the
     * whole snapshot per peer, for a transfer that has not sent a byte.
     *
     * **Except when a newer snapshot is stored (#2843).** A transfer sizes on the snapshot it loaded,
     * so one pinned to a snapshot cut mid-membership-change carries the joint config on every chunk
     * and stays refused after the application cuts a simpler snapshot that would fit. So a refused
     * transfer whose `lastIncludedIndex` is below [storedIndex] is dropped, and this call falls
     * through to the fresh path, which decides against the newer snapshot — sending its first chunk,
     * or refusing it too and installing nothing. Only the *refused* transfer is dropped: one that is
     * still making progress finishes on the snapshot it started with, because restarting every
     * transfer on every compaction would starve a long transfer under frequent compaction. That is
     * also why the hook is here rather than in the engine's compaction.
     *
     * **A refusal costs no load.** With no transfer in flight the refusal is decided against
     * [storedConfig] — the caller's record of the stored snapshot's membership — *before*
     * [RaftStorage.loadSnapshot] runs. The refusal repeats at every heartbeat divert for every stranded
     * peer, and a durable adapter's load copies the whole snapshot, so deciding it after the load
     * meant an `O(snapshot)` copy on the actor loop at the heartbeat interval, thrown away each time.
     *
     * The slice is still sized against the meta the chunk will actually **carry** — on a fresh load,
     * the one just read — so a wrong [storedConfig] can cost a load, never an over-budget chunk. The
     * two can differ: a restore that drops a malformed stored config leaves the engine's record `null`
     * over it, and there the early check passes and the second one refuses, loading on each refusal.
     *
     * **A fresh transfer waits for a round no earlier chunk to [peer] was stamped with.** An
     * `InstallSnapshotResponse` names no snapshot, only an offset and the round it answers, and
     * heartbeats resend the outstanding chunk, so duplicate acks are routine. A transfer started in the
     * round that stamped its predecessor's last chunk could not tell that chunk's late ack from its
     * own — and an old ack past the end of a smaller snapshot reads as completion, crediting the
     * follower with a `matchIndex` it does not hold. So a fresh start in that round returns null, and
     * the next heartbeat (which bumps the round before it diverts here) starts it instead. The cost is
     * at most one heartbeat interval, paid only when one transfer follows another to the same peer.
     *
     * @param storedIndex the stored snapshot's `lastIncludedIndex`, as the caller last recorded it
     *   beside [RaftStorage.saveSnapshot]. Consulted only when an in-flight transfer is refused.
     * @param storedConfig the membership the stored snapshot carries, as the caller last recorded it
     *   beside [RaftStorage.saveSnapshot]. Consulted only when no transfer is in flight, or when a
     *   refused one is dropped for a newer snapshot.
     * @param round the heartbeat round the caller will stamp on the chunk this returns. Must be the
     *   value actually sent, and must never decrease while this sender's state lives — it is cleared
     *   by [abandonAll] on every leadership change, which is where the round counter resets.
     */
    suspend fun nextChunk(peer: NodeId, storedIndex: Long, storedConfig: ConfigPayload?, round: Long): Chunk? {
        snapshotXfer[peer]?.let { inFlight ->
            chunkBytes(peer, inFlight.meta.config)?.let { budget -> return stamp(peer, round, slice(inFlight, budget)) }
            // Refused mid-transfer. Keep the acked offset only while the snapshot it carries is still
            // the newest: once a newer one is stored, the old one is a stale target, and the newer one
            // may fit where it does not (#2843). Fall through to the fresh path, which decides afresh.
            if (inFlight.meta.lastIncludedIndex >= storedIndex) return null
            snapshotXfer.remove(peer)
        }
        if (lastStampedRound[peer]?.let { round <= it } == true) return null  // a late ack could not be told apart
        chunkBytes(peer, storedConfig) ?: return null            // refused before paying for the load
        val stored = storage.loadSnapshot() ?: return null       // nothing to send yet
        val fresh = SnapshotXfer(stored.meta, stored.state, 0L, startRound = round)
        val budget = chunkBytes(peer, fresh.meta.config) ?: return null  // sized on what the chunk carries
        snapshotXfer[peer] = fresh
        return stamp(peer, round, slice(fresh, budget))
    }

    /** Record that [chunk] goes to [peer] stamped with [round], and hand it back. */
    private fun stamp(peer: NodeId, round: Long, chunk: Chunk): Chunk {
        lastStampedRound[peer] = round
        return chunk
    }

    /** The chunk of [xfer] starting at its acked offset, at most [budget] raw bytes long. */
    private fun slice(xfer: SnapshotXfer, budget: Int): Chunk {
        // Lossless by construction: nextOffset is only ever 0 (fresh load) or a value [onAck] clamped
        // into 0..state.size, and state.size is an Int. Keep that clamp if you touch [onAck] (#1818).
        val start = xfer.nextOffset.toInt()
        val end = minOf(start + budget, xfer.state.size)
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
     *
     * That is not an oversight here but the module's exemplar of the "record as accepted — no local
     * witness exists" half of its trust policy; the counterpart exemplar is the §5.2 leader-authority
     * gate, which defends because it *has* one. The full policy and the other accepted exposures
     * (snapshot position #1876, snapshot config #1880) are under "Trust between peers" in
     * kuilt-raft/module.md.
     *
     * **An ack for an earlier transfer is [AckOutcome.Stale] (#2843).** The response carries no
     * snapshot identity, only [echoedRound], the round of the chunk it answers. Every chunk of the
     * current transfer is stamped at or above its start round, and [nextChunk] starts a transfer only
     * in a round above every chunk its predecessor sent, so `echoedRound < startRound` proves the ack
     * answers the predecessor. Read as this transfer's ack, it would move the offset to a position in a
     * different snapshot, and one past the end of a smaller snapshot would return
     * [AckOutcome.Complete] for bytes the follower never received. Unlike a clamp, this discriminates:
     * an honest follower echoes the round it was sent (#364's BLOCKER 1a). A forged round is outside
     * the crash-fault model, like the forged offset above.
     */
    fun onAck(peer: NodeId, nextOffset: Long, echoedRound: Long): AckOutcome {
        val xfer = snapshotXfer[peer] ?: return AckOutcome.NoTransfer
        if (echoedRound < xfer.startRound) return AckOutcome.Stale       // answers an earlier transfer's chunk
        xfer.nextOffset = nextOffset.coerceIn(0L, xfer.state.size.toLong())
        return if (xfer.nextOffset >= xfer.state.size) {          // fully received
            snapshotXfer.remove(peer)
            AckOutcome.Complete(xfer.meta.lastIncludedIndex)
        } else {
            AckOutcome.SendNext
        }
    }

    /** Abandon every in-flight transfer — call on leadership relinquish (leader-only state). */
    fun abandonAll() {
        snapshotXfer.clear()
        lastStampedRound.clear()   // the round counter restarts with the next leadership
    }

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

        /** The ack answers a chunk of an **earlier** transfer to this peer — ignore it; see [onAck]. */
        data object Stale : AckOutcome

        /** More chunks remain — send the next one. */
        data object SendNext : AckOutcome

        /** The follower has the whole snapshot through [lastIncludedIndex] — resume normal replication. */
        data class Complete(val lastIncludedIndex: Long) : AckOutcome
    }
}
