package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.SnapshotSender
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Regression for #1818: a follower's acked `nextOffset` is an unvalidated wire field, and
 * [SnapshotSender.onAck] stored it verbatim into the transfer it drives.
 *
 * [SnapshotSender] is a plain, actor-confined decision machine, so driving one instance directly is
 * legitimate (not a hand-rolled cluster) — the same justification as `LeadershipTransferMachineTest`.
 *
 * The reachable defect is a **negative** offset. `onAck(-1)` leaves `-1 >= state.size` false, so the
 * machine returns `SendNext`; the engine then calls [SnapshotSender.nextChunk], which computes
 * `start = (-1).toInt()` and calls `state.copyOfRange(-1, …)`. That throws inside the engine's actor
 * loop — a `try { for (c in cmd) … } finally { … }` with **no** `catch` — so the throw unwinds the loop,
 * the `finally` runs the full teardown (cancels every timer, fails every pending proposal/read), and the
 * leader is permanently dead. One malformed frame from one follower ends the leadership.
 *
 * The clamp is `nextOffset.coerceIn(0L, state.size.toLong())`, mirroring the sibling
 * `minOf(m.matchIndex, state.lastLogIndex)` clamp in the AppendEntries handler (#1175): it converts a
 * leader-killing crash into a benign no-op. `require` would be the wrong shape here — `onAck` is called
 * from `onInstallSnapshotResponse`, which runs *inside* that same uncaught actor loop, so a failed
 * `require` would kill the leader exactly as the crash it replaced.
 *
 * The clamp's upper bound is defensive rather than corrective, and this test says so: any
 * `nextOffset > Int.MAX_VALUE` is necessarily `>= state.size` (a `ByteArray`'s size is an `Int`), so it
 * already exited via `Complete` and never reached the `.toInt()` narrowing. After the clamp the
 * remaining range is `0..state.size`, which makes that narrowing lossless *by construction* instead of
 * by a two-step argument, and makes the class KDoc's stated invariant ("a peer's `nextOffset` never
 * exceeds the snapshot's byte length") literally true of the field.
 *
 * **Not fixed here, because no clamp can fix it:** a follower that stored zero bytes but acks
 * `nextOffset = state.size` gets `Complete`, and the engine credits it `matchIndex = lastIncludedIndex`.
 * That value is perfectly in range, so clamping cannot distinguish it from an honest completion. It is a
 * Byzantine lie, and Raft's model is crash-fault, not Byzantine — the leader has no proof the bytes were
 * stored. `Long.MAX_VALUE` is covered below only to pin that it is *equivalent* to that in-range lie and
 * that the credited index comes from the stored snapshot's own metadata, never from the forged number.
 */
internal class SnapshotSenderOffsetClampTest {

    private val peer = NodeId("f1")
    private val snapshotBytes = ByteArray(10) { it.toByte() }
    private val meta = SnapshotMeta(lastIncludedIndex = 42L, lastIncludedTerm = 3L)
    private val chunkBytes = 4

    /**
     * A sender with one transfer already in flight — the only state in which an ack is reachable.
     * `snapshotXfer[peer]` is created solely by [SnapshotSender.nextChunk], so the engine has always
     * sent a first chunk before any follower ack can arrive.
     */
    private suspend fun senderWithTransferInFlight(): SnapshotSender {
        val storage = InMemoryRaftStorage()
        storage.saveSnapshot(meta, snapshotBytes)
        return SnapshotSender(storage) { chunkBytes }.also { it.nextChunk(peer) }
    }

    /**
     * #1818 — the invariant. Whatever a follower acks, the transfer's offset must stay inside
     * `0..state.size`, so the machine never hands the engine an out-of-range slice request.
     */
    @Test
    fun malformedAckOffsetNeverEscapesSnapshotBounds() = raftRunTest {
        for (forged in listOf(-1L, 1L shl 32, Long.MAX_VALUE)) {
            val sender = senderWithTransferInFlight()
            when (val outcome = sender.onAck(peer, forged)) {
                // Clamped up to state.size ⇒ reported finished. The credited index must come from the
                // stored snapshot's metadata, never be derived from the forged offset.
                is SnapshotSender.AckOutcome.Complete ->
                    assertEquals(
                        meta.lastIncludedIndex,
                        outcome.lastIncludedIndex,
                        "forged=$forged: the completed index must be the stored snapshot's own",
                    )
                // Clamped into range ⇒ the next chunk must be a real, in-bounds slice.
                // Pre-fix with forged = -1 this line THROWS IndexOutOfBoundsException from
                // copyOfRange(-1, 3) — inside the engine's uncaught actor loop, killing the leader.
                SnapshotSender.AckOutcome.SendNext -> {
                    val chunk = assertNotNull(sender.nextChunk(peer), "forged=$forged: a transfer is in flight")
                    assertAll(
                        {
                            assertEquals(
                                true,
                                chunk.offset in 0L..snapshotBytes.size.toLong(),
                                "forged=$forged: offset ${chunk.offset} escaped 0..${snapshotBytes.size}",
                            )
                        },
                        {
                            assertEquals(
                                true,
                                chunk.data.size in 0..snapshotBytes.size,
                                "forged=$forged: slice of ${chunk.data.size} bytes escaped the snapshot",
                            )
                        },
                    )
                }
                SnapshotSender.AckOutcome.NoTransfer -> fail("forged=$forged: a transfer IS in flight")
            }
        }
    }

    /**
     * The other direction: the clamp must not turn every ack into a completion (or every ack into a
     * restart). An honest partial ack advances the transfer to that offset; an honest final ack completes it.
     */
    @Test
    fun honestAckAdvancesThenCompletesTransfer() = raftRunTest {
        val sender = senderWithTransferInFlight()

        val afterPartial = sender.onAck(peer, chunkBytes.toLong())          // stored 4 of 10 bytes
        val resumed = assertNotNull(sender.nextChunk(peer), "transfer must still be in flight")
        val afterFinal = sender.onAck(peer, snapshotBytes.size.toLong())    // stored all 10

        assertAll(
            { assertEquals(SnapshotSender.AckOutcome.SendNext, afterPartial, "a partial ack must ask for the next chunk") },
            { assertEquals(chunkBytes.toLong(), resumed.offset, "the resumed chunk must start at the acked offset") },
            { assertEquals(snapshotBytes.copyOfRange(4, 8).toList(), resumed.data.toList(), "the resumed chunk must carry the acked slice") },
            { assertEquals(SnapshotSender.AckOutcome.Complete(meta.lastIncludedIndex), afterFinal, "a full-length ack must complete the transfer") },
        )
    }

    /**
     * A negative ack must not merely avoid crashing — it must restart the transfer from byte 0, which is
     * the only in-range reading of "I have stored -1 bytes" and matches the follower-driven
     * `ReAdvertise(0)` rewind the machine already supports.
     */
    @Test
    fun negativeAckRewindsToStartOfSnapshot() = raftRunTest {
        val sender = senderWithTransferInFlight()

        val outcome = sender.onAck(peer, -1L)
        val chunk = assertNotNull(sender.nextChunk(peer), "a rewound transfer must still be in flight")

        assertAll(
            { assertEquals(SnapshotSender.AckOutcome.SendNext, outcome, "a negative ack must resume, not complete") },
            { assertEquals(0L, chunk.offset, "a negative ack must rewind to the start of the snapshot") },
            { assertEquals(snapshotBytes.copyOfRange(0, chunkBytes).toList(), chunk.data.toList(), "the rewound chunk must be the first slice") },
        )
    }
}
