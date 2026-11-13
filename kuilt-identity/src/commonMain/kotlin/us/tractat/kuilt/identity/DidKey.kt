package us.tractat.kuilt.identity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

@Serializable(with = DidKey.Serializer::class)
@JvmInline
public value class DidKey internal constructor(public val value: String) {
    public fun compressedPoint(): ByteArray = TODO("stub")

    public suspend fun verify(message: ByteArray, signature: ByteArray): Boolean = TODO("stub")

    public companion object {
        public suspend fun parse(value: String): DidKey = TODO("stub")

        internal fun ofCompressedPoint(point: ByteArray): DidKey = TODO("stub")
    }

    internal object Serializer : KSerializer<DidKey> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("us.tractat.kuilt.identity.DidKey", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: DidKey): Unit = encoder.encodeString(value.value)

        override fun deserialize(decoder: Decoder): DidKey = DidKey(decoder.decodeString())
    }
}
