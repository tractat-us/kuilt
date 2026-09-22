package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.SnapshotSender
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            assertNull(sender.nextChunk(peer, storedConfig), "rig: this sizer must refuse the transfer")
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

        val chunk = sender.nextChunk(peer, storedConfig = null)   // the caller's record disagrees with storage

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
}
