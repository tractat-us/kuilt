@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Browser-only round-trip tests for [IndexedDbDurableStore].
 *
 * Each test uses a unique database name so tests are isolated and
 * cannot interfere with each other through shared IDB state.
 *
 * The "crash-recovery" model is simulated by closing the first store
 * instance and opening a fresh one against the same database name —
 * this exercises the durability contract: bytes survive across
 * store-object lifetimes (and, by extension, process restarts).
 */
class IndexedDbDurableStoreTest {

    // ---- helpers ----

    private var dbCounter = 0

    /** Each call returns a fresh unique DB name so tests don't share state. */
    private fun uniqueDb(): String = "kuilt-otel-test-${dbCounter++}"

    // ---- read returns null for absent key ----

    @Test
    fun missingKeyReturnsNull() = runTest {
        val store = IndexedDbDurableStore.open(uniqueDb())
        assertNull(store.read(StoreKey("absent")))
        store.close()
    }

    // ---- write then read in same instance ----

    @Test
    fun writeAndReadInSameInstance() = runTest {
        val store = IndexedDbDurableStore.open(uniqueDb())
        val key = StoreKey("k")
        val bytes = byteArrayOf(10, 20, 30)
        store.write(key, bytes)
        assertContentEquals(bytes, store.read(key))
        store.close()
    }

    // ---- crash-recovery round-trip ----

    @Test
    fun crashRecoveryRoundTrip() = runTest {
        val dbName = uniqueDb()
        val key = StoreKey("span-state")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        // First store instance: write and close (simulates process exit after durable commit).
        val store1 = IndexedDbDurableStore.open(dbName)
        store1.write(key, bytes)
        store1.close()

        // Second store instance against the same DB: simulates a process restart.
        val store2 = IndexedDbDurableStore.open(dbName)
        assertContentEquals(bytes, store2.read(key))
        store2.close()
    }

    // ---- overwrite replaces previous value ----

    @Test
    fun overwriteReplacesValue() = runTest {
        val dbName = uniqueDb()
        val key = StoreKey("k")
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(9, 8, 7, 6)

        val store1 = IndexedDbDurableStore.open(dbName)
        store1.write(key, first)
        store1.close()

        val store2 = IndexedDbDurableStore.open(dbName)
        store2.write(key, second)
        store2.close()

        val store3 = IndexedDbDurableStore.open(dbName)
        assertContentEquals(second, store3.read(key))
        store3.close()
    }

    // ---- delete removes the key ----

    @Test
    fun deleteRemovesKey() = runTest {
        val dbName = uniqueDb()
        val key = StoreKey("k")

        val store1 = IndexedDbDurableStore.open(dbName)
        store1.write(key, byteArrayOf(42))
        store1.close()

        val store2 = IndexedDbDurableStore.open(dbName)
        store2.delete(key)
        store2.close()

        val store3 = IndexedDbDurableStore.open(dbName)
        assertNull(store3.read(key))
        store3.close()
    }

    // ---- delete is a no-op for absent keys ----

    @Test
    fun deleteIsNoOpForAbsentKey() = runTest {
        val store = IndexedDbDurableStore.open(uniqueDb())
        // Must not throw.
        store.delete(StoreKey("nonexistent"))
        store.close()
    }

    // ---- multiple keys are stored independently ----

    @Test
    fun multipleKeysAreIndependent() = runTest {
        val dbName = uniqueDb()
        val keyA = StoreKey("a")
        val keyB = StoreKey("b")
        val bytesA = byteArrayOf(1)
        val bytesB = byteArrayOf(2)

        val store1 = IndexedDbDurableStore.open(dbName)
        store1.write(keyA, bytesA)
        store1.write(keyB, bytesB)
        store1.close()

        val store2 = IndexedDbDurableStore.open(dbName)
        assertContentEquals(bytesA, store2.read(keyA))
        assertContentEquals(bytesB, store2.read(keyB))
        store2.close()
    }

    // ---- byte integrity: all 256 byte values survive round-trip ----

    @Test
    fun allByteValuesRoundTrip() = runTest {
        val dbName = uniqueDb()
        val key = StoreKey("full-range")
        val bytes = ByteArray(256) { it.toByte() }

        val store1 = IndexedDbDurableStore.open(dbName)
        store1.write(key, bytes)
        store1.close()

        val store2 = IndexedDbDurableStore.open(dbName)
        val recovered = store2.read(key)!!
        assertEquals(256, recovered.size)
        assertContentEquals(bytes, recovered)
        store2.close()
    }
}
