@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.raftCbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LogEntryDedupTest {
    @Test
    fun dedupKeyDefaultsNullAndSurvivesCbor() {
        val plain = LogEntry(index = 1, term = 1, command = byteArrayOf(1))
        assertEquals(null, plain.dedupKey)

        val keyed = plain.copy(dedupKey = DedupKey(ClientId("c"), 5))
        val decoded = raftCbor.decodeFromByteArray(
            LogEntry.serializer(),
            raftCbor.encodeToByteArray(LogEntry.serializer(), keyed),
        )
        assertEquals(keyed, decoded)
    }

    @Test
    fun dedupKeyParticipatesInEquality() {
        val base = LogEntry(index = 1, term = 1, command = byteArrayOf(1))
        assertNotEquals(base.copy(dedupKey = DedupKey(ClientId("c"), 1)), base)
    }
}
