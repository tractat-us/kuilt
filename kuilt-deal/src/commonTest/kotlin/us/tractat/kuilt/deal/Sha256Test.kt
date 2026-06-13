package us.tractat.kuilt.deal

import kotlin.test.Test
import kotlin.test.assertEquals

/** NIST test vectors for the internal [sha256] function (FIPS 180-4). */
class Sha256Test {

    @Test
    fun emptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(byteArrayOf()).hex(),
        )
    }

    @Test
    fun asciiAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun nistVector_448BitMessage() {
        // "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun nistVector_oneMillionAs() {
        // SHA-256("a" × 1,000,000) — multi-block, tests padding + schedule extension
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            sha256(ByteArray(1_000_000) { 'a'.code.toByte() }).hex(),
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { b ->
        val v = b.toInt() and 0xFF
        HEX_CHARS[v shr 4].toString() + HEX_CHARS[v and 0xF]
    }

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )
}
