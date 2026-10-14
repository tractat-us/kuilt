package us.tractat.kuilt.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A permitted backend schedule, not a requirement that every store commit cancelled writes. */
class IndeterminateWriteTest {
    @Test
    fun cancellationCanPrecedeDurableVisibility() = runTest {
        val backing = InMemoryDurableStore()
        val key = StoreKey("pending")
        val old = byteArrayOf(1)
        val next = byteArrayOf(2)
        backing.write(key, old)
        val finishTransaction = CompletableDeferred<Unit>()
        val acknowledgement = CompletableDeferred<Unit>()
        var started = 0
        var committed = 0
        // The transaction belongs to the backend, not to the coroutine awaiting its result.
        val store = object : DurableStore by backing {
            override suspend fun write(key: StoreKey, bytes: ByteArray) {
                started++
                launch {
                    finishTransaction.await()
                    backing.write(key, bytes)
                    committed++
                    acknowledgement.complete(Unit)
                }
                acknowledgement.await()
            }
        }
        val writer = launch(start = CoroutineStart.UNDISPATCHED) { store.write(key, next) }
        writer.cancelAndJoin()
        val atCancellation = backing.read(key)
        val commitsAtCancellation = committed
        finishTransaction.complete(Unit)
        acknowledgement.await()
        val afterCommit = backing.read(key)
        assertAll(
            { assertEquals(1, started, "the transaction must have started") },
            { assertTrue(writer.isCancelled, "the caller must observe cancellation") },
            { assertEquals(0, commitsAtCancellation, "commit must follow cancellation") },
            { assertContentEquals(old, atCancellation, "even read-back can still see the old value") },
            { assertEquals(1, committed, "the late-commit injection must fire") },
            { assertContentEquals(next, afterCommit) },
        )
    }
}
