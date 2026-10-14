package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.MultipeerConnectivity.MCNearbyServiceBrowser
import platform.MultipeerConnectivity.MCPeerID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
class MultipeerAppleSharedBrowseTest {
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
