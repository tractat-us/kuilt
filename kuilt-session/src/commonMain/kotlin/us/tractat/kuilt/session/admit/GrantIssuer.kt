package us.tractat.kuilt.session.admit

import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a host issues a member its pass back in, and when it issues the next one (identity Task 2.5,
 * #3221).
 *
 * The room calls these outside its lock, since signing suspends: [issue] on every direct `Welcome`,
 * and again every [refreshPeriodMillis] while the member stays connected, as long as [mayIssue]
 * holds. The terms come from the member's membership: the policy's at admission, or those the pass
 * a device returned on carried ([termsOf], #3222). A restore or a takeover keeps them.
 *
 * Stateless: the terms live on the membership and the schedule in the room's renewal jobs, so what a
 * host remembers of a member that left is the room's too (`DepartedMembers`).
 */
internal object GrantIssuer {
    /**
     * How many renewals a pass gets in one term: while a member stays connected, the host issues it a
     * fresh pass every [GrantTerms.readmitFor] / `RENEWALS_PER_TERM` ([refreshPeriodMillis], #3709).
     *
     * What it buys: the pass a member holds was issued at most one period before its last contact, so
     * a member that drops can come back on it for at least `1 − 1/RENEWALS_PER_TERM` of the term after
     * that contact, and never more than the whole term. At 4 that floor is three quarters: with
     * [AdmissionPolicy.open]'s hour, a device away up to 45 minutes gets back in, and up to 60
     * depending on when it dropped. The floor holds provided the last renewal reached the member: one
     * that fails to sign or is lost on the way is not resent (tracked by #3711), so the member is left
     * on the pass before it.
     *
     * What it costs: one signature and one [AdmitMessage.GrantRenewal] per connected member per period,
     * four a term rather than two. A larger value lifts the floor toward the whole term and signs more.
     *
     * One constant, not a setting: a pass does not carry its renewal period, so a per-room value could
     * not follow a device that returns on its pass, whose next passes are issued on the terms that pass
     * carried ([termsOf]).
     */
    const val RENEWALS_PER_TERM: Int = 4

    /**
     * A pass signed by [issuer] for the device whose key is [subject], issued at [issuedAt] on
     * [terms]: good until [GrantTerms.notAfter], in this room ([roomId]) or in every room [issuer]
     * hosts, as [GrantTerms.reach] says.
     */
    suspend fun issue(issuer: DeviceKey, subject: DidKey, terms: GrantTerms, roomId: String, issuedAt: Long): Grant =
        Grant.issue(issuer, subject, scopeOf(terms.reach, roomId), issuedAt, terms.notAfter(issuedAt))

    /** The scope a pass on [reach] is issued for, in the room [roomId]. */
    fun scopeOf(reach: GrantReach, roomId: String): GrantScope = when (reach) {
        GrantReach.ThisRoom -> GrantScope.Room(roomId)
        GrantReach.AnyRoomOfIssuer -> GrantScope.AnyRoomOfIssuer
    }

    /**
     * How long after one pass on [terms] the next is due, in milliseconds: half of
     * [GrantTerms.readmitFor], so a member that drops just before a renewal still holds a pass for half
     * a term after its last contact. Never under 1 ms: a 1 ms term halves to 0 in whole milliseconds,
     * which would spin the renewal loop at one instant, signing without end and freezing a test's
     * virtual clock. Null for a term that never ends, whose pass is never renewed.
     *
     * Read from the terms, not from a pass, so renewals run whether or not the first pass was signed.
     */
    fun refreshPeriodMillis(terms: GrantTerms): Long? =
        if (terms.readmitFor.isInfinite()) null else maxOf(1L, terms.readmitFor.inWholeMilliseconds / 2)

    /**
     * Whether a membership admitted at [admittedAtMs] may be issued a pass, given its device's
     * revocation [cutoff] (`null`: never revoked). No: the device was revoked after this membership
     * was admitted (Decided 14, planning R2(i)), so the host stops renewing its pass and its next
     * return is decided again. A revocation the membership's admission came after does not count: the
     * policy admitted the device knowing it.
     */
    fun mayIssue(admittedAtMs: Long, cutoff: Long?): Boolean = cutoff == null || cutoff <= admittedAtMs

    /**
     * The terms [grant] was issued on, read back from it (#3222): a device let back in on its pass is
     * issued its next on these, so a return keeps the lifetime and reach it presented (the plan's
     * carried-terms decision). The lifetime is `notAfter − issuedAt`, at least 1 ms since a grant ends
     * after it starts, or [Duration.INFINITE] for [Grant.NO_EXPIRY].
     */
    fun termsOf(grant: Grant): GrantTerms = GrantTerms(
        readmitFor = if (grant.notAfter == Grant.NO_EXPIRY) Duration.INFINITE else (grant.notAfter - grant.issuedAt).milliseconds,
        reach = when (grant.scope) {
            is GrantScope.Room -> GrantReach.ThisRoom
            GrantScope.AnyRoomOfIssuer -> GrantReach.AnyRoomOfIssuer
        },
    )

    /**
     * Of two passes a host found good, the one whose terms to carry forward: the longer lifetime, then
     * the wider reach, then [first]. A friend presenting a no-expiry pass beside a stranger's one-hour
     * one keeps the friend's terms.
     */
    fun mostGenerous(first: Grant, second: Grant): Grant {
        val byLifetime = lifetimeOf(second).compareTo(lifetimeOf(first))
        if (byLifetime != 0) return if (byLifetime > 0) second else first
        val widerSecond = second.scope == GrantScope.AnyRoomOfIssuer && first.scope != GrantScope.AnyRoomOfIssuer
        return if (widerSecond) second else first
    }

    private fun lifetimeOf(grant: Grant): Long =
        if (grant.notAfter == Grant.NO_EXPIRY) Long.MAX_VALUE else grant.notAfter - grant.issuedAt
}
