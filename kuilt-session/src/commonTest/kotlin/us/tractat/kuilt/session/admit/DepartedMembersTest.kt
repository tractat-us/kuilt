@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session.admit

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeDidKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The host's bounded memory of departed members, and the last pass it signed each (#3222, #3745). */
class DepartedMembersTest {
    private val key = fakeDidKey(1)

    private suspend fun pass(subject: DidKey, issuedAt: Long, notAfter: Long): Grant =
        Grant.issue(fakeDeviceKey(0), subject, GrantScope.Room("room"), issuedAt, notAfter)

    @Test
    fun severalTabsKeepTheLatestIdAndTheLaterEndingPass() = runTest {
        val departed = DepartedMembers()
        val longer = pass(key, issuedAt = 0, notAfter = 900)
        val shorter = pass(key, issuedAt = 100, notAfter = 600)
        departed.record(key, PeerId("tab-1"), longer, now = 200)
        departed.record(key, PeerId("tab-2"), shorter, now = 300)
        val kept = departed.pass(key, now = 700)
        val id = departed.take(key, now = 700)
        assertAll(
            { assertEquals(longer, kept, "a later tab's departure never shortens what an earlier one left") },
            { assertEquals(PeerId("tab-2"), id, "and the record names the id that left last") },
        )
    }

    @Test
    fun readingThePassForgetsNothingButTakingDoes() = runTest {
        val departed = DepartedMembers()
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
        val departed = DepartedMembers(capacity = 2)
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
