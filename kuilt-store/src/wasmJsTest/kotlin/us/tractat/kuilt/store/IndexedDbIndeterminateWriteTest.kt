package us.tractat.kuilt.store

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexedDbIndeterminateWriteTest {
    @Test
    fun abandoningTheAwaitDoesNotAbortTheTransaction() = runTest {
        val name = "kuilt-indeterminate-write"
        deleteDatabase(name)
        val store = IndexedDbDurableStore.open(name)
        try {
            val key = StoreKey("candidate")
            val bytes = byteArrayOf(3, 1, 4)
            var acknowledged = false
            // Start the put in this JS turn, before any IDB callback can acknowledge it.
            val writer = launch(start = CoroutineStart.UNDISPATCHED) {
                store.write(key, bytes)
                acknowledged = true
            }
            val awaiting = writer.isActive
            writer.cancelAndJoin()
            val reopened = IndexedDbDurableStore.open(name)
            val landed = try { reopened.read(key) } finally { reopened.close() }
            assertAll(
                { assertTrue(awaiting, "write must reach its incomplete transaction await") },
                { assertTrue(writer.isCancelled) },
                { assertFalse(acknowledged, "the cancelled caller must not receive acknowledgement") },
                { assertContentEquals(bytes, landed, "the real transaction must still land") },
            )
        } finally {
            store.close()
            deleteDatabase(name)
        }
    }
}
