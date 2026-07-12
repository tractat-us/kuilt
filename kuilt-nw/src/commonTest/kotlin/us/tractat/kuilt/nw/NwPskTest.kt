package us.tractat.kuilt.nw

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
    fun pskAndIdentityAreThirtyTwoBytes() {
        val m = NwPsk.derive("swatch-oyster-42", "_kuilt._tcp")
        assertAll(
            { assertEquals(32, m.psk.size) },
            { assertEquals(32, m.identity.size) },
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
            { assertEquals("b005a19f5f3fe1e48a3259e3a547ca9ce4d1a41f85587625824e836970c34c87", m.identity.toHex()) },
        )
    }
}
