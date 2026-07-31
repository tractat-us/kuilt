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
 * reassembly. The buffer is discarded on a meta mismatch, a fresh offset-0 chunk (which restarts
 * reassembly from empty), or a chunk that would breach [totalCeiling]; on a **same-meta offset gap
 * the buffer is retained** and its size re-advertised, so the leader resumes sending from the held
 * offset.
 *
 * **[totalCeiling] bounds the sum, because nothing else does (#1881).** `snapshotChunkCeiling` and
 * the transport's `maxPayloadBytes` bound a *single* chunk, and the **sender** chooses `done` — so a
 * peer that keeps sending well-formed, correctly-advancing, non-final chunks grew this buffer without
 * limit until the follower's process died. The §5.2 leader-authority gate (#1383) means that peer has
 * to be a current voter, which puts this in the Byzantine-voter model of #1868/#1876 rather than
 * within reach of a stranger, but a voter should not be able to kill a follower with a resource it
 * alone meters. The check runs **before** the append, so the bytes that would breach the ceiling are
 * never allocated, and the disposition is [ChunkOutcome.TooLarge] rather than a throw: this is
 * reached from the engine's actor loop, whose `try`/`finally` has no `catch`, so throwing would turn
 * a remote frame into permanent node death (#1818).
 *
 * **The buffer is a growable [ByteArray], not an `ArrayList<Byte>`.** The old list boxed every wire
 * byte into a `java.lang.Byte` — roughly 16–24× its own size on a 64-bit JVM once the reference slot
 * is counted — so a ceiling expressed in wire bytes would have meant an order of magnitude more heap
 * than it said. With the array the two are the same number, which is what makes [totalCeiling]
 * legible as a memory bound.
 *
 * **Concurrency:** actor-confined exactly like the fields it holds — every method is called only from
 * inside the engine's single dispatch loop (or the init-restore coroutine that strictly precedes it).
 * Confinement is provided by that single dedicated actor coroutine draining `cmd`, which the repo
 * thread-safety rule sanctions as a real primitive. It holds no locks, launches no coroutines, and
 * must never be handed to a coroutine that isn't an actor message handler.
 *
 * @param totalCeiling Maximum bytes to accumulate for one snapshot; see `RaftConfig.snapshotTotalCeiling`.
 */
internal class SnapshotReceiver(private val totalCeiling: Int) {
    /**
     * The single in-flight reassembly: the snapshot [meta] being received and the bytes accumulated
     * so far. [bytes] is a capacity-doubling backing store of which only the first [size] entries are
     * meaningful — [size], not `bytes.size`, is the value the §7 arithmetic calls "buffer.size".
     */
    private class SnapshotReassembly(val meta: SnapshotMeta) {
        private var bytes: ByteArray = EMPTY

        /** Bytes accumulated so far — the next expected chunk offset. */
        var size: Int = 0
            private set

        /**
         * Append [data]. The caller must already have checked that `size + data.size` fits both
         * [SnapshotReceiver.totalCeiling] and an `Int`, which [SnapshotReceiver.onChunk] does in
         * `Long` arithmetic before calling this.
         */
        fun append(data: ByteArray) {
            grow(size + data.size)
            data.copyInto(bytes, size)
            size += data.size
        }

        /** A copy of the used prefix — the fully-reassembled snapshot, without any capacity slack. */
        fun usedPrefix(): ByteArray = bytes.copyOf(size)

        /**
         * Ensure [bytes] holds at least [required] entries, **doubling** rather than resizing to fit:
         * one exact resize per chunk would make a 64 MiB snapshot at 16 KiB chunks copy ~137 GiB.
         * The first allocation is exact, so a single-chunk snapshot never over-allocates.
         */
        private fun grow(required: Int) {
            if (bytes.size >= required) return
            var capacity = if (bytes.isEmpty()) required else bytes.size
            while (capacity < required) {
                capacity = if (capacity > Int.MAX_VALUE / 2) required else capacity * 2
            }
            bytes = bytes.copyOf(capacity)
        }

        private companion object {
            val EMPTY = ByteArray(0)
        }
    }

    private var incoming: SnapshotReassembly? = null

    /**
     * Accept one InstallSnapshot chunk. [offset] `== 0` (re)starts reassembly with a fresh buffer for
     * [meta]; any later chunk must match the in-flight [meta] and pick up exactly where the buffer ends.
     * A mismatch (no reassembly, differing [meta], or an offset gap) buffers nothing and returns
     * [ChunkOutcome.ReAdvertise] carrying the offset we actually hold (0 when the buffer was discarded).
     * A chunk that would push the accumulated total past [totalCeiling] buffers nothing either, discards
     * the in-flight reassembly, and returns [ChunkOutcome.TooLarge]. A matching non-final chunk is
     * appended and returns [ChunkOutcome.AwaitMore]; the final ([done]) chunk is appended and returns
     * [ChunkOutcome.Complete] with the fully-assembled bytes. The buffer is retained after
     * [ChunkOutcome.Complete] — the engine calls [reset] once the install is finalized.
     */
    fun onChunk(meta: SnapshotMeta, offset: Long, data: ByteArray, done: Boolean): ChunkOutcome {
        val r = if (offset == 0L) SnapshotReassembly(meta).also { incoming = it } else incoming
        // Out-of-order or stale chunk: re-advertise the offset we actually hold and wait for a resend.
        if (r == null || r.meta != meta || offset != r.size.toLong()) {
            val have = if (r?.meta == meta) r.size.toLong() else 0L
            if (have == 0L) incoming = null
            return ChunkOutcome.ReAdvertise(have)
        }
        // Long arithmetic deliberately: `size + data.size` is an Int sum of two attacker-influenced
        // Ints, and an overflowed sum is NEGATIVE — it would sail under the very ceiling this exists
        // to enforce. Widening first makes the comparison say what it means.
        val total = r.size.toLong() + data.size.toLong()
        if (total > totalCeiling.toLong()) {
            incoming = null
            return ChunkOutcome.TooLarge(total, totalCeiling)
        }
        r.append(data)
        return if (!done) {
            ChunkOutcome.AwaitMore(r.size.toLong())
        } else {
            ChunkOutcome.Complete(meta, r.usedPrefix())
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

        /**
         * Chunk would have pushed the reassembly to [attemptedTotal] bytes, past [ceiling]. Nothing was
         * appended and the in-flight reassembly was discarded, so the follower now holds nothing.
         */
        data class TooLarge(val attemptedTotal: Long, val ceiling: Int) : ChunkOutcome

        /** Chunk buffered; more remain — ack the new total held ([haveOffset]) and await the next chunk. */
        data class AwaitMore(val haveOffset: Long) : ChunkOutcome

        /** Final chunk buffered — the snapshot [meta]/[bytes] are fully reassembled and ready to install. */
        class Complete(val meta: SnapshotMeta, val bytes: ByteArray) : ChunkOutcome
    }
}
