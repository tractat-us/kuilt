package us.tractat.kuilt.deal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureRandomTest {

    @Test
    fun returnsRequestedLength() {
        assertEquals(0, secureRandomBytes(0).size)
        assertEquals(1, secureRandomBytes(1).size)
        assertEquals(64, secureRandomBytes(64).size)
    }

    @Test
    fun successiveCallsDiffer() {
        // Two 64-byte draws colliding by chance is ~2^-512 — a real collision means
        // the platform actual is wired wrong (constant/zero fill), not bad luck.
        val a = secureRandomBytes(64)
        val b = secureRandomBytes(64)
        assertFalse(a.contentEquals(b), "two CSPRNG draws must not be identical")
    }

    @Test
    fun notAllZero() {
        // A 64-byte all-zero draw is ~2^-512 by chance; in practice it flags an
        // actual that fails silently (e.g. an unfilled buffer).
        val bytes = secureRandomBytes(64)
        assertTrue(bytes.any { it != 0.toByte() }, "CSPRNG output must not be all-zero")
    }
}
