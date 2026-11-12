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
