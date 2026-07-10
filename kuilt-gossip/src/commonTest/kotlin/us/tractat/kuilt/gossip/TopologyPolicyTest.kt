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

    // --- TwoTier: the federated two-tier shape ---

    private val s1 = PeerId("server-1")
    private val s2 = PeerId("server-2")
    private val s3 = PeerId("server-3")
    private val core = setOf(s1, s2, s3)

    private val c1 = PeerId("client-1") // -> s1
    private val c2 = PeerId("client-2") // -> s1
    private val c3 = PeerId("client-3") // -> s2
    private val c4 = PeerId("client-4") // -> s3
    private val orphan = PeerId("client-orphan") // unattached: attachment -> null

    private val attachment: Map<PeerId, PeerId> =
        mapOf(c1 to s1, c2 to s1, c3 to s2, c4 to s3)

    private val everyone: Set<PeerId> = core + setOf(c1, c2, c3, c4)

    private fun twoTier(): TwoTier = TwoTier(core) { attachment[it] }

    @Test
    fun serverFloodsOtherCoreAndItsOwnLocalClientsOnly() {
        // s1 relays to the other servers plus s1's clients (c1, c2) — never c3/c4.
        assertEquals(setOf(s2, s3, c1, c2), twoTier().activeView(s1, everyone))
    }

    @Test
    fun serverViewIntersectsTheLiveRoster() {
        // s3 and c2 are off-roster (crashed/departed): they drop out of s1's view.
        val roster = setOf(s1, s2, c1, c3, c4)
        assertEquals(setOf(s2, c1), twoTier().activeView(s1, roster))
    }

    @Test
    fun clientFloodsExactlyItsAttachmentServer() {
        assertAll(
            { assertEquals(setOf(s1), twoTier().activeView(c1, everyone)) },
            { assertEquals(setOf(s2), twoTier().activeView(c3, everyone)) },
            { assertEquals(setOf(s3), twoTier().activeView(c4, everyone)) },
        )
    }

    @Test
    fun clientViewEmptyWhenServerAbsentOrUnattached() {
        assertAll(
            // c1's server s1 is off-roster (the failover seam — can't flood until it reattaches).
            { assertEquals(emptySet(), twoTier().activeView(c1, everyone - s1)) },
            // orphan has no attachment (attachment(self) == null).
            { assertEquals(emptySet(), twoTier().activeView(orphan, everyone + orphan)) },
        )
    }

    @Test
    fun activeViewNeverContainsSelf() {
        assertAll(
            { assertFalse(s1 in twoTier().activeView(s1, everyone), "server excludes self") },
            { assertFalse(c1 in twoTier().activeView(c1, everyone), "client excludes self") },
        )
    }

    @Test
    fun antiEntropyPoolIsTierLocal() {
        assertAll(
            // Server samples the core (minus self), intersected with the live roster.
            { assertEquals(setOf(s2, s3), twoTier().antiEntropyPool(s1, everyone)) },
            // Client samples only its attachment server.
            { assertEquals(setOf(s1), twoTier().antiEntropyPool(c1, everyone)) },
            { assertEquals(setOf(s2), twoTier().antiEntropyPool(c3, everyone)) },
        )
    }

    @Test
    fun antiEntropyPoolNeverContainsSelfAndStaysOnRoster() {
        assertAll(
            { assertFalse(s1 in twoTier().antiEntropyPool(s1, everyone), "server pool excludes self") },
            { assertFalse(c1 in twoTier().antiEntropyPool(c1, everyone), "client pool excludes self") },
            // s2 off-roster ⇒ s1's core pool drops it.
            { assertEquals(setOf(s3), twoTier().antiEntropyPool(s1, everyone - s2)) },
            // client's server off-roster ⇒ empty pool.
            { assertEquals(emptySet(), twoTier().antiEntropyPool(c1, everyone - s1)) },
            // unattached client ⇒ empty pool.
            { assertEquals(emptySet(), twoTier().antiEntropyPool(orphan, everyone + orphan)) },
        )
    }

    @Test
    fun everyActiveViewUnionCoversTheWholeGraph() {
        // Dissemination reaches everyone: the union of every node's active view is the
        // whole roster — each server floods the other servers + its own locals, each
        // client floods its server, so every peer is a target of someone's flood.
        val policy = twoTier()
        val union = everyone.flatMap { policy.activeView(it, everyone) }.toSet()
        assertEquals(everyone, union, "dissemination covers the whole federated graph")
    }
}
