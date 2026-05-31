package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlyInboundGateTest {
    private fun data(seq: Long, origin: String = "o", payload: Byte = seq.toByte()) =
        PlyFrame.Data(PeerId(origin), seq, byteArrayOf(payload))

    private fun seqs(out: List<ByteArray>) = out.map { it[0].toLong() }

    @Test
    fun firstFrameFromAnOriginIsDelivered() {
        val gate = PlyInboundGate(maxBuffered = 8)
        assertEquals(listOf(0L), seqs(gate.accept(data(0))))
    }

    @Test
    fun duplicateSecondCopyIsDropped() {
        val gate = PlyInboundGate(maxBuffered = 8)
        gate.accept(data(0))
        assertTrue(gate.accept(data(0)).isEmpty(), "the relay/overlay duplicate is dropped")
    }

    @Test
    fun distinctOriginsAreIndependent() {
        val gate = PlyInboundGate(maxBuffered = 8)
        assertEquals(listOf(0L), seqs(gate.accept(data(0, origin = "a"))))
        assertEquals(listOf(0L), seqs(gate.accept(data(0, origin = "b"))))
    }

    @Test
    fun outOfOrderFramesAreReleasedInSequence() {
        val gate = PlyInboundGate(maxBuffered = 8)
        gate.accept(data(0))                          // baseline
        assertTrue(gate.accept(data(2)).isEmpty(), "seq 2 buffered, waiting for 1")
        assertEquals(listOf(1L, 2L), seqs(gate.accept(data(1))), "1 then buffered 2 drain")
    }

    @Test
    fun bufferOverflowSkipsTheGapForLiveness() {
        val gate = PlyInboundGate(maxBuffered = 2)
        gate.accept(data(0))                          // baseline, expect 1
        assertTrue(gate.accept(data(2)).isEmpty())    // buffer {2}
        // seq 3 arrives, buffer would exceed 2 held → skip the missing 1, release contiguous from lowest
        assertEquals(listOf(2L, 3L), seqs(gate.accept(data(3))))
    }

    @Test
    fun lateFrameAfterSkipIsDropped() {
        val gate = PlyInboundGate(maxBuffered = 2)
        gate.accept(data(0))
        gate.accept(data(2))
        gate.accept(data(3))                          // skipped past 1
        assertTrue(gate.accept(data(1)).isEmpty(), "the late, skipped-over frame is dropped")
    }
}
