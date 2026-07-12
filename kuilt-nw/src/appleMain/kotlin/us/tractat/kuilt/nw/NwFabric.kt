package us.tractat.kuilt.nw

import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag

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
    return NwLoom(RealNwApi(NwPsk.derive(secret, serviceType)), serviceType).host(pattern)
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
    return NwLoom(RealNwApi(NwPsk.derive(secret, serviceType)), serviceType).join(tag)
}
