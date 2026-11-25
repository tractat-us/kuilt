package us.tractat.kuilt.session.admit

import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope

/**
 * How a host issues a member its pass back in, and when it issues the next one (identity Task 2.5,
 * #3221).
 *
 * The room calls these outside its lock, since signing suspends: [issue] on every direct `Welcome`,
 * and again every [refreshPeriodMillis] while the member stays connected, as long as [mayIssue]
 * holds. The terms come from the member's membership, which the policy gave at admission and a
 * restore or a takeover keeps.
 */
internal object GrantIssuer {
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
     * How long after [grant] the next pass is due, in milliseconds: half its lifetime, so a member
     * that drops just before a renewal still holds a pass for half a lifetime after its last contact.
     * Never under 1 ms: a 1 ms term halves to 0 in whole milliseconds, which would spin the renewal
     * loop at one instant, signing without end and freezing a test's virtual clock. Null for a pass
     * that never expires, which is never renewed.
     */
    fun refreshPeriodMillis(grant: Grant): Long? =
        if (grant.notAfter == Grant.NO_EXPIRY) null else maxOf(1L, (grant.notAfter - grant.issuedAt) / 2)

    /**
     * Whether a membership admitted at [admittedAtMs] may be issued a pass, given its device's
     * revocation [cutoff] (`null`: never revoked). No: the device was revoked after this membership
     * was admitted (Decided 14, planning R2(i)), so the host stops renewing its pass and its next
     * return is decided again. A revocation the membership's admission came after does not count: the
     * policy admitted the device knowing it.
     */
    fun mayIssue(admittedAtMs: Long, cutoff: Long?): Boolean = cutoff == null || cutoff <= admittedAtMs
}
