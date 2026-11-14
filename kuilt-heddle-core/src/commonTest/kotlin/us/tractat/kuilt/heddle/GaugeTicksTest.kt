package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [Gauge.ticks], the gauge's wide absolute position (#3353, slice 5): stored exactly and uncapped,
 * joined by `max` on the whole 128-bit value, and read as `max(⌈floor · w · G⌉, ticks)` advanced over
 * the issuance since the fold.
 *
 * Every write assertion here reads the **stored** gauge, never a read through it: a read saturates
 * at the position ceiling, so a capped store and an exact one read alike and only the stored value
 * can tell them apart.
 */
class GaugeTicksTest {

    // ── storage: a seat and a checkpoint are exact past the old ceiling ──────────────────────

    /**
     * A seat past the edge's position ceiling is stored exactly. Before #3353 `seatAt` stored
     * `min(…, positionCeiling(w))`, a cap on an absolute value that an origin (slice 6) would turn
     * into credit once it passed it.
     */
    @Test
    fun aSeatPastTheCeilingIsStoredExactly() {
        val ceiling = positionCeiling(SMALL_CEILING)
        val ledger = ledgerWith(SMALL_CEILING)
        val seat = assertNotNull(ledger.seatAt(EDGE, ceiling + 1_000L), "an unseated edge takes a seat")
        val stored = assertNotNull(seat.delta.gauge(EDGE), "the seat patch carries the gauge")
        assertAll(
            { assertTrue(ceiling + 1_000L > ceiling, "the rig: the seat is past the ceiling") },
            { assertEquals(Int128.of(ceiling + 1_000L), stored.ticks, "stored exactly, not at the ceiling") },
            { assertEquals(Rational.ZERO, stored.floor, "the legacy floor is left at zero") },
            { assertEquals(0L, stored.folded) },
        )
    }

    /**
     * A seat whose own observed issuance is past `Long` in ticks is stored as that issuance,
     * exactly: `max(seat, observed · G)` is taken on the absolute values, in 128 bits.
     */
    @Test
    fun aSeatBehindItsObservedIssuanceStoresTheIssuanceExactlyPastLong() {
        val issued = Long.MAX_VALUE / 4L
        val ledger = ledgerWith(Weight.ONE, issued = issued)
        val stored = assertNotNull(assertNotNull(ledger.seatAt(EDGE, 0L)).delta.gauge(EDGE))
        val expected = issued.timesWide(TICKS_PER_UNIT)
        assertAll(
            { assertTrue(expected.toLongOrNull() == null, "the rig: the issuance in ticks is past Long") },
            { assertEquals(expected, stored.ticks) },
            { assertEquals(issued, stored.folded) },
        )
    }

    /**
     * `delegate`'s checkpoint is the gross position after its own grant, stored exactly. Seated
     * one unit below the ceiling and granted five, the edge's position is four units past it.
     */
    @Test
    fun aCheckpointPastTheCeilingIsStoredExactly() {
        val w = SMALL_CEILING
        val ceiling = positionCeiling(w)
        var ledger = fundedLedgerWith(w)
        val seatedAt = ceiling - TICKS_PER_UNIT
        ledger = ledger.piece(assertNotNull(ledger.seatAt(EDGE, seatedAt)).delta)
        val patch = assertNotNull(ledger.delegate(GRANTOR, EDGE, 5L), "the grantor holds enough")
        val checkpoint = assertNotNull(patch.delta.gauge(EDGE))
        val merged = assertNotNull(ledger.piece(patch.delta).gauge(EDGE))
        val expected = Int128.of(seatedAt) + 5L * TICKS_PER_UNIT
        assertAll(
            { assertTrue(expected > Int128.of(ceiling), "the rig: the position is past the ceiling") },
            { assertEquals(expected, checkpoint.ticks, "the checkpoint stores the exact position") },
            { assertEquals(5L, checkpoint.folded) },
            { assertEquals(expected, merged.ticks, "and the joined gauge keeps it") },
            { assertTrue(ledger.piece(patch.delta).isAtPositionCeiling(EDGE), "while the read saturates") },
        )
    }

