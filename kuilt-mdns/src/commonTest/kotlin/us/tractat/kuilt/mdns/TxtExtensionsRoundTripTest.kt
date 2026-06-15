package us.tractat.kuilt.mdns

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that arbitrary [MDNSAdvertisement.txtExtensions] keys round-trip
 * through [buildTxtMap] — i.e. consumer-supplied extension keys are written
 * into the TXT record map and survive the advertise→discover path at the
 * data-model layer.
 *
 * Platform-level integration (JmDNS.registerService / NsdManager) is covered
 * in the jvmTest [MDNSServiceAdvertiserTest].
 */
class TxtExtensionsRoundTripTest {
    private val selfId = PeerId("peer-ext")

    @Test
    fun `txtExtensions keys are written into the TXT map`() {
        val extensions = mapOf("appVersion" to "42", "roomId" to "room-abc")
        val map = buildTxtMap(selfId, "/peer", null, null, extensions)

        assertEquals("42", map["appVersion"])
        assertEquals("room-abc", map["roomId"])
    }

    @Test
    fun `empty txtExtensions produces only the three mandatory keys`() {
        val map = buildTxtMap(selfId, "/peer", null, null, emptyMap())

        assertEquals(3, map.size)
        assertTrue(MDNSAdvertisement.TXT_KEY_PEER_ID in map)
        assertTrue(MDNSAdvertisement.TXT_KEY_WS_PATH in map)
        assertTrue(MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION in map)
    }

    @Test
    fun `txtExtensions do not clobber mandatory keys when they share no name`() {
        val extensions = mapOf("custom" to "value")
        val map = buildTxtMap(selfId, "/peer", null, null, extensions)

        assertEquals(selfId.value, map[MDNSAdvertisement.TXT_KEY_PEER_ID])
        assertEquals("/peer", map[MDNSAdvertisement.TXT_KEY_WS_PATH])
        assertEquals(MDNSAdvertisement.PROTOCOL_VERSION, map[MDNSAdvertisement.TXT_KEY_PROTOCOL_VERSION])
    }

    @Test
    fun `txtExtensions combined with optional v2 fields produces correct total size`() {
        val extensions = mapOf("appVersion" to "1")
        val map = buildTxtMap(
            selfId,
            "/peer",
            MDNSAdvertisement.HostOs.Jvm,
            "ws,mc",
            extensions,
        )
        // peerId + wsPath + protoVersion + hostOs + fabrics + appVersion
        assertEquals(6, map.size)
    }

    @Test
    fun `MDNSAdvertisement txtExtensions field defaults to empty map`() {
        val ad = MDNSAdvertisement(
            host = "10.0.0.1",
            port = 9000,
            serverPeerId = PeerId("p"),
            displayName = "test",
        )
        assertTrue(ad.txtExtensions.isEmpty())
    }

    @Test
    fun `MDNSAdvertisement txtExtensions field is preserved when supplied`() {
        val extensions = mapOf("sessionId" to "s1", "round" to "3")
        val ad = MDNSAdvertisement(
            host = "10.0.0.1",
            port = 9000,
            serverPeerId = PeerId("p"),
            displayName = "test",
            txtExtensions = extensions,
        )
        assertEquals(extensions, ad.txtExtensions)
    }

    @Test
    fun `no game domain keys exist in MDNSAdvertisement companion`() {
        // Regression test: game-domain TXT keys must not exist in the public API.
        // If the constants are removed, this test becomes a compile-time check
        // (the field references won't compile). Verify at the companion-object level
        // by checking the TXT map produced without extensions contains no game keys.
        val map = buildTxtMap(selfId, "/peer", null, null, emptyMap())
        assertFalse("gameMinVersion" in map)
        assertFalse("gameMaxVersion" in map)
    }
}
