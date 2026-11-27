package us.tractat.kuilt.session.admit

import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantTrust

/**
 * Whether a pass [GrantTrust] has already found good, or that this host signed, still is at [nowMs]
 * against its subject's revocation [cutoff]: the two checks of its verdict that can change after it
 * was given. Issuer, signature, subject and scope cannot.
 */
internal fun Grant.stillGood(cutoff: Long?, nowMs: Long): Boolean =
    (cutoff == null || issuedAt >= cutoff) && (notAfter == Grant.NO_EXPIRY || nowMs < notAfter)

/**
 * Whether this pass is dated more than [GrantTrust.CLOCK_SKEW_ALLOWANCE_MILLIS] past [nowMs], so that
 * [GrantTrust.verify] would answer `NotYetValid`. Saturates rather than wraps, as that check does.
 */
internal fun Grant.isPostDatedAt(nowMs: Long): Boolean {
    val allowance = GrantTrust.CLOCK_SKEW_ALLOWANCE_MILLIS
    val latestAccepted = if (nowMs > Long.MAX_VALUE - allowance) Long.MAX_VALUE else nowMs + allowance
    return issuedAt > latestAccepted
}
