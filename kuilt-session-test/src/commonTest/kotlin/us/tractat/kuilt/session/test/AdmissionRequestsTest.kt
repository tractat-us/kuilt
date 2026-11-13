package us.tractat.kuilt.session.test

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.session.admit.AdmissionDecision
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.GrantTerms
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * [admissionRequest] builds a request a real [AdmissionPolicy] decides on, carrying each field where
 * the room would put it.
 *
 * Keys A and B are the two W3C CCG P-256 `did:key` vectors, parsed through the public entry point, so
 * each is a real key on every target. Opted in per class, as a consumer would, rather than
 * module-wide: this module opts in to nothing but `SessionInternalApi`.
 */
@OptIn(AdmissionTestApi::class)
class AdmissionRequestsTest {

    private fun keysTest(body: suspend (a: DidKey, b: DidKey) -> Unit) = runTest {
        val a = DidKey.parse("did:key:zDnaerx9CtbPJ1q36T5Ln5wYt3MQYeGRG5ehnPAmxcf5mDZpv")
        val b = DidKey.parse("did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169")
        // Every "a different device gets a different answer" case below rests on this.
        assertNotEquals(a, b)
        body(a, b)
    }

    @Test
    fun carriesEachFieldWhereTheRoomWouldPutIt() = keysTest { a, _ ->
        val request = admissionRequest(a, displayName = "Ada", deviceId = "phone-1")
        assertAll(
            { assertEquals(a, request.key) },
            { assertEquals("Ada", request.displayName) },
            { assertEquals("phone-1", request.deviceId) },
        )
    }

    @Test
    fun defaultsToNoNameAndNoDeviceId() = keysTest { a, _ ->
        val request = admissionRequest(a)
        assertAll(
            { assertEquals(a, request.key) },
            { assertEquals("", request.displayName) },
            { assertEquals(null, request.deviceId) },
        )
    }

    @Test
    fun theOpenPolicyDecidesOnABuiltRequest() = keysTest { a, b ->
        val policy = AdmissionPolicy.open(readmitFor = 10.minutes)
        val expected = AdmissionDecision.Admit(GrantTerms(10.minutes))
        val forA = policy.decide(admissionRequest(a, "Ada", "phone-1"))
        val forB = policy.decide(admissionRequest(b))
        // A freshly generated key, the way an app's own test would most often get one.
        val forFresh = policy.decide(admissionRequest(DeviceKey.generate().id))
        assertAll(
            { assertEquals(expected, forA) },
            { assertEquals(expected, forB) },
            { assertEquals(expected, forFresh) },
        )
    }

    @Test
    fun aPolicyKeyedOnEachFieldSeesTheOneItWasGiven() = keysTest { a, b ->
        // Admit only when every field is the one the test passed, so a factory that dropped or
        // swapped any field reds here: each arm below differs from the admitted one in one field.
        val admit = AdmissionDecision.Admit(GrantTerms(1.hours))
        val reject = AdmissionDecision.Reject(RejectCode.Unknown("no"))
        val policy = AdmissionPolicy { r ->
            if (r.key == a && r.displayName == "Ada" && r.deviceId == "phone-1") admit else reject
        }
        val asGiven = policy.decide(admissionRequest(a, "Ada", "phone-1"))
        val otherKey = policy.decide(admissionRequest(b, "Ada", "phone-1"))
        val otherName = policy.decide(admissionRequest(a, "Bea", "phone-1"))
        val otherDevice = policy.decide(admissionRequest(a, "Ada", "phone-2"))
        val noDevice = policy.decide(admissionRequest(a, "Ada", null))
        assertAll(
            // The positive control: without it every reject below would pass on a policy that rejects all.
            { assertEquals(admit, asGiven) },
            { assertEquals(reject, otherKey) },
            { assertEquals(reject, otherName) },
            { assertEquals(reject, otherDevice) },
            { assertEquals(reject, noDevice) },
        )
    }

    @Test
    fun theSampleRefusesTheBlockedAndAdmitsTheStranger() = keysTest { a, b ->
        val (forBlocked, forStranger) = admissionPolicyTestSample(blocked = a, stranger = b)
        assertAll(
            { assertEquals(AdmissionDecision.Reject(RejectCode.Unknown("blocked")), forBlocked) },
            { assertEquals(AdmissionDecision.Admit(GrantTerms(1.hours)), forStranger) },
        )
    }
}
