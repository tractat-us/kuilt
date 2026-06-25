package us.tractat.kuilt.warp

import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClaimStrategyTest {
    @Test
    fun defaultsAreSaneAndRingIsAnObject() = assertAll(
        { assertTrue(ClaimStrategy.Ring != ClaimStrategy.RingWithIntent(), "Ring and RingWithIntent are distinct strategies") },
        {
            val s = ClaimStrategy.RingWithIntent()
            assertTrue(s.settleWindow > 0.seconds, "settleWindow defaults positive")
        },
        { assertEquals(ClaimStrategy.RingWithIntent(), ClaimStrategy.RingWithIntent(), "data class equality") },
    )
}
