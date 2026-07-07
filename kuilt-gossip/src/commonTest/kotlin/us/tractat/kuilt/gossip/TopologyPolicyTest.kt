package us.tractat.kuilt.gossip

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopologyPolicyTest {
    private val self = PeerId("self")

    private fun roster(n: Int): Set<PeerId> = (1..n).map { PeerId("peer-$it") }.toSet() + self

    @Test
    fun fullFanoutSelectsEveryOtherPeer() {
        val roster = roster(4)
        assertAll(
            { assertEquals(roster - self, FullFanout.activeView(self, roster)) },
            { assertEquals(roster - self, FullFanout.antiEntropyPool(self, roster)) },
            { assertEquals(emptySet(), FullFanout.activeView(self, setOf(self)), "alone ⇒ empty view") },
            { assertEquals(emptySet(), FullFanout.activeView(self, emptySet()), "empty roster ⇒ empty view") },
        )
    }

    @Test
    fun randomKRegularSelectsKPeersExcludingSelf() {
        val roster = roster(20)
        val view = RandomKRegular(Random(9)).activeView(self, roster)
        assertAll(
            { assertEquals(recommendedActiveViewSize(roster.size), view.size, "view has exactly k peers") },
            { assertFalse(self in view, "view excludes self") },
            { assertTrue(view.all { it in roster }, "view comes from the roster") },
        )
    }

    @Test
    fun randomKRegularIsCappedByAvailablePeers() {
        val small = roster(2) // k = 4 > the 2 available peers
        assertEquals(small - self, RandomKRegular(Random(1)).activeView(self, small))
    }

    /**
     * Behaviour preservation for the #794 size→shape refactor: [RandomKRegular]
     * draws exactly the seeded k-out shuffle [GossipView] hardcoded before the
     * policy owned selection — same seed + roster ⇒ same neighbours.
     */
    @Test
    fun randomKRegularMatchesTheLegacySeededKOutSample() {
        val roster = roster(12)
        val expected =
            (roster - self)
                .shuffled(Random(7))
                .take(recommendedActiveViewSize(roster.size))
                .toSet()
        assertEquals(expected, RandomKRegular(Random(7)).activeView(self, roster))
    }

    @Test
    fun randomKRegularAntiEntropyPoolIsEveryOtherPeer() {
        val roster = roster(12)
        assertEquals(roster - self, RandomKRegular(Random(7)).antiEntropyPool(self, roster))
    }
}
