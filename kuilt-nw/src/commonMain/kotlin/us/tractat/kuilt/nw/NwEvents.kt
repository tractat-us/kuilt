package us.tractat.kuilt.nw

import kotlin.jvm.JvmInline

/**
 * A locally-scoped identifier for one connection managed by [NwApi] — either an
 * inbound connection accepted while listening, or an outbound connection dialled
 * via [NwApi.connect]. Opaque and only meaningful to the [NwApi] instance that
 * produced it.
 */
@JvmInline
public value class NwConnectionId(public val value: String)

/**
 * A remote endpoint discovered while browsing.
 *
 * [id] is the browse-time opaque endpoint identifier assigned by the runtime —
 * it is NOT a stable cross-session peer identity. It only identifies the
 * endpoint for the duration of this browse/dial. Stable identity (`PeerId`) is
 * exchanged as the first framed message during the connection handshake (see
 * the transport plan's Task 2.4), mirroring `NearbyApi`'s `endpointId` caveat.
 *
 * [serviceName] is the advertised Bonjour instance name of the remote endpoint.
 */
public data class NwEndpoint(
    public val id: String,
    public val serviceName: String,
)

/**
 * A connection was established on [connectionId].
 *
 * [endpoint] is the endpoint that was dialled when this connection was opened
 * via [NwApi.connect] (join role). It is `null` for a connection accepted while
 * listening (host role) — an inbound connection has no dialled endpoint.
 */
public data class NwConnectionOpened(
    public val connectionId: NwConnectionId,
    public val endpoint: NwEndpoint?,
)

/** A chunk of [bytes] arrived on [connectionId]. Framing is above this layer. */
public data class NwBytesReceived(
    public val connectionId: NwConnectionId,
    public val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NwBytesReceived) return false
        return connectionId == other.connectionId && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = connectionId.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * The connection [connectionId] closed.
 *
 * [reason] is a nullable raw transport-level description of why the connection
 * closed (or `null` if unknown/graceful). This is NOT the `Seam`-level
 * `CloseReason` — mapping a raw transport close to the `Seam` contract's close
 * semantics is the concern of the layer above (`NwSeam`), which keeps this
 * interface thin and transport-level.
 */
public data class NwConnectionClosed(
    public val connectionId: NwConnectionId,
    public val reason: String? = null,
)

/**
 * The path viability of an already-established connection [connectionId] changed.
 *
 * Network.framework moves a connection that loses its route from `ready` to
 * `waiting` (NOT `failed`), so no [NwConnectionClosed] ever fires — the peer is
 * silently unreachable. This event exposes that transition:
 *
 *  - [viable]` == false` — a `ready → waiting` transition on a connection that HAD been
 *    ready (path lost). An initial-dial `preparing → waiting` is normal churn and is NOT
 *    reported.
 *  - [viable]` == true` — a `waiting → ready` recovery of a connection that had been ready.
 *
 * `NwSeam` uses this to arm a bounded grace timer on a path loss and tear the peer if the
 * path does not recover in time — the transport-level fix for #1478 (a path-lost `waiting`
 * connection that would otherwise keep a dead peer in the roster forever).
 */
public data class NwConnectionViability(
    public val connectionId: NwConnectionId,
    public val viable: Boolean,
)
