package us.tractat.kuilt.mdns

import android.net.nsd.NsdManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import us.tractat.kuilt.test.assertAll

@OptIn(ExperimentalCoroutinesApi::class)
class NsdManagerBrowserTest {
    @Test
    fun contendingBrowsersEventuallyResolveBothServices() = runTest {
        val platform = FakeNsdPlatform()
        val first = RecordingNsdSink()
        val second = RecordingNsdSink()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val a = NsdManagerBrowser(platform, dispatcher).browse("_first._tcp.", first)
        val b = NsdManagerBrowser(platform, dispatcher).browse("_second._tcp.", second)
        try {
            platform.found(0, "first")
            platform.found(1, "second")
            assertEquals(1, platform.contentions, "rig must reject an overlapping resolution")
            platform.succeed()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, platform.attempts.size, "contended service must be retried after backoff")
            platform.succeed()
            assertAll(
                { assertEquals(listOf("first"), first.resolved) },
                { assertEquals(listOf("second"), second.resolved) },
            )
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun repeatedContentionBacksOffWithCappedJitterAndDeduplicatesFound() = runTest {
        val platform = FakeNsdPlatform { testScheduler.currentTime }
        platform.alwaysContend = true
        val handle = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler), Random(81))
            .browse("_test._tcp.", RecordingNsdSink())
        try {
            repeat(20) { platform.found(0, "busy") }
            assertEquals(1, platform.attempts.size, "duplicate found callbacks must share pending work")
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            val gaps = platform.times.zipWithNext { a, b -> b - a }
            assertTrue(gaps.size >= 8, "rig must reach the capped backoff")
            assertEquals(platform.attempts.size, platform.contentions, "every attempt must hit contention")
            assertAll(
                {
                    gaps.forEachIndexed { index, gap ->
                        val cap = (100L shl index.coerceAtMost(6)).coerceAtMost(5_000)
                        assertTrue(gap in cap / 2..cap, "retry $index gap $gap outside jitter window $cap")
                    }
                },
                { assertTrue(gaps.drop(6).toSet().size > 1, "capped delays must retain jitter") },
            )
        } finally {
            handle.stop()
        }
        val before = platform.attempts.size
        val stoppedAt = testScheduler.currentTime
        // A timer that survived stop now fires at most once instead of re-arming forever, so
        // advanceUntilIdle stays bounded and a regression reds on the assertions below, not a hang.
        platform.alwaysContend = false
        advanceUntilIdle()
        assertEquals(stoppedAt, testScheduler.currentTime, "stop must remove scheduled timers")
        advanceTimeBy(10_000)
        assertEquals(before, platform.attempts.size, "stop must cancel retries")
    }

    @Test
    fun stopDropsQueuedWorkAndSuppressesLateCallbacksEvenWhenPlatformStopThrows() = runTest {
        val platform = FakeNsdPlatform()
        val sink = RecordingNsdSink()
        val handle = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler))
            .browse("_test._tcp.", sink)
        platform.found(0, "active")
        platform.found(0, "queued")
        platform.throwOnStop = true
        assertFailsWith<UnsupportedOperationException> { handle.stop() }
        platform.succeed()
        platform.found(0, "after-stop")
        platform.listeners[0].onLost("active")
        assertAll(
            { assertEquals(listOf("active"), platform.attempts) },
            { assertTrue(sink.resolved.isEmpty(), "late completion must not reach stopped sink") },
            { assertTrue(sink.lost.isEmpty(), "late loss must not reach stopped sink") },
            { assertEquals(1, platform.stops) },
        )
    }

    @Test
    fun lossCancelsRetryAndFreshIncarnationCanResolve() = runTest {
        val platform = FakeNsdPlatform()
        platform.alwaysContend = true
        val sink = RecordingNsdSink()
        val handle = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler))
            .browse("_test._tcp.", sink)
        try {
            platform.found(0, "service")
            runCurrent()
            platform.listeners[0].onLost("service")
            val lostAt = testScheduler.currentTime
            // Contention off first: a timer that survived the loss fires once rather than
            // re-arming forever, so advanceUntilIdle stays bounded.
            platform.alwaysContend = false
            advanceUntilIdle()
            assertEquals(lostAt, testScheduler.currentTime, "loss must remove scheduled timer")
            advanceTimeBy(10_000)
            assertEquals(1, platform.attempts.size, "lost service must not retry")
            platform.found(0, "service")
            platform.succeed()
            assertAll(
                { assertEquals(listOf("service"), sink.resolved) },
                { assertEquals(listOf("service"), sink.lost) },
            )
        } finally {
            handle.stop()
        }
    }

    @Test
    fun lossDropsQueuedServiceAndOldCompletionCannotResolveItsReplacement() = runTest {
        val platform = FakeNsdPlatform()
        val sink = RecordingNsdSink()
        val handle = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler))
            .browse("_test._tcp.", sink)
        try {
            platform.found(0, "active")
            platform.found(0, "queued")
            platform.listeners[0].onLost("queued")
            platform.listeners[0].onLost("active")
            platform.found(0, "active")
            platform.succeed()
            assertTrue(sink.resolved.isEmpty(), "old incarnation must not be delivered")
            platform.succeed()
            assertAll(
                { assertEquals(listOf("active", "active"), platform.attempts) },
                { assertEquals(listOf("active"), sink.resolved) },
            )
        } finally {
            handle.stop()
        }
    }

    @Test
    fun stoppingOneRegistrationLeavesItsSiblingRetryLive() = runTest {
        val platform = FakeNsdPlatform()
        platform.alwaysContend = true
        val browser = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler))
        val first = browser.browse("_test._tcp.", RecordingNsdSink())
        val sink = RecordingNsdSink()
        val second = browser.browse("_test._tcp.", sink)
        try {
            platform.found(0, "same-name")
            platform.found(1, "same-name")
            first.stop()
            platform.alwaysContend = false
            advanceTimeBy(1_000)
            runCurrent()
            platform.succeed()
            assertAll(
                { assertEquals(3, platform.attempts.size, "only live registration should retry") },
                { assertEquals(listOf("same-name"), sink.resolved) },
            )
        } finally {
            second.stop()
        }
    }

    @Test
    fun synchronousThrowsReleaseQueueAndDoNotRetryAsContention() = runTest {
        val platform = FakeNsdPlatform()
        val sink = RecordingNsdSink()
        val handle = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler))
            .browse("_test._tcp.", sink)
        try {
            platform.found(0, "first")
            platform.throwNames += "throws"
            platform.found(0, "throws")
            platform.found(0, "last")
            platform.succeed()
            assertEquals(listOf("first", "throws", "last"), platform.attempts, "throw must release the queue")
            platform.succeed()
            advanceTimeBy(10_000)
            assertAll(
                { assertEquals(listOf("first", "throws", "last"), platform.attempts) },
                { assertEquals(1, platform.throws, "rig must throw during dispatch") },
                { assertEquals(listOf("first", "last"), sink.resolved) },
            )
        } finally {
            handle.stop()
        }
    }

    @Test
    fun synchronousThrowOnRetryDoesNotStrandOtherServices() = runTest {
        val platform = FakeNsdPlatform()
        val sink = RecordingNsdSink()
        platform.alwaysContend = true
        val handle = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler))
            .browse("_test._tcp.", sink)
        try {
            platform.found(0, "retry")
            platform.alwaysContend = false
            platform.throwNames += "retry"
            advanceTimeBy(1_000)
            runCurrent()
            platform.found(0, "healthy")
            platform.succeed()
            assertAll(
                { assertEquals(listOf("retry", "retry", "healthy"), platform.attempts) },
                { assertEquals(1, platform.throws) },
                { assertEquals(listOf("healthy"), sink.resolved) },
            )
        } finally {
            handle.stop()
        }
    }

    @Test
    fun nonContentionFailureDrainsWithoutRetry() = runTest {
        val platform = FakeNsdPlatform()
        val sink = RecordingNsdSink()
        val handle = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler))
            .browse("_test._tcp.", sink)
        try {
            platform.found(0, "failed")
            platform.found(0, "healthy")
            platform.fail(NsdManager.FAILURE_INTERNAL_ERROR)
            platform.succeed()
            advanceTimeBy(10_000)
            assertAll(
                { assertEquals(listOf("failed", "healthy"), platform.attempts) },
                { assertEquals(listOf("healthy"), sink.resolved) },
            )
        } finally {
            handle.stop()
        }
    }

    @Test
    fun aNewFoundAfterCompletionCanResolveAgainButAnOldCallbackCannotFinishTheRetry() = runTest {
        val platform = FakeNsdPlatform()
        val sink = RecordingNsdSink()
        val handle = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler))
            .browse("_test._tcp.", sink)
        try {
            platform.found(0, "service")
            val old = platform.activeListener()
            platform.fail(NsdManager.FAILURE_ALREADY_ACTIVE)
            advanceTimeBy(1_000)
            runCurrent()
            old.onResolved(NsdRecord("stale", 1234, "127.0.0.1", emptyMap()))
            assertTrue(sink.resolved.isEmpty(), "old attempt must not complete a newer attempt")
            platform.succeed()
            platform.found(0, "service")
            platform.succeed()
            assertAll(
                { assertEquals(listOf("service", "service", "service"), platform.attempts) },
                { assertEquals(listOf("service", "service"), sink.resolved) },
            )
        } finally {
            handle.stop()
        }
    }

    @Test
    fun discoveryFailureCancelsPendingRetryAndIgnoresFurtherFinds() = runTest {
        val platform = FakeNsdPlatform()
        platform.alwaysContend = true
        val sink = RecordingNsdSink()
        val handle = NsdManagerBrowser(platform, StandardTestDispatcher(testScheduler))
            .browse("_test._tcp.", sink)
        try {
            platform.found(0, "busy")
            platform.listeners[0].onFailed(UnsupportedOperationException("discovery failed"))
            platform.found(0, "late")
            advanceTimeBy(10_000)
            assertAll(
                { assertEquals(listOf("busy"), platform.attempts) },
                { assertEquals(1, sink.failures) },
            )
        } finally {
            handle.stop()
        }
    }

}

