package us.tractat.kuilt.identity

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The host's record of which devices it has revoked, and from when: one cutoff per subject. */
class RevocationListTest {
    @Test
    fun unrevokedSubjectHasNoCutoff() = runTest {
        val list = RevocationList.inMemory()
        assertNull(list.notBefore(DeviceKey.generate().id))
    }

    @Test
    fun revokeRecordsTheCutoff() = runTest {
        val list = RevocationList.inMemory()
        val phone = DeviceKey.generate().id
        list.revoke(phone, notBefore = 1_000)
        assertEquals(1_000L, list.notBefore(phone))
    }

    @Test
    fun revokeIsMonotone() = runTest {
        // A clock that moves backwards, or a replayed revoke, must never un-revoke anyone: the
        // cutoff only ever moves later.
        val list = RevocationList.inMemory()
        val phone = DeviceKey.generate().id
        list.revoke(phone, notBefore = 1_000)
        val afterFirst = list.notBefore(phone)
        list.revoke(phone, notBefore = 500)
        val afterEarlier = list.notBefore(phone)
        list.revoke(phone, notBefore = 2_000)
        val afterLater = list.notBefore(phone)
        assertAll(
            { assertEquals(1_000L, afterFirst, "the first cutoff is recorded") },
            { assertEquals(1_000L, afterEarlier, "an earlier cutoff is ignored") },
            { assertEquals(2_000L, afterLater, "a later cutoff replaces it") },
        )
    }

    @Test
    fun subjectsAreIndependent() = runTest {
        val list = RevocationList.inMemory()
        val phone = DeviceKey.generate().id
        val laptop = DeviceKey.generate().id
        val tablet = DeviceKey.generate().id
        list.revoke(phone, notBefore = 1_000)
        list.revoke(laptop, notBefore = 3_000)
        assertAll(
            { assertEquals(1_000L, list.notBefore(phone)) },
            { assertEquals(3_000L, list.notBefore(laptop)) },
            { assertNull(list.notBefore(tablet)) },
        )
    }

    @Test
    fun listsAreIndependent() = runTest {
        // `inMemory` makes a fresh list each call, never a shared one.
        val first = RevocationList.inMemory()
        val second = RevocationList.inMemory()
        val phone = DeviceKey.generate().id
        first.revoke(phone, notBefore = 1_000)
        assertNull(second.notBefore(phone))
    }
}
