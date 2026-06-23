package us.tractat.kuilt.session

import us.tractat.kuilt.core.Seam
import kotlin.jvm.JvmInline

/**
 * An opaque, caller-verified identity for the peer on a connection — e.g. an
 * authenticated device id or user id the host established out of band (token, TLS
 * client cert, signed header) **before** admitting the peer.
 *
 * Distinct from [MemberIdentity], which is *self-asserted* by the joiner in its
 * `Hello`. A [Principal] is what the host vouches for; kuilt treats the wrapped
 * [value] as opaque and never parses or transmits it on the wire.
 */
@JvmInline
public value class Principal(public val value: String)

/**
 * A [Seam] whose connection carries a host-verified [Principal]. A fabric that can
 * authenticate a connection (e.g. a Ktor server reading `call.principal()`) attaches
 * one via [withPrincipal]; [SeamRoom] reads it at admit time and carries it onto the
 * admitted [Member].
 *
 * This replaces out-of-band `peer → principal` maps: the principal rides the
 * connection object itself, so it cannot desync from the peer it describes.
 */
public interface PrincipalAttested {
    /** The verified principal for this connection, or `null` if unauthenticated. */
    public val principal: Principal?
}

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
