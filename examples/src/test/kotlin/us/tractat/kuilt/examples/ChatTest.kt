package us.tractat.kuilt.examples

import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Example: a two-replica chat log using [Rga].
 *
 * Shows how to use kuilt-crdt's [Rga] as a standalone value object (no network
 * needed). Each replica inserts messages locally and exchanges ops; [Rga.piece]
 * merges any two op-logs into an identical sequence regardless of order.
 *
 * In production, broadcast the [us.tractat.kuilt.crdt.RgaOp] values to peers
 * via [us.tractat.kuilt.crdt.replicator.SeamReplicator] over a
 * [us.tractat.kuilt.core.Seam].
 */
class ChatTest {

    @Test
    fun `messages from two replicas converge`() {
        val alice = ReplicaId("alice")
        val bob = ReplicaId("bob")

        var aliceLog = Rga.empty<String>()
        var bobLog = Rga.empty<String>()

        // Alice sends a message — inserts after HEAD (the empty-list sentinel)
        val (aliceNext, aliceOp) = aliceLog.insertAfter(alice, RgaId.HEAD, "hello from alice")
        aliceLog = aliceNext

        // Bob sends a concurrent message — also starts from the empty log
        val (bobNext, bobOp) = bobLog.insertAfter(bob, RgaId.HEAD, "hello from bob")
        bobLog = bobNext

        // Each replica applies the other's op
        val aliceMerged = aliceLog.piece(bobLog)
        val bobMerged = bobLog.piece(aliceLog)

        // Both replicas converge to the same sequence
        assertEquals(aliceMerged.toList(), bobMerged.toList())
    }
}
