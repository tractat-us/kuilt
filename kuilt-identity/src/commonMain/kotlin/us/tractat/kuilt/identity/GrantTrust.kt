package us.tractat.kuilt.identity

/**
 * What a host makes of a [Grant] a device presents: good here and now, or the first reason it is not.
 *
 * Only [Valid] lets the device back in without being vetted. Every other verdict means the device is
 * treated as a newcomer, and which one tells the host why.
 */
public sealed interface GrantVerdict {
    /**
     * Issued by this host, signed by it, to this device, for this room, not revoked, not dated ahead,
     * not expired.
     */
    public data object Valid : GrantVerdict

    /**
     * Issued by some other key than this host's. A grant is good only with the host that issued it:
     * after a host change, a returning device's old pass is this, and the device goes through
     * ordinary admission.
     */
    public data object UntrustedIssuer : GrantVerdict

    /** Names this host as issuer, but the signature is not this host's over these exact terms. */
    public data object BadSignature : GrantVerdict

    /** A good grant, but for another device: its subject is not the key the presenter proved it holds. */
    public data object WrongSubject : GrantVerdict

    /** A good grant for another room this host runs: a [GrantScope.Room] whose id is not this room's. */
    public data object WrongScope : GrantVerdict

    /** Issued before the host revoked this device: see [RevocationList]. */
    public data object Revoked : GrantVerdict

    /**
     * Dated in the future: its [Grant.issuedAt] is more than [GrantTrust.CLOCK_SKEW_ALLOWANCE_MILLIS]
     * past the time it was checked at. The host's clock ran ahead when it issued the pass and has
     * since been set back. The pass is refused rather than trusted, and the device goes through
     * ordinary admission, which the host's policy may refuse.
     *
     * The fresh pass ordinary admission hands the device replaces the bad one, although it is older:
     * a room keeps the pass on its `Welcome` with [DeviceKeyStore.keepFreshGrant], which gives up a
     * held grant dated beyond this allowance past it (#3221).
     */
    public data object NotYetValid : GrantVerdict

    /** Past its [Grant.notAfter]. */
    public data object Expired : GrantVerdict
}

/**
 * Decides whether a presented [Grant] lets a device back into this host's room now.
 *
 * A device that comes back shows the pass the host gave it. Before letting it skip vetting, the host
 * checks that the pass is its own ([hostKey] issued and signed it), that it names the device that is
 * presenting it, that it is for this room, that the device was not revoked since ([revocations]), that
 * it is not dated in the future, and that it has not run out. [verify] answers with the first check that fails, in that order, or
 * [GrantVerdict.Valid]:
 *
 * 1. [GrantVerdict.UntrustedIssuer] — [Grant.issuer] is not [hostKey].
 * 2. [GrantVerdict.BadSignature] — the signature is not [hostKey]'s over the grant's terms.
 * 3. [GrantVerdict.WrongSubject] — [Grant.subject] is not the presenting device's key.
 * 4. [GrantVerdict.WrongScope] — a [GrantScope.Room] for another room id.
 *    [GrantScope.AnyRoomOfIssuer] is good in every room [hostKey] hosts.
 * 5. [GrantVerdict.Revoked] — [Grant.issuedAt] is before the subject's [RevocationList.notBefore].
 * 6. [GrantVerdict.NotYetValid] — [Grant.issuedAt] is more than [CLOCK_SKEW_ALLOWANCE_MILLIS] past
 *    `now`. A grant dated up to the allowance ahead is accepted, so a clock nudged back a little
 *    between issuing and checking does not turn a good pass away.
 * 7. [GrantVerdict.Expired] — `now` is at or past [Grant.notAfter]. A grant with
 *    [Grant.NO_EXPIRY] never expires, not even at `now = Long.MAX_VALUE`.
 *
 * The last two are the two ends of the span a grant is good for, and no grant meets both: its
 * [Grant.notAfter] is always after its [Grant.issuedAt], so a grant dated after `now` has not run out.
 *
 * A future-dated grant is refused because of what it does to a revocation. A pass issued while the
 * host's clock ran a year ahead carries an [Grant.issuedAt] a year out. Once the clock is corrected,
 * `revoke(subject, now + 1)` sets a cutoff before that date and does not void the pass. Refusing it
 * as [GrantVerdict.NotYetValid] stops the device returning on it until the clock nears its date.
 * From then on it is good again, so a host that must void such a pass for good still picks a cutoff
 * past the latest [Grant.issuedAt] it gave the device.
 *
 * The only trusted issuer is the host's own key. So losing that key is the safe direction: every
 * grant the host ever issued becomes [GrantVerdict.UntrustedIssuer], and those devices are vetted
 * again. Losing the revocations is the dangerous one, since revoked devices come back on their old passes.
 *
 * [verify] reads no clock and keeps no state of its own: given the same grant, subject, room, time
 * and revocations it gives the same verdict. It reads [revocations] as they are at the call.
 *
 * @property hostKey The verifying host's own key: the one issuer this host trusts. A room builds this
 *   from its own device key, so the key that issues grants and the key that checks them cannot differ.
 * @property revocations The host's revocations, consulted on every [verify].
 * @param scheme The scheme the signature is checked in: the room's own [DeviceKeyStore.keyScheme].
 *   The two-argument constructor checks in [KeyScheme.P256].
 * @sample us.tractat.kuilt.identity.sampleGrantTrust
 */
