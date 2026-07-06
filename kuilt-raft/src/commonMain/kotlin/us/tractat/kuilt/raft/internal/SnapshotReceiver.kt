package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.SnapshotMeta

/**
 * Follower-side chunked-InstallSnapshot reassembly state machine (§7). Owns the single in-flight
 * reassembly buffer and the offset arithmetic that accepts in-order chunks; it is a **synchronous,
 * pure decision-returning** machine — it never sends, traces, mutates engine/[RaftState] fields, or
 * touches storage. The engine keeps every `send(...)`, `emitTrace(...)`, `debug { }`, and the entire
 * `finalizeInstalledSnapshot` storage/log/commit mutation at the call site.
 *
 * Invariants (unchanged from the pre-extraction engine): follower-only; at most one reassembly in
 * flight; `buffer.size` is always the next expected byte offset; `meta` is constant across a
 * reassembly. The buffer is discarded only on a meta mismatch or a fresh offset-0 chunk (which
 * restarts reassembly from empty); on a **same-meta offset gap the buffer is retained** and its size
 * re-advertised, so the leader resumes sending from the held offset.
 *
 * **Concurrency:** actor-confined exactly like the fields it holds — every method is called only from
 * inside the engine's single dispatch loop (or the init-restore coroutine that strictly precedes it).
 * Confinement is provided by that single dedicated actor coroutine draining `cmd`, which the repo
 * thread-safety rule sanctions as a real primitive. It holds no locks, launches no coroutines, and
 * must never be handed to a coroutine that isn't an actor message handler.
 */
internal class SnapshotReceiver {
    /** The single in-flight reassembly: the snapshot [meta] being received and the bytes accumulated so far. */
    private class SnapshotReassembly(val meta: SnapshotMeta, val buffer: ArrayList<Byte> = ArrayList())

    private var incoming: SnapshotReassembly? = null

    /**
     * Accept one InstallSnapshot chunk. [offset] `== 0` (re)starts reassembly with a fresh buffer for
     * [meta]; any later chunk must match the in-flight [meta] and pick up exactly where the buffer ends.
     * A mismatch (no reassembly, differing [meta], or an offset gap) buffers nothing and returns
     * [ChunkOutcome.ReAdvertise] carrying the offset we actually hold (0 when the buffer was discarded).
     * A matching non-final chunk is appended and returns [ChunkOutcome.AwaitMore]; the final ([done])
     * chunk is appended and returns [ChunkOutcome.Complete] with the fully-assembled bytes. The buffer
     * is retained after [ChunkOutcome.Complete] — the engine calls [reset] once the install is finalized.
     */
    fun onChunk(meta: SnapshotMeta, offset: Long, data: ByteArray, done: Boolean): ChunkOutcome {
        val r = if (offset == 0L) SnapshotReassembly(meta).also { incoming = it } else incoming
        // Out-of-order or stale chunk: re-advertise the offset we actually hold and wait for a resend.
        if (r == null || r.meta != meta || offset != r.buffer.size.toLong()) {
            val have = if (r?.meta == meta) r.buffer.size.toLong() else 0L
            if (have == 0L) incoming = null
            return ChunkOutcome.ReAdvertise(have)
        }
        r.buffer.addAll(data.asList())
        return if (!done) {
            ChunkOutcome.AwaitMore(r.buffer.size.toLong())
        } else {
            ChunkOutcome.Complete(meta, r.buffer.toByteArray())
        }
    }

    /** Clear the reassembly buffer — called after the engine finalizes an installed snapshot. */
    fun reset() {
        incoming = null
    }

    /** The engine's next action after a follower InstallSnapshot chunk. */
    sealed interface ChunkOutcome {
        /** Chunk did not fit — ack the offset we actually hold ([haveOffset]) so the leader resends from there. */
        data class ReAdvertise(val haveOffset: Long) : ChunkOutcome

        /** Chunk buffered; more remain — ack the new total held ([haveOffset]) and await the next chunk. */
        data class AwaitMore(val haveOffset: Long) : ChunkOutcome

        /** Final chunk buffered — the snapshot [meta]/[bytes] are fully reassembled and ready to install. */
        class Complete(val meta: SnapshotMeta, val bytes: ByteArray) : ChunkOutcome
    }
}
