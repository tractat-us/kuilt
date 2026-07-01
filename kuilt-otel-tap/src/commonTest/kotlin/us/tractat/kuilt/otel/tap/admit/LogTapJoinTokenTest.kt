package us.tractat.kuilt.otel.tap.admit

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LogTapJoinTokenTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private fun clockAt(instant: Instant) = object : Clock { override fun now(): Instant = instant }

    @Test
    fun issuesADeterministicCodeFromSeededRandom() {
        val a = LogTapJoinToken.issue(Random(42), clockAt(t0), ttl = 5.minutes)
        val b = LogTapJoinToken.issue(Random(42), clockAt(t0), ttl = 5.minutes)
        assertEquals(a.code, b.code) // seeded Random ⇒ reproducible
        assertEquals(8, a.code.length) // 8-char code
        assertTrue(a.code.all { it in CROCKFORD_ALPHABET })
        assertEquals(t0, a.issuedAt)
    }

    @Test
    fun validWithinTtlAndExpiredAfter() {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        assertTrue(token.isValid(t0))
        assertTrue(token.isValid(t0 + 4.minutes))
        assertTrue(token.isValid(t0 + 5.minutes))
        assertFalse(token.isValid(t0 + 5.minutes + 1.seconds))
        assertFalse(token.isValid(t0 - 1.seconds)) // clock skew before issuance
    }

    @Test
    fun toStringDoesNotLeakTheCode() {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        assertFalse(token.toString().contains(token.code))
    }

    private companion object {
        const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
