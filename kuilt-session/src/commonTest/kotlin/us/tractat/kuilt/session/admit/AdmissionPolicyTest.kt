package us.tractat.kuilt.session.admit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.identity.GrantTrust
import us.tractat.kuilt.identity.GrantVerdict
import us.tractat.kuilt.session.hostAdmissionSample
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The host's admission policy and the terms of the grant it hands out, as types: what
 * [AdmissionPolicy.open] decides, which [GrantTerms] can exist at all, and the [Grant.notAfter] each
 * one stamps on a grant. Nothing here touches a room; the room consults the policy in a later task.
 *
 * Keys A and B are the two W3C CCG P-256 `did:key` vectors, parsed through the public entry point, so
 * each is a real key on every target.
 */
class AdmissionPolicyTest {

    private fun keysTest(body: suspend (a: DidKey, b: DidKey) -> Unit) = runTest {
        val a = DidKey.parse("did:key:zDnaerx9CtbPJ1q36T5Ln5wYt3MQYeGRG5ehnPAmxcf5mDZpv")
        val b = DidKey.parse("did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169")
        // Every "a different device" case below rests on this.
        assertNotEquals(a, b)
        body(a, b)
    }

    @Test
    fun openAdmitsEveryoneWithTheRoomDefault() = keysTest { a, b ->
        val policy = AdmissionPolicy.open()
        val roomDefault = AdmissionDecision.Admit(GrantTerms(1.hours, GrantReach.ThisRoom))
        val forA = policy.decide(AdmissionRequest(a, "Ada", deviceId = "phone-1"))
        val forB = policy.decide(AdmissionRequest(b, "", deviceId = null))
        assertAll(
            { assertEquals(roomDefault, forA) },
            { assertEquals(roomDefault, forB) },
        )
    }

    @Test
    fun openCarriesTheTermItWasGiven() = keysTest { a, _ ->
        assertEquals(
            AdmissionDecision.Admit(GrantTerms(10.minutes, GrantReach.ThisRoom)),
            AdmissionPolicy.open(readmitFor = 10.minutes).decide(AdmissionRequest(a, "Ada", deviceId = null)),
        )
    }

    @Test
    fun openRefusesABadTermWhenBuiltNotWhenAsked() {
        // The throw lands where the host is configured, never on a joiner's admission.
        assertFailsWith<IllegalArgumentException> { AdmissionPolicy.open(readmitFor = Duration.ZERO) }
    }

