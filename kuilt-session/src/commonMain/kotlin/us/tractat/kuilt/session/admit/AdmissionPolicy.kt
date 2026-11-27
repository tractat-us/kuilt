package us.tractat.kuilt.session.admit

import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.session.Principal
import us.tractat.kuilt.session.SessionInternalApi
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
 * Revoking is not a ban, so do not refuse on it: a revocation cannot be undone, and a policy that
 * refuses every revoked key keeps that device out for good. Refuse on the app's own blocked set,
 * which it can undo, and keep that set across restarts as you keep the revocations: a forgotten block
 * lets a revoked device back in as a newcomer. To keep a device out from its next return, block it
 * and revoke it: blocking alone leaves a held seat or a pass it holds working, and revoking alone
 * only sends it back to this policy. Neither removes a device that is in the room now.
 *
 * Every host entry point takes one inside a [HostAdmission]: `RoomFactory.host`, an `AdoptRole.Host`
 * adopt, and `ElectionLobby.start`. A newcomer is a device whose proven key holds no seat in the room:
 * a first visit, or a second tab of a device already in the room, which gets a membership of its own
 * and so is asked about like any other.
 *
 * [decide] suspends so a policy can look something up, but it must answer within the host's admit
 * timeout. A policy still deciding when the newcomer's challenge runs out is cancelled and the
 * newcomer hears nothing, so its own admit deadline ends the attempt; a later Hello is asked about
 * afresh. A policy that throws refuses the newcomer with [RejectCode.AdmissionPolicyFailed], which is
 * retryable, and the room keeps running. It may run concurrently with itself, for two joiners at once.
 *
 * @sample us.tractat.kuilt.session.hostAdmissionSample
 */
