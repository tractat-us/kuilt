package us.tractat.kuilt.gossip

import kotlin.test.Test
import kotlin.test.assertEquals

class ActiveViewPolicyTest {

    @Test
    fun fullFanoutTargetsEveryOtherPeer() {
        assertEquals(4, ActiveViewPolicy.FullFanout.activeViewSize(5))
        assertEquals(1, ActiveViewPolicy.FullFanout.activeViewSize(2))
        assertEquals(0, ActiveViewPolicy.FullFanout.activeViewSize(1))
        assertEquals(0, ActiveViewPolicy.FullFanout.activeViewSize(0))
    }

    @Test
    fun randomKRegularMatchesRecommendedSize() {
        assertEquals(recommendedActiveViewSize(20), ActiveViewPolicy.RandomKRegular.activeViewSize(20))
        assertEquals(recommendedActiveViewSize(3), ActiveViewPolicy.RandomKRegular.activeViewSize(3))
    }
}
