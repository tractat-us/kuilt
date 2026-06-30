@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.liveness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HeartbeatPartitionDetectorTransportCloseTest {

    private val config = HeartbeatConfig(
        interval = 5.seconds,
        timeout = 15.seconds,
        reconnectWindow = 60.seconds,
    )

    /** A minimal controllable [Seam]: `peers` is mutable, `incoming` never completes. */
    private class FakeLink(
        override val selfId: PeerId,
        private val target: PeerId,
    ) : Seam {
        private val _peers = MutableStateFlow(setOf(selfId, target))
        override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()
        private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
        override val state: StateFlow<SeamState> = _state.asStateFlow()
        private val _incoming = MutableSharedFlow<Swatch>(extraBufferCapacity = 64)
        override val incoming: Flow<Swatch> = _incoming.asSharedFlow()
        override suspend fun broadcast(payload: ByteArray) = Unit
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
        override suspend fun close(reason: CloseReason) = Unit
        fun dropTarget() = _peers.update { it - target }
    }

    @Test
    fun `target leaving peers fires PeerUnresponsive TransportClosed immediately`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val self = PeerId("self")
            val target = PeerId("target")
            val link = FakeLink(self, target)
            val detector = HeartbeatPartitionDetector(link, target, config, { Instant.fromEpochMilliseconds(0L) })

            detector.start(backgroundScope)
            // Let the detector observe the target as present, then drop it.
            advanceTimeBy(config.interval.inWholeMilliseconds)
            link.dropTarget()

            val event = detector.events.first()
            assertEquals(target, event.peerId)
            val unresponsive = event as PartitionEvent.PeerUnresponsive
            assertEquals(PartitionEvent.Reason.TransportClosed, unresponsive.reason)
        }

    @Test
    fun `peer that stays in peers still uses the Timeout path`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val self = PeerId("self")
            val target = PeerId("target")
            val link = FakeLink(self, target) // never drops target
            var nowMs = 0L
            val detector = HeartbeatPartitionDetector(link, target, config, { Instant.fromEpochMilliseconds(nowMs) })

            detector.start(backgroundScope)
            // No pong ever arrives; advance past the timeout.
            nowMs = config.timeout.inWholeMilliseconds + config.interval.inWholeMilliseconds
            advanceTimeBy(nowMs)

            val event = detector.events.first()
            val unresponsive = event as PartitionEvent.PeerUnresponsive
            assertEquals(PartitionEvent.Reason.Timeout, unresponsive.reason)
        }
}
