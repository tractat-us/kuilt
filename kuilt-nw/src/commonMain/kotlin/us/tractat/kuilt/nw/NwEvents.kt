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
 * [id] is the endpoint's **stable per-peer identity** — the remote's `PeerId`,
 * published in its Bonjour **TXT record** and read back at browse time (Option A,
 * #1502). It is the key the pre-dial self-filter and the redial coordinator key on.
 * **Backstop:** if a browsed endpoint carries no TXT PeerId (absent or malformed),
 * the runtime falls back to `id = serviceName` (the pre-Option-A behaviour); the
 * post-connect `NwSeam` self-connection guard, which resolves the `PeerId` from the
 * [NwHello] handshake, remains the correctness backstop for that case.
 *
 * [serviceName] is the advertised Bonjour instance name of the remote endpoint, and it
 * is the **dial target** — mDNS re-resolves it at connect time, so it must name exactly
 * one device. Since ADR-005 (#2416) `NwLoom` advertises its own `PeerId` here under
 * **both** rendezvous arms; it previously advertised the session name under
 * `Rendezvous.New`, shared by every peer, which is what let a dial armed for one peer
 * land on another. A peer running an older build may still advertise a shared label, and
 * mDNS conflict resolution may still rename either to `"… (2)"` — so this is a routing
 * key, never a stable identity. Read [id] for that.
 *
 * [identityResolved] says which of those two [id] actually is: `true` when it came from a
 * resolved TXT record (a real per-peer identity), `false` when it is the [serviceName]
 * backstop. Without it the two are the same `String` and a consumer cannot tell a peer's
 * identity from a placeholder — the #1709 race: Network.framework can deliver the browse
 * `add` BEFORE TXT resolves. With a per-peer instance name the backstop id equals the
 * `PeerId` that later resolves, so that window no longer hides a peer's identity behind a
 * label — but the flag still records the provenance, and still tells the two apart for an
 * advertiser whose name is not its id.
 */
public data class NwEndpoint(
    public val id: String,
    public val serviceName: String,
    public val identityResolved: Boolean = true,
) {
    /**
     * Equality is over ([id], [serviceName]) ONLY — [identityResolved] is provenance of a *sighting*,
     * not part of the endpoint's identity. The same endpoint is routinely re-sighted as its TXT record
     * resolves; folding the flag into equality would make those two sightings unequal, so a set-keyed
     * discovery roster ([NwLoom.visiblePeers]) would gain a ghost entry for one peer, and an
     * `endpointLost` carrying the other flag value would fail to prune the one that was added.
     * Same reason [NwBytesReceived] hand-writes its equality: the generated one would be wrong.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NwEndpoint) return false
        return id == other.id && serviceName == other.serviceName
    }

    override fun hashCode(): Int = 31 * id.hashCode() + serviceName.hashCode()
}

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
