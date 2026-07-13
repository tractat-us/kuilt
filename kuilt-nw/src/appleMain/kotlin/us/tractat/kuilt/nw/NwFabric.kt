package us.tractat.kuilt.nw

import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import kotlin.time.Duration

/**
 * Build an Apple [NwLoom] over Network.framework, deriving the link's TLS pre-shared key from an
 * out-of-band [roomKey] — the public way to construct the peer-to-peer fabric as a reusable [Loom]
 * (rather than a one-shot [Seam] via [nwHost]/[nwJoin]).
 *
 * This is the drop-in replacement for the Multipeer peer-link factory: hand the returned loom to
 * [NwRoomHost] to host a session, or to a `join` path to enter one, and read its
 * [NwLoom.visiblePeers] to drive a lobby view. The Multipeer→nw migration is then mechanical —
 * `MultipeerPeerLinkFactory(displayName, serviceType)` becomes `appleNwLoom(serviceType, roomKey)`,
 * and `MultipeerRoomHost` becomes [NwRoomHost] (#1427).
 *
 * The [roomKey] is a **bearer secret, not a label**: it is fed through HKDF-SHA256 to derive the
 * TLS-PSK that secures every link (it never leaves the device in cleartext), so only a peer holding
 * the same `roomKey` under the same [serviceType] can complete the handshake. Two co-located
 * sessions with different `roomKey`s therefore never merge even on the same [serviceType] — the
 * advertised Bonjour name is only a human-readable label; the `roomKey` is what gates who connects.
 * The PSK derivation ([NwPsk]) and the secured `NwApi` (`RealNwApi`) stay `internal`; this factory
 * is the one public seam that turns a `roomKey` string into a wired loom.
 *
 * @param serviceType the Bonjour service type both advertised and browsed (e.g. `"_kuilt._tcp"`).
 * @param roomKey     the out-of-band shared secret the TLS-PSK is derived from.
 * @param selfId      this peer's stable identity; defaults to a fresh random UUID per loom (#1405).
 * @param policy      inbound delivery policy for each woven [Seam] (default [DeliveryPolicy.Reliable]).
 * @param weaveTimeout how long [NwLoom.weave] waits for the first peer before failing
 *   (default [NwLoom.DEFAULT_WEAVE_TIMEOUT]).
 */
public fun appleNwLoom(
    serviceType: String,
    roomKey: String,
    selfId: PeerId = freshPeerId(),
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    weaveTimeout: Duration = NwLoom.DEFAULT_WEAVE_TIMEOUT,
): NwLoom = NwLoom(
    api = RealNwApi(NwPsk.derive(roomKey, serviceType)),
    serviceType = serviceType,
    selfId = selfId,
    policy = policy,
    weaveTimeout = weaveTimeout,
)

/**
 * Start hosting a peer-to-peer session over Apple's local radios, encrypted with a code you
 * share out of band.
 *
 * Call this on the device that opens the session. It advertises over Bonjour and connects to
 * nearby peers over Apple's peer-to-peer link (AWDL), returning a [Seam] that is already
 * connected to the first peer that joins. Everyone who wants in must be given the same
 * `roomKey` (the [Pattern.roomKey]) ahead of time — think of it as the session's password,
 * shared through a channel outside this fabric (a QR code, a spoken word, a chat message).
 *
 * Under the hood the `roomKey` is not sent over the air: it is fed through HKDF to derive the
 * TLS pre-shared key (via HKDF-SHA256) that secures every link. Two consequences follow:
 * - **The `roomKey` is a bearer secret, not a label.** It is therefore **required** on this
 *   fabric (a `null` [Pattern.roomKey] throws immediately, before any network is touched) —
 *   an "open", unencrypted session is not silently allowed.
 * - **Different `roomKey` ⇒ different session.** Because the key scopes the TLS handshake,
 *   two co-located sessions advertising the *same* [serviceType] can never merge — a peer
 *   holding a different `roomKey` derives a different PSK and its handshake simply fails. The
 *   advertised Bonjour [serviceType]/name is only a human-readable label; the `roomKey` is
 *   what actually gates who connects.
 *
 * @param pattern     the session to open; its [Pattern.roomKey] is the required bearer secret.
 * @param serviceType the Bonjour service type to advertise and browse (e.g. `"_kuilt._tcp"`).
 * @throws IllegalArgumentException if [Pattern.roomKey] is `null`.
 */
public suspend fun nwHost(pattern: Pattern, serviceType: String): Seam {
    val secret = requireNotNull(pattern.roomKey) {
        "nwHost requires a non-null Pattern.roomKey: on the Apple Network.framework fabric the " +
            "roomKey is the bearer secret the TLS pre-shared key is derived from — an open, " +
            "unencrypted session is not allowed. Set Pattern.roomKey to the code you share " +
            "out of band with joiners."
    }
    return appleNwLoom(serviceType, secret).host(pattern)
}

/**
 * Join a peer-to-peer session over Apple's local radios, encrypted with a code you were given
 * out of band.
 *
 * Call this on a device that wants to enter a session someone else is hosting. It browses over
 * Bonjour and connects to the host over Apple's peer-to-peer link (AWDL), returning a [Seam]
 * that is already connected. You must supply the same `roomKey` (the [Tag.roomKey]) the host
 * used — the session's shared password, delivered through a channel outside this fabric.
 *
 * Under the hood the `roomKey` is not sent over the air: it is fed through HKDF to derive the
 * TLS pre-shared key (via HKDF-SHA256) that secures the link. Two consequences follow:
 * - **The `roomKey` is a bearer secret, not a label.** It is therefore **required** on this
 *   fabric (a `null` [Tag.roomKey] throws immediately, before any network is touched) — you
 *   cannot join an "open", unencrypted session because there is no such thing here.
 * - **Different `roomKey` ⇒ different session.** Because the key scopes the TLS handshake, a
 *   tag whose `roomKey` differs from the host's cannot merge into the host's session even when
 *   both use the same [serviceType]: the derived PSK differs and the handshake fails. The
 *   advertised Bonjour [serviceType]/name is only a human-readable label; the `roomKey` is
 *   what actually gates who connects.
 *
 * @param tag         the session to join; its [Tag.roomKey] is the required bearer secret.
 * @param serviceType the Bonjour service type to browse (e.g. `"_kuilt._tcp"`).
 * @throws IllegalArgumentException if [Tag.roomKey] is `null`.
 */
public suspend fun nwJoin(tag: Tag, serviceType: String): Seam {
    val secret = requireNotNull(tag.roomKey) {
        "nwJoin requires a non-null Tag.roomKey: on the Apple Network.framework fabric the " +
            "roomKey is the bearer secret the TLS pre-shared key is derived from — you cannot " +
            "join an open, unencrypted session. Set Tag.roomKey to the code the host shared " +
            "with you out of band."
    }
    return appleNwLoom(serviceType, secret).join(tag)
}