    // ── the lattice: max on the whole 128-bit value ────────────────────────────────────────

    /**
     * A `max` on each half is not the 128-bit `max`: `2⁶⁴ + 5` against `7` is `2⁶⁴ + 5`, while a
     * per-half `max` answers `2⁶⁴ + 7`, a position neither writer asserted.
     */
    @Test
    fun theJoinTakesTheWiderPositionWholeNotAMaxPerHalf() {
        val wide = Gauge(Rational.ZERO, 0L, ticksHigh = 1L, ticksLow = 5L)
        val narrow = Gauge(Rational.ZERO, 0L, ticksHigh = 0L, ticksLow = 7L)
        val unsignedLow = Gauge(Rational.ZERO, 0L, ticksHigh = 0L, ticksLow = -1L) // 2⁶⁴ − 1
        assertAll(
            { assertEquals(wide, wide.join(narrow), "2⁶⁴ + 5 ⊔ 7 = 2⁶⁴ + 5") },
            { assertEquals(wide, narrow.join(wide), "commutative on the same pair") },
            { assertEquals(wide, wide.join(unsignedLow), "2⁶⁴ + 5 ⊔ 2⁶⁴ − 1 = 2⁶⁴ + 5: the low word is unsigned") },
            { assertEquals(unsignedLow, unsignedLow.join(narrow), "2⁶⁴ − 1 ⊔ 7 = 2⁶⁴ − 1, not 7") },
        )
    }

    /**
     * Idempotent, commutative and associative over gauges that differ in every component, the
     * ticks spread across both words; and the joined ticks are always one of the two inputs' and
     * no lower than either.
     */
    @Test
    fun theJoinIsALatticeOnAllThreeComponents() {
        val rnd = Random(0x3353_5)
        fun gauge() = Gauge(
            Rational.of(rnd.nextLong(0L, 500L), rnd.nextLong(1L, 9L)),
            rnd.nextLong(0L, 500L),
            ticksHigh = rnd.nextLong(0L, 3L),
            ticksLow = rnd.nextLong(),
        )
        repeat(500) {
            val a = gauge()
            val b = gauge()
            val c = gauge()
            val ab = a.join(b)
            assertAll(
                { assertEquals(a, a.join(a), "idempotent") },
                { assertEquals(ab, b.join(a), "commutative") },
                { assertEquals(ab.join(c), a.join(b.join(c)), "associative") },
                { assertTrue(ab.ticks >= a.ticks && ab.ticks >= b.ticks, "ticks fell: $a ⊔ $b = $ab") },
                { assertTrue(ab.ticks == a.ticks || ab.ticks == b.ticks, "ticks invented: $a ⊔ $b = $ab") },
            )
        }
    }

    // ── the read: max(⌈floor · w · G⌉, ticks) + (issued − folded) · G ───────────────────────

    /**
     * A legacy floor-only gauge joined with a ticks-only one reads whichever asserts more, each
     * paired with the joined fold. Ticks dominating: a seat at 50 (floor) and a checkpoint at 70
     * after 20 units (ticks) read 70.
     */
    @Test
    fun aLegacyFloorJoinedWithTicksReadsTheTicksWhereTheyDominate() {
        val legacy = Gauge(Rational.of(50L), 0L)
        val checkpoint = gaugeAt(Int128.of(70L * TICKS_PER_UNIT), 20L)
        val read = ledgerWith(Weight.ONE, issued = 20L, gauge = legacy.join(checkpoint)).grossVirtualService(EDGE)
        assertEquals(Rational.of(70L), read)
    }

    /**
     * The class KDoc's stale-fold example with each side in a different component: a stale legacy
     * floor `(70¼, 0)` joined with an honest ticks-only checkpoint at 70 after 20 units. The floor
     * dominates the base and pairs with the honest fold, so it reads `70¼`, not the `90¼` the stale
     * pair alone claims and not the `70` that dropping the legacy floor would read.
     */
    @Test
    fun aStaleLegacyFloorJoinedWithAnHonestTicksCheckpointIsDeflatedNotDropped() {
        val stale = Gauge(Rational.of(281L, 4L), 0L)
        val honest = gaugeAt(Int128.of(70L * TICKS_PER_UNIT), 20L)
        val merged = stale.join(honest)
        assertAll(
            { assertEquals(Rational.of(281L, 4L), ledgerWith(Weight.ONE, issued = 20L, gauge = merged).grossVirtualService(EDGE)) },
            { assertEquals(Rational.of(361L, 4L), ledgerWith(Weight.ONE, issued = 20L, gauge = stale).grossVirtualService(EDGE), "the stale pair alone: 90¼") },
        )
    }

