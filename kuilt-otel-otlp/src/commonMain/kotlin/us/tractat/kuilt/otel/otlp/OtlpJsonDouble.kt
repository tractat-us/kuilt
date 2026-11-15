package us.tractat.kuilt.otel.otlp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * A `double` field of the OTLP/JSON model. Every `Double` in `OtlpJson.kt` is declared as this,
 * never as a bare `Double`, and `OtlpJsonDoubleCoverageTest` reds on one that is not.
 *
 * OTLP/JSON follows the proto3 JSON mapping, which writes a `double` as a JSON number or, when it
 * is not finite, as one of the strings `"NaN"`, `"Infinity"` and `"-Infinity"`. kotlinx's `Json`
 * refuses a non-finite `Double` outright, and with `allowSpecialFloatingPointValues` writes a bare
 * `NaN`, which is not JSON at all; either way one non-finite gauge or overflowed double sum used to
 * cost the whole metric batch, on every drain (#3309).
 */
internal typealias OtlpJsonDouble = @Serializable(with = OtlpJsonDoubleSerializer::class) Double

/**
 * Writes a finite value through [Encoder.encodeDouble], exactly as the default serializer does, so
 * a finite body is byte-identical to the one before #3309, `-0.0` included. Writes a non-finite one
 * as the proto3 JSON mapping's string. Reads either form back, as that mapping requires a parser to.
 */
internal object OtlpJsonDoubleSerializer : KSerializer<Double> {
    private const val NAN = "NaN"
    private const val POSITIVE_INFINITY = "Infinity"
    private const val NEGATIVE_INFINITY = "-Infinity"

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("us.tractat.kuilt.otel.otlp.OtlpJsonDouble", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double) {
        when {
            value.isFinite() -> encoder.encodeDouble(value)
            value.isNaN() -> encoder.encodeString(NAN)
            value > 0.0 -> encoder.encodeString(POSITIVE_INFINITY)
            else -> encoder.encodeString(NEGATIVE_INFINITY)
        }
    }

    override fun deserialize(decoder: Decoder): Double {
        if (decoder !is JsonDecoder) return decoder.decodeDouble()
        val element = decoder.decodeJsonElement()
        if (element !is JsonPrimitive) throw SerializationException("an OTLP/JSON double must be a primitive, was $element")
        val special = if (element.isString) {
            when (element.content) {
                NAN -> Double.NaN
                POSITIVE_INFINITY -> Double.POSITIVE_INFINITY
                NEGATIVE_INFINITY -> Double.NEGATIVE_INFINITY
                else -> null
            }
        } else {
            null
        }
        // Otherwise a number, bare or quoted, as the mapping accepts both; an empty string is
        // invalid. A non-finite result here is a bare `NaN` or an out-of-range literal, neither
        // of which is the mapping's spelling.
        return special
            ?: element.content.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw SerializationException("not an OTLP/JSON double: $element")
    }
}
