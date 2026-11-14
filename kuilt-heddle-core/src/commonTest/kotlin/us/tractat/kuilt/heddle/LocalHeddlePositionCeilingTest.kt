package us.tractat.kuilt.heddle

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the engine does at the position ceiling (#3313's ruling): a call that **chooses** refuses
 * with a typed outcome and changes nothing, wake memory included; a call that **charges** lands, and
 * the position saturates at [positionCeiling] instead of throwing.
 *
 * The ceiling is reached by raising one lane's gauge floor to just under it ([pushTowardCeiling]),
 * since a run that gets there by picks takes thousands of maximum-size leases. Every arm first
 * asserts that its lane is where the rig put it, so a refusal is the ceiling's doing and not a
 * fixture that never got there.
 */
class LocalHeddlePositionCeilingTest {

    private val topology = draft("ceiling") {
        lane("a", Weight.of(3, 7))
        lane("b", Weight.of(2, 5))
        group("g", Weight.of(2)) {
            lane("x", Weight.ONE)
            lane("y", Weight.of(5, 3))
        }
    }
    private val a = topology.groupId("a")
    private val b = topology.groupId("b")
    private val g = topology.groupId("g")
    private val x = topology.groupId("g", "x")
    private val y = topology.groupId("g", "y")

    private val root = engine().root

    private fun engine() = LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        .apply { reconcileCapacity(1L) }

    /** A history in which lanes idle and wake, so the wake memory is not empty. */
    private fun LocalHeddle.warmUp() {
        val sets = listOf(setOf(a, b), setOf(a), setOf(a, x, y), setOf(b, x), setOf(a, b, y))
        repeat(20) { i ->
            val waiting = sets[i % sets.size]
            val lease = (acquire(waiting, waiting, startedAt = i.toLong()) as AcquireOutcome.Acquired).lease
            complete(lease.id, actualCost = 1L + i % 4)
        }
    }

    private fun LocalHeddle.edgeOf(lane: GroupId): AttachmentId =
        checkNotNull(snapshot().ledger.lineageOf(lane)?.last()) { "${lane.value} has no lineage" }

    /** [lane]'s gross position in ticks of its own service, as the ledger reads it. */
    private fun LocalHeddle.grossTicks(lane: GroupId): Long {
        val ledger = snapshot().ledger
        val edge = edgeOf(lane)
        val weight = checkNotNull(ledger.record(edge)).weight
        return ledger.gauge(edge).grossTicksAt(ledger.baseIssuance(edge), weight)
    }

    private fun LocalHeddle.ceilingOf(lane: GroupId): Long =
        positionCeiling(checkNotNull(snapshot().ledger.record(edgeOf(lane))).weight)

    /**
     * Raise [lane]'s gauge floor so its gross position reads [below] ticks under its ceiling. A
     * floor only goes up under the gauge's join, so this is a write any peer could have made.
     */
    private fun LocalHeddle.pushTowardCeiling(lane: GroupId, below: Long) {
        val edge = edgeOf(lane)
        reshapeForTest { ledger ->
            val weight = checkNotNull(ledger.record(edge)).weight
            val issued = ledger.baseIssuance(edge)
            val floor = ticksToVirtual(positionCeiling(weight) - below, weight)
            ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to Gauge(floor, issued))))
        }
        check(grossTicks(lane) == ceilingOf(lane) - below) { "the rig did not put ${lane.value} $below under its ceiling" }
    }

    private fun LocalHeddle.bytes(): ByteArray = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), persist())

    @Test
    fun anAcquireAtASaturatedParentRefusesTypedAndChangesNothing() {
        val engine = engine().apply { warmUp() }
        val twin = engine().apply { warmUp() }
        engine.pushTowardCeiling(a, below = 0L)
        twin.pushTowardCeiling(a, below = 0L)
        val stateBefore = engine.snapshot()
        val bytesBefore = engine.bytes()

        // b alone is eligible, but a is a child of the same parent: the pick there would order a
        // saturated position, so it is refused, not guessed.
        val refused = engine.acquire(setOf(a, b), setOf(b), startedAt = 100L)
        assertAll(
            { assertEquals(AcquireOutcome.PositionCeiling(root), refused) },
            { assertEquals(stateBefore, engine.snapshot(), "the state is as it was, wake memory included") },
            { assertContentEquals(bytesBefore, engine.bytes(), "the snapshot encodes as it did") },
            { assertContentEquals(twin.bytes(), engine.bytes(), "and matches a twin that never made the call") },
        )
    }

    @Test
    fun aSaturatedNestedGroupRefusesOnlyPicksThatOrderIt() {
        val engine = engine().apply { warmUp() }
        engine.pushTowardCeiling(x, below = 0L)
        val elsewhere = engine.acquire(setOf(a, b), setOf(a, b), startedAt = 100L)
        val beneath = engine.acquire(setOf(x, y), setOf(y), startedAt = 101L)
        assertAll(
            // The root orders a, b and g; g's own position is nowhere near its ceiling.
            { assertIs<AcquireOutcome.Acquired>(elsewhere) },
            { assertEquals(AcquireOutcome.PositionCeiling(g), beneath) },
        )
    }

    @Test
    fun anAcquireWhoseOwnDelegationWouldSaturateRefuses() {
        // No warm-up: a carries no wake offset, so the pick itself reads a below its ceiling and
        // only the delegation can be what crosses.
        val engine = engine()
        check(engine.snapshot().allocatorMemory.wakeOffsets.isEmpty()) { "a fresh engine has no wake offsets" }
        // One unit is 16 ticks: one tick under the ceiling, a pick of a is still exact, and its
        // delegation is what would cross.
        engine.pushTowardCeiling(a, below = 1L)
        val stateBefore = engine.snapshot()
        val refused = engine.acquire(setOf(a), setOf(a), startedAt = 100L)
        assertAll(
            { assertEquals(AcquireOutcome.PositionCeiling(root), refused) },
            { assertEquals(stateBefore, engine.snapshot()) },
            { assertEquals(engine.ceilingOf(a) - 1L, engine.grossTicks(a), "a did not move") },
        )
    }

    @Test
    fun anExtendAcrossTheCeilingRefusesTypedAndChangesNothing() {
        val engine = engine()
        val lease = (engine.acquire(setOf(a), setOf(a), startedAt = 100L) as AcquireOutcome.Acquired).lease
        engine.pushTowardCeiling(a, below = 1L)
        val stateBefore = engine.snapshot()
        val bytesBefore = engine.bytes()
        val refused = engine.extend(lease.id, amount = 1L)
        assertAll(
            { assertEquals(LeaseOutcome.PositionCeiling(root), refused) },
            { assertEquals(stateBefore, engine.snapshot(), "the lease's reservation is as it was") },
            { assertContentEquals(bytesBefore, engine.bytes()) },
            // The lease still settles: a charge is never refused.
            { assertIs<LeaseOutcome.Done>(engine.complete(lease.id, actualCost = 1L)) },
        )
    }

    @Test
    fun admitAndCompleteNeverRefuseAndThePositionSaturates() {
        val engine = engine().apply { warmUp() }
        engine.pushTowardCeiling(a, below = 1L)
        val admitted = engine.admit(a, startedAt = 100L, reservation = LocalHeddle.MAX_RESERVATION)
        val lease = assertIs<AcquireOutcome.Acquired>(admitted).lease
        val afterAdmit = engine.grossTicks(a)
        val done = engine.complete(lease.id, actualCost = 3 * LocalHeddle.MAX_RESERVATION)
        assertAll(
            { assertEquals(engine.ceilingOf(a), afterAdmit, "the admission saturated a's position") },
            { assertEquals(LeaseOutcome.Done(charged = 3 * LocalHeddle.MAX_RESERVATION, overrun = 2 * LocalHeddle.MAX_RESERVATION), done) },
            { assertEquals(engine.ceilingOf(a), engine.grossTicks(a), "the overrun's checkpoint saturated too") },
            // What saturates is the position, never the books: the charge is exact.
            { assertEquals(0L, engine.snapshot().ledger.edge(engine.edgeOf(a))?.outstanding) },
            { assertEquals(AcquireOutcome.PositionCeiling(root), engine.acquire(setOf(a, b), setOf(b), startedAt = 101L)) },
        )
    }

    @Test
    fun anAcquireThatThrowsPartWayChangesNothing() {
        val failing = engine().apply { warmUp() }
        val twin = engine().apply { warmUp() }
        val stateBefore = failing.snapshot()
        val bytesBefore = failing.bytes()

        // Only the nested group's lanes wait, so the descent must go root → g → leaf.
        val waiting = setOf(x, y)
        var fired = 0
        var stateMidCall: LocalHeddleState? = null
        failing.afterPickForTest = { group ->
            if (group == g) {
                fired++
                stateMidCall = failing.snapshot()
                throw IllegalStateException("injected after the pick at ${group.value}")
            }
        }
        assertFailsWith<IllegalStateException> { failing.acquire(waiting, waiting, startedAt = 100L) }
        failing.afterPickForTest = {}
        val stateAfter = failing.snapshot()
        val bytesAfter = failing.bytes()

        val next = failing.acquire(waiting, waiting, startedAt = 101L)
        val twinNext = twin.acquire(waiting, waiting, startedAt = 101L)
        assertAll(
            { assertEquals(1, fired, "the injected failure fired once") },
            // The rig: part-way through, the call had changed the engine, so an unchanged engine
            // afterwards is the rollback's doing and not a call that never got going.
            { assertNotEquals(stateBefore, stateMidCall, "the failing call had moved the engine before it threw") },
            { assertEquals(stateBefore, stateAfter, "the state is as it was before the call") },
            { assertContentEquals(bytesBefore, bytesAfter, "the snapshot encodes as it did before the call") },
            { assertEquals(twinNext, next, "the next call does what it would have done had the failed one never run") },
            { assertContentEquals(twin.bytes(), failing.bytes(), "and leaves the engine where the twin's is") },
        )
    }

    @Test
    fun explainAndPreviewNameTheCeiling() {
        val engine = engine().apply { warmUp() }
        engine.pushTowardCeiling(a, below = 0L)
        val why = engine.explain(b, waiting = setOf(a, b), eligible = setOf(b))
        assertAll(
            { assertEquals(WaitExplanation.PositionCeiling(root), why) },
            { assertEquals(WaitExplanation.Kind.POSITION_CEILING, why.kind) },
            { assertEquals(emptyList(), engine.previewPicks(mapOf(a to 1, b to 1), setOf(b), listOf(1L))) },
        )
    }

    @Test
    fun aWeightSetOverTheBudgetIsRefusedAtApply() {
        // Two primes just over 2³², so the lcm of the denominators leaves Long.
        val p = 4_294_967_311L
        val q = 4_294_967_357L
        val first = draft("budget") { lane("a", Weight.of(1, p)) }
        val second = draft("budget") {
            lane("a", Weight.of(1, p))
            lane("b", Weight.of(1, q))
        }
        val engine = LocalHeddle.fromTopology(first, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        val result = engine.apply(second, generation = 1L)
        val refusal = result.refusals.singleOrNull()
        assertAll(
            { assertEquals(second.groupId("b"), refusal?.child) },
            { assertTrue((refusal as? StepRefusal.Refused)?.reason?.contains("weight") == true, "refused for its weight: $refusal") },
            { assertTrue(engine.snapshot().ledger.activeChildren(engine.root).size == 1, "a alone is active") },
        )
    }

    @Test
    fun scheduleStopsAtASaturatedParentAndPickChildThrows() {
        val engine = engine().apply { warmUp() }
        engine.pushTowardCeiling(a, below = 0L)
        val ledger = engine.snapshot().ledger
        val allocator = FairShareAllocator(ReplicaId("local"), PolicyConfig(quantum = 1L))
        val wants = Demand(targetOutstanding = 10L, maximumUsefulGrant = 1L)
        val cell = object : LedgerCell {
            override var current: EntitlementLedger = ledger
            override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
                val patch = transform(current) ?: return false
                current = current.piece(patch.delta)
                return true
            }
        }
        assertAll(
            { assertEquals(0, allocator.schedule(root, cell, earmarked = 0L) { wants }) },
            { assertFailsWith<ArithmeticException> { allocator.pickChild(root, ledger, { wants }, { wants }) } },
        )
    }
}
