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
 * and again every [refreshPeriodMillis] (a third of the pass's life, [PERIODS_PER_PASS]) while the
 * member stays connected, as long as [mayIssue] holds. The terms come from the member's membership:
 * the policy's at admission, or those the pass a device returned on carried ([termsOf], #3222). A
 * restore or a takeover keeps them.
 *
 * Stateless: the terms live on the membership and the schedule in the room's renewal jobs, so what a
 * host remembers of a member that left is the room's too (`DepartedMembers`).
 */
internal object GrantIssuer {
    /**
     * How many renewal periods one pass lives (#3709). A pass on [GrantTerms.readmitFor] `r` lives
     * `r × N/(N−1)` ([lifetimeMillis]), and while its member stays connected the host renews it every
     * `r/(N−1)` ([refreshPeriodMillis]), a period of `1/N` of the pass's life.
     *
     * What it buys: the pass a member holds was issued at most one period before its last contact, so
     * it has at least `r` left then, whatever `N` is: [GrantTerms.readmitFor] is the floor, and a
     * device away up to it always gets back in on its pass. It may get back in later, up to the whole
     * life of the pass, depending on when it dropped. At 3, with [AdmissionPolicy.open]'s hour: a pass
     * lives 90 minutes, is renewed every 30, and a device gets back in on it 60 to 90 minutes after its
     * last contact. A renewal that fails to sign or to send is tried again ([retryDelayMillis], #3711).
     * One the link took and then lost leaves the member holding the pass before it, but the host keeps
     * the one it signed in its record of the departure (`DepartedMembers`, #3745) and lets the device
     * back in on that, so the floor holds until the host restarts or forgets the departure.
     *
     * What it costs: one signature and one [AdmitMessage.GrantRenewal] per connected member per
     * period, and a pass that outlives the promise by up to `r/(N−1)`. A larger value brings the
     * ceiling down toward the floor and signs more often; the floor does not move.
     *
     * One constant, not a setting: a pass does not carry its renewal period, so a per-room value could
     * not follow a device that returns on its pass, whose next passes are issued on the terms read back
     * from it ([termsOf]). That read inverts this factor, so a change to it carries a pass issued
     * before the change forward on a different term, once.
     */
    const val PERIODS_PER_PASS: Int = 3

    /**
     * How long a pass on a finite [GrantTerms.readmitFor] of [readmitForMs] lives, in milliseconds:
     * `readmitForMs × N/(N−1)` for [PERIODS_PER_PASS] `N`, rounded **up**, so it is never short of the
     * floor. [readmitForMillis] rounds its inverse down, which makes the round trip exact for every
     * term of at least 1 ms. Saturates at `Long.MAX_VALUE`, which no finite `Duration` reaches.
     */
    fun lifetimeMillis(readmitForMs: Long): Long {
        val extra = ceilDiv(readmitForMs, PERIODS_PER_PASS - 1L)
        return if (readmitForMs > Long.MAX_VALUE - extra) Long.MAX_VALUE else readmitForMs + extra
    }

    /**
     * The [GrantTerms.readmitFor] a pass that lives [lifetimeMs] was issued on, in milliseconds: the
     * inverse of [lifetimeMillis], `lifetimeMs × (N−1)/N` rounded **down**, at least 1 ms. Without the
     * inversion a device returning on its pass would be issued the next on its pass's longer life, and
     * its term would grow by half on every return.
     */
    fun readmitForMillis(lifetimeMs: Long): Long = maxOf(1L, lifetimeMs - ceilDiv(lifetimeMs, PERIODS_PER_PASS.toLong()))

    /** [dividend] over [divisor] rounded up, for a [dividend] of at least 0; never overflows. */
    private fun ceilDiv(dividend: Long, divisor: Long): Long = dividend / divisor + if (dividend % divisor == 0L) 0 else 1

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
     * How long after one pass on [terms] the next is due, in milliseconds: [GrantTerms.readmitFor] over
     * `N − 1` for [PERIODS_PER_PASS] `N` (half of it at 3), rounded down. A pass lives `N` of these, so
     * a member that drops just before a renewal still holds a pass for [GrantTerms.readmitFor] after
     * its last contact. Never under 1 ms: a term shorter than `N − 1` ms divides to 0 in whole
     * milliseconds, which would spin the renewal loop at one instant, signing without end and freezing
     * a test's virtual clock; [lifetimeMillis] rounds up, so the floor still holds there. Null for a
     * term that never ends, whose pass is never renewed.
     *
     * Read from the terms, not from a pass, so renewals run whether or not the first pass was signed.
     */
    fun refreshPeriodMillis(terms: GrantTerms): Long? =
        if (terms.readmitFor.isInfinite()) null else maxOf(1L, terms.readmitFor.inWholeMilliseconds / (PERIODS_PER_PASS - 1))

    /** The first wait before a renewal that failed to sign or send is tried again (#3711): a second. */
    const val RETRY_FIRST_DELAY_MS: Long = 1_000

    /**
     * Each retry waits at most this share of the time left on the pass the device is known to hold:
     * a quarter (#3711). The waits therefore close up as that pass nears its end.
     */
    const val RETRY_SHARE_OF_REMAINING: Long = 4

    /**
     * The shortest wait the closing-in cap may impose: a quarter second (#3711). A cap with no floor
     * would land a burst of tries in the pass's last second, each a platform signature and a logged
     * failure; with it, about four.
     */
    const val RETRY_MIN_DELAY_MS: Long = 250

    /**
     * How long to wait before retry number [retry] (0 for the first) of a renewal that failed to sign
     * or to send, in milliseconds (#3711). The wait doubles from [RETRY_FIRST_DELAY_MS], never past
     * [periodMs], so a retry is never later than the schedule it replaces. While the pass the device
     * is known to hold, ending at [sentNotAfter], has time left at [nowMs], the wait is also at most
     * `1/`[RETRY_SHARE_OF_REMAINING] of that time, but not under [RETRY_MIN_DELAY_MS], so the last tries
     * before it runs out come closest together without bunching. A try that would land at or past its
     * end waits the plain doubling instead: there is nothing left to close in on. A [sentNotAfter] of
     * null (no pass known), [Grant.NO_EXPIRY], or already past sets no cap. Never under 1 ms, so the
     * loop cannot spin at one instant.
     */
    fun retryDelayMillis(retry: Int, nowMs: Long, sentNotAfter: Long?, periodMs: Long): Long {
        val doubled = if (retry >= MAX_DOUBLINGS) Long.MAX_VALUE else RETRY_FIRST_DELAY_MS shl retry
        val scheduled = maxOf(1L, minOf(doubled, periodMs))
        val left = if (sentNotAfter == null || sentNotAfter == Grant.NO_EXPIRY) 0L else sentNotAfter - nowMs
        if (left <= 0) return scheduled
        val capped = maxOf(minOf(scheduled, left / RETRY_SHARE_OF_REMAINING), minOf(RETRY_MIN_DELAY_MS, scheduled))
        return if (capped < left) capped else scheduled
    }

    /** Past this many doublings [RETRY_FIRST_DELAY_MS] would overflow a `Long`; the period caps it long before. */
    private const val MAX_DOUBLINGS = 52

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
     * issued its next on these, so a return keeps the term and reach it presented (the plan's
     * carried-terms decision). The term is [readmitForMillis] of the pass's life, `notAfter − issuedAt`,
     * which inverts [lifetimeMillis] exactly, so returning on a pass never lengthens the term (#3709);
     * or [Duration.INFINITE] for [Grant.NO_EXPIRY]. A pass issued before #3709, which lived its term,
     * carries forward on two thirds of it, once.
     */
    fun termsOf(grant: Grant): GrantTerms = GrantTerms(
        readmitFor = if (grant.notAfter == Grant.NO_EXPIRY) Duration.INFINITE else readmitForMillis(grant.notAfter - grant.issuedAt).milliseconds,
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
