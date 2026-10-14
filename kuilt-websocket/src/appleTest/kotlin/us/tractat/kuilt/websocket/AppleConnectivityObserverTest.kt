@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.websocket

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Network.nw_path_status_invalid
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_t
import platform.Network.nw_path_status_unsatisfied
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AppleConnectivityObserverTest {
    @Test
    fun pathCallbacksMoveSeamCapability() = runTest {
        val monitor = CallbackMonitor()
        val observer = AppleConnectivityObserver(monitor)
        val seam = seam(observer)
        val collected = mutableListOf<TransportCapability>()
        backgroundScope.launch { seam.capability.collect { collected += it } }
        try {
            runCurrent()
            val initial = seam.capability.value
            val initialReading = observer.reachability.value
            val statuses = listOf(
                nw_path_status_satisfied,
                nw_path_status_unsatisfied,
                nw_path_status_satisfied,
                nw_path_status_satisfiable,
                nw_path_status_satisfied,
                nw_path_status_invalid,
            )
            val actual = statuses.map { status ->
                monitor.emit(status)
                runCurrent()
                seam.capability.value.availability
            }
            assertAll(
                { assertEquals(1, monitor.starts, "the adapter must register its callback") },
                { assertEquals(statuses.size, monitor.deliveries, "every status must reach the adapter") },
                { assertNull(initialReading, "no reading may be invented before the first callback") },
                { assertIs<FabricAvailability.Unknown>(initial.availability) },
                { assertEquals(FabricAvailability.Available, actual[0], "satisfied path") },
                { assertIs<FabricAvailability.Unavailable>(actual[1], "unsatisfied path") },
                { assertEquals(FabricAvailability.Available, actual[2], "path recovery") },
                { assertIs<FabricAvailability.Unknown>(actual[3], "requires a connection cannot confirm reachability") },
                { assertEquals(FabricAvailability.Available, actual[4], "path activation") },
                { assertIs<FabricAvailability.Unknown>(actual[5], "invalid status is indeterminate") },
                { assertEquals(listOf(initial.availability) + actual, collected.map { it.availability }) },
                { assertEquals(setOf(RELAY_ROLES), collected.map { it.roles }.toSet()) },
            )
        } finally {
            observer.close()
            seam.close(CloseReason.Normal)
        }
    }

    @Test
    fun closeCancelsOnceAndSuppressesCallbacksDuringAndAfterCancellation() = runTest {
        val monitor = CallbackMonitor()
        val observer = AppleConnectivityObserver(monitor)
        val seam = seam(observer)
        try {
            monitor.emit(nw_path_status_satisfied)
            val before = seam.capability.value
            // Deterministic teardown interleaving: cancellation invokes the retained callback.
            monitor.onCancel = { monitor.emit(nw_path_status_unsatisfied) }
            observer.close()
            val duringCancel = seam.capability.value
            monitor.emit(nw_path_status_unsatisfied)
            val afterCancel = seam.capability.value
            observer.close()
            assertAll(
                { assertEquals(FabricAvailability.Available, before.availability) },
                { assertEquals(1, monitor.cancels, "close must cancel exactly once") },
                { assertEquals(3, monitor.deliveries, "late callbacks must actually be delivered by the rig") },
                { assertEquals(before, duringCancel, "a callback during cancel must be suppressed") },
                { assertEquals(before, afterCancel, "a callback after close must be suppressed") },
            )
        } finally {
            observer.close()
            seam.close(CloseReason.Normal)
        }
    }

    @Test
    fun closeBeforeFirstCallbackKeepsTheUnobservedFloor() = runTest {
        val monitor = CallbackMonitor()
        val observer = AppleConnectivityObserver(monitor)
        val seam = seam(observer)
        try {
            val before = seam.capability.value
            observer.close()
            monitor.emit(nw_path_status_satisfied)
            assertAll(
                { assertEquals(1, monitor.cancels) },
                { assertEquals(1, monitor.deliveries) },
                { assertNull(observer.reachability.value) },
                { assertEquals(before, seam.capability.value) },
            )
        } finally {
            observer.close()
            seam.close(CloseReason.Normal)
        }
    }

    @Test
    fun seamCloseLeavesTheCallerOwnedObserverRunning() = runTest {
        val monitor = CallbackMonitor()
        val observer = AppleConnectivityObserver(monitor)
        val seam = seam(observer)
        try {
            seam.close(CloseReason.Normal)
            monitor.emit(nw_path_status_satisfied)
            assertAll(
                { assertEquals(0, monitor.cancels, "seam teardown must not cancel a shared observer") },
                { assertEquals(NetworkReachability.Reachable, observer.reachability.value) },
            )
        } finally {
            observer.close()
        }
    }

    private fun TestScope.seam(observer: ConnectivityObserver): Seam = WebSocketSeam(
        selfId = PeerId("apple"),
        remoteId = PeerId("relay"),
        session = IdleSession(backgroundScope.coroutineContext),
        dispatcher = backgroundScope.coroutineContext,
        roles = RELAY_ROLES,
        connectivity = observer,
    )

    private class CallbackMonitor : ApplePathMonitor {
        private lateinit var callback: (nw_path_status_t) -> Unit
        var starts = 0
        var cancels = 0
        var deliveries = 0
        var onCancel: () -> Unit = {}

        override fun start(onStatus: (nw_path_status_t) -> Unit) {
            starts++
            callback = onStatus
        }

        override fun cancel() {
            cancels++
            onCancel()
        }

        fun emit(status: nw_path_status_t) {
            deliveries++
            callback(status)
        }
    }

    // No relay or real network: the production WebSocketSeam still owns its normal capability view.
    private class IdleSession(override val coroutineContext: CoroutineContext) : DefaultWebSocketSession {
        override var pingIntervalMillis: Long = 0
        override var timeoutMillis: Long = 0
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override val closeReason = CompletableDeferred<io.ktor.websocket.CloseReason?>()
        @OptIn(io.ktor.utils.io.InternalAPI::class)
        override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit
        override suspend fun flush() = Unit
        @Deprecated("Use cancel() instead", level = DeprecationLevel.ERROR)
        override fun terminate() = Unit
    }
}
