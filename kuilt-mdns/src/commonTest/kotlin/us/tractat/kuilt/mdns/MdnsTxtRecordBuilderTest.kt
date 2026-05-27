package us.tractat.kuilt.mdns

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for [buildTxtMap] — the shared TXT record builder used by both
 * the JVM JmDNS advertiser and the Android NsdManager advertiser.
 *
 * Platform-level integration (JmDNS.registerService / NsdManager.registerService)
 * is tested separately in jvmTest and (gated) Android instrumented tests.
 */
class MdnsTxtRecordBuilderTest {
    private val selfId = PeerId("peer-1")

    @Test
    fun `peerId is always present`() {
        val map = buildTxtMap(PeerId("my-peer"), "/peer", null, null, null, null)
        assertEquals("my-peer", map[MDNSAdvertisement.TXT_KEY_PEER_ID])
    }

    @Test
    fun `wsPath is always present`() {
        val map = buildTxtMap(selfId, "/custom", null, null, null, null)
        assertEquals("/custom", map[MDNSAdvertisement.TXT_KEY_WS_PATH])
    }

    @Test
    fun `protocol version is always present and is v2`() {
        val map = buildTxtMap(selfId, "/peer", null, null, null, null)
        assertEquals(MDNSAdvertisement.PROTOCOL_VERSION, map[MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION])
        assertEquals("2", map[MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION])
    }

    @Test
    fun `default wsPath produces DEFAULT_WS_PATH`() {
        val map = buildTxtMap(selfId, MDNSAdvertisement.DEFAULT_WS_PATH, null, null, null, null)
        assertEquals(MDNSAdvertisement.DEFAULT_WS_PATH, map[MDNSAdvertisement.TXT_KEY_WS_PATH])
    }

    @Test
    fun `hostOs is included when supplied`() {
        val map = buildTxtMap(selfId, "/peer", MDNSAdvertisement.HostOs.Android, null, null, null)
        assertEquals("android", map[MDNSAdvertisement.TXT_KEY_HOST_OS])
    }

    @Test
    fun `hostOs jvm value is correct`() {
        val map = buildTxtMap(selfId, "/peer", MDNSAdvertisement.HostOs.Jvm, null, null, null)
        assertEquals("jvm", map[MDNSAdvertisement.TXT_KEY_HOST_OS])
    }

    @Test
    fun `hostOs apple value is correct`() {
        val map = buildTxtMap(selfId, "/peer", MDNSAdvertisement.HostOs.Apple, null, null, null)
        assertEquals("apple", map[MDNSAdvertisement.TXT_KEY_HOST_OS])
    }

    @Test
    fun `hostOs is absent when null`() {
        val map = buildTxtMap(selfId, "/peer", null, null, null, null)
        assertFalse(MDNSAdvertisement.TXT_KEY_HOST_OS in map)
    }

    @Test
    fun `fabrics is included when supplied`() {
        val map = buildTxtMap(selfId, "/peer", null, "ws,nearby", null, null)
        assertEquals("ws,nearby", map[MDNSAdvertisement.TXT_KEY_FABRICS])
    }

    @Test
    fun `fabrics is absent when null`() {
        val map = buildTxtMap(selfId, "/peer", null, null, null, null)
        assertFalse(MDNSAdvertisement.TXT_KEY_FABRICS in map)
    }

    @Test
    fun `gameMinVersion is included when supplied`() {
        val map = buildTxtMap(selfId, "/peer", null, null, 1, null)
        assertEquals("1", map[MDNSAdvertisement.TXT_KEY_GAME_MIN_VERSION])
    }

    @Test
    fun `gameMaxVersion is included when supplied`() {
        val map = buildTxtMap(selfId, "/peer", null, null, null, 5)
        assertEquals("5", map[MDNSAdvertisement.TXT_KEY_GAME_MAX_VERSION])
    }

    @Test
    fun `game version range included together`() {
        val map = buildTxtMap(selfId, "/peer", null, null, 2, 4)
        assertEquals("2", map[MDNSAdvertisement.TXT_KEY_GAME_MIN_VERSION])
        assertEquals("4", map[MDNSAdvertisement.TXT_KEY_GAME_MAX_VERSION])
    }

    @Test
    fun `game version keys absent when null`() {
        val map = buildTxtMap(selfId, "/peer", null, null, null, null)
        assertFalse(MDNSAdvertisement.TXT_KEY_GAME_MIN_VERSION in map)
        assertFalse(MDNSAdvertisement.TXT_KEY_GAME_MAX_VERSION in map)
    }

    @Test
    fun `minimal v1 map has exactly three keys`() {
        val map = buildTxtMap(selfId, MDNSAdvertisement.DEFAULT_WS_PATH, null, null, null, null)
        assertEquals(3, map.size)
    }

    @Test
    fun `full v2 map has seven keys`() {
        val map =
            buildTxtMap(
                selfId = PeerId("peer-full"),
                wsPath = "/peer",
                hostOs = MDNSAdvertisement.HostOs.Android,
                fabrics = "ws",
                gameMinVersion = 1,
                gameMaxVersion = 3,
            )
        // peerId, wsPath, protoVersion, hostOs, fabrics, gameMinVersion, gameMaxVersion
        assertEquals(7, map.size)
    }
}
