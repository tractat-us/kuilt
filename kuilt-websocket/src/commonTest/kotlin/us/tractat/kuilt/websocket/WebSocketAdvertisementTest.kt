package us.tractat.kuilt.websocket

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies [WebSocketAdvertisement] carries the optional [Tag.roomKey] — permissive
 * (`null`) by default, and preserved when a consumer sets it on a flat fabric.
 */
class WebSocketAdvertisementTest {
    @Test
    fun `roomKey defaults to null`() {
        val ad =
            WebSocketAdvertisement(
                url = "ws://10.0.0.1:8080/peer",
                serverPeerId = PeerId("p1"),
                displayName = "host",
            )
        assertNull(ad.roomKey)
    }

    @Test
    fun `roomKey is carried when supplied`() {
        val ad: Tag =
            WebSocketAdvertisement(
                url = "ws://10.0.0.1:8080/peer",
                serverPeerId = PeerId("p1"),
                displayName = "host",
                roomKey = "room-7",
            )
        assertEquals("room-7", ad.roomKey)
    }
}