    /** The same example with both sides in `ticks`: `(70¼, 0) ⊔ (70, 20)` reads `70¼`. */
    @Test
    fun theStaleFoldExampleInTicksIsDeflatedByTheComponentwiseJoin() {
        val stale = gaugeAt(Int128.of(281L * TICKS_PER_UNIT / 4L), 0L)
        val honest = gaugeAt(Int128.of(70L * TICKS_PER_UNIT), 20L)
        val merged = stale.join(honest)
        assertAll(
            { assertEquals(gaugeAt(Int128.of(281L * TICKS_PER_UNIT / 4L), 20L), merged) },
            { assertEquals(Rational.of(281L, 4L), ledgerWith(Weight.ONE, issued = 20L, gauge = merged).grossVirtualService(EDGE)) },
        )
    }

    /** Ticks past `Long` read as the ceiling: the read narrows and saturates, and never throws. */
    @Test
    fun ticksPastLongReadAsTheCeiling() {
        val pastLong = Gauge(Rational.ZERO, 0L, ticksHigh = 1L, ticksLow = 0L)
        assertEquals(
            ticksToVirtual(positionCeiling(Weight.ONE), Weight.ONE),
            ledgerWith(Weight.ONE, issued = 3L, gauge = pastLong).grossVirtualService(EDGE),
        )
    }

    // ── the wire ─────────────────────────────────────────────────────────────────────────

    /** `ticks` is elided at zero, so a floor-only gauge encodes as it did before #3353. */
    @Test
    fun zeroTicksAreElidedAndTicksRoundTrip() {
        val legacy = Gauge(Rational.of(70L), 20L)
        val wide = Gauge(Rational.ZERO, 20L, ticksHigh = 3L, ticksLow = -2L)
        assertAll(
            { assertEquals("""{"floor":{"numerator":70,"denominator":1},"folded":20}""", Json.encodeToString(legacy)) },
            { assertEquals(wide, Json.decodeFromString<Gauge>(Json.encodeToString(wide))) },
        )
    }

    /** Negative ticks name no position, so they do not decode. */
    @Test
    fun negativeTicksCannotArriveOffTheWire() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<Gauge>("""{"floor":{"numerator":0,"denominator":1},"folded":0,"ticksHigh":-1}""")
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private fun ledgerWith(weight: Weight, issued: Long = 0L, gauge: Gauge? = null): EntitlementLedger =
        EntitlementLedger.of(
            records = mapOf(EDGE to setOf(AttachmentRecord(EDGE, PARENT, CHILD, weight))),
            issued = if (issued == 0L) emptyMap() else mapOf(EDGE to GCounter.of(GRANTOR to issued)),
            gauges = gauge?.let { mapOf(EDGE to it) } ?: emptyMap(),
        )

    private fun fundedLedgerWith(weight: Weight): EntitlementLedger {
        var ledger = EntitlementLedger.bootstrap(PARENT, mapOf(GRANTOR to 1_000L), nonce = "3353-5")
        val record = AttachmentRecord(EDGE, PARENT, CHILD, weight)
        ledger = ledger.piece(assertNotNull(ledger.prepare(record)).delta)
        return ledger.piece(assertNotNull(ledger.activate(EDGE)).delta)
    }

    private companion object {
        val EDGE = AttachmentId("e")
        val PARENT = GroupId("parent")
        val CHILD = GroupId("child")
        val GRANTOR = ReplicaId("g")

        /** A weight whose denominator brings the position ceiling down to about 2²³ ticks. */
        val SMALL_CEILING: Weight = Weight.of(1L, 1L shl 40)
    }
}
