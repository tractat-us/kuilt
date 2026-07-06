package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.SnapshotReceiver
import us.tractat.kuilt.raft.internal.SnapshotReceiver.ChunkOutcome
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pure single-class unit test of the follower-side reassembly ladder. The integrated behavior is
 * pinned by the sim suite (`InstallSnapshotTest`); this exercises the offset arithmetic directly:
 * in-order assembly, out-of-order / meta-mismatch re-advertise, offset-0 restart, and reset.
 */
class SnapshotReceiverTest {
    private val meta = SnapshotMeta(lastIncludedIndex = 7L, lastIncludedTerm = 3L)

    @Test
    fun inOrderChunksAssembleToCompleteBytes() {
        val r = SnapshotReceiver()
        val await = r.onChunk(meta, offset = 0L, data = byteArrayOf(1, 2), done = false)
        assertIs<ChunkOutcome.AwaitMore>(await)
        assertEquals(2L, await.haveOffset) // buffer.size == next expected offset

        val done = r.onChunk(meta, offset = 2L, data = byteArrayOf(3, 4, 5), done = true)
        assertIs<ChunkOutcome.Complete>(done)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), done.bytes)
        assertEquals(meta, done.meta)
    }

    @Test
    fun singleChunkSnapshotCompletesImmediately() {
        val r = SnapshotReceiver()
        val done = r.onChunk(meta, offset = 0L, data = byteArrayOf(9), done = true)
        assertIs<ChunkOutcome.Complete>(done)
        assertContentEquals(byteArrayOf(9), done.bytes)
    }

    @Test
    fun outOfOrderChunkReAdvertisesHeldOffset() {
        val r = SnapshotReceiver()
        r.onChunk(meta, offset = 0L, data = byteArrayOf(1, 2), done = false) // hold 2 bytes
        // A chunk that skips ahead (offset 5, not 2) is rejected; we re-advertise what we actually hold.
        val re = r.onChunk(meta, offset = 5L, data = byteArrayOf(7), done = false)
        assertIs<ChunkOutcome.ReAdvertise>(re)
        assertEquals(2L, re.haveOffset)

        // The leader resends from offset 2 — reassembly resumes exactly where it stopped.
        val done = r.onChunk(meta, offset = 2L, data = byteArrayOf(3), done = true)
        assertIs<ChunkOutcome.Complete>(done)
        assertContentEquals(byteArrayOf(1, 2, 3), done.bytes)
    }

    @Test
    fun firstChunkAtNonZeroOffsetReAdvertisesZero() {
        val r = SnapshotReceiver()
        // No reassembly in flight and offset != 0 → nothing held, re-advertise 0 to restart from the top.
        val re = r.onChunk(meta, offset = 4L, data = byteArrayOf(1), done = false)
        assertIs<ChunkOutcome.ReAdvertise>(re)
        assertEquals(0L, re.haveOffset)
    }

    @Test
    fun offsetZeroRestartsDiscardsPriorBuffer() {
        val r = SnapshotReceiver()
        r.onChunk(meta, offset = 0L, data = byteArrayOf(1, 2, 3), done = false) // hold 3 bytes
        // A fresh offset-0 chunk restarts reassembly from empty (leader restarted the transfer).
        val restart = r.onChunk(meta, offset = 0L, data = byteArrayOf(8), done = false)
        assertIs<ChunkOutcome.AwaitMore>(restart)
        assertEquals(1L, restart.haveOffset)

        val done = r.onChunk(meta, offset = 1L, data = byteArrayOf(9), done = true)
        assertIs<ChunkOutcome.Complete>(done)
        assertContentEquals(byteArrayOf(8, 9), done.bytes)
    }

    @Test
    fun metaMismatchOnContinuationDiscardsWholeBuffer() {
        val r = SnapshotReceiver()
        r.onChunk(meta, offset = 0L, data = byteArrayOf(1, 2), done = false)
        // A continuation carrying different meta (a newer snapshot) at the matching offset is still
        // rejected — meta is constant across a reassembly — and the whole buffer is discarded (have == 0).
        val other = SnapshotMeta(lastIncludedIndex = 11L, lastIncludedTerm = 4L)
        val re = r.onChunk(other, offset = 2L, data = byteArrayOf(3), done = false)
        assertIs<ChunkOutcome.ReAdvertise>(re)
        assertEquals(0L, re.haveOffset)

        // Prove the discard actually happened: a follow-up with the ORIGINAL meta at offset 2 must NOT
        // match a still-held 2-byte buffer. It re-advertises 0 (nothing held) rather than AwaitMore —
        // if the discard were removed the buffer would still be size 2 and this would return AwaitMore.
        val followUp = r.onChunk(meta, offset = 2L, data = byteArrayOf(9), done = false)
        assertIs<ChunkOutcome.ReAdvertise>(followUp)
        assertEquals(0L, followUp.haveOffset)
    }

    @Test
    fun resetClearsBufferSoNextContinuationReAdvertisesZero() {
        val r = SnapshotReceiver()
        r.onChunk(meta, offset = 0L, data = byteArrayOf(1, 2), done = false)
        r.reset()
        // After reset there is no in-flight reassembly; a non-zero-offset chunk re-advertises 0.
        val re = r.onChunk(meta, offset = 2L, data = byteArrayOf(3), done = false)
        assertIs<ChunkOutcome.ReAdvertise>(re)
        assertEquals(0L, re.haveOffset)
    }
}
