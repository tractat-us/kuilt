package us.tractat.kuilt.identity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A host's revocations kept in a [DurableStore], so a restart does not let a revoked device back in on
 * its old pass. Every "after a restart" here is a second [RevocationList.open] on the same store: the
 * first list is never read again, so nothing it holds in memory can stand in for what reached the
 * store.
 */
class RevocationListPersistenceTest {
    @Test
    fun revocationSurvivesReopen() = runTest {
        val store = InMemoryDurableStore()
        val list = RevocationList.open(store)
        list.revoke(PHONE, notBefore = 1_000)
        list.revoke(LAPTOP, notBefore = 3_000)

        val restarted = RevocationList.open(store)
        assertAll(
            { assertEquals(1_000L, restarted.notBefore(PHONE)) },
            { assertEquals(3_000L, restarted.notBefore(LAPTOP)) },
            { assertNull(restarted.notBefore(TABLET), "a subject never revoked stays unrevoked") },
        )
    }

    @Test
    fun theKeyIsTheCallersChoice() = runTest {
        // Two lists in one store under different keys are two lists.
        val store = InMemoryDurableStore()
        RevocationList.open(store, StoreKey("room-a.revocations")).revoke(PHONE, notBefore = 1_000)

        val sameKey = RevocationList.open(store, StoreKey("room-a.revocations"))
        val otherKey = RevocationList.open(store, StoreKey("room-b.revocations"))
        val defaultKey = RevocationList.open(store)
        assertAll(
            { assertEquals(1_000L, sameKey.notBefore(PHONE)) },
            { assertNull(otherKey.notBefore(PHONE)) },
            { assertNull(defaultKey.notBefore(PHONE)) },
        )
    }

    @Test
    fun aLostStoreForgetsEveryRevocation() = runTest {
        // The spec's first failure mode, and the dangerous direction: a store that comes back empty
        // is indistinguishable from a host that never revoked anyone, so a revoked device returns on
        // its old pass. Pinned so it stays a documented property rather than a surprise.
        val store = InMemoryDurableStore()
        RevocationList.open(store).revoke(PHONE, notBefore = 1_000)
        val before = RevocationList.open(store).notBefore(PHONE)
        store.delete(RevocationList.DEFAULT_STORE_KEY)

        val afterLoss = RevocationList.open(store)
        assertAll(
            { assertEquals(1_000L, before, "the revocation was in the store before it was lost") },
            { assertNull(afterLoss.notBefore(PHONE), "a lost store opens as an empty list") },
        )
    }

    @Test
    fun aLostHostKeyIsTheSafeDirection() = runTest {
        // The spec's second failure mode. The revocations survive, but the host's key does not: every
        // pass it issued is now from an issuer it does not trust, so the device is vetted again.
        val store = InMemoryDurableStore()
        val oldHost = DeviceKey.generate()
        val phone = DeviceKey.generate()
        val pass = Grant.issue(oldHost, phone.id, GrantScope.AnyRoomOfIssuer, issuedAt = 1_000, notAfter = Grant.NO_EXPIRY)
        RevocationList.open(store).revoke(phone.id, notBefore = 500)

        val newHost = DeviceKey.generate()
        val restarted = RevocationList.open(store)
        val verdict = GrantTrust(newHost.id, restarted).verify(pass, phone.id, "table-7", now = 2_000)
        assertAll(
            { assertEquals(500L, restarted.notBefore(phone.id), "the revocations came back") },
            { assertEquals(GrantVerdict.UntrustedIssuer, verdict) },
        )
    }

