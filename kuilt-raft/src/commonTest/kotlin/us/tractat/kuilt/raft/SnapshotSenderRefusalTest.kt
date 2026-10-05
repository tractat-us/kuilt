package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.SnapshotSender
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How [SnapshotSender] treats a `null` from its sizer — a refusal, because the transport's budget
 * cannot carry the snapshot's `InstallSnapshot` envelope at all (#2720).
 *
 * [SnapshotSender] is a plain, actor-confined decision machine, so driving one instance directly is
 * legitimate (not a hand-rolled cluster) — the same justification as [SnapshotSenderOffsetClampTest].
 * The behaviour arms on the canonical harness live in [SnapshotEnvelopeReserveTest]; this suite holds
 * the halves of the contract that a cluster cannot reach on demand.
 */
internal class SnapshotSenderRefusalTest {

    private val peer = NodeId("f1")

    /** A membership the sizers below refuse by identity — its contents do not matter. */
    private val wide = ConfigPayload(old = null, new = ClusterConfig(voters = setOf(NodeId("a"), NodeId("b"))))

    /**
     * A refusal never leaves a transfer behind: after a refused [SnapshotSender.nextChunk], an ack for
     * that peer finds nothing in flight.
     *
     * Installing the transfer first and refusing second would retain a private copy of the whole
     * snapshot per stranded peer, for a transfer that has not sent a byte, and it would turn the next
     * stray ack into a `SendNext` for a transfer the engine never started. No cluster test can see
     * it: a stranded peer never acks.
     *
     * Two arms, because a fresh transfer can be refused at two points. The **early** refusal — the
     * `{ _, _ -> null }` sizer — happens before the load, so nothing exists yet that could be
     * installed; it stays green if the install is moved ahead of the *second* check. The **late**
     * refusal passes the early check on the caller's record and refuses the loaded config, which is
     * the one arm that can see a transfer installed before refusing.
     *
     * ### What proves the rig fired
     *
     * Each arm counts loads: none for the early refusal, exactly one for the late one. Without the
     * count the late arm could be refused early and prove nothing about the second check.
     */
    @Test
    fun aRefusalNeverLeavesATransferBehind() = raftRunTest {
        suspend fun refused(storedConfig: ConfigPayload?, sizer: (NodeId, ConfigPayload?) -> Int?): Pair<SnapshotSender.AckOutcome, Int> {
            var loads = 0
            val storage = object : RaftStorage by InMemoryRaftStorage() {
                override suspend fun loadSnapshot(): StoredSnapshot? =
                    StoredSnapshot(SnapshotMeta(lastIncludedIndex = 42L, lastIncludedTerm = 3L, config = wide), ByteArray(10))
                        .also { loads++ }
            }
            val sender = SnapshotSender(storage, sizer)
            assertNull(sender.nextChunk(peer, storedIndex = 42L, storedConfig), "rig: this sizer must refuse the transfer")
            return sender.onAck(peer, 4L) to loads
        }

        val (early, earlyLoads) = refused(storedConfig = wide) { _, _ -> null }
        val (late, lateLoads) = refused(storedConfig = null) { _, config -> if (config == wide) null else 4 }

        assertAll(
            { assertEquals(0, earlyLoads, "rig: the early refusal must precede the load") },
            { assertEquals(SnapshotSender.AckOutcome.NoTransfer, early, "an early refusal must install nothing") },
            { assertEquals(1, lateLoads, "rig: the late refusal must come after exactly one load") },
            { assertEquals(SnapshotSender.AckOutcome.NoTransfer, late, "a late refusal must install nothing") },
        )
    }

