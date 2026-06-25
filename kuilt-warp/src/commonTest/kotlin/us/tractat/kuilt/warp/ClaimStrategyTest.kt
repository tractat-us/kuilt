package us.tractat.kuilt.warp

import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClaimStrategyTest {
    @Test
    fun defaultsAreSaneAndRingIsAnObject() = assertAll(
        { assertTrue(ClaimStrategy.Ring === ClaimStrategy.Ring, "Ring is a singleton object") },
        {
            val s = ClaimStrategy.RingWithIntent()
            assertTrue(s.settleWindow > 0.seconds, "settleWindow defaults positive")
            assertTrue(s.claimLease > s.settleWindow, "lease must exceed settle window")
        },
        { assertEquals(ClaimStrategy.RingWithIntent(), ClaimStrategy.RingWithIntent(), "data class equality") },
    )
}
