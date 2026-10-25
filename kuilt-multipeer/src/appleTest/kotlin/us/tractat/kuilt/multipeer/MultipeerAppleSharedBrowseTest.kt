package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.MultipeerConnectivity.MCNearbyServiceBrowser
import platform.MultipeerConnectivity.MCPeerID
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
class MultipeerAppleSharedBrowseTest {
    @Test
    fun lateDiscoveriesReceiveCurrentPeersWithoutAnotherCallback() = runTest {
        val factory = MultipeerPeerLinkFactory("local", "kuilt-test")
        val source = MultipeerServiceBrowser(factory)
        val departure = backgroundScope.launch { source.departures().collect {} }
        try {
            runCurrent()
            val delegate = assertNotNull(factory.activeBrowserDelegate)
            val peer = MCPeerID(displayName = "present#12345678")
            val gone = MCPeerID(displayName = "gone#12345678")
            val browser = MCNearbyServiceBrowser(peer = peer, serviceType = "kuilt-test")
            delegate.browser(browser, foundPeer = peer, withDiscoveryInfo = null)
            delegate.browser(browser, foundPeer = gone, withDiscoveryInfo = null)
            delegate.browser(browser, lostPeer = gone)
            val seen = mutableListOf<String>()
            val discovery = backgroundScope.launch { source.discoveries().collect { seen += it.peerKey } }
            runCurrent()
            assertEquals(listOf(peer.displayName), seen, "late discovery collector must receive the current peer without a repeated callback")
            val later = MCPeerID(displayName = "later#12345678")
            delegate.browser(browser, foundPeer = later, withDiscoveryInfo = null)
            runCurrent()
            assertEquals(listOf(peer.displayName, later.displayName), seen, "live arrivals follow the initial snapshot")
            assertSame(delegate, factory.activeBrowserDelegate, "seeding must not restart browsing")
            discovery.cancelAndJoin()
        } finally {
            departure.cancelAndJoin()
            factory.close()
        }
    }

    @Test
    fun cancellingDiscoveriesKeepsDeparturesAlive() = overlap(cancelDiscoveriesFirst = true)

    @Test
    fun cancellingDeparturesKeepsDiscoveriesAlive() = overlap(cancelDiscoveriesFirst = false)

    private fun overlap(cancelDiscoveriesFirst: Boolean) = runTest {
        val factory = MultipeerPeerLinkFactory("local", "kuilt-test")
        val source = MultipeerServiceBrowser(factory)
        val arrivals = mutableListOf<String>()
        val departures = mutableListOf<String>()
        val departure = backgroundScope.launch { source.departures().collect { departures += it } }
        val discovery = backgroundScope.launch { source.discoveries().collect { arrivals += it.peerKey } }
        try {
            runCurrent()
            val delegate = assertNotNull(factory.activeBrowserDelegate)
            val peer = MCPeerID(displayName = "remote#12345678")
            val browser = MCNearbyServiceBrowser(peer = peer, serviceType = "kuilt-test")
            if (cancelDiscoveriesFirst) discovery.cancelAndJoin() else departure.cancelAndJoin()
            assertSame(delegate, factory.activeBrowserDelegate, "one remaining collector must retain the same browser")
            if (cancelDiscoveriesFirst) {
                delegate.browser(browser, lostPeer = peer)
                runCurrent()
                assertEquals(listOf(peer.displayName), departures, "remaining departure feed must still deliver")
            } else {
                delegate.browser(browser, foundPeer = peer, withDiscoveryInfo = null)
                runCurrent()
                assertEquals(listOf(peer.displayName), arrivals, "remaining discovery feed must still deliver")
            }
            discovery.cancelAndJoin()
            departure.cancelAndJoin()
            assertNull(factory.activeBrowserDelegate, "the last collector stops browsing")
            val restarted = backgroundScope.launch { source.departures().collect {} }
            runCurrent()
            assertNotNull(factory.activeBrowserDelegate, "departures alone must restart browsing")
            restarted.cancelAndJoin()
            assertNull(factory.activeBrowserDelegate)
        } finally {
            discovery.cancelAndJoin()
            departure.cancelAndJoin()
            factory.close()
        }
    }

