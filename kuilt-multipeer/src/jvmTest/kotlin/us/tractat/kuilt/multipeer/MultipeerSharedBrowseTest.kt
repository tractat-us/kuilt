package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: exercises the production IO boundary with callbacks delivered during registration and overlapping real collectors
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.LinkedBlockingQueue
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultipeerSharedBrowseTest {
    @Test
    fun collectorQueuedDuringTeardownDoesNotReplayTheStoppedSession() = runBlocking {
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        val lib = BrowseLib(
            arrivalForStart = { if (it == 1) "old" else "current" },
            beforeStop = {
                stopEntered.countDown()
                assertTrue(allowStop.await(5, TimeUnit.SECONDS), "test must release teardown")
            },
        )
        val browser = lib.browser()
        // Observe the actual lifecycle lock rather than guessing when onSubscription runs.
        val lockField = browser.javaClass.getDeclaredField("lock").apply { isAccessible = true }
        val lifecycleLock = lockField.get(browser) as ReentrantLock
        val ready = CountDownLatch(1)
        val first = launch(Dispatchers.Default) { browser.departures().collect { ready.countDown() } }
        val seen = LinkedBlockingQueue<String>()
        var second: kotlinx.coroutines.Job? = null
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS), "first session must start")
            first.cancel()
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS), "last collector must enter teardown")
            second = launch(Dispatchers.Default) { browser.discoveries().collect { seen.add(it.peerKey) } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!lifecycleLock.hasQueuedThreads() && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(lifecycleLock.hasQueuedThreads(), "new subscriber must queue behind teardown after reserving replay")
            allowStop.countDown()
            first.join()
            assertEquals("current", seen.poll(5, TimeUnit.SECONDS), "the restarted collector must not receive the stopped session's peer")
            assertEquals(2, lib.starts.get(), "the collector must own a newly started session")
        } finally {
            allowStop.countDown()
            first.cancelAndJoin()
            second?.cancelAndJoin()
        }
    }

    @Test
    fun lateDiscoveriesReceiveCurrentPeersWithoutAnotherCallback() = runBlocking {
        val lib = BrowseLib()
        val browser = lib.browser()
        val ready = CountDownLatch(1)
        val departure = launch(Dispatchers.Default) { browser.departures().collect { ready.countDown() } }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS), "departure collector must start browsing")
            lib.fireFound("gone")
            lib.fireLost("gone")
            val seen = LinkedBlockingQueue<String>()
            val discovery = launch(Dispatchers.Default) { browser.discoveries().collect { seen.add(it.peerKey) } }
            try {
                assertEquals("arriving", seen.poll(5, TimeUnit.SECONDS), "late discovery collector must receive the current peer without a repeated callback")
                lib.fireFound("later")
                assertEquals("later", seen.poll(5, TimeUnit.SECONDS), "live arrivals follow the initial snapshot; departed peers are not replayed")
                assertEquals(1, lib.starts.get(), "seeding must not restart browsing")
            } finally {
                discovery.cancelAndJoin()
            }
        } finally {
            departure.cancelAndJoin()
        }
    }

    @Test
    fun departureSubscriptionPrecedesSynchronousRegistrationCallback() = runBlocking {
        val lib = BrowseLib()
        val received = CountDownLatch(1)
        val collector = launch(Dispatchers.Default) {
            lib.browser().departures().collect { if (it == "leaving") received.countDown() }
        }
        try {
            assertTrue(received.await(5, TimeUnit.SECONDS), "departure emitted during registration must reach the first collector")
            assertEquals(1, lib.lostFirings.get(), "the registration callback must actually fire")
        } finally {
            collector.cancelAndJoin()
        }
        assertEquals(1, lib.stops.get(), "the lone departure collector owns teardown")
    }

    @Test
    fun cancellingDiscoveriesKeepsDeparturesAlive() = overlap(cancelDiscoveriesFirst = true)

    @Test
    fun cancellingDeparturesKeepsDiscoveriesAlive() = overlap(cancelDiscoveriesFirst = false)

    private fun overlap(cancelDiscoveriesFirst: Boolean) = runBlocking {
        val lib = BrowseLib()
        val browser = lib.browser()
        val arrivals = CountDownLatch(1)
        val departures = CountDownLatch(1)
        val discovery = launch(Dispatchers.Default) { browser.discoveries().collect { arrivals.countDown() } }
        val departure = launch(Dispatchers.Default) { browser.departures().collect { departures.countDown() } }
        try {
            lib.deliverUntil(arrivals, departures)
            assertEquals(1, lib.starts.get(), "overlapping feeds must share one session")
            if (cancelDiscoveriesFirst) discovery.cancelAndJoin() else departure.cancelAndJoin()
            assertEquals(0, lib.stops.get(), "one remaining collector must keep browsing")
        } finally {
            discovery.cancelAndJoin()
            departure.cancelAndJoin()
        }
        assertEquals(1, lib.stops.get(), "the last collector stops browsing exactly once")
    }

    @Test
    fun overlappingDiscoveryCollectorsShareOneSessionAndCanRestart() = runBlocking {
        val lib = BrowseLib()
        val browser = lib.browser()
        val firstSeen = CountDownLatch(1)
        val secondSeen = CountDownLatch(1)
        val first = launch(Dispatchers.Default) { browser.discoveries().collect { firstSeen.countDown() } }
        val second = launch(Dispatchers.Default) { browser.discoveries().collect { secondSeen.countDown() } }
        try {
            lib.deliverUntil(firstSeen, secondSeen)
            assertEquals(1, lib.starts.get(), "overlapping discovery collectors must not start separate sessions")
            first.cancelAndJoin()
            assertEquals(0, lib.stops.get(), "the second discovery collector still owns the session")
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
        val restarted = CountDownLatch(1)
        val third = launch(Dispatchers.Default) { browser.departures().collect { restarted.countDown() } }
        try {
            assertTrue(restarted.await(5, TimeUnit.SECONDS), "a new collection must restart browsing")
        } finally {
            third.cancelAndJoin()
        }
        assertAll(
            { assertEquals(2, lib.starts.get(), "one start per collection group") },
            { assertEquals(2, lib.stops.get(), "one stop per collection group") },
        )
    }

    /**
     * `mc_browser_stop` does not wait for callbacks already in flight, so a found callback that was
     * blocked on the factory's discovery lock can run after teardown. Invoking the stopped session's
     * captured callback after `cancelAndJoin` is that interleaving, forced: the stale peer must reach
     * neither [MultipeerPeerLinkFactory.visiblePeers] nor the next session's discovery snapshot.
     */
    @Test
    fun foundCallbackFromAStoppedSessionNeverReachesTheNextSession() = runBlocking {
        val lib = BrowseLib()
        val browser = lib.browser()
        val ready = CountDownLatch(1)
        val first = launch(Dispatchers.Default) { browser.departures().collect { ready.countDown() } }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "first session must start")
        val staleFound = lib.foundCallback()
        first.cancelAndJoin()
        assertEquals(1, lib.stops.get(), "the first session must be torn down before its callback fires")

        staleFound.invoke("ghost", "Ghost")
        val visibleAfterStaleCallback = lib.factory.visiblePeers.value.map { it.handle }

        val seen = LinkedBlockingQueue<String>()
        val discovery = launch(Dispatchers.Default) { browser.discoveries().collect { seen.add(it.peerKey) } }
        try {
            val firstSeen = seen.poll(5, TimeUnit.SECONDS)
            assertAll(
                { assertEquals(emptyList(), visibleAfterStaleCallback, "a stopped session must not repopulate visible peers") },
                { assertEquals("arriving", firstSeen, "the next session's first discovery is its own peer, not the stopped session's") },
                { assertEquals(listOf("arriving"), lib.factory.visiblePeers.value.map { it.handle }) },
                { assertEquals(2, lib.starts.get(), "the discovery collector owns a newly started session") },
            )
        } finally {
            discovery.cancelAndJoin()
        }
    }

    /**
     * The departure half of the same interleaving: a lost callback from a stopped session must not
     * drop a peer the next session found, nor emit a departure for it. The live `marker` loss is
     * queued behind the stale one on the same feed, so the order of the departures proves absence
     * without a timeout.
     */
    @Test
    fun lostCallbackFromAStoppedSessionCannotDropAPeerTheNextSessionFound() = runBlocking {
        val lib = BrowseLib()
        val browser = lib.browser()
        val ready = CountDownLatch(1)
        val first = launch(Dispatchers.Default) { browser.departures().collect { ready.countDown() } }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "first session must start")
        val staleLost = lib.lostCallback()
        first.cancelAndJoin()

        val departed = LinkedBlockingQueue<String>()
        val second = launch(Dispatchers.Default) { browser.departures().collect { departed.add(it) } }
        try {
            assertEquals("leaving", departed.poll(5, TimeUnit.SECONDS), "the next session's registration departure marks it live")
            staleLost.invoke("arriving")
            val visibleAfterStaleLoss = lib.factory.visiblePeers.value.map { it.handle }
            lib.fireLost("marker")
            assertAll(
                { assertEquals(listOf("arriving"), visibleAfterStaleLoss, "a stopped session must not drop the next session's peer") },
                { assertEquals("marker", departed.poll(5, TimeUnit.SECONDS), "a stopped session's loss must not reach the departure feed") },
                { assertEquals(2, lib.starts.get()) },
            )
        } finally {
            second.cancelAndJoin()
        }
    }

    /** Callbacks fire inline during registration, before the native start call returns. */
    private class BrowseLib(
        private val arrivalForStart: (Int) -> String = { "arriving" },
        private val beforeStop: () -> Unit = {},
    ) : MultipeerNativeLib by FakeMultipeerNativeLib() {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val lostFirings = AtomicInteger()
        private val found = AtomicReference<MultipeerNativeLib.PeerFoundCallback?>()
        private val lost = AtomicReference<MultipeerNativeLib.PeerLostCallback?>()

        val factory = MultipeerPeerLinkFactory("test", "kuilt-test", injectedLib = this, injectedRuntimeHandle = Pointer(1))

        fun browser(): MultipeerServiceBrowser = MultipeerServiceBrowser(factory, libLoader = { this })

        override fun mc_browser_start(runtime: Pointer?, peerFoundCb: MultipeerNativeLib.PeerFoundCallback): Pointer {
            val arrival = arrivalForStart(starts.incrementAndGet())
            found.set(peerFoundCb)
            peerFoundCb.invoke(arrival, "Arrival")
            return Pointer(2)
        }

        override fun mc_browser_set_peer_lost_callback(browser: Pointer?, peerLostCb: MultipeerNativeLib.PeerLostCallback) {
            lost.set(peerLostCb)
            lostFirings.incrementAndGet()
            peerLostCb.invoke("leaving")
        }

        override fun mc_browser_stop(browser: Pointer?) {
            beforeStop()
            stops.incrementAndGet()
            found.set(null)
            lost.set(null)
        }

        fun fireFound(handle: String) = checkNotNull(found.get()).invoke(handle, handle)

        fun fireLost(handle: String) = checkNotNull(lost.get()).invoke(handle)

        /** The live session's found callback, retained so a test can fire it after that session stops. */
        fun foundCallback(): MultipeerNativeLib.PeerFoundCallback = checkNotNull(found.get())

        /** The live session's lost callback, retained so a test can fire it after that session stops. */
        fun lostCallback(): MultipeerNativeLib.PeerLostCallback = checkNotNull(lost.get())

        fun deliverUntil(vararg received: CountDownLatch) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (received.any { it.count != 0L } && System.nanoTime() < deadline) {
                found.get()?.invoke("arriving", "Arrival")
                lost.get()?.invoke("leaving")
                Thread.sleep(10)
            }
            assertTrue(received.all { it.count == 0L }, "each overlapping collector must receive its own feed")
        }
    }
}
