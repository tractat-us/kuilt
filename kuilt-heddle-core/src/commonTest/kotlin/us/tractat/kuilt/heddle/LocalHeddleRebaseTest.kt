package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How far one rebase moves a parent's children (#3329): every child by the same virtual time `Δ`,
 * as far as the demanding child furthest behind allows and no further, and an idle child only as
 * far as keeps it at `−R`, where `R` is a sixteenth of its ceiling.
 *
 * Positions are planted by raising a lane's gauge floor ([pushTo]), a write any peer could make.
 * Every lane weighs 1, so `Δ` in ticks is the same number on every lane. Each arm asserts first
 * that the rig put the lanes where it meant to.
 */
class LocalHeddleRebaseTest {

    private val topology = draft("rebase") {
        lane("a", Weight.ONE)
        lane("b", Weight.ONE)
        lane("c", Weight.ONE)
    }
    private val a = topology.groupId("a")
    private val b = topology.groupId("b")
    private val c = topology.groupId("c")

    /** `R` for a lane of weight 1, in ticks. */
    private val threshold = positionCeiling(Weight.ONE) / 16

    /** An engine in which every lane has been picked once, so every lane is seated and observed waiting. */
    private fun engine(rebase: Boolean = true) = LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        .apply {
            rebaseForTest = rebase
            reconcileCapacity(1L)
            repeat(3) {
                val lease = (acquire(setOf(a, b, c), setOf(a, b, c), startedAt = 0L) as AcquireOutcome.Acquired).lease
                complete(lease.id, actualCost = 1L)
            }
        }

    private fun LocalHeddle.edgeOf(lane: GroupId): AttachmentId =
        checkNotNull(snapshot().ledger.lineageOf(lane)?.last()) { "${lane.value} has no lineage" }

    /** [lane]'s raw position in ticks: gross, less what it returned. */
    private fun LocalHeddle.ticks(lane: GroupId): Long {
        val ledger = snapshot().ledger
        val edge = edgeOf(lane)
        val gross = ledger.gauge(edge).grossTicksAt(ledger.baseIssuance(edge), Weight.ONE)
        return netTicks(gross, ledger.edge(edge)?.returned ?: 0L, Weight.ONE)
    }

    /** Put [lane]'s position at exactly [ticks] by raising its gauge floor at its current fold. */
    private fun LocalHeddle.pushTo(lane: GroupId, ticks: Long) {
        val edge = edgeOf(lane)
        reshapeForTest { ledger ->
            val gauge = checkNotNull(ledger.gauge(edge)) { "${lane.value} is seated" }
            val issued = ledger.baseIssuance(edge)
            val floorTicks = ticks - (issued - gauge.folded) * TICKS_PER_UNIT
            ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to Gauge(ticksToVirtual(floorTicks, Weight.ONE), gauge.folded))))
        }
        check(ticks(lane) == ticks) { "the rig put ${lane.value} at ${ticks(lane)}, not $ticks" }
    }

    /**
     * `a` is past the threshold and `b`, also waiting, sits 1,000 units and 5 ticks above its
     * origin. `Δ` is 1,000 units: the most that keeps `b` at or above zero. Every lane moves by it,
     * `c` too although it is idle, since 1,000 units is nowhere near `−R`. Then `b` is picked and
     * delegated one unit.
     */
    @Test
    fun aDemandingChildFarBehindHoldsTheShiftBack() {
        val engine = engine()
        engine.pushTo(a, 2 * threshold)
        engine.pushTo(b, 16_005L)
        val cBefore = engine.ticks(c)
        val rebasesBefore = engine.rebasesForTest

        val out = engine.acquire(setOf(a, b), setOf(b), startedAt = 1L)
        val shift = 1_000L * TICKS_PER_UNIT
        assertAll(
            { assertEquals(b, (out as AcquireOutcome.Acquired).lease.leaf) },
            { assertEquals(rebasesBefore + 1, engine.rebasesForTest, "one rebase") },
            { assertEquals(2 * threshold - shift, engine.ticks(a), "a moved by Δ") },
            { assertEquals(5L + TICKS_PER_UNIT, engine.ticks(b), "b moved by Δ, to 5 ticks, then took its unit") },
            { assertEquals(cBefore - shift, engine.ticks(c), "idle c moved by Δ too: it is far above −R") },
        )
    }

    /**
     * `a` and `b` both wait, `b` the lower at `2R`, so `Δ` is about `2R`; `c` is idle a few ticks
     * above its origin, and moving it the whole way would put it near `−R` below `−R`. It moves only
     * to `−R` instead: credit the wake clamp takes back. And it does take it back. When `c` wakes
     * beside `a` it is clamped to `a`'s front and served, and the engine picks exactly as a twin
     * that never rebased.
     */
    @Test
    fun anIdleChildFarBehindIsLiftedOnlyToMinusR() {
        val engine = engine()
        val twin = engine(rebase = false)
        for (e in listOf(engine, twin)) {
            e.pushTo(a, 3 * threshold)
            e.pushTo(b, 2 * threshold)
        }
        val aBefore = engine.ticks(a)
        val cBefore = engine.ticks(c)
        check(cBefore in 0L..(4 * TICKS_PER_UNIT)) { "the rig: c sits a few units above its origin, at $cBefore" }

        val out = engine.acquire(setOf(a, b), setOf(b), startedAt = 1L)
        twin.acquire(setOf(a, b), setOf(b), startedAt = 1L)
        val shift = aBefore - engine.ticks(a)
        val afterRebase = engine.ticks(c)
        val pickedAfterWake = (0 until 40).map { i ->
            val waiting = if (i % 3 == 0) setOf(a, b, c) else setOf(a, c)
            listOf(engine, twin).map { e ->
                val lease = (e.acquire(waiting, waiting, startedAt = 2L + i) as AcquireOutcome.Acquired).lease
                e.complete(lease.id, actualCost = 1L)
                lease.leaf
            }
        }
        assertAll(
            { assertEquals(b, (out as AcquireOutcome.Acquired).lease.leaf) },
            { assertTrue(shift > 2 * threshold - TICKS_PER_UNIT && shift <= 2 * threshold, "Δ is b's position in whole units: $shift") },
            { assertTrue(afterRebase >= -threshold && afterRebase < -threshold + TICKS_PER_UNIT, "c lands at −R, not ${afterRebase + threshold} ticks from it") },
            { assertTrue(cBefore - afterRebase < shift, "c moved less than Δ") },
            { assertEquals(pickedAfterWake.map { it[1] }, pickedAfterWake.map { it[0] }, "once c wakes, every pick is the twin's") },
            { assertTrue(pickedAfterWake.any { it[0] == c }, "and c was picked, so the wake was reached") },
        )
    }
}
