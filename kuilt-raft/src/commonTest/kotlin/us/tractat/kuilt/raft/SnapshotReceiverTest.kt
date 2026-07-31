package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.SnapshotReceiver
import us.tractat.kuilt.raft.internal.SnapshotReceiver.ChunkOutcome
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pure single-class unit test of the follower-side reassembly ladder. The integrated behavior is
 * pinned by the sim suite (`InstallSnapshotTest`); this exercises the offset arithmetic directly:
 * in-order assembly, out-of-order / meta-mismatch re-advertise, offset-0 restart, and reset.
 *
 * The last group pins the **total** bound added for #1881: the sender picks `done`, so without a
 * ceiling on the accumulated size a peer that keeps sending well-formed `done = false` chunks grows
 * the follower's buffer without limit. Those tests, and [bufferSizeStaysTheNextExpectedOffset],
 * also guard the `ArrayList<Byte>` → growable `ByteArray` migration that made the ceiling mean the
 * number it says (a boxed byte cost ~16–24× its wire size).
 */
class SnapshotReceiverTest {
    private val meta = SnapshotMeta(lastIncludedIndex = 7L, lastIncludedTerm = 3L)

    /** Small enough to reach in a handful of bytes, so the boundary cases stay readable. */
    private val ceiling = 8

    /**
     * The default is far above every byte the pre-#1881 tests below move, so they exercise the
     * offset ladder alone and the ceiling never confounds them.
     */
    private fun receiver(totalCeiling: Int = 1024) = SnapshotReceiver(totalCeiling)

    @Test
    fun inOrderChunksAssembleToCompleteBytes() {
        val r = receiver()
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
        val r = receiver()
        val done = r.onChunk(meta, offset = 0L, data = byteArrayOf(9), done = true)
        assertIs<ChunkOutcome.Complete>(done)
        assertContentEquals(byteArrayOf(9), done.bytes)
    }

    @Test
    fun outOfOrderChunkReAdvertisesHeldOffset() {
        val r = receiver()
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
        val r = receiver()
        // No reassembly in flight and offset != 0 → nothing held, re-advertise 0 to restart from the top.
        val re = r.onChunk(meta, offset = 4L, data = byteArrayOf(1), done = false)
        assertIs<ChunkOutcome.ReAdvertise>(re)
        assertEquals(0L, re.haveOffset)
    }

    @Test
    fun offsetZeroRestartsDiscardsPriorBuffer() {
        val r = receiver()
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
        val r = receiver()
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
        val r = receiver()
        r.onChunk(meta, offset = 0L, data = byteArrayOf(1, 2), done = false)
        r.reset()
        // After reset there is no in-flight reassembly; a non-zero-offset chunk re-advertises 0.
        val re = r.onChunk(meta, offset = 2L, data = byteArrayOf(3), done = false)
        assertIs<ChunkOutcome.ReAdvertise>(re)
        assertEquals(0L, re.haveOffset)
    }

    // ── #1881: the accumulated total is bounded ──────────────────────────────

