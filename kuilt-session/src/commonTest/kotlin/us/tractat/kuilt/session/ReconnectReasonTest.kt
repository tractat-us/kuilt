package us.tractat.kuilt.session

import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectReasonTest {
    @Test
    fun partitionReasonMapsToReconnectReason() = assertAll(
        { assertEquals(ReconnectReason.LinkTimeout, PartitionEvent.Reason.Timeout.toReconnectReason()) },
        { assertEquals(ReconnectReason.Backpressure, PartitionEvent.Reason.Backpressure.toReconnectReason()) },
        { assertEquals(ReconnectReason.TransportClosed, PartitionEvent.Reason.TransportClosed.toReconnectReason()) },
    )
}
