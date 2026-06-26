package us.tractat.kuilt.warp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreelTest {

    @Test
    fun putAndGetRoundTrip() {
        val creel = Creel()
        val bytes = byteArrayOf(10, 20, 30, 40)
        val hash = creel.put(bytes)
        assertContentEquals(bytes, creel.get(hash), "get should return byte-identical content")
    }

    @Test
    fun putIsIdempotent() {
        val creel = Creel()
        val bytes = byteArrayOf(1, 2, 3)
        val hash1 = creel.put(bytes)
        val hash2 = creel.put(bytes)
        assertEquals(hash1, hash2, "identical bytes must produce the same hash")
        assertEquals(1, creel.loaded.size, "a second put of identical bytes must not grow loaded")
    }

    @Test
    fun getUnknownHashReturnsNull() {
        val creel = Creel()
        val unknown = BobbinHash("0000000000000000")
        assertNull(creel.get(unknown), "a hash not in the creel is the legitimate 'not loaded yet' state")
    }

    @Test
    fun containsReflectsLoadedState() {
        val creel = Creel()
        val unknown = BobbinHash("0000000000000000")
        assertFalse(creel.contains(unknown))
        val hash = creel.put(byteArrayOf(9, 8, 7))
        assertTrue(creel.contains(hash))
    }

    @Test
    fun loadedSetGrowsOnNewPut() {
        val creel = Creel()
        creel.put(byteArrayOf(1))
        creel.put(byteArrayOf(2))
        assertEquals(2, creel.loaded.size)
    }

    @Test
    fun putVerifiedSucceedsOnMatchingHash() {
        val creel = Creel()
        val bytes = byteArrayOf(7, 6, 5, 4)
        val hash = creel.put(bytes)
        // must not throw
        creel.putVerified(hash, bytes)
    }

    @Test
    fun putVerifiedThrowsOnHashMismatch() {
        val creel = Creel()
        val bytes = byteArrayOf(7, 6, 5, 4)
        val wrong = BobbinHash("deadbeefdeadbeef")
        assertFailsWith<IllegalArgumentException> {
            creel.putVerified(wrong, bytes)
        }
    }

    @Test
    fun putVerifiedStoresOnFirstCallWithCorrectHash() {
        val creel = Creel()
        val bytes = byteArrayOf(3, 1, 4, 1, 5)
        val expected = creel.put(bytes)
        val fresh = Creel()
        fresh.putVerified(expected, bytes)
        assertContentEquals(bytes, fresh.get(expected))
    }

    /**
     * Cross-target hash golden vector. Pins FNV-1a-64 determinism across
     * JVM, Android, iOS, macOS, and wasmJs — the hash key crosses the fabric
     * so it must be byte-identical on every platform.
     *
     * Input  : byteArrayOf(1, 2, 3, 4, 5)
     * Expected: 0f66dcbf4f6b7d88  (FNV-1a-64, verified against canonical spec)
     */
    @Test
    fun hashGoldenVector() {
        val creel = Creel()
        val hash = creel.put(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(
            "0f66dcbf4f6b7d88",
            hash.value,
            "FNV-1a-64 of [1,2,3,4,5] must be identical on every KMP target",
        )
    }

    @Test
    fun getReturnsCopyNotStoredReference() {
        val creel = Creel()
        val original = byteArrayOf(11, 22, 33)
        val hash = creel.put(original)
        val fetched = creel.get(hash)!!
        fetched[0] = 99
        assertContentEquals(byteArrayOf(11, 22, 33), creel.get(hash), "external mutation must not corrupt stored content")
    }
}
