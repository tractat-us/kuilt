package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CommittedTypeTest {
    @Test
    fun snapshot_valueEquality() {
        assertEquals(Snapshot(3L, byteArrayOf(1, 2)), Snapshot(3L, byteArrayOf(1, 2)))
        assertNotEquals(Snapshot(3L, byteArrayOf(1, 2)), Snapshot(3L, byteArrayOf(9)))
        assertNotEquals(Snapshot(3L, byteArrayOf(1, 2)), Snapshot(4L, byteArrayOf(1, 2)))
    }

    @Test
    fun committed_entryWrapsLogEntry() {
        val e = LogEntry(7L, 2L, byteArrayOf(42))
        assertEquals(e, Committed.Entry(e).entry)
    }

    // ── LogEntry equality covers the config field ──────────────────────────────

    @Test
    fun logEntry_equalWhenAllFieldsMatch_includingNullConfig() {
        val e1 = LogEntry(1L, 2L, byteArrayOf(0x01), isNoOp = false, config = null)
        val e2 = LogEntry(1L, 2L, byteArrayOf(0x01), isNoOp = false, config = null)
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun logEntry_equalWhenConfigMatches() {
        val cfg = ConfigPayload(old = null, new = ClusterConfig(voters = setOf(NodeId("a"))))
        val e1 = LogEntry(3L, 1L, byteArrayOf(), config = cfg)
        val e2 = LogEntry(3L, 1L, byteArrayOf(), config = cfg)
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun logEntry_notEqualWhenConfigDiffers() {
        val cfg1 = ConfigPayload(old = null, new = ClusterConfig(voters = setOf(NodeId("a"))))
        val cfg2 = ConfigPayload(old = null, new = ClusterConfig(voters = setOf(NodeId("b"))))
        val e1 = LogEntry(3L, 1L, byteArrayOf(), config = cfg1)
        val e2 = LogEntry(3L, 1L, byteArrayOf(), config = cfg2)
        assertNotEquals(e1, e2)
    }

    @Test
    fun logEntry_notEqualWhenOneHasConfigAndOtherDoesNot() {
        val cfg = ConfigPayload(old = null, new = ClusterConfig(voters = setOf(NodeId("a"))))
        val withConfig = LogEntry(5L, 1L, byteArrayOf(), config = cfg)
        val withoutConfig = LogEntry(5L, 1L, byteArrayOf(), config = null)
        assertNotEquals(withConfig, withoutConfig)
    }

    @Test
    fun logEntry_hashCodeDiffersWhenConfigDiffers() {
        val cfg1 = ConfigPayload(old = null, new = ClusterConfig(voters = setOf(NodeId("x"))))
        val cfg2 = ConfigPayload(old = null, new = ClusterConfig(voters = setOf(NodeId("y"))))
        val e1 = LogEntry(1L, 1L, byteArrayOf(), config = cfg1)
        val e2 = LogEntry(1L, 1L, byteArrayOf(), config = cfg2)
        // Hash collisions are possible but statistically unlikely for these distinct configs.
        assertNotEquals(e1.hashCode(), e2.hashCode())
    }
}
