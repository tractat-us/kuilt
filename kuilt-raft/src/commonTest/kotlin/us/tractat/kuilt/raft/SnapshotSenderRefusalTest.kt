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
            {
                assertEquals(
                    SnapshotSender.AckOutcome.NoTransfer, sender.onAck(peer, 4L),
                    "and a refused transfer must not be installed",
                )
            },
        )
    }
}