    /**
     * The honest path is untouched: a multi-chunk transfer that stays inside the ceiling still
     * assembles and completes exactly as before. Without this the ceiling could be "correct" by
     * rejecting everything.
     */
    @Test
    fun reassemblyUnderTheCeilingStillCompletes() {
        val r = receiver(totalCeiling = ceiling)
        val await = r.onChunk(meta, offset = 0L, data = byteArrayOf(1, 2, 3, 4), done = false)
        assertIs<ChunkOutcome.AwaitMore>(await)
        assertEquals(4L, await.haveOffset)

        val done = r.onChunk(meta, offset = 4L, data = byteArrayOf(5, 6, 7), done = true)
        assertIs<ChunkOutcome.Complete>(done)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7), done.bytes)
    }

    /**
     * The boundary, in both directions — the failure mode a ceiling actually ships with. Get the
     * inclusivity wrong and the bound either rejects a legal transfer or admits an illegal one, one
     * byte either side. Two receivers so neither case can be contaminated by the other's discard.
     */
    @Test
    fun exactlyTheCeilingIsAcceptedAndOneByteOverIsRejected() {
        val atCeiling = receiver(totalCeiling = ceiling)
        atCeiling.onChunk(meta, offset = 0L, data = ByteArray(4), done = false)
        val accepted = atCeiling.onChunk(meta, offset = 4L, data = ByteArray(4), done = true)

        val overCeiling = receiver(totalCeiling = ceiling)
        overCeiling.onChunk(meta, offset = 0L, data = ByteArray(4), done = false)
        val rejected = overCeiling.onChunk(meta, offset = 4L, data = ByteArray(5), done = true)

        assertAll(
            {
                assertIs<ChunkOutcome.Complete>(
                    accepted,
                    "a total of exactly $ceiling bytes is inside the bound and must complete",
                )
            },
            {
                assertIs<ChunkOutcome.TooLarge>(
                    rejected,
                    "a total of ${ceiling + 1} bytes is outside the bound and must be rejected",
                )
            },
            { assertEquals(ceiling, (accepted as ChunkOutcome.Complete).bytes.size) },
        )
    }

    /**
     * The rejection discards rather than truncates, and the overshoot is never appended — the check
     * runs *before* the copy, so the bytes that would breach the ceiling are never allocated at all.
     * Both halves are observable: a resend at the previously-held offset must miss (nothing held),
     * and a fresh transfer must assemble only its own bytes.
     */
    @Test
    fun theRejectedChunkIsNeitherAppendedNorHeld() {
        val r = receiver(totalCeiling = ceiling)
        r.onChunk(meta, offset = 0L, data = byteArrayOf(1, 2, 3, 4), done = false)
        val tooLarge = assertIs<ChunkOutcome.TooLarge>(
            r.onChunk(meta, offset = 4L, data = ByteArray(5), done = false)
        )
        // If the buffer were retained, this resend at the held offset would return AwaitMore(5).
        val followUp = assertIs<ChunkOutcome.ReAdvertise>(
            r.onChunk(meta, offset = 4L, data = byteArrayOf(9), done = false)
        )
        val restarted = assertIs<ChunkOutcome.Complete>(
            r.onChunk(meta, offset = 0L, data = byteArrayOf(7, 7), done = true)
        )

        assertAll(
            { assertEquals(ceiling + 1L, tooLarge.attemptedTotal, "the total the chunk would have reached") },
            { assertEquals(ceiling, tooLarge.ceiling, "the bound that rejected it") },
            { assertEquals(0L, followUp.haveOffset, "the in-flight reassembly must be discarded, not truncated") },
            { assertContentEquals(byteArrayOf(7, 7), restarted.bytes, "no byte of the rejected chunk survives") },
        )
    }

    /**
     * `buffer.size` is the next expected byte offset, and after the `ArrayList<Byte>` → growable
     * `ByteArray` migration that is a *used-prefix length*, not the backing array's capacity. Twelve
     * seven-byte appends push the array past several doublings, so any capacity/size confusion shows
     * up either as a wrong `haveOffset` or as trailing zero bytes in the completed snapshot.
     */
    @Test
    fun bufferSizeStaysTheNextExpectedOffset() {
        val r = receiver()
        val chunks = List(12) { i -> ByteArray(7) { j -> (i * 7 + j).toByte() } }
        var offset = 0L
        chunks.dropLast(1).forEach { chunk ->
            val await = assertIs<ChunkOutcome.AwaitMore>(r.onChunk(meta, offset, chunk, done = false))
            offset += chunk.size
            assertEquals(offset, await.haveOffset, "buffer.size must remain the next expected byte offset")
        }
        val done = assertIs<ChunkOutcome.Complete>(r.onChunk(meta, offset, chunks.last(), done = true))

        assertAll(
            { assertEquals(chunks.sumOf { it.size }, done.bytes.size, "no capacity slack may leak into the snapshot") },
            { assertContentEquals(chunks.reduce { a, b -> a + b }, done.bytes) },
        )
    }
}