public class GrantTrust @IdentityInternalApi constructor(
    public val hostKey: DidKey,
    public val revocations: RevocationList,
    private val scheme: KeyScheme,
) {
    /** A trust that checks signatures in [KeyScheme.P256]: ECDSA through the platform's provider. */
    public constructor(hostKey: DidKey, revocations: RevocationList) : this(hostKey, revocations, KeyScheme.P256)

    /**
     * The verdict on [grant], presented by the device whose key is [subject], to the room [roomId],
     * at [now] in epoch milliseconds. [subject] must be a key the presenter has *proved* it holds,
     * not one it merely claims: the grant names a key, and is worth nothing to anyone without it.
     *
     * Never throws on a bad grant: every defect is a verdict. It throws [IllegalStateException] only
     * when this platform's cryptographic provider cannot run at all.
     */
    public suspend fun verify(grant: Grant, subject: DidKey, roomId: String, now: Long): GrantVerdict = when {
        grant.issuer != hostKey -> GrantVerdict.UntrustedIssuer
        !grant.isSignedByIssuer(scheme) -> GrantVerdict.BadSignature
        grant.subject != subject -> GrantVerdict.WrongSubject
        !grant.scope.covers(roomId) -> GrantVerdict.WrongScope
        grant.isRevoked() -> GrantVerdict.Revoked
        grant.isPostDatedAt(now) -> GrantVerdict.NotYetValid
        grant.isExpiredAt(now) -> GrantVerdict.Expired
        else -> GrantVerdict.Valid
    }

    override fun toString(): String = "GrantTrust(hostKey=${hostKey.value})"

    /** Whether a grant in this scope, already known to be [hostKey]'s, is good in the room [roomId]. */
    private fun GrantScope.covers(roomId: String): Boolean = when (this) {
        is GrantScope.Room -> this.roomId == roomId
        GrantScope.AnyRoomOfIssuer -> true
    }

    private fun Grant.isRevoked(): Boolean {
        val cutoff = revocations.notBefore(subject) ?: return false
        return issuedAt < cutoff
    }

    /** Whether this grant is dated more than the allowance past [now]. Saturates rather than wraps. */
    private fun Grant.isPostDatedAt(now: Long): Boolean {
        val latestAccepted =
            if (now > Long.MAX_VALUE - CLOCK_SKEW_ALLOWANCE_MILLIS) Long.MAX_VALUE else now + CLOCK_SKEW_ALLOWANCE_MILLIS
        return issuedAt > latestAccepted
    }

    private fun Grant.isExpiredAt(now: Long): Boolean = notAfter != Grant.NO_EXPIRY && now >= notAfter

    public companion object {
        /**
         * How far past `now`, in milliseconds, a grant's [Grant.issuedAt] may lie and still be
         * accepted: five minutes. One millisecond further and [verify] answers
         * [GrantVerdict.NotYetValid].
         *
         * A host checks only grants it issued itself, so the one clock in play is its own, read
         * twice: when it issued the pass and when the pass comes back. Between the two a time sync
         * can set that clock back, usually by well under a second, and on a device left unsynced for
         * weeks by a minute or two. Five minutes covers that. A pass dated further ahead was issued
         * while the clock was badly wrong, and is refused until its date comes near.
         */
        public const val CLOCK_SKEW_ALLOWANCE_MILLIS: Long = 5 * 60 * 1000L
    }
}
