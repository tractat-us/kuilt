package us.tractat.kuilt.heddle

import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * The smallest binary format that writes each collection's size before its elements and reads every
 * structure sequentially: the shape of the size-prefixed format in the kotlinx custom-format guide.
 * Neither JSON nor CBOR as configured here does both, so this is what shows whether a serializer
 * reads a collection's size before its first element. Numbers are big-endian, a string is its UTF-8
 * length then its bytes, an enum is its ordinal, and a nullable value is a `0`/`1` mark first.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object SizePrefixedFormat : BinaryFormat {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val out = ArrayList<Byte>()
        SizePrefixedEncoder(out).encodeSerializableValue(serializer, value)
        return out.toByteArray()
    }

    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        SizePrefixedDecoder(Cursor(bytes), elementsCount = 0).decodeSerializableValue(deserializer)

    /**
     * The bytes left to read, shared by every nested decoder. Reading past the end is a
     * [SerializationException], as it is in a real format, so a misread surfaces as corrupt input.
     */
    private class Cursor(val bytes: ByteArray) {
        var at = 0

        fun take(n: Int): ByteArray {
            if (n < 0 || n > bytes.size - at) throw SerializationException("wants $n bytes at $at of ${bytes.size}")
            return bytes.copyOfRange(at, at + n).also { at += n }
        }

        fun number(width: Int): Long = take(width).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
    }

    private class SizePrefixedEncoder(private val out: MutableList<Byte>) : AbstractEncoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()

        private fun number(value: Long, width: Int) {
            for (shift in width - 1 downTo 0) out += (value shr (8 * shift)).toByte()
        }

        override fun encodeBoolean(value: Boolean) = number(if (value) 1L else 0L, 1)
        override fun encodeByte(value: Byte) = number(value.toLong(), 1)
        override fun encodeShort(value: Short) = number(value.toLong(), 2)
        override fun encodeChar(value: Char) = number(value.code.toLong(), 2)
        override fun encodeInt(value: Int) = number(value.toLong(), 4)
        override fun encodeLong(value: Long) = number(value, 8)
        override fun encodeFloat(value: Float) = encodeInt(value.toBits())
        override fun encodeDouble(value: Double) = encodeLong(value.toBits())
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = encodeInt(index)
        override fun encodeNull() = encodeBoolean(false)
        override fun encodeNotNullMark() = encodeBoolean(true)

        override fun encodeString(value: String) {
            val bytes = value.encodeToByteArray()
            encodeInt(bytes.size)
            out += bytes.asList()
        }

        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
            encodeInt(collectionSize)
            return this
        }
    }

    private class SizePrefixedDecoder(private val input: Cursor, private var elementsCount: Int) : AbstractDecoder() {
        private var elementIndex = 0
        override val serializersModule: SerializersModule = EmptySerializersModule()

        override fun decodeBoolean(): Boolean = input.number(1) != 0L
        override fun decodeByte(): Byte = input.number(1).toByte()
        override fun decodeShort(): Short = input.number(2).toShort()
        override fun decodeChar(): Char = input.number(2).toInt().toChar()
        override fun decodeInt(): Int = input.number(4).toInt()
        override fun decodeLong(): Long = input.number(8)
        override fun decodeFloat(): Float = Float.fromBits(decodeInt())
        override fun decodeDouble(): Double = Double.fromBits(decodeLong())
        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = decodeInt()
        override fun decodeNotNullMark(): Boolean = decodeBoolean()
        override fun decodeString(): String = input.take(decodeInt()).decodeToString()

        override fun decodeSequentially(): Boolean = true

        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = decodeInt().also { elementsCount = it }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
            if (elementIndex == elementsCount) CompositeDecoder.DECODE_DONE else elementIndex++

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
            SizePrefixedDecoder(input, descriptor.elementsCount)
    }
}
