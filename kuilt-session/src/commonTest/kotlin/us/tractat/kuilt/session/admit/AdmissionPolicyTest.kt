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
import kotlin.test.assertTrue
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

    /**
     * The proof signs a missing device id as `""`, so a joiner sending `""` and one sending nothing
     * cannot be told apart. The request settles it in its own constructor (#3220): `""` is none,
     * whoever builds the request, so a policy branching on `deviceId == null` sees one value.
     */
    @Test
    fun anEmptyDeviceIdIsNone() = keysTest { a, _ ->
        assertAll(
            { assertNull(AdmissionRequest(a, "Ada", deviceId = "").deviceId) },
            { assertNull(AdmissionRequest(a, "Ada", deviceId = null).deviceId) },
            { assertEquals("phone-1", AdmissionRequest(a, "Ada", deviceId = "phone-1").deviceId) },
        )
    }

    /**
     * `"superseded"` is the room's alone: it rides with the successor that took the seat (Decided
     * 32), so a policy refusing with it would make the room build a `Reject` it cannot send. The
     * decision refuses it by id, an app's own `Unknown("superseded")` included.
     */
    @Test
    fun aPolicyCannotRefuseAsSuperseded() {
        val refused = assertFailsWith<IllegalArgumentException> { AdmissionDecision.Reject(RejectCode.Unknown("superseded")) }
        assertAll(
            { assertTrue(refused.message.orEmpty().contains("superseded"), "names the code: ${refused.message}") },
            { assertFailsWith<IllegalArgumentException> { AdmissionDecision.Reject(RejectCode.Superseded) } },
            // The control: every other code still builds.
            { assertEquals(RejectCode.Unknown("blocked"), AdmissionDecision.Reject(RejectCode.Unknown("blocked")).code) },
            { assertEquals<RejectCode>(RejectCode.KeyConflict, AdmissionDecision.Reject(RejectCode.KeyConflict).code) },
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

    /**
     * A pass on a finite term lasts one and a half times it (#3709), rounded up to the millisecond, so
     * the pass held when a device drops, issued up to half a term earlier, still has the term left.
     */
    @Test
    fun finiteTermEndsOneAndAHalfTermsAfterIssue() {
        assertAll(
            { assertEquals(1_700_005_400_000L, GrantTerms(1.hours).notAfter(1_700_000_000_000L)) },
            // 1.5 ms is rounded up, never down: the pass is not short of the term's floor.
            { assertEquals(2L, GrantTerms(1.milliseconds).notAfter(0)) },
            { assertEquals(3L, GrantTerms(2.milliseconds).notAfter(0)) },
            // A part millisecond of the term is dropped first: 1.5 ms is a 1 ms term.
            { assertEquals(2L, GrantTerms(1_500.microseconds).notAfter(0)) },
            // Before the epoch: a sum below zero is a plain sum, not an overflow.
            { assertEquals(0L, GrantTerms(1.milliseconds).notAfter(-2)) },
            { assertEquals(Long.MIN_VALUE + 90.minutes.inWholeMilliseconds, GrantTerms(1.hours).notAfter(Long.MIN_VALUE)) },
        )
    }

    @Test
    fun finiteTermPastTheEndOfTimeSaturatesToNoExpiry() {
        val lifeMs = 90.minutes.inWholeMilliseconds
        val hour = GrantTerms(1.hours)
        val longestFinite = (Long.MAX_VALUE / 2 - 1).milliseconds
        val longestLife = GrantTerms(longestFinite).notAfter(0)
        assertAll(
            { assertEquals(true, longestFinite.isFinite()) },
            { assertEquals(longestFinite.inWholeMilliseconds / 2 * 3, longestLife, "rig: the longest term's pass fits in a Long from 0") },
            // One short of the end: the plain sum, so the saturation below is not the only answer.
            { assertEquals(Grant.NO_EXPIRY - 1, hour.notAfter(Grant.NO_EXPIRY - 1 - lifeMs)) },
            // Lands exactly on the end.
            { assertEquals(Grant.NO_EXPIRY, hour.notAfter(Grant.NO_EXPIRY - lifeMs)) },
            // Would overflow a Long: saturates instead of wrapping to a time long past.
            { assertEquals(Grant.NO_EXPIRY, hour.notAfter(Long.MAX_VALUE - 10)) },
            // The longest finite Duration, from a start where it fits and from one where it overflows.
            { assertEquals(Long.MAX_VALUE - 1, GrantTerms(longestFinite).notAfter(Long.MAX_VALUE - 1 - longestLife)) },
            { assertEquals(Grant.NO_EXPIRY, GrantTerms(longestFinite).notAfter(Long.MAX_VALUE - longestLife + 10)) },
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
            { assertEquals(issuedAt + 2, brief.notAfter) },
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
                // The joiner's view of the refusal is the wire's: the id and its retryability (v8,
                // Decided 25), rebuilt as Unknown, and terminal as the app meant it.
                val code = assertIs<AdmissionDecision.Reject>(refused).code
                val seenByJoiner = AdmitMessage.Reject("not welcome", code, answers = null).code
                assertEquals("not-welcome", code.id)
                assertEquals(RejectCode.Unknown("not-welcome", retryable = false), seenByJoiner)
                assertEquals(false, seenByJoiner.retryable)
            },
            { assertEquals(forever, friend) },
            { assertEquals(forever, unblocked) },
            // Blocking is the app's own set: it writes nothing to the revocations.
            { assertNull(admission.revocations.notBefore(b)) },
        )
    }
}
