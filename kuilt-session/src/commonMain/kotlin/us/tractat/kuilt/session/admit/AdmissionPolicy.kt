package us.tractat.kuilt.session.admit

import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.RevocationList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * The host's say on who gets into its room, and on how long each newcomer may come back without
 * being asked again.
 *
 * A device that knocks for the first time — or that has been away too long to be let straight back
 * in — is a newcomer, and only the app knows whether to let it in. kuilt asks this policy, and the
 * answer is either [AdmissionDecision.Admit], with the [GrantTerms] of the pass the newcomer gets, or
 * [AdmissionDecision.Reject] with a [RejectCode] the joiner sees.
 *
 * The policy sees **only** newcomers. A device the host is still holding a seat for, or one coming
 * back inside the pass it was given, is let back in by the room without asking: the room checks the
 * device's key against the seats it holds and the pass against its own key first. So a policy cannot
 * refuse a returning device; revoking its key in [HostAdmission.revocations] makes it a newcomer
 * again, and then the policy decides.
 *
 * [decide] suspends so a policy can look something up, but it must answer within the joiner's admit
 * timeout. It may run concurrently with itself, for two joiners at once.
 *
 * **No room asks one yet.** These are the types an app writes its policy against; host entry points
 * that take a [HostAdmission] and consult it are tracked by #3220.
 *
 * @sample us.tractat.kuilt.session.hostAdmissionSample
 */
public fun interface AdmissionPolicy {
    /** Whether the newcomer described by [request] gets in, and on what terms. */
    public suspend fun decide(request: AdmissionRequest): AdmissionDecision

    public companion object {
        /**
         * Admits everyone, each with a pass back into this room that is good for [readmitFor] after
         * last contact: [GrantTerms] of [readmitFor] and [GrantReach.ThisRoom].
         *
         * [readmitFor] is checked here, so a bad value throws when the host is configured rather than
         * on some joiner's admission.
         *
         * @throws IllegalArgumentException when [readmitFor] is not a valid [GrantTerms.readmitFor].
         */
        public fun open(readmitFor: Duration = 1.hours): AdmissionPolicy = OpenPolicy(GrantTerms(readmitFor))
    }
}

/** [AdmissionPolicy.open]: the same decision for everyone, built once. */
private class OpenPolicy(terms: GrantTerms) : AdmissionPolicy {
    private val decision = AdmissionDecision.Admit(terms)

    override suspend fun decide(request: AdmissionRequest): AdmissionDecision = decision

    override fun toString(): String = "AdmissionPolicy.open(readmitFor=${decision.grant.readmitFor})"
}

/**
 * A newcomer asking to be let in, as the host's [AdmissionPolicy] sees it.
 *
 * Only the room builds one, after the joiner has proved it holds [key]; that is why the constructor
 * is internal. The three fields are not equally trustworthy:
 *
 * - [key] is **proved**: the joiner signed the host's fresh challenge with it. Key a decision on this.
 * - [deviceId] is **bound** to the key, since the joiner signed over it too, but it is still whatever
 *   the device chose to say. It tells you which id this key goes with, not that the id is unique.
 * - [displayName] is the joiner's **say-so** and nothing more. Show it; never decide on it.
 */
public class AdmissionRequest internal constructor(
    /** The device key the joiner proved it holds. */
    public val key: DidKey,
    /** The name the joiner asked to be shown under. Unverified. */
    public val displayName: String,
    /** The app's own device id, if the joiner sent one; signed by [key]. */
    public val deviceId: String?,
) {
    override fun toString(): String =
        "AdmissionRequest(key=${key.value}, displayName=$displayName, deviceId=$deviceId)"
}

/** An [AdmissionPolicy]'s answer for one newcomer. */
public sealed interface AdmissionDecision {
    /** Let the newcomer in, and give it a pass back on these terms. */
    public data class Admit(
        /** The terms of the [Grant] the newcomer is given on admission. */
        public val grant: GrantTerms,
    ) : AdmissionDecision

    /**
     * Turn the newcomer away with [code].
     *
     * The joiner sees only [RejectCode.id]: the wire carries the id, and the joiner rebuilds the code
     * with [RejectCode.fromId]. So kuilt's own codes arrive whole, but an app's own code — [RejectCode]
     * is open, so a policy may refuse with one — arrives as [RejectCode.Unknown] with the same id, and
     * that is treated as retryable whatever the app's code says.
     */
    public data class Reject(
        /** Why, as the joiner will see it. */
        public val code: RejectCode,
    ) : AdmissionDecision
}

/** Where a pass from [GrantTerms] is good. */
public enum class GrantReach {
    /** Only in the room that issued it: [us.tractat.kuilt.identity.GrantScope.Room]. */
    ThisRoom,

    /**
     * In any room the same host runs, now or later: [us.tractat.kuilt.identity.GrantScope.AnyRoomOfIssuer].
     * For a friend, say, so the pass outlives the room it was given in.
     */
    AnyRoomOfIssuer,
}

/**
 * What a host promises a newcomer it admits: how long the device may come back without being asked
 * again ([readmitFor]), and where ([reach]).
 *
 * The room re-issues the pass while the member stays connected, so [readmitFor] counts from the
 * member's last contact, not from when it first joined. [Duration.INFINITE] means the pass never runs
 * out.
 *
 * A grant is dated in whole epoch milliseconds and must end after it starts, so [readmitFor] is at
 * least one millisecond; a part millisecond beyond that is dropped.
 *
 * @property readmitFor How long after it was issued the pass is good. At least 1 ms, or infinite.
 * @property reach Where the pass is good. This room only, by default.
 * @throws IllegalArgumentException when [readmitFor] is under one millisecond, zero or negative.
 */
public data class GrantTerms(
    public val readmitFor: Duration,
    public val reach: GrantReach = GrantReach.ThisRoom,
) {
    init {
        require(readmitFor >= 1.milliseconds) {
            "readmitFor must be at least 1 ms, or Duration.INFINITE; was $readmitFor"
        }
    }

    /**
     * The [Grant.notAfter] these terms stamp on a pass issued at [issuedAt], in epoch milliseconds:
     * [issuedAt] plus [readmitFor], or [Grant.NO_EXPIRY] when [readmitFor] is infinite.
     *
     * Saturating: a finite term that would run past the end of a `Long` stamps [Grant.NO_EXPIRY],
     * which means the pass never expires. That is intended — such a pass would outlast any clock
     * anyway. Always after [issuedAt] when [issuedAt] is before [Grant.NO_EXPIRY].
     */
    public fun notAfter(issuedAt: Long): Long {
        if (readmitFor.isInfinite()) return Grant.NO_EXPIRY
        val end = issuedAt + readmitFor.inWholeMilliseconds
        // The term is positive, so the sum wrapped exactly when it came out smaller.
        return if (end < issuedAt) Grant.NO_EXPIRY else end
    }
}

/**
 * Everything a host needs to decide who comes in: the app's [policy] for newcomers, and the host's
 * [revocations] for devices whose passes it no longer honours.
 *
 * There is deliberately no host key here. The room checks every pass against its own device key,
 * the one it signs passes with, so the key that issues passes and the key that checks them cannot
 * differ.
 *
 * @property policy Decides every newcomer; never asked about a returning device.
 * @property revocations The cutoffs the room checks a returning device's pass against.
 * @sample us.tractat.kuilt.session.hostAdmissionSample
 */
public class HostAdmission(
    public val policy: AdmissionPolicy,
    public val revocations: RevocationList,
) {
    override fun toString(): String = "HostAdmission(policy=$policy)"
}
