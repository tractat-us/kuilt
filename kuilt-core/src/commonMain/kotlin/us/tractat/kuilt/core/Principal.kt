package us.tractat.kuilt.core

import us.tractat.kuilt.core.fabric.Connection
import kotlin.jvm.JvmInline

/**
 * An opaque, caller-verified identity for the peer on a connection — e.g. an
 * authenticated device id or user id the host established out of band (token, TLS
 * client cert, signed header) **before** admitting the peer.
 *
 * Distinct from any *self-asserted* identity a joiner announces in its handshake
 * preamble (a `Hello` / `MeshHello` [PeerId]). A [Principal] is what the host vouches
 * for; kuilt treats the wrapped [value] as opaque and never parses or transmits it on
 * the wire.
 */
@JvmInline
public value class Principal(public val value: String)

/**
 * A seam or connection whose transport accept carries a host-verified [Principal].
 * A fabric that can authenticate a connection (e.g. a Ktor server reading
 * `call.principal()`) attaches one via a `withPrincipal` wrapper; admission layers
 * read it at admit time and carry it onto the admitted peer.
 *
 * This replaces out-of-band `peer → principal` maps: the principal rides the
 * connection object itself, so it cannot desync from the peer it describes.
 */
public interface PrincipalAttested {
    /** The verified principal for this connection, or `null` if unauthenticated. */
    public val principal: Principal?
}

/**
 * Returns a [Connection] that reports [principal] via [PrincipalAttested]. When
 * [principal] is `null` the receiver is returned unchanged — an unauthenticated
 * connection carries no attestation and is never wrapped.
 *
 * The hub-accept counterpart of the relay path's `Seam.withPrincipal`: a
 * [us.tractat.kuilt.core.fabric.ConnectionSource] accept handler attaches the
 * principal it verified, and the mesh admission layer reads it back before the
 * link is published.
 */
public fun Connection.withPrincipal(principal: Principal?): Connection =
    if (principal == null) this else PrincipalConnection(this, principal)

private class PrincipalConnection(
    inner: Connection,
    override val principal: Principal?,
) : Connection by inner, PrincipalAttested
