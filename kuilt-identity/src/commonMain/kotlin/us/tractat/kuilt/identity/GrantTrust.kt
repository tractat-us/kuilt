package us.tractat.kuilt.identity

/**
 * What a host makes of a [Grant] a device presents: good here and now, or the first reason it is not.
 *
 * Only [Valid] lets the device back in without being vetted. Every other verdict means the device is
 * treated as a newcomer, and which one tells the host why.
 */
public sealed interface GrantVerdict {
    /** Issued by this host, signed by it, to this device, for this room, not revoked, not expired. */
    public data object Valid : GrantVerdict

    /**
     * Issued by some other key than this host's. A grant is good only with the host that issued it:
     * after a host change, a returning device's old pass is this, and the device is admitted afresh.
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

    /** Past its [Grant.notAfter]. */
    public data object Expired : GrantVerdict
}

/**
 * Decides whether a presented [Grant] lets a device back into this host's room now.
 *
 * A device that comes back shows the pass the host gave it. Before letting it skip vetting, the host
 * checks that the pass is its own ([hostKey] issued and signed it), that it names the device that is
 * presenting it, that it is for this room, that the device was not revoked since ([revocations]), and
 * that it has not run out. [verify] answers with the first check that fails, in that order, or
 * [GrantVerdict.Valid]:
 *
 * 1. [GrantVerdict.UntrustedIssuer] — [Grant.issuer] is not [hostKey].
 * 2. [GrantVerdict.BadSignature] — the signature is not [hostKey]'s over the grant's terms.
 * 3. [GrantVerdict.WrongSubject] — [Grant.subject] is not the presenting device's key.
 * 4. [GrantVerdict.WrongScope] — a [GrantScope.Room] for another room id.
 *    [GrantScope.AnyRoomOfIssuer] is good in every room [hostKey] hosts.
 * 5. [GrantVerdict.Revoked] — [Grant.issuedAt] is before the subject's [RevocationList.notBefore].
 * 6. [GrantVerdict.Expired] — `now` is at or past [Grant.notAfter]. A grant with
 *    [Grant.NO_EXPIRY] never expires, not even at `now = Long.MAX_VALUE`.
 *
 * The only trusted issuer is the host's own key. So losing that key is the safe direction: every
 * grant the host ever issued becomes [GrantVerdict.UntrustedIssuer], and those devices are vetted
 * again. Losing the revocations is the dangerous one, since revoked devices come back.
 *
 * [verify] reads no clock and keeps no state of its own: given the same grant, subject, room, time
 * and revocations it gives the same verdict. It reads [revocations] as they are at the call.
 *
 * @property hostKey The verifying host's own key: the one issuer this host trusts. A room builds this
 *   from its own device key, so the key that issues grants and the key that checks them cannot differ.
 * @property revocations The host's revocations, consulted on every [verify].
 * @sample us.tractat.kuilt.identity.sampleGrantTrust
 */
public class GrantTrust(
    public val hostKey: DidKey,
    public val revocations: RevocationList,
) {
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
        !grant.isSignedByIssuer() -> GrantVerdict.BadSignature
        grant.subject != subject -> GrantVerdict.WrongSubject
        !grant.scope.covers(roomId) -> GrantVerdict.WrongScope
        grant.isRevoked() -> GrantVerdict.Revoked
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

    private fun Grant.isExpiredAt(now: Long): Boolean = notAfter != Grant.NO_EXPIRY && now >= notAfter
}
