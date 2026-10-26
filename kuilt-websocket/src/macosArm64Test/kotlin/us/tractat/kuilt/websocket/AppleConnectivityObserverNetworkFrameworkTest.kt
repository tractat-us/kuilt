package us.tractat.kuilt.websocket

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Smoke test of the real Network.framework binding behind [appleConnectivityObserver]. Every test in
 * `AppleConnectivityObserverTest` injects a fake monitor, so without this one nothing exercises
 * `nw_path_monitor_create` / `_set_queue` / `_set_update_handler` / `_start` / `_cancel`.
 *
 * Runs under [runBlocking] in wall-clock time because the reading arrives on a GCD queue the test does
 * not own; a virtual clock would time out before the OS delivers. macOS only: a host network path is
 * always present on the runner, which the iOS simulator does not promise.
 *
 * What this cannot discriminate: the path does not change while the test runs, so "the reading
 * never changes after close" would hold even if [AppleConnectivityObserver.close] stopped suppressing
 * late callbacks. Suppression is pinned by the fake-monitor tests; this test pins that the real
 * monitor delivers a first reading, and that cancelling it neither throws nor delivers a different one.
 */
class AppleConnectivityObserverNetworkFrameworkTest {
    @Test
    fun realPathMonitorDeliversAReadingAndCloseFreezesIt() = runBlocking {
        val observer = appleConnectivityObserver()
        try {
            val first = withTimeoutOrNull(FIRST_READING_CEILING) { observer.reachability.filterNotNull().first() }
            assertNotNull(first, "the real Network.framework monitor must deliver a first reading within $FIRST_READING_CEILING")
            observer.close()
            // close() sets the latch under the observer's lock before cancelling, so this is the last
            // value any callback could have written.
            val atClose = observer.reachability.value
            // Give a callback already queued on the monitor's GCD queue, or one cancellation delivers,
            // time to land before reading again.
            delay(AFTER_CLOSE_WINDOW)
            assertAll(
                { assertNotNull(atClose) },
                { assertEquals(atClose, observer.reachability.value, "a reading after close must not change") },
            )
        } finally {
            observer.close()
        }
    }

    private companion object {
        val FIRST_READING_CEILING = 10.seconds
        val AFTER_CLOSE_WINDOW = 500.milliseconds
    }
}