public fun interface AdmissionPolicy {
    /** Whether the newcomer described by [request] gets in, and on what terms. */
    public suspend fun decide(request: AdmissionRequest): AdmissionDecision

    public companion object {
        /**
         * Admits everyone, each with a pass back into this room on [GrantTerms] of [readmitFor] and
         * [GrantReach.ThisRoom]: a device away up to [readmitFor] after its last contact always gets
         * back in on its pass, and up to one and a half times it, depending on when it dropped. With
         * the default hour that is 60 to 90 minutes: the pass lasts 90 minutes and is renewed every 30
         * while the device stays connected. A renewal that fails to sign or to send is tried again,
         * more often as the device's pass nears its end (#3711). One the link took and then lost
         * costs nothing either: the host lets the device back in on the last pass it signed it
         * (#3745), unless the host restarted since or 256 later departures pushed that record out.
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
 * Only the room builds one in production, after the joiner has proved it holds [key]; that is why
 * the constructor is gated by [SessionInternalApi]. To test your own policy without a room, build one
 * with `admissionRequest` from `:kuilt-session-test`. The four fields are not equally trustworthy:
 *
 * - [key] is **proved**: the joiner signed the host's fresh challenge with it. Key a decision on this.
 * - [principal] is **attested by the transport**: who the link the joiner came in on was
 *   authenticated as, before kuilt read a frame from it (a Ktor server's `principalExtractor`, say).
 *   It is the value the admitted [us.tractat.kuilt.session.Member.principal] will carry, and `null`
 *   on a link nothing authenticated. It vouches for the link, not the key: a policy that needs the
 *   two to agree, such as the key a gate checked before the upgrade and the key the joiner then
 *   proved, compares them itself.
 * - [deviceId] is **bound** to the key, since the joiner signed over it too, but it is still whatever
 *   the device chose to say. It tells you which id this key goes with, not that the id is unique.
 * - [displayName] is the joiner's **say-so** and nothing more. Show it; never decide on it. A joiner
 *   that gave no name is shown, and asked about, under its peer id.
 *
 * **No device id is always `null`, never `""`.** The proof signs a missing device id as the empty
 * string, so a joiner that sends `""` and one that sends nothing sign the same bytes and cannot be
 * told apart. The constructor therefore reads `""` as `null`, which is what a policy branching on
 * `deviceId == null` sees whoever built the request: the room, or a test.
 */
public class AdmissionRequest @SessionInternalApi constructor(
    /** The device key the joiner proved it holds. */
    public val key: DidKey,
    /** The name the joiner asked to be shown under. Unverified. */
    public val displayName: String,
    deviceId: String?,
    /** Who the joiner's link was authenticated as, or `null` when nothing authenticated it. See above. */
    public val principal: Principal? = null,
) {
    /** The app's own device id, if the joiner sent one; signed by [key]. Never empty: see above. */
    public val deviceId: String? = deviceId?.takeUnless { it.isEmpty() }

    /**
     * Names the key, the name and the device id, and says only **whether** the link was attested. A
     * [principal] is whatever the app's authentication made of the link, which may be an account id
     * the app would not want in a log line, so a policy that logs one reads it on purpose.
     */
    override fun toString(): String =
        "AdmissionRequest(key=${key.value}, displayName=$displayName, deviceId=$deviceId, " +
            "principal=${if (principal == null) "none" else "attested"})"
}

/**
 * The request the room asks its [AdmissionPolicy] about, for a joiner the key → seat table decided
 * is a [newcomer] and whose Hello and proven key are [keyed] (#3220).
 *
 * [newcomer] is read for nothing but its type: only the [SeatDecision.Newcomer] verdicts reach the
 * policy, so a caller holding a restore has nothing to pass here, and asking the policy about a
 * returning device does not compile. That holds for the room because this is the only place it builds
 * a request; the constructor itself is public under [SessionInternalApi], which this module opts into.
 *
 * The display name is the one the joiner's Hello carried: an unnamed kuilt joiner says its own peer
 * id there. The device id is the Hello's too, and the request reads an empty one as none, as the
 * proof does. [principal] is what the joiner's link was attested as: the value its member will carry.
 */
@Suppress("UNUSED_PARAMETER") // [newcomer]'s job is its type; see the KDoc.
internal fun newcomerRequest(newcomer: SeatDecision.Newcomer, keyed: KeyedHello, principal: Principal?): AdmissionRequest =
    AdmissionRequest(key = keyed.key, displayName = keyed.hello.displayName, deviceId = keyed.hello.deviceId, principal = principal)

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
     * The joiner sees the code's [RejectCode.id] and its [RejectCode.retryable]: the wire carries both
     * (v8, Decided 25), and the joiner rebuilds the code with [RejectCode.fromWire]. So kuilt's own
     * codes arrive whole, and an app's own code — [RejectCode] is open, so a policy may refuse with
     * one — arrives as [RejectCode.Unknown] with the same id and retryability: a terminal one stays
     * terminal, and a reconnecting device stops saying Hello. An app code that reuses one of kuilt's
     * ids arrives as kuilt's code, with kuilt's retryability.
     *
     * **Not [RejectCode.Superseded].** That code tells a joiner another process of its own device took
     * its seat, and the room sends it only with the successor that took it
     * ([AdmitMessage.Reject.supersededBy]), so a policy cannot. Refusing it here, at construction,
     * keeps the room from building a refusal it could not send: a policy that tries throws inside
     * `decide`, and the joiner hears [RejectCode.AdmissionPolicyFailed]. It is refused by id, so an
     * app's own `RejectCode.Unknown("superseded")` is refused too.
     *
     * @throws IllegalArgumentException when [code]'s id is [RejectCode.Superseded]'s, whatever its type.
     */
    public data class Reject(
        /** Why, as the joiner will see it. */
        public val code: RejectCode,
    ) : AdmissionDecision {
        init {
            require(code.id != RejectCode.Superseded.id) {
                "a policy cannot refuse with \"${RejectCode.Superseded.id}\": the room sends it only with the successor that took the seat"
            }
        }
    }
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
 * [readmitFor] is a floor: a device away up to [readmitFor] after its last contact always gets back in
 * on its pass, and up to one and a half times it, depending on when it dropped, however long ago it
 * first joined. The pass itself lasts one and a half times [readmitFor] ([notAfter]), and the room
 * issues a fresh one every half of [readmitFor] while the member stays connected, so the pass a device
 * holds when it drops has at least [readmitFor] left. A renewal that fails to sign or to send is tried
 * again, more often as the device's pass nears its end (#3711). One the link took and then lost costs
 * nothing either: the host lets a device whose seat is gone back in on the last pass it signed it, as
 * on one the device presents (#3745). Only a host restart, or 256 later departures pushing that record
 * out, leaves the device with the pass before it.
 * [Duration.INFINITE] means the pass never runs out.
 *
 * A grant is dated in whole epoch milliseconds and must end after it starts, so [readmitFor] is at
 * least one millisecond; a part millisecond beyond that is dropped.
 *
 * @property readmitFor How long after its last contact a device is sure to get back in on its pass.
 *   At least 1 ms, or infinite.
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
     * [issuedAt] plus the pass's life, one and a half times [readmitFor] rounded up to the millisecond,
     * or [Grant.NO_EXPIRY] when [readmitFor] is infinite. The half is what keeps [readmitFor] a floor
     * when the pass held at a device's last contact was issued a renewal period earlier.
     *
     * Saturating: a finite term that would run past the end of a `Long` stamps [Grant.NO_EXPIRY],
     * which means the pass never expires. That is intended — such a pass would outlast any clock
     * anyway. Always after [issuedAt] when [issuedAt] is before [Grant.NO_EXPIRY].
     */
    public fun notAfter(issuedAt: Long): Long {
        if (readmitFor.isInfinite()) return Grant.NO_EXPIRY
        val end = issuedAt + GrantIssuer.lifetimeMillis(readmitFor.inWholeMilliseconds)
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
 * @property revocations Per-device cutoffs. A device revoked after its seat was admitted does not get
 *   that seat back, and goes to the [policy] as a newcomer; while it stays connected it keeps its seat,
 *   but the room stops renewing its pass (Decided 14). A pass issued before its device's cutoff does not
 *   let it back in unvetted either (#3222). They void seats and passes; they do not keep a device out,
 *   which is the [policy]'s job.
 * @sample us.tractat.kuilt.session.hostAdmissionSample
 * @sample us.tractat.kuilt.session.revokeALostDeviceSample
 */
public class HostAdmission(
    public val policy: AdmissionPolicy,
    public val revocations: RevocationList,
) {
    override fun toString(): String = "HostAdmission(policy=$policy)"
}
