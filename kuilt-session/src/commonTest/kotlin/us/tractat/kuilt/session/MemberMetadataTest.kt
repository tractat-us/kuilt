package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemberMetadataTest {

    private val peerA = PeerId("peer-a")
    private val peerB = PeerId("peer-b")
    private val peerC = PeerId("peer-c")
    private val replicaX = ReplicaId("replica-x")
    private val replicaY = ReplicaId("replica-y")

    @Test
    fun singleReplicaSet() {
        val meta = MemberMetadata.empty()
            .set(peerA, "Alice", timestamp = 1L, replica = replicaX)

        assertEquals("Alice", meta.names[peerA])
    }

    @Test
    fun lastWriteWins_higherTimestampWins() {
        val meta = MemberMetadata.empty()
            .set(peerA, "Alice", timestamp = 1L, replica = replicaX)
            .set(peerA, "Alicia", timestamp = 2L, replica = replicaX)

        assertEquals("Alicia", meta.names[peerA])
    }

    @Test
    fun lastWriteWins_staleMergeLooses() {
        // LWW resolution happens at merge time, not at local-set time.
        // A stale write (lower timestamp) from a remote replica loses on merge.
        val current = MemberMetadata.empty()
            .set(peerA, "Alicia", timestamp = 2L, replica = replicaX)
        val stale = MemberMetadata.empty()
            .set(peerA, "Alice", timestamp = 1L, replica = replicaX)

        val merged = current.merge(stale)

        assertEquals("Alicia", merged.names[peerA])
    }

    @Test
    fun concurrentWrites_tieBreakByReplicaId_isDeterministic() {
        // replicaY > replicaX lexicographically ("replica-y" > "replica-x")
        val replicaXMeta = MemberMetadata.empty()
            .set(peerA, "Alice", timestamp = 1L, replica = replicaX)
        val replicaYMeta = MemberMetadata.empty()
            .set(peerA, "Alicia", timestamp = 1L, replica = replicaY)

        val mergedXY = replicaXMeta.merge(replicaYMeta)
        val mergedYX = replicaYMeta.merge(replicaXMeta)

        assertAll(
            { assertEquals(mergedXY.names[peerA], mergedYX.names[peerA], "merge order must not affect outcome") },
            { assertEquals("Alicia", mergedXY.names[peerA], "higher replicaId wins on timestamp tie") },
        )
    }

    @Test
    fun mergeIsCommutative() {
        val metaA = MemberMetadata.empty()
            .set(peerA, "Alice", timestamp = 1L, replica = replicaX)
        val metaB = MemberMetadata.empty()
            .set(peerB, "Bob", timestamp = 2L, replica = replicaY)

        assertAll(
            { assertEquals(metaA.merge(metaB).names, metaB.merge(metaA).names) },
        )
    }

    @Test
    fun mergeIsIdempotent() {
        val meta = MemberMetadata.empty()
            .set(peerA, "Alice", timestamp = 1L, replica = replicaX)

        assertEquals(meta.names, meta.merge(meta).names)
    }

    @Test
    fun multiPeerMerge_unionIsVisible() {
        val metaAB = MemberMetadata.empty()
            .set(peerA, "Alice", timestamp = 1L, replica = replicaX)
            .set(peerB, "Bob", timestamp = 1L, replica = replicaX)
        val metaC = MemberMetadata.empty()
            .set(peerC, "Charlie", timestamp = 1L, replica = replicaY)

        val merged = metaAB.merge(metaC)

        assertAll(
            { assertEquals("Alice", merged.names[peerA]) },
            { assertEquals("Bob", merged.names[peerB]) },
            { assertEquals("Charlie", merged.names[peerC]) },
        )
    }

    @Test
    fun emptyNames_returnsEmptyMap() {
        val meta = MemberMetadata.empty()

        assertEquals(emptyMap(), meta.names)
    }

    @Test
    fun getUnsetPeer_returnsNull() {
        val meta = MemberMetadata.empty()
            .set(peerA, "Alice", timestamp = 1L, replica = replicaX)

        assertNull(meta.names[peerB])
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
