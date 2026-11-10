package us.tractat.kuilt.websocket.tls

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The few DER encodings an X.509 certificate needs, and nothing else.
 *
 * kuilt writes its own certificates rather than taking a dependency for it: the JCA on both the JVM
 * and Android already generates the keys, signs, parses the result and validates the chain, so the
 * one missing piece is *writing* a to-be-signed certificate with a fixed set of extensions. This file
 * is that piece. It never parses — every certificate it helps build is read back by the platform's own
 * `CertificateFactory`, which is the check that what it wrote is well formed.
 */
internal object Der {
    private const val INTEGER = 0x02
    private const val BIT_STRING = 0x03
    private const val OCTET_STRING = 0x04
    private const val OBJECT_IDENTIFIER = 0x06
    private const val UTF8_STRING = 0x0c
    private const val UTC_TIME = 0x17
    private const val GENERALIZED_TIME = 0x18
    private const val BOOLEAN = 0x01
    private const val SEQUENCE = 0x30
    private const val SET = 0x31

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(SEQUENCE, concat(parts.asList()))

    fun sequence(parts: List<ByteArray>): ByteArray = tlv(SEQUENCE, concat(parts))

    fun set(vararg parts: ByteArray): ByteArray = tlv(SET, concat(parts.asList()))

    fun integer(value: BigInteger): ByteArray = tlv(INTEGER, value.toByteArray())

    fun integer(value: Int): ByteArray = integer(BigInteger.valueOf(value.toLong()))

    fun boolean(value: Boolean): ByteArray = tlv(BOOLEAN, byteArrayOf(if (value) 0xff.toByte() else 0))

    fun octetString(content: ByteArray): ByteArray = tlv(OCTET_STRING, content)

    /** A BIT STRING whose bits fill whole bytes: no unused bits in the last one. */
    fun bitString(content: ByteArray): ByteArray = tlv(BIT_STRING, byteArrayOf(0) + content)

    /**
     * A named-bit-list BIT STRING (KeyUsage) from bit positions, bit 0 being the most significant bit
     * of the first byte. DER drops trailing zero bits, so the unused-bits count is the trailing zeros
     * of the last byte.
     */
    fun namedBits(vararg bits: Int): ByteArray {
        val bytes = ByteArray(bits.max() / 8 + 1)
        bits.forEach { bit -> bytes[bit / 8] = (bytes[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte() }
        val unused = Integer.numberOfTrailingZeros(bytes.last().toInt() or 0x100)
        return tlv(BIT_STRING, byteArrayOf(unused.toByte()) + bytes)
    }

    fun utf8String(value: String): ByteArray = tlv(UTF8_STRING, value.encodeToByteArray())

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map(String::toLong)
        require(arcs.size >= 2) { "an object identifier has at least two arcs: $dotted" }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        arcs.drop(2).forEach { arc -> out.write(base128(arc)) }
        return tlv(OBJECT_IDENTIFIER, out.toByteArray())
    }

    /**
     * An X.509 `Time`: UTCTime through 2049 and GeneralizedTime from 2050, which is what RFC 5280
     * §4.1.2.5 requires of a certificate. Whole seconds; the caller has already truncated.
     */
    fun time(date: Date): ByteArray {
        val year = utc("yyyy").format(date).toInt()
        return if (year < 2050) {
            tlv(UTC_TIME, utc("yyMMddHHmmss'Z'").format(date).encodeToByteArray())
        } else {
            tlv(GENERALIZED_TIME, utc("yyyyMMddHHmmss'Z'").format(date).encodeToByteArray())
        }
    }

    /** `[tag] EXPLICIT` — the inner encoding wrapped whole. */
    fun explicit(tag: Int, inner: ByteArray): ByteArray = tlv(0xa0 or tag, inner)

    /** `[tag] IMPLICIT` over a primitive type: the content replaces the universal tag. */
    fun implicitPrimitive(tag: Int, content: ByteArray): ByteArray = tlv(0x80 or tag, content)

    /** `[tag] IMPLICIT` over a constructed type (a SEQUENCE OF): the parts become the content. */
    fun implicitConstructed(tag: Int, parts: List<ByteArray>): ByteArray = tlv(0xa0 or tag, concat(parts))

    private fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    private fun length(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        else -> {
            val bytes = BigInteger.valueOf(size.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            byteArrayOf((0x80 or bytes.size).toByte()) + bytes
        }
    }

    private fun base128(value: Long): ByteArray {
        val groups = generateSequence(value) { (it ushr 7).takeIf { rest -> rest > 0 } }
            .map { (it and 0x7f).toInt() }
            .toList()
            .reversed()
        return ByteArray(groups.size) { i -> (groups[i] or if (i < groups.lastIndex) 0x80 else 0).toByte() }
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun utc(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
}
