package us.tractat.kuilt.heddle

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.mulDiv
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
 * What the engine does at and past the position ceiling. Picks order the full 128-bit position, so
 * every call that used to refuse there now lands, the choosing ones ([LocalHeddle.acquire],
 * [LocalHeddle.extend], [LocalHeddle.explain], [LocalHeddle.previewPicks]) and the charging ones
 * alike; the position goes past [positionCeiling] exactly; and only the public virtual-time reads
 * saturate there. Each arm that used to assert a `PositionCeiling` refusal asserts, at the same rig,
 * what the engine does instead.
 *
 * The ceiling is reached by raising one lane's gauge to just under it ([pushTowardCeiling]), since a
 * run that gets there by picks takes thousands of maximum-size leases. Every arm first asserts that
 * its lane is where the rig put it, so a landing past the ceiling is not a fixture that never got
 * there.
 *
 * The engine rebases a parent long before a child gets there (#3329), so an arm that wants a child
 * to stay past the ceiling keeps a demanding sibling below its floor ([holdAtFloor]): the rebase
 * takes no demanding child out of its window, so a sibling with no room left holds it at nothing
 * (#3353, the window rule). Every position is planted relative to the parent's origin, which
 * [holdAtFloor] raises.
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

    private fun engine() = LocalHeddle.bootWhole(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
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

    /** [lane]'s gross position in ticks of its own service, relative to its parent's origin, exactly, as a pick reads it. */
    private fun LocalHeddle.grossWide(lane: GroupId): Int128 {
        val ledger = snapshot().ledger
        val edge = edgeOf(lane)
        val record = checkNotNull(ledger.record(edge))
        val gross = ledger.gauge(edge).exactGrossTicksAt(ledger.baseIssuance(edge))
        return gross - originTicks(ledger.origin(record.parent), record.weight)
    }

    /** [grossWide] held at [lane]'s ceiling, as a `Long`: what the rig plants and checks. */
    private fun LocalHeddle.grossTicks(lane: GroupId): Long =
        saturated(grossWide(lane), checkNotNull(snapshot().ledger.record(edgeOf(lane))).weight)

    /** [lane]'s gross virtual service as the public read reports it, held inside its window. */
    private fun LocalHeddle.publicGross(lane: GroupId): Rational =
        checkNotNull(snapshot().ledger.grossVirtualService(edgeOf(lane)))

    /** [lane]'s ceiling as the public read reports it: `C / (G · w)`. */
    private fun LocalHeddle.publicCeiling(lane: GroupId): Rational {
        val weight = checkNotNull(snapshot().ledger.record(edgeOf(lane))).weight
        return ticksToVirtual(positionCeiling(weight), weight)
    }

    /** The leaf [outcome] leased. */
    private fun leafOf(outcome: AcquireOutcome): GroupId = assertIs<AcquireOutcome.Acquired>(outcome, "it lands: $outcome").lease.leaf

    private fun LocalHeddle.ceilingOf(lane: GroupId): Long =
        positionCeiling(checkNotNull(snapshot().ledger.record(edgeOf(lane))).weight)

    /**
     * Raise [lane]'s gauge so its gross position reads [below] ticks under its ceiling, relative to
     * its parent's origin. A gauge only goes up under its join, so this is a write any peer could
     * have made.
     */
    private fun LocalHeddle.pushTowardCeiling(lane: GroupId, below: Long) {
        val edge = edgeOf(lane)
        reshapeForTest { ledger ->
            val record = checkNotNull(ledger.record(edge))
            val stored = originTicks(ledger.origin(record.parent), record.weight) + Int128.of(positionCeiling(record.weight) - below)
            ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to gaugeAt(stored, folded = ledger.baseIssuance(edge)))))
        }
        check(grossTicks(lane) == ceilingOf(lane) - below) { "the rig did not put ${lane.value} $below under its ceiling" }
    }

    /** [lane]'s effective position, relative to its parent's origin, as a pick reads it. */
    private fun LocalHeddle.effectiveTicks(lane: GroupId): Int128 {
        val state = snapshot()
        val ledger = state.ledger
        val edge = edgeOf(lane)
        val record = checkNotNull(ledger.record(edge))
        val raw = ledger.gauge(edge).rawTicksAt(ledger.baseIssuance(edge), ledger.edge(edge)?.returned ?: 0L, record.weight, ledger.origin(record.parent))
        return raw + (state.allocatorMemory.wakeOffsets[edge] ?: Int128.ZERO)
    }

    /**
     * Raise the origin of [lane]'s parent until [lane] sits below its floor, `−C` (#3353): a
     * demanding child there has no room to come down, so it holds every rebase at its parent at
     * nothing. Every sibling comes down by as much; [pushTowardCeiling] reads relative to the origin.
     */
    private fun LocalHeddle.holdAtFloor(lane: GroupId) {
        val edge = edgeOf(lane)
        val weight = checkNotNull(snapshot().ledger.record(edge)).weight
        val above = effectiveTicks(lane) - Int128.of(positionFloor(weight))
        // One unit more than the room, so the origin's rounding cannot leave it a tick short.
        val units = checkNotNull(mulDiv(above, weight.denominator, TICKS_PER_UNIT * weight.numerator, Rounding.CEILING)) + Int128.of(1L)
        reshapeForTest { ledger ->
            val parent = checkNotNull(ledger.record(edge)).parent
            ledger.piece(EntitlementLedger.of(origins = mapOf(parent to ledger.origin(parent) + units)))
        }
        check(effectiveTicks(lane) < Int128.of(positionFloor(weight))) { "the rig did not put ${lane.value} below its floor" }
    }

    private fun LocalHeddle.bytes(): ByteArray = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), persist())

    /**
     * A sibling at its ceiling no longer stops a pick at its parent: the pick orders the wide
     * positions, so with `b` alone eligible it picks `b`, and the engine moves. This used to be
     * refused with `PositionCeiling` and change nothing.
     */
    @Test
    fun anAcquireBesideASiblingAtItsCeilingLands() {
        val engine = engine().apply { warmUp() }
        engine.pushTowardCeiling(a, below = 0L)
        val stateBefore = engine.snapshot()

        val outcome = engine.acquire(setOf(a, b), setOf(b), startedAt = 100L)
        assertAll(
            { assertEquals(b, leafOf(outcome), "b, the one eligible leaf, is picked") },
            { assertNotEquals(stateBefore, engine.snapshot(), "and the engine kept the lease") },
        )
    }

    @Test
    fun aSaturatedNestedGroupLeasesBothBesideAndBeneathIt() {
        val engine = engine().apply { warmUp() }
        engine.pushTowardCeiling(x, below = 0L)
        val elsewhere = engine.acquire(setOf(a, b), setOf(a, b), startedAt = 100L)
        val beneath = engine.acquire(setOf(x, y), setOf(y), startedAt = 101L)
        assertAll(
            // The root orders a, b and g; g's own position is nowhere near its ceiling.
            { assertIs<AcquireOutcome.Acquired>(elsewhere) },
            // Not refused since #3353 round 2: x's position is exact, so g's pick rebases it down first.
            { assertIs<AcquireOutcome.Acquired>(beneath) },
        )
    }

    /**
     * A pick whose own delegation carries its child past the ceiling lands, and the position goes
     * past it exactly, by the one unit leased. Only the public read stops at the ceiling. This used
     * to be refused with `PositionCeiling`, with `a` left where it was.
     */
    @Test
    fun anAcquireWhoseOwnDelegationCrossesTheCeilingLandsExactly() {
        // No warm-up: a carries no wake offset, so the pick itself reads a below its ceiling and
        // only the delegation can be what crosses.
        val engine = engine()
        // One pick with a and b both waiting seats b and wakes nobody. b then waits beside a below
        // its floor, which holds the rebase at nothing.
        val seating = (engine.acquire(setOf(a, b), setOf(a, b), startedAt = 99L) as AcquireOutcome.Acquired).lease
        engine.complete(seating.id, actualCost = 1L)
        check(engine.snapshot().allocatorMemory.wakeOffsets.isEmpty()) { "nobody has woken, so there are no wake offsets" }
        engine.holdAtFloor(b)
        // One unit is 16 ticks: one tick under the ceiling, the delegation of one unit crosses it.
        engine.pushTowardCeiling(a, below = 1L)
        val outcome = engine.acquire(setOf(a, b), setOf(a), startedAt = 100L)
        val past = Int128.of(engine.ceilingOf(a) - 1L + TICKS_PER_UNIT)
        assertAll(
            { assertEquals(a, leafOf(outcome)) },
            { assertEquals(past, engine.grossWide(a), "a is one unit further on, past its ceiling, exactly") },
            { assertEquals(engine.publicCeiling(a), engine.publicGross(a), "while the public read holds at the ceiling") },
        )
    }

    /**
     * An extend whose delegation crosses the ceiling lands, and the lease holds the extension. This
     * used to be refused with `PositionCeiling`, the reservation left as it was.
     */
    @Test
    fun anExtendAcrossTheCeilingLandsAndTheLeaseHoldsIt() {
        val engine = engine()
        // An extend's own rebase runs after its charge. b waits beside a below its floor all the
        // same, which holds every rebase at nothing, so a stays where the extension puts it.
        val lease = (engine.acquire(setOf(a, b), setOf(a), startedAt = 100L) as AcquireOutcome.Acquired).lease
        engine.holdAtFloor(b)
        engine.pushTowardCeiling(a, below = 1L)
        val extended = engine.extend(lease.id, amount = 1L)
        assertAll(
            { assertEquals(LeaseOutcome.Done(charged = 0L, overrun = 0L), extended) },
            { assertEquals(lease.reserved + 1L, engine.snapshot().leases.single().reserved, "the lease holds the extension") },
            { assertEquals(Int128.of(engine.ceilingOf(a) - 1L + TICKS_PER_UNIT), engine.grossWide(a), "a went past its ceiling exactly") },
            { assertIs<LeaseOutcome.Done>(engine.complete(lease.id, actualCost = 2L)) },
        )
    }

    /**
     * An admission and an overrunning completion each carry `a` further past its ceiling, by exactly
     * what they charge, and the next acquire beside it still picks: the books and the positions stay
     * exact, and only the public read stops at the ceiling. The last assertion used to be a
     * `PositionCeiling` refusal.
     */
    @Test
    fun admitAndCompleteCarryThePositionPastTheCeilingExactly() {
        val engine = engine().apply { warmUp() }
        engine.holdAtFloor(b)
        engine.pushTowardCeiling(a, below = 1L)
        val start = engine.grossWide(a)
        val admitted = engine.admit(a, startedAt = 100L, reservation = LocalHeddle.MAX_RESERVATION)
        val lease = assertIs<AcquireOutcome.Acquired>(admitted).lease
        val afterAdmit = engine.grossWide(a)
        val done = engine.complete(lease.id, actualCost = 3 * LocalHeddle.MAX_RESERVATION)
        val unit = Int128.of(LocalHeddle.MAX_RESERVATION * TICKS_PER_UNIT)
        assertAll(
            { assertEquals(Int128.of(engine.ceilingOf(a) - 1L), start, "the rig: a starts a tick under its ceiling") },
            { assertEquals(start + unit, afterAdmit, "the admission moved a by its reservation, past the ceiling") },
            { assertEquals(LeaseOutcome.Done(charged = 3 * LocalHeddle.MAX_RESERVATION, overrun = 2 * LocalHeddle.MAX_RESERVATION), done) },
            { assertEquals(afterAdmit + unit + unit, engine.grossWide(a), "and the overrun's checkpoint by the overrun") },
            { assertEquals(engine.publicCeiling(a), engine.publicGross(a), "the public read holds at the ceiling") },
            { assertEquals(0L, engine.snapshot().ledger.edge(engine.edgeOf(a))?.outstanding) },
            { assertEquals(b, leafOf(engine.acquire(setOf(a, b), setOf(b), startedAt = 101L)), "and the next pick goes ahead") },
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

    /**
     * [LocalHeddle.explain] and [LocalHeddle.previewPicks] agree with the acquire beside a sibling at
     * its ceiling: the leaf is picked next, and the preview lists it. Both used to name the ceiling.
     */
    @Test
    fun explainAndPreviewAgreeWithTheAcquirePastTheCeiling() {
        val engine = engine().apply { warmUp() }
        engine.pushTowardCeiling(a, below = 0L)
        val why = engine.explain(b, waiting = setOf(a, b), eligible = setOf(b))
        val preview = engine.previewPicks(mapOf(a to 1, b to 1), setOf(b), listOf(1L))
        assertAll(
            { assertEquals(WaitExplanation.WouldBePickedNext, why) },
            { assertEquals(listOf(b), preview) },
            { assertEquals(b, leafOf(engine.acquire(setOf(a, b), setOf(b), startedAt = 100L)), "and the acquire picks it") },
        )
    }

    /**
     * A queues preview runs on past the ceiling from either call it makes (#3321): the acquire, beside
     * a sibling at its ceiling, and the extend that takes the lease up to its head, whose delegation
     * crosses. Each arm has a control below the ceiling, and each lists what that control lists.
     * Both used to stop the preview there.
     */
    @Test
    fun aQueuePreviewRunsOnPastTheCeiling() {
        val queues = mapOf(a to listOf(1L), b to listOf(1L))
        val unsaturated = engine().apply { warmUp() }.previewPicks(queues, setOf(b), reservation = 1L)
        val saturated = engine().apply { warmUp() }
        saturated.pushTowardCeiling(a, below = 0L)

        // One unit is 16 ticks: 17 under, an acquire of 1 lands one tick under, and an extend crosses.
        // b waits beside a, never eligible, below its floor: it holds the rebase's shift at zero
        // (#3353), so a is not brought down. One pick with both waiting seats b first.
        val crossing = engine()
        val seating = assertIs<AcquireOutcome.Acquired>(crossing.acquire(setOf(a, b), setOf(a), startedAt = 0L)).lease
        crossing.complete(seating.id, actualCost = 1L)
        crossing.holdAtFloor(b)
        crossing.pushTowardCeiling(a, below = 17L)
        val before = crossing.snapshot()
        val withoutExtend = crossing.previewPicks(mapOf(a to listOf(1L), b to listOf(1L)), setOf(a), reservation = 1L)
        val withExtend = crossing.previewPicks(mapOf(a to listOf(2L), b to listOf(1L)), setOf(a), reservation = 1L)
        val unchanged = crossing.snapshot() == before
        val lease = assertIs<AcquireOutcome.Acquired>(crossing.acquire(setOf(a, b), setOf(a), startedAt = 0L, reservation = 1L)).lease
        assertAll(
            { assertEquals(listOf(b), unsaturated, "the control: b is picked when a is not saturated") },
            { assertEquals(listOf(b), saturated.previewPicks(queues, setOf(b), reservation = 1L), "and when it is") },
            { assertEquals(listOf(a), withoutExtend, "the control: the acquire alone lands") },
            { assertEquals(listOf(a), withExtend, "and so does the acquire whose extend crosses") },
            { assertTrue(unchanged, "neither preview moved the engine") },
            { assertEquals(LeaseOutcome.Done(charged = 0L, overrun = 0L), crossing.extend(lease.id, 1L), "the rig: the extend lands") },
            { assertTrue(crossing.grossWide(a) > Int128.of(crossing.ceilingOf(a)), "the rig: and it crossed the ceiling") },
        )
    }

    @Test
    fun aWeightSetOverTheBudgetIsRefusedAtApply() {
        // `2⁵⁷/3` fits alone, and beside `1/192` the sum over `L = 192` is `2⁶³ + 1`, out of Long.
        // A lone `1/p` with `p` past 2³² no longer boots: it is too fine for one full lease (#3353).
        val first = draft("budget") { lane("a", Weight.of(1L shl 57, 3L)) }
        val second = draft("budget") {
            lane("a", Weight.of(1L shl 57, 3L))
            lane("b", Weight.of(1L, 192L))
        }
        val engine = LocalHeddle.bootWhole(first, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        val result = engine.apply(second, generation = 1L)
        val refusal = result.refusals.singleOrNull()
        assertAll(
            { assertEquals(second.groupId("b"), refusal?.child) },
            { assertTrue((refusal as? StepRefusal.Refused)?.reason?.contains("weight") == true, "refused for its weight: $refusal") },
            { assertTrue(engine.snapshot().ledger.activeChildren(engine.root).size == 1, "a alone is active") },
        )
    }

    /**
     * [FairShareAllocator.schedule] and [FairShareAllocator.pickChild] beside a child at its ceiling
     * grant as they would anywhere: the schedule delegates and writes, and the pick names a child.
     * They used to answer `PositionCeiling`, write nothing, and throw.
     */
    @Test
    fun scheduleAndPickChildGrantBesideAChildAtItsCeiling() {
        val engine = engine().apply { warmUp() }
        engine.holdAtFloor(b)
        engine.pushTowardCeiling(a, below = 0L)
        // Supply at the root, so the allocator reaches a pick at all rather than stopping at empty holdings.
        engine.mintSupply(100L)
        val ledger = engine.snapshot().ledger
        val self = ReplicaId("local")
        check(ledger.holdings(root, self) > 0L) { "the root holds supply for the allocator to grant" }
        // The engine's wake memory: it has seen b demanding, so b at its floor is not clamped up as a
        // waker, which is what a fresh allocator does with a child below zero it never saw.
        val allocator = FairShareAllocator(self, PolicyConfig(quantum = 1L), engine.snapshot().allocatorMemory)
        val wants = Demand(targetOutstanding = 10L, maximumUsefulGrant = 1L)
        val cell = object : LedgerCell {
            override var current: EntitlementLedger = ledger
            override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
                val patch = transform(current) ?: return false
                current = current.piece(patch.delta)
                return true
            }
        }
        val picked = FairShareAllocator(self, PolicyConfig(quantum = 1L), engine.snapshot().allocatorMemory)
            .pickChild(root, ledger, { wants }, { wants })
        assertAll(
            { assertIs<AllocationOutcome.Delegated>(allocator.schedule(root, cell, earmarked = 0L) { wants }) },
            { assertNotEquals(ledger, cell.current, "the grants were written") },
            { assertTrue(picked != null, "and a single pick names a child") },
        )
    }

    /** Put [lane]'s gross position at exactly [ticks], by raising its floor as [pushTowardCeiling] does. */
    private fun LocalHeddle.pushTo(lane: GroupId, ticks: Long) {
        val below = ceilingOf(lane) - ticks
        pushTowardCeiling(lane, below)
    }

    /**
     * A waker about to be clamped to a front past its own ceiling is rebased first (#3353 review,
     * item A). The front on a heavy waker's grid passes the waker's ceiling long before any sibling
     * reaches its own: with `a` at 1/1000 and `b` at 1000, `b`'s front is `10⁶` times `a`'s position.
     * Since review of #3352 that pick refused. The front, read on `b`'s grid, is past `b`'s threshold,
     * so the origin is raised before the wake step, `b` is clamped near zero, and the pick goes ahead.
     *
     * b is never seen asking before it wakes, so this peer holds no departure for it and clamps it.
     * A b seen to leave would come back by its parent's clock, near where it left, and count in the
     * front there (#3446), which then sits nowhere near its ceiling: until that, this rig passed on
     * a returner the front left out, so it raised the origin for a clamp that never happened.
     */
    @Test
    fun aWakerAboutToBeClampedPastItsCeilingIsRebasedFirst() {
        val clampTopology = draft("clamp") {
            lane("a", Weight.of(1, 1000))
            lane("b", Weight.of(1000))
        }
        val a = clampTopology.groupId("a")
        val b = clampTopology.groupId("b")
        val engine = LocalHeddle.bootWhole(clampTopology, LocalPolicy(defaultReservation = 1L, sleeperCredit = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        // b is seen idle, never asking, so the next pick that sees it waiting clamps it as a waker.
        repeat(2) {
            val lease = (engine.acquire(setOf(a), setOf(a), startedAt = 0L) as AcquireOutcome.Acquired).lease
            engine.complete(lease.id, actualCost = 1L)
        }
        engine.pushTo(a, 10_000_000_000_000L)
        val aWeight = Weight.of(1, 1000)
        val bWeight = Weight.of(1000)
        val bEdge = engine.edgeOf(b)
        check(engine.snapshot().allocatorMemory.demandingObserved[bEdge] == false) { "the rig: b was last observed idle" }
        check(bEdge !in engine.snapshot().allocatorMemory.departures) { "the rig: b was never seen leaving, so it is clamped" }
        // The rig: a is far below its own ceiling, and b's front, a's position times 10⁶, is past b's.
        check(engine.grossTicks(a) < positionCeiling(aWeight) / 100) { "a sits well below its ceiling" }
        check(engine.grossTicks(a).timesWide(1_000_000L) > Int128.of(positionCeiling(bWeight))) { "b's front is past b's ceiling" }
        val rebasesBefore = engine.rebasesForTest
        val outcome = engine.acquire(setOf(a, b), setOf(a, b), startedAt = 1L)
        assertAll(
            { assertIs<AcquireOutcome.Acquired>(outcome, "the pick goes ahead: $outcome") },
            { assertTrue(engine.rebasesForTest > rebasesBefore, "the rig: it rebased first") },
        )
    }

    /**
     * The same waker, observed by an `admit` with a waiting set (#3320). An admission is never
     * refused, so it charges, and the clamp it takes past the waker's ceiling is stored exactly, wide,
     * and the next pick goes ahead.
     *
     * b is never seen asking before it wakes, so this peer holds no departure for it and clamps it
     * to the front. A b seen to leave would come back by its parent's clock instead (#3376), and
     * the clock runs only on service: it never sees the gauge [pushTo] moves.
     */
    @Test
    fun anAdmitThatClampsAWakerPastItsCeilingChargesAndTheClampSaturates() {
        val clampTopology = draft("clamp") {
            lane("a", Weight.of(1, 1000))
            lane("b", Weight.of(1000))
        }
        val a = clampTopology.groupId("a")
        val b = clampTopology.groupId("b")
        val engine = LocalHeddle.bootWhole(clampTopology, LocalPolicy(defaultReservation = 1L, sleeperCredit = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        repeat(2) {
            val lease = (engine.acquire(setOf(a), setOf(a), startedAt = 0L) as AcquireOutcome.Acquired).lease
            engine.complete(lease.id, actualCost = 1L)
        }
        engine.pushTo(a, 10_000_000_000_000L)
        val bWeight = Weight.of(1000)
        val bEdge = engine.edgeOf(b)
        check(engine.snapshot().allocatorMemory.demandingObserved[bEdge] == false) { "the rig: b was last observed idle" }
        check(bEdge !in engine.snapshot().allocatorMemory.departures) { "the rig: b was never seen leaving, so it is clamped" }
        check(engine.grossTicks(a).timesWide(1_000_000L) > Int128.of(positionCeiling(bWeight))) { "b's front is past b's ceiling" }
        val admitted = engine.admit(a, setOf(a, b), startedAt = 1L)
        val offset = engine.snapshot().allocatorMemory.wakeOffsets[bEdge]
        assertAll(
            { assertIs<AcquireOutcome.Acquired>(admitted) },
            { assertTrue(offset != null && offset > Int128.of(positionCeiling(bWeight)), "b's clamp is exact, past its ceiling: $offset") },
            // Not refused since #3353 round 2: the clamp is exact, and the next pick's rebase brings b down.
            { assertIs<AcquireOutcome.Acquired>(engine.acquire(setOf(a, b), setOf(b), startedAt = 2L)) },
        )
    }

    /**
     * A newborn seated against a front that includes a sibling at its ceiling lands at the exact
     * front (#3353). The review of #3352 sent such a seat to the newborn's own ceiling, because the
     * sibling's position read lower than it stood for and so did the front. Nothing is narrowed
     * before the front is taken now, so the front is exact and the seat is stored exactly. `admit`
     * must charge, so it seats. The sibling `y`
     * waits at its floor, so the admission's own rebase cannot move `x` off its ceiling (#3353).
     */
    @Test
    fun aSeatAgainstASaturatedFrontLandsAtTheExactFront() {
        val rtTopology = draft("seat") {
            lane("a", Weight.ONE)
            group("rt", Weight.of(2)) { template(Weight.of(3, 2)) }
        }
        val rt = listOf("rt")
        val engine = LocalHeddle.bootWhole(rtTopology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            .apply { reconcileCapacity(1L) }
        for (name in listOf("x", "y")) check(engine.attach(rt, name, generation = 1L).refusals.isEmpty())
        val x = rtTopology.attachedChildId(rt, "x")
        val y = rtTopology.attachedChildId(rt, "y")
        // x and y are both observed waiting, so the seat's front is taken over both.
        repeat(4) {
            val lease = (engine.acquire(setOf(x, y), setOf(x, y), startedAt = 0L) as AcquireOutcome.Acquired).lease
            engine.complete(lease.id, actualCost = 1L)
        }
        // y at its floor holds the admission's own rebase at nothing, so x is still at its ceiling after it.
        engine.holdAtFloor(y)
        engine.pushTowardCeiling(x, below = 0L)
        check(engine.attach(rt, "n", generation = 2L).refusals.isEmpty())
        val n = rtTopology.attachedChildId(rt, "n")
        assertIs<AcquireOutcome.Acquired>(engine.admit(n, startedAt = 1L))
        // x, y and n share a weight, so the front on n's grid is ⌈(x + y) / 2⌉, and the admission charged one unit.
        val front = (engine.grossTicks(x) + engine.grossTicks(y) + 1L) / 2L
        assertAll(
            { assertEquals(engine.ceilingOf(x), engine.grossTicks(x), "the rig: x is at its ceiling") },
            { assertEquals(front + TICKS_PER_UNIT, engine.grossTicks(n), "the newborn is seated at the exact front") },
        )
    }
}
