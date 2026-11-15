package us.tractat.kuilt.otel.otlp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** #3309: the OTLP/JSON `double` spelling, and that every `double` in the model uses it. */
class OtlpJsonDoubleTest {

    private val json = Json { encodeDefaults = false }

    private val finite = listOf(0.0, -0.0, 0.5, 3.0, -2.25, 1e-9, 1e308, Double.MAX_VALUE, Double.MIN_VALUE, -Double.MAX_VALUE)
    private val nonFinite = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

    @Test
    fun finiteValuesEncodeExactlyAsTheDefaultDoubleSerializerDoes() {
        assertAll(
            *finite.map { v ->
                {
                    assertEquals(
                        json.encodeToString(Double.serializer(), v),
                        json.encodeToString(OtlpJsonDoubleSerializer, v),
                        "finite $v must keep the bytes it had before #3309",
                    )
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun nonFiniteValuesEncodeAsTheProto3JsonMappingStrings() {
        assertAll(
            { assertEquals("\"NaN\"", json.encodeToString(OtlpJsonDoubleSerializer, Double.NaN)) },
            { assertEquals("\"Infinity\"", json.encodeToString(OtlpJsonDoubleSerializer, Double.POSITIVE_INFINITY)) },
            { assertEquals("\"-Infinity\"", json.encodeToString(OtlpJsonDoubleSerializer, Double.NEGATIVE_INFINITY)) },
        )
    }

    @Test
    fun everyValueRoundTripsBitForBitThroughADataPoint() {
        assertAll(
            *(finite + nonFinite).map { v ->
                {
                    val point = NumberDataPoint(timeUnixNano = "1", asDouble = v)
                    val body = json.encodeToString(NumberDataPoint.serializer(), point)
                    val back = json.decodeFromString(NumberDataPoint.serializer(), body)
                    assertEquals(v.toRawBits(), back.asDouble?.toRawBits(), "$v via $body")
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun theDecoderAcceptsAQuotedNumberAndRefusesWhatTheMappingDoesNot() {
        val point = NumberDataPoint.serializer()
        assertAll(
            { assertEquals(1.5, json.decodeFromString(point, """{"timeUnixNano":"1","asDouble":"1.5"}""").asDouble) },
            { assertFailsWith<SerializationException> { json.decodeFromString(point, """{"timeUnixNano":"1","asDouble":""}""") } },
            { assertFailsWith<SerializationException> { json.decodeFromString(point, """{"timeUnixNano":"1","asDouble":"nan"}""") } },
            { assertFailsWith<SerializationException> { json.decodeFromString(point, """{"timeUnixNano":"1","asDouble":"1e999"}""") } },
        )
    }

    /**
     * The structural half: a `double` added to the model as a bare `Double` would bring #3309
     * back for that field alone. Walks every request envelope's descriptor tree and names any
     * DOUBLE element whose serializer is not [OtlpJsonDoubleSerializer].
     */
    @Test
    fun everyDoubleInTheOtlpJsonModelUsesTheOtlpSpelling() {
        val roots = listOf(MetricsRequest.serializer().descriptor, TracesRequest.serializer().descriptor, LogsRequest.serializer().descriptor)
        val doubles = roots.flatMap { doublesUnder(it, mutableSetOf()) }
        val bare = doubles.filter { it.second != OtlpJsonDoubleSerializer.descriptor.serialName }
        assertAll(
            // The rig: the walk reaches the two doubles the model has today, so an empty `bare`
            // is not an empty walk.
            { assertTrue(doubles.map { it.first }.containsAll(listOf("NumberDataPoint.asDouble", "OtlpExponentialHistogramDataPoint.zeroThreshold")), "$doubles") },
            { assertTrue(bare.isEmpty(), "bare Double fields in the OTLP/JSON model: $bare") },
        )
    }

    /** (`Owner.field`, the field serializer's serial name) for every DOUBLE element reachable from [d]. */
    private fun doublesUnder(d: SerialDescriptor, seen: MutableSet<SerialDescriptor>): List<Pair<String, String>> {
        // Keyed on the descriptor, not its serial name: every List shares one serial name.
        if (!seen.add(d)) return emptyList()
        return (0 until d.elementsCount).flatMap { i ->
            val child = d.getElementDescriptor(i)
            if (child.kind == PrimitiveKind.DOUBLE) {
                listOf("${d.serialName.substringAfterLast('.')}.${d.getElementName(i)}" to child.serialName.removeSuffix("?"))
            } else {
                doublesUnder(child, seen)
            }
        }
    }
}
