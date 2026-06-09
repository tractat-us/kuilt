package us.tractat.kuilt.deal

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardStateTest {

    @Test
    fun schemeKeyPairRoundTrips() {
        val bytes = byteArrayOf(1, 2, 3)
        val key = SchemeKeyPair(SchemeKey(bytes), SchemeKey(bytes))
        assertEquals(key.encryptKey, key.stripKey)
    }
}