    /**
     * `stopBrowsing` clears the browser's delegate but cannot recall a callback already dispatched to
     * it. Holding the first session's delegate and calling it after teardown is that interleaving,
     * forced: the stale peer must reach neither [MultipeerPeerLinkFactory.visiblePeers] nor the next
     * session's discovery snapshot.
     */
    @Test
    fun foundCallbackFromAStoppedSessionNeverReachesTheNextSession() = runTest {
        val factory = MultipeerPeerLinkFactory("local", "kuilt-test")
        val source = MultipeerServiceBrowser(factory)
        val first = backgroundScope.launch { source.departures().collect {} }
        val seen = mutableListOf<String>()
        try {
            runCurrent()
            val staleDelegate = assertNotNull(factory.activeBrowserDelegate)
            first.cancelAndJoin()
            assertNull(factory.activeBrowserDelegate, "the first session must be torn down before its callback fires")
            val ghost = MCPeerID(displayName = "ghost#12345678")
            val browser = MCNearbyServiceBrowser(peer = ghost, serviceType = "kuilt-test")
            staleDelegate.browser(browser, foundPeer = ghost, withDiscoveryInfo = null)
            val visibleAfterStaleCallback = factory.visiblePeers.value.map { it.handle }

            val discovery = backgroundScope.launch { source.discoveries().collect { seen += it.peerKey } }
            runCurrent()
            val delegate = assertNotNull(factory.activeBrowserDelegate)
            val arriving = MCPeerID(displayName = "arriving#12345678")
            delegate.browser(browser, foundPeer = arriving, withDiscoveryInfo = null)
            runCurrent()
            assertAll(
                { assertEquals(emptyList(), visibleAfterStaleCallback, "a stopped session must not repopulate visible peers") },
                { assertEquals(listOf(arriving.displayName), seen, "the next session sees its own peer, never the stopped session's") },
                { assertEquals(listOf(arriving.displayName), factory.visiblePeers.value.map { it.handle }) },
            )
            discovery.cancelAndJoin()
        } finally {
            first.cancelAndJoin()
            factory.close()
        }
    }

    /**
     * The departure half of the same interleaving: a stopped session's `lostPeer` must not drop a
     * peer the next session found, nor emit a departure for it. The live `marker` loss follows the
     * stale one on the same feed, so the recorded departures prove absence without a timeout.
     */
    @Test
    fun lostCallbackFromAStoppedSessionCannotDropAPeerTheNextSessionFound() = runTest {
        val factory = MultipeerPeerLinkFactory("local", "kuilt-test")
        val source = MultipeerServiceBrowser(factory)
        val first = backgroundScope.launch { source.departures().collect {} }
        val departed = mutableListOf<String>()
        try {
            runCurrent()
            val staleDelegate = assertNotNull(factory.activeBrowserDelegate)
            first.cancelAndJoin()

            val second = backgroundScope.launch { source.departures().collect { departed += it } }
            runCurrent()
            val delegate = assertNotNull(factory.activeBrowserDelegate)
            val arriving = MCPeerID(displayName = "arriving#12345678")
            val marker = MCPeerID(displayName = "marker#12345678")
            val browser = MCNearbyServiceBrowser(peer = arriving, serviceType = "kuilt-test")
            delegate.browser(browser, foundPeer = arriving, withDiscoveryInfo = null)
            staleDelegate.browser(browser, lostPeer = arriving)
            val visibleAfterStaleLoss = factory.visiblePeers.value.map { it.handle }
            delegate.browser(browser, lostPeer = marker)
            runCurrent()
            assertAll(
                { assertEquals(listOf(arriving.displayName), visibleAfterStaleLoss, "a stopped session must not drop the next session's peer") },
                { assertEquals(listOf(marker.displayName), departed, "a stopped session's loss must not reach the departure feed") },
            )
            second.cancelAndJoin()
        } finally {
            first.cancelAndJoin()
            factory.close()
        }
    }

    @Test
    fun overlappingDiscoveryCollectorsRetainTheSameBrowser() = runTest {
        val factory = MultipeerPeerLinkFactory("local", "kuilt-test")
        val source = MultipeerServiceBrowser(factory)
        val first = backgroundScope.launch { source.discoveries().collect {} }
        try {
            runCurrent()
            val delegate = assertNotNull(factory.activeBrowserDelegate)
            val second = backgroundScope.launch { source.discoveries().collect {} }
            runCurrent()
            assertSame(delegate, factory.activeBrowserDelegate)
            first.cancelAndJoin()
            assertSame(delegate, factory.activeBrowserDelegate)
            second.cancelAndJoin()
            assertNull(factory.activeBrowserDelegate)
        } finally {
            first.cancelAndJoin()
            factory.close()
        }
    }
}
