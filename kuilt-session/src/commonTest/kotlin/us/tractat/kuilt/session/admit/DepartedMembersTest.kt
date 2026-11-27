@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session.admit

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The host's bounded memory of departed members, and the last good pass it signed each (#3222, #3745, #3762). */
class DepartedMembersTest {
    private val key = fakeDidKey(1)

    private suspend fun pass(subject: DidKey, issuedAt: Long, notAfter: Long): Grant =
        Grant.issue(fakeDeviceKey(0), subject, GrantScope.Room("room"), issuedAt, notAfter)

    @Test
    fun severalTabsKeepTheLatestIdAndTheLastIssuedPass() = runTest {
        val departed = DepartedMembers(RevocationList.inMemory())
        val earlier = pass(key, issuedAt = 0, notAfter = 900)
        val later = pass(key, issuedAt = 100, notAfter = 600)
        departed.record(key, PeerId("tab-2"), later, now = 200)
        departed.record(key, PeerId("tab-1"), earlier, now = 300)
        val kept = departed.pass(key, now = 400)
        val id = departed.take(key, now = 400)
        assertAll(
            { assertEquals(later, kept, "the pass the host issued last, as the device's own store keeps, whichever tab left last") },
            { assertEquals(PeerId("tab-1"), id, "and the record names the id that left last") },
        )
    }

    /**
     * #3762: a pass that never expires but was issued before the device's revocation cutoff, and a
     * good one for an hour issued after it. The good one is kept whichever tab leaves first, and so
     * whichever pass reaches the record last, and whether the revocation came before the departures
     * or after them.
     */
    @Test
    fun aRevokedPassNeverDisplacesAGoodOne() = runTest {
        val revocations = RevocationList.inMemory()
        revocations.revoke(key, notBefore = 100)
        val revoked = pass(key, issuedAt = 0, notAfter = Grant.NO_EXPIRY)
        val good = pass(key, issuedAt = 100, notAfter = 3_600)
        val voidLast = DepartedMembers(revocations).apply {
            record(key, PeerId("good-tab"), good, now = 200)
            record(key, PeerId("void-tab"), revoked, now = 300)
        }
        val goodLast = DepartedMembers(revocations).apply {
            record(key, PeerId("void-tab"), revoked, now = 200)
            record(key, PeerId("good-tab"), good, now = 300)
        }
        val voidAlone = DepartedMembers(revocations).apply { record(key, PeerId("void-tab"), revoked, now = 200) }
        val laterRevocations = RevocationList.inMemory()
        val revokedLater = DepartedMembers(laterRevocations).apply {
            record(key, PeerId("good-tab"), good, now = 200)
            record(key, PeerId("void-tab"), revoked, now = 300)
        }
        laterRevocations.revoke(key, notBefore = 100)
        assertAll(
            { assertEquals(good, voidLast.pass(key, now = 400), "a void pass arriving last does not displace the good one") },
            { assertEquals(good, goodLast.pass(key, now = 400), "nor does one that was there first") },
            { assertEquals(0, voidAlone.size(now = 400), "and a void pass alone is not recorded") },
            { assertEquals(good, revokedLater.pass(key, now = 400), "nor does a revocation made after both were recorded") },
        )
    }

    /** #3762: a revocation made after the record was written drops it, at the next read. */
    @Test
    fun aRecordIsDroppedWhenItsPassIsRevokedAfterItWasWritten() = runTest {
        val revocations = RevocationList.inMemory()
        val departed = DepartedMembers(revocations)
        val other = fakeDidKey(2)
        departed.record(key, PeerId("one"), pass(key, issuedAt = 0, notAfter = Grant.NO_EXPIRY), now = 100)
        departed.record(other, PeerId("two"), pass(other, issuedAt = 0, notAfter = Grant.NO_EXPIRY), now = 100)
        val before = departed.size(now = 200)
        revocations.revoke(key, notBefore = 1)
        assertAll(
            { assertEquals(2, before, "rig: both departures were recorded") },
            { assertNull(departed.pass(key, now = 200), "the revoked key's record is dropped") },
            { assertEquals(1, departed.size(now = 200), "and only it: another key's record stays") },
            { assertNull(departed.take(key, now = 200), "with the id it held") },
        )
    }

    @Test
    fun readingThePassForgetsNothingButTakingDoes() = runTest {
        val departed = DepartedMembers(RevocationList.inMemory())
        val good = pass(key, issuedAt = 0, notAfter = 900)
        departed.record(key, PeerId("one"), good, now = 100)
        val first = departed.pass(key, now = 200)
        val second = departed.pass(key, now = 200)
        val taken = departed.take(key, now = 200)
        assertAll(
            { assertEquals(good, first) },
            { assertEquals(good, second, "a read leaves the record for the return that takes it") },
            { assertEquals(PeerId("one"), taken) },
            { assertNull(departed.pass(key, now = 200), "taken on return") },
        )
    }

    @Test
    fun aRecordIsForgottenWhenItsPassRunsOutOrALaterDeparturePushesItOut() = runTest {
        val departed = DepartedMembers(RevocationList.inMemory(), capacity = 2)
        val other = fakeDidKey(2)
        val third = fakeDidKey(3)
        departed.record(key, PeerId("one"), pass(key, issuedAt = 0, notAfter = 500), now = 100)
        val beforeExpiry = departed.pass(key, now = 499)
        val atExpiry = departed.pass(key, now = 500)
        departed.record(key, PeerId("one"), pass(key, issuedAt = 600, notAfter = 2_000), now = 700)
        departed.record(other, PeerId("two"), pass(other, issuedAt = 600, notAfter = 2_000), now = 800)
        departed.record(third, PeerId("three"), pass(third, issuedAt = 600, notAfter = 2_000), now = 900)
        assertAll(
            { assertEquals(500, beforeExpiry?.notAfter, "rig: kept until its pass runs out") },
            { assertNull(atExpiry, "and forgotten when it does") },
            { assertNull(departed.pass(key, now = 1_000), "the oldest departure is pushed out past the capacity") },
            { assertEquals(2, departed.size(now = 1_000)) },
        )
    }
}
