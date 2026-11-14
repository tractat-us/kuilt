package us.tractat.kuilt.heddle

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
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
 *
 * The engine rebases a parent long before a child gets there (#3329), so an arm that wants the
 * ceiling keeps a demanding sibling near its origin: the rebase can move no demanding child below
 * zero, so that sibling holds it at nothing, the one way a running engine still reaches the ceiling.
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
        // One pick with a and b both waiting seats b near its origin and wakes nobody. b then
        // waits beside a, which holds the rebase at nothing.
        val seating = (engine.acquire(setOf(a, b), setOf(a, b), startedAt = 99L) as AcquireOutcome.Acquired).lease
        engine.complete(seating.id, actualCost = 1L)
        check(engine.snapshot().allocatorMemory.wakeOffsets.isEmpty()) { "nobody has woken, so there are no wake offsets" }
        // One unit is 16 ticks: one tick under the ceiling, a pick of a is still exact, and its
        // delegation is what would cross.
        engine.pushTowardCeiling(a, below = 1L)
        val stateBefore = engine.snapshot()
        val refused = engine.acquire(setOf(a, b), setOf(a), startedAt = 100L)
        assertAll(
            { assertEquals(AcquireOutcome.PositionCeiling(root), refused) },
            { assertEquals(stateBefore, engine.snapshot()) },
            { assertEquals(engine.ceilingOf(a) - 1L, engine.grossTicks(a), "a did not move") },
        )
    }

    @Test
    fun anExtendAcrossTheCeilingRefusesTypedAndChangesNothing() {
        val engine = engine()
        // An extend never rebases, only a pick does, so its delegation is what crosses. b waits
        // beside a near its origin all the same, which also holds the acquire's rebase at nothing.
        val lease = (engine.acquire(setOf(a, b), setOf(a), startedAt = 100L) as AcquireOutcome.Acquired).lease
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

    /**
     * A queues preview stops at a position-ceiling refusal from either call it makes (#3321): the
     * acquire, when a sibling of the pick is saturated, or the extend that takes the lease up to its
     * head, when that delegation would cross. In the second the acquire landed, and its pick is
     * still left out. Each arm has a control that differs only in the refusal.
     */
    @Test
    fun aQueuePreviewStopsAtTheCeiling() {
        val queues = mapOf(a to listOf(1L), b to listOf(1L))
        val unsaturated = engine().apply { warmUp() }.previewPicks(queues, setOf(b), reservation = 1L)
        val saturated = engine().apply { warmUp() }
        saturated.pushTowardCeiling(a, below = 0L)

        // One unit is 16 ticks: 17 under, an acquire of 1 lands one tick under, and an extend crosses.
        // b waits beside a, never eligible, at its origin: it holds the rebase's shift at zero
        // (#3329), the one way a running engine still reaches the ceiling, so a is not brought down.
        // One pick with both waiting seats b near its origin first.
        val crossing = engine()
        val seating = assertIs<AcquireOutcome.Acquired>(crossing.acquire(setOf(a, b), setOf(a), startedAt = 0L)).lease
        crossing.complete(seating.id, actualCost = 1L)
        crossing.pushTowardCeiling(a, below = 17L)
        val before = crossing.snapshot()
        val withoutExtend = crossing.previewPicks(mapOf(a to listOf(1L), b to listOf(1L)), setOf(a), reservation = 1L)
        val withExtend = crossing.previewPicks(mapOf(a to listOf(2L), b to listOf(1L)), setOf(a), reservation = 1L)
        val unchanged = crossing.snapshot() == before
        val lease = assertIs<AcquireOutcome.Acquired>(crossing.acquire(setOf(a, b), setOf(a), startedAt = 0L, reservation = 1L)).lease
        assertAll(
            { assertEquals(listOf(b), unsaturated, "the control: b is picked when a is not saturated") },
            { assertEquals(emptyList(), saturated.previewPicks(queues, setOf(b), reservation = 1L)) },
            { assertEquals(listOf(a), withoutExtend, "the control: the acquire alone lands") },
            { assertEquals(emptyList(), withExtend) },
            { assertTrue(unchanged, "neither preview moved the engine") },
            { assertEquals(LeaseOutcome.PositionCeiling(root), crossing.extend(lease.id, 1L), "the rig: the extend is what refuses") },
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
        // Supply at the root, so the allocator reaches a pick at all rather than stopping at empty holdings.
        engine.mintSupply(100L)
        val ledger = engine.snapshot().ledger
        val self = ReplicaId("local")
        check(ledger.holdings(root, self) > 0L) { "the root holds supply for the allocator to grant" }
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 1L))
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
            { assertEquals(ledger, cell.current, "the refused pick wrote nothing") },
            { assertFailsWith<ArithmeticException> { allocator.pickChild(root, ledger, { wants }, { wants }) } },
        )
    }

    /** Put [lane]'s gross position at exactly [ticks], by raising its floor as [pushTowardCeiling] does. */
    private fun LocalHeddle.pushTo(lane: GroupId, ticks: Long) {
        val below = ceilingOf(lane) - ticks
        pushTowardCeiling(lane, below)
    }

    /**
     * A waker clamped to a front past its own ceiling stores the ceiling itself, so it reads as
     * saturated and the pick refuses (review of #3352). The front on a heavy waker's grid passes
     * the waker's ceiling long before any sibling reaches its own: with `a` at 1/1000 and `b` at
     * 1000, `b`'s front is `10⁶` times `a`'s position. An offset of `ceiling − raw − credit`, the
     * old clamp, reads `16 · credit` ticks under the ceiling as if it were exact, so with any
     * sleeper credit the pick went ahead on a position that stands for more.
     */
    @Test
    fun aWakerClampedPastItsCeilingSaturatesWhole() {
        val clampTopology = draft("clamp") {
            lane("a", Weight.of(1, 1000))
            lane("b", Weight.of(1000))
        }
        val a = clampTopology.groupId("a")
        val b = clampTopology.groupId("b")
        val engine = LocalHeddle.fromTopology(clampTopology, LocalPolicy(defaultReservation = 1L, sleeperCredit = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        // b is seen waiting, then idle, so the next pick that sees it waiting clamps it as a waker.
        for (waiting in listOf(setOf(a, b), setOf(a))) {
            val lease = (engine.acquire(waiting, waiting, startedAt = 0L) as AcquireOutcome.Acquired).lease
            engine.complete(lease.id, actualCost = 1L)
        }
        engine.pushTo(a, 10_000_000_000_000L)
        val aWeight = Weight.of(1, 1000)
        val bWeight = Weight.of(1000)
        // The rig: a is far below its own ceiling, and b's front, a's position times 10⁶, is past b's.
        check(engine.grossTicks(a) < positionCeiling(aWeight) / 100) { "a sits well below its ceiling" }
        check(engine.grossTicks(a).timesWide(1_000_000L) > Int128.of(positionCeiling(bWeight))) { "b's front is past b's ceiling" }
        assertEquals(AcquireOutcome.PositionCeiling(engine.root), engine.acquire(setOf(a, b), setOf(a, b), startedAt = 1L))
    }

    /**
     * The same waker, observed by an `admit` with a waiting set (#3320). An admission is never
     * refused, so it charges, and the clamp it takes past the waker's ceiling saturates rather than
     * throwing: the stored offset is the ceiling itself, and the next pick refuses as it would have.
     */
    @Test
    fun anAdmitThatClampsAWakerPastItsCeilingChargesAndTheClampSaturates() {
        val clampTopology = draft("clamp") {
            lane("a", Weight.of(1, 1000))
            lane("b", Weight.of(1000))
        }
        val a = clampTopology.groupId("a")
        val b = clampTopology.groupId("b")
        val engine = LocalHeddle.fromTopology(clampTopology, LocalPolicy(defaultReservation = 1L, sleeperCredit = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        for (waiting in listOf(setOf(a, b), setOf(a))) {
            val lease = (engine.acquire(waiting, waiting, startedAt = 0L) as AcquireOutcome.Acquired).lease
            engine.complete(lease.id, actualCost = 1L)
        }
        engine.pushTo(a, 10_000_000_000_000L)
        val bWeight = Weight.of(1000)
        val bEdge = engine.edgeOf(b)
        check(engine.snapshot().allocatorMemory.demandingObserved[bEdge] == false) { "the rig: b was last observed idle" }
        check(engine.grossTicks(a).timesWide(1_000_000L) > Int128.of(positionCeiling(bWeight))) { "b's front is past b's ceiling" }
        val admitted = engine.admit(a, setOf(a, b), startedAt = 1L)
        val offset = engine.snapshot().allocatorMemory.wakeOffsets[bEdge]
        assertAll(
            { assertIs<AcquireOutcome.Acquired>(admitted) },
            { assertEquals(positionCeiling(bWeight), offset?.let { ticksAt(it, bWeight) }, "b's clamp is held at its ceiling") },
            { assertEquals(AcquireOutcome.PositionCeiling(engine.root), engine.acquire(setOf(a, b), setOf(b), startedAt = 2L)) },
        )
    }

    /**
     * A newborn seated against a front that includes a saturated sibling lands at its own ceiling
     * (review of #3352). The sibling's position reads lower than it stands for, so the front does too;
     * a seat there would sit behind the true front, and once the saturated sibling left, the newborn
     * would take the picks that gap is worth, the lifetime credit §10.5 forbids. `admit` must charge,
     * so it seats, and the seat goes to the penalty end.
     */
    @Test
    fun aSeatAgainstASaturatedFrontLandsAtTheCeiling() {
        val rtTopology = draft("seat") {
            lane("a", Weight.ONE)
            group("rt", Weight.of(2)) { runtimeChildren(Weight.of(3, 2)) }
        }
        val rt = listOf("rt")
        val engine = LocalHeddle.fromTopology(rtTopology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        for (name in listOf("x", "y")) check(engine.attachRuntimeChild(rt, name, generation = 1L).refusals.isEmpty())
        val x = rtTopology.runtimeChildId(rt, "x")
        val y = rtTopology.runtimeChildId(rt, "y")
        // x and y are both observed waiting, so the seat's front is taken over both.
        repeat(4) {
            val lease = (engine.acquire(setOf(x, y), setOf(x, y), startedAt = 0L) as AcquireOutcome.Acquired).lease
            engine.complete(lease.id, actualCost = 1L)
        }
        engine.pushTowardCeiling(x, below = 0L)
        check(engine.attachRuntimeChild(rt, "n", generation = 2L).refusals.isEmpty())
        val n = rtTopology.runtimeChildId(rt, "n")
        assertIs<AcquireOutcome.Acquired>(engine.admit(n, startedAt = 1L))
        assertEquals(engine.ceilingOf(n), engine.grossTicks(n), "the newborn is seated at its own ceiling, not behind the true front")
    }
}
