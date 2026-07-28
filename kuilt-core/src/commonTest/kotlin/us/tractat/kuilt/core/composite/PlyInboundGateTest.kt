package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlyInboundGateTest {
    private fun data(seq: Long, origin: String = "o", payload: Byte = seq.toByte()) =
        PlyFrame.Data(PeerId(origin), seq, byteArrayOf(payload))

    private fun seqs(out: List<ByteArray>) = out.map { it[0].toLong() }

    /**
     * Admit distinct origins until the gate refuses one; returns how many it admitted — i.e. the cap.
     * Setup only, so it probes rather than asserts: the assertions belong to the tests below.
     *
     * The catch is deliberately narrowed to the refusal this probe is named for. A `runCatching`
     * here would catch **any** [Throwable] — including the [OutOfMemoryError] a pre-fix
     * 4096-origin flood can raise, which is the very defect #1814 describes — end the loop, and
     * report a bogus cap, turning the bug under test into a green probe.
     */
    private fun fillOriginTable(gate: PlyInboundGate): Int {
        var admitted = 0
        while (admitted < ORIGIN_PROBE_CEILING) {
            try {
                gate.accept(data(0, origin = "o$admitted"))
            } catch (_: IllegalStateException) {
                return admitted
            }
            admitted++
        }
        return admitted
    }

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

    /**
     * `originId` is chosen by the *sending* peer and read straight off the wire, so the number of
     * per-origin entries the gate holds is remote-controlled. Every frame below is well-formed —
     * the growth needed no malformed input at all, which is what made it reachable (#1814).
     */
    @Test
    fun theOriginTableIsBounded() {
        val gate = PlyInboundGate(maxBuffered = 8)
        val admitted = fillOriginTable(gate)
        assertTrue(
            admitted < ORIGIN_PROBE_CEILING,
            "the gate must refuse a new origin once its table is full; it admitted all $admitted probes",
        )
    }

    @Test
    fun theRefusalNamesTheOriginAndTheCap() {
        val gate = PlyInboundGate(maxBuffered = 8)
        val cap = fillOriginTable(gate)
        val message = assertFailsWith<IllegalStateException> {
            gate.accept(data(0, origin = "intruder"))
        }.message.orEmpty()
        assertAll(
            { assertTrue("intruder" in message, "names the refused origin — message was: $message") },
            { assertTrue("$cap" in message, "names the cap it hit — message was: $message") },
        )
    }

    /**
     * Refuse, never evict. Eviction would let a peer rotating `originId` displace an established
     * origin, and re-admitting it re-baselines its sequence — re-opening the dedup the gate exists
     * to provide. So a refusal must leave every admitted origin exactly as it was.
     */
    @Test
    fun aRefusalLeavesAdmittedOriginsUntouched() {
        val gate = PlyInboundGate(maxBuffered = 8)
        fillOriginTable(gate)
        assertFailsWith<IllegalStateException> { gate.accept(data(0, origin = "intruder")) }
        assertAll(
            { assertTrue(gate.accept(data(0, origin = "o0")).isEmpty(), "o0's duplicate is still collapsed") },
            { assertEquals(listOf(1L), seqs(gate.accept(data(1, origin = "o0"))), "o0 still delivers in order") },
            // The refusal recorded nothing, so the same id is refused again rather than half-admitted.
            { assertFailsWith<IllegalStateException> { gate.accept(data(1, origin = "intruder")) } },
        )
    }

    private companion object {
        /**
         * Far above both the gate's cap and any legitimate composite's origin set (one origin per
         * remote composite peer), so "ran to the ceiling" can only mean the table is unbounded.
         */
        const val ORIGIN_PROBE_CEILING = 4096
    }
}
