package us.tractat.kuilt.mdns

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MDNSAdvertisementTest {
    @Test
    fun `wsUrl is composed from host port and path`() {
        val ad =
            MDNSAdvertisement(
                host = "192.168.1.42",
                port = 9000,
                serverPeerId = PeerId("peer-1"),
                displayName = "Alice",
                wsPath = "/peer",
            )
        assertEquals("ws://192.168.1.42:9000/peer", ad.wsUrl)
    }

    @Test
    fun `default wsPath is DEFAULT_WS_PATH`() {
        val ad =
            MDNSAdvertisement(
                host = "localhost",
                port = 8080,
                serverPeerId = PeerId("peer-2"),
                displayName = "Bob",
            )
        assertEquals(MDNSAdvertisement.DEFAULT_WS_PATH, ad.wsPath)
    }

    @Test
    fun `displayName is preserved`() {
        val ad =
            MDNSAdvertisement(
                host = "10.0.0.1",
                port = 7777,
                serverPeerId = PeerId("my-peer"),
                displayName = "My Game",
            )
        assertEquals("My Game", ad.displayName)
    }

    @Test
    fun `serverPeerId is preserved`() {
        val ad =
            MDNSAdvertisement(
                host = "10.0.0.1",
                port = 7777,
                serverPeerId = PeerId("my-peer"),
                displayName = "My Game",
            )
        assertEquals(PeerId("my-peer"), ad.serverPeerId)
    }

    @Test
    fun `satisfies Tag`() {
        val ad =
            MDNSAdvertisement(
                host = "localhost",
                port = 1234,
                serverPeerId = PeerId("x"),
                displayName = "test",
            )
        assertIs<us.tractat.kuilt.core.Tag>(ad)
    }

    @Test
    fun `TXT key for peerId is stable`() {
        assertEquals("peerId", MDNSAdvertisement.TXT_KEY_PEER_ID)
    }

    @Test
    fun `TXT key for wsPath is stable`() {
        assertEquals("wsPath", MDNSAdvertisement.TXT_KEY_WS_PATH)
    }

    @Test
    fun `TXT key for protocol version is stable`() {
        assertEquals("protoVersion", MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION)
    }

    @Test
    fun `PROTOCOL_VERSION is 2 for v2 schema`() {
        assertEquals("2", MDNSAdvertisement.PROTOCOL_VERSION)
    }

    // ── v2 schema ─────────────────────────────────────────────────────────────

    @Test
    fun `TXT key constants for v2 fields are stable`() {
        assertEquals("hostOs", MDNSAdvertisement.TXT_KEY_HOST_OS)
        assertEquals("fabrics", MDNSAdvertisement.TXT_KEY_FABRICS)
        assertEquals("mcPeer", MDNSAdvertisement.TXT_KEY_MC_PEER)
        assertEquals("gameMinVersion", MDNSAdvertisement.TXT_KEY_GAME_MIN_VERSION)
        assertEquals("gameMaxVersion", MDNSAdvertisement.TXT_KEY_GAME_MAX_VERSION)
    }

    @Test
    fun `fabric label constants are stable`() {
        assertEquals("ws", MDNSAdvertisement.FABRIC_WS)
        assertEquals("mc", MDNSAdvertisement.FABRIC_MC)
        assertEquals("nearby", MDNSAdvertisement.FABRIC_NEARBY)
    }

    @Test
    fun `v2 fields default to null when omitted`() {
        val ad =
            MDNSAdvertisement(
                host = "10.0.0.1",
                port = 9000,
                serverPeerId = PeerId("p"),
                displayName = "test",
            )
        assertNull(ad.hostOs)
        assertNull(ad.fabrics)
        assertNull(ad.mcPeer)
        assertNull(ad.gameMinVersion)
        assertNull(ad.gameMaxVersion)
    }

    @Test
    fun `v2 fields are preserved when supplied`() {
        val ad =
            MDNSAdvertisement(
                host = "10.0.0.1",
                port = 9000,
                serverPeerId = PeerId("p"),
                displayName = "test",
                hostOs = MDNSAdvertisement.HostOs.Jvm,
                fabrics = "ws,mc",
                mcPeer = "MCPeer-abc123",
                gameMinVersion = 1,
                gameMaxVersion = 3,
            )
        assertEquals(MDNSAdvertisement.HostOs.Jvm, ad.hostOs)
        assertEquals("ws,mc", ad.fabrics)
        assertEquals("MCPeer-abc123", ad.mcPeer)
        assertEquals(1, ad.gameMinVersion)
        assertEquals(3, ad.gameMaxVersion)
    }

    // ── HostOs enum ───────────────────────────────────────────────────────────

    @Test
    fun `HostOs txt values are stable`() {
        assertEquals("jvm", MDNSAdvertisement.HostOs.Jvm.txtValue)
        assertEquals("android", MDNSAdvertisement.HostOs.Android.txtValue)
        assertEquals("apple", MDNSAdvertisement.HostOs.Apple.txtValue)
    }

    @Test
    fun `HostOs fromTxt returns correct entry for each value`() {
        assertEquals(MDNSAdvertisement.HostOs.Jvm, MDNSAdvertisement.HostOs.fromTxt("jvm"))
        assertEquals(MDNSAdvertisement.HostOs.Android, MDNSAdvertisement.HostOs.fromTxt("android"))
        assertEquals(MDNSAdvertisement.HostOs.Apple, MDNSAdvertisement.HostOs.fromTxt("apple"))
    }

    @Test
    fun `HostOs fromTxt returns null for unknown value`() {
        assertNull(MDNSAdvertisement.HostOs.fromTxt("windows"))
        assertNull(MDNSAdvertisement.HostOs.fromTxt(""))
    }
}
