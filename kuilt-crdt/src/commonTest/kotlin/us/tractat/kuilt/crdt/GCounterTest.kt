package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GCounterTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun zeroHasValueZero() {
        assertEquals(0L, GCounter.ZERO.value)
    }

    @Test
    fun valueSumsAcrossReplicas() {
        assertEquals(7L, GCounter.of(a to 2L, b to 5L).value)
    }

    @Test
    fun incProducesADeltaThatRaisesTheCount() {
        val gc = GCounter.ZERO
        val delta = gc.inc(a, 3L)
        val next = gc.piece(delta)
        assertEquals(3L, next.value)
        assertEquals(3L, next.count(a))
    }

    @Test
    fun incDefaultsToOne() {
        assertEquals(1L, GCounter.ZERO.let { it.piece(it.inc(a)) }.value)
    }

    @Test
    fun incMustBePositive() {
        assertFailsWith<IllegalArgumentException> { GCounter.ZERO.inc(a, 0L) }
    }

    @Test
    fun pieceTakesElementwiseMaxNotSum() {
        // each replica owns its own slot; merge is max, so concurrent bumps to
        // DIFFERENT slots both count, but the same slot does not double-count
        assertEquals(
            GCounter.of(a to 2L, b to 3L),
            GCounter.of(a to 2L, b to 1L).piece(GCounter.of(a to 1L, b to 3L)),
        )
    }

    @Test
    fun roundTripsThroughJson() {
        val gc = GCounter.of(a to 2L, b to 5L)
        val encoded = Json.encodeToString(GCounter.serializer(), gc)
        assertEquals(gc, Json.decodeFromString(GCounter.serializer(), encoded))
    }

    @Test
    fun replicasListsEverySlotHolder() {
        assertEquals(setOf(a, b), GCounter.of(a to 2L, b to 5L).replicas())
    }

    @Test
    fun replicasIsEmptyForZero() {
        assertEquals(emptySet(), GCounter.ZERO.replicas())
    }

    @Test
    fun replicasCountsAnIncrementerButNotAnUntouchedReplica() {
        val gc = GCounter.ZERO.let { it.piece(it.inc(a)) }
        assertEquals(setOf(a), gc.replicas())
    }

    /**
     * [GCounter.of] can seed a slot at `0`, and such a replica *is* listed — the set is every
     * replica with a slot, not every replica with a positive count. Through [GCounter.inc] the
     * two coincide (an increment must be positive), so this only bites a hand-seeded or
     * deserialized counter. Pinned because the distinction is invisible on the mutator path.
     */
    @Test
    fun replicasListsAZeroSeededSlot() {
        assertEquals(setOf(a, b), GCounter.of(a to 0L, b to 5L).replicas())
    }

    // ── Overflow (#3318) ─────────────────────────────────────────────────────────────────────

    /**
     * An increment that would carry a slot past [Long.MAX_VALUE] is refused. Before the fix it
     * returned a patch whose count had wrapped negative, and [GCounter.piece], a max, then
     * discarded it, so the increment was lost for good with nothing reporting it.
     */
    @Test
    fun incRefusesAnIncrementThatWouldOverflowItsSlot() {
        val gc = GCounter.of(a to Long.MAX_VALUE - 5L)
        assertFailsWith<ArithmeticException> { gc.inc(a, 6L) }
    }

    /** The boundary on the other side: an increment landing exactly on [Long.MAX_VALUE] is kept. */
    @Test
    fun incReachesExactlyLongMaxValue() {
        val gc = GCounter.of(a to Long.MAX_VALUE - 5L)
        assertEquals(Long.MAX_VALUE, gc.piece(gc.inc(a, 5L)).count(a))
    }

    /** The refusal is about the slot being incremented, not about any other slot in the counter. */
    @Test
    fun incIsNotRefusedByAnotherReplicasFullSlot() {
        val gc = GCounter.of(a to 1L, b to Long.MAX_VALUE)
        assertEquals(2L, gc.piece(gc.inc(a)).count(a))
    }

    @Test
    fun valueRefusesASumThatDoesNotFitInALong() {
        val gc = GCounter.of(a to (1L shl 62), b to (1L shl 62))
        assertFailsWith<ArithmeticException> { gc.value }
    }

    @Test
    fun valueOrNullAnswersNullWhereValueRefusesAndTheSumWhereItFits() {
        assertAll(
            { assertEquals(null, GCounter.of(a to (1L shl 62), b to (1L shl 62)).valueOrNull()) },
            { assertEquals(Long.MAX_VALUE, GCounter.of(a to Long.MAX_VALUE - 1L, b to 1L).valueOrNull()) },
        )
    }

    @Test
    fun valueReachesExactlyLongMaxValueAcrossSlots() {
        val gc = GCounter.of(a to Long.MAX_VALUE - 1L, b to 1L)
        assertEquals(Long.MAX_VALUE, gc.value)
    }

    /**
     * Each replica's own increments fit its own slot; only the join overflows. This is why the
     * check on [GCounter.inc] cannot protect [GCounter.value] as well: no single replica ever held
     * a count it could have refused.
     */
    @Test
    fun valueRefusesAnOverflowThatOnlyTheJoinProduces() {
        val left = GCounter.ZERO.let { it.piece(it.inc(a, 1L shl 62)) }
        val right = GCounter.ZERO.let { it.piece(it.inc(b, 1L shl 62)) }
        assertAll(
            { assertEquals(1L shl 62, left.value, "each side fits on its own") },
            { assertEquals(1L shl 62, right.value, "each side fits on its own") },
            { assertFailsWith<ArithmeticException> { left.piece(right).value } },
        )
    }

    /**
     * A G-counter's slots are non-negative by definition: [GCounter.inc] only ever raises one, and
     * every sum built on the counter assumes it. A negative slot handed to [GCounter.of] is refused
     * (#3397). Before the fix it was accepted, and `value` came out below a slot's own count.
     */
    @Test
    fun ofRefusesANegativeSlot() {
        assertFailsWith<IllegalArgumentException> { GCounter.of(a to 5L, b to -1L) }
    }

    /** The boundary on the other side: a zero slot is a legal seed. */
    @Test
    fun ofAcceptsAZeroSlot() {
        assertEquals(0L, GCounter.of(a to 0L).count(a))
    }

    /**
     * The decoder refuses a negative slot too, because the check lives in the type rather than in
     * [GCounter.of] (#3397). The rig asserts that the JSON it rewrites really carries the slot it
     * negates, so a change of wire shape cannot turn this into a decode of a valid counter.
     */
    @Test
    fun decoderRefusesANegativeSlot() {
        val valid = Json.encodeToString(GCounter.serializer(), GCounter.of(a to 7L))
        val negated = valid.replace(":7", ":-7")
        assertAll(
            { assertEquals(7L, Json.decodeFromString(GCounter.serializer(), valid).count(a), "the valid encoding decodes") },
            { assertNotEquals(valid, negated, "the rig found the slot to negate in $valid") },
            { assertFailsWith<IllegalArgumentException> { Json.decodeFromString(GCounter.serializer(), negated) } },
        )
    }
}