    /**
     * A fresh transfer is sized on the config it **loads**, not on the caller's record of it.
     *
     * `nextChunk` decides a refusal against the caller's `storedConfig` before loading, so a refusal
     * costs no load. That early check is an optimisation, not the verdict: the chunk carries the
     * loaded snapshot's config, and the engine's record can disagree with it — a restore that drops a
     * malformed stored config leaves the record `null` over it. Sizing on the record there would mint
     * a chunk around an envelope nobody measured, which is #2720 again.
     *
     * ### What proves the rig fired
     *
     * The sizer logs every config it is asked about. The early check must be asked about the record
     * and pass, and the second check must be asked about the loaded config and refuse — otherwise a
     * `null` chunk could come from the early check alone and the arm would pass against a sender that
     * sized on the record.
     */
    @Test
    fun aFreshTransferIsSizedOnTheConfigItLoadsNotTheOneItWasTold() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 42L, lastIncludedTerm = 3L, config = wide), ByteArray(10))
        val asked = mutableListOf<ConfigPayload?>()
        val sender = SnapshotSender(storage) { _, config -> asked += config; if (config == wide) null else 4 }

        val chunk = sender.nextChunk(peer, storedIndex = 42L, storedConfig = null)   // the caller's record disagrees with storage

        assertAll(
            {
                assertEquals(
                    listOf(null, wide), asked,
                    "rig: the early check must be asked about the caller's record and pass, then the " +
                        "second about the config the chunk would carry",
                )
            },
            { assertNull(chunk, "the chunk would carry a config the sizer refuses, so none may be minted") },
        )
    }

    /** The settled membership past the change [wide] stands in for — the sizers below always fit it. */
    private val narrow = ConfigPayload(old = null, new = ClusterConfig(voters = setOf(NodeId("a"))))

    /** A transfer in flight on a snapshot at index 42, with its first 4-byte chunk sent and acked. */
    private class InFlight(val storage: InMemoryRaftStorage, val sender: SnapshotSender, val starve: (Boolean) -> Unit)

    /**
     * Starts [InFlight] under a sizer that refuses [wide] while starved, and gives any other config
     * — and [wide] otherwise — a 4-byte slice.
     */
    private suspend fun inFlight(): InFlight {
        val storage = InMemoryRaftStorage()
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 42L, lastIncludedTerm = 3L, config = wide), ByteArray(10))
        var starved = false
        val sender = SnapshotSender(storage) { _, config -> if (starved && config == wide) null else 4 }
        val first = assertNotNull(sender.nextChunk(peer, storedIndex = 42L, wide), "rig: the transfer must start")
        assertEquals(0L, first.offset, "rig: the first chunk starts at 0")
        assertEquals(SnapshotSender.AckOutcome.SendNext, sender.onAck(peer, 4L), "rig: the first chunk is acked")
        return InFlight(storage, sender) { starved = it }
    }

    /**
     * A refusal with **no** newer snapshot stored keeps the transfer and its acked offset: once the
     * budget recovers, the next chunk is the same snapshot at the offset the follower acked.
     *
     * The half of #2843's fix that must not move. The sim-level arm
     * `SnapshotEnvelopeReserveTest.aRefusalMidTransferResumesFromTheAckedOffsetWhenTheBudgetRecovers`
     * holds it end to end; this one holds it at the decision itself, over repeated refusals.
     *
     * ### What proves the rig fired
     *
     * Each refusal is asserted to have returned `null` — a chunk there would mean the budget never
     * dropped, and "resumed at 4" would merely be "continued at 4".
     */
    @Test
    fun aRefusalWithNoNewerSnapshotKeepsTheAckedOffset() = raftRunTest {
        val f = inFlight()
        f.starve(true)
        repeat(3) {
            assertNull(f.sender.nextChunk(peer, storedIndex = 42L, wide), "rig: the starved budget must refuse")
        }
        f.starve(false)

        val resumed = assertNotNull(f.sender.nextChunk(peer, storedIndex = 42L, wide), "the transfer must survive")
        assertAll(
            { assertEquals(42L, resumed.meta.lastIncludedIndex, "the same snapshot") },
            { assertEquals(4L, resumed.offset, "resumed at the acked offset, not restarted") },
        )
    }

    /**
     * A refused transfer pinned to a snapshot **below the one now stored** gives way to it: the same
     * call returns the newer snapshot's first chunk, while the budget is still starved for the old
     * one (#2843).
     *
     * ### What proves the rig fired
     *
     * The refusal is asserted against the old index first, so the transfer is provably in flight and
     * refused when the newer snapshot appears. The chunk that follows carries the newer meta from
     * offset 0, which no resumption of the old transfer could produce.
     */
    @Test
    fun aRefusedTransferPinnedBelowTheStoredSnapshotRestartsOnIt() = raftRunTest {
        val f = inFlight()
        f.starve(true)
        assertNull(f.sender.nextChunk(peer, storedIndex = 42L, wide), "rig: the starved budget must refuse the old snapshot")

        f.storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 50L, lastIncludedTerm = 3L, config = narrow), ByteArray(10))
        val restarted = assertNotNull(
            f.sender.nextChunk(peer, storedIndex = 50L, narrow),
            "a newer snapshot that fits must replace the refused transfer while the budget is still starved",
        )
        assertAll(
            { assertEquals(50L, restarted.meta.lastIncludedIndex, "the newer snapshot") },
            { assertEquals(narrow, restarted.meta.config, "carrying the newer config") },
            { assertEquals(0L, restarted.offset, "from its first byte") },
        )
    }

    /**
     * A duplicate ack for the **old** transfer, arriving after the restart onto a newer snapshot, must
     * not complete the new transfer.
     *
     * `InstallSnapshotResponse` names no snapshot, and heartbeats resend the outstanding chunk, so
     * duplicate acks are routine. Here the old transfer's ack of 8 bytes arrives after the restart onto
     * a 4-byte snapshot: read as the new transfer's ack it is past the end, and `Complete(50)` would
     * have the engine credit `matchIndex = 50` to a follower holding none of it.
     *
     * ### What proves the rig fired
     *
     * The old transfer provably reached offset 8 (its second chunk was sent and acked), was refused,
     * and the restart provably happened (the chunk returned carries index 50 from offset 0).
     */
    @Test
    fun aStaleAckFromTheDroppedTransferDoesNotCompleteTheNewOne() = raftRunTest {
        val storage = InMemoryRaftStorage()
        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 42L, lastIncludedTerm = 3L, config = wide), ByteArray(10))
        var starved = false
        val sender = SnapshotSender(storage) { _, config -> if (starved && config == wide) null else 4 }
        assertNotNull(sender.nextChunk(peer, storedIndex = 42L, wide), "rig: the transfer must start")
        assertEquals(SnapshotSender.AckOutcome.SendNext, sender.onAck(peer, 4L), "rig: the first chunk is acked")
        val second = assertNotNull(sender.nextChunk(peer, storedIndex = 42L, wide), "rig: the second chunk")
        assertEquals(4L, second.offset, "rig: the second chunk starts at 4")
        assertEquals(SnapshotSender.AckOutcome.SendNext, sender.onAck(peer, 8L), "rig: the old transfer reached 8")
        starved = true
        assertNull(sender.nextChunk(peer, storedIndex = 42L, wide), "rig: the starved budget must refuse the old snapshot")

        storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 50L, lastIncludedTerm = 3L, config = narrow), ByteArray(4))
        val restarted = assertNotNull(sender.nextChunk(peer, storedIndex = 50L, narrow), "rig: the restart")
        assertEquals(50L to 0L, restarted.meta.lastIncludedIndex to restarted.offset, "rig: restarted on 50 from 0")

        val stale = sender.onAck(peer, 8L)                    // the old chunk's duplicate ack, delivered late
        assertTrue(
            stale !is SnapshotSender.AckOutcome.Complete,
            "an ack the old transfer earned must not complete the new one; got $stale",
        )
    }

    /**
     * When the newer snapshot is refused too, the old transfer is still dropped and nothing is
     * installed in its place — the same "a refusal never leaves a transfer behind" rule as a fresh
     * refusal. When the budget recovers, the transfer starts on the newest snapshot rather than
     * resuming the stale one.
     *
     * ### What proves the rig fired
     *
     * The old transfer is refused against its own index first, and the stray ack afterwards would
     * read `SendNext` had it survived.
     */
    @Test
    fun aRefusedTransferDroppedForANewerSnapshotThatIsAlsoRefusedLeavesNothingBehind() = raftRunTest {
        val f = inFlight()
        f.starve(true)
        assertNull(f.sender.nextChunk(peer, storedIndex = 42L, wide), "rig: the starved budget must refuse the old snapshot")

        f.storage.saveSnapshot(SnapshotMeta(lastIncludedIndex = 50L, lastIncludedTerm = 3L, config = wide), ByteArray(10))
        assertNull(f.sender.nextChunk(peer, storedIndex = 50L, wide), "rig: the newer snapshot is refused as well")
        val strayAck = f.sender.onAck(peer, 6L)
        f.starve(false)
        val recovered = assertNotNull(f.sender.nextChunk(peer, storedIndex = 50L, wide), "the budget recovered")

        assertAll(
            { assertEquals(SnapshotSender.AckOutcome.NoTransfer, strayAck, "no transfer may be left in flight") },
            { assertEquals(50L, recovered.meta.lastIncludedIndex, "recovery starts on the newest snapshot") },
            { assertEquals(0L, recovered.offset, "from its first byte") },
        )
    }
}
