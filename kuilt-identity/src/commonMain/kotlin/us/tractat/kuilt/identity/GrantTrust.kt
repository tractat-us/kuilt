package us.tractat.kuilt.identity

/** Skeleton: the verdicts land with the tests that pin them. */
public sealed interface GrantVerdict {
    public data object Valid : GrantVerdict
    public data object UntrustedIssuer : GrantVerdict
    public data object BadSignature : GrantVerdict
    public data object WrongSubject : GrantVerdict
    public data object WrongScope : GrantVerdict
    public data object Revoked : GrantVerdict
    public data object Expired : GrantVerdict
}

/** Skeleton. */
public class GrantTrust(
    public val hostKey: DidKey,
    public val revocations: RevocationList,
) {
    public suspend fun verify(grant: Grant, subject: DidKey, roomId: String, now: Long): GrantVerdict = TODO()
}
