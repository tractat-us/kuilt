package us.tractat.kuilt.session

import us.tractat.kuilt.core.Seam

/**
 * Moved to `kuilt-core` (`us.tractat.kuilt.core.Principal`) so fabric-level admission
 * (`LinkAdmission` on the hosted-hub path) can consume it without depending on
 * `kuilt-session`. This alias keeps existing `kuilt-session` consumers source-compatible.
 */
public typealias Principal = us.tractat.kuilt.core.Principal

/**
 * Moved to `kuilt-core` (`us.tractat.kuilt.core.PrincipalAttested`). This alias keeps
 * existing `kuilt-session` consumers source-compatible. [SeamRoom] reads the attested
 * principal at admit time and carries it onto the admitted [Member].
 */
public typealias PrincipalAttested = us.tractat.kuilt.core.PrincipalAttested

/**
 * Returns a [Seam] that reports [principal] via [PrincipalAttested]. When [principal]
 * is `null` the receiver is returned unchanged — an unauthenticated connection carries
 * no attestation and is never wrapped.
 */
public fun Seam.withPrincipal(principal: Principal?): Seam =
    if (principal == null) this else PrincipalSeam(this, principal)

private class PrincipalSeam(
    inner: Seam,
    override val principal: Principal?,
) : Seam by inner, PrincipalAttested
