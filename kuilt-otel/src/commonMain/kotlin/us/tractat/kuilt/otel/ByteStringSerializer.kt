package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes [ByteString] as a raw byte array.
 *
 * The descriptor deliberately does NOT share the identity of [ByteArraySerializer]'s
 * descriptor — this prevents the CBOR encoder from short-circuiting to a direct
 * `ByteArray` cast before our [serialize] gets to call [ByteArraySerializer] itself.
 * The actual encoding delegates to [ByteArraySerializer], which the CBOR encoder
 * then picks up and encodes as major type 2 (byte string) when
 * `alwaysUseByteString = true`.
 *
 * Value-based equality is preserved by [ByteString] itself — the ORSet keys by
 * spanId correctly because two [ByteString]s wrapping identical bytes are `==`.
 */
internal object ByteStringSerializer : KSerializer<ByteString> {

    private val delegate = ByteArraySerializer()

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.io.bytestring.ByteString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteString) {
        encoder.encodeSerializableValue(delegate, value.toByteArray())
    }

    override fun deserialize(decoder: Decoder): ByteString =
        ByteString(decoder.decodeSerializableValue(delegate))
}
