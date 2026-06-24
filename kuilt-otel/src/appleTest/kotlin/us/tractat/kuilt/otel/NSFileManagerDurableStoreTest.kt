@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Crash-recovery and correctness tests for [NSFileManagerDurableStore].
 *
 * Each test creates a fresh temporary directory under `NSTemporaryDirectory()` so
 * there is no cross-test state. The crash-recovery test verifies the durability
 * contract by constructing a second store instance over the *same directory* and
 * confirming the previously-written bytes are returned — simulating a process
 * restart.
 */
class NSFileManagerDurableStoreTest {

    private fun tempDir(): String {
        val base = NSTemporaryDirectory()
        val dir = base + "kuilt-otel-test-${kotlin.random.Random.nextLong()}/"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    @Test
    fun crashRecoveryRoundTrip() = runTest {
        val dir = tempDir()
        val key = StoreKey("spans")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        // First store instance — write then let it go out of scope (simulates process exit).
        NSFileManagerDurableStore(dir).write(key, bytes)

        // Second store instance over the same directory — simulates process restart.
        val recovered = NSFileManagerDurableStore(dir).read(key)
        assertContentEquals(bytes, recovered)
    }

    @Test
    fun readReturnsNullForAbsentKey() = runTest {
        val store = NSFileManagerDurableStore(tempDir())
        assertNull(store.read(StoreKey("never-written")))
    }

    @Test
    fun overwriteReturnsLatestBytes() = runTest {
        val dir = tempDir()
        val key = StoreKey("k")
        val store = NSFileManagerDurableStore(dir)
        store.write(key, byteArrayOf(1))
        store.write(key, byteArrayOf(2, 3))
        assertContentEquals(byteArrayOf(2, 3), store.read(key))
    }

    @Test
    fun deleteRemovesKey() = runTest {
        val dir = tempDir()
        val key = StoreKey("k")
        val store = NSFileManagerDurableStore(dir)
        store.write(key, byteArrayOf(42))
        store.delete(key)
        assertNull(store.read(key))
    }

    @Test
    fun deleteOfAbsentKeyIsNoOp() = runTest {
        val store = NSFileManagerDurableStore(tempDir())
        // Must not throw.
        store.delete(StoreKey("never-written"))
    }

    @Test
    fun readReturnsCopyNotReference() = runTest {
        val dir = tempDir()
        val key = StoreKey("k")
        val store = NSFileManagerDurableStore(dir)
        store.write(key, byteArrayOf(1, 2, 3))
        val read = store.read(key)!!
        read[0] = 99
        // A fresh read must still return the original bytes.
        assertEquals(1, store.read(key)!![0])
    }

    @Test
    fun keyWithSpecialCharsSanitisedSafely() = runTest {
        val dir = tempDir()
        val key = StoreKey("otel/spans.v1")
        val bytes = byteArrayOf(7, 8, 9)
        val store = NSFileManagerDurableStore(dir)
        store.write(key, bytes)
        assertContentEquals(bytes, store.read(key))
    }

    @Test
    fun independentKeysDoNotInterfere() = runTest {
        val dir = tempDir()
        val store = NSFileManagerDurableStore(dir)
        val k1 = StoreKey("alpha")
        val k2 = StoreKey("beta")
        store.write(k1, byteArrayOf(1))
        store.write(k2, byteArrayOf(2))
        assertEquals(1, store.read(k1)!![0])
        assertEquals(2, store.read(k2)!![0])
    }
}
