package us.tractat.kuilt.heddle

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WeightTest {

    @Test
    fun ordersByExactCrossMultiplication() {
        assertTrue(Weight.of(1, 3) < Weight.of(1, 2))
        assertTrue(Weight.of(3, 1) > Weight.of(2, 1))
        assertTrue(Weight.of(2, 3) > Weight.of(1, 2))
        assertEquals(0, Weight.of(1, 2).compareTo(Weight.of(2, 4)))
    }

    @Test
    fun reducesToLowestTermsSoEqualRatiosAreStructurallyEqual() {
        assertEquals(Weight.of(1, 2), Weight.of(2, 4))
        assertEquals(Weight.of(3, 1), Weight.of(9, 3))
        assertEquals(Weight.of(2), Weight.of(4, 2))
        assertEquals(Weight.ONE, Weight.of(5, 5))
    }

    @Test
    fun rejectsNonPositiveComponents() {
        assertFailsWith<IllegalArgumentException> { Weight.of(0) }
        assertFailsWith<IllegalArgumentException> { Weight.of(-1, 2) }
        assertFailsWith<IllegalArgumentException> { Weight.of(1, 0) }
        assertFailsWith<IllegalArgumentException> { Weight.of(1, -3) }
    }

    @Test
    fun comparisonThatWouldOverflowThrowsRatherThanWrapping() {
        // a/b vs c/d compares a*d against c*b. With near-MAX numerator and denominator
        // the cross-products overflow Long — the comparator must throw, never wrap to a
        // wrong verdict.
        val huge = Weight.of(Long.MAX_VALUE / 2, 1)
        val alsoHuge = Weight.of(1, 3)
        assertFailsWith<ArithmeticException> { huge.compareTo(alsoHuge) }
    }

    @Test
    fun roundTripsThroughJson() {
        val w = Weight.of(3, 7)
        val encoded = Json.encodeToString(Weight.serializer(), w)
        assertEquals(w, Json.decodeFromString(Weight.serializer(), encoded))
    }

    // ── read-path invariant enforcement (#1647) ─────────────────────────────────
    // `of()` is the only invariant-enforcing construction path, and the wire is a
    // second one. A peer's frame must land on the same canonical value the factory
    // would produce, or `equals`/`compareTo` — which both assume lowest terms and a
    // positive denominator — give wrong answers cluster-wide.

    @Test
    fun deserializationReducesUnreducedComponents() {
        val decoded = Json.decodeFromString(Weight.serializer(), """{"numerator":2,"denominator":4}""")
        assertAll(
            { assertEquals(Weight.of(1, 2), decoded) },
            { assertEquals(Weight.of(1, 2).hashCode(), decoded.hashCode()) },
            { assertEquals(1L, decoded.numerator) },
            { assertEquals(2L, decoded.denominator) },
        )
    }

    @Test
    fun deserializationSignNormalizesRatherThanFlippingSiblingOrder() {
        // -1/-2 *is* 1/2, but stored with a negative denominator the cross-multiplication
        // comparator reports it as GREATER than 1/1 — the ordering flip a corrupt or
        // hostile peer could inject cluster-wide through AttachmentRecord.weight.
        val decoded = Json.decodeFromString(Weight.serializer(), """{"numerator":-1,"denominator":-2}""")
        assertAll(
            { assertEquals(Weight.of(1, 2), decoded) },
            { assertTrue(decoded < Weight.ONE, "1/2 must order below 1/1, was $decoded") },
            { assertTrue(decoded.numerator > 0L, "numerator must be positive, was ${decoded.numerator}") },
            { assertTrue(decoded.denominator > 0L, "denominator must be positive, was ${decoded.denominator}") },
        )
    }

    @Test
    fun deserializationRefusesComponentsThatNameNoWeight() {
        // Sign-normalization repairs -1/-2; nothing repairs a zero, negative, or
        // undefined share, so the frame is refused rather than a fairness claim invented.
        assertAll(
            { assertFailsWith<SerializationException> { decodeWeight("""{"numerator":0,"denominator":1}""") } },
            { assertFailsWith<SerializationException> { decodeWeight("""{"numerator":-1,"denominator":2}""") } },
            { assertFailsWith<SerializationException> { decodeWeight("""{"numerator":1,"denominator":-2}""") } },
            { assertFailsWith<SerializationException> { decodeWeight("""{"numerator":1,"denominator":0}""") } },
        )
    }

    @Test
    fun aDenormalizedWeightInsideAReplicatedAttachmentRecordIsNormalized() {
        // The reachable path: Weight rides the wire inside AttachmentRecord, which is
        // replicated in EntitlementLedger and in the Raft control command Prepare.
        val record = Json.decodeFromString(
            AttachmentRecord.serializer(),
            """{"id":"a","parent":"root","child":"c","weight":{"numerator":-2,"denominator":-4},"initialVirtualTime":0}""",
        )
        assertAll(
            { assertEquals(Weight.of(1, 2), record.weight) },
            { assertTrue(record.weight < Weight.ONE, "decoded sibling weight must order below 1/1") },
            {
                assertEquals(
                    AttachmentRecord(AttachmentId("a"), GroupId("root"), GroupId("c"), Weight.of(1, 2), 0L),
                    record,
                )
            },
        )
    }

    private fun decodeWeight(json: String): Weight = Json.decodeFromString(Weight.serializer(), json)
}
