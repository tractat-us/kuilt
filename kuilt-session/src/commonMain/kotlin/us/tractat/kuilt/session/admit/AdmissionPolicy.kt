package us.tractat.kuilt.session.admit

import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.RevocationList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

public fun interface AdmissionPolicy {
    public suspend fun decide(request: AdmissionRequest): AdmissionDecision

    public companion object {
        public fun open(readmitFor: Duration = 1.hours): AdmissionPolicy = TODO("#3219")
    }
}

public class AdmissionRequest internal constructor(
    public val key: DidKey,
    public val displayName: String,
    public val deviceId: String?,
)

public sealed interface AdmissionDecision {
    public data class Admit(public val grant: GrantTerms) : AdmissionDecision

    public data class Reject(public val code: RejectCode) : AdmissionDecision
}

public enum class GrantReach { ThisRoom, AnyRoomOfIssuer }

public data class GrantTerms(
    public val readmitFor: Duration,
    public val reach: GrantReach = GrantReach.ThisRoom,
) {
    public fun notAfter(issuedAt: Long): Long = TODO("#3219")
}

public class HostAdmission(
    public val policy: AdmissionPolicy,
    public val revocations: RevocationList,
)
