package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Crash-recovery tests for [FileChannelDurableStore].
 *
 * "Crash" is simulated by constructing a second [FileChannelDurableStore] instance
 * over the same directory — there is no shared in-memory state, so the second
 * instance must read everything from disk.
 */
class FileChannelDurableStoreTest {

    private fun tempStore(): FileChannelDurableStore =
        FileChannelDurableStore(createTempDirectory("kuilt-otel-test").toFile())

    private fun storeAt(dir: java.io.File): FileChannelDurableStore =
        FileChannelDurableStore(dir)

    // ---- missing key → null ----

    @Test
    fun readReturnsNullForAbsentKey() = runTest {
        assertNull(tempStore().read(StoreKey("missing")))
    }

    // ---- write then read ----

    @Test
    fun writeAndReadRoundTrips() = runTest {
        val store = tempStore()
        val key = StoreKey("key")
        val bytes = byteArrayOf(1, 2, 3)
        store.write(key, bytes)
        assertContentEquals(bytes, store.read(key))
    }

    // ---- crash recovery ----

    @Test
    fun crashRecoveryRoundTrip() = runTest {
        val dir = createTempDirectory("kuilt-otel-crash").toFile()
        val key = StoreKey("spans")
        val bytes = byteArrayOf(10, 20, 30)

        // Write with first store instance.
        storeAt(dir).write(key, bytes)

        // Simulate restart: brand-new instance, same directory.
        val recovered = storeAt(dir).read(key)
        assertContentEquals(bytes, recovered)
    }

    // ---- overwrite ----

    @Test
    fun secondWriteOverwritesFirst() = runTest {
        val dir = createTempDirectory("kuilt-otel-overwrite").toFile()
        val key = StoreKey("k")
        storeAt(dir).write(key, byteArrayOf(1))
        storeAt(dir).write(key, byteArrayOf(2))
        assertContentEquals(byteArrayOf(2), storeAt(dir).read(key))
    }

    // ---- overwrite survives simulated restart ----

    @Test
    fun overwritePersistedAfterRestart() = runTest {
        val dir = createTempDirectory("kuilt-otel-overwrite2").toFile()
        val key = StoreKey("k")
        storeAt(dir).write(key, byteArrayOf(1))
        storeAt(dir).write(key, byteArrayOf(99))

        val recovered = FileChannelDurableStore(dir).read(key)
        assertContentEquals(byteArrayOf(99), recovered)
    }

    // ---- delete ----

    @Test
    fun deleteRemovesKey() = runTest {
        val store = tempStore()
        val key = StoreKey("k")
        store.write(key, byteArrayOf(42))
        store.delete(key)
        assertNull(store.read(key))
    }

    @Test
    fun deleteIdsNoOpForAbsentKey() = runTest {
        // No exception thrown when deleting a key that never existed.
        tempStore().delete(StoreKey("ghost"))
    }

    @Test
    fun deletePersistedAfterRestart() = runTest {
        val dir = createTempDirectory("kuilt-otel-delete").toFile()
        val key = StoreKey("k")
        storeAt(dir).write(key, byteArrayOf(1))
        storeAt(dir).delete(key)

        assertNull(FileChannelDurableStore(dir).read(key))
    }

    // ---- multiple keys are independent ----

    @Test
    fun multipleKeysAreIndependent() = runTest {
        val dir = createTempDirectory("kuilt-otel-multi").toFile()
        val k1 = StoreKey("a")
        val k2 = StoreKey("b")
        storeAt(dir).write(k1, byteArrayOf(1))
        storeAt(dir).write(k2, byteArrayOf(2))

        val store = FileChannelDurableStore(dir)
        assertContentEquals(byteArrayOf(1), store.read(k1))
        assertContentEquals(byteArrayOf(2), store.read(k2))
    }

    // ---- key sanitization: a key with unusual chars stays distinct from another ----

    @Test
    fun keysWithSimilarNamesDontCollide() = runTest {
        val dir = createTempDirectory("kuilt-otel-sanitize").toFile()
        val k1 = StoreKey("otel.spans")
        val k2 = StoreKey("otel.metrics")
        storeAt(dir).write(k1, byteArrayOf(10))
        storeAt(dir).write(k2, byteArrayOf(20))

        val store = FileChannelDurableStore(dir)
        assertContentEquals(byteArrayOf(10), store.read(k1))
        assertContentEquals(byteArrayOf(20), store.read(k2))
    }

    // ---- read returns defensive copy ----

    @Test
    fun readReturnsCopy() = runTest {
        val store = tempStore()
        val key = StoreKey("k")
        store.write(key, byteArrayOf(1, 2, 3))
        val first = requireNotNull(store.read(key)) { "expected non-null bytes after write" }
        first[0] = 99
        assertContentEquals(byteArrayOf(1, 2, 3), store.read(key))
    }

    // ---- large payload ----

    @Test
    fun largePayloadSurvivesRoundTrip() = runTest {
        val dir = createTempDirectory("kuilt-otel-large").toFile()
        val key = StoreKey("big")
        val big = ByteArray(512 * 1024) { it.toByte() } // 512 KiB
        storeAt(dir).write(key, big)
        assertContentEquals(big, FileChannelDurableStore(dir).read(key))
    }

    // ---- empty payload ----

    @Test
    fun emptyPayloadRoundTrips() = runTest {
        val store = tempStore()
        val key = StoreKey("empty")
        store.write(key, ByteArray(0))
        val read = store.read(key)
        assertEquals(0, read?.size)
    }
}