    @Test
    fun corruptStoredListFailsLoudly() = runTest {
        // Opening a damaged list as empty would forget every revocation without a word, which is the
        // dangerous direction; so it refuses, names the key, and leaves the bytes for a person to see.
        val key = StoreKey("host-7.revocations")
        val entry = POINT + CUTOFF_BYTES
        val corrupt = mapOf(
            "empty" to byteArrayOf(),
            "foreign" to "not a revocation list".encodeToByteArray(),
            "unknown version" to MAGIC + byteArrayOf(0x02) + entry,
            "truncated entry" to HEADER + entry.copyOf(entry.size - 1),
            "uncompressed lead byte" to HEADER + byteArrayOf(0x04) + POINT.copyOfRange(1, POINT.size) + CUTOFF_BYTES,
            "one subject twice" to HEADER + entry + entry,
        )
        val outcomes = corrupt.map { (name, bytes) ->
            val store = InMemoryDurableStore()
            store.write(key, bytes)
            val failure = assertFailsWith<IllegalStateException>(name) { RevocationList.open(store, key) }
            Triple(name, failure.message.orEmpty(), store.read(key))
        }
        assertAll(
            { assertEquals(corrupt.size, outcomes.size, "every corrupt blob was opened") },
            *outcomes.map { (name, message, left) ->
                {
                    assertContains(message, key.name, message = "$name: the message names the key")
                    assertContentEquals(corrupt.getValue(name), left, "$name: the bytes are left as they were")
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun anUnreadableStoreFailsTheOpen() = runTest {
        // A store that cannot be read is not an empty one: the failure reaches the caller unchanged.
        val unavailable = StoreUnavailable()
        val store = object : DurableStore by InMemoryDurableStore() {
            override suspend fun read(key: StoreKey): ByteArray? = throw unavailable
        }
        val failure = assertFailsWith<StoreUnavailable> { RevocationList.open(store) }
        assertSame(unavailable, failure)
    }

    @Test
    fun revokeReturnsAfterTheWriteCommits() = runTest {
        val store = HeldFirstWriteStore()
        val list = RevocationList.open(store)

        val revoking = launch { list.revoke(PHONE, notBefore = 1_000) }
        runCurrent()
        val writesStartedWhileHeld = store.writesStarted
        val returnedWhileHeld = revoking.isCompleted
        val storedWhileHeld = store.inner.read(RevocationList.DEFAULT_STORE_KEY)

        store.release()
        revoking.join()
        val stored = RevocationList.open(store.inner).notBefore(PHONE)
        assertAll(
            { assertEquals(1, writesStartedWhileHeld, "the rig: the write reached the store and is held there") },
            { assertNull(storedWhileHeld, "the rig: nothing is committed while the write is held") },
            { assertTrue(!returnedWhileHeld, "revoke has not returned while its write is uncommitted") },
            { assertEquals(1_000L, stored, "and the write it waited for landed") },
        )
    }

    @Test
    fun concurrentRevokesNeverLoseOneInTheStore() = runTest {
        // Two revokes at once. Each writes the whole list, so if the first could compute its list,
        // let the second compute and write a newer one, and then write its own older one last, the
        // store would end up without the second revocation. The first write is held in the store
        // until the second revoke has had every chance to run.
        val store = HeldFirstWriteStore()
        val list = RevocationList.open(store)

        val first = launch { list.revoke(PHONE, notBefore = 1_000) }
        runCurrent()
        val firstHeld = store.writesStarted
        val second = launch { list.revoke(LAPTOP, notBefore = 2_000) }
        runCurrent()

        store.release()
        joinAll(first, second)
        val restarted = RevocationList.open(store.inner)
        assertAll(
            { assertEquals(1, firstHeld, "the rig: the first revoke's write is held in the store") },
            { assertEquals(2, store.writesCommitted, "both revokes wrote") },
            { assertEquals(1_000L, restarted.notBefore(PHONE), "the first revocation is in the store") },
            { assertEquals(2_000L, restarted.notBefore(LAPTOP), "and so is the second") },
        )
    }

    @Test
    fun theLaterCutoffSurvivesARestart() = runTest {
        // `revoke` is monotone, and what reaches the store is the merged cutoff, never the argument.
        val store = InMemoryDurableStore()
        val list = RevocationList.open(store)
        list.revoke(PHONE, notBefore = 2_000)
        list.revoke(PHONE, notBefore = 1_000)
        val afterEarlier = RevocationList.open(store).notBefore(PHONE)

        // A restarted host's list starts from what the store held, so an earlier cutoff after the
        // restart still cannot pull it back.
        val restarted = RevocationList.open(store)
        restarted.revoke(PHONE, notBefore = 1_500)
        val afterRestart = RevocationList.open(store).notBefore(PHONE)
        assertAll(
            { assertEquals(2_000L, afterEarlier, "an earlier cutoff does not reach the store") },
            { assertEquals(2_000L, afterRestart, "nor after a restart") },
        )
    }

    @Test
    fun aFailedWriteIsMadeGoodByTheNextRevoke() = runTest {
        // A write that throws leaves the store's state unknown, and revoke throws with it. The cutoff
        // still holds in this process, since refusing the device is the safe side; and because every
        // revoke writes the whole list, retrying the same revoke puts it in the store.
        val store = FailFirstWriteStore()
        val list = RevocationList.open(store)
        assertFailsWith<WriteFailed> { list.revoke(PHONE, notBefore = 1_000) }
        val heldInProcess = list.notBefore(PHONE)
        val storedAfterFailure = store.inner.read(RevocationList.DEFAULT_STORE_KEY)

        list.revoke(PHONE, notBefore = 1_000)
        val stored = RevocationList.open(store.inner).notBefore(PHONE)
        assertAll(
            { assertEquals(1, store.failures, "the rig: the first write failed") },
            { assertNull(storedAfterFailure, "the rig: and nothing reached the store") },
            { assertEquals(1_000L, heldInProcess, "the cutoff holds in this process regardless") },
            { assertEquals(1_000L, stored, "the retried revoke stored it") },
        )
    }

    @Test
    fun storedLayoutIsPinned() {
        // The bytes a host restart reads back are a format other versions must read: magic `KRL`,
        // version 1, then per subject its 33-byte compressed point and an 8-byte big-endian cutoff.
        val encoded = RevocationListLayout.encode(mapOf(SUBJECT to CUTOFF))
        assertAll(
            { assertEquals(GOLDEN, encoded.toHex()) },
            { assertEquals(mapOf(SUBJECT to CUTOFF), assertNotNull(RevocationListLayout.decode(encoded))) },
            { assertEquals(emptyMap(), assertNotNull(RevocationListLayout.decode(HEADER)), "a header alone is an empty list") },
        )
    }

    @Test
    fun storedLayoutDoesNotDependOnRevocationOrder() {
        val ab = RevocationListLayout.encode(linkedMapOf(PHONE to 1L, LAPTOP to 2L, TABLET to 3L))
        val ba = RevocationListLayout.encode(linkedMapOf(TABLET to 3L, LAPTOP to 2L, PHONE to 1L))
        assertContentEquals(ab, ba)
    }

    /** A store whose first write is held until [release]; every later write goes straight through. */
    private class HeldFirstWriteStore : DurableStore {
        val inner = InMemoryDurableStore()
        private val gate = CompletableDeferred<Unit>()
        var writesStarted = 0
            private set
        var writesCommitted = 0
            private set

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun read(key: StoreKey): ByteArray? = inner.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (writesStarted++ == 0) gate.await()
            inner.write(key, bytes)
            writesCommitted++
        }

        override suspend fun delete(key: StoreKey) = inner.delete(key)
    }

    /** A store whose first write throws [WriteFailed] before committing anything. */
    private class FailFirstWriteStore : DurableStore {
        val inner = InMemoryDurableStore()
        var failures = 0
            private set
        private var writes = 0

        override suspend fun read(key: StoreKey): ByteArray? = inner.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (writes++ == 0) {
                failures++
                throw WriteFailed()
            }
            inner.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey) = inner.delete(key)
    }

    private class StoreUnavailable : RuntimeException("the store cannot be read")

    private class WriteFailed : RuntimeException("the write failed")

    private companion object {
        /** Syntactically valid names for no particular key: the list compares names and never parses them. */
        fun syntheticKey(n: Int): DidKey = DidKey.ofCompressedPoint(ByteArray(33) { if (it == 0) 0x03 else (n + it).toByte() })

        val PHONE = syntheticKey(1)
        val LAPTOP = syntheticKey(2)
        val TABLET = syntheticKey(3)

        val POINT = ByteArray(33) { if (it == 0) 0x02 else 0x11 }
        val SUBJECT = DidKey.ofCompressedPoint(POINT)
        const val CUTOFF = 0x0102030405060708L
        val CUTOFF_BYTES = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val MAGIC = "KRL".encodeToByteArray()
        val HEADER = MAGIC + byteArrayOf(0x01)
        const val GOLDEN = "4b524c01" + "02" + "1111111111111111111111111111111111111111111111111111111111111111" + "0102030405060708"

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
