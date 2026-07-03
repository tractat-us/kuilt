package us.tractat.kuilt.multipeer

import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies [MultipeerAdvertisement] carries the optional [Tag.roomKey] — permissive
 * (`null`) by default, and preserved when supplied.
 */
class MultipeerAdvertisementTest {
    @Test
    fun `roomKey defaults to null`() {
        val ad =
            MultipeerAdvertisement(
                handle = "peer-a",
                sessionName = "peer-a",
                serviceType = "kuilt-mc",
            )
        assertNull(ad.roomKey)
    }

    @Test
    fun `roomKey is carried when supplied`() {
        val ad: Tag =
            MultipeerAdvertisement(
                handle = "peer-a",
                sessionName = "peer-a",
                serviceType = "kuilt-mc",
                roomKey = "room-3",
            )
        assertEquals("room-3", ad.roomKey)
    }
}
