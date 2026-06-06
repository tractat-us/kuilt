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
}
