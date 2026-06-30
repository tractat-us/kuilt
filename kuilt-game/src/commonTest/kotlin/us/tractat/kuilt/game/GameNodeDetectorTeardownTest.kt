@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.raft.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Guards #1001-class leak in [GameNode]: cancelling the [kotlinx.coroutines.Job] returned by
 * [launchDetectorFor] must tear down **all** of the detector's coroutines — its heartbeat loop
 * and its inbound collector — not just the events collector. The inbound collector subscribes to
 * the shared `rawLiveness` flow, so a leaked detector keeps that subscription alive past eviction.
 *
 * Signal: `rawLiveness.subscriptionCount` is exactly the number of live detector inbound
 * collectors. It must return to 0 once the only detector's job is cancelled.
 */
class GameNodeDetectorTeardownTest {

    /** A minimal heartbeat seam: the target peer is present in [peers]; sends are no-ops. */
    private class FakeHeartbeatSeam(self: PeerId, target: PeerId) : Seam {
        override val selfId: PeerId = self
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(self, target))
        override val state: StateFlow<SeamState> = MutableStateFlow<SeamState>(SeamState.Woven)
        override val incoming: Flow<Swatch> = MutableSharedFlow()
        override suspend fun broadcast(payload: ByteArray) = Unit
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
        override suspend fun close(reason: CloseReason) = Unit
    }

    @Test
    fun `cancelling the detector job tears down the inbound collector`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val self = PeerId("host")
            val target = PeerId("voter-1")
            val rawLiveness = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)
            val heartbeatSeam = FakeHeartbeatSeam(self, target)
            val evictions = Channel<NodeId>(Channel.UNLIMITED)

            val job = backgroundScope.launchDetectorFor(
                voterId = NodeId(target.value),
                heartbeatSeam = heartbeatSeam,
                rawLiveness = rawLiveness,
                evictions = evictions,
                config = fastLivenessConfig(),
                clock = { Instant.fromEpochMilliseconds(0L) },
            )
            runCurrent() // let the detector's inbound collector subscribe

            assertEquals(1, rawLiveness.subscriptionCount.value, "detector must subscribe to rawLiveness while live")

            job.cancel()
            runCurrent() // let cancellation propagate to the detector's child coroutines

            assertEquals(0, rawLiveness.subscriptionCount.value, "cancelling the detector job must drop its rawLiveness subscription (a leaked inbound collector stays subscribed)")
        }
}
