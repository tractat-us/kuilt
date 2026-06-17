package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientSessionTableTest {
    @Test
    fun firstSerialAppliesAndIsRecorded() {
        val t = ClientSessionTable()
        assertTrue(t.shouldApply(DedupKey(ClientId("c"), 1)))
    }

    @Test
    fun serialAtOrBelowHighWaterMarkIsSkipped() {
        val t = ClientSessionTable()
        t.shouldApply(DedupKey(ClientId("c"), 5))
        assertFalse(t.shouldApply(DedupKey(ClientId("c"), 5))) // exact retry
        assertFalse(t.shouldApply(DedupKey(ClientId("c"), 3))) // stale
        assertTrue(t.shouldApply(DedupKey(ClientId("c"), 6)))  // advances
    }

    @Test
    fun unkeyedEntriesAlwaysApply() {
        val t = ClientSessionTable()
        assertTrue(t.shouldApply(null))
        assertTrue(t.shouldApply(null))
    }

    @Test
    fun snapshotRoundTripPreservesHighWaterMarks() {
        val t = ClientSessionTable()
        t.shouldApply(DedupKey(ClientId("a"), 4))
        t.shouldApply(DedupKey(ClientId("b"), 9))
        val restored = ClientSessionTable.fromBytes(t.toBytes())
        assertFalse(restored.shouldApply(DedupKey(ClientId("a"), 4)))
        assertEquals(true, restored.shouldApply(DedupKey(ClientId("a"), 5)))
    }
}
