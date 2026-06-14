package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals

class HelloTest {
    @Test
    fun encodeDecodeRoundTrips() {
        val id = PeerId("node-42")
        assertEquals(id, Hello.decode(Hello.encode(id)))
    }
}