    @Test
    fun termsRefuseZeroAndNegative() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { GrantTerms(Duration.ZERO) } },
            { assertFailsWith<IllegalArgumentException> { GrantTerms((-1).hours) } },
            { assertFailsWith<IllegalArgumentException> { GrantTerms(-Duration.INFINITE) } },
            // A grant is dated in whole milliseconds and must end after it starts, so a term shorter
            // than one millisecond would stamp notAfter == issuedAt: a grant Grant.issue refuses.
            { assertFailsWith<IllegalArgumentException> { GrantTerms(999_999.nanoseconds) } },
        )
    }

    @Test
    fun termsAcceptOneMillisecondAndInfinity() {
        // The positive control for the refusals above: the boundaries themselves are accepted.
        assertAll(
            { assertEquals(1.milliseconds, GrantTerms(1.milliseconds).readmitFor) },
            { assertEquals(Duration.INFINITE, GrantTerms(Duration.INFINITE).readmitFor) },
            { assertEquals(GrantReach.ThisRoom, GrantTerms(1.hours).reach) },
        )
    }

    @Test
    fun infiniteMeansNoExpiry() {
        val forever = GrantTerms(Duration.INFINITE, GrantReach.AnyRoomOfIssuer)
        assertAll(
            { assertEquals(Grant.NO_EXPIRY, forever.notAfter(0)) },
            { assertEquals(Grant.NO_EXPIRY, forever.notAfter(1_700_000_000_000L)) },
            { assertEquals(Grant.NO_EXPIRY, forever.notAfter(-1)) },
        )
    }

    @Test
    fun finiteTermEndsReadmitForAfterIssue() {
        assertAll(
            { assertEquals(1_700_003_600_000L, GrantTerms(1.hours).notAfter(1_700_000_000_000L)) },
            { assertEquals(1L, GrantTerms(1.milliseconds).notAfter(0)) },
            // A part millisecond is dropped, never rounded up: the grant runs no longer than asked.
            { assertEquals(1L, GrantTerms(1_500.microseconds).notAfter(0)) },
            // Before the epoch: a sum below zero is a plain sum, not an overflow.
            { assertEquals(-1L, GrantTerms(1.milliseconds).notAfter(-2)) },
            { assertEquals(Long.MIN_VALUE + 1.hours.inWholeMilliseconds, GrantTerms(1.hours).notAfter(Long.MIN_VALUE)) },
        )
    }

    @Test
    fun finiteTermPastTheEndOfTimeSaturatesToNoExpiry() {
        val hourMs = 1.hours.inWholeMilliseconds
        val hour = GrantTerms(1.hours)
        val longestFinite = (Long.MAX_VALUE / 2 - 1).milliseconds
        assertAll(
            { assertEquals(true, longestFinite.isFinite()) },
            // One short of the end: the plain sum, so the saturation below is not the only answer.
            { assertEquals(Grant.NO_EXPIRY - 1, hour.notAfter(Grant.NO_EXPIRY - 1 - hourMs)) },
            // Lands exactly on the end.
            { assertEquals(Grant.NO_EXPIRY, hour.notAfter(Grant.NO_EXPIRY - hourMs)) },
            // Would overflow a Long: saturates instead of wrapping to a time long past.
            { assertEquals(Grant.NO_EXPIRY, hour.notAfter(Long.MAX_VALUE - 10)) },
            // The longest finite Duration, from a start where it fits and from one where it overflows.
            { assertEquals(Long.MAX_VALUE - 1, GrantTerms(longestFinite).notAfter(Long.MAX_VALUE / 2 + 1)) },
            { assertEquals(Grant.NO_EXPIRY, GrantTerms(longestFinite).notAfter(Long.MAX_VALUE / 2 + 10)) },
        )
    }

    @Test
    fun stampedTermsMakeGrantsThatCanBeIssued() = runTest {
        val host = DeviceKey.generate()
        val phone = DeviceKey.generate()
        val issuedAt = 1_700_000_000_000L
        // The shortest term and the longest: each notAfter satisfies Grant's own notAfter > issuedAt.
        val shortest = GrantTerms(1.milliseconds)
        val forever = GrantTerms(Duration.INFINITE)
        val brief = Grant.issue(host, phone.id, GrantScope.Room("table-7"), issuedAt, shortest.notAfter(issuedAt))
        val lasting = Grant.issue(host, phone.id, GrantScope.AnyRoomOfIssuer, issuedAt, forever.notAfter(issuedAt))
        assertAll(
            { assertEquals(issuedAt + 1, brief.notAfter) },
            { assertEquals(Grant.NO_EXPIRY, lasting.notAfter) },
        )
    }

    private val forever = AdmissionDecision.Admit(GrantTerms(Duration.INFINITE, GrantReach.AnyRoomOfIssuer))

    @Test
    fun sampleRevocationVoidsAFriendsPassButDoesNotBanIt() = keysTest { a, b ->
        val host = DeviceKey.generate()
        val stranger = DeviceKey.generate().id
        val store = InMemoryDurableStore()
        val blocked = MutableStateFlow<Set<DidKey>>(emptySet())
        val admission = hostAdmissionSample(friends = setOf(a, b), blocked = blocked, store = store)
        val trust = GrantTrust(host.id, admission.revocations)
        val now = 1_700_000_000_000L
        val oldPass = Grant.issue(host, b, GrantScope.AnyRoomOfIssuer, issuedAt = now, notAfter = forever.grant.notAfter(now))
        val oldBefore = trust.verify(oldPass, b, "table-7", now)

        admission.revocations.revoke(b, notBefore = now + 1) // the clock now, plus one
        val oldAfter = trust.verify(oldPass, b, "table-7", now + 1)
        // b knocks again as a newcomer, and the policy, which never consults the revocations, admits it.
        val readmitted = admission.policy.decide(AdmissionRequest(b, "Bea", deviceId = null))
        val freshPass = Grant.issue(host, b, GrantScope.AnyRoomOfIssuer, issuedAt = now + 1, notAfter = forever.grant.notAfter(now + 1))
        val friend = admission.policy.decide(AdmissionRequest(a, "Ada", deviceId = null))
        val other = admission.policy.decide(AdmissionRequest(stranger, "Cy", deviceId = "laptop"))
        // The host restarts on the same store: the revocation survives, and still bans nobody.
        val restarted = hostAdmissionSample(friends = setOf(a, b), blocked = blocked, store = store)
        val freshVerdict = trust.verify(freshPass, b, "table-7", now + 2)
        val oldAfterRestart = GrantTrust(host.id, restarted.revocations).verify(oldPass, b, "table-7", now + 2)
        val readmittedAfterRestart = restarted.policy.decide(AdmissionRequest(b, "Bea", deviceId = null))
        assertAll(
            // The rig fired: the old pass worked, then the revocation voided it.
            { assertEquals(GrantVerdict.Valid, oldBefore) },
            { assertEquals(GrantVerdict.Revoked, oldAfter) },
            { assertEquals(forever, readmitted) },
            { assertEquals(GrantVerdict.Valid, freshVerdict) },
            { assertEquals(forever, friend) },
            { assertEquals(AdmissionDecision.Admit(GrantTerms(1.hours, GrantReach.ThisRoom)), other) },
            { assertEquals(now + 1, restarted.revocations.notBefore(b)) },
            { assertEquals(GrantVerdict.Revoked, oldAfterRestart) },
            { assertEquals(forever, readmittedAfterRestart) },
        )
    }

    @Test
    fun sampleBlockedDeviceIsRefusedUntilUnblocked() = keysTest { a, b ->
        val blocked = MutableStateFlow<Set<DidKey>>(emptySet())
        val admission = hostAdmissionSample(friends = setOf(a, b), blocked = blocked, store = InMemoryDurableStore())
        // Before the block b is a friend like a, so the refusal below is the block's doing.
        val before = admission.policy.decide(AdmissionRequest(b, "Bea", deviceId = null))
        blocked.value = setOf(b)
        val refused = admission.policy.decide(AdmissionRequest(b, "Bea", deviceId = null))
        val friend = admission.policy.decide(AdmissionRequest(a, "Ada", deviceId = null))
        blocked.value = emptySet()
        val unblocked = admission.policy.decide(AdmissionRequest(b, "Bea", deviceId = null))
        assertAll(
            { assertEquals(forever, before) },
            {
                // The joiner's view of the refusal is the wire's: the id alone, rebuilt as Unknown, retryable.
                val code = assertIs<AdmissionDecision.Reject>(refused).code
                val seenByJoiner = AdmitMessage.Reject("not welcome", code).code
                assertEquals("not-welcome", code.id)
                assertEquals(RejectCode.Unknown("not-welcome"), seenByJoiner)
                assertEquals(true, seenByJoiner.retryable)
            },
            { assertEquals(forever, friend) },
            { assertEquals(forever, unblocked) },
            // Blocking is the app's own set: it writes nothing to the revocations.
            { assertNull(admission.revocations.notBefore(b)) },
        )
    }
}
