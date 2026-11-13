package us.tractat.kuilt.identity

/**
 * base58btc, the Bitcoin alphabet, as `did:key` uses it after its multibase `z`. Each leading zero
 * byte is one leading `1`; the rest is the big-endian number written in base 58.
 *
 * Quadratic in the length, which is fine for the 35 bytes of a `did:key`. [decode] takes a
 * `maxBytes` bound and refuses anything that would exceed it, so a long string off the wire is
 * refused after its length is checked rather than ground through.
 */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private val INDEX = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val zeros = bytes.indexOfFirst { it != 0.toByte() }.let { if (it < 0) bytes.size else it }
        // Base-58 digits, least significant first: repeatedly multiply by 256 and add each byte.
        val digits = IntArray(bytes.size * 138 / 100 + 1)
        var length = 0
        for (i in zeros until bytes.size) {
            var carry = bytes[i].toInt() and 0xff
            for (j in 0 until length) {
                carry += digits[j] shl 8
                digits[j] = carry % 58
                carry /= 58
            }
            while (carry > 0) {
                digits[length++] = carry % 58
                carry /= 58
            }
        }
        return buildString(zeros + length) {
            repeat(zeros) { append('1') }
            for (j in length - 1 downTo 0) append(ALPHABET[digits[j]])
        }
    }

    /** The bytes [text] encodes, or `null` when it holds a character outside the alphabet or more than [maxBytes] bytes. */
    fun decode(text: String, maxBytes: Int): ByteArray? {
        val zeros = text.indexOfFirst { it != '1' }.let { if (it < 0) text.length else it }
        if (zeros > maxBytes) return null
        // Bytes, least significant first: repeatedly multiply by 58 and add each digit.
        val bytes = IntArray(maxBytes)
        var length = 0
        for (i in zeros until text.length) {
            val code = text[i].code
            var carry = if (code < INDEX.size) INDEX[code] else -1
            if (carry < 0) return null
            for (j in 0 until length) {
                carry += bytes[j] * 58
                bytes[j] = carry and 0xff
                carry = carry shr 8
            }
            while (carry > 0) {
                if (zeros + length == maxBytes) return null
                bytes[length++] = carry and 0xff
                carry = carry shr 8
            }
        }
        return ByteArray(zeros + length).also { out ->
            for (j in 0 until length) out[zeros + length - 1 - j] = bytes[j].toByte()
        }
    }
}
