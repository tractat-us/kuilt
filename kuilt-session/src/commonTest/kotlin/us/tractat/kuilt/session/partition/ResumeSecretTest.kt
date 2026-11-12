@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.session.partition

import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResumeSecretTest {
    private fun secretOf(fill: Int) = ResumeSecret(ByteArray(ResumeSecret.SIZE_BYTES) { fill.toByte() })

    @Test
    fun `a secret must be exactly 32 bytes`() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { ResumeSecret(ByteArray(31)) } },
            { assertFailsWith<IllegalArgumentException> { ResumeSecret(ByteArray(33)) } },
        )
    }

    @Test
    fun `matches compares content and a single differing byte anywhere fails it`() {
        val base = ByteArray(ResumeSecret.SIZE_BYTES) { it.toByte() }
        val lastDiffers = base.copyOf().also { it[ResumeSecret.SIZE_BYTES - 1] = 99 }
        assertAll(
            { assertTrue(ResumeSecret(base).matches(ResumeSecret(base.copyOf()))) },
            { assertFalse(ResumeSecret(base).matches(ResumeSecret(lastDiffers))) },
            { assertEquals(ResumeSecret(base), ResumeSecret(base.copyOf())) },
            { assertEquals(ResumeSecret(base).hashCode(), ResumeSecret(base.copyOf()).hashCode()) },
        )
    }

    @Test
    fun `the secret is copied in so a caller mutating its array cannot change it`() {
        val bytes = ByteArray(ResumeSecret.SIZE_BYTES) { 1 }
        val secret = ResumeSecret(bytes)
        bytes[0] = 2
        assertEquals(secretOf(1), secret)
    }

    @Test
    fun `generated secrets differ`() {
        assertNotEquals(ResumeSecret.generate(), ResumeSecret.generate())
    }

    @Test
    fun `toString never prints the secret and neither does a token holding one`() {
        val secret = secretOf(0xAB)
        val token = ResumeToken(PeerId("p"), RoomId("r"), issuedAt = 0L, secret = secret)
        assertAll(
            { assertFalse("ab" in secret.toString().lowercase()) },
            { assertFalse("abab" in token.toString().lowercase(), "token toString leaked: $token") },
        )
    }

    @Test
    fun `a token round-trips through serialization with its secret`() {
        val token = ResumeToken(PeerId("p"), RoomId("room-1"), issuedAt = 42L, secret = ResumeSecret.generate())
        val decoded = Cbor.decodeFromByteArray<ResumeToken>(Cbor.encodeToByteArray(token))
        assertEquals(token, decoded)
    }

    @Test
    fun `decoding refuses a secret of the wrong width`() {
        val short = Cbor.encodeToByteArray("ab".repeat(ResumeSecret.SIZE_BYTES - 1))
        assertFailsWith<SerializationException> { Cbor.decodeFromByteArray<ResumeSecret>(short) }
    }
}
