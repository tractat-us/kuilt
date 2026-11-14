package us.tractat.kuilt.session.test

import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.session.admit.AdmissionDecision
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.GrantTerms
import us.tractat.kuilt.session.admit.RejectCode
import kotlin.time.Duration.Companion.hours

/**
 * Test your own admission policy without a room: build the request the room would hand it, and ask
 * it. Here the policy keeps out devices on an app-owned blocked list and lets everyone else in for an
 * hour after last contact.
 */
@OptIn(AdmissionTestApi::class)
public suspend fun admissionPolicyTestSample(blocked: DidKey, stranger: DidKey): List<AdmissionDecision> {
    // Your policy, as your app would hand it to the room. Decide on the key; the name is say-so.
    val blockedList = setOf(blocked)
    val policy = AdmissionPolicy { request ->
        if (request.key in blockedList) {
            AdmissionDecision.Reject(RejectCode.Unknown("blocked"))
        } else {
            AdmissionDecision.Admit(GrantTerms(readmitFor = 1.hours))
        }
    }
    // The requests the room would build once each joiner had proved its key.
    return listOf(
        policy.decide(admissionRequest(blocked, displayName = "Mallory")),
        policy.decide(admissionRequest(stranger, displayName = "Ada", deviceId = "phone-1")),
    )
}