private class RecordingNsdSink : NsdBrowseSink {
    val resolved = mutableListOf<String>()
    val lost = mutableListOf<String>()
    var failures = 0
    override fun onFound(serviceName: String, requestResolve: () -> Unit) = requestResolve()
    override fun onResolved(record: NsdRecord) { resolved += record.serviceName }
    override fun onLost(serviceName: String) { lost += serviceName }
    override fun onFailed(cause: Throwable) { failures++ }
}

/** A single occupied platform slot shared by independent browsers; callbacks are explicit. */
private class FakeNsdPlatform(private val now: () -> Long = { 0L }) : NsdPlatform {
    val listeners = mutableListOf<NsdPlatform.Listener>()
    val attempts = mutableListOf<String>()
    val times = mutableListOf<Long>()
    val throwNames = mutableSetOf<String>()
    var throws = 0
    var stops = 0
    var throwOnStop = false
    var alwaysContend = false
    var contentions = 0
    private var active: Pair<String, NsdResolutionListener>? = null

    override fun discover(serviceType: String, listener: NsdPlatform.Listener): NsdBrowseHandle {
        listeners += listener
        return NsdBrowseHandle {
            stops++
            if (throwOnStop) throw UnsupportedOperationException("stop failed")
        }
    }

    fun found(registration: Int, name: String) {
        listeners[registration].onFound(object : NsdService {
            override val name: String = name
            override fun resolve(listener: NsdResolutionListener) {
                attempts += name
                times += now()
                if (name in throwNames) {
                    throws++
                    throw UnsupportedOperationException("resolve failed")
                }
                if (alwaysContend || active != null) {
                    contentions++
                    listener.onFailed(NsdManager.FAILURE_ALREADY_ACTIVE)
                } else {
                    active = name to listener
                }
            }
        })
    }

    fun activeListener(): NsdResolutionListener = checkNotNull(active).second

    fun fail(code: Int) {
        val (_, listener) = checkNotNull(active) { "rig has no outstanding resolution" }
        active = null
        listener.onFailed(code)
    }

    fun succeed() {
        val (name, listener) = checkNotNull(active) { "rig has no outstanding resolution" }
        active = null
        listener.onResolved(NsdRecord(name, 1234, "127.0.0.1", emptyMap()))
    }
}
