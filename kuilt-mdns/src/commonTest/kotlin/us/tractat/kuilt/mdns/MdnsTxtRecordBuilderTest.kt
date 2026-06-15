package us.tractat.kuilt.mdns

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val map = buildTxtMap(PeerId("my-peer"), "/peer", null, null, emptyMap())
        assertEquals("my-peer", map[MDNSAdvertisement.TXT_KEY_PEER_ID])
    }

    @Test
    fun `wsPath is always present`() {
        val map = buildTxtMap(selfId, "/custom", null, null, emptyMap())
        assertEquals("/custom", map[MDNSAdvertisement.TXT_KEY_WS_PATH])
    }

    @Test
    fun `protocol version is always present and is v2`() {
        val map = buildTxtMap(selfId, "/peer", null, null, emptyMap())
        assertEquals(MDNSAdvertisement.PROTOCOL_VERSION, map[MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION])
        assertEquals("2", map[MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION])
    }

    @Test
    fun `default wsPath produces DEFAULT_WS_PATH`() {
        val map = buildTxtMap(selfId, MDNSAdvertisement.DEFAULT_WS_PATH, null, null, emptyMap())
        assertEquals(MDNSAdvertisement.DEFAULT_WS_PATH, map[MDNSAdvertisement.TXT_KEY_WS_PATH])
    }

    @Test
    fun `hostOs is included when supplied`() {
        val map = buildTxtMap(selfId, "/peer", MDNSAdvertisement.HostOs.Android, null, emptyMap())
        assertEquals("android", map[MDNSAdvertisement.TXT_KEY_HOST_OS])
    }

    @Test
    fun `hostOs jvm value is correct`() {
        val map = buildTxtMap(selfId, "/peer", MDNSAdvertisement.HostOs.Jvm, null, emptyMap())
        assertEquals("jvm", map[MDNSAdvertisement.TXT_KEY_HOST_OS])
    }

    @Test
    fun `hostOs apple value is correct`() {
        val map = buildTxtMap(selfId, "/peer", MDNSAdvertisement.HostOs.Apple, null, emptyMap())
        assertEquals("apple", map[MDNSAdvertisement.TXT_KEY_HOST_OS])
    }

    @Test
    fun `hostOs is absent when null`() {
        val map = buildTxtMap(selfId, "/peer", null, null, emptyMap())
        assertFalse(MDNSAdvertisement.TXT_KEY_HOST_OS in map)
    }

    @Test
    fun `fabrics is included when supplied`() {
        val map = buildTxtMap(selfId, "/peer", null, "ws,nearby", emptyMap())
        assertEquals("ws,nearby", map[MDNSAdvertisement.TXT_KEY_FABRICS])
    }

    @Test
    fun `fabrics is absent when null`() {
        val map = buildTxtMap(selfId, "/peer", null, null, emptyMap())
        assertFalse(MDNSAdvertisement.TXT_KEY_FABRICS in map)
    }

    @Test
    fun `txtExtensions keys are merged into the map`() {
        val extensions = mapOf("appVersion" to "3", "sessionId" to "abc")
        val map = buildTxtMap(selfId, "/peer", null, null, extensions)
        assertEquals("3", map["appVersion"])
        assertEquals("abc", map["sessionId"])
    }

    @Test
    fun `minimal v1 map has exactly three keys`() {
        val map = buildTxtMap(selfId, MDNSAdvertisement.DEFAULT_WS_PATH, null, null, emptyMap())
        assertEquals(3, map.size)
    }

    @Test
    fun `full v2 map with hostOs and fabrics has five keys`() {
        val map =
            buildTxtMap(
                selfId = PeerId("peer-full"),
                wsPath = "/peer",
                hostOs = MDNSAdvertisement.HostOs.Android,
                fabrics = "ws",
                txtExtensions = emptyMap(),
            )
        // peerId, wsPath, protoVersion, hostOs, fabrics
        assertEquals(5, map.size)
    }

    @Test
    fun `txtExtensions increase total map size correctly`() {
        val map =
            buildTxtMap(
                selfId = PeerId("peer-ext"),
                wsPath = "/peer",
                hostOs = MDNSAdvertisement.HostOs.Android,
                fabrics = "ws",
                txtExtensions = mapOf("extra1" to "a", "extra2" to "b"),
            )
        // peerId, wsPath, protoVersion, hostOs, fabrics, extra1, extra2
        assertEquals(7, map.size)
    }

    @Test
    fun `no game domain keys appear in a default map`() {
        val map = buildTxtMap(selfId, "/peer", null, null, emptyMap())
        assertFalse("gameMinVersion" in map)
        assertFalse("gameMaxVersion" in map)
    }
}
