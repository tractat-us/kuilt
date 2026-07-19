package us.tractat.kuilt.nw

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class NwPskTest {

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun determinismProducesByteIdenticalMaterial() {
        val a = NwPsk.derive("swatch-oyster-42", "_kuilt._tcp")
        val b = NwPsk.derive("swatch-oyster-42", "_kuilt._tcp")
        assertAll(
            { assertContentEquals(a.psk, b.psk) },
            { assertContentEquals(a.identity, b.identity) },
            { assertEquals(a, b) },
        )
    }

    @Test
    fun differentRoomKeyYieldsDifferentPsk() {
        val a = NwPsk.derive("swatch-oyster-42", "_kuilt._tcp")
        val b = NwPsk.derive("swatch-oyster-43", "_kuilt._tcp")
        assertFalse(a.psk.contentEquals(b.psk))
    }

    @Test
    fun differentServiceTypeYieldsDifferentPsk() {
        val a = NwPsk.derive("swatch-oyster-42", "_kuilt._tcp")
        val b = NwPsk.derive("swatch-oyster-42", "_other._tcp")
        assertFalse(a.psk.contentEquals(b.psk))
    }

    @Test
    fun pskDiffersFromIdentity() {
        val m = NwPsk.derive("swatch-oyster-42", "_kuilt._tcp")
        assertFalse(m.psk.contentEquals(m.identity))
    }

    @Test
    fun pskIsThirtyTwoBytesAndIdentityIsSixtyFourHexAscii() {
        val m = NwPsk.derive("swatch-oyster-42", "_kuilt._tcp")
        assertAll(
            { assertEquals(32, m.psk.size) },
            // The identity is lowercase-hex ASCII of the 32 derived bytes (#1577): 64 octets, all
            // printable, structurally NUL-free. Raw bytes here would violate RFC 4279 §5.1 and, ~12%
            // of the time, contain a 0x00 that Apple's C-string external-PSK path truncates.
            { assertEquals(64, m.identity.size) },
            { assertTrue(m.identity.all { it in 0x30..0x39 || it in 0x61..0x66 }, "identity must be lowercase hex ASCII") },
        )
    }

    /**
     * Golden vector: pins the exact HKDF-SHA256 output for a fixed input so any
     * future cross-platform HMAC drift is caught (mirrors kuilt-deal's golden
     * vectors). Computed once from the reference implementation.
     */
    @Test
    fun knownAnswerVector() {
        val m = NwPsk.derive("swatch-oyster-42", "_kuilt._tcp")
        assertAll(
            { assertEquals("16a4dda7f5f049c8344d983475f8694fe2c97b85e7eccf7ff2c04137944d8f25", m.psk.toHex()) },
            // Same underlying HKDF output as before #1577 — only the REPRESENTATION changed: the
            // identity now IS this hex string (ASCII), rather than the raw bytes it encodes. The
            // vector's purpose (catching cross-platform HMAC drift) is unchanged.
            { assertEquals("b005a19f5f3fe1e48a3259e3a547ca9ce4d1a41f85587625824e836970c34c87", m.identity.decodeToString()) },
        )
    }
}
