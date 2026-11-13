package us.tractat.kuilt.identity

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The store that keeps one device's key and the grants it holds, for as long as the process lives.
 *
 * The concurrency arm forces the interleaving rather than hoping for it: its key generator yields,
 * so every one of the fifty callers is already inside `deviceKey()` before the first key exists, and
 * the test asserts that they were. An unguarded store makes fifty keys there, every time.
 */
class InMemoryDeviceKeyStoreTest {
    @Test
    fun deviceKeyIsStable() = runTest {
        var callersInside = 0
        var callersInsideWhileGenerating = -1
        var generated = 0
        val store = InMemoryDeviceKeyStore.generatingWith {
            generated++
            yield() // let every other caller reach the store while this key is still being made
            callersInsideWhileGenerating = callersInside
            DeviceKey.generate()
        }

        val ids = List(CALLERS) {
            async {
                callersInside++
                store.deviceKey().id
            }
        }.awaitAll()
        val later = store.deviceKey().id

        assertAll(
            { assertEquals(CALLERS, callersInsideWhileGenerating, "precondition: all $CALLERS calls were in flight while the key was made") },
            { assertEquals(1, generated, "one key generated for $CALLERS concurrent first calls") },
            { assertEquals(setOf(later), ids.toSet(), "every call, concurrent or later, names the same key") },
        )
    }

    @Test
    fun theKeyIsTheSameObjectOnEveryCall() = runTest {
        val store = InMemoryDeviceKeyStore()
        val first = store.deviceKey()
        val message = "signed by the stored key".encodeToByteArray()
        val second = store.deviceKey()
        val verifies = first.id.verify(message, second.sign(message))
        assertAll(
            { assertSame(first, second) },
            { assertTrue(verifies, "the stored key signs under its own name") },
        )
    }

    @Test
    fun distinctStoresHaveDistinctKeys() = runTest {
        val one = InMemoryDeviceKeyStore().deviceKey().id
        val other = InMemoryDeviceKeyStore().deviceKey().id
        assertNotEquals(one, other)
    }

    @Test
    fun keepsNewestPerIssuer() = runTest {
        val host = DeviceKey.generate()
        val store = InMemoryDeviceKeyStore()
        val subject = store.deviceKey().id
        val middle = grant(host, subject, issuedAt = T + 1_000)
        val older = grant(host, subject, issuedAt = T)
        val newer = grant(host, subject, issuedAt = T + 2_000)

        store.keepGrant(middle)
        val afterFirst = store.grants()
        store.keepGrant(older)
        val afterOlder = store.grants()
        store.keepGrant(newer)
        val afterNewer = store.grants()

        assertAll(
            { assertEquals(listOf(middle), afterFirst, "control: a first grant is kept") },
            { assertEquals(listOf(middle), afterOlder, "an older grant from the same issuer is ignored") },
            { assertEquals(listOf(newer), afterNewer, "a newer grant from the same issuer replaces the held one") },
        )
    }

    @Test
    fun aGrantIssuedAtTheSameInstantKeepsTheHeldOne() = runTest {
        val host = DeviceKey.generate()
        val store = InMemoryDeviceKeyStore()
        val subject = store.deviceKey().id
        val held = grant(host, subject, issuedAt = T)
        val sameInstant = grant(host, subject, issuedAt = T)

        store.keepGrant(held)
        store.keepGrant(sameInstant)
        val afterSameInstant = store.grants()
        store.keepGrant(held)
        val afterKeptTwice = store.grants()

        assertAll(
            { assertNotEquals(held, sameInstant, "precondition: two different grants") },
            { assertEquals(listOf(held), afterSameInstant, "only a strictly newer grant replaces the held one") },
            { assertEquals(listOf(held), afterKeptTwice, "keeping the held grant again is a no-op") },
        )
    }

    @Test
    fun twoIssuersBothKept() = runTest {
        val store = InMemoryDeviceKeyStore()
        val subject = store.deviceKey().id
        val empty = store.grants()
        val fromOne = grant(DeviceKey.generate(), subject, issuedAt = T + 5_000)
        val fromOther = grant(DeviceKey.generate(), subject, issuedAt = T) // older, but another issuer

        store.keepGrant(fromOne)
        store.keepGrant(fromOther)
        val kept = store.grants()

        assertAll(
            { assertEquals(emptyList<Grant>(), empty, "a new store holds no grants") },
            { assertEquals(setOf(fromOne, fromOther), kept.toSet()) },
            { assertEquals(2, kept.size) },
        )
    }

    @Test
    fun twoRoomsFromOneIssuerBothKept() = runTest {
        val host = DeviceKey.generate() // one host key, hosting two rooms
        val store = InMemoryDeviceKeyStore()
        val subject = store.deviceKey().id
        val roomA = grant(host, subject, issuedAt = T + 5_000, scope = GrantScope.Room("A"))
        val roomB = grant(host, subject, issuedAt = T, scope = GrantScope.Room("B")) // older, but another room
        val roomARenewed = grant(host, subject, issuedAt = T + 9_000, scope = GrantScope.Room("A"))

        store.keepGrant(roomA)
        store.keepGrant(roomB)
        val afterBoth = store.grants()
        store.keepGrant(roomARenewed)
        val afterRenewal = store.grants()

        assertAll(
            { assertEquals(setOf(roomA, roomB), afterBoth.toSet(), "a pass into each room is kept") },
            { assertEquals(2, afterBoth.size) },
            { assertEquals(setOf(roomARenewed, roomB), afterRenewal.toSet(), "a newer pass into A replaces A's alone") },
            { assertEquals(2, afterRenewal.size) },
        )
    }

    private suspend fun grant(
        issuer: DeviceKey,
        subject: DidKey,
        issuedAt: Long,
        scope: GrantScope = GrantScope.AnyRoomOfIssuer,
    ): Grant = Grant.issue(issuer, subject, scope, issuedAt, issuedAt + HOUR)

    private companion object {
        const val CALLERS = 50
        const val T = 1_700_000_000_000L
        const val HOUR = 3_600_000L
    }
}
