package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FairShareAllocator.pickChild], one pick with separate waiting and eligible views, and the
 * allocator's memory as a value ([AllocatorMemory]) (#3173, plan Task L1).
 */
class FairShareAllocatorPickChildTest {

    private val root = GroupId("root")
    private val policy = PolicyConfig(quantum = Long.MAX_VALUE)
    private val wants = Demand(targetOutstanding = 1L shl 40, maximumUsefulGrant = 2L)

    private fun demandOf(edges: List<AttachmentId>): (AttachmentId) -> Demand = { if (it in edges) wants else Demand.NONE }

    private fun flat(n: Int): Pair<EntitlementLedger, List<AttachmentId>> {
        val records = (0 until n).map { edgeOf(root, GroupId("g$it")) }
        val l = shapedLedger(root, records)
        return l.piece(l.mint(MintId("m"), root, LOCAL, 1_000L).delta) to records.map { it.id }
    }

    @Test
    fun picksAmongEligibleButObservesWaiting() {
        val (ledger, edges) = flat(2)
        val allocator = FairShareAllocator(LOCAL, policy)
        val pick = assertNotNull(allocator.pickChild(root, ledger, demandOf(edges), demandOf(listOf(edges[1]))))
        val memory = allocator.memory.demandingObserved
        assertAll(
            { assertEquals(Grant(edges[1], 2L), pick.grant, "only the eligible child can win, at maximumUsefulGrant") },
            { assertEquals(mapOf(edges[0] to true, edges[1] to true), memory, "both were observed waiting") },
            { assertTrue(edges.all { pick.ledger.gauge(it) != null }, "both were seated before the pick") },
            { assertEquals(0L, pick.ledger.baseIssuance(edges[1]), "nothing was delegated") },
            { assertTrue(edges.all { ledger.gauge(it) == null }, "the input ledger is a value, untouched") },
        )
    }

    @Test
    fun nullWhenNothingIsEligibleButTheObservationStands() {
        val (ledger, edges) = flat(2)
        val allocator = FairShareAllocator(LOCAL, policy)
        val pick = allocator.pickChild(root, ledger, demandOf(listOf(edges[0])), demandOf(emptyList()))
        assertAll(
            { assertNull(pick) },
            { assertEquals(mapOf(edges[0] to true, edges[1] to false), allocator.memory.demandingObserved) },
        )
    }

    @Test
    fun aLargeNewbornCohortIsSeatedInOnePick() {
        val (ledger, edges) = flat(2_000)
        val pick = assertNotNull(FairShareAllocator(LOCAL, policy).pickChild(root, ledger, { wants }, { wants }))
        assertEquals(2_000, edges.count { pick.ledger.gauge(it) != null }, "every newborn carries a gauge")
    }

    /**
     * A seat patch is a gauge-only ledger built outside `piece`, so the index a pick carries into
     * its result must still agree with one rebuilt from scratch: the generation floor (#3196) and
     * the inbound lists the lineage lift reads. The edges are lowered from a real topology, so the
     * generation top is not the empty one.
     */
    @Test
    fun aSeatedLedgerCarriesTheIndexItWouldRebuild() {
        val topology = draft("pool") { (0 until 40).forEach { lane("c-$it") } }
        val records = topology.lower(generation = 5L)
        val poolRoot = topology.groupId()
        var start = shapedLedger(poolRoot, records)
        start = start.piece(start.mint(MintId("m"), poolRoot, LOCAL, 1_000L).delta)
        start.generationTop() // build the base's index, so the result carries one rather than building its own
        val pick = assertNotNull(FairShareAllocator(LOCAL, policy).pickChild(poolRoot, start, { wants }, { wants }))
        val delegated = pick.ledger.piece(assertNotNull(pick.ledger.delegate(LOCAL, pick.grant.attachment, 2L)).delta)
        val rebuilt = Json.decodeFromString(EntitlementLedger.serializer(), Json.encodeToString(EntitlementLedger.serializer(), delegated))
        val leaves = records.map { it.child }
        assertAll(
            { assertEquals(40, records.count { delegated.gauge(it.id) != null }, "the rig: every newborn was seated by the gauge-only patch") },
            { assertEquals(GenerationTop(5L, 40), rebuilt.generationTop(), "the rig: a real generation top") },
            { assertEquals(rebuilt.generationTop(), delegated.generationTop(), "the carried generation top") },
            { assertEquals(rebuilt.withLiveLineages(leaves), delegated.withLiveLineages(leaves), "the carried inbound lists") },
            { assertEquals(leaves.map { rebuilt.inboundEdges(it) }, leaves.map { delegated.inboundEdges(it) }) },
        )
    }

    @Test
    fun anAllocatorResumedFromItsMemoryPicksTheSame() {
        val (start, edges) = flat(3)
        val original = FairShareAllocator(LOCAL, policy)
        var ledger = start
        // Edge 2 runs alone for a while, then the others wake: a history with wake offsets in it.
        repeat(5) {
            val pick = assertNotNull(original.pickChild(root, ledger, demandOf(listOf(edges[2])), demandOf(listOf(edges[2]))))
            ledger = pick.ledger.piece(assertNotNull(pick.ledger.delegate(LOCAL, pick.grant.attachment, pick.grant.amount)).delta)
        }
        val all = demandOf(edges)
        val first = assertNotNull(original.pickChild(root, ledger, all, all))
        val resumed = FairShareAllocator(LOCAL, policy, original.memory)
        val next = first.ledger.piece(assertNotNull(first.ledger.delegate(LOCAL, first.grant.attachment, 2L)).delta)
        assertAll(
            { assertTrue(original.memory.wakeOffsets.isNotEmpty(), "the rig: the wakers were clamped") },
            { assertEquals(original.memory, resumed.memory) },
            { assertEquals(original.pickChild(root, next, all, all), resumed.pickChild(root, next, all, all), "the same next pick") },
            { assertEquals(original.memory, resumed.memory, "and the same memory after it") },
        )
    }

    @Test
    fun memoryEncodesCanonicallyAndRoundTrips() {
        val e1 = AttachmentId("a/b")
        val e2 = AttachmentId("#3")
        val one = AllocatorMemory(
            wakeOffsets = linkedMapOf(e1 to Rational.of(3L, 2L), e2 to Rational.ONE),
            demandingObserved = linkedMapOf(e1 to true, e2 to false),
        )
        val other = AllocatorMemory(
            wakeOffsets = linkedMapOf(e2 to Rational.ONE, e1 to Rational.of(3L, 2L)),
            demandingObserved = linkedMapOf(e2 to false, e1 to true),
        )
        val bytes = Json.encodeToString(AllocatorMemory.serializer(), one)
        assertAll(
            { assertEquals(one, other) },
            { assertEquals(bytes, Json.encodeToString(AllocatorMemory.serializer(), other), "equal memories, equal bytes") },
            { assertEquals(one, Json.decodeFromString(AllocatorMemory.serializer(), bytes)) },
            { assertSame(AllocatorMemory.EMPTY, AllocatorMemory.EMPTY) },
            { assertEquals(AllocatorMemory.EMPTY, FairShareAllocator(LOCAL, policy).memory, "a new allocator remembers nothing") },
        )
    }
}
